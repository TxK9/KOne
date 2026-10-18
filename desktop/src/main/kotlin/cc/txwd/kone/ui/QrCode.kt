package cc.txwd.kone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Which modules of a QR code for [text] are dark, row by row, with the quiet zone around them; null when it is too long for one. */
fun qrModules(text: String): List<BooleanArray>? = runCatching {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    (0 until matrix.height).map { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
}.getOrNull()

/**
 * A QR code for [text], [size] square, always dark on light whatever the theme, as phone cameras
 * read it best; nothing when the text is too long for one.
 */
@Composable
fun QrCode(text: String, size: Dp = 220.dp) {
    val modules = remember(text) { qrModules(text) } ?: return
    Canvas(Modifier.size(size)) {
        val cell = this.size.width / modules.size
        drawRect(androidx.compose.ui.graphics.Color.White)
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) drawRect(androidx.compose.ui.graphics.Color(0xFF1B1F1D), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
