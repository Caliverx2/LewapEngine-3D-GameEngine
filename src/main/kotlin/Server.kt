package org.lewapnoob.FileZeroServer

import javax.swing.*
import java.awt.FlowLayout
import java.awt.Color
import java.awt.Dimension
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

interface ServerStatusListener {
    fun onStatusChanged(status: ServerStatus)
}

enum class ServerStatus(val color: Color) {
    STOPPED(Color.BLACK),
    RUNNING(Color.GREEN),
    ERROR(Color.RED)
}

class ServerLogic(private val listener: ServerStatusListener) {
    private var serverSocket: DatagramSocket? = null
    private var running = false
    private val clients = ConcurrentHashMap<Pair<InetAddress, Int>, ClientInfo>()

    private data class ClientInfo(
        var lastSeen: Long = System.currentTimeMillis(),
        var lastPositionData: String = ""
    )

    fun start(port: Int) {
        if (running) return
        try {
            serverSocket = DatagramSocket(port)
            running = true
            listener.onStatusChanged(ServerStatus.RUNNING)
            println("Serwer uruchomiony na porcie $port")

            thread(isDaemon = true) { listen() }
            thread(isDaemon = true) { checkClientTimeouts() }
        } catch (e: Exception) {
            listener.onStatusChanged(ServerStatus.ERROR)
            e.printStackTrace()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        serverSocket?.close()
        clients.clear()
        listener.onStatusChanged(ServerStatus.STOPPED)
        println("Serwer zatrzymany")
    }

    private fun checkClientTimeouts() {
        while (running) {
            try {
                val now = System.currentTimeMillis()
                val clientsToRemove = mutableListOf<Pair<InetAddress, Int>>()

                clients.forEach { (clientAddress, clientInfo) ->
                    if (now - clientInfo.lastSeen > 2000) {
                        clientsToRemove.add(clientAddress)
                    }
                }

                clientsToRemove.forEach { clientAddress ->
                    clients.remove(clientAddress) ?: return@forEach
                    println("Klient ${clientAddress.first.hostAddress} rozłączony (timeout).")
                    val clientId = "${clientAddress.first.hostAddress}:${clientAddress.second}"
                    val disconnectMessage = "DISCONNECT,$clientId".toByteArray()

                    clients.forEach { (addr, _) ->
                        val packet = java.net.DatagramPacket(disconnectMessage, disconnectMessage.size, addr.first, addr.second)
                        serverSocket?.send(packet)
                    }
                }

                Thread.sleep(1000)
            } catch (e: Exception) {
                if (running) e.printStackTrace()
            }
        }
    }

    private fun listen() {
        val buffer = ByteArray(1024)
        while (running) {
            try {
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                serverSocket?.receive(packet) ?: continue

                val message = String(packet.data, 0, packet.length)
                val parts = message.split(',')
                if (parts.isEmpty()) continue

                val messageType = parts[0]
                val clientAddress = Pair(packet.address, packet.port)

                val clientId = "${clientAddress.first.hostAddress}:${clientAddress.second}"

                if (messageType == "POSITION" && parts.size == 5) {
                    val isNewClient = !clients.containsKey(clientAddress)

                    val clientInfo = clients.computeIfAbsent(clientAddress) {
                        println("Nowy klient połączony: $clientId")
                        ClientInfo()
                    }

                    val broadcastMessage = "POSITION,$clientId,${parts.slice(1..4).joinToString(",")}"

                    clientInfo.lastSeen = System.currentTimeMillis()
                    clientInfo.lastPositionData = broadcastMessage

                    if (isNewClient) {
                        clients.forEach { (otherClientAddress, otherClientInfo) ->
                            if (otherClientAddress != clientAddress && otherClientInfo.lastPositionData.isNotEmpty()) {
                                val positionData = otherClientInfo.lastPositionData.toByteArray()
                                val sendPacket = java.net.DatagramPacket(positionData, positionData.size, clientAddress.first, clientAddress.second)
                                serverSocket?.send(sendPacket)
                            }
                        }
                    }

                    val data = broadcastMessage.toByteArray()
                    clients.forEach { (otherClientAddress, _) ->
                        if (otherClientAddress != clientAddress) {
                            val sendPacket = java.net.DatagramPacket(data, data.size, otherClientAddress.first, otherClientAddress.second)
                            serverSocket?.send(sendPacket)
                        }
                    }
                } else if (messageType == "P") {
                    clients[clientAddress]?.lastSeen = System.currentTimeMillis()
                } else if (messageType == "PUSH" && parts.size == 5) {
                    val targetClientId = parts[1]
                    println("Odebrano PUSH dla klienta: $targetClientId od $clientId")

                    val pushX = parts[2]
                    val pushY = parts[3]
                    val pushZ = parts[4]

                    // Znajdź klienta docelowego po jego ID (IP:PORT)
                    val targetClient = clients.entries.find { "${it.key.first.hostAddress}:${it.key.second}" == targetClientId }

                    if (targetClient != null) {
                        val (targetAddr, targetPort) = targetClient.key
                        // Stwórz nową wiadomość dla popychanego klienta
                        val pushMessage = "PUSH_ME,$pushX,$pushY,$pushZ".toByteArray()
                        val sendPacket = java.net.DatagramPacket(pushMessage, pushMessage.size, targetAddr, targetPort)
                        serverSocket?.send(sendPacket)
                        println("Przekazano pchnięcie do $targetClientId")
                    }

                }

            } catch (e: Exception) {
                if (running) {
                    listener.onStatusChanged(ServerStatus.ERROR)
                    e.printStackTrace()
                }
            }
        }
    }
}

class Server : JFrame("Server"), ServerStatusListener {
    private val startButton = JButton("Start Server")
    private val stopButton = JButton("Stop Server")
    private val portField = JTextField("1050", 5)
    private val statusIndicator = JPanel()
    private val serverLogic = ServerLogic(this)

    init {
        statusIndicator.preferredSize = Dimension(15, 15)
        onStatusChanged(ServerStatus.STOPPED)
        iconImage = java.awt.Toolkit.getDefaultToolkit().getImage(Server::class.java.getResource("/icons/LewapEngineServer.png"))
        defaultCloseOperation = EXIT_ON_CLOSE
        layout = FlowLayout()
        add(JLabel("Port:"))
        add(portField)
        add(startButton)
        add(stopButton)
        add(JLabel("Status:"))
        add(statusIndicator)

        setSize(400, 75)
        setLocationRelativeTo(null)
        isVisible = true

        startButton.addActionListener {
            portField.text.toIntOrNull()?.let { port ->
                serverLogic.start(port)
            }
        }

        stopButton.addActionListener {
            serverLogic.stop()
        }
    }

    override fun onStatusChanged(status: ServerStatus) {
        SwingUtilities.invokeLater {
            statusIndicator.background = status.color
            startButton.isEnabled = status != ServerStatus.RUNNING
            stopButton.isEnabled = status == ServerStatus.RUNNING
        }
    }
}

fun main() {
    Server()
}