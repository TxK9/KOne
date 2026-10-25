# Shared: node continuity across updates, and Clash proxies sing-box takes

Branch `codex/claude-shared-completion-20261025`, based on `64fe668`. Scope: `shared/` and its
tests. No platform UI, template or build configuration changed.

## Problems

1. **A choice moves to another server after a subscription update.** Both Apps remember the
   chosen node by its tag (Windows: `Account.select`, kept in the binding; Android: sing-box's
   `cache_file`, which stores the selector's choice by tag). A subscription's tags are its names
   in order, made unique with a number (`SubscriptionContent.tagged`: "Tokyo", "Tokyo 2").
   - When a provider reorders two nodes with the same name, "Tokyo 2" comes back on the other
     server, and the person is moved there without being told.
   - When a provider renames the node ("JP 01 | 1.0x" becomes "JP 01 | 1.5x") or drops a
     namesake, the tag is gone. Windows falls back to the first node, and Android to the
     selector's default.
2. **The same node listed twice is shown and tested twice.** Nothing in the shared reading
   path tells such entries apart. Windows' own imports do this (`Imports.key`), but its
   subscriptions do not. Some panels also write their notices ("剩余流量", "套餐到期") as copies
   of the first real node, so a naive merge could keep the notice and lose the node.
3. **One Clash proxy could break a whole profile.** The share-link parser normalizes Reality keys
   and refuses VLESS flows and uTLS fingerprints that sing-box does not take (`a960977`). The
   Clash reader passed them through unchecked. sing-box then refuses the whole configuration:
   - on Android, `Libbox.checkConfig` fails the remote profile's update or creation;
   - on Windows, the check has to halve the node list to find the proxy.

## What changed in `shared/`

`NodeIdentity.kt` (new), which nothing calls yet:

- `key(outbound)`: an opaque identifier of 64 lowercase hex digits. It is the SHA-256 of the
  label `kone.node.key.v1`, a NUL, and the outbound without its tag as JSON with object keys
  sorted. It is equal for the same node however that node is written or named. Settings and
  credentials cannot be read from it. It is still for comparing, not publishing, because a
  guessed password could be checked against it.
- `endpoint(outbound)`: the server and account, opaque in the same way, and public now. It is
  the SHA-256 of `kone.node.endpoint.v1`, a NUL, and one compact JSON array holding, in order:
  - the protocol, in lowercase;
  - the address, trimmed, in lowercase and without IPv6 brackets;
  - the ports as an array: the one port, or the port ranges in sorted order;
  - `uuid`, `username`, `user`, `password`, `auth_str` and `auth`, empty when absent.

  For example: `["trojan","203.0.113.1",["443"],"","","","pw","",""]`. Every field is a JSON
  string of its own, so no character in a field can stand for a boundary between fields.
  Joining the fields with bare NULs, as an earlier revision of this branch did, let different
  accounts share a form. For example, user `a` NUL with password `b`, and user `a` with
  password NUL `b`, joined to the same bytes. A comma inside a port range could likewise pass
  for two ranges.

  It returns null for an outbound without a server. TLS, transport and chain settings do not
  change it.
- Both identifiers use only `MessageDigest` and a table of hex digits. Both exist on API 21, the
  oldest Android KOne is built for, so neither relies on core-library desugaring.
- A label holds no NUL, so the first NUL ends it. The labels keep a key and an endpoint apart.
  The API is unreleased, so the endpoint form changed under its `v1` label; a form changed
  after release takes a new label.
- `distinct(nodes)`: keeps each tagged node once, the first occurrence. A chain through a dropped
  twin is redirected to the kept twin, and twins that only match once their chains are joined
  go too. `twins` maps each dropped tag to the kept one. Run it after `NodeFilter.apply`, so a
  notice copied from a real node never stands in for it.
- `follow(tag, before, after)`: where the chosen node of the previous list is in the new one.
  The rules are tried in this order:
  1. The same node (`key`).
  2. The node on the same server and account under new settings. This applies only when that
     server and account carry one node before and one node after; exact twins count as one.
     - If other nodes shared the server and account before (CDN-fronted nodes with one address
       and user ID), this rule is skipped.
     - If the old node became several different nodes there, none of them the same node,
       `follow` returns null. This holds even when one of the new nodes kept the old tag.
  3. The same tag, when the provider moved the node to a new address and kept its name. This
     applies unless that tag now belongs to a node that was another one before.

  It returns null when the node is gone or was split as described above. A tag that is not a
  node of `before` (Automatic, a country, the console's nodes) comes back unchanged.
- `renamed(before, after)`: old tag to new tag for every node that changed tag. Use it to move
  per-node state such as delays, health history and the choice.

`ClashProxies.kt`, which takes effect in both Apps at once:

- A Reality `public-key` is read in either base64 alphabet, padded or not, and refused unless it
  is 32 bytes. This shares `ShareLinks.realityKey` with the share-link parser.
- A VLESS `flow` other than `xtls-rprx-vision` is refused, as mihomo and sing-box both refuse it.
- A `client-fingerprint` that sing-box does not know is dropped over plain TLS, as mihomo drops
  it, so the node stays usable. With Reality, which needs a fingerprint, the proxy is refused.

`ShareLinks.kt` only exposes its existing Reality-key normalization and fingerprint list
(`realityKey`, `FINGERPRINTS`). Share-link behavior is unchanged.

## Evidence

- `desktop/gradlew test --tests 'cc.txwd.kone.shared.*'` (Linux, JDK 21): 69 tests pass. That is
  the 51 existing tests, 17 new `NodeIdentityTest` tests and 1 new Clash test.
- With the previous NUL-joined endpoint form, the new boundary regression fails. Both accounts
  get the same endpoint, `e2c14378…`, and `follow("Proxy A")` returns `Proxy B`, the other
  account under another name. A temporary probe, not committed, showed the `follow` result.
- With the new ambiguity check removed, the split regression test fails: `follow` picks
  `Osaka A`, the first of the two new nodes.
- The expected `key` and `endpoint` values in the tests were computed separately with Python's
  `hashlib` and `json` from the labelled canonical forms. That includes the two boundary
  accounts, whose NULs JSON writes as `\u0000`.
- Android API 21 signature check: animal-sniffer 1.24 with `android-api-level-21` 5.0.1_r2, run
  on the compiled `NodeIdentity` classes. It found no references outside API 21, with Kotlin's
  own libraries excluded. As a control, the same check flags `ShareLinks` for `java.util.Base64`
  (API 26). That use predates this change, and the Android build's `desugar_jdk_libs` 2.1.5
  lists `java.util.Base64` among the APIs it backports to API 21.
- The new Clash test fails against the previous `ClashProxies.kt`, which accepted all five
  fixture proxies.
- sing-box built from `core/` (go1.25.5, tags `with_utls,with_quic,with_gvisor`) checked one
  configuration per fixture proxy:
  - The previous reader's outputs failed `sing-box check` with `unsupported flow`,
    `decode public_key`, `invalid public_key` and `unknown uTLS fingerprint` (twice).
  - Every output of the new reader passes. The three proxies it refuses are counted with a
    reason in fixed words.
- `desktop/gradlew test` (the full desktop JVM suite on Linux): 256 tests pass, 0 fail, and
  14 are skipped. The skipped tests need Windows, the installed service, a real core or the
  network (for example `ServiceEndToEndTest` and `NetworkReachTest`).
- Not run: the Android JVM tests, because this environment has no Android SDK. Android Clash
  fixtures use none of the changed fields (source inspection only).
- No device, installer, live network or Windows run.

## Platform follow-up (owned by the client conversations)

Nothing below is wired yet. Until it is, selection behaves as before.

**Windows** (`desktop/.../app`):

- Before a subscription's nodes are replaced (`SubscriptionUpdates.fetch`, then
  `AppModel.loadNodes`), keep the merged outbounds.
- After the replacement, call `NodeIdentity.follow(selected, beforeMerged, afterMerged)` on the
  merged lists, so tags renamed by `Merge.into` are covered. Then do one of:
  - for a different tag, `Account.select(newTag)`, and move the running core with
    `ClashApi.select` when connected;
  - for null on a subscribed node, follow the removal policy below.
- Move `delays` and `Health` entries with `NodeIdentity.renamed`.
- Optionally apply `NodeIdentity.distinct` to `Subscription.kept` after `NodeFilter.apply`, and
  map a chosen dropped twin through `twins`. `Imports.key` can become `NodeIdentity.key`.

**Android**:

- In `UpdateProfileWork`, `EditProfileViewModel` and `NewProfileViewModel`, read the
  outbounds of the profile file before writing the new content.
- After `serviceReload()`, for the selector `PairedProfile.PROXY_TAG`, map the current choice
  with `NodeIdentity.follow`. When the tag changes, call
  `CommandTarget.standaloneClient().selectOutbound(group, newTag)`. sing-box's cache keeps the
  old tag, which may now name another server.
- Optionally apply `NodeIdentity.distinct` in `SubscriptionProfile.build` after
  `NodeFilter.apply`.

**Removal policy (decided by the owner).** When `follow` returns null for a selected subscribed
node, the client switches to its existing Automatic group and shows this notice:

> Selected node is no longer available. Switched to Automatic.

The Automatic group is `KoneConfig.AUTO_TAG` on Windows and `Routing.AUTO` on Android. On
Android the notice is a string resource with this English text as its default, and existing
translations are kept. The notice is not a constant in `shared/`, because each client keeps its
own display text.
