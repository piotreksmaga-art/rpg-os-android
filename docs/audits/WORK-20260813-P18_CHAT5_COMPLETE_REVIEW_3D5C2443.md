# WORK-20260813-P18 — CHAT-5 Complete Independent Correctness Review

Role: CHAT-5 — independent adversarial / robustness auditor
Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `3d5c24438d477bb6670efcb31771058332bd451f`
Exact CI: GitHub Actions `#385`, run ID `31720139533`, exact head SHA `3d5c24438d477bb6670efcb31771058332bd451f`, completed `SUCCESS`.

# FINAL CHAT-5 VERDICT: FAIL

The Phase-18 runtime correctly establishes deterministic command canonicalization, exact-kind routing, duplicate resolver registration rejection, command→proposal envelope linkage checks, and revalidation of the returned Phase-17 PlayerChangeSet. Historical Phase-17 project-zero, exact delta, composite target, asset identity, financial/ledger, strict serialization and fingerprint invariants remain intact.

However the public Phase-18 resolver boundary is under-constrained in three release-blocking ways. `PlayerCommandResolver.resolve()` is an unrestricted callback returning the final PlayerChangeSet directly. The registry retains the resolver object itself. The engine invokes that callback before proposal-link and ChangeSet validation. No read-only context capability, side-effect barrier, deterministic entropy contract, or Phase-18 reference/scope validation port is present. This violates the Phase-18 architecture's own purity/determinism/reference-validation requirements and permits concrete correctness failures through the normal public API.

---

## 1. SHA discipline / freshness

The target commit exists and is the reviewed runtime. At audit start, fresh master was `b028ee89ef5c086849a0ce2f8a6b6b0b1b5e8ab3`, exactly one commit ahead of the target. The only changed path was the report-only file `docs/audits/WORK-20260813-P18_CHAT1_IMPLEMENTATION_AND_RECOVERY_3D5C2443.md`.

During the audit, additional CHAT-2/CHAT-3 report-only commits appeared. Final target..master inspection still showed no production or test change after the target. Therefore the reviewed runtime remains exactly `3d5c24438d477bb6670efcb31771058332bd451f`.

---

## 2. Real production path

Actual production `PlayerDomainEngine.resolve()` path:

```text
PlayerCommand
-> PlayerCommandKindRegistry.validate
-> encode + decode into detached canonical command
-> fingerprint canonical command
-> resolverRegistry.resolverFor(commandKindUid)
-> resolveTyped(payloadType check)
-> PlayerCommandResolver.resolve(command)
-> verify command fingerprint unchanged
-> validate command/proposal campaign, sourceCommand, actor, causation, correlation, requested order and source preconditions
-> PlayerChangeSetValidator.validate
-> return PlayerChangeSet
```

Phase-18 production consists of the single `PlayerDomainEngine.kt` orchestration file; no competing Phase-18 production orchestrator was found.

---

## 3. Routing / registry / double dispatch

PASS findings:

- routing is exact by `commandKindUid` through one map lookup;
- duplicate resolver registration for the same kind rejects with `DUPLICATE_COMMAND_RESOLVER`;
- input resolver list is defensively copied;
- public `commandKindUids` is exposed as an unmodifiable set;
- unsupported command with no resolver fails `UNKNOWN_COMMAND_RESOLVER`;
- command kind/payload mismatch fails before resolver execution;
- one engine call invokes only one selected resolver; there is no fallback handler or second dispatch loop;
- Phase-16 canonicalization prevents subtype data loss before routing.

No wrong-handler, multiple-handler, silent fallback or engine-level double-dispatch reproducer was found.

---

# RELEASE BLOCKER 1 — P18-RESOLVER-AUTHORITY-01

Severity: CRITICAL / authority and atomicity boundary

## Invariant

Phase-18 resolution must be observationally pure with respect to campaign-authoritative state. `PlayerDomainEngine`, `PlayerCommandResolver`, rules/mechanics/validation dependencies must not commit or mutate authoritative state before future TurnTransaction.

The Phase-18 prep explicitly defines resolvers/components as pure/read-only and forbids SQLiteDatabase, writable stores/repositories, StatePatch, transaction managers and write/commit hooks.

## Production path

```text
PlayerDomainEngine.resolve(command)
-> resolverRegistry.resolverFor(...)
-> resolveTyped(...)
-> PlayerCommandResolver.resolve(command)   // unrestricted callback
-> later command/proposal linkage checks
-> later PlayerChangeSet validation
```

`PlayerCommandResolver` receives only a command as an argument, but its implementation is an arbitrary public object and can capture a database/store/repository in a field/closure. The interface has no capability restriction, purity marker, detached read context, transaction rollback wrapper or side-effect detection.

## Minimal reproducer

```kotlin
val db = SQLiteDatabase.create(null)
db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
db.execSQL("INSERT INTO authority_fixture VALUES('A', 7)")

val resolver = object : PlayerCommandResolver<TrainCommandPayload> {
    override val commandKindUid = PlayerCommandKinds.TRAIN
    override val payloadType = TrainCommandPayload::class

    override fun resolve(command: PlayerCommand<TrainCommandPayload>): PlayerChangeSet {
        db.execSQL("UPDATE authority_fixture SET value=99 WHERE uid='A'")
        throw PlayerDomainEngineStructuralException("DOMAIN_REJECTED")
    }
}

val engine = PlayerDomainEngine(PlayerCommandResolverRegistry.of(listOf(resolver)))
runCatching { engine.resolve(trainCommand) }
```

Expected:

```text
authority_fixture.value == 7
```

Actual permitted by the production API:

```text
authority_fixture.value == 99
resolution throws DOMAIN_REJECTED
```

The same defect exists if the resolver performs the write and then returns a proposal later rejected for campaign/source/actor/precondition mismatch or by `PlayerChangeSetValidator`.

## Why it matters

A failed Phase-18 resolution can leave authoritative state changed while returning no successful proposal. There is no rollback because TurnTransaction is intentionally not present yet. This directly violates zero-authoritative-mutation and failure-atomicity requirements.

## Existing test gap

`p18Engine11_resolverFailureCausesNoAuthoritativeMutation` and `p18Engine12_successfulProposalGenerationCausesNoAuthoritativeMutation` create an authority DB but never give the resolver access to it. They prove only that the engine body does not independently touch that unrelated DB; they do not test the public resolver capability boundary.

---

# RELEASE BLOCKER 2 — P18-RESOLVER-DETERMINISM-01

Severity: HIGH / deterministic orchestration

## Invariant

Equivalent semantic command + equivalent dependencies must produce equivalent proposal semantics. Hidden `Random`, `UUID.randomUUID`, wall-clock time, mutable counters or mutable resolver state must not influence resolution unless explicit deterministic entropy/evidence is supplied.

## Production path

The Phase-18 engine accepts any `PlayerCommandResolver` and calls `resolve(command)`. There is no `ResolutionEntropy`, immutable resolution context, resolver version evidence contract, deterministic draft type or output determinism check. The only fingerprint check verifies that the command itself was not mutated; it does not constrain the resolver output.

## Minimal reproducer

```kotlin
val resolver = object : PlayerCommandResolver<TrainCommandPayload> {
    override val commandKindUid = PlayerCommandKinds.TRAIN
    override val payloadType = TrainCommandPayload::class

    override fun resolve(command: PlayerCommand<TrainCommandPayload>) =
        PlayerChangeSet.create(
            changeSetUid = UUID.randomUUID().toString(),
            campaignUid = command.campaignUid,
            sourceCommandUid = command.commandUid,
            actor = command.actor,
            changes = listOf(validStatChange()),
            provenance = validProvenance(command),
            causationUid = command.causationUid,
            correlationUid = command.correlationUid,
            requestedEffectiveOrder = command.requestedEffectiveOrder,
            preconditions = mappedPreconditions(command)
        )
}

val engine = PlayerDomainEngine(PlayerCommandResolverRegistry.of(listOf(resolver)))
val a = engine.resolve(command)
val b = engine.resolve(command)
```

Expected: equivalent proposal semantics / reproducible deterministic evidence.

Actual permitted: `a != b` and fingerprints differ solely because hidden resolver entropy chose different `changeSetUid` values. A mutable counter or time source produces the same issue.

## Existing test gap

`p18Engine07` and `p18Engine08` use a deliberately deterministic fake resolver. They test deterministic behavior of that fixture, not that the production resolver contract prevents hidden entropy or mutable state.

---

# RELEASE BLOCKER 3 — P18-RESOLVER-MUTABLE-ALIAS-01

Severity: HIGH / registry dependency immutability

## Invariant

After registry/engine construction, caller-owned mutable dependency state must not silently change orchestration semantics for the same command.

## Minimal reproducer

```kotlin
class MutableResolver : PlayerCommandResolver<TrainCommandPayload> {
    override val commandKindUid = PlayerCommandKinds.TRAIN
    override val payloadType = TrainCommandPayload::class
    var delta = 1L

    override fun resolve(command: PlayerCommand<TrainCommandPayload>) =
        statProposal(command, delta)
}

val r = MutableResolver()
val engine = PlayerDomainEngine(PlayerCommandResolverRegistry.of(listOf(r)))
val first = engine.resolve(command)
r.delta = 2
val second = engine.resolve(command)
```

The registry copies the resolver list/map, but intentionally retains the resolver object reference. Nothing in the resolver contract requires resolver object immutability. Thus external mutation after registry construction can change future proposal semantics/fingerprint.

This is not aliasing of an already-returned `PlayerChangeSet` — Phase 17 protects that correctly — but it is aliasing of a Phase-18 dependency retained by the supposedly immutable registry.

---

# RELEASE BLOCKER 4 — P18-REFERENCE-SCOPE-VALIDATION-01

Severity: HIGH / incomplete Phase-18 orchestration boundary

## Invariant

Phase-18 architecture assigns orchestration reference/scope validation to the PlayerDomainEngine boundary: target refs must resolve in the command campaign, required evidence must exist, and resolution scope must be bound before returning a proposal. Phase-17 validation is structural only and does not prove target existence.

## Minimal reproducer

A TRAIN resolver can return an otherwise valid linked ChangeSet containing:

```kotlin
StatChange(
    subject = DomainRef("PLAYER", "GHOST-NOT-IN-CAMPAIGN"),
    statUid = "STAT:DOES-NOT-EXIST",
    delta = ExactLongDelta.of(1)
)
```

with correct campaign/sourceCommand/actor/causation/correlation/order/preconditions. `PlayerChangeSetValidator` validates the ref shape and nonblank IDs but not existence. `PlayerDomainEngine` has no resolution context reader/reference resolver and therefore returns the proposal.

Expected Phase-18 orchestration: unresolved/wrong-campaign target fails before proposal is returned.

Actual: reference existence/scope is entirely delegated to arbitrary resolver behavior; the engine itself cannot establish the advertised Layer-C reference validation contract.

This also demonstrates that the implementation is thinner than the Phase-18 prep architecture: resolver returns the final PlayerChangeSet directly rather than participating in explicit read-context -> rule/mechanics -> invariant-validation -> ChangeSet assembly stages.

---

## 4. Historical Phase-17 regression surface

PASS:

### Project zero progress

`ProjectProgressDelta` is exact non-negative `Long`, accepts `0` and `Long.MAX_VALUE`, rejects negative values in constructor/init. Phase-18 tests route `FAILURE + 0` and `NO_PROGRESS + 0`, and project proposals survive Phase-17 encode/decode/fingerprint.

### ExactLongDelta

Constructor `init` and factory reject zero; generated `copy(units=0)` re-enters constructor invariant. Positive/negative nonzero values remain exact Long; checked arithmetic uses `Math.addExact/subtractExact`.

### OwnershipShare

Existing fixed-scale constructor invariant remains in force; zero/out-of-range copy attempts reject.

### Composite conflict identity

Phase-17 uses shared `compositeConflictKey`. Historical STAT attack:

```text
DomainRef("PLAYER", "X:Y"), statUid="Z"
vs
DomainRef("PLAYER", "X"), statUid="Y:Z"
```

selects CK1 for the colon-bearing later component and remains distinct. Legacy encoding permits colons only in the first component; later components are colon-free, so delimiter shifting cannot alias another legal tuple. CK1 is length-prefixed and carries discriminator + component count, preventing CK1 internal and legacy↔CK1 aliasing. Different discriminators remain separate.

### Asset identity

`AssetChange` retains `OwnedAssetRef(assetKindUid, assetUid)` through validation, conflict key, codec, roundtrip and fingerprint. PROPERTY/A-1 and BUSINESS/A-1 remain distinct; delimiter-shift cases use the shared injective composite encoder.

### Financial / ledger

Phase-17 validator still enforces:

- exact from account;
- exact to account;
- exact `amountMinor`;
- exact currency;
- exact transaction type;
- dangling causal refs fail;
- non-financial-only causal refs fail;
- a causal FinancialChange can be represented by at most one causal ledger intent;
- independent FinancialChanges can have independent ledgers;
- standalone ledger with empty causal list remains legal by contract.

Phase-18 does not reconstruct or normalize a returned ChangeSet after resolver output, so these Phase-17 guards remain effective on the final proposal.

---

## 5. Routing / single-entry / fallback

PASS:

- only one new Phase-18 production orchestration file was introduced;
- no competing Phase-18 PlayerCommand -> PlayerChangeSet path was found;
- lower-level Phase-16/17 factories/codecs are not competing orchestration entry points;
- command kind selects one exact resolver;
- unknown resolver fails closed;
- duplicate resolver kind is rejected;
- payload type is checked before resolver execution;
- no generic fallback / StatePatch path exists;
- no second handler is invoked after a failure.

---

## 6. Immutability / canonical command isolation

PASS for command/proposal data structures themselves:

- engine validates then encode/decode canonicalizes the input command into a detached copy before resolver execution;
- caller command fingerprint is unaffected by normal resolver work;
- engine re-fingerprints canonical command after resolver execution and rejects `COMMAND_MUTATED_DURING_RESOLUTION` if a resolver mutates the canonical command graph;
- Phase-17 root/nested lists are defensive-copied/unmodifiable;
- project evidence refs survive local/caller list mutation;
- returned PlayerChangeSet does not retain mutable aliases to resolver local lists.

FAIL for retained mutable resolver dependency aliases, separately reported as `P18-RESOLVER-MUTABLE-ALIAS-01`.

---

## 7. Numeric correctness

PASS for engine/Phase-17 numeric representation:

- PlayerDomainEngine itself performs no numeric casts, `toInt`, Float/Double conversions, `abs`, arithmetic or parsing;
- exact amounts stay Long;
- values > 2^53 are represented as Long, not double;
- ProjectProgressDelta supports exact 0..Long.MAX_VALUE;
- ExactLongDelta uses exact nonzero signed Long;
- OwnershipShare remains fixed scale;
- Phase-17 strict JSON numeric readers reject quoted numerics/wrong numeric scalar types.

No new Phase-18 numeric conversion blocker was found.

---

## 8. Serialization closure and fingerprint

PASS for legal proposals returned by resolvers:

- engine performs final Phase-17 validation before return;
- representative engine-produced project zero, stat, finance and asset proposals remain legal Phase-17 objects;
- canonical `PlayerChangeSetCodec.encode -> decode -> encode` remains deterministic;
- fingerprint is deterministic for the same legal proposal;
- project result/progress, asset kind/UID, financial amount/currency/type, composite target components, causal/evidence refs remain fingerprint-significant.

No accepted in-memory proposal whose own canonical encoding fails to decode was found.

The Phase-18 determinism FAIL is upstream: unrestricted resolver behavior can create different legal proposals for the same command; it is not a collision or instability in Phase-17 fingerprint itself.

---

## 9. Architecture / world-agnostic / phase boundary

PASS with respect to direct Phase-19+ leakage:

- no Naruto/Bleach/chakra/reiatsu branching or constants appear in PlayerDomainEngine;
- engine does not implement WorldRuleProvider, ProgressionEngine, TurnTransaction, snapshot builder or StatePatch;
- engine has no direct persistence methods or DB/store fields;
- public action is `resolve`, not `apply/commit/execute/save`.

However authority/reference/determinism responsibilities are under-specified at the resolver boundary as documented in the blockers above.

---

## 10. Test quality

FAIL.

Existing Phase-18 tests are meaningful for:

- correct/one-handler routing;
- duplicate registration;
- unknown resolver;
- payload mismatch;
- envelope linkage;
- deterministic fake resolver;
- project zero;
- financial/asset/composite Phase-17 regressions;
- proposal codec/fingerprint;
- registry list/set defensive copying.

But they do not catch the release blockers:

1. authority DB fixture is never captured by the resolver, so resolver-side writes are not attacked;
2. deterministic tests use a deterministic resolver and do not attempt UUID/Random/time/stateful dependencies;
3. registry immutability test mutates only the source list/set view, not resolver internal state after registration;
4. no unresolved/wrong-campaign domain target is returned by a resolver to test Phase-18 reference/scope validation.

---

## 11. Phase 3-17 regression / JVM / CI

Exact CI #385 (`31720139533`) is pinned to the target SHA, completed SUCCESS, and reports success for:

- Validate project;
- Run JVM unit tests;
- Build signed ALPHA APK;
- Prepare release files;
- Upload Actions artifact;
- Check existing release;
- Update existing GitHub Release assets;
- Show release information;
- overall workflow.

The workflow defines the full JVM step as:

```text
gradle --no-daemon :app:testDebugUnitTest --stacktrace
```

Therefore exact CI demonstrates the full repository JVM suite is green on the target.

Independent local JVM execution was attempted but could not start because the isolated audit environment could not resolve `github.com` during clone (`Could not resolve host: github.com`). Local FULL JVM is therefore NOT-RUN, not claimed as a local PASS.

---

# FINAL MATRIX

```text
PLAYERDOMAINENGINE: FAIL
SINGLE ENTRY-POINT: PASS
ROUTING: PASS
RESOLVER REGISTRATION: PASS
DOUBLE-DISPATCH RESISTANCE: PASS
FAILURE ATOMICITY: FAIL
DETERMINISM: FAIL
IMMUTABILITY/ALIASING: FAIL
AUTHORITY BOUNDARY: FAIL
ZERO AUTHORITATIVE MUTATION: FAIL
PROJECT ZERO-PROGRESS: PASS
EXACTLONGDELTA: PASS
COMPOSITE TARGET IDENTITY: PASS
LEGACY/CK1: PASS
ASSET IDENTITY: PASS
FINANCIAL/LEDGER: PASS
REFERENCE VALIDATION: FAIL
NUMERIC CORRECTNESS: PASS
IN-MEMORY/SERIALIZED CLOSURE: PASS
SERIALIZATION: PASS
FINGERPRINT: PASS
WORLD-AGNOSTIC: PASS
PHASE BOUNDARY: PASS
TEST QUALITY: FAIL
PHASE 3-17 REGRESSION: PASS
FULL JVM: NOT-RUN locally; PASS in exact CI
EXACT CI: PASS
```

# NEW CORRECTNESS PROBLEMS

1. `P18-RESOLVER-AUTHORITY-01` — CRITICAL — unrestricted resolver callback can mutate authoritative state and then fail/return invalid proposal; no rollback.
2. `P18-RESOLVER-DETERMINISM-01` — HIGH — hidden UUID/Random/time/mutable counter can make equivalent commands produce different accepted proposals; no explicit entropy/determinism contract.
3. `P18-RESOLVER-MUTABLE-ALIAS-01` — HIGH — registry retains mutable resolver object references; caller mutation after engine construction can change subsequent resolution semantics.
4. `P18-REFERENCE-SCOPE-VALIDATION-01` — HIGH — engine has no Phase-18 reference/context validation layer; structurally valid ghost/wrong-campaign targets can be returned by a resolver and pass final Phase-17 validation.

No fixes were implemented.

Phase 18 is NOT globally accepted by this report. Phase 19 remains blocked pending independent acceptance gates.
