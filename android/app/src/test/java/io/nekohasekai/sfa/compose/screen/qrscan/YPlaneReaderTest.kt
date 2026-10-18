package io.nekohasekai.sfa.compose.screen.qrscan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer

class YPlaneReaderTest {
    @Test fun packedPlaneStartsAtCurrentPositionWithoutConsumingOriginalBuffer() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99, 98, 1, 2, 3, 4))
        buffer.position(2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), YPlaneReader.read(buffer, 2, 2, 2, 1))
        assertEquals(2, buffer.position())
        assertEquals(6, buffer.limit())
    }

    @Test fun rowPaddingAndMissingFinalPaddingAreHandled() {
        val buffer = ByteBuffer.wrap(byteArrayOf(90, 1, 2, 3, 99, 99, 4, 5, 6))
        buffer.position(1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), YPlaneReader.read(buffer, 3, 2, 5, 1))
        assertEquals(1, buffer.position())
    }

    @Test fun interleavedPixelStrideAndCurrentPositionReadOnlyVisibleSamples() {
        val buffer = ByteBuffer.wrap(byteArrayOf(91, 92, 1, 80, 2, 80, 3, 99, 99, 4, 80, 5, 80, 6))
        buffer.position(2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), YPlaneReader.read(buffer.asReadOnlyBuffer(), 3, 2, 7, 2))
        assertEquals(2, buffer.position())
    }

    @Test fun directBufferLimitAndReusableStorageAreRespected() {
        val buffer = ByteBuffer.allocateDirect(16)
        buffer.put(byteArrayOf(90, 1, 2, 88, 3, 4, 77, 76))
        buffer.position(1)
        buffer.limit(6)
        val reusable = ByteArray(4)
        assertSame(reusable, YPlaneReader.read(buffer, 2, 2, 3, 1, reusable))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), reusable)
        assertEquals(1, buffer.position())
        assertEquals(6, buffer.limit())
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedVisiblePixelIsRejectedBeforeBufferReads() {
        YPlaneReader.read(ByteBuffer.wrap(byteArrayOf(1, 2, 99, 3)), 2, 2, 3, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingRowsAreRejected() {
        YPlaneReader.read(ByteBuffer.allocate(12), 3, 2, 4, 2)
    }
}
