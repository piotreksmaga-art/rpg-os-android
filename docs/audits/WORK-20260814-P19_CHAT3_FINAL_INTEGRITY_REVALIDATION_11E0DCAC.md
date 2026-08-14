# WORK-20260814-P19 — CHAT-3 FINAL INTEGRITY REVALIDATION

PHASE 19 INTEGRITY REVALIDATION: **FAIL**

ROLE: **CHAT-3 — INDEPENDENT INTEGRITY AUDITOR**

VALIDATED RUNTIME SHA: `11e0dcac8e128404524350bc53b9963124e9bbd7`

FRESH MASTER (pre-report): `c6b05d5e3928496c3fe45967a888c07e7584b101`

## Executive verdict

The exact target runtime retains the Phase-19 canonical-framing, provider-state, request-immutability, reference-ordering and failure-atomicity hardening. The prior nullable/unbound downgrade is closed. Exact CI run #482 is successful on the target SHA.

However, an independent authority-boundary attack found a remaining supported-API substitution path: `PlayerResolutionContext.create(...)` publicly accepts any caller-supplied `WorldRuleMode.Bound(WorldPackRuleBinding(...))`, while `PlayerDomainEngine` trusts that binding directly. The engine does not independently bind it to the canonical active World Pack selected/validated by `CampaignSelectionManager.activeWorldRuleMode()` or to any equivalent campaign authority token. If the registry contains a valid provider for a different World Pack UID/version, a normal caller can construct a context using that different binding and cause the bound campaign to be evaluated under the wrong provider. Missing and stale versions fail closed, but a *different registered* World Pack is accepted.

This is an authority-substitution blocker, not a null/unbound bypass and not a canonical-hash collision.

## 1. Fresh master / history

Target is the merge base of target..master. At the final pre-report history check, fresh master was `c6b05d5e3928496c3fe45967a888c07e7584b101`, eight commits ahead of the target.

Post-target file classification:

- **runtime:** none
- **tests:** none
- **build/workflow/config:** none
- **docs/audits:** three added audit reports
- **housekeeping/support-only:** five root-level `DRAGON_*` support/validation files removed
- **unknown:** none

Removed support files inspected/classified:

- `DRAGON_ANIMATION_NOTES.md`
- `DRAGON_ANIMATION_VALIDATION.json`
- `DRAGON_GUARDIAN_127.md`
- `DRAGON_GUARDIAN_127_VALIDATION.json`
- `DRAGON_UI_HOTFIX_126_VALIDATION.json`

The deleted Markdown/JSON files are narrative/validation snapshots rather than Android source/resources, Gradle inputs, workflow configuration, migration input, generated runtime configuration or release payload. Repository searches found no surviving production/build/test/workflow references to their exact names. Their locations and contents do not establish a build dependency. Exact target CI independently builds/tests/releases without treating them as inputs.

RUNTIME CHANGED AFTER TARGET: **NO**

PROCEDURAL PURITY: **FAIL** — five non-audit housekeeping/support files were deleted after the pinned target, so the post-target history is not strictly report-only.

PROCEDURAL IMPACT: **NON-BLOCKING / NO RUNTIME, TEST, BUILD OR RELEASE IMPACT FOUND**. The procedural impurity is reported separately and is not treated as runtime-integrity evidence.

## 2. World-rule authority / downgrade attacks

### Null/default/legacy/unbound downgrade

`PlayerResolutionContext.create(...)` now requires a non-null `WorldRuleMode` argument; there is no default value for that argument. `UnboundGenericWorldRuleMode` and `createUnboundGeneric(...)` are internal. Public `WorldRuleMode` exposes `Bound`, not a public generic-unbound constructor/factory. Engine evaluation skips rules only on the internal unbound singleton.

No supported null/default/legacy/copy/secondary-constructor/factory route was found that converts a bound campaign into unbound mode. Reflection against Kotlin/JVM internals is outside the supported capability contract and is not required to be sandboxed.

NULL/DOWNGRADE BYPASS: **PASS** for the former null/unbound defect.

UNBOUND MODE EXPOSURE: **PASS**. `UnboundGenericWorldRuleMode` is Core-internal; no public alias/factory/return helper exposing it was found.

### Wrong-bound substitution — BLOCKER

`CampaignSelectionManager.activeWorldRuleMode()` is explicitly documented as the canonical app-level authority. It reads the active World Pack directory, validates the package, extracts manifest package ID/version and returns `WorldRuleMode.Bound(WorldPackRuleBinding(uid, version))`.

But `PlayerResolutionContext.create(...)` is public and accepts any caller-created `WorldRuleMode`. `WorldRuleMode.Bound` and `WorldPackRuleBinding` are public value types. `PlayerDomainEngine.evaluateWorldRules()` obtains its binding solely from `context.worldRuleMode`; it does not receive or check `CampaignSelectionManager`, an active-selection token, or any independently authenticated campaign→World Pack association.

Attack construction using supported APIs:

1. Active application World Pack is A and canonical selector would produce `Bound(A, versionA)`.
2. Registry contains providers for A and B.
3. Caller creates `PlayerResolutionContext.create(..., worldRuleMode = Bound(B, versionB))`.
4. Engine trusts B and calls `worldRuleRegistry.providerFor(B)`.
5. Because B is a valid registered UID/version, lookup succeeds. No mismatch with active A is detected.
6. A permissive B provider can therefore authorize a command/effect that A would reject.

Wrong UID that is absent fails `WORLD_RULE_PROVIDER_MISSING`; same UID with stale/wrong version fails `WORLD_RULE_PROVIDER_VERSION_MISMATCH`. Those fail-closed cases do not close the distinct **wrong-but-registered World Pack substitution**.

Repository search also found no production call site proving that every `PlayerResolutionContext` is necessarily constructed from `CampaignSelectionManager.activeWorldRuleMode()`; the selector exists, but the engine contract does not enforce its authority.

WORLD RULE AUTHORITY: **FAIL**

NEW BLOCKER: **P19-C3-001 — BOUND_WORLD_PACK_AUTHORITY_SUBSTITUTION**.

## 3. Registry integrity

Registry construction defensively copies the provider list, validates provider retained state, keys deterministically by `worldPackUid`, and rejects duplicate registration for the same World Pack UID. Provider lookup requires exact World Pack version.

Attacks:

- duplicate same World Pack UID: fail closed (`DUPLICATE_WORLD_RULE_PROVIDER`)
- missing provider: engine fails closed (`WORLD_RULE_PROVIDER_MISSING`)
- same World Pack UID / wrong provider World Pack version: fail closed (`WORLD_RULE_PROVIDER_VERSION_MISMATCH`)
- registration-order changes: do not change UID-keyed lookup semantics
- caller mutation of source provider list after registry construction: detached by `ArrayList` plus unmodifiable map/set
- same provider UID across different World Pack entries is not itself an identity collision because provider/world-pack/version participate in decision identity; registry authority remains World-Pack keyed.

REGISTRY INTEGRITY: **PASS**

The authority blocker above is deliberately not mislabeled as a registry collision: the registry correctly resolves the *binding it is given*; the defect is that the binding is not authenticated against active campaign/World-Pack authority.

## 4. Provider retained-state security

Provider subclass fields are inspected through inheritance. Non-static retained instance fields must be final. Primitive/String/wrapper scalar state is accepted; arbitrary objects, arrays, collections, atomics, lazy/delegate backing objects and writer-like capabilities are rejected because their field types are outside the safe scalar/enum set.

Enum state is recursively inspected rather than automatically trusted. A mutable enum `var` is non-final and rejected. An enum retaining a mutable arbitrary object is rejected. A nested enum is recursively inspected. Therefore storing a writer through an enum does not become safe merely because the outer field is enum-typed.

A theoretical arbitrary malicious same-module provider could reach static/global state directly without retaining it; Phase 19 does not promise a JVM bytecode sandbox and this audit does not require one.

One robustness observation: the recursive enum type validator has no explicit visited-type set, so mutually recursive *safe enum type graphs* can theoretically recurse until stack exhaustion. That is fail-closed from an authority perspective and does not admit mutable/writer retained state; it is not classified as a Phase-19 authority blocker in this audit.

PROVIDER STATE SECURITY: **PASS**

MUTABLE ENUM ATTACK: **PASS**

## 5. Request immutability / command detachment

Before provider entry the engine canonicalizes the command through command codec encode/decode and computes the canonical command fingerprint. It creates a separate provider command via another encode/decode round trip. After provider evaluation the engine re-fingerprints provider command input and effect snapshot and fails with `WORLD_RULE_PROVIDER_INPUT_MUTATED` if semantics changed.

`PlayerResolutionContext` defensively copies known-reference sets and dependency-version maps; the map is normalized into `TreeMap`. Effect-snapshot top-level collections are defensively copied and unmodifiable. Decision evidence is defensively copied; decision records sort/copy evidence before storage/fingerprinting. World Pack binding consists of immutable String values.

Caller-owned collection mutation after construction therefore cannot alter the already-created canonical context/request identity through supported aliases.

REQUEST IMMUTABILITY: **PASS**

## 6. Canonical collision resistance

`WorldRuleCanonicalWriter` uses structural binary framing through `DataOutputStream`, not delimiter concatenation. It has a document marker/version, explicit operation/type tags, UTF-8 byte lengths, fixed-width longs, explicit booleans, independent child payloads for sections/records and list counts plus length-framed list elements.

Adversarial cases reviewed:

- null vs ordinary string: distinct type tags
- empty string vs absent/null: distinct encoding
- zero long vs absent nullable value: distinct encoding
- `:`, `|`, `\\`, whitespace, Unicode and length-looking content: encoded as length-delimited UTF-8 data
- record split / section split / collection split / nested-list split: explicit structural tags and child-byte lengths prevent cross-boundary aliasing
- multiple records: per-element list framing and counts
- input ordering: normalized where semantic order is declared irrelevant (known-reference set, dependency map, decision evidence); retained where list order is semantic.

No pair of distinct tested/reviewed semantic structures sharing an encoder preimage was identified.

CANONICAL COLLISION RESISTANCE: **PASS**

## 7. Effect snapshot integrity

Current production canonicalizer covers **13** `PlayerDomainChangePayload` families:

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

All 13 have explicit payload-family records and field-level canonical encoding. High-risk tuples are not flattened: asset kind+UID, owner kind+UID, finance source/destination/amount/currency/type, project UID/result/progress/evidence, equipment subject/slot/operation/item, skill/technique subject+target identity/delta and condition/runtime target payload are represented structurally.

Event intents, ledger intents and warnings are independent framed lists with explicit fields/payload records. Project evidence uses full `DomainRef` kind+UID records.

No two semantically different snapshots with the same reviewed canonical preimage were found.

EFFECT SNAPSHOT INTEGRITY: **PASS**

## 8. Context fingerprint

`PlayerResolutionContext.deterministicFingerprint()` uses the same structural writer. Campaign, actor, known references, dependency versions, entropy and world-rule mode occupy separate named sections/lists. Known references are sorted by campaign/kind/UID; dependency versions are held in `TreeMap` order. Bound mode includes World Pack UID/version; generic-unbound has a distinct mode value.

Cross-section collision attacks between knownReferences/dependencyVersions/world-rule mode/campaign/actor/entropy are structurally separated. Set/map insertion order cannot change the fingerprint.

CONTEXT FINGERPRINT: **PASS**

## 9. Decision integrity and stale-decision resistance

Core-owned `WorldRuleDecisionRecord.create()` fingerprints:

- provider UID/version
- World Pack UID/version
- evaluation stage
- request fingerprint
- explicit decision variant (`ALLOWED`/`REJECTED`)
- rule UID
- structurally nullable reason UID
- sorted evidence UID list.

Therefore Allowed and Rejected cannot alias merely because reason text resembles an old sentinel. A meaningful change to provider version, World Pack version, stage, command/context/effect request fingerprint, rule/reason or evidence changes decision identity. Evidence ordering is normalized intentionally.

Records are generated from the current request; providers do not supply arbitrary `WorldRuleDecisionRecord` provenance. Proposal identity later includes the resulting decision fingerprints.

DECISION INTEGRITY: **PASS**

STALE DECISION RESISTANCE: **PASS** for command/context/provider-version/world-pack-version/stage/effect changes. The separate wrong-binding blocker is an *authority selection* defect before this identity system; once the wrong binding is selected, the resulting record correctly identifies that wrong World Pack.

## 10. Proposal UID

Production `assembleProposal()` derives `changeSetUid` with `WorldRuleCanonicalWriter.fingerprint("PLAYER_DOMAIN_PROPOSAL")`. It structurally includes command encoding, context fingerprint, component identity and a framed list of rule-decision fingerprints.

No residual proposal UID path based on `toString()`, `hashCode()`, `identityHashCode()`, unordered iteration or legacy delimiter concatenation was identified in the Phase-19 construction path.

Same canonical command/context/component/decisions yields the same UID; a changed relevant decision fingerprint or stage changes the proposal identity through the decision record.

PROPOSAL UID: **PASS**

## 11. Failure atomicity / authority boundary

Production ordering is proposal-only. World-rule rejection returns a typed rejection before proposal construction. Missing provider, version mismatch, provider fault and malformed decision throw structural failure before assembly. Reference rejection occurs before corresponding provider evaluation. Component output remains a draft until draft references and draft world rules pass.

Provider API/request/registry types expose no supported SQLiteDatabase, DAO/store/repository writer, ledger writer, `StatePatch`, `TurnTransaction` or commit callback capability. Provider returns only a typed rule decision, not a `PlayerChangeSet`.

Consequently reviewed failure paths do not have a supported authoritative writer to mutate and no final `PlayerChangeSet` escapes those failures.

FAILURE ATOMICITY: **PASS**

AUTHORITY BOUNDARY: **FAIL** overall because World Pack *selection authority* can be substituted via arbitrary public `Bound`, despite writer-capability isolation being PASS.

ZERO AUTHORITATIVE MUTATION: **PASS**

## 12. Phase-18 ordering and locks

`resolve()` performs Phase-18 command reference validation before command provider evaluation and draft reference validation before draft provider evaluation. An `Allowed` decision therefore cannot legalize `UNKNOWN_REFERENCE`, `WRONG_CAMPAIGN_REFERENCE` or a ghost draft reference.

Phase-18 semantic locks remain represented in production/tests: equipment slot remains definition identity rather than campaign lookup; ownership record remains local identity; asset/from-owner/to-owner retain full typed identities; finance retains account/currency reference coverage.

PHASE-18 ORDERING: **PASS**

## 13. Phase-20 exclusion / schema and persistence

Repository production search found no `ProgressionEngine` implementation introduced by Phase 19; hits for that concept are documentation/planning. No progression writer/persistence/Phase-20 runtime path was found in the Phase-19 delta.

Comparing the accepted Phase-18 runtime to the target, Phase-19 production changes are confined to the player-domain rule boundary/canonicalization and active World Pack binding support. No `PlayerChangeSet` model/codec schema migration, database migration, new authoritative table or progression persistence is part of this Phase-19 target.

SCHEMA/PERSISTENCE: **PASS — NONE**

## 14. Test integrity

Phase-19 tests present at target include the P19-01..30 suite plus hardening H1..H7 and blocker-reproduction tests. They exercise real production `PlayerDomainEngine`, production canonical writer/snapshot code and production registry/provider contracts rather than a separate test-only encoder. P19-29/30 use independently allocated equivalent/different project drafts and production effect fingerprints, providing value-semantics coverage rather than object-identity comparison.

Repository searches found no Phase-19 production test disabled with `@Disabled` or `@Ignore`; such text search hits were audit documentation rather than test annotations. Exact CI invokes the unfiltered full `:app:testDebugUnitTest`, not a focused Phase-19-only task.

Coverage gap relevant to this audit: existing hardening tests prove that `Bound` cannot internally downgrade to `UnboundGeneric`, but they do not prove that a public caller cannot substitute a *different valid registered Bound World Pack* for the active World Pack selected by `CampaignSelectionManager`. That missing negative test corresponds to P19-C3-001.

TEST QUALITY: **FAIL** narrowly because the authority-substitution path is not covered by an active-selection-vs-context-binding adversarial test; otherwise the inspected Phase-19 test construction is substantive.

## 15. Phase 3–18 regression

Representative Phase-17/18 exactness/identity/reference tests remain in the full test suite and exact CI passes the whole JVM suite. Phase 19 does not alter their persistence/schema foundation. No evidence of a Phase 3–18 regression was found.

PHASE 3–18 REGRESSION: **PASS**

## 16. Exact CI

Verified directly from GitHub Actions, not from another auditor report:

- workflow: `Build & Release RPG OS ALPHA`
- run number: **482**
- run ID: **31806156168**
- exact head SHA: `11e0dcac8e128404524350bc53b9963124e9bbd7`
- status: **completed**
- conclusion: **success**

Job `build` completed/success. Direct job logs show checkout of exact target SHA and:

- `Validate project`: `gradle --no-daemon :app:tasks --stacktrace` — BUILD SUCCESSFUL
- full JVM: `gradle --no-daemon :app:testDebugUnitTest --stacktrace` — BUILD SUCCESSFUL; task `:app:testDebugUnitTest` executed
- signed release: `gradle --no-daemon :app:assembleRelease --stacktrace` — BUILD SUCCESSFUL including `validateSigningRelease`, `packageRelease`, `assembleRelease`
- release files prepared: APK, `.sha256`, `update.json`
- Actions artifact upload successful, artifact ID `9221387982`
- existing release assets updated with `gh release upload ... --clobber`
- release inspection showed APK, checksum and update JSON on tag `v1.2.0-alpha5-hybrid140`.

FULL JVM: **PASS (EXACT CI)** — no separate local JVM rerun was performed or claimed.

EXACT CI: **PASS**

## Final status matrix

PHASE 19 INTEGRITY REVALIDATION: **FAIL**

ROLE: **CHAT-3**

VALIDATED RUNTIME SHA: `11e0dcac8e128404524350bc53b9963124e9bbd7`

FRESH MASTER: `c6b05d5e3928496c3fe45967a888c07e7584b101` (pre-report)

RUNTIME CHANGED AFTER TARGET: **NO**

PROCEDURAL PURITY: **FAIL — post-target housekeeping/support deletions exist outside docs/audits**

PROCEDURAL IMPACT: **NON-BLOCKING / NONE FOUND FOR RUNTIME, TEST, BUILD OR RELEASE**

WORLD RULE AUTHORITY: **FAIL**

NULL/DOWNGRADE BYPASS: **PASS**

UNBOUND MODE EXPOSURE: **PASS**

REGISTRY INTEGRITY: **PASS**

PROVIDER STATE SECURITY: **PASS**

MUTABLE ENUM ATTACK: **PASS**

REQUEST IMMUTABILITY: **PASS**

CANONICAL COLLISION RESISTANCE: **PASS**

EFFECT SNAPSHOT INTEGRITY: **PASS**

CONTEXT FINGERPRINT: **PASS**

DECISION INTEGRITY: **PASS**

STALE DECISION RESISTANCE: **PASS**

PROPOSAL UID: **PASS**

FAILURE ATOMICITY: **PASS**

PHASE-18 ORDERING: **PASS**

AUTHORITY BOUNDARY: **FAIL — bound World Pack selection is caller-substitutable**

ZERO AUTHORITATIVE MUTATION: **PASS**

SCHEMA/PERSISTENCE: **PASS — NONE**

TEST QUALITY: **FAIL — no active-selection vs arbitrary-valid-Bound substitution negative test found**

PHASE 3–18 REGRESSION: **PASS**

FULL JVM: **PASS (EXACT CI); LOCAL RERUN NOT PERFORMED**

EXACT CI: **PASS**

NEW BLOCKERS:

- **P19-C3-001 BOUND_WORLD_PACK_AUTHORITY_SUBSTITUTION** — public context construction accepts an arbitrary valid `WorldRuleMode.Bound`; engine does not authenticate it against canonical active World Pack/campaign authority. A different registered provider can therefore be selected through a supported API.

REPORT PATH: `docs/audits/WORK-20260814-P19_CHAT3_FINAL_INTEGRITY_REVALIDATION_11E0DCAC.md`

FINAL CHAT-3 VERDICT: **FAIL**

Phase 19 is not globally accepted by this report. Phase 20 remains blocked. No production code or tests were modified.