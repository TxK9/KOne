package io.nekohasekai.sfa.compose.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableStateOf
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings

/** How the categories lay out when the current category is tapped again. */
enum class KnineSwitcherStyle(val id: String, val label: String) {
    Dial("dial", "Dial"),
    Wheel("wheel", "Wheel"),
    Floating("float", "Floating"),
    Cards("cards", "Cards"),
    ;

    companion object {
        fun of(id: String): KnineSwitcherStyle = entries.firstOrNull { it.id == id } ?: Dial
    }
}

/**
 * Which ink paintings take turns when there is no wallpaper: those of where the phone is (by its
 * time zone, as the sun and the moon are), all of them, or one set.
 */
enum class KnineInkStyle(val id: String, val label: String) {
    Local("local", "Local scenes"),
    All("all", "All"),
    China("china", "Mountain landscapes"),
    America("america", "American landmarks"),
    ;

    companion object {
        fun of(id: String): KnineInkStyle = entries.firstOrNull { it.id == id && it != Local } ?: All
    }
}

/** Appearance preferences for the dashboard; Compose readers recompose when they change. */
/** Light or dark: as the phone is set, or KOne's own choice. */
enum class KnineThemeMode(val id: String, val label: String, val nightMode: Int) {
    System("system", "System default", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    Light("light", "Light", AppCompatDelegate.MODE_NIGHT_NO),
    Dark("dark", "Dark", AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        fun of(id: String?): KnineThemeMode = entries.firstOrNull { it.id == id } ?: System
    }
}

object KnineAppearance {
    // In plain preferences, not the settings database: it is read before the first frame.
    private val themePrefs by lazy { Application.application.getSharedPreferences("kone_appearance", Context.MODE_PRIVATE) }
    private val themeState by lazy { mutableStateOf(KnineThemeMode.of(themePrefs.getString("theme", null))) }

    /** Changing it redraws KOne in the new light at once; every page follows it, the paintings too. */
    var theme: KnineThemeMode
        get() = themeState.value
        set(value) {
            themeState.value = value
            themePrefs.edit().putString("theme", value.id).apply()
            AppCompatDelegate.setDefaultNightMode(value.nightMode)
        }

    /** Applies the saved choice; called before the window is first drawn. */
    fun applyTheme() = AppCompatDelegate.setDefaultNightMode(theme.nightMode)

    private val inkStyleState by lazy { mutableStateOf(KnineInkStyle.of(Settings.knineInkStyle)) }

    var inkStyle: KnineInkStyle
        get() = inkStyleState.value
        set(value) {
            inkStyleState.value = value
            Settings.knineInkStyle = value.id
        }

    private val switcherState by lazy { mutableStateOf(KnineSwitcherStyle.of(Settings.knineSwitcherStyle)) }

    var switcher: KnineSwitcherStyle
        get() = switcherState.value
        set(value) {
            switcherState.value = value
            Settings.knineSwitcherStyle = value.id
        }
}
