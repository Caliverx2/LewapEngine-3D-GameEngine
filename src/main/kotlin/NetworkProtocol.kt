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
     * Pakuje cały chunk używając RLE.
     * ZMIENIONA SYGNATURA: Dodano metadata: ByteArray
     */
    fun encodeChunk(cx: Int, cz: Int, blocks: IntArray, metadata: ByteArray): ByteBuffer {
        // FIX: Używamy allocateDirect dla lepszej wydajności natywnej i uniknięcia błędów JNI przy dużych pakietach.
        // Zmniejszono do ~150KB. 256KB to ryzykowna granica, która może zamykać kanał.
        // 150KB jest bezpieczną wartością, która mieści się w MTU i buforach.
        val buffer = ByteBuffer.allocateDirect(150000) 
        buffer.put(PACKET_CHUNK_DATA)
        buffer.putInt(cx)
        buffer.putInt(cz)

        // 1. Kompresja Bloków (Int)
        var i = 0
        val size = blocks.size
        while (i < size) {
            // Safety check: Zostawiamy miejsce na metadane (ok. 40KB marginesu)
            if (buffer.position() > 110000) break 
            
            val currentBlock = blocks[i]
            var count = 1
            while (i + count < size && blocks[i + count] == currentBlock && count < 255) {
                count++
            }
            buffer.put(count.toByte())
            buffer.putInt(currentBlock)
            i += count
        }

        // 2. Kompresja Metadanych (Byte)
        i = 0
        val metaSize = metadata.size
        while (i < metaSize) {
            if (buffer.position() > 149900) break // Safety check (zostawiamy minimalny margines)
            
            val currentMeta = metadata[i]
            var count = 1
            // Rzutowanie na Byte jest bezpieczne przy porównaniu
            while (i + count < metaSize && metadata[i + count] == currentMeta && count < 255) {
                count++
            }
            buffer.put(count.toByte())
            buffer.put(currentMeta) // Zapisujemy bajt metadanych
            i += count
        }

        buffer.flip()
        return buffer
    }

    /**
     * Pakuje dane świata: Seed, Czas, Dzień.
     */
    fun encodeWorldData(seed: Int, gameTime: Double, dayCounter: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(17) // 1 + 4 + 8 + 4
        buffer.put(PACKET_WORLD_DATA)
        buffer.putInt(seed)
        buffer.putDouble(gameTime)
        buffer.putInt(dayCounter)
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

    // --- DEKODERY (Odbieranie) ---

    data class DecodedPosition(val playerId: Byte, val x: Double, val y: Double, val z: Double, val yaw: Double, val pitch: Double)
    data class DecodedBlock(val x: Int, val y: Int, val z: Int, val color: Int, val metadata: Byte)
    data class DecodedChunk(val cx: Int, val cz: Int, val blocks: IntArray, val metadata: ByteArray)
    data class DecodedWorldData(val seed: Int, val gameTime: Double, val dayCounter: Int)
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

    fun decodeWorldData(buffer: ByteBuffer): DecodedWorldData {
        buffer.get() // Skip header
        val seed = buffer.int
        val gameTime = buffer.double
        val dayCounter = buffer.int
        return DecodedWorldData(seed, gameTime, dayCounter)
    }

    fun decodeChunkRequest(buffer: ByteBuffer): DecodedChunkRequest {
        buffer.get() // Skip header
        val cx = buffer.int
        val cz = buffer.int
        return DecodedChunkRequest(cx, cz)
    }
}