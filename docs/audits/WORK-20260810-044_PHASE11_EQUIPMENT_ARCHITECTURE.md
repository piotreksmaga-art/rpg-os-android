# WORK-20260810-044 — Phase 11 Equipment Architecture

Status: READ-ONLY RUNTIME / FUTURE PHASE ARCHITECTURE

Work ID: `WORK-20260810-044`
Worker: `CHAT-4`
Role: READ-ONLY NEXT-PHASE ARCHITECT
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master observed during audit: `bf4e504421eb1c8135b71b9fdf3ffeb063367c88`
Input architecture: `docs/audits/WORK-20260810-039_NEXT_PHASE_ARCHITECTURE.md`
Roadmap next dependency: Phase 10 Inventory model -> Phase 11 Equipment domain/loadout model.

This document is architecture/audit only. It does not implement Phase 10, Phase 11, schema, migration, Equipment runtime, OwnershipRecord, PlayerCommand, PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider, ProgressionEngine, or CharacterPanelSnapshot v2.

---

## 1. Canonical phase identity

Current Roadmap order:

```text
10. Inventory model
11. Equipment domain/loadout model
12. OwnershipRecord domain
```

Therefore this document designs:

# PHASE 11 — EQUIPMENT DOMAIN / LOADOUT MODEL

Implementation remains blocked until Phase 10 establishes the actual accepted `ItemDefinition` / `ItemInstance` / inventory possession contract.

---

## 2. Fundamental semantic split

Hard invariant:

```text
INVENTORY POSSESSION
!= EQUIPMENT STATE
!= ITEM OWNERSHIP
```

Inventory answers whether a character currently possesses/carries/controls an item or instance.

Equipment answers whether a possessed equippable item instance is currently assigned to one or more loadout slots / wielded / worn / active as equipment.

Ownership answers who legally/economically owns the asset and belongs to Phase 12.

Therefore:

- possessing an item does not mean it is equipped,
- equipping an item does not create ownership,
- unequipping does not delete inventory possession,
- losing/removing inventory possession must invalidate dependent equipment state through an explicit legal mutation,
- borrowed/stolen/entrusted items may be equipped if rules allow, despite ownership belonging elsewhere.

---

## 3. Repo-wide legacy/runtime evidence

### 3.1 `character_inventory` is inventory evidence, not equipment authority

WORK-039 confirmed the current CharacterPanel reads `character_inventory.item_name` and places those names into a presentation field named `equipment`.

This is a legacy UI shortcut only. It must not be migrated as proof that every inventory item is equipped.

Phase 11 must never use:

```text
row exists in character_inventory
=> equipped = true
```

### 3.2 `character_techniques.is_equipped`

Technique runtime already contains an `is_equipped` field. That state belongs to Technique selection/presentation semantics, not physical item Equipment.

Hard boundary:

```text
PlayerTechnique.isEquipped
!= PlayerEquipment
```

Phase 11 must not reuse Technique `is_equipped` rows as item equipment/loadout authority.

### 3.3 CharacterPanel presentation

Current CharacterPanel `equipment: List<String>` is not authoritative because it is populated from inventory names rather than a dedicated equipment store.

Until Phase 11 exists, this field must be treated as presentation compatibility only.

### 3.4 Phase-5 modifier source lifecycle

Current generic modifier model already contains:

```text
ModifierLifecycle.EQUIPMENT
sourceType
sourceUid
sourceActive
active
```

This is the correct derived-effect foundation for equipment-origin bonuses/penalties.

Phase 11 must integrate with this existing mechanism rather than create `EquipmentModifierEngine` or a second resolver.

### 3.5 Search terms / surfaces checked

Audit scope included equipment-like concepts:

```text
equipment
equipped
is_equipped
slot
weapon
armor
loadout
wielded
worn
character_inventory
character_techniques.is_equipped
modifier lifecycle EQUIPMENT
sourceActive
```

No accepted dedicated physical Equipment runtime/store was found in the audited master.

---

## 4. Authority model

### 4.1 World Pack / content authority

World Pack definitions own:

- equipment slot identities,
- slot categories/groups,
- item-to-slot compatibility rules,
- multi-slot occupancy requirements,
- exclusivity/conflict groups,
- optional loadout constraints,
- generic requirement/rule bindings,
- definition version/provenance.

Core does not hardcode concepts such as sword, armor, helmet, ring, hand, bankai weapon, ninja tool, etc.

### 4.2 Campaign-character equipment authority

Campaign player state owns:

- which concrete item instance/holding is equipped,
- which loadout it belongs to,
- which slot reservation(s) it occupies,
- activation state where equipment can be equipped-but-inactive,
- entry version/provenance,
- equip/unequip event/command provenance where available.

### 4.3 Inventory authority remains Phase 10

Equipment references existing inventory possession / item instance identity.

It does not create inventory entries and must not synthesize item instances from labels.

### 4.4 Ownership authority remains Phase 12

No ownership inference from equipment state.

---

## 5. Proposed generic Core model

Exact Kotlin/table names are implementation decisions. The following semantic contract is required.

### 5.1 `EquipmentSlotDefinition`

```text
slotUid
worldPackUid
key
displayName
slotKindUid/groupUid?
capacity = 1 or generic capacity semantics
exclusiveGroupUid?
definitionVersion
provenance
status ACTIVE|DEPRECATED
metadata?
```

Stable UID is identity. Display label is presentation only.

### 5.2 Item equipment compatibility

Prefer explicit relation/rule rather than embedding universe-specific slot assumptions in Core.

Conceptual shape:

```text
EquipmentCompatibilityRule {
  ruleUid
  worldPackUid
  itemUid or itemKindUid
  allowedSlotUid / allowedSlotGroupUid
  requiredSlotCount?
  requiredSlotSet?
  exclusiveGroupUid?
  ruleVersion
  provenance
}
```

Compatibility may alternatively be provided by a generic WorldRuleProvider binding, but identity and deterministic rule version must remain explicit.

### 5.3 `EquipmentLoadout`

A loadout is a character-scoped named/grouped equipment state, not a copy of inventory.

```text
loadoutUid
campaignId
characterUid
key/displayName?
activeLoadout flag or status if multiple loadouts are supported
version
provenance
```

Do not require multiple loadouts if actual Phase-10/11 implementation does not need them. A single implicit default loadout is acceptable only if its identity remains deterministic and future-compatible.

### 5.4 `PlayerEquipment`

Recommended authoritative entry:

```text
campaignId
characterUid
loadoutUid
itemInstanceUid
itemUid
entryUid
state EQUIPPED|INACTIVE_EQUIPPED or equivalent
entryVersion
provenance
```

Slot occupancy should be represented explicitly, preferably through separate binding rows:

```text
PlayerEquipmentSlotBinding {
  campaignId
  characterUid
  equipmentEntryUid
  slotUid
  bindingRole PRIMARY|SECONDARY|RESERVED or generic equivalent
}
```

This naturally supports multi-slot equipment without hardcoding two-handed logic.

---

## 6. Multi-slot / two-handed / conflicting equipment

Core must not contain a special `twoHanded: Boolean` rule as the only mechanism.

Generic rule:

```text
one equipment entry may require/reserve N stable slot UIDs or slot groups
```

Examples supported generically:

- one item occupies one slot,
- one item occupies two compatible slots,
- one item reserves a primary slot and blocks another group,
- mutually exclusive equipment classes,
- paired items occupying distinct compatible slots,
- forms/species with arbitrary slot layouts defined by World Pack.

Conflict validation must be deterministic and fail-loud before commit.

### Required conflict classes

- same capacity-1 slot claimed twice,
- required slot missing,
- incompatible slot kind,
- exclusive group conflict,
- multi-slot reservation incomplete,
- same unique item instance equipped twice in one loadout,
- same item instance equipped by two players/campaigns contrary to inventory identity.

---

## 7. Equip / unequip legal mutation contract

Phase 11 should not legitimize arbitrary direct row mutation.

Future legal path:

```text
EquipItem / UnequipItem command
-> PlayerDomainEngine
-> inventory/equipment validation
-> WorldRuleProvider compatibility
-> PlayerChangeSet
-> transaction
-> equipment state + modifier source lifecycle + events
-> commit
```

Until PlayerCommand/DomainEngine phases exist, any interim store API must preserve the same validation boundary and must not become an AI bypass.

### Equip preconditions

At minimum:

1. campaign/player identity valid,
2. referenced ItemInstance/Inventory entry exists in canonical Phase-10 state,
3. item definition is active/usable or explicit legacy policy permits existing item,
4. slot definitions exist,
5. World Pack ownership/compatibility valid,
6. slot capacity/exclusivity valid,
7. same unique instance not already illegally equipped,
8. no unresolved inventory identity ambiguity,
9. any explicit requirements evaluate deterministically.

### Unequip semantics

Unequip removes/deactivates equipment state and equipment-derived modifier sources.

It must not:

- delete inventory possession,
- delete ItemInstance,
- change OwnershipRecord,
- lower base Stat/mastery,
- erase Skill/Technique/Talent/Potential progression.

---

## 8. Phase-5 modifier integration

Current accepted generic foundation already has `ModifierLifecycle.EQUIPMENT`, `sourceUid` and `sourceActive`.

Canonical equipment effect contract:

```text
equipped item/source becomes active
-> equipment-origin Modifier sourceActive = true (or source considered active)
-> DerivedValueResolver computes effective projection

unequip/deactivate
-> source becomes inactive
-> derived projection rebuilds without that source
-> base authoritative values unchanged
```

Equipment-derived effects may legally target existing generic target kinds such as:

- `STAT_EFFECTIVE`,
- `RESOURCE_MAXIMUM`,
- `RESOURCE_REGENERATION`,
- `SKILL_EFFECTIVE`,
- `TECHNIQUE_EFFECTIVE`.

Phase 11 does not need a new resolver.

### Hard no-retrogression invariant

Equip/unequip/equipment effect lifecycle may never directly rewrite:

```text
PlayerStat.baseValue
PlayerSkill.baseMastery
PlayerTechnique.baseMastery
TalentProfile authoritative value
PotentialProfile authoritative value
```

If an item permanently trains/changes a character, that is a separate legal progression/domain mutation, not an equipment-derived effect.

### Source identity

Recommended:

```text
sourceType = EQUIPMENT_ITEM / generic stable type
sourceUid = equipmentEntryUid or itemInstanceUid
```

The chosen source UID must uniquely identify the actual equipped source and remain stable across resolver rebuild/reopen.

Do not use display name as source identity.

---

## 9. Inventory binding rules

Phase 11 depends directly on the accepted Phase-10 instance contract.

Required invariants:

- unique equipment should bind to `ItemInstance.instanceUid`, not `item_name`,
- stackable commodity cannot be equipped as an anonymous stack unless World Pack explicitly defines an equipable unit identity,
- if one unit is separated from a stack for equipping, Phase 10 must provide an unambiguous instance/holding identity or explicit reserved quantity semantics,
- removing/transferring/destroying an equipped item requires a legal operation that also resolves dependent equipment state atomically,
- unresolved legacy inventory evidence cannot become canonical equipped state by name guessing.

Because Phase 10 is still under implementation, final field names and exact binding tables must be rechecked against its accepted runtime before Phase 11 implementation.

---

## 10. Legacy compatibility / migration

### 10.1 CharacterPanel `equipment` labels

These are not canonical equipment evidence.

Do not migrate them to PlayerEquipment automatically.

### 10.2 `character_techniques.is_equipped`

Preserve under Technique domain. Do not reinterpret as item equipment.

### 10.3 Inventory labels resembling weapons/armor

A label such as `weapon`, `armor`, `worn`, `equipped`, or item name is insufficient to canonicalize equipment state unless actual schema semantics and explicit mapping prove it.

### 10.4 Explicit mapping only

If real legacy item rows contain authoritative equipped/slot fields, future migration should require a typed mapping such as:

```text
LegacyEquipmentMapping {
  campaignId
  legacyEquipmentIdentity
  canonicalItemInstanceUid
  canonicalSlotUid(s)
  mappingVersion
  worldPackUid
  provenance
}
```

Mixed legacy + typed same semantic equipment state without mapping must remain unresolved/fail-loud, never double-active.

---

## 11. Slot ownership and World Pack isolation

Slot definitions are World-Pack-owned stable identities.

Hard rules:

- World Pack B cannot redefine/hijack slotUid owned by A,
- same slot display label in A and B may represent different stable UIDs,
- item compatibility uses stable UIDs or explicit rule bindings,
- no global `slot key == semantic slot` merge,
- no global `weapon == hand` or `armor == body` Core assumption.

---

## 12. Equipment vs innate/evolution forms

Phase 9 may alter available body/form slot topology in some World Packs.

Phase 11 should not hardcode those interactions.

Preferred future contract:

- active Phase-9 form may provide a generic slot-layout/availability rule input,
- Phase-11 validator reevaluates loadout legality,
- temporary invalidation may deactivate/suppress equipment-derived effects according to explicit rules,
- it must not silently destroy ItemInstance or inventory possession.

If equipment becomes invalid after form change, resolution should be explicit: inactive equipment, forced unequip proposal, or validation error according to World Pack rule.

---

## 13. Determinism requirements

Equipment resolution must not depend on:

- SQLite row order,
- insertion order,
- map iteration order,
- display-name sorting,
- unspecified conflict resolution.

Tie/conflict handling must use stable identity and explicit priority/rule semantics.

Atomic equip of a multi-slot item must either reserve all required slots or reserve none.

---

## 14. Persistence / migration architecture

If Phase 11 adds tables, migration must be additive and idempotent.

Production path must eventually be:

```text
CurrentSchema.ensure()
-> Phase 10 accepted schema
-> Phase 11 schema
```

Phase 11 migration must not mutate legacy inventory bytes merely to derive equipment.

It must preserve Phase 3–10 state, including:

- ActivePlayerRef,
- stats/resources,
- modifiers,
- Talent/Potential,
- Skills,
- Techniques,
- Phase-9 innate/evolution state,
- Phase-10 inventory/item identities.

---

## 15. Required implementation test gates

### Identity / definitions

1. slot definition registration.
2. duplicate slot UID fail-loud.
3. same label different slot UID allowed.
4. World Pack slot ownership hijack rejected.
5. incompatible slot mapping rejected.

### Inventory binding

6. inventory possession alone is not equipped.
7. equip references canonical ItemInstance/holding.
8. missing inventory instance fails.
9. unresolved legacy item cannot be equipped by guessed name.
10. equipped item remains in inventory after unequip.
11. unequip does not alter OwnershipRecord semantics.

### Loadout / slots

12. single-slot equip.
13. slot capacity conflict.
14. incompatible slot.
15. multi-slot equip succeeds atomically.
16. partial multi-slot reservation impossible.
17. exclusive-group conflict fails deterministically.
18. same unique instance cannot be equipped twice illegally.
19. separate loadouts remain isolated if supported.

### Derived effects / Phase 5

20. EQUIPMENT modifier activates only with valid active equipment source.
21. unequip/sourceActive false removes derived effect.
22. re-equip restores identical deterministic derived result.
23. equipment never writes PlayerStat.baseValue.
24. equipment never writes PlayerSkill.baseMastery.
25. equipment never writes PlayerTechnique.baseMastery.
26. equipment never writes Talent.
27. equipment never writes Potential.
28. modifier source from player A cannot affect B.
29. modifier source from campaign A cannot affect B.
30. sourceUid identity stable after reopen.

### Boundaries

31. `character_techniques.is_equipped` does not create PlayerEquipment.
32. CharacterPanel legacy `equipment` names do not create PlayerEquipment.
33. Inventory != Equipment preserved.
34. Equipment != Ownership preserved.
35. Phase-9 form/state does not directly mutate inventory/equipment without rule path.

### Persistence / scale

36. reopen equality.
37. migration idempotency.
38. production current-schema routes to Phase 11 once implemented.
39. campaign switch isolation.
40. 1000 equipment/slot bindings without authoritative truncation.
41. integrity_check.
42. foreign_key_check or explicit FK policy test.
43. Phase 3–10 no-regression.

---

## 16. Automatic blocker conditions

Phase 11 implementation must be rejected if it:

- treats every inventory item as equipped,
- treats Technique `is_equipped` as physical equipment,
- equates possession with ownership,
- equips by display name instead of stable identity,
- creates a universe-specific fixed slot model in Core,
- cannot represent multi-slot equipment generically,
- silently resolves slot conflicts by row/insertion order,
- allows a unique item instance in incompatible simultaneous bindings,
- lets World Pack B hijack World Pack A slot identity,
- creates a second modifier/resolver engine,
- writes equipment-derived effects into base stat/mastery/Talent/Potential,
- loses equipment state after reopen,
- truncates authoritative equipment rows,
- bypasses canonical Phase-10 inventory identity.

---

## 17. Known dependency on unfinished Phase 10

At audit time, fresh master `bf4e504421eb1c8135b71b9fdf3ffeb063367c88` contained Phase-9 validation work; no final Phase-10 runtime result was visible.

Therefore Phase-11 implementation must re-read the accepted Phase-10 runtime and bind to its real:

- ItemDefinition identity,
- ItemInstance identity,
- stack/quantity semantics,
- inventory reconciliation model,
- repository APIs,
- migration/current-schema version.

This dependency changes field/API names, not the semantic architecture above.

---

## 18. Final contract

Canonical Phase-11 equation:

```text
Inventory possession
+ stable ItemInstance identity
+ World-Pack-owned slot definitions/compatibility
+ legal equip mutation
= persistent Equipment/Loadout state

Equipment state
+ Phase-5 Modifier lifecycle/sourceActive
= derived effective effects

Derived effects
!= base progression

Equipment state
!= ownership
```

No world-specific equipment vocabulary belongs in Core.

# PHASE 11 ARCHITECTURE READY — IMPLEMENTATION BLOCKED BY PHASE 10
