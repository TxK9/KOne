package cc.txwd.kone

import cc.txwd.kone.app.Preferences
import cc.txwd.kone.i18n.Language
import cc.txwd.kone.i18n.Strings
import cc.txwd.kone.i18n.tr
import cc.txwd.kone.console.ClaimLink
import cc.txwd.kone.console.ConsoleFailure
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.ui.Appearance
import cc.txwd.kone.ui.Page
import cc.txwd.kone.ui.NodeWeather
import java.nio.file.Files
import java.util.Locale
import kotlin.test.*

class LocalizationTest {
    @Test fun systemLocaleAndExplicitChoiceAreIndependent() {
        assertTrue(Language.isChinese(Language.System, Locale.SIMPLIFIED_CHINESE))
        assertTrue(Language.isChinese(Language.System, Locale.forLanguageTag("zh-SG")))
        assertTrue(Language.isChinese(Language.SimplifiedChinese, Locale.US))
        assertFalse(Language.isChinese(Language.English, Locale.SIMPLIFIED_CHINESE))
        assertFalse(Language.isChinese(Language.System, Locale.TRADITIONAL_CHINESE))
    }

    @Test fun catalogKeepsEveryPlaceholderAndCanFallBackWithoutChangingData() {
        val tokens = Regex("\\{\\d+\\}")
        assertTrue(Strings.translations.size >= 300)
        Strings.translations.forEach { (english, translated) ->
            assertTrue(translated.isNotBlank(), english)
            assertEquals(tokens.findAll(english).map { it.value }.toList().sorted(), tokens.findAll(translated).map { it.value }.toList().sorted(), english)
        }
        assertEquals("正在下载 37%", Strings.text("Downloading {0}%", listOf(37), chinese = true))
        assertEquals("Downloading 37%", Strings.text("Downloading {0}%", listOf(37), chinese = false))
        assertEquals("通过 Global {1}", Strings.text("Through {0}", listOf("Global {1}"), chinese = true))
        assertEquals("my-node.example", Strings.text("my-node.example", chinese = true))
    }

    @Test fun languageChangesRefreshEnumsAndLeaveProtocolValuesAndKeysIntact() {
        val before = Strings.language
        try {
            Strings.language = Language.SimplifiedChinese
            assertEquals("仪表", Page.Dashboard.title)
            assertEquals("浅色", Appearance.Light.title)
            assertEquals("晴", NodeWeather.Clear.char)
            assertEquals("开通密钥无效、已使用或已过期", ConsoleFailure.InviteInvalid.message)
            assertTrue("Rule" in KoneConfig.MODES)
            val link = "kone://claim?server=https%3A%2F%2Fconsole.example.com&code=example_code"
            assertEquals("example_code", ClaimLink.parse(link)?.code)
            Strings.language = Language.English
            assertEquals("Dashboard", Page.Dashboard.title)
            assertEquals("Light", Appearance.Light.title)
            assertEquals("S", NodeWeather.Clear.char)
            assertEquals("Get started", tr("Get started"))
        } finally { Strings.language = before }
    }

    @Test fun savedLanguageSurvivesRestartAndUnknownValuesUseSystem() {
        val directory = Files.createTempDirectory("kone-language-test").toFile()
        try {
            Preferences(directory).language = Language.SimplifiedChinese
            assertEquals(Language.SimplifiedChinese, Preferences(directory).language)
            Preferences(directory).language = Language.English
            assertEquals(Language.English, Preferences(directory).language)
            assertEquals(Language.System, Language.of("unknown"))
        } finally { directory.deleteRecursively() }
    }
}
