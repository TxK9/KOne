package io.nekohasekai.sfa.preview

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h720dp-xhdpi")
class VisualChannelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun accountEnglishLight() = capture("account-en-light-320", false)
    @Test fun accountEnglishDark() = capture("account-en-dark-320", true)
    @Test @Config(qualifiers = "zh-rCN-w320dp-h720dp-xhdpi") fun accountChineseLight() = capture("account-zh-light-320", false)
    @Test @Config(qualifiers = "zh-rCN-w320dp-h720dp-xhdpi") fun accountChineseDark() = capture("account-zh-dark-320", true)
    @Test fun accountExpired() = capture("account-expired-en-light-320", false, "expired")
    @Test fun settingsEnglish() = capture("settings-en-light-320", false, page = "settings")
    @Test @Config(qualifiers = "zh-rCN-w320dp-h720dp-xhdpi") fun settingsChinese() = capture("settings-zh-light-320", false, page = "settings")
    @Test fun longNetworkValues() = capture("network-long-en-light-320", false, page = "tools")
    @Test fun languageDropdownShowsLanguageAutonyms() {
        compose.setContent { VisualPreviewScene(false, initialPage = "settings") }
        compose.onNodeWithText("Language").performClick()
        compose.onNodeWithText("English").assertIsDisplayed()
        compose.onAllNodesWithText("中文", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Русский", substring = true).assertIsDisplayed()
    }
    private fun capture(name: String, dark: Boolean, state: String = "synced", page: String = "account") {
        compose.setContent { VisualPreviewScene(dark, initialPage = page, state = state) }
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()
        val dir = File(System.getProperty("knine.previewDir")).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "$name.semantics.txt").writeText(compose.onRoot().printToString())
    }
}
