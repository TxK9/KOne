package cc.txwd.kone

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import cc.txwd.kone.app.AppModel
import cc.txwd.kone.app.Places
import cc.txwd.kone.app.Preferences
import cc.txwd.kone.app.Screen
import cc.txwd.kone.app.SettingsModel
import cc.txwd.kone.app.SingleInstance
import cc.txwd.kone.app.WindowPlace
import cc.txwd.kone.app.WindowsShell
import cc.txwd.kone.city.Ambient
import cc.txwd.kone.city.CityGallery
import cc.txwd.kone.ink.InkGallery
import cc.txwd.kone.ui.Appearance
import cc.txwd.kone.ui.Backdrop
import cc.txwd.kone.ui.Connection
import cc.txwd.kone.ui.Palette
import cc.txwd.kone.ui.DashboardActions
import cc.txwd.kone.ui.DashboardView
import cc.txwd.kone.ui.Frame
import cc.txwd.kone.ui.KeyView
import cc.txwd.kone.ui.KoneTheme
import cc.txwd.kone.ui.LocalAmbient
import cc.txwd.kone.ui.LocalSoftware
import cc.txwd.kone.ui.LogView
import cc.txwd.kone.ui.NodeWeather
import cc.txwd.kone.ui.NetworkActions
import cc.txwd.kone.ui.NodesActions
import cc.txwd.kone.ui.NodesView
import cc.txwd.kone.ui.RulesActions
import cc.txwd.kone.ui.RulesView
import cc.txwd.kone.ui.Page
import cc.txwd.kone.ui.SettingsActions
import cc.txwd.kone.ui.SettingsView
import cc.txwd.kone.ui.TitleBar
import cc.txwd.kone.ui.ToolsActions
import cc.txwd.kone.ui.ToolsView
import cc.txwd.kone.ui.TrayIcon
import cc.txwd.kone.ui.TrayPanel
import cc.txwd.kone.ui.UpdateState
import cc.txwd.kone.ui.WithRail
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import org.jetbrains.skia.Image
import java.awt.GraphicsEnvironment
import java.awt.CheckboxMenuItem
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.roundToInt

@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    // A kone:// link clicked in a browser arrives as the first argument; --tray when Windows
    // starts KOne as the person signs in (Settings → Start with Windows).
    val link = args.firstOrNull { it.startsWith("kone://", ignoreCase = true) }
    val atSignIn = "--tray" in args
    val inbox = Channel<String>(Channel.UNLIMITED)
    if (!SingleInstance(Places.data).claim(link ?: if (atSignIn) "tray" else "show") { inbox.trySend(it) }) return
    link?.let { inbox.trySend(it) }

    application {
        val scope = rememberCoroutineScope()
        val preferences = remember { Preferences(Places.data) }
        val model = remember { AppModel(scope, preferences) }
        // Where the person left it, if that is still on a screen; else 1280 by 800 in the middle.
        val window = remember {
            val place = preferences.window?.takeIf(::onScreen)
            WindowState(
                placement = if (place?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
                size = place?.let { DpSize(it.width.dp, it.height.dp) } ?: firstSize(),
                position = place?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
            )
        }
        LaunchedEffect(window) {
            // Kept as it changes, a second after it settles, so even a shutdown leaves it noted.
            // Maximized, the place it will return to stays the one noted last.
            snapshotFlow { Triple(window.position, window.size, window.placement) }.drop(1).debounce(1000).collect { (position, size, placement) ->
                when {
                    placement == WindowPlacement.Maximized -> preferences.window?.let { preferences.window = it.copy(maximized = true) }
                    position is WindowPosition.Absolute -> preferences.window =
                        WindowPlace(position.x.value.roundToInt(), position.y.value.roundToInt(), size.width.value.roundToInt(), size.height.value.roundToInt(), maximized = false)
                }
            }
        }
        // Started at sign-in with a key in place, KOne waits by the clock; otherwise it opens.
        var shown by remember { mutableStateOf(!(atSignIn && model.screen == Screen.Dashboard && SystemTray.isSupported())) }
        var panel by remember { mutableStateOf(false) }
        // When the panel lost focus to a click on the tray icon, that click must not open it again.
        var panelClosedAt by remember { mutableStateOf(0L) }
        var page by remember { mutableStateOf(Page.Dashboard) }
        // The command palette (Ctrl+K), once a key is in.
        var palette by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        val icon = remember { BitmapPainter(Image.makeFromEncoded(resource("kone.png")).toComposeImageBitmap()) }
        val ambient = remember { Ambient() }

        fun quit() {
            ambient.mix = null
            model.close()
            exitApplication()
        }

        val settings = remember { SettingsModel(scope, preferences, onQuit = ::quit, onHotkey = model::power) }
        var systemDark by remember { mutableStateOf(WindowsShell.appsDark()) }
        LaunchedEffect(settings.state.appearance) {
            // Following Windows: a change there shows here within a few seconds.
            while (settings.state.appearance == Appearance.System) {
                systemDark = WindowsShell.appsDark()
                delay(3000)
            }
        }
        val dark = when (settings.state.appearance) {
            Appearance.System -> systemDark
            Appearance.Light -> false
            Appearance.Dark -> true
        }

        LaunchedEffect(Unit) {
            for (message in inbox) {
                if (message == "tray") continue
                if (message.startsWith("kone://", ignoreCase = true)) model.offerKey(message)
                shown = true
                window.isMinimized = false
            }
        }

        Tray(
            model,
            windowInSight = { shown && !window.isMinimized },
            onOpen = { shown = true; window.isMinimized = false },
            onPanel = { if (panel || System.currentTimeMillis() - panelClosedAt > 400) panel = !panel },
            onQuit = ::quit,
        )

        // A minute in the tray, the window and its paintings are let go (tens of MB): it comes
        // back as it was, page and all, when KOne is opened again.
        var resting by remember { mutableStateOf(false) }
        LaunchedEffect(shown) {
            if (shown) {
                resting = false
            } else {
                delay(60_000)
                resting = true
                delay(2_000)
                CityGallery.rest()
                InkGallery.rest()
                System.gc()
            }
        }
        if (!resting) {
            Window(
                onCloseRequest = {
                    // With a binding, closing keeps KOne by the clock, connected or not; without one there is nothing to keep.
                    if (model.screen == Screen.Dashboard && SystemTray.isSupported()) shown = false else quit()
                },
                state = window, visible = shown, title = "KOne", icon = icon, undecorated = true,
                onPreviewKeyEvent = { event ->
                    // Ctrl+K opens the command palette from anywhere, a text field included.
                    (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.K && model.screen == Screen.Dashboard)
                        .also { if (it) palette = !palette }
                },
                onKeyEvent = { event ->
                    // Esc folds the ⋮ menu away.
                    (event.key == Key.Escape && event.type == KeyEventType.KeyDown && menu).also { if (it) menu = false }
                },
            ) {
                LaunchedEffect(shown) { if (shown) { this@Window.window.toFront(); this@Window.window.requestFocus() } }
                // The connections are asked for only while Tools is in sight and KOne connected.
                val online = model.dashboard.connection == Connection.Online
                LaunchedEffect(page, online, shown) { model.watchConnections(page == Page.Tools && online && shown) }
                // The scrolls stand still while no one can see them, and step slowly when Windows draws in software.
                val inSight = shown && !window.isMinimized
                var software by remember { mutableStateOf(false) }
                DisposableEffect(Unit) {
                    val composeWindow = this@Window.window
                    fun check() { software = composeWindow.renderApi.name.startsWith("SOFTWARE") }
                    composeWindow.onRenderApiChanged(::check)
                    check()
                    onDispose {}
                }
                LaunchedEffect(Unit) { this@Window.window.minimumSize = java.awt.Dimension(MINIMUM.width.value.toInt(), MINIMUM.height.value.toInt()) }
                KoneTheme(dark) {
                  // The cities are heard only when the person asked, on Dashboard, with the window in sight.
                  val heard = settings.state.sound && settings.state.backdrop == Backdrop.City && page == Page.Dashboard && inSight
                  CompositionLocalProvider(LocalSoftware provides software, LocalAmbient provides ambient.takeIf { heard }) {
                    Box {
                      Frame({
                          TitleBar(
                              onMinimize = { window.isMinimized = true },
                              onMaximize = {
                                  window.placement = if (window.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
                              },
                              onClose = { if (model.screen == Screen.Dashboard && SystemTray.isSupported()) shown = false else quit() },
                              drag = { content -> WindowDraggableArea { androidx.compose.foundation.layout.Row(content = content) } },
                          )
                      }) {
                          when (model.screen) {
                              Screen.Key -> KeyView(
                                  model.key,
                                  onKey = model::typeKey,
                                  onPaste = { clipboardText()?.let(model::typeKey) },
                                  onStart = model::claim,
                                  moving = inSight,
                              )
                              Screen.Dashboard -> WithRail(
                                  page, { page = it }, listOf(Page.Dashboard, Page.Nodes, Page.Rules, Page.Log, Page.Tools, Page.Settings),
                                  marked = if (settings.state.update is UpdateState.Available) setOf(Page.Settings) else emptySet(),
                              ) {
                                  when (page) {
                                      Page.Nodes -> NodesView(
                                          model.nodesPage,
                                          NodesActions(
                                              onUse = model::pickNode,
                                              onTest = model::testAll,
                                              onAddSubscription = model::addSubscription,
                                              onUpdate = model::updateSubscription,
                                              onEdit = model::editSubscription,
                                              onRemoveSubscription = model::removeSubscription,
                                              onRemoveNode = model::removeImported,
                                              onImport = { model.importText(clipboardText().orEmpty()) },
                                              onCopyLink = { tag -> model.copyLink(tag, ::copyText) },
                                              onQr = model::showQr,
                                              onAddNode = model::addNode,
                                              onSpeed = model::testSpeed,
                                              onEditNode = model::editNode,
                                              onSaveNode = model::saveNode,
                                              onCancelNode = model::cancelNode,
                                              onExport = {
                                                  val links = model.exportLinks()
                                                  if (links.isEmpty()) model.onNotice("No nodes of yours to export") else saveText(this@Window.window, "kone-nodes.txt", links)
                                              },
                                          ),
                                      )
                                      Page.Log -> LogView(
                                          model.logs,
                                          onCopy = { shown -> copyText(shown.joinToString("\n")) },
                                          onClear = { model.logs.clear() },
                                      )
                                      Page.Rules -> RulesView(
                                          model.rulesPage,
                                          RulesActions(
                                              onBypass = model::setBypass,
                                              onAddProgram = { pickProgram(this@Window.window)?.let { model.setBypass(it, true) } },
                                              onAddSite = model::addSite,
                                              onRemoveSite = model::removeSite,
                                              onBlockAds = model::setBlockAds,
                                              onAddRule = model::addRule,
                                              onRemoveRule = model::removeRule,
                                              onMoveRule = model::moveRule,
                                              onUnmatched = model::setUnmatched,
                                              onUpdateLists = { model.updateLists(force = true) },
                                          ),
                                      )
                                      Page.Tools -> ToolsView(
                                          model.tools,
                                          ToolsActions(
                                              onMode = model::pickMode,
                                              onWay = model::pickWay,
                                              onBypass = model::setBypass,
                                              onClose = model::closeConnection,
                                              onDiagnose = model::diagnose,
                                              onClearChecks = model::clearChecks,
                                              onLookUp = model::lookUp,
                                              onClearLookup = model::clearLookup,
                                              onUsage = model::showUsage,
                                              onCheckSite = model::checkSite, onKeepSite = model::keepSite, onClearSite = model::clearSite,
                                              onTakeAdvice = model::takeAdvice, onDismissAdvice = model::dismissAdvice,
                                              onSendSite = model::sendSite,
                                          ),
                                      )
                                      Page.Settings -> SettingsView(
                                          settings.state,
                                          SettingsActions(
                                              onAppearance = settings::pickAppearance,
                                              onBackdrop = settings::pickBackdrop,
                                              onSound = settings::setSound,
                                              onAutostart = settings::setAutostart,
                                              onHotkey = settings::setHotkey,
                                              onCheck = settings::check,
                                              onUpdate = settings::update,
                                          ),
                                          model.network,
                                          NetworkActions(
                                              onRemoteDns = model::setRemoteDns, onDirectDns = model::setDirectDns, onIpv6 = model::setIpv6, onFailover = model::setFailover,
                                              onAddHost = model::addHost, onRemoveHost = model::removeHost,
                                              onExport = {
                                                  // It holds subscription links and node passwords: the person is told so.
                                                  if (saveText(this@Window.window, "kone-backup.json", model.backup())) model.onNotice("Backup saved. It holds your subscription links; keep it private.")
                                              },
                                              onImport = { pickText(this@Window.window)?.let { model.onNotice(model.restore(it) ?: "That is not a KOne backup") } },
                                          ),
                                      )
                                      else -> DashboardView(
                                          model.dashboard,
                                          DashboardActions(
                                              onTab = model::pickTab, onNode = model::pickNode, onPower = model::power, onRemove = model::removeImported,
                                              onSignIn = model::signIn, onCancelSignIn = model::cancelSignIn,
                                              onMenu = { item ->
                                                  when (item) {
                                                      cc.txwd.kone.ui.MenuItem.Clipboard -> model.importText(clipboardText().orEmpty())
                                                      cc.txwd.kone.ui.MenuItem.File -> pickText(this@Window.window)?.let(model::importText)
                                                  }
                                              },
                                          ),
                                          menu, { menu = it },
                                          moving = inSight,
                                          cities = settings.state.backdrop == Backdrop.City,
                                      )
                                  }
                              }
                          }
                      }
                        if (palette) Palette({ query -> commands(model, query) { page = it } }, onClose = { palette = false })
                        // The edges a window frame would have: drag the right, the bottom or the corner.
                        ResizeHandles(window)
                    }
                  }
                }
            }
        }

        if (panel) {
            val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val panelState = remember { WindowState(size = DpSize(360.dp, 420.dp)) }
            panelState.position = WindowPosition((area.x + area.width - 360 - 12).dp, (area.y + area.height - 420 - 12).dp)
            Window(
                onCloseRequest = { panel = false }, state = panelState, undecorated = true, resizable = false, alwaysOnTop = true, focusable = true, title = "KOne",
                onKeyEvent = { event -> (event.key == Key.Escape && event.type == KeyEventType.KeyDown).also { if (it) panel = false } },
            ) {
                DisposableEffect(Unit) {
                    val listener = object : java.awt.event.WindowAdapter() {
                        override fun windowDeactivated(e: java.awt.event.WindowEvent?) {
                            panel = false
                            panelClosedAt = System.currentTimeMillis()
                        }
                    }
                    this@Window.window.addWindowListener(listener)
                    onDispose { this@Window.window.removeWindowListener(listener) }
                }
                KoneTheme(dark) {
                    TrayPanel(
                        model.dashboard,
                        onPower = model::power,
                        onOpen = { panel = false; shown = true; window.isMinimized = false },
                        onQuit = ::quit,
                    )
                }
            }
        }
    }
}

/**
 * The icon by the clock: the mark with the weather's seal, its tip naming the node; a click opens
 * the tray panel, the right button offers Node, Open KOne and Quit.
 */
@Composable
private fun Tray(model: AppModel, windowInSight: () -> Boolean, onOpen: () -> Unit, onPanel: () -> Unit, onQuit: () -> Unit) {
    if (!SystemTray.isSupported()) return
    val darkTaskbar = remember { WindowsShell.taskbarDark() }
    val tray = remember {
        java.awt.TrayIcon(TrayIcon.image(NodeWeather.Rest, darkTaskbar), "KOne").apply {
            isImageAutoSize = true
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) onPanel()
                }
            })
        }
    }
    DisposableEffect(Unit) {
        SystemTray.getSystemTray().add(tray)
        // The connection dropping or coming back, said by the clock while KOne's window is away.
        model.onNotice = { text -> if (!windowInSight()) tray.displayMessage("KOne", text, java.awt.TrayIcon.MessageType.NONE) }
        // The network's sign-in page, in the person's own browser; only http and https pages.
        model.onBrowse = { page ->
            if (page.startsWith("http://") || page.startsWith("https://")) {
                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(page)) }
            }
        }
        onDispose {
            model.onNotice = {}
            SystemTray.getSystemTray().remove(tray)
        }
    }
    val state = model.dashboard
    val online = state.connection == Connection.Online
    val weather = if (online) state.current?.weather ?: NodeWeather.Rest else NodeWeather.Rest
    LaunchedEffect(weather, darkTaskbar) { tray.image = TrayIcon.image(weather, darkTaskbar) }
    LaunchedEffect(state.current, online, state.current?.delay) { tray.toolTip = TrayIcon.tip(state.current, online) }
    // The right button: Connect or Close, Node to switch to another (when there is more than one),
    // Mode, Open KOne, Quit.
    val mode = model.tools.mode
    LaunchedEffect(state.nodes.map { it.id to it.name }, state.selected, state.connection, mode) {
        tray.popupMenu = PopupMenu().apply {
            add(MenuItem(when (state.connection) {
                Connection.Online -> "Close"
                Connection.Connecting -> "Connecting"
                Connection.Idle -> "Connect"
            }).apply {
                isEnabled = state.connection != Connection.Connecting && state.nodes.isNotEmpty()
                addActionListener { model.power() }
            })
            addSeparator()
            if (state.nodes.size > 1) {
                add(Menu("Node").apply {
                    state.nodes.forEach { node ->
                        add(CheckboxMenuItem(node.name, node.id == state.selected).apply { addItemListener { model.pickNode(node.id) } })
                    }
                })
            }
            add(Menu("Mode").apply {
                for ((value, name) in listOf("Rule" to "Rule", "Global" to "Global", "Direct" to "Direct")) {
                    add(CheckboxMenuItem(name, value == mode).apply { addItemListener { model.pickMode(value) } })
                }
            })
            addSeparator()
            add(MenuItem("Open KOne").apply { addActionListener { onOpen() } })
            add(MenuItem("Quit").apply { addActionListener { onQuit() } })
        }
    }
}

/** The smallest the window goes: the dashboard's deck and the rail still fit. */
private val MINIMUM = DpSize(960.dp, 620.dp)

/** The preview's 1280 by 800, or less on a smaller screen (a laptop at 150% has 1280 by 720), keeping its shape. */
private fun firstSize(): DpSize {
    val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val scale = minOf(1f, area.width * 0.92f / 1280f, area.height * 0.92f / 800f)
    return DpSize((1280 * scale).coerceAtLeast(MINIMUM.width.value).dp, (800 * scale).coerceAtLeast(MINIMUM.height.value).dp)
}

/**
 * Handles along the right and bottom edges and in the corner, since a window drawn without
 * Windows' own frame has none: the resize pointer over them, and dragging resizes.
 */
@Composable
private fun BoxScope.ResizeHandles(state: WindowState) {
    if (state.placement != WindowPlacement.Floating) return
    val density = LocalDensity.current
    fun Modifier.resizes(width: Boolean, height: Boolean, cursor: Int) = pointerHoverIcon(PointerIcon(java.awt.Cursor(cursor)))
        .pointerInput(width, height) {
            detectDragGestures { change, drag ->
                change.consume()
                val size = state.size
                val grow = with(density) { DpSize(if (width) drag.x.toDp() else 0.dp, if (height) drag.y.toDp() else 0.dp) }
                state.size = DpSize(maxOf(MINIMUM.width, size.width + grow.width), maxOf(MINIMUM.height, size.height + grow.height))
            }
        }
    Box(Modifier.align(Alignment.CenterEnd).width(6.dp).fillMaxHeight().resizes(width = true, height = false, java.awt.Cursor.E_RESIZE_CURSOR))
    Box(Modifier.align(Alignment.BottomCenter).height(6.dp).fillMaxWidth().resizes(width = false, height = true, java.awt.Cursor.S_RESIZE_CURSOR))
    Box(Modifier.align(Alignment.BottomEnd).size(14.dp).resizes(width = true, height = true, java.awt.Cursor.SE_RESIZE_CURSOR))
}

/** Windows' own file picker for a program to send straight out; its file name, or null when none was picked. */
private fun pickProgram(owner: java.awt.Frame): String? {
    val dialog = java.awt.FileDialog(owner, "KOne", java.awt.FileDialog.LOAD).apply {
        file = "*.exe"
        setFilenameFilter { _, name -> name.endsWith(".exe", ignoreCase = true) }
        isVisible = true
    }
    return dialog.file?.takeIf { it.endsWith(".exe", ignoreCase = true) }
}

/** Writes [text] to a file the person picks, starting from [name]; false when they did not pick one. */
private fun saveText(owner: java.awt.Frame, name: String, text: String): Boolean {
    val dialog = java.awt.FileDialog(owner, "KOne", java.awt.FileDialog.SAVE).apply {
        file = name
        isVisible = true
    }
    val file = java.io.File(dialog.directory ?: return false, dialog.file ?: return false)
    return runCatching { file.writeText(text) }.isSuccess
}

/** The text of a file the person picks (a list of share links, a subscription), 2 MB at most. */
private fun pickText(owner: java.awt.Frame): String? {
    val dialog = java.awt.FileDialog(owner, "KOne", java.awt.FileDialog.LOAD).apply { isVisible = true }
    val file = java.io.File(dialog.directory ?: return null, dialog.file ?: return null)
    if (!file.isFile || file.length() > 2L * 1024 * 1024) return null
    return runCatching { file.readText() }.getOrNull()
}

/** Whether a window put at [place] would show its title bar on one of the screens there are now. */
private fun onScreen(place: WindowPlace): Boolean = place.width >= 400 && place.height >= 300 &&
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.contains(place.x + 120, place.y + 18) }

private fun resource(name: String): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream("/$name")) { "missing $name" }.use { it.readBytes() }

private fun copyText(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
}

private fun clipboardText(): String? = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()?.trim()

/**
 * What the command palette offers for [query]: a typed site first (check it, look it up), then
 * connecting, the nodes by name, the modes and ways, the pages, and the tools. [go] shows a page.
 */
private fun commands(model: AppModel, query: String, go: (Page) -> Unit): List<cc.txwd.kone.ui.Command> = buildList {
    val state = model.dashboard
    cc.txwd.kone.app.DnsLookup.clean(query)?.takeIf { '.' in query }?.let { site ->
        add(cc.txwd.kone.ui.Command("Check $site", "Straight out and through nodes") { go(Page.Tools); model.checkSite(site) })
        add(cc.txwd.kone.ui.Command("Look up $site", "DNS, every way") { go(Page.Tools); model.lookUp(site) })
    }
    add(cc.txwd.kone.ui.Command(if (state.connection == Connection.Idle) "Connect" else "Disconnect", state.current?.name.orEmpty()) { model.power() })
    state.nodes.forEach { node ->
        add(cc.txwd.kone.ui.Command("Use ${node.name}", listOfNotNull(node.category, node.delay?.let { "$it ms" }).joinToString(" · ")) { go(Page.Dashboard); model.pickNode(node.id) })
    }
    for (mode in listOf("Rule", "Global", "Direct")) add(cc.txwd.kone.ui.Command("Mode: $mode", "Tools") { model.pickMode(mode) })
    add(cc.txwd.kone.ui.Command("Connect with the virtual adapter", "Tools") { model.pickWay("Adapter") })
    add(cc.txwd.kone.ui.Command("Connect as the system proxy", "Tools") { model.pickWay("Proxy") })
    add(cc.txwd.kone.ui.Command("Run diagnosis", "Tools") { go(Page.Tools); model.diagnose() })
    add(cc.txwd.kone.ui.Command("Test all nodes", "Nodes") { go(Page.Nodes); model.testAll() })
    add(cc.txwd.kone.ui.Command("Update category lists", "Rules") { go(Page.Rules); model.updateLists(force = true) })
    add(cc.txwd.kone.ui.Command("Import from clipboard", "Nodes") { model.importText(clipboardText().orEmpty()) })
    for (target in listOf(Page.Dashboard, Page.Nodes, Page.Rules, Page.Log, Page.Tools, Page.Settings)) {
        add(cc.txwd.kone.ui.Command("Go to ${target.title}", "Page") { go(target) })
    }
}
