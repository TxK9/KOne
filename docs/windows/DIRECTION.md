# KOne for Windows: where it stands and where it goes

An independent assessment written after the second round of priorities (node management,
bandwidth, DNS diagnosis, usage, category rules). It is meant to be argued with. Evidence
levels are stated: *source* (read the code), *unit* (automated tests), *CI* (installed and run
on Windows x64 and ARM64 runners), *device* (a real PC). Nothing in KOne has reached *device*
yet.

## Where KOne stands

| | Today | Evidence |
| --- | --- | --- |
| Core | sing-box only, configuration built by a fixed template in a SYSTEM service; the App never sends raw configuration | unit, CI |
| Nodes | Console nodes, share links, subscriptions (links, base64, sing-box JSON, Clash YAML), add and edit by form, export of the person's own nodes | unit, CI build |
| Selection | Health history (median, spread, loss), Automatic with patience, failover from a chosen node and back | unit |
| Routing | Ordered rules (names, addresses, apps, ports, category lists) after the Claude block; ads; modes | unit, CI (category list blocked end to end) |
| Diagnosis | Network (open, sign-in, offline), other VPNs, key, service, node, DNS, sites, exit; DNS lookup four ways | unit |
| Monitoring | Rates, connections, usage by node and program over 30 days, logs | unit, screens |
| Install | MSI, x64 and ARM64, upgrade and uninstall leave nothing behind | CI |
| Size | MSI about 143 MB (x64), of which 63 MB are the City backdrop's 604 pictures | CI artefacts |
| Memory, CPU | Measured from this round in CI's job summary (idle, no key) | CI |
| Code | 16,900 lines of Kotlin (AppModel alone 1,800), 2,300 of Go, 3,900 of tests | source |

## Where the leading clients are ahead

Honestly, in most of what years of users bring:

- **Field hours.** v2rayN and Clash Verge Rev have run on millions of PCs, through every
  driver, antivirus and network. KOne has run on CI runners. No feature closes that gap; only
  real-device testing does (see `RELEASE.md`).
- **Breadth for experts.** Custom proxy groups (fallback, load-balance), profile merge and
  scripts, many rule-provider formats, LAN sharing, TUN stack and MTU choices, multiple cores.
  KOne leaves out raw configuration, scripts, LAN sharing and other cores on purpose
  (`ROADMAP.md`); custom groups are the one omission experts will miss most.
- **DNS options.** Fake-IP, per-domain nameserver policy, hosts. KOne has validated presets
  and custom servers, no fake-IP and no hosts yet.
- **Footprint.** A JVM with Skia is heavier than Clash Verge Rev's Tauri shell or v2rayN's
  .NET; KOne's installer is larger than either. This round adds the measurements; the numbers
  decide whether it matters.
- **Platforms.** Both run on more than Windows.

## What people struggle with in those clients

From their issue trackers and forums:

- **"Connected, but nothing works" under TUN**, most often DNS
  ([v2rayN #7798](https://github.com/2dust/v2rayN/issues/7798)), with logs only experts read.
- **Conflicts with other VPNs**, such as Tailscale
  ([v2rayN issues](https://github.com/2dust/v2rayN/issues)).
- **Sign-in networks** (hotels, airports): the system's connectivity probe and the login
  redirect are pulled into the tunnel, so the page never appears
  ([how captive portals work](https://www.purple.ai/guides/captive-portal-login-troubleshooting-common-issues-and-optimizing-user-experience)).
- **Resource leaks in the UI**: a connections page leaking memory, a traffic graph keeping the
  CPU busy while hidden ([Clash Verge Rev 2.5.2](https://github.com/clash-verge-rev/clash-verge-rev/releases/tag/v2.5.2),
  [Clash Verge 1.3.x](https://github.com/zzzgydi/clash-verge/releases)).
- **Configuration as the interface**: YAML, rule syntax and group logic are the price of
  their power; a person who just wants a site to work has to learn routing first.

## Where KOne is ahead, or can be

1. **Safety by construction.** The privileged side takes typed, validated fields only, and
   every input that replaces something (subscriptions, category lists, nodes) is checked by
   sing-box first and keeps the last good copy. Two implementations of the template, Go and
   Kotlin, are held identical by a test.
2. **Saying what is wrong in words.** Diagnosis now starts with the network itself and
   with other VPNs, the two causes the leaders leave people to guess at.
3. **Sign-in networks handled end to end** (this round): KOne notices the network holding
   traffic, steps aside on one press, opens the page and connects again when the network
   opens. Neither leading client does this.
4. **Quiet when unseen.** Animation stops when minimised; CI now fails if the minimised App
   keeps a core more than 10 % busy, so a regression of the kind above is caught before release.

## Direction

The aim is not more switches. It is fewer moments where a person has to understand networking
to get what they want. In order of value for effort:

### Without a decision (KOne's own judgement; in progress or next)

1. **Outcome-based routing: "make this site work".** Name a site; KOne tries it straight out
   and through the nodes (the core can test any address through any outbound), shows what
   works and how fast, and with one press writes the rule. Rules from evidence instead of from
   syntax. Sites the console refuses are never tried.
2. **Network profiles.** Remember, per network, how KOne should behave (home: Direct mode;
   office: System proxy; elsewhere: Automatic), recognised by its gateway, kept locally.
3. **Passive health.** Read stalls in real connections to notice a failing node sooner and
   probe less.
4. **AppModel in parts.** 1,800 lines in one class is where bugs will hide; split into
   connection, nodes, rules and tools controllers, a part at a time, each with tests.
5. **Hosts and QR export.** Small, expected, already on the roadmap.

### Needing the owner

| Proposal | Why | Cost or risk |
| --- | --- | --- |
| City pictures on demand | Installer from about 143 MB to about 80 MB; most people keep the ink backdrop | Needs a place to host 63 MB of pictures (the website); offline first run shows ink until fetched |
| Fake-IP DNS as an option | Faster first connections, no DNS leaks for proxied names | Some programs cache fake addresses; a template change in Go and Kotlin |
| Custom groups (fallback, load-balance) | The expert feature most missed | More surface in the template; must stay typed and validated |
| Opt-in crash and health reports | Turns field hours into evidence | Privacy; a service to receive them |
| Real-device test round | The one gap no code closes | Hardware and time |
| Other platforms | Reach | A second packaging and service model |

## How progress is judged

Each item lands with tests at the level it can reach, keeps the Go/Kotlin consistency test
passing, passes Windows CI on x64 and ARM64, and is marked here with the evidence it has.
Claims of being better than the leading clients wait for *device* evidence.
