package io.nekohasekai.sfa.compose.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import io.nekohasekai.sfa.compose.theme.ink.InkMountains
import io.nekohasekai.sfa.compose.theme.ink.InkPainter
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Paints every scene on light and dark paper, as the plain wallpaper shows it, for a look. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class InkLandscapeVisualTest {
    @Test
    fun paintEveryMountain() {
        val dir = File(System.getenv("KONE_INK_PREVIEW") ?: System.getProperty("knine.previewDir"), "ink").apply { mkdirs() }
        val brush = android.graphics.BitmapFactory.decodeResource(org.robolectric.RuntimeEnvironment.getApplication().resources, io.nekohasekai.sfa.R.drawable.kone_ink_mountains)
        for ((dark, painted) in listOf(false to true, true to true, false to false)) {
            // All of them side by side, a third of their size, to compare at a glance.
            val rows = (InkMountains.all.size + 3) / 4
            val sheet = Bitmap.createBitmap(InkPainter.WIDTH / 3 * 4, InkPainter.HEIGHT / 3 * rows, Bitmap.Config.ARGB_8888)
            val sheetCanvas = Canvas(sheet)
            InkMountains.all.forEachIndexed { index, mountain ->
                val scene = InkPainter.render(mountain, dark, if (painted) brush else null)
                val out = Bitmap.createBitmap(InkPainter.WIDTH, InkPainter.HEIGHT, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawColor(if (dark) 0xFF181A1F.toInt() else 0xFFF0F2F5.toInt())
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(scene.back, 0f, 0f, paint)
                canvas.drawBitmap(scene.front, 0f, 0f, paint)
                val sealX = if (scene.sealOnLeft) InkPainter.WIDTH * 0.06f else InkPainter.WIDTH * 0.94f - scene.seal.width
                canvas.drawBitmap(scene.seal, sealX, InkPainter.HEIGHT * 0.10f, paint)
                File(dir, "ink-${if (dark) "dark" else "light"}${if (painted) "" else "-plain"}-$index.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val cell = Bitmap.createScaledBitmap(out, InkPainter.WIDTH / 3, InkPainter.HEIGHT / 3, true)
                sheetCanvas.drawBitmap(cell, (index % 4) * cell.width.toFloat(), (index / 4) * cell.height.toFloat(), null)
            }
            File(dir, "sheet-${if (dark) "dark" else "light"}${if (painted) "" else "-plain"}.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
