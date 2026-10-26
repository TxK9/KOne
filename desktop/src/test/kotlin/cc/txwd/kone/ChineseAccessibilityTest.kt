package cc.txwd.kone

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import cc.txwd.kone.i18n.Language
import cc.txwd.kone.i18n.Strings
import cc.txwd.kone.ui.*
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ChineseAccessibilityTest {
    @Test fun activationControlsUseChineseAccessibleNames() {
        val original = Strings.language
        try {
            Strings.language = Language.SimplifiedChinese
            runComposeUiTest {
                setContent { KoneTheme(false) { KeyView(KeyState(), {}, {}, {}, moving = false) } }
                onNodeWithContentDescription("开通密钥").assertExists()
                onNodeWithContentDescription("粘贴开通密钥").assertExists()
                onNodeWithText("开始使用").assertExists()
            }
        } finally { Strings.language = original }
    }
}
