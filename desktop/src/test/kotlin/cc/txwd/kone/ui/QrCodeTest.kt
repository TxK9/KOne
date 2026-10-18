package cc.txwd.kone.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QrCodeTest {
    /** The modules drawn as pixels, [scale] to a module, as a camera would see the screen. */
    private class Drawn(private val modules: List<BooleanArray>, private val scale: Int) :
        LuminanceSource(modules.size * scale, modules.size * scale) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) out[x] = if (modules[y / scale][x / scale]) 0 else 0xFF.toByte()
            return out
        }
        override fun getMatrix(): ByteArray = ByteArray(width * height).also { all -> for (y in 0 until height) getRow(y, null).copyInto(all, y * width) }
    }

    @Test
    fun aShareLinkReadsBackFromItsCode() {
        // An example node only (reserved address, made-up keys).
        val link = "vless://00000000-0000-4000-8000-000000000001@203.0.113.10:443?encryption=none&security=reality&sni=www.example.com" +
            "&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123abcd&type=grpc&serviceName=kone#Japan%20%C2%B7%20Tokyo"
        val modules = assertNotNull(qrModules(link))
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(Drawn(modules, 4))), mapOf(DecodeHintType.PURE_BARCODE to true))
        assertEquals(link, read.text)
    }

    @Test
    fun textTooLongForOneCodeGivesNone() {
        assertNull(qrModules("x".repeat(5_000)))
    }
}
