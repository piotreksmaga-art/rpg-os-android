# WORK-20260813-P18 — CHAT-3 INTEGRITY REVALIDATION

**ROLE:** CHAT-3 — Independent Integrity Auditor  
**Phase:** 18 — PlayerDomainEngine orchestration  
**Validated runtime SHA:** `3d5c24438d477bb6670efcb31771058332bd451f`  
**Result:** **FAIL**

## 1. Repository pin

Fresh master before this report was `b028ee89ef5c086849a0ce2f8a6b6b0b1b5e8ab3`.

`b028ee89...` is a single report-only commit after the audited target and adds only `docs/audits/WORK-20260813-P18_CHAT1_IMPLEMENTATION_AND_RECOVERY_3D5C2443.md`. No newer Phase-18 production/test runtime exists after `3d5c2443...`.

The Phase-17 accepted runtime `583cadda7aca20e3d4c243a3007e8f8a19e1bbae` is the merge base of the Phase-18 runtime. The Phase-18 runtime diff adds only:

- `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- `app/src/test/java/com/rpgos/app/PlayerDomainEngineTest.kt`

plus preserved Phase-17 audit reports.

## 2. Production inspection

`PlayerDomainEngine.resolve()` performs:

1. canonical command registry validation;
2. canonical encode/decode detachment;
3. command fingerprint capture;
4. exact resolver lookup by `commandKindUid`;
5. runtime payload-type guard;
6. resolver invocation;
7. post-resolver command fingerprint check;
8. command/proposal linkage validation;
9. canonical `PlayerChangeSetValidator.validate()`;
10. return of the proposal.

`PlayerCommandResolverRegistry` defensively copies registrations into an unmodifiable map/set and rejects duplicate command kind registrations.

These mechanisms successfully close ordinary routing ambiguity and caller-owned registry aliasing.

## 3. Release blocker P18-INT-AUTH-01 — resolver contract permits authoritative side effects

### Invariant

Phase 18 is required to be proposal-only. Resolution must not expose a normal API path capable of directly mutating authoritative state.

### Production path

`app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`

```kotlin
interface PlayerCommandResolver<P : PlayerCommandPayload> {
    val commandKindUid: String
    val payloadType: KClass<P>
    fun resolve(command: PlayerCommand<P>): PlayerChangeSet
}
```

`PlayerDomainEngine.resolve()` obtains the resolver and directly invokes it via `resolveTyped(...)`.

The resolver interface accepts no capability-restricted/pure context and has no structural restriction on captured dependencies or side effects. Any caller can register a resolver object that closes over `SQLiteDatabase`, a DAO, repository/store, mutable authoritative service, or another writer.

### Minimal reproducer

Conceptually, using only the public Phase-18 API:

```kotlin
val db = SQLiteDatabase.create(null)
db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
db.execSQL("INSERT INTO authority_fixture VALUES('A', 7)")

val resolver = object : PlayerCommandResolver<TrainCommandPayload> {
    override val commandKindUid = PlayerCommandKinds.TRAIN
    override val payloadType = TrainCommandPayload::class

    override fun resolve(command: PlayerCommand<TrainCommandPayload>): PlayerChangeSet {
        db.execSQL("UPDATE authority_fixture SET value=99 WHERE uid='A'")
        return validProposalFor(command)
    }
}

PlayerDomainEngine(PlayerCommandResolverRegistry.of(listOf(resolver))).resolve(trainCommand)
```

### Expected

The Phase-18 contract must make proposal-only resolution enforceable: no normal resolver implementation accepted by the engine should be able to perform authoritative writes as part of resolution.

### Actual

The resolver write executes before the proposal is returned and before post-resolution proposal validation. The engine has no mechanism to prevent, detect, or rollback it. A valid proposal can be returned while authoritative state has already changed.

### Architectural impact

This creates a side channel around the intended pipeline:

`PlayerCommand -> PlayerDomainEngine -> PlayerChangeSet -> later TurnTransaction -> COMMIT`

because authoritative mutation can occur inside resolver execution before `PlayerChangeSet` reaches the transaction boundary.

### Why existing tests miss it

`p18Engine11_resolverFailureCausesNoAuthoritativeMutation` uses a resolver that only throws and performs no write. `p18Engine12_successfulProposalGenerationCausesNoAuthoritativeMutation` uses a benign resolver that performs no write. `p18Engine13_noDirectTurnTransactionOrCommitExecutionSurface` reflects over field/method type names on the engine/registry/interface, but cannot inspect or constrain behavior captured by resolver implementations.

Therefore all three tests remain green while the public resolver side-effect path remains available.

## 4. Release blocker P18-INT-ATOMIC-01 — failure after resolver side effect is not atomic

The same public resolver path can mutate authority and then throw:

```kotlin
override fun resolve(command: PlayerCommand<TrainCommandPayload>): PlayerChangeSet {
    db.execSQL("UPDATE authority_fixture SET value=99 WHERE uid='A'")
    throw PlayerDomainEngineStructuralException("DOMAIN_REJECTED")
}
```

Expected:

`failure -> no authoritative mutation`

Actual:

The engine propagates the exception, but the write has already occurred. No rollback boundary exists in Phase 18.

Thus failure atomicity is not structurally guaranteed.

## 5. Release blocker P18-INT-STATE-01 — resolver reuse permits cross-invocation state leakage

`PlayerCommandResolverRegistry` stores resolver instances, and `PlayerDomainEngine` reuses them across calls. The resolver interface does not require statelessness or isolate per-resolution state.

Minimal example:

```kotlin
var counter = 0L
val resolver = object : PlayerCommandResolver<TrainCommandPayload> {
    override val commandKindUid = PlayerCommandKinds.TRAIN
    override val payloadType = TrainCommandPayload::class
    override fun resolve(command: PlayerCommand<TrainCommandPayload>): PlayerChangeSet {
        counter++
        return proposalWithDelta(command, counter)
    }
}
```

Then command B depends on whether command A was previously resolved. The engine itself does not clear or isolate resolver state.

This does not necessarily imply a DB mutation, but it violates the requested reuse/state-leakage integrity gate and undermines deterministic resolution unless resolver purity/statelessness is structurally enforced elsewhere.

## 6. Gates that pass

### Public construction paths — PASS except resolver side-effect capability

The registry constructor is private and `of()` copies input. `PlayerDomainEngine` accepts an immutable registry. No direct alternate engine proposal path bypassing final `PlayerChangeSetValidator` was found.

### Routing integrity — PASS

Exact lookup by `commandKindUid`; unsupported kind -> `UNKNOWN_COMMAND_RESOLVER`; duplicate registration -> `DUPLICATE_COMMAND_RESOLVER`; payload mismatch -> `COMMAND_RESOLVER_PAYLOAD_TYPE_MISMATCH` before resolver execution. No fallback/second-resolver path exists in engine code.

### Single-handler guarantee — PASS

A single map entry is selected. Duplicate registrations for the same kind are rejected during registry creation.

### Registry integrity — PASS

Caller-owned resolver list is copied; internal map and exposed kind set are unmodifiable. External mutation of the original list does not alter registry membership.

### Immutability / aliasing — PASS for canonical command/proposal values

The command passed to the resolver is detached by canonical encode/decode. Phase-17 `PlayerChangeSet` and nested project evidence use defensive immutable copies. No returned collection alias introduced by the engine itself was found.

### Reference integrity — PASS

After resolver return, command/proposal linkage is checked for campaign, source command, actor, causation, correlation, requested order, and required preconditions. Then canonical Phase-17 `PlayerChangeSetValidator` validates internal causal/reference invariants.

### Numeric integrity — PASS

No Phase-18 numeric coercion/conversion was introduced. Existing Phase-17 constrained values remain authoritative: `ExactLongDelta`, `ProjectProgressDelta`, exact financial minor-unit Long values, and fixed-scale `OwnershipShare`.

### Serialization / fingerprint — PASS

Engine-generated legal proposals continue through the Phase-17 canonical codec/fingerprint path. Tests cover encode -> decode -> encode and stable fingerprint.

### Phase-17 regression — PASS in inspected/CI-covered scope

The suite preserves project zero-progress, ExactLongDelta non-zero, ownership range, historical STAT delimiter separation, full asset tuple identity, and finance/ledger term equality.

## 7. Test quality — FAIL

Routing and serialization tests are meaningful, but the suite does not test the strongest authority-boundary property. A production behavior change allowing resolver-side SQLite/DAO/repository writes would not make the present Phase-18 tests fail, because the resolver is itself already allowed to perform arbitrary code.

A correct test for the current API would actually demonstrate the blocker rather than prove safety.

## 8. Full JVM and exact CI

Independent exact run verification:

- workflow: `Build & Release RPG OS ALPHA`
- run number: `385`
- run ID: `31720139533`
- head SHA: `3d5c24438d477bb6670efcb31771058332bd451f`
- status: completed
- conclusion: success

Job steps show SUCCESS for:

- Validate project
- Run JVM unit tests
- Build signed ALPHA APK
- Prepare release files
- Upload Actions artifact
- Check existing release
- Update existing GitHub Release assets
- Show release information

`Create GitHub Release` was skipped because an existing release was updated.

A second local `:app:testDebugUnitTest` was **not run** because the audit container has no local checkout of this repository. I do not claim a local JVM execution.

## 9. Final gate matrix

- PUBLIC CONSTRUCTION PATHS: **FAIL** (normal public resolver construction admits arbitrary side-effectful implementations)
- ROUTING INTEGRITY: **PASS**
- SINGLE-HANDLER GUARANTEE: **PASS**
- REGISTRY INTEGRITY: **PASS**
- IMMUTABILITY: **PASS**
- ALIASING: **PASS** for canonical values/collections
- FAILURE ATOMICITY: **FAIL**
- AUTHORITY BOUNDARY: **FAIL**
- ZERO AUTHORITATIVE MUTATION: **FAIL** as an enforceable engine contract
- NUMERIC INTEGRITY: **PASS**
- REFERENCE INTEGRITY: **PASS**
- SERIALIZATION: **PASS**
- FINGERPRINT: **PASS**
- REUSE/STATE LEAKAGE: **FAIL**
- PHASE-17 BUG REGRESSION: **PASS**
- TEST QUALITY: **FAIL**
- PHASE 3–17 REGRESSION: **PASS** in inspected/exact-CI scope
- FULL JVM: **NOT-RUN locally; exact CI JVM PASS**
- EXACT CI: **PASS**

## 10. Minimal correction scope

Do not implement here. Required correction is Phase-18-only: make resolver execution structurally proposal-only rather than convention-only. The orchestration contract needs a capability-restricted/pure resolution boundary such that resolvers cannot receive/capture normal authoritative writer capability through the supported engine construction path, and resolution-scoped state cannot persist between calls. Add regressions proving a resolver cannot mutate authoritative state on success or failure and cannot contaminate later invocations.

No Phase-19 implementation is required for this correction.

## 11. Verdict

# PHASE 18 INTEGRITY REVALIDATION: FAIL

Phase 18 is not globally accepted. Phase 19 remains blocked.
