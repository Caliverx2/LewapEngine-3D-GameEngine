/*
package com.krzychu.PyramidCustomBlock

import org.lewapnoob.gridMap.*
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.floor

class PyramidCustomBlockMod : LewapMod {
    private lateinit var game: gridMap
    override fun getName() = "PyramidCustomBlockMod"

    // Definiujemy ID naszego nowego bloku
    val PYRAMID_BLOCK_ID = 55
    var radius = 10
    private var radiusLabel: UIText? = null

    override fun onEnable(game: gridMap) {
        println("[PyramidCustomBlockMod] loaded successfully!")
        this.game = game
        println("[PyramidCustomBlockMod] Załadowano! ID bloku: $PYRAMID_BLOCK_ID")

        // 1. Rejestrujemy blok jako "custom render", żeby silnik nie rysował go jako zwykły sześcian
        game.customRenderBlocks.add(PYRAMID_BLOCK_ID)

        // 2. Dodajemy blok do ekwipunku gracza (żebyś mógł go postawić)
        // Kolor w ekwipunku będzie czarny (bo ID 55 to ciemny kolor), ale to tylko ikona
        game.addItem(PYRAMID_BLOCK_ID.toString(), 64)
    }

    override fun onUIInit(uiManager: UIManager) {
        val pauseMenu = uiManager.getPanel(GameState.PAUSED)
        pauseMenu.add(UIButton(game.baseCols*2 + 300, game.baseRows*2 - 115, 25, 25, "+") {
            radius += 1
        })
        pauseMenu.add(UIButton(game.baseCols*2 + 335, game.baseRows*2 - 115, 25, 25, "-") {
            radius -= 1
        })
        radiusLabel = UIText(game.baseCols*2 + 200, game.baseRows*2 - 115, "Radius: $radius", 16f, Color.WHITE)
        pauseMenu.add(radiusLabel!!)
    }

    override fun onTickUI(game: gridMap) {
        radiusLabel?.text = "Radius: $radius"
    }

    override fun onRender3D(game: gridMap) {
        // Skanujemy okolicę gracza w poszukiwaniu naszych bloków
        // (Dla optymalizacji sprawdzamy tylko promień 10 kratek, w pełnej grze użyłbyś visibleSegments)
        val px = floor(game.camX / game.cubeSize).toInt()
        val py = floor(game.viewY / game.cubeSize).toInt()
        val pz = floor(game.camZ / game.cubeSize).toInt()

        for (x in px - radius..px + radius) {
            for (y in py - radius..py + radius) {
                for (z in pz - radius..pz + radius) {
                    // Pobieramy blok z mapy
                    val blockId = game.getRawBlock(x, y, z)

                    if (blockId == PYRAMID_BLOCK_ID) {
                        renderPyramidAt(game, x, y, z)
                    }
                }
            }
        }
    }

    private fun renderPyramidAt(game: gridMap, bx: Int, by: Int, bz: Int) {
        // Konwersja współrzędnych bloku na świat 3D
        // Y w silniku jest przesunięte o -10.0
        val worldX = bx * game.cubeSize
        val worldY = by * game.cubeSize - 10.0
        val worldZ = bz * game.cubeSize

        // Animacja obrotu
        val angleDeg = (System.currentTimeMillis() / 1000.0) * 90.0 // 90 stopni na sekundę
        val angleRad = Math.toRadians(angleDeg)

        // Definicja piramidy (lokalnie, środek w 0,0,0)
        // Przesuwamy o +0.5 * cubeSize w górę, żeby stała na ziemi, a nie była w niej zagrzebana
        val size = 0.4 * game.cubeSize
        val heightOffset = -0.5 * game.cubeSize // Korekta wysokości (zależy od definicji Y w silniku)

        val apex = Vector3d(0.0, size, 0.0)
        val p1 = Vector3d(-size, -size, -size)
        val p2 = Vector3d(size, -size, -size)
        val p3 = Vector3d(size, -size, size)
        val p4 = Vector3d(-size, -size, size)

        fun drawFace(v1: Vector3d, v2: Vector3d, v3: Vector3d, color: Color) {
            // Obrót
            fun transform(v: Vector3d): Vector3d {
                // 1. Obrót Y
                val rx = v.x * cos(angleRad) - v.z * sin(angleRad)
                val rz = v.x * sin(angleRad) + v.z * cos(angleRad)

                // 2. Przesunięcie do pozycji bloku
                return Vector3d(rx + worldX, v.y + worldY, rz + worldZ)
            }

            val w1 = transform(v1)
            val w2 = transform(v2)
            val w3 = transform(v3)

            // Rzutowanie na ekran (korzystamy z publicznych metod silnika)
            val c1 = game.transform(w1); val c2 = game.transform(w2); val c3 = game.transform(w3)

            if (c1.z > 0.1 && c2.z > 0.1 && c3.z > 0.1) {
                val s1 = game.project(c1); val s2 = game.project(c2); val s3 = game.project(c3)
                game.fillTriangle(s1, s2, s3, c1, c2, c3, color)
            }
        }

        drawFace(apex, p1, p2, Color.RED)
        drawFace(apex, p2, p3, Color.GREEN)
        drawFace(apex, p3, p4, Color.BLUE)
        drawFace(apex, p4, p1, Color.YELLOW)
    }
}
*/