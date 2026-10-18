# Historical source handoff: 2026-10-11

This document describes the initial export. Read the current `HANDOFF.md`, source, and build metadata for subsequent work. Historical claims below are bounded to that snapshot.

## Snapshot provenance

The recorded Android upstream baseline was `5c7b4ce969b926063737d059edf7b256c8f56ed0`; the core baseline was `c992297988288565a24a6d36e2cf4d77cb835fcd`. The export included tracked and untracked source without copying the original Git history. Its recorded versions were `0.1.17-selfuse` / `0.1.17-plain`, versionCode 18; later UI edits could retain those values.

The export was a fixed handoff, not a live mirror. Source changes outside it were not imported automatically. Refer to the snapshot hashes for original provenance.

Recorded features included combined proxy/Tailscale home configuration, import, four navigation areas, categories, node preview/selection, plain and wallpaper appearances, and exit-address detection. Planned customer-management features were not established as implemented by that export.

Source entry points were the dashboard, theme, self-use configuration, and `bg/BoxService.kt` modules. At that baseline, `PairedProfile.requirePaired()` required the Tailscale private-network configuration. A plain appearance was therefore not equivalent to a separate client edition with personal home access removed.

## Historical build inputs

The handoff recommended JDK 21 for Gradle and recorded Gradle 9.7.0, compileSdk 37 minor 1, and NDK 28.0.13004108. These are historical values; current source defines present requirements. JVM bytecode target 17 is distinct from the JDK running Gradle.

Copy the required AAR inputs to `android/app/libs/`, configure the SDK through an appropriate local environment or excluded `local.properties`, then use the Gradle wrapper. Historical examples were:

```sh
cd android
./gradlew :app:assembleOtherDebug -PkninePlain=true --no-daemon
./gradlew :app:testOtherDebugUnitTest -PkninePlain=true --no-daemon
```

Native rendering tests additionally used `-PknineVisualTests=true` and `app/src/testVisual/`. Do not automatically run a release, publication, or deployment workflow as part of reading a handoff.

The export had no fresh compilation result. Preserving resource IDs after removing photographs did not validate photographic rendering. Historical AARs were arm64 artifacts; other architectures were not qualified. The recorded core inputs included Go 1.26.3 and NDK 28.2.13676358. Before a core rebuild, check the current builder's JDK requirement, tags, and artifact destinations instead of assuming the old copy paths remain valid.

No release signing material was supplied. A development-signed APK cannot replace an installation using a different signing identity. Never uninstall an existing application to bypass that mismatch.

## Evidence and limitations

`historical-0.1.17-acceptance.md` records 79 base tests, eight native tests, signed release/lint results, and an in-place installation for the earlier project. It is not a retest of the handoff or proof of final visual acceptance. Category layout, content placement, transparency, and node categorization remained under refinement at export time.

The client-management plan required a real edition boundary, device binding, server authorization, signed configuration publication, rollback, and controlled diagnostics. Client devices must not inherit personal private-network access. Build an isolated prototype and obtain deployment/billing authorization before creating public services or paid infrastructure.

Preserve existing networking, signing identity, and application data. Do not automatically update upstream, replace the core, delete earlier projects, or distribute personal photographs. Record the reason for each change, checks actually executed, and remaining acceptance limits.
