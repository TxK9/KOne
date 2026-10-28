package io.nekohasekai.sfa.compose.screen.qrscan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.selfuse.PairedProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val config = JSONObject(PairedProfile.fromVlessLink(decoded!!))
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

    @Test fun unsupportedTransportExplainsTheLimitWithoutTheLink() {
        val failure = runCatching { PairedProfile.fromVlessLink(link.replace("type=tcp", "type=ws")) }.exceptionOrNull() as Exception
        assertEquals(ScanMessage.Resource(R.string.knine_qr_error_transport), QRImportErrors.controlledMessage(failure))
    }

    @Test fun unencryptedNodeNamesTheSupportedSecurity() {
        val failure = runCatching { PairedProfile.fromVlessLink(link.replace("security=tls", "security=none")) }.exceptionOrNull() as Exception
        assertEquals(ScanMessage.Resource(R.string.knine_qr_error_encryption), QRImportErrors.controlledMessage(failure))
    }

    @Test fun knownValidatorMessagesPassThroughWithoutTheLink() {
        val failure = runCatching { PairedProfile.fromVlessLink(link.replace("00000000-0000-0000-0000-000000000001", "not-a-uuid")) }.exceptionOrNull() as Exception
        val message = QRImportErrors.controlledMessage(failure) as ScanMessage.Literal
        assertFalse(message.text.contains("node.invalid"))
        assertFalse(message.text.contains("vless://"))
    }

    @Test fun unexpectedDecoderErrorsCannotEchoCredentials() {
        assertEquals(ScanMessage.Resource(R.string.knine_qr_error_parameters), QRImportErrors.controlledMessage(IllegalArgumentException(link)))
    }
}
