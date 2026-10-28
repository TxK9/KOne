package io.nekohasekai.sfa.compose.screen.qrscan

import androidx.annotation.StringRes
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.nekohasekai.sfa.R

/** Local image pixels only; camera and gallery share the node import path. */
internal object QRImageDecoder {
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        // ZXing ignores alpha. Transparent PNG backgrounds commonly contain
        // black RGB pixels, which would otherwise erase the QR's contrast.
        val opaquePixels = if (pixels.any { it ushr 24 != 255 }) {
            IntArray(pixels.size) { index -> compositeOnWhite(pixels[index]) }
        } else {
            pixels
        }
        val source = RGBLuminanceSource(width, height, opaquePixels)
        val reader = QRCodeReader()
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        for (luminance in listOf(source, source.invert())) {
            for (bitmap in listOf(BinaryBitmap(HybridBinarizer(luminance)), BinaryBitmap(GlobalHistogramBinarizer(luminance)))) {
                try {
                    return reader.decode(bitmap, hints).text
                } catch (_: ReaderException) {
                    // Try the alternate threshold / light-on-dark QR image.
                } finally {
                    reader.reset()
                }
            }
        }
        return null
    }

    private fun compositeOnWhite(pixel: Int): Int {
        val alpha = pixel ushr 24
        fun channel(shift: Int): Int = (((pixel ushr shift) and 255) * alpha + 255 * (255 - alpha) + 127) / 255
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/** A scanner message resolved by the UI, so it follows the in-app language rather than the Application locale. */
sealed interface ScanMessage {
    data class Resource(@StringRes val id: Int) : ScanMessage

    /** Untranslated validator text; only a fixed allow-list ever reaches this, never decoded content. */
    data class Literal(val text: String) : ScanMessage
}

internal object QRImportErrors {
    private val knownParameters = setOf(
        "Invalid connection link", "Invalid connection identifier", "Connection server is missing", "Invalid connection port",
        "Connection contains duplicate parameters", "Reality public key is missing", "Invalid Reality public key format", "Invalid Reality short ID format",
    )

    fun controlledMessage(failure: Exception): ScanMessage = when (val message = failure.message.orEmpty()) {
        "Import this transport using a complete configuration" -> ScanMessage.Resource(R.string.knine_qr_error_transport)
        "The paired configuration requires an encrypted VLESS connection" -> ScanMessage.Resource(R.string.knine_qr_error_encryption)
        in knownParameters -> ScanMessage.Literal(message)
        else -> ScanMessage.Resource(R.string.knine_qr_error_parameters)
    }
}
