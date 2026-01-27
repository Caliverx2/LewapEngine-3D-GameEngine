package org.lewapnoob.FileZeroVoxelEditor

import java.awt.AWTException
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Toolkit
import java.awt.GridLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import javax.swing.JColorChooser

// Data class representing a 3D Voxel coordinate
data class Voxel(val x: Int, val y: Int, val z: Int, val color: Int = Color.GREEN.rgb) {
    // Override equals and hashCode to only consider coordinates for the HashSet.
    // This prevents multiple voxels from occupying the same space.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Voxel) return false
        return x == other.x && y == other.y && z == other.z
    }
    override fun hashCode(): Int = 31 * (31 * x + y) + z
}

// Simple 3D Vector class for math
data class Vector3(var x: Double, var y: Double, var z: Double)

// Extension function to normalize a Vector3
fun Vector3.normalized(): Vector3 {
    val len = sqrt(x * x + y * y + z * z)
    if (len == 0.0) return Vector3(0.0, 0.0, 0.0)
    return Vector3(x / len, y / len, z / len)
}

class DemoDisplay : JPanel() {
    // World Data
    val voxels = HashSet<Voxel>()
    
    // Camera Settings
    private var camX = 0.0
    private var camY = 2.0
    private var camZ = -5.0
    private var camYaw = 0.0
    private var camPitch = 0.0
    
    // Input State
    private val keys = HashSet<Int>()
    private var mouseLookEnabled = false
    private var robot: Robot? = null

    // Color State
    var currentColor: Int = Color.WHITE.rgb
    var onVoxelPlaced: ((Int) -> Unit)? = null

    // Define faces and their normals once for efficiency.
    // Winding order is counter-clockwise when viewed from the outside.
    private val cubeFaces = listOf(
        Pair(intArrayOf(0, 1, 2, 3), Vector3(0.0, 0.0, -1.0)),  // Front face (-Z)
        Pair(intArrayOf(7, 6, 5, 4), Vector3(0.0, 0.0, 1.0)),   // Back face (+Z)
        Pair(intArrayOf(3, 2, 6, 7), Vector3(0.0, 1.0, 0.0)),   // Top face (+Y)
        Pair(intArrayOf(4, 5, 1, 0), Vector3(0.0, -1.0, 0.0)),  // Bottom face (-Y)
        Pair(intArrayOf(1, 5, 6, 2), Vector3(1.0, 0.0, 0.0)),   // Right face (+X)
        Pair(intArrayOf(4, 0, 3, 7), Vector3(-1.0, 0.0, 0.0))   // Left face (-X)
    )

    init {
        // Initialize with a simple floor
        for(x in -2..2) {
            for(z in -2..2) {
                voxels.add(Voxel(x, 0, z, Color.GREEN.rgb))
            }
        }

        // Setup Inputs
        isFocusable = true
        try {
            robot = Robot()
        } catch (e: AWTException) {
            e.printStackTrace()
            robot = null
        }

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                keys.add(e.keyCode)
                handleSinglePress(e.keyCode)
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    mouseLookEnabled = false
                    cursor = Cursor.getDefaultCursor()
                }
            }
            override fun keyReleased(e: KeyEvent) {
                keys.remove(e.keyCode)
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                if (mouseLookEnabled && robot != null) {
                    val centerX = width / 2
                    val centerY = height / 2

                    val dx = e.x - centerX
                    val dy = e.y - centerY

                    if (dx == 0 && dy == 0) return

                    val rotSpeed = 0.002 // Mouse sensitivity

                    camYaw -= dx * rotSpeed
                    camPitch += dy * rotSpeed

                    // Re-center the mouse
                    val centerOnScreen = Point(centerX, centerY)
                    SwingUtilities.convertPointToScreen(centerOnScreen, this@DemoDisplay)
                    robot?.mouseMove(centerOnScreen.x, centerOnScreen.y)
                }
            }
        })

        val mouseHandler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!mouseLookEnabled) {
                    mouseLookEnabled = true
                    val blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                        BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank cursor")
                    cursor = blankCursor
                    val centerOnScreen = Point(width / 2, height / 2)
                    SwingUtilities.convertPointToScreen(centerOnScreen, this@DemoDisplay)
                    robot?.mouseMove(centerOnScreen.x, centerOnScreen.y)
                } else {
                    handleMouseClick(e.button)
                }
            }
        }
        addMouseListener(mouseHandler)

        preferredSize = Dimension(1920 / 2, 1080 / 2)
        setBackground(Color.DARK_GRAY)
        
        // Start Game Loop
        loop()
    }

    private fun loop() {
        Timer(16) {
            updateCamera()
            repaint()
        }.start()
    }

    private fun handleSinglePress(keyCode: Int) {
        // Export
        if (keyCode == KeyEvent.VK_E) {
            val data = voxels.joinToString(";") { "${it.x},${it.y},${it.z},${it.color};" }
            println("=== EXPORTED MODEL ===")
            println()
            println(data)
            println()
            println("======================")
        }
        // Import
        if (keyCode == KeyEvent.VK_I) {
            val input = JOptionPane.showInputDialog(this, "Paste model data here:")
            if (input != null && input.isNotBlank()) {
                try {
                    voxels.clear()
                    input.split(";").forEach { part ->
                        val coords = part.split(",")
                        if (coords.size >= 3) {
                            val x = coords[0].toInt()
                            val y = coords[1].toInt()
                            val z = coords[2].toInt()
                            val c = if (coords.size > 3) coords[3].toInt() else Color.WHITE.rgb
                            voxels.add(Voxel(x, y, z, c))
                        }
                    }
                } catch (e: Exception) {
                    println("Import failed: ${e.message}")
                }
            }
        }
    }

    private fun updateCamera() {
        val speed = 0.1

        // Clamp pitch to avoid flipping upside down, which feels chaotic
        val maxPitch = Math.toRadians(89.0)
        camPitch = camPitch.coerceIn(-maxPitch, maxPitch)

        // Movement (WASD - Added for convenience to move around the model)
        val forwardX = -sin(camYaw) * speed
        val forwardZ = cos(camYaw) * speed
        val rightX = cos(camYaw) * speed
        val rightZ = sin(camYaw) * speed

        if (keys.contains(KeyEvent.VK_W)) { camX += forwardX; camZ += forwardZ }
        if (keys.contains(KeyEvent.VK_S)) { camX -= forwardX; camZ -= forwardZ }
        if (keys.contains(KeyEvent.VK_A)) { camX -= rightX; camZ -= rightZ }
        if (keys.contains(KeyEvent.VK_D)) { camX += rightX; camZ += rightZ }
        if (keys.contains(KeyEvent.VK_SPACE)) camY += speed
        if (keys.contains(KeyEvent.VK_SHIFT)) camY -= speed
    }

    private fun handleMouseClick(button: Int) {
        // Raycast from camera through the center of the screen (crosshair)
        val fov = Math.toRadians(90.0)
        val halfHeight = tan(fov / 2)
        val halfWidth = halfHeight * (width.toDouble() / height.toDouble())

        // Normalized Device Coordinates (-1 to 1)
        // Since the ray is from the center, NDC coordinates are (0, 0)
        val ndcX = 0.0
        val ndcY = 0.0

        // Calculate Ray Direction based on Camera Rotation
        val x = ndcX * halfWidth
        val y = ndcY * halfHeight
        val z = 1.0

        // Rotate ray by Pitch then Yaw
        // 1. Pitch (around X)
        val y1 = y * cos(camPitch) - z * sin(camPitch)
        val z1 = y * sin(camPitch) + z * cos(camPitch)
        // 2. Yaw (around Y)
        // This rotation logic now correctly matches the camera's movement and projection math.
        // The previous calculation had an inverted sign, causing the ray to fire in the wrong horizontal direction.
        val x2 = x * cos(camYaw) - z1 * sin(camYaw)
        val z2 = x * sin(camYaw) + z1 * cos(camYaw)

        val dir = Vector3(x2, y1, z2)
        // Normalize
        val len = sqrt(dir.x*dir.x + dir.y*dir.y + dir.z*dir.z)
        dir.x /= len; dir.y /= len; dir.z /= len

        // DDA Algorithm for precise voxel raycasting
        var mapX = kotlin.math.floor(camX + 0.5).toInt()
        var mapY = kotlin.math.floor(camY + 0.5).toInt()
        var mapZ = kotlin.math.floor(camZ + 0.5).toInt()

        val deltaDistX = if (dir.x == 0.0) 1e30 else kotlin.math.abs(1.0 / dir.x)
        val deltaDistY = if (dir.y == 0.0) 1e30 else kotlin.math.abs(1.0 / dir.y)
        val deltaDistZ = if (dir.z == 0.0) 1e30 else kotlin.math.abs(1.0 / dir.z)

        var stepX = 0
        var sideDistX = 0.0
        if (dir.x < 0) {
            stepX = -1
            sideDistX = (camX - (mapX - 0.5)) * deltaDistX
        } else {
            stepX = 1
            sideDistX = ((mapX + 0.5) - camX) * deltaDistX
        }

        var stepY = 0
        var sideDistY = 0.0
        if (dir.y < 0) {
            stepY = -1
            sideDistY = (camY - (mapY - 0.5)) * deltaDistY
        } else {
            stepY = 1
            sideDistY = ((mapY + 0.5) - camY) * deltaDistY
        }

        var stepZ = 0
        var sideDistZ = 0.0
        if (dir.z < 0) {
            stepZ = -1
            sideDistZ = (camZ - (mapZ - 0.5)) * deltaDistZ
        } else {
            stepZ = 1
            sideDistZ = ((mapZ + 0.5) - camZ) * deltaDistZ
        }

        var hit = false
        var side = 0 // 0:X, 1:Y, 2:Z
        var steps = 0
        val maxSteps = 200

        while (!hit && steps < maxSteps) {
            if (sideDistX < sideDistY) {
                if (sideDistX < sideDistZ) {
                    sideDistX += deltaDistX
                    mapX += stepX
                    side = 0
                } else {
                    sideDistZ += deltaDistZ
                    mapZ += stepZ
                    side = 2
                }
            } else {
                if (sideDistY < sideDistZ) {
                    sideDistY += deltaDistY
                    mapY += stepY
                    side = 1
                } else {
                    sideDistZ += deltaDistZ
                    mapZ += stepZ
                    side = 2
                }
            }

            val hitVoxel = Voxel(mapX, mapY, mapZ)
            if (voxels.contains(hitVoxel)) {
                hit = true
                if (button == MouseEvent.BUTTON1) { // Right Click - Remove
                    voxels.remove(hitVoxel)
                } else if (button == MouseEvent.BUTTON3) { // Left Click - Place
                    // Place at the previous empty step based on the side hit
                    var prevX = mapX
                    var prevY = mapY
                    var prevZ = mapZ
                    when (side) {
                        0 -> prevX -= stepX
                        1 -> prevY -= stepY
                        2 -> prevZ -= stepZ
                    }
                    voxels.add(Voxel(prevX, prevY, prevZ, currentColor))
                    onVoxelPlaced?.invoke(currentColor)
                }
            }
            steps++
        }
    }

    override fun paintComponent(g: Graphics?) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Clear Screen
        g2d.color = Color.DARK_GRAY
        g2d.fillRect(0, 0, width, height)

        // Render Voxels
        // Sort voxels by distance to camera (simple painter's algo)
        val sortedVoxels = voxels.sortedByDescending { 
            (it.x - camX)*(it.x - camX) + (it.y - camY)*(it.y - camY) + (it.z - camZ)*(it.z - camZ) 
        }

        for (v in sortedVoxels) {
            drawVoxel(g2d, v)
        }

        // Draw UI
        g2d.color = Color.LIGHT_GRAY
        g2d.font = Font("Arial", Font.BOLD, 20)
        g2d.drawString("Controls: E (Export), I (Import)", 20, 30)
        g2d.drawString("Voxels: ${voxels.size}", 20, 60)
        g2d.drawString("Kliknij, aby zablokować mysz. ESC, aby zwolnić.", 20, 90)
        
        // Draw Crosshair
        g2d.color = Color.WHITE
        g2d.drawLine(width/2 - 10, height/2, width/2 + 10, height/2)
        g2d.drawLine(width/2, height/2 - 10, width/2, height/2 + 10)
    }

    private fun drawVoxel(g: Graphics2D, v: Voxel) {
        // 8 corners of a cube in world space
        val worldPoints = arrayOf(
            Vector3(v.x - 0.5, v.y - 0.5, v.z - 0.5),
            Vector3(v.x + 0.5, v.y - 0.5, v.z - 0.5),
            Vector3(v.x + 0.5, v.y + 0.5, v.z - 0.5),
            Vector3(v.x - 0.5, v.y + 0.5, v.z - 0.5),
            Vector3(v.x - 0.5, v.y - 0.5, v.z + 0.5),
            Vector3(v.x + 0.5, v.y - 0.5, v.z + 0.5),
            Vector3(v.x + 0.5, v.y + 0.5, v.z + 0.5),
            Vector3(v.x - 0.5, v.y + 0.5, v.z + 0.5)
        )

        // Project all 8 points to screen space
        val projectedPoints = worldPoints.map { project(it) }

        // Simple clipping: if any vertex is behind the camera, don't draw the voxel.
        if (projectedPoints.any { it == null }) return

        val screenPoints = projectedPoints.filterNotNull()

        // Create a list of faces to render, culled and sorted
        val facesToRender = cubeFaces.mapNotNull { (indices, normal) ->
            // Back-face culling: check if the face is pointing towards the camera
            val faceCenter = Vector3(
                indices.sumOf { worldPoints[it].x } / indices.size,
                indices.sumOf { worldPoints[it].y } / indices.size,
                indices.sumOf { worldPoints[it].z } / indices.size
            )
            val viewDir = Vector3(faceCenter.x - camX, faceCenter.y - camY, faceCenter.z - camZ)
            val dotProduct = viewDir.x * normal.x + viewDir.y * normal.y + viewDir.z * normal.z

            if (dotProduct >= 0) { // If dot product is non-negative, face is pointing away from camera
                null
            } else {
                // Calculate average Z depth of the face in view space for sorting (Painter's Algorithm)
                var avgZ = 0.0
                for (i in indices) {
                    val p = worldPoints[i]
                    // This is a partial repeat of the projection logic, just to get view-space Z
                    val translatedX = p.x - camX
                    val translatedY = p.y - camY
                    val translatedZ = p.z - camZ

                    // Rotate around Yaw (Y axis)
                    val rotatedZ1 = translatedX * sin(-camYaw) + translatedZ * cos(-camYaw)
                    // Rotate around Pitch (X axis)
                    val rotatedZ2 = translatedY * sin(-camPitch) + rotatedZ1 * cos(-camPitch)
                    avgZ += rotatedZ2
                }
                Triple(indices, normal, avgZ / indices.size)
            }
        }.sortedByDescending { it.third } // Sort visible faces by depth (farthest first)


        for ((indices, normal, _) in facesToRender) {
            val polygon = java.awt.Polygon()
            for (i in indices) {
                polygon.addPoint(screenPoints[i].x, screenPoints[i].y)
            }

            // Simple directional lighting model
            val lightDir = Vector3(0.5, 1.0, 0.5).normalized() // Light from top-right-front
            val baseColor = Color(v.color)

            // Calculate light intensity based on the angle between the face normal and the light direction
            val dot = normal.x * lightDir.x + normal.y * lightDir.y + normal.z * lightDir.z
            // Remap dot product from [-1, 1] to a brightness factor [0.3, 1.0]
            val brightness = (dot * 0.3 + 0.65).coerceIn(0.3, 1.0)

            val r = (baseColor.red * brightness).toInt()
            val g_ = (baseColor.green * brightness).toInt()
            val b = (baseColor.blue * brightness).toInt()
            g.color = Color(r, g_, b)

            g.fill(polygon)
            g.color = g.color.darker() // Draw a slightly darker outline
            g.draw(polygon)
        }
    }
    private fun project(v: Vector3): Point? {
        // Translate relative to camera
        var x = v.x - camX
        var y = v.y - camY
        var z = v.z - camZ

        // Rotate Yaw (around Y)
        val x2 = x * cos(-camYaw) - z * sin(-camYaw)
        val z2 = x * sin(-camYaw) + z * cos(-camYaw)
        x = x2
        z = z2

        // Rotate Pitch (around X)
        val y3 = y * cos(-camPitch) - z * sin(-camPitch)
        val z3 = y * sin(-camPitch) + z * cos(-camPitch)
        y = y3
        z = z3

        // Perspective Projection
        if (z <= 0.1) return null // Behind camera or too close

        val fov = 500.0 // Scaling factor
        val screenX = (x / z) * fov + width / 2
        val screenY = -(y / z) * fov + height / 2

        return Point(screenX.toInt(), screenY.toInt())
    }
}

class PaletteWindow(private val display: DemoDisplay) : JFrame("Paleta Barw") {
    private val historyPanel = JPanel(FlowLayout(FlowLayout.LEFT))
    private val historyColors = HashSet<Int>()

    init {
        layout = BorderLayout()
        preferredSize = Dimension(340, 650)
        defaultCloseOperation = DO_NOTHING_ON_CLOSE // Don't close app on palette close

        val mainContainer = JPanel()
        mainContainer.layout = BoxLayout(mainContainer, BoxLayout.Y_AXIS)

        // 1. Basic Colors Grid
        val basicColorsPanel = JPanel(GridLayout(3, 3, 5, 5))
        basicColorsPanel.border = BorderFactory.createTitledBorder("Podstawowe")
        val basics = listOf(
            Color.RED, Color.GREEN, Color.BLUE,
            Color.YELLOW, Color.CYAN, Color.MAGENTA,
            Color.WHITE, Color.GRAY, Color.BLACK
        )

        for (c in basics) {
            val btn = createColorButton(c.rgb)
            basicColorsPanel.add(btn)
        }

        // 2. Custom Color Picker
        val pickerBtn = JButton("Wybierz inny kolor...")
        pickerBtn.addActionListener {
            val newColor = JColorChooser.showDialog(this, "Wybierz kolor voxela", Color(display.currentColor))
            if (newColor != null) {
                display.currentColor = newColor.rgb
            }
        }

        val colorsContainer = JPanel(BorderLayout())
        colorsContainer.add(basicColorsPanel, BorderLayout.CENTER)
        colorsContainer.add(pickerBtn, BorderLayout.SOUTH)
        colorsContainer.maximumSize = Dimension(340, 160)

        // 2.5 Grid Map Editor
        val gridPanel = JPanel(BorderLayout())
        gridPanel.border = BorderFactory.createTitledBorder("Mapa 2D (Edytor)")

        val ySlider = JSlider(JSlider.VERTICAL, -25, 25, 0)
        val yField = JTextField("0", 3)

        val gridCanvas = object : JPanel() {
            init {
                preferredSize = Dimension(220, 220)
                background = Color.BLACK
                val mouseHandler = object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) = handleInput(e)
                    override fun mouseDragged(e: MouseEvent) = handleInput(e)
                    private fun handleInput(e: MouseEvent) {
                        val gridSize = 21
                        val cellW = width.toDouble() / gridSize
                        val cellH = height.toDouble() / gridSize
                        val col = (e.x / cellW).toInt()
                        val row = (e.y / cellH).toInt()
                        if (col in 0 until gridSize && row in 0 until gridSize) {
                            val vx = 10 - col // Odwrócenie osi X
                            val vz = row - 10
                            val vy = ySlider.value
                            val targetVoxel = Voxel(vx, vy, vz, display.currentColor)

                            if (SwingUtilities.isLeftMouseButton(e)) { // Lewy przycisk (lub przeciągnięcie) - stawiaj/maluj
                                display.voxels.remove(targetVoxel) // Usuń stary, jeśli istnieje, by zaktualizować kolor
                                display.voxels.add(targetVoxel)    // Dodaj nowy z aktualnym kolorem
                                display.onVoxelPlaced?.invoke(display.currentColor)
                            } else if (SwingUtilities.isRightMouseButton(e)) { // Prawy przycisk (lub przeciągnięcie) - usuwaj
                                display.voxels.remove(targetVoxel)
                            }
                            repaint()
                        }
                    }
                }
                addMouseListener(mouseHandler)
                addMouseMotionListener(mouseHandler)
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val gridSize = 21
                val cellW = width.toDouble() / gridSize
                val cellH = height.toDouble() / gridSize
                g.color = Color.DARK_GRAY
                for (i in 0..gridSize) {
                    val p = (i * cellW).toInt()
                    g.drawLine(p, 0, p, height)
                    val py = (i * cellH).toInt()
                    g.drawLine(0, py, width, py)
                }
                val vy = ySlider.value
                display.voxels.filter { it.y == vy }.forEach { v ->
                    val col = 10 - v.x // Odwrócenie osi X
                    val row = v.z + 10
                    if (col in 0 until gridSize && row in 0 until gridSize) {
                        g.color = Color(v.color)
                        g.fillRect((col * cellW).toInt() + 1, (row * cellH).toInt() + 1, cellW.toInt() - 1, cellH.toInt() - 1)
                    }
                }
                g.color = Color.WHITE
                g.drawRect((10 * cellW).toInt(), (10 * cellH).toInt(), cellW.toInt(), cellH.toInt())
            }
        }

        val controlsPanel = JPanel(BorderLayout())
        yField.horizontalAlignment = JTextField.CENTER
        controlsPanel.add(yField, BorderLayout.NORTH)
        controlsPanel.add(ySlider, BorderLayout.CENTER)

        ySlider.addChangeListener {
            yField.text = ySlider.value.toString()
            gridCanvas.repaint()
        }
        yField.addActionListener {
            try {
                val v = yField.text.toInt().coerceIn(-25, 25)
                ySlider.value = v
                gridCanvas.repaint()
            } catch (_: Exception) {}
        }

        gridPanel.add(gridCanvas, BorderLayout.CENTER)
        gridPanel.add(controlsPanel, BorderLayout.EAST)

        Timer(100) { gridCanvas.repaint() }.start()

        // 3. History
        historyPanel.border = BorderFactory.createTitledBorder("Historia")
        historyPanel.preferredSize = Dimension(280, 150)

        // Assembly
        mainContainer.add(colorsContainer)
        mainContainer.add(gridPanel)
        mainContainer.add(historyPanel)

        add(mainContainer, BorderLayout.CENTER)

        pack()
    }

    fun addToHistory(rgb: Int) {
        if (!historyColors.contains(rgb)) {
            historyColors.add(rgb)
            val btn = createColorButton(rgb)
            historyPanel.add(btn)
            historyPanel.revalidate()
            historyPanel.repaint()
        }
    }

    private fun createColorButton(rgb: Int): JButton {
        val btn = JButton()
        btn.preferredSize = Dimension(30, 30)
        btn.background = Color(rgb)
        btn.isContentAreaFilled = false
        btn.isOpaque = true
        btn.addActionListener {
            display.currentColor = rgb
        }
        return btn
    }
}

fun main() {
    SwingUtilities.invokeLater {
        val display = DemoDisplay()
        val palette = PaletteWindow(display)

        // Link history logic
        display.onVoxelPlaced = { color -> palette.addToHistory(color) }

        val frame = JFrame("FileZeroVoxelEditor.kt")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.isResizable = false
        frame.add(display)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        
        // Show palette next to main window
        palette.location = Point(frame.x + frame.width + 10, frame.y)
        palette.isVisible = true
    }
}