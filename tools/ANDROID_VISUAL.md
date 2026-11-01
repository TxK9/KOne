# Android visual channel

This channel runs production Compose presentation components in an isolated debug app on an Android 15 AOSP arm64 emulator. It uses synthetic data. The application ID is `cc.txwd.kone.visual`; it has a platform Application and separate launcher, without Internet permission or production services, receivers or providers. It does not run the VPN or activate an account.

From the repository root:

```sh
python3 tools/android_visual.py review --locale zh-CN --label final-preview --name account-zh-light
```

The command incrementally builds, starts the isolated headless emulator if needed, installs the verified preview APK, opens the page, and captures pixels and UI hierarchy. All output locations and host dependencies come from an untracked host profile outside the repository. Delivery requires the configured volume to be mounted, writable and match its expected UUID; there is no alternate output location.

## Host profile

The default profile is `../runtime/host-local.json` relative to the repository. Set `KONE_VISUAL_HOST_PROFILE` to use another file outside the checkout. Never commit, include in a source handoff, or upload this profile. It contains host dependency paths and delivery-volume identity, not account credentials or signing keys.

Required keys:

| Key | Meaning |
| --- | --- |
| `shared_sdk` | Existing Android SDK dependency root |
| `java_home` | Existing compatible JDK root |
| `ndk_version` | Installed NDK version |
| `build_tools_version` | Installed Android build-tools version |
| `volume_mount` | Required delivery volume mount point |
| `volume_uuid` | Expected volume UUID |
| `delivery_root` | Output root inside that volume |
| `volume_probe.command` | Argument array for a host volume inspector; `{mountpoint}` is substituted |
| `volume_probe.format` | `json` or `plist` |
| `volume_probe.uuid_key` | Inspector output key containing the UUID |
| `volume_probe.writable_key` | Inspector output key containing a writable boolean |

Runtime SDK, AVD, diagnostics and evidence fingerprints remain outside the source repository under `../runtime`. Existing accepted SDK licenses and platform-tools may be referenced read-only. Do not automatically accept new license prompts, modify a shared SDK, or copy host authentication keys into the repository.

## Inspect a state

```sh
python3 tools/android_visual.py show --locale en --dark --state expired
python3 tools/android_visual.py capture --label final-preview --name expired-en-dark
python3 tools/android_visual.py matrix --label final-preview
python3 tools/android_visual.py show --page settings --locale zh-CN
python3 tools/android_visual.py tap --text 语言
python3 tools/android_visual.py capture --name settings-language-open-zh
```

Pages: `account` uses the actual ManagedAccountContent; `settings` uses SettingsPickRow; `tools` uses TestRow with long documentation-only IPv6 and latency fixtures; `dashboard` uses passive folded-panel/connect components; `city` places those controls over bundled city artwork with the production contrast theme. Full dashboard orchestration and its automatic probes are excluded. No preview button starts a service.

Language choices come from the app's generated LocaleConfig. Dropdown interaction is not proof of production AppCompat locale persistence. Chinese and English demo names are localized synthetic fixtures; `--state external` preserves literal external names separately. Policy fixtures use YYYY-MM billing periods and Unix-second expiry. `--state connected` changes presentation state only.

## Local wallpaper runtime

```sh
python3 tools/android_visual.py show --page motion --dark
python3 tools/android_visual.py record --label final-preview --name city-motion-night --seconds 12
python3 tools/android_visual.py show --page motion --state reduced
```

The motion page reuses the production bundled HTML/JS and WallpaperAssetClient with fixed scene/time parameters. It has no network access or native JavaScript bridge. The `KOneVisual` logcat tag reports ready/error/running state, frame counters and finite Canvas element geometry. Static city frames do not establish animated wallpaper behavior.

The reduced fixture supplies `motion=off` to the same local runtime. It tests that runtime path, not Android's persisted reduced-animation preference.

## Isolation and evidence

Only the named local AVD is controlled; scripts verify its identity and foreground package before interaction/capture. They never target physical devices, uninstall apps, clear data or change phone/host networking. The default is headless. Keep the existing foreground work undisturbed.

The preview flag is `-PkoneVisualPreview=true`, used with managed-edition debug tasks. Explicit release tasks reject it; release variants are also disabled under the flag. Ordinary debug/release manifests and authentication are unchanged. Preview Kotlin/resources live under `android/app/src/debug`, native visual tests under `android/app/src/testPreview`. Installation checks the separate package/launcher, platform Application, absence of Internet/background components, and XML-only placeholders for private wallpaper resource names.

Coordinate the Gradle build slot before building; do not run concurrent builds in one checkout. The retained AVD uses 2 virtual cores, 1536 MB RAM and a 320 dp width. Runtime and AVD config paths must be updated if their parent directory moves. `stop` preserves synthetic app data.

Host-rendered Robolectric images are labelled native render. Emulator screenshots come from screencap and include UIAutomator XML and source/APK fingerprints. Neither establishes physical-phone, live-account or VPN acceptance. Stress fixtures with arbitrary long language labels are kept separate from the app's actual supported-language menu.

Official references: [Android Emulator](https://developer.android.com/studio/run/emulator-commandline), [AVD manager](https://developer.android.com/tools/avdmanager).
