<!--
Copyright 2026 Deutsche Telekom AG

SPDX-License-Identifier: Apache-2.0
-->

# Review: Should switching to Hazelcast be tied to Curator's LOST event?

Branch: `feature/local-cache-with-backup` | Reference: commit `c2a1d1b` | Curator `5.7.1` | ZooKeeper server `3.9.5`

> **Grace period:** The default for `stale-local-cache-read-grace-period` is now **300 s** (previously 120 s), including in `awsd-test-system`. The scenarios below were calculated using 120 s; with 300 s, the boundaries shift accordingly.

## Recommendations at a glance

**Do not tie the switch to Hazelcast to LOST.** Switching remains tied to the grace period for stale local data (`stale-local-cache-read-grace-period`).

> **Priority goal:** All pods should use **the same local cache state** wherever possible. Individual pods should not switch arbitrarily to Hazelcast. Every option that increases inconsistency between pods is marked **WARNING: Conflicts with the priority goal** in this document.

| # | Recommendation | Brief rationale |
|---|---|---|
| 1 | **Do not switch on LOST**, either as the only trigger or as an additional early trigger | LOST says nothing about data freshness. Pods would switch **individually** more often and therefore read inconsistent data. **WARNING: Conflicts with the priority goal** ([Reasons against it](#reasons-against-tying-the-switch-to-lost)). |
| 2 | **Continue treating SUSPENDED and LOST identically** | Neither event buffers changes. Neither provides technical information that would require different handling in our implementation. Both lead to the same result ([Section 3](#3-correcting-the-assumption-suspended-does-not-buffer-events)). |
| 3 | **Do not shorten the grace period.** The default was extended to 300 s to support consistent pods, including in `awsd-test-system`. A further extension (up to 15 min) requires a business decision. | The shorter the period, the more often an individual pod falls back to live Hazelcast data while other pods continue reading the snapshot. A shorter period **conflicts with the priority goal** ([Section 5](#5-consistency-between-pods)). |
| 4 | **Keep rereading after every RECONNECTED** | This is the only way to detect changes made during disconnection ([Section 3](#3-correcting-the-assumption-suspended-does-not-buffer-events)). |
| 5 | **Observe SUSPENDED, LOST, RECONNECTED, STALE duration and fallback reads per pod**, and alert when **individual** pods diverge | This directly signals the inconsistency we want to avoid ([Section 7](#7-detailed-recommendations)). |
| 6 | **Keep the session timeout at 30 s; do not increase it** to switch later | A longer session timeout delays detection of half-open connections. Pods remain blind longer and switch at more widely scattered times, causing **more** inconsistency. **WARNING: Conflicts with the priority goal**. The server allows at most 40 s anyway ([Section 5.4](#54-impact-of-the-session-timeout-on-consistency)). |

### Key insight

From the moment it loses its ZooKeeper connection, a pod no longer sees head changes, whether the session is still alive (SUSPENDED) or has expired (LOST). After reconnecting, it detects changes only by rereading. LOST therefore provides no additional information for deciding to switch to Hazelcast. Switching earlier on LOST (approximately 30 s instead of after the 120 s grace period expires) **WARNING: Conflicts with the priority goal** of consistent local cache data across all pods.

### Reasons against tying the switch to LOST

The following points explain the recommendation. The numbered sections below provide background and evidence.

1. **LOST says nothing about data freshness.** What matters is whether ACTIVATED has changed. The pod already loses that knowledge at SUSPENDED. LOST only reports that the session has expired; it provides **no additional information** about the data.
2. **The buffering assumption is incorrect** ([Section 3](#3-correcting-the-assumption-suspended-does-not-buffer-events)). The idea that "everything is buffered until LOST, but not afterwards" has no basis. SUSPENDED and LOST leave the pod with the same data state.
3. **Individual pods would switch more often.** LOST occurs approximately 30 s after SUSPENDED. Any disruption of an individual pod lasting more than 30 s would switch that pod alone to Hazelcast, rather than waiting until 120 s. **WARNING: Conflicts with the priority goal** of consistent local cache data across all pods ([Section 5](#5-consistency-between-pods)).
4. **Two different responsibilities would be tied to one parameter.**
   - **Today they are separate:** The session timeout (30 s) is a technical parameter. It determines how quickly a pod detects a connection failure. The grace period (120 s) is a business decision. It determines how long a pod may continue reading unconfirmed local data.
     - **This supports the priority goal:** Short disruptions of individual pods (under 120 s) do not cause switching. During a shared outage, such as a ZooKeeper ensemble failure, all pods become STALE at almost the same time. Since all pods use the same grace period, they then switch to the fallback **together**. This ensures consistent cache access across all pods.
     - **Limitation:** An individual pod with a disruption lasting more than 120 s still switches alone ([Section 5.2](#52-scenarios)).
   - **With LOST as the trigger, both would depend on the session timeout:** LOST occurs after session timeout multiplied by `sessionExpirationPercent`, approximately 30 s in our setup. Changing the session timeout to detect failures faster or slower would unintentionally also shift when the pod switches to Hazelcast.
   - **This would not provide a way to switch later:** The session timeout would have to be increased. That would also cause **half-open connections to be detected later**: Affected pods would remain blind longer and miss a snapshot switch while other pods were already reading the new snapshot. Switching times would also become more scattered. **WARNING: Later detection of half-open connections conflicts with the priority goal** ([Section 5.4](#54-impact-of-the-session-timeout-on-consistency)). The server also caps the session timeout at 40 s.
5. **Only some causes of STALE would be covered.** LOST does not cover an unreadable head or failed activation while the connection remains established ([Section 4.2](#42-when-reads-switch-to-hazelcast)). The time-based rule would still be needed. There would be two rules for the same goal, meaning more states, more tests and more potential errors.
6. **Switching back and forth during an unstable connection.** SUSPENDED -> LOST -> RECONNECTED -> SUSPENDED ... would alternate between the local cache and Hazelcast. This means inconsistent reads and load spikes on Hazelcast. A pod that jumps between data sources alternately reads a different state from the other pods. **WARNING: Conflicts with the priority goal.**
7. **A pod cannot determine locally whether only it or all pods are affected.** A reaction to LOST behaves identically regardless of how many pods are affected:
   - **If only one pod is affected,** it switches alone. This causes inconsistency and **conflicts with the priority goal**.
   - **If all pods are affected,** they switch together, but earlier than with the grace period. Even short ensemble-wide disruptions (30-120 s) that could otherwise be bridged locally would make all pods switch and put load on Hazelcast. Compared with the grace period, this gains nothing for consistency.

---

## 1. Initial question and goal

**Question:** Should a pod switch to Hazelcast as soon as Curator reports LOST for its ZooKeeper connection, instead of waiting until the grace period expires (default 300 s from STALE)?

**Assumption behind the question:** During SUSPENDED, changes are buffered and delivered after reconnecting; during LOST, they are not. LOST would therefore signal the point at which the local cache is no longer reliable.

**Priority goal:** All pods should use the same local cache state wherever possible. **Individual pods should not switch arbitrarily to Hazelcast**, because this causes inconsistent results between pods. This takes precedence over switching to fresher Hazelcast data as early as possible. All options are evaluated against this goal.

---

## 2. Background: SUSPENDED and LOST in Curator

### 2.1 Connection states

Sources: `ConnectionState` and `ConnectionStateManager`, Curator 5.7.1.

| State | Meaning | Trigger |
|---|---|---|
| **SUSPENDED** | "There has been a loss of connection." The session **may still be alive**. | The ZooKeeper client reports `Disconnected`, immediately after a TCP disconnect or after the read timeout without a response. Not reported again while the state is already SUSPENDED or LOST. |
| **LOST** | Curator assumes that the **session has expired**. | a) The server reports `Expired`/`SESSIONEXPIRED`, b) Curator closes the internal ZooKeeper instance, or c) the session timeout **has elapsed while SUSPENDED**. In case c, Curator forces expiration itself (`injectSessionExpiration()`). |
| **RECONNECTED** | The connection is back, following SUSPENDED, LOST or READ_ONLY. | CONNECTED occurs only once per client instance; afterwards, it is always RECONNECTED. |

### 2.2 Differences at ZooKeeper level

| Aspect | SUSPENDED -> RECONNECTED | LOST -> RECONNECTED |
|---|---|---|
| Session | same session | new session, new ZooKeeper client instance |
| Watches on the server | remain registered, but deliver nothing while disconnected | removed because they are session-bound |
| Ephemeral nodes | remain | removed (we do not use them) |
| Watch registration | the client uses `setWatches`, with additional `addWatch` calls by Curator's `PersistentWatcher` | only `addWatch` by `PersistentWatcher` |
| Possible subsequent states | RECONNECTED or LOST | only RECONNECTED |

### 2.3 Timing with our configuration

- Client: `sessionTimeout: 30s`, `connectionTimeout: 5s` (Helm defaults).
- Server in `awsd-test-system`, read using `conf`: `tickTime=2000`, `minSessionTimeout=4000`, `maxSessionTimeout=40000`.

```text
t = 0      Connection is lost
           TCP disconnect: immediately; no response: after approximately 2/3 of the session timeout (approximately 20 s)
           -> SUSPENDED
t ~ 30 s   Session timeout has elapsed while SUSPENDED -> Curator forces session expiration
           -> LOST
later      New connection with a new session
           -> RECONNECTED
```

If the connection returns before 30 s have elapsed, RECONNECTED follows directly **with the same session**, and LOST does not occur.

**The session timeout cannot be increased arbitrarily**, for example to delay LOST and thereby delay switching:
- **Technically:** The server clamps it to `maxSessionTimeout` (40 s here). Curator uses the negotiated value. A higher limit requires a server configuration change affecting all ensemble clients.
- **From a business perspective:** The session timeout also determines when the ZooKeeper client detects a **half-open connection** (read timeout approximately 2/3 of the session timeout). A high value makes pods appear blindly FRESH for longer and spreads switching times further apart. Both cause **more** inconsistency and **conflict with the priority goal** ([Section 5.4](#54-impact-of-the-session-timeout-on-consistency)).

---

## 3. Correcting the assumption: SUSPENDED does not buffer events

> **The assumption is incorrect.** Neither during SUSPENDED nor during LOST are changes buffered or delivered later after reconnecting. **For our implementation, it makes no difference which event occurs.**

### 3.1 Evidence

**ZooKeeper server (3.9.5, `DataTree.setWatches`):** When reconnecting within the same session, the client sends its watches with the last zxid it has seen.

```java
for (String path : dataWatches) {            // classic one-shot watches
    if (node == null)                          -> NodeDeleted        // replayed
    else if (node.mzxid > relativeZxid)        -> NodeDataChanged    // replayed
    else addWatch(...)
}
for (String path : persistentWatches) {      // watches used by CuratorCache
    childWatches.addWatch(path, watcher, PERSISTENT);
    dataWatches.addWatch(path, watcher, PERSISTENT);   // registered again only; NO event
}
```

- Replay happens only for **classic one-shot watches**, and even there only **one** "changed since zxid X" event is delivered, not every individual change.
- **Persistent watches**, as used by `CuratorCache`, are registered again **without a replayed event**.

**Curator (5.7.1):**
- `ConnectionStateManager` only buffers the **connection states** themselves, in a queue of 25 entries. On overflow, Curator discards the oldest entries.
- `CuratorCache` and `PersistentWatcher` do **not react at all** to SUSPENDED or LOST. Storage remains, and there are no events.
- For every state with `isConnected()`, `PersistentWatcher` registers the watch again using `addWatch`, then invokes `CuratorCacheImpl.rebuild()`. `rebuild()` reads the head using `getData` and compares `stat.version` with storage.
- **Ongoing operations** such as `getData` are not held until reconnection. Curator retries them according to the RetryPolicy; afterwards, they fail.

### 3.2 What actually arrives after reconnecting (same for SUSPENDED and LOST)

| What happened during disconnection | What our listener sees |
|---|---|
| nothing | no event |
| one or multiple `setData` operations on the head | **exactly one** `NODE_CHANGED` with the final state; intermediate states are lost |
| head deleted | `NODE_DELETED` |
| head deleted and recreated, with the same version by coincidence | **no event**, because Curator detects changes only through the version. Covered by our own reread after reconnecting. |

### 3.3 Consequence

- Changes during disconnection are detected **exclusively by rereading**: through Curator's `rebuild()` and by rereading both heads in our watcher after RECONNECTED.
- **If the implementation had relied on the assumption**, for example "events arrive after SUSPENDED, so reread only after LOST", changes during short disconnections would have gone unnoticed. After RECONNECTED, the cache would be FRESH again but hold the old head until the next periodic check, up to 60 s later.
- Our implementation rereads after **every** RECONNECTED. This is correct and must remain in place.

---

## 4. Existing implementation

### 4.1 Reaction to connection states

[`ZooKeeperSubscriptionHeadWatcher.onConnectionStateChanged`](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadWatcher.java#L204-L233)

| Event | Effect |
|---|---|
| SUSPENDED or LOST | Cancel pending PREPARED and reconnect tasks. `zooKeeperConnected = false`, `connectionEpoch++`. On the **first** connection loss, additionally call `reconciler.onConnectionLost()` -> `cache.clearExpectedHead()` -> **STALE**, `staleSince = now`. |
| LOST after SUSPENDED | As above, but without a second `clearExpectedHead()`. STALE and `staleSince` remain unchanged. |
| RECONNECTED (or CONNECTED after starting without a connection) | New epoch; jittered reconciliation (0-10 s) rereads PREPARED and ACTIVATED -> FRESH again if successful |

The epoch and commit gate ensure that work from the old connection cannot apply cache changes after a state transition.

### 4.2 When reads switch to Hazelcast

- [`LocalSubscriptionCache.canServeLocalReads`](../horizon-core/src/main/java/de/telekom/eni/pandora/horizon/cache/service/LocalSubscriptionCache.java#L535-L545): FRESH -> local. STALE -> local while `now - staleSince < staleLocalCacheReadGracePeriod` (default 300 s).
- Afterwards, [`FallbackSubscriptionCacheReader`](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/JsonCacheAutoconfiguration.java#L104) redirects reads to Hazelcast.
- **STALE has several causes**, not just connection problems: an unreadable or missing ACTIVATED head and failed activations (for example a MongoDB load failure), even while the ZooKeeper connection is established.

### 4.3 Mode `fallback-mode: none`

- Reads are exclusively local ([JsonCacheAutoconfiguration.java](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/JsonCacheAutoconfiguration.java#L83-L89)).
- The grace period is `ChronoUnit.FOREVER` ([LocalSubscriptionCacheAutoConfiguration.java](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/LocalSubscriptionCacheAutoConfiguration.java#L237-L240)). A STALE pod keeps reading its last activated snapshot indefinitely.
- A FRESH snapshot is mandatory at startup.
- Health is always `UP`; `cacheStatus` and `staleSince` are available as details.

---

## 5. Consistency between pods

### 5.1 What a STALE pod reads compared with FRESH pods

Assumption: All FRESH pods read snapshot N; one pod is STALE.

| What the STALE pod reads | ACTIVATED is still N (normal case) | ACTIVATED is now N+1 |
|---|---|---|
| **Local** (within the grace period) | **same as the other pods** | different (N instead of N+1) |
| **Hazelcast** | **different** (live data instead of N) | different (live data instead of N+1), but closer |

In `awsd-test-system`, the heads are updated approximately four times per day (`dataVersion = 26` over roughly seven days). During a disruption, it is therefore **usually the normal case** that ACTIVATED remains unchanged. Continuing to read locally is then **more consistent** than Hazelcast. Switching early makes the pod an outlier. **WARNING: Conflicts with the priority goal.**

### 5.2 Scenarios

| Scenario | Affected | Today (120 s grace period) | With switching tied to LOST (approximately 30 s) |
|---|---|---|---|
| Network interruption or GC pause of one pod (seconds) | 1 pod | stays local | stays local (no LOST) |
| Restart of one ZooKeeper server (rolling restart) | approximately 1/3 of pods | stay local (switch to another server, same session) | stay local |
| Disruption of an individual pod, 30-120 s | 1 pod | **stays local** | **switches alone** -> WARNING: Conflicts with the priority goal |
| Disruption of an individual pod lasting more than 120 s | 1 pod | switches alone | switches alone (earlier) |
| Failed snapshot load on one pod | 1 pod | switches after 120 s | LOST does not occur; the time-based rule applies anyway |
| Failure of the entire ensemble | all pods | switch **together** after 120 s | switch **together** after approximately 30 s |

Tying the switch to LOST worsens precisely the cases critical to the priority goal: short to medium disruptions of **individual** pods. **WARNING: This conflicts with the priority goal** of consistent local cache data across all pods.

### 5.3 What the current implementation already does correctly

- **Failure duration is counted from the first event.** `staleSince` starts at SUSPENDED or LOST, whichever occurs first.
- **Ensemble outage:** All pods become STALE at almost the same time and switch together. They remain consistent with each other. "Almost" means immediately after a TCP disconnect, otherwise spread over the read timeout. The degree of spread depends on the session timeout ([Section 5.4](#54-impact-of-the-session-timeout-on-consistency)).
- **Recovery:** The 0-10 s jitter makes pods become FRESH at staggered times. This is a short, bounded window.

### 5.4 Impact of the session timeout on consistency

The session timeout affects not only LOST, but also **when a pod notices at all** that its connection is gone. With a half-open connection, the client receives no TCP disconnect, for example during a network partition, a lost NAT or conntrack entry, or a frozen server. The pod detects disconnection only through the read timeout (approximately 2/3 of the session timeout). This has two effects:

1. **A longer blind period.** Until SUSPENDED is reported, the cache is considered FRESH even though watch events no longer arrive. If a new ACTIVATED head is published during that period, the pod continues reading the old snapshot **and reports itself as current**. The other pods already read the new snapshot. **WARNING: Conflicts with the priority goal.**
2. **More widely scattered switching times.** Each pod calculates its grace period from its own `staleSince`. Pods with a TCP disconnect become STALE immediately; pods with a half-open connection do so only after the read timeout. The longer the timeout, the further apart the times at which pods switch to Hazelcast. During that interval, pods read different data sources. **WARNING: Conflicts with the priority goal.**

**Limitation through the periodic head check** (derived from the code): The watcher reads ACTIVATED every `reconcileInterval` (60 s, with an initial offset through `headCheckJitter`). The reader waits at most `connectionTimeout` (5 s) for a response. If none arrives, this counts as a read failure and the cache becomes STALE. This limits the blind period **to approximately 60-75 s, independently of the session timeout**. Today, ZooKeeper detection usually takes effect sooner (approximately 20 s).

| Session timeout | ZooKeeper detection of a half-open connection | Maximum blind period | Spread of switching times |
|---|---|---|---|
| **30 s (today)** | approximately 20 s | approximately 20 s (ZooKeeper detection is faster than the periodic check) | up to approximately 20 s |
| 40 s (server maximum) | approximately 27 s | approximately 27 s | up to approximately 27 s |
| hypothetical 30 min (requires changed server configuration) | approximately 20 min | approximately 60-75 s (periodic check takes effect first) | up to approximately 60-75 s, **3-4 times as much as today** |

**Conclusion:** A longer session timeout does not postpone switching in a controlled way. It makes detection slower and less uniform. Pods would remain blind longer and switch at more widely scattered times. **WARNING: This conflicts with the priority goal** of consistent cache access across all pods. The correct control for switching later is the grace period, because it extends tolerance without worsening detection.

---

## 6. Special case: Tying switching to LOST in `fallback-mode: none`

Without Hazelcast, there is no destination to switch to. Any conceivable reaction to LOST would be ineffective or harmful:

| Possible reaction to LOST | Consequence | Assessment |
|---|---|---|
| Do nothing (today) | The pod keeps reading the last snapshot and rereads after reconnecting | **correct** |
| Stop reading (`isReady=false`, readiness DOWN) | One pod becomes unavailable; during an ensemble outage, **all** become unavailable | bad |
| Determine the head independently from MongoDB | Each pod decides which snapshot to activate -> pods **diverge**, bypassing the activation authority | **WARNING: Conflicts with the priority goal** |
| Restart or fail liveness | Without ZooKeeper, the pod does not become ready after restarting; during an ensemble outage, **all** remain stuck | bad |
| Use only as a signal (log, metric) | no behavioral change | **useful as an addition** |

**Residual risk of `none`, independent of LOST:** An individual pod with a **permanent** disruption keeps reading the old snapshot indefinitely, and health remains `UP`. Only observation across all pods helps, not LOST.

---

## 7. Detailed recommendations

1. **Do not switch on LOST.** The time-based rule remains the only criterion for fallback. It covers all STALE causes uniformly and is independent of ZooKeeper settings.
2. **Continue treating SUSPENDED and LOST identically.** Both set STALE immediately and lead to rereading after RECONNECTED. This is correct because neither event buffers changes.
   - **Neither event provides information that requires distinguishing between them.** Both provide only connection state, not head or data details.
   - **The only technical difference** is whether the session survived. Only two things depend on this: ephemeral nodes, which we do not use, and watch registration on the server. Curator's `PersistentWatcher` restores registration itself on RECONNECTED in both cases ([Section 2.2](#22-differences-at-zookeeper-level)).
   - Distinguishing between them would only add complexity without improving the decision.
3. **Choose the grace period deliberately:**
   - To support consistent pods, **do not shorten it**. A shorter period makes individual pods switch more often. **WARNING: Conflicts with the priority goal.**
   - **Implemented:** The default was extended from 120 s to **300 s**, including in `awsd-test-system`. A further extension (up to 15 min) requires a business decision. The cost: If a disrupted pod misses a snapshot switch, it reads the old state for longer. With only a few switches per day, this is rare.
   - Lower bound: session timeout + jitter + read time (approximately 45 s). This prevents switching during short disruptions that recover within the session timeout.
   - If consistency is more important than freshness, **`fallback-mode: none`** is the most consistent choice, with the residual risk described in [Section 6](#6-special-case-tying-switching-to-lost-in-fallback-mode-none).
4. **Keep rereading after every RECONNECTED**, ideally with a short code comment so that it is not removed as an optimization.
5. **Keep the session timeout at 30 s and do not increase it to switch later.** Its purpose is failure detection. A higher value prolongs the blind period for half-open connections and spreads switching times further apart. **WARNING: Conflicts with the priority goal** ([Section 5.4](#54-impact-of-the-session-timeout-on-consistency)). The server limits it to 40 s anyway. To switch later, extend the grace period (point 3).
6. **Extend observability:**
   - Counters for SUSPENDED, LOST and RECONNECTED per pod. Implemented as logs (logger `de.telekom.eni.pandora.horizon.cache.events.connection`, default `INFO`); a counter is still missing.
   - STALE duration per pod (from `staleSince`) and fallback reads (`FALLBACK_READS` already exists).
   - Alert when **individual** pods are STALE or using fallback while others are FRESH. This can only be detected across pods in Prometheus, not within an individual pod.

### Open points

- **Business decision:** In the rare case of a missed snapshot switch, how long may a pod read the old state, compared with how often an individual pod may fall back to live Hazelcast data? This determines the grace period or the choice of `none`.
- **Not measured:** The timing figures (approximately 20 s until SUSPENDED without a TCP disconnect, approximately 30 s until LOST) are derived from configuration and source code. If needed, verify them with a test against `TestingServer` (stop and start the server).
- **Not checked in source code:** The ZooKeeper client read timeout (approximately 2/3 of the session timeout) and Curator's default `sessionExpirationPercent = 100` come from knowledge of the libraries. Both affect only the timing figures, not the assessment.

---

## Sources

| Statement | Source |
|---|---|
| Meaning of SUSPENDED, LOST and RECONNECTED | Curator 5.7.1, `org.apache.curator.framework.state.ConnectionState` (Javadoc) |
| LOST after the session timeout elapses while SUSPENDED; connection-state queue (25) | Curator 5.7.1, `ConnectionStateManager.checkSessionExpiration()`, `postState()` |
| Watch registration and `rebuild()` only for `isConnected()` | Curator 5.7.1, `PersistentWatcher.reset()`, `CuratorCacheImpl.rebuild()` |
| Events only when `stat.version` changes | Curator 5.7.1, `CuratorCacheImpl.putStorage()` |
| No event replay for persistent watches | ZooKeeper 3.9.5, `DataTree.setWatches()` |
| Session timeout limits | `awsd-test-system`, `horizon-zookeeper-0`, 4LW `conf` |
| Frequency of head changes | `awsd-test-system`, `zkCli stat` on `/horizon/subscriptions/{prepared,activated}` |
| Watcher reaction | [ZooKeeperSubscriptionHeadWatcher.java](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/ZooKeeperSubscriptionHeadWatcher.java#L204-L233) |
| Reading within the grace period | [LocalSubscriptionCache.java](../horizon-core/src/main/java/de/telekom/eni/pandora/horizon/cache/service/LocalSubscriptionCache.java#L535-L545) |
| Fallback reader and mode `none` | [JsonCacheAutoconfiguration.java](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/JsonCacheAutoconfiguration.java#L83-L104), [LocalSubscriptionCacheAutoConfiguration.java](../horizon-spring-boot-autoconfigure/src/main/java/de/telekom/eni/pandora/horizon/autoconfigure/cache/LocalSubscriptionCacheAutoConfiguration.java#L237-L240) |
