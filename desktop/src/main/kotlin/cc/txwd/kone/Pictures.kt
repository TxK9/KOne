package cc.txwd.kone

import androidx.compose.ui.awt.ComposeWindow
import cc.txwd.kone.app.AppModel
import cc.txwd.kone.node.QrReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.ImageIcon

// Nodes from pictures: what the clipboard, a file or the screens hold, read for QR codes
// (node/QrReader.kt) and imported as the App imports text (app/OwnNodes.kt).

/** The files read as pictures, by their ending; anything else is read as text. */
private val PICTURES = setOf("png", "jpg", "jpeg", "bmp", "gif")

/** What the clipboard holds, imported: its text (links, Clash or sing-box), or the QR codes in a picture copied. */
internal fun importClipboard(model: AppModel) {
    val text = clipboardText()
    if (!text.isNullOrEmpty()) return model.importText(text)
    val picture = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.imageFlavor) as? Image }.getOrNull()
    if (picture == null) return model.importText("")
    model.importQr("in the copied picture") { listOfNotNull(buffered(picture)) }
}

/** A file picked and imported: a picture by its QR codes, anything else as text. */
internal fun importFile(model: AppModel, owner: Frame) {
    val file = pickFile(owner) ?: return
    if (file.extension.lowercase() in PICTURES) {
        model.importQr("in the picture") { listOfNotNull(readPicture(file)) }
    } else {
        file.takeIf { it.length() <= 2L * 1024 * 1024 }?.let { runCatching { it.readText() }.getOrNull() }?.let(model::importText)
    }
}

/** The QR codes on every screen imported, KOne's own window minimised for the moment they are read. */
internal fun importScreen(model: AppModel, window: ComposeWindow) {
    model.importQr("on the screen") {
        val shown = withContext(Dispatchers.Main) { window.isVisible && !window.isMinimized }
        if (shown) {
            withContext(Dispatchers.Main) { window.isMinimized = true }
            // Windows draws the window shrinking away for about a quarter of a second.
            delay(450)
        }
        try {
            QrReader.screens()
        } finally {
            if (shown) withContext(Dispatchers.Main) { window.isMinimized = false; window.toFront() }
        }
    }
}

/** A file chosen in Windows' own dialog; null when none was. */
internal fun pickFile(owner: Frame): File? {
    val dialog = java.awt.FileDialog(owner, "KOne", java.awt.FileDialog.LOAD).apply { isVisible = true }
    return File(dialog.directory ?: return null, dialog.file ?: return null).takeIf { it.isFile }
}

/**
 * [file] as a picture: at most 25 MB and 50 million pixels, its size read before the picture
 * itself, so no file can make the App unpack more than that; null when it is not one.
 */
private fun readPicture(file: File): BufferedImage? = runCatching {
    if (file.length() > 25L * 1024 * 1024) return null
    ImageIO.createImageInputStream(file).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.input = input
            if (reader.getWidth(0).toLong() * reader.getHeight(0) > 50_000_000L) return null
            reader.read(0)
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()

/** [image], as the clipboard may give it still loading, as a picture in memory; null when it has no size. */
private fun buffered(image: Image): BufferedImage? {
    if (image is BufferedImage) return image
    val loaded = ImageIcon(image).image
    val width = loaded.getWidth(null)
    val height = loaded.getHeight(null)
    if (width <= 0 || height <= 0) return null
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
        copy.createGraphics().apply {
            drawImage(loaded, 0, 0, null)
            dispose()
        }
    }
}
