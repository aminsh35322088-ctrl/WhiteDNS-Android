# Iran Survival Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make WhiteDNS prefer locally proven CottenDNS resolvers, preserve direct custom ports, persist resolver evidence across restarts, and widen safely to seeds/user/full-scan candidates when the hot set is insufficient.

**Architecture:** Keep the existing scanner/profile/runtime pipeline and add a small resolver-survival layer under `scan/`. Pure scoring/planning code stays Android-light and testable; persistence uses `AtomicFile`; network fingerprinting is coarse and privacy-preserving; existing CottenDNS validation remains the source of truth for success/failure.

**Tech Stack:** Kotlin, Android SDK, JVM unit tests/JUnit4, `AtomicFile`, existing WhiteDNS scan/runtime services and CottenDNS engine.

**Spec:** `docs/superpowers/specs/2026-10-14-iran-survival-resolvers-design.md`

## Global Constraints

- No CottenDNS wire-format, crypto, ARQ, MTU framing, query-carrier, domain-rotation, or session-protocol changes.
- No device identifiers, SSID/BSSID, precise location, SIM serial, phone number, IMEI, Android ID, or account data in network fingerprints.
- No always-on background probe service in phase one.
- Existing resolver profiles and broad scanner remain available as fallback.
- Learned state is device-local, bounded, versioned, and must fail open when unreadable/corrupt.
- Non-default resolver ports must remain part of endpoint identity for CottenDNS.
- Keep upstream divergence narrow; do not repoint the Android CottenDNS submodule in this work.

## Review Focus

- Mixed resolver lists containing IPv4, IPv6, CIDR, `:53`, and non-default ports must normalize without collisions or silent port loss.
- Corrupt/unknown-version survival JSON must never block connect/scan and must fall back to empty evidence.
- Network-specific history must outrank global history only for the matching coarse fingerprint; unknown network uses global safely.
- Repeated failures must demote a resolver without permanently excluding a user-provided endpoint; later success must recover ranking.
- Seed/user/full-pool deduplication must preserve deterministic order and non-default ports while bounding the hot set.

---

### Task 1: Engine-aware custom-port preservation

**Files:**
- Modify: `app/src/main/java/shop/whitedns/client/scan/WhiteDnsScannerResultStore.kt`
- Modify/Test: `app/src/test/java/shop/whitedns/client/scan/ScanResolverChunkerTest.kt`

**Interfaces:**
- Consumes: existing `DnsClientEngine.normalize(engine)` and `validateResolverText(raw)`.
- Produces: `normalizeScanResolverText(rawText: String, engine: String): ResolverTextValidation` and engine-aware file/line normalization callers.

- [ ] **Step 1: Write failing tests** proving CottenDNS keeps `8.8.8.8:5353` and `[2001:4860:4860::8888]:5353`, normalizes `:53` to the bare address, and StormDNS keeps its existing port-stripping behavior.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'shop.whitedns.client.scan.ScanResolverChunkerTest' --no-daemon` and confirm the new assertions fail.
- [ ] **Step 3: Implement** engine-aware scan normalization in `WhiteDnsScannerResultStore`; thread `engine` through scan file/line normalization and pending-file rewriting without changing the generic profile parser.
- [ ] **Step 4: Re-run** the targeted test and confirm PASS.
- [ ] **Step 5: Commit** `fix(scan): preserve CottenDNS custom resolver ports`.

### Task 2: Pure score model and deterministic planner

**Files:**
- Create: `app/src/main/java/shop/whitedns/client/scan/ResolverSurvivalModels.kt`
- Create: `app/src/main/java/shop/whitedns/client/scan/ResolverSurvivalPlanner.kt`
- Create/Test: `app/src/test/java/shop/whitedns/client/scan/ResolverSurvivalPlannerTest.kt`

**Interfaces:**
- Produces: `ResolverEvidence`, `ResolverOutcome`, `ResolverCandidateSource`, `ResolverSurvivalPlan`, `ResolverSurvivalPlanner.plan(...)`, and pure score/decay helpers.
- `ResolverEvidence` endpoint identity includes non-default port and engine; optional network fingerprint is nullable/global.

- [ ] **Step 1: Write failing tests** for current-network > global > seed > user > full-pool ordering, recency decay, repeated-failure demotion, later-success recovery, port-sensitive dedupe, unknown-network fallback, deterministic ties, and default hot-set size `20`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'shop.whitedns.client.scan.ResolverSurvivalPlannerTest' --no-daemon`; expect compile/test failure.
- [ ] **Step 3: Implement** the pure model and planner with centralized score constants matching the spec (`+50`, `+25`, `+20`, `+10`, `+10`, `-20`, `-15`, `-25`) and age decay applied only as an ordering heuristic.
- [ ] **Step 4: Re-run** the planner test and confirm PASS.
- [ ] **Step 5: Commit** `feat(scan): add resolver survival scoring and planning`.

### Task 3: Durable local evidence store and coarse network fingerprint

**Files:**
- Create: `app/src/main/java/shop/whitedns/client/scan/ResolverSurvivalStore.kt`
- Create: `app/src/main/java/shop/whitedns/client/scan/NetworkFingerprint.kt`
- Create/Test: `app/src/test/java/shop/whitedns/client/scan/ResolverSurvivalStoreTest.kt`
- Create/Test: `app/src/test/java/shop/whitedns/client/scan/NetworkFingerprintTest.kt`

**Interfaces:**
- Produces: `ResolverSurvivalStore.read(): List<ResolverEvidence>`, `record(outcomes: Iterable<ResolverOutcome>)`, `replaceForTest(...)`; `NetworkFingerprintProvider.current(context: Context): NetworkFingerprint?`.
- Store schema version starts at `1`; writes are atomic; records are bounded and evicted lowest-score/oldest-first.

- [ ] **Step 1: Write failing store tests** for JSON round-trip, corrupt JSON, unknown schema, bounded eviction, global/network scoped coexistence, and custom-port identity.
- [ ] **Step 2: Write failing fingerprint tests** for canonicalization and no use of SSID/BSSID/device identifiers; Android-facing provider should degrade to transport-only/unknown when metadata is unavailable.
- [ ] **Step 3: Run** both targeted test classes and confirm RED.
- [ ] **Step 4: Implement** `AtomicFile` JSON persistence in app-private storage and coarse fingerprint construction from transport plus ordinary carrier/capability metadata only.
- [ ] **Step 5: Re-run** both test classes and confirm PASS.
- [ ] **Step 6: Commit** `feat(scan): persist local resolver survival evidence`.

### Task 4: Bundled seeds and candidate widening

**Files:**
- Create: `app/src/main/assets/resolvers/iran_survival_resolvers.txt`
- Create: `app/src/main/java/shop/whitedns/client/scan/ResolverSeedLoader.kt`
- Create/Test: `app/src/test/java/shop/whitedns/client/scan/ResolverSeedLoaderTest.kt`
- Modify/Test: `app/src/test/java/shop/whitedns/client/scan/ResolverSurvivalPlannerTest.kt`

**Interfaces:**
- Produces: `ResolverSeedLoader.load(context: Context): List<String>` returning validated normalized endpoints; invalid/comment/duplicate rows are ignored.
- Planner exposes progressive tiers: hot set first, then remaining global/seeds/user/full candidates.

- [ ] **Step 1: Write failing tests** for comments, duplicates, invalid endpoints, `:53` normalization, non-default port preservation, and tier widening after an insufficient hot set.
- [ ] **Step 2: Run** seed/planner targeted tests and confirm RED.
- [ ] **Step 3: Implement** loader and ship a conservative initial seed mechanism; do not label any seed as guaranteed NIN-whitelisted without field evidence.
- [ ] **Step 4: Re-run** targeted tests and confirm PASS.
- [ ] **Step 5: Commit** `feat(scan): add offline survival seed tier`.

### Task 5: Integrate learning and ordering into existing scan/connect flow

**Files:**
- Modify: `app/src/main/java/shop/whitedns/client/scan/WhiteDnsScanService.kt`
- Modify: `app/src/main/java/shop/whitedns/client/ui/WhiteDnsViewModel.kt`
- Modify: `app/src/main/java/shop/whitedns/client/storm/StormDnsProcessManager.kt` only if needed to accept an ordered resolver override cleanly.
- Test: `app/src/test/java/shop/whitedns/client/scan/ResolverSurvivalIntegrationTest.kt`
- Update targeted existing tests where signatures change.

**Interfaces:**
- Scan completion records `state.validResolverEntries` as successful CottenDNS validation evidence and `state.rejectedResolverEntries` as lower-confidence failures for the active engine/fingerprint.
- `beginConnection()` derives an ordered candidate list through `ResolverSurvivalPlanner` before creating runtime resolver files; failure to read survival metadata falls back to the original settings unchanged.
- Existing user resolver entries are never removed from the eventual candidate set.

- [ ] **Step 1: Write failing integration tests** proving recent current-network success is emitted before unproven entries, failed hot candidates widen to later tiers, evidence survives a store reload, and a corrupt store uses original resolver order instead of failing.
- [ ] **Step 2: Run** the integration test and confirm RED.
- [ ] **Step 3: Implement** scan-result recording in `WhiteDnsScanService` and connection candidate ordering in `WhiteDnsViewModel`, keeping the existing broad scan/runtime launch as the fallback path.
- [ ] **Step 4: Re-run** integration plus `WhiteDnsModelsTest`, `CottenDnsProfileSettingsTest`, `ScanResolverChunkerTest`, and `StormDnsConfigRendererTest`; all must PASS.
- [ ] **Step 5: Commit** `feat: integrate resolver survival learning and fallback`.

### Task 6: Minimal status visibility and release qualification

**Files:**
- Modify: `app/src/main/java/shop/whitedns/client/model/WhiteDnsModels.kt` only for a small UI state field if necessary.
- Modify: `app/src/main/java/shop/whitedns/client/ui/WhiteDnsViewModel.kt`
- Modify: `app/src/main/java/shop/whitedns/client/ui/WhiteDnsScreen.kt`
- Modify localized strings only for the one survival-status message if the project requires resource/string abstraction.
- Tests: relevant ViewModel/UI model unit tests plus full JVM suite.

**Interfaces:**
- UI exposes a concise read-only status such as `Survival: N proven resolvers for this network` and candidate source; no new resolver-management dashboard.

- [ ] **Step 1: Write failing unit assertions** for proven-resolver count/source state and zero-history behavior.
- [ ] **Step 2: Implement** the minimal status surface without changing profile-management UX.
- [ ] **Step 3: Run** `./gradlew testDebugUnitTest --no-daemon`; require PASS.
- [ ] **Step 4: Run** `./gradlew assembleDebug --no-daemon`; require successful APK build.
- [ ] **Step 5: Run** repository formatting/lint/check tasks used by CI and `git diff --check`.
- [ ] **Step 6: Qualify** a BotKeep direct endpoint using `IP:custom-port`; verify the endpoint survives Android normalization, CottenDNS session establishes, application traffic passes, and learned ordering is present after restart/reload. Also verify an ordinary public-resolver profile still follows its normal path.
- [ ] **Step 7: Commit** `feat(ui): surface resolver survival status` and record qualification evidence in the eventual PR body.
