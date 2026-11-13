package io.nekohasekai.sfa.qrs

object QRSConstants {
    const val OFFICIAL_URL_PREFIX = "https://qrss.netlify.app/#"

    const val DEFAULT_FRAME_COUNT = 200
    const val BITMAP_BUFFER_SIZE = 8
    const val RECOVERY_FACTOR = 1.3

    // FPS settings
    const val DEFAULT_FPS = 10
    const val MIN_FPS = 1
    const val MAX_FPS = 60

    // Slice Size settings
    const val DEFAULT_SLICE_SIZE = 500
    const val MIN_SLICE_SIZE = 100
    const val MAX_SLICE_SIZE = 1500

    // Bounds for frames received from a scanner. Every header field is untrusted
    // camera input, so a malformed frame must be rejected before it can size an
    // allocation or index into the decoding state.
    const val MAX_DECODE_BLOCKS = 100_000
    const val MAX_DECODE_DEGREE = 1_000
    const val MAX_ASSEMBLED_SIZE = 16 * 1024 * 1024
    const val MAX_DECOMPRESSED_SIZE = 16 * 1024 * 1024

    // A sender searches at most this many blocks per source block for a decodable prefix.
    const val MAX_LOOP_BLOCKS_PER_SOURCE = 50

    fun blockCount(compressedSize: Int, sliceSize: Int): Int = (compressedSize + sliceSize - 1) / sliceSize

    /**
     * Frames shown in one loop for [k] source blocks. The loop repeats a fixed set of frames, so
     * the set must contain the [decodablePrefix] frames a receiver needs; a few more let a
     * receiver that missed a frame finish without waiting for the next pass.
     */
    fun calculateRequiredFrames(k: Int, decodablePrefix: Int): Int {
        if (k <= 0) return 1
        return maxOf((k * RECOVERY_FACTOR).toInt(), k + 5, decodablePrefix + maxOf(2, k / 10))
    }
}
