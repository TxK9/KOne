package io.nekohasekai.sfa.compose.component.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexedFrameBufferTest {
    @Test
    fun overwrittenSlotNeverMasqueradesAsEarlierFrame() {
        val buffer = IndexedFrameBuffer<String>(8)
        repeat(200) { buffer.put(it, "frame-$it") }
        assertNull(buffer[0])
        assertNull(buffer[31])
        assertEquals("frame-199", buffer[199])
        assertEquals(8, buffer.size)
        buffer.clear()
        assertEquals(0, buffer.size)
    }

    @Test
    fun movingPlaybackWindowCoversEveryFrameAcrossWraparound() {
        val buffer = IndexedFrameBuffer<String>(8)
        val observed = mutableSetOf<Int>()
        repeat(400) { step ->
            val current = step % 200
            repeat(8) { offset ->
                val index = (current + offset) % 200
                if (buffer[index] == null) buffer.put(index, "frame-$index")
            }
            assertEquals("frame-$current", buffer[current])
            observed += current
        }
        assertEquals((0 until 200).toSet(), observed)
    }
}
