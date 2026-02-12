/*
package com.krzychu.minimap

import org.lewapnoob.gridMap.GameState
import org.lewapnoob.gridMap.LewapMod
import org.lewapnoob.gridMap.gridMap
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Point
import java.awt.event.KeyEvent
import kotlin.math.floor

class MyMinimapMod : LewapMod {
    private lateinit var game: gridMap
    private var isMapActive = true
    private val mapSize = 150
    private val pixelScale = 4
    private val mapRange = mapSize / (2 * pixelScale)

    override fun getName() = "Krzychu's Minimap v1.0"

    override fun onEnable(game: gridMap) {
        this.game = game
        println("[MinimapMod] Załadowano pomyślnie!")
    }

    // Helpery muszą być tutaj, bo mod nie ma dostępu do prywatnych metod silnika,
    // ale ma dostęp do publicznych pól (chunks, blocks) dzięki temu, że je otworzyliśmy.
    private fun getBlockId(game: gridMap, x: Int, y: Int, z: Int): Int {
        if (y !in 0..127) return 0
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
        val chunk = game.chunks[Point(cx, cz)] ?: return 0

        var lx = x % 16; if (lx < 0) lx += 16
        var lz = z % 16; if (lz < 0) lz += 16
        return chunk.getBlock(lx, y, lz)
    }

    private fun getHighestBlockY(game: gridMap, x: Int, z: Int): Int {
        val cx = if (x >= 0) x / 16 else (x + 1) / 16 - 1
        val cz = if (z >= 0) z / 16 else (z + 1) / 16 - 1
        val chunk = game.chunks[Point(cx, cz)] ?: return 0

        var lx = x % 16; if (lx < 0) lx += 16
        var lz = z % 16; if (lz < 0) lz += 16

        for (y in 127 downTo 0) {
            if (chunk.getBlock(lx, y, lz) != 0) return y
        }
        return 0
    }

    override fun onRender(g: Graphics2D, width: Int, height: Int) {
        if (!isMapActive || game.gameState != GameState.IN_GAME) return

        val mapX = 20
        val mapY = 20

        // Tło
        g.color = Color(0, 0, 0, 180)
        g.fillRect(mapX, mapY, mapSize, mapSize)
        g.color = Color.WHITE
        g.drawRect(mapX, mapY, mapSize, mapSize)

        val px = floor((game.camX + (game.cubeSize/2))/2).toInt()
        val py = floor((game.camY + (game.cubeSize/2))/2).toInt()+5
        val pz = floor((game.camZ + (game.cubeSize/2))/2).toInt()

        val surfaceY = getHighestBlockY(game, px, pz)
        val isCave = py < (surfaceY - 15)

        for (dx in -mapRange..mapRange) {
            for (dz in -mapRange..mapRange) {
                val wx = px + dx
                val wz = pz + dz
                val sx = mapX + (mapSize / 2) + (dx * pixelScale)
                val sy = mapY + (mapSize / 2) + (dz * pixelScale)

                if (sx < mapX || sx >= mapX + mapSize || sy < mapY || sy >= mapY + mapSize) continue

                var drawColor: Color? = null

                if (isCave) {
                    val blockAtHead = getBlockId(game, wx, py + 1, wz)
                    val blockAtFeet = getBlockId(game, wx, py, wz)
                    val blockBelow = getBlockId(game, wx, py - 1, wz)

                    if (blockAtHead != 0 || blockAtFeet != 0) drawColor = Color.GRAY
                    else if (blockBelow != 0) drawColor = Color(50, 50, 50)
                } else {
                    for (y in 127 downTo 0) {
                        val id = getBlockId(game, wx, y, wz)
                        if (id != 0) {
                            val rgb = game.blockIdColors[id] ?: id
                            drawColor = Color(rgb)
                            break
                        }
                    }
                }

                if (drawColor != null) {
                    g.color = drawColor
                    g.fillRect(sx, sy, pixelScale, pixelScale)
                }
            }
        }

        g.color = Color.RED
        g.fillOval(mapX + mapSize / 2 - 2, mapY + mapSize / 2 - 2, 5, 5)
        g.color = Color.YELLOW
        g.font = game.fpsFont.deriveFont(12f)
        g.drawString(if (isCave) "CAVE" else "SURFACE", mapX, mapY + mapSize + 12)
    }

    override fun onKeyPress(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.VK_M) {
            isMapActive = !isMapActive
            return true
        }
        return false
    }
}
*/