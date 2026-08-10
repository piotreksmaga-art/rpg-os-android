# WORK-20260810-047 — Phase 11 Semantic / Determinism Equipment Oracle

Status: FINAL HOTFIX SEMANTIC REVALIDATION / READ-ONLY RUNTIME

Work ID: `WORK-20260810-047`
Owner: `CHAT-2`
Role: READ-ONLY PHASE 11 SEMANTIC REVALIDATOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-10 runtime: `eb8bb64f8be566982c91f1062f319078899c1e47`
Previous Phase-11 candidate: `c96136964e4adb7144eee42b2b8680f153a839f2`
Final audited hotfix runtime: `c87193a69136a6680102779e4f0cd3d90a616d41`
Original oracle report commit: `ed062fb2865b5564ef32d06875b23f44972ae7b2`
Previous semantic revalidation report commit: `85f8f07974be745eb7689d6fc02dedab83e2b5ea`
Exact CI: GitHub Actions `#259`, run ID `31369089655`, head SHA `c87193a69136a6680102779e4f0cd3d90a616d41`, conclusion `success`.

This report validates only the exact hotfix SHA above. CHAT-2 did not modify Kotlin runtime, schema, migrations, tests, OwnershipRecord, Phase 12, or authoritative campaign state.

---

## 1. Frozen semantic boundary

Hard invariants remain:

```text
ItemDefinition != ItemInstance
Inventory possession != Equipment state
Equipment state != OwnershipRecord
Inventory possession != OwnershipRecord
```

Physical Equipment binds to an exact stable `ItemInstance`. Inventory remains possession authority. Phase 11 does not create OwnershipRecord semantics.

Result: PASS.

---

## 2. Exact ItemInstance / stackable boundary

`EquipmentStore.equip()` requires an exact existing `itemInstanceUid`, an ACTIVE definition and `ItemStoragePolicy.UNIQUE_INSTANCE`. A stackable quantity without an exact physical ItemInstance cannot receive synthetic Equipment identity.

Two instances of one ItemDefinition remain independent physical identities. Equipment and modifier source identity use the exact instance UID, not item name or definition label.

Result: PASS.

---

## 3. Equip / unequip authority and atomicity

Equip preserves Inventory possession. Unequip removes Equipment state but preserves Inventory possession and ItemInstance identity. Neither operation creates or mutates OwnershipRecord.

Successful equip transaction contains:

```text
player_equipment insert
+ all required player_equipment_slots inserts
+ Phase-5 EQUIPMENT modifier source activation
```

Successful unequip transaction contains:

```text
modifier source deactivation
+ all slot-binding deletion
+ exact player_equipment deletion
```

Failed writes rollback the full transaction.

Result: PASS.

---

## 4. Slot compatibility / no silent replacement

Compatibility remains explicit by stable rule and slot UIDs. Runtime requires the requested slot set to equal the rule-required slot set, checks active slot definitions, World-Pack ownership, capacity and conflict/exclusive semantics.

There is no silent replacement contract. Occupied/incompatible/conflicting proposals fail loudly.

Result: PASS.

---

## 5. Multi-slot all-or-none

All required slot rows are written inside the same Equipment transaction. Any trigger or SQL failure on one required slot aborts the transaction, rolling back the PlayerEquipment row, prior slot rows in the proposal, and modifier activation.

Canonical invariant:

```text
ALL REQUIRED SLOT BINDINGS COMMIT OR NONE COMMIT
```

Result: PASS.

---

## 6. Phase-5 single derived foundation / no-retrogression

Phase 11 continues to use the existing Phase-5 `ModifierStore` / `DerivedValueResolver` foundation with `ModifierLifecycle.EQUIPMENT`. No `EquipmentModifierEngine` or second Equipment resolver exists in the audited runtime.

Equipment lifecycle does not rewrite:

```text
PlayerStat.baseValue
PlayerResource.currentValue
PlayerSkill.baseMastery
PlayerTechnique.baseMastery
Talent
Potential
```

The accepted Phase-11 integration contract keeps `PlayerResource.currentValue = 100` exactly 100 before equip, during equip and after unequip even while derived maximum/regeneration change.

Result: PASS.

---

## 7. Legacy / presentation non-authority

The following remain non-authoritative for physical Equipment:

- legacy `character_inventory`;
- historical `CharacterPanel.equipment` presentation label;
- `character_techniques.is_equipped`;
- item/display labels.

No automatic legacy/name-based Equipment synthesis is introduced.

Result: PASS.

---

## 8. Previous blocker EQ-RACE-01 — possession/equip TOCTOU

Previous candidate `c961369...` could theoretically perform a stale application possession pre-read, allow a concurrent Inventory transfer, then insert Equipment for the old holder.

The hotfix moves the authoritative invariant to SQLite write time through:

```text
trg_equipment_possession_guard
```

The trigger runs `BEFORE INSERT ON player_equipment` and requires an exact row in `player_inventory_unique` matching:

```text
campaign_id = NEW.campaign_id
character_uid = NEW.character_uid
item_instance_uid = NEW.item_instance_uid
```

SQLite serializes competing writers at the database write boundary. Therefore both meaningful interleavings are safe:

### transfer commits first

```text
A owns X
T2 transfer A -> B commits
T1 stale equip insert for A executes
possession trigger sees no (A,X)
=> ABORT
```

Final state: B possesses X, A has no Equipment X.

### equip commits first

```text
A owns X
T1 equipment insert/transaction commits
T2 transfer attempts UPDATE player_inventory_unique
trg_equipped_instance_inventory_transfer_guard sees Equipment X
=> ABORT
```

Final state: A still possesses and equips X.

The equivalent remove race is also closed by the possession insert guard in the remove-first direction and `trg_equipped_instance_inventory_delete_guard` in the equip-first direction.

Forbidden committed state:

```text
Inventory holder = B
Equipment holder = A
itemInstanceUid = X
```

is not reachable through these write paths.

Result: PASS — EQ-RACE-01 closed.

---

## 9. Previous blocker EQ-RACE-02 — slot capacity TOCTOU

Previous candidate relied on application-level capacity pre-reads before beginning the Equipment write transaction.

The hotfix adds authoritative write-time enforcement:

```text
trg_equipment_slot_capacity_guard
BEFORE INSERT ON player_equipment_slots
```

For the incoming equipment entry the trigger resolves its canonical loadout and counts existing committed occupancy for the same campaign, character, slot and loadout. It aborts when occupancy is already greater than or equal to the declared slot capacity.

For a capacity-1 slot and two competing equip attempts, SQLite write serialization means at most one transaction can commit the first slot binding. The second writer then observes committed occupancy=1 at trigger execution and aborts. Because the second Equipment operation is transactional, its `player_equipment` row and any earlier bindings/modifier changes in that proposal roll back as well.

A manual second occupancy write is protected by the same DB trigger rather than relying only on `EquipmentStore` prechecks.

Result: PASS — EQ-RACE-02 closed.

---

## 10. Exclusive/conflict-group races

The hotfix adds/recreates authoritative guards:

```text
trg_equipment_rule_exclusive_guard
trg_equipment_slot_exclusive_guard
```

Rule-level conflicts are checked before inserting the incoming `player_equipment` row. Slot-level conflicts are checked before each incoming slot binding. Checks are scoped to campaign + character + loadout and compare stable World-Pack/group identities.

Under a competing writer race, one transaction commits first; the second writer then encounters the now-committed conflicting state and aborts. If the conflict is detected during a slot insert, the whole incoming Equipment transaction rolls back.

Result: PASS.

---

## 11. Multi-slot race and rollback

For a multi-slot item, the incoming `player_equipment` row, all slot reservations and modifier activation share one transaction. Capacity/exclusive triggers execute per slot at write time.

If another transaction wins a required slot/conflict before this proposal reaches that binding, trigger failure aborts the entire transaction. No partial multi-slot Equipment state can remain committed.

Result: PASS.

---

## 12. Modifier lifecycle under concurrency/failure

Modifier activation occurs only after Equipment row/slot writes within the same transaction. A capacity, possession or conflict trigger abort occurring before activation prevents activation entirely. A later failure still rolls the transaction back.

Unequip deactivates the exact instance source and removes Equipment state transactionally. The source identity is exact `itemInstanceUid`, so two instances of one ItemDefinition do not collide merely by definition identity.

Result: PASS.

---

## 13. Transfer/remove equipped instance

V11 retains:

```text
trg_equipped_instance_inventory_delete_guard
trg_equipped_instance_inventory_transfer_guard
```

Plain Inventory remove/transfer of an already equipped exact instance fails loudly. Canonical minimal legal behavior remains explicit unequip before transfer/removal, or a future explicit combined atomic command.

No hidden OwnershipRecord behavior is introduced.

Result: PASS.

---

## 14. Already-migrated V11 database hotfix refresh

`ensureV11()` deliberately executes:

```text
DROP TRIGGER IF EXISTS ...
CREATE TRIGGER ...
```

for all corrected Phase-11 guards on every schema ensure. Therefore a database already carrying the `RPGOS-11.0-EQUIPMENT` migration marker receives the corrected trigger definitions on normal `ensureV11()` without reinstall, destructive migration, or new campaign creation.

The migration marker remains `INSERT OR IGNORE`; trigger repair is independent of whether the marker already exists.

Result: PASS.

---

## 15. Determinism / isolation / scale semantics

The hotfix does not change the established semantic separations:

- campaign isolation;
- player isolation;
- World-Pack slot/rule ownership;
- exact ItemInstance isolation;
- exact modifier-source isolation;
- no authoritative presentation LIMIT;
- stable-UID-based compatibility rather than labels.

The concurrency guards move critical validity decisions to the serialized authoritative SQLite boundary, reducing dependence on stale application read timing.

Result: PASS.

---

## 16. Exact CI evidence

Verified directly from GitHub Actions API:

```text
workflow: Build & Release RPG OS ALPHA
run number: 259
run ID: 31369089655
head SHA: c87193a69136a6680102779e4f0cd3d90a616d41
status: completed
conclusion: success
```

Result: PASS.

---

## 17. Final hotfix semantic matrix

| Invariant | Result |
|---|---|
| Inventory possession != Equipment != OwnershipRecord | PASS |
| exact ItemInstance identity | PASS |
| equip/unequip atomicity | PASS |
| multi-slot all-or-none | PASS |
| slot capacity | PASS |
| no silent replacement | PASS |
| exclusive/conflict groups | PASS |
| stackable without exact instance rejected | PASS |
| equipped transfer/remove requires explicit unequip | PASS |
| Phase-5 EQUIPMENT lifecycle is single derived foundation | PASS |
| Stat base unchanged | PASS |
| PlayerResource.currentValue unchanged | PASS |
| Skill baseMastery unchanged | PASS |
| Technique baseMastery unchanged | PASS |
| Talent/Potential unchanged | PASS |
| legacy/presentation surfaces do not create Equipment | PASS |
| EQ-RACE-01 possession/equip TOCTOU | PASS |
| EQ-RACE-02 capacity TOCTOU | PASS |
| exclusive/conflict race | PASS |
| multi-slot race rollback | PASS |
| modifier activation/deactivation atomicity | PASS |
| already-migrated V11 gets refreshed guards | PASS |
| exact SHA CI #259 | PASS |

---

## 18. Blockers

No reproducible semantic release blocker was found in exact runtime `c87193a69136a6680102779e4f0cd3d90a616d41`.

CHAT-2 does not mark Phase 11 COMPLETE and does not begin Phase 12.

# PHASE 11 SEMANTIC REVALIDATION: PASS
