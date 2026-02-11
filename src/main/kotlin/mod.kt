/*
package com.krzychu.hardcorelights

import org.lewapnoob.gridMap.*
import java.awt.Color
import java.awt.Point
import java.util.concurrent.ConcurrentHashMap
import java.util.ArrayDeque
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.max

class RGBLightingMod : LewapMod {
    private lateinit var game: gridMap
    // Przechowujemy pozycje kolorowych świateł: Pozycja -> Kolor (R, G, B)
    private val lightSources = ConcurrentHashMap<BlockPos, Color>()

    // Mapa propagacji światła RGB. Klucz: BlockPos, Wartość: Spakowane poziomy RGB (R<<8 | G<<4 | B)
    // To jest nasza "pre-obliczona" mapa. Zawiera już zmieszane kolory.
    private var rgbLightMap = ConcurrentHashMap<BlockPos, Int>()

    // Zbiór chunków, które już przeskanowaliśmy
    private val scannedChunks = ConcurrentHashMap.newKeySet<Point>()
    private var tickCounter = 0

    // Definicje kolorów dla nowych bloków
    private val ID_RED = 6
    private val ID_GREEN = 7
    private val ID_BLUE = 8

    override fun getName() = "Hardcore RGB Lighting Mod"

    override fun onEnable(game: gridMap) {
        this.game = game
        println("[RGBMod] Inicjalizacja kolorowego światła...")
        println("[RGBMod] Uruchamianie silnika propagacji BFS...")

        // 1. Dodajemy bloki do ekwipunku
        game.addItem("$ID_RED", 10)
        game.addItem("$ID_GREEN", 10)
        game.addItem("$ID_BLUE", 10)

        // 2. Rejestrujemy kolory bloków w silniku
        game.blockIdColors[ID_RED] = Color.RED.rgb
        game.blockIdColors[ID_GREEN] = Color.GREEN.rgb
        game.blockIdColors[ID_BLUE] = Color.BLUE.rgb

        // 3. Podmieniamy procesor światła
        game.lightProcessor = RGBMixerProcessor()
    }

    // Flaga, czy trzeba przeliczyć światło
    private var lightDirty = false

    override fun onTick(game: gridMap) {
        tickCounter++

        // 1. Weryfikacja istniejących świateł (co 10 ticków)
        if (tickCounter % 10 == 0) {
            val iterator = lightSources.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val pos = entry.key
                val currentBlockId = getBlockId(pos.x, pos.y, pos.z)
                if (currentBlockId !in ID_RED..ID_BLUE) {
                    iterator.remove()
                    lightDirty = true
                }
            }
        }

        // 2. Skanowanie NOWYCH chunków (co 30 ticków)
        if (tickCounter >= 30) {
            tickCounter = 0
            scanNewChunks()
        }

        // 3. Przeliczanie propagacji światła (BFS)
        // To wykonuje się TYLKO gdy układ świateł się zmienił.
        // Wynik jest zapisywany w rgbLightMap i używany wielokrotnie.
        if (lightDirty) {
            recalculateLightingBFS()
            lightDirty = false
        }
    }

    override fun onBlockPlace(game: gridMap, x: Int, y: Int, z: Int, color: Int): Boolean {
        if (color in ID_RED..ID_BLUE) {
            val c = when (color) {
                ID_RED -> Color.RED
                ID_GREEN -> Color.GREEN
                ID_BLUE -> Color.BLUE
                else -> Color.WHITE
            }
            lightSources[BlockPos(x, y, z)] = c
            lightDirty = true
        } else {
            if (lightSources.remove(BlockPos(x, y, z)) != null) {
                lightDirty = true
            }
        }
        return false
    }

    private fun scanNewChunks() {
        val currentChunks = game.chunks.keys.toSet()
        val newChunks = currentChunks.filter { !scannedChunks.contains(it) }
        scannedChunks.retainAll(currentChunks)

        for (chunkPos in newChunks) {
            val chunk = game.chunks[chunkPos] ?: continue
            scanChunk(chunk)
            scannedChunks.add(chunkPos)
            lightDirty = true
        }
    }

    private fun scanChunk(chunk: Chunk) {
        for (i in chunk.blocks.indices) {
            val blockId = chunk.blocks[i]
            if (blockId in ID_RED..ID_BLUE) {
                val lx = i % 16
                val lz = (i / 16) % 16
                val ly = i / 256
                val wx = chunk.x * 16 + lx
                val wz = chunk.z * 16 + lz
                val color = when (blockId) {
                    ID_RED -> Color.RED
                    ID_GREEN -> Color.GREEN
                    ID_BLUE -> Color.BLUE
                    else -> Color.WHITE
                }
                lightSources[BlockPos(wx, ly, wz)] = color
            }
        }
    }

    // --- CORE: ALGORYTM BFS DLA KOLOROWEGO ŚWIATŁA ---
    // Oblicza mieszanie kolorów RAZ i zapisuje wynik w mapie.
    private fun recalculateLightingBFS() {
        val newMap = ConcurrentHashMap<BlockPos, Int>()

        // OPTYMALIZACJA: Używamy Long zamiast obiektów LightNode, aby odciążyć Garbage Collector
        val queue = ArrayDeque<Long>()

        // 1. Inicjalizacja źródeł
        for ((pos, color) in lightSources) {
            val r = if (color.red > 128) 15 else 0
            val g = if (color.green > 128) 15 else 0
            val b = if (color.blue > 128) 15 else 0

            val packed = (r shl 8) or (g shl 4) or b
            newMap[pos] = packed
            queue.add(packPos(pos.x, pos.y, pos.z))
        }

        // 2. Pętla BFS
        // Unikamy tworzenia obiektów Triple w pętli
        val dxs = intArrayOf(1, -1, 0, 0, 0, 0)
        val dys = intArrayOf(0, 0, 1, -1, 0, 0)
        val dzs = intArrayOf(0, 0, 0, 0, 1, -1)

        while (!queue.isEmpty()) {
            val packedPos = queue.poll()
            val cx = unpackX(packedPos)
            val cy = unpackY(packedPos)
            val cz = unpackZ(packedPos)

            // Pobieramy aktualny stan światła z mapy
            val currentPacked = newMap[BlockPos(cx, cy, cz)] ?: 0
            val cr = (currentPacked shr 8) and 0xF
            val cg = (currentPacked shr 4) and 0xF
            val cb = currentPacked and 0xF

            if (cr <= 0 && cg <= 0 && cb <= 0) continue

            for (i in 0 until 6) {
                val nx = cx + dxs[i]
                val ny = cy + dys[i]
                val nz = cz + dzs[i]

                if (ny !in 0..127) continue

                val neighborId = getBlockId(nx, ny, nz)
                val isTransparent = neighborId == 0 || neighborId in ID_RED..ID_BLUE || neighborId == 3 || neighborId == 4

                if (!isTransparent) continue

                val nextR = (cr - 1).coerceAtLeast(0)
                val nextG = (cg - 1).coerceAtLeast(0)
                val nextB = (cb - 1).coerceAtLeast(0)

                if (nextR == 0 && nextG == 0 && nextB == 0) continue

                val nPos = BlockPos(nx, ny, nz)
                val existingPacked = newMap[nPos] ?: 0
                val exR = (existingPacked shr 8) and 0xF
                val exG = (existingPacked shr 4) and 0xF
                val exB = existingPacked and 0xF

                var changed = false
                // Tutaj następuje "zderzenie" świateł. Używamy MAX, aby kolory się mieszały (np. R+G = Yellow).
                var finalR = exR
                var finalG = exG
                var finalB = exB

                if (nextR > exR) { finalR = nextR; changed = true }
                if (nextG > exG) { finalG = nextG; changed = true }
                if (nextB > exB) { finalB = nextB; changed = true }

                if (changed) {
                    val newPacked = (finalR shl 8) or (finalG shl 4) or finalB
                    newMap[nPos] = newPacked
                    queue.add(packPos(nx, ny, nz))
                }
            }
        }
        rgbLightMap = newMap
    }

    // --- Helpery do pakowania pozycji w Long (oszczędność pamięci) ---
    private fun packPos(x: Int, y: Int, z: Int): Long {
        return (x.toLong() and 0xFFFFFF) or ((y.toLong() and 0xFF) shl 24) or ((z.toLong() and 0xFFFFFF) shl 32)
    }

    private fun unpackX(packed: Long): Int {
        var x = (packed and 0xFFFFFF).toInt()
        if (x > 0x7FFFFF) x -= 0x1000000 // obsługa ujemnych koordynatów (24-bit signed)
        return x
    }
    private fun unpackY(packed: Long): Int = ((packed ushr 24) and 0xFF).toInt()
    private fun unpackZ(packed: Long): Int {
        var z = ((packed ushr 32) and 0xFFFFFF).toInt()
        if (z > 0x7FFFFF) z -= 0x1000000
        return z
    }

    private fun getBlockId(x: Int, y: Int, z: Int): Int {
        if (y !in 0..127) return 0
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
        val chunk = game.chunks[Point(cx, cz)] ?: return 0
        var lx = x % 16; if (lx < 0) lx += 16
        var lz = z % 16; if (lz < 0) lz += 16
        return chunk.getBlock(lx, y, lz)
    }

    private fun getRawLight(x: Int, y: Int, z: Int): Int {
        if (y !in 0..127) return 15 shl 4
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
        val chunk = game.chunks[Point(cx, cz)] ?: return 15 shl 4
        var lx = x % 16; if (lx < 0) lx += 16
        var lz = z % 16; if (lz < 0) lz += 16
        return chunk.getLight(lx, y, lz)
    }

    inner class RGBMixerProcessor : LightProcessor {
        override fun process(x: Int, y: Int, z: Int, baseColor: Color, skyLevel: Int, blockLevel: Int, sunIntensity: Double, minLight: Double): Int {
            // --- FIX 1: ODWRÓCENIE TRANSFORMACJI KAMERY ---
            val tx = x - game.camX
            val ty = y - game.viewY
            val tz = z - game.camZ

            val y_unpitch = ty * game.cosPitch + tz * game.sinPitch
            val z_unpitch = tz * game.cosPitch - ty * game.sinPitch

            val x_final = tx * game.cosYaw + z_unpitch * game.sinYaw
            val z_final = z_unpitch * game.cosYaw - tx * game.sinYaw

            val worldX = x_final + game.camX
            val worldY = y_unpitch + game.viewY
            val worldZ = z_final + game.camZ

            // --- FIX 2: NAPRAWA OŚWIETLENIA DZIENNEGO ---
            var effectiveSkyLevel = skyLevel
            if (effectiveSkyLevel == 0) {
                val bX = floor(worldX / game.cubeSize).toInt()
                val bY = floor((worldY + 10.0) / game.cubeSize).toInt()
                val bZ = floor(worldZ / game.cubeSize).toInt()
                val rawLight = getRawLight(bX, bY, bZ)
                effectiveSkyLevel = (rawLight shr 4) and 0xF
            }

            // 1. Obliczamy światło słoneczne
            val skyIntensity = (effectiveSkyLevel / 15.0) * sunIntensity

            // 2. Obliczamy światło blokowe
            val rawBlockIntensity = blockLevel / 15.0

            if (rawBlockIntensity <= 0.01 && skyIntensity <= 0.01) {
                val r = (baseColor.red * minLight).toInt()
                val g = (baseColor.green * minLight).toInt()
                val b = (baseColor.blue * minLight).toInt()
                return (r shl 16) or (g shl 8) or b
            }

            // --- FIX 3: STABILNY ODCZYT Z MAPY Z WYGASZANIEM (DISTANCE DECAY) ---
            // Zamiast szukać "pasującej jasności", szukamy "najsilniejszego koloru" w okolicy,
            // ale pomniejszonego o dystans od środka bloku.
            // To eliminuje "przeskakiwanie" światła na odległe bloki (problem 2 bloków),
            // jednocześnie zachowując stabilność (anty-migotanie) przy ścianach.

            val bX = floor(worldX / game.cubeSize).toInt()
            val bY = floor((worldY + 10.0) / game.cubeSize).toInt()
            val bZ = floor(worldZ / game.cubeSize).toInt()

            var maxR = 0.0
            var maxG = 0.0
            var maxB = 0.0

            val searchRadius = 2
            val cubeSize = game.cubeSize
            val halfCube = cubeSize / 2.0

            for (dx in -searchRadius..searchRadius) {
                for (dy in -searchRadius..searchRadius) {
                    for (dz in -searchRadius..searchRadius) {
                        val nx = bX + dx
                        val ny = bY + dy
                        val nz = bZ + dz
                        
                        val pos = BlockPos(nx, ny, nz)
                        val packed = rgbLightMap[pos] ?: 0
                        if (packed == 0) continue

                        // Pozycja środka sąsiedniego bloku w świecie
                        val centerX = nx * cubeSize + halfCube
                        val centerY = ny * cubeSize - 10.0 + halfCube
                        val centerZ = nz * cubeSize + halfCube

                        val distSq = (worldX - centerX)*(worldX - centerX) + 
                                     (worldY - centerY)*(worldY - centerY) + 
                                     (worldZ - centerZ)*(worldZ - centerZ)
                        
                        // Dystans w blokach
                        val dist = sqrt(distSq) / cubeSize
                        
                        // Efektywna jasność = Jasność Bloku - Dystans
                        // Jeśli jesteśmy 2 bloki od źródła o sile 2, to 2 - 2 = 0. Światło nie dociera.
                        val r = ((packed shr 8) and 0xF).toDouble()
                        val g = ((packed shr 4) and 0xF).toDouble()
                        val b = (packed and 0xF).toDouble()

                        val effR = (r - dist).coerceAtLeast(0.0)
                        val effG = (g - dist).coerceAtLeast(0.0)
                        val effB = (b - dist).coerceAtLeast(0.0)

                        if (effR > maxR) maxR = effR
                        if (effG > maxG) maxG = effG
                        if (effB > maxB) maxB = effB
                    }
                }
            }

            // Mamy stabilny kolor z mapy
            var rMix = maxR / 15.0
            var gMix = maxG / 15.0
            var bMix = maxB / 15.0

            // --- LOGIKA MIESZANIA Z BIAŁYM ŚWIATŁEM ---
            // Jeśli silnik mówi, że jest ciemno (ściana), to nasze światło też gaśnie.
            if (rawBlockIntensity <= 0.001) {
                val lightStrength = 0.75
                val base = minLight
                val mult = (base + skyIntensity * lightStrength).coerceIn(0.0, 1.0)
                val r = (baseColor.red * mult).toInt()
                val g = (baseColor.green * mult).toInt()
                val b = (baseColor.blue * mult).toInt()
                return (r shl 16) or (g shl 8) or b
            }

            // Jeśli jasność z silnika jest większa niż nasz kolor, uzupełniamy to białym światłem.
            // (np. Lawa obok słabego zielonego światła -> Zielony + Biały)
            val maxColoredBrightness = maxOf(rMix, gMix, bMix)

            // Jeśli nasz kolor jest jaśniejszy niż to co widzi silnik (np. przez ścianę), przycinamy go.
            if (maxColoredBrightness > rawBlockIntensity) {
                val scale = rawBlockIntensity / maxColoredBrightness
                rMix *= scale
                gMix *= scale
                bMix *= scale
            }

            val residualWhite = (rawBlockIntensity - maxOf(rMix, gMix, bMix)).coerceAtLeast(0.0)

            val finalBlockR = rMix + residualWhite
            val finalBlockG = gMix + residualWhite
            val finalBlockB = bMix + residualWhite

            // 4. Łączenie ze światłem słonecznym
            val lightStrength = 0.75
            val base = minLight

            val multR = (base + maxOf(skyIntensity, finalBlockR) * lightStrength).coerceIn(0.0, 1.0)
            val multG = (base + maxOf(skyIntensity, finalBlockG) * lightStrength).coerceIn(0.0, 1.0)
            val multB = (base + maxOf(skyIntensity, finalBlockB) * lightStrength).coerceIn(0.0, 1.0)

            val r = (baseColor.red * multR).toInt()
            val g = (baseColor.green * multG).toInt()
            val b = (baseColor.blue * multB).toInt()

            return (r shl 16) or (g shl 8) or b
        }
    }
}
*/