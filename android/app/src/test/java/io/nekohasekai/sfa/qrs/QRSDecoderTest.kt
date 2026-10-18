package io.nekohasekai.sfa.qrs

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QRSDecoderTest {
    private val content = "{\"outbounds\":[{\"type\":\"direct\"}]}".toByteArray()

    private fun compress(dictionary: ByteArray? = null): ByteArray {
        val deflater = Deflater()
        return try {
            dictionary?.let(deflater::setDictionary)
            deflater.setInput(content)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(128)
            while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun singleFrame(payload: ByteArray): ByteArray {
        val checksum = CRC32().apply { update(content) }.value xor 1L
        return ByteBuffer.allocate(20 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1) // Degree.
            .putInt(0) // The sole block's index.
            .putInt(1) // Total blocks.
            .putInt(payload.size)
            .putInt(checksum.toInt())
            .put(payload)
            .array()
    }

    @Test(timeout = 1000)
    fun dictionaryCompressedFrameDoesNotHangTheScanner() {
        val result = QRSDecoder().processFrame(singleFrame(compress("outbounds type direct".toByteArray())))
        assertNotNull(result)
        assertTrue(result!!.isComplete)
        assertNotNull(result.error)
        assertNull(result.data)
    }

    @Test(timeout = 1000)
    fun truncatedCompressedFrameDoesNotImportPartialData() {
        val result = QRSDecoder().processFrame(singleFrame(compress().dropLast(4).toByteArray()))
        assertNotNull(result!!.error)
        assertNull(result.data)
    }

    @Test fun regularCompressedFrameStillImports() {
        val result = QRSDecoder().processFrame(singleFrame(compress()))
        assertNull(result!!.error)
        assertArrayEquals(content, result.data)
    }

    @Test fun uncompressedFrameStillImports() {
        val result = QRSDecoder().processFrame(singleFrame(content))
        assertNull(result!!.error)
        assertArrayEquals(content, result.data)
    }
}
