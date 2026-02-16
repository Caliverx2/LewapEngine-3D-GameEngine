package org.lewapnoob.gridMap

import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.util.Collections
import javax.swing.SwingUtilities

open class InputManager(private val component: Component) {
    // Zbiór aktualnie wciśniętych klawiszy
    val keys: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf<Int>())
    // Zbiór klawiszy wciśniętych tylko w tej klatce
    val justPressedKeys: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf<Int>())

    // Obsługa myszki
    var isMouseCaptured = false
        private set
    var isLeftMouseDown = false
        private set
    var isRightMouseDown = false
        private set

    private val robot = try { Robot() } catch (e: AWTException) { null }
    private var windowPos = Point(0, 0)

    private val blankCursor: Cursor by lazy {
        val blankImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        Toolkit.getDefaultToolkit().createCustomCursor(blankImage, Point(0, 0), "blank")
    }

    val sensitivity = 0.003
    var mouseDeltaX = 0
        private set
    var mouseDeltaY = 0
        private set

    init {
        component.focusTraversalKeysEnabled = false
        component.addKeyListener(KeyboardListener())
        component.addMouseListener(MouseListener())
        component.addMouseMotionListener(MouseMotionListener())

        component.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                resetInputState()
                component.cursor = Cursor.getDefaultCursor()
            }
        })

        component.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent?) {
                if (component.isShowing) windowPos = component.locationOnScreen
            }
        })
    }

    fun captureMouse() {
        if (isMouseCaptured) return
        isMouseCaptured = true
        component.cursor = blankCursor
        if (component.isShowing) {
            windowPos = component.locationOnScreen
            // Reset deltas and center the mouse
            resetFrameState()
            robot?.mouseMove(windowPos.x + component.width / 2, windowPos.y + component.height / 2)
        }
    }

    fun releaseMouse() {
        if (!isMouseCaptured) return
        isMouseCaptured = false
        component.cursor = Cursor.getDefaultCursor()
    }

    fun isKeyDown(keyCode: Int): Boolean = keys.contains(keyCode)

    fun consumeJustPressedKeys(): Set<Int> {
        synchronized(justPressedKeys) {
            if (justPressedKeys.isEmpty()) return emptySet()
            val consumed = justPressedKeys.toSet()
            justPressedKeys.clear()
            return consumed
        }
    }

    fun resetFrameState() {
        mouseDeltaX = 0
        mouseDeltaY = 0
    }

    fun resetInputState() {
        keys.clear()
        justPressedKeys.clear()
        isMouseCaptured = false
        isLeftMouseDown = false
        isRightMouseDown = false
    }

    fun clearMouseState() {
        isLeftMouseDown = false
        isRightMouseDown = false
    }

    private inner class KeyboardListener : KeyAdapter() {
        override fun keyPressed(e: KeyEvent?) {
            e?.let {
                if (keys.add(it.keyCode)) {
                    justPressedKeys.add(it.keyCode)
                }
            }
        }

        override fun keyReleased(e: KeyEvent?) {
            e?.let { keys.remove(it.keyCode) }
        }
    }

    private inner class MouseListener : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            // The decision to capture the mouse is now external.
            // This listener just reports which button is down.
            if (SwingUtilities.isLeftMouseButton(e)) isLeftMouseDown = true
            if (SwingUtilities.isRightMouseButton(e)) isRightMouseDown = true
        }

        override fun mouseReleased(e: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e)) isLeftMouseDown = false
            if (SwingUtilities.isRightMouseButton(e)) isRightMouseDown = false
        }
    }

    private inner class MouseMotionListener : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            handleCameraLook(e)
        }

        override fun mouseDragged(e: MouseEvent) {
            handleCameraLook(e)
        }

        private fun handleCameraLook(e: MouseEvent) {
            if (isMouseCaptured && component.isShowing) {
                val centerX = component.width / 2
                val centerY = component.height / 2

                val dx = e.x - centerX
                val dy = e.y - centerY

                // Jeśli myszka jest na środku, nic nie rób (unikamy pętli zwrotnej od robota)
                if (dx == 0 && dy == 0) return

                mouseDeltaX += dx
                mouseDeltaY += dy

                // Centrujemy myszkę z powrotem
                robot?.mouseMove(windowPos.x + centerX, windowPos.y + centerY)
            }
        }
    }
}