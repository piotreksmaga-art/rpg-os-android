# WORK-20260814-P19 — CHAT-5 FINAL COMPLETE ADVERSARIAL / CORRECTNESS REVIEW

**Role:** CHAT-5 — independent adversarial reviewer  
**Validated runtime:** `48854043bdde9753830ffc20ff6a8e8a4d4299e1`  
**Audit type:** report-only; no production/test modification; no Phase 20 work  
**Final verdict:** **FAIL**

## 1. Target pin / later commits

Fresh master immediately before report creation was `d39f2f94c1fb68bbc59737b8d9f148a03f3962c8`.

`48854043bdde9753830ffc20ff6a8e8a4d4299e1..d39f2f94c1fb68bbc59737b8d9f148a03f3962c8` is forward-only (`ahead_by=4`, merge-base exactly target) and contains only four added files under `docs/audits/`:

- `WORK-20260814-P19_CHAT1_WORLDRULEPROVIDER_IMPLEMENTATION_48854043.md`
- `WORK-20260814-P19_CHAT2_FINAL_SEMANTIC_REVALIDATION_48854043.md`
- `WORK-20260814-P19_CHAT3_FINAL_INTEGRITY_REVALIDATION_48854043.md`
- `WORK-20260814-P19_CHAT4_FINAL_ARCHITECTURE_REVALIDATION_48854043.md`

No production, test, workflow, schema, migration, build or runtime file changed after target.

**RUNTIME CHANGED AFTER TARGET: NO**

A local fresh clone/JVM rerun was attempted, but the sandbox failed at `git clone` with `Could not resolve host: github.com`. Local FULL JVM is therefore **NOT-RUN**. Exact target CI is independently verified in §24.

## 2. Provider selection attack

**PASS.** `WorldRuleProviderRegistry` defensively copies the input list and keys providers by `worldPackUid`. Registration is fail-closed:

- duplicate providers for one world pack -> `DUPLICATE_WORLD_RULE_PROVIDER`;
- same provider object registered twice -> duplicate rejection;
- same world pack with different provider/world-pack versions -> duplicate rejection rather than first/last wins;
- wrong/missing world pack -> no provider and engine raises `WORLD_RULE_PROVIDER_MISSING`;
- exact world pack UID with wrong version -> `WORLD_RULE_PROVIDER_VERSION_MISMATCH`;
- input list mutation after registry construction cannot alter selection;
- registration order cannot silently choose between duplicate candidates because duplicates are rejected.

No silent fallback or first-wins behavior was found.

## 3. Priority null-binding attack — BLOCKER P19-C5-001

**FAIL.** `PlayerResolutionContext.create(..., worldPackBinding: WorldPackRuleBinding? = null)` exposes null as the default through the supported Core API. `PlayerDomainEngine.evaluateWorldRules` begins with:

```kotlin
val binding = context.worldPackBinding ?: return null
```

Therefore omission of a binding skips **both** `COMMAND_PRECHECK` and `DRAFT_EFFECT_CHECK` even if the caller externally knows that an active World Pack exists.

The target deliberately keeps a legacy/no-world-rule mode and P19-28 exercises it. Keeping such a mode is not itself a defect. The correctness defect is that the type/API carries no independent `rulesRequired` / active-world state and there is no distinct engine/context type or factory that lets Core distinguish:

1. intentional legacy/no-world-rule mode, from
2. active World Pack whose binding was accidentally/incorrectly omitted.

Minimal reproducer:

1. register a provider for active WP `W@1` that rejects the command;
2. construct the otherwise valid `PlayerResolutionContext` with the supported default `worldPackBinding=null`;
3. resolve through the only public engine method;
4. provider is never invoked; command can reach component resolution and resolve under Phase-18-only behavior.

Normal app tracing found that `RpgOsViewModel`/`LocalGameStore` persist/expose active World Pack selection but do not yet construct/call `PlayerDomainEngine`, so there is no current UI call site already dropping the binding. That does **not** close the Core API bypass: when an active-WP caller uses this engine, omission is indistinguishable from the intentional legacy mode.

Consequences:

- **NULL BINDING BYPASS: FAIL**
- **COMMAND PRECHECK: FAIL** as a rule-required command can bypass it through supported API omission.
- **DRAFT EFFECT CHECK: FAIL** for the same omission path.

When a non-null binding is supplied, both checks are ordered correctly and fail closed.

## 4. Command precheck ordering when bound

The bound-mode flow is correct:

1. command registry structural validation/canonicalization;
2. context campaign/actor checks;
3. Phase-18 command reference validation;
4. `COMMAND_PRECHECK` provider call;
5. component dispatch.

Unknown/wrong-campaign command references therefore prevent provider invocation. P19 tests use an exploding provider to lock this ordering.

The category is nevertheless overall **FAIL** because P19-C5-001 permits a rule-required invocation to omit binding and reach the component without any command precheck.

## 5. Draft check / effect snapshot completeness

**EFFECT SNAPSHOT COMPLETENESS: PASS.** `PlayerResolutionDraft` consists exactly of `changes`, `eventIntents`, `ledgerIntents`, and `warnings`, and `WorldRuleEffectSnapshot.create` copies all four.

The canonicalizer explicitly handles every current sealed `PlayerDomainChange` family and all meaningful payload fields:

1. `StatChange`
2. `ResourceChange`
3. `SkillChange`
4. `TechniqueChange`
5. `InnateChange`
6. `InventoryChange`
7. `EquipmentChange`
8. `FinancialChange`
9. `AssetChange`
10. `OwnershipChange`
11. `ConditionChange`
12. `RuntimeChange`
13. `DevelopmentProjectChange`

It also includes `PlayerEventIntent` fields/payload, `PlayerLedgerIntent` fields/payload, and warnings. The sealed `when` structure is exhaustive for present payload types.

Bound-mode ordering is also correct: Phase-18 `draftReferences` runs before snapshot/provider evaluation, then `DRAFT_EFFECT_CHECK`, then proposal assembly/final ChangeSet validation.

However, **DRAFT EFFECT CHECK overall is FAIL** because P19-C5-001 can suppress it and P19-C5-003/P19-C5-005 make distinct draft semantics fingerprint-identical.

## 6. Lossy fingerprint/canonicalization attacks

### P19-C5-003 — nullable string sentinel collision

**BLOCKER.** `nullableToken` is:

```kotlin
private fun StringBuilder.nullableToken(value: String?) {
    token(value ?: "RPGOS-NULL")
}
```

There is no presence tag. Consequently any nullable string field has identical canonical encoding for `null` and the legal literal string `"RPGOS-NULL"`.

Confirmed affected effect semantics include at least:

- `PlayerDomainChange.sourceRuleUid`;
- `EquipmentChange.itemInstanceUid` at the canonical helper level;
- `ChangeSetWarning.detail`;
- `ChangeSetWarning.relatedChangeUid`.

Minimal collision requiring no special delimiters:

- snapshot A warning: same warning kind, `detail=null`;
- snapshot B warning: same warning kind, `detail="RPGOS-NULL"`;
- all other fields equal.

Both feed the exact same token sequence and therefore the exact same effect SHA-256 despite semantically different snapshots.

### P19-C5-004 — ALLOW/REJECT decision sentinel collision

**BLOCKER.** `WorldRuleDecisionRecord` fingerprints the reason as:

```kotlin
token(reason ?: "RPGOS-WORLD-RULE:ALLOW")
```

but does not encode the decision variant/allowed bit. `Rejected.create` only requires a nonblank reason and does not reserve that sentinel.

Thus for equal provider/request/rule/evidence:

- `Allowed(ruleUid="R")`
- `Rejected(ruleUid="R", reasonUid="RPGOS-WORLD-RULE:ALLOW")`

produce the same `decisionFingerprint` although the decisions have opposite legality semantics. The materialized records differ (`reasonUid=null` versus literal reason), but the canonical decision identity collides.

### P19-C5-005 — unframed variable-length effect stream collision

**BLOCKER.** Length-prefixed tokens protect token contents against `:`, `|`, `\\`, Unicode and whitespace delimiter injection, but the snapshot does not encode collection counts, per-record boundaries, or section tags.

This becomes lossy because `DevelopmentProjectChange.evidenceRefs` is variable length and each evidence contributes two arbitrary nonblank tokens `(kindUid, uid)`.

Constructive collision:

- Snapshot A: one project change `P` whose evidence list contains four `DomainRef`s chosen so their eight tokens are exactly the eight canonical tokens of a legal `EquipmentChange E`.
- Snapshot B: the same project change `P` with no evidence, followed by actual equipment change `E`.

For an `UNEQUIP` equipment change with `itemInstanceUid=null`, the eight canonical tokens after selection are:

`changeUid, changeKindUid, sourceRule/null-token, subject.kind, subject.uid, slotUid, operation, item/null-token`.

Those eight arbitrary/nonblank strings can be represented exactly by four project evidence refs. Because there is no evidence count/change boundary, snapshots A and B concatenate to the same canonical token stream while representing different effects.

The same general framing weakness exists around variable-length event target/causal lists and top-level snapshot sections.

Therefore **CANONICALIZATION / COLLISION RESISTANCE: FAIL**.

Delimiter-heavy ordinary strings by themselves are safe because each token is length-prefixed; the blockers are structural framing/sentinel ambiguity, not raw delimiter parsing.

## 7. Evidence-order semantics

**PASS.** Two separate semantics are correctly distinguished:

- Provider decision evidence is treated set-like: duplicate evidence is rejected, and `WorldRuleDecisionRecord.create` sorts evidence before decision fingerprinting. Different input orders therefore yield the same canonical record identity.
- `DevelopmentProjectChange.evidenceRefs`, event targets/causal IDs, ledger causal IDs and top-level draft lists are modelled as ordered lists in `PlayerChangeSet`; snapshot canonicalization preserves that order rather than sorting it.

No HashSet/HashMap iteration is used for decision evidence fingerprinting. The effect framing blocker is separate from order semantics.

## 8. Provider decision validation

**PASS for structural/provider-identity validation; canonical identity still fails under P19-C5-004.**

Core validates:

- nonblank rule UID;
- nonblank rejected reason;
- nonblank evidence;
- duplicate evidence rejected;
- caller evidence list detached/unmodifiable;
- provider/world-pack identity is not supplied by the decision at all: the record derives it from the actually selected registered provider;
- registry verifies exact bound world-pack version.

Thus a provider cannot claim another provider/world pack in its return value. Malformed normal Kotlin decisions cannot be constructed through factories. The ALLOW/REJECT fingerprint collision is classified under canonicalization/stale identity, not object-shape acceptance.

## 9. Provider retained-state attack — BLOCKER P19-C5-002

**FAIL.** Registry state validation walks inherited subclass fields and rejects non-final fields plus most object/collection/array types, which correctly blocks ordinary `var`, mutable lists, arrays, DB/DAO/store/repository objects, etc.

But it explicitly whitelists any enum field:

```kotlin
field.type.isEnum
```

JVM/Kotlin enum instances are not guaranteed immutable. An enum can legally contain a mutable instance field.

Minimal reproducer:

```kotlin
enum class MutableMode {
    INSTANCE;
    var counter: Int = 0
}

class StatefulProvider(
    private val mode: MutableMode = MutableMode.INSTANCE
) : WorldRuleProvider(...) {
    override fun evaluate(request: WorldRuleRequest): WorldRuleDecision =
        if (mode.counter++ == 0)
            WorldRuleDecision.Allowed.create("R")
        else
            WorldRuleDecision.Rejected.create("R", "DENY")
}
```

The provider field `mode` is final and its type is enum, so `validateProviderState` accepts it. The provider then mutates retained state and can return different valid decisions for the identical canonical request.

This defeats the intended deterministic retained-state guard and can change the proposal/decision outcome across identical invocations.

Accordingly:

- **PROVIDER FAULT HANDLING: FAIL** for the broader provider-state/fault adversarial category;
- **PROPOSAL UID DETERMINISM: FAIL** because identical semantic input can yield different valid rule decisions and therefore different/no proposal;
- **WORLDRULEPROVIDER: FAIL** overall.

Thrown provider exceptions themselves remain fail-closed: non-Core exceptions become `WORLD_RULE_PROVIDER_FAILURE`, no proposal is returned, and authority fixtures remain unchanged. A provider-thrown `PlayerDomainEngineStructuralException` propagates as structural failure, still fail-closed.

## 10. Request immutability

**PASS under supported API/trust model.**

- provider command is an encode/decode canonical copy, not caller command alias;
- effect snapshot copies/unmodifiably exposes its four lists;
- underlying ChangeSet model objects use val/defensive immutable collections;
- after provider call, command fingerprint and effect fingerprint are recomputed and mutation is rejected as `WORLD_RULE_PROVIDER_INPUT_MUTATED`;
- provider decision evidence is detached.

No request API exposes DB/writer callbacks. Arbitrary reflection/static-global attacks are outside the stated supported internal-extension trust model and no JVM sandbox is required.

## 11. Stale/cross-context resistance — BLOCKER P19-C5-006

**FAIL.** `PlayerResolutionContext.deterministicFingerprint` uses length-prefixed tokens but no section counts/tags between:

- sorted `knownReferences` triples `(campaign, kind, uid)`;
- `dependencyVersions` pairs `(key, value)`;
- entropy/world-pack suffix.

Therefore different semantic contexts can have identical token streams.

Constructive collision before the identical suffix:

Context A:

- known refs `(A,B,C)` and `(D,E,F)`;
- no dependencies.

Token sequence: `A,B,C,D,E,F`.

Context B:

- no known refs;
- dependencies `{A:B, C:D, E:F}` (TreeMap order `A,C,E`).

Token sequence: `A,B,C,D,E,F`.

Use the same campaign, actor, entropy and world-pack binding for both contexts. Both are structurally legal and produce the same context fingerprint despite different reference/dependency snapshots. Since `WorldRuleRequest.requestFingerprint` incorporates the context only through this fingerprint, the rule request identity also collides.

Additionally, P19-C5-003/P19-C5-005 let different draft semantics produce the same effect fingerprint/request identity.

Campaign/actor/worldPack/provider/stage/command changes are otherwise explicitly represented, but these constructive collisions mean stale/cross-context identity is not injective enough for the claimed canonical contract.

## 12. Proposal UID attack

**FAIL.** Proposal UID hashes:

- canonical command encoding;
- context fingerprint;
- component identity/version;
- each rule decision fingerprint.

For well-formed deterministic providers and collision-free inputs, independent allocations reproduce the same UID, and provider/rule decision changes alter it.

However:

1. P19-C5-002 permits identical requests to generate different decisions across invocations;
2. P19-C5-004 gives opposite decisions the same decision fingerprint;
3. P19-C5-006 allows distinct contexts to share context/request identity;
4. P19-C5-003/P19-C5-005 allow distinct effect snapshots to share draft request identity.

Thus the claimed semantic determinism/collision resistance is not valid globally.

## 13. Phase-18 ordering attack

**PASS.** Phase-18 reference enforcement is correctly ordered relative to world rules:

- unknown command ref -> reject before provider;
- wrong-campaign command ref -> reject before provider;
- component resolves draft;
- unknown/wrong-campaign draft refs -> reject before draft provider call.

A provider cannot ALLOW a missing reference because it does not run before the relevant Phase-18 rejection. Null binding skips the provider but does **not** skip reference validation.

## 14. World-rule vs reference bypass

**PASS.** No decision value can override `validateReferences`. Command and draft references are validated independently before their respective provider stage.

## 15. Mechanics separation

**PASS.** `WorldRuleProvider.evaluate` can return only `WorldRuleDecision`. The decision surface contains rule/reason/evidence identity only. It contains no stat/resource delta, ownership share, financial amount, proposal, StatePatch, change list, mutation command, or commit callback.

## 16. Progression separation

**PASS.** No Phase-20 progression calculation API, stat growth calculator, RNG progression result, mastery calculation or numeric mechanics result is hidden in the provider contract. Providers decide legality only.

## 17. Authority boundary / zero authoritative mutation

**PASS under the supported capability model.**

`WorldRuleRequest`, provider base state and decision outputs expose no DB, DAO, repository, store, transaction, StatePatch or writer callback. Provider rejection/fault tests use independent SQLite authority fixtures and observe zero mutation. Engine itself assembles a transient proposal only.

P19-C5-002 permits **provider-local mutable state**, but that is a determinism/state-isolation defect, not an exposed authoritative persistence capability. Arbitrary trusted code opening a global DB by itself would require a JVM sandbox that this phase does not claim.

## 18. Core world-specific leak

**PASS.** Phase-19 modified Core production uses generic `WorldPack`, `WorldRule`, command/change/effect terminology. No Naruto/Bleach-specific rules, tokens, techniques, races, chakra/Reiatsu assumptions, or world-specific mechanics were found in the Phase-19 Core implementation.

## 19. PlayerChangeSet regression

**PASS.** PlayerChangeSet schema remains version 1; Phase 19 does not add rule-decision serialization to authoritative ChangeSet schema. Existing ChangeSet encode/decode/encode and fingerprint tests remain deterministic. Proposal provenance retains resolver and optional world-rule provider UID without changing the canonical ChangeSet contract.

## 20. Phase-18 regression

**PASS.** Reviewed locks remain intact:

- equipment slot remains structural/non-campaign-owned (B);
- ownership record remains local (D), asset/fromOwner/toOwner remain existing typed scoped references (A/A/A);
- finance account/currency refs remain typed and campaign-scoped;
- command refs occur before command provider;
- draft refs occur before draft provider;
- reference rejection produces no authoritative mutation.

World rules cannot bless a missing Phase-18 reference.

## 21. Phase-17 regression

**PASS.** Historical suites and production contracts still retain:

- `ExactLongDelta`: zero rejected, exact Long bounds retained;
- `ProjectProgressDelta`: zero legal, negative rejected;
- `OwnershipShare`: internal fixed scale and copy/bounds invariants;
- composite conflict identities including delimiter-heavy cases;
- `OwnedAssetRef(kind,uid)` exact identity;
- financial/ledger exact-term and causal uniqueness rules;
- canonical serialization/fingerprint regression coverage.

No Phase-19 production change alters those value objects/codecs.

## 22. Test-quality adversarial review

**FAIL.** P19-01..30 contain useful real-engine tests, including reference-before-provider spies, provider reject/fault zero-mutation fixtures, Phase-18/17 regressions, canonical project evidence fingerprint regression and exact round trips. They do not use disabled/@Ignore cases in the inspected Phase-19 suite.

However the suite misses real production bypasses confirmed above:

- P19-28 proves null means legacy/no-rule mode but does not prove that an **active** World Pack cannot be represented accidentally by the same null state; the API has no enforceable distinction (P19-C5-001).
- no mutable-enum retained-state attack; existing state-policy checks would accept it (P19-C5-002).
- no nullable-sentinel adversarial values such as literal `RPGOS-NULL` (P19-C5-003).
- no ALLOW-sentinel rejected reason attack (P19-C5-004).
- no all-family/variable-list canonical framing attack; P19-29/30 only demonstrate one project happy-path/evidence-difference case (P19-C5-005).
- no constructive cross-section context-fingerprint collision attack (P19-C5-006).

Other missing Cartesian tests would be non-blocking where production is provably correct, but these omissions correspond to actual reachable correctness defects, so TEST QUALITY is FAIL.

## 23. Local FULL JVM

**NOT-RUN.** Attempted fresh clone:

```text
fatal: unable to access 'https://github.com/piotreksmaga-art/rpg-os-android.git/':
Could not resolve host: github.com
```

This is a sandbox network/DNS limitation, not a test result.

## 24. Exact CI / artifact

**PASS.** Independently verified GitHub Actions run:

- run number: **#452**
- run ID: **31801538074**
- exact head: `48854043bdde9753830ffc20ff6a8e8a4d4299e1`
- status: `completed`
- conclusion: `success`
- job checkout/logs show exact target commit;
- JVM step executes `gradle --no-daemon :app:testDebugUnitTest --stacktrace`;
- log result: `BUILD SUCCESSFUL`.

Artifact association is exact and structured metadata points to the same workflow run/head:

- artifact ID: **9219582395**
- name: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140`
- digest: `sha256:c6260b782de96335e8d8db24d811b87df25f7b90528460d6e3b6ee90f53934d3`
- workflow run: `31801538074`
- artifact head SHA: `48854043bdde9753830ffc20ff6a8e8a4d4299e1`

Green CI does not negate the six adversarial gaps because the existing tests do not exercise those constructive cases.

## 25. Complete blocker list / minimal reproducers

### P19-C5-001 — ACTIVE_WORLD_NULL_BINDING_BYPASS

Supported context default `worldPackBinding=null` causes engine to return from world-rule evaluation and cannot distinguish intentional legacy mode from omitted binding for an active world.

### P19-C5-002 — MUTABLE_ENUM_PROVIDER_STATE_BYPASS

Provider state validator accepts any enum field; a custom enum with mutable `counter` gives accepted retained mutable state and nondeterministic decisions for identical requests.

### P19-C5-003 — NULLABLE_SENTINEL_CANONICAL_COLLISION

`nullableToken(null)` equals `nullableToken("RPGOS-NULL")`; semantically distinct source-rule/warning values can share effect fingerprint.

### P19-C5-004 — ALLOW_REJECT_DECISION_FINGERPRINT_COLLISION

Allowed reason sentinel equals legal literal rejected reason `RPGOS-WORLD-RULE:ALLOW`; opposite decisions can share `decisionFingerprint`.

### P19-C5-005 — UNFRAMED_EFFECT_SNAPSHOT_COLLISION

Variable-length project evidence has no count/record framing; evidence token pairs can encode an entire following EquipmentChange so two different effect structures yield the same canonical stream.

### P19-C5-006 — UNFRAMED_CONTEXT_FINGERPRINT_COLLISION

Two known-reference triples and three dependency key/value pairs can be chosen to produce identical token streams with different semantic context snapshots.

## 26. Final classification

| Area | Result |
|---|---|
| WORLDRULEPROVIDER | **FAIL** |
| PROVIDER SELECTION | **PASS** |
| NULL BINDING BYPASS | **FAIL** |
| COMMAND PRECHECK | **FAIL** |
| DRAFT EFFECT CHECK | **FAIL** |
| EFFECT SNAPSHOT COMPLETENESS | **PASS** |
| CANONICALIZATION / COLLISION RESISTANCE | **FAIL** |
| EVIDENCE SEMANTICS | **PASS** |
| DECISION VALIDATION | **PASS** |
| PROVIDER FAULT HANDLING | **FAIL** |
| REQUEST IMMUTABILITY | **PASS** |
| STALE DECISION RESISTANCE | **FAIL** |
| PROPOSAL UID DETERMINISM | **FAIL** |
| PHASE-18 ORDERING | **PASS** |
| REFERENCE BYPASS RESISTANCE | **PASS** |
| MECHANICS SEPARATION | **PASS** |
| PROGRESSION SEPARATION | **PASS** |
| AUTHORITY BOUNDARY | **PASS** |
| ZERO AUTHORITATIVE MUTATION | **PASS** |
| CORE WORLD-AGNOSTIC | **PASS** |
| PLAYERCHANGESET REGRESSION | **PASS** |
| PHASE 3–18 REGRESSION | **PASS** |
| TEST QUALITY | **FAIL** |
| FULL JVM | **NOT-RUN** |
| EXACT CI | **PASS** |

## 27. Non-blocking observations

1. Bound provider selection itself is conservative: one provider per world-pack UID means multiple world-pack versions cannot coexist in one registry. That is restrictive but fail-closed, not a correctness bypass.
2. Provider-thrown `PlayerDomainEngineStructuralException` is propagated rather than normalized to `WORLD_RULE_PROVIDER_FAILURE`; still fail-closed and zero-mutation, but diagnostic namespace ownership is less strict than ordinary exception wrapping.
3. Ordinary delimiter-heavy (`:`, `|`, `\\`), Unicode and whitespace content is protected by length-prefixed token encoding. The blockers arise from reserved sentinel ambiguity and missing structural/list framing.
4. Current `LocalGameStore`/`RpgOsViewModel` does not yet route active-world execution through `PlayerDomainEngine`; therefore P19-C5-001 is a Core contract/enforcement bypass rather than an already-observed UI runtime call-site bug.
5. No arbitrary JVM sandbox was required or assumed. Findings are limited to supported API/state-validation semantics.

## 28. Final verdict

**NEW CORRECTNESS PROBLEMS: P19-C5-001, P19-C5-002, P19-C5-003, P19-C5-004, P19-C5-005, P19-C5-006.**

**FINAL CHAT-5 VERDICT: FAIL.**

This report does **not** globally accept Phase 19 and does **not** begin Phase 20.
