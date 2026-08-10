# WORK-20260810-047 — Phase 11 Semantic / Determinism Equipment Oracle

Status: READ-ONLY RUNTIME / SEMANTIC + DETERMINISM ORACLE

Work ID: `WORK-20260810-047`
Owner: `CHAT-2`
Role: READ-ONLY SEMANTIC / DETERMINISM ORACLE
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-10 runtime: `eb8bb64f8be566982c91f1062f319078899c1e47`
Fresh master observed before write: `138bf67b8e6af52efe66254fd2289a77804b88dc`
Primary architecture source: `docs/audits/WORK-20260810-044_PHASE11_EQUIPMENT_ARCHITECTURE.md`

This report is an independent semantic oracle for future Phase 11 implementation. It modifies documentation only. It does not implement Equipment runtime, schema, migration, Phase 12 OwnershipRecord, PlayerCommand, PlayerChangeSet, PlayerDomainEngine, or any later phase.

---

## 1. Canonical semantic boundary

Hard invariants:

```text
ItemDefinition != ItemInstance
Inventory possession != Equipment
Equipment != OwnershipRecord
Inventory possession != OwnershipRecord
```

`ItemDefinition` is a World-Pack-owned type identity.

`ItemInstance` is a concrete physical/individual item identity within a campaign when uniqueness matters.

Inventory answers whether a character possesses/carries/controls an item definition quantity or a concrete item instance.

Equipment answers whether a possessed equippable item instance is currently assigned to explicit loadout slot state.

OwnershipRecord, planned for Phase 12, answers legal/economic ownership. Equip/unequip must never create, delete or infer OwnershipRecord.

The accepted Phase-10 runtime provides:

- `ItemDefinition(itemDefinitionUid, worldPackUid, ..., storagePolicy, ...)`;
- `ItemInstance(campaignId, itemInstanceUid, itemDefinitionUid, ...)`;
- stackable inventory as `(campaignId, characterUid, itemDefinitionUid, quantity)`;
- unique inventory as `(campaignId, characterUid, itemInstanceUid)`;
- explicit legacy inventory evidence/mapping.

Therefore Phase 11 should bind physical Equipment to exact stable `itemInstanceUid` for unique equipment. A display name or `itemDefinitionUid` alone is not enough to identify which physical instance is equipped.

---

## 2. EQUIP / UNEQUIP oracle

### EQ-01 — possession is prerequisite, not equipment

Given:

```text
Inventory(P) contains ItemInstance X
Equipment(P) has no entry for X
```

Expected:

```text
P possesses X
X is not equipped
```

Inventory presence alone must not produce Equipment state.

### EQ-02 — equip preserves possession

Given:

```text
Inventory(P) contains X
slot requirements valid
```

Operation:

```text
equip X
```

Expected after atomic commit:

```text
Inventory(P) still contains X
Equipment(P) references exact X
required equipment slots are occupied by the equipment entry for X
no OwnershipRecord is created or changed
```

### EQ-03 — unequip preserves possession

Given X equipped and still possessed.

Operation:

```text
unequip X
```

Expected:

```text
Inventory(P) still contains X
Equipment(P) contains no active slot binding for X
Equipment-origin modifier source is inactive
no OwnershipRecord is created/deleted/changed
```

### EQ-04 — equip/unequip does not clone inventory

Equip and unequip must not change the quantity/instance count in Inventory.

For unique instance X:

```text
before inventory instances = {X}
after equip = {X}
after unequip = {X}
```

---

## 3. Slot compatibility and deterministic failure classes

Equipment slot identity must use stable `EquipmentSlotDefinition.slotUid` or equivalent typed UID. Slot labels are presentation only.

Every equip proposal must be deterministically classified before authoritative mutation.

### SLOT-01 — allowed

All required slots exist, compatibility rules pass, capacity is available, exclusivity rules pass, player/campaign/item scope is valid.

Expected: equip may commit atomically.

### SLOT-02 — incompatible

The exact item instance/definition is not compatible with a requested slot or required slot group.

Expected: deterministic failure, zero mutation.

### SLOT-03 — occupied

A required capacity-1 slot is already occupied by another equipment entry.

Default expected semantics:

```text
FAIL LOUD
```

No silent replacement.

Replacement is legal only through a distinct explicit atomic replace/swap operation whose contract unequips the old entry and equips the new entry in one transaction.

### SLOT-04 — conflicts / exclusive groups

If equipment X conflicts with active equipment Y via explicit exclusivity group/rule, equipping X fails unless an explicit atomic replace operation specifies the intended conflict resolution.

No dependence on insertion order, row order, name ordering or which conflict is encountered first. Conflict diagnostics should be ordered by stable UID for deterministic replay.

### SLOT-05 — missing slot definition

Missing required slot UID => deterministic failure before equipment mutation.

### SLOT-06 — same slot label / different UID

World Pack A slot `hand` and World Pack B slot `hand` remain distinct unless an explicit cross-pack mapping/rule exists. Same text does not imply same semantic slot.

---

## 4. Multi-slot atomicity

An item may require N slots through explicit World-Pack-owned compatibility/occupancy rules.

Canonical example:

```text
X requires slots A + B
A available
B occupied/conflicting
```

Expected:

```text
equip X fails
A remains unchanged
B remains unchanged
no PlayerEquipment row for X
no slot reservation for X
no equipment modifier activation for X
Inventory unchanged
```

Hard invariant:

```text
ALL REQUIRED SLOT BINDINGS COMMIT OR NONE COMMIT
```

Partial multi-slot occupancy is forbidden.

The exact order in which required slots are supplied must not affect the result. Validation should canonicalize by stable slot UID or explicit binding role before committing.

---

## 5. Item instance identity oracle

Two instances of the same definition are distinct physical identities.

Given:

```text
ItemDefinition D
ItemInstance X -> D
ItemInstance Y -> D
Inventory(P) contains X and Y
```

Equip X must produce:

```text
Equipment references X
Y remains unequipped
modifier source for X active
modifier source for Y unchanged/inactive
```

Unequip X must not affect Y.

Hard forbidden shortcuts:

```text
same ItemDefinition UID => same physical equipment
same displayName => same physical equipment
same category => same physical equipment
```

Equipment source identity should therefore be exact instance/equipment-entry identity, not definition name.

---

## 6. Stackable inventory boundary

Accepted Phase 10 models stackables as definition quantity, not individual `ItemInstance` rows.

Therefore Phase 11 must not equip an anonymous stack by simply pointing at `(itemDefinitionUid, quantity)` when physical instance identity matters.

Canonical minimal contract:

- unique physical equipment requires an exact `ItemInstance`;
- if a World Pack considers a stackable definition equippable, it must provide a legal deterministic conversion/reservation semantics that identifies the equipped unit/holding without duplicating quantity;
- Phase 11 must not invent such a conversion from item labels or category names.

If the accepted Phase-11 implementation does not support stackable equipment, deterministic rejection is semantically safer than guessing.

---

## 7. Equipment modifiers — Phase-5 single foundation

Equipment-derived effects must use the existing generic Modifier / DerivedValueResolver infrastructure.

Canonical source lifecycle:

```text
valid equip commit
-> equipment sourceActive = true
-> resolver includes active EQUIPMENT-lifecycle modifiers

valid unequip commit
-> equipment sourceActive = false
-> resolver rebuild excludes those modifiers
```

No `EquipmentModifierEngine` and no second resolver.

### MOD-01 — stat example

Given:

```text
PlayerStat.baseValue = 100
X equipped
X equipment modifier = +20 STAT_EFFECTIVE
```

Expected:

```text
effective = 120
base remains 100
```

After unequip:

```text
effective = 100
base remains 100
```

### MOD-02 — Skill

Equipment may affect `SKILL_EFFECTIVE` only through derived modifier resolution.

`PlayerSkill.baseMastery` remains unchanged before, during and after equip/unequip.

### MOD-03 — Technique

Equipment may affect `TECHNIQUE_EFFECTIVE`; `PlayerTechnique.baseMastery` remains unchanged.

### MOD-04 — Resource maximum

Equipment may affect `RESOURCE_MAXIMUM` via derived resolution.

Removing equipment rebuilds maximum from unchanged authority.

### MOD-05 — Resource regeneration

Equipment may affect `RESOURCE_REGENERATION` via derived resolution. Resolver does not perform regeneration.

### MOD-06 — current resource safety

If:

```text
currentValue = 150
derived maximum with equipment = 200
derived maximum after unequip = 100
```

unequip/resolution must not silently write `currentValue = 100`.

The resolver may surface an inconsistency/clamp proposal for a later legal mutation path, but Phase 11 equip/unequip must not rewrite current resource merely because derived maximum changed.

### MOD-07 — source identity isolation

Equipment source UID must identify the exact equipped source, preferably equipment entry UID or exact `itemInstanceUid` where one equipment entry per instance is guaranteed.

Do not use `itemDefinitionUid` if two physical instances of the same definition can be independently equipped.

---

## 8. Transfer / removal semantics while equipped

Phase 10 supports Inventory transfer/removal. Phase 11 must prevent dangling Equipment references.

The safest minimal Phase-11 contract is:

```text
Inventory transfer/remove of an actively equipped instance
=> FAIL LOUD
```

unless the caller uses a distinct explicit atomic operation whose semantics are:

```text
UNEQUIP + INVENTORY TRANSFER/REMOVE
```

within one transaction/change set.

This choice is preferred over implicit auto-unequip because it makes causal intent explicit and prevents hidden equipment/modifier changes during a seemingly Inventory-only operation.

### XFER-01 — equipped transfer via plain Inventory operation

Given X equipped by P.

Plain transfer X P -> Q:

Expected: deterministic failure; no inventory move, no equipment change, no modifier lifecycle change.

### XFER-02 — explicit atomic unequip+transfer

Expected atomic result:

```text
P no longer has X in Equipment
X equipment modifiers inactive for P
P no longer possesses X
Q possesses X
no OwnershipRecord is inferred or moved
```

All-or-nothing.

### XFER-03 — removal/destruction boundary

Plain removal of equipped X should fail for the same reason.

If a later operation represents destruction/loss, it must explicitly resolve dependent Equipment state atomically before the Inventory instance disappears.

### XFER-04 — failed dependent cleanup

If unequip/modifier deactivation fails, transfer/removal must rollback entirely. Never allow `Equipment -> missing ItemInstance` dangling reference.

---

## 9. Legacy semantic oracle

Three current legacy/presentation surfaces must remain non-authoritative for physical Equipment.

### LEG-01 — `character_inventory`

A legacy `character_inventory` row proves inventory evidence only. It does not prove equipped state.

No automatic:

```text
character_inventory row -> PlayerEquipment
```

### LEG-02 — CharacterPanel `equipment`

The historical CharacterPanel field named `equipment` was populated from `character_inventory.item_name`. This is a presentation naming shortcut, not authoritative Equipment evidence.

No migration may treat that field as proof of equipped/loadout state.

### LEG-03 — `character_techniques.is_equipped`

Technique `is_equipped` belongs to Technique selection/presentation semantics. It does not identify a physical item instance and cannot create Phase-11 Equipment state.

### LEG-04 — no name-based migration

A label such as `sword`, `armor`, `equipped`, `worn`, item display name or inventory name is insufficient to establish physical Equipment state.

Only explicit typed legacy mapping with exact item instance + exact slot identity may canonicalize real legacy equipment evidence, if such evidence is proven by actual schema/data.

### LEG-05 — unresolved legacy remains unresolved

If evidence cannot prove exact item instance and slot semantics, preserve it without granting Equipment state.

---

## 10. Failure atomicity oracle

### FAIL-EQUIP

Any failed equip attempt must leave all of the following unchanged:

```text
Inventory possession
ItemInstance state
Equipment entries
slot occupancy
modifier sourceActive state
base stats/masteries
OwnershipRecord domain
```

No partial occupancy and no partially-created modifier source.

### FAIL-UNEQUIP

Any failed unequip must not leave Equipment and modifier lifecycle inconsistent.

Forbidden outcomes:

```text
Equipment row remains but sourceActive=false accidentally
Equipment row removed but sourceActive=true
one multi-slot binding removed while another remains
Inventory unexpectedly modified
```

Equipment state + all slot bindings + source lifecycle should mutate in one atomic transaction/change set.

### FAIL-REPLACE

If an explicit replace/swap operation exists, failure at any stage leaves old equipment fully intact and new equipment fully unequipped.

---

## 11. Determinism oracle

The same logical equipment input must yield the same result regardless of:

- SQLite row order;
- insertion order;
- order of requested slot UIDs;
- map iteration order;
- display-name ordering;
- order in which conflicts are discovered.

Recommended deterministic normalization:

1. resolve exact campaign/player/item instance identity;
2. resolve canonical item definition and World Pack owner;
3. collect required slot bindings/rules;
4. canonicalize dependencies by stable UID / explicit role;
5. validate compatibility/capacity/exclusivity across the full proposed occupancy set;
6. if any failure -> zero writes;
7. otherwise commit Equipment + slot bindings + modifier source lifecycle atomically.

Conflict diagnostics should also be deterministic, e.g. stable-UID-sorted conflict sets rather than "first row returned".

---

## 12. Isolation oracle

### Campaign isolation

Equipment in campaign A cannot occupy slots, activate modifiers or reference an ItemInstance belonging to campaign B.

### Player isolation

Player A's equipment entry/modifier source cannot affect player B.

A unique ItemInstance possessed by A cannot be equipped by B without a legal Inventory transfer first.

### World Pack isolation

World Pack B cannot hijack:

- item definition UID owned by A;
- slot UID owned by A;
- compatibility rule UID owned by A;
- modifier binding/source identity for A's equipment.

Same labels across packs remain distinct.

### Slot isolation

One slot occupancy applies only inside its declared campaign/player/loadout scope. Slot UID identity does not make occupancy global across characters.

### Instance isolation

X and Y remain separate even when they share ItemDefinition.

### Modifier-source isolation

Source identity for equipment X must not toggle modifiers belonging to equipment Y.

---

## 13. Deprecated definition semantics

`ItemDefinitionStatus.DEPRECATED` does not mean an existing physical `ItemInstance` disappears from Inventory.

Canonical oracle:

- existing possession remains historical/current possession unless another legal mutation removes it;
- whether a deprecated definition may be newly equipped must be an explicit Phase-11/World-Pack policy;
- no implicit deletion or ownership change;
- no migration may reinterpret deprecated as destroyed.

For deterministic minimal semantics, new equip of a deprecated definition should fail unless an explicit compatibility/rule contract allows continued use. Existing equipped state may remain until explicit unequip/migration policy says otherwise, but implementation must choose and test one contract rather than silently varying by read path.

---

## 14. Scale / authoritative reads

Authoritative equipment reads must return the complete equipment/loadout state for the requested campaign/player/loadout scope.

Hard rule:

```text
No presentation LIMIT in authoritative EquipmentStore / repository reads.
```

A UI/Context projection may be bounded later, but the bound is presentation-only and cannot influence validation, occupancy, conflict detection, modifiers or persistence.

Required scale oracle:

- 1000+ equipment/slot bindings are returned without truncation;
- conflict validation considers all relevant active bindings, not only the first N;
- deterministic result remains independent of insertion order.

---

## 15. Canonical example matrix

### EX-01 — simple equip

```text
Inventory: X present
Slot A free
X compatible with A
```

Result:

```text
Inventory: X present
Equipment: X -> A
modifier source X active
```

### EX-02 — simple unequip

```text
Equipment: X -> A
```

Result:

```text
Inventory: X still present
Equipment: no X binding
modifier source X inactive
```

### EX-03 — occupied slot

```text
Y occupies A
try equip X -> A
```

Result: fail; Y unchanged; X unequipped; inventory unchanged; no X modifier activation.

### EX-04 — multi-slot failure

```text
X requires A+B
A free
B occupied
```

Result: fail; A remains free; B remains unchanged; X has zero slot bindings.

### EX-05 — instance separation

```text
X,Y -> same definition D
Equip X
```

Result: only X active/equipped; Y unaffected.

### EX-06 — derived stat

```text
base stat = 100
X equipment +20
```

Equipped => effective 120, base 100.

Unequipped => effective 100, base 100.

### EX-07 — resource max

```text
current = 150
base/derived max without equipment = 100
X adds +100 max
```

Equipped => derived max 200, current remains 150.

Unequip => derived max 100, current still 150; no hidden clamp write.

### EX-08 — transfer equipped item

Plain Inventory transfer while X equipped => fail with zero change.

Explicit atomic unequip+transfer => X leaves Equipment and P's inventory, appears in Q's inventory, source deactivates for P; no ownership inference.

### EX-09 — legacy inventory presentation

Legacy `character_inventory.item_name = "Sword"` and CharacterPanel `equipment=["Sword"]` => no canonical Equipment state until explicit proven mapping.

---

## 16. Runtime recheck gates for future WORK-046

When WORK-046 is published, semantic recheck must inspect actual runtime paths, not test names only.

Minimum PASS gates:

1. equip requires canonical Phase-10 possession and exact instance identity;
2. equip keeps Inventory possession intact;
3. unequip keeps Inventory possession intact;
4. no OwnershipRecord mutation;
5. slot compatibility fail-loud;
6. occupied slot does not silently replace;
7. explicit replace, if present, is atomic;
8. multi-slot reservation all-or-none;
9. instance X lifecycle does not affect Y;
10. Equipment modifiers use Phase-5 resolver/source lifecycle;
11. equip/unequip never write base stat/masteries/Talent/Potential;
12. resource currentValue is not resolver-clamped;
13. equipped item transfer/remove cannot create dangling reference;
14. plain equipped transfer/remove fails OR explicit atomic unequip+transfer is enforced;
15. legacy `character_inventory` is not auto-equipped;
16. CharacterPanel `equipment` label is not authority;
17. Technique `is_equipped` is not physical Equipment;
18. failed equip creates zero partial occupancy/modifiers/inventory change;
19. failed unequip preserves Equipment/modifier consistency;
20. campaign/player/World Pack/slot/instance/source isolation;
21. authoritative reads have no presentation LIMIT;
22. 1000+ binding path remains complete/deterministic;
23. reopen preserves exact equipment and source identity;
24. production migration reaches Phase 11 without altering accepted Phase-10 inventory semantics.

Any violation of Inventory != Equipment, Equipment != OwnershipRecord, exact instance identity, atomic multi-slot state or no-retrogression is a semantic blocker.

---

## 17. Final semantic contract

Phase 11 Equipment is a persistent character-scoped loadout/slot assignment over already-existing Phase-10 possession identities.

It does not create possession and does not establish legal ownership.

For physical unique equipment, exact `ItemInstance` identity is authoritative.

Equip/unequip are atomic state transitions that keep Inventory intact and toggle only Equipment state plus generic Phase-5 equipment-source lifecycle.

Slot conflict resolution is explicit and deterministic. No silent replacement. Multi-slot equipment is all-or-none.

Inventory transfer/removal of an equipped item must not leave a dangling Equipment reference; the minimal safe contract is fail-loud unless an explicit atomic unequip+transfer/remove operation is used.

Legacy inventory/presentation/Technique equipped labels do not constitute physical Equipment authority.

# PHASE 11 SEMANTIC ORACLE READY
