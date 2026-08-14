# WORK-20260814-P19 — CHAT-5 FINAL COMPLETE CORRECTNESS REVIEW

**Role:** CHAT-5 — independent adversarial correctness reviewer  
**Validated runtime:** `11e0dcac8e128404524350bc53b9963124e9bbd7`  
**Audit type:** audit only; report-only repository change; no production/test modification; no Phase 20 implementation  
**Final verdict:** **FAIL**

## Executive verdict matrix

- **VALIDATED RUNTIME SHA:** `11e0dcac8e128404524350bc53b9963124e9bbd7`
- **FRESH MASTER:** `c6b05d5e3928496c3fe45967a888c07e7584b101` immediately before this report write
- **RUNTIME CHANGED AFTER TARGET:** **NO**
- **RELEASE INPUT CHANGED AFTER TARGET:** **NO**
- **PROCEDURAL PURITY:** **FAIL**
- **PROCEDURAL IMPACT:** **FAIL / MATERIAL RELEASE-PROVENANCE IMPACT**
- **WORLDRULEPROVIDER:** **FAIL**
- **WORLD RULE MODE:** **FAIL**
- **NULL/DOWNGRADE BYPASS:** **FAIL** — implicit null/unbound downgrade is closed, but wrong-active-binding substitution remains supported
- **PROVIDER SELECTION:** **PASS** for the binding actually supplied to the registry
- **COMMAND PRECHECK:** **PASS** for bound contexts
- **DRAFT EFFECT CHECK:** **PASS** for bound contexts
- **EFFECT SNAPSHOT COMPLETENESS:** **PASS**
- **CANONICAL FORMAT:** **PASS**
- **CANONICAL COLLISION RESISTANCE:** **PASS** for the reviewed production v1 format
- **CONTEXT FINGERPRINT:** **PASS**
- **REQUEST REPLAYABILITY:** **PASS**
- **DECISION REPLAYABILITY:** **FAIL** because accepted constant-specific enum state can make an identical request return a different decision
- **STALE DECISION RESISTANCE:** **PASS** at fingerprint level; active-binding authenticity is a separate FAIL
- **PROPOSAL UID DETERMINISM:** **FAIL** end-to-end because accepted provider state can change rule decisions for identical inputs; UID framing itself is correct
- **PROVIDER STATE SECURITY:** **FAIL**
- **REQUEST IMMUTABILITY:** **PASS**
- **DETERMINISM:** **FAIL**
- **FAILURE ATOMICITY:** **PASS**
- **ZERO AUTHORITATIVE MUTATION:** **PASS**
- **PHASE-18 ORDERING:** **PASS**
- **PHASE-18 REFERENCE SEMANTICS:** **PASS**
- **PLAYERCHANGESET REGRESSION:** **PASS**
- **CORE WORLD-AGNOSTIC:** **PASS**
- **PHASE BOUNDARY:** **PASS**
- **TEST QUALITY:** **FAIL**
- **PHASE 3–18 REGRESSION:** **PASS**
- **FULL JVM:** **PASS** on exact-target CI
- **EXACT CI:** **FAIL overall release-chain verification** — exact run and exact artifact PASS, current mutable release no longer represents exact-target APK

---

## 1. Fresh master and all post-target changes

The target is exactly:

`11e0dcac8e128404524350bc53b9963124e9bbd7`

Fresh `master` immediately before this report was:

`c6b05d5e3928496c3fe45967a888c07e7584b101`

`target..master` is forward-only, merge-base is exactly the target, and it is eight commits ahead. Net changed paths are only:

1. removal of `DRAGON_ANIMATION_NOTES.md`;
2. removal of `DRAGON_ANIMATION_VALIDATION.json`;
3. removal of `DRAGON_GUARDIAN_127.md`;
4. removal of `DRAGON_GUARDIAN_127_VALIDATION.json`;
5. removal of `DRAGON_UI_HOTFIX_126_VALIDATION.json`;
6. addition of `docs/audits/WORK-20260814-P19_CHAT1_CONTRACT_CANONICAL_HARDENING_11E0DCAC.md`;
7. addition of `docs/audits/WORK-20260814-P19_CHAT4_FINAL_ARCHITECTURE_REVALIDATION_11E0DCAC.md`;
8. addition of `docs/audits/WORK-20260814-P19_CHAT2_FINAL_SEMANTIC_REVALIDATION_11E0DCAC.md`.

The five removal commits observed in history are:

- `1f78f535e237b2417e28ad76d319c63da90dad67` — delete `DRAGON_ANIMATION_NOTES.md`;
- `60f2100de65fe506c8d8f419b152cc8699e74b0a` — delete `DRAGON_ANIMATION_VALIDATION.json`;
- `9052f92435c566d83069b282b5f54664880164be` — delete `DRAGON_GUARDIAN_127.md`;
- `70f2e5489bb21ee42a37711cf9cfe449e1f0d28b` — delete `DRAGON_GUARDIAN_127_VALIDATION.json`;
- `419f32561d62e1e19ec31ae2defc9f99be4d2b9c` — delete `DRAGON_UI_HOTFIX_126_VALIDATION.json`.

The audit-report commits include:

- `f7c9d6895c2d360c62199d4a5eb5b0a8f34ee4ad` — CHAT-1 canonical hardening report;
- `035147a450d009aa16994b5c6eab586ca7925633` — CHAT-4 architecture revalidation report;
- `c6b05d5e3928496c3fe45967a888c07e7584b101` — CHAT-2 semantic revalidation report.

### 1.1 Independent DRAGON_* removal analysis

The five removed files were inspected at the exact target rather than classified merely by extension or by “not production Kotlin”. Their contents are descriptive dragon-animation notes or declarative validation snapshots. They do not contain executable code, resource binaries, generated source, signing data, Gradle configuration, release commands, package manifests, update-feed templates, database data, migrations or dependency locks.

The exact target build topology was independently checked:

- root `settings.gradle.kts` includes only `:app`;
- root `build.gradle.kts` only declares Android/Kotlin plugins;
- `app/build.gradle.kts` uses standard Android source sets and does not import top-level `DRAGON_*` files;
- `.github/workflows/build-alpha.yml` reads version from `app/build.gradle.kts`, runs Gradle validation/tests, builds the release APK, then creates the checksum and `update.json` from the APK;
- `tools/static_audit.py` scans `app/src/main/java/**/*.kt` plus backend route text; it does not consume `DRAGON_*`;
- exact-target Android assets are the real files under `app/src/main/assets/` (`Naruto.worldpack.zip`, `Naruto_Default.campaign.zip`, `api_contract.json`, `rpg_core.db`, plus dependency-generated APK assets); no top-level DRAGON file is an Android asset;
- exact target APK was unpacked and contains no `DRAGON_*`, `*_VALIDATION.json`, guardian note, or animation-note path.

`FINAL_MANIFEST_SHA256.json` at target also contains no `DRAGON` entry. Therefore deletion does not invalidate that manifest as a direct listed hash input.

**RUNTIME CHANGED AFTER TARGET: NO.**  
**RELEASE INPUT CHANGED AFTER TARGET: NO.**

### 1.2 Procedural purity versus procedural impact

The tail is not procedurally pure under the audit-tail rule because five tracked non-`docs/audits` removals followed the target. Therefore:

**PROCEDURAL PURITY: FAIL.**

However, their material impact is not “zero”. The workflow triggers on pushes to `master`, and the release step updates/clobbers the existing version-tag assets. Thus these housekeeping/report pushes can initiate a complete signed rebuild/release even though they are not Android release inputs.

The exact target run finished around 13:50Z. During this audit, release `v1.2.0-alpha5-hybrid140` showed assets updated at 15:34Z — after the five removal/report commits. Its current APK digest was not the exact-target APK digest. This is material release provenance drift, covered again under P19-C5-009.

**PROCEDURAL IMPACT: FAIL / MATERIAL RELEASE-PROVENANCE IMPACT.**

---

## 2. Phase-19 contract rebuilt directly from production

This review did not use the CHAT-1 matrix as an authority. The production contract reconstructed from `WorldRuleProvider.kt`, `WorldRuleCanonical.kt`, `PlayerDomainEngine.kt`, `CampaignSelectionManager.kt`, the command registry and PlayerChangeSet model is:

1. caller supplies `PlayerCommand` and `PlayerResolutionContext`;
2. command is structurally validated and canonicalized through `PlayerCommandKindRegistry`;
3. context campaign and actor must match command;
4. Phase-18 command references are checked before any world-rule provider call;
5. bound contexts perform `COMMAND_PRECHECK`;
6. only then is the typed resolution component selected/invoked;
7. returned draft references are Phase-18 validated;
8. all draft effects are snapshotted;
9. bound contexts perform `DRAFT_EFFECT_CHECK`;
10. only an allowed draft reaches proposal assembly;
11. `PlayerChangeSetValidator` validates the proposal;
12. engine returns proposal/evidence but does not commit state.

World-rule provider supported output is only `WorldRuleDecision`; it cannot return a `PlayerChangeSet`, StatePatch, transaction, DB operation or mechanics/progression result.

---

## 3. Active World Pack authority / downgrade attacks

### 3.1 Previous null-binding bypass is closed

Production no longer has `worldPackBinding: ...? = null` on `PlayerResolutionContext.create`. Instead:

- public `WorldRuleMode.Bound(WorldPackRuleBinding)` exists;
- `UnboundGenericWorldRuleMode` is internal;
- public `PlayerResolutionContext.create(...)` requires an explicit `WorldRuleMode` argument;
- `createUnboundGeneric(...)` is internal.

For a context whose mode is `Bound`, `evaluateWorldRules` cannot silently treat it as null. It resolves a provider or fails closed.

This closes the known omission/null downgrade blocker.

### 3.2 New blocker P19-C5-008 — active binding authenticity is not enforced

The API still accepts an arbitrary caller-created `WorldRuleMode.Bound(WorldPackRuleBinding(uid, version))`.

`CampaignSelectionManager.activeWorldRuleMode()` is the app-level authority that derives an exact bound UID/version from the currently selected and validated World Pack. But there is no production call path tying this authoritative value to `PlayerResolutionContext`, and repository search found no production construction/use of `PlayerDomainEngine` from that manager. The engine itself receives only the context-supplied binding and has no second authoritative expected binding with which to compare it.

Therefore the following supported construction remains possible inside normal app/Core code:

1. active selected World Pack is `A@1`;
2. registry contains provider `A@1` that would reject, and provider `B@1` that allows;
3. caller constructs `PlayerResolutionContext.create(..., WorldRuleMode.Bound(WorldPackRuleBinding("B", "1")))`;
4. engine selects provider B and performs both legality stages against B;
5. the command can resolve although the active authoritative World Pack A would reject it.

This is not the provider registry choosing incorrectly: it correctly obeys the binding supplied. The defect is that an active binding is not authenticated against app-level active World Pack authority.

Stale version with the **same** UID fails closed if registry version differs. Wrong UID with an installed provider does not.

**WORLD RULE MODE: FAIL.**  
**NULL/DOWNGRADE BYPASS: FAIL overall** (null bypass closed; wrong-binding substitution remains).

Minimal correction scope: make production context construction derive bound mode from the active World Pack authority through a non-bypassable factory/engine entrypoint, or otherwise make the engine validate the supplied binding against an independent authoritative active binding. This report does not implement that correction.

---

## 4. Provider selection

For the binding actually supplied, `WorldRuleProviderRegistry` behavior is deterministic and fail closed:

- empty registry + Bound -> `WORLD_RULE_PROVIDER_MISSING`;
- missing UID -> missing-provider failure;
- same UID wrong version -> `WORLD_RULE_PROVIDER_VERSION_MISMATCH`;
- duplicate providers for one World Pack UID -> `DUPLICATE_WORLD_RULE_PROVIDER`;
- same provider object twice -> duplicate UID failure;
- multiple different world UID providers are unambiguous;
- registration list is defensively copied;
- later caller mutation of the list cannot alter registry contents;
- registration order cannot choose between duplicates because duplicates are rejected.

There is no first-wins or fallback provider path.

**PROVIDER SELECTION: PASS.**

P19-C5-008 remains a separate authority-input defect.

---

## 5. Command precheck ordering

Bound resolution order is:

1. command validate/canonicalize;
2. context campaign check;
3. context actor check;
4. `validateReferences(context, commandReferences(...))`;
5. `COMMAND_PRECHECK`;
6. component lookup/resolve.

An unknown or wrong-campaign command reference therefore prevents provider invocation. A reference-valid command rejected by world rules returns `WORLD_RULE_REJECTED` before component resolution.

Original P19 tests and H1 use exploding providers to ensure invalid references do not reach provider evaluation.

**COMMAND PRECHECK: PASS** for a correctly bound context.

---

## 6. Draft check ordering

After component resolution:

1. `draftReferences(...)` is Phase-18 validated;
2. invalid draft references return a reference rejection before draft provider call/proposal;
3. valid draft becomes `WorldRuleEffectSnapshot`;
4. bound mode executes `DRAFT_EFFECT_CHECK`;
5. rule rejection returns no proposal;
6. only allowed draft proceeds to `assembleProposal` and final PlayerChangeSet validation.

A world-rule provider cannot ALLOW a missing reference past Phase 18.

**DRAFT EFFECT CHECK: PASS** for a correctly bound context.

---

## 7. Complete effect snapshot — production count rebuilt

The current production `PlayerDomainChangePayload` sealed family was counted from `PlayerChangeSetModel.kt`, not accepted from an audit declaration. It has exactly 13 current payload families:

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

`appendCanonicalChange` has an exhaustive sealed `when` over those exact families and encodes every meaningful payload field, including nested `DomainRef`, exact delta/share units, operation enum, asset/owner identity and project evidence refs.

`WorldRuleEffectSnapshot` also includes all four draft surfaces:

- `changes`;
- `eventIntents`;
- `ledgerIntents`;
- `warnings`.

Current event payload (`DomainEffectEventIntentPayload`) and current ledger payload (`FinancialTransferLedgerIntentPayload`) are exhaustively encoded.

**EFFECT SNAPSHOT COMPLETENESS: PASS.**

---

## 8. Canonical format adversarial review

`WorldRuleCanonicalWriter` v1 uses a structural pre-hash format with:

- format name/version;
- domain tag;
- typed field operation tags;
- UTF-8 byte-length-prefixed token values;
- explicit section begin/end + section name;
- explicit record begin/end + record type;
- list begin/end + list name + count;
- per-item begin/end + index;
- explicit nullable presence `NULL`/`VALUE`;
- explicit nullable-long presence;
- explicit nullable-domain-ref presence.

The Phase-19 effect/context/request/decision/proposal identities use separate domains.

Adversarial cases reviewed:

- `null` vs old sentinels (`RPGOS-NULL`, `NULL`, `VALUE`): structurally different;
- Allowed vs Rejected: explicit decision variant;
- section shifts: section names and boundaries differ;
- record shifts: record type/boundaries differ;
- list shifts: list name/count/index/boundaries differ;
- empty vs nonempty lists: count differs;
- nested project evidence list vs another top-level change: explicit nested list and record framing prevents the old splice collision;
- event/ledger/warning section substitution: distinct named top-level lists and record types;
- delimiter-looking `:`, `|`, `\`: token length prevents delimiter injection;
- Unicode: length is calculated on UTF-8 bytes and final preimage is UTF-8, preserving unambiguous byte framing;
- whitespace/control-looking strings: ordinary length-delimited data;
- very long legal strings: no alternate framing path was found;
- zero where legal (`ProjectProgressDelta(0)`, Long fields): explicit decimal field data;
- negative legal Long values, `Long.MIN_VALUE`, `Long.MAX_VALUE`: unique decimal encodings under field framing;
- ordered lists: index preserves deliberate ordering;
- set/map semantics in context are normalized before writing.

No different semantic tree with identical canonical v1 preimage was constructed in this review.

**CANONICAL FORMAT: PASS.**  
**CANONICAL COLLISION RESISTANCE: PASS.**

This PASS is about the canonical preimage design, not a cryptographic proof against SHA-256 collisions.

---

## 9. Context identity

`PlayerResolutionContext.deterministicFingerprint()` includes:

- context version;
- campaign UID;
- actor kind + UID;
- all campaign-scoped known references;
- dependency versions;
- entropy evidence UID + exact value;
- world-rule mode;
- for Bound, World Pack UID + version.

Known references are sorted by campaign/kind/UID. `dependencyVersions` is copied to `TreeMap`, so map insertion order does not affect identity. Sections/lists are framed.

Changing campaign, actor, reference tuple, dependency version, entropy, bound/unbound mode, World Pack UID or version changes the context canonical preimage.

No clock, timezone, locale, UUID or iteration-order dependency occurs in this fingerprint path.

**CONTEXT FINGERPRINT: PASS.**

P19-C5-008 is not a fingerprint omission — a *wrong supplied* binding is faithfully fingerprinted. The issue is lack of comparison with the independent active authority.

---

## 10. Request identity

`WorldRuleRequest.requestFingerprint` includes:

- evaluation stage;
- World Pack UID/version;
- campaign UID;
- actor kind/UID;
- command UID/kind;
- canonical command fingerprint;
- context fingerprint;
- explicit effects presence;
- draft effect fingerprint when present.

Constructor invariants require:

- command campaign/actor equal request campaign/actor;
- COMMAND_PRECHECK has no effects;
- DRAFT_EFFECT_CHECK has effects.

Thus command vs draft stages cannot share a semantic request identity merely by manipulating effect presence; stage and effect framing both differ.

Independent allocation with equivalent semantic input reproduces identity.

**REQUEST REPLAYABILITY: PASS.**

---

## 11. Decision identity

`WorldRuleDecisionRecord` validates provider World Pack identity against request binding and fingerprints:

- provider UID/version;
- provider World Pack UID/version;
- request stage;
- request fingerprint;
- explicit `ALLOWED` / `REJECTED` variant;
- rule UID;
- nullable reason UID;
- sorted evidence UIDs.

Decision constructors reject blank rule/reason/evidence IDs and duplicate evidence. Decision evidence is copied/frozen. Evidence ordering is therefore treated deliberately as set-like for decision identity: different caller ordering with equal unique evidence produces the same canonical decision fingerprint.

The old ALLOW reason sentinel collision is closed.

Fingerprint structure is correct, but replayability of **provider evaluation** is broken by P19-C5-007 below.

**DECISION REPLAYABILITY: FAIL overall because accepted provider state can change output for identical request.**

---

## 12. Proposal identity

Proposal UID uses canonical domain `PLAYER_DOMAIN_PROPOSAL` with:

- full canonical command encoding as one framed field;
- context fingerprint;
- component kind/version section;
- framed/list-counted ordered world-rule decision fingerprints.

For bound successful resolution, the draft effect is transitively included through the DRAFT_EFFECT_CHECK request/decision fingerprint. Decision list count/order is explicit; no stale delimiter concatenation remains.

Same semantic command/context/component/rule decisions reproduce the same proposal UID independently of object allocation. Different decision version/outcome/effect changes the UID.

However, because P19-C5-007 allows a registered provider to return different decisions for identical requests, end-to-end repeated resolution can produce different proposal UIDs for the same external semantic inputs.

**PROPOSAL UID DETERMINISM: FAIL end-to-end.**  
The proposal UID canonical *format* itself passes.

---

## 13. Retained provider state adversarial review

Current provider validator:

- requires all provider instance fields to be final;
- accepts primitives/scalar wrappers/String;
- rejects arrays/collections/arbitrary object types;
- recursively validates enum-typed fields;
- walks inherited provider fields;
- rejects unsafe inherited writer/object fields.

The previous simple mutable-enum blocker (`enum { INSTANCE; var counter = ... }`) is closed because the mutable field is directly declared on the base enum type.

### P19-C5-007 — constant-specific enum subclass state bypass

**Severity:** BLOCKER / HIGH correctness-determinism.

`validateEnumRetainedState(enumType, ...)` inspects `enumType.declaredFields`, excluding static/synthetic fields. It never inspects the runtime class of each enum constant.

Java/Kotlin permit constant-specific enum class bodies. A legal construction is conceptually:

```kotlin
enum class Mode {
    INSTANCE {
        private var counter = 0
        override fun allow(): Boolean = counter++ == 0
    };
    abstract fun allow(): Boolean
}
```

The `counter` field belongs to the JVM-generated constant-specific subclass (for example `Mode$1`), not to the base `Mode` class. A provider can retain `private val mode: Mode` — a final enum-typed field accepted by `validateProviderState`. Recursive enum validation sees no unsafe base-enum instance field. `provider.evaluate(request)` then calls `mode.allow()` and receives different output on repeated identical requests.

This requires no reflection, Unsafe, DB/repository, writer callback, array alias or arbitrary external mutable collection. It is ordinary Kotlin/JVM enum syntax on the exact extension surface the validator explicitly claims to support.

**Minimal reproducer:**

1. define an enum with one constant-specific body containing `var counter`;
2. define provider with final field `val mode: Mode`;
3. register provider — registry accepts it;
4. call identical bound resolution twice;
5. first evaluation allows, later evaluation rejects (or vice versa).

**Expected:** retained state policy rejects any provider configuration capable of mutation-driven output.

**Actual:** constant-specific mutable state is not inspected.

**Minimal correction scope:** enum-state validation must validate runtime enum constant classes (with safe cycle handling) or remove enum retained state from the allowed surface / replace with explicitly whitelisted immutable value types. No fix is made by this audit.

#### Other retained-state attacks

- ordinary mutable enum base field: rejected;
- enum containing a final collection/object field: rejected;
- provider array/collection: rejected;
- inherited provider writer/object: rejected;
- companion/static/global mutable state is outside the retained-instance validator and is effectively trusted-code behavior; arbitrary provider code can always call global APIs directly. This is a trust-boundary observation, not classified as a separate blocker because the provider extension is explicitly internal/trusted and no retained-state validator can sandbox arbitrary JVM code;
- synthetic outer/delegate references on provider classes are not globally skipped by `validateProviderState`; unsafe provider instance fields are examined. The concrete bypass found is specifically the runtime subclass of an accepted enum constant.

**PROVIDER STATE SECURITY: FAIL.**

---

## 14. Immutability / aliasing

Production defensively copies/fixes the relevant supported inputs:

- context known-reference set -> new `LinkedHashSet` + unmodifiable set;
- context dependency map -> `TreeMap` + unmodifiable map;
- provider registry provider list -> `ArrayList` then immutable map/set;
- effect snapshot lists -> copied unmodifiable lists;
- decision evidence -> copied unmodifiable list;
- decision record evidence -> copied unmodifiable list;
- resolution evidence rule decisions -> copied list;
- draft/change-set event/ledger/warning lists -> copied lists.

Provider receives a command re-decoded from canonical command serialization. After provider return, engine recomputes command fingerprint and effect fingerprint and fails `WORLD_RULE_PROVIDER_INPUT_MUTATED` if either changed.

Nested model objects used here are immutable value/data objects or classes exposing val/frozen lists.

Caller-owned list mutation after decision/snapshot construction does not change fingerprints.

**REQUEST IMMUTABILITY: PASS.**

---

## 15. Determinism

Positive determinism properties:

- context set/map normalized;
- evidence normalized by sorting;
- registry duplicates fail rather than order-select;
- canonical lists preserve semantic order with indexes;
- fingerprints use SHA-256 over UTF-8;
- no locale-sensitive formatting in WorldRule canonical writer;
- Long values use deterministic decimal strings;
- no clock/random/UUID in canonical identity implementation;
- independent object allocation does not enter fingerprints.

But P19-C5-007 makes accepted provider evaluation stateful, so identical semantic requests can return different decisions and proposals.

**DETERMINISM: FAIL.**

---

## 16. Failure atomicity

World-rule rejection/fault paths return rejection or throw structural failure before proposal escape. Provider has no supported DB/DAO/repository/store/transaction/StatePatch/writer handle in request/output. Provider output is a decision only.

Engine also performs reference rejection before provider/component as appropriate and draft rejection before proposal assembly.

Original P19 tests include SQLite-backed sentinel checks for no mutation on rule rejection and provider failure. Phase-18 regression tests include SQLite-backed no-mutation checks on reference failure.

**FAILURE ATOMICITY: PASS.**  
**ZERO AUTHORITATIVE MUTATION: PASS.**

This assumes trusted internal extension code obeys its supported capability surface; arbitrary JVM code could call external globals directly and is not sandboxed.

---

## 17. PlayerChangeSet / Phase-17 regression

Production was re-read rather than inferred from P19 tests.

- `ExactLongDelta`: zero rejected; full signed Long range otherwise represented exactly; arithmetic uses `Math.addExact`/`subtractExact`.
- `ProjectProgressDelta`: exact non-negative Long; zero remains legal.
- `OwnershipShare`: fixed scale `3_600_000_000`, exact integer units/fractions, no float constructor, bounds `(0,100%]`.
- `OwnedAssetRef`: identity is `(assetKindUid, assetUid)`, not UID alone.
- `OwnershipChange`: retains ownership record UID, full asset identity, from-owner, to-owner, exact share units.
- composite conflict identity remains typed (`DomainRef` tuples), not delimiter-concatenated.
- finance change and ledger intent preserve exact from/to/amount/currency/transaction-type fields.
- PlayerChangeSet schema version remains `1`.
- canonical encode/decode/fingerprint regressions execute in exact JVM suite.
- Phase-19 provenance only populates the existing `worldRuleProviderUid`; no schema replacement was introduced.

**PLAYERCHANGESET REGRESSION: PASS.**

---

## 18. Phase-18 reference model

Representative production reference semantics remain:

- equipment slot remains classification B / definition identity: item instance is reference-validated; slot UID is not treated as a separately resolvable `EQUIPMENT_SLOT` record;
- ownership record UID remains D (new/derived record identity, not a required existing reference);
- ownership asset/fromOwner/toOwner are A (existing references);
- finance from-account, to-account and currency are A;
- practice skill / use technique produce definition references;
- project work/requirement/milestone/evidence/resource refs remain represented in command/draft reference extraction;
- draft financial ledger references also include both accounts and currency;
- reference validation happens before world rules on command and before draft rule check on draft.

World rules cannot ALLOW through an unknown or wrong-campaign reference.

**PHASE-18 ORDERING: PASS.**  
**PHASE-18 REFERENCE SEMANTICS: PASS.**

---

## 19. Phase boundary / authority

Production Phase-19 contract remains legality-only. No supported provider request/output surface exposes:

- ProgressionEngine calculations;
- diminishing returns;
- aggregate InvariantValidator authority;
- transaction/COMMIT primitive;
- DB/DAO/repository/store;
- StatePatch;
- authoritative state writer;
- world-mechanics numeric effect calculation.

`WorldRuleProvider` returns only legality decisions. `PlayerDomainEngine` builds a proposal, not a committed state.

Existing Phase-20 material in repository is planning/documentation; this CHAT-5 review neither modifies nor begins Phase 20.

**PHASE BOUNDARY: PASS.**

---

## 20. Core world-agnostic review

`WorldRuleProvider.kt`, `WorldRuleCanonical.kt` and the Phase-19 engine contract contain no Naruto/Bleach/Chakra/Reiatsu/Sharingan/Kido/Raiton/Sonido/Hollow/Shinigami-specific rules or types.

`CampaignSelectionManager` retains a pre-existing app default World Pack directory name (`Naruto.worldpack`), but the Core legality contract itself remains universe-agnostic and derives bound identity from validated package metadata rather than hard-coded Naruto rule semantics.

**CORE WORLD-AGNOSTIC: PASS.**

---

## 21. Test quality — original P19 and H1–H7

### Strong coverage

Original P19 tests cover:

- allow/reject paths;
- no-proposal rejection;
- SQLite-backed no-authority-mutation checks;
- unknown/wrong-campaign ordering with explode-if-called provider;
- draft rejection after command allow;
- missing/duplicate/version mismatch provider;
- deterministic fingerprints/proposals in standard provider fixtures;
- equipment B;
- ownership D/A/A/A;
- finance references;
- Phase-17 exact-value/identity/serialization regressions;
- world-specific token scan;
- supported writer-type scan.

H1–H7 materially improve coverage for:

- explicit world-rule mode;
- nullable/canonical sentinel removal;
- decision variant;
- old project-evidence/equipment splice collision;
- context section collision;
- set/map ordering;
- independent-allocation fingerprints;
- proposal identity framing.

### Defects / gaps

1. **H2 false confidence:** its mutable-enum fixture is `enum MutableMode { INSTANCE; var counter:Int=0 }`. That puts `counter` on the base enum class — exactly what the recursive validator checks. There is no constant-specific class-body fixture, so P19-C5-007 is untested.

2. **No active-authority wrong-UID test:** H1 verifies no nullable mode and verifies missing/same-UID wrong-version failures, but it does not model active authority A with context-supplied B plus an installed B provider. P19-C5-008 is therefore untested.

3. **Weak H7 zero-mutation assertions:** H7 uses a local `var authority=7` not reachable by the provider and then asserts it remains 7. That assertion cannot detect a hidden write capability. This does not erase the stronger original P19/Phase-18 SQLite-backed tests, but H7 itself is a false-strength test.

4. Collision tests generally compare resulting fingerprints rather than exposing canonical preimages. Because the canonical writer is structured and directly inspectable this did not produce a new canonical collision in this review, but preimage-level tests would diagnose framing errors more precisely than hash-only comparisons.

No disabled hardening test was found in the inspected suites; exact JVM job passed them.

Because two real production bypasses remain untested, **TEST QUALITY: FAIL.**

---

## 22. FULL JVM / exact CI / artifact / release

### Exact run

Verified GitHub Actions run:

- run number: **#482**
- run ID: **31806156168**
- workflow: `Build & Release RPG OS ALPHA`
- event: push
- exact head: `11e0dcac8e128404524350bc53b9963124e9bbd7`
- status: `completed`
- conclusion: `success`

Single build job head is the same exact SHA. Successful steps independently include:

- Validate project;
- Run JVM unit tests (`:app:testDebugUnitTest` from workflow definition);
- Build signed ALPHA APK;
- Prepare release files;
- Upload Actions artifact;
- Update existing GitHub Release assets.

**FULL JVM: PASS** on exact-target CI.

### Exact artifact association

Verified artifact:

- artifact ID: **9221387982**
- name: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140`
- GitHub artifact digest: `sha256:3caf5f75f76534bd2e2d5e6c9a8c369089795d446f880cf8ffd6391ff64104f1`
- artifact workflow_run ID: **31806156168**
- artifact workflow_run head SHA: exact `11e0dcac8e128404524350bc53b9963124e9bbd7`

The artifact ZIP was downloaded and independently hashed/unpacked during this audit. Exact-target contents:

- APK size: `26176175` bytes
- exact-target APK SHA-256: `34c247c5535ed9c72a3d58d4898f0b0b9bcef3df130fb81f4529fd87f9ebc586`
- `.apk.sha256` content matches that APK digest exactly
- exact-target `update.json` has version name `1.2.0-alpha5-hybrid140`, version code `140`, and `sha256` exactly `34c247c5535ed9c72a3d58d4898f0b0b9bcef3df130fb81f4529fd87f9ebc586`
- exact-target APK contains no `DRAGON_*` files.

Thus **exact run -> exact Actions artifact association: PASS**.

### Current release does not represent the exact target artifact

Release tag checked:

`v1.2.0-alpha5-hybrid140`

Release ID: `367217333`.

At audit time it had been updated after the target run, at 2026-08-14 15:34Z. Current APK asset metadata:

- asset name: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140.apk`
- asset ID: `514583936`
- size: `26176175`
- current release APK digest: `sha256:8d588e52cb6d5d395c136202d53e8a5c4f62e910089d03d4b2d727bd14c70c3c`

This differs from exact-target artifact APK digest:

`sha256:34c247c5535ed9c72a3d58d4898f0b0b9bcef3df130fb81f4529fd87f9ebc586`.

Current release also contains a newly uploaded `.apk.sha256` asset and `update.json`, both with later asset creation/update timestamps.

Therefore the current mutable release cannot be claimed as the exact-target signed APK release artifact even though run #482 originally completed its release step successfully.

**EXACT CI: FAIL overall for the requested end-to-end run/artifact/release verification.**  
Sub-verdicts: exact run PASS; Validate PASS; full JVM PASS; signed APK build PASS; exact Actions artifact association PASS; current release exact-head provenance **FAIL**.

---

## 23. New correctness problems

### P19-C5-007 — CONSTANT_SPECIFIC_ENUM_RETAINED_STATE_BYPASS

**Severity:** BLOCKER / HIGH  
**Production path:** `WorldRuleProviderRegistry.of` -> `validateProviderState` -> `validateEnumRetainedState` -> accepted provider -> `PlayerDomainEngine.evaluateWorldRules` -> stateful decision.  
**Minimal reproducer:** provider retains a final base-enum field whose selected constant has a constant-specific class body with mutable counter; registration accepts; identical requests produce different outputs.  
**Expected:** retained state policy rejects mutation-capable provider configuration.  
**Actual:** runtime subclass fields of enum constants are not inspected.  
**Minimal correction scope:** validate enum constant runtime classes recursively/cycle-safely, or disallow generic enum retained state and whitelist immutable enum forms.

### P19-C5-008 — ACTIVE_WORLD_WRONG_BINDING_SUBSTITUTION

**Severity:** BLOCKER / HIGH legality-authority  
**Production path:** active `CampaignSelectionManager` world A exists, but caller constructs public `WorldRuleMode.Bound(B)` -> `PlayerResolutionContext.create` -> engine -> registry selects B.  
**Minimal reproducer:** active A/reject + installed B/allow; pass context Bound(B); resolution uses B and may succeed.  
**Expected:** active/bound world legality is evaluated against the authoritative active World Pack identity.  
**Actual:** Core trusts arbitrary context-supplied bound identity and has no independent active-binding comparison.  
**Minimal correction scope:** non-bypassable production context factory/engine entrypoint derived from `activeWorldRuleMode`, or engine validation against independent active binding authority.

### P19-C5-009 — MUTABLE_RELEASE_TAG_POST_TARGET_OVERWRITE

**Severity:** HIGH release-integrity/provenance blocker  
**Production path:** any push to `master`, including non-release-input housekeeping/audit changes -> build workflow -> same version/tag -> release asset update/clobber.  
**Minimal reproducer:** build target at fixed version; push a top-level documentation deletion with no Android input change; workflow rebuilds and overwrites assets under the same release tag.  
**Expected:** exact audited target artifact remains attributable/pinned, or a later release change is represented by a distinct immutable version/tag/provenance.  
**Actual:** current release APK digest differs from exact-target artifact while tag/version is unchanged; target-to-release provenance is lost from the mutable current release surface.  
**Minimal correction scope:** restrict release workflow triggers to release-input changes / explicit release action, or publish immutable SHA/version-tagged artifacts and prevent `--clobber` replacement of an audited release by unrelated pushes.

---

## 24. Non-blocking observations

- The six blockers reported against the older `48854043...` target are materially addressed in the present canonical hardening: implicit nullable binding, simple mutable base-enum state, nullable sentinel collision, ALLOW/REJECT sentinel collision, unframed effect stream collision and unframed context stream collision are no longer reproduced by the current v1 format/mode model.
- One-provider-version-per-World-Pack-UID registry is a deployment constraint but deterministic/fail-closed, not a correctness blocker by itself.
- Trusted internal provider/component code is not a JVM sandbox; direct access to process-global/static mutable facilities cannot be prevented by instance-field validation. The blocker here is narrower: the validator explicitly accepts enum retained state while missing legal runtime enum-constant state.
- Current Android source/build inputs do not include the removed DRAGON validation/note files.
- The report-only commit requested by CHAT-5 itself may trigger the broad master-push release workflow; this audit does not modify that workflow because production/workflow fixes are prohibited.

---

## 25. Final required format

**VALIDATED RUNTIME SHA:**  
`11e0dcac8e128404524350bc53b9963124e9bbd7`

**FRESH MASTER:**  
`c6b05d5e3928496c3fe45967a888c07e7584b101` immediately before report write

**RUNTIME CHANGED AFTER TARGET:**  
**NO**

**RELEASE INPUT CHANGED AFTER TARGET:**  
**NO**

**PROCEDURAL PURITY:**  
**FAIL**

**PROCEDURAL IMPACT:**  
**FAIL — later non-release-input pushes rebuilt/clobbered mutable release assets; current release APK no longer equals exact-target artifact APK**

**WORLDRULEPROVIDER:**  
**FAIL**

**WORLD RULE MODE:**  
**FAIL**

**NULL/DOWNGRADE BYPASS:**  
**FAIL** — null/unbound omission closed; wrong active binding substitution remains

**PROVIDER SELECTION:**  
**PASS**

**COMMAND PRECHECK:**  
**PASS**

**DRAFT EFFECT CHECK:**  
**PASS**

**EFFECT SNAPSHOT COMPLETENESS:**  
**PASS**

**CANONICAL FORMAT:**  
**PASS**

**CANONICAL COLLISION RESISTANCE:**  
**PASS**

**CONTEXT FINGERPRINT:**  
**PASS**

**REQUEST REPLAYABILITY:**  
**PASS**

**DECISION REPLAYABILITY:**  
**FAIL**

**STALE DECISION RESISTANCE:**  
**PASS**

**PROPOSAL UID DETERMINISM:**  
**FAIL** end-to-end due accepted mutable provider state; UID framing itself PASS

**PROVIDER STATE SECURITY:**  
**FAIL**

**REQUEST IMMUTABILITY:**  
**PASS**

**DETERMINISM:**  
**FAIL**

**FAILURE ATOMICITY:**  
**PASS**

**ZERO AUTHORITATIVE MUTATION:**  
**PASS**

**PHASE-18 ORDERING:**  
**PASS**

**PHASE-18 REFERENCE SEMANTICS:**  
**PASS**

**PLAYERCHANGESET REGRESSION:**  
**PASS**

**CORE WORLD-AGNOSTIC:**  
**PASS**

**PHASE BOUNDARY:**  
**PASS**

**TEST QUALITY:**  
**FAIL**

**PHASE 3–18 REGRESSION:**  
**PASS**

**FULL JVM:**  
**PASS** — exact target CI full JVM step completed successfully

**EXACT CI:**  
**FAIL overall** — run #482 / ID `31806156168` exact head PASS; Validate PASS; full JVM PASS; signed APK PASS; artifact `9221387982` association/digest PASS; current mutable release exact-target association FAIL

**NON-BLOCKING OBSERVATIONS:**  
Canonical v1 hardening closes the previously known structural collisions; provider registry and Phase-18 ordering are correct for supplied bound mode; removed DRAGON files are not APK/release inputs; trusted JVM extensions are not sandboxed beyond supported retained-state restrictions.

**NEW CORRECTNESS PROBLEMS:**  
`P19-C5-007`, `P19-C5-008`, `P19-C5-009`

**REPORT PATH:**  
`docs/audits/WORK-20260814-P19_CHAT5_FINAL_COMPLETE_CORRECTNESS_REVIEW_11E0DCAC.md`

**REPORT COMMIT SHA:**  
filled by the report-only commit containing this file

**FINAL CHAT-5 VERDICT:**  
**FAIL**

Phase 19 is **not** globally accepted by CHAT-5. This review does **not** begin Phase 20.
