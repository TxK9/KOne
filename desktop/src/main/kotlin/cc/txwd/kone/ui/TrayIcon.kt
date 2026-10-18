package cc.txwd.kone.ui

import cc.txwd.kone.ink.paint
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The tray icon as the preview draws it: the mountain mark, with a square of the weather's
 * pigment in its corner (none for Z, when the mark fades instead), in white on a dark taskbar
 * and near-black on a light one.
 */
object TrayIcon {
    private val mark: List<Path> = Glyphs.mark.map { data ->
        Path.makeFromSVGString(data).also { it.fillMode = PathFillMode.EVEN_ODD }
    }

    fun image(weather: NodeWeather, darkTaskbar: Boolean, size: Int = 32): BufferedImage {
        val surface = Surface.makeRasterN32Premul(size, size)
        val canvas = surface.canvas
        val k = size / 108f
        val taskbar = if (darkTaskbar) 0xFF202020.toInt() else 0xFFE6E8EB.toInt()
        val rest = weather == NodeWeather.Rest
        canvas.save()
        canvas.scale(k, k)
        canvas.translate(-6f, -8f)
        val ink = paint { color = if (darkTaskbar) 0xFFFFFFFF.toInt() else 0xFF1C1C1C.toInt(); alpha = if (rest) 128 else 255 }
        mark.forEach { canvas.drawPath(it, ink) }
        canvas.restore()
        if (!rest) {
            canvas.save()
            canvas.scale(k, k)
            val badge = RRect.makeXYWH(62f, 62f, 44f, 44f, 8f)
            // A ring in the taskbar's colour cuts the badge out of the mark.
            canvas.drawRRect(badge, paint { color = weather.pigment.toInt(); mode = org.jetbrains.skia.PaintMode.FILL })
            canvas.drawRRect(badge, paint { color = taskbar; mode = org.jetbrains.skia.PaintMode.STROKE; strokeWidth = 9f })
            canvas.restore()
        }
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        surface.close()
        return ImageIO.read(png.inputStream())
    }

    /** The tip on hover: "KOne · United States S 245 ms", or "KOne · Z" while not connected. */
    fun tip(node: Node?, online: Boolean): String =
        if (!online || node == null) "KOne · ${NodeWeather.Rest.char}" else "KOne · ${node.name} ${node.weather.char} ${delayText(node.delay)}"
}
