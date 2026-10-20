package cc.txwd.kone.node

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage

/**
 * QR codes read from pictures, as a phone's scanner reads them (ZXing): what the screens show, a
 * picture copied, or a picture file. A provider's page or a friend's phone often shows a node as
 * a QR code; here it becomes text for the import that reads links.
 */
object QrReader {
    /** The largest side read at full size; a larger picture is also read halved, which finds big codes faster. */
    private const val LARGEST = 4096

    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * The text of every QR code in [picture], each once, in the order found; empty when there is
     * none. Dark codes on light first, then light on dark, as dark-themed pages draw them.
     */
    fun read(picture: BufferedImage): List<String> {
        for (image in sizes(opaque(picture))) {
            val plain = luminance(image)
            for (source in listOf(plain, InvertedLuminanceSource(plain))) {
                // The hybrid reading copes with shadows and gradients; the global one finds what it misses on flat screens.
                for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
                    val texts = decode(bitmap)
                    if (texts.isNotEmpty()) return texts
                }
            }
        }
        return emptyList()
    }

    /** Several codes at once; when none is found that way, one alone, which the single reader finds more often. */
    private fun decode(bitmap: BinaryBitmap): List<String> {
        val many = runCatching { QRCodeMultiReader().decodeMultiple(bitmap, HINTS).toList() }.getOrDefault(emptyList())
        val results = many.ifEmpty { listOfNotNull(runCatching { QRCodeReader().decode(bitmap, HINTS) }.getOrNull()) }
        return results.mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    }

    private fun luminance(image: BufferedImage): LuminanceSource =
        RGBLuminanceSource(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))

    /** [picture] with any transparency over white, as a code drawn on nothing is meant to be seen. */
    private fun opaque(picture: BufferedImage): BufferedImage {
        if (!picture.colorModel.hasAlpha()) return picture
        return BufferedImage(picture.width, picture.height, BufferedImage.TYPE_INT_RGB).also { copy ->
            copy.createGraphics().apply {
                color = java.awt.Color.WHITE
                fillRect(0, 0, copy.width, copy.height)
                drawImage(picture, 0, 0, null)
                dispose()
            }
        }
    }

    /** [picture] as it is, and halved when it is very large. */
    private fun sizes(picture: BufferedImage): List<BufferedImage> {
        if (maxOf(picture.width, picture.height) <= LARGEST) return listOf(picture)
        val half = BufferedImage(picture.width / 2, picture.height / 2, BufferedImage.TYPE_INT_RGB)
        half.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(picture, 0, 0, half.width, half.height, null)
            dispose()
        }
        return listOf(picture, half)
    }

    /** What every screen shows now, one picture a screen at its full resolution; empty where screens cannot be read. */
    fun screens(): List<BufferedImage> = runCatching {
        val robot = Robot()
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.mapNotNull { device ->
            val shot = robot.createMultiResolutionScreenCapture(device.defaultConfiguration.bounds)
            shot.resolutionVariants.filterIsInstance<BufferedImage>().maxByOrNull { it.width.toLong() * it.height }
        }
    }.getOrDefault(emptyList())
}
