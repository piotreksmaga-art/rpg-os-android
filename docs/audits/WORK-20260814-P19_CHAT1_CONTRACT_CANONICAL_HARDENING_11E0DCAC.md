# WORK-20260814-P19 — CHAT-1 Contract / Canonical Identity Hardening Closure

## Scope

Role: CHAT-1 — implementation / recovery owner.

This is a report-only closure for the accepted implementation candidate. It does not globally accept Phase 19; it establishes the final CHAT-1 implementation runtime for fresh independent revalidation.

- Old rejected runtime: `48854043bdde9753830ffc20ff6a8e8a4d4299e1`
- Final Phase-19 implementation runtime: `11e0dcac8e128404524350bc53b9963124e9bbd7`
- Fresh master before this report: `11e0dcac8e128404524350bc53b9963124e9bbd7`
- History policy: forward-only; no reset, rebase, force push, or published-history rewrite.
- Phase 20: planning only; no Phase-20 runtime implementation is part of this closure.

## Exact CI evidence

Canonical workflow `Build & Release RPG OS ALPHA` was verified on exactly the final runtime SHA:

- run number: `482`
- run ID: `31806156168`
- head SHA: `11e0dcac8e128404524350bc53b9963124e9bbd7`
- status: `completed`
- conclusion: `success`

Verified successful stages:

- Validate project — SUCCESS
- Run JVM unit tests — SUCCESS
- Build signed ALPHA APK — SUCCESS
- Prepare release files — SUCCESS
- Upload Actions artifact — SUCCESS
- Check if release already exists — SUCCESS
- Update existing GitHub Release assets — SUCCESS
- Show release information — SUCCESS
- overall build job — SUCCESS

The existing release path was used; the create-release step was correctly skipped because the release already existed, and the existing release assets were updated successfully.

## Artifact evidence

- artifact ID: `9221387982`
- artifact name: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140`
- artifact digest: `sha256:3caf5f75f76534bd2e2d5e6c9a8c369089795d446f880cf8ffd6391ff64104f1`
- artifact workflow head SHA: `11e0dcac8e128404524350bc53b9963124e9bbd7`

The artifact digest above is the GitHub Actions artifact digest and must not be confused with the APK release-asset digest.

## Release evidence

Release `v1.2.0-alpha5-hybrid140` was verified after run #482:

- release ID: `367217333`
- release name: `RPG OS ALPHA 1.2.0-alpha5-hybrid140`
- tag: `v1.2.0-alpha5-hybrid140`
- release updated at: `2026-08-14T13:50:18Z`
- APK asset: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140.apk`
- APK asset ID: `514471807`
- APK state: `uploaded`
- APK size: `26176175` bytes
- APK digest: `sha256:87aaadc81801f5c418fd87df91c869c4de98d8d47ec589c76c0ffc7cb429f827`
- checksum sidecar asset: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140.apk.sha256`
- update metadata asset: `update.json`

Release update evidence is consistent with the successful `Update existing GitHub Release assets` step in exact CI #482.

## Runtime ancestry and tree cleanliness

Before this report was created, `master` was exactly identical to `11e0dcac8e128404524350bc53b9963124e9bbd7`. Therefore:

- the target runtime was an ancestor of master;
- no production, test, configuration, workflow, or other runtime change occurred after the target runtime and before this report;
- there were no intervening report-only/planning-only commits either.

The runtime tree was scanned for temporary Phase-19 implementation machinery:

- `.github/workflows/` contained only `build-alpha.yml`;
- the temporary Phase-19 diagnostic/patch/compile-fix workflows were absent;
- no `scripts/` directory existed at the final runtime;
- recursive tree search contained no `phase19-` or `phase19_` temporary files;
- no temporary audit marker or debug artifact attributable to the Phase-19 recovery remained.

Result: final runtime tree clean — PASS.

## Blocker closure

### P19-ARCH-NULL-BINDING-01 — PASS

The authoritative rule mode is explicit and typed. `PlayerResolutionContext` carries `worldRuleMode: WorldRuleMode` instead of nullable authoritative binding state.

Supported construction therefore cannot silently interpret omitted binding information as a generic no-rule campaign.

### P19-C5-001 ACTIVE_WORLD_NULL_BINDING_BYPASS — PASS

`WorldRuleMode.Bound(WorldPackRuleBinding)` is the public bound mode. Generic unbound mode is represented by Core-internal `UnboundGenericWorldRuleMode` and its Core-internal factory path.

`CampaignSelectionManager.activeWorldRuleMode()` derives the bound mode from the already-existing active World Pack selection plus the validated package manifest ID/version. It does not create a competing persisted source of truth.

For `Bound`, `evaluateWorldRules()` resolves the provider and does not skip `COMMAND_PRECHECK` or `DRAFT_EFFECT_CHECK`. Only the explicit Core-internal generic mode bypasses world-rule evaluation.

### P19-C5-002 MUTABLE_ENUM_PROVIDER_STATE_BYPASS — PASS

The blocker was reproduced before production modification and confirmed.

Final provider-state validation does not treat `field.type.isEnum` as proof of deep immutability. Enum retained state is recursively inspected. Non-static, non-synthetic enum instance fields must be final and restricted to primitive/scalar-safe or recursively safe enum state. Mutable enum instance state is rejected.

Safe stateless enum configuration remains accepted.

Duplicate provider handling is preserved and still fails closed with `DUPLICATE_WORLD_RULE_PROVIDER`.

### P19-C5-003 NULLABLE_SENTINEL_CANONICAL_COLLISION — PASS

Sentinel-based nullable encoding was removed from the hardened identity path.

The shared canonical writer emits explicit nullable structure:

- nullable field tag;
- field name;
- `NULL` presence variant, or
- `VALUE` presence variant followed by the actual length-prefixed value.

Therefore semantic null cannot alias empty string, `RPGOS-NULL`, or any other legal string.

### P19-C5-004 ALLOW/REJECT_DECISION_FINGERPRINT_COLLISION — PASS

Decision identity carries an explicit structural `DECISION_VARIANT` with `ALLOWED` or `REJECTED` before reason semantics are encoded.

Consequently an allowed decision cannot collide with a rejected decision even when the rejected `reasonUid` is the historical magic value `RPGOS-WORLD-RULE:ALLOW`.

### P19-C5-005 UNFRAMED_EFFECT_SNAPSHOT_COLLISION — PASS

Effect snapshot encoding is structurally framed.

The canonical effect snapshot independently frames:

- changes;
- event intents;
- ledger intents;
- warnings.

Every list has an explicit count and item boundaries. Every domain change is encoded as a typed record. Nested variable-length collections such as project evidence references, event target references, causal change UIDs, and ledger causal UIDs have their own collection framing.

All 13 current domain change families are explicitly discriminated and encoded:

1. StatChange
2. ResourceChange
3. SkillChange
4. TechniqueChange
5. InnateChange
6. InventoryChange
7. EquipmentChange
8. FinancialChange
9. AssetChange
10. OwnershipChange
11. ConditionChange
12. RuntimeChange
13. DevelopmentProjectChange

The project-evidence-versus-equipment alias class is therefore eliminated by the pre-hash structure rather than by SHA substitution.

### P19-C5-006 UNFRAMED_CONTEXT_FINGERPRINT_COLLISION — PASS

Context identity is structurally separated into typed sections/collections including campaign, actor, known references, dependency versions, entropy, and world-rule mode.

Known references are sorted deterministically and encoded as counted `CAMPAIGN_SCOPED_DOMAIN_REF` records. Dependency versions are held in deterministic key order and encoded as counted `DEPENDENCY_VERSION` records.

Two reference triples therefore cannot masquerade as three dependency pairs through scalar token repartitioning.

## Additional finding — proposal UID canonical preimage weakness — PASS

The requested static hardening audit found one additional identity weakness beyond the seven named blockers: proposal UID derivation still used legacy unframed token assembly.

That path was migrated to the same shared structural canonical writer under domain `PLAYER_DOMAIN_PROPOSAL`.

The hardened proposal preimage now structurally includes:

- canonical command encoding;
- context fingerprint;
- component kind/version section;
- counted world-rule decision fingerprint records.

No legacy unframed token assembly remains on the hardened proposal UID path.

## World rule mode and authority

Status: PASS.

Final model:

- `WorldRuleMode.Bound(WorldPackRuleBinding)` — public supported bound mode;
- `UnboundGenericWorldRuleMode` — Core-internal explicit generic/no-rule mode.

There is no nullable authoritative rule mode and no dual meaning for null.

`CampaignSelectionManager.activeWorldRuleMode()` validates the selected World Pack and returns `Bound` with the package ID/version. A normal app-bound campaign therefore cannot downgrade to generic unbound merely by omitting nullable binding data.

## Provider selection

Status: PASS.

- missing provider: fails closed with `WORLD_RULE_PROVIDER_MISSING`;
- bound provider version mismatch: fails closed with `WORLD_RULE_PROVIDER_VERSION_MISMATCH`;
- duplicate provider registration: fails closed with `DUPLICATE_WORLD_RULE_PROVIDER`;
- provider/worldPack UID/version consistency remains validated when constructing decision records.

## Provider state security

Status: PASS.

- direct mutable provider fields remain rejected;
- inherited retained state remains inspected;
- unsafe mutable collections remain rejected;
- mutable enum instance state is rejected;
- enum class identity alone is not considered proof of immutability;
- safe scalar/stateless enum retained configuration remains supported.

## Canonical format

Status: PASS.

- format identity: `RPGOS-WORLD-RULE-CANONICAL`
- canonical format version: `1`
- hash: SHA-256 retained
- fix class: canonical preimage serialization hardening, not a claimed SHA-256 cryptographic collision fix.

Structural properties:

- length-prefixed UTF-8 scalar tokens;
- explicit format/domain identity;
- explicit field tags;
- structural nullable `NULL`/`VALUE` discrimination;
- explicit section begin/end boundaries;
- explicit record begin/end boundaries;
- explicit list begin/end boundaries;
- explicit collection counts;
- explicit list-item boundaries/indexes;
- explicit decision variant discriminator;
- explicit effect-family record discriminator;
- deterministic sorted/canonicalized collections where semantics are order-insensitive.

## Context fingerprint

Status: PASS.

The context fingerprint includes the semantic world-rule mode. Therefore `Bound(W,V)` cannot share context identity with the explicit generic-unbound mode.

Campaign, actor, dependency versions, reference kind/campaign/UID, entropy evidence, and World Pack binding changes alter deterministic context identity as required.

## Effect snapshot

Status: PASS.

Structurally different snapshots no longer alias because of missing record/list/section framing. Event, ledger, warning, and domain-change sections are framed independently.

## Decision fingerprint

Status: PASS.

Decision identity includes:

- provider UID/version;
- bound World Pack UID/version;
- stage;
- request fingerprint;
- decision variant;
- rule UID;
- structural nullable reason;
- deterministic evidence list.

Allowed versus Rejected identity is structurally different independently of all legal reason strings.

## Request / decision replayability

Status: PASS.

Phase-19 request and decision identities are deterministic and semantic. Hardened identities are not based on object identity, object address, `toString()`, or JVM `hashCode()` representations.

Independent equivalent semantic objects reproduce the same identity; changes in meaningful inputs produce different identity in the H-suite dimensions.

## Stale-decision resistance

Status: PASS.

The hardened identity path distinguishes changes in:

- command identity/content;
- campaign;
- actor;
- provider version;
- World Pack version;
- rule stage;
- effect snapshot;
- evidence;
- Allowed versus Rejected outcome.

## Proposal UID determinism

Status: PASS.

Same semantic command/context/component/rule decisions yield the same proposal identity. Meaningful changes in hardened inputs alter the structural preimage.

## Phase-18 ordering lock

Status: PASS.

Final engine order remains:

1. canonical command validation;
2. Phase-18 command reference validation;
3. Phase-19 `COMMAND_PRECHECK`;
4. resolution component execution;
5. Phase-18 draft reference validation;
6. Phase-19 `DRAFT_EFFECT_CHECK`;
7. engine-owned `PlayerChangeSet` assembly and validation.

Unknown and wrong-campaign references therefore remain ahead of provider evaluation at the corresponding Phase-18 validation gate.

## Phase-18 semantic lock

Status: PASS.

No classification redesign occurred. Accepted Phase-18 semantics remain locked, including:

- `EquipmentChange.slotUid` — class B;
- `ownershipRecordUid` — class D;
- `OwnedAssetRef`, `fromOwner`, `toOwner` — class A;
- financial account/currency reference handling — class A;
- command and draft reference closure remains enforced before the corresponding later stage.

No generic UID heuristic was introduced.

## Phase-17 regression lock

Status: PASS through full JVM regression.

Phase-17 accepted behavior remains covered by the complete test suite, including exact numeric/domain delta semantics, composite conflict identity, owned-asset identity, financial/ledger exact matching, serialization/fingerprint/immutability, and proposal-only semantics.

## Separation / non-goals

Status: PASS.

Phase 19 did not absorb or implement:

- ProgressionEngine;
- diminishing returns;
- training/passive-growth progression runtime;
- Progression Ledger;
- Phase-22 invariants;
- TurnTransaction;
- COMMIT authority;
- persistence/database authority;
- database migrations.

World-rule legality remains separated from mechanics and persistence authority.

Core remains world-agnostic. No Naruto/Bleach production rule branch was introduced.

## Authoritative mutation

Status: PASS.

WorldRuleProvider remains a read-only transient legality extension point. It cannot return or commit authoritative state. Normal rejection and provider structural fault paths produce no authoritative mutation. The H7 regressions cover zero-authoritative-mutation behavior.

## Determinism and immutability

Status: PASS.

- provider registry construction remains deterministic;
- retained-state validation blocks mutable provider configuration classes covered by the contract;
- request/effect/evidence lists are defensively copied/frozen;
- context reference/dependency collections are defensively copied into deterministic unmodifiable containers;
- evidence identity is deterministic;
- no object-identity-dependent canonicalization is used on the hardened path.

## Schema / persistence impact

- PlayerChangeSet schema delta: NONE.
- PlayerChangeSet serialization schema delta: NONE attributable to this hardening.
- database delta: NONE.
- migration delta: NONE.
- ProgressionEngine delta: NONE.
- Phase-20 runtime delta: NONE.

## Test closure

Exact full JVM unit test execution in canonical CI #482: PASS.

The final runtime includes and executes:

- original Phase-19 `P19-01..30` suite — PASS;
- hardening H1 `P19-H1-01..10` — PASS;
- hardening H2 `P19-H2-01..04` — PASS;
- hardening H3 `P19-H3-01..05` — PASS;
- hardening H4 `P19-H4-01..06` — PASS;
- hardening H5 `P19-H5-01..09` — PASS;
- hardening H6 `P19-H6-01..09` — PASS;
- hardening H7 `P19-H7-01..13` — PASS;
- Phase 3–18 regression suite — PASS.

The H-suite explicitly covers bound/unbound authority, missing/version-mismatched providers, Phase-18-before-provider ordering, enum retained state, structural nulls, decision variants, effect framing, context framing, replayability, proposal UID determinism, zero authoritative mutation, Phase-18 classification regression, and Phase-17 regression.

## Final blocker matrix

| Blocker | Final status |
|---|---|
| `P19-ARCH-NULL-BINDING-01` | PASS — fixed |
| `P19-C5-001 ACTIVE_WORLD_NULL_BINDING_BYPASS` | PASS — fixed |
| `P19-C5-002 MUTABLE_ENUM_PROVIDER_STATE_BYPASS` | PASS — fixed |
| `P19-C5-003 NULLABLE_SENTINEL_CANONICAL_COLLISION` | PASS — fixed |
| `P19-C5-004 ALLOW/REJECT_DECISION_FINGERPRINT_COLLISION` | PASS — fixed |
| `P19-C5-005 UNFRAMED_EFFECT_SNAPSHOT_COLLISION` | PASS — fixed |
| `P19-C5-006 UNFRAMED_CONTEXT_FINGERPRINT_COLLISION` | PASS — fixed |
| Additional proposal-UID unframed canonical preimage weakness | PASS — fixed |

## CHAT-1 final verdict

**PASS — final implementation runtime established.**

Final Phase-19 runtime:

`11e0dcac8e128404524350bc53b9963124e9bbd7`

This CHAT-1 verdict does **not** constitute global Phase-19 acceptance. Phase 19 remains subject to fresh independent revalidation by the designated audit chats.

Phase 20 remains **PLANNING ONLY** and runtime implementation remains blocked until Phase 19 receives global acceptance.
