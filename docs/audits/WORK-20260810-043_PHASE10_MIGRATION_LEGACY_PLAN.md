# WORK-20260810-043 — Phase 10 Migration / Legacy Integrity Plan

Status: READ-ONLY RUNTIME / FINAL VALIDATION

Work ID: `WORK-20260810-043`
Role: READ-ONLY PHASE 10 MIGRATION / LEGACY INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-9 runtime: `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`
Audited Phase-10 final candidate: `eb8bb64f8be566982c91f1062f319078899c1e47`
Implementation work item: `WORK-20260810-041`
Allowed write scope: this report only.

This report records final validation of Phase 10 Inventory persistence, legacy reconciliation, quantity/instance integrity, migration, production routing, isolation and no-regression. It does not implement Phase 10 or Phase 11.

## 1. Canonical Phase-10 boundary

The accepted contract remains:

`ItemDefinition != ItemInstance != Inventory possession != Equipment != OwnershipRecord`.

Phase 10 owns canonical item identity plus character-scoped possession state. It does not implement loadout/equipped authority and does not create legal OwnershipRecord state. Historical CharacterPanel naming of legacy inventory rows as `equipment` remains presentation debt only.

Stable UID is identity. Display name is evidence/presentation only. Same name does not imply same item.

## 2. Final runtime and diff scope

The exact audited runtime is:

`eb8bb64f8be566982c91f1062f319078899c1e47`

Fresh master at final validation points to this exact SHA.

Compared with the pre-implementation baseline `03d8740de0f7b3f525ff9b9e47d80b5dfdaee920`, the Phase-10 runtime adds:

- `InventoryModel.kt`;
- `InventoryStore.kt`;
- `Phase10Migration.kt`;
- Phase-10 production, persistence, legacy, scale, transfer and context tests;
- `CurrentSchema.ensure()` routing to V10;
- ContextBuilder consumption of reconciled inventory.

No Equipment or OwnershipRecord runtime tables are introduced.

## 3. Schema / migration validation

Migration ID:

`RPGOS-10.0-INVENTORY`

`MigrationManager.ensureV10()` first calls `ensureV9RequirementHotfix()`, preserving the full Phase 3 -> 9.1 chain, then transactionally creates only new Phase-10 tables/indexes and writes the V10 marker.

New authoritative tables:

- `item_definitions_v2`;
- `item_instances`;
- `player_inventory_stacks`;
- `player_inventory_unique`;
- `legacy_inventory_mappings`.

The migration is additive: no legacy `character_inventory` mutation occurs and no Phase 3-9.1 table is rewritten by V10.

Idempotency is covered by repeated `CurrentSchema.ensure()` and marker-count assertions: exactly one `RPGOS-10.0-INVENTORY` marker remains.

`CurrentSchema.ensure()` delegates to `ensureV10()`. Production bootstrap, restore and campaign switch route through the same current-schema path.

Result: PASS.

## 4. Real legacy `character_inventory` preflight

The final candidate contains a real bundled-asset preflight test: `InventoryLegacyAssetPreflightTest` extracts the actual `campaign.db` from `Naruto_Default.campaign.zip`, opens it with SQLite and executes:

- `PRAGMA table_info(character_inventory)`;
- `PRAGMA index_list(character_inventory)`;
- `PRAGMA foreign_key_list(character_inventory)`;
- the compatibility reader after `CurrentSchema.ensure()`.

The test verifies from the actual bundled schema that, when the table is present, `entity_uid` and `item_name` exist. It intentionally does not hardcode or invent any additional column contract.

The production compatibility reader itself executes `SELECT * FROM character_inventory WHERE entity_uid=?`, obtains the live column list, preserves every non-`entity_uid` field in `rawFields`, including BLOBs via deterministic hex encoding, and computes deterministic evidence identity from sorted raw field names/values rather than SQLite row order.

Therefore extra/custom legacy columns remain evidence even when Core does not know their semantics.

Result: PASS.

## 5. Legacy compatibility and reconciliation

Default behavior is conservative:

`legacy row -> unresolved evidence`.

No name-based canonicalization occurs.

`legacyEvidence()` returns deterministic `LegacyInventoryEvidence` containing:

- campaign ID;
- character UID;
- deterministic evidence UID;
- optional item name;
- identical-row count;
- complete raw field map.

Canonicalization requires explicit `LegacyInventoryMapping` with:

- campaign;
- character;
- legacy evidence UID;
- canonical ItemDefinition UID;
- optional explicit ItemInstance UID for unique items;
- World Pack UID;
- mappingVersion;
- provenance.

Mapping registration validates target definition ownership. Unique mappings require an explicit existing ItemInstance for the mapped definition. Missing/changed legacy evidence fails. Existing mapping identity is immutable: replay is accepted only when target, owner, version and provenance are exactly equal.

A deleted/missing canonical target fails on reconciliation through normal definition/instance lookup; it cannot silently become an empty canonical read.

Result: PASS.

## 6. No silent same-name merge

The runtime never reconciles by `item_name`, display name, key casing or row position.

A typed item with display name `Same` may coexist with unresolved legacy evidence named `Same`. The typed item remains typed authority while legacy evidence remains unresolved until an explicit mapping is registered.

Same display name across World Packs is explicitly tested and remains separate because identity is UID + World Pack ownership.

Result: PASS.

## 7. Duplicate identical legacy rows / quantity inference

Identical legacy rows are grouped only as evidence and record `rowCount`.

They are not converted into a stack quantity. `registerLegacyMappings()` requires `rowCount == 1`; therefore two identical name-only rows are an explicit ambiguity and mapping fails loudly.

For a single explicitly mapped legacy stack row, a real legacy `quantity` field is parsed only when present and must be a positive integer. If that field is absent, the single mapped row represents one possession unit; duplicate names are never summed into quantity.

This preserves the required rule that duplicate same-name legacy rows do not imply quantity.

Result: PASS.

## 8. Stackable quantity integrity

`ItemStoragePolicy.STACKABLE` uses `Long` quantity.

Hard invariants:

- quantity must be > 0;
- zero add/remove amount is rejected;
- negative amount is rejected;
- addition uses `Math.addExact`, so overflow fails loudly;
- remove-more-than-possessed fails before mutation;
- removing the exact remaining quantity deletes the stack row;
- stack transfer is transactionally remove + add;
- target overflow is checked before source mutation.

Tests cover quantity addition, exact removal-to-zero semantics, negative/zero rejection, `Long.MAX_VALUE` and overflow, remove-more-than-possessed and failed-transfer atomicity.

Result: PASS.

## 9. Unique item / instance integrity

`ItemStoragePolicy.UNIQUE_INSTANCE` is represented by:

`ItemDefinition -> ItemInstance(itemInstanceUid) -> player_inventory_unique`.

A unique definition cannot be added as a stack. A unique instance can have only one active holder within a campaign because `player_inventory_unique` uses a campaign+instance uniqueness constraint and the store explicitly checks `uniqueHolder()`.

Unique transfer updates the existing possession row; it does not delete/recreate the instance identity. Stable `itemInstanceUid` is preserved.

Legacy unique canonicalization requires an explicit existing instance UID. Name-only evidence cannot synthesize a unique instance automatically.

Result: PASS.

## 10. Transfer atomicity and Phase-11/12 boundary

Stack transfer is transactional and preserves total quantity when successful. A failed source-quantity check rolls back the target side.

Unique transfer is a transactional holder update preserving `itemInstanceUid`.

The Phase-10 tests also assert that inventory operations do not create tables/state named `player_equipment` or `ownership_records_v2`.

Inventory transfer therefore means possession transfer only. It does not create Equipment/loadout state or legal OwnershipRecord state.

Result: PASS.

## 11. Isolation / ownership validation

Validated:

- campaign isolation;
- player isolation;
- World Pack definition ownership;
- same display name across two World Packs;
- duplicate definition UID rejection;
- duplicate `(worldPackUid,itemKey)` rejection;
- unique ItemInstance identity scoped by campaign;
- one unique instance cannot be held by two players in one campaign;
- mapping World Pack mismatch rejected.

No migration correctness depends on ActivePlayer heuristics; legacy compatibility is scoped by the requested `entity_uid`.

Result: PASS.

## 12. Authoritative read / ContextBuilder / scale

`InventoryStore.typedStacks()`, `typedUnique()`, `legacyEvidence()` and `reconciled()` do not use presentation `LIMIT 50/60` truncation.

`InventoryLegacyScaleTest` persists 1005 distinct unresolved legacy rows for one player and verifies all 1005 remain visible and unresolved while another player's row remains isolated.

`InventoryPersistenceTest` persists 1005 typed stack definitions/values and verifies all 1005 after reopen.

`InventoryContextBuilderTest` creates 1001 typed entries plus one unresolved legacy evidence row and verifies `ContextBuilder.playerInventory.size == 1002` before any later presentation budgeting. The final fixture correctly uses canonical `active_player_ref(campaign_id,player_uid,updated_at)`.

The earlier CI #237 fixture failure referenced the removed/nonexistent `active_player_ref.source` column and is not a runtime Inventory failure. The final exact SHA contains the corrected fixture while preserving the 1002-record assertion.

Result: PASS.

## 13. Backup / restore / reopen / campaign switch

`InventoryBackupRestoreTest` verifies backup and restore preserve together:

- typed stack quantity;
- explicit legacy mapping;
- mappingVersion;
- mapping provenance;
- original `character_inventory` bytes/values.

`Phase10ProductionRoutingTest` verifies:

- bundled bootstrap reaches V10;
- a V9 campaign selected by campaign switch reaches V10;
- restoring a V9 backup reaches V10.

Reopen tests verify typed inventory state and counts remain stable.

Result: PASS.

## 14. Phase 3-9.1 no-regression

The V10 migration itself only creates Phase-10 tables/indexes/marker after first ensuring V9.1. It contains no UPDATE/DELETE against earlier authoritative domains.

The exact CI executes the complete JVM regression suite, including the established Phase 3-9.1 persistence, migration, reconciliation, modifiers, Talent/Potential, Skill, Technique and Phase-9 requirement/state tests.

No Phase-10 runtime path writes:

- ActivePlayerRef;
- PlayerStat baseValue;
- PlayerResource currentValue;
- modifiers;
- Talent/Potential;
- Skill baseMastery;
- Technique baseMastery/history;
- innate/evolution/form state;
- requirement bindings.

Result: PASS.

## 15. Database integrity / FK

After V10 migration and a 1005-entry typed persistence fixture:

`PRAGMA integrity_check` returns `ok`.

`PRAGMA foreign_key_check` returns no rows.

Schema FKs bind instances to ItemDefinition, stack holdings to ItemDefinition, unique possession to campaign-scoped ItemInstance, and mappings to canonical definitions/instances.

Result: PASS.

## 16. Final release matrix

- P10-01 real bundled legacy PRAGMA preflight: PASS
- P10-02 lossless legacy preservation: PASS
- P10-03 name-only unresolved without mapping: PASS
- P10-04 no silent same-name merge: PASS
- P10-05 explicit mapping exactly-one canonical projection: PASS
- P10-06 mappingVersion/provenance preservation: PASS
- P10-07 missing/deleted/orphan target fail-loud: PASS
- P10-08 World-Pack ownership mismatch rejected: PASS
- P10-09 duplicate identical legacy rows remain ambiguous: PASS
- P10-10 no automatic duplicate-row quantity inference: PASS
- P10-11 positive quantity contract: PASS
- P10-12 zero/negative rejection: PASS
- P10-13 overflow rejection: PASS
- P10-14 remove-more-than-possessed atomic rejection: PASS
- P10-15 stack transfer atomicity: PASS
- P10-16 unique transfer preserves stable instance UID: PASS
- P10-17 unique instance single-holder integrity: PASS
- P10-18 campaign/player/World-Pack isolation: PASS
- P10-19 reopen: PASS
- P10-20 migration idempotency: PASS
- P10-21 bootstrap V10 routing: PASS
- P10-22 restore V10 routing: PASS
- P10-23 campaign switch V10 routing: PASS
- P10-24 backup/restore typed+legacy preservation: PASS
- P10-25 Phase 3-9.1 no-regression: PASS
- P10-26 >1000 typed no truncation: PASS
- P10-27 >1000 unresolved legacy no truncation: PASS
- P10-28 ContextBuilder 1001 typed + 1 unresolved = 1002: PASS
- P10-29 Inventory != Equipment: PASS
- P10-30 Inventory != OwnershipRecord: PASS
- P10-31 `PRAGMA integrity_check = ok`: PASS
- P10-32 `PRAGMA foreign_key_check` empty: PASS
- P10-33 exact candidate CI: PASS

## 17. Exact CI evidence

Exact audited SHA:

`eb8bb64f8be566982c91f1062f319078899c1e47`

GitHub Actions:

- run number: `#241`;
- run ID: `31358857064`;
- conclusion: `SUCCESS`.

Successful steps include:

- Validate project;
- Run JVM unit tests;
- Build signed ALPHA APK;
- artifact/release update steps.

The previous #237 failure is superseded and does not apply to this final SHA.

## 18. Final verdict

`PHASE 10 INTEGRITY VALIDATION: PASS`
