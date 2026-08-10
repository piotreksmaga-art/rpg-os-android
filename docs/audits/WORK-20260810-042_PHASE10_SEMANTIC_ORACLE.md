# WORK-20260810-042 — Phase 10 Semantic Oracle

Status: READ-ONLY RUNTIME / SEMANTIC ORACLE

Work ID: `WORK-20260810-042`
Owner: `CHAT-2`
Role: READ-ONLY PHASE 10 SEMANTIC ORACLE
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-9 runtime: `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`
Fresh master inspected before write: `bf4e504421eb1c8135b71b9fdf3ffeb063367c88`
Primary architecture source: `docs/audits/WORK-20260810-039_NEXT_PHASE_ARCHITECTURE.md`

This report is documentation-only. It does not implement Phase 10 runtime, schema, migration, Equipment, OwnershipRecord, PlayerCommand, PlayerChangeSet, PlayerDomainEngine, or any later phase.

---

## 1. Canonical semantic separation

Phase 10 must preserve these hard separations:

```text
ItemDefinition != ItemInstance
Inventory possession != Equipment
Inventory possession != OwnershipRecord
Inventory possession != Financial ownership/value
```

### ItemDefinition

A World-Pack/content-owned type identity. It describes what kind of item exists.

Canonical properties include stable `itemUid`, owning `worldPackUid`, stable key, display metadata, version/provenance, active/deprecated state, and an explicit stackability/instance policy.

A definition is not a physical possession and is not an individual object.

### ItemInstance

A campaign-scoped stable identity for one concrete individualized item where individuality matters.

Examples of why an instance may be required include independent history, condition, provenance, customization, or later ownership/equipment linkage. The Core must not create instances for fungible commodity units when a quantity stack is sufficient.

### PlayerInventoryEntry

A character-scoped persistent statement that an item definition/instance is present in that character's inventory, with quantity where appropriate.

It answers possession/location-in-inventory semantics only. It does not prove equipped state or legal ownership.

### Legacy inventory evidence

Existing `character_inventory` rows are compatibility evidence. They become canonical typed state only through a lossless, explicit, deterministic migration/reconciliation contract.

---

## 2. Confirmed legacy/runtime evidence

The actual runtime `CharacterPanelReader` currently executes:

```sql
SELECT item_name
FROM character_inventory
WHERE entity_uid=?
ORDER BY item_name
```

and appends the returned names to `CharacterPanelSnapshot.equipment`.

This proves only that the supported runtime expects at least:

- `character_inventory.entity_uid`,
- `character_inventory.item_name`.

The inspected runtime does **not** prove from this read path:

- quantity,
- unique instance UID,
- definition UID,
- equipped flag,
- slot/loadout state,
- legal owner,
- condition/durability,
- acquisition provenance,
- monetary value semantics,
- transfer semantics.

Therefore none of those may be inferred from the legacy row merely because `item_name` exists.

### Critical presentation bug/shortcut

The legacy UI currently maps:

```text
character_inventory.item_name -> CharacterPanelSnapshot.equipment
```

Semantic oracle:

```text
legacy inventory row shown under UI field named "equipment"
DOES NOT IMPLY
equipped = true
```

This is PRESENTATION evidence only. Phase 10 must not migrate or canonicalize equipped state from this label. Equipment remains Phase 11.

---

## 3. Stackable vs unique

Stackability must be explicit definition semantics, not inferred from item name/category.

### 3.1 Stackable item

A stackable definition represents interchangeable units.

Canonical state may be represented as one logical holding:

```text
(campaignId, characterUid, itemUid) -> quantity
```

provided the World Pack definition states that units are interchangeable.

Canonical example:

```text
item = STACKABLE-A
quantity = 10
remove = 3
=> quantity = 7
```

No unrelated inventory entry changes.

### 3.2 Unique item

A unique/individualized definition requires a stable instance identity.

Canonical example:

```text
instance X -> definition SWORD
instance Y -> definition SWORD
inventory contains X and Y
remove X
=> X no longer appears in this inventory
=> Y remains present
=> definition SWORD remains valid
```

Deleting/removing X from inventory must not delete Y or the shared definition.

### 3.3 Semi-fungible/ambiguous legacy items

If units may differ by condition/history/customization, they must not be silently merged into one quantity stack unless the definition contract explicitly permits stacking.

Legacy duplicate name rows do not prove quantity and do not prove distinct instances. They remain unresolved evidence until mapping semantics establish which interpretation is correct.

---

## 4. Quantity semantics

Quantity is authoritative inventory state only for definitions whose stack policy permits quantity semantics.

Required invariants:

- quantity must use the numeric type defined by the item/unit policy;
- NaN and Infinity are always invalid if floating representation is used;
- negative active quantity is invalid;
- adding quantity must not silently overflow;
- removing more than present must fail deterministically unless a later explicit debt/backorder mechanic exists outside Inventory;
- quantity mutation must be scoped to one campaign, character and canonical item identity;
- no SQLite row-order semantics.

### Zero quantity

Preferred canonical state:

```text
quantity reaches 0
=> active inventory holding is removed/absent
```

Do not keep `quantity=0` as a normal possession row unless the implementation explicitly uses tombstones/history and keeps them outside active inventory reads.

Therefore:

```text
quantity 3
remove 3
=> no active inventory possession
```

not:

```text
quantity = 0
=> still possessed
```

---

## 5. Duplicate stacks

Two active stack rows for the same canonical stackable identity and same inventory scope are semantically dangerous because they create two quantity authorities.

Canonical rule:

```text
same campaign + same character + same canonical stackable item + same stacking partition
=> exactly one active quantity authority
```

If future metadata creates distinct stacking partitions (for example condition/batch/container), that partition identity must be explicit and stable.

Legacy duplicate rows with the same name must **not** be auto-summed simply because the label matches. Without explicit mapping/stack semantics, they remain unresolved.

---

## 6. Add / remove semantics

### Stackable add

Given quantity 10, add 4:

```text
10 + 4 = 14
```

The definition is unchanged. Equipment and OwnershipRecord are unchanged.

### Stackable remove

Given quantity 10, remove 3:

```text
10 - 3 = 7
```

Remove 10 from quantity 10:

```text
active holding disappears
```

Remove 11 from quantity 10:

```text
deterministic failure; no partial mutation
```

### Unique add

Adding a unique item means adding one explicit `instanceUid` to the character inventory. Re-adding the same active instance UID to the same inventory must be idempotent/rejected according to the future mutation contract, but must never create a second physical identity.

### Unique remove

Removing instance X removes only X from that inventory. It does not delete:

- ItemDefinition,
- other instances of the definition,
- future ownership history,
- unrelated equipment/ownership records.

---

## 7. Transfer semantics — Inventory scope only

Phase 10 may define inventory transfer only as movement of possession state between inventory scopes.

### Stack transfer

Example:

```text
Player A has 10 units of item I
transfer 3 from A inventory to B inventory
=> A has 7
=> B gains 3
```

Required semantic properties:

- atomic source decrement + target increment;
- no partial state if target validation fails;
- same canonical item identity;
- campaign scope preserved;
- no automatic legal ownership transfer.

### Unique transfer

```text
A inventory contains instance X
transfer X to B inventory
=> A inventory no longer contains X
=> B inventory contains exactly X
```

The stable `instanceUid` must not change.

### Boundary with OwnershipRecord

Inventory transfer means possession transfer only.

```text
transfer inventory A -> B
DOES NOT IMPLY
transfer legal ownership A -> B
```

Phase 12 will own legal ownership semantics.

---

## 8. Deletion/removal

Three concepts must not be conflated:

1. remove from a character's inventory,
2. destroy/delete a concrete item instance,
3. delete/deprecate an item definition.

Removing possession must not delete a World Pack definition.

Destroying an instance is a stronger future domain event and must preserve historical provenance if implemented.

A definition with historical references should generally be deprecated rather than physically deleted.

---

## 9. Deprecated definition

`DEPRECATED` means new normal creation/acquisition may be forbidden by later rules, but existing campaign holdings must remain readable and lossless.

Canonical oracle:

```text
item definition D becomes DEPRECATED
character already possesses D / instance of D
=> possession remains readable
=> identity remains stable
=> no automatic deletion from inventory
```

Display may warn that the definition is deprecated. Deprecation is not removal.

---

## 10. Legacy unresolved item

A legacy row containing only `item_name` cannot safely establish a canonical `itemUid`.

Canonical unresolved state:

```text
legacy evidence: entity_uid=P, item_name="X"
no explicit World Pack mapping
=> preserve evidence
=> do not synthesize canonical World-Pack item identity from the label
=> do not infer stackability
=> do not infer instance identity
=> do not infer equipped state
=> do not infer ownership
```

Same text name in different World Packs is allowed to map to different stable item UIDs.

---

## 11. Explicit legacy mapping

Canonical mapping must be stable, versioned, provenance-bearing and scoped sufficiently to prevent cross-campaign/World-Pack hijacking.

Conceptual shape:

```text
LegacyInventoryMapping {
  campaignId
  legacyEvidenceIdentity
  worldPackUid
  canonicalItemUid
  canonicalInstanceUid? // only if explicit evidence proves a unique instance mapping
  mappingVersion
  provenance
}
```

Rules:

- mapping is explicit; never `same name == same item` globally;
- mapped typed identity becomes canonical read identity for that mapped evidence;
- original legacy bytes remain preserved;
- unknown/unmapped legacy rows remain visible as unresolved evidence;
- a mapping to unique instance semantics requires stronger evidence than a name-only row;
- a World Pack cannot map evidence to a definition it does not own;
- remapping/hijacking an already established canonical identity must fail loudly unless there is an explicit versioned migration path.

---

## 12. Mixed legacy + typed state

Critical oracle:

```text
legacy row "X"
+ typed canonical item X-like
WITHOUT explicit mapping
=> do not auto-merge by name
=> canonical mutation/read path must expose unresolved ambiguity or fail loudly where exactly-one identity is required
```

After explicit mapping:

```text
legacy evidence -> typed item UID
=> exactly one canonical typed inventory identity
=> legacy bytes still preserved
```

This follows the same reconciliation lesson established for Phase 4 Stats/Resources and Phase 7/8 learned domains.

---

## 13. ItemDefinition vs ItemInstance matrix

| Question | ItemDefinition | ItemInstance |
|---|---|---|
| Stable identity | type UID | per-instance UID |
| Owner/scope | World Pack/content | campaign |
| Display name/type/category | yes | references definition; may have instance metadata |
| Quantity | no | normally no; possession entry carries presence/quantity policy |
| Unique history | no | yes, when individuality matters |
| Character possession | no | only through PlayerInventoryEntry |
| Equipped state | no | Phase 11 may reference it |
| Legal ownership | no | Phase 12 may reference it |
| Deprecation | definition status | instance remains identifiable |

---

## 14. Inventory vs Equipment oracle

The following states are all legal:

```text
in inventory = true, equipped = false
in inventory = true, equipped = true   // future Phase 11 relationship
in inventory = false, legally owned = true // possible stored elsewhere / not carried
in inventory = true, legally owned = false // borrowed/stolen/entrusted
```

Phase 10 must represent only inventory possession.

The current `CharacterPanelSnapshot.equipment` field cannot be used to prove any of these equipment/ownership relations because its contents are currently sourced directly from `character_inventory.item_name`.

---

## 15. Inventory vs OwnershipRecord oracle

Possession and ownership are independent axes.

Examples the future model must support:

- borrowed item: inventory yes, ownership no;
- stolen item: inventory yes, ownership no;
- personally owned item stored elsewhere: inventory no, ownership yes;
- organization-owned item carried by player: inventory yes, personal ownership no.

Therefore Phase 10 must not create `OwnershipRecord` from an inventory insert or legacy inventory row.

---

## 16. No silent semantic guessing

If the repository/runtime does not prove a field's semantics, Phase 10 implementation must preserve it as opaque evidence until a canonical mapping/rule is provided.

Especially forbidden inference from legacy data:

- duplicate row => quantity,
- single row => unique instance,
- `item_name` => stable identity,
- CharacterPanel `equipment` label => equipped state,
- possession => ownership,
- category/name substring => stackability,
- item presence => modifier activation,
- disappearance from inventory => physical destruction.

---

## 17. Canonical test oracle set

### OR-01 stack add/remove

```text
start: 10 units
remove 3
expected: 7
```

### OR-02 stack remove all

```text
start: 3
remove 3
expected: no active holding
```

### OR-03 stack over-remove

```text
start: 3
remove 4
expected: deterministic failure + original 3 preserved
```

### OR-04 unique remove isolation

```text
instances X,Y share one definition
remove X
expected: X absent, Y present
```

### OR-05 unique identity transfer

```text
A owns inventory presence X
transfer X to B inventory
expected: A absent, B contains same instanceUid X
```

No ownership semantics are implied.

### OR-06 duplicate active stack

Two active quantity authorities for the same canonical stacking key must be rejected or reconciled explicitly before canonical reads.

### OR-07 zero quantity

Active inventory read must not report possession at quantity zero.

### OR-08 deprecated definition

Existing holding remains readable after definition deprecation.

### OR-09 legacy name-only

Unmapped name-only row remains unresolved; no typed identity/equipment/ownership inference.

### OR-10 explicit mapping

Explicit versioned mapping creates one canonical typed identity while preserving legacy evidence.

### OR-11 same name, different World Packs

No automatic merge. Stable UIDs remain separate.

### OR-12 inventory UI mislabeled as equipment

Legacy `item_name` appearing in `CharacterPanelSnapshot.equipment` does not create any canonical equipment state.

### OR-13 transfer atomicity

Target validation failure leaves source and target inventory unchanged.

### OR-14 campaign/player isolation

Inventory mutation/read for campaign/player A cannot change or expose B.

### OR-15 no ownership side effect

Inventory add/remove/transfer changes no future OwnershipRecord authority.

---

## 18. Release-blocking semantic invariants for future WORK-041 validation

Future Phase-10 runtime must satisfy all of the following before CHAT-2 semantic recheck can PASS:

1. `ItemDefinition` and `ItemInstance` are structurally distinct identities.
2. Stackable and unique policies are explicit and deterministic.
3. Quantity authority has exactly-one canonical source per stacking identity.
4. Unique `instanceUid` is stable across inventory transfer.
5. Remove-one unique instance cannot affect sibling instances.
6. Quantity zero does not mean possession.
7. Over-removal fails without partial mutation.
8. Inventory transfer is possession-only and does not imply OwnershipRecord mutation.
9. No Phase-10 write creates Equipment/loadout state.
10. Legacy `CharacterPanelSnapshot.equipment` naming is not used as equipment evidence.
11. Name-only legacy rows remain unresolved until explicit mapping.
12. Same-name items are not automatically merged across World Packs.
13. Mixed legacy+typed ambiguity is resolved explicitly/fail-loud, never silently by name.
14. Legacy bytes remain lossless.
15. Deprecated definitions do not erase existing holdings.
16. Campaign/player isolation is preserved.
17. Authoritative reads are not truncated by UI/context limits.
18. No hidden mutation of Phase 3–9 authoritative domains.

---

## 19. Final semantic decision

Phase 10 should be interpreted narrowly as canonical inventory possession/holding state built from stable item type/instance identities.

The critical semantic equation is:

```text
ItemDefinition
!= ItemInstance
!= PlayerInventoryEntry
!= Equipment
!= OwnershipRecord
```

Legacy inventory displayed under the field name `equipment` is a presentation defect/compatibility shortcut, not authority. The future runtime must correct the semantic boundary without using the presentation label as migration evidence.

# PHASE 10 SEMANTIC ORACLE READY
