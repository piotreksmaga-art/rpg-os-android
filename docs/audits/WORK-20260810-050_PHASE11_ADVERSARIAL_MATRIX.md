# WORK-20260810-050 — Phase 11 Equipment Adversarial Matrix

Status: FINAL HOTFIX REVALIDATION COMPLETE

Work ID: `WORK-20260810-050`
Role: READ-ONLY PHASE 11 ADVERSARIAL REVALIDATOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-10 runtime: `eb8bb64f8be566982c91f1062f319078899c1e47`
Previous Phase-11 candidate: `c96136964e4adb7144eee42b2b8680f153a839f2`
Previous verdict: `PHASE 11 ADVERSARIAL VALIDATION: FAIL`
Hotfix candidate audited: `c87193a69136a6680102779e4f0cd3d90a616d41`
Exact CI: GitHub Actions `#259`, run ID `31369089655`, `head_sha=c87193a69136a6680102779e4f0cd3d90a616d41`, `status=completed`, `conclusion=success`.

## Canonical boundaries revalidated

- Inventory possession != Equipment state != OwnershipRecord.
- Equipment requires exact `ItemInstance` identity; stackable commodities do not receive synthetic Equipment identity.
- Stable UID is authoritative; labels/names are presentation only.
- Equipment uses the existing Phase-5 `ModifierLifecycle.EQUIPMENT` and `DerivedValueResolver`; no Equipment-specific modifier engine/resolver exists.
- Equipment effects are derived only and do not rewrite `PlayerStat.baseValue`, `PlayerResource.currentValue`, `PlayerSkill.baseMastery`, `PlayerTechnique.baseMastery`, Talent, or Potential.
- Legacy `character_inventory`, CharacterPanel `equipment`, and `character_techniques.is_equipped` do not synthesize physical Equipment.
- Phase 11 introduces no OwnershipRecord mutation path and does not start Phase 12.

## Previous blockers

The previous candidate `c96136964...` failed on two TOCTOU classes:

- `EQ-RACE-01`: possession could change after application precheck but before Equipment commit, allowing `Inventory holder=B / Equipment holder=A`.
- `EQ-RACE-02`: slot capacity/exclusive-group checks occurred before the authoritative write transaction and could become stale under competing equip attempts.

## Hotfix architecture

`ensureV11()` now recreates authoritative SQLite write guards on every execution:

- `trg_equipment_possession_guard`
- `trg_equipment_rule_exclusive_guard`
- `trg_equipment_slot_parent_scope_guard`
- `trg_equipment_slot_capacity_guard`
- `trg_equipment_slot_exclusive_guard`
- `trg_equipped_instance_inventory_delete_guard`
- `trg_equipped_instance_inventory_transfer_guard`

The function explicitly performs `DROP TRIGGER IF EXISTS` followed by `CREATE TRIGGER` inside the V11 transaction. Therefore databases already carrying the `RPGOS-11.0-EQUIPMENT` migration marker receive the corrected guards on the next normal `ensureV11()` / `CurrentSchema.ensure()` without reinstall, destructive migration, or a new campaign.

SQLite is the authoritative write boundary. Concurrent writers are serialized, while the triggers evaluate against the authoritative state at the actual insert/update/delete operation rather than relying on stale application pre-reads.

## EQ-RACE-01 revalidation — PASS

### transfer -> equip

Initial state:

```text
Inventory holder=A, instance=X
Equipment X absent
```

If transfer `A -> B` commits before Equipment insertion, the later `INSERT player_equipment(character=A, instance=X)` executes `trg_equipment_possession_guard`. No row `(campaign, A, X)` exists in `player_inventory_unique`, so the Equipment write aborts.

Forbidden final state cannot commit:

```text
Inventory holder=B
Equipment holder=A
itemInstanceUid=X
```

### equip -> transfer

If Equipment insertion commits first, both inventory mutation guards see the committed `player_equipment` row. `transferUnique()` or `removeUnique()` then aborts until explicit unequip.

Thus both deterministic interleavings are safe:

```text
transfer wins -> equip aborts
equip wins    -> transfer/remove aborts
```

The hotfix test `stalePossessionPrecheckCannotCommitEquipmentAfterTransfer` also directly performs the stale post-transfer `player_equipment` insert and verifies fail-loud with no Equipment row left behind.

**EQ-RACE-01: CLOSED.**

## EQ-RACE-02 revalidation — PASS

For a slot with `capacity=1`, `trg_equipment_slot_capacity_guard` executes on every `player_equipment_slots` insert. It determines the incoming entry's loadout and counts already committed occupancy for the same campaign, character, slot and loadout.

Two competing equip attempts can no longer both commit occupancy=2:

```text
writer 1 slot bind commits
writer 2 slot bind evaluates authoritative occupancy >= capacity
writer 2 aborts
```

If writer 2 reaches SQLite first, the roles reverse. At most one committed winner remains.

The test `staleCapacityAndExclusivePrechecksCannotCommitSecondWinnerAndRollbackMultiSlot` explicitly attempts a manual second parent+slot write after a capacity winner and verifies rollback to one Equipment row / one slot occupant.

**EQ-RACE-02: CLOSED.**

## Exclusive/conflict race — PASS

Rule-level conflicts are guarded by `trg_equipment_rule_exclusive_guard` at `player_equipment` insertion. Slot-level conflicts are guarded by `trg_equipment_slot_exclusive_guard` at slot-binding insertion. Both compare within campaign + character + loadout and World-Pack-scoped exclusive identity.

Because the checks execute at the serialized SQLite write boundary, stale application conflict prechecks cannot produce two committed conflicting winners.

## Multi-slot race / rollback — PASS

`EquipmentStore.equip()` writes the parent Equipment entry, all required slot bindings, and Phase-5 source activation in one SQLite transaction. If any required slot binding fails capacity/exclusive validation, the transaction rolls back the parent and any earlier slot bindings. Modifier activation occurs only after slot writes and is rolled back with the same transaction on failure.

No reproducer was found for partial multi-slot state.

## Already-equipped race — PASS

`player_equipment` has `UNIQUE(campaign_id,item_instance_uid)`. Therefore two attempts to equip the same exact instance cannot both commit even if both pass stale application prechecks.

## Modifier lifecycle — PASS

Equipment modifiers use exact `itemInstanceUid` as `sourceUid` and lifecycle `EQUIPMENT` in the existing Phase-5 `ModifierStore`.

- failed equip: transaction rollback prevents committed source activation;
- successful equip: source becomes active within the same transaction;
- successful unequip: source becomes inactive before Equipment deletion, in the same transaction;
- failed unequip: rollback restores the previous Equipment/source state;
- two instances of the same ItemDefinition retain distinct source identities.

No `EquipmentModifierEngine` or `EquipmentResolver` exists.

## No-retrogression — PASS

The Phase-11 modifier integration test snapshots authoritative values and verifies semantic equality across equip/unequip. In particular:

```text
PlayerResource.currentValue = 100
before equip  = 100
during equip  = 100
after unequip = 100
```

while maximum/regeneration change only in derived resolution. The same test verifies unchanged Stat base, Skill baseMastery, Technique baseMastery, Talent and Potential.

## Inventory boundary — PASS

An equipped exact instance cannot be transferred or removed through ordinary Inventory mutation. SQLite guards reject both until explicit unequip.

Unequip removes only physical Equipment state and modifier activity; it does not remove Inventory possession or create/delete the `ItemInstance`.

No committed dangling state `Inventory holder=B / Equipment holder=A` was found after the hotfix.

## Ownership boundary — PASS

Phase 11 does not create `ownership_records_v2` or any OwnershipRecord mutation API. Equip, unequip and inventory transfer do not infer legal/historical ownership.

## Legacy inference attacks — PASS

No automatic physical Equipment state is created from:

- `character_inventory` rows;
- CharacterPanel's historical/presentation `equipment` label;
- `character_techniques.is_equipped`;
- item names/categories/labels.

Physical Equipment requires typed exact ItemInstance possession plus explicit compatibility/slot identities.

## Identity / isolation — PASS

Validated from runtime/schema/tests:

- duplicate slot UID/key fail-loud;
- slot and compatibility World-Pack ownership checks;
- same display label with distinct stable UIDs is not identity merging;
- missing/unpossessed/deprecated item/slot conditions fail-loud for new equip;
- exact instances of the same definition remain distinct;
- campaign and player Equipment reads/writes are scoped;
- cross-player possession cannot authorize equip;
- cross-campaign instance state does not become cross-campaign Equipment authority;
- modifier source identity is exact instance-scoped.

## Scale / truncation — PASS

Tests cover >1000 slot definitions and 1001 Equipment records with reopen equality. Authoritative Equipment reads iterate complete result sets and do not use presentation limits such as LIMIT 50/60.

## Migration / already-migrated V11 refresh — PASS

`CurrentSchema.ensure()` routes to V11 through the existing migration chain. The hotfix retains `RPGOS-11.0-EQUIPMENT` and refreshes corrected triggers every time `ensureV11()` executes, independently of whether the migration marker already exists.

Therefore an already-migrated V11 DB receives the guards on reopen/bootstrap/restore/campaign switch without destructive migration.

The production routing contract from Phase 11 remains unchanged: bootstrap, reopen, restore and campaign switching route through current schema.

## Database safety — PASS

Phase-11 tests exercise `PRAGMA integrity_check = ok` and empty `PRAGMA foreign_key_check` after Equipment operations, including race-guard fixtures. The hotfix adds write guards without removing Phase-10/earlier foreign-key relationships.

## Phase 3–10 regression review — PASS

The runtime diff from the failed candidate to the hotfix is limited to Phase-11 migration/write guards and Equipment persistence tests. No mutation path was added for ActivePlayerRef, Stats/Resources authority, Talent/Potential, Skill/Technique base mastery, Phase-9 origin/innate/evolution/form state, legacy inventory bytes, or Phase-10 reconciliation authority.

## CI evidence

Independently verified GitHub Actions:

```text
run number: 259
run ID: 31369089655
head SHA: c87193a69136a6680102779e4f0cd3d90a616d41
status: completed
conclusion: success
```

The CI record identifies the exact hotfix SHA. The green result is treated as supporting evidence, not as a substitute for the runtime/write-boundary review above.

## Blocking defects

None reproduced on `c87193a69136a6680102779e4f0cd3d90a616d41` within the Phase-11 contract.

The previous `EQ-RACE-01` and `EQ-RACE-02` release blockers are closed by authoritative SQLite write guards and deterministic serialized-write behavior.

## Final verdict

PHASE 11 ADVERSARIAL REVALIDATION: PASS
