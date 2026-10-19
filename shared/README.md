# KOne's shared core

Kotlin both Apps build from source: KOne for Windows (`desktop/`, through its `kotlin.srcDir`)
and KOne for Android (`android/app`, through its `java.srcDir`). Plain Kotlin and
kotlinx-serialization only, no platform code, JVM 17.

- `Names.kt`: sites, programs, apps, address ranges and ports, read and written one way.
- `Rules.kt`: the person's routing rules, their kinds and how each is checked.
- `RuleImport.kt`: rules brought over from Clash and v2rayN.

The tests in `src/test/kotlin` run in the Windows build (`desktop/gradlew test`), where the
Windows service's Go checks are also held to these by the configuration consistency test.
Each App writes the rules into its own template; what a rule is, is decided here once.
