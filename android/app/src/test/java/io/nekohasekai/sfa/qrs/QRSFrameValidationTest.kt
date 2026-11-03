package io.nekohasekai.sfa.qrs

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frames come straight from the camera, so every header field is untrusted.
 * These tests pin the rule that a malformed frame is skipped rather than
 * allowed to allocate, index outside the decoding state, or throw on the
 * analysis thread.
 */
class QRSFrameValidationTest {
    private fun frame(
        indices: IntArray,
        totalBlocks: Int,
        compressedSize: Int,
        checksum: Int = 0,
        data: ByteArray = ByteArray(8),
        degree: Int = indices.size,
    ): ByteArray = ByteBuffer.allocate(16 + 4 * indices.size + data.size).order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            putInt(degree)
            indices.forEach(::putInt)
            putInt(totalBlocks)
            putInt(compressedSize)
            putInt(checksum)
            put(data)
        }.array()

    private fun payload(block: LubyCodec.EncodedBlock): ByteArray = frame(
        indices = block.indices,
        totalBlocks = block.totalBlocks,
        compressedSize = block.compressedSize,
        checksum = block.checksum.toInt(),
        data = block.data,
    )

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun encodedFrames(content: ByteArray, sliceSize: Int): Sequence<ByteArray> {
        val compressed = deflate(content)
        return LubyCodec(sliceSize).encode(content, compressed, compressed.size).map(::payload)
    }

    @Test fun acceptsWellFormedFrame() {
        val block = QRSDecoder.parseFramePayload(frame(intArrayOf(2, 0), totalBlocks = 3, compressedSize = 20))
        assertNotNull(block)
        assertEquals(3, block!!.totalBlocks)
        assertArrayEquals(intArrayOf(2, 0), block.indices)
    }

    @Test(timeout = 1000)
    fun overflowingDegreeIsRejectedWithoutAllocating() {
        // 4 * 0x40000000 wraps to zero in Int arithmetic, which once let this
        // header pass the length check and request a gigantic index array.
        val payload = frame(intArrayOf(0), totalBlocks = 1, compressedSize = 8, degree = 0x40000000)
        assertNull(QRSDecoder.parseFramePayload(payload))
        assertNull(QRSDecoder().processFrame(payload))
    }

    @Test fun degreeLongerThanPayloadIsRejected() {
        assertNull(QRSDecoder.parseFramePayload(frame(intArrayOf(0), totalBlocks = 4, compressedSize = 30, degree = 3)))
    }

    @Test fun outOfRangeIndicesAreRejected() {
        assertNull(QRSDecoder.parseFramePayload(frame(intArrayOf(3), totalBlocks = 3, compressedSize = 20)))
        assertNull(QRSDecoder.parseFramePayload(frame(intArrayOf(-1), totalBlocks = 3, compressedSize = 20)))
    }

    @Test fun duplicateIndicesAreRejected() {
        assertNull(QRSDecoder.parseFramePayload(frame(intArrayOf(1, 1), totalBlocks = 3, compressedSize = 20)))
    }

    @Test fun implausibleBlockCountsAreRejected() {
        assertNull(QRSDecoder.parseFramePayload(frame(intArrayOf(0), totalBlocks = 0, compressedSize = 1)))
        assertNull(QRSDecoder.parseFramePayload(frame(intArrayOf(0), totalBlocks = -5, compressedSize = 1)))
        assertNull(
            QRSDecoder.parseFramePayload(
                frame(intArrayOf(0), totalBlocks = QRSConstants.MAX_DECODE_BLOCKS + 1, compressedSize = 8),
            ),
        )
        // Within the block limit, but the assembled buffer would exceed the import cap.
        assertNull(
            QRSDecoder.parseFramePayload(
                frame(intArrayOf(0), totalBlocks = 90_000, compressedSize = 1_500 * 89_999 + 1, data = ByteArray(1_500)),
            ),
        )
    }

    @Test fun compressedSizeMustFitTheFinalSlice() {
        assertNull(QRSDecoder.parseFramePayload(frame(intArrayOf(0), totalBlocks = 3, compressedSize = -1)))
        assertNull(QRSDecoder.parseFramePayload(frame(intArrayOf(0), totalBlocks = 3, compressedSize = 25)))
        assertNull(QRSDecoder.parseFramePayload(frame(intArrayOf(0), totalBlocks = 3, compressedSize = 16)))
        assertNotNull(QRSDecoder.parseFramePayload(frame(intArrayOf(0), totalBlocks = 3, compressedSize = 17)))
        assertNotNull(QRSDecoder.parseFramePayload(frame(intArrayOf(0), totalBlocks = 3, compressedSize = 24)))
    }

    @Test fun emptyDataIsRejected() {
        assertNull(QRSDecoder.parseFramePayload(frame(intArrayOf(0), totalBlocks = 1, compressedSize = 0, data = ByteArray(0))))
    }

    @Test fun frameWithForeignLayoutDoesNotCorruptTheCurrentDataset() {
        val decoder = QRSDecoder()
        val first = decoder.processFrame(frame(intArrayOf(0), totalBlocks = 3, compressedSize = 20, checksum = 7))
        assertEquals(3, first!!.totalBlocks)

        // Same checksum, different slice size and block count: once this
        // reached the codec and threw out of the camera analyzer.
        val foreign = frame(intArrayOf(4), totalBlocks = 5, compressedSize = 70, checksum = 7, data = ByteArray(16))
        val progress = decoder.processFrame(foreign)
        assertEquals(3, progress!!.totalBlocks)
        assertEquals(1, progress.decodedBlocks)
        assertFalse(progress.isComplete)
    }

    @Test fun multiBlockStreamRoundTrips() {
        val random = Random(42)
        val content = ByteArray(6_000) { random.nextInt(256).toByte() }
        val decoder = QRSDecoder()
        var result: QRSDecoder.DecodeProgress? = null
        for (payload in encodedFrames(content, sliceSize = 200).take(2_000)) {
            result = decoder.processFrame(payload)
            if (result?.isComplete == true) break
        }
        assertTrue(result!!.isComplete)
        assertNull(result.error)
        assertArrayEquals(content, result.data)
    }

    @Test fun streamSurvivesInterleavedGarbage() {
        val content = "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]}".repeat(40).toByteArray()
        val random = Random(7)
        val decoder = QRSDecoder()
        var result: QRSDecoder.DecodeProgress? = null
        for (payload in encodedFrames(content, sliceSize = 100).take(1_000)) {
            val garbage = ByteArray(random.nextInt(0, 160)) { random.nextInt(256).toByte() }
            decoder.processFrame(garbage)
            result = decoder.processFrame(payload)
            if (result?.isComplete == true) break
        }
        assertTrue(result!!.isComplete)
        assertArrayEquals(content, result.data)
    }

    @Test(timeout = 5000)
    fun decompressionBombIsRefused() {
        val content = ByteArray(QRSConstants.MAX_DECOMPRESSED_SIZE + 1)
        val compressed = deflate(content)
        val block = LubyCodec(compressed.size).encode(content, compressed, compressed.size).first()
        val result = QRSDecoder().processFrame(payload(block))
        assertTrue(result!!.isComplete)
        assertNotNull(result.error)
        assertNull(result.data)
    }
}
