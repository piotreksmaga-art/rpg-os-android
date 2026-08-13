# SYSTEM RPG OS — PHASE 18 — CHAT-5 FINAL COMPLETE CORRECTNESS / ADVERSARIAL REVIEW

Role: `CHAT-5 — FRESH COMPLETE CORRECTNESS / ADVERSARIAL REVIEW AFTER STRUCTURAL REDESIGN`

Repository: `piotreksmaga-art/rpg-os-android`

VALIDATED RUNTIME SHA:

`f9781df9c3828b06562aad86a91dec9682c02530`

Exact CI requested and verified:

- workflow: `Build & Release RPG OS ALPHA`
- run number: `401`
- run ID: `31727239097`
- head SHA: `f9781df9c3828b06562aad86a91dec9682c02530`
- status: `completed`
- conclusion: `success`
- `Run JVM unit tests`: `success`
- signed ALPHA APK build: `success`

This is a report-only audit. No production/test/schema/migration/runtime changes were made. Phase 19 was not started.

# FINAL CHAT-5 VERDICT: FAIL

The structural redesign materially improves Phase 18: `PlayerDomainEngine` now owns canonical `PlayerChangeSet` construction, component/draft types are internal, command/context linkage is explicit, inherited/direct writer *fields* are rejected before component execution, context is detached/immutable, typed rejection exists, deterministic entropy is represented in context/evidence, routing is single-dispatch and Phase-17 serialization/value invariants survive.

However four correctness blockers remain:

1. `P18-COMPONENT-STATE-GLOBAL-CAPABILITY-01` — the component-state validator inspects only fields on the component class hierarchy. A stateless component can call a top-level/global/static writer service and pass validation. This reopens authoritative mutation before a later rejection/exception/invalid draft and breaks failure atomicity.
2. `P18-COMPONENT-STATE-SAFE-FALSE-POSITIVE-01` — the same validator rejects legitimate immutable/read-only component state such as `DomainRef`, immutable typed config holders or read-only capability interfaces because only primitives/wrappers/String/enums are allowlisted. This is over-broad and makes legitimate pure component construction fail.
3. `P18-DETERMINISM-GLOBAL-SOURCE-01` — a stateless component can call hidden global/time/random sources without retaining a forbidden field. The engine has explicit entropy evidence but does not enforce it as the exclusive semantic entropy source, so identical command+context can still yield different accepted drafts.
4. `P18-REFERENCE-COVERAGE-01` — Phase-18 reference/scope validation does not inspect all typed authoritative references. `TransferFundsCommandPayload` and `FinancialChange` account UIDs are not checked against `PlayerResolutionContext`; `AssetChange` and `OwnershipChange` draft references are skipped entirely. A syntactically valid but unresolved financial/account proposal can therefore leave the engine as a valid `PlayerChangeSet`.

---

# 1. Fresh target verification

Fresh `master` was repeatedly checked during the review. At the final pre-report freshness check, later commits above the target were report-only Phase-18 audits. Comparison from target to fresh master contained only `docs/audits/*` additions; no production or test runtime changed after the target.

Result:

`RUNTIME CHANGED AFTER TARGET: NO`

---

# 2. Real production path

The real Phase-18 path at the exact target is:

```text
PlayerCommand
-> PlayerDomainEngine.resolve(command, PlayerResolutionContext)
-> Phase-16 structural validation
-> encode/decode canonical detached command
-> command fingerprint
-> detached PlayerResolutionContext fingerprint
-> campaign/actor checks
-> command reference/scope checks
-> exact commandKindUid lookup in PlayerResolutionComponentRegistry
-> payload KClass check
-> exactly one internal PlayerResolutionComponent.resolve(command, context)
-> typed PlayerResolutionComponentOutcome.Resolved/Rejected
-> draft reference/scope checks
-> engine-owned PlayerChangeSet assembly
-> PlayerChangeSetValidator.validate
-> PlayerResolutionOutcome.Resolved / Rejected
```

`PlayerResolutionDraft`, `PlayerResolutionComponentOutcome`, `PlayerResolutionComponent`, and `PlayerResolutionComponentRegistry` are internal. Components return a draft, not a final ChangeSet. The engine computes the ChangeSet UID from canonical command serialization + context fingerprint + component kind/version and creates the final proposal itself.

This is a real structural improvement over the prior Phase-18 runtime.

---

# 3. Public bypass review

No supported public Phase-18 API was found that accepts `PlayerCommand` and directly returns final `PlayerChangeSet` outside `PlayerDomainEngine`.

Important distinction:

- `PlayerChangeSet.create(...)` remains a legitimate Phase-17 low-level proposal factory;
- it does not interpret a `PlayerCommand` and therefore is not a competing Phase-18 orchestration entrypoint;
- internal components/drafts are not public orchestration surfaces.

Result: `PUBLIC BYPASS RESISTANCE: PASS`.

---

# 4. Component-state validator — exact production behavior

`PlayerResolutionComponentStateValidator.validate(component)` walks concrete component superclasses up to (but excluding) `PlayerResolutionComponent` and inspects each declared field.

For every field it requires:

```text
field is final
AND field type is one of:
primitive
boxed Long/Integer/Boolean/Short/Byte/Character
String
enum
```

This correctly catches direct and inherited writer fields. It also rejects ordinary mutable collections/lambdas/wrappers/lazy fields because their field type is not allowlisted.

But this validator has both an evasion and an overreach defect.

---

# 5. P18-COMPONENT-STATE-GLOBAL-CAPABILITY-01

Severity: `CRITICAL / RELEASE BLOCKER`

## Minimal reproducer

A practical same-module component can have no instance fields and use a top-level/global authority:

```kotlin
internal object GlobalAuthority {
    lateinit var db: SQLiteDatabase
    fun write() {
        db.execSQL("UPDATE authority_fixture SET value=99 WHERE uid='A'")
    }
}

internal class GlobalWriterTrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
    PlayerCommandKinds.TRAIN,
    TrainCommandPayload::class,
    "RPGOS-COMPONENT:GLOBAL-WRITER",
    "1"
) {
    override fun resolve(
        command: PlayerCommand<TrainCommandPayload>,
        context: PlayerResolutionContext
    ): PlayerResolutionComponentOutcome {
        GlobalAuthority.write()
        throw IllegalStateException("later failure")
    }
}
```

## Expected

The component is unsupported/rejected before execution or the architecture otherwise guarantees zero authoritative mutation on all resolution failure paths.

## Actual

`PlayerResolutionComponentStateValidator` sees no unsafe component field. Registration succeeds. The engine invokes `resolve()`. The global write occurs before the thrown error is wrapped as `RESOLUTION_COMPONENT_FAILURE`. There is no Phase-18 transaction/rollback boundary, so the mutation survives.

## Production path

```text
PlayerResolutionComponentRegistry.of
-> field hierarchy validation: no unsafe field found
-> PlayerDomainEngine.resolve
-> resolveTyped
-> component.resolve
-> GlobalAuthority.write
-> throw
-> RESOLUTION_COMPONENT_FAILURE
```

## Why existing tests miss it

`DbCapturingTrainComponent` and inherited writer fixtures store `SQLiteDatabase` as a component/superclass field, which the validator can see. The independent authority DB in success/rejection/failure tests is never reachable by the executing component. No test exercises a stateless component calling a global/static writer service.

Consequences:

- `READ-ONLY CAPABILITY BOUNDARY: FAIL`
- `FAILURE ATOMICITY: FAIL`
- `COMPONENT-STATE VALIDATOR: FAIL`

---

# 6. Inherited and direct writer attacks

## Inherited writer

`PlayerDomainEngineInheritedStateTest` creates a superclass with a final `SQLiteDatabase` field. The concrete child has no writer field itself. Registry construction walks the hierarchy and rejects it with `UNSAFE_RESOLUTION_COMPONENT_STATE` before resolve is possible. The authority fixture remains unchanged.

This is a valid regression: the test is supposed to reject at registration, so not invoking the resolve body is the correct expected behavior.

Result: `INHERITED WRITER RESISTANCE: PASS`.

## Direct writer

The direct `SQLiteDatabase` field fixture is likewise rejected during component registration and cannot execute its write body.

Result: `DIRECT WRITER RESISTANCE: PASS`.

---

# 7. P18-COMPONENT-STATE-SAFE-FALSE-POSITIVE-01

Severity: `HIGH / RELEASE BLOCKER`

The validator is too restrictive in the opposite direction.

## Minimal reproducer

```kotlin
internal class SafeConfiguredComponent(
    private val target: DomainRef
) : PlayerResolutionComponent<TrainCommandPayload>(...) {
    override fun resolve(...) = ... // pure
}
```

`DomainRef` is an immutable value object. The field is final. It carries no write capability and no mutable state.

## Expected

A pure component with immutable typed configuration is legal.

## Actual

`safeFieldType(DomainRef::class.java) == false`, therefore registration throws `UNSAFE_RESOLUTION_COMPONENT_STATE`.

The same false-positive applies to legitimate final immutable/read-only objects such as a typed immutable config holder, stable typed evidence holder, or a read-only capability interface — even where the object has no writer authority.

Current positive inherited-state coverage only proves inherited primitive `Long` is allowed; it does not test immutable domain values or read-only capability objects.

Result: `SAFE STATE FALSE-POSITIVE SAFETY: FAIL` and `COMPONENT-STATE VALIDATOR: FAIL`.

---

# 8. Mutable state leakage

Direct mutable semantic fields (`var`, mutable list/map/buffer/lambda field) are rejected by the hierarchy validator.

However a component with no instance fields can read mutable global state:

```kotlin
object MutableSemanticState { var delta = 1L }

class GlobalStateComponent : PlayerResolutionComponent<TrainCommandPayload>(...) {
    override fun resolve(...) = resolvedDraft(statChange(delta = MutableSemanticState.delta))
}
```

The component passes field validation. Changing the global value after engine construction changes the result for the same explicit command/context.

Result: `MUTABLE STATE LEAKAGE: FAIL`.

---

# 9. P18-DETERMINISM-GLOBAL-SOURCE-01

Severity: `HIGH / RELEASE BLOCKER`

The redesigned context includes explicit `ResolutionEntropyEvidence(evidenceUid, exactValue)` and its deterministic fingerprint includes entropy, sorted references, dependency versions, campaign and actor. The returned `PlayerResolutionEvidence` also preserves context fingerprint, entropy and component identity/version.

That path is correct when the component uses `context.entropy`.

But a stateless component can still call a hidden source directly:

```kotlin
class HiddenRandomComponent : PlayerResolutionComponent<TrainCommandPayload>(...) {
    override fun resolve(...) = resolvedDraft(
        statChange(delta = if (System.nanoTime() and 1L == 0L) 1L else 2L)
    )
}
```

No component field exists, so the state validator accepts it. Equivalent command + equivalent context + same explicit entropy can therefore produce different drafts/proposals.

Equivalent attacks exist with `UUID.randomUUID`, `Random`, `Instant.now`, `System.currentTimeMillis`, mutable global counters or unordered global data.

The tests verify a compliant entropy component and deterministic stat component, but they do not prove hidden-source exclusion.

Result:

- `DETERMINISM: FAIL`
- `ENTROPY/EVIDENCE: FAIL`

The explicit entropy model itself is good; the blocker is that the production contract does not make it the exclusive semantic entropy input.

---

# 10. Reference/scope validation

The context design is strong for refs it actually receives:

- known references are campaign-scoped;
- wrong-campaign and unknown are distinct typed rejection reasons;
- actor and campaign mismatch are checked before component execution;
- command preconditions are included;
- draft references are checked before ChangeSet assembly.

But extraction is incomplete.

`commandReferences()` explicitly skips:

- `TransferFundsCommandPayload` account UIDs;
- LearnSkill/LearnTechnique stable UID strings as input refs;
- SettleObligation UID;
- projectUid/requirementUid/milestoneUid/status project IDs in several project commands unless later reproduced in the draft.

More importantly, `draftReferences()` explicitly does `Unit` for:

- `FinancialChange`;
- `AssetChange`;
- `OwnershipChange`.

## P18-REFERENCE-COVERAGE-01

Severity: `HIGH / RELEASE BLOCKER`

Minimal financial reproducer:

1. Context contains no `ACCOUNT/GHOST-A` or `ACCOUNT/GHOST-B` references.
2. A stateless `TransferFunds` component returns a valid `FinancialChange("GHOST-A", "GHOST-B", 100, "CUR", "TRANSFER")` and matching `FinancialTransferLedgerIntentPayload`.
3. Phase-17 financial/ledger consistency succeeds because both representations agree.
4. `commandReferences()` contributes no account refs for `TransferFunds`.
5. `draftReferences()` contributes no refs for `FinancialChange` and does not inspect ledger account UIDs.
6. Engine assembles and returns a valid proposal.

Expected: unresolved/wrong-campaign financial endpoints are rejected by Phase-18 reference/scope validation.

Actual: they can escape Phase 18 as a structurally valid proposal.

The same coverage omission exists structurally for ownership/asset draft refs; whether a particular AssetChange may legally introduce a new asset depends on command semantics, but existing authoritative ownership/finance endpoints require an explicit policy rather than unconditional omission.

Existing tests cover ghost Train/stat references but not financial account or ownership/asset reference coverage. The happy-path finance test succeeds with account IDs absent from `knownReferences`, confirming the gap is encoded into current test behavior.

Result:

- `REFERENCE/SCOPE VALIDATION: FAIL`
- `REFERENCE VALIDATION: FAIL`

---

# 11. Domain rejection and failure behavior

`PlayerResolutionComponentOutcome.Rejected` is a typed branch and remains distinguishable from a resolved draft and from structural exceptions. A rejected outcome cannot be converted to an empty resolved proposal by the engine.

Result: `DOMAIN REJECTION: PASS`.

Failure atomicity is nevertheless FAIL because supported state-validator evasion allows side effects before typed rejection/throw/invalid draft. The engine has no rollback authority by design.

---

# 12. Routing and registry

Routing is exact by `commandKindUid`:

- duplicate kind registration fails;
- unsupported kind fails closed;
- payload KClass mismatch fails before component execution;
- one lookup -> one component resolve;
- there is no generic fallback;
- no subtype/base-class scanning or multiple-handler fanout exists.

Registry input list and exposed key set are defensively copied.

Result: `ROUTING: PASS`.

---

# 13. Context immutability

`PlayerResolutionContext.create` copies:

- `knownReferences` into a new LinkedHashSet and exposes an unmodifiable set;
- `dependencyVersions` into a TreeMap and exposes an unmodifiable map;
- entropy is an immutable data value.

The deterministic fingerprint sorts reference tuples and uses deterministic dependency-map order.

Caller-owned set/map mutation after construction cannot alter context state.

Result: `CONTEXT IMMUTABILITY: PASS`.

---

# 14. Draft/outcome immutability

`PlayerResolutionDraft.create` defensively copies changes/events/ledgers/warnings into unmodifiable lists. Phase-17 nested list-bearing payloads retain their defensive-copy guarantees, including project evidence refs and event/ledger causal/target lists.

Engine assembles a new canonical ChangeSet from the detached draft. Existing project-evidence aliasing test mutates the original caller list after resolution and proposal semantics remain unchanged.

Result: `OUTCOME/DRAFT IMMUTABILITY: PASS`.

---

# 15. Canonical PlayerChangeSet construction

Components cannot return final `PlayerChangeSet`; they return internal draft/rejection outcome. Engine owns:

- changeSetUid calculation;
- source command UID;
- campaign UID;
- actor;
- precondition mapping;
- provenance resolver kind/version;
- causation/correlation;
- requested effective order;
- final Phase-17 validation.

An already-built wrong-linkage PlayerChangeSet cannot be smuggled through the typed component outcome surface.

Result: `PLAYERCHANGESET CANONICAL CONSTRUCTION: PASS`.

---

# 16. Phase-17 historic regression attacks

## ExactLongDelta

The exact target adds constructor `init` validation as well as factory validation. Therefore:

```text
ExactLongDelta.of(1).copy(units = 0)
```

re-enters the private constructor and fails with `ZERO_DELTA`.

`Long.MIN_VALUE` and `Long.MAX_VALUE` nonzero exact deltas remain legal; checked arithmetic still detects overflow.

Result: `EXACTLONGDELTA: PASS`.

## ProjectProgressDelta

`ProjectProgressDelta.of(0)` is legal.

Private-constructor init rejects negative values, including `copy(units=-1)`.

Result: PASS.

## Project FAILURE / NO_PROGRESS + zero

Engine tests route both zero-progress semantics through component -> draft -> canonical proposal. `ProjectProgressDelta(0)` and `workResultKindUid` are retained. The proposal passes Phase-17 encode/decode/fingerprint closure.

Result: `PROJECT ZERO-PROGRESS: PASS`.

## Composite conflict identities

Phase-17 `compositeConflictKey` uses legacy unambiguous keys only when safe and CK1 length-prefixed encoding when opaque later components contain delimiters. The engine does not normalize these identities.

Delimiter-shift STAT and Asset cases survive.

Result: `COMPOSITE TARGET IDENTITY: PASS`.

## Asset identity

Full `(assetKindUid, assetUid)` survives component draft, engine assembly and canonical proposal. PROPERTY/A-1 and BUSINESS/A-1 remain distinct; delimiter-shift inputs retain both dimensions.

Result: `ASSET IDENTITY: PASS`.

## Financial/ledger

Phase-17 validates matching financial terms, dangling/non-financial causal refs and duplicate FinancialChange causal representation. Engine does not rewrite those structures. Matching terms survive orchestration.

Result: `FINANCIAL/LEDGER: PASS` for internal ChangeSet consistency. This does not cure the separate Phase-18 account-reference coverage blocker.

---

# 17. Numeric correctness

No Phase-18 Float/Double conversion, `toInt` narrowing, lenient string parsing, `abs(Long.MIN_VALUE)`, or unchecked arithmetic was found on the production orchestration path.

Context entropy is exact Long. Context/changeSet hashes operate on strings. Phase-17 exact numeric validators remain responsible for deltas, money and ownership share.

Values > 2^53 remain exact Long values. MIN/MAX historic Phase-17 tests survive.

Result: `NUMERIC CORRECTNESS: PASS`.

---

# 18. Serialization closure

Representative engine-produced proposals (stat, project-zero, finance, asset) are ordinary canonical Phase-17 ChangeSets. Existing closure test verifies:

```text
accepted in memory
-> encode
-> decode
-> equality
-> encode identical
-> fingerprint identical
```

No engine-private/draft-only field is required to reconstitute the ChangeSet.

Result:

- `IN-MEMORY/SERIALIZED CLOSURE: PASS`
- `SERIALIZATION: PASS`

---

# 19. Fingerprint

ChangeSet fingerprint remains Phase-17 SHA-256 over canonical validated serialization. Engine-owned changeSetUid also includes canonical command serialization, deterministic context fingerprint and component kind/version.

No deterministic collision between semantically different legal proposals was found. Legal roundtrip preserves fingerprint.

Hidden global nondeterminism can generate different proposal semantics for the same explicit inputs, but that is the determinism/capability blocker rather than a fingerprint collision defect.

Result: `FINGERPRINT: PASS`.

---

# 20. World-agnostic / phase boundary

No Naruto/Bleach/chakra/reiatsu-specific branching was found in the Phase-18 production files.

The engine does not persist, commit, run TurnTransaction, StatePatch or authoritative stores. It does not implement Phase-19 WorldRuleProvider or Phase-20 ProgressionEngine contracts. It performs orchestration/reference/structural validation and canonical assembly, so it is not an empty facade.

Result:

- `WORLD-AGNOSTIC: PASS`
- `PHASE BOUNDARY: PASS`

---

# 21. Test quality

Strengths:

- production path is genuinely executed;
- exact-kind routing/double-dispatch checks are behavioral;
- inherited and direct field writer rejection tests include real writer-capable fixtures;
- mutable direct component state is rejected;
- context copying, typed rejections, draft invalidity, project zero, asset identity, finance/ledger equality, serialization closure and fingerprint are tested;
- exact CI runs the full JVM suite.

Critical omissions:

1. no stateless component that calls a global/static writer service;
2. no stateless component that reads `System.nanoTime`/Random/global mutable state while passing field validation;
3. no safe immutable `DomainRef`/typed config/read-only capability field acceptance test;
4. no finance account/ownership/asset reference-coverage adversarial test proving every Phase-18 authoritative ref family is actually checked.

These omissions are exactly where the release blockers live.

Result: `TEST QUALITY: FAIL`.

---

# 22. Full JVM / exact CI

Local execution attempt:

```text
git clone https://github.com/piotreksmaga-art/rpg-os-android.git
```

failed in the isolated audit environment with:

```text
Could not resolve host: github.com
```

Therefore local `:app:testDebugUnitTest` is `NOT-RUN`.

Exact GitHub CI is fully verified:

```text
run #401
ID 31727239097
head f9781df9c3828b06562aad86a91dec9682c02530
status completed
conclusion success
```

Configured successful gates include project validation, full `:app:testDebugUnitTest`, signed ALPHA APK, release preparation, artifact upload, release existence check/update and release information.

Result: `EXACT CI: PASS`.

---

# 23. Final gate matrix

```text
PLAYERDOMAINENGINE: FAIL
CANONICAL ENTRY: PASS
PUBLIC BYPASS RESISTANCE: PASS
READ-ONLY CAPABILITY BOUNDARY: FAIL
INHERITED WRITER RESISTANCE: PASS
DIRECT WRITER RESISTANCE: PASS
SAFE STATE FALSE-POSITIVE SAFETY: FAIL
MUTABLE STATE LEAKAGE: FAIL
DETERMINISM: FAIL
ENTROPY/EVIDENCE: FAIL
REFERENCE/SCOPE VALIDATION: FAIL
DOMAIN REJECTION: PASS
FAILURE ATOMICITY: FAIL
ROUTING: PASS
CONTEXT IMMUTABILITY: PASS
OUTCOME/DRAFT IMMUTABILITY: PASS
PLAYERCHANGESET CANONICAL CONSTRUCTION: PASS
PROJECT ZERO-PROGRESS: PASS
EXACTLONGDELTA: PASS
COMPOSITE TARGET IDENTITY: PASS
ASSET IDENTITY: PASS
FINANCIAL/LEDGER: PASS
REFERENCE VALIDATION: FAIL
NUMERIC CORRECTNESS: PASS
IN-MEMORY/SERIALIZED CLOSURE: PASS
SERIALIZATION: PASS
FINGERPRINT: PASS
WORLD-AGNOSTIC: PASS
PHASE BOUNDARY: PASS
COMPONENT-STATE VALIDATOR: FAIL
TEST QUALITY: FAIL
PHASE 3–17 REGRESSION: PASS
FULL JVM: NOT-RUN locally
EXACT CI: PASS
```

# NEW CORRECTNESS PROBLEMS

1. `P18-COMPONENT-STATE-GLOBAL-CAPABILITY-01` — CRITICAL — stateless component can access a global/static writer and mutate authoritative state before later failure; shallow-to-hierarchy field validation cannot see external capability access.
2. `P18-COMPONENT-STATE-SAFE-FALSE-POSITIVE-01` — HIGH — legal immutable/read-only component state such as DomainRef/read-only capability/typed immutable holder is rejected by primitive/String/enum-only allowlist.
3. `P18-DETERMINISM-GLOBAL-SOURCE-01` — HIGH — stateless component can use hidden Random/time/global mutable state despite explicit context entropy, producing nondeterministic accepted proposals for identical explicit inputs.
4. `P18-REFERENCE-COVERAGE-01` — HIGH — reference/scope extraction omits financial account endpoints and skips FinancialChange/AssetChange/OwnershipChange draft refs, so syntactically valid unresolved authoritative refs can leave Phase 18.

# FINAL CHAT-5 VERDICT

`FAIL`

Phase 18 is **not** globally accepted by this report.

Phase 19 remains **BLOCKED** pending independent CHAT-2 + CHAT-3 + CHAT-4 + CHAT-5 PASS on exactly `f9781df9c3828b06562aad86a91dec9682c02530` or a later explicitly assigned corrected runtime.