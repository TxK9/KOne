# KOne for Windows: benchmark and roadmap

This compares the Windows client with two mature Windows proxy clients, v2rayN and
Clash Verge Rev, from their public documentation and common use. It lists what KOne
lacks, where it can do better, and the order of work. Status is updated as items land.

## Ground rules that shape every item

- The UI runs as the signed-in user; the tunnel runs in the SYSTEM service, which builds
  its configuration from a fixed template. Every new option travels as a typed, validated
  field and is checked identically in Go (`desktop/service/plan.go`) and Kotlin
  (`core/KoneConfig.kt`), with the byte-for-byte consistency test extended to cover it.
  The service never accepts raw configuration, scripts or file paths. The one kind of
  file it takes is a category list (a sing-box binary rule set), checked as described below.
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
| P1 | Subscriptions: HTTPS URL sealed with DPAPI; share links, base64 and sing-box JSON; `subscription-userinfo`, name and update interval from headers; sing-box check (halving to find bad nodes); keep last good; quiet auto update | Done |
| P1 | Clash/mihomo YAML subscriptions converted to sing-box outbounds (Shadowsocks with obfs/v2ray-plugin, VMess, VLESS with Reality, Trojan, Hysteria2 with port hopping, TUIC, AnyTLS, SOCKS, HTTP); safe YAML loader with alias limit | Done |
| P1 | Nodes page: every source, subscription usage and expiry, update/edit/remove, test all (eight at a time), kind and loss per node | Done |
| P2 | Rules page: ordered domain, suffix, keyword, IP range, app and port rules to Proxy, Direct or Block; Block ads (v2fly category-ads-all, bundled); existing app and site lists | Done |
| P2 | DNS presets and validated custom servers (DoH remote, plain direct); IPv6 | Done |
| P3 | Health history (median, spread, loss); Automatic steered with patience; failover from a chosen node and back, with its dead connections closed | Done |
| P3 | Diagnostics in Tools: key, service, node, DNS, four sites, exit, with one suggestion | Done |
| P4 | Traffic sparkline, connection search and sort, log search, level filter and pause | Done |

The owner's second round of priorities:

| # | Work | Status |
| --- | --- | --- |
| P1 | Node management: add and edit by protocol without a link (every protocol the share links carry, round-tripped exactly or refused), search, sort by order, delay or name, hide failing, export imported and subscribed nodes as links; console nodes stay read-only and are never exported | Done |
| P2 | Bandwidth test down and up through a test core of its own (the live connection is untouched; about 35 MB, one node at a time); test cores reach their nodes straight out of the tunnel | Done |
| P3 | DNS lookup in Tools: a name as programs see it, through KOne's DNS and rules, from the direct server (UDP) and the remote one (DoH), with addresses and times, and one sentence on what they add up to | Done |
| P4 | Usage by node and by program, today, 7 or 30 days, with a bar per day; read from the core's connection list every five seconds, 31 days kept, 40 largest a day | Done |
| P5 | Category rules (services, websites, regions) as ordinary ordered rules, lists downloaded, checked and handed to the service (below) | Done |

Every item above has unit tests; changes to the service template keep the Go/Kotlin
byte-for-byte test passing and are checked by `sing-box check`. Windows CI installs,
connects and uninstalls on x64 and ARM64 for each push. None of it has been tried on a
real Windows PC yet.

## Next

| Work | Status |
| --- | --- |
| Copy an imported or subscribed node as a share link (managed nodes are never written out) | Done |
| Backup and restore of the setup (never the key or the binding) | Done |
| Add or edit a node by protocol without a link | Done |
| QR export of a node | Done |
| Sign-in networks (hotels, airports) detected, with one press to sign in and reconnect | Done |
| Other VPNs and Windows' proxy elsewhere named in diagnosis and on fall-back | Done |
| Windows' clock checked against the network's own answer (HTTP Date): diagnosis says how far off and how to fix it, and a clock far enough off to turn nodes away is said once after failures | Done |
| Site check: a site tried straight out and through nodes, the better way kept | Done |
| Site check asks the running core first where the site goes now and by which rule (a real connection found in the core's own list, not an imitation of its rules); checked on sing-box through the proxy port, the adapter path only on Windows | Done |
| Each connection's reason shown in the person's terms | Done |
| Memory and CPU measured in CI on x64 and ARM64 | Done |
| Bandwidth test through a chosen node | Done |
| Hosts: fixed answers for chosen names (after the Claude block; resolved before routing) | Done |
| DNS for domains: the names under a domain answered by a server of the person's own (an intranet's), after the Claude block and the hosts; DNS only, routing unchanged | Done |
| Where you are (Settings): outside China by default, with no China split and Cloudflare as the direct DNS; China keeps the split and AliDNS. In the Go service, the Windows App and the Android App, held identical by the consistency test | Done |
| Per-node and per-app traffic over the day | Done |
| Automatic over large subscriptions: the template's url-test sweep now runs every ten minutes, as KOne steers and tests often itself | Done |
| GEOSITE and GEOIP category rules (e.g. a streaming category to Proxy) | Done (Option B, below) |
| Learning from real traffic: a site that keeps failing straight out (or stalls) is offered a rule to go through the node, in Rule mode only; a node whose connections stall is tested at once rather than at the next round | Done |
| What nothing matches: through the node (default) or straight out, for Rule mode | Done |
| Command palette (Ctrl+K): pages, nodes, modes, imports, and a typed site checked or looked up | Done |
| Automatic within a country: a region's nodes steered as Automatic is, from the region's own menu entry | Done |
| Pause for 15 minutes, then connect again; copy a diagnosis report (no key, no node addresses, no exit address) | Done |
| Each node's last ten delays drawn beside it | Done |
| Import a Clash profile's rules (domain, suffix, keyword, IP range, process, port, GEOSITE, GEOIP); the rest counted and left out | Done |
| Import v2rayN's routing rules or an Xray configuration's (domain:, full:, keyword:, geosite:, geoip:, addresses, ports, programs); a rule blocking the ad list turns on Block ads | Done |
| Import nodes from a pasted or picked Clash profile or sing-box configuration, as well as share links | Done |
| A connection's site sent straight out or through the node from the connection list | Done |
| A subscription that has ended, ends within days, or has used 90 % of its data is said once | Done |
| A provider's notices among its nodes ("剩余流量", "官网", "Expire:") left out of every list and Automatic; words of the person's own leave out more, per subscription, with chains through them | Done |
| Fake IP as an option, in the service and the App alike (consistency test), accepted by the real sing-box; name lookups say when an answer is its stand-in; its trade-off for address rules said where it is chosen | Done |
| Rules page: a rule's way changed where it stands (a click on it: the node, straight out, blocked, or a country); a search once there are many; every rule copied as Clash rule lines, which either App, or another client, reads back (Android too) | Done |
| Import from QR codes: on the screens (KOne's window out of the way for the moment it takes), in a copied picture, or in a picture file; several at once, light on dark too. Node links import as text does; a subscription's link, or another client's import link holding one (clash://install-config, sing-box://import-remote-profile, hiddify://import), is added as a subscription, which Add subscription also takes now | Done |
| Rules through a chosen country (`via:JP`): any rule kind, Rule mode only; a group of that country's nodes (by their names), tested as Automatic is, with its names asked through it too (and faked there with Fake IP); a country no node says goes through the node in use, and its row says so. Clash rules aimed at a country group come over this way, and a whole profile's groups are followed to their first choice. Service, App and Android alike (consistency test; accepted by the real sing-box) | Done |
| Restoring a backup twice adds nothing twice (rules, hosts, DNS for domains) | Done |
| The App's model in controllers: rules, network, subscriptions, site tools, sign-in networks, own nodes, diagnosis, backup, connections | Done; the connection and steering wait for the device round |
| Third-party notices: an overview of what KOne includes and under which license, and each full text, installed in `app\resources\licenses` and shown in Settings; the core's notice quoted word for word (test) | Done; the source route for the core is the owner's decision (RELEASE.md) |
| The chosen node kept through subscription updates (NodeIdentity, shared): followed through renames, new orders and the tag shifts of the lists after a changed one; when it can no longer be found, Automatic, said in a line. Delays, tests and bandwidth stay only with nodes exactly as measured (chains included). A running core carries on, under the names it started with, only through a rename or a new order of exactly the nodes it runs, in the same groups (selector, Automatic, the rules' countries); any other change, a node gone included, starts it afresh with the list (tests: rename, namesakes trading places, a server or TLS change under the same tag, two nodes replaced by two, removal, a relay renamed or moved, a rename across a country's group, a shared server, a split, an old name now another's; mutations checked) | Done |

**Category rules (Option B, chosen by the owner).** A category is one more rule kind,
`set`, in the person's ordered rule list: `geosite-netflix → Proxy`, `geoip-jp → Direct`.
Priority is therefore the list's own order, first match wins, after the Claude block, the
test cores, the person's app and site lists, and before ad blocking and the modes, so no
category can reach round the Claude block. Lists of names (geosite) also steer DNS; lists
of addresses (geoip) match the destination only.

- **Names.** `geosite-` or `geoip-` and up to 64 of `a-z 0-9 ! @ _ -`; the template's own
  lists are refused; at most 32 lists. Checked the same way in Go and Kotlin, and covered
  by the consistency test.
- **App.** Downloads each list from SagerNet's fixed address
  (`raw.githubusercontent.com/SagerNet/sing-geosite|sing-geoip/rule-set/<name>.srs`) over
  HTTPS only, through KOne's port in system-proxy mode, at most 4 MiB, asking with the
  ETag whether it changed. A list is kept only after the header check (`SRS`, format 1–5)
  and, where KOne is installed, `sing-box check` on a configuration that loads it; it is written beside the old copy
  and renamed into place, so any failure keeps the last good copy. Each list is looked at
  once a day, a failure retried after an hour; Rules shows each list's state and an Update.
- **Service.** Two pipe operations: `ruleSets` (names and SHA-256 kept) and `putRuleSet`
  (name, bytes, SHA-256). It checks the name, size, digest and header itself, then has its
  own sing-box read the new copy before renaming it into `core\rules\sets\`. At connect it
  leaves out rules naming a list it does not have, so a missing list never stops a
  connection. The core reads the lists as local files; it never fetches them.
- **Applying.** A list ready for the first time restarts a running connection so its rules
  apply. An update to a list already in use is read by the running core itself when the
  service renames the new copy into place (sing-box watches its local lists), so no
  connection is cut for it; checked with sing-box on Linux, not yet on Windows.
- **Uninstall.** `core\rules\sets\` is removed with KOne; the end-to-end test hands the
  service a list, connects with a rule on it, and then checks nothing is left.
- **Security note.** The list bytes are parsed by sing-box running as SYSTEM. That was
  already true of the remote rule sets the core downloads itself; here the bytes are also
  limited in size, matched to their digest and header, and read once by a check before
  they are kept. Only the installed KOne.exe may use the pipe.

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
