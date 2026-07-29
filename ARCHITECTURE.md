# Architecture — Traffic Inspector (local firewall & egress inspector)

A no-root, on-device Android firewall built on `VpnService`. All packet processing happens
locally; nothing ever leaves the phone. Min SDK 26, target SDK 35. Kotlin, Jetpack Compose
(Material 3), Room, WorkManager, Hilt, Coroutines/Flow, Paging 3.

```
┌──────────────────────────────────────────────────────────────┐
│ UI (Compose + MVVM)  ui/home · ui/connections · ui/apps · ui/rules │
├──────────────────────────────────────────────────────────────┤
│ Repository layer (Flow)        repository/                    │
│  ConnectionsRepository · RulesRepository · AppsRepository     │
│  SettingsRepository (SharedPreferences: blockQuic)            │
├──────────────┬───────────────────────────────────────────────┤
│ Room DB      │ Rule engine (pure Kotlin)      rules/         │
│ db/ (7d TTL) │  RuleEngineImpl: compiled snapshot, hot reload│
├──────────────┴───────────────────────────────────────────────┤
│ Packet pipeline (VpnService foreground service)  vpn/        │
│  PacketLoop (TUN reader) → FlowTracker → metadata extractors │
│  (vpn/parse: DnsParser, TlsSniParser, ProtocolClassifier,    │
│   IpHostnameCache) → RuleEngine.evaluate → drop or relay     │
│  (vpn/tun: UdpRelay, TcpRelay, AppAttribution)               │
└──────────────────────────────────────────────────────────────┘
```

## Packet pipeline

`TrafficVpnService` (foreground service, persistent notification with a Stop action,
`specialUse` FGS type) establishes an IPv4-only TUN interface:
`10.0.0.2/32`, route `0.0.0.0/0`, DNS `8.8.8.8`, MTU 1500, the app itself disallowed.

**IPv6 choice (v1):** no IPv6 address/route is configured on the TUN, so IPv6 traffic
bypasses the VPN entirely — it is neither inspected nor logged. Documented limitation.

Threads:

- `tun-loop` (1 thread): blocking reads into one reused 32 KB buffer (no per-packet
  allocations), IPv4 header parse, flow demux by 5-tuple, UDP sends, TCP client-side
  state machine.
- `tun-maintenance` (1 thread, 1 s tick): reaps idle flows (60 s) and flushes the log
  buffer when due.
- `tcp-worker-*` (cached executor): upstream connects + one blocking socket reader per
  TCP flow. `udp-rdr-*`: one reader thread per UDP flow.
- Service coroutine scope: Room writes, retention prune.

Data path per flow:

1. First packet(s) feed metadata extractors: DNS queries/responses (UDP/53) through
   `DnsParser` (answers also populate `IpHostnameCache` for SNI-less flows), TCP/443
   first bytes through a stateful `TlsSniParser` (record framing, fragmentation),
   TCP/80 first payload scanned for a `Host:` header.
2. `ProtocolClassifier` produces the `ProtocolType` (SNI → HTTPS; UDP/443 → QUIC;
   port 53 → DNS; port 80 or Host header → HTTP; else OTHER_TCP/OTHER_UDP).
3. `AppAttribution` resolves the owning UID (`ConnectivityManager.getConnectionOwnerUid`
   on API 29+, `/proc/net/{tcp,udp,tcp6,udp6}` parsing on API 26–28) → package + label.
4. `RuleEngine.evaluate(FlowContext)` returns a verdict. `BLOCK` → RST (TCP) / silent
   drop (UDP) and a blocked `ConnectionLog`; allow → re-originated on real sockets via
   `VpnService.protect(...)` (UDP relay; minimal userspace TCP relay with seq/ack
   translation and correct checksums — no congestion control/window scaling/SACK).
5. On flow close, a `ConnectionLog` row is offered to `ConnectionLogBuffer`, which
   batches Room inserts (50 rows or 5 s) to avoid write amplification.

**QUIC toggle:** `SettingsRepository.blockQuic` blocks UDP/443 at verdict time as a
built-in highest-priority rule (`matchedRuleId = null`), forcing apps back to
inspectable TCP/TLS.

## Rule engine & matching semantics

`rules/RuleEngineImpl` implements `core.RuleEngine`. `updateRules(rules)` compiles an
immutable snapshot — **enabled rules only, sorted by priority ascending** — into a
`@Volatile` field; `evaluate()` is lock-free and safe to call from the packet thread.
`RulesRepository` collects the Room rules `Flow` and hot-reloads the engine on every
change, so rule edits apply without a VPN restart.

Semantics:

- **First match wins** (lowest priority value first). A matching BLOCK yields
  `Verdict.Block(ruleId)`, ALLOW yields `Verdict.Allow(ruleId)`.
- **Explicit ALLOW beats nothing else** — it wins only by priority ordering, like any
  other rule. No match at all → `Verdict.DefaultAllow` (fail-open).
- Selector matching (case-insensitive, trailing-dot tolerant for hosts):
  - `APP` — exact package name.
  - `HOST` — exact match against any hostname candidate (SNI → DNS → IP-cache).
  - `HOST_SUFFIX` — suffix like `.x.com` matches `x.com` and any subdomain
    (`ads.x.com`, `deep.ads.x.com`), never `notx.com` or `x.com.evil.com`.
  - `IP` — exact IPv4/IPv6 string or IPv4 CIDR (`1.2.3.0/24`, 32-bit match).
  - `TYPE` — `ProtocolType` name (`HTTPS`, `QUIC`, …).
- Malformed selector values never match and never throw.
- v1 has one selector per rule; the matcher is an internal `(Rule, FlowContext)`
  function so compound selectors can be added later without breaking the API.
- `RuleEngine.matches(rule, flow)` evaluates a single rule in isolation and powers the
  add-rule dialog's live preview ("would have matched N connections in the last 7 days").

## Data retention

- `db/RetentionPolicy` (`RETENTION_DAYS = 7`) computes `cutoffMillis() = now − 7 days`
  with an injectable `Clock` (unit-tested with a fixed clock).
- Pruning happens two ways: on every VPN service start, and daily via
  `worker/RetentionWorker` (unique periodic WorkManager job `retention_prune`, 24 h,
  KEEP policy). Both call `ConnectionLogDao.deleteOlderThan(cutoff)`.
- UI queries only ever see the retained window.

## Testing

69 JVM unit tests (`./gradlew :app:testDebugUnitTest`), all logic extracted into pure
classes — the raw TUN I/O loop is deliberately not unit-tested:

- `RuleEngineTest` — every selector type, case-insensitivity, suffix/subdomain logic,
  priority ordering, disabled rules, allow-vs-block, empty set → default allow,
  hot reload, `matches()` semantics.
- `DnsParserTest` / `TlsSniParserTest` — wire-format fixtures, fragmentation, malformed
  input (never crashes). `ProtocolClassifierTest` — every branch + precedence.
- `RetentionPolicyTest` — cutoff with fixed `Clock`.
- `ConnectionLogDaoTest` (Robolectric, in-memory Room) — prune boundary, paged-feed
  filters, aggregations, distinct hosts.
- `RulesRepositoryTest` (MockK + Turbine + coroutines-test) — emissions, DAO delegation,
  hot-reload of the engine.
- `ConnectionLogBufferTest` — batching, time-based flush, thread-safety smoke test.
- `androidTest/db/MigrationTest` — `MigrationTestHelper` skeleton (v1 create/open,
  with a template for future 1→2 migration tests).

## Play-policy compliance notes

- Local processing only; no remote servers, no analytics, no ads.
- Explicit user consent via `VpnService.prepare()` before every start; boot restart
  only occurs if consent is still valid.
- Persistent low-importance notification with a Stop action while the VPN is active.
- `FOREGROUND_SERVICE_SPECIAL_USE` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
  declaration in the manifest.

## Build

```
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # JVM unit tests
./gradlew :app:connectedDebugAndroidTest  # instrumented (needs a device/emulator)
```
