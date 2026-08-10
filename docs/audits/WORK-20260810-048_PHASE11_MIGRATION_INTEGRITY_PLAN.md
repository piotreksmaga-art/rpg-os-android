# WORK-20260810-048 — Phase 11 Migration / Integrity Validation

Status: FINAL READ-ONLY RUNTIME VALIDATION

Work ID: `WORK-20260810-048`
Role: READ-ONLY PHASE 11 MIGRATION / INTEGRITY VALIDATOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase 10 runtime: `eb8bb64f8be566982c91f1062f319078899c1e47`
Audited Phase 11 candidate: `c96136964e4adb7144eee42b2b8680f153a839f2`
Exact CI: GitHub Actions run `#250`, run ID `31362782857`, conclusion `SUCCESS`.
Allowed write scope: this report only.

This report supersedes the planning checkpoint with final evidence from the exact WORK-20260810-046 runtime. No runtime code was changed by CHAT-3 and Phase 12 was not started.

## 1. Scope and authority boundaries

The final runtime preserves the required split:

```text
Inventory possession
!= Equipment/loadout state
!= legal OwnershipRecord
```

Phase 11 uses exact Phase-10 `ItemInstance` identity. It does not infer physical Equipment from `character_inventory`, the historical `CharacterPanel.equipment` presentation field, or `character_techniques.is_equipped`.

No Phase-12 `OwnershipRecord` runtime is created by the Phase-11 migration or by legal equip/unequip/transfer operations.

## 2. Migration and production routing — PASS

`RPGOS-11.0-EQUIPMENT` is additive and calls `ensureV10()` before creating Phase-11 objects.

V11 creates:

- `equipment_slot_definitions`;
- `equipment_compatibility_rules`;
- `equipment_rule_slots`;
- `player_equipment`;
- `player_equipment_slots`;
- indexes;
- DB-level transfer/remove guards for equipped unique inventory instances;
- exactly one migration marker through `INSERT OR IGNORE`.

The migration does not rewrite Phase 3–10 authority or legacy inventory bytes.

`CurrentSchema.ensure()` routes to `MigrationManager().ensureV11(...)`, preserving the complete prior migration chain through V10 and earlier.

Production tests cover:

- bootstrap of the bundled campaign -> V11;
- campaign switch from a V10 campaign -> V11;
- restore of a V10 database -> V11;
- restore does not synthesize equipment from legacy `character_inventory`.

Repeated schema ensure is idempotent; migration tests confirm a single V11 marker.

## 3. ItemInstance and holder integrity — PASS

`EquipmentStore.equip()` validates before persistence:

1. exact `ItemInstance` exists in the same campaign;
2. definition is `UNIQUE_INSTANCE`;
3. definition is ACTIVE;
4. exact instance is possessed by the target character through Phase-10 inventory reconciliation;
5. exact instance is not already equipped;
6. compatibility rule matches the instance's `ItemDefinition`;
7. World-Pack ownership of item, rule and slots is consistent.

Missing instance, wrong/unpossessed instance and duplicate exact-instance equip fail before a legal equipment state is committed.

Two instances of one ItemDefinition remain independent equipment identities. The test suite equips separate instance UIDs for separate players and also verifies campaign isolation when the same text instance UID exists in another campaign.

## 4. Critical transfer/remove invariant — PASS

The forbidden committed state:

```text
Inventory holder = B
Equipment holder = A
same itemInstanceUid
```

is prevented at two layers.

### Store-level policy

Phase-10 `removeUnique()` / `transferUnique()` fail while the exact instance is equipped. After explicit `unequip()`, transfer succeeds.

### DB-level integrity guard

V11 installs:

- `trg_equipped_instance_inventory_delete_guard`;
- `trg_equipped_instance_inventory_transfer_guard`.

These triggers abort direct DELETE or holder/campaign/instance UPDATE of `player_inventory_unique` whenever `player_equipment` still references the exact instance. This protects consistency even from callers that bypass the InventoryStore mutation API.

The regression test explicitly performs:

1. equip instance X for P;
2. attempt remove -> fail-loud;
3. attempt transfer P -> Q -> fail-loud;
4. verify holder remains P and equipment remains present;
5. explicit unequip;
6. transfer P -> Q succeeds;
7. verify no stale Equipment remains for P.

No reproducer produced a committed holder/equipment mismatch.

## 5. Slot definition / World-Pack integrity — PASS

Slot identity is stable `slot_uid`, not label.

Validation covers:

- duplicate slot UID rejection;
- duplicate `(world_pack_uid, slot_key)` rejection;
- World-Pack owner validation;
- positive capacity;
- ACTIVE/DEPRECATED semantics for new equip;
- stable version/provenance;
- rule ownership matching ItemDefinition owner;
- slot ownership matching rule owner.

Same-label concepts are not globally merged by display name.

## 6. Compatibility, capacity, exclusive groups and multi-slot atomicity — PASS

Compatibility uses explicit stable rule UID plus exact required slot UID set.

`equip()` rejects:

- missing requested slots;
- duplicate requested slot UID;
- requested slot set different from the rule's canonical set;
- incompatible ItemDefinition/rule;
- slot capacity exhaustion;
- deprecated slot;
- exclusive-group conflict;
- duplicate equip of the same exact ItemInstance.

For multi-slot equipment, `player_equipment` and every `player_equipment_slots` binding are inserted in one SQLite transaction, together with Phase-5 modifier-source activation. A failed validation occurs before the transaction; a failed write cannot legally leave only a subset of slot reservations committed.

The runtime does not silently replace existing equipment to resolve a conflict.

## 7. Phase-5 modifier lifecycle and no-retrogression — PASS

Phase 11 reuses the existing Phase-5 `ModifierStore` and `DerivedValueResolver`. No `EquipmentModifierEngine` or second Equipment resolver is introduced.

Equipment modifiers require:

- `ModifierLifecycle.EQUIPMENT`;
- exact equipment source type;
- `sourceUid == itemInstanceUid`;
- campaign/player scope match.

Equip activates that exact source; unequip deactivates it. Two instances of the same ItemDefinition retain separate modifier source identities.

The integration test snapshots authoritative state before equip and proves identical authoritative state during and after equipment lifecycle for:

- `PlayerStat.baseValue`;
- `PlayerResource.currentValue`;
- `PlayerSkill.baseMastery`;
- `PlayerTechnique.baseMastery`;
- Talent profile;
- Potential profile.

The final resource regression is explicitly covered:

```text
currentValue before equip = 100
equipment active: derived maximum 200, currentValueObserved = 100
after unequip: derived maximum 100, currentValueObserved = 100
```

Resource regeneration changes only as a derived result. No current-resource clamp/write occurs.

## 8. Legacy non-authority and Phase-10 boundary — PASS

A fixture containing both:

- `character_inventory` legacy data;
- `character_techniques.is_equipped = 1`

is migrated through V11 and produces zero `player_equipment` rows.

Therefore historical inventory labels, CharacterPanel naming and Technique equipped state are not physical Equipment authority.

Phase 11 requires a real unique `ItemInstance`; stackable commodity state cannot be equipped without an explicit physical instance contract. The migration does not synthesize instances from legacy names.

The updated Phase-10 regression contract correctly allows the V11 `player_equipment` table to exist, while still asserting that Inventory transfer does not itself create Equipment state and does not create `ownership_records_v2`.

## 9. Reopen, isolation and authoritative scale — PASS

Persistence test creates 1001 exact unique instances and 1001 Equipment entries, closes the DB, reopens through `CurrentSchema.ensure()`, and retrieves all 1001 entries without truncation.

A slot with sufficient explicit capacity retains the complete occupancy state. No `LIMIT 50/60` exists in the authoritative `EquipmentStore.equipment()` path.

The slot-definition scale fixture registers 1005 slot definitions and reads all 1005.

Player isolation, campaign isolation, and exact-instance separation are covered by runtime tests.

## 10. Backup / restore and campaign switch — PASS

Production routing tests independently prove restore and campaign-switch upgrade old V10 databases to V11. The restore fixture also proves no synthetic Equipment is created from legacy inventory evidence.

Existing backup/restore infrastructure copies/restores the campaign database as the persistence unit; Phase-11 Equipment tables, slot bindings and modifiers therefore remain inside the same campaign DB persistence boundary. No Phase-11 code introduces an external parallel Equipment store.

No campaign-switch path bypassing `CurrentSchema.ensure()` was found in the audited production routing.

## 11. Phase 3–10 no-regression — PASS

The exact CI JVM suite includes all prior regression suites and the Phase-11 tests. The Phase-11 migration is additive and only creates new Equipment objects plus two guards on the Phase-10 unique-inventory table.

Equipment integration directly proves zero mutation of Stat, Resource current value, Skill mastery, Technique mastery, Talent and Potential authority.

Phase-10 Inventory remains possession authority; Phase-9 and earlier data paths are not rewritten by `ensureV11()`.

No OwnershipRecord runtime or Phase-12 mutation path is introduced.

## 12. Database integrity / FK — PASS

Phase-11 persistence tests execute:

```sql
PRAGMA integrity_check
```

and require `ok`.

They also execute:

```sql
PRAGMA foreign_key_check
```

and require no rows.

Schema FKs connect compatibility rules to ItemDefinitions, rule-slot bindings to rules/slots, player equipment to exact `(campaign_id,item_instance_uid)`, and slot bindings to equipment entries and slot definitions.

The additional transfer/delete triggers cover the holder-consistency invariant that cannot be represented by the static ItemInstance FK alone.

## 13. CI evidence — PASS

Exact candidate:

`c96136964e4adb7144eee42b2b8680f153a839f2`

GitHub Actions:

- run number: `#250`;
- run ID: `31362782857`;
- head SHA: exact candidate above;
- conclusion: `success`.

Successful steps include:

- `Validate project`;
- `Run JVM unit tests`;
- `Build signed ALPHA APK`;
- artifact/release update steps.

Earlier failing/WIP CI runs are not used as the final evidence.

## 14. Final gate matrix

- P11-01 additive migration: PASS
- P11-02 latest CurrentSchema routing: PASS
- P11-03 bootstrap: PASS
- P11-04 reopen: PASS
- P11-05 backup/restore: PASS
- P11-06 campaign switch: PASS
- P11-07 migration idempotency: PASS
- P11-08 Phase 3–10 no-regression: PASS
- P11-09 integrity/FK: PASS
- P11-10 legacy inventory does not grant Equipment: PASS
- P11-11 CharacterPanel historical equipment naming is not authority: PASS
- P11-12 Technique `is_equipped` does not grant physical Equipment: PASS
- P11-20 stable slot UID: PASS
- P11-21 World-Pack slot ownership: PASS
- P11-22 duplicate slot UID fail-loud: PASS
- P11-23 compatibility validation: PASS
- P11-24 slot conflict/capacity validation: PASS
- P11-25 exclusive-group validation: PASS
- P11-26 multi-slot atomic reservation: PASS
- P11-30 exact ItemInstance identity/FK: PASS
- P11-31 wrong holder rejection: PASS
- P11-32 moved/deleted equipped instance consistency: PASS
- P11-33 same definition / different instance separation: PASS
- P11-34 dangling-reference protections: PASS
- P11-40 equipment modifier activation: PASS
- P11-41 unequip deactivation: PASS
- P11-42 exact source identity/reopen persistence: PASS
- P11-43 multiple-instance source isolation: PASS
- P11-44 base Stat/Skill/Technique/Talent/Potential unchanged: PASS
- P11-45 PlayerResource.currentValue unchanged: PASS
- P11-50 equipped-instance transfer/removal explicit-unequip policy: PASS
- P11-51 atomic consistency: PASS
- P11-60 >1000 equipment/slot state without authoritative truncation: PASS
- P11-61 exact WORK-046 tests/build/CI: PASS

## 15. Release blockers

No reproducible Phase-11 migration/integrity release blocker was found on the exact candidate.

The most important adversarial integrity state requested by the coordinator — Inventory holder B while Equipment holder A references the same exact ItemInstance — is prevented by both legal-store validation and V11 database triggers.

Global Phase-11 completion remains a coordinator decision after the independent CHAT-2 and CHAT-5 results.

PHASE 11 INTEGRITY VALIDATION: PASS
