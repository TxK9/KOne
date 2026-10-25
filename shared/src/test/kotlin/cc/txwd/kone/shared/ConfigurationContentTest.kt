package cc.txwd.kone.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationContentTest {
    @Test fun formattingDoesNotDropActiveConnections() {
        assertTrue(ConfigurationContent.same("""{"route":{"rules":[{"domain":"example.com","outbound":"proxy"}]},"log":{"level":"warn"}}""",
            """{ "log": { "level": "warn" }, "route": { "rules": [{"outbound":"proxy","domain":"example.com"}] } }"""))
    }
    @Test fun ruleOrderAndDestinationsStillRequireAnUpdate() {
        assertFalse(ConfigurationContent.same("""{"rules":[1,2]}""", """{"rules":[2,1]}"""))
        assertFalse(ConfigurationContent.same("""{"outbound":"proxy"}""", """{"outbound":"direct"}"""))
        assertFalse(ConfigurationContent.same("""{"enabled":true}""", """{"enabled":"true"}"""))
    }
    @Test fun malformedOrNonObjectResponsesAreNotEquivalent() {
        assertFalse(ConfigurationContent.same("bad", " bad"))
        assertFalse(ConfigurationContent.same("null", " null"))
    }
}
