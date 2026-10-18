package cc.txwd.kone.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import cc.txwd.kone.city.Cities
import cc.txwd.kone.city.CityGallery
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tokyo at night, two seconds apart: the water and windows move while the scroll is in sight, nothing while it is not. */
class CityScrollTest {
    private val night = Instant.parse("2026-10-17T12:00:00Z")

    private fun frames(moving: Boolean, software: Boolean = false): Pair<Image, Image> {
        runBlocking { CityGallery.load(Cities.pick("JP", night.atZone(ZoneId.systemDefault()).toLocalDate())) }
        val scene = ImageComposeScene(900, 600, Density(1f)) {
            KoneTheme(dark = true) { CompositionLocalProvider(LocalSoftware provides software) { CityScroll("JP", moving = moving, at = night) } }
        }
        try {
            var t = 0L
            val first = scene.render(t)
            while (t < 2_000_000_000L) {
                t += 33_333_333L
                scene.render(t)
            }
            return first to scene.render(t)
        } finally {
            scene.close()
        }
    }

    private fun changed(a: Image, b: Image): Int {
        val one = Bitmap.makeFromImage(a).readPixels()!!
        val two = Bitmap.makeFromImage(b).readPixels()!!
        var count = 0
        for (i in one.indices step 4) if ((0..2).any { c -> kotlin.math.abs((one[i + c].toInt() and 0xFF) - (two[i + c].toInt() and 0xFF)) > 8 }) count++
        return count
    }

    @Test
    fun theWaterMovesWhileInSight() {
        val (first, later) = frames(moving = true)
        assertTrue(changed(first, later) > 2_000, "the city stood still")
    }

    @Test
    fun nothingMovesOutOfSight() {
        val (first, later) = frames(moving = false)
        assertEquals(0, changed(first, later))
    }

    @Test
    fun drawnInSoftwareTheWaterRests() {
        val (first, later) = frames(moving = true, software = true)
        // The scroll still pans a little; the water no longer sways.
        assertTrue(changed(first, later) < 2_000, "the water moved in software")
    }
}
