package cc.txwd.kone.node

import cc.txwd.kone.ui.qrModules
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrReaderTest {
    private val tokyo = "trojan://example-password@node1.invalid:443?sni=example.org#Tokyo"
    private val osaka = "vless://00000000-0000-0001-0000-000000000001@node2.invalid:443?security=tls&sni=example.org#Osaka"

    /** A screen's worth of a provider's page, light or dark, with lines of text and [codes] (text, x, y) drawn [scale] pixels a module. */
    private fun page(codes: List<Triple<String, Int, Int>> = emptyList(), width: Int = 1920, height: Int = 1080, dark: Boolean = false, scale: Int = 4): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = if (dark) Color(0x1B1F1D) else Color(0xF4F2EC)
        graphics.fillRect(0, 0, width, height)
        graphics.color = if (dark) Color(0xD8D8D8) else Color(0x333333)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
        for (line in 0 until height / 40) graphics.drawString("Example provider, line $line: plans, nodes and help for every device", 40, 40 + line * 40)
        for ((text, x, y) in codes) draw(graphics, text, x, y, scale, dark)
        graphics.dispose()
        return image
    }

    private fun draw(graphics: Graphics2D, text: String, x: Int, y: Int, scale: Int, dark: Boolean) {
        val modules = qrModules(text)!!
        graphics.color = if (dark) Color(0x1B1F1D) else Color.WHITE
        graphics.fillRect(x, y, modules.size * scale, modules.size * scale)
        graphics.color = if (dark) Color(0xF4F2EC) else Color(0x1B1F1D)
        modules.forEachIndexed { row, cells -> cells.forEachIndexed { column, on -> if (on) graphics.fillRect(x + column * scale, y + row * scale, scale, scale) } }
    }

    @Test
    fun aCodeOnAPageIsRead() {
        assertEquals(listOf(tokyo), QrReader.read(page(listOf(Triple(tokyo, 1200, 300)))))
    }

    @Test
    fun severalCodesAreReadEachOnce() {
        val read = QrReader.read(page(listOf(Triple(tokyo, 100, 100), Triple(osaka, 900, 500), Triple(tokyo, 1500, 120))))
        assertEquals(setOf(tokyo, osaka), read.toSet())
        assertEquals(2, read.size)
    }

    @Test
    fun aLightCodeOnADarkPageIsRead() {
        assertEquals(listOf(tokyo), QrReader.read(page(listOf(Triple(tokyo, 700, 200)), dark = true)))
    }

    @Test
    fun aPageWithoutACodeGivesNothing() {
        val started = System.nanoTime()
        assertEquals(emptyList(), QrReader.read(page()))
        println("A screen without a code read in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    @Test
    fun aVeryLargePictureAndATransparentOneAreRead() {
        assertEquals(listOf(osaka), QrReader.read(page(listOf(Triple(osaka, 3000, 1200)), width = 5120, height = 2880, scale = 9)))
        // Dark modules on nothing, as a code saved with a transparent background.
        val modules = qrModules(tokyo)!!
        val transparent = BufferedImage(modules.size * 6, modules.size * 6, BufferedImage.TYPE_INT_ARGB)
        transparent.createGraphics().apply {
            color = Color(0x1B1F1D)
            modules.forEachIndexed { row, cells -> cells.forEachIndexed { column, on -> if (on) fillRect(column * 6, row * 6, 6, 6) } }
            dispose()
        }
        assertEquals(listOf(tokyo), QrReader.read(transparent))
    }

    @Test
    fun aCodeTooSmallOrCutIsNotMadeUp() {
        val cut = page(listOf(Triple(tokyo, 1880, 1040)))
        assertTrue(QrReader.read(cut).isEmpty())
    }
}
