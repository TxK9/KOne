package io.nekohasekai.sfa.compose.screen.qrscan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.nekohasekai.sfa.selfuse.HomeLink
import io.nekohasekai.sfa.selfuse.PairedProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QRImageDecoderTest {
    private val link = "vless://00000000-0000-0000-0000-000000000001@node.invalid:443?security=tls&sni=example.org&type=tcp"

    private fun image(payload: String, inverted: Boolean): IntArray {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 480, 480, mapOf(EncodeHintType.MARGIN to 4))
        return IntArray(480 * 480) { index ->
            if (matrix[index % 480, index / 480] xor inverted) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }

    @Test fun nodeImageFeedsTheExistingPairedConfigurationBuilder() {
        val decoded = QRImageDecoder.decode(image(link, false), 480, 480)
        assertEquals(link, decoded)
        val config = JSONObject(PairedProfile.fromLink(decoded!!, HomeLink.Home(true, emptyList(), "phone")))
        assertEquals("tailscale-selfuse", config.getJSONArray("endpoints").getJSONObject(0).getString("state_directory"))
        PairedProfile.requirePaired(config.toString())
    }

    @Test fun lightOnDarkNodeQRCodeIsRecognized() {
        assertEquals(link, QRImageDecoder.decode(image(link, true), 480, 480))
    }

    @Test fun transparentPngBackgroundKeepsTheNodeReadable() {
        val pixels = image(link, false).map { if (it == 0xFFFFFFFF.toInt()) 0x00000000 else it }.toIntArray()
        assertEquals(link, QRImageDecoder.decode(pixels, 480, 480))
        assertEquals(0x00000000, pixels[0])
    }

    @Test fun blankPhotoDoesNotPretendToImport() {
        assertNull(QRImageDecoder.decode(IntArray(480 * 480) { 0xFFFFFFFF.toInt() }, 480, 480))
    }

    @Test fun photographedActivationCodeIsRecognizedAwayFromTheCenter() {
        val claim = "kone://claim?server=https%3A%2F%2Fconsole.example.org&code=TestInvite_0123456789"
        val code = image(claim, false)
        val width = 1920
        val height = 1440
        // A rotated screen photo, dim paper, and unrelated background texture.
        val photo = IntArray(width * height) { index ->
            val gray = 48 + ((index * 17L + index / width * 29L) % 42).toInt()
            0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
        }
        for (y in 0 until 480) for (x in 0 until 480) {
            photo[(y + 760) * width + x + 1220] = if (code[x * 480 + (479 - y)] == 0xFF000000.toInt()) 0xFF222222.toInt() else 0xFFCCCCCC.toInt()
        }
        assertEquals(claim, QRImageDecoder.decode(photo, width, height))
    }

    @Test fun unsupportedTransportExplainsTheLimitWithoutTheLink() {
        val failure = runCatching { PairedProfile.fromLink(link.replace("type=tcp", "type=xhttp"), HomeLink.Home(true, emptyList(), "phone")) }.exceptionOrNull() as Exception
        val message = QRImportErrors.controlledMessage(failure)
        assertTrue(message.contains("transport"))
        assertFalse(message.contains("node.invalid"))
        assertFalse(message.contains("vless://"))
    }

    @Test fun unexpectedDecoderErrorsCannotEchoCredentials() {
        assertEquals("The QR code contains invalid node or configuration parameters.", QRImportErrors.controlledMessage(IllegalArgumentException(link)))
    }
}
