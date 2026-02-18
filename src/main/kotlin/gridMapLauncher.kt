package org.lewapnoob.gridMap

import java.awt.*
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.swing.*

// Logika katalogu gry (wspólna, ale tutaj jako osobna aplikacja)
private val launcherGameDir: File by lazy {
    val appName = "gridMap"
    val dottedName = ".$appName"

    val userHome = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase(java.util.Locale.ROOT)

    val path = when {
        os.contains("win") -> {
            val appData = System.getenv("APPDATA")
            if (appData != null) File(appData, dottedName) else File(userHome, dottedName)
        }
        os.contains("mac") -> {
            File(userHome, "Library/Application Support/$dottedName")
        } else -> {
            File(userHome, dottedName)
        }
    }
    path.apply { mkdirs() }
}

class GridMapLauncher : JFrame("KapeLuż Launcher") {
    private val versionsDir = File(launcherGameDir, "versions").apply { mkdirs() }
    private val defaultJvmArgs = "-Xmx1024m -Xms512m -XX:+UseZGC -XX:+ZGenerational"

    // --- KONFIGURACJA ZDALNYCH WERSJI ---
    private val remoteVersions = mapOf(
        "FileZero.jar" to "https://drive.usercontent.google.com/download?id=1W7ngxc0Pa9jHxH_Gadtqk6WdPKoCeP_2", // Przykładowy link
        "gridMap-v0.4.jar" to "https://lewapnoob.ddns.net/files/gridMap-v0.4.jar"
    )

    private val versionComboBox: JComboBox<String>
    private val argsField: JTextField
    private val launchButton: JButton
    private val progressBar: JProgressBar

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(500, 350)
        setLocationRelativeTo(null)
        layout = GridBagLayout()
        isResizable = false

        val gbc = GridBagConstraints()
        gbc.insets = Insets(10, 10, 10, 10)
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.gridx = 0

        // --- Title ---
        val titleLabel = JLabel("KapeLuż Launcher", SwingConstants.CENTER)
        titleLabel.font = Font("Arial", Font.BOLD, 24)
        gbc.gridy = 0
        add(titleLabel, gbc)

        // --- Version Selection ---
        gbc.gridy = 1
        add(JLabel("Select Version:"), gbc)

        // 1. Pobierz lokalne pliki
        val localFiles = versionsDir.listFiles { _, name -> name.endsWith(".jar") }
            ?.map { it.name }
            ?.toSet() ?: emptySet()

        // 2. Połącz z wersjami zdalnymi (unikając duplikatów)
        val allVersions = (localFiles + remoteVersions.keys).sortedDescending()

        val displayVersions = allVersions.map { it.removeSuffix(".jar") }
        versionComboBox = JComboBox(displayVersions.toTypedArray())
        
        // Obsługa zmiany wyboru (aktualizacja tekstu przycisku)
        versionComboBox.addActionListener {
            updateLaunchButtonState()
        }

        if (allVersions.isEmpty()) {
            versionComboBox.addItem("No versions found")
            versionComboBox.isEnabled = false
        }
        gbc.gridy = 2
        add(versionComboBox, gbc)

        // --- JVM Arguments ---
        gbc.gridy = 3
        add(JLabel("JVM Arguments:"), gbc)

        argsField = JTextField(defaultJvmArgs)
        gbc.gridy = 4
        add(argsField, gbc)

        // --- Reset Defaults Button ---
        val resetButton = JButton("Reset Defaults")
        resetButton.addActionListener {
            argsField.text = defaultJvmArgs
        }
        gbc.gridy = 5
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.EAST
        add(resetButton, gbc)

        // --- Launch Button ---
        launchButton = JButton("LAUNCH GAME")
        launchButton.font = Font("Arial", Font.BOLD, 16)
        launchButton.background = Color(100, 200, 100)
        launchButton.isEnabled = allVersions.isNotEmpty()

        launchButton.addActionListener {
            launchGame()
        }

        gbc.gridy = 6
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.CENTER
        gbc.ipady = 20
        add(launchButton, gbc)

        // --- Progress Bar ---
        progressBar = JProgressBar(0, 100)
        progressBar.isStringPainted = true
        progressBar.isVisible = false
        gbc.gridy = 7
        gbc.ipady = 0
        add(progressBar, gbc)

        // Info label location
        val pathLabel = JLabel("Game path: ${launcherGameDir.absolutePath}")
        pathLabel.font = Font("Consolas", Font.PLAIN, 10)
        pathLabel.foreground = Color.GRAY
        gbc.gridy = 8
        gbc.ipady = 0
        add(pathLabel, gbc)

        // Inicjalny stan przycisku
        updateLaunchButtonState()
    }

    private fun updateLaunchButtonState() {
        val selectedVersion = versionComboBox.selectedItem as? String ?: return
        val jarFile = File(versionsDir, "$selectedVersion.jar")

        if (jarFile.exists()) {
            launchButton.text = "LAUNCH GAME"
            launchButton.background = Color(100, 200, 100) // Zielony
        } else {
            launchButton.text = "DOWNLOAD & PLAY"
            launchButton.background = Color(100, 150, 255) // Niebieski
        }
    }

    private fun launchGame() {
        val selectedVersion = versionComboBox.selectedItem as? String ?: return
        val fileName = "$selectedVersion.jar"
        val jarFile = File(versionsDir, fileName)

        if (!jarFile.exists()) {
            // Jeśli pliku nie ma, spróbuj pobrać
            val url = remoteVersions[fileName]
            if (url != null) {
                downloadAndLaunch(url, jarFile)
            } else {
                JOptionPane.showMessageDialog(this, "File not found locally and no download URL defined for: $fileName", "Error", JOptionPane.ERROR_MESSAGE)
            }
            return
        }

        runJar(jarFile)
    }

    private fun downloadAndLaunch(urlString: String, destination: File) {
        launchButton.isEnabled = false
        versionComboBox.isEnabled = false
        progressBar.isVisible = true
        progressBar.value = 0
        progressBar.string = "Connecting..."

        // Pobieranie w osobnym wątku, żeby nie zamrozić UI
        Thread {
            try {
                val url = URL(urlString)
                val connection = url.openConnection()
                val fileSize = connection.contentLengthLong

                connection.getInputStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var totalBytesRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (fileSize > 0) {
                                val percent = (totalBytesRead * 100 / fileSize).toInt()
                                SwingUtilities.invokeLater {
                                    progressBar.value = percent
                                    progressBar.string = "Downloading... $percent%"
                                }
                            }
                        }
                    }
                }
                SwingUtilities.invokeLater {
                    progressBar.string = "Download Complete!"
                    runJar(destination)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(this, "Download failed: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
                    progressBar.isVisible = false
                    launchButton.isEnabled = true
                    versionComboBox.isEnabled = true
                    // Usuń uszkodzony plik
                    if (destination.exists()) destination.delete()
                }
            }
        }.start()
    }

    private fun runJar(jarFile: File) {
        val javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java" +
                if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""

        val args = argsField.text.split(" ").filter { it.isNotBlank() }

        val command = ArrayList<String>()
        command.add(javaBin)
        command.addAll(args)
        command.add("-jar")
        command.add(jarFile.absolutePath)

        println("Launching: $command")

        val pb = ProcessBuilder(command)
        pb.inheritIO() // Przekieruj wyjście gry do konsoli launchera (jeśli uruchomiony z konsoli)
        pb.start()
        System.exit(0) // Zamknij launcher po uruchomieniu gry
    }
}

fun main() {
    SwingUtilities.invokeLater { GridMapLauncher().isVisible = true }
}