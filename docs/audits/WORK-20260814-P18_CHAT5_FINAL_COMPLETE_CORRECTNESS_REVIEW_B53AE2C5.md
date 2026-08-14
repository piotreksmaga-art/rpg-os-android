# WORK-20260814-P18 — CHAT-5 FINAL COMPLETE ADVERSARIAL / CORRECTNESS REVIEW

**Role:** CHAT-5 — independent adversarial / robustness / correctness auditor  
**Validated runtime:** `b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7`  
**Audit type:** report-only; no production/test modification; no Phase 19 work  
**Verdict:** **PASS**

## 1. Exact target and fresh master

Fresh master immediately before report creation was `fbf8340edd40a7136855d9e5da0e3ff01379c4e7`.

`b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7..fbf8340edd40a7136855d9e5da0e3ff01379c4e7` is forward-only and contains exactly three later files, all under `docs/audits/`:

- `WORK-20260814-P18_CHAT1_REFERENCE_CLASSIFICATION_CONSISTENCY_b53ae2c5.md`
- `WORK-20260814-P18_CHAT3_FINAL_INTEGRITY_REVALIDATION_B53AE2C5.md`
- `WORK-20260814-P18_CHAT4_FINAL_ARCHITECTURE_REVALIDATION_B53AE2C5.md`

No production, test, workflow, build, schema, migration or runtime file changed after target.

**RUNTIME CHANGED AFTER TARGET: NO**

A local fresh clone/JVM rerun was attempted. The sandbox could not resolve `github.com`, so local FULL JVM is **NOT-RUN**. This is an environment limitation, not a target failure. Exact target CI is verified independently below.

## 2. Independent reference classification

I rebuilt the semantic classification from production command payloads/preconditions, `commandReferences`, `draftReferences`, change payloads, event and ledger intents, finance, projects, equipment, asset/ownership structures and proposal-local identities. I did not use the claimed counts as the enumeration premise.

| Class | Meaning | Count |
|---|---|---:|
| A | PHASE18_EXISTENCE_SCOPE_REFERENCE | 73 |
| B | STRUCTURAL_TYPED_UID_ONLY | 38 |
| C | PHASE19_RULE_REFERENCE | 2 |
| D | LOCAL_IDENTITY | 15 |
| E | OTHER / OTHER CONTRACT | 14 |
| **TOTAL** | reference/identity-bearing fields reviewed | **142** |

**A covered: 73/73. Unclassified: 0. Non-A accidentally campaign-looked-up: 0.**

The increase from the earlier 70-A layout is semantically explained by three draft-side existing identities in `OwnershipChange`: full `asset`, `fromOwner`, and `toOwner`. `ownershipRecordUid` remains local; equipment slot definition remains structural/rule-scoped; new `AssetChange.asset` creation identity is not generically looked up; proposal-local change/event/ledger/causal IDs remain local; `transactionTypeUid` remains structural; learning definition IDs are not falsely required to pre-exist.

No A identity was found hidden in B/E merely to avoid validation, and no non-A field was found accidentally routed through `referenceStatus`.

## 3. Equipment slot adversarial attack

**PASS.** `EQUIPMENT_SLOT` is not a Phase-18 reference kind. `EquipItemCommandPayload.requestedSlotUid` is structurally validated as a nonblank slot UID, but `commandReferences` extracts the existing item reference only. `EquipmentChange.slotUid` survives in the proposal and codec/conflict identity but `draftReferences` does not campaign-look it up.

Counterexample `SLOT:HAND` absent from current-campaign `knownReferences` therefore remains resolvable. A `SLOT:HAND` entry under another campaign also cannot produce `WRONG_CAMPAIGN_REFERENCE`, because slot definitions do not call `referenceStatus`. The focused classification suite exercises both cases on the real `PlayerDomainEngine` path and verifies unchanged slot preservation.

No remaining generic equipment-slot campaign lookup was found.

## 4. Ownership record local identity

**PASS.** `OwnershipChange.ownershipRecordUid` is validated structurally by the Phase-17 ownership change codec and participates in conflict identity as `OWNERSHIP:<recordUid>`, but is intentionally absent from Phase-18 `draftReferences`.

A new `OWNERSHIP:NEW` absent from `knownReferences` therefore remains legal as a successor/new ownership record identity and is preserved unchanged in the proposal. The final classification regression test exercises this through the production engine path.

## 5. Owned asset reference

**PASS.** Draft ownership now converts `OwnedAssetRef(assetKindUid, assetUid)` directly to `DomainRef(assetKindUid, assetUid)`. `referenceStatus` operates on the full typed `DomainRef` plus campaign, not raw asset UID.

Consequences independently verified from production semantics and focused tests:

- known full tuple in current campaign -> resolve;
- absent full tuple -> `UNKNOWN_REFERENCE`;
- exact full tuple existing only in another campaign -> `WRONG_CAMPAIGN_REFERENCE`;
- same `assetUid` under another `assetKindUid` does not satisfy lookup;
- delimiter-heavy, Unicode and spaced legal kind/UID values remain two independent components and are not flattened.

Full tuple identity is also preserved in Phase-17 conflict identity, serialization and fingerprinting.

## 6. From-owner and to-owner attacks

**PASS.** `OwnershipOwnerRef(ownerKindUid, ownerUid)` is converted directly to a full `DomainRef(ownerKindUid, ownerUid)` for both `fromOwner` and `toOwner`.

For each owner side:

- exact current-campaign typed owner -> resolve;
- unknown typed owner -> `UNKNOWN_REFERENCE`;
- exact typed owner only in another campaign -> `WRONG_CAMPAIGN_REFERENCE`;
- same textual owner UID under another owner kind cannot match.

No flattened owner string identity is used. The same `referenceStatus` implementation governs asset and both owners, so wrong-kind/wrong-campaign behavior is uniform and deterministic.

## 7. Draft substitution

**PASS.** Command-side transfer references are validated before component execution, but component output is independently scanned afterward. A valid command therefore does not bless arbitrary ownership identities introduced by a component.

Focused production-engine tests cover ghost asset and ghost `toOwner` substitution and a draft-only ghost asset despite a valid command identity. The production extractor equally includes `fromOwner`; replacing it with a ghost or wrong-campaign typed ref enters the same post-component `validateReferences` path and yields typed rejection before final proposal escape.

The architecture therefore closes asset/fromOwner/toOwner component substitution independently of command validation.

## 8. Ownership creation versus existing references

**PASS.** The target distinguishes creation/local identities from existing authoritative references:

- `ownershipRecordUid`: new/local, not pre-existence checked;
- `AssetChange.asset`: new asset creation identity, not generically campaign-looked-up;
- ownership transfer `asset`: existing typed campaign reference;
- ownership transfer `fromOwner`: existing typed campaign reference;
- ownership transfer `toOwner`: existing typed campaign reference;
- transfer command `subject` and `toParty`: existing typed command references.

This avoids both false `UNKNOWN_REFERENCE` on legal creation and under-validation of existing transfer identities.

## 9. Finance adversarial revalidation

**PASS.** Finance extraction remains unchanged and typed:

- `fromAccountUid` -> `FINANCIAL_ACCOUNT`;
- `toAccountUid` -> `FINANCIAL_ACCOUNT`;
- `currencyUid` -> `CURRENCY`.

The independent finance suite still exercises unknown source/destination/currency, wrong-campaign source/destination/currency, same textual UID under the wrong kind, command reference failure before component execution, component-introduced unknown draft account, component-substituted unknown destination and wrong-campaign currency, deterministic duplicate references, exact finance/ledger terms and causal linkage.

Phase-17 Hotfix2 remains intact: one financial change cannot be represented by two causal ledger intents; independent financial changes remain legal; mixed/non-financial/dangling causal refs fail as designed; standalone ledger remains legal by contract; exact five-term mismatch wins before duplicate causal guard where applicable.

## 10. Other reference families

**PASS.** Production extraction remains complete for the meaningful A families reviewed:

- practice skill -> existing `SKILL`;
- use technique -> existing `TECHNIQUE` plus target;
- obligation counterparty/currency and existing obligation;
- existing project;
- project requirement;
- project milestone;
- successor project;
- evidence refs;
- resource-use refs;
- source-work ref;
- change subjects/typed target identities;
- event actor/targets/domain-effect subject;
- financial ledger account/currency identities.

Learning definition identities, method/type/status IDs and other structural/rule/local identities are not incorrectly campaign-looked-up.

## 11. Cross-kind, cross-campaign and duplicate evidence

**PASS.** Lookup key is `CampaignScopedDomainRef(campaignUid, DomainRef(kindUid, uid))`. Same textual UID under another kind never matches. Exact typed identity known only in another campaign produces `WRONG_CAMPAIGN_REFERENCE`; current-campaign exact match wins deterministically even if the same typed identity is also represented in another campaign.

`knownReferences` is copied to a set, removing identical duplicates. Context fingerprinting sorts references by campaign/kind/uid and uses a `TreeMap` for dependency versions. Input ordering therefore cannot create first-write/last-write behavior or fingerprint instability.

## 12. Context immutability / aliasing

**PASS.** `PlayerResolutionContext.create` defensively copies known-reference input into an unmodifiable `LinkedHashSet` and dependency versions into an unmodifiable `TreeMap`. Existing engine tests attempt mutation of exposed collections and verify it fails. Registry source lists and draft/proposal nested collections are also defensively detached in the relevant constructors.

Caller-owned collection mutation after construction cannot retroactively change reference semantics or context fingerprint.

## 13. Component state / supported trust model

**PASS.** `PlayerResolutionComponentStateValidator` walks the concrete class hierarchy up to the resolution component base:

- non-final retained field -> `MUTABLE_RESOLUTION_COMPONENT_STATE`;
- unsupported object-valued retained field, including writer/store authority -> `UNSAFE_RESOLUTION_COMPONENT_STATE`;
- inherited writer fields are included in inspection;
- immutable primitive/boxed scalar/string/enum retained configuration is accepted.

Existing regression tests cover direct writer, inherited writer, mutable retained state and safe immutable inherited state. `PlayerResolutionContext` exposes no SQLite/DAO/repository/store/transaction/StatePatch writer, mutable authority, clock, random or UUID capability.

Arbitrary trusted-internal JVM bytecode can theoretically access unrelated static/global facilities; Phase 18 does not promise a JVM sandbox, so this remains non-blocking and outside the supported injected capability model.

## 14. Determinism

**PASS.** Same canonical command, context, sorted reference snapshot, dependency versions, explicit entropy evidence and component identity/version produce the same semantic proposal and fingerprint. Context reference order and dependency-map insertion order cannot change the context fingerprint. Exact command kind lookup avoids registration-order ambiguity for successful dispatch.

No HashMap/HashSet iteration was found entering canonical proposal encoding in a way that changes output semantics.

## 15. Failure atomicity / zero authoritative mutation

**PASS.** Resolution components receive no supported writable authority. Reference failures happen as typed rejections either before component execution (command refs) or before final ChangeSet assembly/escape (draft refs). Component exceptions are wrapped as structural engine failure; final ChangeSet validation failure occurs without commit authority.

Robolectric-backed authority fixtures verify unchanged authoritative state across successful resolution, typed rejection, reference rejection, component rejection, component exception and final ChangeSet validation failure. Unknown asset, wrong-campaign asset/owner and draft ownership reference failures use the same read-only rejection path and have no writer capability.

The SQLite fixtures are correctly annotated with `RobolectricTestRunner`; there is no Android-not-mocked false test path here.

## 16. Ownership share unit semantics

**PASS. Mandatory check completed.** The two scales are distinct and remain distinct:

- `TransferOwnershipCommandPayload.requestedShareBasisPoints`: **1..10,000**, where 100% = **10,000**;
- internal `OwnershipShare`: fixed exact scale `OWNERSHIP_SHARE_SCALE = 3,600,000,000`, where `OwnershipShare.full().units == 3,600,000,000`.

The command codec validates `requestedShareBasisPoints !in 1..10000` as `INVALID_SHARE_BPS`. Therefore `3_600_000_000` is structurally rejected as `INVALID_SHARE_BPS`, while `10_000` is structurally legal. The new ownership classification test uses `10_000L`, not internal share units. Internal ownership tests separately use `OwnershipShare.full()`/`ofUnits`.

No production/test path was found conflating basis points with internal fixed-scale ownership units.

## 17. Value objects / numeric correctness

**PASS.** `ExactLongDelta` rejects zero at factory and constructor/init level, so generated `copy(units=0)` also rejects; ±1 and Long.MIN/MAX are legal. `ProjectProgressDelta` permits zero and non-negative Long values, rejects negative values including copy bypass through init. `OwnershipShare` enforces `1..3_600_000_000` and copy cannot bypass its init invariant.

Financial amounts and command/change-set numeric values remain exact `Long`. Strict JSON numeric parsing rejects quoted numeric scalars, wrong scalar types and overflow. No Float/Double representation is used for exact money/delta/share semantics.

## 18. Composite identity

**PASS.** The historical composite hardening suite remains present and executes all composite families:

`STAT`, `RESOURCE`, `SKILL`, `TECHNIQUE`, `INNATE`, `INVENTORY`, `EQUIPMENT`, `ASSET`, `OWNED_ASSET`, `CONDITION`, `RUNTIME`.

It attacks delimiter-shifting aliases and legal values containing `:`, multiple delimiters, `|`, backslash, Unicode and spaces. Distinct legal tuples produce distinct conflict identities; identical tuples still conflict. Historical STAT and asset reproducers remain explicit regression tests.

## 19. Asset identity

**PASS.** `OwnedAssetRef(assetKindUid, assetUid)` remains a two-field value identity throughout:

- model;
- Phase-17 validation;
- Phase-18 ownership reference lookup;
- composite conflict key;
- JSON encode/decode;
- canonical roundtrip;
- fingerprint.

PROPERTY/BUSINESS same textual UID and boundary-shifting delimiter shapes remain distinguishable.

## 20. Serialization / fingerprint

**PASS.** Canonical `PlayerChangeSetCodec` validates before encoding, decodes typed payloads, rejects unknown/duplicate/wrong-shape fields, then revalidates. Representative legal finance, project zero-progress, asset/composite and engine-produced proposals retain `encode -> decode -> identical encode` and stable SHA-256 fingerprint semantics. Equipment slot and ownership full tuples are explicitly represented in codec fields, so meaningful changes to them change canonical serialized proposal/fingerprint.

Validator-internal reference snapshot/status/conflict machinery does not leak into serialized PlayerChangeSet fingerprint.

## 21. Test quality

**PASS.** The final classification test uses the real production `PlayerDomainEngine`, canonical `PlayerDomainChange.create` factory and correct `10_000L` command share basis-point value. It does not use private-constructor bypasses. Existing authority tests use Robolectric rather than Android SQLite in an unsupported plain-JVM fixture. Repository search found no `@Ignore`.

The focused tests assert independent expected behavior rather than deriving expected reference sets from the production extractor. The full CI job invokes the full Gradle unit-test target, not only focused Phase-18 tests.

Non-blocking coverage note: there is not a dedicated focused test for every Cartesian ownership substitution (for example ghost `fromOwner` and wrong-campaign draft substitutions), but the single shared production extraction/`referenceStatus` path is explicit and the focused suite covers each semantic branch across asset/owners. This is not a correctness blocker.

## 22. Phase 3–17 regression

**PASS.** Compare from the prior validated Phase-18 runtime to this target shows no deletion/modification of historical Phase-3–17 tests; only `PlayerDomainEngine.kt` and the new classification test are runtime/test changes. Critical suites for ProjectProgressDelta zero, ExactLongDelta invariants, OwnershipShare, financial ledger Hotfix2, composite identities, command numeric/JSON hardening, serialization and fingerprint remain present. Exact CI compiles/runs the full unit-test task.

## 23. Exact CI / artifact

**PASS.** GitHub Actions evidence:

- workflow run number: **#441**;
- run ID: **31755078554**;
- head SHA: `b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7`;
- status: **completed**;
- conclusion: **success**;
- JVM command: `gradle --no-daemon :app:testDebugUnitTest --stacktrace`;
- JVM task completed with `BUILD SUCCESSFUL`;
- signed release APK build step: success;
- artifact upload: success;
- existing release asset update: success.

Structured artifact metadata:

- artifact ID: **9202516571**;
- name: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140`;
- digest: `sha256:48132835a7a121cf2215c3e70453f303cf1330cc06713cbff1c32b8648bb47df`;
- workflow run ID: **31755078554**;
- workflow head SHA: `b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7`.

The artifact is therefore directly tied to the exact requested run and runtime SHA.

## 24. Final consolidated result

VALIDATED RUNTIME SHA: `b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7`  
FRESH MASTER: `fbf8340edd40a7136855d9e5da0e3ff01379c4e7`  
RUNTIME CHANGED AFTER TARGET: **NO**

REFERENCE MATRIX: **PASS**  
EQUIPMENT SLOT: **PASS**  
OWNERSHIP RECORD LOCAL IDENTITY: **PASS**  
OWNED ASSET REFERENCE: **PASS**  
FROM OWNER: **PASS**  
TO OWNER: **PASS**  
DRAFT SUBSTITUTION: **PASS**  
CROSS-KIND SAFETY: **PASS**  
CROSS-CAMPAIGN SAFETY: **PASS**  
FINANCIAL REFERENCES: **PASS**  
OTHER REFERENCE FAMILIES: **PASS**  
CONTEXT IMMUTABILITY: **PASS**  
COMPONENT STATE SECURITY: **PASS**  
READ-ONLY CAPABILITIES: **PASS**  
DETERMINISM: **PASS**  
FAILURE ATOMICITY: **PASS**  
ZERO AUTHORITATIVE MUTATION: **PASS**  
OWNERSHIP SHARE UNIT SEMANTICS: **PASS**  
PROJECT ZERO-PROGRESS: **PASS**  
EXACTLONGDELTA: **PASS**  
NUMERIC CORRECTNESS: **PASS**  
COMPOSITE IDENTITY: **PASS**  
ASSET IDENTITY: **PASS**  
FINANCIAL/LEDGER: **PASS**  
IMMUTABILITY/ALIASING: **PASS**  
SERIALIZATION: **PASS**  
FINGERPRINT: **PASS**  
WORLD-AGNOSTIC: **PASS**  
PHASE BOUNDARY: **PASS**  
TEST QUALITY: **PASS**  
PHASE 3–17 REGRESSION: **PASS**  
FULL JVM: **NOT-RUN** locally (sandbox DNS; exact CI full JVM passed)  
EXACT CI: **PASS**

### Non-blocking observations

1. Local fresh clone/JVM rerun could not start because the sandbox DNS could not resolve `github.com`; exact target GitHub Actions is used for the CI execution fact.
2. The retained-state validator is intentionally conservative and rejects complex retained objects even when a particular object might be immutable; this is fail-closed, not a bypass.
3. Trusted internal JVM code is not sandboxed against arbitrary static/global access; Phase 18's supported capability boundary does not promise such sandboxing.
4. Focused ownership tests do not enumerate every equivalent asset/fromOwner/toOwner substitution permutation, but production uses one explicit full-typed reference path and the tested semantic branches cover current/unknown/wrong-campaign/wrong-kind behavior.

### New correctness problems

**NONE.** No supported Phase-18 production-path blocker was independently confirmed.

## CHAT-5 verdict

**PASS** for exact runtime `b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7`.

This report does **not** globally accept Phase 18 and does **not** begin Phase 19.
