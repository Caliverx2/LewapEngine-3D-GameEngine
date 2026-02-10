/*
package com.krzychu.hardcorelights

import org.lewapnoob.gridMap.*
import java.awt.Color
import java.awt.Point
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.sqrt

class RGBLightingMod : LewapMod {
    private lateinit var game: gridMap
    // Przechowujemy pozycje kolorowych świateł: Pozycja -> Kolor (R, G, B)
    private val lightSources = ConcurrentHashMap<BlockPos, Color>()

    // Zbiór chunków, które już przeskanowaliśmy (żeby nie skanować ich co klatkę)
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

        // 1. Dodajemy bloki do ekwipunku
        game.addItem("$ID_RED", 10)
        game.addItem("$ID_GREEN", 10)
        game.addItem("$ID_BLUE", 10)

        // 2. Rejestrujemy kolory bloków w silniku (żeby same bloki miały kolor)
        game.blockIdColors[ID_RED] = Color.RED.rgb
        game.blockIdColors[ID_GREEN] = Color.GREEN.rgb
        game.blockIdColors[ID_BLUE] = Color.BLUE.rgb

        // 3. Podmieniamy procesor światła na nasz "Hardcore Mixer"
        game.lightProcessor = RGBMixerProcessor()
    }

    override fun onTick(game: gridMap) {
        tickCounter++

        // 1. Weryfikacja istniejących świateł (co 10 ticków - szybko)
        // Usuwa "duchy" po zniszczeniu bloku
        if (tickCounter % 10 == 0) {
            val iterator = lightSources.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val pos = entry.key
                val currentBlockId = getBlockId(pos.x, pos.y, pos.z)
                if (currentBlockId !in ID_RED..ID_BLUE) {
                    iterator.remove()
                }
            }
        }

        // 2. Skanowanie NOWYCH chunków (co 30 ticków - 1 sekunda)
        // Naprawia brak świateł po wczytaniu gry
        if (tickCounter >= 30) {
            tickCounter = 0
            scanNewChunks()
        }
    }

    // NOWE: Reagujemy na stawianie bloków w czasie rzeczywistym
    override fun onBlockPlace(game: gridMap, x: Int, y: Int, z: Int, color: Int): Boolean {
        if (color in ID_RED..ID_BLUE) {
            val c = when (color) {
                ID_RED -> Color.RED
                ID_GREEN -> Color.GREEN
                ID_BLUE -> Color.BLUE
                else -> Color.WHITE
            }
            lightSources[BlockPos(x, y, z)] = c
        } else {
            // Jeśli postawiono inny blok w miejscu światła (nadpisanie), usuń światło
            lightSources.remove(BlockPos(x, y, z))
        }
        // Zwracamy false, żeby silnik normalnie postawił blok fizycznie
        return false
    }

    // Inteligentne skanowanie tylko nowych chunków
    private fun scanNewChunks() {
        val currentChunks = game.chunks.keys.toSet()

        // Znajdź chunki, których jeszcze nie znamy
        val newChunks = currentChunks.filter { !scannedChunks.contains(it) }

        // Usuń z pamięci chunki, które zostały odładowane z gry
        scannedChunks.retainAll(currentChunks)

        // Przeskanuj tylko nowe
        for (chunkPos in newChunks) {
            val chunk = game.chunks[chunkPos] ?: continue
            scanChunk(chunk)
            scannedChunks.add(chunkPos)
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

    // Helper do pobierania ID bloku
    private fun getBlockId(x: Int, y: Int, z: Int): Int {
        if (y !in 0..127) return 0
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
        val chunk = game.chunks[Point(cx, cz)] ?: return 0
        var lx = x % 16; if (lx < 0) lx += 16
        var lz = z % 16; if (lz < 0) lz += 16
        return chunk.getBlock(lx, y, lz)
    }

    // Helper do pobierania surowego światła z chunka
    private fun getRawLight(x: Int, y: Int, z: Int): Int {
        if (y !in 0..127) return 15 shl 4 // Poza światem jasno
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
        val chunk = game.chunks[Point(cx, cz)] ?: return 15 shl 4
        var lx = x % 16; if (lx < 0) lx += 16
        var lz = z % 16; if (lz < 0) lz += 16
        return chunk.getLight(lx, y, lz)
    }

    // Nasz zaawansowany procesor mieszania światła
    inner class RGBMixerProcessor : LightProcessor {
        override fun process(x: Int, y: Int, z: Int, baseColor: Color, skyLevel: Int, blockLevel: Int, sunIntensity: Double, minLight: Double): Int {
            // --- FIX 1: NAPRAWA KOORDYNATÓW (INVERSE TRANSFORM) ---
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

            // Jeśli silnik przekazuje 0 (cień/błąd), próbujemy pobrać światło bezpośrednio z chunka.
            if (effectiveSkyLevel == 0) {
                // WAŻNE: Konwersja współrzędnych przestrzennych (Double) na blokowe (Int)
                // Wcześniej używaliśmy worldX.toInt(), co dawało błędne wyniki (bo 1 blok = 2.0 jednostki).
                val bX = floor(worldX / game.cubeSize).toInt()
                // Oś Y w świecie jest przesunięta o -10.0: yPos = y * cubeSize - 10.0
                // Więc: y = (yPos + 10.0) / cubeSize
                val bY = floor((worldY + 10.0) / game.cubeSize).toInt()
                val bZ = floor(worldZ / game.cubeSize).toInt()

                val rawLight = getRawLight(bX, bY, bZ)
                effectiveSkyLevel = (rawLight shr 4) and 0xF
            }

            // 1. Obliczamy światło słoneczne
            val skyIntensity = (effectiveSkyLevel / 15.0) * sunIntensity

            // 2. Obliczamy światło blokowe
            val rawBlockIntensity = blockLevel / 15.0

            // Optymalizacja: Jeśli jest ciemno, zwracamy ciemny kolor
            if (rawBlockIntensity <= 0.01 && skyIntensity <= 0.01) {
                val r = (baseColor.red * minLight).toInt()
                val g = (baseColor.green * minLight).toInt()
                val b = (baseColor.blue * minLight).toInt()
                return (r shl 16) or (g shl 8) or b
            }

            // 3. Mieszanie kolorów
            var rMix = 0.0
            var gMix = 0.0
            var bMix = 0.0

            val range = 15.0 * game.cubeSize
            val rangeSq = range * range

            for ((pos, color) in lightSources) {
                val srcX = pos.x * game.cubeSize
                val srcY = pos.y * game.cubeSize - 10.0
                val srcZ = pos.z * game.cubeSize

                val dx = worldX - srcX
                val dy = worldY - srcY
                val dz = worldZ - srcZ

                if (kotlin.math.abs(dx) > range || kotlin.math.abs(dy) > range || kotlin.math.abs(dz) > range) continue

                val distSq = dx*dx + dy*dy + dz*dz
                if (distSq < rangeSq) {
                    val dist = sqrt(distSq.toDouble())
                    val intensity = (1.0 - (dist / range)).coerceIn(0.0, 1.0)

                    if (intensity > 0) {
                        rMix += (color.red / 255.0) * intensity
                        gMix += (color.green / 255.0) * intensity
                        bMix += (color.blue / 255.0) * intensity
                    }
                }
            }

            // --- LOGIKA MIESZANIA Z BIAŁYM ŚWIATŁEM ---
            if (rawBlockIntensity <= 0.001) {
                // Jeśli silnik mówi, że jest ciemno (ściana), to nasze światło też gaśnie
                // Ale musimy uwzględnić światło słoneczne!
                val lightStrength = 0.75
                val base = minLight
                val mult = (base + skyIntensity * lightStrength).coerceIn(0.0, 1.0)

                val r = (baseColor.red * mult).toInt()
                val g = (baseColor.green * mult).toInt()
                val b = (baseColor.blue * mult).toInt()
                return (r shl 16) or (g shl 8) or b
            }

            val maxMix = maxOf(rMix, gMix, bMix)

            if (maxMix > rawBlockIntensity) {
                val scale = rawBlockIntensity / maxMix
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