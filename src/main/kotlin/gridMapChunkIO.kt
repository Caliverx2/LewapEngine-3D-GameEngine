package org.lewapnoob.gridMap

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale.getDefault

/**
 * Leniwie inicjalizowany, globalny obiekt przechowujący ścieżkę do głównego folderu gry.
 * Używa standardowych lokalizacji dla różnych systemów operacyjnych, aby zapisy były zawsze w tym samym miejscu.
 */
internal val gameDir: File by lazy {
    val appName = "gridMap"
    val userHome = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase(getDefault())

    val path = when {
        os.contains("win") -> System.getenv("APPDATA")?.let { File(it, appName) } ?: File(userHome, ".$appName")
        os.contains("mac") -> File(userHome, "Library/Application Support/$appName")
        else -> File(userHome, ".$appName") // Linux, etc.
    }
    path.apply { mkdirs() }
}

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
}