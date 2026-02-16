package org.lewapnoob.gridMap

import java.nio.ByteBuffer
import kotlin.math.PI

/**
 * Wysokowydajny protokół binarny.
 * Little Endian jest zazwyczaj szybszy na procesorach x86/ARM (native order).
 */
object NetworkProtocol {

    // Nagłówki pakietów
    const val PACKET_PLAYER_POS: Byte = 0x01
    const val PACKET_BLOCK_SET: Byte = 0x02
    const val PACKET_CHUNK_DATA: Byte = 0x03
    const val PACKET_WORLD_DATA: Byte = 0x04
    const val PACKET_CHUNK_REQUEST: Byte = 0x05
    const val PACKET_PLAYER_LIST: Byte = 0x06
    const val PACKET_KEEP_ALIVE: Byte = 0x07
    const val PACKET_DISCONNECT: Byte = 0x08
    const val PACKET_CHUNK_SECTION: Byte = 0x09

    // --- HELPERY (Dla wygody) ---
    fun ByteBuffer.putBool(value: Boolean) = this.put(if (value) 1.toByte() else 0.toByte())
    fun ByteBuffer.getBool(): Boolean = this.get() == 1.toByte()

    // --- World Data ---
    /**
     * HOST
     */
    fun encodeWorldData(game: gridMap, targetPlayerId: Byte): ByteBuffer {
        val estimatedSize = 64 + (game.oreColors.size * 4)
        val buffer = ByteBuffer.allocate(estimatedSize)
        buffer.put(PACKET_WORLD_DATA)

        // --- LISTA ZMIENNYCH ---
        buffer.putInt(game.seed)
        buffer.putDouble(game.gameTime)
        buffer.putInt(game.dayCounter)
        buffer.put(targetPlayerId)
        buffer.putBool(game.gameFrozen)
        buffer.putInt(game.oreColors.size) // Zapisujemy liczbę elementów
        for (color in game.oreColors) {
            buffer.putInt(color)           // Zapisujemy każdy kolor jako Int
        }

        buffer.flip()
        return buffer
    }

    /**
     * CLIENT
     */
    fun decodeWorldData(buffer: ByteBuffer, game: gridMap) {
        buffer.get() // Skip header

        val newSeed = buffer.int
        if (game.seed != newSeed) {
            game.seed = newSeed
        }

        val serverTime = buffer.double
        if (kotlin.math.abs(game.gameTime - serverTime) > 0.5) game.gameTime = serverTime

        game.dayCounter = buffer.int

        val assignedId = if (buffer.hasRemaining()) buffer.get() else 0
        if (assignedId != 0.toByte()) game.myPlayerId = assignedId.toString()

        game.gameFrozen = if (buffer.hasRemaining()) buffer.getBool() else false

        // Deserializacja oreColors:
        if (buffer.hasRemaining()) {
            val colorCount = buffer.int
            val newColors = mutableListOf<Int>()
            for (i in 0 until colorCount) {
                newColors.add(buffer.int)
            }

            // Logika aktualizacji: Czyścimy i dodajemy nowe, jeśli zbiór się zmienił
            // (Własna implementacja porównania zbiorów byłaby tu wskazana)
            if (game.oreColors.size != newColors.size || !game.oreColors.containsAll(newColors)) {
                game.oreColors.clear()
                game.oreColors.addAll(newColors)
            }
        }
    }


    // --- ENKODERY (Wysyłanie) ---
    /**
     * Pakuje pozycję gracza do 16 bajtów (1 Header + 1 ID + 12 XYZ + 2 Rot).
     * Oryginalnie: ~50 bajtów (z ID String i Double).
     */
    fun encodePlayerPosition(playerId: Byte, x: Double, y: Double, z: Double, yaw: Double, pitch: Double): ByteBuffer {
        val buffer = ByteBuffer.allocate(16)
        buffer.put(PACKET_PLAYER_POS)
        buffer.put(playerId)

        // Konwersja Double -> Float (4 bajty vs 8 bajtów)
        buffer.putFloat(x.toFloat())
        buffer.putFloat(y.toFloat())
        buffer.putFloat(z.toFloat())

        // Kompresja kątów do 1 bajta (0-255)
        // Yaw: normalizacja do 0..2PI -> 0..255
        val yawNorm = (yaw % (2 * PI))
        val yawByte = ((if (yawNorm < 0) yawNorm + 2 * PI else yawNorm) / (2 * PI) * 255).toInt().toByte()

        // Pitch: zakres -PI/2..PI/2 -> 0..255
        val pitchByte = (((pitch + (PI / 2)) / PI) * 255).toInt().coerceIn(0, 255).toByte()

        buffer.put(yawByte)
        buffer.put(pitchByte)

        buffer.flip() // Gotowy do wysłania
        return buffer
    }

    /**
     * Pakuje zmianę bloku do 14 bajtów (1 Header + 8 XZ + 1 Y + 4 Color).
     */
    fun encodeBlockChange(x: Int, y: Int, z: Int, color: Int, metadata: Byte): ByteBuffer {
        val buffer = ByteBuffer.allocate(15)
        buffer.put(PACKET_BLOCK_SET)
        buffer.putInt(x)
        buffer.putInt(z)
        buffer.put(y.toByte()) // Y mieści się w 0-127
        buffer.putInt(color)
        buffer.put(metadata)

        buffer.flip()
        return buffer
    }

    /**
     * Pakuje sekcję chunka (16x16x16) używając RLE.
     * Dzieli chunk na 8 części w pionie (dla wysokości 128).
     */
    fun encodeChunkSection(cx: Int, cz: Int, sectionY: Int, chunkBlocks: IntArray, chunkMeta: ByteArray): ByteBuffer {
        // Sekcja 16x16x16 = 4096 bloków.
        // Max rozmiar bez kompresji: 4096 * 5 (blok) + 4096 * 2 (meta) ~ 28KB.
        // Alokujemy 64KB dla bezpieczeństwa.
        val buffer = ByteBuffer.allocateDirect(65536)
        buffer.put(PACKET_CHUNK_SECTION)
        buffer.putInt(cx)
        buffer.putInt(cz)
        buffer.put(sectionY.toByte())

        val sectionSize = 16 * 16 * 16
        val startIdx = sectionY * sectionSize
        val endIdx = startIdx + sectionSize

        // 1. Kompresja Bloków (Int)
        var i = startIdx
        while (i < endIdx) {
            if (buffer.position() > 60000) break // Safety check
            val currentBlock = chunkBlocks[i]
            var count = 1
            while (i + count < endIdx && chunkBlocks[i + count] == currentBlock && count < 255) {
                count++
            }
            buffer.put(count.toByte())
            buffer.putInt(currentBlock)
            i += count
        }

        // 2. Kompresja Metadanych (Byte)
        i = startIdx
        while (i < endIdx) {
            if (buffer.position() > 65000) break // Safety check
            val currentMeta = chunkMeta[i]
            var count = 1
            while (i + count < endIdx && chunkMeta[i + count] == currentMeta && count < 255) {
                count++
            }
            buffer.put(count.toByte())
            buffer.put(currentMeta)
            i += count
        }

        buffer.flip()
        return buffer
    }

    /**
     * Klient prosi o chunk: [Header][CX][CZ]
     */
    fun encodeChunkRequest(cx: Int, cz: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(9)
        buffer.put(PACKET_CHUNK_REQUEST)
        buffer.putInt(cx)
        buffer.putInt(cz)
        buffer.flip()
        return buffer
    }

    /**
     * Pakuje listę graczy: [Header][Count][ID, X, Y, Z, Yaw, Pitch]...
     */
    fun encodePlayerList(players: Map<Byte, RemotePlayer>): ByteBuffer {
        // 1 (Header) + 1 (Count) + N * 15 (Player Data)
        val buffer = ByteBuffer.allocate(2 + players.size * 15)
        buffer.put(PACKET_PLAYER_LIST)
        buffer.put(players.size.toByte())

        players.forEach { (id, p) ->
            buffer.put(id)
            buffer.putFloat(p.x.toFloat())
            buffer.putFloat(p.y.toFloat())
            buffer.putFloat(p.z.toFloat())
            
            val yawNorm = (p.yaw % (2 * PI))
            val yawByte = ((if (yawNorm < 0) yawNorm + 2 * PI else yawNorm) / (2 * PI) * 255).toInt().toByte()
            val pitchByte = (((p.pitch + (PI / 2)) / PI) * 255).toInt().coerceIn(0, 255).toByte()
            
            buffer.put(yawByte)
            buffer.put(pitchByte)
        }
        buffer.flip()
        return buffer
    }

    fun encodeSimpleSignal(type: Byte): ByteBuffer {
        val buffer = ByteBuffer.allocate(1)
        buffer.put(type)
        buffer.flip()
        return buffer
    }

    fun encodeKeepAlive() = encodeSimpleSignal(PACKET_KEEP_ALIVE)
    fun encodeDisconnect() = encodeSimpleSignal(PACKET_DISCONNECT)

    // --- DEKODERY (Odbieranie) ---

    data class DecodedPosition(val playerId: Byte, val x: Double, val y: Double, val z: Double, val yaw: Double, val pitch: Double)
    data class DecodedBlock(val x: Int, val y: Int, val z: Int, val color: Int, val metadata: Byte)
    data class DecodedChunk(val cx: Int, val cz: Int, val blocks: IntArray, val metadata: ByteArray)
    data class DecodedChunkSection(val cx: Int, val cz: Int, val sectionY: Int, val blocks: IntArray, val metadata: ByteArray)
    data class DecodedChunkRequest(val cx: Int, val cz: Int)

    fun decodePacketType(buffer: ByteBuffer): Byte {
        if (buffer.remaining() == 0) return 0
        return buffer.get(0) // Podglądamy pierwszy bajt bez przesuwania pozycji (chyba że chcemy przesunąć)
    }

    fun decodePlayerPosition(buffer: ByteBuffer): DecodedPosition {
        buffer.get() // Skip header (zakładamy, że już sprawdziliśmy typ)
        val pid = buffer.get()

        val x = buffer.float.toDouble()
        val y = buffer.float.toDouble()
        val z = buffer.float.toDouble()

        // Dekompresja kątów
        val yawByte = buffer.get().toUByte().toInt()
        val pitchByte = buffer.get().toUByte().toInt()

        val yaw = (yawByte / 255.0) * (2 * PI)
        val pitch = (pitchByte / 255.0) * PI - (PI / 2)

        return DecodedPosition(pid, x, y, z, yaw, pitch)
    }

    fun decodeBlockChange(buffer: ByteBuffer): DecodedBlock {
        buffer.get() // Skip header
        val x = buffer.int
        val z = buffer.int
        val y = buffer.get().toInt() and 0xFF // Konwersja unsigned byte do int
        val color = buffer.int
        val metadata = if (buffer.hasRemaining()) buffer.get() else 0

        return DecodedBlock(x, y, z, color, metadata)
    }

    fun decodeChunk(buffer: ByteBuffer): DecodedChunk {
        buffer.get() // Skip header
        val cx = buffer.int
        val cz = buffer.int
        
        val totalSize = 16 * 128 * 16
        val blocks = IntArray(totalSize)
        val metadata = ByteArray(totalSize)
        
        // 1. Dekodowanie Bloków
        var index = 0
        while (buffer.hasRemaining() && index < totalSize) {
            // Zabezpieczenie: Musimy mieć co najmniej 5 bajtów (1 byte count + 4 bytes int)
            if (buffer.remaining() < 5) break
            
            val count = buffer.get().toInt() and 0xFF // Unsigned byte
            val color = buffer.int
            
            for (i in 0 until count) {
                if (index < totalSize) blocks[index++] = color
            }
        }
        
        // 2. Dekodowanie Metadanych
        // Jeśli bufor ma jeszcze dane, to są to metadane. Jeśli nie (stary serwer?), zostaną zera.
        index = 0
        while (buffer.hasRemaining() && index < totalSize) {
            // Zabezpieczenie: Musimy mieć co najmniej 2 bajty (1 byte count + 1 byte meta)
            if (buffer.remaining() < 2) break
            
            val count = buffer.get().toInt() and 0xFF
            val meta = buffer.get()
            
            for (i in 0 until count) {
                if (index < totalSize) metadata[index++] = meta
            }
        }
        
        return DecodedChunk(cx, cz, blocks, metadata)
    }

    fun decodeChunkSection(buffer: ByteBuffer): DecodedChunkSection {
        buffer.get() // Skip header
        val cx = buffer.int
        val cz = buffer.int
        val sectionY = buffer.get().toInt() and 0xFF

        val sectionSize = 16 * 16 * 16
        val blocks = IntArray(sectionSize)
        val metadata = ByteArray(sectionSize)

        // 1. Dekodowanie Bloków
        var index = 0
        while (buffer.hasRemaining() && index < sectionSize) {
            if (buffer.remaining() < 5) break
            val count = buffer.get().toInt() and 0xFF
            val color = buffer.int
            for (k in 0 until count) {
                if (index < sectionSize) blocks[index++] = color
            }
        }

        // 2. Dekodowanie Metadanych
        index = 0
        while (buffer.hasRemaining() && index < sectionSize) {
            if (buffer.remaining() < 2) break
            val count = buffer.get().toInt() and 0xFF
            val meta = buffer.get()
            for (k in 0 until count) {
                if (index < sectionSize) metadata[index++] = meta
            }
        }
        return DecodedChunkSection(cx, cz, sectionY, blocks, metadata)
    }

    fun decodeChunkRequest(buffer: ByteBuffer): DecodedChunkRequest {
        buffer.get() // Skip header
        val cx = buffer.int
        val cz = buffer.int
        return DecodedChunkRequest(cx, cz)
    }

    fun decodePlayerList(buffer: ByteBuffer): Map<Byte, RemotePlayer> {
        buffer.get() // Skip header
        val count = buffer.get().toInt() and 0xFF
        val map = HashMap<Byte, RemotePlayer>()

        for (i in 0 until count) {
            val id = buffer.get()
            val x = buffer.float.toDouble()
            val y = buffer.float.toDouble()
            val z = buffer.float.toDouble()
            val yawByte = buffer.get().toUByte().toInt()
            val pitchByte = buffer.get().toUByte().toInt()

            val yaw = (yawByte / 255.0) * (2 * PI)
            val pitch = (pitchByte / 255.0) * PI - (PI / 2)

            map[id] = RemotePlayer(x, y, z, yaw, pitch, System.currentTimeMillis(), System.currentTimeMillis())
        }
        return map
    }
}