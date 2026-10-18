package io.nekohasekai.sfa.compose.theme

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings

data class WallpaperChoice(val id: String, val label: String, val resource: Int, internal val focus: BackdropFocus = BackdropFocus())

object WallpaperCatalog {
    val choices = listOf(
        WallpaperChoice("portrait", "Portrait", R.drawable.portrait_backdrop, BackdropFocus(0.56f, 0.71f)),
        WallpaperChoice("sunset", "Sunset sky", R.drawable.sunset_backdrop),
        WallpaperChoice("owner1", "Anime", R.drawable.owner_wallpaper_1, BackdropFocus(0.51f, 0.65f)),
        WallpaperChoice("owner2", "Kaoruko Waguri", R.drawable.owner_wallpaper_2, BackdropFocus(0.535f, 0.58f)),
        WallpaperChoice("owner3", "Summer", R.drawable.owner_wallpaper_3, BackdropFocus(0.705f, 0.61f)),
        WallpaperChoice("owner4", "Indoors", R.drawable.owner_wallpaper_4, BackdropFocus(0.575f, 0.56f)),
        WallpaperChoice("owner5", "Anime summer", R.drawable.owner_wallpaper_5),
        WallpaperChoice("owner6", "Saved wallpaper", R.drawable.owner_wallpaper_6, BackdropFocus(0.49f, 0.55f)),
    )

    private val rotation = WallpaperRotation()
    private val selection by lazy { mutableStateOf(Settings.selfUseWallpaper) }
    private val revisionState = mutableIntStateOf(0)
    val revision: Int get() = revisionState.intValue
    val selectedId: String get() = selection.value
    val selectedChoice: WallpaperChoice get() = choices.find { it.id == selectedId } ?: choices.first()
    internal fun focusFor(resource: Int): BackdropFocus = choices.find { it.resource == resource }?.focus ?: BackdropFocus()
    internal fun initialThemeFor(resource: Int): WallpaperTheme {
        val bright = choices.find { it.resource == resource }?.id in setOf("owner1", "owner2", "owner3", "owner5", "owner6")
        return WallpaperTheme(lightInk = !bright, brightComposition = bright)
    }

    fun select(id: String) {
        require(choices.any { it.id == id })
        Settings.selfUseWallpaper = id
        selection.value = id
        revisionState.intValue += 1
    }

    suspend fun rotateIfDue() {
        val change = rotation.check(
            choices.map { it.id }, selectedId, Settings.selfUseWallpaperChangedAt,
            System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(),
        ) ?: return
        if (change.id != selectedId) select(change.id)
        Settings.selfUseWallpaperChangedAt = change.wallTime
        Settings.dataStore.flush()
    }
}
