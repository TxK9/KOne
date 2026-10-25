package io.nekohasekai.sfa.compose.screen.qrscan

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import cc.txwd.kone.shared.ShareLinkException

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
        decodeSource(source)?.let { return it }
        // A photographed screen often places a small code among text and scenery.
        // Local crops isolate its finder patterns; smaller copies smooth camera moire.
        val luma = source.matrix
        QRCodeSmartCrop.findCropArea(luma, width, height, 0)?.let { crop ->
            decodeSource(source.crop(crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top))?.let { return it }
        }
        for (divisions in 2..3) {
            val cropWidth = (width * 2 / (divisions + 1)).coerceAtLeast(1)
            val cropHeight = (height * 2 / (divisions + 1)).coerceAtLeast(1)
            for (row in 0 until divisions) for (column in 0 until divisions) {
                val left = column * (width - cropWidth) / (divisions - 1)
                val top = row * (height - cropHeight) / (divisions - 1)
                decodeSource(source.crop(left, top, cropWidth, cropHeight))?.let { return it }
            }
        }
        for (step in intArrayOf(2, 3)) {
            if (minOf(width, height) / step < 160) continue
            val scaledWidth = width / step
            val scaledHeight = height / step
            val scaled = IntArray(scaledWidth * scaledHeight) { index ->
                val x = (index % scaledWidth) * step
                val y = (index / scaledWidth) * step
                var red = 0; var green = 0; var blue = 0
                for (dy in 0 until step) for (dx in 0 until step) {
                    val pixel = opaquePixels[(y + dy) * width + x + dx]
                    red += pixel ushr 16 and 255
                    green += pixel ushr 8 and 255
                    blue += pixel and 255
                }
                val count = step * step
                0xFF000000.toInt() or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            }
            decodeSource(RGBLuminanceSource(scaledWidth, scaledHeight, scaled))?.let { return it }
        }
        return null
    }

    private fun decodeSource(source: com.google.zxing.LuminanceSource): String? {
        val reader = QRCodeReader()
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        for (luminance in listOf(source, source.invert())) {
            for (binarizer in listOf(HybridBinarizer(luminance), GlobalHistogramBinarizer(luminance))) {
                try {
                    return reader.decode(BinaryBitmap(binarizer), hints).text
                } catch (_: ReaderException) {
                    // Keep the original pixels; all threshold and crop attempts are local.
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
