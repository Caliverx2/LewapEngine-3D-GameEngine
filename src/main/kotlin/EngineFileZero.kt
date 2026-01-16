package org.lewapnoob.FileZero

import javafx.animation.AnimationTimer
import javafx.embed.swing.SwingFXUtils
import javafx.scene.canvas.Canvas
import javafx.scene.paint.Color
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.image.WritableImage
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.scene.image.PixelFormat
import javafx.scene.text.Font
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.imageio.ImageIO
import java.awt.Robot
import javafx.scene.Cursor
import kotlin.math.*

class DrawingPanel : StackPane() {
    private var lastUpdateTime = System.nanoTime()
    private var frameCount = 0
    private var lastFpsTime = 0L
    private var fps = 0

    private val physicsTimestep = 1.0 / 120.0
    private var accumulator = 0.0

    private val isRendering = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lightGizmoMeshes = Collections.synchronizedList(mutableListOf<PlacedMesh>())
    private val lightSources = Collections.synchronizedList(mutableListOf<LightSource>())
    private data class OrbitingLight(val light: LightSource, val center: Vector3d, var angle: Double = 0.0)
    private val orbitingLights = Collections.synchronizedList(mutableListOf<OrbitingLight>())

    private data class StaticMeshBatch(
        val mesh: Mesh,
        val texture: Image?,
        val aabb: AABB,
        val faceIndexToOriginal: Map<Int, Pair<PlacedMesh, Int>>
    )

    private data class SubLightmap(
        val grid: Array<Array<Color>>,
        val texture: WritableImage,
        val worldPosition: Vector3d,
        val aabb: AABB,
        var needsUpdate: Boolean = true,
        var lastLitTime: Long = System.nanoTime(),
        var isLitByStaticLight: Boolean = false
    )

    private data class GI_LightPoint(
        val targetFaceKey: Pair<PlacedMesh, Int>,
        val gridX: Int,
        val gridY: Int,
        val color: Vector3d
    )

    private data class FaceToProcess(
        val source: Any, // PlacedMesh or StaticMeshBatch
        val faceIndex: Int,
        val indices: List<Int>,
        val worldVertices: List<Vector3d>,
        val hasCollision: Boolean
    )

    private data class PathfindingAgent(
        val id: Long,
        var position: Vector3d,
        var targetPosition: Vector3d,
        var path: List<Vector3d>? = null,
        var currentPathIndex: Int = 0,
        val speed: Double = 200.0,
        var mesh: PlacedMesh,
        var lastPathUpdateTime: Long = 0,
        var lastNavMeshCheckTime: Long = 0
    )


    private var staticMeshBatches = listOf<StaticMeshBatch>()
    private val textureTransparencyCache = ConcurrentHashMap<Image, Boolean>()
    private val textureCache = ConcurrentHashMap<Image, IntArray>()
    private val textureDimensions = ConcurrentHashMap<Image, Pair<Int, Int>>()

    private val faceLightGrids = ConcurrentHashMap<Pair<PlacedMesh, Int>, Array<Array<Color>>>()
    private val largeFaceSubLightmaps = ConcurrentHashMap<Pair<PlacedMesh, Int>, MutableList<SubLightmap>>()
    private val giLightGrids = ConcurrentHashMap<Pair<PlacedMesh, Int>, Array<Array<Color>>>()
    private val lightingUpdateQueue = ConcurrentLinkedQueue<Pair<PlacedMesh, Int>>()
    private val giUpdateQueue = ConcurrentLinkedQueue<Pair<PlacedMesh, Int>>()
    private val giLightPoints = Collections.synchronizedList(mutableListOf<GI_LightPoint>())
    private val lightingExecutor = Executors.newSingleThreadExecutor()
    private val lightingUpdateJobQueue = ConcurrentLinkedQueue<LightSource>()
    private var lightingFuture: Future<*>? = null

    private val cubeSize = 100.0
    private var gridDimension = 9

    private val playerHitboxScale = 0.3
    private val baseHitboxScale = 0.3
    private val playerHitboxOffset = Vector3d(0.0, -cubeSize * 1.0 * playerHitboxScale, 0.0)
    private val playerVertexRadius = 0.1 * cubeSize * (playerHitboxScale / baseHitboxScale)
    private lateinit var playerHitboxMesh: PlacedMesh

    private var cameraPosition = Vector3d(0.0, 0.0, 0.0)
    private var cameraYaw = 2.4
    private var cameraPitch = 0.0
    private val baseFov = 90.0
    private val floorLevel = -3.4075 * cubeSize
    private val dynamicFov = (baseFov / sqrt(playerHitboxScale / baseHitboxScale).coerceAtLeast(0.5)).coerceIn(60.0..120.0)

    private var fogMode = true
    private var debugFly = false
    private var debugNoclip = false
    private var debugShowHitboxes = false
    private var debugShowNavMesh = false
    private var retroScanLineMode = false
    private var antyAliasingMode = false

    // Stan opadania
    private val gravityAcceleration = 9.8 * cubeSize // Przyspieszenie grawitacyjne (cubeSize/s2)
    private var isGrounded = true
    private var verticalVelocity = 0.0
    private val groundedStateHistory = ConcurrentLinkedQueue<Boolean>()
    // Minimalna „pionowość” normalnej, aby traktować powierzchnię jako podłogę/rampę (a nie ścianę)
    private val minGroundNormalY = 0.7 // ~45° nachylenia
    // Punkt kontaktu musi być wyraźnie pod środkiem sfery, żeby nie „łapać” boków ścian jako podłoże
    private val groundContactVerticalOffset = 0.3

    private val fogColor = Color.rgb(180, 180, 180)
    private val fogStartDistance = 1.5 * cubeSize
    private val fogEndDistance = 7.0 * cubeSize
    private val fogDensity = 0.5
    private var fogAffectsNonCollidables = false

    private val ambientIntensity = 0.5
    private val HIGH_QualityRes = 16
    private val LOW_QualityRes = 8
    private val globalLightIntensity = 6.0
    private val GI_LightIntensity = 1.1
    private val GI_SAMPLES = 4 // 4
    private val bouncesLeft = 2 // 2

    private val subLightmap_offset = 0.01 * cubeSize
    private val largeFaceThreshold = 4.0 * cubeSize
    private val renderDownscaleFactor = 4
    private val renderDistanceBlocks = 24.0 * cubeSize
    private val baseResolutionWidth = 1920
    private val baseResolutionHeight = 1080
    private val virtualWidth = baseResolutionWidth / renderDownscaleFactor
    private val virtualHeight = baseResolutionHeight / renderDownscaleFactor

    private val outputUpscaleFactor = 2
    private val outputWidth = virtualWidth * outputUpscaleFactor
    private val outputHeight = virtualHeight * outputUpscaleFactor
    private lateinit var upscaledPixelBuffer: IntArray
    private var previousCombinedMatrix: Matrix4x4? = null
    private lateinit var depthBuffer: DoubleArray
    private lateinit var pixelBuffer: IntArray
    private lateinit var msaaDepthBuffer: DoubleArray
    private lateinit var msaaColorBuffer: IntArray
    private val msaaOffsetsX = doubleArrayOf(0.375, 0.875, 0.125, 0.625)
    private val msaaOffsetsY = doubleArrayOf(0.125, 0.375, 0.625, 0.875)
    private var bgColorInt: Int
    private lateinit var backBuffer: WritableImage
    private val imageView = ImageView()
    private val overlayCanvas = Canvas(virtualWidth.toDouble(), virtualHeight.toDouble())
    lateinit var texSkybox: Image
    lateinit var texBlackBricks: Image
    lateinit var texBricks: Image
    lateinit var texCeiling: Image
    lateinit var texFloor: Image
    private val dynamicTextures = ConcurrentHashMap<String, Image>()
    private var modelRegistry: Map<String, Mesh>

    private var isMouseCaptured = false
    private val mouseSensitivity = 0.006
    private var accumulatedMouseDeltaX = 1.0
    private var accumulatedMouseDeltaY = 1.0

    private val robot: Robot by lazy { Robot() }
    private var isRobotCentering = false

    val pressedKeys = Collections.synchronizedSet(mutableSetOf<KeyCode>())

    private val staticCollisionGrid = SpatialGrid<PlacedMesh>(cubeSize * 2)
    private val staticBvh = BVH()

    // Struktury dla obiektów dynamicznych
    private val dynamicCollisionGrid = SpatialGrid<PlacedMesh>(cubeSize * 2)
    private val dynamicBvh = BVH()

    private val meshAABBs = ConcurrentHashMap<PlacedMesh, AABB>()
    private val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    private val renderQueue = ConcurrentLinkedQueue<RenderableFace>()
    private val transparentRenderQueue = ConcurrentLinkedQueue<RenderableFace>()

    private var lastLightCheckTime = 0L
    private val fpsFont: Font
    private val lightCheckInterval = (1_000_000_000.0 / 30.0).toLong() // 30 Hz

    private val serverPort: Int = 1050
    private val serverAddress: InetAddress = InetAddress.getByName("lewapnoob.ddns.net")
    private val clientSocket: DatagramSocket = DatagramSocket()
    private var lastPacketSendTime = 0L
    private val staticMeshes = mutableListOf<PlacedMesh>()
    private val keepAliveTimer = java.util.Timer()
    private val dynamicMeshes = ConcurrentHashMap<String, PlacedMesh>()

    private val pathfindingAgents = Collections.synchronizedList(mutableListOf<PathfindingAgent>())
    private data class PlayerState(
        var currentPos: Vector3d,
        var currentYaw: Double,
        var targetPos: Vector3d,
        var targetYaw: Double
    )
    private val otherPlayers = ConcurrentHashMap<String, PlayerState>()

    private var lastSentPacketData: String? = null
    private var externalPushVector = Vector3d(0.0, 0.0, 0.0)
    private var navMesh: NavMesh? = null
    private val generatedNavMeshRegions = mutableSetOf<Triple<Int, Int, Int>>()

    init {
        sceneProperty().addListener { _, _, newScene ->
            if (newScene != null) {
                newScene.windowProperty().addListener { _, _, newWindow ->
                    if (newWindow != null) {
                        newWindow.setOnCloseRequest {
                            System.exit(0)
                        }
                    }
                }
            }
        }

        fpsFont = try {
            Font.loadFont(DrawingPanel::class.java.classLoader.getResourceAsStream("fonts/mojangles.ttf"), 30.0)
                ?: Font.font("Consolas", 16.0)
        } catch (e: Exception) {
            println("Failed to load font 'mojangles.ttf', using default.")
            Font.font("Consolas", 16.0)
        }

        depthBuffer = DoubleArray(virtualWidth * virtualHeight) { Double.MAX_VALUE }
        pixelBuffer = IntArray(virtualWidth * virtualHeight)
        msaaDepthBuffer = DoubleArray(virtualWidth * virtualHeight * 4) { Double.MAX_VALUE }
        msaaColorBuffer = IntArray(virtualWidth * virtualHeight * 4)
        upscaledPixelBuffer = IntArray(outputWidth * outputHeight)
        backBuffer = WritableImage(outputWidth, outputHeight)

        val bgColor = Color.rgb(40, 40, 40)
        bgColorInt = colorToInt(bgColor)

        imageView.image = backBuffer
        imageView.fitWidthProperty().bind(this.widthProperty())
        imageView.fitHeightProperty().bind(this.heightProperty())

        overlayCanvas.widthProperty().bind(this.widthProperty())
        overlayCanvas.heightProperty().bind(this.heightProperty())
        overlayCanvas.graphicsContext2D.isImageSmoothing = false

        setOnMouseClicked {
            if (!isMouseCaptured) {
                requestFocus()
                scene.cursor = Cursor.NONE
                isMouseCaptured = true
            }
        }

        setOnMouseMoved { event: MouseEvent ->
            if (isMouseCaptured) {
                if (isRobotCentering) {
                    isRobotCentering = false
                    return@setOnMouseMoved
                }

                val window = scene.window
                val centerX = window.x + window.width / 2
                val centerY = window.y + window.height / 2

                val deltaX = event.screenX - centerX
                val deltaY = event.screenY - centerY

                if (deltaX == 0.0 && deltaY == 0.0) {
                    return@setOnMouseMoved
                }

                accumulatedMouseDeltaX += deltaX
                accumulatedMouseDeltaY += deltaY

                // Center the mouse
                isRobotCentering = true
                robot.mouseMove(centerX.toInt(), centerY.toInt())
            }
        }

        children.addAll(imageView, overlayCanvas)

        cameraPosition = Vector3d(-2.5 * cubeSize, floorLevel + cubeSize, 1.5 * cubeSize)

        val grids = listOf(GRID_1, GRID_2, GRID_3, GRID_4)
        for (x in 0 until gridDimension) {
            for (y in grids.indices) {
                for (z in 0 until gridDimension) {
                    when (grids[y][x][z]) {
                        1 -> GRID_MAP[z][y][x] = 1
                        2 -> GRID_MAP[z][y][x] = 2
                        3 -> GRID_MAP[z][y][x] = 3
                    }
                }
            }
        }
        texBlackBricks = loadImage("textures/black_bricks.png")
        texBricks = loadImage("textures/bricks.jpg")
        texCeiling = loadImage("textures/ceiling.jpg")
        texFloor = loadImage("textures/floor.jpg")
        texSkybox = loadImage("textures/skybox.png")

        modelRegistry = mapOf(
            "cube" to createCubeMesh(cubeSize, Color.GRAY),
            "cubeRed" to createCubeMesh(cubeSize, Color.RED),
            "cubeGates" to createCubeMesh(cubeSize, Color.rgb(40, 255, 40)),
            "skybox" to createSkyboxMesh(1000.0 * cubeSize, Color.rgb(80, 80, 80), inverted = true),
            "pyramid" to createPyramidMesh(cubeSize, Color.RED),
            "invertedPyramid" to createInvertedPyramidMesh(cubeSize, Color.DEEPSKYBLUE),
            "tower" to createTowerMesh(cubeSize, Color.WHITE),
            "kotlin" to createKotlinModelMesh(cubeSize, Color.DARKVIOLET),
            "tank" to createTankMesh(cubeSize, Color.GREEN),
            "offroadCar" to createOffroadCarMesh(cubeSize, Color.GRAY),
            "stair" to createStairMesh(cubeSize, Color.WHITE),
            "sphere" to createCapsuleMesh(cubeSize, Color.WHITE),
            "map" to createMapMesh(cubeSize, Color.WHITE),
            "colorPalette" to createColorPaletteMesh(cubeSize, Color.WHITE),
            "TNT" to createTNTMesh(cubeSize, Color.WHITE),
            "player" to createPlayerMesh(cubeSize, Color.WHITE),
            "rtMap" to createRayTracingMapMesh(cubeSize, Color.WHITE),
            "ramp" to createRampMesh(cubeSize, Color.WHITE),
            "fence" to createFenceMesh(cubeSize, Color.WHITE),
            "plate" to createPlateMesh(100 * cubeSize, Color.WHITE),
        )

        // Inicjalizacja hitboxa gracza
        val playerCollisionModel = modelRegistry["sphere"] ?: throw IllegalStateException("Sphere model not found in registry")
        playerHitboxMesh = PlacedMesh(playerCollisionModel, collision = true)

        modelRegistry.forEach { (modelName, mesh) ->
            mesh.customTextures.forEach { (localId, hexData) ->
                val globalId = "${modelName}_$localId"
                dynamicTextures.computeIfAbsent(globalId) { createTextureFromHexData(hexData) }
            }
        }

        val pos0 = Vector3d(0.0, 0.0, 0.0)
        staticMeshes.add(PlacedMesh(modelRegistry["skybox"]!!, Matrix4x4.translation(pos0.x, pos0.y, pos0.z), faceTextures = placedTextures("skybox", modelRegistry["skybox"]!!), collision = false))

        val pos1 = Vector3d(5.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["pyramid"]!!, Matrix4x4.translation(pos1.x, pos1.y, pos1.z), faceTextures = placedTextures("pyramid", modelRegistry["pyramid"]!!)))

        val pos2 = Vector3d(7.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["invertedPyramid"]!!, Matrix4x4.translation(pos2.x, pos2.y, pos2.z), faceTextures = placedTextures("invertedPyramid", modelRegistry["invertedPyramid"]!!)))

        val pos3 = Vector3d(9.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["tower"]!!, Matrix4x4.translation(pos3.x, pos3.y, pos3.z) * Matrix4x4.rotationY(PI/4), texture = texBricks))

        val pos4 = Vector3d(11.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["kotlin"]!!, Matrix4x4.translation(pos4.x, pos4.y, pos4.z), texture = texFloor))

        val pos5 = Vector3d(13.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["tank"]!!, Matrix4x4.translation(pos5.x, pos5.y, pos5.z), faceTextures = placedTextures("tank", modelRegistry["tank"]!!)))

        val pos6 = Vector3d(15.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["offroadCar"]!!, Matrix4x4.translation(pos6.x, pos6.y, pos6.z), texture = texBlackBricks))

        val pos7 = Vector3d(17.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["stair"]!!, Matrix4x4.translation(pos7.x, pos7.y, pos7.z), texture = texCeiling, faceTextures = placedTextures("stair", modelRegistry["stair"]!!)))

        val pos8 = Vector3d(19.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["sphere"]!!, Matrix4x4.translation(pos8.x, pos8.y, pos8.z), texture = texCeiling))

        val pos9 = Vector3d(31.0 * cubeSize, -4.0 * cubeSize, 0 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["map"]!!, Matrix4x4.translation(pos9.x, pos9.y, pos9.z), faceTextures = placedTextures("map", modelRegistry["map"]!!)))

        val pos10 = Vector3d(50.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["colorPalette"]!!, Matrix4x4.translation(pos10.x, pos10.y, pos10.z), faceTextures = placedTextures("colorPalette", modelRegistry["colorPalette"]!!)))

        val pos11 = Vector3d(21.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["TNT"]!!, Matrix4x4.translation(pos11.x, pos11.y, pos11.z), faceTextures = placedTextures("TNT", modelRegistry["TNT"]!!)))

        val pos12 = Vector3d(23.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["rtMap"]!!, Matrix4x4.translation(pos12.x, pos12.y, pos12.z), faceTextures = placedTextures("rtMap", modelRegistry["rtMap"]!!)))

        val pos13 = Vector3d(4.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["ramp"]!!, Matrix4x4.translation(pos13.x, pos13.y, pos13.z), faceTextures = placedTextures("ramp", modelRegistry["ramp"]!!)))

        val pos14 = Vector3d(25.5 * cubeSize, -4.0 * cubeSize, 0.5 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["fence"]!!, Matrix4x4.translation(pos14.x, pos14.y, pos14.z), faceTextures = placedTextures("fence", modelRegistry["fence"]!!)))

        val pos15 = Vector3d(0.5 * cubeSize, -4.01 * cubeSize, 0 * cubeSize)
        staticMeshes.add(PlacedMesh(modelRegistry["plate"]!!, Matrix4x4.translation(pos15.x, pos15.y, pos15.z), faceTextures = placedTextures("plate", modelRegistry["plate"]!!), alwaysRender = true))

        for (x in 0 until gridDimension) {
            for (y in 0 until gridDimension) {
                for (z in 0 until gridDimension) {
                    val offset = (gridDimension) / 2.0
                    val pos = Vector3d(
                        (x - offset) * cubeSize,
                        (y - offset) * cubeSize,
                        (z - offset) * cubeSize
                    )
                    val mat = Matrix4x4.translation(pos.x, pos.y, pos.z)
                    when (GRID_MAP[x][y][z]) {
                        1 -> staticMeshes.add(PlacedMesh(modelRegistry["cube"]!!, mat, texBlackBricks, collisionPos = pos))
                        2 -> staticMeshes.add(PlacedMesh(modelRegistry["cubeRed"]!!, mat, texBlackBricks, collisionPos = pos))
                        3 -> {staticMeshes.add(PlacedMesh(modelRegistry["cubeGates"]!!, mat, texBricks, collision=false, collisionPos = pos))
                            println("${pos.x} ${pos.y} ${pos.z}")}
                    }
                }
            }
        }

        staticMeshes.forEach { mesh ->
            val aabb = AABB.fromCube(mesh.getTransformedVertices())
            meshAABBs[mesh] = aabb
        }

        rebuildStaticPhysics()

        rebuildPhysicsStructures()
        rebuildStaticMeshBatches()

        warmUpTextureCache()

        println("Client started on local port: ${clientSocket.localPort}")
        println("Listening for server messages from ${serverAddress.hostAddress}:$serverPort")

        val initialMessageString = "POSITION,${cameraPosition.x},${cameraPosition.y},${cameraPosition.z},${cameraYaw - PI}"
        lastSentPacketData = initialMessageString
        val initialMessage = initialMessageString.toByteArray()
        val initialPacket = DatagramPacket(initialMessage, initialMessage.size, serverAddress, serverPort)
        try {
            clientSocket.send(initialPacket)
            println("Wysłano początkową pozycję do serwera.")
        } catch (e: Exception) {
            println("Błąd podczas wysyłania początkowej pozycji: ${e.message}")
        }

        Thread { listenForServerMessages() }.start()

        keepAliveTimer.schedule(object : java.util.TimerTask() {
            override fun run() {
                sendKeepAlive()
            }
        }, 0, 1000)

        object : AnimationTimer() {
            override fun handle(now: Long) {
                val rawDeltaTime = (now - lastUpdateTime) / 1_000_000_000.0
                lastUpdateTime = now
                accumulator += rawDeltaTime

                if (now - lastFpsTime >= 1_000_000_000) {
                    fps = frameCount
                    frameCount = 0
                    lastFpsTime = now
                }

                while (accumulator >= physicsTimestep) {
                    updateCameraPosition(physicsTimestep)
                    updateGameLogic(physicsTimestep)
                    accumulator -= physicsTimestep
                }

                if (now - lastLightCheckTime > lightCheckInterval) {
                    lastLightCheckTime = now
                    updateDynamicLights()
                }

                requestRender()
            }
        }.start()
    }

    private fun listenForServerMessages() {
        val buffer = ByteArray(1024)
        while (true) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                clientSocket.receive(packet)
                val message = String(packet.data, 0, packet.length)
                val parts = message.split(",")

                // Rozdziel logikę w zależności od typu wiadomości
                when (parts.getOrNull(0)) {
                    "DISCONNECT" -> {
                        // Serwer informuje, że gracz się rozłączył
                        if (parts.size == 2) {
                            val disconnectedClientId = parts[1]
                            if (otherPlayers.remove(disconnectedClientId) != null) {
                                println("Gracz się rozłączył (ID: $disconnectedClientId).")
                                dynamicMeshes.remove(disconnectedClientId)
                            }
                        }
                    }
                    "PUSH_ME" -> {
                        if (parts.size == 4) {
                            val pushX = parts[1].toDoubleOrNull() ?: 0.0
                            val pushY = parts[2].toDoubleOrNull() ?: 0.0
                            val pushZ = parts[3].toDoubleOrNull() ?: 0.0

                            externalPushVector += Vector3d(pushX, pushY, pushZ)
                        }
                    }
                    else -> {
                        // Standardowa wiadomość o pozycji gracza ("POSITION",uuid,x,y,z,yaw)
                        if (parts.size == 6 && parts[0] == "POSITION") {
                            val receivedClientId = parts[1]
                            // Ignoruj pakiety od samego siebie (serwer nie powinien ich odsyłać, ale to zabezpieczenie)
                            if (receivedClientId != "${clientSocket.localAddress.hostAddress}:${clientSocket.localPort}") {
                                val x = parts[2].toDouble()
                                val y = parts[3].toDouble()
                                val z = parts[4].toDouble()
                                val yaw = parts[5].toDouble()
                                val newPos = Vector3d(x, y, z)

                                // Zaktualizuj stan innego gracza lub dodaj go, jeśli jest nowy
                                otherPlayers.compute(receivedClientId) { _, existingState ->
                                    if (existingState == null) {
                                        println("Nowy gracz dołączył (ID: $receivedClientId)")
                                        PlayerState(newPos, yaw, newPos, yaw)
                                    } else {
                                        existingState.targetPos = newPos
                                        existingState.targetYaw = yaw
                                        existingState
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun placedTextures(modelName: String, mesh: Mesh): Map<Int, Image> {
        val loadedTextures = mapOf(
            "blackBricks" to texBlackBricks,
            "bricks" to texBricks,
            "ceiling" to texCeiling,
            "floor" to texFloor,
            "skybox" to texSkybox,
            "red" to createColorTexture(Color.RED),
            "darkRed" to createColorTexture(Color.DARKRED),
            "blue" to createColorTexture(Color.BLUE),
            "darkBlue" to createColorTexture(Color.DARKBLUE),
            "green" to createColorTexture(Color.GREEN),
            "darkGreen" to createColorTexture(Color.DARKGREEN),
            "gray" to createColorTexture(Color.GRAY),
            "darkGray" to createColorTexture(Color.DARKGRAY),
            "orange" to createColorTexture(Color.ORANGE),
            "darkOrange" to createColorTexture(Color.DARKORANGE),
            "yellow" to createColorTexture(Color.YELLOW),
            "darkGoldenrod" to createColorTexture(Color.DARKGOLDENROD),
            "cyan" to createColorTexture(Color.CYAN),
            "darkCyan" to createColorTexture(Color.DARKCYAN),
            "magenta" to createColorTexture(Color.MAGENTA),
            "darkMagenta" to createColorTexture(Color.DARKMAGENTA),
            "brown" to createColorTexture(Color.BROWN),
            "navy" to createColorTexture(Color.NAVY),
            "purple" to createColorTexture(Color.PURPLE),
            "darkViolet" to createColorTexture(Color.DARKVIOLET),
            "olive" to createColorTexture(Color.OLIVE),
            "maroon" to createColorTexture(Color.MAROON),
            "teal" to createColorTexture(Color.TEAL),
            "black" to createColorTexture(Color.web("#000000")),
            "darkPurple" to createColorTexture(Color.web("#7E2553")),
            "lightGray" to createColorTexture(Color.web("#C2C3C7")),
            "white" to createColorTexture(Color.web("#FFF1E8")),
            "indigo" to createColorTexture(Color.web("#83769C")),
            "pink" to createColorTexture(Color.web("#FF77A8")),
            "peach" to createColorTexture(Color.web("#FFCCAA")),
            "silver" to createColorTexture(Color.SILVER),
            "gold" to createColorTexture(Color.GOLD),
            "mintCream" to createColorTexture(Color.MINTCREAM),
            "salmon" to createColorTexture(Color.SALMON),
            "turquoise" to createColorTexture(Color.TURQUOISE),
            "orchid" to createColorTexture(Color.ORCHID)
        )
        return mesh.faceTextureNames.mapValues { (_, textureName) ->
            loadedTextures[textureName]
                ?: dynamicTextures["${modelName}_${textureName.substringAfter("custom_")}"]
                ?: texSkybox
        }
    }

    fun handleKeyRelease(code: KeyCode) {
        if (code == KeyCode.ESCAPE) {
            isMouseCaptured = false
            scene.cursor = Cursor.DEFAULT
        }
        if (code == KeyCode.NUMPAD9) {
            debugFly = !debugFly
            println("debugFly: $debugFly")
        }
        if (code == KeyCode.NUMPAD8) {
            debugNoclip = !debugNoclip
            println("debugNoclip: $debugNoclip")
        }
        if (code == KeyCode.NUMPAD7) {
            retroScanLineMode = !retroScanLineMode
            println("CRT Monitor Mode: $retroScanLineMode")
        }
        if (code == KeyCode.NUMPAD6) {
            debugShowHitboxes = !debugShowHitboxes
            println("debugShowHitboxes: $debugShowHitboxes")
        }
        if (code == KeyCode.NUMPAD5) {
            debugShowNavMesh = !debugShowNavMesh
            println("debugShowNavMesh: $debugShowNavMesh")
        }
        if (code == KeyCode.NUMPAD4) {
            antyAliasingMode = !antyAliasingMode
            println("antyAliasingMode: $antyAliasingMode")
        }
        if (code == KeyCode.NUMPAD3) {
            fogMode = !fogMode
            println("fogMode: $fogMode")
        }
        if (code == KeyCode.J) {
            val agentStartPos = Vector3d(63.2, -331.0, -868.3)
            if (navMesh == null) {
                navMesh = NavMesh(mutableListOf(), mutableMapOf(), cubeSize / 2.0)
                extendNavMesh(agentStartPos, 5.0 * cubeSize)
            }

            val agentModel = modelRegistry["player"] ?: return
            val agentMesh = PlacedMesh(
                agentModel,
                Matrix4x4.translation(agentStartPos.x, agentStartPos.y - 0.6 * cubeSize, agentStartPos.z) * Matrix4x4.scale(0.3, 0.3, 0.3),
                collision = false,
                faceTextures = placedTextures("player", agentModel)
            )
            val newAgent = PathfindingAgent(System.nanoTime(), agentStartPos, cameraPosition.copy(), mesh = agentMesh, lastNavMeshCheckTime = System.nanoTime())
            pathfindingAgents.add(newAgent)
            println("Spawned pathfinding agent at $agentStartPos")
        }
        if (code == KeyCode.R) {
            val cubeMeshGray = createCubeMesh(cubeSize, Color.GRAY)
            val playerGridPos = worldToGridCoords(cameraPosition)
            val gridX = playerGridPos.x.toInt()
            val gridY = playerGridPos.y.toInt()
            val gridZ = playerGridPos.z.toInt()
            val offset = gridDimension / 2.0
            val initialPos = Vector3d(
                (gridX - offset) * cubeSize,
                (gridY - offset) * cubeSize,
                (gridZ - offset) * cubeSize
            )
            val translationMatrix = Matrix4x4.translation(initialPos.x, initialPos.y, initialPos.z)
            val newMesh = PlacedMesh(cubeMeshGray, transformMatrix = translationMatrix, texture = loadImage("textures/black_bricks.png"), collisionPos = initialPos, collision = true)
            staticMeshes.add(newMesh)

            // 1. NAJPIERW zaktualizuj fizykę, aby NavMesh "wiedział" o nowym bloku.
            rebuildStaticPhysics()

            // 2. DOPIERO TERAZ zaktualizuj NavMesh, który użyje nowej fizyki.
            val blockAABB = AABB.fromCube(newMesh.getTransformedVertices())
            val expandedAABB = AABB(
                blockAABB.min - Vector3d(cubeSize, cubeSize, cubeSize),
                blockAABB.max + Vector3d(cubeSize, cubeSize, cubeSize)
            )
            invalidateNavMeshInAABB(expandedAABB)
            extendNavMesh(initialPos, cubeSize * 1.5) // Użyj nieco większego promienia
        }
    }

    private fun invalidateNavMeshInAABB(aabb: AABB) {
        navMesh?.let {
            it.removeNodesInAABB(aabb)
        }
        val navMeshCellSize = cubeSize / 2.0
        val minX = floor(aabb.min.x / navMeshCellSize).toInt()
        val maxX = ceil(aabb.max.x / navMeshCellSize).toInt()
        val minZ = floor(aabb.min.z / navMeshCellSize).toInt()
        val maxZ = ceil(aabb.max.z / navMeshCellSize).toInt()

        for (gx in minX..maxX) {
            for (gz in minZ..maxZ) {
                generatedNavMeshRegions.remove(Triple(gx, 0, gz))
            }
        }
    }

    private fun closestPointOnTriangle(p: Vector3d, a: Vector3d, b: Vector3d, c: Vector3d): Vector3d {
        val ab = b - a
        val ac = c - a
        val bc = c - b

        // Sprawdzenie, czy p jest w regionie wierzchołka A
        val ap = p - a
        val d1 = ab.dot(ap)
        val d2 = ac.dot(ap)
        if (d1 <= 0.0 && d2 <= 0.0) return a

        // Sprawdzenie, czy p jest w regionie wierzchołka B
        val bp = p - b
        val d3 = ab.dot(bp)
        val d4 = ac.dot(bp)
        if (d3 >= 0.0 && d4 <= d3) return b

        // Sprawdzenie, czy p jest na krawędzi AB
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) {
            val v = d1 / (d1 - d3)
            return a + ab * v
        }

        // Sprawdzenie, czy p jest w regionie wierzchołka C
        val cp = p - c
        val d5 = ab.dot(cp)
        val d6 = ac.dot(cp)
        if (d6 >= 0.0 && d5 <= d6) return c

        // Sprawdzenie, czy p jest na krawędzi AC
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) {
            val w = d2 / (d2 - d6)
            return a + ac * w
        }

        // Sprawdzenie, czy p jest na krawędzi BC
        val va = d3 * d6 - d5 * d4
        if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) {
            val w = (d4 - d3) / ((d4 - d3) + (d5 - d6)) // To jest dla krawędzi BC
            return b + bc * w
        }

        // p jest wewnątrz trójkąta, oblicz rzut
        val denom = 1.0 / (va + vb + vc)
        val v = vb * denom
        val w = vc * denom
        return a + ab * v + ac * w
    }

    private fun closestPointOnSegment(p: Vector3d, a: Vector3d, b: Vector3d): Vector3d {
        val ap = p - a
        val ab = b - a
        val ab2 = ab.dot(ab)
        if (ab2 == 0.0) return a
        val t = (ap.dot(ab) / ab2).coerceIn(0.0, 1.0)
        return a + ab * t
    }

    /**
     * Zaawansowany resolver kolizji gracza oparty o sferyczny hitbox (model „sphere”).
     *
     * - obsługuje rampy (pochylone ściany) – wypchnięcie odbywa się wzdłuż lokalnej normalnej,
     * - uniemożliwia przechodzenie przez grube bryły i cienkie ściany,
     * - zwraca informację czy kontakt był „pod stopami” (grunt/rampa), aby poprawnie obsłużyć skakanie.
     */
    private fun resolveCollisions(oldPos: Vector3d, desiredPos: Vector3d): Pair<Vector3d, Boolean> {
        if (debugNoclip) return Pair(desiredPos, false)

        var resolvedPos = desiredPos.copy()
        var isGroundContact = false

        val playerRadius = playerVertexRadius
        val epsilon = 1e-4 * cubeSize

        // Wysokość kapsuły - od stóp do głowy. playerHitboxOffset to teraz środek kapsuły.
        val capsuleHalfHeight = playerHitboxOffset.y.absoluteValue

        // Podział ruchu na mniejsze kroki – zabezpiecza przed „tunelowaniem” przez cienkie ściany.
        // Zamiast dzielić ruch, będziemy iteracyjnie rozwiązywać kolizje dla pełnego kroku.
        // To zapobiega drżeniu, ponieważ nie pozwalamy na ruch "w głąb" ściany.
        val maxIterations = 8
        for (iteration in 0 until maxIterations) {
            var maxPenetration = 0.0
            var correctionVector = Vector3d(0.0, 0.0, 0.0)
            var groundCandidate = false

            // Logika przepychania graczy ---
            var pushVector = Vector3d(0.0, 0.0, 0.0)
            var pushedMesh: PlacedMesh? = null
            var isDynamicCollision = false

            // AABB dla całej kapsuły w jej docelowej pozycji
            run {
                val capsuleCenter = resolvedPos + playerHitboxOffset
                val capsuleTop = capsuleCenter + Vector3d(0.0, capsuleHalfHeight, 0.0)
                val capsuleBottom = capsuleCenter - Vector3d(0.0, capsuleHalfHeight, 0.0)

                // AABB dla całej kapsuły
                val queryAabb = AABB(
                    Vector3d(capsuleBottom.x - playerRadius, capsuleBottom.y - playerRadius, capsuleBottom.z - playerRadius),
                    Vector3d(capsuleTop.x + playerRadius, capsuleTop.y + playerRadius, capsuleTop.z + playerRadius)
                )

                val candidateMeshes = staticCollisionGrid.query(queryAabb) + dynamicCollisionGrid.query(queryAabb)

                for (mesh in candidateMeshes) {
                    val meshAabb = meshAABBs[mesh] ?: continue
                    if (!meshAabb.intersects(queryAabb)) continue

                    // Definicja segmentu osiowego kapsuły (ponownie, wewnątrz pętli po meshu, na wypadek zmian)
                    val capsuleCenter = resolvedPos + playerHitboxOffset
                    val capsuleTop = capsuleCenter + Vector3d(0.0, capsuleHalfHeight, 0.0)
                    val capsuleBottom = capsuleCenter - Vector3d(0.0, capsuleHalfHeight, 0.0)

                    val worldBlushes by lazy {
                        mesh.mesh.blushes.map { blush ->
                            val transformedCorners = blush.getCorners().map { corner -> mesh.transformMatrix.transform(corner) }
                            AABB.fromCube(transformedCorners)
                        }
                    }

                    val worldVertices = mesh.getTransformedVertices()
                    for (faceIndices in mesh.mesh.faces) {
                        if (faceIndices.size < 3) continue

                        // Triangulacja „triangle fan” – działa dla grubych brył i cienkich ścian 3/4-wierzchołkowych
                        for (i in 1 until faceIndices.size - 1) {
                            val v0 = worldVertices[faceIndices[0]]
                            val v1 = worldVertices[faceIndices[i]]
                            val v2 = worldVertices[faceIndices[i + 1]]

                            val closestPointOnCapsuleAxis = closestPointOnSegment(closestPointOnTriangle(capsuleCenter, v0, v1, v2), capsuleBottom, capsuleTop)
                            val closestPointOnTriangle = closestPointOnTriangle(closestPointOnCapsuleAxis, v0, v1, v2)

                            // Promień do przepychania graczy ---
                            val isDynamicObject = dynamicMeshes.containsValue(mesh)
                            val effectiveRadius = if (isDynamicObject) playerRadius * 1.5 else playerRadius

                            val penetrationVector = closestPointOnCapsuleAxis - closestPointOnTriangle
                            val penetrationDepth = effectiveRadius - penetrationVector.length()

                            if (penetrationDepth > 0) {
                                // Sprawdź, czy punkt kolizji na trójkącie jest wewnątrz "blush"
                                if (worldBlushes.isNotEmpty() && worldBlushes.any { it.contains(closestPointOnTriangle) }) {
                                    continue // Ignoruj tę kolizję, ponieważ jest wewnątrz "blush"
                                }

                                val correctionNormal = penetrationVector.normalize()

                                // Jeśli to kolizja z innym graczem, tylko go odepchnij, nie koryguj własnej pozycji
                                if (isDynamicObject) {
                                    isDynamicCollision = true
                                    val pushPenetration = playerRadius - (closestPointOnCapsuleAxis - closestPointOnTriangle).length()
                                    if (pushPenetration > 0) {
                                        pushVector = correctionNormal * (pushPenetration + epsilon)
                                        pushedMesh = mesh
                                    }
                                    continue // Przejdź do następnego trójkąta, nie rozwiązuj tej kolizji dla siebie
                                }

                                // Prawdziwy „grunt” tylko wtedy, gdy:
                                // 1) normalna jest wystarczająco „pionowa” (max kąt rampy),
                                // 2) punkt kontaktu jest pod środkiem sfery (nie z boku ściany).
                                val isSlopeOk = correctionNormal.y >= minGroundNormalY
                                val isBelowFeet = closestPointOnTriangle.y <= capsuleBottom.y + playerRadius * (1.0 - groundContactVerticalOffset)
                                if (isSlopeOk && isBelowFeet) {
                                    groundCandidate = true
                                }

                                // Zamiast natychmiast przesuwać, znajdujemy największą penetrację w tej iteracji
                                if (penetrationDepth > maxPenetration) {
                                    maxPenetration = penetrationDepth
                                    correctionVector = correctionNormal * (penetrationDepth + epsilon)
                                }
                            }
                        }
                    }
                }
            }

            // Wysłanie siły pchania do innego gracza
            if (isDynamicCollision && pushedMesh != null) {
                val pushedPlayerId = dynamicMeshes.entries.find { it.value == pushedMesh }?.key
                if (pushedPlayerId != null) {
                    otherPlayers[pushedPlayerId]?.let { playerState ->
                        val horizontalPushVector = pushVector.copy(y = 0.0)
                        if (horizontalPushVector.length() > 0) {
                            val pushStrength = 0.03
                            val finalPush = horizontalPushVector * -pushStrength
                            val pushMessage = "PUSH,$pushedPlayerId,${finalPush.x},0.0,${finalPush.z}"
                            val data = pushMessage.toByteArray()
                            clientSocket.send(DatagramPacket(data, data.size, serverAddress, serverPort))
                        }
                    }
                }
                isDynamicCollision = false
            }

            if (maxPenetration > 0) {
                // Zastosuj korektę do pozycji
                resolvedPos += correctionVector

                // Jeśli ta korekta była zderzeniem z podłożem, oznaczamy to
                if (groundCandidate) {
                    isGroundContact = true
                }
            } else {
                // Jeśli nie było żadnej penetracji, pozycja jest prawidłowa i możemy zakończyć pętlę
                break
            }
        }

        return Pair(resolvedPos, isGroundContact)
    }

    private fun updateCameraPosition(deltaTime: Double) {
        var lookDirection = Vector3d(
            cos(cameraPitch) * sin(cameraYaw),
            0.0,
            cos(cameraPitch) * cos(cameraYaw)
        ).normalize()

        if (debugFly) {
            lookDirection = Vector3d(
                cos(cameraPitch) * sin(cameraYaw),
                sin(cameraPitch),
                cos(cameraPitch) * cos(cameraYaw)
            ).normalize()
        }

        val upVector = Vector3d(0.0, 1.0, 0.0)
        val rightVector = lookDirection.cross(upVector).normalize()
        val forwardVector = lookDirection

        var newCameraPosition = cameraPosition.copy()
        val scaleRatio = playerHitboxScale / baseHitboxScale
        var currentMovementSpeed = 2.8 * cubeSize * scaleRatio
        val playerSprintSpeed = 4.4 * cubeSize * scaleRatio
        if (pressedKeys.contains(KeyCode.SHIFT)) currentMovementSpeed = playerSprintSpeed

        if (pressedKeys.contains(KeyCode.W)) newCameraPosition += forwardVector * currentMovementSpeed * deltaTime
        if (pressedKeys.contains(KeyCode.S)) newCameraPosition -= forwardVector * currentMovementSpeed * deltaTime
        if (pressedKeys.contains(KeyCode.D)) newCameraPosition += rightVector * currentMovementSpeed * deltaTime
        if (pressedKeys.contains(KeyCode.A)) newCameraPosition -= rightVector * currentMovementSpeed * deltaTime
        if (pressedKeys.contains(KeyCode.SPACE) && debugFly) newCameraPosition += Vector3d(0.0, currentMovementSpeed, 0.0) * deltaTime

        // Zastosuj pchnięcie ---
        if (externalPushVector.length() > 0.0) {
            newCameraPosition += externalPushVector
            // Stopniowo wygaszaj pchnięcie, aby nie trwało wiecznie
            externalPushVector *= 0.9 // Współczynnik tłumienia
            if (externalPushVector.length() < 0.01) externalPushVector = Vector3d(0.0, 0.0, 0.0)
        }

        // Logika skoku
        if ((pressedKeys.contains(KeyCode.SPACE) && !debugFly) && (debugNoclip || isGrounded)) {
            val jumpHeight = 0.75 * cubeSize
            verticalVelocity = sqrt(2 * gravityAcceleration * jumpHeight)
            isGrounded = false
            groundedStateHistory.clear()
        }
        if (pressedKeys.contains(KeyCode.CONTROL) && debugFly) newCameraPosition -= Vector3d(0.0, currentMovementSpeed, 0.0) * deltaTime

        // Wykrywanie strefy antygrawitacyjnej (powierzchnie pochylone)
        var isInGravityZone = false
        if (!debugFly) {
            val playerFeetAABB = AABB(
                min = newCameraPosition + playerHitboxOffset - Vector3d(playerVertexRadius, 0.0, playerVertexRadius),
                max = newCameraPosition + playerHitboxOffset + Vector3d(playerVertexRadius, playerVertexRadius, playerVertexRadius)
            )
            for (placedMesh in staticMeshes) {
                if (placedMesh.mesh.gravityZoneFaces.isNotEmpty()) {
                    val worldVertices = placedMesh.getTransformedVertices()
                    for (faceIndex in placedMesh.mesh.gravityZoneFaces) {
                        val faceVertexIndices = placedMesh.mesh.faces[faceIndex]
                        val faceVertices = faceVertexIndices.map { worldVertices[it] }
                        val faceAABB = AABB.fromCube(faceVertices)
                        if (faceAABB.intersects(playerFeetAABB)) {
                            isInGravityZone = true
                            break
                        }
                    }
                }
                if (isInGravityZone) break
            }
        }

        // Logika opadania oparta o lokalną prędkość pionową – kolizje z geometrią są obsługiwane niżej.
        if (!debugFly && (!isInGravityZone || verticalVelocity > 0)) {
            verticalVelocity -= gravityAcceleration * deltaTime
            newCameraPosition.y += verticalVelocity * deltaTime
        }

        val isMovingHorizontally = pressedKeys.contains(KeyCode.W) || pressedKeys.contains(KeyCode.A) || pressedKeys.contains(KeyCode.S) || pressedKeys.contains(KeyCode.D)

        // Zaawansowany system kolizji 3D z rampami i ścianami
        val (resolvedPos, groundHit) = resolveCollisions(cameraPosition, newCameraPosition)

        cameraPosition = resolvedPos

        if (!debugFly && !isInGravityZone) {
            // Dodatkowe ograniczenie nieskończonej płaszczyzny „floorLevel”
            if (cameraPosition.y < floorLevel) {
                cameraPosition = cameraPosition.copy(y = floorLevel)
                verticalVelocity = 0.0
                isGrounded = true
            }

            if (groundHit && verticalVelocity <= 0.0) {
                isGrounded = true
                verticalVelocity = 0.0 // Resetuj prędkość tylko przy kontakcie z ziemią
            } else {
                isGrounded = false // Jeśli nie ma kontaktu z ziemią (lub jesteśmy w trakcie wznoszenia), jesteśmy w powietrzu
            }
        } else if (isInGravityZone) {
            // W strefie grawitacyjnej gracz jest zawsze "uziemiony", ale grawitacja nie działa
            isGrounded = true
            verticalVelocity = 0.0
        } else { // debugFly == true
            // W trybie latania ignorujemy grawitację i kolizje pod kątem „uziemienia”
            isGrounded = false
            verticalVelocity = 0.0
        }

        if (isMouseCaptured) {
            val rotationSpeed = 1.0
            cameraYaw -= accumulatedMouseDeltaX * mouseSensitivity * rotationSpeed * deltaTime * 60
            cameraPitch -= accumulatedMouseDeltaY * mouseSensitivity * rotationSpeed * deltaTime * 60
            cameraPitch = cameraPitch.coerceIn(-1.5, 1.5)

            accumulatedMouseDeltaX = 0.0
            accumulatedMouseDeltaY = 0.0
        }

        val currentRotationSpeed = 4.0
        if (pressedKeys.contains(KeyCode.LEFT)) cameraYaw += currentRotationSpeed * deltaTime
        if (pressedKeys.contains(KeyCode.RIGHT)) cameraYaw -= currentRotationSpeed * deltaTime
        if (pressedKeys.contains(KeyCode.DOWN) && (cameraPitch > -1.3)) cameraPitch -= currentRotationSpeed * deltaTime
        if (pressedKeys.contains(KeyCode.UP) && (cameraPitch < 1.3)) cameraPitch += currentRotationSpeed * deltaTime

        if (pressedKeys.contains(KeyCode.G)) {
            val gridPos = worldToGridCoords(cameraPosition)
            println(
                "X:${gridPos.x.toInt()}" +
                        " Y:${gridPos.y.toInt()}" +
                        " Z:${gridPos.z.toInt()}" +
                        " YAW:${((cameraYaw*10).toInt()/10.0)}" +
                        " PITCH:${((cameraPitch*10).toInt()/10.0)}" +
                        " SPEED:$currentMovementSpeed" +
                        " FOV: $dynamicFov" +
                        " IS_GROUNDED: $isGrounded" +
                        " VERTICAL_VELOCITY: $verticalVelocity")
        }

        if (pressedKeys.contains(KeyCode.O)) {
            val lightRadius = 6.0 * cubeSize
            lightSources.add(LightSource(Vector3d(cameraPosition.x, cameraPosition.y, cameraPosition.z), lightRadius, Color.rgb(160, 160, 160), intensity = 1.0, type = LightType.RAYTRACED))
            pressedKeys.remove(KeyCode.O)
        }
        if (pressedKeys.contains(KeyCode.P)) {
            val lightRadius = 6.0 * cubeSize
            lightSources.add(LightSource(Vector3d(cameraPosition.x, cameraPosition.y, cameraPosition.z), lightRadius, Color.rgb(0, 255, 0), type = LightType.VERTEX))
            pressedKeys.remove(KeyCode.P)
        }
        if (pressedKeys.contains(KeyCode.COMMA)) { // ','
            val lightRadius = 6.0 * cubeSize
            val initialPos = cameraPosition.copy()
            val newLight = LightSource(
                position = initialPos.copy(),
                radius = lightRadius,
                color = Color.YELLOW,
                type = LightType.VERTEX
            )
            lightSources.add(newLight)
            orbitingLights.add(OrbitingLight(newLight, initialPos))
            pressedKeys.remove(KeyCode.COMMA)
        }
        if (pressedKeys.contains(KeyCode.PERIOD)) { // '.'
            val lightRadius = 6.0 * cubeSize
            val initialPos = cameraPosition.copy()
            val newLight = LightSource(
                position = initialPos.copy(), radius = lightRadius, color = Color.rgb(0, 0, 255), type = LightType.RAYTRACED
            )
            lightSources.add(newLight)
            orbitingLights.add(OrbitingLight(newLight, initialPos))
            pressedKeys.remove(KeyCode.PERIOD)
        }
        if (pressedKeys.contains(KeyCode.X)) {
            lightingFuture?.cancel(true)
            synchronized(lightSources) { lightSources.clear() }
            synchronized(orbitingLights) { orbitingLights.clear() }
            lightingUpdateJobQueue.clear()
            faceLightGrids.clear()
            giLightGrids.clear()
            largeFaceSubLightmaps.clear()
            giLightPoints.clear()
        }

        if (cameraYaw > 2 * PI) cameraYaw -= 2 * PI
        if (cameraYaw < -2 * PI) cameraYaw += 2 * PI

        if (!debugFly) {
            cameraPosition.y = max(cameraPosition.y, floorLevel)
        }


        // Przygotuj wiadomość o pozycji w nowym formacie
        val messageString = "POSITION,${cameraPosition.x},${cameraPosition.y},${cameraPosition.z},${cameraYaw - PI}"

        // Wysyłaj pakiet z pozycją tylko wtedy, gdy dane faktycznie się zmieniły
        if (messageString != lastSentPacketData) { // Prosta optymalizacja, aby nie spamować serwera identycznymi danymi
            lastSentPacketData = messageString
            val message = messageString.toByteArray()
            val packet = DatagramPacket(message, message.size, serverAddress, serverPort)
            try {
                clientSocket.send(packet)
            } catch (e: Exception) {
            }
        }
    }

    private fun sendKeepAlive() {
        val message = "P".toByteArray() // "P" - Ping server
        val packet = DatagramPacket(message, message.size, serverAddress, serverPort)
        try {
            clientSocket.send(packet)
        } catch (e: Exception) {
        }
    }

    private fun drawOverlay() {
        val gc = overlayCanvas.graphicsContext2D
        gc.clearRect(0.0, 0.0, overlayCanvas.width, overlayCanvas.height)

        val mapScale = min(overlayCanvas.width, overlayCanvas.height) / 100.0
        val cursorSize = mapScale/5 * 2.5
        val mapOffsetX = 10.0
        val mapOffsetY = 10.0

        gc.stroke = Color.WHITE // cursor
        gc.strokeLine(overlayCanvas.width/2-cursorSize, overlayCanvas.height/2, overlayCanvas.width/2+cursorSize, overlayCanvas.height/2)
        gc.strokeLine(overlayCanvas.width/2, overlayCanvas.height/2-cursorSize, overlayCanvas.width/2, overlayCanvas.height/2+cursorSize)

        gc.font = fpsFont
        gc.fill = Color.WHITE
        val fpsText = "$fps"
        gc.fillText(fpsText, overlayCanvas.width - gc.font.size*2, gc.font.size + 4)

        if (debugShowHitboxes || debugShowNavMesh) {
            val lookDirection = Vector3d(cos(cameraPitch) * sin(cameraYaw), sin(cameraPitch), cos(cameraPitch) * cos(cameraYaw)).normalize()
            val upVector = Vector3d(0.0, 1.0, 0.0)
            val viewMatrix = Matrix4x4.lookAt(cameraPosition, cameraPosition + lookDirection, upVector)
            val projectionMatrix = Matrix4x4.perspective(dynamicFov, overlayCanvas.width / overlayCanvas.height, 0.1, renderDistanceBlocks * 2)
            val combinedMatrix = projectionMatrix * viewMatrix

            if (debugShowHitboxes) {
                val allMeshes = staticMeshes + dynamicMeshes.values
                for (mesh in allMeshes.filter { it.collision }) {
                    val aabb = meshAABBs[mesh] ?: continue
                    if (isAabbOutsideFrustum(aabb, combinedMatrix)) continue

                    // Draw collision mesh (green)
                    gc.stroke = Color.LIMEGREEN
                    gc.lineWidth = 1.5
                    val worldVertices = mesh.getTransformedVertices()
                    for (faceIndices in mesh.mesh.faces) {
                        if (faceIndices.size < 3) continue
                        drawFaceWireframe(gc, faceIndices.map { worldVertices[it] }, combinedMatrix)
                    }

                    // Draw blushes (red)
                    gc.stroke = Color.RED
                    gc.lineWidth = 1.0
                    for (blush in mesh.mesh.blushes) {
                        val blushCorners = blush.getCorners().map { mesh.transformMatrix.transform(it) }
                        val blushFaces = listOf(
                            listOf(blushCorners[0], blushCorners[1], blushCorners[3], blushCorners[2]), // Bottom
                            listOf(blushCorners[4], blushCorners[5], blushCorners[7], blushCorners[6]), // Top
                            listOf(blushCorners[0], blushCorners[1], blushCorners[5], blushCorners[4]), // Front
                            listOf(blushCorners[2], blushCorners[3], blushCorners[7], blushCorners[6]), // Back
                            listOf(blushCorners[1], blushCorners[3], blushCorners[7], blushCorners[5]), // Right
                            listOf(blushCorners[0], blushCorners[2], blushCorners[6], blushCorners[4])  // Left
                        )
                        blushFaces.forEach { drawFaceWireframe(gc, it, combinedMatrix) }
                    }
                }

                // Draw pathfinding agent paths
                synchronized(pathfindingAgents) {
                    gc.stroke = Color.CYAN
                    gc.lineWidth = 2.0
                    for (agent in pathfindingAgents) {
                        agent.path?.let { path ->
                            if (path.size > 1) {
                                for (i in 0 until path.size - 1) {
                                    val p1 = projectToScreen(path[i], combinedMatrix, overlayCanvas.width, overlayCanvas.height)
                                    val p2 = projectToScreen(path[i+1], combinedMatrix, overlayCanvas.width, overlayCanvas.height)
                                    if (p1 != null && p2 != null) {
                                        gc.strokeLine(p1.x, p1.y, p2.x, p2.y)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (debugShowNavMesh) {
            }
        }
        /*for (x in 0 until gridDimension) {
            for (z in 0 until gridDimension) {
                for (zLevel in 0 until 4) {
                    if (GRID_MAP[x][zLevel][z] == 1) {
                        gc.strokeOval(mapOffsetX + z * mapScale, mapOffsetY + x * mapScale + zLevel * (gridDimension + 2) * mapScale, mapScale, mapScale)
                    }
                }
            }
        }*/
    }

    private fun projectToScreen(worldPos: Vector3d, viewProjMatrix: Matrix4x4, screenWidth: Double, screenHeight: Double): Vector3d? {
        val projected = viewProjMatrix.transformHomogeneous(worldPos)
        if (projected.w <= 0) return null // Point is behind the camera

        val screenX = (projected.x / projected.w + 1) * screenWidth / 2.0
        val screenY = (1 - projected.y / projected.w) * screenHeight / 2.0

        // Check if the point is within the screen bounds (with a small margin)
        if (screenX < -10 || screenX > screenWidth + 10 || screenY < -10 || screenY > screenHeight + 10) {
            return null
        }
        return Vector3d(screenX, screenY, projected.z / projected.w)
    }

    private fun drawFaceWireframe(gc: javafx.scene.canvas.GraphicsContext, worldVertices: List<Vector3d>, combinedMatrix: Matrix4x4) {
        // Triangulate face to draw edges for each triangle
        for (i in 0 until worldVertices.size - 2) {
            val triWorldVerts = listOf(worldVertices[0], worldVertices[i + 1], worldVertices[i + 2])

            val screenPoints = triWorldVerts.mapNotNull { vertex ->
                val projected = combinedMatrix.transformHomogeneous(vertex)
                if (projected.w <= 0) return@mapNotNull null // Point behind camera
                Vector3d(
                    (projected.x / projected.w + 1) * overlayCanvas.width / 2.0,
                    (1 - projected.y / projected.w) * overlayCanvas.height / 2.0,
                    projected.z / projected.w
                )
            }
            if (screenPoints.size != 3) continue

            // Draw the 3 edges of the triangle
            gc.strokeLine(screenPoints[0].x, screenPoints[0].y, screenPoints[1].x, screenPoints[1].y)
            gc.strokeLine(screenPoints[1].x, screenPoints[1].y, screenPoints[2].x, screenPoints[2].y)
            gc.strokeLine(screenPoints[2].x, screenPoints[2].y, screenPoints[0].x, screenPoints[0].y)
        }
    }

    private fun updateGameLogic(deltaTime: Double) {
        val playerGridPos = worldToGridCoords(cameraPosition)
        val gridX = playerGridPos.x.toInt()
        val gridY = playerGridPos.y.toInt()
        val gridZ = playerGridPos.z.toInt()

        if (gridX in 0 until gridDimension &&
            gridY in 0 until 4 &&
            gridZ in 0 until gridDimension &&
            GRID_MAP[gridX][gridY][gridZ] == 3) {
            val offset = gridDimension / 2.0
            val pos = Vector3d(
                (gridX - offset) * cubeSize,
                (gridY - offset) * cubeSize,
                (gridZ - offset) * cubeSize
            )
            val meshToRemove = staticMeshes.find { it.collisionPos == pos }
            if (meshToRemove != null) {
                staticMeshes.remove(meshToRemove)
                meshAABBs.remove(meshToRemove)
                rebuildStaticPhysics()
                rebuildStaticMeshBatches()
                GRID_MAP[gridX][gridY][gridZ] = 0
            }
        }

        // Update orbiting lights
        val orbitSpeed = 0.5 * cubeSize
        val orbitRadius = 2.5 * cubeSize
        val angularVelocity = orbitSpeed / orbitRadius // v = ω * r  => ω = v / r

        synchronized(orbitingLights) {
            for (orbitingLight in orbitingLights) {
                orbitingLight.angle += angularVelocity * deltaTime
                val offsetX = sin(orbitingLight.angle) * orbitRadius
                val offsetZ = cos(orbitingLight.angle) * orbitRadius
                orbitingLight.light.position = orbitingLight.center.copy(x = orbitingLight.center.x + offsetX, z = orbitingLight.center.z + offsetZ)
            }
        }

        // Update pathfinding agents
        synchronized(pathfindingAgents) {
            val allAgentPositions = pathfindingAgents.map { it.position }
            val agentsToRemove = mutableListOf<PathfindingAgent>()
            for (agent in pathfindingAgents) {
                agent.targetPosition = cameraPosition.copy()
                val distSq = agent.position.distanceSquared(agent.targetPosition)
                val activationDist = 20.0 * cubeSize

                // Zatrzymaj agenta, jeśli jest wystarczająco blisko gracza
                val minApproachDistance = 1.5 * cubeSize
                if (distSq < minApproachDistance * minApproachDistance) {
                    agent.path = null // Wyczyść ścieżkę, aby się zatrzymał
                    continue // Przejdź do następnego agenta, nie wykonuj logiki ruchu
                }

                if (distSq > activationDist * activationDist) { // Nie aktywuj agenta, jeśli jest za daleko
                    continue
                }
                val now = System.nanoTime()

                // Co sekundę sprawdzaj i rozszerzaj NavMesh wokół agenta
                if (now - agent.lastNavMeshCheckTime > 1_000_000_000) {
                    val nodesNearby = navMesh?.countNodesInRadius(agent.position, 3.0 * cubeSize) ?: 0
                    if (agent.path == null || nodesNearby < 200) {
                        // Rozszerz siatkę wokół agenta
                        extendNavMesh(agent.position, 5.0 * cubeSize)
                    }
                    agent.lastNavMeshCheckTime = now
                }

                // Recalculate path periodically
                if (now - agent.lastPathUpdateTime > 1_000_000_000) { // Update path every second
                    agent.lastPathUpdateTime = now
                    val currentNavMesh = navMesh
                    if (currentNavMesh != null) {
                        val startNode = currentNavMesh.findClosestNode(agent.position)
                        val endNode = currentNavMesh.findClosestNode(agent.targetPosition)

                        if (startNode != null && endNode != null) {
                            // Znajdź ścieżkę, omijając innych agentów
                            val otherAgentPositions = allAgentPositions.filter { it != agent.position }
                            val agentAvoidanceRadius = cubeSize // Promień unikania innych agentów

                            agent.path = currentNavMesh.findPath(startNode, endNode, otherAgentPositions, agentAvoidanceRadius)
                            agent.currentPathIndex = 0
                        } else {
                            agent.path = null
                        }
                    }
                }

                agent.path?.let { path ->
                    if (agent.currentPathIndex >= path.size) {
                        // Reached destination or path ended, maybe remove agent or set to idle
                        // For now, let's just clear the path to trigger recalculation
                        agent.path = null
                        return@let
                    }

                    val nextWaypoint = path[agent.currentPathIndex]
                    val direction = (nextWaypoint - agent.position).normalize()
                    val distanceToWaypoint = (nextWaypoint - agent.position).length()

                    if (distanceToWaypoint < 10.0) { // Threshold to move to next waypoint
                        agent.currentPathIndex++
                    } else {
                        val proposedPos = agent.position + direction * agent.speed * deltaTime
                        val rayOrigin = Vector3d(proposedPos.x, agent.position.y + 1.9 * cubeSize, proposedPos.z)
                        val hit = staticBvh.intersectWithDetails(rayOrigin, Vector3d(0.0, -1.0, 0.0), 10.0 * cubeSize)

                        if (hit != null) {
                            val groundY = rayOrigin.y - hit.second
                            // NavMesh nodes są generowane +1.0 nad ziemią, więc agenta też trzymamy +1.0, lub po prostu na ziemi + epsilon.
                            // Tutaj trzymamy go na poziomie węzłów NavMesh (groundY + 1.0), aby pasował do ścieżki.
                            val targetY = groundY + 1.0
                            val heightDiff = targetY - agent.position.y

                            // Pozwól na wejście (rampa) max 0.6 bloku, i spadanie (grawitacja)
                            if (heightDiff <= 0.6 * cubeSize && heightDiff >= -3.0 * cubeSize) {
                                agent.position = Vector3d(proposedPos.x, targetY, proposedPos.z)
                            }
                        }

                        val agentYaw = atan2(direction.x, direction.z) + PI
                        agent.mesh.transformMatrix = Matrix4x4.translation(agent.position.x, agent.position.y, agent.position.z) * Matrix4x4.rotationY(agentYaw) * Matrix4x4.scale(0.3, 0.3, 0.3)
                        meshAABBs[agent.mesh] = AABB.fromCube(agent.mesh.getTransformedVertices())
                    }
                } ?: run {
                    // Fallback: No path found. Move towards target and generate mesh.
                    val targetDirection = (agent.targetPosition - agent.position).normalize()
                    var moveDirection = targetDirection

                    // Dodatkowa separacja w trybie "bez ścieżki", aby agenci nie wchodzili w siebie
                    var separation = Vector3d(0.0, 0.0, 0.0)
                    var neighbors = 0
                    val separationRadius = cubeSize * 1.2 // Nieco więcej niż 1 blok odstępu

                    for (otherPos in allAgentPositions) {
                        val distSq = agent.position.distanceSquared(otherPos)
                        if (distSq > 0.001 && distSq < separationRadius * separationRadius) {
                            separation += (agent.position - otherPos).normalize()
                            neighbors++
                        }
                    }
                    if (neighbors > 0) {
                        // Mieszamy chęć pójścia do gracza z silnym odpychaniem od innych (waga 2.0)
                        moveDirection = (moveDirection + separation.normalize() * 2.0).normalize()
                    }

                    val proposedPos = agent.position + moveDirection * agent.speed * deltaTime

                    // Fizyka dla ruchu "mimowolnego" - pozwala wejść na rampę, ale blokuje ściany
                    val rayOrigin = Vector3d(proposedPos.x, agent.position.y + 1.9 * cubeSize, proposedPos.z)
                    val hit = staticBvh.intersectWithDetails(rayOrigin, Vector3d(0.0, -1.0, 0.0), 10.0 * cubeSize)

                    if (hit != null) {
                        val groundY = rayOrigin.y - hit.second
                        val targetY = groundY + 1.0 // Utrzymujemy konwencję wysokości NavMesh
                        val heightDiff = targetY - agent.position.y

                        // Sprawdź czy to nie ściana (max 0.6 bloku w górę)
                        if (heightDiff <= 0.9 * cubeSize && heightDiff >= -3.0 * cubeSize) {
                            agent.position = Vector3d(proposedPos.x, targetY, proposedPos.z)
                        }
                    }
                    // Update mesh position and rotation
                    val agentYaw = atan2(targetDirection.x, targetDirection.z) + PI
                    agent.mesh.transformMatrix = Matrix4x4.translation(agent.position.x, agent.position.y, agent.position.z) * Matrix4x4.rotationY(agentYaw) * Matrix4x4.scale(0.3, 0.3, 0.3)
                    meshAABBs[agent.mesh] = AABB.fromCube(agent.mesh.getTransformedVertices())
                }
            }
            pathfindingAgents.removeAll(agentsToRemove)
        }


        // Update light gizmos
        lightGizmoMeshes.clear()
        val lightGizmoBaseMesh = createCubeMesh(0.1 * cubeSize, Color.WHITE)
        val currentLightSources = synchronized(lightSources) { lightSources.toList() }
        for (light in currentLightSources) {
            val gizmoTransform = Matrix4x4.translation(light.position.x, light.position.y, light.position.z)
            val coloredGizmoMesh = lightGizmoBaseMesh.copy(color = light.color)
            val placedGizmo = PlacedMesh(coloredGizmoMesh, gizmoTransform, collision = false)
            meshAABBs[placedGizmo] = AABB.fromCube(placedGizmo.getTransformedVertices())
            lightGizmoMeshes.add(placedGizmo)
        }

        // Add agent meshes to dynamic meshes for rendering
        pathfindingAgents.forEach { agent ->
            dynamicMeshes["agent_${agent.id}"] = agent.mesh
        }

        // Interpolate other players' positions
        val interpolationFactor = (deltaTime * 10.0).coerceIn(0.0, 1.0) // Adjust the multiplier for faster/slower interpolation
        otherPlayers.values.forEach { state ->
            state.currentPos = state.currentPos.lerp(state.targetPos, interpolationFactor)

            // Smooth yaw interpolation
            var yawDiff = state.targetYaw - state.currentYaw
            while (yawDiff < -PI) yawDiff += 2 * PI
            while (yawDiff > PI) yawDiff -= 2 * PI
            state.currentYaw += yawDiff * interpolationFactor
        }


        // Update dynamic player meshes
        val currentDynamicMeshIds = dynamicMeshes.keys.toSet()
        val activePlayerIds = otherPlayers.keys.toSet()
        val activeAgentIds = pathfindingAgents.map { "agent_${it.id}" }.toSet()

        // Clear old agent meshes before update
        currentDynamicMeshIds.filter { it.startsWith("agent_") && it !in activeAgentIds }.forEach { dynamicMeshes.remove(it) }
        val toRemove = currentDynamicMeshIds - activePlayerIds - activeAgentIds
        val toAdd = activePlayerIds - currentDynamicMeshIds
        val toUpdate = currentDynamicMeshIds.intersect(activePlayerIds)

        toRemove.forEach { id ->
            val meshToRemove = dynamicMeshes.remove(id)
            if (meshToRemove != null) {
                meshAABBs.remove(meshToRemove)
            }
        }

        toAdd.forEach { id ->
            val playerData = otherPlayers[id]
            if (playerData != null) {
                val (pos, yaw) = Pair(playerData.currentPos, playerData.currentYaw)
                val playerGizmoBaseMesh = createPlayerMesh(0.3 * cubeSize, Color.WHITE)
                val gizmoTransform = Matrix4x4.translation(pos.x, pos.y - 0.6 * cubeSize, pos.z) * Matrix4x4.rotationY(yaw)
                val placedGizmo = PlacedMesh(playerGizmoBaseMesh, gizmoTransform, collision = true, texture = texCeiling, faceTextures = placedTextures("player", modelRegistry["player"]!!))
                dynamicMeshes[id] = placedGizmo
                meshAABBs[placedGizmo] = AABB.fromCube(placedGizmo.getTransformedVertices())
            }
        }

        toUpdate.forEach { id ->
            val mesh = dynamicMeshes[id]
            val playerData = otherPlayers[id]
            if (mesh != null && playerData != null) {
                val (pos, yaw) = Pair(playerData.currentPos, playerData.currentYaw)
                mesh.transformMatrix =
                    Matrix4x4.translation(pos.x, pos.y - 0.6 * cubeSize, pos.z) * Matrix4x4.rotationY(yaw)
                meshAABBs[mesh] = AABB.fromCube(mesh.getTransformedVertices())
            }
        }
        rebuildDynamicPhysics()
    }

    private fun rebuildPhysicsStructures() {
        rebuildStaticPhysics()
        rebuildDynamicPhysics()
    }

    private fun rebuildStaticPhysics() {
        staticCollisionGrid.clear()
        staticMeshes.filter { it.collision }.forEach { mesh ->
            meshAABBs[mesh]?.let { aabb -> staticCollisionGrid.add(mesh, aabb) }
                ?: run { // Jeśli AABB nie istnieje, stwórz je
                    val newAabb = AABB.fromCube(mesh.getTransformedVertices())
                    meshAABBs[mesh] = newAabb
                    staticCollisionGrid.add(mesh, newAabb)
                }
        }
        staticBvh.build(staticMeshes)
    }

    private fun rebuildDynamicPhysics() {
        dynamicCollisionGrid.clear()
        dynamicMeshes.values.filter { it.collision }.forEach { mesh ->
            meshAABBs[mesh]?.let { aabb -> dynamicCollisionGrid.add(mesh, aabb) }
        }
        dynamicBvh.build(dynamicMeshes.values.toList())
    }

    private fun rebuildStaticMeshBatches() {
        val newBatches = mutableMapOf<Image?, MutableList<PlacedMesh>>()

        staticMeshes.filter { mesh ->
            !mesh.collision && mesh.texture != null && mesh.texture != texSkybox && !isTextureFullyTransparent(mesh.texture) && !mesh.alwaysRender
        }.forEach { mesh ->
            newBatches.computeIfAbsent(mesh.texture) { mutableListOf() }.add(mesh)
        }

        staticMeshBatches = newBatches.map { (texture, groupedMeshes) ->
            val combinedVertices = mutableListOf<Vector3d>()
            val combinedFaces = mutableListOf<List<Int>>()
            val combinedUVs = mutableListOf<List<Vector3d>>()
            val faceIndexToOriginal = mutableMapOf<Int, Pair<PlacedMesh, Int>>()
            var vertexOffset = 0

            val batchAABBs = mutableListOf<AABB>()

            for (placedMesh in groupedMeshes) {
                val worldVertices = placedMesh.mesh.vertices.map { placedMesh.transformMatrix.transform(it) }
                combinedVertices.addAll(worldVertices)
                meshAABBs[placedMesh]?.let { batchAABBs.add(it) }

                placedMesh.mesh.faces.forEachIndexed { faceIndex, face ->
                    combinedFaces.add(face.map { it + vertexOffset })
                    val newFaceIndex = combinedFaces.size - 1
                    faceIndexToOriginal[newFaceIndex] = Pair(placedMesh, faceIndex)
                }
                combinedUVs.addAll(placedMesh.mesh.faceUVs)

                vertexOffset += worldVertices.size
            }

            val batchedMesh = Mesh(
                vertices = combinedVertices,
                faces = combinedFaces,
                faceUVs = combinedUVs,
                color = Color.WHITE
            )
            val combinedAABB = AABB.fromAABBs(batchAABBs)
            StaticMeshBatch(batchedMesh, texture, combinedAABB, faceIndexToOriginal)
        }
        println("Rebuilt static batches. Found ${staticMeshBatches.size} batches.")
    }

    private fun updateDynamicLights() {
        synchronized(lightSources) {
            for (light in lightSources) {
                val isMoving = orbitingLights.any { it.light === light }

                var currentStateHash = 1
                if (!isMoving) {
                    val radiusVec = Vector3d(light.radius, light.radius, light.radius)
                    val lightAABB = AABB(light.position - radiusVec, light.position + radiusVec)
                    val meshesInRadius = staticCollisionGrid.query(lightAABB) + dynamicCollisionGrid.query(lightAABB)
                    meshesInRadius.sortedBy { System.identityHashCode(it) }.forEach { mesh ->
                        currentStateHash = 31 * currentStateHash + System.identityHashCode(mesh)
                        currentStateHash = 31 * currentStateHash + mesh.transformMatrix.hashCode()
                    }
                }

                if (isMoving || currentStateHash != light.lastKnownMeshStateHash) {
                    light.lastKnownMeshStateHash = currentStateHash
                    if (!lightingUpdateJobQueue.contains(light)) {
                        lightingUpdateJobQueue.add(light)
                    }
                }
            }
        }

        if ((lightingFuture == null || lightingFuture!!.isDone) && lightingUpdateJobQueue.isNotEmpty()) {
            lightingUpdateJobQueue.poll()?.let { nextLightToProcess ->
                val isMoving = orbitingLights.any { it.light === nextLightToProcess }
                val resolution = if (isMoving || nextLightToProcess.type == LightType.VERTEX) LOW_QualityRes else HIGH_QualityRes
                lightingFuture = scheduleLightingUpdate(nextLightToProcess, resolution)
            }
        }
    }

    private fun isFaceLarge(worldVerts: List<Vector3d>): Boolean {
        if (worldVerts.isEmpty()) return false
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        worldVerts.forEach { v ->
            minX = min(minX, v.x); minY = min(minY, v.y); minZ = min(minZ, v.z)
            maxX = max(maxX, v.x); maxY = max(maxY, v.y); maxZ = max(maxZ, v.z)
        }
        return Vector3d(maxX - minX, maxY - minY, maxZ - minZ).length() > largeFaceThreshold
    }

    private fun scheduleLightingUpdate(lightToProcess: LightSource, resolution: Int): Future<*> {
        val lightSnapshot = LightSource(
            position = lightToProcess.position.copy(),
            radius = lightToProcess.radius,
            color = lightToProcess.color,
            intensity = lightToProcess.intensity,
            type = lightToProcess.type
        )

        val facesToUpdate = ConcurrentHashMap.newKeySet<Pair<PlacedMesh, Int>>()

        val radiusVec = Vector3d(lightSnapshot.radius, lightSnapshot.radius, lightSnapshot.radius)
        val lightAABB = AABB(lightSnapshot.position - radiusVec, lightSnapshot.position + radiusVec)
        val meshesInRadius = (staticMeshes + dynamicMeshes.values).filter { mesh ->
            val meshAABB = meshAABBs[mesh] ?: return@filter false
            lightAABB.intersects(meshAABB)
        }

        for (mesh in meshesInRadius) {
            if (mesh.mesh.faces.isNotEmpty() && mesh.texture != texSkybox) {
                mesh.mesh.faces.forEachIndexed { faceIndex, faceIndices ->
                    val faceKey = Pair(mesh, faceIndex)
                    val worldVerts = faceIndices.map { mesh.transformMatrix.transform(mesh.mesh.vertices[it]) }
                    if (isFaceLarge(worldVerts)) {
                        generateSubLightmapsForLargeFace(lightSnapshot, mesh, faceIndex, resolution) //generowanie pod-lightmap
                        // Nie dodajemy dużej ściany do standardowej kolejki
                    } else {
                        // Standardowa obsługa
                        facesToUpdate.add(faceKey)
                    }
                }
            }
        }

        lightingUpdateQueue.clear()
        lightingUpdateQueue.addAll(facesToUpdate)


        return lightingExecutor.submit {
            processLightingQueue(resolution)
        }
    }

    private fun isPointInFace(point: Vector3d, faceWorldVerts: List<Vector3d>): Boolean {
        if (faceWorldVerts.size < 3) return false

        // Używamy rzutowania na płaszczyznę XZ, ponieważ sublightmapy są płaskie na osi Y
        val px = point.x
        val pz = point.z

        for (i in 0 until faceWorldVerts.size - 2) {
            val v0 = faceWorldVerts[0]
            val v1 = faceWorldVerts[i + 1]
            val v2 = faceWorldVerts[i + 2]

            val d = (v1.z - v0.z) * (v2.x - v0.x) - (v1.x - v0.x) * (v2.z - v0.z)
            if (d == 0.0) continue // Trójkąt zdegenerowany
            val bary_u = ((pz - v0.z) * (v2.x - v0.x) - (px - v0.x) * (v2.z - v0.z)) / d
            val bary_v = ((v1.z - v0.z) * (px - v0.x) - (v1.x - v0.x) * (pz - v0.z)) / d
            if (bary_u >= 0 && bary_v >= 0 && (bary_u + bary_v) <= 1) return true
        }
        return false
    }

    private fun generateSubLightmapsForLargeFace(light: LightSource, mesh: PlacedMesh, faceIndex: Int, resolution: Int) {
        val faceKey = Pair(mesh, faceIndex)
        val subLightmaps = largeFaceSubLightmaps.computeIfAbsent(faceKey) { Collections.synchronizedList(mutableListOf()) }

        val lightGridMinX = floor((light.position.x - light.radius) / cubeSize).toInt()
        val lightGridMaxX = ceil((light.position.x + light.radius) / cubeSize).toInt()
        val lightGridMinZ = floor((light.position.z - light.radius) / cubeSize).toInt()
        val lightGridMaxZ = ceil((light.position.z + light.radius) / cubeSize).toInt()

        val faceWorldVerts = mesh.mesh.faces[faceIndex].map { mesh.transformMatrix.transform(mesh.mesh.vertices[it]) }
        val faceAABB = AABB.fromCube(faceWorldVerts)
        val faceY = faceWorldVerts.firstOrNull()?.y ?: return

        for (gx in lightGridMinX..lightGridMaxX) {
            for (gz in lightGridMinZ..lightGridMaxZ) {
                val subLightmapCenter = Vector3d(gx * cubeSize, faceY, gz * cubeSize)
                val halfSize = cubeSize / 2.0
                val subLightmapAABB = AABB(
                    subLightmapCenter - Vector3d(halfSize, 0.1, halfSize),
                    subLightmapCenter + Vector3d(halfSize, 0.1, halfSize)
                )

                // Sprawdź, czy sub-lightmapa przecina się z AABB ściany
                if (!subLightmapAABB.intersects(faceAABB)) {
                    continue // Pomiń generowanie, jeśli nie ma przecięcia
                }

                // Dokładniejszy test: sprawdź, czy jakikolwiek wierzchołek ściany jest w AABB sublightmapy
                // LUB czy jakikolwiek wierzchołek sublightmapy jest wewnątrz wielokąta ściany.
                if (!faceWorldVerts.any { subLightmapAABB.contains(it) } && !subLightmapAABB.getCorners().any { isPointInFace(it, faceWorldVerts) }) {
                    continue
                }

                var subLightmap = subLightmaps.find { it.worldPosition == subLightmapCenter }
                if (subLightmap == null) {
                    val newGrid = Array(resolution) { Array(resolution) { Color.TRANSPARENT } }
                    val newTexture = WritableImage(resolution, resolution)

                    subLightmap = SubLightmap(newGrid, newTexture, subLightmapCenter, subLightmapAABB, needsUpdate = true, lastLitTime = System.nanoTime())
                    subLightmaps.add(subLightmap)
                }
                subLightmap.needsUpdate = true
            }
        }
    }

    private fun colorsAreSimilar(colors: List<Color>, threshold: Double = 0.1): Boolean {
        if (colors.size < 2) return true
        val first = colors.first()
        for (i in 1 until colors.size) {
            val other = colors[i]
            val dr = abs(first.red - other.red)
            val dg = abs(first.green - other.green)
            val db = abs(first.blue - other.blue)
            if (dr + dg + db > threshold) {
                return false
            }
        }
        return true
    }

    private fun processLightingQueue(resolution: Int) {
        val itemsToProcess = lightingUpdateQueue.toList()
        lightingUpdateQueue.clear()

        val allLightsWithStaticInfo = synchronized(lightSources) {
            val dynamicLightsSet = synchronized(orbitingLights) { orbitingLights.map { it.light }.toSet() }
            lightSources.map { lightToCopy ->
                val isStatic = !dynamicLightsSet.contains(lightToCopy)
                val snapshot = LightSource(lightToCopy.position.copy(), lightToCopy.radius, lightToCopy.color, lightToCopy.intensity, lightToCopy.type)
                Pair(snapshot, isStatic)
            }
        }
        val allLightsSnapshot = allLightsWithStaticInfo.map { it.first }

        val sortedItems = itemsToProcess.sortedBy { (mesh, faceIndex) ->
            val faceIndices = mesh.mesh.faces.getOrNull(faceIndex)
            if (faceIndices == null || faceIndices.size < 3) {
                Double.MAX_VALUE
            } else {
                val worldVerts = faceIndices.map { vi -> mesh.transformMatrix.transform(mesh.mesh.vertices[vi]) }
                val faceCenter = worldVerts.reduce { acc, vec -> acc + vec } / worldVerts.size.toDouble()
                (faceCenter - cameraPosition).length()
            }
        }

        for (faceKey in sortedItems) {
            if (Thread.currentThread().isInterrupted) {
                break
            }
            val (mesh, faceIndex) = faceKey

            val faceIndices = mesh.mesh.faces[faceIndex]
            if (faceIndices.size < 3) continue

            val worldVerts = faceIndices.map { vi -> mesh.transformMatrix.transform(mesh.mesh.vertices[vi]) }
            val v0 = worldVerts[0]
            val v1 = worldVerts[1]
            val v2 = worldVerts[2]
            val faceNormal = (v1 - v0).cross(v2 - v0).normalize()
            val faceCenter = worldVerts.reduce { acc, vec -> acc + vec } / worldVerts.size.toDouble()

            val faceTexture = mesh.faceTextures[faceIndex] ?: mesh.texture
            val hasPartialTransparency = faceTexture != null && isTextureTransparent(faceTexture) && !isTextureFullyTransparent(faceTexture)
            val subDivisions = if (hasPartialTransparency && faceTexture != null) {
                min(faceTexture.width.toInt(), faceTexture.height.toInt()).coerceIn(resolution, 64)
            } else {
                resolution
            }
            val finalLightGrid = Array(subDivisions) { Array(subDivisions) { Color.BLACK } }
            var isFaceLitAtAll = false

            for (light in allLightsSnapshot) {
                val toLightDirForNormal = (light.position - faceCenter).normalize()
                if (faceNormal.dot(toLightDirForNormal) <= 0) {
                    continue
                }

                if (light.type == LightType.RAYTRACED || light.type == LightType.RAYTRACED_GI) {
                    val cornerUVs = if (worldVerts.size == 4) listOf(Pair(0.0, 0.0), Pair(1.0, 0.0), Pair(1.0, 1.0), Pair(0.0, 1.0)) else listOf(Pair(0.0, 0.0), Pair(1.0, 0.0), Pair(0.0, 1.0))
                    val cornerPoints = cornerUVs.map { (u, v) ->
                        if (worldVerts.size == 4) {
                            val p1 = worldVerts[0].lerp(worldVerts[1], u)
                            val p2 = worldVerts[3].lerp(worldVerts[2], u)
                            p1.lerp(p2, v)
                        } else {
                            worldVerts[0] * (1.0 - u - v) + worldVerts[1] * u + worldVerts[2] * v
                        }
                    }
                    val cornerColors = cornerPoints.map { pointOnFace ->
                        val rayOrigin = light.position
                        val rayTarget = pointOnFace
                        val rayVector = rayTarget - rayOrigin
                        val dist = rayVector.length()
                        if (dist > light.radius || isOccluded(rayOrigin, rayTarget, mesh, faceIndex)) {
                            Color.BLACK
                        } else {
                            val attenuation = 1.0 - (dist / light.radius).coerceIn(0.0, 10.0)
                            val influence = light.intensity * attenuation
                            Color(
                                (light.color.red * influence).coerceIn(0.0, 1.0),
                                (light.color.green * influence).coerceIn(0.0, 1.0),
                                (light.color.blue * influence).coerceIn(0.0, 1.0),
                                1.0
                            )
                        }
                    }

                    if (worldVerts.size == 4 && colorsAreSimilar(cornerColors)) {
                        val c00 = cornerColors[0]; val c10 = cornerColors[1]; val c11 = cornerColors[2]; val c01 = cornerColors[3]
                        for (i_sub in 0 until subDivisions) {
                            for (j_sub in 0 until subDivisions) {
                                val u = i_sub.toDouble() / (subDivisions - 1)
                                val v = j_sub.toDouble() / (subDivisions - 1)
                                val top = c00.interpolate(c10, u)
                                val bottom = c01.interpolate(c11, u)
                                val finalColor = top.interpolate(bottom, v)

                                val existingColor = finalLightGrid[i_sub][j_sub]
                                val newR = (existingColor.red + finalColor.red).coerceIn(0.0, 1.0)
                                val newG = (existingColor.green + finalColor.green).coerceIn(0.0, 1.0)
                                val newB = (existingColor.blue + finalColor.blue).coerceIn(0.0, 1.0)
                                finalLightGrid[i_sub][j_sub] = Color(newR, newG, newB, 1.0)
                            }
                        }
                        isFaceLitAtAll = true
                    } else {
                        for (i_sub in 0 until subDivisions) {
                            for (j_sub in 0 until subDivisions) {
                                val u = (i_sub + 0.5) / subDivisions
                                val v = (j_sub + 0.5) / subDivisions

                                val pointOnFace = if (worldVerts.size == 4) {
                                    val p1 = worldVerts[0].lerp(worldVerts[1], u)
                                    val p2 = worldVerts[3].lerp(worldVerts[2], u)
                                    p1.lerp(p2, v)
                                } else {
                                    var u_bary = u; var v_bary = v
                                    if (u_bary + v_bary > 1.0) { u_bary = 1.0 - u_bary; v_bary = 1.0 - v_bary }
                                    worldVerts[0] * (1.0 - u_bary - v_bary) + worldVerts[1] * u_bary + worldVerts[2] * v_bary
                                }

                                val rayOrigin = light.position
                                val rayTarget = pointOnFace
                                val dist = (rayTarget - rayOrigin).length()

                                if (dist > light.radius) continue

                                if (!isOccluded(rayOrigin, rayTarget, mesh, faceIndex)) {
                                    val attenuation = 1.0 - (dist / light.radius).coerceIn(0.0, 10.0)
                                    val influence = (light.intensity * attenuation)//.coerceIn(0.0, 1.0)
                                    val existingColor = finalLightGrid[i_sub][j_sub]
                                    val lightColor = light.color
                                    val newR = (existingColor.red + lightColor.red * influence).coerceIn(0.0, 1.0)
                                    val newG = (existingColor.green + lightColor.green * influence).coerceIn(0.0, 1.0)
                                    val newB = (existingColor.blue + lightColor.blue * influence).coerceIn(0.0, 1.0)
                                    finalLightGrid[i_sub][j_sub] = Color(newR, newG, newB, 1.0)
                                    isFaceLitAtAll = true

                                    // --- Global Illumination (odbicia) ---
                                    if (light.type == LightType.RAYTRACED_GI) {
                                        for (i in 0 until GI_SAMPLES) {
                                            val incidentDir = (pointOnFace - rayOrigin).normalize()
                                            traceReflection(
                                                reflectionOrigin = pointOnFace,
                                                incidentDirection = incidentDir,
                                                surfaceNormal = faceNormal,
                                                incomingLight = Vector3d(lightColor.red * influence, lightColor.green * influence, lightColor.blue * influence),
                                                bouncesLeft = bouncesLeft,
                                                sourceMesh = mesh,
                                                sourceFaceIndex = faceIndex,
                                                remainingDistance = light.radius - dist
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else { // LightType.VERTEX
                    val vertexColors = worldVerts.map { vertex ->
                        val dist = (light.position - vertex).length()
                        if (dist > light.radius) {
                            Color.BLACK
                        } else {
                            val isBlocked = isLineOfSightBlockedFast(light.position, vertex, mesh)
                            if (isBlocked) {
                                Color.BLACK
                            } else {
                                val attenuation = 1.0 - (dist / light.radius).coerceIn(0.0, 10.0)
                                val influence = light.intensity * attenuation
                                val lightColor = light.color
                                Color(
                                    (lightColor.red * influence).coerceIn(0.0, 1.0),
                                    (lightColor.green * influence).coerceIn(0.0, 1.0),
                                    (lightColor.blue * influence).coerceIn(0.0, 1.0),
                                    1.0
                                )
                            }
                        }
                    }

                    for (i_sub in 0 until subDivisions) {
                        for (j_sub in 0 until subDivisions) {
                            val u = (i_sub + 0.5) / subDivisions
                            val v = (j_sub + 0.5) / subDivisions

                            val interpolatedColor = if (worldVerts.size == 4) {
                                val c1 = vertexColors[0].interpolate(vertexColors[1], u)
                                val c2 = vertexColors[3].interpolate(vertexColors[2], u)
                                c1.interpolate(c2, v)
                            } else {
                                var u_bary = u
                                var v_bary = v
                                if (u_bary + v_bary > 1.0) {
                                    u_bary = 1.0 - u_bary
                                    v_bary = 1.0 - v_bary
                                }
                                val w_bary = 1.0 - u_bary - v_bary

                                val r = vertexColors[0].red * w_bary + vertexColors[1].red * u_bary + vertexColors[2].red * v_bary
                                val g = vertexColors[0].green * w_bary + vertexColors[1].green * u_bary + vertexColors[2].green * v_bary
                                val b = vertexColors[0].blue * w_bary + vertexColors[1].blue * u_bary + vertexColors[2].blue * v_bary
                                Color(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0), 1.0)
                            }

                            val existingColor = finalLightGrid[i_sub][j_sub]
                            val newR = (existingColor.red + interpolatedColor.red).coerceIn(0.0, 1.0)
                            val newG = (existingColor.green + interpolatedColor.green).coerceIn(0.0, 1.0)
                            val newB = (existingColor.blue + interpolatedColor.blue).coerceIn(0.0, 1.0)
                            finalLightGrid[i_sub][j_sub] = Color(newR, newG, newB, 1.0)
                            isFaceLitAtAll = true
                        }
                    }
                }
            }

            if (isFaceLitAtAll) {
                faceLightGrids[faceKey] = finalLightGrid
            } else {
                faceLightGrids.remove(faceKey)
            }

            try {
                Thread.sleep(1)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        // Przetwarzanie pod-lightmap dla dużych ścian
        val largeFacesToUpdate = largeFaceSubLightmaps.filterValues { list -> list.any { it.needsUpdate } }
        for ((faceKey, subLightmaps) in largeFacesToUpdate) {
            val (mesh, faceIndex) = faceKey
            val faceWorldVerts = mesh.mesh.faces[faceIndex].map { mesh.transformMatrix.transform(mesh.mesh.vertices[it]) }
            val faceNormal = (faceWorldVerts[1] - faceWorldVerts[0]).cross(faceWorldVerts[2] - faceWorldVerts[0]).normalize()

            val litSubLightmaps = ConcurrentHashMap.newKeySet<SubLightmap>()

            for (subLightmap in subLightmaps.filter { it.needsUpdate }) {
                if (Thread.currentThread().isInterrupted) break

                val grid = subLightmap.grid
                grid.forEach { row -> row.fill(Color.TRANSPARENT) } // Reset
                var isLit = false
                subLightmap.isLitByStaticLight = false

                for ((light, isStatic) in allLightsWithStaticInfo) {
                    // Twoja optymalizacja: Sprawdź 4 narożniki sub-lightmapy
                    val corners = subLightmap.aabb.getCorners()
                    val areAllCornersInside = corners.all { isPointInFace(it, faceWorldVerts) }

                    // Jeśli wszystkie narożniki są wewnątrz, możemy pominąć drogie sprawdzanie per-pixel.
                    // Jeśli nie, musimy sprawdzać każdy piksel indywidualnie.
                    val needsPerPixelCheck = !areAllCornersInside

                    // Szybki test AABB
                    val lightAABB = AABB(light.position - Vector3d(light.radius, light.radius, light.radius), light.position + Vector3d(light.radius, light.radius, light.radius))
                    if (!lightAABB.intersects(subLightmap.aabb)) continue

                    for (y in 0 until resolution) {
                        for (x in 0 until resolution) {
                            val u = (x + 0.5) / resolution
                            val v = (y + 0.5) / resolution

                            val pointOnSubLightmap = Vector3d(
                                subLightmap.worldPosition.x + (u - 0.5) * cubeSize,
                                subLightmap.worldPosition.y,
                                subLightmap.worldPosition.z + (v - 0.5) * cubeSize
                            )

                            // Renderuj piksel tylko jeśli jest nad rzeczywistą geometrią ściany (tylko dla krawędziowych sub-lightmap)
                            if (needsPerPixelCheck && !isPointInFace(pointOnSubLightmap, faceWorldVerts)) continue

                            val toLightDir = (light.position - pointOnSubLightmap).normalize()
                            if (faceNormal.dot(toLightDir) <= 0) continue

                            val dist = (light.position - pointOnSubLightmap).length()
                            if (dist > light.radius) continue

                            if (!isOccluded(light.position, pointOnSubLightmap, mesh, faceIndex)) {
                                val attenuation = 1.0 - (dist / light.radius).coerceIn(0.0, 1.0)
                                val influence = light.intensity * attenuation
                                val existingColor = grid[x][y]
                                val lightColor = light.color

                                val srcR = lightColor.red
                                val srcG = lightColor.green
                                val srcB = lightColor.blue
                                val srcA = influence.coerceIn(0.0, 1.0)

                                val invSrcA = 1.0 - srcA
                                val finalR = srcR * srcA + existingColor.red * invSrcA
                                val finalG = srcG * srcA + existingColor.green * invSrcA
                                val finalB = srcB * srcA + existingColor.blue * invSrcA
                                val finalA = srcA + existingColor.opacity * invSrcA

                                grid[x][y] = Color(finalR.coerceIn(0.0, 1.0), finalG.coerceIn(0.0, 1.0), finalB.coerceIn(0.0, 1.0), finalA.coerceIn(0.0, 1.0))
                                isLit = true
                                if (isStatic) {
                                    subLightmap.isLitByStaticLight = true
                                }

                                if (light.type == LightType.RAYTRACED_GI) {
                                    for (i in 0 until GI_SAMPLES) {
                                        val incidentDir = (pointOnSubLightmap - light.position).normalize()
                                        traceReflection(
                                            reflectionOrigin = pointOnSubLightmap,
                                            incidentDirection = incidentDir,
                                            surfaceNormal = faceNormal,
                                            incomingLight = Vector3d(lightColor.red * influence, lightColor.green * influence, lightColor.blue * influence),
                                            bouncesLeft = bouncesLeft,
                                            sourceMesh = mesh,
                                            sourceFaceIndex = faceIndex,
                                            remainingDistance = light.radius - dist
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (isLit) {
                    litSubLightmaps.add(subLightmap)
                    subLightmap.lastLitTime = System.nanoTime()
                }
                subLightmap.needsUpdate = false

                // Wypal siatkę kolorów na teksturę WritableImage
                val pixelWriter = subLightmap.texture.pixelWriter
                val newPixels = IntArray(resolution * resolution)
                for (y in 0 until resolution) {
                    for (x in 0 until resolution) {
                        val color = grid[x][y]
                        val r = (color.red * 255).toInt().coerceIn(0, 255)
                        val g = (color.green * 255).toInt().coerceIn(0, 255)
                        val b = (color.blue * 255).toInt().coerceIn(0, 255)
                        val a = (color.opacity * 255).toInt().coerceIn(0, 255)
                        val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                        pixelWriter.setArgb(x, y, argb)
                        newPixels[x + y * resolution] = argb
                    }
                }
                textureCache[subLightmap.texture] = newPixels
                textureDimensions[subLightmap.texture] = resolution to resolution
            }
            if (Thread.currentThread().isInterrupted) break

            // Usuń sub-lightmapy, które nie są już oświetlane
            val removalThreshold = 5_000_000_000L // 1 sekunda w nanosekundach
            val currentTime = System.nanoTime()
            subLightmaps.removeIf { !it.needsUpdate && !it.isLitByStaticLight && (currentTime - it.lastLitTime) > removalThreshold }
        }


        applyGILightPoints(resolution)
    }

    private fun extendNavMesh(center: Vector3d, radius: Double) {
        val currentNavMesh = navMesh ?: return
        val navMeshCellSize = cubeSize / 2.0
        val step = navMeshCellSize.toInt()
        val checkRadius = playerVertexRadius * 0.5 // Mniejszy promień do sprawdzania, aby przejść przez węższe miejsca
        val agentHeight = playerHitboxScale * cubeSize * 2.0 // Przybliżona wysokość agenta
        val maxWalkableSlope = 0.7 // Cosinus kąta ~45 stopni, tak jak u gracza

        val minX = floor((center.x - radius) / navMeshCellSize).toInt()
        val maxX = ceil((center.x + radius) / navMeshCellSize).toInt()
        val minZ = floor((center.z - radius) / navMeshCellSize).toInt()
        val maxZ = ceil((center.z + radius) / navMeshCellSize).toInt()

        val navMeshNodes = mutableListOf<NavMeshNode>()
        val nodeGrid = mutableMapOf<Triple<Int, Int, Int>, NavMeshNode>()

        println("Extending NavMesh in area: X($minX..$maxX), Z($minZ..$maxZ)")

        for (gx in minX..maxX) {
            for (gz in minZ..maxZ) {
                val regionKey = Triple(gx, 0, gz)
                if (generatedNavMeshRegions.contains(regionKey)) continue

                val x = (gx + 0.5) * navMeshCellSize
                val z = (gz + 0.5) * navMeshCellSize

                // Rzutuj promień z góry w dół, aby znaleźć podłoże
                val rayOrigin = Vector3d(x, center.y + 1.9 * cubeSize, z)
                val rayDir = Vector3d(0.0, -1.0, 0.0)

                val hitResult = staticBvh.intersectWithDetails(rayOrigin, rayDir, 10 * cubeSize)

                if (hitResult != null) {
                    val (hitPrimitive, hitDist) = hitResult
                    val hitPoint = rayOrigin + rayDir * hitDist
                    val hitNormal = (hitPrimitive.v1 - hitPrimitive.v0).cross(hitPrimitive.v2 - hitPrimitive.v0).normalize()

                    // Sprawdź, czy powierzchnia jest wystarczająco płaska do chodzenia
                    if (hitNormal.y < maxWalkableSlope) continue

                    // Sprawdź, czy jest wystarczająco dużo miejsca nad głową
                    val headCheckOrigin = hitPoint + Vector3d(0.0, agentHeight, 0.0)
                    if (staticBvh.intersect(headCheckOrigin, Vector3d(0.0, -1.0, 0.0), agentHeight - 0.1, hitPrimitive.mesh, hitPrimitive.faceIndex)) {
                        continue // Coś jest tuż nad głową
                    }

                    // Sprawdź, czy agent się zmieści (uproszczone sprawdzenie)
                    val checkAABB = AABB(hitPoint - Vector3d(checkRadius, 0.1, checkRadius), hitPoint + Vector3d(checkRadius, agentHeight, checkRadius))
                    val isObstructed = staticCollisionGrid.query(checkAABB).any { potentialObstacle ->
                        if (potentialObstacle != hitPrimitive.mesh && meshAABBs[potentialObstacle]?.intersects(checkAABB) == true) {
                            // Wykryto kolizję. Sprawdź, czy nie jest ona zanegowana przez "blush".
                            val worldBlushes by lazy {
                                potentialObstacle.mesh.blushes.map { blush ->
                                    val transformedCorners = blush.getCorners().map { corner -> potentialObstacle.transformMatrix.transform(corner) }
                                    AABB.fromCube(transformedCorners)
                                }
                            }
                            // Jeśli obszar testowy agenta jest wewnątrz "blusha", to nie jest to przeszkoda.
                            // Używamy uproszczonego sprawdzenia - czy środek AABB agenta jest w blushu.
                            !worldBlushes.any { it.contains(checkAABB.min + (checkAABB.max - checkAABB.min) / 2.0) }
                        } else false
                    }
                    if (isObstructed) {
                        continue // Miejsce jest zablokowane przez geometrię bez blusha
                    }

                    val node = NavMeshNode(hitPoint + Vector3d(0.0, 1.0, 0.0)) // Lekko nad ziemią
                    navMeshNodes.add(node)
                    val gridKey = Triple(floor(hitPoint.x / navMeshCellSize).toInt(), floor(hitPoint.y / navMeshCellSize).toInt(), floor(hitPoint.z / navMeshCellSize).toInt())
                    nodeGrid[gridKey] = node
                }
            }
        }

        generatedNavMeshRegions.addAll(nodeGrid.keys.map { Triple(it.first, 0, it.third) })
        currentNavMesh.addAndConnectNodes(navMeshNodes, nodeGrid)

        // Post-processing: Validate diagonal connections
        // Remove connections that pass through walls or over air (gaps)
        val checkRadius2 = playerVertexRadius * 0.5
        for (node in navMeshNodes) {
            val neighbors = ArrayList(node.neighbors)
            for (neighbor in neighbors) {
                // Check if diagonal (distance check)
                val distSq = node.position.distanceSquared(neighbor.position)
                // cellSize is cubeSize / 2.0. Diagonal is sqrt(2) * cellSize.
                // Threshold: slightly larger than cellSize^2
                if (distSq > (navMeshCellSize * navMeshCellSize * 1.1)) {
                    // Only validate diagonal connections on the same level (flat terrain)
                    // Multi-level connections (ramps) are skipped to avoid breaking them
                    if (abs(node.position.y - neighbor.position.y) > 0.1 * cubeSize) continue

                    val midPos = (node.position + neighbor.position) * 0.5

                    // 1. Check for ground at midpoint
                    val rayOrigin = midPos + Vector3d(0.0, 1.0, 0.0)
                    val hit = staticBvh.intersectWithDetails(rayOrigin, Vector3d(0.0, -1.0, 0.0), 5.0 * cubeSize)

                    var isValid = true
                    if (hit == null) {
                        isValid = false
                    } else {
                        val groundY = rayOrigin.y - hit.second
                        // Ensure ground is at similar height (not a drop)
                        if (abs(groundY - node.position.y) > 0.5 * cubeSize) {
                            isValid = false
                        }
                    }

                    // 2. Check for obstruction at midpoint
                    if (isValid) {
                        val checkAABB = AABB(
                            midPos - Vector3d(checkRadius2, 0.1, checkRadius2),
                            midPos + Vector3d(checkRadius2, agentHeight, checkRadius2)
                        )
                        val isObstructed = staticCollisionGrid.query(checkAABB).any { potentialObstacle ->
                            if (potentialObstacle != hit!!.first.mesh && meshAABBs[potentialObstacle]?.intersects(checkAABB) == true) {
                                val worldBlushes = potentialObstacle.mesh.blushes.map { blush ->
                                    val transformedCorners = blush.getCorners().map { corner -> potentialObstacle.transformMatrix.transform(corner) }
                                    AABB.fromCube(transformedCorners)
                                }
                                !worldBlushes.any { it.contains(checkAABB.min + (checkAABB.max - checkAABB.min) / 2.0) }
                            } else false
                        }
                        if (isObstructed) isValid = false
                    }

                    if (!isValid) {
                        node.neighbors.remove(neighbor)
                        neighbor.neighbors.remove(node)
                    }
                }
            }
        }

        println("NavMesh extended with ${navMeshNodes.size} new nodes.")
    }

    private fun traceReflection(reflectionOrigin: Vector3d, incidentDirection: Vector3d, surfaceNormal: Vector3d, incomingLight: Vector3d, bouncesLeft: Int, sourceMesh: PlacedMesh, sourceFaceIndex: Int, remainingDistance: Double, resolution: Int = HIGH_QualityRes) {
        if (bouncesLeft <= 0 || remainingDistance <= 0) {
            return
        }

        var randomDir: Vector3d
        do {
            randomDir = Vector3d(Math.random() * 2 - 1, Math.random() * 2 - 1, Math.random() * 2 - 1)
        } while (randomDir.length() > 1.0)
        randomDir = randomDir.normalize()
        if (randomDir.dot(surfaceNormal) < 0) {
            randomDir *= -1.0
        }
        val bounceOrigin = reflectionOrigin + randomDir * 0.1

        val bounceHitResult = staticBvh.intersectWithDetails(bounceOrigin, randomDir, remainingDistance, sourceMesh, sourceFaceIndex) ?: dynamicBvh.intersectWithDetails(bounceOrigin, randomDir, remainingDistance, sourceMesh, sourceFaceIndex)

        if (bounceHitResult != null) {
            val (finalHitPrimitive, finalHitDist) = bounceHitResult
            val finalHitPoint = bounceOrigin + randomDir * finalHitDist

            // albedo
            val surfaceTexture = sourceMesh.faceTextures[sourceFaceIndex] ?: sourceMesh.texture
            val surfaceAlbedo = if (surfaceTexture != null) {
                val texColor = surfaceTexture.pixelReader.getColor(0, 0)
                Vector3d(texColor.red, texColor.green, texColor.blue)
            } else {
                val meshColor = sourceMesh.mesh.color
                Vector3d(meshColor.red, meshColor.green, meshColor.blue)
            }

            val bounceAttenuation = 0.5
            val outgoingLight = Vector3d(
                (incomingLight.x * surfaceAlbedo.x * bounceAttenuation).coerceIn(0.0, 1.0),
                (incomingLight.y * surfaceAlbedo.y * bounceAttenuation).coerceIn(0.0, 1.0),
                (incomingLight.z * surfaceAlbedo.z * bounceAttenuation).coerceIn(0.0, 1.0)
            )

            // dodaj światło do siatki trafionej powierzchni (jeśli to ostatnie odbicie) lub kontynuuj rekurencję
            if (bouncesLeft == 1) {
                val hitFaceKey = Pair(finalHitPrimitive.mesh, finalHitPrimitive.faceIndex)
                val subLightmapsForHitFace = largeFaceSubLightmaps[hitFaceKey]

                if (subLightmapsForHitFace != null) {
                    // Trafiono w dużą ścianę z sub-lightmapami
                    val subLightmap = subLightmapsForHitFace.find { it.aabb.contains(finalHitPoint) }
                    if (subLightmap != null) {
                        val localPos = finalHitPoint - subLightmap.worldPosition
                        val u = (localPos.x / cubeSize) + 0.5
                        val v = (localPos.z / cubeSize) + 0.5

                        val gridX = (u * resolution).toInt().coerceIn(0, resolution - 1)
                        val gridY = (v * resolution).toInt().coerceIn(0, resolution - 1)

                        giLightPoints.add(GI_LightPoint(hitFaceKey, gridX, gridY, outgoingLight))
                    }
                    /*
                    // Stara logika, która bezpośrednio modyfikowała siatkę
                    if (subLightmap != null) {
                        val localPos = finalHitPoint - subLightmap.worldPosition
                        val u = (localPos.x / cubeSize) + 0.5
                        val v = (localPos.z / cubeSize) + 0.5

                        val gridX = (u * resolution).toInt().coerceIn(0, resolution - 1)
                        val gridY = (v * resolution).toInt().coerceIn(0, resolution - 1)

                        val existingColor = subLightmap.grid[gridX][gridY]
                        val newR = (existingColor.red + outgoingLight.x * GI_LightIntensity).coerceIn(0.0, 1.0)
                        val newG = (existingColor.green + outgoingLight.y * GI_LightIntensity).coerceIn(0.0, 1.0)
                        val newB = (existingColor.blue + outgoingLight.z * GI_LightIntensity).coerceIn(0.0, 1.0)
                        subLightmap.grid[gridX][gridY] = Color(newR, newG, newB, 1.0)
                        subLightmap.needsUpdate = true // Oznacz do ponownego wypalenia tekstury
                    }
                    */
                } else {
                    // Trafiono w normalną ścianę, użyj starego systemu giLightPoints
                    val barycentricCoords = finalHitPrimitive.barycentricCoords
                    val allFaceUVs = finalHitPrimitive.mesh.mesh.faceUVs[finalHitPrimitive.faceIndex]

                    val u: Double
                    val v: Double

                    if (allFaceUVs.size == 4) {
                        val triUVs: List<Vector3d>
                        val meshFaceIndices = finalHitPrimitive.mesh.mesh.faces[finalHitPrimitive.faceIndex]
                        val meshV1 = finalHitPrimitive.mesh.getTransformedVertices()[meshFaceIndices[1]]

                        triUVs = if (finalHitPrimitive.v1 == meshV1) {
                            listOf(allFaceUVs[0], allFaceUVs[1], allFaceUVs[2])
                        } else {
                            listOf(allFaceUVs[0], allFaceUVs[2], allFaceUVs[3])
                        }
                        u = triUVs[0].x * barycentricCoords.z + triUVs[1].x * barycentricCoords.x + triUVs[2].x * barycentricCoords.y
                        v = triUVs[0].y * barycentricCoords.z + triUVs[1].y * barycentricCoords.x + triUVs[2].y * barycentricCoords.y
                    } else {
                        u = allFaceUVs[0].x * barycentricCoords.z + allFaceUVs[1].x * barycentricCoords.x + allFaceUVs[2].x * barycentricCoords.y
                        v = allFaceUVs[0].y * barycentricCoords.z + allFaceUVs[1].y * barycentricCoords.x + allFaceUVs[2].y * barycentricCoords.y
                    }

                    val gridX = (u * resolution).toInt().coerceIn(0, resolution - 1)
                    val gridY = ((1.0 - v) * resolution).toInt().coerceIn(0, resolution - 1)

                    giLightPoints.add(GI_LightPoint(
                        targetFaceKey = hitFaceKey,
                        gridX = gridX,
                        gridY = gridY,
                        color = outgoingLight
                    ))
                }
            } else {
                val finalHitNormal = (finalHitPrimitive.v1 - finalHitPrimitive.v0).cross(finalHitPrimitive.v2 - finalHitPrimitive.v0).normalize()
                traceReflection(finalHitPoint, randomDir, finalHitNormal, outgoingLight, bouncesLeft - 1, finalHitPrimitive.mesh, finalHitPrimitive.faceIndex, remainingDistance - finalHitDist)
            }
        }
    }

    private fun getPointOnFace(mesh: PlacedMesh, faceIndex: Int, u: Double, v: Double): Vector3d {
        val worldVerts = mesh.mesh.faces[faceIndex].map { vi -> mesh.transformMatrix.transform(mesh.mesh.vertices[vi]) }
        return if (worldVerts.size == 4) {
            val p1 = worldVerts[0].lerp(worldVerts[1], u)
            val p2 = worldVerts[3].lerp(worldVerts[2], u)
            p1.lerp(p2, v)
        } else {
            var u_bary = u; var v_bary = v
            if (u_bary + v_bary > 1.0) { u_bary = 1.0 - u_bary; v_bary = 1.0 - v_bary }
            worldVerts[0] * (1.0 - u_bary - v_bary) + worldVerts[1] * u_bary + worldVerts[2] * v_bary
        }
    }

    private fun applyGILightPoints(resolution: Int) {
        synchronized(giLightPoints) {
            if (giLightPoints.isEmpty()) return

            // pogrupuj punkty GI według ściany, na którą padają
            val pointsByFace = giLightPoints.groupBy { it.targetFaceKey }

            // mapa wierzchołków do ścian dla szybkiego wyszukiwania sąsiadów
            val vertexToFacesMap = mutableMapOf<Vector3d, MutableList<Pair<PlacedMesh, Int>>>()
            for (faceKey in pointsByFace.keys) {
                val (mesh, faceIndex) = faceKey
                val worldVerts = mesh.mesh.faces[faceIndex].map { mesh.transformMatrix.transform(mesh.mesh.vertices[it]) }
                worldVerts.forEach { vertex ->
                    vertexToFacesMap.computeIfAbsent(vertex) { mutableListOf() }.add(faceKey)
                }
            }

            for ((faceKey, pointsOnFace) in pointsByFace) {
                if (Thread.currentThread().isInterrupted) {
                    break
                }

                val giGrid = giLightGrids.computeIfAbsent(faceKey) { Array(resolution) { Array(resolution) { Color.BLACK } } }

                // Znajdź sąsiadujące ściany
                val (currentMesh, currentFaceIndex) = faceKey
                val currentWorldVerts = currentMesh.mesh.faces[currentFaceIndex].map { currentMesh.transformMatrix.transform(currentMesh.mesh.vertices[it]) }
                val neighborFaceKeys = currentWorldVerts.flatMap { vertex -> vertexToFacesMap[vertex] ?: emptyList() }
                    .filter { it != faceKey }
                    .distinct()

                val allRelevantPoints = pointsOnFace.toMutableList()
                neighborFaceKeys.forEach { neighborKey ->
                    pointsByFace[neighborKey]?.let { allRelevantPoints.addAll(it) }
                }

                // wypełnij siatkę oświetlenia - metodą IDW
                for (y in 0 until resolution) {
                    for (x in 0 until resolution) {
                        // Oblicz pozycję 3D piksela na siatce
                        val u = (x + 0.5) / resolution
                        val v = 1.0 - ((y + 0.5) / resolution) // Odwrócone V
                        val pointOnFace3D = getPointOnFace(currentMesh, currentFaceIndex, u, v)

                        var totalWeight = 0.0
                        var weightedR = 0.0
                        var weightedG = 0.0
                        var weightedB = 0.0

                        // Zbierz wpływy od wszystkich relevantnych punktów GI (z tej ściany i sąsiadów)
                        for (point in allRelevantPoints) {
                            val (pointMesh, pointFaceIndex) = point.targetFaceKey
                            val pointU = (point.gridX + 0.5) / resolution
                            val pointV = 1.0 - ((point.gridY + 0.5) / resolution)
                            val point3D = getPointOnFace(pointMesh, pointFaceIndex, pointU, pointV)

                            val distanceSq = pointOnFace3D.distanceSquared(point3D)

                            val weight = 1.0 / (distanceSq + 0.01)

                            weightedR += point.color.x * weight
                            weightedG += point.color.y * weight
                            weightedB += point.color.z * weight
                            totalWeight += weight
                        }

                        // Oblicz ostateczny kolor jako średnią ważoną
                        if (totalWeight > 0) {
                            val finalR = (weightedR / totalWeight).coerceIn(0.0, 1.0)
                            val finalG = (weightedG / totalWeight).coerceIn(0.0, 1.0)
                            val finalB = (weightedB / totalWeight).coerceIn(0.0, 1.0)

                            giGrid[x][y] = Color(
                                finalR,
                                finalG,
                                finalB,
                                1.0
                            )
                        }
                    }
                }

                // --- POST-PROCESSING: Wygładzanie oryginalnych punktów odbicia ---
                // Zastąp kolor w miejscu oryginalnego trafienia promienia GI średnią z sąsiadów,
                // aby wtopić go w uśrednione otoczenie.
                for (point in pointsOnFace) { // Iteruj tylko po punktach, które trafiły w TĘ ścianę
                    val px = point.gridX
                    val py = point.gridY

                    var sumR = 0.0
                    var sumG = 0.0
                    var sumB = 0.0
                    var count = 0

                    val neighbors = listOf(
                        Pair(px, py - 1), // Góra
                        Pair(px, py + 1), // Dół
                        Pair(px - 1, py), // Lewo
                        Pair(px + 1, py)  // Prawo
                    )

                    for ((nx, ny) in neighbors) {
                        if (nx >= 0 && nx < resolution && ny >= 0 && ny < resolution) {
                            val neighborColor = giGrid[nx][ny]
                            sumR += neighborColor.red
                            sumG += neighborColor.green
                            sumB += neighborColor.blue
                            count++
                        }
                    }

                    if (count > 0) {
                        giGrid[px][py] = Color(
                            (sumR / count).coerceIn(0.0, 1.0),
                            (sumG / count).coerceIn(0.0, 1.0),
                            (sumB / count).coerceIn(0.0, 1.0),
                            1.0
                        )
                    }
                }

                giUpdateQueue.add(faceKey)
            }
            giLightPoints.clear()
        }
    }

    private fun isOccluded(rayOrigin: Vector3d, rayTarget: Vector3d, ignoreMesh: PlacedMesh, ignoreFaceIndex: Int): Boolean {
        val rayDir = (rayTarget - rayOrigin).normalize()
        val rayLength = (rayTarget - rayOrigin).length()
        if (rayLength < 1e-5) return false

        val biasedRayLength = rayLength - 0.01
        // Sprawdzamy oba BVH
        return staticBvh.intersect(rayOrigin, rayDir, biasedRayLength, ignoreMesh, ignoreFaceIndex) || dynamicBvh.intersect(rayOrigin, rayDir, biasedRayLength, ignoreMesh, ignoreFaceIndex)
    }

    private fun isLineOfSightBlockedFast(start: Vector3d, end: Vector3d, ignoreMesh: PlacedMesh): Boolean {
        val direction = (end - start).normalize()
        val distance = (end - start).length()
        if (distance < 1e-2) return false

        val rayAABB = AABB(
            Vector3d(min(start.x, end.x), min(start.y, end.y), min(start.z, end.z)),
            Vector3d(max(start.x, end.x), max(start.y, end.y), max(start.z, end.z))
        )
        val potentialOccluders = staticCollisionGrid.query(rayAABB) + dynamicCollisionGrid.query(rayAABB)

        val numSteps = 5
        val stepSize = distance / numSteps

        for (i in 1 until numSteps) {
            val testPoint = start + direction * (i * stepSize)
            for (mesh in potentialOccluders) {
                if (mesh === ignoreMesh) continue
                val meshAABB = meshAABBs[mesh] ?: continue
                if (meshAABB.contains(testPoint)) {
                    val worldBlushes = mesh.mesh.blushes.map { blush ->
                        val transformedCorners = blush.getCorners().map { corner -> mesh.transformMatrix.transform(corner) }
                        AABB.fromCube(transformedCorners)
                    }
                    if (worldBlushes.isEmpty() || !worldBlushes.any { it.contains(testPoint) }) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun clipTriangleAgainstNearPlane(worldVertices: List<Vector3d>, uvs: List<Vector3d>, viewMatrix: Matrix4x4, nearPlane: Double): List<Pair<List<Vector3d>, List<Vector3d>>> {
        val viewVertices = worldVertices.map { viewMatrix.transform(it) }
        val insidePoints = mutableListOf<Vector3d>(); val insideUvs = mutableListOf<Vector3d>()
        val outsidePoints = mutableListOf<Vector3d>(); val outsideUvs = mutableListOf<Vector3d>()
        val clipZ = -nearPlane

        for (i in viewVertices.indices) {
            if (viewVertices[i].z < clipZ) {
                insidePoints.add(worldVertices[i]); insideUvs.add(uvs[i])
            } else {
                outsidePoints.add(worldVertices[i]); outsideUvs.add(uvs[i])
            }
        }

        when (insidePoints.size) {
            0 -> return emptyList()
            3 -> return listOf(Pair(worldVertices, uvs))
            1 -> {
                val pIn = insidePoints[0]; val uvIn = insideUvs[0]
                val pOut1 = outsidePoints[0]; val uvOut1 = outsideUvs[0]
                val pOut2 = outsidePoints[1]; val uvOut2 = outsideUvs[1]
                val vInView = viewMatrix.transform(pIn); val vOut1View = viewMatrix.transform(pOut1); val vOut2View = viewMatrix.transform(pOut2)
                val t1 = (clipZ - vInView.z) / (vOut1View.z - vInView.z)
                val newPoint1 = pIn + (pOut1 - pIn) * t1; val newUv1 = uvIn + (uvOut1 - uvIn) * t1
                val t2 = (clipZ - vInView.z) / (vOut2View.z - vInView.z)
                val newPoint2 = pIn + (pOut2 - pIn) * t2; val newUv2 = uvIn + (uvOut2 - uvIn) * t2
                return listOf(Pair(listOf(pIn, newPoint1, newPoint2), listOf(uvIn, newUv1, newUv2)))
            }
            2 -> {
                val pIn1 = insidePoints[0]; val uvIn1 = insideUvs[0]
                val pIn2 = insidePoints[1]; val uvIn2 = insideUvs[1]
                val pOut = outsidePoints[0]; val uvOut = outsideUvs[0]
                val vIn1View = viewMatrix.transform(pIn1); val vIn2View = viewMatrix.transform(pIn2); val vOutView = viewMatrix.transform(pOut)
                val t1 = (clipZ - vIn1View.z) / (vOutView.z - vIn1View.z)
                val newPoint1 = pIn1 + (pOut - pIn1) * t1; val newUv1 = uvIn1 + (uvOut - uvIn1) * t1
                val t2 = (clipZ - vIn2View.z) / (vOutView.z - vIn2View.z)
                val newPoint2 = pIn2 + (pOut - pIn2) * t2; val newUv2 = uvIn2 + (uvOut - uvIn2) * t2
                return listOf(Pair(listOf(pIn1, pIn2, newPoint1), listOf(uvIn1, uvIn2, newUv1)), Pair(listOf(newPoint1, pIn2, newPoint2), listOf(newUv1, uvIn2, newUv2)))
            }
        }
        return emptyList()
    }

    private fun requestRender() {
        if (isRendering.getAndSet(true)) return
        if (virtualWidth <= 0 || virtualHeight <= 0) {
            isRendering.set(false)
            return
        }

        if (depthBuffer.size != virtualWidth * virtualHeight) {
            depthBuffer = DoubleArray(virtualWidth * virtualHeight) { Double.MAX_VALUE }
            pixelBuffer = IntArray(virtualWidth * virtualHeight)
            msaaDepthBuffer = DoubleArray(virtualWidth * virtualHeight * 4) { Double.MAX_VALUE }
            msaaColorBuffer = IntArray(virtualWidth * virtualHeight * 4)
            upscaledPixelBuffer = IntArray(outputWidth * outputHeight)
            backBuffer = WritableImage(outputWidth, outputHeight)
            imageView.image = backBuffer
        }

        java.util.Arrays.fill(msaaColorBuffer, bgColorInt)
        java.util.Arrays.fill(msaaDepthBuffer, Double.MAX_VALUE)
        renderQueue.clear()
        transparentRenderQueue.clear()

        val lookDirection = Vector3d(cos(cameraPitch) * sin(cameraYaw), sin(cameraPitch), cos(cameraPitch) * cos(cameraYaw)).normalize()
        val upVector = Vector3d(0.0, 1.0, 0.0)
        val viewMatrix = Matrix4x4.lookAt(cameraPosition, cameraPosition + lookDirection, upVector)
        val projectionMatrix = Matrix4x4.perspective(dynamicFov, virtualWidth.toDouble() / virtualHeight.toDouble(), 0.1, renderDistanceBlocks)
        val combinedMatrix = projectionMatrix * viewMatrix

        val facesToProcess = mutableListOf<FaceToProcess>()

        // add walls from individual objects (dynamic, transparent, special)
        val individualMeshesToRender = (staticMeshes + lightGizmoMeshes + dynamicMeshes.values).filter { mesh ->
            val isBatched = staticMeshBatches.any { batch -> batch.texture == mesh.texture && !mesh.collision }
            !isBatched
        }

        for (mesh in individualMeshesToRender) {
            val worldVertices = mesh.mesh.vertices.map { mesh.transformMatrix.transform(it) }
            mesh.mesh.faces.forEachIndexed { faceIndex, faceIndicies ->
                facesToProcess.add(FaceToProcess(mesh, faceIndex, faceIndicies, worldVertices, mesh.collision))
            }
        }

        // add walls from batched objects
        for (batch in staticMeshBatches) {
            val worldVertices = batch.mesh.vertices
            batch.mesh.faces.forEachIndexed { faceIndex, faceIndices ->
                facesToProcess.add(FaceToProcess(batch, faceIndex, faceIndices, worldVertices, false))
            }
        }

        // Dodaj pod-lightmapy dla dużych ścian
        largeFaceSubLightmaps.forEach { (faceKey, subLightmaps) ->
            val (mesh, faceIndex) = faceKey
            val faceWorldVerts = mesh.mesh.faces[faceIndex].map { mesh.transformMatrix.transform(mesh.mesh.vertices[it]) }
            val faceNormal = (faceWorldVerts[1] - faceWorldVerts[0]).cross(faceWorldVerts[2] - faceWorldVerts[0]).normalize()
            val offsetVector = faceNormal * subLightmap_offset

            val subLightmapsSnapshot = synchronized(subLightmaps) { subLightmaps.toList() }
            for (subLightmap in subLightmapsSnapshot) {
                val halfSize = cubeSize / 2.0
                val corners = listOf(
                    subLightmap.worldPosition + Vector3d(-halfSize, 0.0, -halfSize) + offsetVector,
                    subLightmap.worldPosition + Vector3d( halfSize, 0.0, -halfSize) + offsetVector,
                    subLightmap.worldPosition + Vector3d( halfSize, 0.0,  halfSize) + offsetVector,
                    subLightmap.worldPosition + Vector3d(-halfSize, 0.0,  halfSize) + offsetVector
                )
                // UVs for a quad
                val uvs = listOf(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(1.0, 1.0, 0.0), Vector3d(0.0, 1.0, 0.0))

                // Render the sub-lightmap as a textured quad, with its own light texture. No base texture, no lightgrid.
                // Triangle 1: 0, 3, 2
                processAndQueueRenderableFace(listOf(corners[0], corners[3], corners[2]), listOf(uvs[0], uvs[3], uvs[2]), mesh.mesh.color, subLightmap.texture, null, null, emptyList(), null, combinedMatrix, viewMatrix, false)
                // Triangle 2: 0, 2, 1
                processAndQueueRenderableFace(listOf(corners[0], corners[2], corners[1]), listOf(uvs[0], uvs[2], uvs[1]), mesh.mesh.color, subLightmap.texture, null, null, emptyList(), null, combinedMatrix, viewMatrix, false)
            }
        }

        val tasks = mutableListOf<Future<*>>()
        val chunkSize = (facesToProcess.size / Runtime.getRuntime().availableProcessors()).coerceAtLeast(100)
        val chunks = facesToProcess.chunked(chunkSize)

        for (chunk in chunks) {
            tasks.add(executor.submit {
                for (faceData in chunk) {
                    if (faceData.indices.size < 3) continue

                    // Face-level Culling1
                    var skipDistanceCheck = false
                    if (faceData.source is PlacedMesh) {
                        val mesh = faceData.source
                        val modelName = modelRegistry.entries.find { it.value === mesh.mesh }?.key
                        if (modelName == "skybox" || mesh.alwaysRender) {
                            skipDistanceCheck = true
                        }
                    }

                    if (!skipDistanceCheck) {
                        val faceWorldVerticesForDist = faceData.indices.map { faceData.worldVertices[it] }
                        var minDistanceSq = Double.MAX_VALUE

                        // Triangulate the face to find the closest point to the camera
                        for (i in 0 until faceWorldVerticesForDist.size - 2) {
                            val v0 = faceWorldVerticesForDist[0]
                            val v1 = faceWorldVerticesForDist[i + 1]
                            val v2 = faceWorldVerticesForDist[i + 2]
                            val closestPoint = closestPointOnTriangle(cameraPosition, v0, v1, v2)
                            val distanceSq = cameraPosition.distanceSquared(closestPoint)
                            if (distanceSq < minDistanceSq) {
                                minDistanceSq = distanceSq
                            }
                        }

                        if (minDistanceSq > renderDistanceBlocks * renderDistanceBlocks) {
                            continue // Cull this face as it's too far
                        }
                    }

                    val worldVertices = faceData.worldVertices
                    val p0View = viewMatrix.transform(worldVertices[faceData.indices[0]])
                    val p1View = viewMatrix.transform(worldVertices[faceData.indices[1]])
                    val p2View = viewMatrix.transform(worldVertices[faceData.indices[2]])

                    val normalView = (p1View - p0View).cross(p2View - p0View).normalize()
                    val cameraRayView = (Vector3d(0.0, 0.0, 0.0) - p0View).normalize()

                    if (normalView.dot(cameraRayView) > 0) { // Back-face culling
                        val faceWorldVertices = faceData.indices.map { worldVertices[it] }
                        val faceTexCoords: List<Vector3d>
                        val lightGrid: Array<Array<Color>>?
                        val faceTexture: Image?
                        val giGrid: Array<Array<Color>>?
                        val meshColor: Color
                        val modelName: String?
                        val hasCollision: Boolean
                        val worldBlushes: List<AABB>
                        val blushContainerAABB: AABB?

                        if (faceData.source is PlacedMesh) {
                            val mesh = faceData.source
                            faceTexCoords = mesh.mesh.faceUVs[faceData.faceIndex]
                            lightGrid = faceLightGrids[Pair(mesh, faceData.faceIndex)]
                            giGrid = giLightGrids[Pair(mesh, faceData.faceIndex)]
                            meshColor = mesh.mesh.color
                            modelName = modelRegistry.entries.find { it.value === mesh.mesh }?.key
                            hasCollision = mesh.collision
                            val textureName = mesh.mesh.faceTextureNames[faceData.faceIndex]
                            faceTexture = if (textureName != null && textureName.startsWith("custom_")) {
                                val textureId = textureName.substringAfter("custom_").toIntOrNull()
                                if (textureId != null && modelName != null) dynamicTextures["${modelName}_$textureId"]
                                else mesh.faceTextures[faceData.faceIndex] ?: mesh.texture
                            } else {
                                mesh.faceTextures[faceData.faceIndex] ?: mesh.texture
                            }
                            worldBlushes = mesh.mesh.blushes.map { blush ->
                                val transformedCorners = blush.getCorners().map { corner -> mesh.transformMatrix.transform(corner) }
                                AABB.fromCube(transformedCorners)
                            }
                            blushContainerAABB = if (worldBlushes.isNotEmpty()) AABB.fromAABBs(worldBlushes) else null

                        } else { // source is StaticMeshBatch
                            val batch = faceData.source as StaticMeshBatch
                            faceTexCoords = batch.mesh.faceUVs[faceData.faceIndex]
                            val originalFaceInfo = batch.faceIndexToOriginal[faceData.faceIndex]
                            lightGrid = if (originalFaceInfo != null) faceLightGrids[originalFaceInfo] else null
                            giGrid = if (originalFaceInfo != null) giLightGrids[originalFaceInfo] else null
                            faceTexture = batch.texture
                            meshColor = batch.mesh.color
                            modelName = null
                            hasCollision = false

                            // blushes system
                            if (originalFaceInfo != null) {
                                val (originalMesh, _) = originalFaceInfo
                                worldBlushes = originalMesh.mesh.blushes.map { blush ->
                                    val transformedCorners = blush.getCorners().map { corner -> originalMesh.transformMatrix.transform(corner) }
                                    AABB.fromCube(transformedCorners)
                                }
                                blushContainerAABB = if (worldBlushes.isNotEmpty()) AABB.fromAABBs(worldBlushes) else null
                            } else {
                                worldBlushes = emptyList()
                                blushContainerAABB = null
                            }
                        }

                        var finalWorldVertices = faceWorldVertices
                        var finalUVs = faceTexCoords
                        var isRightAngledTriangle = false

                        if (faceData.indices.size == 3) {
                            val (v0, v1, v2) = faceWorldVertices
                            val vectors = listOf(v1 - v0, v2 - v1, v0 - v2)
                            val dotProducts = listOf(vectors[0].dot(vectors[1]), vectors[1].dot(vectors[2]), vectors[2].dot(vectors[0]))
                            val rightAngleIndex = dotProducts.indexOfFirst { abs(it) < 1e-4 }

                            if (rightAngleIndex != -1) {
                                isRightAngledTriangle = true
                                val rightAngleVertex = faceWorldVertices[(rightAngleIndex + 1) % 3]
                                val adjacentVertex1 = faceWorldVertices[rightAngleIndex]
                                val adjacentVertex2 = faceWorldVertices[(rightAngleIndex + 2) % 3]

                                val v4 = adjacentVertex1 + adjacentVertex2 - rightAngleVertex
                                finalWorldVertices = listOf(adjacentVertex2, rightAngleVertex, adjacentVertex1, v4)
                                finalUVs = listOf(Vector3d(1.0, 1.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
                            }
                        }

                        val triangles = if (finalWorldVertices.size == 4) {
                            if (isRightAngledTriangle) {
                                // Renderuj tylko oryginalny trójkąt (adj1, rightAngle, adj2) z UV z kwadratu
                                listOf(Pair(listOf(finalWorldVertices[0], finalWorldVertices[1], finalWorldVertices[2]), listOf(finalUVs[0], finalUVs[1], finalUVs[2])))
                            } else { // Zwykły kwadrat
                                listOf(
                                    Pair(listOf(finalWorldVertices[0], finalWorldVertices[1], finalWorldVertices[2]), listOf(finalUVs[0], finalUVs[1], finalUVs[2])),
                                    Pair(listOf(finalWorldVertices[0], finalWorldVertices[2], finalWorldVertices[3]), listOf(finalUVs[0], finalUVs[2], finalUVs[3]))
                                )
                            }
                        } else {
                            listOf(Pair(finalWorldVertices, finalUVs))
                        }

                        for ((triWorld, triUV) in triangles) {
                            processAndQueueRenderableFace(triWorld, triUV, meshColor, faceTexture, lightGrid, giGrid, worldBlushes, blushContainerAABB, combinedMatrix, viewMatrix, hasCollision)
                        }
                    }
                }
            })
        }

        for (task in tasks) { task.get() }

        // Przetwarzanie ścian zaktualizowanych przez GI
        while (giUpdateQueue.isNotEmpty()) {
            val faceToUpdateKey = giUpdateQueue.poll() ?: continue
            val (mesh, faceIndex) = faceToUpdateKey

            val faceIndices = mesh.mesh.faces.getOrNull(faceIndex) ?: continue
            val worldVertices = faceIndices.map { mesh.transformMatrix.transform(mesh.mesh.vertices[it]) }
            val faceTexCoords = mesh.mesh.faceUVs.getOrNull(faceIndex) ?: continue
            val lightGrid = faceLightGrids[faceToUpdateKey]
            val faceTexture = mesh.faceTextures[faceIndex] ?: mesh.texture
            val giGrid = giLightGrids[faceToUpdateKey]

            // Ponownie trianguluj i dodaj do kolejki renderowania
            val triangles = if (faceIndices.size == 4) {
                listOf(
                    Pair(listOf(worldVertices[0], worldVertices[1], worldVertices[2]), listOf(faceTexCoords[0], faceTexCoords[1], faceTexCoords[2])),
                    Pair(listOf(worldVertices[0], worldVertices[2], worldVertices[3]), listOf(faceTexCoords[0], faceTexCoords[2], faceTexCoords[3]))
                )
            } else {
                listOf(Pair(worldVertices, faceTexCoords))
            }

            triangles.forEach { (triWorld, triUV) -> processAndQueueRenderableFace(triWorld, triUV, mesh.mesh.color, faceTexture, lightGrid, giGrid, emptyList(), null, combinedMatrix, viewMatrix, mesh.collision) }
        }

        // Rasteryzuj kolejki
        val coreCount = Runtime.getRuntime().availableProcessors()
        val bandHeight = (virtualHeight.toDouble() / coreCount).roundToInt()
        val rasterTasks = mutableListOf<Future<*>>()

        if (renderQueue.isNotEmpty()) {
            val renderQueueSnapshot = renderQueue.toList()
            renderQueue.clear()

            for (i in 0 until coreCount) {
                val yStart = i * bandHeight
                val yEnd = if (i == coreCount - 1) virtualHeight else yStart + bandHeight

                rasterTasks.add(executor.submit {
                    for (renderableFace in renderQueueSnapshot) {
                        rasterizeTexturedTriangle(renderableFace, virtualWidth, virtualHeight, yStart, yEnd)
                    }
                })
            }
        }

        for (task in rasterTasks) { task.get() }


        val sortedTransparentFaces = transparentRenderQueue.sortedByDescending { face ->
            face.screenVertices.map { it.z }.average()
        }
        for (renderableFace in sortedTransparentFaces) {
            rasterizeTexturedTriangle(renderableFace, virtualWidth, virtualHeight)
        }

        resolveMSAA(virtualWidth, virtualHeight)

        if (debugShowNavMesh) {
            drawNavMeshDebug(combinedMatrix)
        }

        // Upscaling pass (Nearest Neighbor Dynamic)
        val upscaleTasks = mutableListOf<Future<*>>()
        val upscaleBandHeight = virtualHeight / coreCount

        for (i in 0 until coreCount) {
            val yStart = i * upscaleBandHeight
            val yEnd = if (i == coreCount - 1) virtualHeight else yStart + upscaleBandHeight

            upscaleTasks.add(executor.submit {
                for (y in yStart until yEnd) {
                    val srcRowStart = y * virtualWidth

                    for (dy in 0 until outputUpscaleFactor) {
                        val dstRowStart = ((y * outputUpscaleFactor) + dy) * outputWidth
                        var dstIndex = dstRowStart
                        for (x in 0 until virtualWidth) {
                            val color = pixelBuffer[srcRowStart + x]
                            for (dx in 0 until outputUpscaleFactor) {
                                upscaledPixelBuffer[dstIndex++] = color
                            }
                        }
                    }
                }
            })
        }
        for (task in upscaleTasks) { task.get() }

        backBuffer.pixelWriter.setPixels(0, 0, outputWidth, outputHeight, PixelFormat.getIntArgbInstance(), upscaledPixelBuffer, 0, outputWidth)
        imageView.image = backBuffer
        drawOverlay()

        previousCombinedMatrix = combinedMatrix
        frameCount++
        isRendering.set(false)
    }

    private fun drawNavMeshDebug(matrix: Matrix4x4) {
        val mesh = navMesh ?: return
        val nodeColor = 0xFFADD8E6.toInt() // LightBlue
        val edgeColor = 0xFF0000FF.toInt() // Blue

        for (node in mesh.nodes) {
            val proj = matrix.transformHomogeneous(node.position)
            if (proj.w > 0) {
                val x = ((proj.x / proj.w + 1) * virtualWidth / 2.0).toInt()
                val y = ((1 - proj.y / proj.w) * virtualHeight / 2.0).toInt()
                val z = proj.z / proj.w

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val px = x + dx
                        val py = y + dy
                        if (px in 0 until virtualWidth && py in 0 until virtualHeight) {
                            val idx = py * virtualWidth + px
                            if (z < depthBuffer[idx]) {
                                pixelBuffer[idx] = nodeColor
                            }
                        }
                    }
                }
            }

            for (neighbor in node.neighbors) {
                rasterizeLine(node.position, neighbor.position, edgeColor, matrix)
            }
        }
    }

    private fun rasterizeLine(p0: Vector3d, p1: Vector3d, color: Int, matrix: Matrix4x4) {
        val proj0 = matrix.transformHomogeneous(p0)
        val proj1 = matrix.transformHomogeneous(p1)

        var x0h = proj0.x; var y0h = proj0.y; var z0h = proj0.z; var w0 = proj0.w
        var x1h = proj1.x; var y1h = proj1.y; var z1h = proj1.z; var w1 = proj1.w

        if (w0 <= 0 && w1 <= 0) return

        if (w0 <= 0 || w1 <= 0) {
            val near = 0.001
            val t = (near - w0) / (w1 - w0)
            val x = x0h + (x1h - x0h) * t
            val y = y0h + (y1h - y0h) * t
            val z = z0h + (z1h - z0h) * t
            val w = near
            if (w0 <= 0) { x0h = x; y0h = y; z0h = z; w0 = w }
            else { x1h = x; y1h = y; z1h = z; w1 = w }
        }

        val x0 = ((x0h / w0 + 1) * virtualWidth / 2.0).toInt()
        val y0 = ((1 - y0h / w0) * virtualHeight / 2.0).toInt()
        val z0 = z0h / w0

        val x1 = ((x1h / w1 + 1) * virtualWidth / 2.0).toInt()
        val y1 = ((1 - y1h / w1) * virtualHeight / 2.0).toInt()
        val z1 = z1h / w1

        var x = x0; var y = y0; var z = z0
        val dx = abs(x1 - x0); val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        val dist = max(dx, dy).toDouble()
        val dz = if (dist > 0) (z1 - z0) / dist else 0.0
        val maxSteps = max(virtualWidth, virtualHeight) * 2
        var steps = 0

        while (true) {
            if (x in 0 until virtualWidth && y in 0 until virtualHeight) {
                val idx = y * virtualWidth + x
                if (z < depthBuffer[idx]) {
                    pixelBuffer[idx] = color
                }
            }
            if (x == x1 && y == y1) break
            if (steps++ > maxSteps) break
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; x += sx }
            if (e2 < dx) { err += dx; y += sy }
            z += dz
        }
    }

    private fun resolveMSAA(width: Int, height: Int) {
        val samples = if (antyAliasingMode) 4 else 1
        val coreCount = Runtime.getRuntime().availableProcessors()
        val bandHeight = height / coreCount
        val tasks = mutableListOf<Future<*>>()

        for (i in 0 until coreCount) {
            val yStart = i * bandHeight
            val yEnd = if (i == coreCount - 1) height else yStart + bandHeight
            tasks.add(executor.submit {
                for (y in yStart until yEnd) {
                    for (x in 0 until width) {
                        val pixelIndex = y * width + x
                        val baseIdx = pixelIndex * 4

                        var r = 0; var g = 0; var b = 0

                        for (s in 0 until samples) {
                            val c = msaaColorBuffer[baseIdx + s]
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                        }

                        pixelBuffer[pixelIndex] = (0xFF shl 24) or
                                ((r / samples) shl 16) or
                                ((g / samples) shl 8) or
                                (b / samples)

                        // Copy depth for debug drawing (use sample 0)
                        depthBuffer[pixelIndex] = msaaDepthBuffer[baseIdx]
                    }
                }
            })
        }
        for (task in tasks) { task.get() }
    }

    private fun rasterizeTexturedTriangle(renderableFace: RenderableFace, screenWidth: Int, screenHeight: Int, bandYStart: Int = 0, bandYEnd: Int = screenHeight) {
        val (screenVertices, originalClipW, textureVertices, color, _, texture, worldVertices, lightGrid, blushes, blushContainerAABB, giGrid) = renderableFace
        if (screenVertices.size != 3 || textureVertices.size != 3 || originalClipW.size != 3) return

        val v0 = screenVertices[0]; val v1 = screenVertices[1]; val v2 = screenVertices[2]
        val wVert0 = worldVertices[0]; val wVert1 = worldVertices[1]; val wVert2 = worldVertices[2]
        val uv0 = textureVertices[0]; val uv1 = textureVertices[1]; val uv2 = textureVertices[2]
        val w0 = originalClipW[0]; val w1 = originalClipW[1]; val w2 = originalClipW[2]

        val minX = max(0, minOf(v0.x.toInt(), v1.x.toInt(), v2.x.toInt()))
        val maxX = min(screenWidth - 1, maxOf(v0.x.toInt(), v1.x.toInt(), v2.x.toInt()))
        val minY = max(bandYStart, max(0, minOf(v0.y.toInt(), v1.y.toInt(), v2.y.toInt())))
        val maxY = min(bandYEnd - 1, min(screenHeight - 1, maxOf(v0.y.toInt(), v1.y.toInt(), v2.y.toInt())))

        if (minX > maxX || minY > maxY) return

        val A12 = v1.y - v2.y; val B12 = v2.x - v1.x
        val A20 = v2.y - v0.y; val B20 = v0.x - v2.x
        val A01 = v0.y - v1.y; val B01 = v1.x - v0.x
        val C12 = v1.x * v2.y - v2.x * v1.y
        val C20 = v2.x * v0.y - v0.x * v2.y
        val C01 = v0.x * v1.y - v1.x * v0.y

        val totalArea = A12 * v0.x + B12 * v0.y + C12
        if (abs(totalArea) < 1e-5) return
        val invTotalArea = 1.0 / totalArea

        val u0_prime = uv0.x / w0; val v0_prime = uv0.y / w0; val z0_inv_prime = 1.0 / w0
        val u1_prime = uv1.x / w1; val v1_prime = uv1.y / w1; val z1_inv_prime = 1.0 / w1
        val u2_prime = uv2.x / w2; val v2_prime = uv2.y / w2; val z2_inv_prime = 1.0 / w2

        val wVert0_prime = wVert0 / w0
        val wVert1_prime = wVert1 / w1
        val wVert2_prime = wVert2 / w2

        // Obliczanie gradientów dla atrybutów wzdłuż osi X
        val alpha_dx = A12 * invTotalArea
        val beta_dx = A20 * invTotalArea
        val gamma_dx = A01 * invTotalArea
        val alpha_dy = B12 * invTotalArea
        val beta_dy = B20 * invTotalArea
        val gamma_dy = B01 * invTotalArea

        val z_inv_prime_dx = alpha_dx * z0_inv_prime + beta_dx * z1_inv_prime + gamma_dx * z2_inv_prime
        val u_prime_dx = alpha_dx * u0_prime + beta_dx * u1_prime + gamma_dx * u2_prime
        val v_prime_dx = alpha_dx * v0_prime + beta_dx * v1_prime + gamma_dx * v2_prime
        val world_x_prime_dx = alpha_dx * wVert0_prime.x + beta_dx * wVert1_prime.x + gamma_dx * wVert2_prime.x
        val world_y_prime_dx = alpha_dx * wVert0_prime.y + beta_dx * wVert1_prime.y + gamma_dx * wVert2_prime.y
        val world_z_prime_dx = alpha_dx * wVert0_prime.z + beta_dx * wVert1_prime.z + gamma_dx * wVert2_prime.z
        val z_dx = alpha_dx * v0.z + beta_dx * v1.z + gamma_dx * v2.z
        val z_dy = alpha_dy * v0.z + beta_dy * v1.z + gamma_dy * v2.z

        val ambientR = color.red * ambientIntensity
        val ambientG = color.green * ambientIntensity
        val ambientB = color.blue * ambientIntensity

        val fogR = fogColor.red; val fogG = fogColor.green; val fogB = fogColor.blue
        val fogRange = fogEndDistance - fogStartDistance

        val texPixels: IntArray? = if (texture != null) getTexturePixels(texture) else null
        val texDim: Pair<Int, Int>? = if (texture != null) textureDimensions[texture] else null

        val hasLightGrid = lightGrid != null
        val hasGiGrid = giGrid != null
        val hasTexture = texPixels != null && texDim != null
        val applyFog = renderableFace.hasCollision || fogAffectsNonCollidables
        val lightGridResolution = lightGrid?.size ?: 1

        val useMSAA = antyAliasingMode
        val samples = if (useMSAA) 4 else 1

        // Oblicz wartości startowe dla alpha i beta na początku pierwszego wiersza
        var alpha_row_start = (A12 * minX + B12 * minY + C12) * invTotalArea
        var beta_row_start = (A20 * minX + B20 * minY + C20) * invTotalArea

        for (py in minY..maxY) {
            val rowOffset = py * screenWidth

            // Ustaw wartości startowe dla bieżącego wiersza
            var alpha_px = alpha_row_start
            var beta_px = beta_row_start

            // Oblicz wartości startowe dla atrybutów na początku wiersza
            val gamma_row_start = 1.0 - alpha_row_start - beta_row_start

            var interpolated_z_inv_prime_px = alpha_row_start * z0_inv_prime + beta_row_start * z1_inv_prime + gamma_row_start * z2_inv_prime
            var interpolated_u_prime_px = alpha_row_start * u0_prime + beta_row_start * u1_prime + gamma_row_start * u2_prime
            var interpolated_v_prime_px = alpha_row_start * v0_prime + beta_row_start * v1_prime + gamma_row_start * v2_prime
            var interpolated_world_x_prime_px = alpha_row_start * wVert0_prime.x + beta_row_start * wVert1_prime.x + gamma_row_start * wVert2_prime.x
            var interpolated_world_y_prime_px = alpha_row_start * wVert0_prime.y + beta_row_start * wVert1_prime.y + gamma_row_start * wVert2_prime.y
            var interpolated_world_z_prime_px = alpha_row_start * wVert0_prime.z + beta_row_start * wVert1_prime.z + gamma_row_start * wVert2_prime.z
            var interpolated_z_px = alpha_row_start * v0.z + beta_row_start * v1.z + gamma_row_start * v2.z

            for (px in minX..maxX) {
                val gamma_px = 1.0 - alpha_px - beta_px

                var coverageMask = 0
                for (s in 0 until samples) {
                    val sx = if (useMSAA) msaaOffsetsX[s] else 0.5
                    val sy = if (useMSAA) msaaOffsetsY[s] else 0.5
                    // Calculate barycentric at sample position
                    val sAlpha = alpha_px + alpha_dx * sx + alpha_dy * sy
                    val sBeta = beta_px + beta_dx * sx + beta_dy * sy
                    val sGamma = 1.0 - sAlpha - sBeta

                    if (sAlpha >= 0 && sBeta >= 0 && sGamma >= 0) {
                        coverageMask = coverageMask or (1 shl s)
                    }
                }

                if (coverageMask != 0) {
                    val pixelIndex = px + rowOffset

                    if (interpolated_z_inv_prime_px < 1e-6) {
                        alpha_px += alpha_dx; beta_px += beta_dx
                        interpolated_z_inv_prime_px += z_inv_prime_dx; interpolated_u_prime_px += u_prime_dx; interpolated_v_prime_px += v_prime_dx
                        interpolated_world_x_prime_px += world_x_prime_dx; interpolated_world_y_prime_px += world_y_prime_dx; interpolated_world_z_prime_px += world_z_prime_dx
                        interpolated_z_px += z_dx
                        continue
                    }
                    val inv_z_prime = 1.0 / interpolated_z_inv_prime_px

                    // OPTYMALIZACJA: Rozpakowanie Vector3d na prymitywy, aby uniknąć alokacji w pętli
                    val iWorldX = interpolated_world_x_prime_px * inv_z_prime
                    val iWorldY = interpolated_world_y_prime_px * inv_z_prime
                    val iWorldZ = interpolated_world_z_prime_px * inv_z_prime

                    // Sprawdzenie AABB ręcznie na prymitywach (zakładając, że AABB ma metody lub można to rozwinąć)
                    // Tutaj musimy sprawdzić czy blushContainerAABB zawiera punkt (x,y,z) bez tworzenia wektora
                    val isInBlush = blushContainerAABB?.let {
                        iWorldX >= it.min.x && iWorldX <= it.max.x &&
                                iWorldY >= it.min.y && iWorldY <= it.max.y &&
                                iWorldZ >= it.min.z && iWorldZ <= it.max.z
                    } == true && blushes.any { blush ->
                        iWorldX >= blush.min.x && iWorldX <= blush.max.x &&
                                iWorldY >= blush.min.y && iWorldY <= blush.max.y &&
                                iWorldZ >= blush.min.z && iWorldZ <= blush.max.z
                    }

                    if (!isInBlush) {
                        if (!hasTexture) { // This is a gizmo or untextured face
                            val finalColor = colorToInt(color)
                            for (s in 0 until samples) {
                                if ((coverageMask and (1 shl s)) != 0) {
                                    val sx = if (useMSAA) msaaOffsetsX[s] else 0.5
                                    val sy = if (useMSAA) msaaOffsetsY[s] else 0.5
                                    val sZ = interpolated_z_px + z_dx * sx + z_dy * sy
                                    val idx = (pixelIndex * 4) + s
                                    if (sZ < msaaDepthBuffer[idx]) {
                                        msaaDepthBuffer[idx] = sZ
                                        msaaColorBuffer[idx] = finalColor
                                    }
                                }
                            }
                            // Inkrementuj i kontynuuj
                            alpha_px += alpha_dx; beta_px += beta_dx
                            interpolated_z_inv_prime_px += z_inv_prime_dx; interpolated_u_prime_px += u_prime_dx; interpolated_v_prime_px += v_prime_dx
                            interpolated_world_x_prime_px += world_x_prime_dx; interpolated_world_y_prime_px += world_y_prime_dx; interpolated_world_z_prime_px += world_z_prime_dx
                            interpolated_z_px += z_dx
                            continue
                        }

                        val (texWidth, texHeight) = texDim
                        val u = interpolated_u_prime_px * inv_z_prime
                        val v = interpolated_v_prime_px * inv_z_prime
                        val texX = (u * texWidth).toInt().coerceIn(0, texWidth - 1)
                        val texY = (v * texHeight).toInt().coerceIn(0, texHeight - 1)

                        val texColorArgb = texPixels[texX + texY * texWidth]
                        val texOpacity = (texColorArgb ushr 24) / 255.0

                        if (texOpacity < 0.01) {
                            // Inkrementuj i kontynuuj
                            alpha_px += alpha_dx; beta_px += beta_dx
                            interpolated_z_inv_prime_px += z_inv_prime_dx; interpolated_u_prime_px += u_prime_dx; interpolated_v_prime_px += v_prime_dx
                            interpolated_world_x_prime_px += world_x_prime_dx; interpolated_world_y_prime_px += world_y_prime_dx; interpolated_world_z_prime_px += world_z_prime_dx
                            interpolated_z_px += z_dx
                            continue
                        }

                        val texR = ((texColorArgb ushr 16) and 0xFF) / 255.0
                        val texG = ((texColorArgb ushr 8) and 0xFF) / 255.0
                        val texB = (texColorArgb and 0xFF) / 255.0

                        val dynamicLight = if (hasLightGrid) {
                            val gridX = (u * lightGridResolution).toInt().coerceIn(0, lightGridResolution - 1)
                            val gridY = ((1.0 - v) * lightGridResolution).toInt().coerceIn(0, lightGridResolution - 1)
                            lightGrid!![gridX][gridY]
                        } else {
                            Color.BLACK
                        }

                        val giLight = if (hasGiGrid) {
                            val gridX = (u * lightGridResolution).toInt().coerceIn(0, lightGridResolution - 1)
                            val gridY = ((1.0 - v) * lightGridResolution).toInt().coerceIn(0, lightGridResolution - 1)
                            giGrid!![gridX][gridY]
                        } else {
                            Color.BLACK
                        }

                        val ambientLitR = texR * (ambientR * 2)
                        val ambientLitG = texG * (ambientG * 2)
                        val ambientLitB = texB * (ambientB * 2)

                        val dynamicLightR = dynamicLight.red * (ambientIntensity * globalLightIntensity)
                        val dynamicLightG = dynamicLight.green * (ambientIntensity * globalLightIntensity)
                        val dynamicLightB = dynamicLight.blue * (ambientIntensity * globalLightIntensity)

                        val directLightLuminance = (dynamicLight.red + dynamicLight.green + dynamicLight.blue) / 3.0
                        val giModulationFactor = (1.0 - directLightLuminance).coerceIn(0.0, 1.0)

                        val giLightR = giLight.red * (ambientIntensity * globalLightIntensity) * giModulationFactor
                        val giLightG = giLight.green * (ambientIntensity * globalLightIntensity) * giModulationFactor
                        val giLightB = giLight.blue * (ambientIntensity * globalLightIntensity) * giModulationFactor

                        var r = ambientLitR + dynamicLightR / 4 + giLightR * GI_LightIntensity * 2.0
                        var g = ambientLitG + dynamicLightG / 4 + giLightG * GI_LightIntensity * 2.0
                        var b = ambientLitB + dynamicLightB / 4 + giLightB * GI_LightIntensity * 2.0

                        if (applyFog and fogMode) {
                            val distance = inv_z_prime
                            val fogFactor = ((distance - fogStartDistance) / fogRange).coerceIn(0.0, 1.0) * fogDensity

                            r = r * (1 - fogFactor) + fogR * fogFactor
                            g = g * (1 - fogFactor) + fogG * fogFactor
                            b = b * (1 - fogFactor) + fogB * fogFactor
                        }

                        if (retroScanLineMode) {
                            val screenCenterY = screenHeight / 2.0
                            val screenCenterX = screenWidth / 2.0
                            val distFromCenterY = (py - screenCenterY) / screenCenterY
                            val distFromCenterX = (px - screenCenterX) / screenCenterX
                            val distSquared = distFromCenterX * distFromCenterX + distFromCenterY * distFromCenterY
                            val vignetteFactor = 1.0 - distSquared * 0.15

                            r *= vignetteFactor
                            g *= vignetteFactor
                            b *= vignetteFactor

                            val rShiftFactor = 1.0 + distSquared * 0.05
                            val bShiftFactor = 1.0 + distSquared * 0.05
                            val tempR = r * rShiftFactor
                            val tempB = b * bShiftFactor
                            val scanlineFactor = 0.80
                            val bloomFactor = 1.05

                            when (py % 3) {
                                0 -> {
                                    r *= scanlineFactor; g *= scanlineFactor; b *= scanlineFactor
                                }
                                1 -> {
                                    r *= bloomFactor; g *= bloomFactor; b *= bloomFactor
                                }
                            }

                            val noise = (Math.random() * 0.05) - 0.025
                            r += noise; g += noise; b += noise

                            r = tempR
                            b = tempB
                        }

                        val finalR = (r * 255).toInt().coerceIn(0, 255)
                        val finalG = (g * 255).toInt().coerceIn(0, 255)
                        val finalB = (b * 255).toInt().coerceIn(0, 255)

                        val isOpaque = texOpacity >= 1.0
                        if (!isOpaque) {
                            val srcAlpha = texOpacity
                            val invSrcAlpha = 1.0 - srcAlpha

                            for (s in 0 until samples) {
                                if ((coverageMask and (1 shl s)) != 0) {
                                    val sx = if (useMSAA) msaaOffsetsX[s] else 0.5
                                    val sy = if (useMSAA) msaaOffsetsY[s] else 0.5
                                    val sZ = interpolated_z_px + z_dx * sx + z_dy * sy
                                    val idx = (pixelIndex * 4) + s

                                    if (sZ < msaaDepthBuffer[idx]) {
                                        val dstColorInt = msaaColorBuffer[idx]
                                        val dstR = (dstColorInt shr 16) and 0xFF
                                        val dstG = (dstColorInt shr 8) and 0xFF
                                        val dstB = dstColorInt and 0xFF

                                        val blendedR = (finalR * srcAlpha + dstR * invSrcAlpha).toInt()
                                        val blendedG = (finalG * srcAlpha + dstG * invSrcAlpha).toInt()
                                        val blendedB = (finalB * srcAlpha + dstB * invSrcAlpha).toInt()

                                        val blendedColor = (0xFF shl 24) or (blendedR shl 16) or (blendedG shl 8) or blendedB
                                        msaaColorBuffer[idx] = blendedColor
                                    }
                                }
                            }
                        } else {
                            val finalColor = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                            for (s in 0 until samples) {
                                if ((coverageMask and (1 shl s)) != 0) {
                                    val sx = if (useMSAA) msaaOffsetsX[s] else 0.5
                                    val sy = if (useMSAA) msaaOffsetsY[s] else 0.5
                                    val sZ = interpolated_z_px + z_dx * sx + z_dy * sy
                                    val idx = (pixelIndex * 4) + s
                                    if (sZ < msaaDepthBuffer[idx]) {
                                        msaaDepthBuffer[idx] = sZ
                                        msaaColorBuffer[idx] = finalColor
                                    }
                                }
                            }
                        }
                    }
                }
                // Inkrementuj wartości dla następnego piksela w rzędzie
                alpha_px += alpha_dx
                beta_px += beta_dx
                interpolated_z_inv_prime_px += z_inv_prime_dx
                interpolated_u_prime_px += u_prime_dx
                interpolated_v_prime_px += v_prime_dx
                interpolated_world_x_prime_px += world_x_prime_dx
                interpolated_world_y_prime_px += world_y_prime_dx
                interpolated_world_z_prime_px += world_z_prime_dx
                interpolated_z_px += z_dx
            }
            // Przesuń wartości startowe do następnego wiersza
            alpha_row_start += B12 * invTotalArea
            beta_row_start += B20 * invTotalArea
        }
    }

    private fun processAndQueueRenderableFace(worldVerts: List<Vector3d>, texCoords: List<Vector3d>, color: Color, texture: Image?, lightGrid: Array<Array<Color>>?, giGrid: Array<Array<Color>>?, blushes: List<AABB>, blushContainerAABB: AABB?, combinedMatrix: Matrix4x4, viewMatrix: Matrix4x4, hasCollision: Boolean) {
        val clippedTriangles = clipTriangleAgainstNearPlane(worldVerts, texCoords, viewMatrix, 0.1)

        for ((clippedW, clippedUVs) in clippedTriangles) {
            val projectedVertices = mutableListOf<Vector3d>()
            val originalClipW = mutableListOf<Double>()
            for (vertex in clippedW) {
                val projectedHomogeneous = combinedMatrix.transformHomogeneous(vertex)
                val w = projectedHomogeneous.w
                if (w.isCloseToZero()) continue
                projectedVertices.add(Vector3d((projectedHomogeneous.x / w + 1) * virtualWidth / 2.0, (1 - projectedHomogeneous.y / w) * virtualHeight / 2.0, projectedHomogeneous.z / w))
                originalClipW.add(w)
            }
            if (projectedVertices.size == 3) {
                val isTransparent = isTextureTransparent(texture)
                val face = RenderableFace(projectedVertices, originalClipW, clippedUVs, color, false, texture, clippedW, lightGrid, blushes, blushContainerAABB, giGrid)
                face.hasCollision = hasCollision
                if (isTransparent) {
                    transparentRenderQueue.add(face)
                } else {
                    renderQueue.add(face)
                }
            }
        }
    }

    private fun isAabbOutsideFrustum(aabb: AABB, viewProjMatrix: Matrix4x4): Boolean {
        val corners = aabb.getCorners()
        val clipSpaceCorners = corners.map { viewProjMatrix.transformHomogeneous(it) }

        for (i in 0..5) {
            var allCornersOutside = true
            for (corner in clipSpaceCorners) {
                val isOutside = when (i) {
                    0 -> corner.x < -corner.w // Lewa
                    1 -> corner.x > corner.w  // Prawa
                    2 -> corner.y < -corner.w // Dół
                    3 -> corner.y > corner.w  // Góra
                    4 -> corner.z < -corner.w // Bliska //0
                    5 -> corner.z > corner.w  // Daleka
                    else -> false
                }
                if (!isOutside) {
                    allCornersOutside = false
                    break
                }
            }
            if (allCornersOutside) {
                return true
            }
        }
        return false
    }

    private fun colorToInt(c: Color): Int {
        val r = (c.red * 255).toInt() and 0xFF
        val g = (c.green * 255).toInt() and 0xFF
        val b = (c.blue * 255).toInt() and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun createColorTexture(color: Color): WritableImage {
        val image = WritableImage(8, 8)
        val pixelWriter = image.pixelWriter
        val colorInt = colorToInt(color)
        val pixels = IntArray(8 * 8) { colorInt }

        pixelWriter.setPixels(
            0, 0,
            8, 8,
            PixelFormat.getIntArgbInstance(),
            pixels,
            0,
            8
        )

        return image
    }

    private fun warmUpTextureCache() {
        println("Warming up texture cache...")
        val allKnownTextures = mutableSetOf<Image>()
        allKnownTextures.addAll(listOf(texBlackBricks, texBricks, texCeiling, texFloor, texSkybox))
        allKnownTextures.addAll(dynamicTextures.values)
        staticMeshes.forEach { mesh ->
            mesh.texture?.let { allKnownTextures.add(it) }
            allKnownTextures.addAll(mesh.faceTextures.values)
        }
        allKnownTextures.forEach {
            getTexturePixels(it)
            isTextureTransparent(it)
        }
        println("Texture cache warmed up for ${allKnownTextures.size} textures.")
    }

    private fun worldToGridCoords(worldPos: Vector3d): Vector3d {
        val offset = gridDimension / 2.0

        val gridX = (worldPos.x / cubeSize + offset).roundToInt()
        val gridY = (worldPos.y / cubeSize + offset).roundToInt()
        val gridZ = (worldPos.z / cubeSize + offset).roundToInt()

        return Vector3d(gridX.toDouble(), gridY.toDouble(), gridZ.toDouble())
    }

    fun loadImage(path: String): Image {
        val stream = DrawingPanel::class.java.classLoader.getResourceAsStream(path)
        val bufferedImage = ImageIO.read(stream)
        return SwingFXUtils.toFXImage(bufferedImage, null)
    }

    private fun getTexturePixels(texture: Image): IntArray? {
        textureCache[texture]?.let { return it }
        return try {
            val width = texture.width.toInt()
            val height = texture.height.toInt()
            if (width == 0 || height == 0) return null
            val pixels = IntArray(width * height)
            texture.pixelReader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width)
            textureCache.put(texture, pixels)
            textureDimensions.put(texture, width to height)
            pixels
        } catch (e: Exception) {
            null
        }
    }

    private fun isTextureTransparent(texture: Image?): Boolean {
        if (texture == null) return false
        return textureTransparencyCache.computeIfAbsent(texture) {
            val pixels = getTexturePixels(it)
            if (pixels != null) {
                for (pixel in pixels) {
                    if ((pixel ushr 24) and 0xFF < 255) return@computeIfAbsent true
                }
                return@computeIfAbsent false
            }
            val reader = it.pixelReader ?: return@computeIfAbsent false
            for (y in 0 until it.height.toInt()) {
                for (x in 0 until it.width.toInt()) {
                    if (reader.getColor(x, y).opacity < 1.0) return@computeIfAbsent true
                }
            }
            false
        }
    }

    private fun isTextureFullyTransparent(texture: Image?): Boolean {
        if (texture == null) return false
        val pixels = getTexturePixels(texture)
        if (pixels != null) {
            for (pixel in pixels) {
                if ((pixel ushr 24) and 0xFF >= 128) return false
            }
            return true
        }
        val reader = texture.pixelReader ?: return false
        for (y in 0 until texture.height.toInt()) {
            for (x in 0 until texture.width.toInt()) {
                if (reader.getColor(x, y).opacity >= 0.5) return false
            }
        }
        return true
    }

    private fun createTextureFromHexData(hexData: List<Int>): WritableImage {
        val width = 16
        val height = 16
        if (hexData.size != width * height) {
            println("Warning: Custom texture data has incorrect size. Expected ${width * height}, got ${hexData.size}")
            return createColorTexture(Color.MAGENTA)
        }
        val image = WritableImage(width, height)
        val pixelWriter = image.pixelWriter
        pixelWriter.setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), hexData.toIntArray(), 0, width)
        return image
    }
}