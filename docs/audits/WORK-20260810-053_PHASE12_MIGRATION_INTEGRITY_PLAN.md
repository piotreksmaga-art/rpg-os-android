# WORK-20260810-053 — Phase 12 Migration / Integrity Plan

Status: READ-ONLY RUNTIME / VALIDATION PLAN

Work ID: `WORK-20260810-053`
Role: `READ-ONLY PHASE 12 MIGRATION / INTEGRITY AUDITOR`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-11 runtime: `c87193a69136a6680102779e4f0cd3d90a616d41`
Exact accepted Phase-11 CI: GitHub Actions `#259`, run ID `31369089655`, `SUCCESS`
Fresh master at plan creation: `6c31afaab2d6d72f246655e07fe0cb2f74e88b8f`
Phase-12 implementation work item: `WORK-20260810-051`
Allowed write scope: this report only.

This document defines independent release gates for Phase 12 OwnershipRecord migration, persistence, integrity, concurrency and no-regression. It does not implement Phase 12 and must not repair WORK-051.

## 1. Canonical boundary

Hard invariant:

```text
Inventory possession != Equipment state != OwnershipRecord
```

Phase 10 owns item identity and possession. Phase 11 owns loadout/equipment state and equipment-driven modifier activation. Phase 12 must add durable ownership/right relations without reinterpreting either previous domain.

Therefore:

- inventory transfer alone must not create, close or transfer ownership;
- equip/unequip must not create, close or transfer ownership;
- `character_inventory` is not ownership evidence;
- legacy `CharacterPanel.equipment` is not ownership evidence;
- `item_name` is never asset identity;
- theft/loan/custody may intentionally make possession differ from ownership.

Any implementation violating this split is a Phase-12 integrity failure even if CRUD tests pass.

## 2. Architectural input from WORK-20260810-049

WORK-049 establishes OwnershipRecord as an explicit time-bounded relationship rather than a boolean or mutable `ownerUid` on an asset. Required semantics include stable ownership-record identity, stable owner identity, generic asset reference, ownership/right type, share, temporal bounds, source/provenance and history preservation.

The target semantic shape is equivalent to:

```text
OwnershipRecord {
  ownershipRecordUid
  campaignId
  ownerEntityUid
  assetKindUid
  assetUid
  ownershipTypeUid
  shareNumerator/shareDenominator or equivalent exact share
  validFrom
  validUntil?
  sourceEventUid?
  supersedesRecordUid?
  recordVersion
  provenance
}
```

Exact Kotlin/table names may differ, but the integrity properties may not.

## 3. Existing Phase-10 / Phase-11 integration boundary

Accepted Phase 10 provides stable unique item identity through `ItemInstance.itemInstanceUid` and character possession through `player_inventory_unique`. Same display name does not imply same item.

Accepted Phase 11 provides `player_equipment` and `player_equipment_slots`, references `itemInstanceUid`, and requires possession for equip. Phase-11 concurrency hardening demonstrated that application prechecks are insufficient when TOCTOU can violate invariants; authoritative DB/write-boundary enforcement is required where races are possible.

Phase 12 must therefore integrate with `ItemInstance` by stable UID for unique-item ownership while remaining asset-generic for future property/business/share/etc. domains.

## 4. Additive migration gate

Phase-12 migration must be additive.

PASS requires:

1. no destructive rewrite/drop/truncation of Phase 3–11 authoritative tables;
2. no rewriting legacy inventory/equipment rows into ownership rows merely because they exist;
3. Phase-12 tables/indexes/triggers/constraints created transactionally;
4. migration marker/version written only after successful schema creation;
5. a failed migration leaves the prior campaign usable at the last valid schema state;
6. rerunning migration is idempotent;
7. no automatic synthesis of ownership when explicit legacy ownership evidence does not exist.

If the implementation discovers actual legacy ownership evidence, it must document its exact table/columns/semantics and prove deterministic migration. Otherwise the correct migration result is zero synthesized OwnershipRecords.

## 5. Latest CurrentSchema routing

Direct invocation of `ensureV12()` or equivalent is insufficient.

Final validation must prove the production route:

```text
LocalGameStore / repository bootstrap
-> CurrentSchema.ensure()
-> latest schema
-> Phase 12
```

Required scenarios:

- fresh/new campaign bootstrap reaches Phase 12;
- accepted Phase-11 DB reopens and migrates once;
- old campaign restored from backup routes through latest CurrentSchema and reaches Phase 12;
- campaign switch ensures the selected campaign independently;
- repeated bootstrap/reopen/switch does not duplicate ownership state or migration markers.

## 6. Phase 3–11 preservation snapshot

Before/after Phase-12 migration semantic equality must be demonstrated for all prior authoritative domains, including at minimum:

- active campaign identity;
- ActivePlayerRef;
- dynamic stats/resources;
- modifiers and lifecycle state;
- Talent/Potential;
- Skills;
- Techniques;
- Phase-9 origins/innate/evolution/forms and requirement bindings;
- Phase-10 item definitions, ItemInstances, stacks, unique possession and legacy mappings/evidence;
- Phase-11 slot/rule definitions, equipped entries, occupied slots and modifier activation state;
- legacy rows not owned by Phase 12.

Phase 12 migration must not equip/unequip items, move inventory, change modifiers, infer ownership from possession, or mutate previous progression/state.

## 7. Stable ownershipRecord UID

Every persisted ownership relation/history segment requires stable identity independent of owner label, asset label, row position and current active state.

Release gates:

- UID nonblank and stable through reopen/backup/restore;
- duplicate UID in the same authoritative namespace rejected;
- replay/idempotent registration with identical immutable identity is either deterministic no-op or explicitly rejected without duplicate effects;
- UID never regenerated simply because `validUntil` changes during legal close/transfer unless the model uses a successor record.

Historical records must remain addressable after closure.

## 8. Owner reference integrity

Owner reference must use stable entity identity and campaign scope, not player display name.

Required gates:

- blank owner UID rejected;
- unresolved owner rejected unless contract explicitly supports external/unresolved owner refs;
- owner from campaign A cannot be used in campaign B;
- identical owner UID strings in two campaigns remain isolated;
- owner type must not be hardcoded to active player only; organizations/NPCs/future entities must remain representable by the generic contract.

If the current repository lacks a universal entity FK target, the implementation must still enforce campaign-scoped referential validity at the authoritative write boundary and document the strategy.

## 9. Asset reference integrity

Ownership must reference stable asset identity.

For unique items:

```text
assetKind = ITEM_INSTANCE (or equivalent)
assetUid = ItemInstance.itemInstanceUid
```

Required gates:

- missing ItemInstance fails loudly;
- item display name cannot substitute for UID;
- same-name ItemInstances remain separate assets;
- ItemInstance from another campaign cannot be referenced;
- deleting/invalidating a referenced asset must not silently retarget ownership.

For non-item assets, the reference scheme must be generic (`assetKindUid + assetUid` or equivalent), not a hardcoded item-only FK model that prevents future property/business/share assets.

## 10. Generic asset reference gate

Phase 12 must not implement Phase 14 Assets, but its ownership reference must be extensible enough to bind future stable asset identities without schema redesign per asset type.

PASS requires either:

- a generic typed entity-reference contract with validated namespace/kind; or
- an equivalently safe registry/reference mechanism.

FAIL examples:

- only `item_instance_uid` exists and no generic path is possible;
- arbitrary free-text asset names are accepted as identity;
- cross-kind UID collision can cause one asset to resolve as another.

## 11. Temporal history: validFrom / validUntil

Ownership history is append/history preserving.

Required semantics:

- `validFrom` required;
- `validUntil = NULL` or equivalent means currently open/active;
- closed historical rows are preserved, not deleted or rewritten into new owner identity;
- `validUntil >= validFrom` under the chosen time domain;
- transfer boundary is deterministic;
- query-at-time-T returns ownership valid at T according to one documented half-open/closed interval convention;
- reopen/restore preserves exact temporal values.

Implementation must document whether intervals are `[from, until)`, `[from, until]`, turn-index based, event-index based, or another deterministic convention. Ambiguous overlap semantics are a release blocker.

## 12. Source / provenance

Every authoritative ownership record/change requires nonblank provenance sufficient to explain why the right exists.

At minimum validate preservation of the implementation's equivalents of:

- source type;
- source/event/transaction identity where available;
- migration/legacy origin where applicable;
- actor/method/version metadata if part of the contract.

Migration must never invent historical source events for legacy data. If no ownership evidence exists, synthesize nothing.

## 13. Co-ownership and share constraints

The model must support co-ownership without allowing impossible totals.

Required gates:

- exact/deterministic share representation; avoid floating-point drift for legal conservation;
- share > 0;
- share <= full ownership;
- aggregate active share for the same asset/right domain cannot exceed 100% (or exact unit equivalent), unless the ownership type explicitly describes a non-conserving right category;
- partial transfer conserves total share;
- full transfer closes/reduces source share and opens destination share atomically;
- two valid co-owners may coexist when aggregate share is legal;
- a single-owner/full-ownership type cannot coexist with another active full owner over the same interval.

If ownershipType semantics can overlap independently (e.g. legal vs beneficial rights), constraints must partition by the explicit right/type domain rather than incorrectly summing unrelated rights.

## 14. Duplicate / overlapping record gates

The database/write boundary must prevent illegal duplicate or overlapping active records.

Test cases:

- duplicate identical active full record;
- same owner + same asset + overlapping interval;
- different owners + same asset + overlapping full shares;
- legal co-ownership shares within capacity;
- overlapping historical intervals that exceed aggregate share;
- adjacent intervals at the transfer boundary;
- closed historical record plus new active successor;
- duplicate replay of the same transfer/creation UID.

Application-level `SELECT then INSERT` alone is not sufficient if two writers can pass the precheck concurrently.

## 15. Authoritative DB/write boundary and concurrency release gates

Phase 11 proved TOCTOU must be treated as a first-class integrity problem. Final Phase-12 validation must inspect the actual authoritative DB/write boundary and not accept only service-layer prechecks.

Mandatory adversarial races:

### A -> B vs A -> C

Two concurrent transfers of the same source ownership/share must not both commit. Exactly one legal result may survive, or both may fail atomically; aggregate ownership must never exceed the source right.

### Share race

Two concurrent partial transfers that each individually fit the pre-read source share but jointly exceed it must be prevented at commit/write boundary.

### Temporal overlap

Concurrent creation of records whose intervals overlap illegally must be prevented even if both prechecks observe no conflict.

### Duplicate active ownership

Two writers attempting to create full active ownership for the same asset/right domain must not produce two active full owners.

### Stale transfer

A transfer based on a record already changed/closed by another writer must fail without creating destination ownership.

### Double-close

Two concurrent closes of the same active record must result in exactly one authoritative close transition; no corrupted end time, duplicate successor or silent success with divergent provenance.

### Transfer-vs-close

A transfer and independent close/revoke racing on the same source record must serialize to one legal outcome. No destination row may commit from a source record that was not valid at the transaction boundary.

Acceptable enforcement may use transactions, conditional `UPDATE ... WHERE ...`, uniqueness constraints, triggers, version/CAS columns or equivalent DB-authoritative guards. The key gate is that TOCTOU cannot violate the invariant.

## 16. Inventory independence tests

Mandatory tests:

1. A owns X; inventory transfer A -> B: ownership remains A unless explicit ownership command is part of the same intended transaction.
2. A owns X; B possesses X: valid divergent state.
3. B steals X via possession transfer: no automatic ownership transfer.
4. item leaves all inventories: ownership history/current owner remains unchanged unless explicitly changed.
5. ownership transfer A -> B: inventory may remain with A if possession is not part of the operation.
6. failed inventory transfer must not touch ownership.
7. failed ownership transfer must not touch inventory unless one higher-level atomic transaction explicitly couples them.

## 17. Equipment independence tests

Mandatory tests:

- equip owned item -> ownership unchanged;
- equip borrowed item -> owner remains lender;
- unequip -> ownership unchanged;
- equipment deletion/rebuild -> ownership unchanged;
- equipment modifier activation/deactivation -> ownership unchanged;
- ownership transfer while item is equipped must not silently rewrite equipment unless explicit domain rules say the resulting equipment state is illegal and a higher-level atomic operation handles it.

Phase 12 must not reuse `player_equipment` as ownership authority.

## 18. Campaign isolation / leakage

Required tests:

- same `ownershipRecordUid` string in campaign A and B according to documented namespace semantics;
- same owner UID string in A/B;
- same asset UID string in A/B;
- reads for campaign A never return B rows;
- transfer in A cannot close/create B ownership;
- restore/switch cannot leave a store bound to the previous campaign;
- generic asset resolver cannot resolve an asset from another campaign.

Cross-owner leakage and cross-asset leakage are explicit failure classes: a write scoped to owner/asset X must never mutate owner/asset Y due to insufficient predicates, shared caches, or UID-only queries missing campaign/asset kind.

## 19. Legacy ownership evidence preflight

Forbidden assumptions:

```text
character_inventory == ownership
CharacterPanel.equipment == ownership
item_name == asset identity
```

Final validation must search actual repository/bundled schema for explicit historical ownership evidence such as dedicated owner/asset/right fields, transfer ledgers or asset records with stable identity.

If no such evidence exists:

```text
legacy ownership synthesis count MUST equal 0
```

The migration may create schema, but not historical facts.

If evidence exists, final validation must require an exact dump/contract of the evidence and lossless deterministic mapping rules before PASS.

## 20. Scale / no truncation

Authoritative reads and migration must be tested above common UI/query limits.

Minimum fixtures:

- >1000 active ownership records;
- >1000 historical closed records;
- mixed item and non-item generic refs if supported by candidate;
- co-ownership sets;
- many records sharing same display labels but different UIDs;
- many records for multiple campaigns and owners.

PASS requires exact count/equality. `LIMIT 1000`, pagination bugs, UI projection limits or `single()` assumptions may not truncate authoritative state.

## 21. SQLite integrity gates

After migration and after mutation/race fixtures run:

```sql
PRAGMA integrity_check;
PRAGMA foreign_key_check;
```

Required result:

- `integrity_check` = `ok`;
- `foreign_key_check` returns zero violations where FKs exist;
- equivalent application-enforced generic references are separately validated where SQLite FK cannot target a polymorphic asset registry.

Indexes/constraints/triggers used for ownership invariants must also survive reopen.

## 22. Backup / restore preservation

Required scenarios:

1. Phase-12 DB with active + historical + co-owned records -> backup -> restore -> exact semantic equality.
2. Phase-11 backup -> restore under current app -> latest migration -> zero synthesized ownership absent evidence.
3. Backup/restore preserves ownershipRecord UIDs, owner refs, asset refs, shares, temporal bounds, versions and provenance exactly.
4. Restore does not reopen closed records or duplicate active records.
5. Restored campaign remains isolated from currently active campaign.

## 23. Reopen / idempotency matrix

For every authoritative mutation fixture, validate:

```text
write -> close DB -> reopen -> exact read equality
```

For schema/migration:

```text
ensure -> ensure -> close -> reopen -> ensure
```

must preserve:

- one schema marker/version state;
- no duplicate ownership rows;
- no regenerated UIDs;
- no altered provenance/share/time bounds;
- no side effects on Inventory/Equipment.

## 24. Required final-validation evidence from WORK-051

Final WORK-053 validation will inspect the exact WORK-051 result SHA and require evidence for:

- files changed and diff scope;
- migration/schema definitions;
- `CurrentSchema` production routing;
- Ownership model/store/write API;
- DB constraints/triggers/CAS strategy for concurrency invariants;
- tests for migration, reopen, restore, campaign switch, scale and races;
- `integrity_check` / `foreign_key_check` outputs;
- exact GitHub Actions run tied to the candidate SHA.

A green CI on a different SHA is not sufficient.

## 25. PASS / FAIL criteria

`PHASE 12 INTEGRITY VALIDATION: PASS` requires all of the following on the exact candidate SHA:

- additive latest-schema migration;
- bootstrap/reopen/restore/campaign-switch routing;
- idempotency;
- Phase 3–11 semantic preservation;
- stable ownershipRecord UID;
- owner/asset/campaign reference integrity;
- ItemInstance integration without item-name identity;
- generic asset-reference capability;
- temporal history preservation;
- provenance preservation;
- legal co-ownership/share conservation;
- no illegal duplicate/overlapping active ownership;
- no campaign/owner/asset leakage;
- no ownership inference from Inventory or Equipment;
- authoritative concurrency protection for the mandatory race matrix;
- >1000 record no-truncation coverage;
- SQLite integrity/foreign-key checks;
- backup/restore equality;
- exact candidate CI SUCCESS.

Any material invariant failure yields:

`PHASE 12 INTEGRITY VALIDATION: FAIL`

This worker will not implement fixes.

## 26. Stage-1 result

# PHASE 12 MIGRATION / INTEGRITY PLAN READY — WAITING FOR WORK-20260810-051 RESULT COMMIT

At plan creation, fresh master `6c31afaab2d6d72f246655e07fe0cb2f74e88b8f` contains the accepted Phase-11 runtime plus final Phase-11 revalidation reports, but no observed WORK-20260810-051 implementation commit. Final Phase-12 PASS/FAIL is therefore intentionally deferred until the exact WORK-051 result SHA and exact CI are available.
