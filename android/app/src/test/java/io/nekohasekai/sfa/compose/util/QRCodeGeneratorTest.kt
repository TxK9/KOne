package io.nekohasekai.sfa.compose.util

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QRCodeGeneratorTest {
    @Test
    fun opaqueBlackWhiteCodeRoundTripsThroughScanner() {
        val content = "Knine QR readability check"
        val code = QRCodeGenerator.encodePixels(content)
        assertEquals(setOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), code.pixels.toSet())
        val source = RGBLuminanceSource(code.width, code.height, code.pixels)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        assertEquals(content, decoded.text)
    }

    @Test
    fun quietZoneRemainsWhiteOnEveryEdge() {
        val content = "https://example.org/knine"
        val code = QRCodeGenerator.encodePixels(content, 256)
        val white = 0xFFFFFFFF.toInt()
        val modules = Encoder.encode(content, ErrorCorrectionLevel.L).matrix.width
        val quietZone = 4 * (code.width / (modules + 8))
        assertTrue(quietZone > 0)
        for (y in 0 until code.height) {
            for (x in 0 until code.width) {
                if (x < quietZone || y < quietZone || x >= code.width - quietZone || y >= code.height - quietZone) {
                    assertEquals(white, code.pixels[y * code.width + x])
                }
            }
        }
    }

    @Test
    fun longAuthenticationUrlAndQrsSizedPayloadDecodeAtDisplayResolution() {
        val auth = "https://login.example.org/a/" + "0123456789abcdef".repeat(24) + "?redirect=knine%3A%2F%2Fauth"
        val frame = Base64.getEncoder().encodeToString(ByteArray(800) { (it * 37).toByte() })
        for (content in listOf(auth, frame)) {
            for (screenSize in listOf(320, 390, 900)) {
                val code = QRCodeGenerator.encodePixels(content, screenSize)
                val source = RGBLuminanceSource(code.width, code.height, code.pixels)
                assertEquals(content, MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
            }
        }
    }
}
