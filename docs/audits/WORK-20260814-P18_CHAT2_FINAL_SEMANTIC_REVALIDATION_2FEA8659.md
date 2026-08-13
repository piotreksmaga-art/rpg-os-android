# WORK-20260814-P18 — CHAT-2 FINAL SEMANTIC REVALIDATION

ROLE: `CHAT-2 — Independent Semantic Auditor`

VALIDATED RUNTIME SHA: `2fea8659685232ef56947cfbbe87c55df1e44c0f`

FINAL VERDICT: **PHASE 18 SEMANTIC REVALIDATION: FAIL**

This is a report-only audit. No production/test/schema/workflow runtime files were modified.

## 1. Repository pin

Fresh master observed immediately before report creation was `b17dc91bdbe2f2af27fcb74c00709f6b64b1821b`.

`2fea8659685232ef56947cfbbe87c55df1e44c0f..master` was ahead by exactly one commit and the only changed file was:

- `docs/audits/WORK-20260813-P18_CHAT1_REFERENCE_SCOPE_COVERAGE_HARDENING_2FEA8659.md`

Therefore the later delta was report-only and the audit remained pinned to exact runtime `2fea8659685232ef56947cfbbe87c55df1e44c0f`.

`RUNTIME CHANGED AFTER TARGET: NO`.

## 2. Independent reference/identity reconstruction

The audit inspected the actual Phase-16 command model, Phase-17 ChangeSet model, Phase-18 extraction functions, nested event/ledger structures and the authoritative equipment definition model. Fields were classified semantically rather than by the spelling of `*Uid`.

Independent accounting agrees with the intended semantic taxonomy totals:

| Class | Meaning | Count |
|---|---|---:|
| A | PHASE18_EXISTENCE_SCOPE_REFERENCE | 70 |
| B | STRUCTURAL_TYPED_UID_ONLY | 38 |
| C | PHASE19_RULE_REFERENCE | 2 |
| D | LOCAL_IDENTITY | 15 |
| E | OTHER / explicit existing typed authority | 17 |
| **Total** | **reference/identity-bearing fields reviewed** | **142** |

A-class extraction coverage is complete: `70/70` semantic A fields have a Phase-18 extraction/validation route and `UNCLASSIFIED = 0`.

However, coverage completeness alone is insufficient. One B-class definition identity is incorrectly promoted into the campaign existence/scope validator, producing a release-blocking overvalidation defect described below.

Therefore:

- `TOTAL REVIEWED = 142`
- `PHASE18 EXISTENCE/SCOPE = 70`
- `A COVERED = 70`
- `UNCLASSIFIED = 0`
- `REFERENCE COVERAGE MATRIX = FAIL` because the extraction set is not semantically exact: it contains an invalid extra B-class campaign lookup.

## 3. Previous financial reference blocker — CLOSED

The previous financial reference coverage blocker is fixed.

### Command side

`TransferFundsCommandPayload` is extracted as:

- `fromAccountUid -> DomainRef(FINANCIAL_ACCOUNT, ...)`
- `toAccountUid -> DomainRef(FINANCIAL_ACCOUNT, ...)`
- `currencyUid -> DomainRef(CURRENCY, ...)`

These refs are validated before component resolution.

### Draft side

`FinancialChange` independently contributes:

- source account;
- destination account;
- currency.

`FinancialTransferLedgerIntentPayload` independently contributes the same three identities.

Thus a valid command-side identity does not authorize a substituted draft-side identity.

### Typed/campaign behavior

`PlayerResolutionContext.referenceStatus()` uses exact typed `DomainRef` equality plus campaign scope. An exact ref in the current campaign resolves. The same exact typed ref known only in another campaign yields `WRONG_CAMPAIGN_REFERENCE`. Same textual UID with another `kindUid` does not satisfy the lookup.

Focused production-path tests exercise:

- unknown source/destination/currency;
- wrong-campaign source/destination/currency;
- same UID under wrong kind;
- pre-component command rejection;
- component-introduced/substituted draft identities;
- typed rejection rather than structural failure;
- exact five-field finance/ledger term preservation.

The older success fixture is no longer permissive: valid finance resolution supplies `ACCOUNT:A`, `ACCOUNT:B` and `CUR:PLN` in `knownReferences`.

Financial reference gates: **PASS**.

## 4. Other A-class reference closure

Production extraction now covers the required existing identities, including:

- command precondition targets;
- TRAIN focus and resource refs;
- PRACTICE_SKILL existing skill;
- USE_TECHNIQUE existing technique plus optional target;
- item source/item/to-party references;
- ownership subject/to-party;
- financial accounts/currency;
- obligation counterparty/currency and existing obligation;
- project identity for work/requirement/milestone/lifecycle/completion/cancel;
- project requirement and milestone identities;
- successor project;
- project work/evidence/resource refs;
- draft subjects and existing item instances;
- draft financial refs;
- DevelopmentProject target/evidence refs;
- event actor/targets/domain-effect subject;
- ledger financial account/currency refs.

Command refs are checked before component invocation; draft refs are checked after typed resolution and before engine-owned PlayerChangeSet construction.

No A-class omission was found in the audited Phase-18 surface.

## 5. Release blocker `P18-SEM-OVERVALIDATION-EQUIPMENT-SLOT-01`

### Violated invariant

Phase 18 must distinguish campaign existence/scope references from definition/local/type identities and must not absorb Phase-19/world-definition legality into campaign reference validation.

`EquipmentChange.slotUid` identifies an `EquipmentSlotDefinition`. The canonical equipment model defines it as a World Pack definition:

`EquipmentSlotDefinition(slotUid, worldPackUid, key, displayName, ...)`.

It is therefore not, by itself, a campaign-scoped entity identity.

### Production path

`app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`

`draftReferences(draft)` contains:

```kotlin
is EquipmentChange -> {
    add(payload.subject)
    add(DomainRef("EQUIPMENT_SLOT", payload.slotUid))
    payload.itemInstanceUid?.let { add(DomainRef("ITEM_INSTANCE", it)) }
}
```

The resulting `DomainRef("EQUIPMENT_SLOT", slotUid)` is then passed to:

`validateReferences(context, ...) -> PlayerResolutionContext.referenceStatus(ref)`

which interprets absence/current-vs-other-campaign membership as `UNKNOWN_REFERENCE` / `WRONG_CAMPAIGN_REFERENCE`.

By contrast, the command-side `EquipItemCommandPayload.requestedSlotUid` and `UnequipSlotCommandPayload.requestedSlotUid` are intentionally not extracted as campaign existence refs. This makes the semantic boundary asymmetric.

### Minimal reproducer

Construct a legal typed EQUIP proposal where:

- command campaign = `C1`;
- actor/subject `PLAYER/P1` is known in C1;
- item instance is known in C1;
- `requestedSlotUid = SLOT:HAND` names an existing legal `EquipmentSlotDefinition` from the active World Pack;
- `knownReferences` intentionally contains no campaign-scoped `DomainRef("EQUIPMENT_SLOT", "SLOT:HAND")` because slot definitions are not campaign entities;
- component returns an `EquipmentChange(subject=P1, slotUid="SLOT:HAND", operation=EQUIP, itemInstanceUid=...)`.

Command-side reference validation passes because `requestedSlotUid` is not classified as A. After component resolution, `draftReferences()` invents a campaign-scoped `EQUIPMENT_SLOT/SLOT:HAND` lookup. The engine can therefore reject the otherwise structurally legal proposal with `UNKNOWN_REFERENCE` (or `WRONG_CAMPAIGN_REFERENCE` if the same definition is present under another campaign snapshot).

### Expected

The equipment slot UID remains a structural/world-definition identity at Phase 18. Phase 18 may preserve it losslessly and Phase-17 typed validation may validate its shape. World Pack definition availability/compatibility/legality belongs to the appropriate definition/rule/mechanics boundary, not to generic campaign entity existence lookup.

### Actual

Phase 18 applies campaign-aware existence/scope semantics to a World Pack definition UID.

### Architectural consequence

A legal command/proposal can be rejected based on how a caller materializes world definitions into `PlayerResolutionContext.knownReferences`. Worse, the same definition can be labeled `WRONG_CAMPAIGN_REFERENCE`, even though its canonical authority is `worldPackUid`, not campaign ownership. This conflates world-definition availability with campaign entity scope and prematurely leaks rule/definition semantics into Phase 18.

### Minimal correction scope

Phase-18-only extraction/classification correction: remove `EquipmentChange.slotUid` from campaign `draftReferences()` existence/scope extraction (or route it through a separate explicit definition-authority mechanism if architecture intentionally requires definition resolution). Do not implement Phase 19 rules. Add a regression proving a valid equipment proposal does not require a campaign-scoped slot-definition ref while subject/item instance reference validation remains intact.

No production fix was made by CHAT-2.

## 6. No-overvalidation review

The audit explicitly checked definition/kind/local identities rather than applying a `*Uid` heuristic.

Correctly not promoted on command side include new skill/technique definition IDs in LEARN commands, requested equipment slot UID, transaction type, method/kind/status IDs, command/change/event/ledger local identities and causal IDs.

The blocker above is the concrete exception: draft-side equipment `slotUid` is incorrectly promoted despite being a World Pack definition identity.

`NO OVERVALIDATION: FAIL`.

## 7. PlayerDomainEngine semantic boundary

The structural redesign remains sound in the audited areas:

- `PlayerDomainEngine.resolve()` is the canonical public command-to-proposal entry;
- resolution component/registry/draft are internal;
- component returns typed draft/rejection, not final canonical PlayerChangeSet;
- command is Phase-16 validated, canonicalized/detached and fingerprinted;
- campaign and actor context equality are checked;
- command refs are validated before component resolution;
- draft refs are independently validated after resolution;
- engine owns PlayerChangeSet construction/provenance/linkage;
- final Phase-17 PlayerChangeSet validation executes before `Resolved` escapes;
- unsupported component, duplicate registration and payload mismatch are structural failures;
- ordinary domain/reference rejection is a typed `PlayerResolutionOutcome.Rejected`.

No public command -> final PlayerChangeSet component bypass was found.

## 8. Determinism / trust model

Within the supported Phase-18 trust model:

- `PlayerResolutionContext` copies references into an unmodifiable set;
- dependency versions use deterministic sorted `TreeMap` storage;
- context fingerprint sorts known refs by campaign/kind/uid;
- entropy/evidence is explicit input;
- component retained state is constrained by the hierarchy-aware state validator;
- context exposes no DB/store/DAO/TurnTransaction/StatePatch writer capability.

Arbitrary malicious same-process JVM bytecode/global calls were not treated as a sandbox requirement.

`DETERMINISM: PASS`.

## 9. Phase-17 semantic locks

Static production/test inspection plus exact target CI retains the representative Phase-17 contracts:

- `ProjectProgressDelta(0)` legal; negative illegal;
- `ExactLongDelta` positive/negative nonzero legal, zero illegal, constructor/copy invariant retained;
- composite conflict identity historical delimiter collision remains fixed;
- `OwnedAssetRef(assetKindUid, assetUid)` remains full asset identity;
- financial/ledger exact terms and causal uniqueness remain Phase-17 validated;
- standalone ledger semantics remain retained;
- canonical legal serialization closes encode -> decode -> encode;
- equivalent proposals retain deterministic fingerprint;
- immutable collection defensive copies remain in PlayerChangeSet/domain value objects;
- Phase 18 constructs proposals and does not commit authoritative state.

No Phase-17 release regression was found.

## 10. Test quality

The new financial tests genuinely execute production `PlayerDomainEngine.resolve()` and exercise `PlayerResolutionContext.referenceStatus()` through command and draft extraction. Project/scalar focused suites also lock production extraction paths.

However, the suite does not adequately guard the B-class overvalidation boundary described above. A green matrix can prove all intended A references are covered while still missing a wrongly included B reference.

Therefore `TEST QUALITY: FAIL` for complete semantic release coverage, despite strong A-class regression coverage.

## 11. Full JVM / exact CI

No local repository checkout is available in the audit environment, so an independent local Gradle run was not performed.

`FULL JVM: NOT-RUN locally`.

Exact GitHub Actions evidence was independently verified:

- workflow: `Build & Release RPG OS ALPHA`
- run number: `421`
- run ID: `31739185657`
- head SHA: `2fea8659685232ef56947cfbbe87c55df1e44c0f`
- status: `completed`
- conclusion: `success`

Job steps independently returned SUCCESS for:

- Validate project;
- Run JVM unit tests;
- Build signed ALPHA APK;
- Prepare release files;
- Upload Actions artifact;
- Check existing release;
- Update existing GitHub Release assets;
- Show release information;
- overall build job.

The release-create step was skipped because the existing release path was used; the requested release asset update step succeeded.

`EXACT CI: PASS`.

## 12. Gate summary

| Gate | Result |
|---|---|
| Reference coverage matrix | FAIL — A coverage complete, one B-class overvalidation |
| Total reviewed | 142 |
| Phase18 existence/scope | 70 |
| Covered | 70 |
| Unclassified | 0 |
| Financial source account | PASS |
| Financial destination account | PASS |
| Financial currency | PASS |
| Unknown reference | PASS |
| Wrong campaign | PASS |
| Command-side reference closure | PASS |
| Draft-side A-reference closure | PASS |
| Other A-class references | PASS |
| No overvalidation | **FAIL** |
| Canonical PlayerDomainEngine entry | PASS |
| Resolution component boundary | PASS |
| Typed outcome model | PASS |
| Determinism | PASS |
| Zero authoritative mutation | PASS within supported Phase-18 capability model |
| Project zero-progress | PASS |
| ExactLongDelta | PASS |
| Composite target identity | PASS |
| Asset identity | PASS |
| Financial/ledger | PASS |
| Serialization | PASS |
| Fingerprint | PASS |
| Test quality | FAIL for release-complete semantic coverage |
| Phase 3–17 regression | PASS in inspected/exact-CI scope |
| Full JVM | NOT-RUN locally; exact-CI JVM SUCCESS |
| Exact CI | PASS |

## 13. Final CHAT-2 verdict

**PHASE 18 SEMANTIC REVALIDATION: FAIL**

New release blocker:

`P18-SEM-OVERVALIDATION-EQUIPMENT-SLOT-01`

The previous financial/reference coverage defect is closed. The remaining blocker is a semantic overvalidation error: a World Pack equipment-slot definition UID is incorrectly fed into campaign existence/scope resolution on the draft side.

Phase 18 is not globally accepted. Phase 19 remains blocked.