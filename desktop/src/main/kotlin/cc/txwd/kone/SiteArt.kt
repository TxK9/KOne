package cc.txwd.kone

import cc.txwd.kone.ink.InkGallery
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The download page's handscroll (desktop/packaging/site): the four paintings as the App lays
 * them side by side, light and dark, as JPEGs small enough for a phone on a slow line.
 * `./gradlew siteArt` writes them to build/site/art.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/site/art").apply { mkdirs() }
    val names = listOf("liberty", "goldengate", "halfdome", "rainier")
    for (dark in listOf(false, true)) {
        runBlocking { InkGallery.scroll(dark) }.forEachIndexed { index, painting ->
            jpeg(scaled(painting, 540, 720), File(out, "${names[index]}-${if (dark) "dark" else "light"}.jpg"))
        }
    }
}

private fun scaled(image: Image, width: Int, height: Int): Image {
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(width.toFloat(), height.toFloat()), SamplingMode.LINEAR, null, true)
    return surface.makeImageSnapshot()
}

private fun jpeg(image: Image, file: File) {
    val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
    val read = ImageIO.read(png.inputStream())
    val rgb = BufferedImage(read.width, read.height, BufferedImage.TYPE_INT_RGB)
    rgb.createGraphics().apply { drawImage(read, 0, 0, null); dispose() }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val param = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = 0.86f
    }
    ImageIO.createImageOutputStream(file).use { stream ->
        writer.output = stream
        writer.write(null, IIOImage(rgb, null, null), param)
    }
    writer.dispose()
}
