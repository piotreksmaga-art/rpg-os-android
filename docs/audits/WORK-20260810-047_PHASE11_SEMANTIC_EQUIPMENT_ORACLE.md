# WORK-20260810-047 — Phase 11 Semantic / Determinism Equipment Oracle

Status: FINAL SEMANTIC REVALIDATION / READ-ONLY RUNTIME

Work ID: `WORK-20260810-047`
Owner: `CHAT-2`
Role: READ-ONLY PHASE 11 SEMANTIC REVALIDATOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-10 runtime: `eb8bb64f8be566982c91f1062f319078899c1e47`
Final audited Phase-11 runtime: `c96136964e4adb7144eee42b2b8680f153a839f2`
Original oracle report commit: `ed062fb2865b5564ef32d06875b23f44972ae7b2`
CI evidence: GitHub Actions run `#250`, run ID `31362782857`, exact head SHA `c96136964e4adb7144eee42b2b8680f153a839f2`, conclusion `success`.

This document records the final semantic recheck of the exact requested Phase-11 runtime. CHAT-2 did not modify Kotlin runtime, schema, migrations, tests, Phase 12, OwnershipRecord, or authoritative campaign state.

---

## 1. Frozen semantic boundary

The accepted Phase-11 contract is:

```text
ItemDefinition != ItemInstance
Inventory possession != Equipment state
Equipment state != OwnershipRecord
Inventory possession != OwnershipRecord
```

`ItemDefinition` is type/content identity. `ItemInstance` is exact physical unique identity. Inventory says who possesses the instance. Equipment says whether that already-possessed exact instance occupies explicit loadout slots. OwnershipRecord is a separate later domain and is not created by Phase 11.

The audited runtime preserves this split. `EquipmentStore.equip()` requires an exact existing `itemInstanceUid`, requires that its ItemDefinition uses `UNIQUE_INSTANCE`, and verifies that the same character already possesses that exact instance through the reconciled Phase-10 Inventory contract. It does not create inventory state or OwnershipRecord state.

---

## 2. Equip / unequip semantic recheck

### Equip

Runtime path:

```text
possessed exact ItemInstance X
+ active ItemDefinition
+ exact compatibility rule
+ exact required slots
+ capacity/exclusive-group validation
-> transactional PlayerEquipment + slot bindings + equipment modifier source activation
```

Inventory possession is checked before mutation and is not consumed by equip.

Result: PASS.

### Unequip

`EquipmentStore.unequip()` resolves one exact equipment entry, then in one SQLite transaction:

1. sets the exact ItemInstance equipment modifier source inactive;
2. deletes all slot bindings for that equipment entry;
3. deletes exactly one PlayerEquipment row.

It does not remove `player_inventory_unique`, does not delete ItemInstance, and does not create/delete OwnershipRecord.

Result: PASS.

### Equip != possession / unequip != inventory removal

These are separate tables and mutation paths. Inventory remains unchanged by the audited equip/unequip operations.

Result: PASS.

---

## 3. Exact ItemInstance identity

Phase 11 binds Equipment to exact `itemInstanceUid`.

Two physical instances X and Y of one ItemDefinition remain distinct because:

- `player_equipment` stores `item_instance_uid`;
- `(campaign_id,item_instance_uid)` is unique in PlayerEquipment;
- modifier `sourceUid` must equal exact `itemInstanceUid`;
- `isEquipped()` checks exact itemInstanceUid;
- possession check resolves exact unique instance, not item label or definition name.

Equip/unequip X therefore cannot implicitly toggle Y.

Result: PASS.

---

## 4. Stackable commodity boundary

Accepted Phase 10 represents stackables as definition quantity, without physical ItemInstance identity.

The audited `equip()` explicitly requires:

```text
item.storagePolicy == UNIQUE_INSTANCE
```

and fails otherwise with the semantic that a stackable commodity cannot be equipped without an explicit physical ItemInstance contract.

No synthetic ItemInstance is created by Phase 11 equip.

Result: PASS.

---

## 5. Slot compatibility, conflicts and no silent replacement

The runtime validates the complete proposed equip before writing:

- compatibility rule must exist;
- rule ItemDefinition UID must match exact instance definition;
- rule World Pack must match ItemDefinition owner;
- requested slot set must exactly equal the explicit rule-required slot set;
- each slot definition must exist and be ACTIVE;
- slot World Pack must match rule owner;
- occupancy must remain below explicit capacity;
- exclusive-group conflicts are collected and deterministically sorted before failure;
- exact ItemInstance must not already be equipped.

Occupied/incompatible/conflicting state fails loudly. There is no silent replacement path in audited runtime.

Result: PASS.

---

## 6. Multi-slot all-or-none atomicity

Required slots come from the compatibility rule and are canonicalized with stable UID sorting.

All slot/capacity/exclusivity validation occurs before the transaction begins. Only after the full proposal passes does the transaction insert:

```text
PlayerEquipment row
+ every required player_equipment_slots row
+ modifier source activation
```

A conflict in one required slot therefore creates no partial reservation in another required slot. Transaction rollback also protects insertion/modifier failures.

Result: PASS.

---

## 7. Equipment modifier semantics / Phase-5 single foundation

Phase 11 does not introduce a second resolver or Equipment-specific mechanics engine.

`EquipmentStore` integrates with the accepted Phase-5 `ModifierStore` using:

```text
ModifierLifecycle.EQUIPMENT
sourceType = equipment source type
sourceUid = exact ItemInstance UID
sourceActive = equipped state
```

`registerEquipmentModifiers()` requires the exact character/campaign scope, EQUIPMENT lifecycle, correct source type and exact instance source UID.

Equip activates the source. Unequip deactivates it. Effective values are rebuilt by the existing DerivedValueResolver foundation.

Result: PASS.

---

## 8. No-retrogression recheck

The Phase-11 effect path does not write authoritative progression fields.

The final runtime/test contract verifies derived changes for:

- Stat effective value;
- Resource maximum;
- Resource regeneration;
- Skill effective mastery;
- Technique effective mastery.

It verifies unchanged authority before/during/after equipment lifecycle for the underlying persistent data.

No Phase-11 path was found that rewrites:

```text
PlayerStat.baseValue
PlayerSkill.baseMastery
PlayerTechnique.baseMastery
TalentProfile
PotentialProfile
```

Result: PASS.

---

## 9. PlayerResource.currentValue final hotfix gate

The coordinator explicitly required:

```text
PlayerResource.currentValue = 100
before equip = 100
during equip = 100
after unequip = 100
```

The exact final commit `c96136964e4adb7144eee42b2b8680f153a839f2` changes `EquipmentModifierIntegrationTest` to seed current resource at exactly `100.0` and asserts `currentValueObserved == 100.0`:

- before equip;
- while equipment changes derived maximum from 100 -> 200 and regeneration from 2 -> 5;
- after unequip when derived maximum/regeneration return to 100 / 2.

The same test verifies stat/Skill/Technique derived values return to base projections after unequip while the captured authoritative snapshot remains identical.

There is no hidden clamp write in EquipmentStore or DerivedValueResolver equipment lifecycle.

Result: PASS.

---

## 10. Transfer/removal of equipped ItemInstance

This gate was validated against actual runtime, not only tests.

Phase-10 `InventoryStore.transferUnique()` and `removeUnique()` remain possession-domain operations and do not contain Equipment-specific application logic. Phase-11 migration deliberately enforces the cross-domain invariant at the database boundary with two V11 triggers:

```text
trg_equipped_instance_inventory_delete_guard
trg_equipped_instance_inventory_transfer_guard
```

Both check exact `(campaign_id,item_instance_uid)` against `player_equipment` and use SQLite `RAISE(ABORT, ...)` while the instance is equipped.

Therefore plain transfer/remove of equipped X fails before possession changes.

Canonical forbidden result:

```text
Inventory holder = B
Equipment holder = A
same exact itemInstanceUid X
```

cannot be reached by a committed plain Inventory transfer while X is equipped.

The required legal minimal contract is therefore:

```text
explicit unequip
then transfer/remove
```

or a future explicit atomic combined command. Phase 11 does not silently auto-unequip.

Result: PASS.

---

## 11. Failure atomicity

### Failed equip

All semantic validation is performed before Equipment write. The write set is then transactional.

Failure leaves:

- Inventory unchanged;
- no partial PlayerEquipment row;
- no partial slot occupancy;
- no equipment modifier source activation.

### Failed unequip

Modifier deactivation + slot deletion + Equipment deletion are inside one transaction. If deletion does not remove exactly one expected Equipment row, the transaction fails/rolls back.

### Failed transfer/remove while equipped

V11 DB triggers abort the Inventory mutation while Equipment remains intact.

Result: PASS.

---

## 12. Isolation recheck

### Campaign

Equipment, ItemInstance and Inventory unique possession use campaign scope. `player_equipment` references `(campaign_id,item_instance_uid)`.

### Player

`equip()` checks possession using the target `characterUid`; a unique instance held by player A cannot be equipped by player B.

### World Pack

Compatibility registration rejects ItemDefinition or slot ownership mismatch. Equip rechecks rule owner against ItemDefinition owner and slot owner.

### Slot

Occupancy is scoped by campaign + character + loadout + stable slot UID.

### Instance

Exact ItemInstance UID is unique in active Equipment per campaign.

### Modifier source

Source UID is exact ItemInstance UID, preventing two instances of one definition from sharing activation state.

Result: PASS.

---

## 13. Legacy / presentation non-authority

No Phase-11 migration path canonicalizes physical Equipment from:

- `character_inventory` rows;
- historical `CharacterPanelSnapshot.equipment` labels;
- `character_techniques.is_equipped`;
- item display names;
- Inventory transfer operations.

`character_inventory` remains Phase-10 legacy inventory evidence. CharacterPanel's historical equipment label remains presentation debt. Technique `is_equipped` remains Technique-domain state and has no physical ItemInstance semantics.

Phase-11 migration creates Equipment schema and guards only; it does not synthesize PlayerEquipment rows from legacy names.

Result: PASS.

---

## 14. Ownership boundary

The audited Phase-11 schema does not create `ownership_records_v2` and equip/unequip/Inventory transfer do not infer ownership.

The final Phase-10 transfer compatibility test was updated correctly for V11: `player_equipment` table may now legally exist after current-schema migration, but a plain Inventory transfer creates zero Equipment rows and no OwnershipRecord table/state.

Result: PASS.

---

## 15. Authoritative read / determinism

`EquipmentStore.equipment()` performs an unbounded authoritative query for the requested campaign/player/loadout. No presentation `LIMIT` is present.

Determinism is preserved by stable sorting of:

- requested/canonical slot sets;
- equipment entries;
- slot bindings;
- conflict group pairs.

No semantic resolution depends on SQLite insertion order or display name.

Result: PASS.

---

## 16. CI evidence for exact runtime

Verified directly from GitHub Actions API:

```text
run number: 250
run ID: 31362782857
workflow: Build & Release RPG OS ALPHA
head SHA: c96136964e4adb7144eee42b2b8680f153a839f2
status: completed
conclusion: success
```

This is exact-SHA CI evidence for the audited runtime.

Result: PASS.

---

## 17. Final semantic matrix

| Invariant | Result |
|---|---|
| Inventory possession != Equipment != OwnershipRecord | PASS |
| exact ItemInstance identity | PASS |
| equip != possession | PASS |
| unequip != inventory removal | PASS |
| equip/unequip atomicity | PASS |
| multi-slot all-or-none | PASS |
| no silent replacement | PASS |
| deterministic conflicts/exclusive groups | PASS |
| plain transfer/remove cannot leave stale Equipment | PASS |
| Equipment effects derived through Phase 5 | PASS |
| Stat base unchanged | PASS |
| PlayerResource.currentValue unchanged | PASS |
| Skill baseMastery unchanged | PASS |
| Technique baseMastery unchanged | PASS |
| Talent/Potential unchanged | PASS |
| unequip restores derived projection without retrogression | PASS |
| legacy inventory does not create Equipment | PASS |
| CharacterPanel.equipment not authority | PASS |
| Technique is_equipped not physical Equipment | PASS |
| stackable without exact ItemInstance rejected | PASS |
| Phase 5 remains single resolver/modifier foundation | PASS |
| authoritative Equipment read has no presentation LIMIT | PASS |
| exact SHA CI #250 | PASS |

---

## 18. Blockers

No reproducible semantic release blocker was found in the exact audited runtime.

CHAT-2 does not mark Phase 11 COMPLETE and does not begin Phase 12.

# PHASE 11 SEMANTIC REVALIDATION: PASS
