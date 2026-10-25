package cc.txwd.kone.app

import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.SourceNode
import cc.txwd.kone.shared.SubscriptionContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chosen node through a subscription's updates, listed as the App lists it (the console's node,
 * then the subscription's, merged): followed through a rename and through namesakes trading places,
 * its delays and tests with it; given way to Automatic, in a line, when it is gone; never taken for
 * another node that shares its server or its old name; and a running core left on it under the
 * names it started with, rather than started afresh.
 */
class NodeContinuityTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val console = SubscriptionContent.read("trojan://pw@203.0.113.200:443#Console").nodes.single()

    /** The App's list with one subscription of [links]: every node's outbound, under the tag it is listed with. */
    private fun listing(vararg links: String, withConsole: Boolean = true): List<JsonObject> {
        val managed = JsonObject(mapOf("outbounds" to JsonArray(if (withConsole) listOf(console) else emptyList())))
        val subscribed = SubscriptionContent.read(links.joinToString("\n")).nodes
        return (Merge.into(managed, listOf(emptyList(), subscribed)).source["outbounds"] as JsonArray).map { it as JsonObject }
    }

    private fun tagOf(node: JsonObject) = node["tag"]!!.jsonPrimitive.content

    private fun serverOf(tag: String, list: List<JsonObject>) = list.first { tagOf(it) == tag }["server"]!!.jsonPrimitive.content

    private fun NodeContinuity.list(list: List<JsonObject>) = listed(list, list.map { SourceNode(tagOf(it), tagOf(it), null) })

    /** What the App would do, written down: the choice kept or made, the line said, and the delays and tests moved. */
    private class App(override var selected: String?) : NodeContinuity.Host {
        val kept = mutableListOf<String>()
        val chosen = mutableListOf<String>()
        val told = mutableListOf<String>()
        val delays = HashMap<String, Int?>()
        val health = Health()

        override fun keep(tag: String) {
            kept += tag
            selected = tag
        }

        override fun choose(tag: String) {
            chosen += tag
            selected = tag
        }

        override fun move(moves: Map<String, String>) {
            delays.rekey(moves)
            health.rename(moves)
        }

        override fun tell(message: String) {
            told += message
        }

        val untouched get() = kept.isEmpty() && chosen.isEmpty() && told.isEmpty()
    }

    @Test
    fun aRenamedNodeKeepsTheChoiceAndItsTests() {
        val app = App("JP 01 | 1.0x")
        val continuity = NodeContinuity(app)
        continuity.list(listing("trojan://pw@203.0.113.1:443#JP 01 | 1.0x", "trojan://pw@203.0.113.2:443#US 01"))
        app.delays += mapOf("JP 01 | 1.0x" to 120, "US 01" to 240)
        app.health.record("JP 01 | 1.0x", 120)
        continuity.list(listing("trojan://pw@203.0.113.2:443#US 01", "trojan://pw@203.0.113.1:443#JP 01 | 1.5x"))
        assertEquals(listOf("JP 01 | 1.5x"), app.kept)
        assertEquals(emptyList(), app.chosen + app.told)
        assertEquals(mapOf<String, Int?>("JP 01 | 1.5x" to 120, "US 01" to 240), app.delays)
        assertEquals(listOf<Int?>(120), app.health.samples("JP 01 | 1.5x"))
        assertEquals(emptyList(), app.health.samples("JP 01 | 1.0x"))
    }

    @Test
    fun namesakesTradingPlacesKeepTheChosenServer() {
        val app = App("Tokyo 2")
        val continuity = NodeContinuity(app)
        val before = listing("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo")
        continuity.list(before)
        assertEquals("203.0.113.2", serverOf("Tokyo 2", before))
        app.delays += mapOf("Tokyo" to 100, "Tokyo 2" to 200)
        app.health.record("Tokyo", 100)
        app.health.record("Tokyo 2", null)
        val after = listing("trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.1:443#Tokyo")
        continuity.list(after)
        // Kept by its tag the choice would now be the other server: it follows its own to "Tokyo".
        assertEquals(listOf("Tokyo"), app.kept)
        assertEquals("203.0.113.2", serverOf(app.selected!!, after))
        assertEquals(mapOf<String, Int?>("Tokyo" to 200, "Tokyo 2" to 100), app.delays)
        assertEquals(listOf<Int?>(null), app.health.samples("Tokyo"))
        assertEquals(listOf<Int?>(100), app.health.samples("Tokyo 2"))
        assertEquals(emptyList(), app.chosen + app.told)
    }

    @Test
    fun aChosenNodeThatIsGoneGivesWayToAutomaticInALine() {
        val app = App("Paris")
        val continuity = NodeContinuity(app)
        continuity.list(listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin"))
        app.delays += mapOf("Paris" to 90, "Berlin" to 110)
        continuity.list(listing("trojan://pw@203.0.113.2:443#Berlin"))
        assertEquals(listOf(KoneConfig.AUTO_TAG), app.chosen)
        assertEquals(listOf("Paris is no longer listed; using Automatic"), app.told)
        assertEquals(emptyList(), app.kept)
        // What is kept of the others stays as it was.
        assertEquals(110, app.delays["Berlin"])
    }

    @Test
    fun withOneNodeLeftThatNodeTakesTheChoice() {
        // Automatic needs two nodes: with no console node and one of the subscription's left, that one.
        val app = App("Paris")
        val continuity = NodeContinuity(app)
        continuity.list(listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin", withConsole = false))
        continuity.list(listing("trojan://pw@203.0.113.2:443#Berlin", withConsole = false))
        assertEquals(listOf("Berlin"), app.chosen)
        assertEquals(listOf("Paris is no longer listed; using Berlin"), app.told)
    }

    @Test
    fun aNodeIsNotTakenForAnotherThatSharesItsServer() {
        // A CDN in front: one address and one account for both nodes, told apart by their names on TLS.
        fun cdn(sni: String, path: String, name: String) = "vless://$id@203.0.113.50:443?security=tls&sni=$sni&type=ws&host=$sni&path=%2F$path#$name"
        val app = App("JP")
        val continuity = NodeContinuity(app)
        continuity.list(listing(cdn("us.invalid", "a", "US"), cdn("jp.invalid", "a", "JP")))
        continuity.list(listing(cdn("us.invalid", "b", "US")))
        assertEquals(listOf(KoneConfig.AUTO_TAG), app.chosen)
        assertEquals(listOf("JP is no longer listed; using Automatic"), app.told)
        assertEquals(emptyList(), app.kept)
    }

    @Test
    fun aNodeIsNotFollowedToItsOldNameNowCarriedByAnotherServer() {
        val app = App("Tokyo 2")
        val continuity = NodeContinuity(app)
        continuity.list(listing("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo"))
        app.delays += mapOf("Tokyo" to 100, "Tokyo 2" to 200)
        // The chosen server is gone; its tag now names the other one, and a new one is "Tokyo".
        continuity.list(listing("trojan://pw@198.51.100.9:443#Tokyo", "trojan://pw@203.0.113.1:443#Tokyo"))
        assertEquals(listOf(KoneConfig.AUTO_TAG), app.chosen)
        assertEquals(emptyList(), app.kept)
        // The other server's delay went with it; the one that is gone left nothing behind under its tag.
        assertEquals(mapOf<String, Int?>("Tokyo 2" to 100), app.delays)
    }

    @Test
    fun automaticACountryAndTheConsolesNodesAreLeftAsChosen() {
        for (choice in listOf(KoneConfig.AUTO_TAG, "auto@Japan", "Console")) {
            val app = App(choice)
            val continuity = NodeContinuity(app)
            continuity.list(listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin"))
            continuity.list(listing("trojan://pw@203.0.113.2:443#Berlin 2", "trojan://pw@203.0.113.3:443#Rome"))
            assertTrue(app.untouched, "$choice: ${app.kept} ${app.chosen} ${app.told}")
            assertEquals(choice, app.selected)
        }
    }

    @Test
    fun theFirstListingAndOneLikeTheLastChangeNothing() {
        val app = App("Paris")
        val continuity = NodeContinuity(app)
        // Listed first without the chosen node (as when the console's nodes come before a subscription's).
        val list = listing("trojan://pw@203.0.113.2:443#Berlin")
        continuity.list(list)
        continuity.list(list)
        assertTrue(app.untouched)
        assertEquals("Paris", app.selected)
    }

    @Test
    fun aRunningCoreCarriesOnUnderTheNamesItStartedWith() {
        val app = App("Tokyo 2")
        val continuity = NodeContinuity(app)
        val start = listing("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.3:443#JP 01 | 1.0x")
        continuity.list(start)
        continuity.started(start)
        assertTrue(continuity.running.same)
        // An update: the namesakes trade places, one node is renamed, and one is new.
        continuity.list(
            listing(
                "trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.3:443#JP 01 | 1.5x",
                "trojan://pw@203.0.113.4:443#Seoul",
            ),
        )
        // The choice follows its server; nothing is chosen anew, so the core is neither moved nor started afresh.
        assertEquals(listOf("Tokyo"), app.kept)
        assertEquals(emptyList(), app.chosen)
        val names = continuity.running
        assertEquals("Tokyo 2", names.core("Tokyo"))
        assertEquals("Tokyo", names.core("Tokyo 2"))
        assertEquals("JP 01 | 1.0x", names.core("JP 01 | 1.5x"))
        assertNull(names.core("Seoul"), "a node the running core does not carry")
        assertEquals("Console", names.core("Console"))
        assertEquals("Tokyo", names.app("Tokyo 2"))
        assertEquals("JP 01 | 1.5x", names.app("JP 01 | 1.0x"))
        // A second update is read against what the core started with, not against the last list.
        continuity.list(
            listing(
                "trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.3:443#JP 01 | 1.5x",
                "trojan://pw@203.0.113.4:443#Seoul",
            ),
        )
        assertEquals(listOf("Tokyo", "Tokyo 2"), app.kept)
        assertEquals("Tokyo", continuity.running.core("Tokyo"))
        assertEquals("JP 01 | 1.0x", continuity.running.core("JP 01 | 1.5x"))
        continuity.stopped()
        assertTrue(continuity.running.same)
    }

    @Test
    fun entriesMoveAtOnceSoNamesakesSwapAndAGoneNodesEntryGoes() {
        val delays = hashMapOf<String, Int?>("Tokyo" to 100, "Tokyo 2" to 200, "Rome" to 300, "Seoul" to null)
        // Tokyo and Tokyo 2 trade; Paris (untested) takes Rome's tag, Rome itself being gone.
        delays.rekey(mapOf("Tokyo" to "Tokyo 2", "Tokyo 2" to "Tokyo", "Paris" to "Rome"))
        assertEquals(mapOf<String, Int?>("Tokyo" to 200, "Tokyo 2" to 100, "Seoul" to null), delays)
    }
}
