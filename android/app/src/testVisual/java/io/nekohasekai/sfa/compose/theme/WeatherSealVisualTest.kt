package io.nekohasekai.sfa.compose.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import io.nekohasekai.sfa.bg.WeatherSealPicture
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Paints the notification weather symbols on light and dark backgrounds. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class WeatherSealVisualTest {
    @Test
    fun paintWeatherSeals() {
        val dir = File(System.getenv("KONE_INK_PREVIEW") ?: System.getProperty("knine.previewDir"), "weather").apply { mkdirs() }
        val context = RuntimeEnvironment.getApplication()
        val cell = 160
        val sheet = Bitmap.createBitmap(cell * NodeWeather.entries.size, cell * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        listOf(0xFFF0F2F5.toInt(), 0xFF181A1F.toInt()).forEachIndexed { row, ground ->
            canvas.drawRect(0f, (row * cell).toFloat(), sheet.width.toFloat(), ((row + 1) * cell).toFloat(), Paint().apply { color = ground })
            NodeWeather.entries.forEachIndexed { index, weather ->
                val picture = WeatherSealPicture.of(context, weather)
                val inset = 24
                canvas.drawBitmap(picture, null, Rect(index * cell + inset, row * cell + inset, (index + 1) * cell - inset, (row + 1) * cell - inset), paint)
            }
        }
        File(dir, "weather-seals.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
