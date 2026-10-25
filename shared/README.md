# KOne's shared core

Kotlin both Apps build from source: KOne for Windows (`desktop/`, through its `kotlin.srcDir`)
and KOne for Android (`android/app`, through its `java.srcDir`). Plain Kotlin,
kotlinx-serialization, SnakeYAML and org.json (part of Android; a dependency on Windows) only,
no platform code, JVM 17.

- `ShareLinks.kt`: share links (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS) read
  into sing-box outbounds, one way for both Apps.
- `Subscription.kt`: a subscription's body (links, base64, a Clash profile or a sing-box node
  list) read into tagged nodes, and the provider's usage header with its warning.
- `Hosts.kt`, `NameServers.kt`: fixed answers (a pasted hosts file included) and DNS servers for
  domains.
- `FakeIp.kt`: the fake-IP option's server and rules, and its address ranges.
- `Names.kt`: sites, programs, apps, address ranges and ports, read and written one way.
- `Rules.kt`: the person's routing rules, their kinds and how each is checked.
- `RuleImport.kt`: rules brought over from Clash and v2rayN.
- `Categories.kt`, `CategoryLists.kt`: the category lists rules can name, and how a downloaded
  list is checked and kept (each App supplies the download and its own sing-box check).
- `ClashProxies.kt`, `NodeFilter.kt`: a Clash profile's proxies as sing-box outbounds, and the
  provider notices left out among them.
- `NodeIdentity.kt`: which node an outbound is, whatever it is called: a node listed twice used
  once, and a choice (or anything kept per node) followed through an update that renames or
  reorders nodes.
- `Region.kt`: where the person is, and the DNS presets and test sites that follow from it.
- `Checks.kt`: diagnosis checks, the clock verdict and the plain-text report.
- `Countries.kt`: countries, cities and flags, for reading a node's country from its name.
- `Why.kt`: why a connection went its way, in the person's terms.

The tests in `src/test/kotlin` run in the Windows build (`desktop/gradlew test`), where the
Windows service's Go checks are also held to these by the configuration consistency test.
Each App writes the rules into its own template; what a rule is, is decided here once.
