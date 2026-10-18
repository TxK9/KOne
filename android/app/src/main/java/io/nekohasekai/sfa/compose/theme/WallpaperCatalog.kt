package io.nekohasekai.sfa.compose.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where the dashboard's picture comes from. */
enum class WallpaperMode(val id: String, val label: String) {
    /** The photos built into this copy, taking turns. */
    Builtin("builtin", "Built-in rotation"),

    /** One photo the user chose from their gallery. */
    Photo("photo", "My photos"),

    /** No photo: a quiet gradient that follows the system's light or dark mode. */
    None("none", "No wallpaper"),
    ;

    companion object {
        fun of(id: String): WallpaperMode? = entries.firstOrNull { it.id == id }
    }
}

/** [panelAtTop]: the subject sits low in the photo, so the dashboard panel goes above it. */
data class WallpaperChoice(
    val id: String,
    val label: String,
    val resource: Int,
    internal val focus: BackdropFocus = BackdropFocus(),
    val panelAtTop: Boolean = false,
)

object WallpaperCatalog {
    val choices = listOf(
        WallpaperChoice("portrait", "Portrait", R.drawable.portrait_backdrop, BackdropFocus(0.56f, 0.71f), panelAtTop = true),
        WallpaperChoice("sunset", "Sunset sky", R.drawable.sunset_backdrop),
        WallpaperChoice("owner1", "Anime", R.drawable.owner_wallpaper_1, BackdropFocus(0.51f, 0.65f)),
        WallpaperChoice("owner2", "Kaoruko Waguri", R.drawable.owner_wallpaper_2, BackdropFocus(0.535f, 0.58f)),
        WallpaperChoice("owner3", "Summer", R.drawable.owner_wallpaper_3, BackdropFocus(0.705f, 0.61f)),
        WallpaperChoice("owner4", "Indoors", R.drawable.owner_wallpaper_4, BackdropFocus(0.575f, 0.56f)),
        WallpaperChoice("owner5", "Anime summer", R.drawable.owner_wallpaper_5),
        WallpaperChoice("owner6", "Saved wallpaper", R.drawable.owner_wallpaper_6, BackdropFocus(0.49f, 0.55f)),
    )

    /** Longest side kept from a chosen photo; enough for a sharp full-screen picture. */
    private const val PHOTO_LONGEST_SIDE = 2560

    private val rotation = WallpaperRotation()
    private val selection by lazy { mutableStateOf(Settings.selfUseWallpaper) }
    private val revisionState = mutableIntStateOf(0)
    val revision: Int get() = revisionState.intValue
    val selectedId: String get() = selection.value
    val selectedChoice: WallpaperChoice get() = choices.find { it.id == selectedId } ?: choices.first()
    internal fun focusFor(resource: Int): BackdropFocus = choices.find { it.resource == resource }?.focus ?: BackdropFocus()
    internal fun panelAtTopFor(resource: Int): Boolean = choices.find { it.resource == resource }?.panelAtTop == true
    internal fun initialThemeFor(resource: Int): WallpaperTheme {
        val bright = resource == R.drawable.knine_plain_light ||
            choices.find { it.resource == resource }?.id in setOf("owner1", "owner2", "owner3", "owner5", "owner6")
        return WallpaperTheme(lightInk = !bright, brightComposition = bright)
    }

    /**
     * Whether this copy carries the built-in photos. A copy built for others ships placeholders
     * that do not decode as pictures, so it offers only the user's own photo or none.
     */
    val hasBundledPhotos: Boolean by lazy {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(Application.application.resources, R.drawable.portrait_backdrop, bounds)
        bounds.outWidth > 0
    }

    val photoFile: File get() = File(Application.application.filesDir, "wallpaper/photo.jpg")

    private val modeState by lazy { mutableStateOf(resolveMode()) }
    val mode: WallpaperMode get() = modeState.value

    private fun resolveMode(): WallpaperMode {
        val stored = WallpaperMode.of(Settings.knineWallpaperMode)
        val fallback = if (hasBundledPhotos) WallpaperMode.Builtin else WallpaperMode.None
        return when (stored) {
            null -> fallback
            WallpaperMode.Builtin -> fallback
            WallpaperMode.Photo -> if (photoFile.isFile) stored else fallback
            WallpaperMode.None -> stored
        }
    }

    fun setMode(mode: WallpaperMode) {
        require(mode != WallpaperMode.Builtin || hasBundledPhotos)
        Settings.knineWallpaperMode = mode.id
        modeState.value = mode
        revisionState.intValue += 1
    }

    /** The drawable behind everything: a built-in photo, or the plain gradient, also shown while a photo loads. */
    fun backdropResource(dark: Boolean): Int =
        if (mode == WallpaperMode.Builtin) selectedChoice.resource else if (dark) R.drawable.knine_plain_dark else R.drawable.knine_plain_light

    /** The chosen photo's file while it is the wallpaper. */
    fun customPath(): String? = photoFile.takeIf { mode == WallpaperMode.Photo && it.isFile }?.absolutePath

    /** Copies a picked picture in, upright and no larger than a screen needs, and shows it. */
    suspend fun usePhoto(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            val bitmap = decodePhoto(context, uri) ?: throw IOException("unreadable picture")
            val file = photoFile
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "photo.tmp")
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
        setMode(WallpaperMode.Photo)
    }

    private fun decodePhoto(context: Context, uri: Uri): Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
        // ImageDecoder applies the camera's rotation, so the photo stays upright.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = PHOTO_LONGEST_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_LONGEST_SIDE) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    fun select(id: String) {
        require(choices.any { it.id == id })
        Settings.selfUseWallpaper = id
        selection.value = id
        revisionState.intValue += 1
    }

    suspend fun rotateIfDue() {
        if (mode != WallpaperMode.Builtin) return
        val change = rotation.check(
            choices.map { it.id }, selectedId, Settings.selfUseWallpaperChangedAt,
            System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(),
        ) ?: return
        if (change.id != selectedId) select(change.id)
        Settings.selfUseWallpaperChangedAt = change.wallTime
        Settings.dataStore.flush()
    }
}
