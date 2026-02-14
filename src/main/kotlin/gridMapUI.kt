package org.lewapnoob.gridMap

import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.floor

abstract class UIComponent {
    var x: Int = 0
    var y: Int = 0
    var width: Int = 0
    var height: Int = 0
    var isVisible: Boolean = true
    var isEnabled: Boolean = true
    var isFocused: Boolean = false
    var tooltipText: String? = null // Każdy komponent może mieć teraz tooltip

    abstract fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int)

    open fun onClick(x: Int, y: Int): Boolean = false
    open fun onHover(x: Int, y: Int) {}
    open fun onScroll(amount: Int) {}
    open fun onPress(x: Int, y: Int): Boolean = false
    open fun onRelease(x: Int, y: Int) {}
    open fun onDrag(x: Int, y: Int) {}
    open fun onKey(e: KeyEvent): Boolean = false

    // Szybka metoda sprawdzania kolizji bez tworzenia obiektów Rectangle
    fun isMouseOver(mouseX: Int, mouseY: Int): Boolean {
        return isVisible && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
    }
}

enum class TextAlign {
    LEFT, CENTER, RIGHT
}

class UIButton(
    x: Int, y: Int, width: Int, height: Int,
    var text: String = "Button",
    var texture: BufferedImage? = null,
    var textAlign: TextAlign = TextAlign.CENTER,
    var padding: Int = 0,
    var fontSize: Float = 40f,
    tooltip: String? = null,
    var action: () -> Unit
) : UIComponent() {
    init {
        this.x = x
        this.y = y
        this.width = width
        this.height = height
        this.tooltipText = tooltip
    }

    override fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int) {
        if (!isVisible) return

        val isHovered = isMouseOver(mouseX, mouseY) && isEnabled

        // Tło przycisku
        g.color = if (!isEnabled) Color(0x2c2c2c) else if (isHovered) Color(0x808080) else Color(0x6d6d6d)
        g.fillRect(x, y, width, height)

        texture?.let {
            g.drawImage(it, x, y, width, height, null)
        }

        // --- LOGIKA CLIPPINGU ---
        val oldClip = g.clip
        g.clipRect(x, y, width, height)

        g.color = if (isEnabled) Color.WHITE else Color(0x808080)
        g.font = game.fpsFont.deriveFont(fontSize)

        val fm = g.fontMetrics
        val textWidth = fm.stringWidth(text)
        val textHeight = fm.ascent

        val drawX = when (textAlign) {
            TextAlign.LEFT   -> x + padding
            TextAlign.CENTER -> x + (width - textWidth) / 2
            TextAlign.RIGHT  -> x + width - textWidth - padding
        }

        val drawY = y + (height + textHeight) / 2 - 4

        g.drawString(text, drawX, drawY)
        g.clip = oldClip
    }

    override fun onClick(clickX: Int, clickY: Int): Boolean {
        if (isVisible && isEnabled && isMouseOver(clickX, clickY)) {
            action()
            return true
        }
        return false
    }
}

class UIText(
    x: Int, y: Int,
    var text: String,
    var fontSize: Float,
    var color: Color,
    var centered: Boolean = false
) : UIComponent() {
    init {
        this.x = x
        this.y = y
    }

    override fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int) {
        if (!isVisible) return
        g.font = game.fpsFont.deriveFont(fontSize)
        g.color = color
        val fm = g.fontMetrics
        val drawX = if (centered) x - fm.stringWidth(text) / 2 else x
        g.drawString(text, drawX, y + fm.ascent)
    }
}

class UITextField(
    x: Int, y: Int, width: Int, height: Int,
    var text: String = "",
    var placeholder: String = ""
) : UIComponent() {
    init {
        this.x = x
        this.y = y
        this.width = width
        this.height = height
    }

    override fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int) {
        if (!isVisible) return

        if (placeholder.isNotEmpty()) {
            g.color = Color.LIGHT_GRAY
            g.font = game.fpsFont.deriveFont(32f)
            g.drawString(placeholder, x, y - 5)
        }

        g.color = Color.BLACK
        g.fillRect(x, y, width, height)
        g.color = if (isFocused) Color.YELLOW else Color.WHITE
        g.drawRect(x, y, width, height)

        val oldClip = g.clip
        g.clipRect(x, y, width, height)

        g.color = Color.WHITE
        g.font = game.fpsFont.deriveFont(32f)
        val fm = g.fontMetrics
        g.drawString(text, x + 10, y + fm.ascent)

        if (isFocused && (System.currentTimeMillis() / 500) % 2 == 0L) {
            val cursorX = x + 10 + fm.stringWidth(text)
            g.fillRect(cursorX, y + 5, 2, height - 10)
        }
        g.clip = oldClip
    }

    override fun onClick(clickX: Int, clickY: Int): Boolean {
        if (!isVisible || !isEnabled) return false
        isFocused = isMouseOver(clickX, clickY)
        return isFocused
    }

    override fun onKey(e: KeyEvent): Boolean {
        if (!isFocused) return false

        if (e.id == KeyEvent.KEY_TYPED) {
            val char = e.keyChar
            if (char.code >= 32 && char.code != 127) {
                if (text.length < 30) text += char
                return true
            }
        } else if (e.id == KeyEvent.KEY_PRESSED) {
            if (e.keyCode == KeyEvent.VK_BACK_SPACE && text.isNotEmpty()) {
                text = text.dropLast(1)
                return true
            }
        }
        return false
    }
}

class UIScrollPanel(
    x: Int, y: Int, width: Int, height: Int
) : UIComponent() {
    val children = CopyOnWriteArrayList<UIComponent>()
    var scrollY = 0
    private var contentHeight = 0
    private val scrollBarWidth = 15

    private var isDraggingScrollbar = false
    private var dragStartY = 0
    private var initialScrollY = 0

    init {
        this.x = x
        this.y = y
        this.width = width
        this.height = height
    }

    fun addChild(component: UIComponent) {
        children.add(component)
        contentHeight = kotlin.math.max(contentHeight, component.y + component.height)
    }

    fun clear() {
        children.clear()
        contentHeight = 0
        scrollY = 0
    }

    override fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int) {
        if (!isVisible) return

        g.color = Color(0, 0, 0, 100)

        val originalClip = g.clip
        g.clipRect(x, y, width - scrollBarWidth, height)

        val gContent = g.create() as Graphics2D
        gContent.translate(x, y - scrollY)

        val relativeMouseX = mouseX - x
        val relativeMouseY = mouseY - (y - scrollY)

        for (child in children) {
            if (child.y + child.height > scrollY && child.y < scrollY + height) {
                child.render(gContent, game, relativeMouseX, relativeMouseY)
            }
        }
        gContent.dispose()
        g.clip = originalClip

        if (contentHeight > height) {
            g.color = Color(50, 50, 50)
            g.fillRect(x + width - scrollBarWidth, y, scrollBarWidth, height)

            val viewRatio = height.toDouble() / contentHeight.toDouble()
            val handleHeight = (height * viewRatio).coerceAtLeast(20.0).toInt()
            val maxScroll = contentHeight - height
            val scrollRatio = scrollY.toDouble() / maxScroll.toDouble()
            val handleY = y + (scrollRatio * (height - handleHeight)).toInt()

            g.color = if (isDraggingScrollbar) Color.WHITE else Color.LIGHT_GRAY
            g.fillRect(x + width - scrollBarWidth + 2, handleY, scrollBarWidth - 4, handleHeight)
        }
    }

    override fun onClick(clickX: Int, clickY: Int): Boolean {
        if (!isVisible || !isMouseOver(clickX, clickY)) return false

        if (clickX > x + width - scrollBarWidth && contentHeight > height) {
            return true
        }

        val relativeX = clickX - x
        val relativeY = clickY - y + scrollY

        for (child in children) {
            if (child.onClick(relativeX, relativeY)) return true
        }
        return false
    }

    override fun onScroll(amount: Int) {
        if (contentHeight <= height) return
        val scrollSpeed = 20
        scrollY = (scrollY + amount * scrollSpeed).coerceIn(0, contentHeight - height)
    }

    override fun onPress(clickX: Int, clickY: Int): Boolean {
        if (!isVisible) return false
        if (clickX >= x + width - scrollBarWidth && clickX <= x + width &&
            clickY >= y && clickY <= y + height && contentHeight > height) {

            isDraggingScrollbar = true
            dragStartY = clickY
            initialScrollY = scrollY
            return true
        }
        return false
    }

    override fun onRelease(x: Int, y: Int) {
        isDraggingScrollbar = false
    }

    override fun onDrag(dragX: Int, dragY: Int) {
        if (isDraggingScrollbar && contentHeight > height) {
            val deltaY = dragY - dragStartY
            val viewRatio = height.toDouble() / contentHeight.toDouble()
            val handleHeight = (height * viewRatio).coerceAtLeast(20.0)
            val trackHeight = height - handleHeight
            val scrollPerPixel = (contentHeight - height) / trackHeight
            scrollY = (initialScrollY + deltaY * scrollPerPixel).toInt().coerceIn(0, contentHeight - height)
        }
    }

    override fun onHover(hoverX: Int, hoverY: Int) {
        if (!isVisible || !isMouseOver(hoverX, hoverY)) return
        val relativeX = hoverX - x
        val relativeY = hoverY - y + scrollY
        for (child in children) {
            child.onHover(relativeX, relativeY)
        }
    }
}

class UIBackground(var color: Color) : UIComponent() {
    override fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int) {
        g.color = color
        g.fillRect(0, 0, game.referenceWidth, game.referenceHeight)
    }
}

class UIFpsCounter : UIComponent() {
    override fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int) {
        g.font = game.fpsFont
        g.color = Color.YELLOW
        val fpsText = "${game.fps}"
        val fm = g.fontMetrics
        g.drawString(fpsText, game.referenceWidth - fm.stringWidth(fpsText) - 10, fm.ascent + 10)
    }
}

class UIDebugInfo : UIComponent() {
    override fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int) {
        if (!game.showChunkBorders) return
        
        val fm = g.fontMetrics
        val currentChunkX = floor(game.camX / 32.0).toInt()
        val currentChunkZ = floor(game.camZ / 32.0).toInt()
        val chunkText = "Chunk: c_${currentChunkX}_${currentChunkZ}.dat"
        val posText = "Position: (${floor((game.camX + (game.cubeSize/2))/2).toInt()}, ${floor((game.camY + (game.cubeSize/2))/2).toInt()+5}, ${floor((game.camZ + (game.cubeSize/2)) /2).toInt()})"
        val timeText = String.format("Time: %.2fzł (Intensity: %.2f) (Day ${game.dayCounter})", game.gameTime, game.globalSunIntensity)
        val seedText = "Seed: ${game.seed}"

        g.color = Color(0.82f, 0.82f, 0.82f, 0.75f)
        g.fillRect(5, 15, fm.stringWidth(chunkText) + 5, fm.ascent)
        g.fillRect(5, fm.ascent + 15, fm.stringWidth(posText) + 5, fm.ascent)
        g.fillRect(5, fm.ascent*2 + 15, fm.stringWidth(timeText) + 5, fm.ascent)
        g.fillRect(5, fm.ascent*3 + 15, fm.stringWidth(seedText) + 5, fm.ascent)

        g.color = Color.WHITE
        g.drawString(chunkText, 10, fm.ascent + 10)
        g.drawString(posText, 10, fm.ascent*2 + 10)
        g.drawString(timeText, 10, fm.ascent*3 + 10)
        g.drawString(seedText, 10, fm.ascent*4 + 10)

        // Informacje o bloku, na który patrzy gracz
        val hit = game.getTargetBlock()
        if (hit != null) {
            val target = hit.blockPos
            val blockColor = game.getBlock(target.x, target.y, target.z)

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
            val rawLight = game.getLight(nx, ny, nz)
            val skyLight = (rawLight shr 4) and 0xF
            val blockLight = rawLight and 0xF
            val effectiveLight = maxOf(skyLight * game.globalSunIntensity, blockLight.toDouble()).toInt()

            val colorHex = if (blockColor != null) String.format("#%06X", (0xFFFFFF and blockColor.rgb)) else "N/A"
            val targetText = "Target: [${target.x}, ${target.y}, ${target.z}] Color: $colorHex Face: ${hit.faceIndex} Light: $effectiveLight"

            g.color = Color(0.82f, 0.82f, 0.82f, 0.75f)
            g.fillRect(5, fm.ascent*4 + 15, fm.stringWidth(targetText) + 5, fm.ascent)

            g.color = Color.WHITE
            g.drawString(targetText, 10, fm.ascent*5 + 10)
        }
    }
}

class UICrosshair : UIComponent() {
    override fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int) {
        g.color = Color.WHITE
        val crossSize = 5
        val centerX = game.referenceWidth / 2
        val centerY = game.referenceHeight / 2
        g.stroke = BasicStroke(2f)
        g.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY)
        g.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize)
    }
}

class UIInventory : UIComponent() {
    override fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int) {
        val slotSize = 50
        val padding = 5
        val totalWidth = 9 * (slotSize + padding) - padding
        val startX = (game.referenceWidth - totalWidth) / 2
        val startY = game.referenceHeight - slotSize - 20

        for (i in 0 until 9) {
            val x = startX + i * (slotSize + padding)
            val y = startY

            if (i == game.selectedSlot) {
                g.color = Color(255, 255, 255, 180)
                g.stroke = BasicStroke(3f)
            } else {
                g.color = Color(0, 0, 0, 150)
                g.stroke = BasicStroke(1f)
            }

            g.fillRect(x, y, slotSize, slotSize)
            g.color = if (i == game.selectedSlot) Color.YELLOW else Color.GRAY
            g.drawRect(x, y, slotSize, slotSize)

            val stack = game.inventory[i]
            if (stack != null) {
                val color = game.blockIdColors[stack.color] ?: stack.color
                drawIsometricBlock(g, x + slotSize / 2, y + slotSize / 2 + 5, slotSize - 20, Color(color))

                g.color = Color.WHITE
                g.font = game.hotbarFont
                val countStr = stack.count.toString()
                val strW = g.fontMetrics.stringWidth(countStr)
                g.drawString(countStr, x + slotSize - strW - 3, y + slotSize - 3)
            }
        }
    }

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
}

class UIManager(val game: gridMap) {
    val panels = mutableMapOf<GameState, UIPanel>()

    init {
        for (state in GameState.values()) {
            panels[state] = UIPanel()
        }
    }

    fun getPanel(state: GameState): UIPanel = panels[state]!!

    fun render(g: Graphics2D, mouseX: Int, mouseY: Int) {
        val currentPanel = panels[game.gameState] ?: return

        // 1. Rysowanie głównych komponentów
        currentPanel.render(g, game, mouseX, mouseY)

        // 2. Rysowanie Tooltipów na wierzchu wszystkiego
        renderTooltipLayer(g, currentPanel, mouseX, mouseY)
    }

    private fun renderTooltipLayer(g: Graphics2D, panel: UIPanel, mouseX: Int, mouseY: Int) {
        // Szukamy komponentu (również wewnątrz scroll paneli), który ma tooltip i jest pod myszką
        var hoveredText: String? = null

        // Przeszukujemy komponenty od końca (te na wierzchu są ostatnie na liście)
        for (comp in panel.components.asReversed()) {
            if (comp is UIScrollPanel) {
                // Specjalna obsługa dla scroll panelu (współrzędne relatywne)
                if (comp.isMouseOver(mouseX, mouseY)) {
                    val relX = mouseX - comp.x
                    val relY = mouseY - comp.y + comp.scrollY
                    for (child in comp.children.asReversed()) {
                        if (child.tooltipText != null && child.isMouseOver(relX, relY)) {
                            hoveredText = child.tooltipText
                            break
                        }
                    }
                }
            } else if (comp.tooltipText != null && comp.isMouseOver(mouseX, mouseY)) {
                hoveredText = comp.tooltipText
            }
            if (hoveredText != null) break
        }

        hoveredText?.let { drawTooltipWindow(g, it, mouseX, mouseY) }
    }

    private fun drawTooltipWindow(g: Graphics2D, text: String, mouseX: Int, mouseY: Int) {
        g.font = game.fpsFont.deriveFont(18f)
        val fm = g.fontMetrics
        val padding = 8
        val textW = fm.stringWidth(text)
        val textH = fm.ascent

        val rectW = textW + padding * 2
        val rectH = textH + padding * 2

        var tx = mouseX + 15
        var ty = mouseY + 15

        if (tx + rectW > game.referenceWidth) tx = mouseX - rectW - 5
        if (ty + rectH > game.referenceHeight) ty = mouseY - rectH - 5

        g.color = Color(0, 0, 0, 200)
        g.fillRect(tx, ty, rectW, rectH)
        g.color = Color.WHITE
        g.drawRect(tx, ty, rectW, rectH)
        g.drawString(text, tx + padding, ty + fm.ascent + padding - 2)
    }

    // Pozostałe metody delegujące bez zmian...
    fun handleClick(x: Int, y: Int): Boolean = panels[game.gameState]?.handleClick(x, y) ?: false
    fun handleHover(x: Int, y: Int) = panels[game.gameState]?.handleHover(x, y)
    fun handleScroll(amount: Int) = panels[game.gameState]?.handleScroll(amount)
    fun handlePress(x: Int, y: Int): Boolean = panels[game.gameState]?.handlePress(x, y) ?: false
    fun handleRelease(x: Int, y: Int) = panels[game.gameState]?.handleRelease(x, y)
    fun handleDrag(x: Int, y: Int) = panels[game.gameState]?.handleDrag(x, y)
    fun handleKey(e: KeyEvent): Boolean = panels[game.gameState]?.handleKey(e) ?: false
}

class UIPanel {
    val components = CopyOnWriteArrayList<UIComponent>()

    fun add(component: UIComponent) = components.add(component)
    fun clear() = components.clear()

    fun render(g: Graphics2D, game: gridMap, mouseX: Int, mouseY: Int) {
        for (component in components) {
            component.render(g, game, mouseX, mouseY)
        }
    }

    fun handleClick(x: Int, y: Int): Boolean {
        for (component in components) {
            if (component.onClick(x, y)) return true
        }
        return false
    }

    fun handleHover(x: Int, y: Int) = components.forEach { it.onHover(x, y) }
    fun handleScroll(amount: Int) = components.forEach { it.onScroll(amount) }
    fun handlePress(x: Int, y: Int): Boolean = components.any { it.onPress(x, y) }
    fun handleRelease(x: Int, y: Int) = components.forEach { it.onRelease(x, y) }
    fun handleDrag(x: Int, y: Int) = components.forEach { it.onDrag(x, y) }
    fun handleKey(e: KeyEvent): Boolean = components.any { it.onKey(e) }
}