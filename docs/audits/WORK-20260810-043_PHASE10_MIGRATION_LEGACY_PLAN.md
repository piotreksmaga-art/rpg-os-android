# WORK-20260810-043 — Phase 10 Migration / Legacy Integrity Plan

Status: READ-ONLY RUNTIME / VALIDATION PLAN

Work ID: `WORK-20260810-043`
Role: READ-ONLY PHASE 10 MIGRATION / LEGACY INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-9 runtime: `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`
Fresh master at plan creation: `03d8740de0f7b3f525ff9b9e47d80b5dfdaee920`
Phase 10 implementation work item: `WORK-20260810-041`
Allowed write scope: this report only.

This document defines release gates for Phase 10 Inventory persistence, legacy reconciliation, quantity/instance integrity, isolation, migration and no-regression. It does not implement Phase 10 or later phases.

## 1. Canonical source constraints

MASTER and Roadmap establish the following Phase-10 boundaries:

- Phase 10 is `Inventory model`.
- `Inventory != Equipment`.
- unique items require stable UID identity;
- stackable commodities may use quantity;
- location/possession does not imply legal ownership;
- stable UID is identity; display/name text is not identity;
- migration must preserve existing campaigns without creating competing legacy/new truths.

WORK-039 confirms that the current runtime only exposes a partial inventory surface and that Phase 10 must canonicalize item identity plus character inventory possession/holding state without implementing Phase 11 Equipment or Phase 12 OwnershipRecord.

## 2. Legacy `character_inventory` preflight

WORK-039 directly confirmed from runtime source that the currently consumed legacy table has at least:

- `entity_uid`;
- `item_name`.

The current CharacterPanel legacy query is effectively:

```sql
SELECT item_name
FROM character_inventory
WHERE entity_uid=?
ORDER BY item_name
```

and exposes those values through a presentation collection historically named `equipment`. That UI name is not evidence that legacy rows are equipped.

### PRAGMA requirement

A literal execution of `PRAGMA table_info(character_inventory)` against the packaged binary campaign DB could not be performed through the read-only GitHub text connector used by this worker: the campaign database is inside the binary `Naruto_Default.campaign.zip`, and the connector cannot execute SQLite or return binary contents as a queryable database. I therefore do **not** invent additional columns.

This is a hard preflight gate for WORK-041/final validation. The exact implementation candidate must provide a real fixture or test that executes and records:

```sql
PRAGMA table_info(character_inventory);
PRAGMA index_list(character_inventory);
PRAGMA foreign_key_list(character_inventory);
SELECT * FROM character_inventory ORDER BY rowid LIMIT 100;
```

The final WORK-043 validation will compare migration semantics to that actual dump. Until then, only `entity_uid` and `item_name` are treated as confirmed legacy columns.

## 3. Authority split required for Phase 10

Phase 10 must keep these concepts separate:

```text
ItemDefinition
!= inventory holding/entry
!= unique ItemInstance
!= Equipment/loadout state
!= legal OwnershipRecord
!= financial Asset/value
```

World Pack/content authority owns item definition identity and stack/unique semantics. Campaign state owns which item definition or unique instance is currently present in a character's inventory and, for stackable entries, the quantity.

Phase 10 must not infer ownership from possession and must not infer equipped state from inventory presence.

## 4. Legacy compatibility policy

Default rule:

```text
legacy row/evidence -> preserved losslessly
explicit mapping -> canonical typed identity
unmapped ambiguity -> unresolved/fail-loud for canonical interpretation
```

### Name-only legacy rows

`item_name` is legacy evidence, not canonical identity.

A name-only row must not automatically create or bind a World-Pack-owned `ItemDefinition` merely because a typed definition has the same display name/key.

Hard invariant:

```text
same name != same item identity
```

This applies both within one World Pack and across World Packs.

### Mixed legacy + typed state

If legacy and typed entries appear semantically similar:

- without explicit mapping: no silent merge and no silent typed-preferred suppression;
- with explicit mapping: exactly one canonical read representation;
- legacy bytes remain unchanged;
- mapping version/provenance are persisted or deterministically supplied;
- mapping ownership must match the target definition World Pack;
- missing/deleted mapping targets fail loudly.

## 5. Stackable and unique preservation gates

### Stackable

For a definition explicitly declared stackable:

- quantity must be preserved exactly;
- repeated migration must not double quantity;
- mapped legacy + typed state must not sum unless the reconciliation contract explicitly proves that they represent independent holdings;
- zero/negative/NaN/Infinity quantities must be rejected if the model uses numeric quantities;
- authoritative reads must never truncate large counts.

### Unique

For definitions requiring unique instances:

- every individual item must have stable `instanceUid` or equivalent unique identity;
- quantity semantics must not collapse two distinct instances into one stack;
- one active unique instance must not appear in multiple player inventories in the same campaign unless an explicit later shared-custody model allows it;
- same display name must never collapse unique instances;
- reopen and migration rerun preserve exact instance identity.

Legacy rows that cannot prove whether they are stackable or unique remain unresolved evidence until explicit mapping/policy supplies semantics.

## 6. Required migration fixtures

Final WORK-043 validation must include at least:

1. `legacy-only / name-only`: legacy row survives and is visible without synthetic canonical definition.
2. `typed-only`: typed ItemDefinition + inventory entry round-trips without legacy dependency.
3. `mixed same-looking, no mapping`: deterministic unresolved/fail-loud; no silent merge.
4. `mixed with mapping`: exactly one canonical representation while legacy bytes remain.
5. orphan mapping: canonical target absent -> fail-loud.
6. mapping target deleted after registration -> read/reconciliation fails loudly or exposes explicit invalid mapping state.
7. mappingVersion preserved and validated.
8. provenance nonblank and preserved through reopen.
9. World-Pack ownership mismatch -> registration/materialization rejected.
10. same label in World Pack A and B remains separate.
11. two players with identical legacy names remain isolated.
12. same player UID in two campaigns remains isolated.
13. stackable quantity exact preservation.
14. two independent stacks are not merged merely by display name.
15. unique instances with same definition/name stay separate.
16. one unique instance cannot be duplicated across active player inventory rows.
17. legacy unknown/custom names survive losslessly.
18. 1000+ legacy rows survive without truncation.
19. 1000+ typed entries survive without truncation.
20. close/reopen equality.

## 7. Production migration gates

Expected route:

```text
LocalGameStore.ensureCurrentSchema()
-> CurrentSchema.ensure()
-> latest schema
-> Phase 10 migration
```

Final validation must prove:

- an old Phase-9 campaign without Phase-10 tables opens through the production latest-schema path;
- Phase-10 marker/table creation is additive and idempotent;
- repeated current-schema ensure does not duplicate definitions, holdings, mappings, quantities or instances;
- normal bootstrap reaches Phase 10;
- restore reaches Phase 10;
- campaign switch reaches Phase 10;
- new campaign creation/bootstrap receives current schema;
- migration marker is written only after schema migration succeeds.

A test that invokes only `MigrationManager.ensureV10()` directly is insufficient for the production-routing gate.

## 8. Backup / restore gates

The final candidate must demonstrate:

- backup of a Phase-10 campaign preserves typed definitions/entries/instances/mappings plus untouched legacy rows;
- restore of an older Phase-9 campaign triggers latest-schema migration exactly once;
- restore does not invent mappings for name-only rows;
- restore does not change quantities or duplicate unique instances;
- pre-restore safety behavior remains compatible with existing campaign identity.

## 9. Phase 3–9 no-regression snapshot

Before/after Phase-10 migration compare semantic equality for:

- ActiveCampaignRef / campaign identity;
- ActivePlayerRef;
- PlayerStat base values;
- PlayerResource current values;
- stat/resource legacy aliases;
- modifiers and all Phase-5 target kinds;
- Talent/Potential profiles and legacy mappings/evidence;
- Skill definitions, PlayerSkill baseMastery/progress and reconciliation;
- Technique definitions, PlayerTechnique baseMastery/history/resource mappings and reconciliation;
- Phase-9 origins, innate ownership, evolution state/history, form unlock/active state;
- Phase-9.1 requirement bindings/versions;
- legacy `character_status_snapshot` and other pre-existing bytes.

Inventory migration must not create equipment modifiers, ownership records, financial transactions or progression changes.

## 10. Isolation gates

Every authoritative player inventory value must be explicitly scoped by campaign + character and definition/instance identity.

Required tests:

- player A changes do not affect B;
- campaign A changes do not affect B;
- World Pack A cannot register or map a definition owned by B;
- mapping from one campaign/player cannot reconcile another player's legacy row unless the contract explicitly defines a shared World-Pack-level evidence mapping and still preserves player-specific holdings;
- no active-player heuristic is allowed inside migration correctness: all proper legacy `entity_uid` rows must remain preserved.

## 11. Quantity integrity oracle

If the actual PRAGMA confirms a legacy quantity field, final validation must preserve it exactly, including edge cases supported by the old schema. If no legacy quantity field exists, Phase 10 must not manufacture quantity >1 from duplicate names without a documented deterministic policy.

Required scenarios:

- quantity 1;
- quantity >1;
- large finite quantity;
- repeated ensure preserves exact quantity;
- mixed legacy + canonical mapped entry never double-counts the same source evidence;
- mapping one legacy row twice remains exactly-once materialization.

If quantity type is integer in the real schema, migration must not silently convert it to approximate floating point semantics.

## 12. Unique instance integrity oracle

Required scenarios:

- create/save one unique instance -> reopen same UID;
- two unique instances of same ItemDefinition remain separate;
- duplicate instance UID fail-loud;
- same instance assigned to player A then attempted for player B -> fail-loud unless explicit transfer API exists;
- migration does not synthesize a unique instance from name-only evidence without explicit mapping/policy;
- deleting a mapped target instance/definition leaves a detectable invalid mapping, not a silent empty read.

## 13. No silent name merge

The final candidate must contain an explicit adversarial test:

```text
legacy: "Sword"
typed World Pack A: displayName="Sword"
typed World Pack B: displayName="Sword"
```

Expected without mapping:

- legacy remains unresolved;
- both typed definitions remain distinct;
- no automatic binding by name/key/casing/localization;
- canonical read does not silently choose one.

Case-only differences (`Sword`, `sword`, `SWORD`) likewise do not prove identity.

## 14. Large-set / truncation gates

Authoritative inventory APIs must return the full result set.

At minimum:

- 1005 definitions/entries for one player;
- 1005 legacy rows/evidence records where the fixture permits them;
- close/reopen preserves counts;
- ContextBuilder/CharacterPanel may remain bounded presentation views, but no presentation `LIMIT` may leak into canonical InventoryStore/repository reads.

## 15. Integrity / FK gates

After migration, mapping and bulk persistence:

```sql
PRAGMA integrity_check;
```

must return `ok`.

```sql
PRAGMA foreign_key_check;
```

must return no rows under the adopted FK policy.

Additionally test:

- missing ItemDefinition target;
- missing instance definition relationship;
- orphan legacy mapping target;
- cross-World-Pack mapping target;
- duplicate canonical key/UID according to chosen schema constraints.

## 16. Release matrix for WORK-041

- P10-01 actual legacy `PRAGMA table_info(character_inventory)` captured: REQUIRED / NOT YET VALIDATED
- P10-02 lossless legacy row preservation: REQUIRED
- P10-03 name-only row remains unresolved without mapping: REQUIRED
- P10-04 same-name auto-merge impossible: REQUIRED
- P10-05 explicit mapping exactly-once canonicalization: REQUIRED
- P10-06 mappingVersion/provenance preserved: REQUIRED
- P10-07 orphan/deleted target fail-loud: REQUIRED
- P10-08 World-Pack ownership mismatch rejected: REQUIRED
- P10-10 stackable quantity exactness: REQUIRED
- P10-11 unique instance identity integrity: REQUIRED
- P10-12 duplicate unique instance rejected: REQUIRED
- P10-13 player isolation: REQUIRED
- P10-14 campaign isolation: REQUIRED
- P10-15 reopen equality: REQUIRED
- P10-16 migration idempotency: REQUIRED
- P10-17 old campaign production routing reaches V10/latest: REQUIRED
- P10-18 restore routing reaches V10/latest: REQUIRED
- P10-19 campaign switch reaches V10/latest: REQUIRED
- P10-20 backup/restore preserves inventory + legacy bytes: REQUIRED
- P10-21 Phase 3–9 semantic snapshot unchanged: REQUIRED
- P10-22 1000+ typed entries no truncation: REQUIRED
- P10-23 1000+ legacy evidence no truncation where fixture supports it: REQUIRED
- P10-24 integrity_check clean: REQUIRED
- P10-25 foreign_key_check clean: REQUIRED
- P10-26 exact WORK-041 JVM/build/CI success: REQUIRED

## 17. Final validation procedure after WORK-041

When CHAT-1 publishes WORK-041, CHAT-3 must:

1. re-check current master and exact resultCommit;
2. inspect diff from accepted Phase-9 runtime/current baseline;
3. inspect actual ItemDefinition/InventoryEntry/ItemInstance/mapping schema and stores;
4. execute or verify the literal `PRAGMA table_info(character_inventory)` fixture before accepting migration assumptions;
5. compare legacy-only / typed-only / mixed behavior;
6. verify no name-based reconciliation;
7. verify stackable quantity and unique instance invariants;
8. verify mapping ownership/version/provenance and deleted/orphan behavior;
9. verify production latest-schema routing, backup/restore and campaign switch;
10. verify Phase 3–9 no-regression snapshot;
11. verify 1000+ entries and no authoritative truncation;
12. verify integrity/FK;
13. verify exact candidate JVM tests, signed build and CI;
14. update only this report with one exact verdict.

Final runtime verdict after WORK-041 must be exactly one of:

`PHASE 10 INTEGRITY VALIDATION: PASS`

or

`PHASE 10 INTEGRITY VALIDATION: FAIL`

## 18. Current checkpoint

At plan creation fresh master is `03d8740de0f7b3f525ff9b9e47d80b5dfdaee920`, a read-only Phase-10 semantic-oracle commit (`WORK-20260810-042`) layered on top of the accepted Phase-9 runtime/audit chain.

No `WORK-20260810-041` result commit is present in repository history at this checkpoint.

The canonical source documents place Inventory at Phase 10 and explicitly require stable UID identity, stackable quantity support, unique-item integrity, and separation from Equipment and Ownership. The existing legacy source proves `entity_uid` + `item_name` usage but does not, through the available read-only text connector, expose a literal executable PRAGMA dump of the packaged SQLite database. That exact dump remains a mandatory release gate and must be captured before final Phase-10 migration validation.

`PHASE 10 MIGRATION / LEGACY INTEGRITY PLAN READY — WAITING FOR WORK-20260810-041 RESULT COMMIT`
