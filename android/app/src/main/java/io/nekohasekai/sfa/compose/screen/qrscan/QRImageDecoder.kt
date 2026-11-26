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

internal object QRImportErrors {
    private val knownParameters = setOf(
        "Invalid connection link", "Invalid connection identifier", "Connection server is missing", "Invalid connection port",
        "Connection contains duplicate parameters", "Reality public key is missing", "Invalid Reality public key format", "Invalid Reality short ID format",
        "Unsupported VLESS flow",
    )

    /** English diagnostic text; stable for matching and tests. */
    fun controlledMessage(failure: Exception): String = when (val message = failure.message.orEmpty()) {
        "Import this transport using a complete configuration" -> "QR import supports VLESS TCP with TLS or Reality. Import other transports as a complete configuration."
        "The paired configuration requires an encrypted VLESS connection" -> "This VLESS node uses unsupported encryption. TLS and Reality are supported."
        in knownParameters -> message
        else -> "The QR code contains invalid node or configuration parameters."
    }

    /** Display resource for [controlledMessage]; never exposes unrecognized exception text. */
    @StringRes
    fun controlledMessageRes(failure: Exception): Int = when (failure.message.orEmpty()) {
        "Import this transport using a complete configuration" -> R.string.knine_scan_transport
        "The paired configuration requires an encrypted VLESS connection" -> R.string.knine_scan_encryption
        "Invalid connection link" -> R.string.knine_error_invalid_link
        "Invalid connection identifier" -> R.string.knine_error_invalid_id
        "Connection server is missing" -> R.string.knine_error_no_server
        "Invalid connection port" -> R.string.knine_error_invalid_port
        "Connection contains duplicate parameters" -> R.string.knine_error_dup_param
        "Reality public key is missing" -> R.string.knine_error_no_pbk
        "Invalid Reality public key format" -> R.string.knine_error_bad_pbk
        "Invalid Reality short ID format" -> R.string.knine_error_bad_sid
        "Unsupported VLESS flow" -> R.string.knine_error_bad_flow
        else -> R.string.knine_scan_invalid_params
    }
}
