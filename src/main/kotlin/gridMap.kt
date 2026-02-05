package org.lewapnoob.gridMap

import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.Arrays
import java.util.Collections
import javax.swing.*
import java.io.*
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

    // Kamera
    private val fov = 90.0
    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0
    private var yaw = 0.0
    private var pitch = 0.0
        set(value) {
            field = value.coerceIn(-Math.PI / 2, Math.PI / 2)
        }
    private var renderDistance = 5
    private val radiusChunkRender = 1
    private val speed = 0.3
    private val rotationalSpeed = 0.1
    private val sensitivity = 0.003

    private var debugNoclip = false
    private var debugFly = false
    private var showChunkBorders = false
    private var velocityY = 0.0
    private var isOnGround = false
    private val gravity = 0.1
    private val jumpStrength = 0.8

    private val imageWidth = baseCols + 1
    private val displayImage = BufferedImage(imageWidth, baseRows + 1, BufferedImage.TYPE_INT_RGB)
    private val pixels = (displayImage.raster.dataBuffer as DataBufferInt).data
    private val backBuffer = IntArray(pixels.size)
    private val zBuffer = Array(baseRows + 1) { DoubleArray(baseCols + 1) { Double.MAX_VALUE } }

    // Odległość płaszczyzny przycinającej (near plane)
    private val nearPlaneZ = 0.1

    // --- 3D Structures ---
    data class Vector3d(var x: Double, var y: Double, var z: Double, var ao: Double = 1.0)
    private fun Vector3d.copy(): Vector3d = Vector3d(x, y, z, ao)
    data class BlockPos(val x: Int, val y: Int, val z: Int)

    data class Triangle3d(
        val p1: Vector3d, val p2: Vector3d, val p3: Vector3d, val color: Color
    )

    // Scena 3D
    private val BlockAir = 0
    private val chunkMeshes = mutableMapOf<Point, Array<MutableList<Triangle3d>>>()
    // Mapa przechowująca maskę bitową okluzji dla każdego segmentu (index 0-63)
    // Bity: 1=West(X-), 2=East(X+), 4=Down(Y-), 8=Up(Y+), 16=North(Z-), 32=South(Z+)
    private val chunkOcclusion = mutableMapOf<Point, ByteArray>()
    
    private val FACE_WEST = 1
    private val FACE_EAST = 2
    private val FACE_DOWN = 4
    private val FACE_UP = 8
    private val FACE_NORTH = 16
    private val FACE_SOUTH = 32
    private val SEGMENT_FULL = 63 // Wszystkie 6 ścian

    private val trianglesToRaster = mutableListOf<Triangle3d>()

    // Cache widoczności (BFS)
    private val cachedVisibleSegments = java.util.ArrayList<Triple<Int, Int, Int>>()
    private var lastCamSecX = Int.MIN_VALUE
    private var lastCamSecY = Int.MIN_VALUE
    private var lastCamSecZ = Int.MIN_VALUE
    private var visibilityGraphDirty = true

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
        val height = 128
        val depth = 16
        // Zmiana: Płaska tablica intów zamiast tablicy obiektów Color.
        // 0 oznacza brak bloku (powietrze), inne wartości to ARGB koloru.
        val blocks = IntArray(width * height * depth)
        var modified = false

        fun getIndex(x: Int, y: Int, z: Int): Int = x + width * (z + depth * y)
        fun setBlock(x: Int, y: Int, z: Int, color: Int) {
            blocks[getIndex(x, y, z)] = color
            modified = true
        }
        fun getBlock(x: Int, y: Int, z: Int): Int = blocks[getIndex(x, y, z)]
    }

    private val chunks = mutableMapOf<Point, Chunk>()
    private var seed = 6767
    private lateinit var caveNoise: PerlinNoise
    private lateinit var noise: PerlinNoise
    private var lastChunkX = Int.MAX_VALUE
    private var lastChunkZ = Int.MAX_VALUE

    // System zapisu
    private val saveDir = File("saves/world1").apply { mkdirs() }
    private var lastAutoSaveTime = System.currentTimeMillis()

    // Zbiór aktualnie wciśniętych klawiszy
    private val keys = Collections.synchronizedSet(mutableSetOf<Int>())

    // Obsługa myszki
    private var isMouseCaptured = false
    private var isLeftMouseDown = false
    private var isRightMouseDown = false
    private var lastActionTime = 0L
    private val actionDelay = 150 // Opóźnienie w ms (szybkość niszczenia)
    private val robot = try { Robot() } catch (e: AWTException) { null }
    private var windowPos = Point(0, 0)
    @Volatile private var running = true

    // Cache dla obliczeń trygonometrycznych
    private var cosYaw = 0.0
    private var sinYaw = 0.0
    private var cosPitch = 0.0
    private var sinPitch = 0.0

    data class ModelVoxel(val x: Int, val y: Int, val z: Int, val color: Color, val isVoid: Boolean = false)

    private val treeModel = treeModelData
    private val DungeonModel = DungeonModelData
    private val AirModel = AirModelData
    private val IglooModel = IglooModelData

    init {
        preferredSize = Dimension((baseCols + 1) * cellSize, (baseRows + 1) * cellSize)
        setBackground(Color(113, 144, 225))
        addKeyListener(KeyboardListener())
        addMouseListener(GridMouseListener())
        addMouseMotionListener(GridMouseMotionListener())
        isFocusable = true

        addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent?) {
                if (isShowing) windowPos = locationOnScreen
            }
        })

        noise = PerlinNoise(seed)
        caveNoise = PerlinNoise(seed + 1) // Osobny seed dla jaskiń

        // Ustawiamy gracza na powierzchni (pobieramy wysokość terenu w punkcie 0,0)
        val spawnH = getTerrainHeight(0, 0)
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

        // Zmiana: Usuwanie starych chunków i ich meshy (Garbage Collection logiczny)
        val safeZone = renderDistance + 2
        val toRemove = chunks.keys.filter {
            abs(it.x - currentChunkX) > safeZone || abs(it.y - currentChunkZ) > safeZone
        }
        toRemove.forEach {
            // Jeśli chunk był modyfikowany przez gracza, zapisz go na dysk przed usunięciem z RAM
            val chunk = chunks[it]
            if (chunk != null && chunk.modified) saveChunkToDisk(chunk)

            chunks.remove(it)
            chunkMeshes.remove(it)
            chunkOcclusion.remove(it)
        }
    }

    private fun getNeighborChunks(cx: Int, cz: Int): List<Point> {
        return listOf(Point(cx + 1, cz), Point(cx - 1, cz), Point(cx, cz + 1), Point(cx, cz - 1))
    }

    private fun generateChunk(cx: Int, cz: Int) {
        // 0. Próba wczytania z dysku
        val loadedChunk = loadChunkFromDisk(cx, cz)
        if (loadedChunk != null) {
            chunks[Point(cx, cz)] = loadedChunk
            return
        }

        val chunk = Chunk(cx, cz)
        chunks[Point(cx, cz)] = chunk

        // 1. Generowanie terenu i jaskiń (Uniwersalna metoda)
        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                // Optymalizacja: Pobieramy wysokość raz dla kolumny, aby ograniczyć pętlę Y
                val wx = cx * 16 + lx
                val wz = cz * 16 + lz
                val h = getTerrainHeight(wx, wz)
                
                for (y in 0..127) {
                    val wx = cx * 16 + lx
                    val wz = cz * 16 + lz
                    
                    // Używamy tej samej funkcji co struktury - spójność 100%
                    val blockColor = computeWorldBlock(wx, y, wz, h)
                    if (blockColor != BlockAir) {
                        chunk.setBlock(lx, y, lz, blockColor)
                    }
                }
            }
        }

        // 3. Generowanie rud
        generateOres(chunk, cx, cz)

        // 4. Generowanie struktur
        // Drzewa: density=0.004, minH=0, maxH=128, target=Grass, offset=1
        generateStructureType(chunk, cx, cz, treeModel, 0.004, 50, 128, Color(0x59A608).rgb, 1)
        generateStructureType(chunk, cx, cz, AirModel, 0.001, 80, 128, BlockAir, 1, true, listOf(0, 90, 180, 270))
        generateStructureType(chunk, cx, cz, DungeonModel, 0.001, 0, 30, Color(0x8EA3A1).rgb, 1, true, listOf(0, 90, 180, 270))
        generateStructureType(chunk, cx, cz, IglooModel, 0.00001, 50, 80, Color(0x59A608).rgb, 0, false, listOf(0, 90, 180, 270))

        // Resetujemy flagę modified, bo to jest stan początkowy (naturalny)
        chunk.modified = false
    }

    private fun generateOres(chunk: Chunk, cx: Int, cz: Int) {
        val rand = Random((cx * 341873128712L + cz * 132897987541L + seed).hashCode().toLong())
        val targetBlock = Color(0x8EA3A1).rgb

        // Ruda 1: #151716, 1-10 bloków, Y: 20-64, 0-32 żył
        generateOreType(chunk, rand, targetBlock, Color(0x151716).rgb, 1, 10, 20, 64, 32)

        // Ruda 2: #605f60, 1-5 bloków, Y: 1-50, 0-13 żył
        generateOreType(chunk, rand, targetBlock, Color(0xe3c0aa).rgb, 1, 5, 1, 50, 13)

        // Ruda 3: #30ddeb, 1-4 bloków, Y: 1-16, 0-5 żył
        generateOreType(chunk, rand, targetBlock, Color(0x30ddeb).rgb, 1, 4, 1, 16, 5)
    }

    private fun generateOreType(chunk: Chunk, rand: Random, target: Int, color: Int, minSize: Int, maxSize: Int, minY: Int, maxY: Int, maxVeins: Int) {
        val veinsCount = rand.nextInt(maxVeins + 1)
        for (i in 0 until veinsCount) {
            val startX = rand.nextInt(16)
            val startZ = rand.nextInt(16)
            val startY = minY + rand.nextInt(maxY - minY + 1)

            if (chunk.getBlock(startX, startY, startZ) == target) {
                val size = minSize + rand.nextInt(maxSize - minSize + 1)
                placeOreVein(chunk, rand, startX, startY, startZ, size, target, color, minY, maxY)
            }
        }
    }

    private fun placeOreVein(chunk: Chunk, rand: Random, x: Int, y: Int, z: Int, size: Int, target: Int, color: Int, minY: Int, maxY: Int) {
        val vein = java.util.ArrayList<BlockPos>()
        vein.add(BlockPos(x, y, z))
        chunk.setBlock(x, y, z, color)

        var currentSize = 1
        var attempts = 0
        while (currentSize < size && attempts < size * 4) {
            attempts++
            val source = vein[rand.nextInt(vein.size)]
            
            val dir = rand.nextInt(6)
            var dx = 0; var dy = 0; var dz = 0
            when(dir) {
                0 -> dx = 1; 1 -> dx = -1
                2 -> dy = 1; 3 -> dy = -1
                4 -> dz = 1; 5 -> dz = -1
            }

            val nx = source.x + dx
            val ny = source.y + dy
            val nz = source.z + dz

            if (nx in 0..15 && nz in 0..15 && ny in minY..maxY) {
                if (chunk.getBlock(nx, ny, nz) == target) {
                    chunk.setBlock(nx, ny, nz, color)
                    vein.add(BlockPos(nx, ny, nz))
                    currentSize++
                }
            }
        }
    }

    private fun saveChunkToDisk(chunk: Chunk) {
        try {
            val file = File(saveDir, "c_${chunk.x}_${chunk.z}.dat")
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { dos ->
                // 1. Liczymy ile bloków faktycznie istnieje (nie jest powietrzem/zerem)
                val blockCount = chunk.blocks.count { it != 0 }

                // 2. Zapisujemy tę ilość na początku pliku
                dos.writeInt(blockCount)

                // 3. Zapisujemy tylko istniejące bloki: Index (Short) + Kolor (Int)
                for (i in chunk.blocks.indices) {
                    if (chunk.blocks[i] != 0) {
                        dos.writeShort(i)
                        dos.writeInt(chunk.blocks[i])
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun loadChunkFromDisk(cx: Int, cz: Int): Chunk? {
        val file = File(saveDir, "c_${cx}_${cz}.dat")
        if (!file.exists()) return null

        return try {
            val chunk = Chunk(cx, cz)
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { dis ->
                // 1. Odczytujemy ilość bloków do wczytania
                val count = dis.readInt()

                // 2. Wczytujemy listę i wstawiamy bloki w odpowiednie miejsca
                for (i in 0 until count) {
                    val index = dis.readShort().toInt() and 0xFFFF // and 0xFFFF dla bezpieczeństwa znaku
                    val color = dis.readInt()
                    if (index in chunk.blocks.indices) {
                        chunk.blocks[index] = color
                    }
                }
            }
            chunk.modified = false // Po wczytaniu uznajemy, że to jest stan bazowy
            chunk
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun getTerrainHeight(wx: Int, wz: Int): Int {
        val n = noise.noise(wx * 0.02, wz * 0.02)
        return (58 + n * 6).toInt().coerceIn(0, 127) // Zmieniono bazową wysokość i zakres szumu, oraz górną granicę
    }

    // --- UNIWERSALNA LOGIKA GENEROWANIA BLOKU ---
    // To jest jedyne miejsce definiujące wygląd świata (poza rudami).
    // Struktury używają tego do "patrzenia" w sąsiednie chunki.
    private fun computeWorldBlock(wx: Int, wy: Int, wz: Int, precalcHeight: Int? = null): Int {
        if (wy < 0) return BlockAir
        if (wy == 0) return Color.BLACK.rgb // Bedrock

        val h = precalcHeight ?: getTerrainHeight(wx, wz)
        if (wy > h) return BlockAir // Powietrze nad terenem

        // 1. Logika Biomów / Warstw (Tu zmieniasz kolory trawy, ziemi itp.)
        val baseColor = when {
            wy == h -> Color(0x59A608).rgb
            wy > h - 4 -> Color(0x6c3c0c).rgb
            else -> Color(0x8EA3A1).rgb
        }

        // 2. Logika Jaskiń (Wycinanie w terenie)
        val baseCaveThreshold = 0.65
        val surfaceOpeningResistance = 0.2
        val frequency = 0.07
        val noiseVal = caveNoise.noise(wx * frequency, wy * frequency * 2, wz * frequency)

        val depth = h - wy
        val threshold = if (depth < 5) {
            baseCaveThreshold + surfaceOpeningResistance * (1.0 - depth / 5.0)
        } else {
            baseCaveThreshold
        }

        if (noiseVal > threshold) return BlockAir // Jaskinia (powietrze)
        
        return baseColor
    }

    private fun generateStructureType(chunk: Chunk, cx: Int, cz: Int, model: List<ModelVoxel>, density: Double, minH: Int, maxH: Int, targetBlock: Int, yOffset: Int, clearSpace: Boolean = false, allowedRotations: List<Int> = listOf(0)) {
        val margin = 10 // Margines dla struktur wychodzących poza chunk (np. korona drzewa)
        
        // Cache dla obróconych modeli, aby nie tworzyć nowych obiektów dla każdego drzewa
        val modelCache = mutableMapOf<Int, List<ModelVoxel>>()

        for (lx in -margin..16 + margin) {
            for (lz in -margin..16 + margin) {
                val wx = cx * 16 + lx
                val wz = cz * 16 + lz

                // Unikalny seed dla typu struktury (bazując na hashcode modelu)
                if (isStructureAt(wx, wz, density, model.hashCode())) {
                        // Zbieramy wszystkie pasujące wysokości w kolumnie
                        val validYs = mutableListOf<Int>()

                        val startY = maxOf(minH, 0)
                        val endY = minOf(maxH, 127)
                        
                        for (y in startY..endY) {
                            // Sprawdzamy blok na dole i blok powyżej (czy jest miejsce)
                            // computeWorldBlock zwraca ID bloku matematycznie, niezależnie od chunka
                            if (computeWorldBlock(wx, y, wz) == targetBlock && computeWorldBlock(wx, y + 1, wz) == BlockAir) {
                                validYs.add(y)
                            }
                        }
                        
                        if (validYs.isNotEmpty()) {
                            // Wybieramy losową wysokość z dostępnych (deterministycznie)
                            val hash = (wx * 73856093 xor wz * 19349663 xor seed xor model.hashCode()).toString().hashCode()
                            val random = Random(hash.toLong())
                            random.nextDouble() // Przesuwamy stan RNG (to samo wywołanie co w isStructureAt)
                            
                            val selectedY = validYs[random.nextInt(validYs.size)]

                            // Zabezpieczenie przed pustą listą rotacji
                            val rotation = if (allowedRotations.isNotEmpty()) allowedRotations[random.nextInt(allowedRotations.size)] else 0
                            
                            // Pobieramy z cache lub obliczamy i zapisujemy
                            val finalModel = modelCache.getOrPut(rotation) { rotateModel(model, rotation) }

                            placeStructure(chunk, lx, selectedY + yOffset, lz, finalModel, clearSpace)
                        }
                }
            }
        }
    }

    private fun rotateModel(model: List<ModelVoxel>, angle: Int): List<ModelVoxel> {
        var normAngle = angle % 360
        if (normAngle < 0) normAngle += 360
        val steps = (normAngle / 90) % 4

        if (steps == 0) return model

        return model.map { voxel ->
            var x = voxel.x
            var z = voxel.z
            repeat(steps) {
                val oldX = x
                val oldZ = z
                x = -oldZ
                z = oldX
            }
            ModelVoxel(x, voxel.y, z, voxel.color, voxel.isVoid)
        }
    }

    private fun isStructureAt(wx: Int, wz: Int, density: Double, salt: Int): Boolean {
        val hash = (wx * 73856093 xor wz * 19349663 xor seed xor salt).toString().hashCode()
        val random = Random(hash.toLong())
        return random.nextDouble() < density
    }

    private fun placeStructure(chunk: Chunk, rootLx: Int, rootY: Int, rootLz: Int, model: List<ModelVoxel>, clearSpace: Boolean) {
        if (clearSpace && model.isNotEmpty()) {
            // Tryb czyszczenia: obliczamy granice modelu i iterujemy po całym prostopadłościanie
            val minX = model.minOf { it.x }
            val maxX = model.maxOf { it.x }
            val minY = model.minOf { it.y }
            val maxY = model.maxOf { it.y }
            val minZ = model.minOf { it.z }
            val maxZ = model.maxOf { it.z }

            // Mapa voxeli dla szybkiego sprawdzania co jest w modelu
            val voxelMap = model.associate { Triple(it.x, it.y, it.z) to it }

            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        val tx = rootLx + x
                        val ty = rootY + y
                        val tz = rootLz + z

                        if (tx in 0 until 16 && tz in 0 until 16 && ty in 0 until 128) {
                            // Jeśli voxel jest w modelu -> jego kolor, jeśli nie -> powietrze (0)
                            val voxel = voxelMap[Triple(x, y, z)]
                            if (voxel != null) {
                                if (!voxel.isVoid) {
                                    chunk.setBlock(tx, ty, tz, voxel.color.rgb)
                                }
                            } else {
                                chunk.setBlock(tx, ty, tz, 0)
                            }
                        }
                    }
                }
            }
        } else {
            // Tryb standardowy: stawiamy tylko bloki z modelu
            for (voxel in model) {
                val tx = rootLx + voxel.x
                val ty = rootY + voxel.y
                val tz = rootLz + voxel.z

                if (tx in 0 until 16 && tz in 0 until 16 && ty in 0 until 128) {
                    if (voxel.isVoid) {
                        chunk.setBlock(tx, ty, tz, 0) // Blok próżni zamienia się w powietrze (blokuje teren)
                    } else {
                        chunk.setBlock(tx, ty, tz, voxel.color.rgb)
                    }
                }
            }
        }
    }

    private fun setBlock(x: Int, y: Int, z: Int, color: Color?) {
        if (y < 0 || y >= 128) return // Zmieniono górną granicę wysokości
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1

        val chunk = chunks[Point(cx, cz)] ?: return

        var lx = x % 16
        if (lx < 0) lx += 16
        var lz = z % 16
        if (lz < 0) lz += 16

        chunk.setBlock(lx, y, lz, color?.rgb ?: 0)
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
                var updateX = x
                var updateZ = z

                if (place) {
                    if (!isPlayerInsideBlock(lastX, lastY, lastZ)) {
                        setBlock(lastX, lastY, lastZ, Color(0x6c3c0c)) // Kolor ziemi
                        updateX = lastX
                        updateZ = lastZ
                    } else {
                        return
                    }
                } else {
                    setBlock(x, y, z, null)
                }

                // Aktualizujemy tylko chunk, w którym zaszła zmiana
                val cx = if (updateX >= 0) updateX / 16 else (updateX + 1) / 16 - 1
                val cz = if (updateZ >= 0) updateZ / 16 else (updateZ + 1) / 16 - 1
                updateChunkMesh(cx, cz)

                // Jeśli blok jest na krawędzi chunka, aktualizujemy też sąsiada
                val lx = updateX - cx * 16
                val lz = updateZ - cz * 16
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
        if (y < 0 || y >= 128) return null // Zmieniono górną granicę wysokości
        // Obliczamy ID chunka
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1

        val chunk = chunks[Point(cx, cz)] ?: return null

        // Lokalne współrzędne w chunku
        var lx = x % 16
        if (lx < 0) lx += 16
        var lz = z % 16
        if (lz < 0) lz += 16

        val colorInt = chunk.getBlock(lx, y, lz)
        // Jeśli 0 to null (powietrze), w przeciwnym razie odtwarzamy obiekt Color
        return if (colorInt == 0) null else Color(colorInt)
    }

    // Generuje mesh tylko dla jednego chunka
    private fun updateChunkMesh(cx: Int, cz: Int) {
        val chunk = chunks[Point(cx, cz)] ?: return
        // 8x8x8 = 2 sekcje X * 16 sekcji Y * 2 sekcje Z = 64 sekcje
        val sections = Array(64) { mutableListOf<Triangle3d>() }
        val blockCounts = IntArray(64) // Licznik bloków w każdym segmencie

        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                for (y in 0 until 128) { // Zmieniono zakres wysokości dla generowania mesha
                    val colorInt = chunk.getBlock(lx, y, lz)

                    // Obliczamy indeks sekcji 8x8x8
                    val secX = lx / 8
                    val secY = (y / 8).coerceIn(0, 15)
                    val secZ = lz / 8
                    val index = secY * 4 + secZ * 2 + secX // Flattened index (Y * (2*2) + Z * 2 + X)

                    if (colorInt != 0) {
                        blockCounts[index]++
                    } else {
                        continue
                    }

                    // Globalne pozycje logiczne
                    val wx = cx * 16 + lx
                    val wz = cz * 16 + lz

                    // Pozycja w świecie 3D
                    val xPos = wx * cubeSize
                    val yPos = y * cubeSize - 10.0
                    val zPos = wz * cubeSize

                    val color = Color(colorInt)
                    val targetList = sections[index]

                    // Sprawdzamy sąsiadów
                    if (getBlock(wx, y, wz - 1) == null) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 0, color)
                    if (getBlock(wx, y, wz + 1) == null) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 1, color)
                    if (getBlock(wx - 1, y, wz) == null) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 2, color)
                    if (getBlock(wx + 1, y, wz) == null) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 3, color)
                    if (getBlock(wx, y + 1, wz) == null) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 4, color)
                    if (getBlock(wx, y - 1, wz) == null) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 5, color)
                }
            }
        }
        chunkMeshes[Point(cx, cz)] = sections
        // Obliczamy maskę okluzji dla każdego segmentu (czy ściany są pełne)
        chunkOcclusion[Point(cx, cz)] = calculateOcclusionMasks(chunk, blockCounts)
        visibilityGraphDirty = true
    }

    private fun calculateOcclusionMasks(chunk: Chunk, blockCounts: IntArray): ByteArray {
        val occlusion = ByteArray(64)

        for (i in 0 until 64) {
            // Jeśli segment jest pusty, na pewno nie zasłania nic
            if (blockCounts[i] == 0) continue
            // Jeśli segment jest pełny (512), to wszystkie ściany są pełne
            if (blockCounts[i] == 512) {
                occlusion[i] = SEGMENT_FULL.toByte()
                continue
            }

            // Dekodujemy pozycję sekcji
            val secY = i / 4
            val rem = i % 4
            val secZ = rem / 2
            val secX = rem % 2

            val baseX = secX * 8
            val baseY = secY * 8
            val baseZ = secZ * 8

            var mask = 0

            // Sprawdzamy 6 ścian segmentu 8x8x8
            // 1. West (X=0)
            var solid = true
            for (y in 0..7) for (z in 0..7) if (chunk.getBlock(baseX, baseY + y, baseZ + z) == 0) { solid = false; break }
            if (solid) mask = mask or FACE_WEST

            // 2. East (X=7)
            solid = true
            for (y in 0..7) for (z in 0..7) if (chunk.getBlock(baseX + 7, baseY + y, baseZ + z) == 0) { solid = false; break }
            if (solid) mask = mask or FACE_EAST

            // 3. Down (Y=0)
            solid = true
            for (x in 0..7) for (z in 0..7) if (chunk.getBlock(baseX + x, baseY, baseZ + z) == 0) { solid = false; break }
            if (solid) mask = mask or FACE_DOWN

            // 4. Up (Y=7)
            solid = true
            for (x in 0..7) for (z in 0..7) if (chunk.getBlock(baseX + x, baseY + 7, baseZ + z) == 0) { solid = false; break }
            if (solid) mask = mask or FACE_UP

            // 5. North (Z=0)
            solid = true
            for (x in 0..7) for (y in 0..7) if (chunk.getBlock(baseX + x, baseY + y, baseZ) == 0) { solid = false; break }
            if (solid) mask = mask or FACE_NORTH

            // 6. South (Z=7)
            solid = true
            for (x in 0..7) for (y in 0..7) if (chunk.getBlock(baseX + x, baseY + y, baseZ + 7) == 0) { solid = false; break }
            if (solid) mask = mask or FACE_SOUTH

            occlusion[i] = mask.toByte()
        }
        return occlusion
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

    // Czyści ekran
    private fun clearGrid() {
        val bgColor = Color(113, 144, 225).rgb
        Arrays.fill(backBuffer, bgColor)
        for (row in zBuffer) {
            Arrays.fill(row, Double.MAX_VALUE)
        }
    }

    fun loop() {
        Thread {
            var lastTime = System.nanoTime()
            val nsPerTick = 1000000000.0 / 30.0 // TPS (Ticki fizyki na sekundę)
            var delta = 0.0

            while (running) {
                val now = System.nanoTime()
                delta += (now - lastTime) / nsPerTick
                lastTime = now

                // --- AUTO SAVE (Co 15 sekund) ---
                if (System.currentTimeMillis() - lastAutoSaveTime > 15000) {
                    var savedCount = 0
                    chunks.values.forEach { chunk ->
                        if (chunk.modified) {
                            saveChunkToDisk(chunk)
                            chunk.modified = false // Resetujemy flagę, bo na dysku jest już aktualna wersja
                            savedCount++
                        }
                    }
                    if (savedCount > 0) println("Auto-saved $savedCount chunks.")
                    lastAutoSaveTime = System.currentTimeMillis()
                }

                if (delta > 10) delta = 10.0 // Zabezpieczenie przed "spiralą śmierci" przy dużym lagu

                while (delta >= 1) {
                    processInput()
                    updateWorld()
                    delta--
                }

                clearGrid()
                render3D()

                // Kopiujemy bufor renderowania do bufora wyświetlania (Double Buffering)
                System.arraycopy(backBuffer, 0, pixels, 0, pixels.size)
                repaint()
            }
        }.start()
    }

    private fun rebuildVisibilityGraph(startSecX: Int, startSecY: Int, startSecZ: Int) {
        cachedVisibleSegments.clear()

        val queue = java.util.ArrayDeque<Triple<Int, Int, Int>>()
        val visited = java.util.HashSet<Triple<Int, Int, Int>>()

        val startNode = Triple(startSecX, startSecY, startSecZ)
        queue.add(startNode)
        visited.add(startNode)

        val maxRadius = renderDistance * 2
        // Kierunki i odpowiadające im bity ścian:
        // Triple(dx, dy, dz), ExitBit (z obecnego), EntryBit (do sąsiada)
        val dirs = arrayOf(
            Triple(Triple(0, 0, -1), FACE_NORTH, FACE_SOUTH), // Z-
            Triple(Triple(0, 0, 1), FACE_SOUTH, FACE_NORTH),  // Z+
            Triple(Triple(-1, 0, 0), FACE_WEST, FACE_EAST),   // X-
            Triple(Triple(1, 0, 0), FACE_EAST, FACE_WEST),    // X+
            Triple(Triple(0, 1, 0), FACE_UP, FACE_DOWN),      // Y+
            Triple(Triple(0, -1, 0), FACE_DOWN, FACE_UP)      // Y-
        )

        while (!queue.isEmpty()) {
            val current = queue.poll()
            val (currX, currY, currZ) = current

            // Optymalizacja: Dodajemy do listy renderowania TYLKO jeśli segment ma geometrię.
            // Puste segmenty (powietrze) są odwiedzane przez BFS, ale nie muszą być rysowane.
            if (!isSegmentEmptyBySec(currX, currY, currZ)) {
                cachedVisibleSegments.add(current)
            }

            // Pobieramy maskę okluzji obecnego segmentu
            val currMask = getSegmentOcclusionMask(currX, currY, currZ)

            for ((dir, exitBit, entryBit) in dirs) {
                val nx = currX + dir.first
                val ny = currY + dir.second
                val nz = currZ + dir.third

                if (ny < 0 || ny > 15) continue
                if (abs(nx - startSecX) > maxRadius || abs(nz - startSecZ) > maxRadius) continue

                // 1. Sprawdzamy czy możemy wyjść z obecnego segmentu w tym kierunku
                // Jeśli ściana wyjściowa jest pełna, a my nie jesteśmy wewnątrz niej (kamera), to nie widzimy przez nią.
                // Wyjątek: Jesteśmy w segmencie startowym (kamera może być wewnątrz bloku/ściany).
                if ((currMask.toInt() and exitBit) != 0 && (currX != startSecX || currY != startSecY || currZ != startSecZ)) {
                    continue
                }

                // 2. Sprawdzamy czy możemy wejść do sąsiada
                val neighborMask = getSegmentOcclusionMask(nx, ny, nz)
                // Jeśli ściana wejściowa sąsiada jest pełna -> Widzimy tę ścianę (dodajemy do kolejki/listy),
                // ale NIE widzimy nic za nią (nie propagujemy dalej BFS w tym kierunku).
                // Ale musimy dodać sąsiada do visited/queue, żeby został narysowany.
                // W BFS "queue" służy do propagacji. Jeśli tu zablokujemy, to sąsiad zostanie dodany,
                // ale w następnej iteracji pętli while, "currMask" (którym będzie ten sąsiad) zablokuje wyjście (krok 1 powyżej).
                // Więc po prostu dodajemy do kolejki. Logika "ExitBit" w następnym kroku załatwi sprawę zatrzymania.

                val neighbor = Triple(nx, ny, nz)
                if (visited.add(neighbor)) {
                    queue.add(neighbor)
                }
            }
        }
    }

    private fun render3D() {
        trianglesToRaster.clear()

        // Obliczamy sin/cos raz na klatkę, zamiast dla każdego wierzchołka!
        cosYaw = cos(yaw)
        sinYaw = sin(yaw)
        cosPitch = cos(pitch)
        sinPitch = sin(pitch)

        val segmentSize = 8 * cubeSize
        // Pozycja kamery w koordynatach segmentów
        val camSecX = floor(camX / segmentSize).toInt()
        val camSecY = floor((camY + 10.0) / segmentSize).toInt()
        val camSecZ = floor(camZ / segmentSize).toInt()

        // Aktualizacja grafu widoczności tylko gdy zmienimy segment lub świat się zmieni
        if (visibilityGraphDirty || camSecX != lastCamSecX || camSecY != lastCamSecY || camSecZ != lastCamSecZ) {
            rebuildVisibilityGraph(camSecX, camSecY, camSecZ)
            lastCamSecX = camSecX
            lastCamSecY = camSecY
            lastCamSecZ = camSecZ
            visibilityGraphDirty = false
        }

        // Iterujemy po wcześniej obliczonych widocznych segmentach
        for (i in cachedVisibleSegments.indices) {
            val (currX, currY, currZ) = cachedVisibleSegments[i]

            // Konwersja globalnych współrzędnych segmentu na Chunk + Local Segment
            val cx = if (currX >= 0) currX / 2 else (currX + 1) / 2 - 1
            val cz = if (currZ >= 0) currZ / 2 else (currZ + 1) / 2 - 1
            val secX = abs(currX % 2)
            val secY = currY
            val secZ = abs(currZ % 2)

            val mesh = chunkMeshes[Point(cx, cz)]
            if (mesh != null) {
                val index = secY * 4 + secZ * 2 + secX
                if (index in 0 until 64) {
                    val sectionTriangles = mesh[index]
                    if (sectionTriangles.isNotEmpty()) {
                        // Sprawdzamy Frustum Culling (czy segment jest w kadrze kamery)
                        if (isSectionVisible(cx, secX, secY, secZ, cz)) {
                            for (tri in sectionTriangles) {
                                val t1 = transform(tri.p1)
                                val t2 = transform(tri.p2)
                                val t3 = transform(tri.p3)

                                val line1 = Vector3d(t2.x - t1.x, t2.y - t1.y, t2.z - t1.z)
                                val line2 = Vector3d(t3.x - t1.x, t3.y - t1.y, t3.z - t1.z)

                                val normal = Vector3d(
                                    line1.y * line2.z - line1.z * line2.y,
                                    line1.z * line2.x - line1.x * line2.z,
                                    line1.x * line2.y - line1.y * line2.x
                                )

                                if (t1.x * normal.x + t1.y * normal.y + t1.z * normal.z > 0) {
                                    trianglesToRaster.add(Triangle3d(t1, t2, t3, tri.color))
                                }
                            }
                        }
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

        if (showChunkBorders) {
            renderChunkBorders()
        }

        val target = getTargetBlock()
        if (target != null) {
            drawSelectionBox(target)
        }
    }

    private fun checkPointOcclusion(px: Double, py: Double, pz: Double, cx: Double, cy: Double, cz: Double, targetSecX: Int, targetSecY: Int, targetSecZ: Int): Boolean {
        val vx = px - cx; val vy = py - cy; val vz = pz - cz
        val dist = sqrt(vx * vx + vy * vy + vz * vz)
        return fastSegmentRaycast(cx, cy, cz, vx / dist, vy / dist, vz / dist, dist, targetSecX, targetSecY, targetSecZ)
    }

    // Szybki Raycast sprawdzający tylko czy trafiliśmy w jakikolwiek blok na drodze
    private fun fastRaycastHit(startX: Double, startY: Double, startZ: Double, dirX: Double, dirY: Double, dirZ: Double, maxDist: Double): Boolean {
        // Pozycja startowa w przestrzeni voxeli
        var x = floor(startX / cubeSize + 0.5).toInt()
        var y = floor((startY + 10.0) / cubeSize + 0.5).toInt()
        var z = floor(startZ / cubeSize + 0.5).toInt()

        val stepX = if (dirX > 0) 1 else -1
        val stepY = if (dirY > 0) 1 else -1
        val stepZ = if (dirZ > 0) 1 else -1

        val tDeltaX = if (dirX == 0.0) Double.MAX_VALUE else abs(1.0 / dirX)
        val tDeltaY = if (dirY == 0.0) Double.MAX_VALUE else abs(1.0 / dirY)
        val tDeltaZ = if (dirZ == 0.0) Double.MAX_VALUE else abs(1.0 / dirZ)

        val voxelStartX = x * cubeSize // Przybliżona pozycja środka voxela startowego w świecie (uproszczona)
        // Dokładniejsze obliczenie dystansu do pierwszej granicy
        // (startX / cubeSize) to pozycja w jednostkach bloków.
        val gridX = startX / cubeSize
        val gridY = (startY + 10.0) / cubeSize
        val gridZ = startZ / cubeSize

        val distX = if (stepX > 0) (floor(gridX + 0.5) + 0.5 - gridX) else (gridX - (floor(gridX + 0.5) - 0.5))
        val distY = if (stepY > 0) (floor(gridY + 0.5) + 0.5 - gridY) else (gridY - (floor(gridY + 0.5) - 0.5))
        val distZ = if (stepZ > 0) (floor(gridZ + 0.5) + 0.5 - gridZ) else (gridZ - (floor(gridZ + 0.5) - 0.5))

        var tMaxX = if (dirX == 0.0) Double.MAX_VALUE else abs(distX) * tDeltaX
        var tMaxY = if (dirY == 0.0) Double.MAX_VALUE else abs(distY) * tDeltaY
        var tMaxZ = if (dirZ == 0.0) Double.MAX_VALUE else abs(distZ) * tDeltaZ

        // Przeliczamy maxDist na jednostki t (kroki DDA)
        // t = distance / cubeSize (w przybliżeniu, bo tDelta są znormalizowane do 1 bloku)
        val maxT = maxDist / cubeSize

        while (true) {
            if (minOf(tMaxX, tMaxY, tMaxZ) > maxT) return false // Nie trafiliśmy w nic do limitu dystansu

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { x += stepX; tMaxX += tDeltaX } else { z += stepZ; tMaxZ += tDeltaZ }
            } else {
                if (tMaxY < tMaxZ) { y += stepY; tMaxY += tDeltaY } else { z += stepZ; tMaxZ += tDeltaZ }
            }

            // Jeśli trafiliśmy w blok, zwracamy true (jest okluzja)
            if (getBlock(x, y, z) != null) {
                // Sprawdzamy, czy ten blok należy do "pełnego" segmentu.
                // Jeśli tak, to jest to ściana/ziemia i blokuje widok.
                // Jeśli nie (np. drzewo), ignorujemy to trafienie i szukamy dalej.
                if (isSegmentOccluder(x, y, z)) {
                    return true // Trafiliśmy w inny pełny segment -> Zasłonięty.
                }
            }
        }
    }

    // Bardzo szybki Raycast działający na siatce segmentów (8x8x8), a nie bloków.
    // Ignoruje drzewa i małe przeszkody. Zatrzymuje się tylko na pełnych segmentach (Occluderach).
    private fun fastSegmentRaycast(startX: Double, startY: Double, startZ: Double, dirX: Double, dirY: Double, dirZ: Double, maxDist: Double, targetSecX: Int, targetSecY: Int, targetSecZ: Int): Boolean {
        val segmentSize = 8 * cubeSize // 16.0

        // Start w przestrzeni segmentów
        var x = floor(startX / segmentSize).toInt()
        var y = floor((startY + 10.0) / segmentSize).toInt()
        var z = floor(startZ / segmentSize).toInt()

        val stepX = if (dirX > 0) 1 else -1
        val stepY = if (dirY > 0) 1 else -1
        val stepZ = if (dirZ > 0) 1 else -1

        val tDeltaX = if (dirX == 0.0) Double.MAX_VALUE else abs(1.0 / dirX)
        val tDeltaY = if (dirY == 0.0) Double.MAX_VALUE else abs(1.0 / dirY)
        val tDeltaZ = if (dirZ == 0.0) Double.MAX_VALUE else abs(1.0 / dirZ)

        val gridX = startX / segmentSize
        val gridY = (startY + 10.0) / segmentSize
        val gridZ = startZ / segmentSize

        val distX = if (stepX > 0) (floor(gridX) + 1.0 - gridX) else (gridX - floor(gridX))
        val distY = if (stepY > 0) (floor(gridY) + 1.0 - gridY) else (gridY - floor(gridY))
        val distZ = if (stepZ > 0) (floor(gridZ) + 1.0 - gridZ) else (gridZ - floor(gridZ))

        var tMaxX = if (dirX == 0.0) Double.MAX_VALUE else abs(distX) * tDeltaX
        var tMaxY = if (dirY == 0.0) Double.MAX_VALUE else abs(distY) * tDeltaY
        var tMaxZ = if (dirZ == 0.0) Double.MAX_VALUE else abs(distZ) * tDeltaZ

        val maxT = maxDist / segmentSize

        while (true) {
            if (minOf(tMaxX, tMaxY, tMaxZ) > maxT) return false // Dolatujemy do celu bez przeszkód

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { x += stepX; tMaxX += tDeltaX } else { z += stepZ; tMaxZ += tDeltaZ }
            } else {
                if (tMaxY < tMaxZ) { y += stepY; tMaxY += tDeltaY } else { z += stepZ; tMaxZ += tDeltaZ }
            }

            // Jeśli dotarliśmy do segmentu docelowego -> Widoczny
            if (x == targetSecX && y == targetSecY && z == targetSecZ) return false

            // Sprawdzamy czy segment jest pełny (Occluder)
            if (isSegmentEmptyBySec(x, y, z)) {
                return true // Trafiliśmy w ścianę
            }
        }
    }

    // Pobiera maskę okluzji (Byte)
    private fun getSegmentOcclusionMask(secX: Int, secY: Int, secZ: Int): Byte {
        if (secY < 0 || secY > 15) return 0
        // Konwersja globalnych segmentów na Chunk + Local Segment
        val cx = if (secX >= 0) secX / 2 else (secX + 1) / 2 - 1
        val cz = if (secZ >= 0) secZ / 2 else (secZ + 1) / 2 - 1

        val occlusionData = chunkOcclusion[Point(cx, cz)] ?: return 0

        val localSecX = abs(secX % 2) // % 2 może zwrócić -1 dla ujemnych, więc abs
        val localSecZ = abs(secZ % 2)
        val index = secY * 4 + localSecZ * 2 + localSecX
        return occlusionData[index]
    }

    private fun isSegmentOccluder(x: Int, y: Int, z: Int): Boolean {
        // Używane przez Raycast - uznajemy za okluder tylko jeśli WSZYSTKIE ściany są pełne (lub wewnątrz jest pełno)
        // Można to ulepszyć, ale dla raycastu "fast skip" wystarczy sprawdzenie flagi FULL (63)
        val secX = floor(x / 8.0).toInt()
        val secY = floor((y + 10.0) / 8.0).toInt()
        val secZ = floor(z / 8.0).toInt()
        val mask = getSegmentOcclusionMask(secX, secY, secZ)
        return mask == SEGMENT_FULL.toByte()
    }

    private fun isSegmentEmptyBySec(secX: Int, secY: Int, secZ: Int): Boolean {
        if (secY < 0 || secY > 15) return true
        val cx = if (secX >= 0) secX / 2 else (secX + 1) / 2 - 1
        val cz = if (secZ >= 0) secZ / 2 else (secZ + 1) / 2 - 1

        val mesh = chunkMeshes[Point(cx, cz)] ?: return true

        val localSecX = abs(secX % 2)
        val localSecZ = abs(secZ % 2)
        val index = secY * 4 + localSecZ * 2 + localSecX
        return mesh[index].isEmpty()
    }

    private fun calculateSectionIndex(x: Int, y: Int, z: Int): Int {
        var lx = x % 16; if (lx < 0) lx += 16
        var lz = z % 16; if (lz < 0) lz += 16

        val secX = lx / 8
        val secY = (y / 8).coerceIn(0, 15)
        val secZ = lz / 8

        return secY * 4 + secZ * 2 + secX
    }

    private fun isSectionVisible(cx: Int, secX: Int, secY: Int, secZ: Int, cz: Int): Boolean {
        // Sprawdzamy, czy kamera znajduje się wewnątrz AABB (pudełka) tej sekcji.
        // Jeśli tak, sekcja musi być widoczna, więc pomijamy dalsze testy.
        val xMin = (cx * 16 + secX * 8) * cubeSize - cubeSize / 2.0
        val xMax = (cx * 16 + (secX + 1) * 8) * cubeSize - cubeSize / 2.0
        val yMin = (secY * 8) * cubeSize - 10.0 - cubeSize / 2.0
        val yMax = ((secY + 1) * 8) * cubeSize - 10.0 - cubeSize / 2.0
        val zMin = (cz * 16 + secZ * 8) * cubeSize - cubeSize / 2.0
        val zMax = (cz * 16 + (secZ + 1) * 8) * cubeSize - cubeSize / 2.0

        if (camX >= xMin && camX < xMax && camY >= yMin && camY < yMax && camZ >= zMin && camZ < zMax) {
            return true // Gracz jest w środku, sekcja musi być widoczna
        }

        // Środek sekcji w świecie 3D
        // cx * 16 + secX * 8 (początek sekcji) + 4 (środek sekcji 8-blokowej)
        val centerX = (cx * 16 + secX * 8 + 4) * cubeSize
        val centerY = (secY * 8 + 4) * cubeSize - 10.0
        val centerZ = (cz * 16 + secZ * 8 + 4) * cubeSize

        // Promień otaczający sekcję 8x8x8 (sqrt(4^2 + 4^2 + 4^2) ~= 6.9)
        // Dajemy lekki zapas (8.0)
        val radius = 8.0 * cubeSize

        // Transformacja punktu środkowego do przestrzeni kamery (uproszczona wersja transform())
        var x = centerX - camX
        var y = centerY - camY
        var z = centerZ - camZ

        // Obrót Y (Yaw)
        val x2 = x * cosYaw - z * sinYaw
        val z2 = z * cosYaw + x * sinYaw
        x = x2
        z = z2

        // Obrót X (Pitch) - KONIECZNE dla poprawnego działania przy patrzeniu góra/dół
        val y2 = y * cosPitch - z * sinPitch
        val z3 = z * cosPitch + y * sinPitch
        y = y2
        z = z3

        // 1. Czy sekcja jest za kamerą? (z < -radius)
        // Dodajemy mały margines (nearPlaneZ), żeby nie znikało tuż przed nosem
        if (z + radius < nearPlaneZ) return false

        // 2. Czy sekcja jest za daleko? (opcjonalne, renderDistance to załatwia, ale warto mieć)
        if (z > (renderDistance + 1) * 16 * cubeSize) return false

        // 3. Czy sekcja jest w stożku widzenia (FOV)?
        // Obliczamy proporcje ekranu (Aspect Ratio), bo ekran jest szerszy niż wyższy
        val aspect = baseCols.toDouble() / baseRows.toDouble()
        
        // Margines bezpieczeństwa dla promienia (1.5x), aby uwzględnić geometrię sfery vs płaszczyzny
        val safeRadius = radius * 1.5
        
        // Dla FOV 90, tan(45) = 1.0.
        // Sprawdzamy poziomo (X). Slope = 1.0.
        // Dodajemy warunek limitX > 0, aby uniknąć błędów przy ujemnym Z (gdy obiekt jest blisko/za kamerą)
        val limitX = z + safeRadius
        if (limitX > 0 && abs(x) > limitX) return false

        // Sprawdzamy pionowo (Y). Kąt jest mniejszy, więc slope = 1/aspect.
        val limitY = z * (1.0 / aspect) + safeRadius
        if (limitY > 0 && abs(y) > limitY) return false

        return true
    }

    private fun transform(v: Vector3d): Vector3d {
        var x = v.x - camX
        var y = v.y - camY
        var z = v.z - camZ

        // Obrót Y (Yaw)
        val x2 = x * cosYaw - z * sinYaw
        val z2 = z * cosYaw + x * sinYaw
        x = x2
        z = z2

        // Obrót X (Pitch)
        val y2 = y * cosPitch - z * sinPitch
        val z3 = z * cosPitch + y * sinPitch
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

    private fun project(v: Vector3d): Vector3d {
        val localLenght = (baseCols / 2.0) / Math.tan(Math.toRadians(fov / 2.0))
        val zSafe = if (v.z == 0.0) 0.001 else v.z // Unikamy dzielenia przez 0

        // Perspektywa: x' = x / z
        val px = (v.x * localLenght) / zSafe
        // Odwracamy oś Y, ponieważ na ekranie Y rośnie w dół, a w świecie 3D w górę
        val py = -(v.y * localLenght) / zSafe

        // Przesunięcie na środek ekranu (gridMapy)
        val screenX = px + (baseCols / 2)
        val screenY = py + (baseRows / 2)

        return Vector3d(screenX, screenY, v.z)
    }

    private fun renderChunkBorders() {
        val currentChunkX = floor(camX / 32.0).toInt()
        val currentChunkZ = floor(camZ / 32.0).toInt()
        val chunkColor = Color(66, 65, 240)
        val sectionColor = Color.YELLOW
        val offset = cubeSize / 2.0

        fun drawBox(xMin: Double, xMax: Double, yMin: Double, yMax: Double, zMin: Double, zMax: Double, color: Color) {
            val c0 = Vector3d(xMin, yMin, zMin)
            val c1 = Vector3d(xMax, yMin, zMin)
            val c2 = Vector3d(xMax, yMin, zMax)
            val c3 = Vector3d(xMin, yMin, zMax)
            val c4 = Vector3d(xMin, yMax, zMin)
            val c5 = Vector3d(xMax, yMax, zMin)
            val c6 = Vector3d(xMax, yMax, zMax)
            val c7 = Vector3d(xMin, yMax, zMax)

            // Bottom
            drawLine3D(c0, c1, color); drawLine3D(c1, c2, color)
            drawLine3D(c2, c3, color); drawLine3D(c3, c0, color)
            // Top
            drawLine3D(c4, c5, color); drawLine3D(c5, c6, color)
            drawLine3D(c6, c7, color); drawLine3D(c7, c4, color)
            // Vertical
            drawLine3D(c0, c4, color); drawLine3D(c1, c5, color)
            drawLine3D(c2, c6, color); drawLine3D(c3, c7, color)
        }

        for (cx in currentChunkX - radiusChunkRender..currentChunkX + radiusChunkRender) {
            for (cz in currentChunkZ - radiusChunkRender..currentChunkZ + radiusChunkRender) {
                val xMin = cx * 16 * cubeSize - offset
                val xMax = (cx + 1) * 16 * cubeSize - offset
                val zMin = cz * 16 * cubeSize - offset
                val zMax = (cz + 1) * 16 * cubeSize - offset

                // Rysowanie granic chunka (Niebieski)
                val yMinChunk = -10.0 - offset
                val yMaxChunk = 128 * cubeSize - 10.0 - offset
                drawBox(xMin, xMax, yMinChunk, yMaxChunk, zMin, zMax, chunkColor)

                // Rysowanie granic sekcji (Żółty)
                for (i in 0 until 64) {
                    val secY = i / 4
                    val rem = i % 4
                    val secZ = rem / 2
                    val secX = rem % 2

                    val xMinSec = (cx * 16 + secX * 8) * cubeSize - offset
                    val xMaxSec = (cx * 16 + (secX + 1) * 8) * cubeSize - offset
                    val yMinSec = (secY * 8) * cubeSize - 10.0 - offset
                    val yMaxSec = ((secY + 1) * 8) * cubeSize - 10.0 - offset
                    val zMinSec = (cz * 16 + secZ * 8) * cubeSize - offset
                    val zMaxSec = (cz * 16 + (secZ + 1) * 8) * cubeSize - offset

                    drawBox(xMinSec, xMaxSec, yMinSec, yMaxSec, zMinSec, zMaxSec, sectionColor)
                }
            }
        }
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

        rasterizeLine(proj1, proj2, color)
    }

    private fun rasterizeLine(p1: Vector3d, p2: Vector3d, color: Color) {
        var x0 = p1.x; var y0 = p1.y; var z0 = p1.z
        var x1 = p2.x; var y1 = p2.y; var z1 = p2.z

        // Cohen-Sutherland Clipping 2D
        val INSIDE = 0; val LEFT = 1; val RIGHT = 2; val BOTTOM = 4; val TOP = 8
        fun computeOutCode(x: Double, y: Double): Int {
            var code = INSIDE
            if (x < 0) code = code or LEFT
            else if (x > baseCols) code = code or RIGHT
            if (y < 0) code = code or TOP
            else if (y > baseRows) code = code or BOTTOM
            return code
        }

        var code0 = computeOutCode(x0, y0)
        var code1 = computeOutCode(x1, y1)
        var w0 = 1.0 / z0
        var w1 = 1.0 / z1

        while (true) {
            if ((code0 or code1) == 0) break
            if ((code0 and code1) != 0) return

            val code = if (code0 != 0) code0 else code1
            var x = 0.0; var y = 0.0; var w = 0.0

            if ((code and TOP) != 0) {
                val t = (0 - y0) / (y1 - y0); x = x0 + t * (x1 - x0); y = 0.0; w = w0 + t * (w1 - w0)
            } else if ((code and BOTTOM) != 0) {
                val t = (baseRows - y0) / (y1 - y0); x = x0 + t * (x1 - x0); y = baseRows.toDouble(); w = w0 + t * (w1 - w0)
            } else if ((code and RIGHT) != 0) {
                val t = (baseCols - x0) / (x1 - x0); x = baseCols.toDouble(); y = y0 + t * (y1 - y0); w = w0 + t * (w1 - w0)
            } else if ((code and LEFT) != 0) {
                val t = (0 - x0) / (x1 - x0); x = 0.0; y = y0 + t * (y1 - y0); w = w0 + t * (w1 - w0)
            }

            if (code == code0) { x0 = x; y0 = y; w0 = w; code0 = computeOutCode(x0, y0) }
            else { x1 = x; y1 = y; w1 = w; code1 = computeOutCode(x1, y1) }
        }

        var ix0 = x0.toInt(); var iy0 = y0.toInt()
        val ix1 = x1.toInt(); val iy1 = y1.toInt()
        val dx = abs(ix1 - ix0); val dy = abs(iy1 - iy0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        val totalSteps = maxOf(dx, dy)

        for (i in 0..totalSteps) {
            if (ix0 in 0..baseCols && iy0 in 0..baseRows) {
                val t = if (totalSteps == 0) 0.0 else i.toDouble() / totalSteps
                val currZ = 1.0 / (w0 + t * (w1 - w0))
                if (currZ < zBuffer[iy0][ix0]) {
                    backBuffer[iy0 * imageWidth + ix0] = color.rgb
                    zBuffer[iy0][ix0] = currZ
                }
            }
            if (ix0 == ix1 && iy0 == iy1) break
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; ix0 += sx }
            if (e2 < dx) { err += dx; iy0 += sy }
        }
    }

    // Rasteryzacja trójkąta bezpośrednio do gridMap
    private fun fillTriangle(p1: Vector3d, p2: Vector3d, p3: Vector3d, v1: Vector3d, v2: Vector3d, v3: Vector3d, color: Color) {
        // Bounding box
        val minX = minOf(p1.x, p2.x, p3.x).toInt().coerceIn(0, baseCols)
        val maxX = maxOf(p1.x, p2.x, p3.x).toInt().coerceIn(0, baseCols)
        val minY = minOf(p1.y, p2.y, p3.y).toInt().coerceIn(0, baseRows)
        val maxY = maxOf(p1.y, p2.y, p3.y).toInt().coerceIn(0, baseRows)

        val v0x = p2.x - p1.x
        val v0y = p2.y - p1.y
        val v1x = p3.x - p1.x
        val v1y = p3.y - p1.y

        val d00 = v0x * v0x + v0y * v0y
        val d01 = v0x * v1x + v0y * v1y
        val d11 = v1x * v1x + v1y * v1y

        val denom = d00 * d11 - d01 * d01
        if (abs(denom) < 1e-9) return

        val invDenom = 1.0 / denom

        // Funkcja pomocnicza do obliczenia V i W w konkretnym punkcie
        fun getBarycentric(px: Double, py: Double): Pair<Double, Double> {
            val v2x = px - p1.x
            val v2y = py - p1.y
            val d20 = v2x * v0x + v2y * v0y
            val d21 = v2x * v1x + v2y * v1y
            val v = (d11 * d20 - d01 * d21) * invDenom
            val w = (d00 * d21 - d01 * d20) * invDenom
            return Pair(v, w)
        }

        // 1. Obliczamy wartości początkowe dla lewego górnego rogu (minX, minY) + 0.5 (środek piksela)
        val startX = minX + 0.5
        val startY = minY + 0.5
        val (vStart, wStart) = getBarycentric(startX, startY)

        // 2. Obliczamy o ile zmieniają się V i W przy ruchu o 1 piksel w prawo (DX) i w dół (DY)
        // Dzięki liniowości barycentrycznej, te wartości są stałe dla całego trójkąta!
        val (vNextX, wNextX) = getBarycentric(startX + 1.0, startY)
        val (vNextY, wNextY) = getBarycentric(startX, startY + 1.0)

        val dvdx = vNextX - vStart
        val dwdx = wNextX - wStart
        val dvdy = vNextY - vStart
        val dwdy = wNextY - wStart

        // Zmienne robocze dla wierszy
        var rowV = vStart
        var rowW = wStart

        for (y in minY..maxY) {
            var v = rowV
            var w = rowW
            
            // Indeks w tablicy pikseli (optymalizacja dostępu do tablicy)
            var pixelIndex = y * imageWidth + minX

            for (x in minX..maxX) {
                // Dodajemy mały margines błędu (epsilon), aby uniknąć "dziur" na łączeniach trójkątów
                // u = 1 - v - w, więc warunek u >= 0 to v + w <= 1
                if (v >= -0.001 && w >= -0.001 && (v + w) <= 1.001) {
                    val u = 1.0 - v - w
                    
                    // Interpolacja głębokości (Z) i AO dla danego piksela
                    val depth = u * v1.z + v * v2.z + w * v3.z
                    val ao = u * v1.ao + v * v2.ao + w * v3.ao

                    // Z-Buffer Test: Rysujemy tylko jeśli nowy piksel jest bliżej niż obecny
                    if (depth < zBuffer[y][x]) {
                        zBuffer[y][x] = depth
                        val r = (color.red * ao).toInt().coerceIn(0, 255)
                        val g = (color.green * ao).toInt().coerceIn(0, 255)
                        val b = (color.blue * ao).toInt().coerceIn(0, 255)
                        val rgb = (r shl 16) or (g shl 8) or b
                        backBuffer[pixelIndex] = rgb
                    }
                }
                // Krok w prawo: dodajemy gradienty X
                v += dvdx
                w += dwdx
                pixelIndex++
            }
            // Krok w dół: dodajemy gradienty Y do wartości startowych wiersza
            rowV += dvdy
            rowW += dwdy
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D

        // Rysowanie całego bufora obrazu naraz (skalowanie do rozmiaru okna)
        g2d.drawImage(displayImage, 0, 0, width, height, null)

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

        // Rysowanie informacji o aktualnym chunku
        if (showChunkBorders) {
            val currentChunkX = floor(camX / 32.0).toInt()
            val currentChunkZ = floor(camZ / 32.0).toInt()
            val chunkText = "Chunk: c_${currentChunkX}_${currentChunkZ}.dat"
            val posText = "Position: (${floor((camX + (cubeSize/2))/2).toInt()}, ${floor((camY + (cubeSize/2))/2).toInt()+5}, ${floor((camZ + (cubeSize/2)) /2).toInt()})"
            g2d.color = Color.ORANGE
            g2d.drawString(chunkText, 10, fm.ascent + 10)
            g2d.drawString(posText, 10, fm.ascent*2 + 10)
        }
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
                backBuffer[curY * imageWidth + curX] = Color.RED.rgb
            }
            if (curX == x1 && curY == y1) break

            e2 = err
            if (e2 > -dx) { err -= dy; curX += sx }
            if (e2 < dy) { err += dx; curY += sy }
        }
    }

    // Logika poruszania się na podstawie wciśniętych klawiszy
    private fun processInput() {
        var tempSpeed = speed

        if (KeyEvent.VK_CONTROL in keys) tempSpeed *= 2

        var dx = 0.0
        var dz = 0.0

        if (KeyEvent.VK_W in keys) {
            dx += tempSpeed * sin(yaw)
            dz += tempSpeed * cos(yaw)
        }
        if (KeyEvent.VK_S in keys) {
            dx -= tempSpeed * sin(yaw)
            dz -= tempSpeed * cos(yaw)
        }
        if (KeyEvent.VK_A in keys) {
            dx -= tempSpeed * cos(yaw)
            dz += tempSpeed * sin(yaw)
        }
        if (KeyEvent.VK_D in keys) {
            dx += tempSpeed * cos(yaw)
            dz -= tempSpeed * sin(yaw)
        }

        if (KeyEvent.VK_LEFT in keys) yaw -= rotationalSpeed
        if (KeyEvent.VK_RIGHT in keys) yaw += rotationalSpeed
        if (KeyEvent.VK_UP in keys) pitch += rotationalSpeed
        if (KeyEvent.VK_DOWN in keys) pitch -= rotationalSpeed

        if (KeyEvent.VK_G in keys) println("camX: ${camX.toSmartString()}, camY: ${camY.toSmartString()}, camZ: ${camZ.toSmartString()}, yaw: ${yaw.toSmartString()}, pitch: ${pitch.toSmartString()}, speed: $tempSpeed")

        // Obsługa ciągłego niszczenia/stawiania bloków
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime > actionDelay) {
            if (isLeftMouseDown) {
                raycastAction(false)
                lastActionTime = currentTime
            } else if (isRightMouseDown) {
                raycastAction(true)
                lastActionTime = currentTime
            }
        }

        if (debugFly || debugNoclip) {
            // --- Tryb latania (Debug Fly / Noclip) ---
            // Wyłączamy grawitację, zachowujemy stare sterowanie Y

            var dy = 0.0
            if (KeyEvent.VK_SPACE in keys) dy += tempSpeed
            if (KeyEvent.VK_SHIFT in keys) dy -= tempSpeed

            // W trybie fly używamy kolizji (chyba że debugNoclip wyłączy je w checkCollision)
            moveWithCollision(dx, dy, dz)

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

            if ((e?.keyCode == KeyEvent.VK_NUMPAD9) or (e?.keyCode == KeyEvent.VK_9)) {
                debugFly = !debugFly
                println("DebugFly: $debugFly")
            }
            if ((e?.keyCode == KeyEvent.VK_NUMPAD8) or (e?.keyCode == KeyEvent.VK_8)) {
                debugNoclip = !debugNoclip
                println("debugNoclip: $debugNoclip")
            }
            if (e?.keyCode == KeyEvent.VK_V) {
                showChunkBorders = !showChunkBorders
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
                if (isShowing) windowPos = locationOnScreen
            } else {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isLeftMouseDown = true
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    isRightMouseDown = true
                }
            }
        }

        override fun mouseReleased(e: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e)) isLeftMouseDown = false
            if (SwingUtilities.isRightMouseButton(e)) isRightMouseDown = false
        }
    }

    private inner class GridMouseMotionListener : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            handleCameraLook(e)
        }

        override fun mouseDragged(e: MouseEvent) {
            handleCameraLook(e)
        }

        private fun handleCameraLook(e: MouseEvent) {
            if (isMouseCaptured && isShowing) {
                val centerX = width / 2
                val centerY = height / 2

                val dx = e.x - centerX
                val dy = e.y - centerY

                // Jeśli myszka jest na środku, nic nie rób (unikamy pętli zwrotnej od robota)
                if (dx == 0 && dy == 0) return

                yaw += dx * sensitivity
                pitch -= dy * sensitivity // Odwracamy oś Y dla naturalnego sterowania

                // Centrujemy myszkę z powrotem
                robot?.mouseMove(windowPos.x + centerX, windowPos.y + centerY)
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

        fun noise(x: Double, y: Double, z: Double): Double {
            val xi = floor(x).toInt()
            val yi = floor(y).toInt()
            val zi = floor(z).toInt()

            val X = xi and 255
            val Y = yi and 255
            val Z = zi and 255

            val xf = x - xi
            val yf = y - yi
            val zf = z - zi

            val u = fade(xf)
            val v = fade(yf)
            val w = fade(zf)

            val A = p[X] + Y
            val AA = p[A] + Z
            val AB = p[A + 1] + Z
            val B = p[X + 1] + Y
            val BA = p[B] + Z
            val BB = p[B + 1] + Z

            val res = lerp(w,
                lerp(v,
                    lerp(u, grad(p[AA], xf, yf, zf), grad(p[BA], xf - 1, yf, zf)),
                    lerp(u, grad(p[AB], xf, yf - 1, zf), grad(p[BB], xf - 1, yf - 1, zf))
                ),
                lerp(v,
                    lerp(u, grad(p[AA + 1], xf, yf, zf - 1), grad(p[BA + 1], xf - 1, yf, zf - 1)),
                    lerp(u, grad(p[AB + 1], xf, yf - 1, zf - 1), grad(p[BB + 1], xf - 1, yf - 1, zf - 1))
                )
            )
            return (res + 1) / 2.0 // Bring to 0..1 range
        }
        private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
        private fun lerp(t: Double, a: Double, b: Double) = a + t * (b - a)
        private fun grad(hash: Int, x: Double, y: Double) = if (hash and 1 == 0) x else -x + if (hash and 2 == 0) y else -y
        private fun grad(hash: Int, x: Double, y: Double, z: Double): Double {
            // Standardowa implementacja gradientu dla 3D Perlin Noise
            val h = hash and 15
            val u = if (h < 8) x else y
            val v = if (h < 4) y else if (h == 12 || h == 14) x else z
            return (if (h and 1 != 0) -u else u) + if (h and 2 != 0) -v else v
        }
    }

    fun Double.toSmartString(): String {
        val rawValue = (this / 2.0) + 0.5
        val rounded = Math.round(rawValue * 10.0) / 10.0

        return if (rounded % 1.0 == 0.0) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
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