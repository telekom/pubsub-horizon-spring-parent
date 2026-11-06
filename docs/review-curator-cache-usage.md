<!--
Copyright 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# Review: `CuratorCache` usage in the local subscription cache

Branch: `feature/local-cache-with-backup` | Reference: commit `c2a1d1b` | Curator `5.7.1` | Analysis; measure 1 (`SINGLE_NODE_CACHE`) has since been implemented.

## Recommendations at a glance

**Keep `CuratorCache` and switch to `SINGLE_NODE_CACHE`.** I do not recommend switching recipes, even as part of a later refactoring.

### Why use `CuratorCache` rather than `PersistentWatcher` directly

`CuratorCache` with `SINGLE_NODE_CACHE` **uses** a `PersistentWatcher` with `addWatch(PERSISTENT)`, plus ready-made logic for event translation and synchronization. The constructor of `CuratorCacheImpl` in 5.7.1 demonstrates this: `persistentWatcher = new PersistentWatcher(client, path, recursive)`. There is therefore no difference on the ZooKeeper side: It is the same watch on the same node. The only difference is **who writes and takes responsibility for the logic surrounding the watch**.

`PersistentWatcher` is a **lower-level Curator abstraction** for persistent ZooKeeper watches.
`CuratorCache` additionally manages cache storage, event translation, the initial rebuild and synchronization after reconnecting. When using `PersistentWatcher` directly, the surrounding application logic still needs to be implemented. The application must read head data itself, translate events into cache actions, handle `NONODE` and deletions, and reconcile again after every registration and reconnect.

**The recommendation is to keep using `CuratorCache` and its ready-made, tested logic**, rather than rebuilding that logic ourselves. However, `CuratorCache` should be switched to `SINGLE_NODE_CACHE` to disable the default recursive mode.

| Task | `CuratorCache` + `SINGLE_NODE_CACHE` | Direct `PersistentWatcher` usage |
|---|---|---|
| Register the persistent watch at startup and register it again after reconnects | Curator's `PersistentWatcher` | `PersistentWatcher` |
| Reread the current ZNode after registration or reconnect | Curator rereads the ZNode and updates the cache, catching up with changes made in the meantime. | **Implement yourself:** Reread and reconcile after registration/reconnect so that no change during the transition is missed. |
| Detect ZNode changes and provide the corresponding ZNode data in the event | Stores ZNode data internally, compares it and delivers create/change/delete events with ZNode data; unchanged versions do not produce `NODE_CHANGED` | Delivers raw `WatchedEvent`s with type and path, but without ZNode data or the previous version; **implement yourself:** call `getData` and compare if needed |
| Represent a missing ZNode (`NONODE`) and deletion in the cache | Represented in `CuratorCache` state/events | **Implement yourself:** Detect `NONODE` or deletion and trigger the appropriate application action. |
| Filter irrelevant watch events such as `NodeChildrenChanged` and `Watcher.Event.EventType.None` | Curator | **Implement yourself:** Filter irrelevant types to avoid unnecessary reconciliation. |
| Compare ZNode versions and report only relevant changes | `CuratorCache` | **Implement yourself:** Compare versions or otherwise make reconciliation idempotent. |
| Validate snapshot data, and prepare and activate the subscription snapshot | Application code | Application code |
| Release the watch and associated state on close | The caller closes `CuratorCache`; Curator releases the watch and storage | **The caller must close `PersistentWatcher`** and clean up its own state. |

In the current code, PREPARED uses the event payload directly. ACTIVATED, in contrast, uses the event only as a trigger and rereads the head (see B3); the payload benefit is therefore not currently used there.

The parts we would have to build ourselves are precisely the most delicate flows: startup, reconnect, and changes that occur between reading and confirmation of `addWatch`. Curator encapsulates these, tests them and continues to maintain them. The only gain is one or two `getData` calls per event, on ZNodes containing only a few bytes and with infrequent head changes. **The cost-benefit ratio therefore clearly favors `CuratorCache`.** The weaknesses in our use of `CuratorCache` are covered in two ways:
- The default recursive mode is disabled with `SINGLE_NODE_CACHE`.
- Our own reread after reconnecting covers change detection based only on the version.

| # | Measure | Type | Rationale |
|---|---|---|---|
| 1 | `CuratorCache.build(client, path, CuratorCache.Options.SINGLE_NODE_CACHE)` for both caches | **implemented** | Child nodes and nested paths can no longer produce incorrect head events (B2); unnecessary `getChildren` calls disappear (B1). Implemented in `ZooKeeperSubscriptionHeadWatcher`; covered by `bothHeadsAreWatchedAsSingleNodes` and `childNodesBelowPreparedHeadAreNotReportedAsHeadChanges`. |
| 2 | ZooKeeper server version >= 3.6 | **done** | The deployed image is `zookeeper:3.9.5-jre-17`; version 3.9.5 was confirmed using `srvr` in `awsd-test-system` (B6). |
| 3 | **Keep** rereading both heads after RECONNECTED and document it in code | **retain** | Ensures correctness because `CuratorCache` detects changes only through `stat.version` (see "Limitations"). |
| 4 | Reject nested paths in auto-configuration (E) | optional | Misconfiguration is detected at startup; with measure 1, this is only additional protection. |
| 5 | Simultaneous MongoDB loads after reconnecting (B5) | only if needed | Address only if metrics show load spikes. |

**Do not implement:**
- **C (listener filtering):** Redundant alongside measure 1.
- **D (reading cache storage):** Introduces a second read path to save two reads per infrequent change.
- **F (direct `PersistentWatcher` usage):** This would be the same watch that `CuratorCache` already uses internally. We would merely replace Curator's ready-made, tested logic with our own code. That saves one or two requests per event, but introduces our own risks in the most delicate flows (see above).
- **G-I (one-shot watches, `NodeCache`, polling):** More custom code or changed product semantics.

## Prompt

```text
Review the use of Apache Curator `CuratorCache` in our solution for unnecessary ZooKeeper load, unnecessary events, risks and avoidable complexity. Do not change code; provide an evidence-based analysis and recommendations.

Search the entire repository for `CuratorCache` and the associated listeners, especially `ZooKeeperSubscriptionHeadWatcher`. First determine the actual Curator version used by the build. Check its API behavior against the matching documentation or source code. Do not assume whether `CuratorCache` fetches children or which events it produces.

Analyze each usage:

- Which ZNode path is watched and whether children are actually expected below it.
- Which ZooKeeper operations occur at startup, on changes, after reconnects and on close: for example `getData`, `getChildren`, watches or resynchronization.
- Which listener events can occur, including initialization, creation, modification, deletion and reconnect, and which the application actually processes.
- Whether irrelevant or duplicate listener events trigger unnecessary reconciliation, head reads or snapshot loads.
- Whether two or more watched paths overlap, causing the same changes to be processed multiple times.
- Which correctness and failure risks the current usage prevents, and which it may introduce.

Then evaluate alternatives, such as a more targeted watch on a single ZNode, other Curator recipes, or the current `CuratorCache` usage with adjusted listener/cache configuration. In particular, consider initial synchronization, watch re-registration, reconnects, possible race conditions and implementation effort. Do not recommend an alternative solely on the basis of presumed lower load.

Present the result in this format:

1. Brief assessment: Is `CuratorCache` appropriate here or likely oversized?
2. Findings for each cache/listener, with file path and relevant methods; explicitly label assumptions.
3. Expected ZooKeeper requests and event volume per pod, separated into startup, normal operation and reconnect. If this cannot be reliably determined from code, identify the missing measurements and a concrete measurement plan.
4. Comparison of practical alternatives with performance, risk and complexity trade-offs.
5. Recommendation clearly separating required correctness measures from optional optimization.
6. If applicable, focused tests or metrics that confirm or refute the suspected extra costs.

Base statements about requests and events on the specific Curator version and observed code path. Do not claim unnecessary `getChildren` requests or duplicate events without supporting evidence.
```

## Foundations

**Version:** [horizon-spring-boot-autoconfigure/build.gradle](../horizon-spring-boot-autoconfigure/build.gradle#L45-L46) uses `curator-framework` and `curator-recipes` version `5.7.1`.

**Usages:** There are exactly two, both in [ZooKeeperSubscriptionHeadWatcher.java](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadWatcher.java#L118-L124):

```java
this.preparedCache  = CuratorCache.build(client, preparedPath);   // without options
this.activatedCache = CuratorCache.build(client, activatedPath);  // without options
preparedCache.listenable().addListener((type, oldData, data) -> onPreparedHeadChanged(data == null ? null : data.getData()));
activatedCache.listenable().addListener((type, oldData, data) -> onActivatedHeadChanged());
```

**Paths (Helm defaults):** `/horizon/subscriptions/prepared` and `/horizon/subscriptions/activated` ([horizon-comet/values.yaml](../../horizon-helm-charts/horizon-comet/values.yaml#L126-L127)). The paths are siblings and do not overlap. Validation only checks that they are absolute and distinct ([LocalSubscriptionCacheAutoConfiguration.java](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/LocalSubscriptionCacheAutoConfiguration.java#L124-L130)). Nested paths are not rejected.

**Curator 5.7.1 behavior** (source code of `CuratorCache`, `CuratorCacheImpl` and `PersistentWatcher` at tag `apache-curator-5.7.1`):

| Aspect | Evidence in Curator source code |
|---|---|
| Implementation | `CuratorCache.build(...)` always creates `CuratorCacheImpl`. The TreeCache fallback is available only through `bridgeBuilder`. |
| Mode | `recursive = !options.contains(SINGLE_NODE_CACHE)`, so **recursive** without the option |
| `SINGLE_NODE_CACHE` | Same implementation (`CuratorCacheImpl` with `PersistentWatcher`), but `addWatch(PERSISTENT)` instead of `PERSISTENT_RECURSIVE`, and no `getChildren`. It remains a persistent watch; cache storage remains in memory. |
| Watch | `PersistentWatcher` with `addWatch(PERSISTENT_RECURSIVE)` (recursive), or `PERSISTENT`; requires **ZooKeeper server >= 3.6**. |
| Startup | `start()` -> `persistentWatcher.start()` -> `reset()`: one `addWatch`; after an OK callback, `rebuild()` |
| `rebuild()` | `nodeChanged(root)` and `nodeChanged` for every additional stored path |
| `nodeChanged` | One `getData` -> `putStorage` -> `checkChildrenChanged` |
| `checkChildrenChanged` | Only if recursive **and** (`oldStat == null` or `cversion` changed) -> one `getChildren`. With `SINGLE_NODE_CACHE`, there is no `getChildren`. |
| Events | `NODE_CREATED` (new path in storage), `NODE_CHANGED` (**only when `version` changes**), `NODE_DELETED`, `initialized()` (unused: the lambda uses only `event`) |
| Watch events | `NodeCreated`/`NodeDataChanged` -> `nodeChanged`; `NodeDeleted` -> `removeStorage`; `NodeChildrenChanged` and `None` are ignored |
| Reconnect | `PersistentWatcher` registers the watch again (`addWatch`) on every `newState.isConnected()` (CONNECTED/RECONNECTED/READ_ONLY), then invokes `rebuild()`. |
| `addWatch` failure | Callback with result != OK -> **immediately calls `reset()` again**, without backoff |
| Close | `watchers().remove(...).guaranteed().inBackground()` (one request), `storage.clear()` |

## 1. Brief assessment

`CuratorCache` is **functionally appropriate, but oversized in its default configuration**. It watches two individual leaf ZNodes but runs in recursive mode. This produces an unnecessary `getChildren` call per cache at startup and whenever the node is recreated. Any child nodes are also treated as head events. The cache's local storage is never read; the application uses only notifications and rereads the heads itself. The actual extra load is small: one request per cache at startup and two redundant `getData` calls per ACTIVATED change. The more relevant issue is **robustness against child nodes and nested paths**. `CuratorCache.build(client, path, Options.SINGLE_NODE_CACHE)` fixes both without an architectural change. With this option, `CuratorCache` is **appropriate and the best available choice** for our use case (see "Why use `CuratorCache` rather than `PersistentWatcher` directly").

## 2. Findings

### B1 - Recursive mode for leaf ZNodes (both caches)

- **Location:** [ZooKeeperSubscriptionHeadWatcher.java](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadWatcher.java#L118-L119), constructor
- **Evidence:** No `Options` are passed, so `recursive = true`. On the first `nodeChanged(root)`, `oldStat == null`, so `getChildren(root)` follows. This also applies after `NodeDeleted` + `NodeCreated`, because the storage entry was removed beforehand.
- **Assessment:** No children are expected below the heads. This is **verified** in `awsd-test-system` (namespace `integration`): `ls -R /horizon` shows only the two heads, both with `numChildren = 0` and `cversion = 0`. There have therefore never been children below the heads. Other environments have not been checked. At startup or recreation, each cache produces **one unnecessary `getChildren`**. During normal operation (`NodeDataChanged`), `cversion` remains unchanged, so no additional `getChildren` follows.
- **Server side:** A `PERSISTENT_RECURSIVE` watch is registered instead of a `PERSISTENT` watch. Server-side costs are low, but the watch covers the entire subtree.

### B2 - Listeners ignore event type and path (both caches)

- **Location:** [ZooKeeperSubscriptionHeadWatcher.java](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadWatcher.java#L122-L124)
- **Evidence:** The lambdas inspect neither `type` nor `data.getPath()`. In recursive mode, they also fire for descendants.
- **Consequences if children exist** (conditional; there are none in `awsd-test-system`, see B1):
  - PREPARED: The child node's payload is parsed as a PREPARED head (`reconcilePreparedHead`). Either an incorrect snapshot is preloaded or a parsing warning occurs. If a child is deleted, `data == null`, leading to `discardPreparedSnapshot()`. A valid preload is then discarded; this is a **correctness risk**.
  - ACTIVATED: Every child event triggers immediate full reconciliation (two `getData` calls). This is harmless but unnecessary load.
- **Nested paths:** Validation permits nested paths, for example `activated=/h` and `prepared=/h/prepared`. Every PREPARED write then triggers **immediate, unjittered** ACTIVATED reconciliation on all pods, defeating the PREPARED jitter. This does not occur with Helm defaults.

### B3 - Cache storage is unused; ACTIVATED is read twice

- **Location:** [`onActivatedHeadChanged()`](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadWatcher.java#L169-L176) -> [`reconcilePreparedAndActivatedHeads`](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadReconciler.java#L73)
- **Evidence:** Curator has already fetched the new value using `getData`. The event serves only as a trigger; afterwards, the reader rereads PREPARED and ACTIVATED using `getData` ([ZooKeeperSubscriptionSnapshotHeadReader.java](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionSnapshotHeadReader.java)).
- **Assessment:** Each ACTIVATED change produces **three `getData` calls instead of one**. With infrequent changes and small ZNodes, this is negligible. Rereading does not provide stronger consistency (no `sync()`), but coalesces rapid consecutive changes into the latest value and uses a common code path with reconnect and startup. This is a **deliberate simplification, not a bug**.
- **PREPARED** uses the event payload directly ([`onPreparedHeadChanged`](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadWatcher.java#L183-L196)) and does not produce an additional read.

### B4 - Duplicate initial reconciliation at startup

- **Location:** [`start()`](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadWatcher.java#L134-L153)
- **Evidence:** `start()` starts both caches, then schedules `runInitialReconciliation`, which executes two `getData` calls. The ACTIVATED cache's asynchronous `rebuild()` subsequently produces `NODE_CREATED`. The listener blocks on the watcher monitor until `start()` releases it, then sees `watching == true` and executes `reconcileAllHeads`. These are **another two `getData` calls**.
- **Snapshot loads:** There is no duplicate load. The second run encounters `cache.isFresh() && cache.isActiveSnapshot(head)` ([Reconciler](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadReconciler.java#L150)), and `prepare()` skips known versions ([LocalSubscriptionCache.java](../horizon-core/src/main/java/de/telekom/eni/pandora/horizon/cache/service/LocalSubscriptionCache.java#L195-L208)).
- PREPARED's `NODE_CREATED` produces a jittered `reconcilePreparedHead`, without I/O. Loading occurs only if PREPARED is neither active nor already prepared; that is the intended case.
- **Assumption about ordering:** The initial event arrives after `start()` because Curator works asynchronously and the listener needs the monitor. This is plausible but not guaranteed. If `zooKeeperConnected` is still `false` at that point, the second run does not occur.

### B5 - Reconnect: Curator rebuild and our own reconnect read run in parallel

- **Location:** [`onConnectionStateChanged`](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadWatcher.java#L204), and `PersistentWatcher.reset()` in Curator
- **Evidence:** On RECONNECTED, Curator registers the watch again for each cache (`addWatch`), then executes `rebuild()` (`getData`; no `getChildren` because `cversion` is unchanged). The watcher additionally schedules a jittered `reconcileAllHeads` (two `getData` calls).
- **If ACTIVATED changed during the outage,** `rebuild()` produces `NODE_CHANGED`. `onActivatedHeadChanged()` then cancels the jittered reconnect run and reconciles **immediately**. If the new snapshot was not preloaded through PREPARED because the outage prevented this, **all pods load from MongoDB at almost the same time**. Reconnect jitter does not apply in precisely this case. This is an **inference from the code**; load and frequency depend on outage duration.
- **Positive:** Epoch and commit-gate logic prevents results from the old connection from being applied. This protects against differences in event timing between Curator and the watcher.

### B6 - Dependency on ZooKeeper server >= 3.6 and retries without backoff

- **Evidence:** `CuratorCacheImpl` uses only `addWatch`. In `PersistentWatcher.reset()`, a result code != OK causes `reset()` to be called again **immediately and recursively**.
- **Risk:** If the server is older than 3.6 (`UNIMPLEMENTED`) or ACL permissions are missing, a **request loop without backoff** occurs and the cache produces no events. Reconciliation itself would continue, because it uses only `getData`. **Status: done.** The deployed image is `trusted.artifactory.devops.telekom.de/hub.docker.com/zookeeper:3.9.5-jre-17`. In `awsd-test-system` (`horizon-zookeeper-0`, namespace `integration`), `srvr` returns version 3.9.5. Only the ACL scenario remains as a theoretical residual risk.

### B7 - Closing

- [`close()`](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadWatcher.java#L239-L253) closes both caches. This produces one `removeWatches` per cache (guaranteed, in the background). Unproblematic.

### What the current usage provides

- Curator handles watch re-registration, initial synchronization and resynchronization after reconnect. There is no gap between a trigger and re-registration, as can occur with one-shot watches.
- Events occur only on version changes (`NODE_CHANGED` only when `version != old.version`). A rebuild with an unchanged value produces **no** event.
- Head deletions are reported reliably (`NODE_DELETED`).

### Limitations of `CuratorCache`

- **Changes are detected only through `stat.version`.** A head deleted and recreated during a connection outage starts at version 0 again. If storage also held version 0, `rebuild()` produces **no** event even though the content changed. This is an inference from `putStorage`.
- **Our implementation covers this**, because the watcher rereads both heads itself after RECONNECTED. This reread is therefore **required for correctness and must not be removed as an optimization**.
- **Observation in `awsd-test-system`:** Both heads were created on 2026-10-05 (`ctime`) and have since been updated 26 times using `setData` (`dataVersion = 26`, 176 bytes). They are therefore not deleted and recreated, and the version-0 scenario does not occur in normal operation. Protection remains necessary, for example for manual interventions or rebuilding ZooKeeper.

## 3. Expected ZooKeeper requests and events per pod

Assumptions: Both heads exist and have no children. `reconcileInterval` = 60 s (Helm default). Values refer to Curator 5.7.1 and the current code. Retries through the `RetryPolicy` are not included.

| Phase | Curator (2 caches) | Watcher/reconciler | Total | Listener events |
|---|---|---|---|---|
| **Startup** | 2 `addWatch`, 2 `getData`, **2 `getChildren`** | Initial run: 2 `getData` + run triggered by `NODE_CREATED`: 2 `getData` | **10** (with `SINGLE_NODE_CACHE`: 8) | 2 `NODE_CREATED` |
| **PREPARED changed** | 1 `getData` | 0 (uses event payload) | **1** | 1 `NODE_CHANGED` |
| **ACTIVATED changed** | 1 `getData` | 2 `getData` | **3** | 1 `NODE_CHANGED` |
| **Periodic** | 0 | 1 `getData` / 60 s | **1/min** | 0 |
| **Head deleted and recreated** | 1 `getData` (+ **1 `getChildren`**) | 2 `getData` per event (ACTIVATED only) | ACTIVATED: 6 (5) | `NODE_DELETED`, `NODE_CREATED` |
| **Reconnect, heads unchanged** | 2 `addWatch`, 2 `getData` | 2 jittered `getData` | **6** | 0 |
| **Reconnect, ACTIVATED changed** | 2 `addWatch`, 2 `getData` | 2 immediate `getData` (reconnect run canceled) | **6**, plus a possible MongoDB load without jitter | 1 `NODE_CHANGED` |
| **Close** | 2 `removeWatches` | 0 | **2** | 0 |

With N pods, each ACTIVATED change produces 3N reads, periodic reconciliation produces N reads per minute, and a rollout produces approximately 10N requests. This is unproblematic for ZooKeeper as long as N stays in the low hundreds. A reliable assessment requires the **actual pod count** across all consumers (comet, galaxy, etc.). In `awsd-test-system`, the **change frequency** is 26 updates per head over roughly seven days, approximately four per day; production frequency has not been measured. The **server version** has been established as 3.9.5.

## 4. Alternatives

| Option | Performance | Risk | Complexity/effort |
|---|---|---|---|
| **A: Current state** | 10 requests at startup, 3 per ACTIVATED change | Children or nested paths distort events (B2); ZK >= 3.6 required | none |
| **B: `CuratorCache` + `SINGLE_NODE_CACHE`** | 2 fewer `getChildren` at startup, 1 fewer on recreation; non-recursive watch | Children are ignored (`NodeChildrenChanged` is discarded), nested paths have no effect; otherwise identical | One line per cache; existing tests should remain valid |
| **C: B + listener checks `data.getPath()`/`type`** | Same as B | Additional protection | Low; largely redundant with B |
| **D: B + cache storage instead of rereading** (`activatedCache.get(path)`) | 2 fewer `getData` calls per ACTIVATED change and at startup | New coupling: reconciler depends on cache state; reconnect and periodic paths still need `getData`, creating two read paths | Medium; reconciler tests need adjustment |
| **E: Path validation against nesting** | None | Prevents B2 misconfiguration early | Very low |
| **F: Direct `PersistentWatcher` + custom `getData`** | Compared with B, approximately 1 fewer `getData` per ACTIVATED change and 2 fewer per reconnect | Gap between reading and `addWatch`; custom filtering for `None` and `NodeChildrenChanged`; redesign startup/reconnect triggers; implement NONODE handling | High (approximately 60-80 lines of production code, 150-250 test lines); **not recommended**, even during refactoring, because `CuratorCache` internally uses the same `PersistentWatcher` and already provides the surrounding logic |
| **G: One-shot watch (`getData().usingWatcher`)** | 1 read per trigger | Re-registration gap; custom reconnect handling; also works with ZK 3.5 | Medium to high, error-prone |
| **H: `NodeCache`** | Similar to G | Deprecated in Curator 5 | Not recommended |
| **I: Polling only** | N/interval reads, no watches | Activation latency = interval; PREPARED preloading becomes less effective | Low, but changes product semantics |
| **J: `bridgeBuilder`** | Same as A/B | TreeCache fallback for ZK 3.5 | Relevant only if the server is older than 3.6 |

## 5. Recommendation

**Decision:** `CuratorCache` with `SINGLE_NODE_CACHE` (B). Switching to `PersistentWatcher` (F) or another recipe is not justified. `CuratorCache` already uses the same `PersistentWatcher` internally and provides ready-made startup, reconnect and event logic. Direct usage would save only a few requests; we would then have to take responsibility for risks currently covered by Curator (see "Why use `CuratorCache` rather than `PersistentWatcher` directly").

**Required correctness measures**

1. **`SINGLE_NODE_CACHE` for both caches** (option B): **implemented**. Child nodes can no longer be interpreted as head events (B2), and nested paths produce no cross-events. Unnecessary `getChildren` calls also disappear (B1).
2. **ZooKeeper server version >= 3.6** (B6): **done**. Version 3.9.5 is deployed and confirmed in `awsd-test-system`.
3. **Keep rereading after RECONNECTED** and explain it with a short code comment. This protects against change detection based solely on the version (see "Limitations of `CuratorCache`").

**Optional optimization**

4. Add path validation: Reject configurations where one path is a prefix of the other, for example `activated.startsWith(prepared + "/")` and vice versa (E). With measure 1, this is only defense in depth.
5. Address the reconnect herd (B5) only if measurements show MongoDB load spikes. Options: Also apply jitter to ACTIVATED events arriving shortly after RECONNECTED (delays activation), or do not cancel the reconnect run.

**Deliberately not implemented**

- Suppress the duplicate initial run (B4): It costs two reads; suppression adds state logic to event timing that would need race protection.
- Replace the reread on ACTIVATED (B3/D) with a cache-storage read: Two reads per infrequent change do not justify a second read path.
- C, F, G, H, I: See Section 4.

## 6. Tests and metrics

**Tests (using `curator-test` `TestingServer`, already a dependency):**

- *Child-node test:* **Implemented** for `preparedPath` (`childNodesBelowPreparedHeadAreNotReportedAsHeadChanges`). A child is created below `preparedPath`; its data must never reach `reconcilePreparedHead`. Still open: The same test for `activatedPath`, counting calls to `reconcilePreparedAndActivatedHeads`.
- *Nested paths:* `activated=/h`, `prepared=/h/p`; a PREPARED write must not trigger ACTIVATED reconciliation. Alternatively, validation should fail if measure 4 is implemented.
- *Request counting:* Register a Curator `TracerDriver` using `client.getZookeeperClient().setTracerDriver(...)` and count traces by operation. Check the concrete trace names in 5.7.1 before asserting (**assumption**). Expected values are those in Section 3 for startup, ACTIVATED changes and reconnect (`TestingServer.restart()`).
- *Reconnect herd:* Stop the server, change ACTIVATED through a second client, then start the server. Check whether loading occurs without jitter; record `snapshotLoader` calls and their timestamps.

**Operational metrics:**

- ZooKeeper server: `mntr` or Prometheus metrics with request rates by type (`getData`, `getChildren`, `addWatch`), `watch_count` and connection count, measured before and after rollout or the configuration change.
- Application: Correlate existing `LocalSubscriptionCacheMetrics` (snapshot load count and duration) with time since the last RECONNECTED to confirm or refute B5.
- Logs: Observe Curator's `Could not reset persistent watch` warning (B6) and PREPARED parsing warnings (B2).
