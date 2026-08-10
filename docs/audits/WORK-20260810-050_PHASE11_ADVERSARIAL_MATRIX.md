# WORK-20260810-050 — Phase 11 Equipment Adversarial Matrix

Status: FINAL VALIDATION COMPLETE

Work ID: `WORK-20260810-050`
Role: READ-ONLY ADVERSARIAL AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-10 runtime: `eb8bb64f8be566982c91f1062f319078899c1e47`
Preparation baseline observed: `ed062fb2865b5564ef32d06875b23f44972ae7b2`
Primary architecture: `docs/audits/WORK-20260810-044_PHASE11_EQUIPMENT_ARCHITECTURE.md`
Semantic oracle: `docs/audits/WORK-20260810-047_PHASE11_SEMANTIC_EQUIPMENT_ORACLE.md`
Migration/integrity plan: `docs/audits/WORK-20260810-048_PHASE11_MIGRATION_INTEGRITY_PLAN.md`
Audited Phase-11 candidate: `c96136964e4adb7144eee42b2b8680f153a839f2`
Exact CI: GitHub Actions `#250`, run ID `31362782857`, `SUCCESS`

This document defines adversarial attacks only. It does not implement or repair Phase 11 runtime and does not start Phase 12 OwnershipRecord.

## Canonical boundaries

```text
ItemDefinition != ItemInstance
Inventory possession != Equipment/loadout
Equipment != OwnershipRecord
Inventory possession != OwnershipRecord
stable UID > label/name
Equipment effects -> Phase-5 Modifier/DerivedValueResolver
Equipment effects != base-state mutation
```

Legacy `character_inventory`, CharacterPanel `equipment`, and `character_techniques.is_equipped` are explicitly non-authoritative for physical Equipment.

## Adversarial matrix

| ID | Gate | Attack | Required result / blocker condition |
|---|---|---|---|
| EQ-A01 | Slot identity | register duplicate slot UID | fail-loud, no overwrite/hijack |
| EQ-A02 | Slot identity | World Pack B reuses slot UID owned by A | fail-loud |
| EQ-A03 | Slot identity | same display label, different UIDs | remain distinct; no label merge |
| EQ-A04 | Slot identity | equip into missing slot | fail-loud, zero mutation |
| EQ-A05 | Slot identity | equip into deprecated slot | deterministic explicit policy; never silently substitute another slot |
| EQ-A06 | Slot identity | duplicate stable key inside wrong scope | reject according to World-Pack key uniqueness contract |
| EQ-A10 | Item identity | missing ItemInstance | fail-loud before equipment write |
| EQ-A11 | Item identity | existing but unpossessed instance | fail-loud — Equipment without possession is BLOCKER |
| EQ-A12 | Item identity | same definition, instances X/Y | equipping X never equips/mutates Y |
| EQ-A13 | Item identity | instance held by another player | fail-loud |
| EQ-A14 | Item identity | instance from another campaign | fail-loud |
| EQ-A15 | Item identity | attempt item-name identity | reject/no canonical resolution by name |
| EQ-A20 | Compatibility | incompatible item/slot | fail-loud, no occupancy/modifier mutation |
| EQ-A21 | Compatibility | malformed compatibility rule | fail-loud; no permissive fallback |
| EQ-A22 | Compatibility | cross-World-Pack rule/binding hijack | reject unless explicit valid cross-pack contract exists |
| EQ-A23 | Compatibility | missing compatibility target | fail-loud |
| EQ-A24 | Compatibility | infer compatibility from item name/category/slot label | forbidden; any success is BLOCKER |
| EQ-A30 | Occupancy | capacity-1 slot already occupied | fail-loud; no silent replacement |
| EQ-A31 | Occupancy | same unique instance equipped twice | reject |
| EQ-A32 | Occupancy | exclusive-group conflict | deterministic failure |
| EQ-A33 | Occupancy | multi-slot item, one required slot unavailable | all-or-none; zero partial binding |
| EQ-A34 | Occupancy | failure after first multi-slot reservation | transaction rollback; no partial equipment/source state |
| EQ-A35 | Occupancy | replay same equip request | no duplicate equipment/bindings/modifier source |
| EQ-A36 | Occupancy | explicit replacement where old item must leave and new enter | atomic old-out/new-in or complete rollback |
| EQ-A37 | Occupancy | different required-slot input ordering | identical deterministic result |
| EQ-A40 | Inventory boundary | equip unpossessed instance | reject |
| EQ-A41 | Inventory boundary | remove equipped instance | reject or atomically unequip/deactivate then remove; never dangling Equipment |
| EQ-A42 | Inventory boundary | transfer equipped A->B | reject or atomically unequip/deactivate+transfer; never A-equipment/B-inventory split |
| EQ-A43 | Inventory boundary | inject failure during transfer resolution | complete rollback of inventory, equipment, slot and modifier state |
| EQ-A44 | Inventory boundary | simple unequip | inventory instance remains possessed; quantity/count unchanged |
| EQ-A45 | Inventory boundary | equip/unequip | must not create/delete ItemInstance |
| EQ-A50 | Ownership boundary | equip item | zero OwnershipRecord creation/mutation |
| EQ-A51 | Ownership boundary | unequip item | zero ownership deletion/mutation |
| EQ-A52 | Ownership boundary | inventory transfer | no legal/historical ownership transfer inferred by Equipment |
| EQ-A60 | Modifier lifecycle | valid equip | activate existing Phase-5 EQUIPMENT source only after successful equipment commit |
| EQ-A61 | Modifier lifecycle | valid unequip | deactivate equipment source; effective projection rebuilds |
| EQ-A62 | Modifier identity | two instances same definition | distinct stable source UIDs; effects isolated |
| EQ-A63 | Modifier identity | duplicate source UID | reject/fail-loud; no double contribution |
| EQ-A64 | Modifier lifecycle | stale sourceActive after reopen | source activity must match authoritative equipment state |
| EQ-A65 | Modifier lifecycle | failed equip | modifier must remain inactive |
| EQ-A66 | Modifier lifecycle | failed unequip | rollback must preserve prior equipment and modifier state consistently |
| EQ-A67 | Modifier architecture | introduce Equipment-specific resolver/engine | BLOCKER; must reuse Phase-5 resolver |
| EQ-A70 | No-retrogression | equipment stat effect | `PlayerStat.baseValue` byte/semantic equality before/after equip/unequip |
| EQ-A71 | No-retrogression | resource max effect / unequip below current | `PlayerResource.currentValue` unchanged; no hidden clamp |
| EQ-A72 | No-retrogression | skill effect | `Skill.baseMastery` unchanged |
| EQ-A73 | No-retrogression | technique effect | `Technique.baseMastery` unchanged |
| EQ-A74 | No-retrogression | talent effect | Talent authoritative value unchanged |
| EQ-A75 | No-retrogression | potential effect | Potential authoritative value unchanged |
| EQ-A80 | Legacy | `character_inventory` row exists | must not create/equip PlayerEquipment |
| EQ-A81 | Legacy | CharacterPanel field named `equipment` | presentation label must not grant physical Equipment |
| EQ-A82 | Legacy | `character_techniques.is_equipped=1` | must not create physical Equipment |
| EQ-A83 | Legacy | names `weapon`, `armor`, `worn`, `equipped` | must not synthesize slot/equipment identity |
| EQ-A84 | Legacy | unresolved inventory name resembles typed equippable item | no name-based equip; unresolved remains evidence |
| EQ-A90 | Isolation | same player UID across campaigns | equipment isolated by campaign |
| EQ-A91 | Isolation | player A/B same campaign | equipment/slots/source state cannot leak between players |
| EQ-A92 | Isolation | same textual instance UID across campaign scopes | no cross-campaign binding |
| EQ-A93 | Isolation | same slot label/key across World Packs | no cross-pack semantic merge |
| EQ-A94 | Isolation | modifier source from A | cannot affect player/campaign B |
| EQ-A100 | Scale | >1000 slot definitions | complete authoritative read, no truncation |
| EQ-A101 | Scale | >1000 equipment/slot bindings in valid fixture | exact count after write/reopen |
| EQ-A102 | Scale | ContextBuilder equipment projection if implemented | full canonical typed read before presentation budget |
| EQ-A103 | Scale | search for authoritative LIMIT 50/60 | any truncating authoritative query is BLOCKER |
| EQ-A110 | Migration | `CurrentSchema.ensure()` | reaches Phase 11/latest through prior Phase-10 chain |
| EQ-A111 | Migration | bootstrap | Phase-11 marker/schema reachable via production path |
| EQ-A112 | Migration | reopen | exact equipment/bindings/source identity preserved |
| EQ-A113 | Migration | restore Phase-10 backup | reaches Phase 11 without synthesizing equipment |
| EQ-A114 | Migration | campaign switch Phase-10 DB | routes to Phase 11 and preserves isolation |
| EQ-A115 | Migration | repeated ensure | idempotent; marker exactly once; no duplicate rows/sources |
| EQ-A116 | Migration | failure before schema completion | no successful marker/partial authoritative state |
| EQ-A117 | Migration | Phase 3–10 snapshot | semantic equality of all pre-existing authoritative domains |
| EQ-A120 | Database | after normal/adversarial operations | `PRAGMA integrity_check = ok` |
| EQ-A121 | Database | after normal/adversarial operations | `PRAGMA foreign_key_check` empty under adopted FK policy |
| EQ-A122 | Database | dangling equipment->instance/slot | impossible to commit or detected fail-loud |

## Required atomicity probes

### Multi-slot failure

Fixture: X requires slots A+B. A is free, B occupied. Attempt equip X. Assert no equipment entry for X, no A reservation, B unchanged, Inventory unchanged, and no equipment modifier source becomes active.

### Equipped transfer

Fixture: player A possesses and equips instance X. Attempt transfer X to B. Accept only one of two explicit policies: deterministic rejection with state unchanged, or one transaction that deactivates/unequips X and transfers possession. State `inventory holder=B` while `equipment holder=A` is an automatic blocker.

### Replacement

Fixture: slot S occupied by X, explicit replace with Y. Inject failure between removal of X and insertion of Y. Assert rollback restores X, its slot binding and its modifier source, while Y remains unequipped.

### Modifier failure

Inject failure after compatibility/slot validation but before final equipment commit. Assert no `sourceActive=true`, no derived effect and no base-state mutation. Inject failure during unequip and require either complete prior state or complete successful unequip, never split state.

## No-retrogression snapshot

Before and after migration plus equip/unequip/failed operations, compare at minimum:

- ActivePlayerRef;
- PlayerStat.baseValue;
- PlayerResource.currentValue;
- non-equipment Phase-5 modifier state;
- Talent/Potential;
- Skill.baseMastery;
- Technique.baseMastery and Technique-domain `is_equipped`;
- Origin/InnateFeature/Evolution/Form/requirement gates;
- Phase-10 ItemDefinition/ItemInstance/stacks/unique possession/legacy mappings;
- untouched legacy bytes.

Equipment effects may alter derived/effective projections only through the existing Phase-5 modifier lifecycle.

## Automatic blockers

Final validation is FAIL immediately if any of these is established:

- Equipment without canonical possession;
- name/label-based item identity;
- partial multi-slot occupancy;
- dangling equipped instance after remove/transfer;
- cross-player or cross-campaign equip;
- Equipment -> Ownership inference;
- legacy inventory/CharacterPanel/Technique-equipped -> physical Equipment inference;
- second Equipment-specific modifier engine/resolver;
- direct base-state mutation by equipment effects;
- modifier remains active after successful unequip;
- failed equip activates modifier state;
- authoritative Equipment truncation;
- Phase-11 migration unreachable through production `CurrentSchema` path.

## Final validation — exact candidate `c96136964e4adb7144eee42b2b8680f153a839f2`

### CI and routing evidence

GitHub Actions run `#250` / run ID `31362782857` reports `head_sha=c96136964e4adb7144eee42b2b8680f153a839f2`, `status=completed`, `conclusion=success`.

`CurrentSchema.ensure()` routes to `MigrationManager.ensureV11()`. `ensureV11()` calls `ensureV10()` first and creates additive Equipment tables, slot/rule definitions and inventory transfer/delete guards. Production routing tests cover bootstrap, campaign switch, restore from V10 and no synthetic Equipment from legacy inventory.

### Gates that pass on code/test inspection

- slot UID/key ownership and World-Pack registration checks;
- same label/different UID remains possible;
- missing/deprecated slot rejected for new equip;
- exact ItemInstance identity and UNIQUE_INSTANCE requirement;
- static cross-player/cross-campaign possession checks;
- same ItemDefinition/different ItemInstance separation;
- explicit compatibility rules by stable UID, no item-name/category fallback;
- requested multi-slot set must exactly match rule-required slot set;
- capacity and exclusive-group checks under non-concurrent execution;
- exact instance cannot be double-equipped because of runtime check plus schema UNIQUE(campaign_id,item_instance_uid);
- ordinary transfer/remove of an already committed equipped instance fails through V11 triggers until explicit unequip;
- equip/unequip do not create OwnershipRecord and no Phase-12 runtime is introduced;
- Phase-5 ModifierStore remains the only modifier persistence/resolver foundation;
- equipment source identity uses exact ItemInstance UID and two instances of one definition remain isolated;
- modifier activation/deactivation participates in the same SQLite transaction as Equipment row/slot mutation;
- Stat base, Resource current, Skill baseMastery, Technique baseMastery, Talent and Potential remain unchanged by tested equipment effects;
- `PlayerResource.currentValue=100` is explicitly asserted before equip, while equipped and after unequip while maximum/regeneration change only as derived values;
- legacy `character_inventory` and Technique `is_equipped` create zero physical Equipment;
- >1000 slot definitions and 1001 equipment records are read without authoritative truncation and survive reopen;
- migration marker/idempotency, integrity_check and foreign_key_check are covered by Phase-11 tests.

### RELEASE BLOCKER EQ-RACE-01 — possession/equip TOCTOU can commit stale holder

`EquipmentStore.equip()` performs authoritative preconditions before opening its SQLite transaction. In particular it resolves the instance and then checks:

```text
isPossessed(characterUid,itemInstanceUid)
isEquipped(itemInstanceUid)
slot occupancy
exclusive-group conflicts
```

Only after those checks does it call `db.beginTransaction()` and insert `player_equipment` / slot bindings / source activation.

The V11 transfer/delete triggers protect only a possession mutation performed while an Equipment row already exists. They do not protect the race window before the Equipment row is inserted. The schema FK from `player_equipment` points to `(campaign_id,item_instance_uid)` in `item_instances`, not to the current `(campaign_id,character_uid,item_instance_uid)` possession row.

#### Minimal reproducer

Two concurrent callers/connections against the same campaign DB:

```text
Initial:
player_inventory_unique: holder=A, instance=X
player_equipment: no X

T1: EquipmentStore.equip(A,X,...)
    -> requireInstance(X) PASS
    -> isPossessed(A,X) PASS
    -> slot/conflict checks PASS
    -> PAUSE before beginTransaction()

T2: InventoryStore.transferUnique(A,B,X,...)
    -> transfer trigger checks player_equipment: X absent
    -> transfer commits, holder=B

T1 resumes:
    -> beginTransaction()
    -> INSERT player_equipment(character_uid=A,item_instance_uid=X)
    -> commit succeeds because FK references item_instances, not holder row

Forbidden committed result:
Inventory holder = B
Equipment holder = A
same exact itemInstanceUid = X
```

The same window allows `removeUnique(A,X)` to commit before T1 begins its transaction, after which T1 can create Equipment for an ItemInstance with no current inventory holder.

This violates EQ-A11, EQ-A13, EQ-A42, EQ-A43 and the explicit automatic blocker `Inventory holder=B / Equipment holder=A`.

### RELEASE BLOCKER EQ-RACE-02 — concurrent equip can overflow slot capacity

Slot capacity and exclusive-group validation are also performed before the equip transaction. The schema intentionally cannot encode arbitrary dynamic capacity as a simple UNIQUE constraint. Therefore two concurrent equip operations can both observe `occupancyCount=0` for a capacity-1 slot before either starts/commits its write transaction, then serialize their inserts and leave occupancy=2.

Minimal schedule:

```text
slot S capacity=1, currently empty
X and Y both valid/possessed, distinct instances/rules

T1 equip X -> occupancyCount(S)=0 -> PAUSE before beginTransaction
T2 equip Y -> occupancyCount(S)=0 -> begin/commit Y
T1 -> begin/commit X

Committed occupancy(S)=2 > capacity=1
```

The same class can bypass precomputed exclusive-group conflict checks. This violates EQ-A30, EQ-A32, EQ-A33/EQ-A34 atomic conflict semantics and the requirement that conflict resolution cannot depend on race timing.

### Why green CI does not clear these blockers

Current tests validate sequential failure/rollback and ordinary equipped-instance transfer/remove guards, but do not interleave a possession transfer/remove or competing equip between prevalidation and `beginTransaction()`. Therefore CI `#250 = SUCCESS` is compatible with the above TOCTOU defects.

### Minimal hotfix scope for CHAT-1

Do not redesign Equipment or start Phase 12. Minimal Phase-11 repair:

1. Establish the SQLite write transaction/serialization boundary before any authoritative mutable precondition that can change concurrently: possession holder, already-equipped identity, slot occupancy and exclusive-group state.
2. Re-read/revalidate those conditions inside that same transaction immediately before inserts/source activation.
3. Keep current inventory transfer/delete guards; after serialization they correctly reject a transfer/remove that loses the race after Equipment commit.
4. Add deterministic interleaving/concurrency regression tests proving neither `Inventory holder=B / Equipment holder=A` nor capacity/exclusive-group overbooking can commit.
5. Preserve existing all-or-none Equipment + slot + modifier mutation transaction.

No changes to OwnershipRecord, PlayerDomainEngine or later phases are required for this hotfix.

## Final verdict

PHASE 11 ADVERSARIAL VALIDATION: FAIL
