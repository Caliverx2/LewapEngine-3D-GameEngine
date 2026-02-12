package org.lewapnoob.gridMap

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.Locale.getDefault

/**
 * Leniwie inicjalizowany, globalny obiekt przechowujący ścieżkę do głównego folderu gry.
 * Używa standardowych lokalizacji dla różnych systemów operacyjnych, aby zapisy były zawsze w tym samym miejscu.
 */
internal val gameDir: File by lazy {
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
        }
        else -> {
            File(userHome, dottedName)
        }
    }
    path.apply { mkdirs() }
}

/**
 * Zwraca listę nazw światów (folderów) znajdujących się w katalogu zapisu gry.
 */
fun listWorlds(): List<String> {
    val savesDir = File(gameDir, "saves")
    if (!savesDir.exists() || !savesDir.isDirectory) {
        return emptyList()
    }
    return savesDir.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
}

data class WorldData(
    val seed: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Double,
    val pitch: Double,
    val debugNoclip: Boolean = false,
    val debugFly: Boolean = false,
    val debugFullbright: Boolean = false,
    val showChunkBorders: Boolean = false,
    val debugXray: Boolean = false,
    val gameTime: Double = 12.0,
    val dayCounter: Int = 0
)

open class ChunkIO(worldName: String) {
    // Używamy scentralizowanego folderu gry zdefiniowanego w gridMapModAPI.kt
    val saveDir = File(gameDir, "saves/$worldName").apply { mkdirs() }

    open fun saveChunk(chunk: Chunk) {
        try {
            val file = File(saveDir, "c_${chunk.x}_${chunk.z}.dat")
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { dos ->
                // 1. Liczymy ile bloków faktycznie istnieje (nie jest powietrzem/zerem)
                val blockCount = chunk.blocks.count { it != 0 }

                // 2. Zapisujemy tę ilość na początku pliku
                dos.writeInt(blockCount)

                // 3. Zapisujemy tylko istniejące bloki: Index (Short) + Kolor (Int) + Metadata (Byte)
                for (i in chunk.blocks.indices) {
                    if (chunk.blocks[i] != 0) {
                        dos.writeShort(i)
                        dos.writeInt(chunk.blocks[i])
                        dos.writeByte(chunk.metadata[i].toInt())
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    open fun loadChunk(cx: Int, cz: Int): Chunk? {
        val file = File(saveDir, "c_${cx}_${cz}.dat")
        if (!file.exists()) return null

        return try {
            val chunk = Chunk(cx, cz)
            val fileSize = file.length()
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { dis ->
                val count = dis.readInt()
                val isOldFormat = fileSize == (4 + count * 6).toLong()

                for (i in 0 until count) {
                    val index = dis.readShort().toInt() and 0xFFFF
                    val color = dis.readInt()
                    val meta = if (isOldFormat) 0.toByte() else dis.readByte()
                    if (index in chunk.blocks.indices) {
                        chunk.blocks[index] = color
                        chunk.metadata[index] = meta
                    }
                }
            }
            chunk.modified = false
            chunk
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    fun saveWorldData(data: WorldData) {
        try {
            val file = File(saveDir, "world.dat")
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { dos ->
                dos.writeInt(data.seed)
                dos.writeDouble(data.x)
                dos.writeDouble(data.y)
                dos.writeDouble(data.z)
                dos.writeDouble(data.yaw)
                dos.writeDouble(data.pitch)
                dos.writeBoolean(data.debugNoclip)
                dos.writeBoolean(data.debugFly)
                dos.writeBoolean(data.debugFullbright)
                dos.writeBoolean(data.showChunkBorders)
                dos.writeBoolean(data.debugXray)
                dos.writeDouble(data.gameTime)
                dos.writeInt(data.dayCounter)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun loadWorldData(): WorldData? {
        val file = File(saveDir, "world.dat")
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { dis ->
                val seed = dis.readInt()
                val x = dis.readDouble()
                val y = dis.readDouble()
                val z = dis.readDouble()
                val yaw = dis.readDouble()
                val pitch = dis.readDouble()

                var debugNoclip = false
                var debugFly = false
                var debugFullbright = false
                var showChunkBorders = false
                var debugXray = false
                var gameTime = 12.0
                var dayCounter = 0

                try {
                    debugNoclip = dis.readBoolean()
                    debugFly = dis.readBoolean()
                    debugFullbright = dis.readBoolean()
                    showChunkBorders = dis.readBoolean()
                    debugXray = dis.readBoolean()
                    gameTime = dis.readDouble()
                    dayCounter = dis.readInt()
                } catch (e: EOFException) {
                    // Ignorujemy, stary zapis bez flag debugowania lub czasu
                }

                WorldData(seed, x, y, z, yaw, pitch, debugNoclip, debugFly, debugFullbright, showChunkBorders, debugXray, gameTime, dayCounter)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
}