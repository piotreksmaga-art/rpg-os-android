# WORK-20260814-P18 — CHAT-1 Reference Classification Consistency Recovery

## Result

Phase 18 reference-classification consistency hardening is implemented at runtime SHA:

`b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7`

Old rejected runtime:

`2fea8659685232ef56947cfbbe87c55df1e44c0f`

Recovery-start master inspected by CHAT-1:

`8fa0991c410ebddd9de662309c522e2b2f08b7a9`

History remained forward-only. No reset, force push, history rewrite, audit-drop, or replacement of published history was used.

## Recovered full-JVM failure

Diagnostic failed SHA:

`7adb6e47f2e64f66e32bdd4b0bf124a41af50b40`

Canonical workflow #433 / run `31753028272` completed 569 tests with 11 failures. All 11 failures were in `PlayerDomainEngineReferenceClassificationTest` and shared the same exception:

`PlayerCommandStructuralException: INVALID_SHARE_BPS`

The exception originated in `PlayerCommandKindRegistry.validate(PlayerCommandRegistry.kt:70)` before Phase-18 reference validation. The failing fixture passed `OWNERSHIP_SHARE_SCALE` (`3_600_000_000L`, the internal exact ownership-share scale) into `TransferOwnershipCommandPayload.requestedShareBasisPoints`, whose structural command contract accepts only `1..10000` basis points.

Root-cause class: **test-fixture bug**.

The fixture was corrected forward-only to `10_000L` for a 100% transfer. Production ownership shares continue to use `OwnershipShare.full()` and the exact internal ownership scale. No production semantic weakening was made to recover the JVM suite.

Failing methods in #433 were:

- `p18Class10_existingFullOwnedAssetAccepts`
- `p18Class11_unknownAssetRejects`
- `p18Class12_sameAssetUidWrongKindDoesNotSatisfyLookup`
- `p18Class13_wrongCampaignAssetRejects`
- `p18Class14_existingFromOwnerAccepts`
- `p18Class15_unknownFromOwnerRejects`
- `p18Class16_existingToOwnerAccepts`
- `p18Class17_unknownToOwnerRejects`
- `p18Class18_componentCannotSubstituteGhostOwnershipIdentity`
- `p18Class19_commandValidIdentityDoesNotBlessUnrelatedDraftIdentity`
- `p18Class20_fullOwnershipNamespaceSurvivesProposal`

A later canonical run before the fixture correction reproduced the same 569 / 11 failure shape and the same `INVALID_SHARE_BPS` root cause, establishing that there was no second hidden production-classification failure behind #433.

## Equipment-slot semantic proof

`EquipmentChange.slotUid` is classified:

**B — STRUCTURAL_TYPED_UID_ONLY**

`EquipmentSlotDefinition` is a World Pack / definition identity. The slot definition layer, including World Pack ownership and equipment legality/compatibility, is distinct from campaign-owned entity existence.

Phase 18 therefore does **not** route `slotUid` through generic campaign-scoped `referenceStatus()`.

Final `draftReferences()` behavior for `EquipmentChange` extracts the subject and optional item instance only; it does not synthesize `DomainRef("EQUIPMENT_SLOT", slotUid)`.

Command-side `requestedSlotUid` and draft-side `slotUid` therefore follow the same classification.

Blocker `P18-SEM-OVERVALIDATION-EQUIPMENT-SLOT-01`: **CONFIRMED + FIXED**.

## Ownership semantic proof

Fields are classified independently:

- `ownershipRecordUid`: **D — LOCAL_IDENTITY**
- `OwnedAssetRef(assetKindUid, assetUid)`: **A — PHASE18_EXISTENCE_SCOPE_REFERENCE**
- `fromOwner(ownerKindUid, ownerUid)`: **A — PHASE18_EXISTENCE_SCOPE_REFERENCE**
- `toOwner(ownerKindUid, ownerUid)`: **A — PHASE18_EXISTENCE_SCOPE_REFERENCE**

`ownershipRecordUid` identifies the new/proposed successor ownership record and is not required to pre-exist in campaign authority.

Existing asset and owner identities are validated before proposal escape. Namespace identity is preserved injectively:

- asset: `(assetKindUid, assetUid)`
- owner: `(ownerKindUid, ownerUid)`

No flattening to `ASSET:uid` or untyped owner UID is performed.

Final `draftReferences()` extracts:

- `DomainRef(payload.asset.assetKindUid, payload.asset.assetUid)`
- `DomainRef(payload.fromOwner.ownerKindUid, payload.fromOwner.ownerUid)`
- `DomainRef(payload.toOwner.ownerKindUid, payload.toOwner.ownerUid)`

and does not extract `ownershipRecordUid`.

Blocker `P18-INT-REF-OWNERSHIP-01`: **PARTIALLY CONFIRMED + FIXED AT THE PROVEN A-CLASS FIELDS**.

The report classified the original blocker as partial because treating `ownershipRecordUid` as an existing A-class authority record would itself create overvalidation.

## Regression coverage

Equipment regressions P18-CLASS-01..06 remain production-path tests covering:

- no campaign slot requirement,
- no `UNKNOWN_REFERENCE` for an absent campaign slot ref,
- no `WRONG_CAMPAIGN_REFERENCE` for semantically irrelevant campaign placement of the slot definition identity,
- unchanged `slotUid` through proposal construction,
- no Phase-19 compatibility implementation in Phase 18,
- command/draft classification consistency.

Ownership coverage includes:

- explicit regression that a new `ownershipRecordUid` need not exist in `knownReferences`,
- full `OwnedAssetRef` acceptance,
- unknown asset rejection,
- wrong asset kind not satisfying lookup,
- wrong-campaign asset rejection,
- existing and unknown `fromOwner`,
- wrong-campaign `fromOwner`,
- existing and unknown `toOwner`,
- wrong-campaign `toOwner`,
- ghost asset substitution rejection,
- ghost owner substitution rejection,
- command-side valid identity not blessing an unrelated draft identity,
- preservation of full asset/owner namespace and ownership-record identity in the proposal.

The test component retained state contains only safe scalar identities and constructs the typed change inside `resolve()`, preserving the retained-state guard contract.

## Classification matrix

Final matrix:

| Class | Count |
|---|---:|
| A | 73 |
| B | 38 |
| C | 2 |
| D | 15 |
| E | 14 |
| **TOTAL** | **142** |

A covered: **73**

B/C/D/E accidentally routed through generic campaign lookup: **0**

Unclassified: **0**

Classification semantics and extraction behavior are aligned.

## Preserved reference families

The targeted recovery did not roll back or redesign previously hardened surfaces. Full canonical JVM success covers the existing suites for:

- finance source account,
- finance destination account,
- currency,
- finance command-side and draft-side validation,
- financial ledger extraction and matching,
- skill and technique references,
- project/requirement/milestone/successor references,
- obligation/evidence/resource/work references,
- unknown and wrong-campaign reference handling,
- command-side and draft-side closure.

## Phase-17 regression lock

No Phase-17 architecture was redesigned. The complete canonical JVM suite passed at the final runtime SHA, retaining coverage for the existing regression surface including:

- `ExactLongDelta`,
- `ProjectProgressDelta`,
- `OwnershipShare`,
- composite conflict identity,
- legacy/CK1 behavior,
- full `OwnedAssetRef` identity,
- financial/ledger invariants,
- serialization closure,
- fingerprint determinism,
- immutability,
- zero authoritative mutation,
- project zero-progress semantics.

## Test-disablement / temporary-file audit

No new `@Ignore`, `@Disabled`, or `excludeTestsMatching` mechanism was found during the recovery audit.

Temporary Phase-18 diagnostic workflows were removed through normal forward-only commits before runtime designation:

- `.github/workflows/p18-classification-diagnostics.yml`
- `.github/workflows/p18-classification-diagnostics-v2.yml`

At runtime SHA, `.github/workflows` contains only the canonical `build-alpha.yml` workflow. No diagnostic workflow is part of the runtime tree.

## Full JVM gate

Canonical workflow command:

`gradle --no-daemon :app:testDebugUnitTest --stacktrace`

Runtime SHA:

`b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7`

Result:

`BUILD SUCCESSFUL in 2m 11s`

The canonical green workflow does not emit a numeric JUnit test summary and does not upload JUnit XML on success. Therefore this report does not fabricate total/passed/skipped counts for the final SHA. The exact acceptance fact is the successful complete `:app:testDebugUnitTest` task with no focused-only execution and no disabled-test mechanism introduced by this recovery.

## Exact canonical Build & Release CI

Workflow: `Build & Release RPG OS ALPHA`

- run number: **441**
- run ID: **31755078554**
- head SHA: **b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7**
- status: **completed**
- conclusion: **success**

Required steps:

- Validate project — SUCCESS
- full JVM unit tests — SUCCESS
- signed ALPHA APK build — SUCCESS
- release-file preparation — SUCCESS
- Actions artifact upload — SUCCESS
- existing release asset update — SUCCESS
- overall workflow — SUCCESS

## Artifact

Actions artifact:

- ID: **9202516571**
- name: **RPG-OS-ALPHA-1.2.0-alpha5-hybrid140**
- artifact ZIP SHA-256: **48132835a7a121cf2215c3e70453f303cf1330cc06713cbff1c32b8648bb47df**

## Release

Existing release was updated successfully.

- release ID: **367217333**
- tag: **v1.2.0-alpha5-hybrid140**
- APK: **RPG-OS-ALPHA-1.2.0-alpha5-hybrid140.apk**
- APK asset ID: **513675452**
- APK SHA-256: **731557ad75089502d8747ad8ef4d3e50589dd94400c38383b86854e20de4b729**

The `.apk.sha256` and `update.json` release assets were also refreshed by the exact runtime workflow.

## Final CHAT-1 verdict

**PASS**

Phase 18 is implemented at runtime SHA `b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7` and is ready for fresh four-way independent revalidation.

Phase 19 remains blocked until fresh 4× PASS.
