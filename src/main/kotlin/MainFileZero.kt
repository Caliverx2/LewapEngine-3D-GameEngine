package org.lewapnoob.FileZero

import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.paint.Color
import javafx.stage.Stage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import java.util.PriorityQueue

data class Vector3d(var x: Double, var y: Double, var z: Double) {
    operator fun plus(other: Vector3d) = Vector3d(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3d) = Vector3d(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Double) = Vector3d(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Double) = Vector3d(x / scalar, y / scalar, z / scalar)

    fun lerp(other: Vector3d, alpha: Double): Vector3d {
        return this * (1.0 - alpha) + other * alpha
    }
    fun dot(other: Vector3d): Double = x * other.x + y * other.y + z * other.z
    fun cross(other: Vector3d): Vector3d = Vector3d(
        this.y * other.z - this.z * other.y,
        this.z * other.x - this.x * other.z,
        this.x * other.y - this.y * other.x
    )
    fun normalize(): Vector3d {
        val length = length()
        return if (length != 0.0) Vector3d(x / length, y / length, z / length) else Vector3d(0.0, 0.0, 0.0)
    }
    fun length(): Double = sqrt(x * x + y * y + z * z)

    fun distanceSquared(other: Vector3d): Double {
        val dx = this.x - other.x
        val dy = this.y - other.y
        val dz = this.z - other.z
        return dx * dx + dy * dy + dz * dz
    }
}

data class Vector4d(val x: Double, val y: Double, val z: Double, val w: Double)

fun Double.isCloseToZero(epsilon: Double = 1e-9): Boolean = abs(this) < epsilon

class Matrix4x4(private val data: Array<DoubleArray>) {
    init {
        require(data.size == 4 && data.all { it.size == 4 }) { "Macierz musi być 4x4" }
    }
    companion object {
        private val IDENTITY_DATA = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        )
        fun identity(): Matrix4x4 = Matrix4x4(IDENTITY_DATA)

        fun rotationX(angleRad: Double): Matrix4x4 {
            val cos = cos(angleRad)
            val sin = sin(angleRad)
            return Matrix4x4(arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, cos, -sin, 0.0),
                doubleArrayOf(0.0, sin, cos, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 1.0)
            ))
        }

        fun rotationY(angleRad: Double): Matrix4x4 {
            val cos = cos(angleRad)
            val sin = sin(angleRad)
            return Matrix4x4(arrayOf(
                doubleArrayOf(cos, 0.0, sin, 0.0),
                doubleArrayOf(0.0, 1.0, 0.0, 0.0),
                doubleArrayOf(-sin, 0.0, cos, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 1.0)
            ))
        }

        fun rotationZ(angleRad: Double): Matrix4x4 {
            val cos = cos(angleRad)
            val sin = sin(angleRad)
            return Matrix4x4(arrayOf(
                doubleArrayOf(cos, -sin, 0.0, 0.0),
                doubleArrayOf(sin, cos, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 1.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 1.0)
            ))
        }

        fun translation(x: Double, y: Double, z: Double): Matrix4x4 {
            return Matrix4x4(arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0, x),
                doubleArrayOf(0.0, 1.0, 0.0, y),
                doubleArrayOf(0.0, 0.0, 1.0, z),
                doubleArrayOf(0.0, 0.0, 0.0, 1.0)
            ))
        }

        fun scale(x: Double, y: Double, z: Double): Matrix4x4 {
            return Matrix4x4(arrayOf(
                doubleArrayOf(x, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, y, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, z, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 1.0)
            ))
        }

        fun lookAt(eye: Vector3d, target: Vector3d, up: Vector3d): Matrix4x4 {
            val zAxis = (eye - target).normalize()
            val xAxis = up.cross(zAxis).normalize()
            val yAxis = zAxis.cross(xAxis)

            return Matrix4x4(arrayOf(
                doubleArrayOf(xAxis.x, xAxis.y, xAxis.z, -xAxis.dot(eye)),
                doubleArrayOf(yAxis.x, yAxis.y, yAxis.z, -yAxis.dot(eye)),
                doubleArrayOf(zAxis.x, zAxis.y, zAxis.z, -zAxis.dot(eye)),
                doubleArrayOf(0.0, 0.0, 0.0, 1.0)
            ))
        }

        fun perspective(fovY: Double, aspectRatio: Double, near: Double, far: Double): Matrix4x4 {
            val tanHalfFovY = tan(Math.toRadians(fovY / 2.0))
            val f = 1.0 / tanHalfFovY
            val depthRange = near - far

            return Matrix4x4(arrayOf(
                doubleArrayOf(f / aspectRatio, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, f, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, (far + near) / depthRange, (2.0 * far * near) / depthRange),
                doubleArrayOf(0.0, 0.0, -1.0, 0.0)
            ))
        }
    }

    operator fun times(other: Matrix4x4): Matrix4x4 {
        val resultData = Array(4) { DoubleArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += this.data[i][k] * other.data[k][j]
                }
                resultData[i][j] = sum
            }
        }
        return Matrix4x4(resultData)
    }

    fun transformHomogeneous(vector: Vector3d): Vector4d {
        val x = vector.x * data[0][0] + vector.y * data[0][1] + vector.z * data[0][2] + data[0][3]
        val y = vector.x * data[1][0] + vector.y * data[1][1] + vector.z * data[1][2] + data[1][3]
        val z = vector.x * data[2][0] + vector.y * data[2][1] + vector.z * data[2][2] + data[2][3]
        val w = vector.x * data[3][0] + vector.y * data[3][1] + vector.z * data[3][2] + data[3][3]
        return Vector4d(x, y, z, w)
    }

    fun transform(vector: Vector3d): Vector3d {
        val x = vector.x * data[0][0] + vector.y * data[0][1] + vector.z * data[0][2] + data[0][3]
        val y = vector.x * data[1][0] + vector.y * data[1][1] + vector.z * data[1][2] + data[1][3]
        val z = vector.x * data[2][0] + vector.y * data[2][1] + vector.z * data[2][2] + data[2][3]
        val w = vector.x * data[3][0] + vector.y * data[3][1] + vector.z * data[3][2] + data[3][3]

        return if (w.isCloseToZero()) Vector3d(x, y, z) else Vector3d(x / w, y / w, z / w)
    }
}

data class RenderableFace(
    val screenVertices: List<Vector3d>,
    val originalClipW: List<Double>,
    val textureVertices: List<Vector3d>,
    val color: Color,
    val isTransparent: Boolean,
    val texture: Image? = null,
    val worldVertices: List<Vector3d> = emptyList(),
    val lightGrid: Array<Array<Color>>? = null,
    val blushes: List<AABB> = emptyList(),
    val blushContainerAABB: AABB? = null,
    val giGrid: Array<Array<Color>>? = null
) {
    var hasCollision: Boolean = true
}

data class Mesh(
    val vertices: List<Vector3d>,
    val faces: List<List<Int>>,
    val faceUVs: List<List<Vector3d>>,
    val color: Color,
    val faceTextureNames: Map<Int, String> = emptyMap(),
    val blushes: List<AABB> = emptyList(),
    val customTextures: Map<Int, List<Int>> = emptyMap(),
) {
    val gravityZoneFaces: Set<Int>

    init {
        val mutableGravityZoneFaces = mutableSetOf<Int>()
        val minSlopeNormalY = 0.01 // Próg do odróżnienia od ścian pionowych
        val maxSlopeNormalY = 0.99 // Próg do odróżnienia od podłóg

        faces.forEachIndexed { index, faceIndices ->
            if (faceIndices.size >= 3) {
                val v0 = vertices[faceIndices[0]]
                val v1 = vertices[faceIndices[1]]
                val v2 = vertices[faceIndices[2]]

                val normal = (v1 - v0).cross(v2 - v0).normalize()
                val normalY = abs(normal.y)

                if (normalY > minSlopeNormalY && normalY < maxSlopeNormalY) {
                    mutableGravityZoneFaces.add(index)
                }
            }
        }
        gravityZoneFaces = mutableGravityZoneFaces
    }
}

class PlacedMesh(
    val mesh: Mesh,
    var transformMatrix: Matrix4x4 = Matrix4x4.identity(),
    val texture: Image? = null,
    val faceTextures: Map<Int, Image> = emptyMap(),
    val collision: Boolean = true,
    var collisionPos: Vector3d = Vector3d(0.0, 0.0, 0.0),
    val alwaysRender: Boolean = false
) {
    fun getTransformedVertices(): List<Vector3d> =
        mesh.vertices.map { transformMatrix.transform(it) }

    fun applyTransform(matrix: Matrix4x4) {
        transformMatrix = matrix * transformMatrix
    }

    fun applyRotationY(angleRad: Double) {
        val center = getTransformedVertices().reduce { acc, v -> acc + v } / mesh.vertices.size.toDouble()
        val toOrigin = Matrix4x4.translation(-center.x, -center.y, -center.z)
        val rotation = Matrix4x4.rotationY(angleRad)
        val fromOrigin = Matrix4x4.translation(center.x, center.y, center.z)

        val rotationMatrix = fromOrigin * rotation * toOrigin
        transformMatrix = rotationMatrix * transformMatrix
    }
}

data class AABB(val min: Vector3d, val max: Vector3d) {
    companion object {
        fun fromCube(cubeVertices: List<Vector3d>): AABB {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var minZ = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var maxZ = Double.NEGATIVE_INFINITY

            for (v in cubeVertices) {
                minX = minOf(minX, v.x)
                minY = minOf(minY, v.y)
                minZ = minOf(minZ, v.z)
                maxX = maxOf(maxX, v.x)
                maxY = maxOf(maxY, v.y)
                maxZ = maxOf(maxZ, v.z)
            }
            return AABB(Vector3d(minX, minY, minZ), Vector3d(maxX, maxY, maxZ))
        }

        fun fromAABBs(aabbs: List<AABB>): AABB {
            if (aabbs.isEmpty()) return AABB(Vector3d(0.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
            var min = aabbs.first().min.copy()
            var max = aabbs.first().max.copy()
            aabbs.forEach {
                min = Vector3d(min(min.x, it.min.x), min(min.y, it.min.y), min(min.z, it.min.z))
                max = Vector3d(max(max.x, it.max.x), max(max.y, it.max.y), max(max.z, it.max.z))
            }
            return AABB(min, max)
        }
    }

    fun getCorners(): List<Vector3d> {
        return listOf(
            Vector3d(min.x, min.y, min.z),
            Vector3d(max.x, min.y, min.z),
            Vector3d(min.x, max.y, min.z),
            Vector3d(max.x, max.y, min.z),
            Vector3d(min.x, min.y, max.z),
            Vector3d(max.x, min.y, max.z),
            Vector3d(min.x, max.y, max.z),
            Vector3d(max.x, max.y, max.z)
        )
    }

    fun contains(p: Vector3d): Boolean {
        return p.x >= min.x && p.x <= max.x &&
                p.y >= min.y && p.y <= max.y &&
                p.z >= min.z && p.z <= max.z
    }

    fun intersects(other: AABB): Boolean {
        return (this.min.x <= other.max.x && this.max.x >= other.min.x) &&
                (this.min.y <= other.max.y && this.max.y >= other.min.y) &&
                (this.min.z <= other.max.z && this.max.z >= other.min.z)
    }

    fun union(other: AABB): AABB {
        val newMin = Vector3d(
            minOf(this.min.x, other.min.x),
            minOf(this.min.y, other.min.y),
            minOf(this.min.z, other.min.z)
        )
        val newMax = Vector3d(
            maxOf(this.max.x, other.max.x),
            maxOf(this.max.y, other.max.y),
            maxOf(this.max.z, other.max.z)
        )
        return AABB(newMin, newMax)
    }

    fun union(point: Vector3d): AABB {
        val newMin = Vector3d(minOf(this.min.x, point.x), minOf(this.min.y, point.y), minOf(this.min.z, point.z))
        val newMax = Vector3d(maxOf(this.max.x, point.x), maxOf(this.max.y, point.y), maxOf(this.max.z, point.z))
        return AABB(newMin, newMax)
    }
}

class SpatialGrid<T>(private val cellSize: Double) {
    private val grid = mutableMapOf<Triple<Int, Int, Int>, MutableList<T>>()

    private fun getGridCoords(position: Vector3d): Triple<Int, Int, Int> {
        val x = floor(position.x / cellSize).toInt()
        val y = floor(position.y / cellSize).toInt()
        val z = floor(position.z / cellSize).toInt()
        return Triple(x, y, z)
    }

    fun add(item: T, aabb: AABB) {
        val minCoords = getGridCoords(aabb.min)
        val maxCoords = getGridCoords(aabb.max)

        for (x in minCoords.first..maxCoords.first) {
            for (y in minCoords.second..maxCoords.second) {
                for (z in minCoords.third..maxCoords.third) {
                    val key = Triple(x, y, z)
                    grid.computeIfAbsent(key) { mutableListOf() }.add(item)
                }
            }
        }
    }

    fun query(aabb: AABB): Set<T> {
        val items = mutableSetOf<T>()
        val minCoords = getGridCoords(aabb.min)
        val maxCoords = getGridCoords(aabb.max)

        for (x in minCoords.first..maxCoords.first) {
            for (y in minCoords.second..maxCoords.second) {
                for (z in minCoords.third..maxCoords.third) {
                    grid[Triple(x, y, z)]?.let { items.addAll(it) }
                }
            }
        }
        return items
    }

    fun clear() {
        grid.clear()
    }
}

class NavMeshNode(val position: Vector3d, val neighbors: MutableList<NavMeshNode> = mutableListOf()) {
    // Ręczne zaimplementowanie equals i hashCode, aby uniknąć StackOverflowError
    // z powodu rekurencyjnego wywoływania na sąsiadach w data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NavMeshNode
        return position == other.position
    }

    override fun hashCode(): Int {
        return position.hashCode()
    }
}

class NavMesh(
    val nodes: MutableList<NavMeshNode>,
    private val nodeGrid: MutableMap<Triple<Int, Int, Int>, NavMeshNode>,
    private val cellSize: Double
) {
    fun findClosestNode(position: Vector3d): NavMeshNode? {
        val gridX = floor(position.x / cellSize).toInt()
        val gridY = floor(position.y / cellSize).toInt()
        val gridZ = floor(position.z / cellSize).toInt()

        // Proste wyszukiwanie w najbliższym otoczeniu, jeśli dokładny klucz nie istnieje
        return nodeGrid[Triple(gridX, gridY, gridZ)] ?: nodes.minByOrNull { it.position.distanceSquared(position) }
    }

    fun addAndConnectNodes(newNodes: List<NavMeshNode>, newGridEntries: Map<Triple<Int, Int, Int>, NavMeshNode>) {
        nodes.addAll(newNodes)
        nodeGrid.putAll(newGridEntries)

        // Połącz nowe węzły ze sobą i z istniejącymi
        for (node in newNodes) {
            for (otherNode in nodes) { // Iteruj po wszystkich węzłach (starych i nowych)
                if (node !== otherNode && node.position.distanceSquared(otherNode.position) < (cellSize * 2.1).pow(2)) {
                    // Sprawdź, czy różnica wysokości jest akceptowalna (np. mniejsza niż wysokość kroku)
                    val heightDifference = abs(node.position.y - otherNode.position.y)
                    if (heightDifference < cellSize * 1.5) { // Pozwala na wchodzenie na "schody" o wysokości cellSize
                        if (!node.neighbors.contains(otherNode)) node.neighbors.add(otherNode)
                        if (!otherNode.neighbors.contains(node)) otherNode.neighbors.add(node)
                    }
                }
            }
        }
    }

    fun removeNodesInAABB(aabb: AABB) {
        val nodesToRemove = nodes.filter { aabb.contains(it.position) }
        if (nodesToRemove.isEmpty()) return

        println("Removing ${nodesToRemove.size} NavMesh nodes in AABB...")
        nodes.removeAll(nodesToRemove)

        // Usuń usunięte węzły z siatki i z list sąsiadów pozostałych węzłów
        val nodesToRemoveSet = nodesToRemove.toSet()
        nodeGrid.entries.removeIf { it.value in nodesToRemoveSet }

        // Użyj iteratora, aby bezpiecznie usuwać podczas iteracji
        nodes.forEach { node ->
            val iterator = node.neighbors.iterator()
            while (iterator.hasNext()) {
                if (iterator.next() in nodesToRemoveSet) {
                    iterator.remove()
                }
            }
        }
    }

    fun countNodesInRadius(center: Vector3d, radius: Double): Int {
        val radiusSq = radius * radius
        return nodes.count { it.position.distanceSquared(center) <= radiusSq }
    }

    fun findPath(startNode: NavMeshNode, endNode: NavMeshNode, dynamicObstacles: List<Vector3d> = emptyList(), obstacleRadius: Double = 0.0): List<Vector3d>? {
        val gScore = mutableMapOf<NavMeshNode, Double>().withDefault { Double.POSITIVE_INFINITY }
        gScore[startNode] = 0.0

        val fScore = mutableMapOf<NavMeshNode, Double>().withDefault { Double.POSITIVE_INFINITY }
        fScore[startNode] = startNode.position.distanceSquared(endNode.position)

        val openSet = PriorityQueue<NavMeshNode> { n1, n2 ->
            fScore.getValue(n1).compareTo(fScore.getValue(n2))
        }
        openSet.add(startNode)
        val cameFrom = mutableMapOf<NavMeshNode, NavMeshNode>()
        val processed = mutableSetOf<NavMeshNode>()

        while (openSet.isNotEmpty()) {
            val current = openSet.poll()

            if (current == endNode) {
                val path = mutableListOf<Vector3d>()
                var temp = current
                while (temp in cameFrom) {
                    path.add(temp.position)
                    temp = cameFrom.getValue(temp)
                }
                path.add(startNode.position)
                return path.reversed()
            }

            if (!processed.add(current)) continue

            for (neighbor in current.neighbors) {
                // Sprawdź, czy sąsiad nie jest blokowany przez dynamiczną przeszkodę
                val obstacleRadiusSq = obstacleRadius * obstacleRadius
                if (dynamicObstacles.any { it.distanceSquared(neighbor.position) < obstacleRadiusSq }) {
                    continue // Pomiń tego sąsiada, jest zablokowany
                }

                val tentativeGScore = gScore.getValue(current) + current.position.distanceSquared(neighbor.position)
                if (tentativeGScore < gScore.getValue(neighbor)) {
                    cameFrom[neighbor] = current
                    gScore[neighbor] = tentativeGScore
                    fScore[neighbor] = tentativeGScore + neighbor.position.distanceSquared(endNode.position)
                    openSet.add(neighbor)
                }
            }
        }
        return null // No path found
    }
}

val GRID_1 = arrayOf(
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1)
)
val GRID_2 = arrayOf(
    intArrayOf(1, 1, 0, 1, 2, 1, 0, 1, 1),
    intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1),
    intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
    intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1),
    intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1),
    intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1),
    intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
    intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1),
    intArrayOf(1, 1, 0, 1, 3, 1, 0, 1, 1)
)
val GRID_3 = arrayOf(
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
    intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1),
    intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1),
    intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1),
    intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1),
    intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1),
    intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1),
    intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1),
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1)
)
val GRID_4 = arrayOf(
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1),
    intArrayOf(1, 2, 2, 2, 2, 2, 2, 2, 1),
    intArrayOf(1, 2, 0, 0, 0, 0, 0, 2, 1),
    intArrayOf(1, 2, 0, 0, 0, 0, 0, 2, 1),
    intArrayOf(1, 2, 0, 0, 3, 0, 0, 2, 1),
    intArrayOf(1, 2, 0, 0, 0, 0, 0, 2, 1),
    intArrayOf(1, 2, 0, 0, 0, 0, 0, 2, 1),
    intArrayOf(1, 2, 2, 2, 2, 2, 2, 2, 1),
    intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1)
)

val GRID_MAP: Array<Array<Array<Int>>> = Array(18) { Array(18) { Array(18) { 0 } } }

enum class LightType { RAYTRACED, VERTEX, RAYTRACED_GI }

class LightSource(
    var position: Vector3d,
    val radius: Double,
    val color: Color,
    var intensity: Double = 1.0,
    val type: LightType = LightType.RAYTRACED
) {
    var lastKnownMeshStateHash: Int = 0
    var needsUpdate: Boolean = true
}

class MainApp : Application() {
    override fun start(primaryStage: Stage) {
        primaryStage.title = "FileZero.kt"
        val panel = DrawingPanel()
        val scene = Scene(panel, 1920.0/2, 1080.0/2)

        scene.setOnKeyPressed { event ->
            panel.pressedKeys.add(event.code)
        }
        scene.setOnKeyReleased { event ->
            panel.handleKeyRelease(event.code)
            panel.pressedKeys.remove(event.code)
        }

        primaryStage.scene = scene
        primaryStage.icons.add(Image(DrawingPanel::class.java.classLoader.getResourceAsStream("icons/LewapEngine.png")))
        primaryStage.show()
    }
}

fun main(args: Array<String>) {
    Application.launch(MainApp::class.java, *args)
}