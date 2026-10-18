package io.nekohasekai.sfa.bg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.PathParser
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherGlyphs
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The notification's picture of the weather, drawn like a seal: the weather's glyph cut white
 * into a square of its pigment, with a few specks where the stamp did not take. The status bar
 * can only show one colour, so this is where the colour lives.
 */
internal object WeatherSealPicture {
    private val cache = ConcurrentHashMap<NodeWeather, Bitmap>()

    fun of(context: Context, weather: NodeWeather): Bitmap = cache.getOrPut(weather) { draw(context, weather) }

    private fun draw(context: Context, weather: NodeWeather): Bitmap {
        val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width).coerceAtLeast(96)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()
        val ink = weather.onPigment.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, s, s), s * 0.2f, s * 0.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = weather.pigment.toInt() })
        canvas.drawRoundRect(
            RectF(s * 0.05f, s * 0.05f, s * 0.95f, s * 0.95f), s * 0.15f, s * 0.15f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = s * 0.025f
                color = ink and 0x00FFFFFF or (0x40 shl 24)
            },
        )
        val scale = s * 0.6f / 24f
        val place = Matrix().apply {
            setScale(scale, scale)
            postTranslate(s * 0.2f, s * 0.2f)
        }
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = ink
        }
        WeatherGlyphs.paths(weather).forEach { data ->
            val path = PathParser.createPathFromPathData(data)
            path.transform(place)
            canvas.drawPath(path, pen)
        }
        val random = Random(weather.ordinal * 17 + 5)
        val speck = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink and 0x00FFFFFF or (0x55 shl 24) }
        repeat(9) {
            canvas.drawCircle(random.nextFloat() * s, random.nextFloat() * s, s * 0.011f * (0.5f + random.nextFloat()), speck)
        }
        return bitmap
    }
}
