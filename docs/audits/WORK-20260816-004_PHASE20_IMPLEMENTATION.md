# WORK-20260816-004 — Phase 20 Implementation / Candidate Verification

## Work item

- Work ID: `WORK-20260816-004`
- Phase: `20 — ProgressionEngine + Progression Ledger`
- Role: CHAT-1 — main implementer / candidate takeover reviewer
- Mode: `WRITE, BUT FIX ONLY IF REQUIRED`
- Original baseline: `ccf14eace3d23ba519624ec6fe3156e1436c340a`
- Candidate SHA taken over: `a09e22e6505be7849e34fbd27faf2cc36d5bceef`
- Candidate status on takeover: coordinator-approved existing Phase-20 implementation
- Runtime fixes applied by this takeover review: `NONE`
- Runtime candidate SHA after verification: `a09e22e6505be7849e34fbd27faf2cc36d5bceef`
- Classification before takeover review: `PHASE 20 IMPLEMENTED CANDIDATE — NOT YET INDEPENDENTLY POST-AUDITED`

This report does **not** mark Phase 20 ACCEPTED or globally COMPLETE. Global acceptance remains owned by the coordinator after independent post-implementation audits on one exact candidate.

## Repository freshness / drift

Immediately before this report write, `master` still pointed to:

`a09e22e6505be7849e34fbd27faf2cc36d5bceef`

No new runtime drift was detected after candidate takeover. The seven commits between the original baseline and the candidate are a coherent Phase-20 implementation and were retained without rollback, duplication, or unrelated cleanup.

## Seven inherited Phase-20 commits

1. `5bb52617254748376bde1e9806284078dc9f2e91` — `feat(phase20): add pure deterministic progression engine contract`
2. `64a46a766e38256de545d6e6c17c3817a538d8d0` — `feat(phase20): add typed progression ledger intent payload`
3. `b74ae1e60b78de2a4ec5ef77fe2b345c7c1dc632` — `feat(phase20): expose progression kind on player ledger family`
4. `e3a88e42e5042bf8dbbce683e9f7d0b9a75c` — NOTE: canonical full SHA from repository history is `e3a88e42e5042bf8db28bdbce683e9f7d0b9a75c`; message `feat(phase20): validate and serialize progression ledger intents`
5. `be36dc24a3459869ee3380a9ea52dc44de040cf1` — `feat(phase20): expose progression effects to world rule fingerprint`
6. `dbf5f48235fe29b3369fc6d64d364a0c4b71cf48` — `feat(phase20): integrate progression before final world effect check`
7. `a09e22e6505be7849e34fbd27faf2cc36d5bceef` — `test(phase20): cover progression determinism integrity and world rules`

## Exact baseline -> candidate diff scope

Changed files only:

- `app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`
- `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- `app/src/main/java/com/rpgos/app/ProgressionEngine.kt` (new)
- `app/src/main/java/com/rpgos/app/ProgressionLedgerIntent.kt` (new)
- `app/src/main/java/com/rpgos/app/ProgressionLedgerKindExtension.kt` (new)
- `app/src/main/java/com/rpgos/app/WorldRuleProvider.kt`
- `app/src/test/java/com/rpgos/app/Phase20ProgressionEngineTest.kt` (new)

No database schema file, migration file, frontend surface, release workflow, save/load, event-store, transaction, or application-cleanup surface is part of the baseline-to-candidate runtime diff.

## Comparison with CHAT-2 / CHAT-3 pre-implementation audits

Reviewed against:

- `docs/audits/WORK-20260816-002_PHASE20_CONTRACT_ARCHITECTURE_AUDIT.md`
- `docs/audits/WORK-20260816-003_PHASE20_INTEGRITY_MIGRATION_ADVERSARIAL_AUDIT.md`

Both audits required a minimal proposal-only deterministic Core progression layer using the existing player-domain orchestration and ledger envelope. The candidate matches that direction without introducing a parallel Player Engine, direct writer, or independent persisted progression authority.

### 1. ProgressionEngine contract / purity

Production contract exists as:

`ProgressionEngine.evaluate(ProgressionEvaluationInput) -> ProgressionResult`

The engine surface retains only immutable identity/version fields and receives one `ProgressionEvaluationInput`. It has no `SQLiteDatabase`, DAO, writable store/repository, transaction callback, commit callback, `StatePatch`, or mutable authoritative player-state capability.

Evaluation returns immutable proposal/evidence structures only. No DB/state writer path exists from `ProgressionEngine`.

### 2. Progression evaluation input

The immutable input carries the causal/scoping/replay evidence required by the frozen contract, including:

- campaign UID;
- character UID;
- source type/channel;
- stable stimulus UID;
- source command UID;
- command kind UID;
- command fingerprint;
- target kind/UID;
- optional progression-domain UID;
- current target-value evidence and semantics identity/version;
- progress semantics UID/version;
- source-provided effort/duration/intensity/method data where present;
- calculation factors;
- Talent/Potential evidence where present;
- pinned World Pack UID/version and binding identity;
- progression policy UID/version;
- progression engine UID/version;
- dependency/version evidence;
- deterministic input fingerprint.

Missing source activity is not silently replaced with a random/default gain source. The current minimal engine requires causal `effortUnits`; missing effort fails closed.

### 3. Progression result / grant model

`ProgressionResult` contains:

- stable `progressionUid`;
- immutable grants;
- immutable progression ledger intents;
- computation records;
- input fingerprint;
- deterministic result fingerprint.

Each non-zero `ProgressionGrant` has deterministic `grantUid`, deterministic causal change UID, campaign/character/target identity, exact positive grant units, progress semantics UID/version, optional progression domain, source channel/stimulus identity, source policy UID, and computation fingerprint.

Zero-result behavior is explicit: computation evidence remains available, but no grant, no ledger intent, and therefore no `ExactLongDelta(0)` is created.

### 4. Stable identity model

Stable deterministic identities are derived from canonical SHA-256 fingerprints with length-delimited canonical fields rather than random UUIDs.

Covered identities/evidence include:

- `progressionUid`;
- `grantUid`;
- progression-generated causal `changeUid`;
- `ledgerIntentUid`;
- source command UID;
- stimulus UID;
- campaign/character/target identity;
- progression domain UID where applicable;
- engine UID/version;
- progression policy UID/version;
- World Pack UID/version/binding identity;
- input fingerprint;
- computation fingerprint;
- result fingerprint.

Global commit-level retry/idempotency remains intentionally deferred to later transaction phases.

### 5. Numeric policy

The candidate defines one explicit fixed-point boundary:

- Policy UID: `RPGOS-PROGRESSION-NUMERIC:FIXED_1E6_HALF_UP`
- Policy version: `1`
- Scale: `1_000_000`
- Rounding identity: `RPGOS-ROUNDING:HALF_UP`

`Double` profile/mechanics input is converted with `BigDecimal.valueOf`, scaled once at the boundary, and thereafter calculation uses exact `BigInteger` arithmetic before conversion to exact `Long` grant units.

Fail-closed behavior exists for:

- NaN;
- Infinity;
- negative numeric values;
- conversion underflow of positive floating input to zero fixed-point units;
- conversion overflow;
- grant arithmetic overflow;
- negative base grant.

The conversion policy UID/version and rounding UID are included in fingerprints/computation evidence.

### 6. Talent / Potential

Talent and Potential are modifier evidence only. They do not independently create progression.

The engine requires a real causal stimulus/base effort. A zero activity stimulus remains zero even with high Talent/Potential factors. Modifier evidence is fail-closed for campaign mismatch, character mismatch, and progression-domain mismatch.

No ordinary progression write mutates Talent/Potential profiles.

### 7. Progression ledger intent

Phase 20 extends the existing `PlayerLedgerIntent` family with `PlayerLedgerIntentKinds.PROGRESSION` and typed `ProgressionLedgerIntentPayload`.

The payload carries progression/campaign/character/target/source identities, source command and stimulus identity, optional domain/method, current-value evidence, calculation factors, Talent/Potential evidence, base/final grant, progress semantics, engine identity/version, numeric policy identity/version, progression policy identity/version, World Pack identity/binding, input/computation fingerprints, and matching grant UID.

Each generated progression ledger intent has `causalChangeUids` linked to the exact progression-generated typed change.

This object is a proposal/evidence intent inside `PlayerChangeSet`. It is not committed authoritative history and no persisted `progression_ledger` authority/store was added.

### 8. PlayerDomainEngine ordering / reference closure

Verified resolution ordering in the candidate:

1. command validation/canonicalization;
2. campaign/actor/reference checks;
3. pinned Phase-19 `COMMAND_PRECHECK`;
4. existing domain component resolution to base draft;
5. base-draft reference validation;
6. progression evaluation and grant mapping;
7. progression ledger intent append;
8. construction of final immutable augmented draft;
9. full augmented-draft reference validation;
10. one final Phase-19 `DRAFT_EFFECT_CHECK` using the augmented effect snapshot;
11. `PlayerChangeSet` assembly;
12. Phase-17 `PlayerChangeSetValidator` validation.

Progression-generated Stat/Skill/Technique targets, subject references, progression-domain references, ledger references, stimulus references, and explicit evidence refs participate in draft reference closure.

Unknown/wrong-campaign references reject before a proposal is returned.

### 9. WorldRuleProvider interaction / pinned authority

Progression is inserted **before** the sole final `DRAFT_EFFECT_CHECK`.

`WorldRuleEffectSnapshot` includes augmented changes and ledger intents, and its deterministic fingerprint includes the progression payload. Tests verify that a World Rule provider sees progression-generated effects and can reject the entire resolution.

The same `PlayerResolutionContext.worldRuleMode` binding used for command precheck is reused to build progression World Pack identity and the final effect-check request. Expected World Pack/domain ownership mismatches fail closed.

Accepted Phase-19 invariant `ONE RESOLUTION = ONE PINNED WORLD PACK AUTHORITY` is preserved.

### 10. Existing typed changes / source of truth

Progression grants map through Core to existing typed changes only:

- `STAT` -> `StatChange`
- `SKILL` -> `SkillChange`
- `TECHNIQUE` -> `TechniqueChange`

Each uses existing exact `ExactLongDelta` semantics.

No `ProgressionChangeSet`, second command bus, second Player Engine, second progression-state store, second current-state balance, universe-specific ProgressionEngine, or direct DB writer was introduced.

Current authoritative stat/skill/technique state remains the answer to "what is true now". The progression ledger intent explains causal proposal evidence only and cannot reconstruct or overwrite current state during resolution.

### 11. Legacy / custom World Pack impact

No schema or migration path reinterprets legacy XP/mastery, Talent/Potential-like values, custom keys, or historical progression evidence.

Existing `legacy_progression_evidence`, mappings, legacy skill/technique state, and typed/legacy collision semantics remain untouched by the implementation diff.

Production tests explicitly preserve raw legacy progression evidence and verify a custom World Pack progression-domain UID survives unchanged in the typed progression proposal.

Old campaigns do not require fabricated Phase-20 historical ledger entries.

## Schema / migration delta

- Schema delta: `NONE`
- Migration delta: `NONE`
- Persisted progression-ledger table: `NONE`
- New authoritative progression state store: `NONE`

This matches the recommended safe Phase-20 migration strategy from both pre-implementation audits.

## Phase 21 / 22 / 23+ boundary

No full Phase-21 diminishing-returns, novelty/adaptation, passive progression, time-skip orchestration, or scheduler was implemented.

No Phase-22 global Player Invariant Validator / no-retrogression engine was implemented.

No Phase-23+ unified committed player-ledger framework, `TurnTransaction`, global commit/rollback/retry/idempotency, Event Store redesign, Snapshot System, Save/Load, crash recovery, or LAST VALID COMMIT was implemented.

The Phase-20 contracts leave versioned evidence/extension seams for later phases without executing their algorithms.

## Candidate test coverage

`Phase20ProgressionEngineTest` covers at least:

- identical semantic input -> identical result;
- stable progression/grant/ledger identities and fingerprints;
- fixed-point policy/version and deterministic HALF_UP rounding;
- NaN / Infinity / negative / underflow / overflow rejection;
- zero-result evidence without grant/ledger/zero delta;
- no Talent/Potential-only gain;
- modifier campaign/character scope rejection;
- no writer/database/transaction/mutable-state capability on engine surface;
- existing typed Stat/Skill/Technique change mapping;
- typed progression ledger causal linkage;
- codec round-trip and canonical fingerprint stability;
- unknown and wrong-campaign progression-reference rejection;
- unknown progression-domain rejection;
- custom World Pack progression-domain preservation;
- World Pack mismatch rejection;
- progression visibility to exactly one final `DRAFT_EFFECT_CHECK`;
- WorldRule rejection of progression effect -> rejected player resolution;
- augmented reference closure;
- duplicate stimulus UID fail-closed;
- legacy evidence non-reinterpretation.

## Build / CI / Phase 17–19 regression status

Exact candidate SHA checked:

`a09e22e6505be7849e34fbd27faf2cc36d5bceef`

GitHub Actions:

- Workflow: `Validate RPG OS ALPHA`
- Run number: `548`
- Run ID: `31958516535`
- Event: `push`
- Head SHA: `a09e22e6505be7849e34fbd27faf2cc36d5bceef`
- Status: `completed`
- Conclusion: `success`

Successful job steps include:

- Validate release workflow separation;
- Validate project;
- Run JVM unit tests;
- Build signed validation APK;
- immutable validation artifact preparation/upload.

The workflow invokes `gradle --no-daemon :app:testDebugUnitTest --stacktrace`, i.e. the repository JVM unit-test task rather than only the new Phase-20 test class. Therefore the existing Phase-17, Phase-18, and canonical Phase-19 JVM regression suites present in the repository were included in the green unit-test run. No Phase-17/18/19 regression failure is reported by candidate CI.

## Gaps found during takeover review

Concrete frozen-contract gaps requiring a runtime fix: `NONE FOUND`.

No runtime modification was made merely to create a CHAT-1-authored implementation. The inherited candidate is retained as the exact runtime candidate.

## Unresolved risks / deferred work

- Independent CHAT-4 / CHAT-5 post-implementation review is still required before global Phase-20 acceptance.
- Phase-20 proposal identities are designed to support later idempotency, but authoritative retry/commit idempotency remains deferred to the transaction phases by design.
- Future Phase-21 progression algorithms may add additional versioned factors/policies but must not change the Phase-20 source-of-truth boundary.
- Future Phase-22/23/27 validation, committed provenance and transaction behavior remain intentionally absent.

## Final verdict

**IMPLEMENTATION COMPLETE — READY FOR INDEPENDENT POST-AUDIT**

This verdict applies only to CHAT-1 work item `WORK-20260816-004` and the verified Phase-20 implementation candidate. It does **not** mean `PHASE 20 ACCEPTED` or `PHASE 20 COMPLETE` at coordinator level.
