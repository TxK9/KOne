package io.nekohasekai.sfa.compose.screen.qrscan

import com.google.zxing.ReaderException

internal object QRCodeDecodeAttempt {
    fun <T> run(decode: () -> T, reset: () -> Unit): T? = try {
        decode()
    } catch (_: ReaderException) {
        // NotFound, Format and Checksum are all misses for this one threshold
        // attempt. None may prevent the remaining crop/invert/global attempts.
        null
    } finally {
        reset()
    }
}
