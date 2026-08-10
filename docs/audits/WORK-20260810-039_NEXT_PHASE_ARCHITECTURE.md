# WORK-20260810-039 — Next Phase Architecture

Status: READ-ONLY RUNTIME / FUTURE PHASE ARCHITECTURE AUDIT

Work ID: `WORK-20260810-039`
Worker: `CHAT-4`
Role: NEXT-PHASE ARCHITECTURE AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase 8 runtime: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Fresh master observed during audit: `3a7b2429dd177a8c6d410f3bc4255e721d99cc30`
Fresh master change above Phase 8: read-only Phase-9 migration/integrity planning report (`WORK-20260810-038`); no accepted Phase-9 runtime result was found at report preparation time.
Last exact accepted runtime CI evidence observed from final Phase-8 validation: GitHub Actions run #177, head SHA `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`, conclusion `success`.

This document is architecture/audit only. It does not implement Phase 10 runtime, schema, migration, Equipment, OwnershipRecord, PlayerCommand, PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider, ProgressionEngine, DevelopmentProject, or CharacterPanelSnapshot v2.

---

## 1. Exact next phase from the current Roadmap

The current canonical Roadmap still orders Phase A as:

```text
8. Technique model
9. Innate/Racial/Bloodline/Evolution runtime model
10. Inventory model
11. Equipment domain/loadout model
12. OwnershipRecord domain
13. Financial Ledger / Economy model
```

Therefore the exact phase after Phase 9 is:

# PHASE 10 — Inventory model

Roadmap status is currently PARTIAL because `character_inventory` is already read, but canonical item identity, ownership semantics and legal mutation path are not yet implemented.

This report designs only the Phase-10 input architecture. Implementation remains blocked until Phase 9 is formally accepted by the coordinator.

---

## 2. Executive conclusion

The repository already exposes inventory-like data, but the existing surface is not yet a safe Inventory domain.

Confirmed runtime evidence:

1. `CharacterPanelReader` reads `character_inventory` scoped by active/player UID.
2. That read currently selects only `item_name` and places the result into a UI collection named `equipment`.
3. The current `ContextBuilder` does not expose a canonical Inventory repository/read model comparable to Phase-7 Skill or Phase-8 Technique reconciliation.
4. `PlayerStateStore` does not currently expose `character_inventory` as a canonical persistent Player State collection.
5. MASTER explicitly requires `Inventory != Equipment`, stable UID identity, quantity semantics for stackables, separate OwnershipRecord semantics and a legal domain mutation path.
6. Equipment is the following roadmap phase, so Phase 10 must not prematurely implement loadout/equip-slot authority.
7. OwnershipRecord is Phase 12, so Phase 10 must not silently equate physical possession/location with legal ownership.

The architectural priority is therefore:

```text
ITEM DEFINITION
!= INVENTORY ENTRY / POSSESSION
!= EQUIPMENT/LOADOUT STATE
!= OWNERSHIP RECORD
!= ASSET/FINANCIAL VALUE
```

Phase 10 should canonicalize **what item identity exists and what quantity/instances a character currently possesses/carries/controls in inventory**, while preserving the boundary that Equipment and Ownership are separate future domains.

The most dangerous existing regression vector is the current UI naming behavior:

```text
character_inventory.item_name
-> CharacterPanelSnapshot.equipment
```

That is a presentation shortcut, not evidence that every inventory row is equipped.

---

## 3. Canonical Phase-10 semantics

### 3.1 Inventory definition

Inventory is a character-scoped collection of item/commodity presence state.

It answers questions such as:

- which canonical item identity or item instance is present in this character's inventory,
- how many units are present when the item is stackable,
- which concrete unique instance is present when uniqueness matters,
- optional container/location-within-inventory state if justified by real data,
- relevant persistent condition/metadata only when item state truly belongs to the inventory instance,
- provenance/version of the inventory entry.

It does **not** answer:

- whether the item is equipped,
- who legally owns it,
- its current market value,
- whether it is a permanent character trait,
- whether the item grants a Skill/Technique/mastery,
- whether a modifier is active,
- whether the item is a financial asset in accounting terms.

### 3.2 Inventory != Equipment

MASTER explicitly defines:

```text
Inventory != Equipment
```

Therefore:

- inventory presence must not mean equipped,
- removing an item from a future equipment slot must not automatically delete the inventory item unless an explicit transaction moves/destroys/transfers it,
- equipping must not clone quantity,
- an equipped unique item must reference the same stable item instance/identity represented by inventory/possession state,
- Phase 10 must not introduce slot/loadout rules that belong to Phase 11.

### 3.3 Inventory != Ownership

MASTER explicitly states that item location does not imply ownership.

Therefore:

```text
character possesses item
!= character legally owns item
```

Examples that the model must be able to support later:

- borrowed item,
- stolen item,
- entrusted organization item,
- rented item,
- collateral,
- item carried for another person,
- shared or organization-owned item.

Phase 10 may preserve/import existing ownership-like evidence, but authoritative ownership belongs to Phase 12 `OwnershipRecord`.

### 3.4 Inventory != Economy

A price/value field, if present in legacy data, must not become an authoritative financial ledger entry.

Phase 13 owns ledger/economy semantics.

Phase 10 may persist item acquisition cost or descriptive nominal value only if the existing data proves that field is item metadata. It must not treat it as current cash, net worth or transaction history.

### 3.5 Definition vs instance vs stack

The Core must distinguish at least:

```text
ItemDefinition
= World-Pack/content-owned type identity

Inventory stack/holding
= character/campaign quantity of a stackable definition

Unique item instance
= stable per-instance identity when individuality matters
```

A single flat row such as:

```text
item_name = "Sword"
quantity = 3
```

must not be forced on every universe/item type.

### 3.6 Stable UID > name

`item_name` is presentation/legacy evidence, not sufficient identity.

Hard invariant:

```text
same item_name
DOES NOT IMPLY
same item identity
```

Different World Packs may define identically named items with different UIDs and semantics.

Renaming display text must not change persistent inventory identity.

---

## 4. Confirmed legacy/runtime inventory surfaces

### 4.1 `character_inventory`

The current Character Panel reads:

```sql
SELECT item_name
FROM character_inventory
WHERE entity_uid=?
ORDER BY item_name
```

This proves at minimum that the legacy table exists in supported campaign schemas with:

- `entity_uid`,
- `item_name`.

The audit must not infer additional columns that were not observed directly in current source.

Phase-10 implementation preflight must execute a real schema/row inventory on campaign databases before choosing migration semantics:

```sql
PRAGMA table_info(character_inventory);
PRAGMA index_list(character_inventory);
PRAGMA foreign_key_list(character_inventory);
SELECT * FROM character_inventory LIMIT ...;
```

Required questions:

- Is there an existing item UID?
- Is there a row UID?
- Is quantity represented?
- Are unique instances distinguishable?
- Are equipped flags embedded?
- Are condition/durability fields embedded?
- Are acquisition/source fields embedded?
- Are item type/category/rarity fields duplicated in save state?
- Are price/value fields present?
- Are container/location fields present?
- Can duplicate rows represent quantity?
- Are null/blank names possible?
- Are multiple players represented safely by `entity_uid`?

No migration policy should be finalized before this actual inventory.

### 4.2 CharacterPanel

Current presentation behavior:

```text
character_inventory.item_name -> equipment: List<String>
```

This is semantically unsafe as an authority source.

Phase 10 should treat it only as legacy presentation compatibility until Phase 11 provides actual Equipment/loadout state.

Recommended migration path for UI/read model:

- Phase 10 may expose a dedicated `inventory` projection through repository/context if minimally needed.
- It should not rename legacy inventory rows into authoritative equipment state.
- CharacterPanel v1 should remain presentation-only and must not become migration evidence.
- CharacterPanelSnapshot v2 remains a later roadmap phase.

### 4.3 ContextBuilder

Current ContextBuilder has canonical typed reconciliation for Skills and Techniques but no corresponding canonical Inventory read in the inspected runtime.

This creates an important design requirement:

Phase 10 should not add a raw bounded SQL query and call it authoritative. Prefer a dedicated `InventoryStore`/repository read contract and let ContextBuilder consume a bounded presentation projection from it later.

Any ContextBuilder `LIMIT` must remain presentation-only; the authoritative Inventory repository must never silently truncate.

### 4.4 PlayerStateStore

The inspected `PlayerStateStore` exposes stats, skills, techniques, finances, organizations, goals, position and injuries, but not `character_inventory`.

Phase 10 therefore needs an explicit decision:

- either integrate canonical inventory into `PlayerStateSnapshot.persistent`,
- or expose it through the canonical Player/Inventory repository while keeping PlayerStateSnapshot stable until its later v2 redesign.

The choice must not create two authoritative inventory truths.

### 4.5 StatePatch / mutation surfaces

MASTER says AI/UI must not mutate inventory directly. Current project still has broader Single Truth Mutation Path work later in the Roadmap.

Phase 10 should therefore avoid inventing a second permanent mutation engine.

If a minimal save/update API is necessary for tests/runtime, it must be architected so it can later sit behind:

```text
PlayerCommand
-> PlayerDomainEngine
-> PlayerChangeSet
-> transaction/commit
```

and should not legitimize arbitrary AI-generated row writes.

### 4.6 World Pack / content databases

Item definition data may exist in bundled World Pack SQLite/content assets. This audit did not decode every binary SQLite table through the GitHub connector.

Phase-10 implementation preflight must therefore inventory real World Pack tables/columns matching concepts such as:

```text
item
inventory
weapon
armor
tool
consumable
material
commodity
artifact
container
currency-item
```

No Core hardcoding of Naruto/Bleach item names or categories is permitted.

---

## 5. Authoritative ownership split

### 5.1 World Pack/content authority

World Pack definitions should own item type semantics:

- stable item definition UID,
- World Pack UID,
- stable key,
- display name,
- generic category/type metadata,
- stackability policy,
- unique-instance requirement policy,
- optional unit/quantity semantics,
- definition version,
- provenance,
- active/deprecated status,
- optional generic rule/binding references,
- optional metadata for later Equipment/crafting/economy integration.

### 5.2 Campaign-character inventory authority

Campaign state should own:

- character UID,
- item definition UID or unique instance UID,
- quantity for stackable holdings,
- inventory state/status,
- optional container/location reference if truly part of inventory,
- entry version,
- provenance,
- acquisition/event evidence if available,
- unique instance state only where semantically justified.

### 5.3 Equipment authority — NOT PHASE 10

Phase 11 should own:

- equipped/not equipped,
- slot/loadout identity,
- wielded/active equipment state,
- conflict/exclusivity rules,
- equipment-origin modifier lifecycle.

Phase 10 may expose whether legacy data contains equipment-like evidence, but must not canonicalize it as Equipment unless Phase 11 mapping explicitly does so later.

### 5.4 Ownership authority — NOT PHASE 10

Phase 12 should own legal/economic ownership relationships.

Inventory may reference future OwnershipRecord UID, but Phase 10 should not fabricate ownership rows from possession.

---

## 6. Proposed generic Core model

Exact Kotlin/table names remain implementation decisions. The following is the recommended semantic contract.

### 6.1 `ItemDefinition`

```text
itemUid                 stable type identity
worldPackUid            definition owner
key                     stable non-localized key
displayName             presentation only
itemKindUid/category    generic/open category identity
stackPolicy             STACKABLE | UNIQUE_INSTANCE | DEFINITION_QUANTITY or equivalent
quantityUnitUid?        optional semantic unit
definitionVersion       >= 1
provenance              nonblank
status                  ACTIVE | DEPRECATED
metadata?               opaque/extensible data
```

Core item kinds must remain universe-agnostic.

Do not hardcode specific fictional weapon/material names.

### 6.2 `ItemInstance`

Required for unique/individualized items.

```text
instanceUid             stable unique instance identity
itemUid                 definition UID
campaignId              campaign scope
instanceVersion         >= 1
provenance              creation/import source
createdEventUid?        optional historical source
conditionState?         only if real semantics justify it
customName?             presentation override only
metadata?               opaque persistent instance state
```

Do not create one instance per commodity unit when a stack is sufficient.

### 6.3 `PlayerInventoryEntry`

Recommended shape:

```text
campaignId
characterUid
entryUid                stable holding/entry identity OR deterministic composite identity
itemUid
instanceUid?            required for unique-instance entries
quantity                required for stackable entries
inventoryState          PRESENT/HELD/etc. generic state if needed
containerUid?           optional inventory/container relation
entryVersion
provenance
acquiredChapter?
acquiredEventUid?
```

Hard checks:

- stackable entry: quantity finite/integer/decimal according to definition unit policy and > 0 unless zero rows are explicitly allowed as tombstones (prefer delete/archive instead),
- unique entry: quantity must semantically equal one or be absent,
- unique instance cannot appear simultaneously in multiple active inventory entries in the same campaign unless the future model explicitly permits shared custody,
- definition/instance World Pack identity must be compatible,
- campaign and player scope are explicit.

### 6.4 Optional `InventoryContainerDefinition` / containment

Do not add this unless real legacy/runtime data requires bags/containers/locations now.

If needed, model containment separately rather than encoding nested inventory paths inside item names.

Cycle protection is mandatory if container nesting exists:

```text
container A -> B -> A
```

must fail deterministically.

---

## 7. Stackable vs unique identity

This is the central Phase-10 semantic split.

### Stackable commodity

One canonical definition can be represented by quantity:

```text
(character, itemUid) -> quantity N
```

provided units are interchangeable under the World Pack definition.

### Unique item

A unique item requires stable `instanceUid`.

Two items with the same display name can still be different instances with different history, condition, provenance or ownership.

### Semi-fungible items

If items share a definition but condition/properties make them non-interchangeable, they should not be silently merged into one quantity stack.

World Pack definition/policy should decide whether stacking is legal.

Core must not guess based on name/category.

---

## 8. Legacy compatibility and reconciliation

Phase 10 should reuse lessons from Phase 4, 7 and 8:

```text
legacy evidence
-> preserve losslessly

explicit mapping
-> canonical typed identity

unmapped ambiguity
-> unresolved/fail-loud in canonical authoritative input
```

### 8.1 Legacy name-only row

If only `item_name` is known, the row must remain visible as unresolved inventory evidence.

Do not synthesize a World-Pack-owned ItemDefinition simply from the label unless there is an explicit canonical mapping contract.

### 8.2 Explicit mapping

Recommended generic mapping:

```text
LegacyInventoryMapping {
  campaignId
  legacyInventoryIdentity
  canonicalItemUid
  canonicalInstanceUid?   // only if explicitly justified
  worldPackUid
  mappingVersion
  provenance
}
```

The exact legacy identity must be deterministic. If the old row has no stable UID, migration may derive a deterministic compatibility UID from immutable row identity fields, but it must not rely on SQLite row order.

### 8.3 Mixed legacy + typed state

If a legacy row and typed item appear to represent the same logical inventory holding:

- WITHOUT explicit mapping: do not silently merge by name; expose unresolved ambiguity/fail-loud for canonical mutation/resolution paths.
- WITH explicit mapping: typed representation becomes canonical read authority for that mapped legacy identity; legacy bytes remain intact.

### 8.4 Duplicate names

Same `item_name` in two World Packs must remain separate until explicit mapping proves identity.

### 8.5 Legacy bytes

Migration must be additive and lossless.

Do not delete or rewrite legacy `character_inventory` during Phase 10 solely to make the typed model cleaner.

---

## 9. Relationship with Phase 3–9

### Phase 3 — Player State

Inventory is PERSISTENT player state.

Temporary use/equipment effects are not inventory authority.

ActivePlayerRef remains authoritative and no inventory migration may choose a first/random player.

### Phase 4 — Stats/Resources

Item possession must not directly rewrite:

- `PlayerStat.baseValue`,
- `PlayerResource.currentValue`.

If an item later has effects, those should travel through legal Equipment/consumption/domain mechanics and Phase-5 derived modifiers where appropriate.

### Phase 5 — Modifiers

Inventory presence alone should not automatically activate modifiers.

Especially:

```text
item in bag
!= equipment modifier active
```

Future Equipment Phase 11 should control equipment lifecycle/source activation.

Consumable item use may later create a temporary modifier through a legal domain action, not through inventory read itself.

### Phase 6 — Talent/Potential

Item possession cannot directly rewrite Talent or Potential.

### Phase 7 — Skill

Item possession cannot directly set Skill mastery.

A tool/weapon may satisfy a requirement or contribute a derived effect later, but it cannot copy metadata into `PlayerSkill.baseMastery`.

### Phase 8 — Technique

Inventory may satisfy a Technique requirement only through explicit stable UID relationships/rules.

Possession must not auto-create/learn a Technique.

Generic Technique resource cost bindings remain separate from inventory consumption unless a future rule explicitly consumes an item.

### Phase 9 — Innate/Evolution

Inventory must not grant innate ownership/evolution merely because an item name/description implies it.

If an item legally causes a permanent mutation/evolution later, that must be a committed domain transition with explicit rule/provenance, not an inventory side effect.

---

## 10. Phase 11 boundary — Equipment

Phase 10 must deliberately leave these unresolved for the next phase:

- slot definitions,
- loadouts,
- equipped state,
- weapon hand/body slot rules,
- mutually exclusive equipment,
- equipment source lifecycle,
- equipment-derived modifiers,
- active/wielded state,
- equip/unequip commands.

However Phase 10 must make Phase 11 possible by providing stable item/instance identity.

Recommended future relationship:

```text
EquipmentLoadoutEntry
-> references ItemInstance or InventoryEntry stable identity
```

Never duplicate the item into a separate equipment object with independent quantity/identity.

---

## 11. Phase 12 boundary — OwnershipRecord

Phase 10 must also preserve the ability to model possession without ownership.

Recommended future relationship:

```text
OwnershipRecord.assetUid
-> ItemInstance UID for unique items
```

For stackable commodities, ownership may later reference a fungible asset/account/lot representation depending on the Phase-12 architecture.

Do not over-design this now.

---

## 12. Mutation architecture

Phase 10 is still before the later PlayerCommand / PlayerChangeSet / PlayerDomainEngine roadmap items, but it must not make those impossible.

Canonical future command families include:

```text
AcquireItem
TransferItem
ConsumeItem
DestroyItem
SplitStack
MergeStack
MoveInventoryEntry
```

Phase 10 may expose minimal store APIs needed for persistence tests, but they should validate invariants and be explicitly understood as repository-level persistence mechanisms, not AI-authoritative mutation permission.

No direct:

```text
AI text -> INSERT character_inventory
```

should become the architectural contract.

---

## 13. Migration strategy

If Phase 10 adds schema, use an additive latest-schema migration after accepted Phase 9.

Conceptually:

```text
CurrentSchema.ensure()
 -> prior phases
 -> Phase 9
 -> Phase 10
```

Potential tables:

```text
item_definitions_v2
item_instances
player_inventory_entries
legacy_inventory_mappings
```

Names are not mandated.

Migration requirements:

- idempotent,
- no legacy bytes removed,
- no name-based auto-canonicalization,
- no ActivePlayerRef change,
- no Phase 4–9 authority mutation,
- production bootstrap reaches latest schema,
- restore reaches latest schema,
- campaign switch reaches latest schema,
- schema marker cannot be inserted without required objects.

---

## 14. Required implementation preflight inventory

Before Phase-10 code is written, CHAT implementing the phase must inventory real schemas/data for:

### Save DB

- `character_inventory`,
- any item-instance table,
- any equipment/loadout table,
- any item transaction/history table,
- any crafting/material tables,
- any loot/reward tables,
- any ownership/asset references that mention items.

### World Pack DB/content

- item definitions,
- weapon/armor/tool definitions,
- consumables,
- materials/commodities,
- unique/canon artifacts,
- categories/tags,
- rule/cost bindings.

### Runtime/read surfaces

- CharacterPanel,
- ContextBuilder,
- PlayerStateStore,
- backend prompt payloads,
- StatePatch tables/writable lists,
- reward/mission systems,
- shop/economy surfaces,
- backup/restore,
- campaign import/update migration.

### Tests

Find any existing fixtures that rely on current `character_inventory.item_name` semantics before changing presentation behavior.

---

## 15. Recommended invariants

### Identity

1. Every canonical item definition has stable UID.
2. World Pack owns definition identity.
3. Display name does not establish identity.
4. Same label/different UID is legal.
5. Same UID/incompatible owner metadata fails loudly.

### Quantity

6. Stack quantity follows explicit item unit/stack policy.
7. Negative quantity is invalid.
8. NaN/Infinity are invalid if numeric quantity uses floating representation.
9. Unique instance cannot be silently represented as quantity > 1.
10. Merge/split operations preserve total quantity.

### Isolation

11. Campaign A inventory cannot leak into B.
12. Player A inventory cannot leak into B.
13. World Pack A cannot hijack B's definition UID.
14. Active-player switching changes presentation read scope, not stored ownership/state.

### Inventory/Equipment boundary

15. Inventory presence does not imply equipped.
16. Equipment deactivation/removal must not erase inventory item unless explicit transfer/destruction occurs.
17. Item in inventory must not automatically activate Phase-5 equipment modifier.

### Inventory/Ownership boundary

18. Possession does not imply legal ownership.
19. Ownership evidence in legacy rows must not be fabricated into OwnershipRecord before Phase 12.

### No-retrogression/cross-domain

20. Inventory read/write cannot rewrite base stats/masteries/Talent/Potential/innate state.
21. Removing a temporary modifier never removes item possession.
22. Item transfer/removal does not erase unrelated permanent progression.

### Legacy

23. Unknown name-only legacy item remains visible unresolved.
24. Explicit mapping canonicalizes exactly one logical identity.
25. Legacy bytes remain.
26. Mixed mapped typed+legacy does not produce two canonical holdings.
27. Unmapped same-looking typed+legacy state does not silently merge.

### Scale/integrity

28. Authoritative read has no presentation LIMIT.
29. 1000+ entries preserve exact count.
30. Reopen equality holds.
31. Migration is idempotent.
32. `PRAGMA integrity_check = ok`.
33. adopted FK policy passes `foreign_key_check` or equivalent explicit validation.

---

## 16. Suggested Phase-10 test gates

Minimum future implementation test matrix:

1. register ItemDefinition.
2. duplicate definition UID rejected.
3. same display label/different UID allowed.
4. same key across different World Packs remains separate.
5. World Pack owner mismatch rejected.
6. stackable item add/persist/reopen.
7. stack quantity increment/decrement preserves exact value.
8. negative quantity rejected.
9. zero quantity explicit policy tested.
10. unique ItemInstance creation.
11. unique instance cannot duplicate into two active player entries.
12. player A/B isolation.
13. campaign A/B isolation.
14. unknown legacy item_name remains visible.
15. legacy-only inventory -> typed compatibility read not empty.
16. explicit legacy mapping -> one canonical item holding.
17. mixed legacy+typed without mapping -> unresolved/fail-loud.
18. legacy bytes preserved.
19. name change does not change identity.
20. inventory item is not automatically equipped.
21. inventory item does not activate equipment modifier.
22. removing future equipment state leaves inventory possession.
23. inventory possession does not create OwnershipRecord.
24. item does not directly mutate PlayerStat.baseValue.
25. item does not directly mutate PlayerSkill.baseMastery.
26. item does not directly mutate PlayerTechnique.baseMastery.
27. item does not directly mutate TalentProfile.
28. item does not directly mutate PotentialProfile.
29. item does not grant InnateFeature/Evolution state by label.
30. 1000 entries no authoritative truncation.
31. reopen equality.
32. migration idempotency.
33. production current-schema routing reaches Phase 10.
34. restore reaches Phase 10.
35. campaign switch reaches Phase 10.
36. Phase 3–9 state byte/logical equality across migration.
37. SQLite integrity check.
38. FK policy/check.
39. full JVM tests.
40. signed APK build.
41. exact final-SHA CI success.

Additional adversarial gates:

- duplicate legacy rows,
- duplicate item names with different definitions,
- blank/whitespace name evidence,
- huge finite quantity,
- integer overflow if quantity is integer-based,
- repeated split/merge conservation,
- deleted/deprecated definition with existing inventory instance,
- mapping target deletion,
- mapping owner/version mismatch,
- cross-pack mapping hijack,
- orphan instance UID,
- definition says unique but legacy suggests quantity > 1,
- same unique instance attempted in two players/campaigns,
- ContextBuilder/CharacterPanel presentation truncation must not affect authoritative store.

---

## 17. Migration risk register

### R-01 — Current UI calls inventory `equipment`

Risk: future code may interpret the presentation field as authoritative equipped state.

Mitigation: Phase 10 explicitly classifies this as legacy presentation only; Phase 11 defines real equipment authority.

### R-02 — Name-only legacy identity

Risk: automatic mapping by `item_name` merges semantically distinct items.

Mitigation: unresolved compatibility identity + explicit World Pack mapping.

### R-03 — Quantity encoded as duplicate rows

Risk: migration incorrectly collapses unique items or fails to preserve quantities.

Mitigation: inspect actual schema and duplicate-row semantics before mapping.

### R-04 — Unique item history lost

Risk: collapsing individualized items into one stack destroys provenance/continuity.

Mitigation: ItemInstance for non-fungible identity.

### R-05 — Possession mistaken for ownership

Risk: stolen/borrowed/organization items become player property.

Mitigation: leave ownership to Phase 12.

### R-06 — Inventory mistaken for Equipment

Risk: every carried item activates modifiers.

Mitigation: no equipment lifecycle in Phase 10; Phase-5 `EQUIPMENT` modifiers require future Phase-11 source authority.

### R-07 — Presentation LIMIT becomes data loss

Risk: Context/UI bounded reads become authoritative import set.

Mitigation: authoritative InventoryStore has no silent truncation.

### R-08 — World Pack identity collision

Risk: same names/keys across packs auto-merge.

Mitigation: stable UID + World Pack ownership + explicit reconciliation.

### R-09 — Mutation bypass

Risk: AI or StatePatch performs arbitrary inventory writes outside future PlayerDomainEngine.

Mitigation: store contract prepared for later command/change-set path; do not bless free-form writes as canonical design.

### R-10 — Phase 9 overlap

Risk: artifact/evolution item labels create innate/evolution state automatically.

Mitigation: strict domain boundary; item-to-innate/evolution changes require explicit future rule/committed transition.

---

## 18. Forbidden scope for Phase 10

Do not implement in Phase 10:

- Equipment/loadout domain,
- equip/unequip slot rules,
- OwnershipRecord domain,
- full economy/financial ledger,
- asset/net-worth redesign,
- crafting/DevelopmentProject redesign,
- arbitrary loot/reward grant engine,
- PlayerCommand/PlayerChangeSet/PlayerDomainEngine beyond minimal interfaces specifically authorized by coordinator,
- CharacterPanelSnapshot v2,
- next roadmap phase.

Do not hardcode any specific fictional universe item names/categories into Core.

---

## 19. Recommended implementation sequence after Phase 9 COMPLETE

1. Re-audit latest master and accepted Phase-9 result SHA.
2. Run exact schema/data inventory for `character_inventory` and World Pack item data.
3. Classify every legacy field as definition metadata, inventory authority, equipment evidence, ownership evidence, economy evidence, presentation or unresolved.
4. Define `ItemDefinition` stable identity and stack/unique policy.
5. Define unique ItemInstance only where necessary.
6. Define campaign+character inventory entries.
7. Implement explicit legacy compatibility/reconciliation.
8. Add additive Phase-10 migration and current-schema routing.
9. Add authoritative unbounded typed reads.
10. Keep Equipment and Ownership unimplemented but reference-compatible.
11. Validate old campaigns, mixed legacy/typed state, 1000+ entries, reopen, integrity/FK.
12. Run full tests/build/CI.
13. Independent migration and adversarial validation before coordinator marks Phase 10 COMPLETE.

---

## 20. Architecture acceptance checklist

Phase 10 architecture is suitable for implementation only if the eventual runtime preserves all of these:

- `Inventory != Equipment`.
- `Inventory possession != OwnershipRecord`.
- stable item UID > item name.
- World Pack owns item definitions.
- campaign+character own inventory state.
- unique items have stable per-instance identity when needed.
- stackable items use quantity only when interchangeable.
- no automatic same-name merge.
- no authoritative truncation.
- no cross-campaign/player/World-Pack leakage.
- no direct base stat/mastery/Talent/Potential/innate mutation from inventory presence.
- legacy evidence remains lossless.
- explicit mappings are versioned/provenance-safe.
- production migration reaches Phase 10.
- future Equipment and Ownership domains can reference the same identities without parallel truths.

---

## 21. Final status

The exact next Roadmap phase after Phase 9 is confirmed as:

`PHASE 10 — Inventory model`

The current runtime has a real legacy `character_inventory` read surface but lacks canonical item identity, typed inventory authority, safe legacy reconciliation and a clean separation from Equipment/Ownership.

The required architecture is now defined with explicit boundaries against Phases 3–9 and future Phases 11–12.

`NEXT PHASE ARCHITECTURE READY — IMPLEMENTATION BLOCKED BY PHASE 9`
