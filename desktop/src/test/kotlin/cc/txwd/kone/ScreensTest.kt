package cc.txwd.kone

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.txwd.kone.app.Used
import cc.txwd.kone.city.Cities
import cc.txwd.kone.city.CityGallery
import cc.txwd.kone.city.Sky
import cc.txwd.kone.city.WeatherProfile
import cc.txwd.kone.core.Carried
import cc.txwd.kone.core.CoreConnection
import cc.txwd.kone.core.UserRule
import cc.txwd.kone.ink.InkGallery
import cc.txwd.kone.node.Countries
import cc.txwd.kone.node.UserInfo
import cc.txwd.kone.ui.Appearance
import cc.txwd.kone.ui.CityScroll
import cc.txwd.kone.ui.Backdrop
import cc.txwd.kone.ui.Connection
import cc.txwd.kone.ui.DashboardActions
import cc.txwd.kone.ui.DashboardState
import cc.txwd.kone.ui.DashboardView
import cc.txwd.kone.ui.Flag
import cc.txwd.kone.ui.Flags
import cc.txwd.kone.ui.Frame
import cc.txwd.kone.ui.KeyState
import cc.txwd.kone.ui.KeyView
import cc.txwd.kone.ui.KoneTheme
import cc.txwd.kone.ui.Node
import cc.txwd.kone.ui.Page
import cc.txwd.kone.ui.SettingsActions
import cc.txwd.kone.ui.SettingsState
import cc.txwd.kone.ui.SettingsView
import cc.txwd.kone.ui.NodeSection
import cc.txwd.kone.ui.NodeWeather
import cc.txwd.kone.ui.NodesActions
import cc.txwd.kone.ui.NodesState
import cc.txwd.kone.ui.NodesView
import cc.txwd.kone.ui.RulesActions
import cc.txwd.kone.ui.RulesState
import cc.txwd.kone.ui.RulesView
import cc.txwd.kone.ui.SourceKind
import cc.txwd.kone.ui.TitleBar
import cc.txwd.kone.ui.ToolsActions
import cc.txwd.kone.ui.ToolsState
import cc.txwd.kone.ui.ToolsView
import cc.txwd.kone.ui.TrayIcon
import cc.txwd.kone.ui.TrayPanel
import cc.txwd.kone.ui.UpdateState
import cc.txwd.kone.ui.WithRail
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the pages at the preview's 1280 by 800, light and dark, without a window, and leaves
 * the PNGs in build/screens for comparing with docs/windows/preview.html. The nodes, delays and
 * the exit address are the preview's examples, not anyone's real servers.
 */
class ScreensTest {
    private val sample = DashboardState(
        categories = listOf("United States", "Japan", "Singapore"),
        nodes = listOf(
            Node("us1", "United States", "US", "United States", 245),
            Node("us2", "United States 2", "US", "United States", 312),
            Node("jp1", "Japan", "JP", "Japan", 186),
            Node("sg1", "Singapore", "SG", "Singapore", 1040),
        ),
        tab = "United States",
        selected = "us1",
        canImport = true,
    )
    private val online = sample.copy(connection = Connection.Online, exit = "203.0.113.24", home = true, downKbps = 18.6, upKbps = 1.2, seconds = 754, expiry = "3 days remaining")

    private val out = File(System.getProperty("kone.screens") ?: "build/screens").apply { mkdirs() }

    private fun shoot(name: String, dark: Boolean, width: Int = 1280, height: Int = 800, framed: Boolean = true, pointer: Offset? = null, content: @Composable () -> Unit) {
        runBlocking { InkGallery.paint(dark) }
        val scene = ImageComposeScene(width, height, Density(1f)) {
            KoneTheme(dark) { if (framed) Frame({ TitleBar() }) { content() } else content() }
        }
        try {
            // Twice: the first frame lays out, the second has the paintings in place.
            scene.render(0)
            // Where the mouse rests, for the hover wash.
            pointer?.let { scene.sendPointerEvent(PointerEventType.Move, it) }
            val image = scene.render(16_000_000)
            val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "could not encode $name" }
            val file = File(out, "$name-${if (dark) "dark" else "light"}.png")
            file.writeBytes(png.bytes)
            assertTrue(file.length() > 4_000, "$file looks empty")
        } finally {
            scene.close()
        }
    }

    @Test
    fun dashboard() {
        for (dark in listOf(false, true)) {
            shoot("dashboard", dark) { WithRail(Page.Dashboard, {}) { DashboardView(sample, DashboardActions(), false, {}, moving = false) } }
            shoot("dashboard-online", dark) { WithRail(Page.Dashboard, {}) { DashboardView(online, DashboardActions(), false, {}, moving = false) } }
        }
        // Auto chosen: its own tab, on the fastest node.
        val auto = sample.copy(
            categories = listOf("Auto") + sample.categories,
            nodes = listOf(Node("auto", "Auto · Japan", "JP", "Auto", 186)) + sample.nodes,
            tab = "Auto", selected = "auto",
        )
        shoot("dashboard-auto", dark = false) { WithRail(Page.Dashboard, {}) { DashboardView(auto, DashboardActions(), false, {}, moving = false) } }
        // Nodes the person imported, one of them chosen (its × takes it out), with ⋮ open.
        val imported = sample.copy(
            categories = sample.categories + "Imported",
            nodes = sample.nodes + Node("i1", "Friend's node", null, "Imported", 220, imported = true) + Node("i2", "Imported 2", null, "Imported", null, tested = false, imported = true),
            tab = "Imported", selected = "i1", hint = "Imported 2 nodes",
        )
        shoot("dashboard-imported", dark = false) { WithRail(Page.Dashboard, {}) { DashboardView(imported, DashboardActions(), true, {}, moving = false) } }
        // The smallest the window goes.
        shoot("dashboard-smallest", dark = false, width = 960, height = 620) {
            WithRail(Page.Dashboard, {}) { DashboardView(online, DashboardActions(), false, {}, moving = false) }
        }
    }

    /**
     * Background set to Cities: the cities of the node's country, each in its own daylight. Tokyo at noon
     * and at nine at night, Texas at sunset, and Hong Kong's own scene first for a Hong Kong node.
     */
    @Test
    fun cities() {
        val japan = sample.copy(tab = "Japan", selected = "jp1")
        val hongKong = sample.copy(
            categories = listOf("Hong Kong"), nodes = listOf(Node("hk1", "Hong Kong", "HK", "Hong Kong", 42)), tab = "Hong Kong", selected = "hk1",
        )
        for ((name, state, at) in listOf(
            Triple("city-japan-day", japan, "2026-10-17T03:00:00Z"),
            Triple("city-japan-night", japan, "2026-10-17T12:00:00Z"),
            Triple("city-us-dusk", sample, "2026-10-17T23:50:00Z"),
            Triple("city-hongkong", hongKong, "2026-10-17T13:00:00Z"),
        )) {
            val instant = Instant.parse(at)
            val country = state.current?.country
            val scenes = Cities.pick(country, instant.atZone(ZoneId.systemDefault()).toLocalDate())
            assertTrue(scenes.isNotEmpty(), "no scenes for $country")
            runBlocking { CityGallery.load(scenes) }
            for (dark in listOf(false, true)) {
                shoot(name, dark) {
                    WithRail(Page.Dashboard, {}) { DashboardView(state, DashboardActions(), false, {}, moving = false, cities = true, at = instant) }
                }
            }
        }
    }

    /**
     * The cities' real weather (fixed here, made-up values): Tokyo in a night storm, Oslo under
     * snow, London in fog, and a city in low warm sun with its light on the facades and water.
     */
    @Test
    fun weather() {
        val storm = WeatherProfile(cloudCover = .98, rain = .94, fog = .26, wind = 1.7, wetness = 1.0, temperature = 17.0)
        val snow = WeatherProfile(cloudCover = .87, snow = .7, fog = .21, wind = 1.02, wetness = .3, temperature = -3.0)
        val fog = WeatherProfile(cloudCover = .65, fog = .76, wind = .59, wetness = .3, temperature = 14.0)
        val sunny = WeatherProfile(cloudCover = .16, fog = .025, wind = .78, temperature = 23.0)
        val low = lowSun()
        for ((name, country, at, profile) in listOf(
            Shot("weather-storm", "JP", Instant.parse("2026-10-17T12:00:00Z"), storm),
            Shot("weather-snow", "NO", Instant.parse("2026-12-17T18:00:00Z"), snow),
            Shot("weather-fog", "GB", Instant.parse("2026-10-17T09:00:00Z"), fog),
            Shot("weather-low-sun", low.first, low.second, sunny),
        )) {
            val scenes = Cities.pick(country, at.atZone(ZoneId.systemDefault()).toLocalDate())
            runBlocking { CityGallery.load(scenes) }
            val weather = scenes.associate { it.cityId to profile }
            for (dark in listOf(false, true)) {
                shoot(name, dark) { WithRail(Page.Dashboard, {}) { CityScroll(country, moving = false, at = at, weather = weather) } }
            }
        }
    }

    private data class Shot(val name: String, val country: String, val at: Instant, val weather: WeatherProfile)

    /** The first country and time today when the first city on its scroll has the sun low in its painting. */
    private fun lowSun(): Pair<String, Instant> {
        val day = Instant.parse("2026-10-17T00:00:00Z")
        for (country in listOf("JP", "US", "FR", "IT", "AU", "GB", "ES", "TR")) {
            val scene = Cities.pick(country, day.atZone(ZoneId.systemDefault()).toLocalDate()).first()
            for (minute in 0 until 24 * 60 step 5) {
                val at = day.plusSeconds(minute * 60L)
                val sky = Sky.at(scene, at.toEpochMilli())
                if (sky.body?.moon == false && sky.sunAltitude in 4.0..20.0) return country to at
            }
        }
        error("no low sun in sight anywhere")
    }

    /** Every flag, large and at the size a node shows it; Taiwan alone is left without one. */
    @Test
    fun flags() {
        val codes = Countries.all.map { it.code }
        assertTrue(codes.filter { it != "TW" }.all { it in Flags.drawn }, "no flag for ${codes.filter { it != "TW" && it !in Flags.drawn }}")
        shoot("flags", dark = false, width = 820, height = 420, framed = false) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                codes.chunked(13).forEach { row ->
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        row.forEach { code ->
                            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Flag(code, 48.dp, 34.dp)
                                Flag(code)
                            }
                        }
                    }
                }
            }
        }
    }

    /** The mouse resting on a node: the hand and a faint wash of ink under it. */
    @Test
    fun hover() {
        for (dark in listOf(false, true)) {
            shoot("dashboard-hover", dark, pointer = Offset(390f, 672f)) {
                WithRail(Page.Dashboard, {}) { DashboardView(sample, DashboardActions(), false, {}, moving = false) }
            }
        }
    }

    /** Nodes: the console's, one imported, a subscription with its use and expiry, and one whose update failed. */
    @Test
    fun nodes() {
        val now = 1_800_000_000_000L
        val state = NodesState(
            sections = listOf(
                NodeSection("managed", SourceKind.Managed, "Managed", listOf(
                    Node("us1", "United States", "US", "United States", 245, protocol = "vless"),
                    Node("jp1", "Japan", "JP", "Japan", 186, protocol = "trojan"),
                )),
                NodeSection("imported", SourceKind.Imported, "Imported", listOf(Node("i1", "Friend's node", null, "Imported", 220, imported = true, protocol = "hysteria2"))),
                NodeSection(
                    "s1", SourceKind.Subscription, "Example Cloud", listOf(
                        Node("s1a", "Hong Kong 01", "HK", "Hong Kong", 42, protocol = "shadowsocks"),
                        Node("s1b", "Singapore 02", "SG", "Singapore", 1040, protocol = "vmess"),
                        Node("s1c", "Germany 03", "DE", "Germany", null, protocol = "tuic"),
                    ),
                    info = UserInfo(2_000_000_000, 11_000_000_000, 107_374_182_400, (now / 1000) + 40L * 86_400),
                    updated = now - 3 * 3_600_000L, hours = 12, url = "https://example.com/sub",
                ),
                NodeSection("s2", SourceKind.Subscription, "Backup", emptyList(), error = "The provider cannot be reached", hours = 24, url = "https://example.net/sub"),
            ),
            selected = "jp1",
        )
        for (dark in listOf(false, true)) {
            shoot("nodes", dark) { WithRail(Page.Nodes, {}) { NodesView(state, NodesActions(), now) } }
        }
    }

    /** Tools while connected: the mode, two programs sent straight out, and what the core carries (example sites only). */
    @Test
    fun tools() {
        val carried = Carried(
            down = 1_288_490_188, up = 31_457_280,
            connections = listOf(
                CoreConnection("1", "chrome.exe", "www.example.com:443", "United States", 3_481_600, 52_224),
                CoreConnection("2", "Code.exe", "api.example.org:443", "United States", 802_816, 31_744),
                CoreConnection("3", "WeChat.exe", "203.0.113.40:8080", "direct", 120_832, 9_216),
                CoreConnection("4", "svchost.exe", "time.example.net:123", "direct", 96, 96),
                CoreConnection("5", null, "203.0.113.77:53", "United States", 512, 128),
            ),
        )
        val state = ToolsState("Rule", listOf("WeChat.exe", "Steam.exe"), carried, today = Used(1_288_490_189, 64_424_509), month = Used(19_756_849_561, 1_073_741_824))
        for (dark in listOf(false, true)) {
            shoot("tools", dark) { WithRail(Page.Tools, {}) { ToolsView(state, ToolsActions()) } }
        }
    }

    /** Rules: ads blocked, the lists, and the person's own rules in order (example names only). */
    @Test
    fun rules() {
        val state = RulesState(
            bypass = listOf("WeChat.exe", "Steam.exe"), direct = listOf("example.cn"), proxied = listOf("example.com", "example.org"),
            rules = listOf(
                UserRule("keyword", "tracker", "block"), UserRule("suffix", "example.net", "proxy"), UserRule("cidr", "203.0.113.0/24", "direct"),
                UserRule("program", "Game.exe", "direct"), UserRule("port", "6881-6889", "direct"),
            ),
            blockAds = true,
        )
        for (dark in listOf(false, true)) {
            shoot("rules", dark) { WithRail(Page.Rules, {}) { RulesView(state, RulesActions()) } }
        }
    }

    /** Settings with a newer KOne out, and Start with Windows off as it starts. */
    @Test
    fun settings() {
        val state = SettingsState(Appearance.System, Backdrop.City, autostart = false, hotkey = true, version = "1.0.0", update = UpdateState.Available("1.0.7"))
        for (dark in listOf(false, true)) {
            shoot("settings", dark) {
                WithRail(Page.Settings, {}, listOf(Page.Dashboard, Page.Log, Page.Settings), marked = setOf(Page.Settings)) {
                    SettingsView(state, SettingsActions())
                }
            }
        }
    }

    /** The tray panel in each weather: S, M and ! while connected, Z when not. */
    @Test
    fun trayPanel() {
        val weathers = listOf("qing" to 245, "wu" to 1180, "duan" to null)
        for (dark in listOf(false, true)) {
            for ((name, delay) in weathers) {
                val state = online.copy(nodes = online.nodes.map { if (it.id == "us1") it.copy(delay = delay) else it })
                shoot("tray-$name", dark, 360, 420, framed = false) { TrayPanel(state, {}, {}, {}, moving = false) }
            }
            shoot("tray-xie", dark, 360, 420, framed = false) { TrayPanel(sample, {}, {}, {}, moving = false) }
        }
    }

    /** The tray icon on a dark and a light taskbar, in all four weathers. */
    @Test
    fun trayIcons() {
        for (dark in listOf(true, false)) {
            val sheet = java.awt.image.BufferedImage(4 * 56, 56, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = sheet.createGraphics()
            g.color = java.awt.Color(if (dark) 0x202020 else 0xE6E8EB)
            g.fillRect(0, 0, sheet.width, sheet.height)
            NodeWeather.entries.forEachIndexed { index, weather -> g.drawImage(TrayIcon.image(weather, dark, 40), index * 56 + 8, 8, null) }
            g.dispose()
            javax.imageio.ImageIO.write(sheet, "png", File(out, "tray-icons-${if (dark) "dark" else "light"}.png"))
        }
    }

    @Test
    fun key() {
        for (dark in listOf(false, true)) {
            shoot("key", dark) { KeyView(KeyState(), {}, {}, {}, moving = false) }
            shoot("key-filled", dark) { KeyView(KeyState("kone-7q4m-x2p9-hd3k"), {}, {}, {}, moving = false) }
        }
    }
}
