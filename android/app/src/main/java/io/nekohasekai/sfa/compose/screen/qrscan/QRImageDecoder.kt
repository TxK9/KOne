package io.nekohasekai.sfa.compose.screen.qrscan

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.nekohasekai.sfa.selfuse.ShareLinkException

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

internal object QRImportErrors {
    /** Link refusals are fixed words and safe to show; anything else may echo the link. */
    fun controlledMessage(failure: Exception): String = when (failure) {
        is ShareLinkException -> failure.message ?: GENERIC
        else -> GENERIC
    }

    private const val GENERIC = "The QR code contains invalid node or configuration parameters."
}
