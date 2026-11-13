package io.nekohasekai.sfa.qrs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LubyLoopTest {
    private val slice = 4

    private fun loop(k: Int): Pair<ByteArray, List<LubyCodec.EncodedBlock>> {
        val data = Random(k).nextBytes(k * slice - k % slice)
        val prefix = LubyCodec.leadingBlocksToDecode(k, k * QRSConstants.MAX_LOOP_BLOCKS_PER_SOURCE + 100)
        assertNotNull("k=$k", prefix)
        val count = QRSConstants.calculateRequiredFrames(k, prefix!!)
        return data to LubyCodec(slice).encode(data, data, data.size).take(count).toList()
    }

    private fun decode(data: ByteArray, frames: List<LubyCodec.EncodedBlock>): ByteArray? {
        val codec = LubyCodec(slice)
        val state = codec.createDecodingState(frames.first())
        for (frame in frames) {
            if (codec.processBlock(state, frame)) return codec.assembleData(state).copyOf(data.size)
        }
        return null
    }

    @Test
    fun previousLoopSizeCannotDecodeEveryBlockCount() {
        // Eight blocks used to loop max(8 * 1.3, 8 + 5) = 13 frames, which never decode.
        val prefix = LubyCodec.leadingBlocksToDecode(8, 1000)!!
        assertTrue("prefix $prefix", prefix > 13)
    }

    @Test
    fun everyLoopDecodesInAnyOrder() {
        for (k in 1..200) {
            val (data, frames) = loop(k)
            repeat(3) { attempt ->
                val random = Random(k * 31 + attempt)
                val received = frames.shuffled(random).toMutableList()
                // A scanner sees some frames more than once while the loop repeats.
                repeat(received.size / 4) { received.add(random.nextInt(received.size), received.random(random)) }
                assertArrayEquals("k=$k attempt=$attempt", data, decode(data, received))
            }
        }
    }

    @Test
    fun loopKeepsTheRecoveryMargin() {
        assertEquals(1, QRSConstants.calculateRequiredFrames(0, 0))
        assertEquals(15, QRSConstants.calculateRequiredFrames(10, 1))
        assertEquals(130, QRSConstants.calculateRequiredFrames(100, 100))
        assertEquals(160, QRSConstants.calculateRequiredFrames(100, 150))
    }

    @Test
    fun decodablePrefixIgnoresData() {
        assertEquals(0, LubyCodec.leadingBlocksToDecode(0, 10))
        assertEquals(1, LubyCodec.leadingBlocksToDecode(1, 10))
        assertEquals(null, LubyCodec.leadingBlocksToDecode(50, 10))
    }
}
