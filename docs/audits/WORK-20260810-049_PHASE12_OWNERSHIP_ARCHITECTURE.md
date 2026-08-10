# WORK-20260810-049 — Phase 12 Ownership Architecture

Status: READ-ONLY RUNTIME / NEXT-PHASE ARCHITECTURE

Work ID: `WORK-20260810-049`
Worker: `CHAT-4`
Role: READ-ONLY NEXT-PHASE ARCHITECT
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master observed during audit: `138bf67b8e6af52efe66254fd2289a77804b88dc`
Accepted Phase-10 runtime: `eb8bb64f8be566982c91f1062f319078899c1e47`
Phase-11 architecture input: `docs/audits/WORK-20260810-044_PHASE11_EQUIPMENT_ARCHITECTURE.md`

This report is architecture/audit only. It does not implement Phase 11, Phase 12, schema, migration, OwnershipStore, PlayerCommand, PlayerChangeSet, PlayerDomainEngine, Event Store, Economy, Assets/Liabilities or CharacterPanelSnapshot v2.

---

## 1. Exact phase identity from current Roadmap

The current Roadmap order remains:

```text
10. Inventory model
11. Equipment domain/loadout model
12. OwnershipRecord domain
13. Financial Ledger / Economy model
14. Assets / debts / obligations / net-worth model
```

Therefore the exact phase following Phase 11 is:

# PHASE 12 — OWNERSHIP RECORD DOMAIN

No Roadmap divergence was found.

Implementation remains blocked until Phase 11 is accepted, because ownership must coexist with the final Equipment contract without being inferred from equipment or possession state.

---

## 2. Canonical semantic split

Hard invariant:

```text
INVENTORY POSSESSION
!= EQUIPMENT STATE
!= OWNERSHIP RECORD
```

### Inventory possession

Answers:

- who currently carries/holds/controls an item or quantity,
- which character inventory currently contains an item instance or stack,
- where an item is physically represented in player inventory state.

It does not answer legal/historical ownership.

### Equipment state

Answers:

- which possessed item instance is currently equipped/wielded/worn/assigned to a loadout slot,
- whether equipment-origin modifiers are active.

It does not create or destroy ownership.

### OwnershipRecord

Answers:

- who is the recognized owner/right-holder of a stable asset identity,
- what kind/share of ownership/right exists,
- when that relation became valid,
- when it ended or was superseded,
- which event/transaction/provenance created, transferred, revoked or otherwise changed that right.

Ownership is therefore a durable historical/legal/fabular relation, not a boolean stored on an inventory row.

---

## 3. MASTER constraints

MASTER explicitly requires:

```text
Inventory != Equipment.
Unique items have their own UID.
Location of an item does not imply ownership.

OwnershipRecord stores:
owner,
asset,
ownershipType/share,
validFrom/Until,
sourceEvent.

It supports:
items,
real estate,
businesses,
shares,
etc.
```

This has several architectural consequences:

1. Phase 12 must be asset-generic, not item-only.
2. `ItemInstance` is one valid ownable identity, but not the only one.
3. A stable ownership history is required; one mutable `ownerUid` column on the asset is insufficient.
4. Current ownership should be derivable from active historical records.
5. Possession/custody must remain orthogonal to ownership.
6. Equipment state must remain orthogonal to ownership.
7. Ownership mutations eventually need the global legal mutation path and event/provenance integration required by MASTER.

---

## 4. Accepted Phase-10 runtime boundary

Accepted Phase-10 runtime is:

`eb8bb64f8be566982c91f1062f319078899c1e47`

Phase-10 final validation confirms that Inventory owns canonical item identity and possession, not OwnershipRecord state.

The actual runtime model now includes:

```text
ItemDefinition
ItemInstance
PlayerInventoryStack
PlayerInventoryUnique
LegacyInventoryEvidence
LegacyInventoryMapping
```

Notably:

```text
ItemInstance {
  campaignId
  itemInstanceUid
  itemDefinitionUid
  instanceVersion
  provenance
}

PlayerInventoryUnique {
  campaignId
  characterUid
  itemInstanceUid
  entryVersion
  provenance
}
```

This is exactly the separation Phase 12 needs:

```text
ItemInstance identity
!=
PlayerInventoryUnique possession
!=
OwnershipRecord right
```

Phase 12 should reference `itemInstanceUid` for unique item ownership rather than item display name or inventory row identity.

For stackable commodities, ownership semantics require additional care; see section 11.

---

## 5. Phase-11 boundary

WORK-044 defines Phase 11 as Equipment/loadout authority and already states:

```text
Inventory possession
!= Equipment state
!= Item ownership
```

Phase 12 must preserve that separation.

Hard examples:

```text
A owns X and possesses X
```

Legal state:

- active OwnershipRecord: owner A -> X,
- inventory possession: A -> X,
- equipment: independent.

```text
A owns X, B temporarily possesses X
```

Legal state:

- active OwnershipRecord remains A -> X,
- inventory/custody may be B -> X,
- no OwnershipRecord transfer unless an explicit ownership mutation occurs.

```text
B steals X
```

Possible state:

- possession changes A -> B,
- legal ownership may remain A,
- theft event/provenance records why custody and ownership diverged,
- the system must not silently decide that theft transfers legal ownership.

```text
A equips X
```

- Equipment changes,
- Ownership does not.

```text
A unequips X
```

- Equipment changes,
- Ownership does not.

```text
Inventory transfer A -> B
```

- possession changes,
- Ownership changes only if the same committed operation includes an explicit ownership mutation authorized by domain rules.

---

## 6. Repo-wide legacy/runtime evidence

### 6.1 No canonical OwnershipRecord runtime found

A repository-wide ownership search did not reveal an accepted typed OwnershipRecord runtime/store.

This is consistent with Roadmap Phase 12 being MISSING.

### 6.2 `character_inventory` is not ownership evidence

Legacy `character_inventory` and the accepted Phase-10 typed Inventory model represent possession/holding.

Forbidden inference:

```text
item present in inventory
=> character owns item
```

This would destroy legal distinctions such as borrowed, rented, entrusted, stolen or organization-owned possessions.

### 6.3 CharacterPanel equipment presentation is not ownership evidence

Legacy CharacterPanel historically projected inventory names through a collection named `equipment`.

Neither the presence of that presentation entry nor a future typed Equipment binding proves ownership.

### 6.4 `character_techniques.is_equipped` is unrelated

Technique `is_equipped` belongs to Technique state and has no ownership meaning.

### 6.5 Finance summary fields are not OwnershipRecords

Current/legacy finance surfaces include summary-like fields such as:

```text
property_value
investment_value
```

Such totals are not stable asset identities and cannot be migrated into per-asset ownership records without explicit source semantics.

They should remain evidence/financial summaries until later Asset/Economy phases provide canonical asset definitions and ledgers.

### 6.6 Organization membership is not asset ownership

Membership, role, loyalty or position in an organization does not imply ownership of organization assets.

Likewise, organization possession of an item must not automatically become ownership by a member/player.

---

## 7. Ownership is a relationship, not a boolean

Do not model:

```text
ItemInstance {
  owned = true
  ownerUid = A
}
```

as the canonical Phase-12 design.

Reasons:

- cannot represent ownership history cleanly,
- cannot represent co-ownership/shares,
- cannot represent temporary or conditional rights,
- cannot preserve previous owner after transfer,
- cannot support organizations/legal entities cleanly,
- cannot replay historical ownership at time T,
- encourages conflation of possession and ownership,
- makes theft/loan/custody cases ambiguous.

Canonical form should be an explicit time-bounded relationship record.

---

## 8. Proposed generic Core model

Exact Kotlin/table names remain implementation decisions. The semantic contract should be equivalent to the following.

### 8.1 `OwnershipRecord`

```text
ownershipRecordUid        stable record identity
campaignId                campaign scope
ownerEntityUid            owner/right-holder identity
assetKind                  generic asset identity namespace/type
assetUid                   stable asset identity
ownershipTypeUid           generic ownership/right type
shareNumerator?            optional exact share component
shareDenominator?          optional exact share component
validFrom                  required historical start marker
validUntil?                null = currently active/open-ended
sourceEventUid?            event that established this record
supersedesRecordUid?       explicit predecessor when applicable
recordVersion              >= 1
provenance                 nonblank structured/source provenance
status?                    ACTIVE/CLOSED/VOID if needed, but time bounds remain canonical
metadata?                  optional extensibility
```

The Core should avoid universe-specific ownership types.

Recommended ownership type semantics should be open/stable UIDs or a very small generic vocabulary, e.g. concepts equivalent to:

```text
FULL
PARTIAL/SHARED
TRUST/HELD_FOR
BENEFICIAL
SECURED/COLLATERAL
OTHER
```

If legal semantics differ between World Packs, a stable `ownershipTypeUid` is safer than a closed hardcoded enum.

### 8.2 `assetKind`

Phase 12 must support more than items.

Possible generic asset namespaces:

```text
ITEM_INSTANCE
PROPERTY
BUSINESS
ORGANIZATION_SHARE
VEHICLE
INFRASTRUCTURE
LAND
ACCOUNT/CLAIM
OTHER
```

However Core should prefer an extensible stable asset-kind UID / entity-reference scheme instead of assuming a fixed universal list.

A robust generic identity shape is:

```text
OwnedAssetRef {
  assetKindUid
  assetUid
}
```

This allows Phase 12 to reference present and future asset domains without embedding every future table into OwnershipRecord.

### 8.3 Owner identity

Owner must use stable entity identity.

Potential owners may include:

- player character,
- NPC,
- organization,
- state/faction,
- company/business entity,
- other legal/fabular entity defined by the World Pack/campaign.

Therefore `ownerEntityUid` must not be restricted to ActivePlayerRef/player-only identity.

Campaign scope remains explicit so identical UID strings in different campaigns cannot leak ownership.

---

## 9. Current ownership vs historical ownership

Ownership history must remain queryable.

Recommended semantics:

```text
historical record:
validFrom = T1
validUntil = T2

current record:
validFrom = T2
validUntil = NULL
```

Current ownership is therefore a projection/query over open records, not destructive replacement of history.

Transfer must never rewrite the previous owner row to the new owner.

Instead:

1. close previous active record at the transfer boundary,
2. create successor record for the new owner,
3. link both to the same committed transfer/source event,
4. preserve exact share/conservation invariants.

This is compatible with MASTER's immutable-history direction and future replay/temporal retrieval.

---

## 10. Transfer semantics

Canonical ownership transfer should be an atomic domain operation.

Conceptual future path:

```text
TransferOwnership command/proposal
-> validate source owner/right
-> validate asset identity
-> validate transferable share/right
-> validate World Pack/legal rules
-> create OwnershipChangeSet
-> transaction
-> close source OwnershipRecord(s)
-> create destination OwnershipRecord(s)
-> append event/ledger evidence
-> commit
```

Phase 12 should not yet implement PlayerCommand/PlayerDomainEngine if Roadmap places them later, but any interim API must preserve these boundaries and must not become a direct AI write bypass.

### Required transfer invariants

- source active ownership exists,
- destination stable owner identity exists or is valid under repository rules,
- transferred share cannot exceed source share,
- full transfer leaves no overlapping full active owner record,
- partial transfer conserves total shares,
- replay/idempotency does not duplicate transfer,
- transfer time boundaries are deterministic,
- source and destination records share explicit causal provenance.

---

## 11. Stackable commodities and ownership

Accepted Phase 10 represents stackable inventory with:

```text
PlayerInventoryStack(
  campaignId,
  characterUid,
  itemDefinitionUid,
  quantity
)
```

This is possession quantity, not a unique asset identity.

Phase 12 must not automatically treat each stack row as one legal asset if legal ownership needs independent tracing.

Three safe strategies exist depending on future domain needs:

### Strategy A — commodity ownership quantity record

For fully fungible goods:

```text
OwnershipRecord / CommodityOwnershipLot {
  owner
  itemDefinitionUid
  quantity/share
  acquisition lot/source event
  validFrom/Until
}
```

Useful when legal provenance of fungible quantities matters.

### Strategy B — ownership omitted for ordinary fungible inventory

If a World Pack/system treats ordinary fungible carried goods as possession-only and does not need legal distinction, Phase 12 may only create OwnershipRecords when explicit legal/asset significance exists.

However this must be a deliberate domain rule, not an inference that inventory == ownership.

### Strategy C — lot/instance identity

If quantities require legal provenance, collateralization, theft tracking or separate acquisition history, introduce stable lot identity rather than collapsing all units into one mutable quantity.

No single strategy should be globally hardcoded without examining actual asset/economy requirements.

Unique `ItemInstance` ownership is much simpler and should use the stable instance UID directly.

---

## 12. Custody / possession / loan semantics

Ownership does not need to duplicate Inventory as a custody table.

Preferred split:

```text
Inventory / location state
= who currently possesses/controls the physical item

OwnershipRecord
= who currently has the recognized ownership/right
```

Loan example:

```text
OwnershipRecord: A -> X remains active
Inventory possession: B -> X
Loan event/obligation: A lends X to B
```

Phase 12 may store a relationship type such as entrusted/beneficial right only if it is truly an ownership/right relation.

Do not force every possession distinction into OwnershipRecord.

Future obligations/loans may belong partly to Phase 14 assets/debts/obligations.

---

## 13. Theft semantics

Theft is the critical proof that possession and ownership must be independent.

Example:

```text
Before:
owner A
possessor A

Theft:
possessor becomes B

After:
owner may remain A
possessor B
```

A World Pack or later legal/economy rule may eventually recognize a legal ownership change under some conditions, but Core must not infer it from theft/possession transfer alone.

Required rule:

```text
Inventory transfer caused by theft
DOES NOT IMPLY
OwnershipRecord transfer
```

The theft event should become provenance/cause evidence for the custody change and possibly later ownership dispute/resolution.

---

## 14. Lost / abandoned / destroyed assets

These are separate concepts.

### Lost

An item may be physically lost while ownership remains active.

```text
owner A
possession/location unknown
```

Do not close ownership solely because inventory possession disappeared.

### Abandoned

Abandonment may intentionally terminate ownership if the governing domain rules define that effect.

It requires an explicit ownership mutation/event.

### Destroyed

Destruction ends the asset's usable existence, but historical ownership remains queryable.

Recommended handling:

- asset domain marks destroyed/retired state,
- active ownership may close at destruction time under explicit rules,
- prior records remain immutable history.

Do not delete ownership history when the asset disappears.

---

## 15. Co-ownership / shares

MASTER explicitly mentions `ownershipType/share` and shares/businesses.

Phase 12 therefore cannot assume exactly one current owner per asset.

For shareable assets:

```text
A: 60%
B: 40%
```

can be valid simultaneously.

For exclusive assets such as many unique items, World Pack/Core policy may require total active shares == 100% and often exactly one full owner.

### Numeric representation

Avoid floating-point share equality for ownership conservation.

Prefer exact representation such as:

```text
shareNumerator: Long
shareDenominator: Long
```

or a fixed decimal/rational type.

Required invariants:

- denominator > 0,
- numerator > 0,
- normalized/canonical form if practical,
- aggregate active ownership share never exceeds allowed total,
- transfer conserves share,
- no NaN/Infinity path.

If Phase 12 initially supports only full ownership for ItemInstance, schema/API should remain forward-compatible with later partial shares rather than encode `owner_uid UNIQUE(asset_uid)` as the permanent universal model.

---

## 16. Provenance and acquisition

Every OwnershipRecord needs non-ambiguous provenance.

Potential acquisition causes include:

- purchase,
- gift,
- inheritance,
- reward,
- crafting/creation,
- conquest/seizure,
- legal transfer,
- organizational allocation,
- explicit migration,
- campaign seed/canon initialization.

Core should store source identity, not world-specific story labels as logic.

Recommended fields/bindings:

```text
sourceEventUid
sourceTransactionUid?
sourceType
sourceUid
actorUid?
method?
engineVersion?
provenance
```

Do not invent provenance for legacy data that does not contain it.

---

## 17. Event ledger / Chronicle integration

MASTER requires significant history to be append-only and events to explain causality.

Ownership changes are significant events and should eventually emit structured events such as generic equivalents of:

```text
OWNERSHIP_ACQUIRED
OWNERSHIP_TRANSFERRED
OWNERSHIP_SHARE_CHANGED
OWNERSHIP_RELINQUISHED
OWNERSHIP_TERMINATED
```

These event names are implementation details; the important contract is causal linkage.

Chronicle is presentation/derived and must not become ownership authority.

Correct direction:

```text
committed OwnershipRecord history + committed events
-> Chronicle rendering
```

Never:

```text
Chronicle sentence
-> inferred OwnershipRecord
```

---

## 18. Save / replay requirements

Ownership history is a strong candidate for replay verification because it is time-bounded and event-linked.

Required future replay invariant:

```text
replay committed ownership transfer events
-> same current active OwnershipRecords
-> same historical record chain
```

Save/restore must preserve:

- record UIDs,
- owner/asset UIDs,
- share values,
- validFrom/validUntil boundaries,
- supersession links,
- provenance/source events,
- campaign scope.

Snapshot/cache deletion must not lose legal ownership history.

---

## 19. AI mutation authority

MASTER is explicit: AI does not directly mutate ownership.

Forbidden paths:

```text
AI narration says "you own X"
-> direct ownership write
```

```text
CharacterPanel shows X
-> synthesize ownership
```

```text
Inventory contains X
-> synthesize ownership
```

```text
Equip X
-> synthesize ownership
```

Future legal path:

```text
AI/player/world proposes action
-> explicit ownership-domain command/change
-> validate identity/rules/share/provenance
-> transaction
-> OwnershipRecord + event/ledger
-> commit
```

Until PlayerCommand/PlayerDomainEngine phases are implemented, Phase-12 store methods must be clearly internal/domain-authoritative and must not legitimize raw backend/AI row writes.

---

## 20. Legacy migration policy

Conservative rule:

```text
legacy evidence
-> preserve

explicit semantic mapping
-> canonical ownership

ambiguous evidence
-> unresolved / no ownership generation
```

### Never infer OwnershipRecord from

- `character_inventory`,
- Phase-10 `PlayerInventoryStack`,
- Phase-10 `PlayerInventoryUnique`,
- CharacterPanel equipment list,
- Equipment state/loadout,
- `character_techniques.is_equipped`,
- item names,
- financial summary totals,
- narrative/prompt text,
- location of item.

### Explicit legacy mapping

If future preflight discovers real legacy ownership data, use an explicit mapping/compatibility model such as:

```text
LegacyOwnershipEvidence {
  evidenceUid
  campaignId
  raw owner evidence
  raw asset evidence
  raw temporal/source fields
  rawFields
}

LegacyOwnershipMapping {
  campaignId
  evidenceUid
  canonicalOwnerUid
  canonicalAssetKindUid
  canonicalAssetUid
  ownershipTypeUid
  share
  mappingVersion
  provenance
}
```

No label/name-based auto-merge.

No invented legal provenance.

---

## 21. Current-record query semantics

Current ownership should be computed deterministically from historical records.

Example criterion:

```text
validFrom <= queryTime
AND
(validUntil IS NULL OR queryTime < validUntil)
```

The exact inclusive/exclusive boundary must be chosen once and tested consistently.

Recommended temporal convention:

```text
[validFrom, validUntil)
```

This avoids two owners appearing active at the exact transfer boundary when one record closes and the successor opens at the same timestamp/turn/event sequence.

If campaign time is not yet sufficiently precise, use a stable ordered commit/event sequence or ownership revision ordinal in addition to narrative time.

---

## 22. Determinism and identity

Ownership correctness must never depend on:

- SQLite row order,
- display name,
- insertion order,
- CharacterPanel ordering,
- guessed current owner,
- first matching inventory entry.

Stable UID precedence:

```text
ownershipRecordUid
ownerEntityUid
assetKindUid
assetUid
sourceEventUid
```

Transfers at the same campaign time must still have deterministic commit/event ordering.

---

## 23. Campaign / entity scoping

Every ownership relation is campaign scoped.

Hard requirements:

- Campaign A ownership cannot leak to Campaign B.
- Same owner UID string in two campaigns is not the same ownership state.
- Player is not the only possible owner entity.
- NPC/organization ownership must remain possible without schema redesign.
- Ownership queries must not rely on ActivePlayerRef when the owner being queried is another entity.

For player UI, ActivePlayerRef may select whose ownership projection to display; it must not define ownership semantics.

---

## 24. Relationship with Inventory

### Unique ItemInstance

Canonical link:

```text
OwnershipRecord.assetUid
-> ItemInstance.itemInstanceUid
```

Inventory possession may independently point to the same instance UID.

### Possession transfer without ownership transfer

Allowed and required.

### Ownership transfer without immediate possession transfer

Also allowed.

Example:

- sale contract transfers title,
- physical delivery occurs later.

This proves Inventory and Ownership must be independently mutable within one higher-level atomic transaction when required.

### Asset absent from inventory

Ownership can still remain valid.

Examples:

- stored elsewhere,
- property/land/business,
- lost item,
- loaned item,
- remotely held asset.

---

## 25. Relationship with Equipment

Equipment references possession/item identity and activates derived effects.

Ownership does not participate directly in DerivedValueResolver.

Possible World Pack/game rules may require ownership, authorization or permission before equip/use, but that should be an explicit requirement input, not a hidden assumption.

Hard invariants:

```text
Equip X
DOES NOT mutate OwnershipRecord
```

```text
Unequip X
DOES NOT mutate OwnershipRecord
```

```text
Equipment modifier sourceActive toggle
DOES NOT mutate OwnershipRecord
```

---

## 26. Relationship with Phase 13 Economy

Phase 13 follows Phase 12 and introduces Financial Ledger/Economy.

Phase 12 must not implement economy now, but should expose stable ownership identity suitable for purchase/sale integration.

Future purchase transaction may atomically include:

```text
FinancialTransaction: buyer -> seller payment
Ownership transfer: seller -> buyer asset
Inventory/custody transfer: optional/immediate or later
Event(s)
```

These three mutations are related but not interchangeable.

Money transfer alone does not prove ownership unless transaction/domain rules explicitly bind them.

---

## 27. Relationship with Phase 14 Assets / debts / obligations

MASTER requires ownership of property, land, business, shares and rare assets.

Phase 12 should therefore provide a reusable ownership relationship layer, while Phase 14 can define richer Asset/Liability objects.

Recommended dependency direction:

```text
Asset domain defines asset identity/value/liability semantics
OwnershipRecord references asset identity
```

OwnershipRecord should not contain all asset-specific metadata.

This avoids turning Phase 12 into a second Inventory/Economy/Asset database.

---

## 28. Current working state vs immutable history

For performance, Phase 12 may optionally maintain a current-ownership projection/index.

If used:

```text
ownership_records = authoritative history
current_ownership_projection = derived/cache
```

Deleting/rebuilding the projection must reproduce identical current state.

Do not make the cache the only place where current owner exists.

An alternative is querying active records directly if performance is sufficient.

---

## 29. Proposed persistence architecture

If Phase 12 adds tables, recommended minimum conceptual schema:

### `ownership_records`

```text
ownership_record_uid PRIMARY KEY
campaign_id
owner_entity_uid
asset_kind_uid
asset_uid
ownership_type_uid
share_numerator
share_denominator
valid_from_order
valid_until_order NULL
source_event_uid NULL
supersedes_record_uid NULL
record_version
provenance
```

Useful indexes:

```text
(campaign_id, asset_kind_uid, asset_uid, valid_until_order)
(campaign_id, owner_entity_uid, valid_until_order)
(campaign_id, source_event_uid)
```

Avoid a universal unique constraint that forbids multiple current owners, because co-ownership must remain representable.

Instead enforce share/exclusivity through domain validation according to asset/ownership type rules.

### Optional `ownership_events` table

Do not duplicate the global future Event Store if the repository already has/gets one.

Prefer references to canonical event identity over a parallel Ownership-only event universe.

---

## 30. Transfer atomicity and race/conflict safety

A transfer must never leave:

- source record closed but destination missing,
- destination record created while source remains illegally full-active,
- share total > allowed maximum,
- duplicated successor after retry.

Therefore close+create must occur in one transaction.

Until global TurnTransaction is complete, Phase 12 must still use a local atomic persistence transaction and expose transaction/idempotency hooks suitable for later integration.

---

## 31. Required implementation test gates

### Roadmap / boundary

1. Phase 12 implements OwnershipRecord only; no Phase 13 economy redesign.
2. Inventory possession != ownership.
3. Equipment state != ownership.
4. Item location != ownership.

### Identity

5. stable OwnershipRecord UID.
6. stable owner entity UID.
7. stable asset kind + asset UID.
8. same asset display name does not merge ownership.
9. same UID strings in different campaigns remain isolated.
10. non-player owner entity supported.

### Historical state

11. acquire creates active ownership record.
12. transfer closes source record and creates successor.
13. previous owner remains historically queryable.
14. current ownership query returns only active interval.
15. query at historical time returns correct owner/share.
16. boundary time semantics deterministic.
17. reopen preserves full record chain.

### Possession divergence

18. A owns X and possesses X.
19. A owns X while B possesses X.
20. theft moves possession without automatic ownership transfer.
21. loss removes possession without deleting ownership.
22. return of borrowed item changes possession only.
23. inventory transfer without ownership mutation preserves ownership.
24. ownership transfer without possession transfer is representable.

### Equipment boundary

25. equip does not change ownership.
26. unequip does not change ownership.
27. equipment source lifecycle does not change ownership.
28. removing equipment-derived modifier does not change ownership.

### Unique items

29. OwnershipRecord binds to stable ItemInstance UID.
30. missing ItemInstance target fails or remains explicit unresolved state according to policy.
31. deleted/destroyed item preserves ownership history.
32. two same-name ItemInstances remain distinct assets.

### Shares / co-ownership

33. full single owner legal.
34. partial co-ownership legal where asset policy permits.
35. aggregate shares cannot exceed allowed total.
36. partial transfer conserves share.
37. full transfer leaves no overlapping illegal full ownership.
38. denominator zero rejected.
39. invalid/negative share rejected.
40. no floating NaN/Infinity ownership share path.

### Provenance

41. provenance required.
42. source event preserved.
43. transfer source/destination records linked causally.
44. legacy migration does not invent provenance.

### Legacy

45. `character_inventory` alone generates zero OwnershipRecords.
46. CharacterPanel equipment generates zero OwnershipRecords.
47. equipped item state alone generates zero OwnershipRecords.
48. item name alone generates zero OwnershipRecords.
49. explicit legacy ownership mapping canonicalizes exactly once.
50. ambiguous legacy evidence remains unresolved.
51. legacy bytes/evidence preserved.

### Isolation / scale

52. campaign isolation.
53. owner A/B isolation.
54. asset A/B isolation.
55. organization owner vs player owner isolation.
56. 1000+ ownership records no authoritative truncation.
57. 1000 historical transfers remain queryable.

### Persistence / migration

58. migration additive/idempotent.
59. production current-schema reaches Phase 12 once implemented.
60. restore reaches Phase 12.
61. campaign switch reaches Phase 12.
62. Phase 3–11 state unchanged by migration.
63. integrity_check.
64. foreign_key_check or explicit adopted FK policy.

### Replay / no-retrogression

65. save -> close -> reopen -> same current and historical ownership.
66. replay transfer history -> identical current owner/share.
67. current-state cache delete/rebuild -> identical result if cache exists.
68. closing ownership does not delete historical record.
69. rejected transfer leaves no partial mutation.
70. retry/idempotent transfer does not duplicate successor.

---

## 32. Automatic implementation blockers

Phase 12 implementation must be rejected if it:

- models ownership only as `owned=true`,
- stores only mutable `ownerUid` on ItemInstance with no history,
- infers ownership from Inventory,
- infers ownership from Equipment,
- infers ownership from CharacterPanel/prompt/narrative labels,
- assumes possession transfer == ownership transfer,
- lets theft automatically change legal ownership,
- cannot represent historical owners,
- cannot represent non-player owners,
- cannot support or remain forward-compatible with partial/shared ownership,
- deletes ownership history after transfer/loss/destruction,
- uses display name as asset identity,
- permits cross-campaign ownership leakage,
- lets AI/backend mutate ownership outside a validated domain/transaction boundary,
- mutates Phase 10 Inventory or Phase 11 Equipment merely to make ownership state appear consistent.

---

## 33. Implementation dependencies

Phase 12 implementation requires:

1. accepted Phase 11 Equipment runtime/contract,
2. stable accepted Phase-10 ItemInstance/Inventory API,
3. final decision on generic entity/asset reference representation,
4. migration/current-schema hook,
5. transaction-safe history writes,
6. preservation of all Phase 3–11 contracts.

Phase 13 Economy may later consume OwnershipRecord, but Phase 12 must not wait for or implement the Financial Ledger.

---

## 34. Recommended implementation sequence after Phase 11 COMPLETE

1. re-read accepted Phase-11 runtime and exact master;
2. audit all real legacy owner/property/business/share-like schemas with PRAGMA;
3. choose generic `OwnedAssetRef` representation;
4. implement immutable/time-bounded OwnershipRecord persistence;
5. implement current/historical query APIs;
6. implement explicit acquire/transfer/close domain-safe store operations;
7. add ItemInstance integration without possession inference;
8. add optional explicit legacy mapping only for proven ownership evidence;
9. wire current-schema migration;
10. add transfer/share/history/isolation/replay tests;
11. run full JVM/build/CI and independent integrity/adversarial validation.

---

## 35. Final contract

Canonical Phase-12 relationship:

```text
Inventory possession
!= Equipment state
!= OwnershipRecord
```

Canonical ownership is:

```text
stable owner identity
+
stable asset identity
+
ownership/right type
+
exact share where relevant
+
historical validity interval
+
source event/provenance
+
immutable/superseded history
```

Critical examples remain legal and unambiguous:

```text
A owns X and possesses X
A owns X while B possesses X
B steals X without automatic legal ownership transfer
A equips/unequips X without ownership change
inventory transfer without ownership transfer
ownership transfer without immediate possession transfer
```

The model is generic, campaign-scoped, replayable, provenance-bearing and compatible with future item, property, business, share and asset domains without hardcoding a specific World Pack.

---

# FINAL STATUS

`NEXT PHASE ARCHITECTURE READY — IMPLEMENTATION BLOCKED BY PHASE 11`
