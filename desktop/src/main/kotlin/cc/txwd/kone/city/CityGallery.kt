package cc.txwd.kone.city

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect

/**
 * A city scene's two paintings, decoded; the windows lit in the night one, and where the sun's
 * wash falls on the day one, as a mask over the whole wallpaper.
 */
class ScenePaintings(val day: ImageBitmap, val night: ImageBitmap, val lights: List<WindowLight>, val facades: ImageBitmap)

/** The city paintings decoded so far, kept while their scenes are in use: decoding takes a moment, showing them costs nothing. */
object CityGallery {
    private val decoded = LinkedHashMap<String, ScenePaintings>()

    /** The paintings of [scenes] if all are decoded, without waiting. */
    fun ready(scenes: List<CityScene>): List<ScenePaintings>? = synchronized(decoded) {
        scenes.map { decoded[it.id] ?: return null }
    }

    /** Lets every painting go while no one looks; they are decoded again when wanted. */
    fun rest() = synchronized(decoded) { decoded.clear() }

    suspend fun load(scenes: List<CityScene>): List<ScenePaintings> = withContext(Dispatchers.Default) {
        val loaded = scenes.map { scene ->
            synchronized(decoded) { decoded[scene.id] } ?: run {
                val night = checkNotNull(Cities.painting(scene, night = true)) { "no night painting for ${scene.id}" }
                val day = checkNotNull(Cities.painting(scene, night = false)) { "no day painting for ${scene.id}" }
                ScenePaintings(day.toComposeImageBitmap(), night.toComposeImageBitmap(), lights(scene, night), facades(scene, day))
            }
        }
        synchronized(decoded) {
            scenes.forEachIndexed { index, scene -> decoded[scene.id] = loaded[index] }
            // Only the scenes in use stay: another country's are let go.
            decoded.keys.retainAll(scenes.map { it.id }.toSet())
        }
        loaded
    }

    /** The night painting laid back on its whole wallpaper at 768 wide, as Codex looks for lit windows in it. */
    private fun lights(scene: CityScene, night: Image): List<WindowLight> {
        val (pixels, width, height) = laidBack(scene, night, 768)
        return windowLights(scene.id, SceneMotion.of(scene), pixels, width, height)
    }

    /** Where the sun's wash may fall, from the day painting laid back at 480 wide. */
    private fun facades(scene: CityScene, day: Image): ImageBitmap {
        val (pixels, width, height) = laidBack(scene, day, 480)
        return Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), facadeMask(pixels, width, height), width * 4)
            .toComposeImageBitmap()
    }

    /** [painting] back in its place on the whole wallpaper, [most] wide at most, as RGBA: transparent where it was cut away. */
    private fun laidBack(scene: CityScene, painting: Image, most: Int): Triple<ByteArray, Int, Int> {
        val width = minOf(most, painting.width)
        val height = maxOf(1, jsRound(width / scene.crop.aspect))
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        Canvas(bitmap).use { canvas ->
            canvas.drawImageRect(painting, Rect.makeXYWH(0f, (scene.crop.top * height).toFloat(), width.toFloat(), (scene.crop.height * height).toFloat()))
        }
        val pixels = checkNotNull(bitmap.readPixels()) { "could not read ${scene.id}" }
        bitmap.close()
        return Triple(pixels, width, height)
    }
}
