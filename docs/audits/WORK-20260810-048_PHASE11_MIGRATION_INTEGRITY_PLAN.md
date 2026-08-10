# WORK-20260810-048 — Phase 11 Migration / Integrity Plan

Status: READ-ONLY RUNTIME / VALIDATION PLAN

Work ID: `WORK-20260810-048`
Role: READ-ONLY MIGRATION / INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase 10 runtime: `eb8bb64f8be566982c91f1062f319078899c1e47`
Fresh master at plan creation: `138bf67b8e6af52efe66254fd2289a77804b88dc`
Phase 11 implementation work item: `WORK-20260810-046`
Allowed write scope: this report only.

This report defines migration, persistence, lifecycle, isolation and no-regression release gates for Phase 11 Equipment. It does not implement Phase 11, repair runtime, or start Phase 12 OwnershipRecord.

## 1. Canonical authority boundaries

Phase 11 must preserve the following hard split:

```text
Inventory possession
!= Equipment/loadout state
!= legal OwnershipRecord
```

The accepted Phase-10 contract provides stable `ItemDefinition`, `ItemInstance`, stack possession and unique-instance possession. Physical Equipment must bind to a legal Phase-10 inventory identity; it must not create or infer item identity from labels.

Historical compatibility surfaces are not Equipment authority:

- `character_inventory` rows are inventory evidence/possession evidence, not equipped-state evidence;
- `CharacterPanel.equipment` is a historical presentation shortcut populated from inventory names;
- `character_techniques.is_equipped` belongs to Technique-domain semantics and must not materialize physical item Equipment.

Migration must create zero synthetic equipment entries from any of those surfaces unless a future explicit equipment mapping contract proves real equipment semantics.

## 2. Required Phase-11 migration contract

Expected migration characteristics:

- additive only;
- idempotent;
- no destructive rewrite of Phase 3–10 data;
- marker written only after successful schema migration;
- all prior migrations remain reachable;
- production `CurrentSchema.ensure()` advances to Phase 11/latest;
- bootstrap, reopen, restore and campaign switch use the same latest-schema route.

Final WORK-046 validation must compare an old Phase-10 database before and after Phase-11 migration and prove semantic equality of all pre-existing authoritative state.

## 3. Required schema concepts

Exact Kotlin/table names remain implementation decisions, but final runtime must expose equivalent stable concepts for:

- World-Pack-owned equipment slot definition;
- optional loadout identity if multiple loadouts are supported;
- player equipment entry bound to a concrete `ItemInstance`;
- explicit slot occupancy/bindings;
- optional compatibility/conflict/exclusive-group rule identity;
- stable version/provenance fields;
- source identity sufficient to drive Phase-5 equipment modifiers deterministically.

No single string field such as `equipped_item_name` is sufficient.

## 4. Inventory FK / identity release gates

Equipment must reference a legal Phase-10 `ItemInstance` or an equivalently unambiguous accepted inventory identity.

Required cases:

1. existing unique ItemInstance possessed by player -> equip may proceed if slot/rules permit;
2. missing ItemInstance -> fail-loud before equipment state is written;
3. ItemInstance exists but is not held by target player -> fail-loud;
4. ItemInstance exists in another campaign -> fail-loud;
5. same ItemDefinition with two different ItemInstances -> equipment identity remains instance-specific;
6. duplicate equipment attempt for the same unique instance -> deterministic rejection unless the model explicitly supports one entry spanning multiple slots;
7. deleted/missing inventory instance target -> detectable invalid state / fail-loud, never silent empty reconciliation;
8. transferred ItemInstance -> dependent equipment state must be resolved atomically according to explicit policy;
9. removed ItemInstance -> dependent equipment state cannot remain pointing to a non-holder;
10. dangling FK/reference checks must fail integrity validation.

Hard invariant:

```text
Inventory holder = B
Equipment holder = A
same itemInstanceUid
```

must never survive a committed transaction.

## 5. Slot definition and World-Pack integrity

Slot identity is stable UID, not label.

Required tests:

- slot definition registration;
- duplicate slot UID fail-loud;
- same slot label with different stable UID remains distinct;
- World Pack B cannot hijack slot UID owned by A;
- definition version/provenance nonblank and preserved;
- deprecated/inactive slot behavior explicitly defined;
- missing slot target fail-loud;
- item-slot compatibility validated by stable UID/rule identity, never by display-name convention.

Core must not hardcode world-specific slots or item classes.

## 6. Slot compatibility / conflict matrix

Final runtime must deterministically validate at least:

- valid single-slot equip;
- incompatible slot rejection;
- same capacity-1 slot claimed by two entries;
- exclusive-group conflict;
- multi-slot equipment reserving all required slots atomically;
- partial multi-slot reservation impossible;
- missing one required slot -> whole equip fails;
- same ItemInstance cannot occupy duplicated bindings illegally;
- two distinct instances of one definition remain independently equipable if rules allow;
- conflict result independent of SQLite row order / insertion order / map iteration order.

If slot capacity >1 exists, capacity accounting must be explicit and deterministic.

## 7. Equip / unequip atomicity

Equip must be an all-or-nothing state transition.

Before commit validate:

1. campaign/player scope;
2. canonical inventory holder;
3. ItemInstance existence;
4. slot existence and World-Pack compatibility;
5. slot availability/capacity;
6. exclusive-group conflicts;
7. multi-slot completeness;
8. source identity for derived effects;
9. no unresolved legacy identity is being guessed.

Unequip must remove/deactivate only equipment state and derived source activation. It must not:

- delete inventory possession;
- delete ItemInstance;
- create or modify OwnershipRecord;
- mutate base Stat/Skill/Technique/Talent/Potential;
- rewrite PlayerResource.currentValue.

Failed equip/unequip must leave no partial slot rows, equipment rows, source state or modifiers.

## 8. Transfer/remove equipped instance — critical integrity gate

This is a release blocker class.

Required scenarios:

### Transfer equipped unique instance A -> B

Accepted policies are only explicit and atomic, e.g.:

- reject transfer while equipped; or
- transactionally unequip/deactivate effects, transfer inventory holder, then commit consistent final state.

Unacceptable:

```text
inventory: B owns/holds instance X
equipment: A still equips X
```

### Remove/destroy equipped instance

Operation must either fail while equipped or atomically resolve equipment state before inventory removal.

### Rollback/failure injection

Inject failure after equipment deactivation but before inventory transfer/removal and verify transaction rollback restores the complete prior state.

## 9. Phase-5 modifier lifecycle integration

Phase 11 must reuse existing generic Phase-5 modifier infrastructure, especially equipment lifecycle/source semantics. No second resolver or `EquipmentModifierEngine` is permitted.

Required gates:

- equip activates only equipment-origin derived source(s);
- unequip deactivates/removes source contribution;
- reopen reconstructs identical derived result;
- stable source identity tied to equipment entry or ItemInstance, never display name;
- two instances of same definition produce distinct source identities;
- same source does not duplicate after repeated reopen/ensure;
- source from player A cannot affect B;
- source from campaign A cannot affect B;
- source removal restores effective value without altering base progression;
- equipment effects may target accepted Phase-5 target kinds only;
- no direct write to `PlayerStat.baseValue`;
- no direct write to `PlayerSkill.baseMastery`;
- no direct write to `PlayerTechnique.baseMastery`;
- no direct write to Talent/Potential profiles;
- no hidden `PlayerResource.currentValue` clamp/write when maximum changes.

Required over-cap resource case:

```text
current resource = 150
equipment-derived maximum before unequip = 200
after unequip derived maximum = 100
```

Expected: current remains 150 unless a separate explicit authoritative mutation is invoked later.

## 10. Legacy non-authority gates

Final validation must explicitly prove:

- legacy `character_inventory` row does not create PlayerEquipment;
- CharacterPanel historical `equipment` presentation does not create PlayerEquipment;
- `character_techniques.is_equipped = 1` does not create physical PlayerEquipment;
- labels such as weapon/armor/worn/equipped do not auto-create slot or equipment identity;
- unresolved legacy inventory evidence cannot be equipped by name guessing;
- migration preserves all source legacy bytes untouched.

If an actual future legacy-equipment source is discovered, it must use explicit mapping with version/provenance and stable ItemInstance/slot identities. No such authority should be inferred from current presentation data.

## 11. Reopen / persistence gates

Required fixture:

1. create valid ItemInstance + inventory possession;
2. equip into one or more slots;
3. persist active equipment modifier source;
4. close database;
5. reopen via production current-schema path;
6. verify exact equipment entry UID, ItemInstance UID, slot bindings, version/provenance and derived effect;
7. unequip;
8. close/reopen;
9. verify inventory possession remains and equipment-derived effect is absent.

Repeated schema ensure must not duplicate equipment entries, slot bindings or modifiers.

## 12. Backup / restore gates

Required tests:

- backup of Phase-11 campaign preserves inventory + equipment + slot bindings + modifier source identity;
- restore to same campaign reproduces exact equipment state;
- restore of old Phase-10 database reaches latest Phase-11 schema without inventing Equipment;
- restored legacy CharacterPanel/inventory presentation does not become equipment authority;
- pre-restore safety backup continues working;
- restore preserves campaign/player identity and does not cross-bind ItemInstances.

## 13. Campaign switch gates

Switch A -> B must run latest schema for B and return only B equipment.

Required:

- same player UID in two campaigns remains isolated;
- same ItemInstance text UID in two campaigns is resolved according to Phase-10 campaign scoping and cannot leak equipment state;
- switching back restores original campaign loadout exactly;
- no active-player heuristic migrates/equips first available inventory item.

## 14. Phase 3–10 no-regression snapshot

Before/after Phase-11 migration compare semantic equality of:

- ActiveCampaignRef / campaign selection;
- ActivePlayerRef;
- PlayerState persistent/derived/runtime semantics;
- PlayerStat.baseValue;
- PlayerResource.currentValue;
- stat/resource reconciliation aliases;
- Phase-5 modifiers not owned by equipment;
- Talent/Potential profiles;
- Skills / baseMastery / reconciliation;
- Techniques / baseMastery / history / resource mappings / `is_equipped` technique state;
- Phase-9 origin/innate/evolution/form state;
- Phase-9.1 requirement gates;
- Phase-10 ItemDefinition/ItemInstance/inventory stacks/unique possession/legacy mappings;
- all untouched legacy bytes.

Phase-11 migration must not create OwnershipRecord tables/rows or mutate financial state.

## 15. Scale / authoritative read gates

Authoritative Equipment store/repository reads must not contain presentation limits.

Required scale test:

- >1000 equipment entries/slot-binding state where valid fixtures permit;
- >1000 slot definitions or bindings where applicable;
- close/reopen preserves exact count;
- no `LIMIT 50/60` or CharacterPanel historical naming leaks into authoritative Equipment read;
- ContextBuilder, if extended, must consume canonical Equipment reconciliation first and apply presentation budgeting only afterwards.

## 16. Database integrity / FK gates

After migration and adversarial mutation scenarios:

```sql
PRAGMA integrity_check;
```

must return `ok`.

```sql
PRAGMA foreign_key_check;
```

must return no rows under the adopted FK policy.

Explicitly test:

- missing ItemInstance;
- wrong campaign ItemInstance;
- missing slot definition;
- missing compatibility target;
- dangling equipment->inventory reference;
- duplicate slot binding;
- duplicate equipment entry UID;
- World-Pack ownership mismatch;
- moved/deleted instance with stale equipment row.

## 17. Production routing gates

Expected production chain after WORK-046:

```text
LocalGameStore.ensureCurrentSchema()
-> CurrentSchema.ensure()
-> Phase 11/latest migration
-> prior chain through Phase 10 and earlier
```

Required tests:

- bootstrap old/new campaign -> Phase 11 marker/tables;
- ordinary reopen -> Phase 11;
- restore old Phase-10 backup -> Phase 11;
- campaign switch to Phase-10 DB -> Phase 11;
- repeated `CurrentSchema.ensure()` idempotent;
- migration marker exactly once;
- partial migration failure does not leave marker with incomplete schema.

Direct `MigrationManager.ensureV11()` tests alone are insufficient.

## 18. Required final validation matrix

- P11-01 additive migration: REQUIRED
- P11-02 latest `CurrentSchema` routing: REQUIRED
- P11-03 bootstrap: REQUIRED
- P11-04 reopen: REQUIRED
- P11-05 backup/restore: REQUIRED
- P11-06 campaign switch: REQUIRED
- P11-07 migration idempotency: REQUIRED
- P11-08 Phase 3–10 no-regression: REQUIRED
- P11-09 integrity/FK: REQUIRED
- P11-10 legacy inventory does not grant equipment: REQUIRED
- P11-11 CharacterPanel equipment presentation does not grant equipment: REQUIRED
- P11-12 Technique `is_equipped` does not grant physical equipment: REQUIRED
- P11-20 stable slot UID: REQUIRED
- P11-21 World-Pack slot ownership: REQUIRED
- P11-22 duplicate slot UID fail-loud: REQUIRED
- P11-23 compatibility validation: REQUIRED
- P11-24 slot conflict validation: REQUIRED
- P11-25 exclusive-group validation: REQUIRED
- P11-26 multi-slot atomic reservation: REQUIRED
- P11-30 ItemInstance FK identity: REQUIRED
- P11-31 wrong holder rejection: REQUIRED
- P11-32 moved/deleted instance consistency: REQUIRED
- P11-33 same definition / different instance separation: REQUIRED
- P11-34 dangling references fail-loud: REQUIRED
- P11-40 equipment modifier activation: REQUIRED
- P11-41 unequip deactivation: REQUIRED
- P11-42 source identity stable after reopen: REQUIRED
- P11-43 multiple-instance source isolation: REQUIRED
- P11-44 base Stat/Skill/Technique/Talent/Potential unchanged: REQUIRED
- P11-45 no current-resource clamp/write: REQUIRED
- P11-50 equipped-instance transfer/removal atomic policy: REQUIRED
- P11-51 failure rollback leaves consistent inventory/equipment state: REQUIRED
- P11-60 >1000 equipment/slot state no authoritative truncation: REQUIRED
- P11-61 exact WORK-046 JVM/build/CI success: REQUIRED

## 19. Final validation procedure after WORK-046

When CHAT-1 publishes final WORK-046 resultCommit, CHAT-3 must:

1. re-check fresh master and exact SHA;
2. diff accepted Phase-10 runtime/current baseline -> WORK-046;
3. inspect schema/migration marker and `CurrentSchema` routing;
4. inspect slot definitions, equipment entries and slot bindings;
5. verify all Equipment references resolve to legal Phase-10 ItemInstances held by the same player/campaign;
6. verify legacy presentation sources create zero synthetic equipment;
7. test single-slot/multi-slot/conflict/exclusive-group atomicity;
8. test equip/unequip modifier lifecycle and no-retrogression;
9. test transfer/remove equipped instance consistency and rollback;
10. test reopen, backup/restore and campaign switch;
11. verify Phase 3–10 semantic snapshot unchanged;
12. verify >1000 authoritative state without truncation;
13. verify integrity/FK;
14. verify exact candidate full JVM tests, signed build and CI;
15. update only this report with one exact verdict.

Final verdict must be exactly one of:

`PHASE 11 INTEGRITY VALIDATION: PASS`

or

`PHASE 11 INTEGRITY VALIDATION: FAIL`

## 20. Current checkpoint

Fresh master during plan creation is `138bf67b8e6af52efe66254fd2289a77804b88dc`, containing the final read-only Phase-10 integrity report on top of accepted Phase-10 runtime `eb8bb64f8be566982c91f1062f319078899c1e47`.

CI run #243 for fresh master completed `SUCCESS`.

WORK-044 confirms that no accepted dedicated physical Equipment store exists yet, that legacy `character_inventory` / CharacterPanel equipment labels and Technique `is_equipped` are not Equipment authority, and that Phase 11 should bind to Phase-10 ItemInstance identities while reusing Phase-5 `ModifierLifecycle.EQUIPMENT` instead of creating another resolver.

`PHASE 11 MIGRATION / INTEGRITY PLAN READY — WAITING FOR WORK-20260810-046 RESULT COMMIT`
