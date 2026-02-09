package org.lewapnoob.gridMap

import java.awt.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.Arrays
import javax.swing.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.io.*
import kotlin.math.sqrt
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.sign
import java.util.Random

// --- 3D Structures (Moved out of gridMap class) ---
import kotlin.math.cos
import kotlin.math.sin
data class Vector3d(var x: Double, var y: Double, var z: Double, var ao: Double = 1.0)
data class BlockPos(val x: Int, val y: Int, val z: Int)
data class RayHit(val blockPos: BlockPos, val faceIndex: Int)
data class Triangle3d(
    val p1: Vector3d, val p2: Vector3d, val p3: Vector3d, val color: Color, val lightLevel: Int
)
data class ModelVoxel(val x: Int, val y: Int, val z: Int, val color: Color, val isVoid: Boolean = false)
data class ItemStack(val color: Int, var count: Int)
data class FluidProperties(val tickRate: Int) // Ile ticków na aktualizację. 30 ticków = 1 sekunda.
// --- Voxel World Data ---
data class Chunk(val x: Int, val z: Int) {
    val width = 16
    val height = 128
    val depth = 16
    // Zmiana: Płaska tablica intów zamiast tablicy obiektów Color.
    // 0 oznacza brak bloku (powietrze), inne wartości to ARGB koloru.
    val blocks = IntArray(width * height * depth)
    // Mapa światła (0-16)
    val light = ByteArray(width * height * depth)
    // Metadane (np. poziom płynu 0-8)
    val metadata = ByteArray(width * height * depth)
    var modified = false

    fun getIndex(x: Int, y: Int, z: Int): Int = x + width * (z + depth * y)
    fun setBlock(x: Int, y: Int, z: Int, color: Int) {
        blocks[getIndex(x, y, z)] = color
        modified = true
    }
    fun getBlock(x: Int, y: Int, z: Int): Int = blocks[getIndex(x, y, z)]
    fun setLight(x: Int, y: Int, z: Int, level: Int) { light[getIndex(x, y, z)] = level.toByte() }
    fun getLight(x: Int, y: Int, z: Int): Int = light[getIndex(x, y, z)].toInt() and 0xFF
    fun setMeta(x: Int, y: Int, z: Int, meta: Int) { metadata[getIndex(x, y, z)] = meta.toByte() }
    fun getMeta(x: Int, y: Int, z: Int): Int = metadata[getIndex(x, y, z)].toInt()
}

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
    // Getter dla pozycji oczu (uwzględnia kucanie)
    private val viewY: Double
        get() {
            val isCrouching = !debugFly && !debugNoclip && inputManager.isKeyDown(KeyEvent.VK_SHIFT)
            return camY - (if (isCrouching) 0.2 * cubeSize else 0.0)
        }
    private var renderDistance = 5
    private val debugChunkRenderDistance = 1
    private val simulateFluidsDistance = 1
    private val speed = 0.3
    private var currentSpeed = speed
    private val rotationalSpeed = 0.1

    private var debugNoclip = false
    private var debugFly = false
    private var debugFullbright = false
    private var showChunkBorders = false
    private var velocityY = 0.0
    private var isOnGround = false
    private val gravity = 0.1
    private val jumpStrength = 0.8

    private var debugXray = false
    private val oreColors = ConcurrentHashMap.newKeySet<Int>()

    // System dnia i nocy
    private var gameTime = 12.0
    private var dayCounter = 0
    private var globalSunIntensity = 1.0
    private var currentSkyColor = Color(113, 144, 225).rgb
    private val stars = ArrayList<Vector3d>()
    private var cloudOffset = 0.0
    private var FullbrightFactor = 0.0

    private val imageWidth = baseCols + 1
    private val displayImage = BufferedImage(imageWidth, baseRows + 1, BufferedImage.TYPE_INT_RGB)
    private val pixels = (displayImage.raster.dataBuffer as DataBufferInt).data
    private val backBuffer = IntArray(pixels.size)
    private val zBuffer = Array(baseRows + 1) { DoubleArray(baseCols + 1) { Double.MAX_VALUE } }

    // Odległość płaszczyzny przycinającej (near plane)
    private val nearPlaneZ = 0.1

    // --- Definicje Bloków Specjalnych (ID) ---
    private val BLOCK_ID_AIR = 0
    private val BLOCK_ID_LIGHT = 2
    private val BLOCK_ID_LAVA = 3
    private val BLOCK_ID_WATER = 4
    private val blockIdColors = mapOf(
        BLOCK_ID_LIGHT to Color(0xFFFDD0).rgb,
        BLOCK_ID_LAVA to Color(0xFF8C00).rgb,
        BLOCK_ID_WATER to Color(0.37f, 0.69f, 0.78f, 0.5f).rgb
    )
    private val fluidProperties = mapOf(
        BLOCK_ID_LAVA to FluidProperties(tickRate = 30), // 1 aktualizacja na sekundę
        BLOCK_ID_WATER to FluidProperties(tickRate = 15)  // 2 aktualizacje na sekundę
    )
    private val fluidBlocks = fluidProperties.keys
    private var minLightFactor = 0.15

    // Mapa przechowująca maskę bitową okluzji dla każdego segmentu (index 0-63)
    // Bity: 1=West(X-), 2=East(X+), 4=Down(Y-), 8=Up(Y+), 16=North(Z-), 32=South(Z+)
    private val chunkOcclusion = ConcurrentHashMap<Point, ByteArray>()
    private val chunkMeshes = ConcurrentHashMap<Point, Array<MutableList<Triangle3d>>>()

    private val FACE_WEST = 1
    private val FACE_EAST = 2
    private val FACE_DOWN = 4
    private val FACE_UP = 8
    private val FACE_NORTH = 16
    private val FACE_SOUTH = 32
    private val SEGMENT_FULL = 63 // Wszystkie 6 ścian

    // Cache widoczności (BFS)
    // Optymalizacja: Płaska tablica zamiast listy obiektów Triple, aby uniknąć alokacji (GC)
    // ZWIĘKSZONO: Dla renderDistance 12+ potrzeba więcej miejsca (ok. 40k segmentów)
    private var visibleSegmentsFlat = IntArray(524288 * 3) // Zwiększono 8x (dla segmentów 4x4x4)
    private var visibleSegmentsCount = 0
    private val bfsQueue = IntArray(524288 * 3) // Kolejka BFS (x, y, z) - Zwiększono 8x
    private var bfsVisited = IntArray(64 * 32 * 64) // Tokeny odwiedzin
    private var bfsRendered = IntArray(64 * 32 * 64) // Tokeny renderowania (zapobiega duplikatom)
    private var bfsVisitToken = 0

    private var lastCamSecX = Int.MIN_VALUE
    private var lastCamSecY = Int.MIN_VALUE
    private var lastCamSecZ = Int.MIN_VALUE
    private var lastYaw = Double.MAX_VALUE
    private var lastPitch = Double.MAX_VALUE
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
            Font("Consolas", Font.BOLD, 30)
        }
    } catch (e: Exception) {
        println("Failed to load font 'mojangles.ttf', using default.")
        Font("Consolas", Font.BOLD, 30)
    }

    private val hotbarFont = try {
        val stream = javaClass.classLoader.getResourceAsStream("fonts/mojangles.ttf")
        if (stream != null) {
            Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(24f)
        } else {
            Font("Consolas", Font.BOLD, 24)
        }
    } catch (e: Exception) {
        println("Failed to load font 'mojangles.ttf', using default.")
        Font("Consolas", Font.BOLD, 24)
    }

    private val chunks = ConcurrentHashMap<Point, Chunk>()
    private var seed = 6767
    private lateinit var noise: PerlinNoise
    private var lastChunkX = Int.MAX_VALUE
    private var lastChunkZ = Int.MAX_VALUE

    // System zapisu
    private val chunkIO = ChunkIO("world1")
    private var lastAutoSaveTime = System.currentTimeMillis()

    // Zbiór aktualnie wciśniętych klawiszy

    // Obsługa myszki
    private var lastActionTime = 0L
    private val actionDelay = 150 // Opóźnienie w ms (szybkość niszczenia)
    @Volatile private var running = true

    private var gameTicks = 0L
    // Cache dla obliczeń trygonometrycznych
    private var cosYaw = 0.0
    private var sinYaw = 0.0
    private var cosPitch = 0.0
    private var sinPitch = 0.0

    private val chunkGenerator = ChunkGenerator(seed, oreColors)

    // --- Inventory System ---
    private lateinit var inputManager: InputManager
    private val inventory = arrayOfNulls<ItemStack>(9)
    private var selectedSlot = 0

    // --- Threading & Optimization ---
    private val chunkExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    private val chunksBeingGenerated = ConcurrentHashMap.newKeySet<Point>()
    private val chunksToMeshQueue = ConcurrentLinkedQueue<Point>()

    init {
        preferredSize = Dimension((baseCols + 1) * cellSize, (baseRows + 1) * cellSize)
        setBackground(Color(113, 144, 225))
        isFocusable = true

        inputManager = InputManager(this)

        noise = PerlinNoise(seed)

        // Ustawiamy gracza na powierzchni (pobieramy wysokość terenu w punkcie 0,0)
        val spawnH = chunkGenerator.getTerrainHeight(0, 0)
        camY = (spawnH + 3) * cubeSize - 10.0

        updateWorld()

        loop()

        addItem("#FF0000", 64)
        addItem("#00FF00", 64)
        addItem("#0000FF", 64)
        addItem("$BLOCK_ID_LIGHT", 64)
        addItem("$BLOCK_ID_LAVA", 64)
        addItem("$BLOCK_ID_WATER", 64)

        // Generowanie gwiazd (Skymapa)
        val rStar = Random(seed.toLong())
        for (i in 0 until 2000) {
            val u = rStar.nextDouble() * 2.0 - 1.0
            val v = rStar.nextDouble() * 2.0 * Math.PI
            val f = sqrt(1.0 - u * u)
            val x = f * cos(v)
            val y = f * sin(v)
            val z = u
            // Używamy 4 parametru (ao) jako losowego progu pojawienia się gwiazdy (0.0 - 1.0)
            stars.add(Vector3d(x * 500.0, y * 500.0, z * 500.0, rStar.nextDouble()))
        }
    }

    private fun updateWorld() {
        val currentChunkX = floor(camX / 32.0).toInt()
        val currentChunkZ = floor(camZ / 32.0).toInt()

        val maxChunkCoord = 134217727
        val minChunkCoord = -134217728

        // 1. Zlecanie generowania chunków w tle (od środka na zewnątrz)
        val chunksToLoad = java.util.ArrayList<Point>()
        for (cx in currentChunkX - renderDistance..currentChunkX + renderDistance) {
            for (cz in currentChunkZ - renderDistance..currentChunkZ + renderDistance) {
                if (cx > maxChunkCoord || cx < minChunkCoord || cz > maxChunkCoord || cz < minChunkCoord) continue
                val p = Point(cx, cz)
                if (!chunks.containsKey(p) && !chunksBeingGenerated.contains(p)) {
                    chunksToLoad.add(p)
                }
            }
        }

        // Sortowanie: Najpierw najbliższe chunki
        chunksToLoad.sortWith { p1, p2 ->
            val d1 = (p1.x - currentChunkX) * (p1.x - currentChunkX) + (p1.y - currentChunkZ) * (p1.y - currentChunkZ)
            val d2 = (p2.x - currentChunkX) * (p2.x - currentChunkX) + (p2.y - currentChunkZ) * (p2.y - currentChunkZ)
            d1.compareTo(d2)
        }

        var chunksSubmitted = 0
        for (p in chunksToLoad) {
            if (chunksSubmitted >= 1) break // Leniwe ładowanie: max 1 chunk na klatkę, by nie dławić systemu
            chunksBeingGenerated.add(p)
            chunkExecutor.submit {
                try {
                    Thread.sleep(15) // Dajemy "odetchnąć" procesorowi, by nie zużywać 100% wątku
                    val newChunk = generateChunk(p.x, p.y)
                    chunks[p] = newChunk

                    // FIX: Wymuszamy aktualizację światła w nowym chunku i sąsiadach,
                    // aby światło poprawnie rozeszło się przez granice nowo załadowanego obszaru.
                    calculateLighting(newChunk)
                    getNeighborChunks(p.x, p.y).forEach { neighborPos ->
                        chunks[neighborPos]?.let { neighbor ->
                            calculateLighting(neighbor)
                            chunksToMeshQueue.add(neighborPos)
                        }
                    }

                    chunksToMeshQueue.add(p)
                } finally {
                    chunksBeingGenerated.remove(p)
                }
            }
            chunksSubmitted++
        }

        // 2. Przetwarzanie kolejki meshy (Limitowane, aby nie zabić FPS przy dużej ilości zmian naraz)
        var meshesProcessed = 0
        while (!chunksToMeshQueue.isEmpty() && meshesProcessed < 4) { // Max 4 chunki na klatkę
            val p = chunksToMeshQueue.poll()
            if (chunks.containsKey(p)) {
                updateChunkMesh(p.x, p.y)
                meshesProcessed++
            }
        }

        if (currentChunkX != lastChunkX || currentChunkZ != lastChunkZ) {
            lastChunkX = currentChunkX
            lastChunkZ = currentChunkZ
        }

        // Zmiana: Usuwanie starych chunków i ich meshy (Garbage Collection logiczny)
        val safeZone = renderDistance + 2
        val toRemove = chunks.keys.filter {
            abs(it.x - currentChunkX) > safeZone || abs(it.y - currentChunkZ) > safeZone
        }
        toRemove.forEach {
            // Jeśli chunk był modyfikowany przez gracza, zapisz go na dysk przed usunięciem z RAM
            val chunk = chunks[it]
            if (chunk != null && chunk.modified) chunkIO.saveChunk(chunk)

            chunks.remove(it)
            chunkMeshes.remove(it)
            chunkOcclusion.remove(it)
        }
    }

    private fun getNeighborChunks(cx: Int, cz: Int): List<Point> {
        return listOf(
            // Krok 1: Bezpośredni sąsiedzi (Krzyż) - muszą być pierwsi!
            Point(cx + 1, cz), Point(cx - 1, cz), Point(cx, cz + 1), Point(cx, cz - 1),
            // Krok 2: Sąsiedzi diagonalni (Rogi)
            // Pobierają światło od bezpośrednich sąsiadów, którzy chwilę wcześniej zostali zaktualizowani.
            Point(cx + 1, cz + 1), Point(cx - 1, cz - 1), Point(cx - 1, cz + 1), Point(cx + 1, cz - 1)
        )
    }

    // Dodano parametr changedBlocks dla operacji masowych (np. płyny)
    private fun refreshChunkData(cx: Int, cz: Int, lx: Int = -1, lz: Int = -1, changedBlocks: List<BlockPos>? = null) {
        val chunksToUpdate = java.util.HashSet<Point>() // HashSet zapobiega duplikatom przy wielu blokach
        chunksToUpdate.add(Point(cx, cz))

        // 1. ZAWSZE aktualizujemy bezpośrednich sąsiadów (Krzyż).
        // Dlaczego? Ponieważ światło ma zasięg 15 bloków. Nawet blok postawiony na środku (8,8)
        // może rzucić światło lub cień na sąsiada. Poprzedni "margin = 2" był zbyt agresywny i ucinał światło.
        chunksToUpdate.add(Point(cx - 1, cz))
        chunksToUpdate.add(Point(cx + 1, cz))
        chunksToUpdate.add(Point(cx, cz - 1))
        chunksToUpdate.add(Point(cx, cz + 1))

        // 2. Sąsiedzi diagonalni (Rogi) - Inteligentne wykrywanie zasięgu (Manhattan Distance).
        // Światło ma zasięg 15. Jeśli suma odległości do rogu chunka <= 15, światło tam dotrze.
        // To naprawia błędy (np. blok na 3,3 nie oświetlał skosu), zachowując optymalizację dla środka.

        // Funkcja pomocnicza do sprawdzania jednego punktu
        fun checkDiagonals(checkLx: Int, checkLz: Int) {
            if (checkLx + checkLz <= 15) chunksToUpdate.add(Point(cx - 1, cz - 1))
            if ((15 - checkLx) + checkLz <= 15) chunksToUpdate.add(Point(cx + 1, cz - 1))
            if (checkLx + (15 - checkLz) <= 15) chunksToUpdate.add(Point(cx - 1, cz + 1))
            if ((15 - checkLx) + (15 - checkLz) <= 15) chunksToUpdate.add(Point(cx + 1, cz + 1))
        }

        if (changedBlocks != null) {
            // Tryb masowy (np. płyny) - sprawdzamy każdy zmieniony blok
            for (pos in changedBlocks) {
                checkDiagonals(pos.x, pos.z)
            }
        } else if (lx != -1 && lz != -1) {
            // Tryb pojedynczy (np. stawianie bloku)
            checkDiagonals(lx, lz)
        }

        // 1. Reset światła (tylko w wybranych chunkach)
        chunksToUpdate.forEach { p -> chunks[p]?.let { Arrays.fill(it.light, 0.toByte()) } }

        // 2. Przeliczanie (3 przebiegi są konieczne dla łańcucha: Źródło -> Sąsiad -> Cel)
        // Jeśli kolejność w HashSet będzie odwrotna (Cel -> Sąsiad -> Źródło),
        // światło potrzebuje 3 cykli, by dotrzeć z wyzerowanego źródła do celu.
        repeat(3) {
            chunksToUpdate.forEach { p -> chunks[p]?.let { calculateLighting(it) } }
        }

        // 3. Aktualizacja meshy
        chunksToUpdate.forEach { p -> updateChunkMesh(p.x, p.y) }
    }

    private fun generateChunk(cx: Int, cz: Int): Chunk {
        // 0. Próba wczytania z dysku
        val loadedChunk = chunkIO.loadChunk(cx, cz)
        if (loadedChunk != null) {
            calculateLighting(loadedChunk) // Przeliczamy światło dla wczytanego chunka
            return loadedChunk
        }

        val chunk = chunkGenerator.generate(cx, cz)
        calculateLighting(chunk)

        return chunk
    }

    private fun calculateLighting(chunk: Chunk) {
        // Używamy tymczasowego bufora, aby uniknąć race condition (czytanie wyzerowanego światła przez inne wątki)
        val newLight = ByteArray(chunk.width * chunk.height * chunk.depth)

        // Kolejka BFS dla światła
        val lightQueue = IntArray(16 * 128 * 16 * 4) // x, y, z spakowane lub indeksy
        var qHead = 0
        var qTail = 0

        // 1. Inicjalizacja światła słonecznego (z góry)
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                var sunlight = true
                for (y in 127 downTo 0) {
                    val idx = chunk.getIndex(x, y, z)
                    val blockId = chunk.blocks[idx]

                    var skyL = 0
                    var blockL = 0

                    if (blockId != 0) {
                        if (blockId == BLOCK_ID_WATER) {
                            if (sunlight) {
                                skyL = 15
                                sunlight = false // Zatrzymujemy "nieskończony" promień, ale pozwalamy BFS rozprowadzić światło w dół
                            }
                        } else {
                            sunlight = false // Blokada światła dla bloków stałych
                        }
                        // Jeśli to blok światła, ustawiamy max jasność i dodajemy do kolejki propagacji
                        if (blockId == BLOCK_ID_LIGHT) {
                            blockL = 15
                        } else if (blockId == BLOCK_ID_LAVA) {
                            // Lawa emituje światło zależnie od poziomu (1-8 -> 8-15)
                            val level = chunk.getMeta(x, y, z) and 0xF
                            blockL = (level + 7).coerceIn(1, 15)
                        }
                    } else {
                        if (sunlight) {
                            skyL = 15
                        }
                    }

                    if (skyL > 0 || blockL > 0) {
                        newLight[idx] = ((skyL shl 4) or blockL).toByte()
                        lightQueue[qTail++] = idx
                    }
                }
            }
        }

        // 2. Import światła z sąsiadów (Propagacja przez granice chunków - powietrze oświetla powietrze)
        fun importLight(nx: Int, ny: Int, nz: Int, idx: Int, neighbor: Chunk) {
            val nVal = neighbor.getLight(nx, ny, nz)
            val nSky = (nVal shr 4) and 0xF
            val nBlock = nVal and 0xF

            val myVal = newLight[idx].toInt() and 0xFF
            var mySky = (myVal shr 4) and 0xF
            var myBlock = myVal and 0xF

            var changed = false

            val blockId = chunk.blocks[idx]
            if (blockId == 0 || blockId == BLOCK_ID_WATER) {
                val absorption = if (blockId == BLOCK_ID_WATER) 2 else 1 // Woda pochłania światło szybciej (2) niż powietrze (1)
                if (nSky > mySky + absorption) {
                    mySky = nSky - absorption
                    changed = true
                }
                if (nBlock > myBlock + absorption) {
                    myBlock = nBlock - absorption
                    changed = true
                }
            }

            if (changed) {
                newLight[idx] = ((mySky shl 4) or myBlock).toByte()
                lightQueue[qTail++] = idx
            }
        }

        // West (x-1) -> Nasze x=0 czyta z x=15 sąsiada
        chunks[Point(chunk.x - 1, chunk.z)]?.let { neighbor ->
            for (z in 0 until 16) for (y in 0 until 128) importLight(15, y, z, chunk.getIndex(0, y, z), neighbor)
        }
        // East (x+1) -> Nasze x=15 czyta z x=0 sąsiada
        chunks[Point(chunk.x + 1, chunk.z)]?.let { neighbor ->
            for (z in 0 until 16) for (y in 0 until 128) importLight(0, y, z, chunk.getIndex(15, y, z), neighbor)
        }
        // North (z-1) -> Nasze z=0 czyta z z=15 sąsiada
        chunks[Point(chunk.x, chunk.z - 1)]?.let { neighbor ->
            for (x in 0 until 16) for (y in 0 until 128) importLight(x, y, 15, chunk.getIndex(x, y, 0), neighbor)
        }
        // South (z+1) -> Nasze z=15 czyta z z=0 sąsiada
        chunks[Point(chunk.x, chunk.z + 1)]?.let { neighbor ->
            for (x in 0 until 16) for (y in 0 until 128) importLight(x, y, 0, chunk.getIndex(x, y, 15), neighbor)
        }

        // 3. Propagacja światła (BFS)
        while (qHead < qTail) {
            val idx = lightQueue[qHead++]
            val valPacked = newLight[idx].toInt() and 0xFF
            val sky = (valPacked shr 4) and 0xF
            val block = valPacked and 0xF

            if (sky <= 1 && block <= 1) continue // Nie ma co propagować

            val cx = idx % 16
            val cz = (idx / 16) % 16
            val cy_real = idx / 256

            val neighbors = arrayOf(
                Triple(cx + 1, cy_real, cz), Triple(cx - 1, cy_real, cz),
                Triple(cx, cy_real + 1, cz), Triple(cx, cy_real - 1, cz),
                Triple(cx, cy_real, cz + 1), Triple(cx, cy_real, cz - 1)
            )

            for ((nx, ny, nz) in neighbors) {
                if (nx in 0..15 && ny in 0..127 && nz in 0..15) {
                    val nIdx = chunk.getIndex(nx, ny, nz)
                    val nBlockId = chunk.blocks[nIdx]
                    if (nBlockId == 0 || nBlockId == BLOCK_ID_WATER) {
                        val absorption = if (nBlockId == BLOCK_ID_WATER) 2 else 1
                        val nVal = newLight[nIdx].toInt() and 0xFF
                        var nSky = (nVal shr 4) and 0xF
                        var nBlock = nVal and 0xF
                        var changed = false

                        if (sky > nSky + absorption) { nSky = sky - absorption; changed = true }
                        if (block > nBlock + absorption) { nBlock = block - absorption; changed = true }

                        if (changed) {
                            newLight[nIdx] = ((nSky shl 4) or nBlock).toByte()
                            lightQueue[qTail++] = nIdx
                        }
                    }
                }
            }
        }

        // Kopiujemy obliczone światło do głównej tablicy chunka
        System.arraycopy(newLight, 0, chunk.light, 0, newLight.size)
    }

    // --- System dodawania przedmiotów (API) ---

    // Uniwersalna funkcja addItem przyjmująca String (ID lub HEX)
    fun addItem(value: String, count: Int = 1) {
        val id = try {
            if (value.startsWith("#")) {
                Color.decode(value).rgb
            } else {
                value.toInt()
            }
        } catch (e: Exception) {
            println("Błąd: Nieprawidłowa wartość przedmiotu '$value'")
            return
        }

        if (id == 0) return // Ignorujemy ID 0 (powietrze)

        repeat(count) {
            // 1. Szukamy istniejącego stacka z tym samym kolorem, który ma mniej niż 64
            var added = false
            for (i in inventory.indices) {
                val stack = inventory[i]
                if (stack != null && stack.color == id && stack.count < 64) {
                    stack.count++
                    added = true
                    break
                }
            }
            // 2. Jeśli nie znaleziono pasującego stacka, szukamy pierwszego wolnego slotu
            if (!added) {
                for (i in inventory.indices) {
                    if (inventory[i] == null) {
                        inventory[i] = ItemStack(id, 1)
                        break
                    }
                }
            }
        }
    }

    private fun consumeCurrentItem() {
        val stack = inventory[selectedSlot] ?: return
        stack.count--
        if (stack.count <= 0) inventory[selectedSlot] = null
    }

    private fun setBlock(x: Int, y: Int, z: Int, rawBlock: Int) {
        if (y < 0 || y >= 128) return // Zmieniono górną granicę wysokości
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1

        val chunk = chunks[Point(cx, cz)] ?: return

        var lx = x % 16
        if (lx < 0) lx += 16
        var lz = z % 16
        if (lz < 0) lz += 16

        chunk.setBlock(lx, y, lz, rawBlock)
        chunk.setMeta(lx, y, lz, 0) // Reset metadata when placing/breaking
    }

    // Sprawdza czy promień przecina faktyczną bryłę bloku (uwzględniając wysokość płynów)
    private fun isRayIntersectingVoxel(x: Int, y: Int, z: Int, startX: Double, startY: Double, startZ: Double, dirX: Double, dirY: Double, dirZ: Double): Boolean {
        val rawBlock = getRawBlock(x, y, z)
        if (rawBlock == 0) return false

        // Jeśli to nie płyn, traktujemy jako pełny blok (hitbox 1x1x1)
        if (!fluidBlocks.contains(rawBlock)) return true

        // Obliczamy wysokość płynu (tak samo jak w renderowaniu)
        val myLevel = getFluidLevel(x, y, z)
        val blockAbove = getRawBlock(x, y + 1, z)
        val amIFull = (blockAbove == rawBlock)
        val height = if (amIFull) 1.0 else (myLevel / 9.0).coerceAtLeast(0.1)

        // Test przecięcia promienia z AABB (Slab Method)
        // Voxel w przestrzeni 'start' zajmuje zakres [x, x+1], [y, y+height], [z, z+1]
        val minX = x.toDouble(); val maxX = x + 1.0
        val minY = y.toDouble(); val maxY = y + height
        val minZ = z.toDouble(); val maxZ = z + 1.0

        // Dzielenie przez 0.0 w Double daje Infinity, co jest poprawnie obsługiwane przez minOf/maxOf
        val t1 = (minX - startX) / dirX
        val t2 = (maxX - startX) / dirX
        val tMinX = minOf(t1, t2)
        val tMaxX = maxOf(t1, t2)

        val t3 = (minY - startY) / dirY
        val t4 = (maxY - startY) / dirY
        val tMinY = minOf(t3, t4)
        val tMaxY = maxOf(t3, t4)

        val t5 = (minZ - startZ) / dirZ
        val t6 = (maxZ - startZ) / dirZ
        val tMinZ = minOf(t5, t6)
        val tMaxZ = maxOf(t5, t6)

        val tMin = maxOf(tMinX, tMinY, tMinZ)
        val tMax = minOf(tMaxX, tMaxY, tMaxZ)

        // Kolizja zachodzi, jeśli przedział [tMin, tMax] jest prawidłowy i nie jest w całości za nami (tMax >= 0)
        return tMax >= tMin && tMax >= 0.0
    }

    private fun getTargetBlock(): RayHit? {
        val startX = camX / cubeSize + 0.5
        val startY = (viewY + 10.0) / cubeSize + 0.5
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

        var lastFace = -1

        while (true) {
            if (minOf(tMaxX, tMaxY, tMaxZ) > reachDistance) break

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX
                    tMaxX += tDeltaX
                    lastFace = if (stepX > 0) 2 else 3 // 2: Left(X-), 3: Right(X+)
                } else {
                    z += stepZ
                    tMaxZ += tDeltaZ
                    lastFace = if (stepZ > 0) 0 else 1 // 0: Front(Z-), 1: Back(Z+)
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY
                    tMaxY += tDeltaY
                    lastFace = if (stepY > 0) 5 else 4 // 5: Bottom(Y-), 4: Top(Y+)
                } else {
                    z += stepZ
                    tMaxZ += tDeltaZ
                    lastFace = if (stepZ > 0) 0 else 1
                }
            }

            if (isRayIntersectingVoxel(x, y, z, startX, startY, startZ, dirX, dirY, dirZ)) {
                return RayHit(BlockPos(x, y, z), lastFace)
            }
        }
        return null
    }

    private fun raycastAction(place: Boolean) {
        // Pozycja startowa w przestrzeni voxeli (przeliczenie z koordynatów świata)
        val startX = camX / cubeSize + 0.5
        val startY = (viewY + 10.0) / cubeSize + 0.5
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

        // Sprawdzenie czy jesteśmy wewnątrz bloku - jeśli tak, pozwalamy go zniszczyć
        if (isRayIntersectingVoxel(x, y, z, startX, startY, startZ, dirX, dirY, dirZ)) {
            if (!place) {
                // Zbieranie do ekwipunku
                // FIX: Pobieramy surową wartość (ID lub Kolor), aby zachować ID bloku (np. 2)
                val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
                val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
                val chunk = chunks[Point(cx, cz)]
                val rawBlock = getRawBlockFromChunk(chunk, x, y, z)
                if (rawBlock != 0) addItem(rawBlock.toString())

                setBlock(x, y, z, 0)

                // Optymalizacja: Przekazujemy lokalne współrzędne
                var lx = x % 16; if (lx < 0) lx += 16
                var lz = z % 16; if (lz < 0) lz += 16
                refreshChunkData(cx, cz, lx, lz)
                return
            }
        }

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

            if (isRayIntersectingVoxel(x, y, z, startX, startY, startZ, dirX, dirY, dirZ)) {
                var updateX = x
                var updateZ = z

                if (place) {
                    if (!isPlayerInsideBlock(lastX, lastY, lastZ)) {
                        // Sprawdzamy czy mamy blok w ręce
                        val stack = inventory[selectedSlot]
                        if (stack != null) {
                            setBlock(lastX, lastY, lastZ, stack.color)
                            // Jeśli stawiamy płyn (np. lawę), ustawiamy go jako źródło
                            if (fluidBlocks.contains(stack.color)) {
                                val cx = if (lastX >= 0) lastX / 16 else (lastX + 1) / 16 - 1
                                val cz = if (lastZ >= 0) lastZ / 16 else (lastZ + 1) / 16 - 1
                                chunks[Point(cx, cz)]?.let {
                                    var lx = lastX % 16; if (lx < 0) lx += 16
                                    var lz = lastZ % 16; if (lz < 0) lz += 16
                                    it.setMeta(lx, lastY, lz, 8)
                                }
                            }
                            consumeCurrentItem()
                            updateX = lastX
                            updateZ = lastZ
                        } else {
                            return // Nie mamy bloków, nie budujemy
                        }
                    } else {
                        return
                    }
                } else {
                    // Zbieranie do ekwipunku (niszczenie z dystansu)
                    // FIX: Pobieramy surową wartość (ID lub Kolor)
                    val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
                    val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
                    val chunk = chunks[Point(cx, cz)]
                    val rawBlock = getRawBlockFromChunk(chunk, x, y, z)
                    if (rawBlock != 0) addItem(rawBlock.toString())

                    setBlock(x, y, z, 0)
                }

                // Aktualizujemy tylko chunk, w którym zaszła zmiana
                val cx = if (updateX >= 0) updateX / 16 else (updateX + 1) / 16 - 1
                val cz = if (updateZ >= 0) updateZ / 16 else (updateZ + 1) / 16 - 1

                // Optymalizacja: Przekazujemy lokalne współrzędne
                var lx = updateX % 16; if (lx < 0) lx += 16
                var lz = updateZ % 16; if (lz < 0) lz += 16
                refreshChunkData(cx, cz, lx, lz)
                return
            }

            lastX = x
            lastY = y
            lastZ = z
        }
    }

    // Pomocnicza funkcja do pobierania surowego ID/Koloru z chunka
    private fun getRawBlockFromChunk(chunk: Chunk?, x: Int, y: Int, z: Int): Int {
        if (chunk == null || y !in 0..127) return 0
        var lx = x % 16
        if (lx < 0) lx += 16
        var lz = z % 16
        if (lz < 0) lz += 16
        return chunk.getBlock(lx, y, lz)
    }

    private fun getRawBlock(x: Int, y: Int, z: Int): Int {
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
        val chunk = chunks[Point(cx, cz)]
        return getRawBlockFromChunk(chunk, x, y, z)
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
        if (colorInt == 0) return null

        // FIX: Używamy funkcji mapującej ID na kolor
        return Color(getBlockDisplayColor(colorInt))
    }

    private fun getBlockDisplayColor(raw: Int): Int {
        return blockIdColors[raw] ?: raw
    }

    // Pobiera poziom światła z globalnych współrzędnych
    private fun getLight(x: Int, y: Int, z: Int): Int {
        if (y < 0 || y >= 128) return 0xF0 // Poza światem jasno (Sky=15, Block=0)
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1

        val chunk = chunks[Point(cx, cz)] ?: return 0xF0 // Jeśli chunk nie załadowany, zakładamy jasno

        var lx = x % 16
        if (lx < 0) lx += 16
        var lz = z % 16
        if (lz < 0) lz += 16

        return chunk.getLight(lx, y, lz)
    }

    // Generuje mesh tylko dla jednego chunka
    private fun updateChunkMesh(cx: Int, cz: Int) {
        val chunk = chunks[Point(cx, cz)] ?: return
        // 4x4x4 = 4 sekcje X * 32 sekcje Y * 4 sekcje Z = 512 sekcji
        val sections = Array(512) { mutableListOf<Triangle3d>() }
        val blockCounts = IntArray(512) // Licznik bloków w każdym segmencie

        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                for (y in 0 until 128) { // Zmieniono zakres wysokości dla generowania mesha
                    val rawBlock = chunk.getBlock(lx, y, lz)

                    // Obliczamy indeks sekcji 4x4x4
                    val secX = lx / 4
                    val secY = (y / 4).coerceIn(0, 31)
                    val secZ = lz / 4
                    val index = secY * 16 + secZ * 4 + secX // Flattened index (Y * (4*4) + Z * 4 + X)

                    if (rawBlock != 0) {
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

                    // FIX: Mapowanie ID na kolor dla mesha
                    val color = Color(getBlockDisplayColor(rawBlock), true)
                    val targetList = sections[index]

                    // Obliczanie wysokości bloku (dla płynów)
                    var height = 1.0
                    var isFluid = false
                    var myLevel = 0
                    var amIFull = false

                    if (fluidBlocks.contains(rawBlock)) {
                        isFluid = true
                        myLevel = chunk.getMeta(lx, y, lz) and 0xF // Odczytujemy tylko 4 dolne bity (poziom)
                        // Poziom 8 (źródło) = 0.9 (prawie pełny), Poziom 1 = 0.125
                        // Jeśli nad nami jest ten sam płyn, to renderujemy jako pełny (1.0)
                        val blockAbove = if (y < 127) chunk.getBlock(lx, y + 1, lz) else 0
                        amIFull = (blockAbove == rawBlock)
                        height = if (amIFull) 1.0 else (myLevel / 9.0).coerceAtLeast(0.1)
                    }

                    // Helper do decydowania czy rysować ściankę BOCZNĄ (uwzględnia różnice poziomów płynów)
                    fun shouldRenderSide(nx: Int, ny: Int, nz: Int): Boolean {
                        val neighborId = getRawBlock(nx, ny, nz)
                        if (isFluid) {
                            if (neighborId == 0) return true // Widać nas od strony powietrza
                            if (fluidBlocks.contains(neighborId)) {
                                // Sąsiad to też płyn - sprawdzamy czy nas nie zasłania
                                val neighborAbove = getRawBlock(nx, ny + 1, nz)
                                if (neighborAbove == neighborId) return false // Sąsiad jest pełny (ma płyn nad sobą)
                                if (amIFull) return true // My jesteśmy pełni, sąsiad nie -> rysujemy ściankę
                                val neighborLevel = getFluidLevel(nx, ny, nz)
                                return myLevel > neighborLevel // Rysujemy tylko jeśli jesteśmy wyżsi
                            }
                            return false // Sąsiad to blok stały -> nie widać nas
                        }
                        return neighborId == 0 || fluidBlocks.contains(neighborId)
                    }

                    // Helper do obliczania wysokości cieczy sąsiada (dla dolnej krawędzi ścianki)
                    fun getNeighborHeight(nx: Int, ny: Int, nz: Int): Double {
                        val nId = getRawBlock(nx, ny, nz)
                        if (nId != rawBlock) return 0.0 // Jeśli to powietrze lub inny blok, rysujemy od dołu (0.0)
                        
                        val nLevel = getFluidLevel(nx, ny, nz)
                        val nAbove = getRawBlock(nx, ny + 1, nz)
                        val nFull = (nAbove == rawBlock)
                        return if (nFull) 1.0 else (nLevel / 9.0).coerceAtLeast(0.1)
                    }

                    // Helper do decydowania czy rysować GÓRĘ/DÓŁ (standardowa logika)
                    fun shouldRenderCap(nx: Int, ny: Int, nz: Int, isTop: Boolean): Boolean {
                        val neighborId = getRawBlock(nx, ny, nz)
                        if (isFluid) {
                            // Dla górnej ściany lawy: rysujemy zawsze, chyba że nad nami jest ten sam płyn.
                            // Pozwala to widzieć taflę lawy pod blokiem stałym (luka).
                            if (isTop) return neighborId != rawBlock
                            return neighborId == 0
                        }
                        return neighborId == 0 || fluidBlocks.contains(neighborId)
                    }

                    // Sprawdzamy sąsiadów
                    if (shouldRenderSide(wx, y, wz - 1)) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 0, color, isFluid, height, getNeighborHeight(wx, y, wz - 1))
                    if (shouldRenderSide(wx, y, wz + 1)) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 1, color, isFluid, height, getNeighborHeight(wx, y, wz + 1))
                    if (shouldRenderSide(wx - 1, y, wz)) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 2, color, isFluid, height, getNeighborHeight(wx - 1, y, wz))
                    if (shouldRenderSide(wx + 1, y, wz)) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 3, color, isFluid, height, getNeighborHeight(wx + 1, y, wz))
                    if (shouldRenderCap(wx, y + 1, wz, true)) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 4, color, isFluid, height, 0.0)
                    if (shouldRenderCap(wx, y - 1, wz, false)) addFace(targetList, wx, y, wz, xPos, yPos, zPos, 5, color, isFluid, height, 0.0)
                }
            }
        }
        chunkMeshes[Point(cx, cz)] = sections
        // Obliczamy maskę okluzji dla każdego segmentu (czy ściany są pełne)
        chunkOcclusion[Point(cx, cz)] = calculateOcclusionMasks(chunk, blockCounts)
        visibilityGraphDirty = true
    }

    private fun calculateOcclusionMasks(chunk: Chunk, blockCounts: IntArray): ByteArray {
        val occlusion = ByteArray(512)

        for (i in 0 until 512) {
            // Jeśli segment jest pusty, na pewno nie zasłania nic
            if (blockCounts[i] == 0) continue

            // Dekodujemy pozycję sekcji
            val secY = i / 16
            val rem = i % 16
            val secZ = rem / 4
            val secX = rem % 4

            val baseX = secX * 4
            val baseY = secY * 4
            val baseZ = secZ * 4

            var mask = 0

            // Sprawdzamy 6 ścian segmentu 4x4x4
            // 1. West (X=0)
            var solid = true
            for (y in 0..3) for (z in 0..3) { val id = chunk.getBlock(baseX, baseY + y, baseZ + z); if (id == 0 || fluidBlocks.contains(id)) { solid = false; break } }
            if (solid) mask = mask or FACE_WEST

            // 2. East (X=3)
            solid = true
            for (y in 0..3) for (z in 0..3) { val id = chunk.getBlock(baseX + 3, baseY + y, baseZ + z); if (id == 0 || fluidBlocks.contains(id)) { solid = false; break } }
            if (solid) mask = mask or FACE_EAST

            // 3. Down (Y=0)
            solid = true
            for (x in 0..3) for (z in 0..3) { val id = chunk.getBlock(baseX + x, baseY, baseZ + z); if (id == 0 || fluidBlocks.contains(id)) { solid = false; break } }
            if (solid) mask = mask or FACE_DOWN

            // 4. Up (Y=3)
            solid = true
            for (x in 0..3) for (z in 0..3) { val id = chunk.getBlock(baseX + x, baseY + 3, baseZ + z); if (id == 0 || fluidBlocks.contains(id)) { solid = false; break } }
            if (solid) mask = mask or FACE_UP

            // 5. North (Z=0)
            solid = true
            for (x in 0..3) for (y in 0..3) { val id = chunk.getBlock(baseX + x, baseY + y, baseZ); if (id == 0 || fluidBlocks.contains(id)) { solid = false; break } }
            if (solid) mask = mask or FACE_NORTH

            // 6. South (Z=3)
            solid = true
            for (x in 0..3) for (y in 0..3) { val id = chunk.getBlock(baseX + x, baseY + y, baseZ + 3); if (id == 0 || fluidBlocks.contains(id)) { solid = false; break } }
            if (solid) mask = mask or FACE_SOUTH

            occlusion[i] = mask.toByte()
        }
        return occlusion
    }

    private fun addFace(target: MutableList<Triangle3d>, wx: Int, wy: Int, wz: Int, x: Double, y: Double, z: Double, faceType: Int, c: Color, isDoubleSided: Boolean = false, topHeight: Double = 1.0, bottomHeight: Double = 0.0) {
        // Rozmiar kostki = cubeSize (od -cubeSize/2 do +cubeSize/2 względem środka)
        val d = cubeSize / 2.0
        // Obliczamy górną krawędź na podstawie wysokości płynu
        val topY = y - d + (cubeSize * topHeight)
        val bottomY = y - d + (cubeSize * bottomHeight)

        val p = arrayOf(
            Vector3d(x - d, bottomY, z - d), Vector3d(x + d, bottomY, z - d), // 0, 1
            Vector3d(x + d, topY, z - d), Vector3d(x - d, topY, z - d), // 2, 3 (Top)
            Vector3d(x - d, bottomY, z + d), Vector3d(x + d, bottomY, z + d), // 4, 5
            Vector3d(x + d, topY, z + d), Vector3d(x - d, topY, z + d)  // 6, 7 (Top)
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

        fun addTri(i1: Int, i2: Int, i3: Int, i4: Int, shade: Double, ao: DoubleArray, light: Int) {
            val v1 = p[i1].copy().apply { this.ao = ao[0] * shade }
            val v2 = p[i2].copy().apply { this.ao = ao[1] * shade }
            val v3 = p[i3].copy().apply { this.ao = ao[2] * shade }
            val v4 = p[i4].copy().apply { this.ao = ao[3] * shade }

            target.add(Triangle3d(v1, v2, v3, c, light))
            target.add(Triangle3d(v1, v3, v4, c, light))

            if (isDoubleSided) {
                target.add(Triangle3d(v1, v3, v2, c, light))
                target.add(Triangle3d(v1, v4, v3, c, light))
            }
        }

        val aoValues = DoubleArray(4)
        val v = Array(4) { BooleanArray(3) }
        var lightLevel = 16

        // Obliczanie AO dla 4 wierzchołków danej ściany
        when (faceType) {
            0 -> { // Front (Z-), quad 0,1,2,3
                v[0][0] = getBlock(wx - 1, wy, wz - 1) != null; v[0][1] = getBlock(wx, wy - 1, wz - 1) != null; v[0][2] = getBlock(wx - 1, wy - 1, wz - 1) != null
                v[1][0] = getBlock(wx + 1, wy, wz - 1) != null; v[1][1] = getBlock(wx, wy - 1, wz - 1) != null; v[1][2] = getBlock(wx + 1, wy - 1, wz - 1) != null
                v[2][0] = getBlock(wx + 1, wy, wz - 1) != null; v[2][1] = getBlock(wx, wy + 1, wz - 1) != null; v[2][2] = getBlock(wx + 1, wy + 1, wz - 1) != null
                v[3][0] = getBlock(wx - 1, wy, wz - 1) != null; v[3][1] = getBlock(wx, wy + 1, wz - 1) != null; v[3][2] = getBlock(wx - 1, wy + 1, wz - 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                lightLevel = getLight(wx, wy, wz - 1)
                addTri(0, 1, 2, 3, 0.8, aoValues, lightLevel)
            }
            1 -> { // Back (Z+), quad 5,4,7,6
                v[0][0] = getBlock(wx + 1, wy, wz + 1) != null; v[0][1] = getBlock(wx, wy - 1, wz + 1) != null; v[0][2] = getBlock(wx + 1, wy - 1, wz + 1) != null
                v[1][0] = getBlock(wx - 1, wy, wz + 1) != null; v[1][1] = getBlock(wx, wy - 1, wz + 1) != null; v[1][2] = getBlock(wx - 1, wy - 1, wz + 1) != null
                v[2][0] = getBlock(wx - 1, wy, wz + 1) != null; v[2][1] = getBlock(wx, wy + 1, wz + 1) != null; v[2][2] = getBlock(wx - 1, wy + 1, wz + 1) != null
                v[3][0] = getBlock(wx + 1, wy, wz + 1) != null; v[3][1] = getBlock(wx, wy + 1, wz + 1) != null; v[3][2] = getBlock(wx + 1, wy + 1, wz + 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                lightLevel = getLight(wx, wy, wz + 1)
                addTri(5, 4, 7, 6, 0.8, aoValues, lightLevel)
            }
            2 -> { // Left (X-), quad 4,0,3,7
                v[0][0] = getBlock(wx - 1, wy, wz + 1) != null; v[0][1] = getBlock(wx - 1, wy - 1, wz) != null; v[0][2] = getBlock(wx - 1, wy - 1, wz + 1) != null
                v[1][0] = getBlock(wx - 1, wy, wz - 1) != null; v[1][1] = getBlock(wx - 1, wy - 1, wz) != null; v[1][2] = getBlock(wx - 1, wy - 1, wz - 1) != null
                v[2][0] = getBlock(wx - 1, wy, wz - 1) != null; v[2][1] = getBlock(wx - 1, wy + 1, wz) != null; v[2][2] = getBlock(wx - 1, wy + 1, wz - 1) != null
                v[3][0] = getBlock(wx - 1, wy, wz + 1) != null; v[3][1] = getBlock(wx - 1, wy + 1, wz) != null; v[3][2] = getBlock(wx - 1, wy + 1, wz + 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                lightLevel = getLight(wx - 1, wy, wz)
                addTri(4, 0, 3, 7, 0.6, aoValues, lightLevel)
            }
            3 -> { // Right (X+), quad 1,5,6,2
                v[0][0] = getBlock(wx + 1, wy, wz - 1) != null; v[0][1] = getBlock(wx + 1, wy - 1, wz) != null; v[0][2] = getBlock(wx + 1, wy - 1, wz - 1) != null
                v[1][0] = getBlock(wx + 1, wy, wz + 1) != null; v[1][1] = getBlock(wx + 1, wy - 1, wz) != null; v[1][2] = getBlock(wx + 1, wy - 1, wz + 1) != null
                v[2][0] = getBlock(wx + 1, wy, wz + 1) != null; v[2][1] = getBlock(wx + 1, wy + 1, wz) != null; v[2][2] = getBlock(wx + 1, wy + 1, wz + 1) != null
                v[3][0] = getBlock(wx + 1, wy, wz - 1) != null; v[3][1] = getBlock(wx + 1, wy + 1, wz) != null; v[3][2] = getBlock(wx + 1, wy + 1, wz - 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                lightLevel = getLight(wx + 1, wy, wz)
                addTri(1, 5, 6, 2, 0.6, aoValues, lightLevel)
            }
            4 -> { // Top (Y+), quad 3,2,6,7
                v[0][0] = getBlock(wx - 1, wy + 1, wz) != null; v[0][1] = getBlock(wx, wy + 1, wz - 1) != null; v[0][2] = getBlock(wx - 1, wy + 1, wz - 1) != null
                v[1][0] = getBlock(wx + 1, wy + 1, wz) != null; v[1][1] = getBlock(wx, wy + 1, wz - 1) != null; v[1][2] = getBlock(wx + 1, wy + 1, wz - 1) != null
                v[2][0] = getBlock(wx + 1, wy + 1, wz) != null; v[2][1] = getBlock(wx, wy + 1, wz + 1) != null; v[2][2] = getBlock(wx + 1, wy + 1, wz + 1) != null
                v[3][0] = getBlock(wx - 1, wy + 1, wz) != null; v[3][1] = getBlock(wx, wy + 1, wz + 1) != null; v[3][2] = getBlock(wx - 1, wy + 1, wz + 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                lightLevel = getLight(wx, wy + 1, wz)
                addTri(3, 2, 6, 7, 1.0, aoValues, lightLevel)
            }
            5 -> { // Bottom (Y-), quad 4,5,1,0
                v[0][0] = getBlock(wx - 1, wy - 1, wz) != null; v[0][1] = getBlock(wx, wy - 1, wz + 1) != null; v[0][2] = getBlock(wx - 1, wy - 1, wz + 1) != null
                v[1][0] = getBlock(wx + 1, wy - 1, wz) != null; v[1][1] = getBlock(wx, wy - 1, wz + 1) != null; v[1][2] = getBlock(wx + 1, wy - 1, wz + 1) != null
                v[2][0] = getBlock(wx + 1, wy - 1, wz) != null; v[2][1] = getBlock(wx, wy - 1, wz - 1) != null; v[2][2] = getBlock(wx + 1, wy - 1, wz - 1) != null
                v[3][0] = getBlock(wx - 1, wy - 1, wz) != null; v[3][1] = getBlock(wx, wy - 1, wz - 1) != null; v[3][2] = getBlock(wx - 1, wy - 1, wz - 1) != null
                for (i in 0..3) aoValues[i] = vertexAO(v[i][0], v[i][1], v[i][2])
                lightLevel = getLight(wx, wy - 1, wz)
                addTri(4, 5, 1, 0, 0.4, aoValues, lightLevel)
            }
        }
    }

    // --- System Symulacji Płynów ---
    private fun simulateFluids(currentTick: Long) {
        val fluidsToSimulateThisTick = fluidProperties.filter { currentTick % it.value.tickRate == 0L }.keys
        if (fluidsToSimulateThisTick.isEmpty()) return

        val currentChunkX = floor(camX / 32.0).toInt()
        val currentChunkZ = floor(camZ / 32.0).toInt()
        val radius = simulateFluidsDistance

        // Lista zmian do zaaplikowania (aby uniknąć problemów z modyfikacją podczas iteracji)
        val updates = HashMap<Point, MutableList<Triple<BlockPos, Int, Int>>>() // Chunk -> List(Pos, BlockID, Level)

        for (cx in currentChunkX - radius..currentChunkX + radius) {
            for (cz in currentChunkZ - radius..currentChunkZ + radius) {
                val chunk = chunks[Point(cx, cz)] ?: continue

                for (lx in 0 until 16) {
                    for (lz in 0 until 16) {
                        for (y in 0 until 128) {
                            val blockId = chunk.getBlock(lx, y, lz)
                            if (fluidsToSimulateThisTick.contains(blockId)) {
                                val meta = chunk.getMeta(lx, y, lz)
                                val level = meta and 0xF
                                val parentDir = (meta shr 4) and 0xF

                                if (level <= 0) continue

                                val wx = cx * 16 + lx
                                val wz = cz * 16 + lz

                                // 0. Faza Zanikania (Decay) - Sprawdzamy czy rodzic istnieje
                                // Kierunki rodzica: 1=UP, 2=WEST(x-1), 3=EAST(x+1), 4=NORTH(z-1), 5=SOUTH(z+1)
                                var parentExists = true
                                if (parentDir > 0) {
                                    var px = wx; var py = y; var pz = wz
                                    when (parentDir) {
                                        1 -> py++       // Rodzic jest u góry
                                        2 -> px--       // Rodzic jest na zachód (x-1)
                                        3 -> px++       // Rodzic jest na wschód (x+1)
                                        4 -> pz--       // Rodzic jest na północ (z-1)
                                        5 -> pz++       // Rodzic jest na południe (z+1)
                                    }
                                    // Jeśli blok rodzica nie jest tym samym płynem, to my znikamy
                                    if (getRawBlock(px, py, pz) != blockId) {
                                        parentExists = false
                                    }
                                }

                                if (!parentExists) {
                                    // Rodzic zniknął -> my znikamy (zamiana w powietrze)
                                    addFluidUpdate(updates, wx, y, wz, 0, 0)
                                    continue // Nie rozlewamy się dalej, skoro znikamy
                                }

                                // 1. Sprawdź dół (Priorytet)
                                var flowedDown = false
                                if (y > 0) {
                                    val blockBelow = getRawBlock(wx, y - 1, wz)
                                    if (blockBelow == 0) {
                                        // Płynie w dół -> tworzy nowe źródło (8), Rodzic = UP (1)
                                        // 1 << 4 = 16. Meta = 16 | 8 = 24
                                        addFluidUpdate(updates, wx, y - 1, wz, blockId, 8 or (1 shl 4))
                                        flowedDown = true
                                    } else if (fluidBlocks.contains(blockBelow)) {
                                        // Jeśli poniżej jest płyn, ale nie jest źródłem (8), zamień go w źródło
                                        val levelBelow = getFluidLevel(wx, y - 1, wz)
                                        if (levelBelow < 8) {
                                            // Aktualizacja do źródła, Rodzic = UP (1)
                                            addFluidUpdate(updates, wx, y - 1, wz, blockId, 8 or (1 shl 4))
                                            flowedDown = true
                                        }
                                    }
                                }

                                // Jeśli lawa popłynęła w dół, nie rozlewa się na boki w tej turze
                                if (!flowedDown && level > 1) {
                                    // Sprawdzamy czy możemy się rozlać na boki.
                                    // Warunek: Rozlewamy się TYLKO gdy mamy "grunt".
                                    // Jeśli pod nami jest płyn, który spada (Parent=UP), to my też jesteśmy kolumną i nie rozlewamy się.
                                    var canSpread = true
                                    if (y > 0) {
                                        val blockBelow = getRawBlock(wx, y - 1, wz)
                                        if (fluidBlocks.contains(blockBelow)) {
                                            val metaBelow = getFluidMeta(wx, y - 1, wz)
                                            val parentDirBelow = (metaBelow shr 4) and 0xF
                                            if (parentDirBelow == 1) { // 1 = UP (Rodzic jest u góry)
                                                // Blok pod nami to spadająca lawa -> my też spadamy -> nie rozlewamy się na boki
                                                canSpread = false
                                            }
                                        }
                                    }

                                    if (canSpread) {
                                        // 2. Rozlewanie na boki
                                        val neighbors = arrayOf(
                                            // Offset, Direction for Child (Inverse of offset)
                                            Triple(wx + 1, wz, 2), // East Neighbor -> Parent is West (2)
                                            Triple(wx - 1, wz, 3), // West Neighbor -> Parent is East (3)
                                            Triple(wx, wz + 1, 4), // South Neighbor -> Parent is North (4)
                                            Triple(wx, wz - 1, 5)  // North Neighbor -> Parent is South (5)
                                        )
                                        for ((nx, nz, childParentDir) in neighbors) {
                                            val nBlock = getRawBlock(nx, y, nz)
                                            val newMeta = (level - 1) or (childParentDir shl 4)

                                            if (nBlock == 0) {
                                                // Rozlej do pustego (poziom - 1)
                                                addFluidUpdate(updates, nx, y, nz, blockId, newMeta)
                                            } else if (fluidBlocks.contains(nBlock)) {
                                                // Wyrównaj poziomy (jeśli sąsiad ma mniej niż my - 1)
                                                val nLevel = getFluidLevel(nx, y, nz)
                                                if (nLevel < level - 1) {
                                                    addFluidUpdate(updates, nx, y, nz, blockId, newMeta)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Aplikowanie zmian
        updates.forEach { (chunkPos, list) ->
            val chunk = chunks[chunkPos]
            if (chunk != null) {
                var changed = false
                for ((pos, id, packedMeta) in list) {
                    // Sprawdzamy ponownie, czy miejsce jest wolne/nadpisywalne
                    val current = chunk.getBlock(pos.x, pos.y, pos.z)
                    // Porównujemy tylko poziomy (dolne 4 bity), ignorując kierunek rodzica przy sprawdzaniu czy nadpisać
                    val currentLevel = chunk.getMeta(pos.x, pos.y, pos.z) and 0xF
                    val newLevel = packedMeta and 0xF

                    // Nadpisujemy jeśli:
                    // 1. Blok to powietrze (0)
                    // 2. Blok to ten sam płyn, ale nowy poziom jest wyższy
                    // 3. Nowy blok to powietrze (id == 0) - czyli usuwanie/zanikanie
                    if (current == 0 || id == 0 || (fluidBlocks.contains(current) && currentLevel < newLevel)) {
                        chunk.setBlock(pos.x, pos.y, pos.z, id)
                        chunk.setMeta(pos.x, pos.y, pos.z, packedMeta)
                        changed = true
                    }
                }
                if (changed) {
                    chunk.modified = true
                    // Wymuszamy pełne odświeżenie (światło + mesh) aby usunąć "duchy" światła po zniknięciu lawy
                    // FIX: Przekazujemy listę zmienionych pozycji, aby poprawnie odświeżyć skosy (diagonals)
                    val changedPositions = list.map { it.first }
                    refreshChunkData(chunkPos.x, chunkPos.y, -1, -1, changedPositions)
                }
            }
        }
    }

    private fun addFluidUpdate(map: HashMap<Point, MutableList<Triple<BlockPos, Int, Int>>>, x: Int, y: Int, z: Int, id: Int, level: Int) {
        if (y < 0 || y >= 128) return
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
        val pt = Point(cx, cz)

        // Lokalne koordynaty
        var lx = x % 16; if (lx < 0) lx += 16
        var lz = z % 16; if (lz < 0) lz += 16

        map.computeIfAbsent(pt) { ArrayList() }.add(Triple(BlockPos(lx, y, lz), id, level))
    }

    private fun getFluidLevel(x: Int, y: Int, z: Int): Int {
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
        val chunk = chunks[Point(cx, cz)] ?: return 0
        var lx = x % 16; if (lx < 0) lx += 16
        var lz = z % 16; if (lz < 0) lz += 16
        return chunk.getMeta(lx, y, lz) and 0xF // Zwracamy tylko poziom (0-15)
    }

    private fun getFluidMeta(x: Int, y: Int, z: Int): Int {
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
        val chunk = chunks[Point(cx, cz)] ?: return 0
        var lx = x % 16; if (lx < 0) lx += 16
        var lz = z % 16; if (lz < 0) lz += 16
        return chunk.getMeta(lx, y, lz)
    }

    // Czyści ekran
    private fun clearGrid() {
        val bgColor = currentSkyColor
        Arrays.fill(backBuffer, bgColor)
        for (row in zBuffer) {
            Arrays.fill(row, Double.MAX_VALUE)
        }
    }

    fun loop() {
        Thread {
            var lastTime = System.nanoTime()
            val nsPerTick = 1000000000.0 / 30.0 // TPS (Ticki fizyki na sekundę)
            val nsPerFrame = 1000000000.0 / 120.0 // limit FPS
            var delta = 0.0

            while (running) {
                val now = System.nanoTime()
                val elapsedNs = now - lastTime
                delta += elapsedNs / nsPerTick
                lastTime = now

                // --- AUTO SAVE (Co 15 sekund) ---
                if (System.currentTimeMillis() - lastAutoSaveTime > 15000) {
                    var savedCount = 0
                    chunks.values.forEach { chunk ->
                        if (chunk.modified) {
                            chunkIO.saveChunk(chunk)
                            chunk.modified = false // Resetujemy flagę, bo na dysku jest już aktualna wersja
                            savedCount++
                        }
                    }
                    if (savedCount > 0) println("Auto-saved $savedCount chunks.")
                    lastAutoSaveTime = System.currentTimeMillis()
                }

                // --- DAY/NIGHT CYCLE ---
                // 24 minuty = 1440 sekund = 24 godziny w grze
                // 1 godzina w grze = 60 sekund realnych
                val elapsedSeconds = elapsedNs / 1_000_000_000.0
                val gameHoursPassed = elapsedSeconds / 60.0
                if (gameTime + gameHoursPassed >= 24.0) {
                    dayCounter += 1
                }
                gameTime = (gameTime + gameHoursPassed) % 24.0

                // Realistyczne wartości dla równonocy wiosennej (uśrednione)
                val sunriseStart = 5.30
                val dayFullStart = 7.00
                val sunsetStart = 17.30
                val nightFullStart = 19.00

                globalSunIntensity = when {
                    gameTime < sunriseStart || gameTime > nightFullStart -> 0.0
                    gameTime in sunriseStart..dayFullStart -> {
                        (gameTime - sunriseStart) / (dayFullStart - sunriseStart)
                    }
                    gameTime in dayFullStart..sunsetStart -> 1.0
                    gameTime in sunsetStart..nightFullStart -> {
                        1.0 - (gameTime - sunsetStart) / (nightFullStart - sunsetStart)
                    }
                    else -> 0.0
                }.coerceIn(0.0, 1.0)

                // Obliczanie koloru nieba
                val nightColor = Color(10, 10, 30).rgb
                val dayColor = Color(113, 144, 225).rgb

                // Płynne przejście tła (Noc <-> Dzień) bez pomarańczu (pomarańcz będzie w aurze słońca)
                // Używamy globalSunIntensity jako współczynnika (0.0 = noc, 1.0 = dzień)
                currentSkyColor = interpolateColor(nightColor, dayColor, globalSunIntensity)

                if (delta > 10) delta = 10.0 // Zabezpieczenie przed "spiralą śmierci" przy dużym lagu

                while (delta >= 1) {
                    processInput()
                    processSingleInput()
                    updateWorld()
                    cloudOffset += 1.0 / 30.0

                    gameTicks++
                    simulateFluids(gameTicks)

                    delta--

                    inputManager.resetFrameState()
                }

                clearGrid()
                render3D()

                // Kopiujemy bufor renderowania do bufora wyświetlania (Double Buffering)
                System.arraycopy(backBuffer, 0, pixels, 0, pixels.size)
                repaint()

                // --- FPS LIMITER ---
                val frameTime = System.nanoTime() - now
                if (frameTime < nsPerFrame) {
                    val sleepNs = (nsPerFrame - frameTime).toLong()
                    if (sleepNs > 0) {
                        Thread.sleep(sleepNs / 1000000, (sleepNs % 1000000).toInt())
                    }
                }
            }
        }.start()
    }

    // Prekalkulowane wartości dla Frustum Culling
    private val sectionRadius = sqrt(3.0) * 2.0 * cubeSize
    private val sectionSafeRadius = sectionRadius * 2.5 // Zwiększono margines, aby naprawić "wygryzanie" krawędzi

    private fun isSegmentVisible(secX: Int, secY: Int, secZ: Int): Boolean {
        val cx = if (secX >= 0) secX / 4 else (secX + 1) / 4 - 1
        val cz = if (secZ >= 0) secZ / 4 else (secZ + 1) / 4 - 1
        var localSecX = secX % 4; if (localSecX < 0) localSecX += 4
        var localSecZ = secZ % 4; if (localSecZ < 0) localSecZ += 4
        return isSectionVisible(cx, localSecX, secY, localSecZ, cz)
    }

    private fun rebuildVisibilityGraph(startSecX: Int, startSecY: Int, startSecZ: Int) {
        visibleSegmentsCount = 0

        // 16 bloków / 4 bloki na segment = 4 segmenty na chunk
        val maxRadius = renderDistance * 4
        val diameter = maxRadius * 2 + 1

        // Resetujemy token odwiedzin (szybsze niż czyszczenie tablicy)
        bfsVisitToken++
        if (bfsVisitToken == 0) bfsVisitToken = 1 // Unikamy 0

        // Upewniamy się, że tablica visited jest wystarczająco duża
        val requiredSize = diameter * 32 * diameter
        if (bfsVisited.size < requiredSize) {
            bfsVisited = IntArray(requiredSize)
            bfsRendered = IntArray(requiredSize)
        }
        if (bfsRendered.size < requiredSize) {
            bfsRendered = IntArray(requiredSize)
        }

        // Inicjalizacja kolejki
        var qHead = 0
        var qTail = 0

        // Dodajemy startNode
        bfsQueue[qTail++] = startSecX
        bfsQueue[qTail++] = startSecY
        bfsQueue[qTail++] = startSecZ

        // Oznaczamy startNode jako odwiedzony
        // Mapowanie (relativeX, y, relativeZ) -> index
        // relativeX = x - startSecX + maxRadius
        val startIndex = (maxRadius * 32 + startSecY) * diameter + maxRadius
        bfsVisited[startIndex] = bfsVisitToken

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

        while (qHead < qTail) {
            val currX = bfsQueue[qHead++]
            val currY = bfsQueue[qHead++]
            val currZ = bfsQueue[qHead++]

            // Obliczamy index dla obecnego segmentu, aby sprawdzić czy już go wyrenderowaliśmy
            // (Mogliśmy go dodać wcześniej jako "ścianę" z innego kierunku)
            val dxC = currX - startSecX
            val dzC = currZ - startSecZ
            val currIndex = ((dxC + maxRadius) * 32 + currY) * diameter + (dzC + maxRadius)

            if (bfsRendered[currIndex] != bfsVisitToken) {
                if (!isSegmentEmptyBySec(currX, currY, currZ)) {
                    if (visibleSegmentsCount * 3 >= visibleSegmentsFlat.size) {
                        visibleSegmentsFlat = visibleSegmentsFlat.copyOf(visibleSegmentsFlat.size * 2)
                    }
                    visibleSegmentsFlat[visibleSegmentsCount * 3] = currX
                    visibleSegmentsFlat[visibleSegmentsCount * 3 + 1] = currY
                    visibleSegmentsFlat[visibleSegmentsCount * 3 + 2] = currZ
                    visibleSegmentsCount++
                }
                bfsRendered[currIndex] = bfsVisitToken
            }

            // Pobieramy maskę okluzji obecnego segmentu
            val currMask = getSegmentOcclusionMask(currX, currY, currZ)

            for ((dir, exitBit, entryBit) in dirs) {
                val nx = currX + dir.first
                val ny = currY + dir.second
                val nz = currZ + dir.third

                if (ny < 0 || ny > 31) continue

                val dx = nx - startSecX
                val dz = nz - startSecZ
                if (abs(dx) > maxRadius || abs(dz) > maxRadius) continue

                val visitIndex = ((dx + maxRadius) * 32 + ny) * diameter + (dz + maxRadius)
                // Jeśli już odwiedziliśmy (zakolejkowaliśmy) ten segment, pomijamy
                if (bfsVisited[visitIndex] == bfsVisitToken) continue

                // 1. Sprawdzamy czy możemy wyjść z obecnego segmentu w tym kierunku
                // Jeśli ściana wyjściowa jest pełna, a my nie jesteśmy wewnątrz niej (kamera), to nie widzimy przez nią.
                // Wyjątek: Jesteśmy w segmencie startowym (kamera może być wewnątrz bloku/ściany).
                if (!debugNoclip && (currMask.toInt() and exitBit) != 0 && (currX != startSecX || currY != startSecY || currZ != startSecZ)) {
                    continue
                }

                // 2. Sprawdzamy czy możemy wejść do sąsiada
                val neighborMask = getSegmentOcclusionMask(nx, ny, nz)

                // Fix: Jeśli ściana wejściowa jest pełna, widzimy ją, ale nie propagujemy BFS dalej (chyba że noclip).
                if (debugNoclip || (neighborMask.toInt() and entryBit) == 0) { // Jeśli ściana wejściowa NIE jest pełna lub noclip...
                    // Oznaczamy jako odwiedzony (zakolejkowany) TYLKO gdy faktycznie wchodzimy do środka
                    bfsVisited[visitIndex] = bfsVisitToken

                    // ZABEZPIECZENIE: Sprawdzamy czy kolejka się nie przepełni
                    if (qTail + 3 >= bfsQueue.size) break

                    bfsQueue[qTail++] = nx
                    bfsQueue[qTail++] = ny
                    bfsQueue[qTail++] = nz
                } else { // Jeśli ściana wejściowa JEST pełna...
                    // Widzimy tę ścianę, więc musimy ją narysować, ale NIE oznaczamy jako visited (nie wchodzimy).
                    // Sprawdzamy bfsRendered, aby nie dodawać tej samej ściany wielokrotnie.
                    if (bfsRendered[visitIndex] != bfsVisitToken) {
                        if (!isSegmentEmptyBySec(nx, ny, nz)) {
                            if (visibleSegmentsCount * 3 >= visibleSegmentsFlat.size) {
                                visibleSegmentsFlat = visibleSegmentsFlat.copyOf(visibleSegmentsFlat.size * 2)
                            }
                            visibleSegmentsFlat[visibleSegmentsCount * 3] = nx
                            visibleSegmentsFlat[visibleSegmentsCount * 3 + 1] = ny
                            visibleSegmentsFlat[visibleSegmentsCount * 3 + 2] = nz
                            visibleSegmentsCount++
                        }
                        bfsRendered[visitIndex] = bfsVisitToken
                    }
                }
            }
        }
    }

    private fun interpolateColor(c1: Int, c2: Int, fraction: Double): Int {
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF
        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF
        val r = (r1 + (r2 - r1) * fraction).toInt()
        val g = (g1 + (g2 - g1) * fraction).toInt()
        val b = (b1 + (b2 - b1) * fraction).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    private fun render3D() {
        val opaqueTrianglesToRaster = mutableListOf<Triangle3d>()
        val transparentTrianglesToRaster = mutableListOf<Triangle3d>()
        // Obliczamy sin/cos raz na klatkę, zamiast dla każdego wierzchołka!
        cosYaw = cos(yaw)
        sinYaw = sin(yaw)
        cosPitch = cos(pitch)
        sinPitch = sin(pitch)

        renderSky()

        val segmentSize = 4 * cubeSize
        // Pozycja kamery w koordynatach segmentów
        val camSecX = floor(camX / segmentSize).toInt()
        val camSecY = floor((viewY + 10.0) / segmentSize).toInt()
        val camSecZ = floor(camZ / segmentSize).toInt()

        // Aktualizacja grafu widoczności tylko gdy zmienimy segment lub świat się zmieni
        if (visibilityGraphDirty || camSecX != lastCamSecX || camSecY != lastCamSecY || camSecZ != lastCamSecZ ||
            abs(yaw - lastYaw) > 0.001 || abs(pitch - lastPitch) > 0.001) {
            // Fix: Clamp Y to 0..15 to ensure BFS starts within valid world bounds even if player is outside
            rebuildVisibilityGraph(camSecX, camSecY.coerceIn(0, 31), camSecZ)
            lastCamSecX = camSecX
            lastCamSecY = camSecY
            lastCamSecZ = camSecZ
            lastYaw = yaw
            lastPitch = pitch
            visibilityGraphDirty = false
        }

        // Iterujemy po wcześniej obliczonych widocznych segmentach
        for (i in 0 until visibleSegmentsCount) {
            val currX = visibleSegmentsFlat[i * 3]
            val currY = visibleSegmentsFlat[i * 3 + 1]
            val currZ = visibleSegmentsFlat[i * 3 + 2]

            // Konwersja globalnych współrzędnych segmentu na Chunk + Local Segment
            val cx = if (currX >= 0) currX / 4 else (currX + 1) / 4 - 1
            val cz = if (currZ >= 0) currZ / 4 else (currZ + 1) / 4 - 1
            var secX = currX % 4; if (secX < 0) secX += 4
            val secY = currY
            var secZ = currZ % 4; if (secZ < 0) secZ += 4

            val mesh = chunkMeshes[Point(cx, cz)]
            if (mesh != null) {
                val index = secY * 16 + secZ * 4 + secX
                if (index in 0 until 512) {
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
                                    val transformedTri = Triangle3d(t1, t2, t3, tri.color, tri.lightLevel)
                                    if (transformedTri.color.alpha < 255) {
                                        transparentTrianglesToRaster.add(transformedTri)
                                    } else {
                                        opaqueTrianglesToRaster.add(transformedTri)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Projekcja i Rasteryzacja (Bloki nieprzezroczyste)
        for (tri in opaqueTrianglesToRaster) {
            val clippedTriangles = clipTriangleAgainstPlane(tri)
            for (clipped in clippedTriangles) {
                val p1_2d = project(clipped.p1)
                val p2_2d = project(clipped.p2)
                val p3_2d = project(clipped.p3)

                // Aplikujemy oświetlenie dnia/nocy oraz cienie
                // clipped.lightLevel to wartość 0-16 z mapy światła
                // globalSunIntensity to 0.0-1.0 z cyklu dnia
                // Zmiana: Poziom 0 to 1/4 jasności (0.25), poziom 16 to pełna jasność (1.0)
                val packedLight = clipped.lightLevel
                val skyLight = (packedLight shr 4) and 0xF
                val blockLight = packedLight and 0xF
                val effectiveLight = maxOf(skyLight * globalSunIntensity, blockLight.toDouble())
                val lightFactor = (minLightFactor + 0.75 * (effectiveLight / 15.0) + FullbrightFactor).coerceIn(0.0, 1.0)

                val r = (clipped.color.red * lightFactor).toInt().coerceIn(0, 255)
                val g = (clipped.color.green * lightFactor).toInt().coerceIn(0, 255)
                val b = (clipped.color.blue * lightFactor).toInt().coerceIn(0, 255)

                fillTriangle(p1_2d, p2_2d, p3_2d, clipped.p1, clipped.p2, clipped.p3, Color(r, g, b))
            }
        }

        // 4. Projekcja i Rasteryzacja (Bloki przezroczyste)
        // Sortowanie od tyłu do przodu dla poprawnego blendingu
        transparentTrianglesToRaster.sortByDescending { (it.p1.z + it.p2.z + it.p3.z) / 3.0 }

        for (tri in transparentTrianglesToRaster) {
            val clippedTriangles = clipTriangleAgainstPlane(tri)
            for (clipped in clippedTriangles) {
                val p1_2d = project(clipped.p1)
                val p2_2d = project(clipped.p2)
                val p3_2d = project(clipped.p3)

                val packedLight = clipped.lightLevel
                val skyLight = (packedLight shr 4) and 0xF
                val blockLight = packedLight and 0xF
                val effectiveLight = maxOf(skyLight * globalSunIntensity, blockLight.toDouble())
                val lightFactor = (minLightFactor + 0.75 * (effectiveLight / 15.0) + FullbrightFactor).coerceIn(0.0, 1.0)

                val r = (clipped.color.red * lightFactor).toInt().coerceIn(0, 255)
                val g = (clipped.color.green * lightFactor).toInt().coerceIn(0, 255)
                val b = (clipped.color.blue * lightFactor).toInt().coerceIn(0, 255)

                fillTransparentTriangle(p1_2d, p2_2d, p3_2d, clipped.p1, clipped.p2, clipped.p3, Color(r, g, b, clipped.color.alpha))
            }
        }

        renderClouds()

        if (showChunkBorders) {
            renderChunkBorders()
        }

        val hit = getTargetBlock()
        if (hit != null) {
            drawSelectionBox(hit.blockPos)
        }

        if (debugXray) {
            renderXRay()
        }
    }

    private fun renderSky() {
        // Obrót nieba (symulacja obrotu ziemi)
        // 6:00 = 0 stopni (Wschód), 12:00 = 90 stopni (Zenit), 18:00 = 180 stopni (Zachód)
        val rot = (gameTime / 24.0) * 2.0 * Math.PI
        val cosRot = cos(rot)
        val sinRot = sin(rot)

        // --- 1. Rysowanie Gwiazd (tylko w nocy) ---
        if (globalSunIntensity < 1.0) {
            val darkness = 1.0 - globalSunIntensity

            for (star in stars) {
                // Obliczamy widoczność konkretnej gwiazdy
                val minDarknessForVisibility = 0.5
                val maxDarknessForVisibility = 0.99
                val threshold = minDarknessForVisibility + (star.ao * (maxDarknessForVisibility - minDarknessForVisibility))
                // Jeśli jest wystarczająco ciemno dla tej gwiazdy
                if (darkness > threshold) {
                    // Płynne wejście gwiazdy (mnożnik 4.0 sprawia, że pojedyncza gwiazda nie "wskakuje", tylko płynnie się rozjaśnia)
                    val starAlpha = ((darkness - threshold) * 4.0).coerceIn(0.0, 1.0)
                    val c = (255 * starAlpha).toInt()
                    val starColor = (c shl 16) or (c shl 8) or c

                    // 1. Obrót sfery niebieskiej (wokół osi X dla uproszczenia wschodu/zachodu)
                    val ry = star.y * cosRot - star.z * sinRot
                    val rz = star.z * cosRot + star.y * sinRot
                    val rx = star.x

                    // 2. Obrót kamery (Yaw/Pitch) - ignorujemy pozycję gracza (gwiazdy są w nieskończoności)
                    var x = rx * cosYaw - rz * sinYaw
                    var z = rz * cosYaw + rx * sinYaw
                    var y = ry

                    val y2 = y * cosPitch - z * sinPitch
                    val z2 = z * cosPitch + y * sinPitch
                    y = y2
                    z = z2

                    if (z > 0.1) {
                        val proj = project(Vector3d(x, y, z))
                        val px = proj.x.toInt()
                        val py = proj.y.toInt()

                        if (px in 0 until baseCols && py in 0 until baseRows) {
                            backBuffer[py * imageWidth + px] = starColor
                        }
                    }
                }
            }
        }

        // --- 2. Rysowanie Słońca i Księżyca ---

        // Pozycja Słońca (krąży wokół osi X)
        // Promień orbity duży, żeby było "w nieskończoności"
        val orbitRadius = 800.0

        // Kąt słońca: odejmujemy PI/2, żeby o 12:00 (rot=PI) było na górze, a o 6:00 (rot=PI/2) wschodziło
        // Dopasowanie do gameTime:
        // gameTime 6.0 -> rot = PI/2. Chcemy wschód (X+).
        // gameTime 12.0 -> rot = PI. Chcemy górę (Y+).
        // Używamy rot obliczonego wcześniej: (gameTime / 24.0) * 2PI
        // Przesuwamy fazę o -PI/2, żeby 6:00 było startem
        val sunOrbitAngle = rot - (Math.PI / 2.0)

        val sunX = cos(sunOrbitAngle) * orbitRadius
        val sunY = sin(sunOrbitAngle) * orbitRadius
        val sunZ = 0.0 // Słońce porusza się w płaszczyźnie równika

        // Księżyc jest dokładnie naprzeciwko
        val moonX = -sunX
        val moonY = -sunY
        val moonZ = 0.0

        fun drawCelestialBody(bx: Double, by: Double, bz: Double, radius: Double, color: Int, drawHalo: Boolean) {
            // Transformacja kamery (taka sama jak dla gwiazd)
            var x = bx * cosYaw - bz * sinYaw
            var z = bz * cosYaw + bx * sinYaw
            var y = by

            val y2 = y * cosPitch - z * sinPitch
            val z2 = z * cosPitch + y * sinPitch
            y = y2
            z = z2

            if (z > 1.0) { // Jeśli jest przed kamerą
                val proj = project(Vector3d(x, y, z))
                val px = proj.x.toInt()
                val py = proj.y.toInt()

                // Rysowanie Halo (Aura) - tylko dla słońca
                if (drawHalo) {
                    val haloRadius = radius * 3.0
                    val haloColorStart = if (globalSunIntensity < 0.5) Color(255, 100, 20).rgb else Color(255, 255, 200).rgb

                    val minHx = (px - haloRadius).toInt().coerceIn(0, baseCols)
                    val maxHx = (px + haloRadius).toInt().coerceIn(0, baseCols)
                    val minHy = (py - haloRadius).toInt().coerceIn(0, baseRows)
                    val maxHy = (py + haloRadius).toInt().coerceIn(0, baseRows)

                    for (hy in minHy until maxHy) {
                        for (hx in minHx until maxHx) {
                            val dx = hx - px
                            val dy = hy - py
                            val distSq = dx * dx + dy * dy
                            if (distSq < haloRadius * haloRadius) {
                                val dist = sqrt(distSq.toDouble())
                                val alpha = (1.0 - (dist / haloRadius)).coerceIn(0.0, 1.0)
                                // Additive blending (proste dodawanie koloru)
                                val existing = backBuffer[hy * imageWidth + hx]
                                backBuffer[hy * imageWidth + hx] = interpolateColor(existing, haloColorStart, alpha * 0.6)
                            }
                        }
                    }
                }

                // Rysowanie ciała (kwadrat)
                val minBx = (px - radius).toInt().coerceIn(0, baseCols)
                val maxBx = (px + radius).toInt().coerceIn(0, baseCols)
                val minBy = (py - radius).toInt().coerceIn(0, baseRows)
                val maxBy = (py + radius).toInt().coerceIn(0, baseRows)

                for (by_iter in minBy until maxBy) {
                    for (bx_iter in minBx until maxBx) {
                        backBuffer[by_iter * imageWidth + bx_iter] = color
                    }
                }
            }
        }

        // Rysuj Słońce (tylko jeśli jest nad horyzontem lub blisko)
        if (sunY > -200.0) {
            drawCelestialBody(sunX, sunY, sunZ, 20.0, Color(255, 255, 200).rgb, true)
        }

        // Rysuj Księżyc
        if (moonY > -200.0) {
            drawCelestialBody(moonX, moonY, moonZ, 15.0, Color(200, 200, 220).rgb, false)
        }
    }

    private fun renderClouds() {
        val cloudRange = renderDistance * 2.5 * 16.0
        val startCx = floor((camX - cloudRange - cloudOffset) / 2.0).toInt()
        val endCx = floor((camX + cloudRange - cloudOffset) / 2.0).toInt()
        val startCz = floor((camZ - cloudRange) / 2.0).toInt()
        val endCz = floor((camZ + cloudRange) / 2.0).toInt()

        val cloudTriangles = ArrayList<Triangle3d>()

        data class CloudInstance(val x: Double, val z: Double, val distSq: Double)
        val clouds = ArrayList<CloudInstance>()

        // Stałe do cullingu
        val aspect = baseCols.toDouble() / baseRows.toDouble()
        val cloudHeight = 100.0 * cubeSize
        val cullMargin = 4.0 * cubeSize // Margines, żeby chmury nie znikały nagle na krawędziach

        for (cx in startCx..endCx) {
            for (cz in startCz..endCz) {
                // 8x8 noise per 16x16 chunk (16 blocks).
                // 1 cloud unit = 2 blocks. 8 units = 16 blocks.
                // Scale 0.15 gives reasonable variation within the 8-unit span.
                val n = noise.noise(cx * 0.15, cz * 0.15)
                if (n > 0.25) {
                    val wx = cx * 2.0 + cloudOffset
                    val wz = cz * 2.0

                    // --- FRUSTUM CULLING ---
                    // Transformacja pozycji chmury do przestrzeni kamery
                    var tx = wx - camX
                    var ty = cloudHeight - viewY
                    var tz = wz - camZ

                    // Obrót Yaw
                    val tx2 = tx * cosYaw - tz * sinYaw
                    val tz2 = tz * cosYaw + tx * sinYaw
                    tx = tx2
                    tz = tz2

                    // Obrót Pitch
                    val ty2 = ty * cosPitch - tz * sinPitch
                    val tz3 = tz * cosPitch + ty * sinPitch
                    ty = ty2
                    tz = tz3

                    // Sprawdzenie czy chmura jest w widoku
                    // 1. Czy jest za kamerą?
                    if (tz < nearPlaneZ - cullMargin) continue

                    // 2. Czy jest poza ekranem w poziomie? (Zakładamy FOV ~90, więc tan(45)=1.0)
                    if (abs(tx) > tz * 1.2 + cullMargin) continue

                    // 3. Czy jest poza ekranem w pionie?
                    if (abs(ty) > tz * (1.2 / aspect) + cullMargin) continue
                    // -----------------------

                    val distSq = (wx - camX) * (wx - camX) + (wz - camZ) * (wz - camZ)
                    if (distSq < cloudRange * cloudRange) {
                        clouds.add(CloudInstance(wx, wz, distSq))
                    }
                }
            }
        }

        // Sort back-to-front for transparency
        clouds.sortByDescending { it.distSq }

        val yBottom = 100.0 * cubeSize
        val yTop = yBottom + 3.0 * cubeSize
        val boxWidth = 2.0 * cubeSize
        val boxDepth = 2.0 * cubeSize
        val color = Color(255, 255, 255)

        for (cloud in clouds) {
            val x = cloud.x
            val z = cloud.z

            val p0 = Vector3d(x, yBottom, z)
            val p1 = Vector3d(x + boxWidth, yBottom, z)
            val p2 = Vector3d(x + boxWidth, yTop, z)
            val p3 = Vector3d(x, yTop, z)
            val p4 = Vector3d(x, yBottom, z + boxDepth)
            val p5 = Vector3d(x + boxWidth, yBottom, z + boxDepth)
            val p6 = Vector3d(x + boxWidth, yTop, z + boxDepth)
            val p7 = Vector3d(x, yTop, z + boxDepth)

            addCloudQuad(cloudTriangles, p0, p1, p2, p3, color) // Front
            addCloudQuad(cloudTriangles, p5, p4, p7, p6, color) // Back
            addCloudQuad(cloudTriangles, p4, p0, p3, p7, color) // Left
            addCloudQuad(cloudTriangles, p1, p5, p6, p2, color) // Right
            addCloudQuad(cloudTriangles, p3, p2, p6, p7, color) // Top
            addCloudQuad(cloudTriangles, p4, p5, p1, p0, color) // Bottom
        }

        for (tri in cloudTriangles) {
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
                val clipped = clipTriangleAgainstPlane(Triangle3d(t1, t2, t3, tri.color, 0))
                for (c in clipped) {
                    val effectiveLight = 15.0 * globalSunIntensity
                    val lightFactor = 0.25 + 0.75 * (effectiveLight / 15.0)
                    val r = (c.color.red * lightFactor).toInt().coerceIn(0, 255)
                    val g = (c.color.green * lightFactor).toInt().coerceIn(0, 255)
                    val b = (c.color.blue * lightFactor).toInt().coerceIn(0, 255)

                    val p1_2d = project(c.p1)
                    val p2_2d = project(c.p2)
                    val p3_2d = project(c.p3)
                    fillTriangleAlpha(p1_2d, p2_2d, p3_2d, c.p1, c.p2, c.p3, Color(r, g, b))
                }
            }
        }
    }

    private fun addCloudQuad(list: MutableList<Triangle3d>, p1: Vector3d, p2: Vector3d, p3: Vector3d, p4: Vector3d, c: Color) {
        list.add(Triangle3d(p1, p2, p3, c, 0))
        list.add(Triangle3d(p1, p3, p4, c, 0))
    }

    private fun fillTransparentTriangle(p1: Vector3d, p2: Vector3d, p3: Vector3d, v1: Vector3d, v2: Vector3d, v3: Vector3d, color: Color) {
        val minX = minOf(p1.x, p2.x, p3.x).toInt().coerceIn(0, baseCols)
        val maxX = maxOf(p1.x, p2.x, p3.x).toInt().coerceIn(0, baseCols)
        val minY = minOf(p1.y, p2.y, p3.y).toInt().coerceIn(0, baseRows)
        val maxY = maxOf(p1.y, p2.y, p3.y).toInt().coerceIn(0, baseRows)

        val v0x = p2.x - p1.x; val v0y = p2.y - p1.y
        val v1x = p3.x - p1.x; val v1y = p3.y - p1.y

        val d00 = v0x * v0x + v0y * v0y
        val d01 = v0x * v1x + v0y * v1y
        val d11 = v1x * v1x + v1y * v1y

        val denom = d00 * d11 - d01 * d01
        if (abs(denom) < 1e-9) return

        val invDenom = 1.0 / denom

        fun getBarycentric(px: Double, py: Double): Pair<Double, Double> {
            val v2x = px - p1.x; val v2y = py - p1.y
            val d20 = v2x * v0x + v2y * v0y
            val d21 = v2x * v1x + v2y * v1y
            val v = (d11 * d20 - d01 * d21) * invDenom
            val w = (d00 * d21 - d01 * d20) * invDenom
            return Pair(v, w)
        }

        val startX = minX + 0.5; val startY = minY + 0.5
        val (vStart, wStart) = getBarycentric(startX, startY)
        val (vNextX, wNextX) = getBarycentric(startX + 1.0, startY)
        val (vNextY, wNextY) = getBarycentric(startX, startY + 1.0)

        val z1Inv = 1.0 / v1.z; val z2Inv = 1.0 / v2.z; val z3Inv = 1.0 / v3.z
        val ao1z = v1.ao * z1Inv; val ao2z = v2.ao * z2Inv; val ao3z = v3.ao * z3Inv

        val dvdx = vNextX - vStart; val dwdx = wNextX - wStart
        val dvdy = vNextY - vStart; val dwdy = wNextY - wStart

        val alpha = color.alpha / 255.0
        val invAlpha = 1.0 - alpha
        val r = color.red; val g = color.green; val b = color.blue

        var rowV = vStart; var rowW = wStart

        for (y in minY..maxY) {
            var v = rowV; var w = rowW
            var pixelIndex = y * imageWidth + minX

            for (x in minX..maxX) {
                if (v >= 0.0 && w >= 0.0 && (v + w) <= 1.0) {
                    val u = 1.0 - v - w
                    val zRecip = u * z1Inv + v * z2Inv + w * z3Inv
                    val depth = 1.0 / zRecip

                    if (depth < zBuffer[y][x]) {
                        val aoRecip = u * ao1z + v * ao2z + w * ao3z
                        val ao = aoRecip * depth
                        val finalR = (r * ao).toInt(); val finalG = (g * ao).toInt(); val finalB = (b * ao).toInt()

                        val bg = backBuffer[pixelIndex]
                        val newR = (finalR * alpha + ((bg shr 16) and 0xFF) * invAlpha).toInt()
                        val newG = (finalG * alpha + ((bg shr 8) and 0xFF) * invAlpha).toInt()
                        val newB = (finalB * alpha + (bg and 0xFF) * invAlpha).toInt()
                        backBuffer[pixelIndex] = (newR shl 16) or (newG shl 8) or newB
                    }
                }
                v += dvdx; w += dwdx; pixelIndex++
            }
            rowV += dvdy; rowW += dwdy
        }
    }

    private fun fillTriangleAlpha(p1: Vector3d, p2: Vector3d, p3: Vector3d, v1: Vector3d, v2: Vector3d, v3: Vector3d, color: Color) {
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

        val startX = minX + 0.5
        val startY = minY + 0.5
        val (vStart, wStart) = getBarycentric(startX, startY)

        val (vNextX, wNextX) = getBarycentric(startX + 1.0, startY)
        val (vNextY, wNextY) = getBarycentric(startX, startY + 1.0)

        val dvdx = vNextX - vStart
        val dwdx = wNextX - wStart
        val dvdy = vNextY - vStart
        val dwdy = wNextY - wStart

        val z1Inv = 1.0 / v1.z
        val z2Inv = 1.0 / v2.z
        val z3Inv = 1.0 / v3.z

        val alpha = 0.5
        val invAlpha = 1.0 - alpha
        val r = color.red
        val g = color.green
        val b = color.blue

        var rowV = vStart
        var rowW = wStart

        for (y in minY..maxY) {
            var v = rowV
            var w = rowW
            var pixelIndex = y * imageWidth + minX

            for (x in minX..maxX) {
                // Usunięto margines 0.001, aby uniknąć nakładania się trójkątów (double-blending)
                if (v >= 0.0 && w >= 0.0 && (v + w) <= 1.0) {
                    val u = 1.0 - v - w
                    val zRecip = u * z1Inv + v * z2Inv + w * z3Inv
                    val depth = 1.0 / zRecip
                    if (depth < zBuffer[y][x]) {
                        val bg = backBuffer[pixelIndex]
                        val newR = (r * alpha + ((bg shr 16) and 0xFF) * invAlpha).toInt()
                        val newG = (g * alpha + ((bg shr 8) and 0xFF) * invAlpha).toInt()
                        val newB = (b * alpha + (bg and 0xFF) * invAlpha).toInt()
                        backBuffer[pixelIndex] = (newR shl 16) or (newG shl 8) or newB
                    }
                }
                v += dvdx
                w += dwdx
                pixelIndex++
            }
            rowV += dvdy
            rowW += dwdy
        }
    }

    // Pobiera maskę okluzji (Byte)
    private fun getSegmentOcclusionMask(secX: Int, secY: Int, secZ: Int): Byte {
        if (secY < 0 || secY > 31) return 0
        // Konwersja globalnych segmentów na Chunk + Local Segment
        val cx = if (secX >= 0) secX / 4 else (secX + 1) / 4 - 1
        val cz = if (secZ >= 0) secZ / 4 else (secZ + 1) / 4 - 1

        val occlusionData = chunkOcclusion[Point(cx, cz)] ?: return 0

        var localSecX = secX % 4; if (localSecX < 0) localSecX += 4
        var localSecZ = secZ % 4; if (localSecZ < 0) localSecZ += 4
        val index = secY * 16 + localSecZ * 4 + localSecX
        return occlusionData[index]
    }

    private fun isSegmentEmptyBySec(secX: Int, secY: Int, secZ: Int): Boolean {
        if (secY < 0 || secY > 31) return true
        val cx = if (secX >= 0) secX / 4 else (secX + 1) / 4 - 1
        val cz = if (secZ >= 0) secZ / 4 else (secZ + 1) / 4 - 1

        val mesh = chunkMeshes[Point(cx, cz)] ?: return true

        var localSecX = secX % 4; if (localSecX < 0) localSecX += 4
        var localSecZ = secZ % 4; if (localSecZ < 0) localSecZ += 4
        val index = secY * 16 + localSecZ * 4 + localSecX
        return mesh[index].isEmpty()
    }

    private fun isSectionVisible(cx: Int, secX: Int, secY: Int, secZ: Int, cz: Int): Boolean {
        // Sprawdzamy, czy kamera znajduje się wewnątrz AABB (pudełka) tej sekcji.
        // Jeśli tak, sekcja musi być widoczna, więc pomijamy dalsze testy.
        val xMin = (cx * 16 + secX * 4) * cubeSize - cubeSize / 2.0
        val xMax = (cx * 16 + (secX + 1) * 4) * cubeSize - cubeSize / 2.0
        val yMin = (secY * 4) * cubeSize - 10.0 - cubeSize / 2.0
        val yMax = ((secY + 1) * 4) * cubeSize - 10.0 - cubeSize / 2.0
        val zMin = (cz * 16 + secZ * 4) * cubeSize - cubeSize / 2.0
        val zMax = (cz * 16 + (secZ + 1) * 4) * cubeSize - cubeSize / 2.0

        if (camX >= xMin && camX < xMax && camY >= yMin && camY < yMax && camZ >= zMin && camZ < zMax) {
            return true // Gracz jest w środku, sekcja musi być widoczna
        }

        // Środek sekcji w świecie 3D
        // cx * 16 + secX * 4 (początek sekcji) + 2 (środek sekcji 4-blokowej)
        val centerX = (cx * 16 + secX * 4 + 2) * cubeSize
        val centerY = (secY * 4 + 2) * cubeSize - 10.0
        val centerZ = (cz * 16 + secZ * 4 + 2) * cubeSize

        // Używamy pre-kalkulowanego promienia
        val radius = sectionRadius

        // Transformacja punktu środkowego do przestrzeni kamery (uproszczona wersja transform())
        var x = centerX - camX
        var y = centerY - viewY
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

        val safeRadius = sectionSafeRadius

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
        var y = v.y - viewY
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
            result.add(Triangle3d(output[0], output[1], output[2], tri.color, tri.lightLevel))
        } else if (output.size == 4) {
            // Jeśli powstał czworokąt, dzielimy go na dwa trójkąty
            result.add(Triangle3d(output[0], output[1], output[2], tri.color, tri.lightLevel))
            result.add(Triangle3d(output[0], output[2], output[3], tri.color, tri.lightLevel))
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

    private fun renderXRay() {
        val currentChunkX = floor(camX / 32.0).toInt()
        val currentChunkZ = floor(camZ / 32.0).toInt()
        val radius = renderDistance

        for (cx in currentChunkX - radius..currentChunkX + radius) {
            for (cz in currentChunkZ - radius..currentChunkZ + radius) {
                val chunk = chunks[Point(cx, cz)] ?: continue

                for (i in 0 until 512) {
                    val secY = i / 16
                    val rem = i % 16
                    val secZ = rem / 4
                    val secX = rem % 4

                    if (isSectionVisible(cx, secX, secY, secZ, cz)) {
                        val startX = secX * 4
                        val startY = secY * 4
                        val startZ = secZ * 4

                        for (ly in startY until startY + 4) {
                            val yOffset = ly * 256
                            for (lz in startZ until startZ + 4) {
                                val zOffset = lz * 16
                                for (lx in startX until startX + 4) {
                                    val color = chunk.blocks[lx + zOffset + yOffset]
                                    if (oreColors.contains(color)) {
                                        val wx = cx * 16 + lx
                                        val wz = cz * 16 + lz
                                        drawXRayBox(wx, ly, wz, Color(color))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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

        for (cx in currentChunkX - debugChunkRenderDistance..currentChunkX + debugChunkRenderDistance) {
            for (cz in currentChunkZ - debugChunkRenderDistance..currentChunkZ + debugChunkRenderDistance) {
                val xMin = cx.toDouble() * 16.0 * cubeSize - offset
                val xMax = (cx + 1).toDouble() * 16.0 * cubeSize - offset
                val zMin = cz.toDouble() * 16.0 * cubeSize - offset
                val zMax = (cz + 1).toDouble() * 16.0 * cubeSize - offset

                // Rysowanie granic chunka (Niebieski)
                val yMinChunk = -10.0 - offset
                val yMaxChunk = 128 * cubeSize - 10.0 - offset
                drawBox(xMin, xMax, yMinChunk, yMaxChunk, zMin, zMax, chunkColor)

                // Rysowanie granic sekcji (Żółty)
                for (i in 0 until 512) {
                    val secY = i / 16
                    val rem = i % 16
                    val secZ = rem / 4
                    val secX = rem % 4

                    val xMinSec = (cx.toDouble() * 16.0 + secX * 4.0) * cubeSize - offset
                    val xMaxSec = (cx.toDouble() * 16.0 + (secX + 1) * 4.0) * cubeSize - offset
                    val yMinSec = (secY * 4) * cubeSize - 10.0 - offset
                    val yMaxSec = ((secY + 1) * 4) * cubeSize - 10.0 - offset
                    val zMinSec = (cz.toDouble() * 16.0 + secZ * 4.0) * cubeSize - offset
                    val zMaxSec = (cz.toDouble() * 16.0 + (secZ + 1) * 4.0) * cubeSize - offset

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

        // --- Fluid Hitbox Correction ---
        val rawBlock = getRawBlock(block.x, block.y, block.z)
        var height = 1.0

        if (fluidBlocks.contains(rawBlock)) {
            val myLevel = getFluidLevel(block.x, block.y, block.z)
            val blockAbove = getRawBlock(block.x, block.y + 1, block.z)
            val amIFull = (blockAbove == rawBlock)
            height = if (amIFull) 1.0 else (myLevel / 9.0).coerceAtLeast(0.1)
        }

        val bottomY = y - d
        val topY = (y - cubeSize / 2.0) + (cubeSize * height) + 0.01

        val c0 = Vector3d(x - d, bottomY, z - d)
        val c1 = Vector3d(x + d, bottomY, z - d)
        val c2 = Vector3d(x + d, topY, z - d)
        val c3 = Vector3d(x - d, topY, z - d)
        val c4 = Vector3d(x - d, bottomY, z + d)
        val c5 = Vector3d(x + d, bottomY, z + d)
        val c6 = Vector3d(x + d, topY, z + d)
        val c7 = Vector3d(x - d, topY, z + d)

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

    private fun drawXRayBox(x: Int, y: Int, z: Int, color: Color) {
        val wx = x * cubeSize
        val wy = y * cubeSize - 10.0
        val wz = z * cubeSize
        val d = cubeSize / 2.0

        val c0 = Vector3d(wx - d, wy - d, wz - d)
        val c1 = Vector3d(wx + d, wy - d, wz - d)
        val c2 = Vector3d(wx + d, wy + d, wz - d)
        val c3 = Vector3d(wx - d, wy + d, wz - d)
        val c4 = Vector3d(wx - d, wy - d, wz + d)
        val c5 = Vector3d(wx + d, wy - d, wz + d)
        val c6 = Vector3d(wx + d, wy + d, wz + d)
        val c7 = Vector3d(wx - d, wy + d, wz + d)

        // Front
        drawLine3D(c0, c1, color, true); drawLine3D(c1, c2, color, true)
        drawLine3D(c2, c3, color, true); drawLine3D(c3, c0, color, true)
        // Back
        drawLine3D(c4, c5, color, true); drawLine3D(c5, c6, color, true)
        drawLine3D(c6, c7, color, true); drawLine3D(c7, c4, color, true)
        // Connecting
        drawLine3D(c0, c4, color, true); drawLine3D(c1, c5, color, true)
        drawLine3D(c2, c6, color, true); drawLine3D(c3, c7, color, true)
    }

    private fun drawLine3D(p1: Vector3d, p2: Vector3d, color: Color, ignoreDepth: Boolean = false) {
        val t1 = transform(p1)
        val t2 = transform(p2)

        if (t1.z < nearPlaneZ && t2.z < nearPlaneZ) return

        var v1 = t1
        var v2 = t2

        if (v1.z < nearPlaneZ) v1 = intersectPlane(v1, v2, nearPlaneZ)
        if (v2.z < nearPlaneZ) v2 = intersectPlane(v2, v1, nearPlaneZ)

        val proj1 = project(v1)
        val proj2 = project(v2)

        rasterizeLine(proj1, proj2, color, ignoreDepth)
    }

    private fun rasterizeLine(p1: Vector3d, p2: Vector3d, color: Color, ignoreDepth: Boolean = false) {
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
                if (currZ < zBuffer[iy0][ix0] || ignoreDepth) {
                    backBuffer[iy0 * imageWidth + ix0] = color.rgb
                    if (!ignoreDepth) zBuffer[iy0][ix0] = currZ
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

        // Pre-calculate 1/Z and AO/Z for perspective correct interpolation
        val z1Inv = 1.0 / v1.z
        val z2Inv = 1.0 / v2.z
        val z3Inv = 1.0 / v3.z

        val ao1z = v1.ao * z1Inv
        val ao2z = v2.ao * z2Inv
        val ao3z = v3.ao * z3Inv

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

                    // FIX: Perspective Correct Interpolation
                    // Interpolujemy odwrotność Z (1/z), a nie samo Z
                    val zRecip = u * z1Inv + v * z2Inv + w * z3Inv
                    val depth = 1.0 / zRecip

                    // Z-Buffer Test: Rysujemy tylko jeśli nowy piksel jest bliżej niż obecny
                    if (depth < zBuffer[y][x]) {
                        zBuffer[y][x] = depth
                        // Interpolujemy (AO/Z), a potem mnożymy przez Z piksela
                        val aoRecip = u * ao1z + v * ao2z + w * ao3z
                        val ao = aoRecip * depth

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
            val timeText = String.format("Time: %.2fzł (Intensity: %.2f) (Day $dayCounter)", gameTime, globalSunIntensity)

            g2d.color = Color(0.82f, 0.82f, 0.82f, 0.75f)
            g2d.fillRect(5, 15, fm.stringWidth(chunkText) + 5, fm.ascent)
            g2d.fillRect(5, fm.ascent + 15, fm.stringWidth(posText) + 5, fm.ascent)
            g2d.fillRect(5, fm.ascent*2 + 15, fm.stringWidth(timeText) + 5, fm.ascent)

            g2d.color = Color.WHITE
            g2d.drawString(chunkText, 10, fm.ascent + 10)
            g2d.drawString(posText, 10, fm.ascent*2 + 10)
            g2d.drawString(timeText, 10, fm.ascent*3 + 10)

            // Informacje o bloku, na który patrzy gracz
            val hit = getTargetBlock()
            if (hit != null) {
                val target = hit.blockPos
                val blockColor = getBlock(target.x, target.y, target.z)

                // Obliczamy pozycję sąsiada w zależności od ściany, na którą patrzymy
                var nx = target.x; var ny = target.y; var nz = target.z
                when(hit.faceIndex) {
                    0 -> nz-- // Front (Z-) -> sąsiad Z-1
                    1 -> nz++ // Back (Z+) -> sąsiad Z+1
                    2 -> nx-- // Left (X-) -> sąsiad X-1
                    3 -> nx++ // Right (X+) -> sąsiad X+1
                    4 -> ny++ // Top (Y+) -> sąsiad Y+1
                    5 -> ny-- // Bottom (Y-) -> sąsiad Y-1
                }
                val rawLight = getLight(nx, ny, nz)
                val skyLight = (rawLight shr 4) and 0xF
                val blockLight = rawLight and 0xF
                val effectiveLight = maxOf(skyLight * globalSunIntensity, blockLight.toDouble()).toInt()

                val colorHex = if (blockColor != null) String.format("#%06X", (0xFFFFFF and blockColor.rgb)) else "N/A"
                val targetText = "Target: [${target.x}, ${target.y}, ${target.z}] Color: $colorHex Face: ${hit.faceIndex} Light: $effectiveLight"

                g2d.color = Color(0.82f, 0.82f, 0.82f, 0.75f)
                g2d.fillRect(5, fm.ascent*3 + 15, fm.stringWidth(targetText) + 5, fm.ascent)

                g2d.color = Color.WHITE
                g2d.drawString(targetText, 10, fm.ascent*4 + 10)
            }
        }

        renderInventory(g2d)
    }

    private fun renderInventory(g2d: Graphics2D) {
        val slotSize = 50
        val padding = 5
        val totalWidth = 9 * (slotSize + padding) - padding
        val startX = (width - totalWidth) / 2
        val startY = height - slotSize - 20

        for (i in 0 until 9) {
            val x = startX + i * (slotSize + padding)
            val y = startY

            // Tło slotu
            if (i == selectedSlot) {
                g2d.color = Color(255, 255, 255, 180) // Podświetlenie
                g2d.stroke = BasicStroke(3f)
            } else {
                g2d.color = Color(0, 0, 0, 150)
                g2d.stroke = BasicStroke(1f)
            }

            g2d.fillRect(x, y, slotSize, slotSize)
            g2d.color = if (i == selectedSlot) Color.YELLOW else Color.GRAY
            g2d.drawRect(x, y, slotSize, slotSize)

            // Rysowanie przedmiotu (Miniatura 3D)
            val stack = inventory[i]
            if (stack != null) {
                // FIX: Mapowanie ID na kolor w ekwipunku
                drawIsometricBlock(g2d, x + slotSize / 2, y + slotSize / 2 + 5, slotSize - 20, Color(getBlockDisplayColor(stack.color)))

                // Licznik
                g2d.color = Color.WHITE
                g2d.font = hotbarFont
                val countStr = stack.count.toString()
                val strW = g2d.fontMetrics.stringWidth(countStr)
                g2d.drawString(countStr, x + slotSize - strW - 3, y + slotSize - 3)
            }
        }
    }

    // Rysuje prostą kostkę izometryczną 2D udającą 3D
    private fun drawIsometricBlock(g2d: Graphics2D, cx: Int, cy: Int, size: Int, color: Color) {
        val scale = size * 0.4

        val p = arrayOf(
            Vector3d(-1.0, 1.0, -1.0), Vector3d(1.0, 1.0, -1.0),
            Vector3d(1.0, 1.0, 1.0), Vector3d(-1.0, 1.0, 1.0),
            Vector3d(-1.0, -1.0, -1.0), Vector3d(1.0, -1.0, -1.0),
            Vector3d(1.0, -1.0, 1.0), Vector3d(-1.0, -1.0, 1.0)
        )

        val yaw = Math.toRadians(45.0)
        val pitch = Math.asin(1.0 / Math.sqrt(3.0))

        val cosYaw = cos(yaw); val sinYaw = sin(yaw)
        val cosPitch = cos(pitch); val sinPitch = sin(pitch)

        fun project(v: Vector3d): Point {
            var x = v.x * cosYaw - v.z * sinYaw
            var y = v.y
            var z = v.z * cosYaw + v.x * sinYaw
            val y2 = y * cosPitch - z * sinPitch
            return Point((cx + x * scale).toInt(), (cy - y2 * scale).toInt())
        }

        val pts = p.map { project(it) }

        // Top (Y+)
        g2d.color = color.brighter()
        g2d.fillPolygon(intArrayOf(pts[0].x, pts[1].x, pts[2].x, pts[3].x), intArrayOf(pts[0].y, pts[1].y, pts[2].y, pts[3].y), 4)

        // Right (X+)
        g2d.color = color.darker()
        g2d.fillPolygon(intArrayOf(pts[1].x, pts[5].x, pts[6].x, pts[2].x), intArrayOf(pts[1].y, pts[5].y, pts[6].y, pts[2].y), 4)

        // Left (Z+)
        g2d.color = color
        g2d.fillPolygon(intArrayOf(pts[3].x, pts[2].x, pts[6].x, pts[7].x), intArrayOf(pts[3].y, pts[2].y, pts[6].y, pts[7].y), 4)
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

    private fun processSingleInput() {
        val consumedKeys = inputManager.consumeJustPressedKeys()
        for (keyCode in consumedKeys) {
            if (keyCode == KeyEvent.VK_EQUALS) {
                gameTime += 1
            }
            if (keyCode == KeyEvent.VK_MINUS) {
                if (gameTime < 1.0) {gameTime += 23; dayCounter -= 1} else gameTime -= 1
                if (dayCounter < 0) dayCounter = 0
            }
            if (keyCode == KeyEvent.VK_NUMPAD9) {
                    debugFly = !debugFly
                    println("DebugFly: $debugFly")
                }
                if (keyCode == KeyEvent.VK_NUMPAD8) {
                    debugNoclip = !debugNoclip
                    println("debugNoclip: $debugNoclip")
                }
                if (keyCode == KeyEvent.VK_NUMPAD7) {
                    debugFullbright = !debugFullbright
                    if (debugFullbright) FullbrightFactor = 1.0 else FullbrightFactor = 0.0
                    println("debugFullbright: $debugFullbright")
                }
                if (keyCode == KeyEvent.VK_V) {
                    showChunkBorders = !showChunkBorders
                }
                if (keyCode == KeyEvent.VK_X) {
                    debugXray = !debugXray
                }
                if (keyCode >= KeyEvent.VK_1 && keyCode <= KeyEvent.VK_9) {
                    selectedSlot = keyCode - KeyEvent.VK_1
                }
                if (keyCode == KeyEvent.VK_G) {
                    println("camX: ${camX.toSmartString()}, camY: ${camY.toSmartString()}, camZ: ${camZ.toSmartString()}, yaw: ${yaw.toSmartString()}, pitch: ${pitch.toSmartString()}, speed: $currentSpeed")
                }
        }
    }

    // Logika poruszania się na podstawie wciśniętych klawiszy
    private fun processInput() {
        var tempSpeed = speed
        var tempJumpStrength = jumpStrength

        val isCrouching = !debugFly && !debugNoclip && inputManager.isKeyDown(KeyEvent.VK_SHIFT)
        if (isCrouching) {
            tempSpeed *= 0.3 // Spowolnienie podczas kucania
        }

        if (inputManager.isKeyDown(KeyEvent.VK_CONTROL)) tempSpeed *= 2

        // Sprawdzenie czy gracz jest w lawie
        val minX = floor((camX - radiusCollision) / cubeSize + 0.5).toInt()
        val maxX = floor((camX + radiusCollision) / cubeSize + 0.5).toInt()
        val feetY = camY - cubeSize - playerHeight / 2
        val headY = camY + radiusCollision * cubeSize
        val minY = floor((feetY + 10.0) / cubeSize + 0.5).toInt()
        val maxY = floor((headY + 10.0) / cubeSize + 0.5).toInt()
        val minZ = floor((camZ - radiusCollision) / cubeSize + 0.5).toInt()
        val maxZ = floor((camZ + radiusCollision) / cubeSize + 0.5).toInt()

        var inFluid = false
        for (bx in minX..maxX) {
            for (by in minY..maxY) {
                for (bz in minZ..maxZ) {
                    if (fluidBlocks.contains(getRawBlock(bx, by, bz))) inFluid = true
                }
            }
        }
        if (inFluid) {
            tempSpeed /= 3.0
            tempJumpStrength /= 3.0
        }

        var dx = 0.0
        var dz = 0.0

        if (debugFly || debugNoclip) tempSpeed *= 2

        currentSpeed = tempSpeed

        if (inputManager.isKeyDown(KeyEvent.VK_W)) {
            dx += tempSpeed * sin(yaw)
            dz += tempSpeed * cos(yaw)
        }
        if (inputManager.isKeyDown(KeyEvent.VK_S)) {
            dx -= tempSpeed * sin(yaw)
            dz -= tempSpeed * cos(yaw)
        }
        if (inputManager.isKeyDown(KeyEvent.VK_A)) {
            dx -= tempSpeed * cos(yaw)
            dz += tempSpeed * sin(yaw)
        }
        if (inputManager.isKeyDown(KeyEvent.VK_D)) {
            dx += tempSpeed * cos(yaw)
            dz -= tempSpeed * sin(yaw)
        }

        if (inputManager.isKeyDown(KeyEvent.VK_LEFT)) yaw -= rotationalSpeed
        if (inputManager.isKeyDown(KeyEvent.VK_RIGHT)) yaw += rotationalSpeed
        if (inputManager.isKeyDown(KeyEvent.VK_UP)) pitch += rotationalSpeed
        if (inputManager.isKeyDown(KeyEvent.VK_DOWN)) pitch -= rotationalSpeed

        if (inputManager.isMouseCaptured) {
            yaw += inputManager.mouseDeltaX * inputManager.sensitivity
            pitch -= inputManager.mouseDeltaY * inputManager.sensitivity
        }

        // Obsługa ciągłego niszczenia/stawiania bloków
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime > actionDelay) {
            if (inputManager.isLeftMouseDown) {
                raycastAction(false)
                lastActionTime = currentTime
            } else if (inputManager.isRightMouseDown) {
                raycastAction(true)
                lastActionTime = currentTime
            }
        }

        if (debugFly || debugNoclip) {
            // --- Tryb latania (Debug Fly / Noclip) ---
            // Wyłączamy grawitację, zachowujemy stare sterowanie Y

            var dy = 0.0
            if (inputManager.isKeyDown(KeyEvent.VK_SPACE)) dy += tempSpeed
            if (inputManager.isKeyDown(KeyEvent.VK_SHIFT)) dy -= tempSpeed

            // W trybie fly używamy kolizji (chyba że debugNoclip wyłączy je w checkCollision)
            moveWithCollision(dx, dy, dz)

            // Resetujemy prędkość fizyczną, żeby nie "wystrzelić" po wyłączeniu trybu
            velocityY = 0.0
        } else {
            // --- Tryb chodzenia (Grawitacja) ---

            // 1. Logika kucania (zapobieganie spadaniu z krawędzi)
            if (isCrouching && isOnGround) {
                val groundCheckY = camY - 0.1 // Sprawdzamy kolizję nieco poniżej stóp

                // Sprawdzenie osi X
                if (dx != 0.0 && !checkCollision(camX + dx, groundCheckY, camZ)) {
                    // Jeśli pełny ruch powoduje upadek, próbujemy podejść do krawędzi iteracyjnie
                    var safeDx = 0.0
                    val sign = sign(dx)
                    val step = abs(dx) / 10.0
                    for (i in 1..10) {
                        if (checkCollision(camX + safeDx + sign * step, groundCheckY, camZ)) {
                            safeDx += sign * step
                        } else {
                            break
                        }
                    }
                    dx = safeDx
                }

                // Sprawdzenie osi Z (uwzględniając ewentualnie zmieniony dx)
                if (dz != 0.0 && !checkCollision(camX + dx, groundCheckY, camZ + dz)) {
                    var safeDz = 0.0
                    val sign = sign(dz)
                    val step = abs(dz) / 10.0
                    for (i in 1..10) {
                        if (checkCollision(camX + dx, groundCheckY, camZ + safeDz + sign * step)) {
                            safeDz += sign * step
                        } else {
                            break
                        }
                    }
                    dz = safeDz
                }
            }

            // 2. Ruch poziomy z kolizjami (X i Z)
            moveWithCollision(dx, 0.0, dz)

            // 3. Skakanie (tylko gdy na ziemi)
            if (inputManager.isKeyDown(KeyEvent.VK_SPACE) && isOnGround) {
                velocityY = tempJumpStrength
                isOnGround = false
            }

            // 4. Grawitacja
            velocityY -= gravity
            // Ograniczenie prędkości spadania (terminal velocity)
            if (velocityY < -1.5) velocityY = -1.5

            // 5. Aplikowanie ruchu pionowego z fizyką
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
                    val id = getRawBlock(bx, by, bz)
                    if (id != 0 && !fluidBlocks.contains(id)) {
                        return true
                    }
                }
            }
        }
        return false
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
        frame.iconImage = Toolkit.getDefaultToolkit().getImage(gridMap::class.java.getResource("/icons/GridMap.png"))
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.isResizable = false
        frame.add(gridMap())
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}