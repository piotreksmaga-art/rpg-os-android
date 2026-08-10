# WORK-20260810-048 — Phase 11 Migration / Integrity Revalidation

Status: FINAL READ-ONLY HOTFIX REVALIDATION

Work ID: `WORK-20260810-048`
Role: READ-ONLY MIGRATION / INTEGRITY REVALIDATOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase 10 runtime: `eb8bb64f8be566982c91f1062f319078899c1e47`
Previous Phase 11 candidate: `c96136964e4adb7144eee42b2b8680f153a839f2`
Audited hotfix candidate: `c87193a69136a6680102779e4f0cd3d90a616d41`
Exact CI: GitHub Actions `#259`, run ID `31369089655`, conclusion `SUCCESS`.
Allowed write scope: this report only.

This report supersedes the earlier Phase-11 integrity PASS for `c961369...` and revalidates only the concurrency hotfix candidate `c87193a...`. No runtime code was changed by CHAT-3 and Phase 12 was not started.

## 1. Fresh runtime / diff scope

Fresh `master` during revalidation points exactly to:

`c87193a69136a6680102779e4f0cd3d90a616d41`

Compared with `c961369...`, runtime changes are narrowly scoped to Phase-11 migration/write-boundary hardening plus concurrency regression tests. The accepted Equipment model/store semantics remain unchanged.

## 2. V11 migration and latest-schema routing — PASS

`RPGOS-11.0-EQUIPMENT` remains the Phase-11 migration marker and `ensureV11()` still delegates to `ensureV10()` first, preserving the complete earlier migration chain.

`CurrentSchema.ensure()` still routes to V11/latest. Existing production routing contracts for bootstrap, reopen, restore and campaign switch remain covered by the full regression suite.

The hotfix is non-destructive: it does not require reinstall, a new campaign, data reset, or a new migration version merely to repair guards.

## 3. Already-migrated V11 database hotfix refresh — PASS

This was a critical revalidation gate.

`ensureV11()` now explicitly executes `DROP TRIGGER IF EXISTS` followed by `CREATE TRIGGER` for the authoritative Equipment guards on every schema ensure.

Therefore a database that already contains the `RPGOS-11.0-EQUIPMENT` marker still receives the corrected trigger definitions on the next normal `CurrentSchema.ensure()` / `ensureV11()` call.

The refresh covers:

- `trg_equipment_possession_guard`;
- `trg_equipment_rule_exclusive_guard`;
- `trg_equipment_slot_parent_scope_guard`;
- `trg_equipment_slot_capacity_guard`;
- `trg_equipment_slot_exclusive_guard`;
- `trg_equipped_instance_inventory_delete_guard`;
- `trg_equipped_instance_inventory_transfer_guard`.

This preserves migration idempotency while repairing already-migrated V11 databases.

## 4. EQ-RACE-01 — possession/equip TOCTOU — PASS

Forbidden committed state:

```text
Inventory holder = B
Equipment holder = A
itemInstanceUid = X
```

is now prevented at the authoritative SQLite write boundary.

`trg_equipment_possession_guard` runs `BEFORE INSERT ON player_equipment` and requires that the exact `(campaign_id, character_uid, item_instance_uid)` still exists in `player_inventory_unique` at write time.

This closes the stale-precheck interleaving:

1. A appears to possess X during application pre-read;
2. X is transferred A -> B;
3. stale equip insert for A attempts to commit;
4. DB trigger aborts the insert;
5. no stale Equipment row survives.

The complementary delete/update guards still prevent transfer/remove of an instance that is already equipped until explicit unequip.

Both relevant race directions therefore converge to a consistent committed state:

- transfer wins first -> stale equip cannot commit;
- equip wins first -> transfer/remove cannot commit until unequip.

## 5. EQ-RACE-02 — slot capacity TOCTOU — PASS

`trg_equipment_slot_capacity_guard` enforces slot capacity inside the serialized SQLite write transaction when each `player_equipment_slots` row is inserted.

For capacity=1, a second committed occupant cannot survive even if two application-level prechecks both previously observed the slot as free.

The regression fixture directly simulates a stale/manual second writer after the first winner has committed and confirms the second transaction aborts while occupancy remains exactly one.

This is stronger than sequential application validation because the invariant is checked at the authoritative DB boundary.

## 6. Exclusive/conflict-group race — PASS

The hotfix adds DB-level guards for both rule-level and slot-level exclusive groups:

- `trg_equipment_rule_exclusive_guard`;
- `trg_equipment_slot_exclusive_guard`.

These checks compare the incoming Equipment state with already committed Equipment in the same campaign, character and loadout and reject a conflicting commit.

Conflict resolution remains fail-loud; there is no silent replacement.

## 7. Multi-slot atomicity / rollback — PASS

Canonical `EquipmentStore.equip()` still writes:

- one `player_equipment` parent row;
- all required `player_equipment_slots` rows;
- Phase-5 modifier-source activation;

inside one transaction.

The new slot-level DB guards can abort any invalid slot binding during that transaction. SQLite rollback prevents a partial multi-slot state from surviving.

The concurrency regression test confirms a failed stale capacity write does not leave a second Equipment row or partial slot binding.

## 8. Parent scope / manual DB second occupancy — PASS

`trg_equipment_slot_parent_scope_guard` rejects a slot-binding insert whose `(campaign_id, character_uid, equipment_entry_uid)` does not resolve to the matching parent Equipment row.

Together with capacity/exclusive guards, a direct SQL caller cannot legally bypass the canonical EquipmentStore and commit a second occupant or cross-player slot binding merely by inserting rows manually.

## 9. Exact ItemInstance / campaign / player integrity — PASS

The original Phase-11 integrity contract remains intact:

- Equipment binds exact Phase-10 `ItemInstance` identity;
- wrong/unpossessed instance fails;
- stackable commodity receives no synthetic Equipment identity;
- same definition / different instances remain separate;
- player/campaign scope remains explicit;
- World-Pack ownership remains validated for item/rule/slot relationships.

The new possession trigger strengthens this contract without changing its semantics.

## 10. Modifier lifecycle / no-retrogression — PASS

No second resolver or Equipment-specific modifier engine was introduced.

Phase 11 still uses existing Phase-5 `ModifierLifecycle.EQUIPMENT` and exact `itemInstanceUid` source identity.

Equip/unequip continue to affect only derived projections. They do not rewrite:

- `PlayerStat.baseValue`;
- `PlayerResource.currentValue`;
- `PlayerSkill.baseMastery`;
- `PlayerTechnique.baseMastery`;
- Talent;
- Potential.

The earlier exact resource regression remains in the full suite:

```text
PlayerResource.currentValue = 100
before equip = 100
during equip = 100
after unequip = 100
```

while derived maximum/regeneration may change.

## 11. Legacy / Ownership boundaries — PASS

Hotfix changes do not introduce any new inference from:

- `character_inventory`;
- historical `CharacterPanel.equipment`;
- `character_techniques.is_equipped`.

No synthetic physical Equipment is created from those surfaces.

No `OwnershipRecord` runtime or Phase-12 mutation path is introduced.

## 12. Scale / authoritative reads — PASS

Existing regression tests remain part of the exact CI suite and cover:

- 1001 Equipment entries with reopen;
- 1005 slot definitions;
- no authoritative `LIMIT 50/60` in EquipmentStore reads.

The concurrency hotfix adds write guards only and does not introduce presentation limits or alternative stores.

## 13. Database integrity / FK — PASS

Phase-11 tests continue to require:

```sql
PRAGMA integrity_check = ok
```

and no rows from:

```sql
PRAGMA foreign_key_check
```

The hotfix adds trigger-level invariants on top of the existing FK graph; it does not weaken FKs or delete prior constraints.

## 14. CI evidence — PASS

Exact hotfix candidate:

`c87193a69136a6680102779e4f0cd3d90a616d41`

GitHub Actions:

- run number: `#259`;
- run ID: `31369089655`;
- head SHA: exact candidate;
- status: completed;
- conclusion: `success`.

The exact run includes successful project validation, full JVM tests, signed ALPHA APK build, artifact preparation/upload and release asset update.

## 15. Revalidation gate matrix

- additive V11 schema preserved: PASS
- CurrentSchema -> V11/latest: PASS
- full earlier migration chain: PASS
- bootstrap/reopen/restore/campaign switch regression suite: PASS
- migration idempotency: PASS
- already-migrated V11 trigger refresh: PASS
- Phase 3–10 no-regression: PASS
- exact ItemInstance integrity: PASS
- campaign/player/World-Pack isolation: PASS
- slot capacity integrity: PASS
- exclusive/conflict-group integrity: PASS
- multi-slot all-or-none: PASS
- transfer/remove equipped instance guards: PASS
- EQ-RACE-01 possession/equip TOCTOU: PASS
- EQ-RACE-02 capacity TOCTOU: PASS
- manual DB second occupancy: PASS
- modifier lifecycle integrity: PASS
- no persistent base/current mutation: PASS
- no legacy Equipment synthesis: PASS
- no OwnershipRecord synthesis: PASS
- >1000 Equipment records: PASS
- >1000 slot definitions: PASS
- no authoritative truncation: PASS
- integrity_check: PASS
- foreign_key_check: PASS
- exact CI #259: PASS

## 16. Release blockers

No reproducible Phase-11 migration/integrity release blocker was found on `c87193a...`.

The two previously reported adversarial blocker classes, EQ-RACE-01 and EQ-RACE-02, are now closed at the authoritative SQLite write boundary rather than only by application prechecks.

Global Phase-11 completion remains a coordinator decision after independent CHAT-2 and CHAT-5 revalidation.

PHASE 11 INTEGRITY REVALIDATION: PASS
