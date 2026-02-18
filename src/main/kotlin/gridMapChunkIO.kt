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
    val dayCounter: Int = 0,
    val localDimension: String = "overworld"
)

open class ChunkIO(worldName: String) {
    companion object {
        private const val SAVE_MAGIC_HEADER = 0x4C574150 // "LWAP" w HEX - unikalny identyfikator
        private const val CURRENT_VERSION = 2 // Podbijamy wersję dla nowego formatu

        // Typy danych do dynamicznego zapisu (Tag System)
        private const val TAG_END: Byte = 0
        private const val TAG_BOOLEAN: Byte = 1
        private const val TAG_INT: Byte = 2
        private const val TAG_DOUBLE: Byte = 3
        private const val TAG_STRING: Byte = 4
    }

    // Używamy scentralizowanego folderu gry zdefiniowanego w gridMapModAPI.kt
    val saveDir = File(gameDir, "saves/$worldName").apply { mkdirs() }

    open fun saveChunk(chunk: Chunk, dimension: String = "overworld") {
        try {
            val file = File(saveDir, "dimensions/$dimension/region/c_${chunk.x}_${chunk.z}.dat")
            file.parentFile?.mkdirs() // Upewniamy się, że folder regionu istnieje
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

    open fun loadChunk(cx: Int, cz: Int, dimension: String = "overworld"): Chunk? {
        val file = File(saveDir, "dimensions/$dimension/region/c_${cx}_${cz}.dat")
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
                dos.writeInt(SAVE_MAGIC_HEADER)
                dos.writeInt(CURRENT_VERSION)
                dos.writeInt(data.seed)
                dos.writeDouble(data.x)
                dos.writeDouble(data.y)
                dos.writeDouble(data.z)
                dos.writeDouble(data.yaw)
                dos.writeDouble(data.pitch)

                // Funkcja pomocnicza do zapisu pola z nagłówkiem (Typ + Nazwa)
                fun writeField(name: String, type: Byte, value: Any) {
                    dos.writeByte(type.toInt())
                    dos.writeUTF(name)
                    when (value) {
                        is Boolean -> dos.writeBoolean(value)
                        is Int -> dos.writeInt(value)
                        is Double -> dos.writeDouble(value)
                        is String -> dos.writeUTF(value)
                    }
                }

                // Zapisujemy pola w dowolnej kolejności. Każde pole jest "otagowane".
                writeField("debugNoclip", TAG_BOOLEAN, data.debugNoclip)
                writeField("debugFly", TAG_BOOLEAN, data.debugFly)
                writeField("debugFullbright", TAG_BOOLEAN, data.debugFullbright)
                writeField("showChunkBorders", TAG_BOOLEAN, data.showChunkBorders)
                writeField("debugXray", TAG_BOOLEAN, data.debugXray)
                writeField("gameTime", TAG_DOUBLE, data.gameTime)
                writeField("dayCounter", TAG_INT, data.dayCounter)
                writeField("localDimension", TAG_STRING, data.localDimension)

                dos.writeByte(TAG_END.toInt()) // Znacznik końca danych
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
                // Sprawdzamy czy plik ma nowy nagłówek
                val firstInt = dis.readInt()
                var seed = 0
                var version = 0

                if (firstInt == SAVE_MAGIC_HEADER) {
                    version = dis.readInt()
                    seed = dis.readInt()
                } else {
                    seed = firstInt // To był seed, stary format (Legacy)
                }

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
                var localDimension = "overworld"

                try {
                    if (version >= 2) {
                        // Nowy system: Czytamy tagi w pętli
                        while (true) {
                            // Czytamy typ danych. Jeśli plik się urwie, poleci EOFException i pętla się skończy (bezpiecznie)
                            val type = dis.readByte()
                            if (type == TAG_END) break // Koniec danych

                            val name = dis.readUTF()

                            // Wczytujemy wartość zależnie od zapisanego typu
                            // Dzięki temu, nawet jak nie znamy pola "name", wiemy ile bajtów przeczytać, żeby nie zgubić pozycji w pliku!
                            val value: Any = when (type) {
                                TAG_BOOLEAN -> dis.readBoolean()
                                TAG_INT -> dis.readInt()
                                TAG_DOUBLE -> dis.readDouble()
                                TAG_STRING -> dis.readUTF()
                                else -> throw IOException("Nieznany typ danych: $type")
                            }

                            // Przypisujemy do zmiennych tylko to, co znamy
                            when (name) {
                                "debugNoclip" -> debugNoclip = value as Boolean
                                "debugFly" -> debugFly = value as Boolean
                                "debugFullbright" -> debugFullbright = value as Boolean
                                "showChunkBorders" -> showChunkBorders = value as Boolean
                                "debugXray" -> debugXray = value as Boolean
                                "gameTime" -> gameTime = value as Double
                                "dayCounter" -> dayCounter = value as Int
                                "localDimension" -> localDimension = value as String
                            }
                        }
                    } else {
                        debugNoclip = dis.readBoolean()
                    }
                } catch (e: EOFException) {
                    // Koniec pliku - normalne zachowanie
                }

                WorldData(seed, x, y, z, yaw, pitch, debugNoclip, debugFly, debugFullbright, showChunkBorders, debugXray, gameTime, dayCounter, localDimension)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
}