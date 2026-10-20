# KOne test round

One round on real devices, after development, before anything is published on the website. It
adds to the release checklists (`windows/RELEASE.md`, and the signed-build path in `tools/` for
Android); it does not replace them. What unit tests and the configuration consistency test already
prove is noted, so the round spends its time on what only a device can show.

Use one Windows PC (x64; ARM too if it will be offered), one Android phone, an authorized test
console key, and a test subscription. Use reserved names in notes (example.com, 192.0.2.x).
Record the build, the device, each step's result and anything odd.

## 1. Safety first (both Apps; stop the round on any failure)

| # | Check | Expected |
| --- | --- | --- |
| 1.1 | With the console's Claude block on, open claude.ai in a browser in Rule, Global and Direct mode | Refused in all three |
| 1.2 | Repeat 1.1 with Fake IP on, and with a direct site, a proxied site, a fixed answer and an own DNS server set for claude.ai-like names | Still refused: the block comes before every one of them |
| 1.3 | Import a node by link, by Clash profile and by subscription; repeat 1.1 through each | Refused through each |
| 1.4 | Export links, QR code and backup on Windows | The console's nodes never appear; imported and subscribed ones do |
| 1.5 | Disconnect, quit, upgrade, uninstall (Windows system-proxy way) | The system proxy is as it was before KOne |

Proven at source level: the block's position in every template (Kotlin, Go and Android tests),
console nodes never exported (OwnNodesTest).

## 2. Connecting and choosing a node

| # | Check | Expected |
| --- | --- | --- |
| 2.1 | Windows: connect through the service (virtual adapter), then the system-proxy way | Online in both; the exit address is the node's |
| 2.2 | A profile or source with several nodes | Automatic leads the list and is the default; a node picked by hand stays picked after reconnecting (Android) |
| 2.3 | Nodes from two countries, two or more in one | "Automatic · <country>" appears for that country only; it uses a node of that country |
| 2.4 | Stop the node in use (or block its server at the router) | Windows: a stand-in within about 15 s when Fail over is on. Android: Automatic moves within its 5-minute check |
| 2.5 | Android: a subscription's node list | Nodes keep the provider's names (no "node" among them) |

## 3. Routing and DNS

| # | Check | Expected |
| --- | --- | --- |
| 3.1 | Add a direct site and a proxied site; visit both | Tools (Windows) / connection details (Android) say "Your direct sites" / "Your proxied sites" |
| 3.2 | Add a List rule (e.g. Netflix) | Android: the rule says it is waiting, then applies once downloaded, without restarting by hand. Windows: the list is fetched and the rule applies |
| 3.3 | Turn on Block ads; visit an ad domain | Refused |
| 3.4 | Paste Clash rules and v2rayN rules | Rules added; the count of rules left out is said |
| 3.5 | Fixed answer `nas.example.com 192.0.2.10` (use a real local name) | The name resolves to that address, in every mode |
| 3.6 | DNS for domains: an intranet domain and its server | Names under it resolve through that server |
| 3.7 | Where you are: outside China, then China | Outside: no China split, Cloudflare direct DNS. China: Chinese sites go straight out |
| 3.8 | Fake IP on: open several sites, then turn it off | First loads feel quicker; everything still works after turning it off (Windows: a program may need a moment to look a name up again) |
| 3.8b | Windows, Fake IP on: look at the taskbar's network icon; then change a setting (KOne reconnects) and reload a few sites | Windows says it is online; every site is still the right one after the reconnect |
| 3.8a | Fake IP on, with an address-range rule (e.g. a company range direct) | Apps that connect to the address follow the rule; names KOne answered follow name rules (the documented trade-off) |
| 3.9 | IPv6 on, on an IPv6 network | IPv6 sites load; with Fake IP also on, they still load |
| 3.10 | With nodes in two countries, add a rule sending a test site through the other country than the node in use; open a "what is my IP" site under that rule | The exit address is that country's node; other sites keep the node in use; Global and Direct mode ignore the rule |
| 3.11 | Repeat 3.10 with Fake IP on, then with a country no node says | Still through that country; with no node there, through the node in use, and the rule's row says so |
| 3.12 | Paste a whole Clash profile whose rules aim at a country group and a DIRECT-first group | Those rules go through that country and straight out; the profile's proxies are not counted as rules left out |

Proven at source level: the Windows App and service build identical configurations for every
combination above (consistency test), rules through a country included; the real sing-box accepts
them, Fake IP included, and an Android configuration with country groups; Android's routing
re-applies without change and turning a choice off restores the template exactly.

## 4. Subscriptions and imports

| # | Check | Expected |
| --- | --- | --- |
| 4.1 | Add a subscription of links, of base64, of a Clash profile | Nodes appear; provider notices ("Expire: …") are not nodes |
| 4.2 | Its data and end date | Shown under it ("12.00 GB of 100.00 GB · until …"); in the warning colour within three days of the end or at 90% |
| 4.3 | Leave it a day (or set a short interval) | It refreshes on its own |
| 4.4 | Android: copy a Clash profile, Import from clipboard | Each usable proxy becomes a node; the first is selected |
| 4.5 | A whole sing-box configuration as an Android remote profile | Kept as sent (sing-box for Android's behaviour) |

## 5. Diagnosis and the phone's own causes

| # | Check | Expected |
| --- | --- | --- |
| 5.1 | Diagnose on a working network | Every step passes; "Nothing to fix" (Android) / no advice (Windows) |
| 5.2 | A hotel or airport Wi-Fi with a sign-in page | Named as a sign-in network, with the way to open it |
| 5.3 | Set the clock 10 minutes wrong | The clock is named as the cause |
| 5.4 | Android: Private DNS set to a provider; battery optimisation on; another VPN app on | Each is named, with the one thing to do |

## 6. Settings that must survive

| # | Check | Expected |
| --- | --- | --- |
| 6.1 | Back up, then restore on a fresh test install (Windows: another user account; Android: a second test phone or work profile) | Sites, rules, DNS choices, Fake IP, Automatic, subscriptions and imported nodes come back |
| 6.2 | Restore the same backup twice | Nothing appears twice |
| 6.3 | Restart the device | KOne starts as it was left (autostart only when turned on) |

## 7. Resources (Windows)

Measure with the window shown, minimised and in the tray: private memory and CPU over a minute.
The figures belong in `windows/DIRECTION.md`; CI can then hold a ceiling.

## After the round

File what failed with the step number, build and device. Fix, then repeat the failed steps and
section 1 in full before publishing.
