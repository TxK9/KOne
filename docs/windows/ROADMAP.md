# KOne for Windows: benchmark and roadmap

This compares the Windows client with two mature Windows proxy clients, v2rayN and
Clash Verge Rev, from their public documentation and common use. It lists what KOne
lacks, where it can do better, and the order of work. Status is updated as items land.

## Ground rules that shape every item

- The UI runs as the signed-in user; the tunnel runs in the SYSTEM service, which builds
  its configuration from a fixed template. Every new option travels as a typed, validated
  field and is checked identically in Go (`desktop/service/plan.go`) and Kotlin
  (`core/KoneConfig.kt`), with the byte-for-byte consistency test extended to cover it.
  The service never accepts raw configuration, scripts or file paths.
- Managed reject rules (the per-person Claude block) stay first in routing and DNS,
  ahead of every user rule, node source and mode.
- sing-box is the only core. New node sources are checked by `sing-box check` before
  they replace anything.

## Comparison

| Area | v2rayN | Clash Verge Rev | KOne before this round |
| --- | --- | --- | --- |
| Node import | Share links, clipboard, QR scan, files | Profiles (YAML) | Share links from clipboard or file |
| Subscriptions | URL, update interval, filters, user agent | Remote profiles, auto update, traffic and expiry from `subscription-userinfo` | Managed console only |
| Node management | Add/edit forms per protocol, sort, dedupe, export | Edit proxies in profile, sort, hide failed | Remove imported nodes |
| Groups | Custom load-balance/fallback via config | select, url-test, fallback, load-balance | Selector + Automatic (url-test) |
| Latency tests | TCP and real delay, speed test | Per group, unified delay | Every minute, automatic |
| Routing | Presets and custom rules (domain, IP, port, process) to proxy/direct/block | Rules from profile, rule providers, merge/script | Modes; programs direct; sites direct/proxy |
| Ad blocking | Rule presets | Rule providers | None |
| DNS | Remote/direct servers, hosts, fake-ip | nameserver, fallback, policy, fake-ip, hosts | Fixed (223.5.5.5 direct, 1.1.1.1 DoH remote) |
| IPv6 | Toggle | Toggle | IPv4 only |
| Inbounds | TUN (stack, MTU), system proxy, PAC, LAN | TUN, system proxy with guard, LAN | TUN through a SYSTEM service; system-proxy fallback |
| Monitoring | Speeds, per-server traffic | Traffic graph, connections with search and sort, logs with level filter | Speeds, today/month usage, connection list |
| Diagnostics | Test delay/speed | Site tests | None |
| Updates | App and cores, geo files | App and core | Signed app updates; rule sets refreshed by the core |

## Where KOne can be better

1. **Safety by construction.** Both competitors run whatever configuration a profile or
   script contains, with administrator rights for TUN. KOne's privileged side only
   accepts typed, validated fields, and a subscription is checked by sing-box before it
   replaces the last good one, which is kept when an update fails.
2. **Selection with memory.** url-test picks by the latest sample and a tolerance, so it
   flaps on noisy links. KOne can score nodes from their recent history (latency, loss,
   jitter) with hysteresis, and fail over from a chosen node that dies, returning to it
   once it recovers, saying so in one line.
3. **Diagnosis in plain words.** One action that checks the service, the adapter or
   proxy, DNS on both paths, the node, the exit address and a few sites, and says which
   step failed and what to try.
4. **Quiet by default.** Advanced controls stay out of sight until used, and the client
   keeps its small memory and CPU footprint while running all day.

## Priorities

| # | Work | Status |
| --- | --- | --- |
| P1 | Subscriptions: HTTPS URL sealed with DPAPI; share links, base64 and sing-box JSON; `subscription-userinfo`; sing-box check; keep last good; auto update | Planned |
| P1 | Clash/mihomo YAML subscriptions converted to sing-box outbounds | Planned |
| P1 | Nodes page: every source, subscription usage and expiry, update/edit/remove, test now | Planned |
| P2 | Routing rules: ordered domain, keyword, suffix, IP CIDR, process and port rules to Proxy, Direct or Block; ad-block rule set | Planned |
| P2 | DNS presets and validated custom servers; IPv6 | Planned |
| P3 | Smart selection with health history; failover and return | Planned |
| P3 | Diagnostics page | Planned |
| P4 | Traffic graph, connection search/sort/filter, log level filter and pause, per-node usage | Planned |

## Not planned, and why

- **LAN sharing.** It would let other devices use a keyed client without a key.
- **Raw configuration, merge files and scripts.** They would bypass the managed policy
  and the service's validation.
- **Other cores (Xray, mihomo).** One audited core keeps behavior and checks consistent.
- **Tailscale Home.** Declined by the owner for Windows.

## Evidence

Each item is done only when it works end to end, has unit tests, keeps the Go/Kotlin
consistency test passing, and passes the Windows workflow on x64 and ARM64. Real-device
acceptance remains separate (see `RELEASE.md`).
