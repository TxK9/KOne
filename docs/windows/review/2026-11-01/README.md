# Windows integration candidate

This candidate combines the pending Windows work on the released language baseline.
It is a local source candidate, not an installer or a published update.

## Sources

| Source | Exact commit | Role |
| --- | --- | --- |
| `claude/windows-desktop` | `d4011c9f2e1fa6d9a109ad7d5b74787337da3ec4` | Integration baseline, including the 1.0.70 language work |
| PR #9, `codex/windows-polish-20261030` | `14bd4d3253f23cf0475707c160ce61202f5d3174` | Dashboard and Nodes layouts, telemetry snapshots, log batches |
| PR #7, `claude/windows-node-continuity` | `f9ca23e525fdbd039bb268a1344db3278f0235cf` | Logical node selection, exact measurement continuity, running-core names and reloads |

Both PR heads were verified through the GitHub connector and fetched by their exact
pull-request refs. PR #9 was merged first, then PR #7, preserving both histories.
The `AppModel` and `OwnNodes` conflicts keep the current translation calls together
with the continuity changes. No Android or shared-source files were changed by
this integration.

## Additional corrections

- A delay request can complete after a subscription changes the list. A result
  now follows the exact outbound that was tested; it is discarded if that outbound
  is gone or changed, including a new server under the same tag. The core-name
  mapping is captured before dispatching the request, so tag swaps cannot change
  what an already scheduled request asks about.
- Manual delay sweeps stop accepting results from a replaced running or testing
  core. A changed listing invalidates the old testing-core handle, and subscription
  edits rebuild the disconnected testing core through the normal node-change path.
- The chosen-node disappearance notice uses the English/Simplified Chinese
  catalog. Provider names remain literal, including names containing placeholders
  or the word `Automatic`.

The loopback regressions exercise the public Test action with a response held
across a list update: renamed node, same-tag server replacement, and replaced
testing-core handle. They check both latency and health, plus the displayed tested
state. They use no real account, installed service or external network.

## Validation

Executed on macOS ARM64 with JDK 17.0.19 and Gradle 9.7.0 using real dependencies:

- Targeted suite: **35 tests, zero failures, errors or skips**.
- Full desktop/shared JVM suite with `-Pkone.dpi=true`: **323 tests, 309 passed,
  14 gated skips, zero failures or errors**; 132 synthetic PNGs generated.
- `git diff --check`: passed.

The 14 skips require Windows integration, a real core/service or explicitly enabled
network tests: `ServiceEndToEndTest` (3), `KoneConfigTest` (2), `CategoryListsTest`,
`DnsWireTest`, `HotkeyTest`, `NetworkReachTest`, `RouteProbeTest`, `SiteCheckTest`,
`SpeedTestTest`, `UpdaterTest` and the real-core case in `WhyTest`. These are not
native acceptance passes.

Representative output was visually inspected: the narrow dark Nodes page and
light dashboard at 960 pixels, and the Chinese light dashboard at 200%. Names,
secondary node actions and connection controls remain visible. Existing untouched
Kotlin/Compose deprecation and conversion warnings remain.

Commands from `desktop`:

```sh
./gradlew test --tests cc.txwd.kone.app.AppModelContinuityTest \
  --tests cc.txwd.kone.app.NodeContinuityTest \
  --tests cc.txwd.kone.core.RunningNamesTest \
  --tests cc.txwd.kone.app.OwnNodesTest \
  --tests cc.txwd.kone.app.AppModelTest \
  --tests cc.txwd.kone.LocalizationTest
./gradlew test -Pkone.dpi=true
```

Reports are generated under `desktop/build/reports/tests/test`; XML results under
`desktop/build/test-results/test`. Renders are synthetic Compose output under
`desktop/build/screens`, including English and Chinese at 100%, 150% and 200%.
They do not establish Windows-native rendering or measured performance.

## Windows acceptance still required

The native Windows owner must use the exact final candidate commit in a separate
checkout. The initial source-only check needs JDK 17 or newer and network access
for the existing Gradle dependencies:

```powershell
Set-Location desktop
.\gradlew.bat test -Pkone.dpi=true
```

Record the native test totals, gated skips and actual rendered output. Review
English/Chinese, keyboard focus, screen-reader names, narrow controls, 1,000-node
lists, log pause/filter/rollover and hidden-window behavior on Windows itself.

After the Windows owner coordinates a snapshot and any network test, separately
check real-core rename/reorder without a disconnect, removal and endpoint changes
with a reload, updates while starting, Automatic and country groups, and stale
latency/bandwidth results. The loopback tests do not replace real sing-box or
privileged-service evidence.

Installer builds, upgrades, service authorization, TUN/system proxy and live
tunnels are not verified here. x64 and ARM64 require independent native evidence
before a future release. No workflow was dispatched, no installer was built, and
no public manifest, signing identity, account data or production server was changed.
