package cc.txwd.kone.app

import cc.txwd.kone.core.CoreApi
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.RuleSets
import cc.txwd.kone.shared.SubscriptionContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The node list's changes, listed as the core is given them (the console's node, then a
 * subscription's, merged and built): the chosen node followed through a rename and through
 * namesakes trading places, given way to Automatic in a line when it is gone, never taken for
 * another node; what was measured kept only with nodes exactly as measured; and a running core
 * left running only through a rename or a new order of exactly the nodes it runs, in the same
 * groups, and started afresh after anything else.
 */
class NodeContinuityTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val rules = RuleSets.ensure(File(Files.createTempDirectory("kone-rules").toFile(), "rules"))
    private val console = SubscriptionContent.read("trojan://pw@203.0.113.200:443#Console").nodes.single()

    /** The list with one subscription of [links] (or a subscription's [body]), as the core is given it. */
    private fun listing(vararg links: String, withConsole: Boolean = true, body: String = links.joinToString("\n")): NodeContinuity.Listing {
        val managed = JsonObject(mapOf("outbounds" to JsonArray(if (withConsole) listOf(console) else emptyList())))
        val subscribed = SubscriptionContent.read(body).nodes
        val source = Merge.into(managed, listOf(emptyList(), subscribed)).source
        return NodeContinuity.Listing.of(KoneConfig.build(source, rules, CoreApi(9090, "test")))
    }

    private fun serverOf(tag: String, list: NodeContinuity.Listing) = list.members.first { it["tag"]!!.jsonPrimitive.content == tag }["server"]!!.jsonPrimitive.content

    /** What the App would do, written down in order: the choice kept, the line said, the core started afresh. */
    private class App(override var selected: String?, override var through: Set<String> = emptySet()) : NodeContinuity.Host {
        val done = mutableListOf<String>()
        val delays = HashMap<String, Int?>()
        val health = Health()

        override fun keep(tag: String) {
            done += "keep $tag"
            selected = tag
        }

        override fun carry(carry: Map<String, String>) {
            delays.carry(carry)
            health.carry(carry)
        }

        override fun reload() {
            done += "reload"
        }

        override fun tell(message: String) {
            done += "tell $message"
        }
    }

    /** [app] following a list that starts as [start], with a core started on it when [running]. */
    private fun follow(app: App, start: NodeContinuity.Listing, running: Boolean = true) = NodeContinuity(app).also {
        it.listed(start)
        if (running) it.started(start)
    }

    @Test
    fun aRenamedNodeKeepsTheChoiceItsTestsAndTheConnection() {
        val app = App("JP 01 | 1.0x")
        val continuity = follow(app, listing("trojan://pw@203.0.113.1:443#JP 01 | 1.0x", "trojan://pw@203.0.113.2:443#US 01"))
        app.delays += mapOf("JP 01 | 1.0x" to 120, "US 01" to 240)
        app.health.record("JP 01 | 1.0x", 120)
        continuity.listed(listing("trojan://pw@203.0.113.2:443#US 01", "trojan://pw@203.0.113.1:443#JP 01 | 1.5x"))
        // Only the choice moves; the core carries on under the name it started with.
        assertEquals(listOf("keep JP 01 | 1.5x"), app.done)
        assertEquals(mapOf<String, Int?>("JP 01 | 1.5x" to 120, "US 01" to 240), app.delays)
        assertEquals(listOf<Int?>(120), app.health.samples("JP 01 | 1.5x"))
        assertEquals("JP 01 | 1.0x", continuity.running.core("JP 01 | 1.5x"))
        assertEquals("US 01", continuity.running.core("US 01"))
        assertEquals("Console", continuity.running.core("Console"))
    }

    @Test
    fun namesakesTradingPlacesKeepTheChosenServerItsTestsAndTheConnection() {
        val app = App("Tokyo 2")
        val start = listing("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo")
        val continuity = follow(app, start)
        assertEquals("203.0.113.2", serverOf("Tokyo 2", start))
        app.delays += mapOf("Tokyo" to 100, "Tokyo 2" to 200)
        app.health.record("Tokyo", 100)
        app.health.record("Tokyo 2", null)
        val after = listing("trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.1:443#Tokyo")
        continuity.listed(after)
        // Kept by its tag the choice would now be the other server: it follows its own to "Tokyo".
        assertEquals(listOf("keep Tokyo"), app.done)
        assertEquals("203.0.113.2", serverOf(app.selected!!, after))
        assertEquals(mapOf<String, Int?>("Tokyo" to 200, "Tokyo 2" to 100), app.delays)
        assertEquals(listOf<Int?>(null), app.health.samples("Tokyo"))
        assertEquals(listOf<Int?>(100), app.health.samples("Tokyo 2"))
        assertEquals("Tokyo 2", continuity.running.core("Tokyo"))
        assertEquals("Tokyo", continuity.running.core("Tokyo 2"))
    }

    @Test
    fun aNodeMovedToAnotherServerUnderItsTagStaysChosenAndTheCoreStartsAfresh() {
        val app = App("Paris")
        val continuity = follow(app, listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin"))
        app.delays += mapOf("Paris" to 90, "Berlin" to 110)
        app.health.record("Paris", 90)
        continuity.listed(listing("trojan://pw@198.51.100.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin"))
        // Still the node the person chose (the provider moved it and kept its name)...
        assertEquals("Paris", app.selected)
        // ...but what was measured of it measured the old server, and the running core still runs that one.
        assertEquals(mapOf<String, Int?>("Berlin" to 110), app.delays)
        assertEquals(emptyList(), app.health.samples("Paris"))
        assertNull(continuity.running.core("Paris"))
        assertEquals("Berlin", continuity.running.core("Berlin"))
        assertEquals(listOf("reload"), app.done)
    }

    @Test
    fun aNodeWithNewTlsOrTransportUnderItsTagStaysChosenAndTheCoreStartsAfresh() {
        fun osaka(sni: String) = "vless://$id@203.0.113.60:443?security=tls&sni=$sni&type=ws&host=$sni&path=%2Fa#Osaka"
        val app = App("Osaka")
        val continuity = follow(app, listing(osaka("a.invalid"), "trojan://pw@203.0.113.2:443#Berlin"))
        app.delays += mapOf("Osaka" to 150, "Berlin" to 110)
        // The same server and account, another name on TLS.
        continuity.listed(listing(osaka("b.invalid"), "trojan://pw@203.0.113.2:443#Berlin"))
        assertEquals("Osaka", app.selected)
        assertEquals(mapOf<String, Int?>("Berlin" to 110), app.delays)
        assertNull(continuity.running.core("Osaka"))
        assertEquals(listOf("reload"), app.done)
    }

    @Test
    fun aChosenNodeThatIsGoneGivesWayToAutomaticAndTheCoreStartsAfresh() {
        val app = App("Paris")
        val continuity = follow(app, listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin"))
        app.delays += mapOf("Paris" to 90, "Berlin" to 110)
        continuity.listed(listing("trojan://pw@203.0.113.2:443#Berlin"))
        // Automatic first, then the core afresh: its own Automatic would still hold Paris.
        assertEquals(listOf("keep ${KoneConfig.AUTO_TAG}", "tell Paris can no longer be found; using Automatic", "reload"), app.done)
        assertEquals(mapOf<String, Int?>("Berlin" to 110), app.delays)
    }

    @Test
    fun withNoCoreRunningTheChoiceAloneChanges() {
        // Automatic needs two nodes: with no console node and one of the subscription's left, that one.
        val app = App("Paris")
        val continuity = follow(app, listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin", withConsole = false), running = false)
        continuity.listed(listing("trojan://pw@203.0.113.2:443#Berlin", withConsole = false))
        assertEquals(listOf("keep Berlin", "tell Paris can no longer be found; using Berlin"), app.done)
    }

    @Test
    fun twoNodesReplacedByTwoOthersStartTheCoreAfreshThoughAutomaticStaysChosen() {
        val app = App(KoneConfig.AUTO_TAG)
        val continuity = follow(app, listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin", withConsole = false))
        app.delays += mapOf("Paris" to 90, "Berlin" to 110)
        continuity.listed(listing("trojan://pw@203.0.113.3:443#Rome", "trojan://pw@203.0.113.4:443#Madrid", withConsole = false))
        // The running core's Automatic holds Paris and Berlin: it starts afresh with Rome and Madrid.
        assertEquals(listOf("reload"), app.done)
        assertEquals(KoneConfig.AUTO_TAG, app.selected)
        assertNull(continuity.running.core("Rome"))
        assertNull(continuity.running.core("Madrid"))
        assertTrue(app.delays.isEmpty())
    }

    @Test
    fun aNodeLeavingTheGroupsStartsTheCoreAfreshThoughTheChoiceIsElsewhere() {
        val app = App(KoneConfig.AUTO_TAG)
        val continuity = follow(
            app,
            listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin", "trojan://pw@203.0.113.3:443#Rome", withConsole = false),
        )
        continuity.listed(listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin", withConsole = false))
        assertEquals(listOf("reload"), app.done)
    }

    @Test
    fun aRenameIntoAnotherCountrysGroupStartsTheCoreAfreshOnlyWhereTheRulesHaveThatGroup() {
        for ((through, done) in listOf(setOf("JP") to listOf("keep Seoul Premium", "reload"), emptySet<String>() to listOf("keep Seoul Premium"))) {
            val app = App("Tokyo Premium", through)
            val start = listing("trojan://pw@203.0.113.1:443#Tokyo Premium", "trojan://pw@203.0.113.2:443#Berlin")
            assertEquals("JP", start.nodes.first { it.tag == "Tokyo Premium" }.country)
            val continuity = follow(app, start)
            // The same server, renamed: the rules' group for Japan, if there is one, has lost it.
            val after = listing("trojan://pw@203.0.113.1:443#Seoul Premium", "trojan://pw@203.0.113.2:443#Berlin")
            assertEquals("KR", after.nodes.first { it.tag == "Seoul Premium" }.country)
            continuity.listed(after)
            assertEquals(done, app.done, "through $through")
        }
    }

    @Test
    fun aRelayRenamedLeavesTheCoreRunningAndARelayChangedStartsItAfresh() {
        fun chain(relay: String, server: String) = """
            {"outbounds": [
              {"type": "shadowsocks", "tag": "$relay", "server": "$server", "server_port": 8388, "method": "aes-128-gcm", "password": "x"},
              {"type": "trojan", "tag": "exit", "server": "203.0.113.7", "server_port": 443, "password": "y", "detour": "$relay"},
              {"type": "trojan", "tag": "other", "server": "203.0.113.8", "server_port": 443, "password": "z"}
            ]}
        """.trimIndent()
        val app = App("exit")
        val continuity = follow(app, listing(body = chain("relay", "203.0.113.6")))
        app.delays["exit"] = 300
        continuity.listed(listing(body = chain("relay A", "203.0.113.6")))
        assertEquals(emptyList(), app.done)
        assertEquals(300, app.delays["exit"])
        assertEquals("exit", continuity.running.core("exit"))
        // The relay now somewhere else: the chosen node goes another way, and was measured the old one.
        continuity.listed(listing(body = chain("relay A", "198.51.100.6")))
        assertEquals(listOf("reload"), app.done)
        assertEquals("exit", app.selected)
        assertNull(app.delays["exit"])
        assertNull(continuity.running.core("exit"))
    }

    @Test
    fun aNodeIsNotTakenForAnotherThatSharesItsServer() {
        // A CDN in front: one address and one account for both nodes, told apart by their names on TLS.
        fun cdn(sni: String, path: String, name: String) = "vless://$id@203.0.113.50:443?security=tls&sni=$sni&type=ws&host=$sni&path=%2F$path#$name"
        val app = App("JP")
        val continuity = follow(app, listing(cdn("us.invalid", "a", "US"), cdn("jp.invalid", "a", "JP")))
        continuity.listed(listing(cdn("us.invalid", "b", "US")))
        assertEquals(listOf("keep ${KoneConfig.AUTO_TAG}", "tell JP can no longer be found; using Automatic", "reload"), app.done)
    }

    @Test
    fun aNodeSplitIntoSeveralOnItsServerIsNotGuessedAt() {
        fun osaka(sni: String, name: String) = "vless://$id@203.0.113.60:443?security=tls&sni=$sni&type=ws&host=$sni&path=%2F$sni#$name"
        val app = App("Osaka")
        val continuity = follow(app, listing(osaka("a.invalid", "Osaka"), "trojan://pw@203.0.113.2:443#Berlin"))
        // Its server and account now carry two nodes, neither of them the one chosen: not even its name decides.
        continuity.listed(listing(osaka("b.invalid", "Osaka"), osaka("c.invalid", "Osaka"), "trojan://pw@203.0.113.2:443#Berlin"))
        assertEquals(listOf("keep ${KoneConfig.AUTO_TAG}", "tell Osaka can no longer be found; using Automatic", "reload"), app.done)
    }

    @Test
    fun aNodeIsNotFollowedToItsOldNameNowCarriedByAnotherServer() {
        val app = App("Tokyo 2")
        val continuity = follow(app, listing("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo"))
        app.delays += mapOf("Tokyo" to 100, "Tokyo 2" to 200)
        // The chosen server is gone; its tag now names the other one, and a new one is "Tokyo".
        continuity.listed(listing("trojan://pw@198.51.100.9:443#Tokyo", "trojan://pw@203.0.113.1:443#Tokyo"))
        // Said by the name it was listed under.
        assertEquals(listOf("keep ${KoneConfig.AUTO_TAG}", "tell Tokyo 2 can no longer be found; using Automatic", "reload"), app.done)
        // The other server's delay went with it; the one gone left nothing behind under its tag.
        assertEquals(mapOf<String, Int?>("Tokyo 2" to 100), app.delays)
    }

    @Test
    fun automaticACountryAndTheConsolesNodesAreLeftAsChosen() {
        for (choice in listOf(KoneConfig.AUTO_TAG, "auto@Japan", "Console")) {
            val app = App(choice)
            val continuity = follow(app, listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin"), running = false)
            continuity.listed(listing("trojan://pw@203.0.113.2:443#Berlin 2", "trojan://pw@203.0.113.3:443#Rome"))
            assertEquals(emptyList(), app.done, choice)
            assertEquals(choice, app.selected)
        }
    }

    @Test
    fun anEmptyListLeavesTheRunningCoreAsItIs() {
        val app = App("Paris")
        val continuity = follow(app, listing("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin"))
        app.delays["Paris"] = 90
        continuity.listed(NodeContinuity.Listing.NONE)
        // Nothing to follow it to and nothing to start the core with; what was measured goes with the list.
        assertEquals(emptyList(), app.done)
        assertEquals("Paris", app.selected)
        assertTrue(app.delays.isEmpty())
    }

    @Test
    fun theFirstListingAndOneLikeTheLastChangeNothing() {
        val app = App("Paris")
        // Listed first without the chosen node (as when the console's nodes come before a subscription's).
        val list = listing("trojan://pw@203.0.113.2:443#Berlin")
        val continuity = follow(app, list)
        continuity.listed(list)
        continuity.listed(listing("trojan://pw@203.0.113.2:443#Berlin"))
        assertEquals(emptyList(), app.done)
        assertTrue(continuity.running.same)
    }

    @Test
    fun aRunningCoreIsComparedWithWhatItStartedWithNotWithTheLastList() {
        val app = App("Tokyo 2")
        val start = listing("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.3:443#JP 01 | 1.0x")
        val continuity = follow(app, start)
        assertTrue(continuity.running.same)
        // The namesakes trade places and one node is renamed: the core carries on.
        continuity.listed(listing("trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.3:443#JP 01 | 1.5x"))
        // And back, with the rename kept: still the nodes it runs.
        continuity.listed(listing("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.3:443#JP 01 | 1.5x"))
        assertEquals(listOf("keep Tokyo", "keep Tokyo 2"), app.done)
        assertEquals("Tokyo", continuity.running.core("Tokyo"))
        assertEquals("JP 01 | 1.0x", continuity.running.core("JP 01 | 1.5x"))
        // A node added is one it does not run: afresh.
        continuity.listed(
            listing(
                "trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.3:443#JP 01 | 1.5x",
                "trojan://pw@203.0.113.4:443#Seoul",
            ),
        )
        assertEquals(listOf("keep Tokyo", "keep Tokyo 2", "reload"), app.done)
        assertNull(continuity.running.core("Seoul"))
        // Started afresh on the list as it is, it runs every node by its tag; stopped, it runs none.
        continuity.started(listing("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.3:443#JP 01 | 1.5x", "trojan://pw@203.0.113.4:443#Seoul"))
        assertTrue(continuity.running.same)
        continuity.stopped()
        assertTrue(continuity.running.same)
    }

    @Test
    fun onlyEntriesOfNodesExactlyAsTheyWereStayUnderTheirTagsNow() {
        val delays = hashMapOf<String, Int?>("Tokyo" to 100, "Tokyo 2" to 200, "Rome" to 300, "Seoul" to null)
        // Tokyo and Tokyo 2 traded tags; Seoul is as it was; Rome changed, and is not carried.
        delays.carry(mapOf("Tokyo" to "Tokyo 2", "Tokyo 2" to "Tokyo", "Seoul" to "Seoul"))
        assertEquals(mapOf<String, Int?>("Tokyo" to 200, "Tokyo 2" to 100, "Seoul" to null), delays)
    }
}
