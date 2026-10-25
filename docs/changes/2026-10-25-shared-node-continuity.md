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

- `key(outbound)`: the outbound without its tag, with object keys sorted. Equal for the same node
  however it is written or named. It holds credentials, so it is for comparing only, never for
  display or logs.
- `distinct(nodes)`: keeps each tagged node once, the first occurrence. A chain through a dropped
  twin is redirected to the kept twin, and twins that only match once their chains are joined
  go too. `twins` maps each dropped tag to the kept one. Run it after `NodeFilter.apply`, so a
  notice copied from a real node never stands in for it.
- `follow(tag, before, after)`: where the chosen node of the previous list is in the new one.
  The rules are tried in this order:
  1. The same node (`key`).
  2. The node on the same server and account under new settings. This applies only when no
     other node shared that server and account before, so CDN-fronted nodes that share an
     address and user ID are not confused.
  3. The same tag, when the provider moved the node to a new address and kept its name. This
     applies unless that tag now belongs to a node that was another one before.

  It returns null when the node is gone. A tag that is not a node of `before` (Automatic, a
  country, the console's nodes) comes back unchanged.
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

- `desktop/gradlew test --tests 'cc.txwd.kone.shared.*'` (Linux, JDK 21): 65 tests pass. That is
  the 51 existing tests, 13 new `NodeIdentityTest` tests and 1 new Clash test.
- The new Clash test fails against the previous `ClashProxies.kt`, which accepted all five
  fixture proxies.
- sing-box built from `core/` (go1.25.5, tags `with_utls,with_quic,with_gvisor`) checked one
  configuration per fixture proxy:
  - The previous reader's outputs failed `sing-box check` with `unsupported flow`,
    `decode public_key`, `invalid public_key` and `unknown uTLS fingerprint` (twice).
  - Every output of the new reader passes. The three proxies it refuses are counted with a
    reason in fixed words.
- `desktop/gradlew test` (the full desktop JVM suite on Linux): 252 tests pass, 0 fail, and
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
  - for null on a subscribed node, say that the node left its subscription and choose
    Automatic, rather than quietly taking the first node.
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

Behavior to confirm with the owner before wiring: whether a node that is gone should fall back
to Automatic (suggested) or to the first node, as today, and how the one-line notice reads.
