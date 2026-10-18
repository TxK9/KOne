package io.nekohasekai.sfa.compose.screen.qrscan

import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QRCodeDecodeAttemptTest {
    @Test fun checksumAndFormatMissesDoNotAbortTheNextDecodeAttempt() {
        var resets = 0
        val checksum = QRCodeDecodeAttempt.run<String>({ throw ChecksumException.getChecksumInstance() }, { resets++ })
        val format = QRCodeDecodeAttempt.run<String>({ throw FormatException.getFormatInstance() }, { resets++ })
        val nextThreshold = QRCodeDecodeAttempt.run({ "decoded by fallback" }, { resets++ })
        assertNull(checksum)
        assertNull(format)
        assertEquals("decoded by fallback", nextThreshold)
        assertEquals(3, resets)
    }

    @Test fun notFoundMissAlsoResetsDecoder() {
        var resets = 0
        assertNull(QRCodeDecodeAttempt.run<String>({ throw NotFoundException.getNotFoundInstance() }, { resets++ }))
        assertEquals(1, resets)
    }

    @Test(expected = IllegalStateException::class)
    fun unexpectedDecoderFailureRemainsObservable() {
        QRCodeDecodeAttempt.run<String>({ throw IllegalStateException("decoder failure") }, {})
    }
}
