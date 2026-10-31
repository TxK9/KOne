# KOne for Android: the current line

Updated 2026-10-30. `HANDOFF.md` sets the rules and `DIRECTION.md` lists the features. This page
says where the Android work stands now, how parallel work is organised, and the design every
screen follows, so Codex and Claude can work on it side by side.

## Branches

- **Integration branch: `claude/polish-10042`.** It starts from `codex/integrate-cloud-20261025`
  (f7a76ca, build 10041). Every Android change since then lands here. Start new Android work from
  its head (716d7ce, build 10051 in progress).
- **Feature branches** are cut from the integration head. Each owns a disjoint set of files and is
  merged after the owner approves. All of these are merged:

  | Branch | Head | Area |
  |---|---|---|
  | `claude/kone-settings` | 78e7a7d | Settings pages on one set of rows (`SettingsRows.kt`) |
  | `claude/kone-tools` | b0a6981 | Tools pages on the network tests' ink kit |
  | `claude/kone-import` | d6cffa6 | Import and subscription outcomes; links are never shown |
  | `claude/kone-reliability` | d3aa69c | Background checks and notification quieter with the screen off |
  | `claude/kone-city-canvas` | 349c7ec | The city behind every page; shade only behind text |
  | `claude/kone-perf` | b06485a | Lazy node lists, per-node latency state, a wallpaper layer, faster grouping |
  | `claude/kone-surfaces` | beb58b8 | Start seal, dial, detail tiles, icon picker, key page |
  | `claude/kone-status` | c201232 | Status tab: live connections per app |

- **Windows: `claude/kone-windows-perf`**, cut from `codex/windows-city-performance-20261026`
  (2e6cf4c), is in progress: lag and motion. It is not merged anywhere.
- Nothing is pushed. The owner pushes.

## Working in parallel

- Give each area its own branch and worktree, and list the files it owns. Two branches never edit
  the same file. A change that needs another area's file is reported, not made.
- Put new strings in a new resource file per branch (for example `res/values/kone_status.xml`), so
  merges stay clean.
- Feature branches do not raise `KONE_BUILD`, install, or touch the phone. The integration step
  merges, raises `KONE_BUILD`, builds, installs and checks.
- Before editing, run `git status` and `git worktree list`. Never edit another worktree.
- Build the owner edition with `tools/build_owner.sh`. It needs `KONE_PHOTOS` and `KONE_SIGNING`,
  both outside the repository, and signs with the original identity. Raise `KONE_BUILD` for every
  install. Install with `adb install -r` and launch with `am start`. Never uninstall or clear data.
- Device checks are look-only:
  - no taps on Connect, switches or node rows;
  - no settings changes;
  - no network or speed tests on mobile data.
- Report each change with its evidence level: source reading, compile, unit test, device look,
  or live connection.

## Design system

Every screen follows these rules. They come from the owner's reviews, so treat them as settled.

- **The city is the canvas.** The living-city wallpaper (`assets/wallpaper/`, `CityWallpaper.kt`)
  is behind every page.
  - It rotates by itself, a US city then one from elsewhere (`CityShowcase`), on each fresh
    launch and after 30 minutes away.
  - It has no wallpaper settings. Each city follows its own clock and weather.
  - `pulse-v1.js` gives each city its own sky: aircraft and contrails by day, landing lights and
    beacons at night, helicopters where a city has them, searchlights in Las Vegas and Los Angeles,
    a night glow tinted to the city, and a sweep of light along the skyline on connect.
- **Ink follows daylight** (`CityCanvas.kt`).
  - By day: dark ink on a thin paper wash. At night: light ink on a night-blue shade.
  - The switch waits until it is clearly day or night, and fades through.
  - Shade sits only behind text, icons and fields, measured to 4.5:1 (3:1 for icons and large
    titles). There is no flat veil over a page.
- **One tone.** Dividers, outlines, hairlines and switch tracks take the current ink at low alpha.
  No black or grey borders, no slabs, and no cards on dark scenes. Inputs keep a hairline edge.
- **Fit.** The reference phone is 320 dp wide at 1.15 font scale. Nothing is cut mid-word or
  ends in an ellipsis where it can shrink or wrap (`FitText`).
- **Quiet.** No explanatory paragraphs or filler labels. Changes happen in place rather than in
  popups. Rows align. Features with nothing to choose stay hidden, for example the category tabs
  when there is one category.
- **Motion** (`KnineMotion.kt`):
  - sub-pages shift slightly and fade over the still city;
  - sections unfold on a spring without bounce;
  - state changes cross-fade;
  - numbers roll.
  - Durations are 200–600 ms, and an animator scale of 0 means no motion.
- **Language.** English defaults; existing `values-zh-rCN` entries are kept.

## Where things are

| Feature | Code |
|---|---|
| Dashboard folded line and lifted node list | `compose/screen/dashboard/KnineDashboard.kt`, `KnineDeck.kt` (lazy rows, fading edges, country filter above 8 nodes from 2+ countries, four nodes never fold) |
| Node page: Chain through, Share | `compose/screen/profile/KnineNodeEditor.kt` |
| Proxy chaining | `selfuse/NodeChain.kt`: stored per node, added as a `detour` only to what the service runs (`bg/BoxService.kt` `runtimeContent`, `connection/CallingOptionUpdates.kt`), checked with `Libbox.checkConfig` before it is saved |
| Node sharing | `compose/component/NodeShare.kt` (QR via zxing, Copy, system share), `selfuse/NodeShareLink.kt` |
| Status tab | `compose/screen/log/LogScreen.kt`: uptime, node and its front node, rolling speeds, trace, totals, live connections per app |
| Settings rows | `compose/screen/settings/SettingsRows.kt` |
| Tools ink kit | `compose/screen/tools/NetworkTestKit.kt` |
| Background checks | `bg/NodeWatch.kt`: only the node in use |

Sharing and chaining exist only in the owner edition, and never apply to console-managed nodes.

## Verified and not yet verified

- **On the owner's phone (build 10050):**
  - day and night cities on Settings, App, Routing, Tailscale, Status, 软路由 and the dashboard;
  - swipe Share and Delete;
  - the Share QR;
  - the four-node lifted list.
- **On the owner's phone (builds 10051 and 10052, disconnected):**
  - a night city (Washington, D.C.) and a day city (Seoul) on the dashboard;
  - the lazy node list with two category tabs and five nodes, where swiping keeps the row height and the rows above stay put;
  - the node page's Chain through and Share rows;
  - the start seal at rest;
  - the dial opening and closing, with the neighbouring name inside the screen (10052; it was cut at 10051);
  - the folded line for a node without a flag: a node named only by its address shows a globe and stays on one line (10052; at 10051 the address showed in large type and pushed the seal to a second line);
  - App and Status at night.
- **Not yet on a device:**
  - live traffic, live connections, the detail tiles and the connect sweep, which need a connection;
  - the start seal breathing and pressing in while connecting;
  - the dawn and dusk ink switch as it happens;
  - the country filter with more than eight nodes;
  - the key page, which only the edition for others shows;
  - the corrected city labels ("Washington, D.C.", "Viana do Castelo"), checked in the catalog but not yet seen on the App page.
- **Known open:**
  - every node showed the unreachable glyph while the phone was on mobile data; this is being looked at on `claude/kone-probe-fix`.

## Next

- The probe fix on `claude/kone-probe-fix`, then a connected check of the tiles, the Status connections and the connect sweep.
- Windows lag and motion on `claude/kone-windows-perf`.
