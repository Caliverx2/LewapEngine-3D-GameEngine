package org.lewapnoob.LewapEngine

import kotlin.math.max
import kotlin.math.min

data class TrianglePrimitive(
        val v0: Vector3d,
        val v1: Vector3d,
        val v2: Vector3d,
        val mesh: PlacedMesh,
        val faceIndex: Int
) {
    var barycentricCoords: Vector3d = Vector3d(0.0, 0.0, 0.0) // u, v, w
    val center: Vector3d = (v0 + v1 + v2) / 3.0
    val bounds: AABB by lazy {
        val minX = min(v0.x, min(v1.x, v2.x))
        val minY = min(v0.y, min(v1.y, v2.y))
        val minZ = min(v0.z, min(v1.z, v2.z))
        val maxX = max(v0.x, max(v1.x, v2.x))
        val maxY = max(v0.y, max(v1.y, v2.y))
        val maxZ = max(v0.z, max(v1.z, v2.z))
        AABB(Vector3d(minX, minY, minZ), Vector3d(maxX, maxY, maxZ))
    }
}

class BVHNode {
    lateinit var bounds: AABB
    var left: BVHNode? = null
    var right: BVHNode? = null
    val primitives = mutableListOf<TrianglePrimitive>()

    fun isLeaf(): Boolean = left == null && right == null
}

class BVH {
    var root: BVHNode? = null
    private val maxPrimitivesInNode = 8

    fun build(allMeshes: List<PlacedMesh>) {
        val primitives = mutableListOf<TrianglePrimitive>()
        for (mesh in allMeshes) {
            if (!mesh.collision) continue

            val worldVertices = mesh.getTransformedVertices()
            for (faceIndex in mesh.mesh.faces.indices) {
                val face = mesh.mesh.faces[faceIndex]
                if (face.size < 3) continue

                val v0 = worldVertices[face[0]]
                val v1 = worldVertices[face[1]]
                val v2 = worldVertices[face[2]]
                primitives.add(TrianglePrimitive(v0, v1, v2, mesh, faceIndex))

                if (face.size == 4) {
                    val v3 = worldVertices[face[3]]
                    primitives.add(TrianglePrimitive(v0, v2, v3, mesh, faceIndex))
                }
            }
        }
        root = if (primitives.isNotEmpty()) {
                    recursiveBuild(primitives)
                } else {
                    null
                }
    }

    private fun recursiveBuild(primitives: List<TrianglePrimitive>): BVHNode {
        val node = BVHNode()
        node.bounds = primitives.drop(1).fold(primitives.first().bounds) { acc, p -> acc.union(p.bounds) }

        if (primitives.size <= maxPrimitivesInNode) {
            node.primitives.addAll(primitives)
            return node
        }

        val centroidBounds = primitives.drop(1).fold(AABB(primitives.first().center, primitives.first().center)) { acc, p -> acc.union(p.center) }
        val dim = centroidBounds.longestAxis()

        val sortedPrimitives = primitives.sortedBy { it.center[dim] }
        val mid = sortedPrimitives.size / 2

        node.left = recursiveBuild(sortedPrimitives.subList(0, mid))
        node.right = recursiveBuild(sortedPrimitives.subList(mid, sortedPrimitives.size))

        return node
    }

    fun intersect(rayOrigin: Vector3d, rayDir: Vector3d, maxDist: Double, ignoreMesh: PlacedMesh, ignoreFaceIndex: Int): Boolean {
        val rootNode = root ?: return false
        val invDir = Vector3d(1.0 / rayDir.x, 1.0 / rayDir.y, 1.0 / rayDir.z)

        val nodesToVisit = ArrayDeque<BVHNode>()
        nodesToVisit.add(rootNode)

        while (nodesToVisit.isNotEmpty()) {
            val currentNode = nodesToVisit.removeLast()

            if (!rayIntersectsAABB(rayOrigin, invDir, maxDist, currentNode.bounds)) {
                continue
            }

            if (currentNode.isLeaf()) {
                for (primitive in currentNode.primitives) {
                    if (primitive.mesh === ignoreMesh && primitive.faceIndex == ignoreFaceIndex) {
                        continue
                    }

                    return findFirstOpaqueIntersection(rayOrigin, rayDir, maxDist, ignoreMesh, ignoreFaceIndex) != null
                }
            } else {
                currentNode.left?.let { nodesToVisit.add(it) }
                currentNode.right?.let { nodesToVisit.add(it) }
            }
        }
        return false
    }

    private fun findFirstOpaqueIntersection(rayOrigin: Vector3d, rayDir: Vector3d, maxDist: Double, ignoreMesh: PlacedMesh?, ignoreFaceIndex: Int): TrianglePrimitive? {
        var closestOpaqueHit: TrianglePrimitive? = null
        var closestHitDist = maxDist

        val nodesToVisit = ArrayDeque<BVHNode>()
        root?.let { nodesToVisit.add(it) }

        val invDir = Vector3d(1.0 / rayDir.x, 1.0 / rayDir.y, 1.0 / rayDir.z)

        while (nodesToVisit.isNotEmpty()) {
            val currentNode = nodesToVisit.removeLast()

            if (!rayIntersectsAABB(rayOrigin, invDir, closestHitDist, currentNode.bounds)) {
                continue
            }

            if (currentNode.isLeaf()) {
                for (primitive in currentNode.primitives) {
                    if (primitive.mesh === ignoreMesh && primitive.faceIndex == ignoreFaceIndex) {
                        continue
                    }

                    val intersectionDist = rayIntersectsTriangle(rayOrigin, rayDir, primitive.v0, primitive.v1, primitive.v2, primitive)
                    if (intersectionDist != null && intersectionDist > 1e-6 && intersectionDist < closestHitDist) {
                        val intersectionPoint = rayOrigin + rayDir * intersectionDist
                        val worldBlushes by lazy {
                            primitive.mesh.mesh.blushes.map { blush ->
                                val transformedCorners = blush.getCorners().map { corner -> primitive.mesh.transformMatrix.transform(corner) }
                                AABB.fromCube(transformedCorners)
                            }
                        }

                        if ((worldBlushes.isEmpty() || !worldBlushes.any { it.contains(intersectionPoint) }) && !isPixelTransparentAtIntersection(primitive, primitive.barycentricCoords)) {
                            closestOpaqueHit = primitive
                            closestHitDist = intersectionDist
                        }
                    }
                }
            } else {
                currentNode.left?.let { nodesToVisit.add(it) }
                currentNode.right?.let { nodesToVisit.add(it) }
            }
        }
        return closestOpaqueHit
    }

    fun intersectWithDetails(rayOrigin: Vector3d, rayDir: Vector3d, maxDist: Double, ignoreMesh: PlacedMesh? = null, ignoreFaceIndex: Int = -1): Pair<TrianglePrimitive, Double>? {
        val rootNode = root ?: return null
        val invDir = Vector3d(1.0 / rayDir.x, 1.0 / rayDir.y, 1.0 / rayDir.z)

        val nodesToVisit = ArrayDeque<BVHNode>()
        nodesToVisit.add(rootNode)

        var closestIntersection: Pair<TrianglePrimitive, Double>? = null

        while (nodesToVisit.isNotEmpty()) {
            val currentNode = nodesToVisit.removeLast()

            if (!rayIntersectsAABB(rayOrigin, invDir, closestIntersection?.second ?: maxDist, currentNode.bounds)) {
                continue
            }

            if (currentNode.isLeaf()) {
                for (primitive in currentNode.primitives) {
                    if (primitive.mesh === ignoreMesh && primitive.faceIndex == ignoreFaceIndex) {
                        continue
                    }

                    val intersectionDist = rayIntersectsTriangle(rayOrigin, rayDir, primitive.v0, primitive.v1, primitive.v2, primitive)
                    if (intersectionDist != null && intersectionDist > 1e-6 && intersectionDist < (closestIntersection?.second ?: maxDist)) {
                        val intersectionPoint = rayOrigin + rayDir * intersectionDist
                        val worldBlushes by lazy {
                            primitive.mesh.mesh.blushes.map { blush ->
                                val transformedCorners = blush.getCorners().map { corner -> primitive.mesh.transformMatrix.transform(corner) }
                                AABB.fromCube(transformedCorners)
                            }
                        }
                        if (worldBlushes.isEmpty() || !worldBlushes.any { it.contains(intersectionPoint) }) {
                            if (!isPixelTransparentAtIntersection(primitive, primitive.barycentricCoords)) {
                                closestIntersection = Pair(primitive, intersectionDist)
                            }
                        }
                    }
                }
            } else {
                currentNode.left?.let { nodesToVisit.add(it) }
                currentNode.right?.let { nodesToVisit.add(it) }
            }
        }
        return closestIntersection
    }

    fun queryPrimitives(queryAABB: AABB): List<TrianglePrimitive> {
        val primitives = mutableListOf<TrianglePrimitive>()
        val rootNode = root ?: return primitives

        val nodesToVisit = ArrayDeque<BVHNode>()
        nodesToVisit.add(rootNode)

        while (nodesToVisit.isNotEmpty()) {
            val currentNode = nodesToVisit.removeLast()

            if (!queryAABB.intersects(currentNode.bounds)) {
                continue
            }

            if (currentNode.isLeaf()) {
                primitives.addAll(currentNode.primitives.filter { queryAABB.intersects(it.bounds) })
            } else {
                currentNode.left?.let { nodesToVisit.add(it) }
                currentNode.right?.let { nodesToVisit.add(it) }
            }
        }
        return primitives
    }

    private fun rayIntersectsTriangle(rayOrigin: Vector3d, rayDir: Vector3d, v0: Vector3d, v1: Vector3d, v2: Vector3d, primitive: TrianglePrimitive? = null): Double? {
        val epsilon = 1e-5
        val edge1 = v1 - v0
        val edge2 = v2 - v0
        val h = rayDir.cross(edge2)
        val a = edge1.dot(h)
        if (a < epsilon) return null
        val f = 1.0 / a // inv_a
        val s = rayOrigin - v0
        val u = f * s.dot(h)
        if (u < -epsilon || u > 1.0 + epsilon) return null
        val q = s.cross(edge1)
        val v = f * rayDir.dot(q)
        if (v < -epsilon || u + v > 1.0 + epsilon) return null // u+v > 1

        primitive?.barycentricCoords = Vector3d(u, v, 1.0 - u - v)

        val t = f * edge2.dot(q)
        return if (t > epsilon) t else null
    }

    private fun rayIntersectsAABB(rayOrigin: Vector3d, invDir: Vector3d, maxDist: Double, aabb: AABB): Boolean {
        val tx1 = (aabb.min.x - rayOrigin.x) * invDir.x
        val tx2 = (aabb.max.x - rayOrigin.x) * invDir.x
        var tmin = min(tx1, tx2)
        var tmax = max(tx1, tx2)
        val ty1 = (aabb.min.y - rayOrigin.y) * invDir.y
        val ty2 = (aabb.max.y - rayOrigin.y) * invDir.y
        tmin = max(tmin, min(ty1, ty2))
        tmax = min(tmax, max(ty1, ty2))
        val tz1 = (aabb.min.z - rayOrigin.z) * invDir.z
        val tz2 = (aabb.max.z - rayOrigin.z) * invDir.z
        tmin = max(tmin, min(tz1, tz2))
        tmax = min(tmax, max(tz1, tz2))
        return tmax >= max(0.0, tmin) && tmin < maxDist
    }

    private fun isPixelTransparentAtIntersection(primitive: TrianglePrimitive, barycentricCoords: Vector3d): Boolean {
        val texture = primitive.mesh.faceTextures[primitive.faceIndex] ?: primitive.mesh.texture ?: return false
        val faceUVs = primitive.mesh.mesh.faceUVs.getOrNull(primitive.faceIndex) ?: return false
        if (faceUVs.size < 3) return false

        val uv =
                if (faceUVs.size == 4) {
                val meshFaceIndices = primitive.mesh.mesh.faces[primitive.faceIndex]
                val meshV1 = primitive.mesh.getTransformedVertices()[meshFaceIndices[1]]

                val triUVs: List<Vector3d>
                if (primitive.v1 == meshV1) {
                    // This primitive is the first triangle (v0, v1, v2)
                    triUVs = listOf(faceUVs[0], faceUVs[1], faceUVs[2])
                } else {
                    // This primitive is the second triangle (v0, v2, v3)
                    triUVs = listOf(faceUVs[0], faceUVs[2], faceUVs[3])
                }
                val uCoord = triUVs[0].x * barycentricCoords.z + triUVs[1].x * barycentricCoords.x + triUVs[2].x * barycentricCoords.y
                val vCoord = triUVs[0].y * barycentricCoords.z + triUVs[1].y * barycentricCoords.x + triUVs[2].y * barycentricCoords.y
                    Pair(uCoord, vCoord)
                } else {
                    // Triangle face
                    val uCoord = faceUVs[0].x * barycentricCoords.z + faceUVs[1].x * barycentricCoords.x + faceUVs[2].x * barycentricCoords.y
                    val vCoord = faceUVs[0].y * barycentricCoords.z + faceUVs[1].y * barycentricCoords.x + faceUVs[2].y * barycentricCoords.y
                    Pair(uCoord, vCoord)
                }

        // Clamp UV coordinates to [0, 1]
        val u = uv.first.coerceIn(0.0, 1.0)
        val v = uv.second.coerceIn(0.0, 1.0)

        // Convert UV to pixel coordinates
        val pixelX = (u * texture.width).toInt().coerceIn(0, texture.width.toInt() - 1)
        val pixelY = (v * texture.height).toInt().coerceIn(0, texture.height.toInt() - 1)

        // Sample the texture
        val pixelReader = texture.pixelReader
        val color = pixelReader.getColor(pixelX, pixelY)

        // Check if alpha is below threshold (0.5 means more than 50% transparent)
        return color.opacity < 0.5
    }
}

operator fun Vector3d.get(index: Int): Double =
        when (index) {
            0 -> x
            1 -> y
            2 -> z
            else -> throw IndexOutOfBoundsException("Invalid index for Vector3d")
        }

fun AABB.longestAxis(): Int {
    val extent = max - min
    return if (extent.x > extent.y && extent.x > extent.z) 0
    else if (extent.y > extent.z) 1
    else 2
}