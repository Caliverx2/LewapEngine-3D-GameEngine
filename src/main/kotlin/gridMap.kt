package org.lewapnoob.gridMap

import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.sign
import java.util.Random

class gridMap : JPanel() {
    private val downscale = 8
    private val baseCols = 1920 / downscale
    private val baseRows = 1080 / downscale
    private val cellSize = downscale/2
    private val cubeSize = 2.0
    private val reachDistance = 5.0
    private val radiusCollision = 0.3
    private val playerHeight = 1.8

    private var debugNoclip = false
    private var debugFly = false
    private var velocityY = 0.0
    private var isOnGround = false
    private val gravity = 0.08
    private val jumpStrength = 0.8

    private val gridMap = Array(baseCols + 1) { Array(baseRows + 1) { null as Color? } }
    private val zBuffer = Array(baseRows + 1) { DoubleArray(baseCols + 1) { Double.MAX_VALUE } }

    // Odległość płaszczyzny przycinającej (near plane)
    private val nearPlaneZ = 0.1

    // --- 3D Structures ---
    data class Vector3d(var x: Double, var y: Double, var z: Double, var ao: Double = 1.0)
    data class BlockPos(val x: Int, val y: Int, val z: Int)

    data class Triangle3d(
        val p1: Vector3d, val p2: Vector3d, val p3: Vector3d, val color: Color
    )

    // Scena 3D
    private val chunkMeshes = mutableMapOf<Point, List<Triangle3d>>()

    // FPS Counter
    private var lastFpsTime = System.currentTimeMillis()
    private var frames = 0
    private var fps = 0
    private val fpsFont = try {
        val stream = javaClass.classLoader.getResourceAsStream("fonts/mojangles.ttf")
        if (stream != null) {
            Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(30f)
        } else {
            Font("Consolas", Font.PLAIN, 16)
        }
    } catch (e: Exception) {
        println("Failed to load font 'mojangles.ttf', using default.")
        Font("Consolas", Font.PLAIN, 16)
    }

    // --- Voxel World Data ---
    data class Chunk(val x: Int, val z: Int) {
        val width = 16
        val height = 64
        val depth = 16
        // Przechowuje kolor kostki lub null (powietrze)
        val blocks = Array(width) { Array(height) { Array(depth) { null as Color? } } }
    }

    private val chunks = mutableMapOf<Point, Chunk>()
    private var renderDistance = 5
    private var seed = 6767
    private lateinit var noise: PerlinNoise
    private var treeDensity = 0.004 // 2% szansy na drzewo na kratkę
    private var lastChunkX = Int.MAX_VALUE
    private var lastChunkZ = Int.MAX_VALUE

    // Kamera
    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0
    private var yaw = 0.0
    private var pitch = 0.0
        set(value) {
            field = value.coerceIn(-Math.PI / 2, Math.PI / 2)
        }

    // Zbiór aktualnie wciśniętych klawiszy
    private val keys = mutableSetOf<Int>()

    // Obsługa myszki
    private var isMouseCaptured = false
    private val robot = try { Robot() } catch (e: AWTException) { null }

    var runTime: Timer? = null

    // --- Tree Model ---
    private data class TreeVoxel(val x: Int, val y: Int, val z: Int, val color: Color)

    // Model drzewa przekonwertowany do jednej linii
    private val treeModelData = ";2,4,2,#00FF00;;0,0,0,#5D2F0A;;-2,4,2,#00FF00;;-2,4,1,#00FF00;;-2,4,0,#00FF00;;-1,6,0,#00FF00;;-2,4,-1,#00FF00;;-2,4,-2,#00FF00;;0,5,-1,#00FF00;;0,5,0,#5D2F0A;;1,3,-2,#00FF00;;0,5,1,#00FF00;;1,3,-1,#00FF00;;1,3,0,#00FF00;;0,1,0,#5D2F0A;;1,3,1,#00FF00;;1,3,2,#00FF00;;-2,3,2,#00FF00;;-2,3,1,#00FF00;;-2,3,0,#00FF00;;-1,5,1,#00FF00;;-2,3,-1,#00FF00;;-1,5,0,#00FF00;;-2,3,-2,#00FF00;;-1,5,-1,#00FF00;;0,6,-1,#00FF00;;0,6,0,#00FF00;;0,6,1,#00FF00;;1,4,-2,#00FF00;;1,4,-1,#00FF00;;1,4,0,#00FF00;;0,2,0,#5D2F0A;;1,4,1,#00FF00;;1,4,2,#00FF00;;-1,4,2,#00FF00;;-1,4,1,#00FF00;;-1,4,0,#00FF00;;-1,4,-1,#00FF00;;-1,4,-2,#00FF00;;0,3,-2,#00FF00;;1,5,-1,#00FF00;;0,3,-1,#00FF00;;1,5,0,#00FF00;;0,3,0,#5D2F0A;;2,3,-2,#00FF00;;1,5,1,#00FF00;;2,3,-1,#00FF00;;0,3,1,#00FF00;;0,3,2,#00FF00;;2,3,0,#00FF00;;2,3,1,#00FF00;;-1,3,2,#00FF00;;2,3,2,#00FF00;;-1,3,1,#00FF00;;-1,3,0,#00FF00;;-1,3,-1,#00FF00;;-1,3,-2,#00FF00;;0,4,-2,#00FF00;;0,4,-1,#00FF00;;1,6,0,#00FF00;;0,4,0,#5D2F0A;;2,4,-2,#00FF00;;0,4,1,#00FF00;;2,4,-1,#00FF00;;2,4,0,#00FF00;;0,4,2,#00FF00;;2,4,1,#00FF00;"
    private val treeModel = parseTreeModel(treeModelData)

    private fun parseTreeModel(data: String): List<TreeVoxel> {
        return data.split(";")
            .filter { it.isNotBlank() }
            .mapNotNull { part ->
                try {
                    val tokens = part.trim().split(",")
                    if (tokens.size == 4) {
                        val x = tokens[0].toInt()
                        val y = tokens[1].toInt()
                        val z = tokens[2].toInt()
                        val colStr = tokens[3].trim()
                        val color = if (colStr.startsWith("#")) Color.decode(colStr) else Color(colStr.toInt(), true)
                        TreeVoxel(x, y, z, color)
                    } else null
                } catch (e: Exception) { null }
            }
    }

    init {
        preferredSize = Dimension((baseCols + 1) * cellSize, (baseRows + 1) * cellSize)
        setBackground(Color(113, 144, 225))
        addKeyListener(KeyboardListener())
        addMouseListener(GridMouseListener())
        addMouseMotionListener(GridMouseMotionListener())
        isFocusable = true

        noise = PerlinNoise(seed)

        // Ustawiamy gracza na powierzchni (pobieramy wysokość terenu w punkcie 0,0)
        val spawnH = getTerrainHeight(0, 0)
        // Ustawiamy kamerę tak, by była nad ziemią (index trawy + 2 bloki ~ wysokość głowy)
        camY = (spawnH + 2) * cubeSize - 10.0

        updateWorld()

        loop()
    }

    private fun updateWorld() {
        val currentChunkX = floor(camX / 32.0).toInt()
        val currentChunkZ = floor(camZ / 32.0).toInt()

        var newChunkGenerated = false
        val chunksToUpdate = mutableSetOf<Point>()

        for (cx in currentChunkX - renderDistance..currentChunkX + renderDistance) {
            for (cz in currentChunkZ - renderDistance..currentChunkZ + renderDistance) {
                if (!chunks.containsKey(Point(cx, cz))) {
                    generateChunk(cx, cz)
                    // Oznaczamy nowy chunk i jego sąsiadów do aktualizacji mesha
                    chunksToUpdate.add(Point(cx, cz))
                    chunksToUpdate.addAll(getNeighborChunks(cx, cz))
                    newChunkGenerated = true
                }
            }
        }

        if (newChunkGenerated || currentChunkX != lastChunkX || currentChunkZ != lastChunkZ) {
            lastChunkX = currentChunkX
            lastChunkZ = currentChunkZ
        }

        // Aktualizujemy meshe tylko dla nowych/zmienionych chunków
        chunksToUpdate.forEach { p ->
            if (chunks.containsKey(p)) {
                updateChunkMesh(p.x, p.y)
            }
        }
    }

    private fun getNeighborChunks(cx: Int, cz: Int): List<Point> {
        return listOf(Point(cx + 1, cz), Point(cx - 1, cz), Point(cx, cz + 1), Point(cx, cz - 1))
    }

    private fun generateChunk(cx: Int, cz: Int) {
        val chunk = Chunk(cx, cz)
        chunks[Point(cx, cz)] = chunk

        // 1. Generowanie terenu
        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                val wx = cx * 16 + lx
                val wz = cz * 16 + lz
                val h = getTerrainHeight(wx, wz)

                for (y in 0..h) {
                    val color = when {
                        y == h -> Color(0x59A608)
                        y > h - 4 -> Color(0x6c3c0c)
                        else -> Color(0x8EA3A1)
                    }
                    chunk.blocks[lx][y][lz] = color
                }
            }
        }

        // 2. Generowanie drzew
        // Sprawdzamy obszar nieco szerszy niż chunk, aby drzewa z sąsiednich kratek mogły wejść na ten chunk
        // Promień drzewa to ok. 2 kratki, więc sprawdzamy od -3 do 18
        for (lx in -3..18) {
            for (lz in -3..18) {
                val wx = cx * 16 + lx
                val wz = cz * 16 + lz

                // Deterministyczne losowanie pozycji drzewa na podstawie seeda i współrzędnych
                if (isTreeAt(wx, wz)) {
                    val h = getTerrainHeight(wx, wz)
                    // Rysujemy drzewo, jeśli jego elementy wpadają w ten chunk
                    placeTree(chunk, lx, h + 1, lz)
                }
            }
        }
    }

    private fun getTerrainHeight(wx: Int, wz: Int): Int {
        val n = noise.noise(wx * 0.02, wz * 0.02)
        return (16 + n * 12).toInt().coerceIn(0, 63)
    }

    private fun isTreeAt(wx: Int, wz: Int): Boolean {
        // Prosty hash współrzędnych i seeda
        val hash = (wx * 73856093 xor wz * 19349663 xor seed).toString().hashCode()
        val random = Random(hash.toLong())
        return random.nextDouble() < treeDensity
    }

    private fun placeTree(chunk: Chunk, rootLx: Int, rootY: Int, rootLz: Int) {
        for (voxel in treeModel) {
            val tx = rootLx + voxel.x
            val ty = rootY + voxel.y
            val tz = rootLz + voxel.z

            // Sprawdzamy czy voxel mieści się w aktualnym chunku
            if (tx in 0 until 16 && tz in 0 until 16 && ty in 0 until 64) {
                // Nadpisujemy tylko powietrze (null), opcjonalnie można nadpisywać wszystko
                if (chunk.blocks[tx][ty][tz] == null) {
                    chunk.blocks[tx][ty][tz] = voxel.color
                }
                if (chunk.blocks[tx][ty-1][tz] == Color(0x59A608)) {
                    chunk.blocks[tx][ty-1][tz] = Color(0x6c3c0c)
                }
            }
        }
    }

    private fun setBlock(x: Int, y: Int, z: Int, color: Color?) {
        if (y < 0 || y >= 64) return
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1

        val chunk = chunks[Point(cx, cz)] ?: return

        var lx = x % 16
        if (lx < 0) lx += 16
        var lz = z % 16
        if (lz < 0) lz += 16

        chunk.blocks[lx][y][lz] = color
    }

    private fun getTargetBlock(): BlockPos? {
        val startX = camX / cubeSize + 0.5
        val startY = (camY + 10.0) / cubeSize + 0.5
        val startZ = camZ / cubeSize + 0.5

        val dirX = sin(yaw) * cos(pitch)
        val dirY = sin(pitch)
        val dirZ = cos(yaw) * cos(pitch)

        var x = floor(startX).toInt()
        var y = floor(startY).toInt()
        var z = floor(startZ).toInt()

        val stepX = if (dirX > 0) 1 else -1
        val stepY = if (dirY > 0) 1 else -1
        val stepZ = if (dirZ > 0) 1 else -1

        val tDeltaX = if (dirX == 0.0) Double.MAX_VALUE else abs(1.0 / dirX)
        val tDeltaY = if (dirY == 0.0) Double.MAX_VALUE else abs(1.0 / dirY)
        val tDeltaZ = if (dirZ == 0.0) Double.MAX_VALUE else abs(1.0 / dirZ)

        val distX = if (stepX > 0) (floor(startX) + 1.0 - startX) else (startX - floor(startX))
        val distY = if (stepY > 0) (floor(startY) + 1.0 - startY) else (startY - floor(startY))
        val distZ = if (stepZ > 0) (floor(startZ) + 1.0 - startZ) else (startZ - floor(startZ))

        var tMaxX = if (dirX == 0.0) Double.MAX_VALUE else distX * tDeltaX
        var tMaxY = if (dirY == 0.0) Double.MAX_VALUE else distY * tDeltaY
        var tMaxZ = if (dirZ == 0.0) Double.MAX_VALUE else distZ * tDeltaZ

        while (true) {
            if (minOf(tMaxX, tMaxY, tMaxZ) > reachDistance) break

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX
                    tMaxX += tDeltaX
                } else {
                    z += stepZ
                    tMaxZ += tDeltaZ
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY
                    tMaxY += tDeltaY
                } else {
                    z += stepZ
                    tMaxZ += tDeltaZ
                }
            }

            if (getBlock(x, y, z) != null) {
                return BlockPos(x, y, z)
            }
        }
        return null
    }

    private fun raycastAction(place: Boolean) {
        // Pozycja startowa w przestrzeni voxeli (przeliczenie z koordynatów świata)
        val startX = camX / cubeSize + 0.5
        val startY = (camY + 10.0) / cubeSize + 0.5
        val startZ = camZ / cubeSize + 0.5

        // Wektor kierunku (znormalizowany)
        val dirX = sin(yaw) * cos(pitch)
        val dirY = sin(pitch)
        val dirZ = cos(yaw) * cos(pitch)

        // Inicjalizacja DDA
        var x = floor(startX).toInt()
        var y = floor(startY).toInt()
        var z = floor(startZ).toInt()

        val stepX = if (dirX > 0) 1 else -1
        val stepY = if (dirY > 0) 1 else -1
        val stepZ = if (dirZ > 0) 1 else -1

        val tDeltaX = if (dirX == 0.0) Double.MAX_VALUE else Math.abs(1.0 / dirX)
        val tDeltaY = if (dirY == 0.0) Double.MAX_VALUE else Math.abs(1.0 / dirY)
        val tDeltaZ = if (dirZ == 0.0) Double.MAX_VALUE else Math.abs(1.0 / dirZ)

        val distX = if (stepX > 0) (floor(startX) + 1.0 - startX) else (startX - floor(startX))
        val distY = if (stepY > 0) (floor(startY) + 1.0 - startY) else (startY - floor(startY))
        val distZ = if (stepZ > 0) (floor(startZ) + 1.0 - startZ) else (startZ - floor(startZ))

        var tMaxX = if (dirX == 0.0) Double.MAX_VALUE else distX * tDeltaX
        var tMaxY = if (dirY == 0.0) Double.MAX_VALUE else distY * tDeltaY
        var tMaxZ = if (dirZ == 0.0) Double.MAX_VALUE else distZ * tDeltaZ

        var lastX = x
        var lastY = y
        var lastZ = z

        // Pętla DDA
        while (true) {
            if (minOf(tMaxX, tMaxY, tMaxZ) > reachDistance) break

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX
                    tMaxX += tDeltaX
                } else {
                    z += stepZ
                    tMaxZ += tDeltaZ
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY
                    tMaxY += tDeltaY
                } else {
                    z += stepZ
                    tMaxZ += tDeltaZ
                }
            }

            if (getBlock(x, y, z) != null) {
                if (place) {
                    if (!isPlayerInsideBlock(lastX, lastY, lastZ)) {
                        setBlock(lastX, lastY, lastZ, Color(0x6c3c0c)) // Kolor ziemi
                    }
                } else {
                    setBlock(x, y, z, null)
                }

                // Optymalizacja: Aktualizujemy tylko chunk, w którym zaszła zmiana
                val cx = if (lastX >= 0) lastX / 16 else (lastX + 1) / 16 - 1
                val cz = if (lastZ >= 0) lastZ / 16 else (lastZ + 1) / 16 - 1
                updateChunkMesh(cx, cz)

                // Jeśli blok jest na krawędzi chunka, aktualizujemy też sąsiada
                val lx = lastX - cx * 16
                val lz = lastZ - cz * 16
                if (lx == 0) updateChunkMesh(cx - 1, cz)
                if (lx == 15) updateChunkMesh(cx + 1, cz)
                if (lz == 0) updateChunkMesh(cx, cz - 1)
                if (lz == 15) updateChunkMesh(cx, cz + 1)
                return
            }

            lastX = x
            lastY = y
            lastZ = z
        }
    }

    // Pobiera blok z globalnych współrzędnych (obsługuje granice chunków)
    private fun getBlock(x: Int, y: Int, z: Int): Color? {
        if (y < 0 || y >= 64) return null
        // Obliczamy ID chunka
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1

        val chunk = chunks[Point(cx, cz)] ?: return null

        // Lokalne współrzędne w chunku
        var lx = x % 16
        if (lx < 0) lx += 16
        var lz = z % 16
        if (lz < 0) lz += 16

        return chunk.blocks[lx][y][lz]
    }

    // Generuje mesh tylko dla jednego chunka
    private fun updateChunkMesh(cx: Int, cz: Int) {
        val chunk = chunks[Point(cx, cz)] ?: return
        val newTriangles = mutableListOf<Triangle3d>()

        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                for (y in 0 until 64) {
                    val color = chunk.blocks[lx][y][lz] ?: continue

                    // Globalne pozycje logiczne
                    val wx = cx * 16 + lx
                    val wz = cz * 16 + lz

                    // Pozycja w świecie 3D
                    val xPos = wx * cubeSize
                    val yPos = y * cubeSize - 10.0
                    val zPos = wz * cubeSize

                    // Sprawdzamy sąsiadów
                    if (getBlock(wx, y, wz - 1) == null) addFace(newTriangles, wx, y, wz, xPos, yPos, zPos, 0, color)
                    if (getBlock(wx, y, wz + 1) == null) addFace(newTriangles, wx, y, wz, xPos, yPos, zPos, 1, color)
                    if (getBlock(wx - 1, y, wz) == null) addFace(newTriangles, wx, y, wz, xPos, yPos, zPos, 2, color)
                    if (getBlock(wx + 1, y, wz) == null) addFace(newTriangles, wx, y, wz, xPos, yPos, zPos, 3, color)
                    if (getBlock(wx, y + 1, wz) == null) addFace(newTriangles, wx, y, wz, xPos, yPos, zPos, 4, color)
                    if (getBlock(wx, y - 1, wz) == null) addFace(newTriangles, wx, y, wz, xPos, yPos, zPos, 5, color)
                }
            }
        }
        chunkMeshes[Point(cx, cz)] = newTriangles
    }

    private fun addFace(target: MutableList<Triangle3d>, wx: Int, wy: Int, wz: Int, x: Double, y: Double, z: Double, faceType: Int, c: Color) {
        // Rozmiar kostki = cubeSize (od -cubeSize/2 do +cubeSize/2 względem środka)
        val d = cubeSize / 2.0
        val p = arrayOf(
                Vector3d(x - d, y - d, z - d), Vector3d(x + d, y - d, z - d), // 0, 1
                Vector3d(x + d, y + d, z - d), Vector3d(x - d, y + d, z - d), // 2, 3
                Vector3d(x - d, y - d, z + d), Vector3d(x + d, y - d, z + d), // 4, 5
                Vector3d(x + d, y + d, z + d), Vector3d(x - d, y + d, z + d)  // 6, 7
        )

        fun vertexAO(s1: Boolean, s2: Boolean, corner: Boolean): Double {
            val level = if (s1 && s2) 3 else (if (s1) 1 else 0) + (if (s2) 1 else 0) + (if (corner) 1 else 0)
            return when (level) {
                0 -> 1.0 // Najjaśniej
                1 -> 0.75
                2 -> 0.5
                3 -> 0.25 // Najciemniej
                else -> 1.0
            }
        }

        fun addTri(i1: Int, i2: Int, i3: Int, i4: Int, shade: Double, ao: DoubleArray) {
            val v1 = p[i1].copy().apply { this.ao = ao[0] * shade }
            val v2 = p[i2].copy().apply { this.ao = ao[1] * shade }
            val v3 = p[i3].copy().apply { this.ao = ao[2] * shade }
            val v4 = p[i4].copy().apply { this.ao = ao[3] * shade }

            target.add(Triangle3d(v1, v2, v3, c))
            target.add(Triangle3d(v1, v3, v4, c))
        }

        val aoValues = DoubleArray(4)
        val v = Array(4) { BooleanArray(3) }

        // Obliczanie AO dla 4 wierzchołków danej ściany
        when (faceType) {
            0 -> { // Front (Z-), quad 0,1,2,3
                v[0][0] = getBlock(wx - 1, wy, wz - 1) != null; v[0][1] = getBlock(wx, wy - 1, wz - 1) != null; v[0][2] = getBlock(wx - 1, wy - 1, wz - 1) != null
                v[1][0] = getBlock(wx + 1, wy, wz - 1) != null; v[1][1] = getBlock(wx, wy - 1, wz - 1) != null; v[1][2] = getBlock(wx + 1, wy - 1, wz - 1) != null
                v[2][0] = getBlock(wx + 1, wy, wz - 1) != null; v[2][1] = getBlock(wx, wy + 1, wz - 1) != null; v[2][2] = getBlock(wx + 1, wy + 1, wz - 1) != null
                v[3][0] = getBlock(wx - 1, wy, wz - 1) != null; v[3][1] = getBlock(wx, wy + 1, wz - 1) != null; v[3][2] = getBlock(wx - 1, wy + 1, wz - 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                addTri(0, 1, 2, 3, 0.8, aoValues)
            }
            1 -> { // Back (Z+), quad 5,4,7,6
                v[0][0] = getBlock(wx + 1, wy, wz + 1) != null; v[0][1] = getBlock(wx, wy - 1, wz + 1) != null; v[0][2] = getBlock(wx + 1, wy - 1, wz + 1) != null
                v[1][0] = getBlock(wx - 1, wy, wz + 1) != null; v[1][1] = getBlock(wx, wy - 1, wz + 1) != null; v[1][2] = getBlock(wx - 1, wy - 1, wz + 1) != null
                v[2][0] = getBlock(wx - 1, wy, wz + 1) != null; v[2][1] = getBlock(wx, wy + 1, wz + 1) != null; v[2][2] = getBlock(wx - 1, wy + 1, wz + 1) != null
                v[3][0] = getBlock(wx + 1, wy, wz + 1) != null; v[3][1] = getBlock(wx, wy + 1, wz + 1) != null; v[3][2] = getBlock(wx + 1, wy + 1, wz + 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                addTri(5, 4, 7, 6, 0.8, aoValues)
            }
            2 -> { // Left (X-), quad 4,0,3,7
                v[0][0] = getBlock(wx - 1, wy, wz + 1) != null; v[0][1] = getBlock(wx - 1, wy - 1, wz) != null; v[0][2] = getBlock(wx - 1, wy - 1, wz + 1) != null
                v[1][0] = getBlock(wx - 1, wy, wz - 1) != null; v[1][1] = getBlock(wx - 1, wy - 1, wz) != null; v[1][2] = getBlock(wx - 1, wy - 1, wz - 1) != null
                v[2][0] = getBlock(wx - 1, wy, wz - 1) != null; v[2][1] = getBlock(wx - 1, wy + 1, wz) != null; v[2][2] = getBlock(wx - 1, wy + 1, wz - 1) != null
                v[3][0] = getBlock(wx - 1, wy, wz + 1) != null; v[3][1] = getBlock(wx - 1, wy + 1, wz) != null; v[3][2] = getBlock(wx - 1, wy + 1, wz + 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                addTri(4, 0, 3, 7, 0.6, aoValues)
            }
            3 -> { // Right (X+), quad 1,5,6,2
                v[0][0] = getBlock(wx + 1, wy, wz - 1) != null; v[0][1] = getBlock(wx + 1, wy - 1, wz) != null; v[0][2] = getBlock(wx + 1, wy - 1, wz - 1) != null
                v[1][0] = getBlock(wx + 1, wy, wz + 1) != null; v[1][1] = getBlock(wx + 1, wy - 1, wz) != null; v[1][2] = getBlock(wx + 1, wy - 1, wz + 1) != null
                v[2][0] = getBlock(wx + 1, wy, wz + 1) != null; v[2][1] = getBlock(wx + 1, wy + 1, wz) != null; v[2][2] = getBlock(wx + 1, wy + 1, wz + 1) != null
                v[3][0] = getBlock(wx + 1, wy, wz - 1) != null; v[3][1] = getBlock(wx + 1, wy + 1, wz) != null; v[3][2] = getBlock(wx + 1, wy + 1, wz - 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                addTri(1, 5, 6, 2, 0.6, aoValues)
            }
            4 -> { // Top (Y+), quad 3,2,6,7
                v[0][0] = getBlock(wx - 1, wy + 1, wz) != null; v[0][1] = getBlock(wx, wy + 1, wz - 1) != null; v[0][2] = getBlock(wx - 1, wy + 1, wz - 1) != null
                v[1][0] = getBlock(wx + 1, wy + 1, wz) != null; v[1][1] = getBlock(wx, wy + 1, wz - 1) != null; v[1][2] = getBlock(wx + 1, wy + 1, wz - 1) != null
                v[2][0] = getBlock(wx + 1, wy + 1, wz) != null; v[2][1] = getBlock(wx, wy + 1, wz + 1) != null; v[2][2] = getBlock(wx + 1, wy + 1, wz + 1) != null
                v[3][0] = getBlock(wx - 1, wy + 1, wz) != null; v[3][1] = getBlock(wx, wy + 1, wz + 1) != null; v[3][2] = getBlock(wx - 1, wy + 1, wz + 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                addTri(3, 2, 6, 7, 1.0, aoValues)
            }
            5 -> { // Bottom (Y-), quad 4,5,1,0
                v[0][0] = getBlock(wx - 1, wy - 1, wz) != null; v[0][1] = getBlock(wx, wy - 1, wz + 1) != null; v[0][2] = getBlock(wx - 1, wy - 1, wz + 1) != null
                v[1][0] = getBlock(wx + 1, wy - 1, wz) != null; v[1][1] = getBlock(wx, wy - 1, wz + 1) != null; v[1][2] = getBlock(wx + 1, wy - 1, wz + 1) != null
                v[2][0] = getBlock(wx + 1, wy - 1, wz) != null; v[2][1] = getBlock(wx, wy - 1, wz - 1) != null; v[2][2] = getBlock(wx + 1, wy - 1, wz - 1) != null
                v[3][0] = getBlock(wx - 1, wy - 1, wz) != null; v[3][1] = getBlock(wx, wy - 1, wz - 1) != null; v[3][2] = getBlock(wx - 1, wy - 1, wz - 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                addTri(4, 5, 1, 0, 0.4, aoValues)
            }
        }
    }

    private fun Vector3d.copy(): Vector3d = Vector3d(x, y, z, ao)

    // Czyści gridMap (ekran)
    private fun clearGrid() {
        for (y in 0..baseRows) {
            for (x in 0..baseCols) {
                gridMap[x][y] = null
                zBuffer[y][x] = Double.MAX_VALUE
            }
        }
    }

    fun loop() {
        runTime = Timer(33) { // ~30 FPS
            clearGrid()
            processInput() // Obsługa klawiatury w każdej klatce
            updateWorld()
            render3D()
            repaint()
        }
        runTime?.start()
    }

    private fun render3D() {
        val trianglesToRaster = mutableListOf<Triangle3d>()

        val currentChunkX = floor(camX / 32.0).toInt()
        val currentChunkZ = floor(camZ / 32.0).toInt()

        // Iterujemy tylko po widocznych chunkach
        for (cx in currentChunkX - renderDistance..currentChunkX + renderDistance) {
            for (cz in currentChunkZ - renderDistance..currentChunkZ + renderDistance) {
                val mesh = chunkMeshes[Point(cx, cz)] ?: continue
                for (tri in mesh) {

            // Kopia wierzchołków do transformacji
            val t1 = transform(tri.p1)
            val t2 = transform(tri.p2)
            val t3 = transform(tri.p3)

            // Back-face Culling (Odrzucanie tylnych ścianek)
            // Obliczamy wektor normalny powierzchni
            val line1 = Vector3d(t2.x - t1.x, t2.y - t1.y, t2.z - t1.z)
            val line2 = Vector3d(t3.x - t1.x, t3.y - t1.y, t3.z - t1.z)

            // Iloczyn wektorowy (Cross Product)
            val normal = Vector3d(
                line1.y * line2.z - line1.z * line2.y,
                line1.z * line2.x - line1.x * line2.z,
                line1.x * line2.y - line1.y * line2.x
            )

            // Normalizacja (opcjonalna, ale dobra dla oświetlenia)
            val l = sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z)
            normal.x /= l; normal.y /= l; normal.z /= l

            // Iloczyn skalarny z wektorem widoku (kamera patrzy w stronę punktu, więc wektor to p - cam)
            // Tutaj po transformacji kamera jest w (0,0,0), a punkty są względem niej.
            // Sprawdzamy czy normalna jest zwrócona do kamery (dot product > 0)
            if (t1.x * normal.x + t1.y * normal.y + t1.z * normal.z > 0) {
                trianglesToRaster.add(Triangle3d(t1, t2, t3, tri.color)) // AO jest już w wierzchołkach
            }
                }
            }
        }

        // 3. Projekcja i Rasteryzacja
        for (tri in trianglesToRaster) {
            // Clipping: Zamiast odrzucać, przycinamy trójkąt do Near Plane (Z=1.0)
            val clippedTriangles = clipTriangleAgainstPlane(tri)

            for (clipped in clippedTriangles) {
                val p1_2d = project(clipped.p1)
                val p2_2d = project(clipped.p2)
                val p3_2d = project(clipped.p3)

                fillTriangle(p1_2d, p2_2d, p3_2d, clipped.p1, clipped.p2, clipped.p3, clipped.color)
            }
        }

        val target = getTargetBlock()
        if (target != null) {
            drawSelectionBox(target)
        }
    }

    private fun transform(v: Vector3d): Vector3d {
        var x = v.x - camX
        var y = v.y - camY
        var z = v.z - camZ

        // Obrót Y (Yaw)
        val cosY = cos(yaw)
        val sinY = sin(yaw)
        val x2 = x * cosY - z * sinY
        val z2 = z * cosY + x * sinY
        x = x2
        z = z2

        // Obrót X (Pitch)
        val cosP = cos(pitch)
        val sinP = sin(pitch)
        val y2 = y * cosP - z * sinP
        val z3 = z * cosP + y * sinP
        y = y2
        z = z3

        return Vector3d(x, y, z, v.ao)
    }

    // Algorytm Sutherland-Hodgman do przycinania trójkąta względem płaszczyzny Z (Near Plane)
    private fun clipTriangleAgainstPlane(tri: Triangle3d): List<Triangle3d> {
        val input = listOf(tri.p1, tri.p2, tri.p3)
        val output = mutableListOf<Vector3d>()

        // Iterujemy po krawędziach trójkąta
        for (i in input.indices) {
            val current = input[i]
            val prev = input[(i + input.size - 1) % input.size]

            val currIn = current.z >= nearPlaneZ
            val prevIn = prev.z >= nearPlaneZ

            if (currIn && prevIn) {
                output.add(current) // Oba w środku -> dodajemy obecny
            } else if (currIn && !prevIn) {
                output.add(intersectPlane(prev, current, nearPlaneZ)) // Wchodzimy do środka -> punkt przecięcia + obecny
                output.add(current)
            } else if (!currIn && prevIn) {
                output.add(intersectPlane(prev, current, nearPlaneZ)) // Wychodzimy na zewnątrz -> punkt przecięcia
            }
        }

        val result = mutableListOf<Triangle3d>()
        if (output.size == 3) {
            result.add(Triangle3d(output[0], output[1], output[2], tri.color))
        } else if (output.size == 4) {
            // Jeśli powstał czworokąt, dzielimy go na dwa trójkąty
            result.add(Triangle3d(output[0], output[1], output[2], tri.color))
            result.add(Triangle3d(output[0], output[2], output[3], tri.color))
        }
        return result
    }

    private fun intersectPlane(p1: Vector3d, p2: Vector3d, z: Double): Vector3d {
        val t = (z - p1.z) / (p2.z - p1.z)
        val x = p1.x + (p2.x - p1.x) * t
        val y = p1.y + (p2.y - p1.y) * t
        val ao = p1.ao + (p2.ao - p1.ao) * t
        return Vector3d(x, y, z, ao)
    }

    private fun project(v: Vector3d): Point {
        val fov = 90.0 // Field of View / Focal Length
        val zSafe = if (v.z == 0.0) 0.001 else v.z // Unikamy dzielenia przez 0

        // Perspektywa: x' = x / z
        val px = (v.x * fov) / zSafe
        // Odwracamy oś Y, ponieważ na ekranie Y rośnie w dół, a w świecie 3D w górę
        val py = -(v.y * fov) / zSafe

        // Przesunięcie na środek ekranu (gridMapy)
        val screenX = px + (baseCols / 2)
        val screenY = py + (baseRows / 2)

        return Point(screenX.toInt(), screenY.toInt())
    }

    private fun drawSelectionBox(block: BlockPos) {
        val x = block.x * cubeSize
        val y = block.y * cubeSize - 10.0
        val z = block.z * cubeSize
        val d = cubeSize / 2.0 + 0.01 // Slightly larger than 1.0 to avoid Z-fighting

        val c0 = Vector3d(x - d, y - d, z - d)
        val c1 = Vector3d(x + d, y - d, z - d)
        val c2 = Vector3d(x + d, y + d, z - d)
        val c3 = Vector3d(x - d, y + d, z - d)
        val c4 = Vector3d(x - d, y - d, z + d)
        val c5 = Vector3d(x + d, y - d, z + d)
        val c6 = Vector3d(x + d, y + d, z + d)
        val c7 = Vector3d(x - d, y + d, z + d)

        val color = Color.BLACK

        // Front
        drawLine3D(c0, c1, color); drawLine3D(c1, c2, color)
        drawLine3D(c2, c3, color); drawLine3D(c3, c0, color)
        // Back
        drawLine3D(c4, c5, color); drawLine3D(c5, c6, color)
        drawLine3D(c6, c7, color); drawLine3D(c7, c4, color)
        // Connecting
        drawLine3D(c0, c4, color); drawLine3D(c1, c5, color)
        drawLine3D(c2, c6, color); drawLine3D(c3, c7, color)
    }

    private fun drawLine3D(p1: Vector3d, p2: Vector3d, color: Color) {
        val t1 = transform(p1)
        val t2 = transform(p2)

        if (t1.z < nearPlaneZ && t2.z < nearPlaneZ) return

        var v1 = t1
        var v2 = t2

        if (v1.z < nearPlaneZ) v1 = intersectPlane(v1, v2, nearPlaneZ)
        if (v2.z < nearPlaneZ) v2 = intersectPlane(v2, v1, nearPlaneZ)

        val proj1 = project(v1)
        val proj2 = project(v2)

        rasterizeLine(proj1, proj2, v1.z, v2.z, color)
    }

    private fun rasterizeLine(p1: Point, p2: Point, z1: Double, z2: Double, color: Color) {
        var x0 = p1.x; var y0 = p1.y
        val x1 = p2.x; val y1 = p2.y
        val dx = abs(x1 - x0); val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy

        val totalDist = sqrt(((x1 - p1.x) * (x1 - p1.x) + (y1 - p1.y) * (y1 - p1.y)).toDouble())

        while (true) {
            if (x0 in 0..baseCols && y0 in 0..baseRows) {
                val currDist = sqrt(((x0 - p1.x) * (x0 - p1.x) + (y0 - p1.y) * (y0 - p1.y)).toDouble())
                val t = if (totalDist == 0.0) 0.0 else currDist / totalDist
                val z = z1 + t * (z2 - z1)

                if (z < zBuffer[y0][x0]) {
                    gridMap[x0][y0] = color
                    zBuffer[y0][x0] = z
                }
            }
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; x0 += sx }
            if (e2 < dx) { err += dx; y0 += sy }
        }
    }

    // Rasteryzacja trójkąta bezpośrednio do gridMap
    private fun fillTriangle(p1: Point, p2: Point, p3: Point, v1: Vector3d, v2: Vector3d, v3: Vector3d, color: Color) {
        // Bounding box
        val minX = minOf(p1.x, p2.x, p3.x).coerceIn(0, baseCols)
        val maxX = maxOf(p1.x, p2.x, p3.x).coerceIn(0, baseCols)
        val minY = minOf(p1.y, p2.y, p3.y).coerceIn(0, baseRows)
        val maxY = maxOf(p1.y, p2.y, p3.y).coerceIn(0, baseRows)

        // Optymalizacja: Obliczenia stałych dla trójkąta przed pętlą (unikamy liczenia tego per piksel)
        val v0x = (p2.x - p1.x).toDouble()
        val v0y = (p2.y - p1.y).toDouble()
        val v1x = (p3.x - p1.x).toDouble()
        val v1y = (p3.y - p1.y).toDouble()

        val d00 = v0x * v0x + v0y * v0y
        val d01 = v0x * v1x + v0y * v1y
        val d11 = v1x * v1x + v1y * v1y

        val denom = d00 * d11 - d01 * d01
        if (denom == 0.0) return

        val invDenom = 1.0 / denom

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val v2x = (x - p1.x).toDouble()
                val v2y = (y - p1.y).toDouble()
                val d20 = v2x * v0x + v2y * v0y
                val d21 = v2x * v1x + v2y * v1y

                val v = (d11 * d20 - d01 * d21) * invDenom
                val w = (d00 * d21 - d01 * d20) * invDenom
                val u = 1.0 - v - w

                // Dodajemy mały margines błędu (epsilon), aby uniknąć "dziur" na łączeniach trójkątów
                if (u >= -0.001 && v >= -0.001 && w >= -0.001) {
                    // Interpolacja głębokości (Z) i AO dla danego piksela
                    // UWAGA: To jest interpolacja liniowa w przestrzeni ekranu, a nie z korekcją perspektywy.
                    // Dla prostego silnika jest to wystarczające, ale może powodować artefakty.
                    val depth = u * v1.z + v * v2.z + w * v3.z
                    val ao = u * v1.ao + v * v2.ao + w * v3.ao

                    // Z-Buffer Test: Rysujemy tylko jeśli nowy piksel jest bliżej niż obecny
                    if (depth < zBuffer[y][x]) {
                        zBuffer[y][x] = depth
                        val finalColor = Color(
                                (color.red * ao).toInt().coerceIn(0, 255),
                                (color.green * ao).toInt().coerceIn(0, 255),
                                (color.blue * ao).toInt().coerceIn(0, 255))
                        gridMap[x][y] = finalColor
                    }
                }
            }
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D

        drawLine(0, 0, 4, 8)

        // Rysowanie zawartości gridMap (naszego ekranu)
        for (x in 0..baseCols) {
            for (y in 0..baseRows) {
                val color = gridMap[x][y]
                if (color != null) {
                    g2d.color = color
                    g2d.fillRect(x * cellSize, y * cellSize, cellSize, cellSize)
                }
            }
        }

        // Siatka (opcjonalnie, można wyłączyć dla lepszego efektu 3D)
        for (x in 0..baseCols) {
            for (y in 0..baseRows) {
                g2d.color = Color(50, 50, 50) // Ciemniejsza siatka
                g2d.drawRect(x * cellSize, y * cellSize, cellSize, cellSize)
            }
        }

        g2d.color = Color.WHITE
        val crossSize = 5
        val halfWidth = (baseCols*cellSize)/2
        val halfHeight = (baseRows*cellSize)/2
        g2d.stroke = BasicStroke(2f)
        g2d.drawLine(halfWidth - crossSize, halfHeight, halfWidth + crossSize, halfHeight)
        g2d.drawLine(halfWidth, halfHeight - crossSize, halfWidth, halfHeight + crossSize)

        // Rysowanie FPS
        frames++
        if (System.currentTimeMillis() - lastFpsTime >= 1000) {
            fps = frames
            frames = 0
            lastFpsTime = System.currentTimeMillis()
        }
        g2d.font = fpsFont
        g2d.color = Color.YELLOW
        val fpsText = "$fps"
        val fm = g2d.fontMetrics
        g2d.drawString(fpsText, width - fm.stringWidth(fpsText) - 10, fm.ascent + 10)
    }

    private fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int) {
        var curX = x0
        var curY = y0

        val dx = Math.abs(x1 - x0)
        val dy = Math.abs(y1 - y0)

        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1

        var err = (if (dx > dy) dx else -dy) /2
        var e2: Int

        while (true) {
            if (curX in 0..baseCols && curY in 0..baseRows) {
                gridMap[curX][curY] = Color.RED
            }
            if (curX == x1 && curY == y1) break

            e2 = err
            if (e2 > -dx) { err -= dy; curX += sx }
            if (e2 < dy) { err += dx; curY += sy }
        }
    }

    // Logika poruszania się na podstawie wciśniętych klawiszy
    private fun processInput() {
        var speed = 0.8
        val rotSpeed = 0.1

        if (KeyEvent.VK_CONTROL in keys) speed *= 2

        var dx = 0.0
        var dz = 0.0

        if (KeyEvent.VK_W in keys) {
            dx += speed * sin(yaw)
            dz += speed * cos(yaw)
        }
        if (KeyEvent.VK_S in keys) {
            dx -= speed * sin(yaw)
            dz -= speed * cos(yaw)
        }
        if (KeyEvent.VK_A in keys) {
            dx -= speed * cos(yaw)
            dz += speed * sin(yaw)
        }
        if (KeyEvent.VK_D in keys) {
            dx += speed * cos(yaw)
            dz -= speed * sin(yaw)
        }

        if (KeyEvent.VK_LEFT in keys) yaw -= rotSpeed
        if (KeyEvent.VK_RIGHT in keys) yaw += rotSpeed
        if (KeyEvent.VK_UP in keys) pitch += rotSpeed
        if (KeyEvent.VK_DOWN in keys) pitch -= rotSpeed

        if (KeyEvent.VK_G in keys) println("camX: $camX, camY: $camY, camZ: $camZ, yaw: $yaw, pitch: $pitch, speed: $speed")

        if (debugFly) {
            // --- Tryb latania (Debug Fly) ---
            // Wyłączamy grawitację, zachowujemy stare sterowanie Y
            moveWithCollision(dx, 0.0, dz)

            var dy = 0.0
            if (KeyEvent.VK_SPACE in keys) dy += speed
            if (KeyEvent.VK_SHIFT in keys) dy -= speed

            // W trybie fly ignorujemy kolizje (proste przesuwanie)
            camX += dx
            camY += dy
            camZ += dz
            
            // Resetujemy prędkość fizyczną, żeby nie "wystrzelić" po wyłączeniu trybu
            velocityY = 0.0
        } else {
            // --- Tryb chodzenia (Grawitacja) ---
            
            // 1. Ruch poziomy z kolizjami (X i Z)
            moveWithCollision(dx, 0.0, dz)

            // 2. Skakanie (tylko gdy na ziemi)
            if (KeyEvent.VK_SPACE in keys && isOnGround) {
                velocityY = jumpStrength
                isOnGround = false
            }

            // 3. Grawitacja
            velocityY -= gravity
            // Ograniczenie prędkości spadania (terminal velocity)
            if (velocityY < -1.5) velocityY = -1.5

            // 4. Aplikowanie ruchu pionowego z fizyką
            val nextY = camY + velocityY
            if (checkCollision(camX, nextY, camZ)) {
                // Wykryto kolizję (podłoga lub sufit)
                
                // Przesuwamy się tak blisko przeszkody, jak to możliwe
                var tempDy = 0.0
                val sign = sign(velocityY)
                val step = 0.05 // Precyzja dociągania do podłogi
                
                // Prosta pętla dociągająca
                for (i in 0..20) { // Limit iteracji dla bezpieczeństwa
                    if (!checkCollision(camX, camY + tempDy + sign * step, camZ)) {
                        tempDy += sign * step
                    } else {
                        break
                    }
                }
                camY += tempDy

                // Jeśli spadaliśmy (velocityY < 0), to znaczy, że uderzyliśmy w podłogę
                if (velocityY < 0) {
                    isOnGround = true
                }
                velocityY = 0.0
            } else {
                camY += velocityY
                isOnGround = false
            }
        }
    }

    private fun moveWithCollision(dx: Double, dy: Double, dz: Double) {
        // --- Move on X axis ---
        if (dx != 0.0) {
            if (checkCollision(camX + dx, camY, camZ)) {
                // Collision detected. Find the maximum distance we can travel on this axis.
                var tempDx = 0.0
                val sign = dx.sign
                val numSteps = 10
                val step = abs(dx) / numSteps
                for (i in 1..numSteps) {
                    if (!checkCollision(camX + tempDx + (sign * step), camY, camZ)) {
                        tempDx += sign * step
                    } else {
                        break // Stop at the step before collision
                    }
                }
                camX += tempDx
            } else {
                camX += dx
            }
        }

        // --- Move on Z axis ---
        if (dz != 0.0) {
            if (checkCollision(camX, camY, camZ + dz)) {
                var tempDz = 0.0
                val sign = dz.sign
                val numSteps = 10
                val step = abs(dz) / numSteps
                for (i in 1..numSteps) {
                    if (!checkCollision(camX, camY, camZ + tempDz + (sign * step))) {
                        tempDz += sign * step
                    } else {
                        break
                    }
                }
                camZ += tempDz
            } else {
                camZ += dz
            }
        }

        // --- Move on Y axis ---
        if (dy != 0.0) {
            if (checkCollision(camX, camY + dy, camZ)) {
                var tempDy = 0.0
                val sign = dy.sign
                val numSteps = 10
                val step = abs(dy) / numSteps
                for (i in 1..numSteps) {
                    if (!checkCollision(camX, camY + tempDy + (sign * step), camZ)) {
                        tempDy += sign * step
                    } else {
                        break
                    }
                }
                camY += tempDy
            } else {
                camY += dy
            }
        }
    }

    private fun isPlayerInsideBlock(bx: Int, by: Int, bz: Int): Boolean {
        val minX = floor((camX - radiusCollision) / cubeSize + 0.5).toInt()
        val maxX = floor((camX + radiusCollision) / cubeSize + 0.5).toInt()

        val feetY = camY - cubeSize - playerHeight / 2
        val headY = camY + radiusCollision * cubeSize
        val minY = floor((feetY + 10.0) / cubeSize + 0.5).toInt()
        val maxY = floor((headY + 10.0) / cubeSize + 0.5).toInt()

        val minZ = floor((camZ - radiusCollision) / cubeSize + 0.5).toInt()
        val maxZ = floor((camZ + radiusCollision) / cubeSize + 0.5).toInt()

        return bx in minX..maxX && by in minY..maxY && bz in minZ..maxZ
    }

    private fun checkCollision(x: Double, y: Double, z: Double): Boolean {
        if (debugNoclip) return false
        // Przeliczamy bounding box gracza na współrzędne bloków
        val minX = floor((x - radiusCollision) / cubeSize + 0.5).toInt()
        val maxX = floor((x + radiusCollision) / cubeSize + 0.5).toInt()

        // Y w świecie jest przesunięte o -10.0 względem siatki bloków
        val feetY = y - cubeSize - playerHeight/2
        val headY = y + radiusCollision * cubeSize//  + ((playerHeight) / cubeSize)
        val minY = floor((feetY + 10.0) / cubeSize + 0.5).toInt()
        val maxY = floor((headY + 10.0) / cubeSize + 0.5).toInt()

        val minZ = floor((z - radiusCollision) / cubeSize + 0.5).toInt()
        val maxZ = floor((z + radiusCollision) / cubeSize + 0.5).toInt()

        // Sprawdzamy tylko bloki w obrębie bounding boxa gracza (optymalizacja)
        for (bx in minX..maxX) {
            for (by in minY..maxY) {
                for (bz in minZ..maxZ) {
                    if (getBlock(bx, by, bz) != null) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private inner class KeyboardListener : KeyAdapter() {
        override fun keyPressed(e: KeyEvent?) {
            e?.let { keys.add(it.keyCode) }
            
            if (e?.keyCode == KeyEvent.VK_ESCAPE) {
                isMouseCaptured = false
                cursor = Cursor.getDefaultCursor()
            }
            
            if (e?.keyCode == KeyEvent.VK_F) {
                debugFly = !debugFly
                println("DebugFly: $debugFly")
            }
            if (e?.keyCode == KeyEvent.VK_N) {
                debugNoclip = !debugNoclip
                println("debugNoclip: $debugNoclip")
            }
        }

        override fun keyReleased(e: KeyEvent?) {
            e?.let { keys.remove(it.keyCode) }
        }
    }

    private inner class GridMouseListener : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            if (!isMouseCaptured) {
                isMouseCaptured = true
                // Ukrywamy kursor
                val blankImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
                val blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(blankImage, Point(0, 0), "blank")
                cursor = blankCursor
            } else {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    raycastAction(false)
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    raycastAction(true)
                }
            }
        }
    }

    private inner class GridMouseMotionListener : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            if (isMouseCaptured && isShowing) {
                val centerX = width / 2
                val centerY = height / 2

                val dx = e.x - centerX
                val dy = e.y - centerY

                // Jeśli myszka jest na środku, nic nie rób (unikamy pętli zwrotnej od robota)
                if (dx == 0 && dy == 0) return

                val sensitivity = 0.003
                yaw += dx * sensitivity
                pitch -= dy * sensitivity // Odwracamy oś Y dla naturalnego sterowania

                // Centrujemy myszkę z powrotem
                val loc = locationOnScreen
                robot?.mouseMove(loc.x + centerX, loc.y + centerY)
            }
        }
    }

    // Prosta implementacja Perlin Noise
    class PerlinNoise(seed: Int) {
        private val p = IntArray(512)
        init {
            val random = Random(seed.toLong())
            val permutation = (0..255).toMutableList()
            permutation.shuffle(random)
            for (i in 0..255) {
                p[i] = permutation[i]
                p[i + 256] = permutation[i]
            }
        }

        fun noise(x: Double, y: Double): Double {
            // Poprawka: używamy floor() aby poprawnie obsługiwać ujemne współrzędne
            val xi = floor(x).toInt()
            val yi = floor(y).toInt()

            val X = xi and 255
            val Y = yi and 255

            val xf = x - xi
            val yf = y - yi

            val u = fade(xf)
            val v = fade(yf)
            val aa = p[p[X] + Y]; val ab = p[p[X] + Y + 1]
            val ba = p[p[X + 1] + Y]; val bb = p[p[X + 1] + Y + 1]
            return lerp(v, lerp(u, grad(p[aa], xf, yf), grad(p[ba], xf - 1, yf)),
                           lerp(u, grad(p[ab], xf, yf - 1), grad(p[bb], xf - 1, yf - 1)))
        }
        private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
        private fun lerp(t: Double, a: Double, b: Double) = a + t * (b - a)
        private fun grad(hash: Int, x: Double, y: Double) = if (hash and 1 == 0) x else -x + if (hash and 2 == 0) y else -y
    }
}

fun main() {
    SwingUtilities.invokeLater {
        val frame = JFrame("gridMap")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.isResizable = false
        frame.add(gridMap())
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}