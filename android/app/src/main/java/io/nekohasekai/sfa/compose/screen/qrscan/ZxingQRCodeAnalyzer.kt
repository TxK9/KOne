package io.nekohasekai.sfa.compose.screen.qrscan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

class ZxingQRCodeAnalyzer(
    private val onSuccess: ((String) -> Unit),
    private val onFailure: ((Exception) -> Unit),
    private val onCropArea: ((QRCodeCropArea?) -> Unit)? = null,
) : ImageAnalysis.Analyzer {
    private val qrCodeReader = QRCodeReader()
    private val decodeHints = mapOf(DecodeHintType.TRY_HARDER to true)
    private var yDataBuffer: ByteArray? = null

    var qrsMode: Boolean = false

    override fun analyze(image: ImageProxy) {
        try {
            val yData = image.toYUVData()
            val width = image.width
            val height = image.height
            val rotationDegrees = image.imageInfo.rotationDegrees
            val source = PlanarYUVLuminanceSource(yData, width, height, 0, 0, width, height, false)

            // Fast path: HybridBinarizer
            tryDecode(BinaryBitmap(HybridBinarizer(source)))?.let {
                onSuccess(it.text)
                return
            }

            val cropArea = QRCodeSmartCrop.findCropArea(yData, width, height, rotationDegrees)
            onCropArea?.invoke(cropArea)
            if (cropArea != null) {
                val cropWidth = cropArea.right - cropArea.left
                val cropHeight = cropArea.bottom - cropArea.top
                val smartSource = PlanarYUVLuminanceSource(
                    yData,
                    width,
                    height,
                    cropArea.left,
                    cropArea.top,
                    cropWidth,
                    cropHeight,
                    false,
                )
                tryDecode(BinaryBitmap(HybridBinarizer(smartSource)))?.let {
                    onSuccess(it.text)
                    return
                }
            }

            // In QRS mode, skip additional binarizer attempts for performance
            if (qrsMode) return

            // Inverted HybridBinarizer (uses ZXing's native invert)
            tryDecode(BinaryBitmap(HybridBinarizer(source.invert())))?.let {
                onSuccess(it.text)
                return
            }

            // GlobalHistogramBinarizer (normal)
            tryDecode(BinaryBitmap(GlobalHistogramBinarizer(source)))?.let {
                onSuccess(it.text)
                return
            }

            // GlobalHistogramBinarizer (inverted)
            tryDecode(BinaryBitmap(GlobalHistogramBinarizer(source.invert())))?.let {
                onSuccess(it.text)
                return
            }
        } catch (e: Exception) {
            onFailure(e)
        } finally {
            qrCodeReader.reset()
            image.close()
        }
    }

    private fun ImageProxy.toYUVData(): ByteArray {
        val yPlane = planes[0]
        return YPlaneReader.read(yPlane.buffer, width, height, yPlane.rowStride, yPlane.pixelStride, yDataBuffer)
            .also { yDataBuffer = it }
    }

    private fun tryDecode(bitmap: BinaryBitmap): Result? = QRCodeDecodeAttempt.run(
        decode = { qrCodeReader.decode(bitmap, decodeHints) },
        reset = { qrCodeReader.reset() },
    )
}
