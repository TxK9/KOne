package cc.txwd.kone.console

import cc.txwd.kone.core.Ends
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllerInfoTest {
    private fun parse(text: String, requireNodes: Boolean = true) = ControllerInfo.read(Json.parseToJsonElement(text) as JsonObject, requireNodes)
    private val value = """{"format":1,"deviceId":"device","mode":"enabled","required":true,"authGeneration":7,"nodeIds":["entry","relay"],"ownerPublish":false}"""

    @Test fun `wire strings cannot impersonate integer or boolean authority fields`() {
        for (changed in listOf(value.replace("\"format\":1", "\"format\":\"1\""), value.replace("\"authGeneration\":7", "\"authGeneration\":\"7\""), value.replace("\"required\":true", "\"required\":\"true\""))) {
            assertFailsWith<ConsoleException> { parse(changed) }
        }
    }

    @Test fun `registration may omit grants but an enabled connection may not`() {
        val empty = value.replace("[\"entry\",\"relay\"]", "[]")
        parse(empty, requireNodes = false)
        assertFailsWith<ConsoleException> { parse(empty) }
        parse(value)
        assertFailsWith<ConsoleException> { parse(value.replace("[\"entry\",\"relay\"]", "[\"entry\",\"entry\"]")) }
    }

    @Test fun `only changes that can be freshly resynchronized may retry automatically`() {
        for (cause in listOf("controller_network_changed", "controller_stale_generation", "controller_stale_config")) assertTrue(Ends.passing(cause))
        for (cause in listOf("controller_device_revoked", "controller_account_denied", "controller_authorization_revoked", "controller_no_grants", "controller_unavailable", "controller_invalid", "controller_service_required")) assertFalse(Ends.passing(cause))
    }
}
