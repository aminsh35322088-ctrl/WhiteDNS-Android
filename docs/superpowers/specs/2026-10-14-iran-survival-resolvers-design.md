# Iran Survival Resolver Architecture

Date: 2026-10-14
Branch: `iran-survival`
Baseline: WhiteDNS Android 1.6.5 (`559cd0c653ff2a5a4d09b93179e350b203cf0f68`)

## Goal

Make WhiteDNS materially more resilient on Iranian networks where ordinary international connectivity may be restricted and only a subset of recursive DNS resolvers remains usable. The client should prefer resolvers that have actually carried a valid CottenDNS tunnel before, learn from successful sessions, recover quickly after network changes, and avoid re-scanning the full resolver fleet unless necessary.

This work is client-side. The BotKeep CottenDNS server remains protocol-compatible and does not require a matching Android release for basic direct-mode operation.

## Non-goals

This first phase does not:

- claim that any resolver will remain reachable during every national-network event;
- introduce a new DNS tunneling protocol or change CottenDNS wire format;
- change encryption, ARQ, MTU framing, query carriers, domain rotation, or session semantics;
- collect device identifiers or user-identifying network data;
- create an always-on background probe service;
- replace the existing WhiteDNS scanner or resolver-profile model;
- make BotKeep's non-standard public port usable through normal recursive delegation; that still requires a public authoritative DNS frontend on UDP/TCP 53.

## Design principles

1. Reuse the current resolver scanner, profile, and per-engine result storage instead of building a parallel networking stack.
2. Treat a resolver as proven only after CottenDNS-level validation, not merely a generic DNS response.
3. Preserve explicit custom ports for CottenDNS direct mode.
4. Prefer recent, network-specific successes before broad scans.
5. Keep all learned state local to the device and decay stale evidence over time.
6. Fail open to the existing resolver/scanner behavior if survival metadata is missing or corrupt.
7. Keep the subsystem small enough to sync future upstream WhiteDNS changes with limited conflict.

## Existing behavior we build on

WhiteDNS already has:

- `ResolverProfile` and manual resolver text;
- `validateResolverText`, which accepts non-standard `IP:port` entries and preserves non-53 ports;
- `WhiteDnsScannerResultStore`, which persists valid resolvers by engine;
- CottenDNS-specific runtime configuration and scanner flows;
- saved scanner results and startup logic that can reuse previously valid resolvers.

The important current limitation is that `WhiteDnsScannerResultStore.normalizeScanResolverText()` strips explicit ports before scanning. That is suitable for traditional public recursive DNS lists but wrong for direct BotKeep endpoints such as `5.196.80.66:43629`.

## Architecture

Add a small survival layer around the existing resolver flow:

```text
network state
    ↓
NetworkFingerprint
    ↓
ResolverSurvivalPlanner
    ├── network-specific proven resolvers
    ├── global proven resolvers
    ├── bundled survival seeds
    ├── selected/user resolver profile
    └── existing full scan pool
            ↓
existing CottenDNS validation / connection flow
            ↓
ResolverSurvivalStore records outcome
            ↓
next connection is reordered by evidence
```

The planner does not dial DNS itself. It only produces ordered resolver candidates for the existing scanner/client pipeline.

## Components

### 1. `NetworkFingerprint`

Purpose: distinguish resolver history for meaningfully different network contexts without identifying the user.

Proposed inputs, only when available without additional sensitive permissions:

- active transport class: cellular, Wi-Fi, ethernet, VPN/other;
- mobile operator MCC/MNC or equivalent coarse carrier identifier when Android exposes it normally;
- coarse network capabilities relevant to reachability, such as IPv4/IPv6 availability.

The fingerprint must not include phone number, IMEI, Android ID, SSID/BSSID, precise location, SIM serial, or account information.

If carrier metadata is unavailable, the system falls back to a coarser transport-only fingerprint. An unknown fingerprint is valid and uses global history.

### 2. `ResolverScore`

Represents accumulated evidence for one normalized resolver endpoint. The endpoint includes a non-default port when present.

Stored fields:

- resolver endpoint;
- engine (`cottendns` / `stormdns`);
- network fingerprint or global scope;
- successful validations;
- failed validations;
- consecutive failures;
- last success timestamp;
- last failure timestamp;
- most recent measured latency when available;
- most recent loss metric when available;
- whether the resolver was observed during a restrictive/survival attempt;
- computed score version.

Initial scoring model:

```text
+50  successful CottenDNS validation/session evidence
+25  successful on the current network fingerprint
+20  successful during survival/restrictive mode
+10  low observed loss
+10  good latency relative to peers
-20  timeout/failure
-15  injected/invalid/NXDOMAIN-like failed validation
-25  repeated consecutive failures
```

The numeric score is an ordering heuristic, not a security decision. A stale positive score decays with age. Recent successful evidence always outranks old evidence with the same base score.

Exact latency/loss thresholds should be centralized and covered by unit tests, not scattered through UI code.

### 3. `ResolverSurvivalStore`

Purpose: durable local storage for scored resolver evidence.

Storage requirements:

- app-private storage;
- atomic writes;
- versioned schema;
- bounded size;
- tolerant read path: malformed or unknown-version data is ignored without blocking connection;
- per-engine evidence remains separate;
- network-scoped and global aggregates can coexist.

The initial implementation should use a compact JSON file through `AtomicFile`, matching existing project patterns. No Room/SQLite dependency is needed for phase one.

A maximum record count should prevent indefinite growth. Eviction order: lowest score, then oldest last-success/last-observed timestamp.

### 4. `ResolverSurvivalPlanner`

Purpose: combine existing resolver sources into one deterministic ordered candidate list.

Priority order:

1. recent proven-good resolvers for the current network fingerprint;
2. recent global proven-good resolvers for CottenDNS;
3. bundled Iran survival seed resolvers;
4. the user's selected/manual resolver profile;
5. remaining scanner/full-pool resolvers.

Deduplication is by normalized endpoint, including non-default port.

The planner must not silently discard user-provided resolvers. User entries remain in the candidate set even when their score is low; they simply appear after stronger evidence unless the user selects a strict/manual-only mode in an existing flow.

The first connection attempt should use a small hot set (target 10–30 entries, default 20). If it does not produce enough valid resolvers, the existing scan path widens progressively instead of immediately probing thousands of endpoints.

### 5. Custom-port preservation

Change scanner normalization so CottenDNS scan flows preserve explicit non-default ports:

```text
5.196.80.66:43629  →  5.196.80.66:43629
1.1.1.1:53         →  1.1.1.1
[2001:db8::1]:5300 →  [2001:db8::1]:5300
```

StormDNS behavior should remain compatible with its existing assumptions. The port-preservation rule therefore belongs in an engine-aware normalization path rather than changing every resolver parser globally.

### 6. Bundled survival seeds

Ship a small versioned seed file inside the APK. Seeds are bootstrap hints, not trusted permanently and not considered proven merely because they ship with the app.

Requirements:

- endpoint only, with optional label/source metadata that is not used for networking decisions;
- no remote fetch is required to use the seed set;
- successful runtime evidence may promote seeds into the same learned store as other resolvers;
- failed seeds are penalized locally;
- the seed file can be updated in later app releases without migration complexity.

The first implementation should support the mechanism and tests even if the initial seed set is deliberately conservative.

## Validation semantics

A resolver is promoted only from evidence produced by the existing CottenDNS validation/connection path. A generic DNS answer does not count as tunnel success.

Strong success evidence includes:

- resolver accepted by CottenDNS probing/MTU validation; and preferably
- successful session initialization or live connection evidence when exposed by the current service flow.

Strong failure evidence includes:

- timeout;
- rejected resolver after CottenDNS validation;
- injected/invalid reply detected by the existing client;
- repeated connection failure attributable to that resolver.

When only coarse scanner success/failure is available, store that evidence with lower confidence rather than inventing session-level certainty.

## Restrictive / survival mode

Phase one introduces survival behavior as an internal planner mode, not a new always-on daemon.

It activates when one of these occurs:

- normal connection fails and fallback begins;
- the user explicitly chooses the existing CottenDNS `survival` preset;
- the scanner/connection path is invoked as a recovery attempt after loss of connectivity.

Evidence collected while this mode is active sets the `survivalObserved` flag. This gives extra ranking weight on future restrictive-network attempts.

The design intentionally avoids trying to infer nationally restricted internet from one website or one DNS lookup; that signal is too fragile and censorship-dependent.

## Connection flow

Normal startup:

```text
load current network fingerprint
        ↓
load survival evidence
        ↓
planner returns hot candidate set
        ↓
existing CottenDNS validation
        ↓
if enough valid resolvers → connect
        ↓
record successes/failures
```

Fallback:

```text
hot set insufficient
        ↓
append global + seed + user candidates
        ↓
validate next tier
        ↓
if still insufficient → existing broad/full scan
        ↓
record all attributable outcomes
```

A failure to read or write survival metadata must never prevent the existing connection path from running.

## UI scope

Phase one keeps UI changes minimal.

Add only enough visibility to make behavior understandable:

- a short status such as `Survival: 12 proven resolvers for this network`;
- optional indication that the current connection used learned/seed/user/full-scan candidates;
- no large resolver-management dashboard in phase one.

The existing resolver profile editor remains the place for manual resolver management.

## Error handling

- Corrupt survival store: ignore it, log a concise warning, continue with existing resolver sources.
- Unsupported schema version: ignore learned state and continue.
- No network fingerprint: use global evidence.
- Empty survival store: use seeds/user/full scan normally.
- All hot candidates fail: widen to the next tier automatically.
- Store write failure: connection remains usable; learning is skipped for that event.
- Invalid bundled seed entry: reject during load and continue with remaining seeds.
- Resolver port outside 1..65535: existing resolver validation rejects it.

## Upstream compatibility

The implementation should minimize changes to existing shared parsing code. New logic should live primarily under `scan/` or a narrowly-scoped resolver package and expose small pure functions that are easy to test.

The current WhiteDNS/CottenDNS submodule should not be repointed to the user's CottenDNS fork merely to implement survival ranking. The Android app remains compatible with upstream CottenDNS protocol behavior. A fork pin can be considered separately later if Android needs a client-side CottenDNS feature that upstream does not contain.

## Privacy

All learned resolver history remains on-device. No analytics or remote reporting is added.

Network fingerprints must be deliberately coarse and must not contain identifiers capable of tracking a specific device or Wi-Fi access point.

## Testing strategy

### Unit tests

Cover:

- score calculation and decay;
- ranking ties and recency;
- network-specific vs global ordering;
- deduplication with custom ports;
- engine-aware port preservation;
- bounded-store eviction;
- corrupt/unknown schema fallback;
- seed parsing;
- planner tier expansion;
- no-fingerprint fallback.

### Existing regression tests

Keep all current WhiteDNS tests green, especially:

- `WhiteDnsModelsTest`;
- `CottenDnsProfileSettingsTest`;
- `ScanResolverChunkerTest`;
- `StormDnsConfigRendererTest`.

Update scanner tests intentionally where CottenDNS custom-port behavior changes while preserving StormDNS expectations.

### Integration tests

Add tests that simulate:

1. a current-network known-good resolver being selected before an unproven resolver;
2. all current-network candidates failing and fallback widening to global/seeds/user/full pool;
3. a successful CottenDNS scan updating persistent ranking;
4. app restart preserving learned ordering;
5. direct custom-port endpoint surviving normalization unchanged.

### Real qualification

Before merging the implementation:

- build the Android debug APK;
- run the relevant JVM unit suite;
- run the existing CottenDNS client/server tests available in the repository;
- verify a BotKeep direct endpoint with `IP:custom-port` still works;
- verify learned resolver order after at least one successful connection and one simulated/real failure cycle;
- verify no regression in a normal public-resolver profile.

## Delivery phases

### Phase 1 — Core survival ranking

- engine-aware custom-port preservation;
- `NetworkFingerprint`;
- `ResolverScore`;
- `ResolverSurvivalStore`;
- `ResolverSurvivalPlanner`;
- bundled seed loader;
- connection/scanner integration;
- minimal status text;
- unit/integration tests.

### Phase 2 — Iran-specific tuning

After real measurements:

- refine score weights and decay;
- populate/update carrier-specific seed groups if evidence supports them;
- tune hot-set sizes and fallback thresholds;
- improve restrictive-mode heuristics using observed failures rather than assumptions.

### Phase 3 — Optional advanced features

Only if field data justifies them:

- periodic background revalidation;
- richer per-carrier diagnostics;
- signed remote seed updates;
- advanced resolver-health UI.

These are explicitly out of scope for the first implementation.

## Success criteria

The first implementation is successful when:

1. existing WhiteDNS behavior still works with no survival history;
2. CottenDNS custom-port resolver endpoints are preserved and usable through scan/connection paths;
3. a resolver that recently succeeded on the current network is tried before unproven bulk candidates;
4. failures automatically widen the candidate pool without trapping the user;
5. successful/failed validation updates survive app restart;
6. corrupted learning state cannot prevent connection;
7. all targeted and full relevant test suites pass;
8. a BotKeep direct-mode endpoint is qualified end-to-end with the Android client path.
