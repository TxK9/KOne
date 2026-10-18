package cc.txwd.kone

import cc.txwd.kone.ink.InkGallery
import cc.txwd.kone.ink.linear
import cc.txwd.kone.ui.Glyphs
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The installer's two pictures, painted by the App's own ink painter so the installer looks like
 * KOne: the Golden Gate down the left of the welcome and finish pages (493 by 312, WiX's
 * WixUIDialogBmp), and the mark on paper across the top of the others (493 by 58, WixUIBannerBmp).
 * `./gradlew installerArt` writes them to build/wix.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/wix").apply { mkdirs() }
    val paper = 0xFFF0F2F5.toInt()
    val bridge = runBlocking { InkGallery.paint(dark = false) }[1]

    val dialog = Surface.makeRasterN32Premul(493, 312)
    dialog.canvas.clear(paper)
    // The painting fills the left band, its right edge melting into the paper behind the text.
    val band = 164f
    val layer = dialog.canvas.saveLayer(0f, 0f, band, 312f, null)
    val height = 312f * 1.18f
    dialog.canvas.drawImageRect(bridge, Rect.makeWH(bridge.width.toFloat(), bridge.height.toFloat()), Rect.makeXYWH(-40f, -30f, height * 0.75f, height), SamplingMode.LINEAR, null, true)
    dialog.canvas.drawRect(Rect.makeWH(band, 312f), Paint().apply {
        blendMode = org.jetbrains.skia.BlendMode.DST_IN
        shader = linear(0f, 0f, band, 0f, intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000), floatArrayOf(0f, 0.7f, 1f))
    })
    dialog.canvas.restoreToCount(layer)
    save(dialog, File(out, "dialog.bmp"))

    val banner = Surface.makeRasterN32Premul(493, 58)
    banner.canvas.clear(paper)
    banner.canvas.drawRect(Rect.makeXYWH(0f, 57f, 493f, 1f), Paint().apply { color = 0x1F1C2622 })
    val mark = Glyphs.mark.map { Path.makeFromSVGString(it).also { path -> path.fillMode = PathFillMode.EVEN_ODD } }
    banner.canvas.save()
    banner.canvas.translate(493f - 14f - 36f, 10f)
    banner.canvas.scale(36f / 108f, 36f / 108f)
    banner.canvas.translate(0f, -4f)
    mark.forEach { banner.canvas.drawPath(it, Paint().apply { isAntiAlias = true; color = 0xFF1C2622.toInt() }) }
    banner.canvas.restore()
    save(banner, File(out, "banner.bmp"))
}

/** WiX takes 24-bit BMPs. */
private fun save(surface: Surface, file: File) {
    val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    val read = ImageIO.read(png.inputStream())
    val rgb = BufferedImage(read.width, read.height, BufferedImage.TYPE_INT_RGB)
    rgb.createGraphics().apply { drawImage(read, 0, 0, null); dispose() }
    check(ImageIO.write(rgb, "bmp", file)) { "no BMP writer" }
}
