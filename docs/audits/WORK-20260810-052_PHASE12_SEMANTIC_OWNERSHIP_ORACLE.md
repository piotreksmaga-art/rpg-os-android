# WORK-20260810-052 — Phase 12 Semantic Ownership Oracle

Status: READ-ONLY RUNTIME / SEMANTIC ORACLE READY

Work ID: `WORK-20260810-052`
Worker: `CHAT-2`
Role: READ-ONLY PHASE 12 SEMANTIC ORACLE
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master observed before report commit: `6c31afaab2d6d72f246655e07fe0cb2f74e88b8f`
Accepted Phase-11 runtime under oracle baseline: `c87193a69136a6680102779e4f0cd3d90a616d41`
Architecture input: `docs/audits/WORK-20260810-049_PHASE12_OWNERSHIP_ARCHITECTURE.md`

This document is an independent semantic oracle for Phase 12. It does not implement runtime, schema, migrations, OwnershipStore, commands, change sets, economy, assets/liabilities, or any hotfix. It defines observable semantics against which the eventual `FINAL WORK-20260810-051 RESULT COMMIT` must be revalidated.

---

## 1. Canonical boundary

The hard semantic identity split is:

```text
LEGAL OWNERSHIP
!= PHYSICAL POSSESSION
!= EQUIPMENT
!= CUSTODY
!= LOAN
!= LEGACY/PRESENTATION LABEL
```

Each axis answers a different question.

### Legal ownership

Answers who has the recognized ownership/right relation to a stable asset identity, for what exact share/type, during what historical interval, and under what source event/provenance.

### Physical possession

Answers who currently physically holds/carries/controls an asset. In accepted Phase 10, unique-item possession is represented by `PlayerInventoryUnique(characterUid, itemInstanceUid, ...)`; stack possession is represented by `PlayerInventoryStack(... quantity ...)`.

### Equipment

Answers whether a possessed unique item instance is bound into a character loadout/slots. Accepted Phase 11 `PlayerEquipment` references `itemInstanceUid` and remains loadout authority, not ownership authority.

### Custody

Answers who is entrusted with/control-responsible for an asset without necessarily receiving title. Custody can coincide with possession but is not ownership.

### Loan

A loan is a causal/contractual relation explaining why possession/custody may differ from ownership. A loan does not inherently transfer title.

### Legacy/presentation label

Names, legacy rows, panel labels, strings such as `equipment`, inventory item names, finance summary values, organization roles, or narrative descriptions are evidence/presentation only unless an explicit canonical migration/domain rule creates an OwnershipRecord.

Forbidden inference family:

```text
inventory row -> ownership
physical holder -> ownership
equipment binding -> ownership
custody -> ownership
borrower -> ownership
legacy item name -> ownership
panel label -> ownership
```

---

## 2. Runtime boundary verified from accepted Phase 10/11

At accepted Phase-11 runtime `c87193a...`, the relevant accepted APIs establish the separation required by this oracle.

Phase 10:

```text
ItemInstance(
  campaignId,
  itemInstanceUid,
  itemDefinitionUid,
  instanceVersion,
  provenance
)

PlayerInventoryUnique(
  campaignId,
  characterUid,
  itemInstanceUid,
  entryVersion,
  provenance
)
```

`InventoryStore.transferUnique(A, B, X, provenance)` changes the inventory holder of the stable `itemInstanceUid = X`. It has no ownership argument and therefore MUST NOT be interpreted by Phase 12 as proof of title transfer.

Phase 11:

```text
PlayerEquipment(
  campaignId,
  characterUid,
  equipmentEntryUid,
  itemInstanceUid,
  compatibilityRuleUid,
  loadoutUid,
  entryVersion,
  provenance
)
```

Equipment authority therefore consumes stable unique item identity but does not contain owner identity/share/title semantics.

Required Phase-12 integration invariant:

```text
ItemInstance identity
!= PlayerInventoryUnique possession
!= PlayerEquipment loadout state
!= OwnershipRecord title/right
```

---

## 3. OwnershipRecord semantic minimum

Exact Kotlin/table names are implementation details. Observable behavior must be equivalent to a durable time-bounded relation containing at least:

```text
stable ownership-record identity
campaign identity
stable owner identity
stable asset identity / asset kind
ownership type
exact share
validFrom
validUntil or open-ended marker
source event / provenance
record version or equivalent stale-write guard if mutations expose versions
```

### Stable owner identity

Owner identity MUST be a stable UID, not a display name. Renaming `A` must not change ownership identity. The same display name on two entities must not merge rights. Owner scope must support more than only the active player; organizations/NPCs/future legal entities must remain representable.

### Stable asset identity

Asset identity MUST be stable and not derived from display name. For unique inventory items the canonical asset reference is the existing `itemInstanceUid` plus its asset-kind/namespace if needed. Two swords both named `Katana` must remain distinguishable if their instance UIDs differ.

### Ownership type

Ownership type describes the legal/right relation and must not be inferred from physical possession. Core must not hardcode world-specific legal doctrine. If types are extensible UIDs, unknown valid types must remain lossless.

### Source event / provenance

Every authoritative ownership establishment, transfer, split, close, abandonment or destruction-related closure requires nonblank provenance and, where event infrastructure is available, a source-event identity or equivalent causal link. Presentation labels are not acceptable provenance substitutes.

---

## 4. Temporal interval oracle

Canonical interval semantics for ownership history are half-open:

```text
[validFrom, validUntil)
```

where `validUntil = null` means open-ended/current.

Therefore for record R:

```text
activeAt(R, T) := R.validFrom <= T AND (R.validUntil == null OR T < R.validUntil)
```

Required invariants:

- `validFrom` is required.
- If `validUntil != null`, then `validUntil > validFrom`.
- A closed record remains queryable historically.
- Closing a record changes its end boundary but does not rewrite its previous owner, asset, start, share or causal identity.
- Successor and predecessor may touch at exactly the same boundary `T` without temporal overlap because predecessor excludes `T` and successor includes `T`.
- Historical queries at `T < validFrom` must not see the record.
- Historical queries at `T == validUntil` must not see the closed record.

This boundary choice is required for deterministic before/after transfer behavior.

---

## 5. Exact share semantics

Ownership shares MUST use deterministic exact arithmetic. Floating point is semantically invalid for conservation.

Preferred oracle representation:

```text
share = numerator / denominator
numerator > 0
denominator > 0
```

Equivalent exact fixed-scale integer representation is acceptable if its scale is canonical and cannot round inconsistently.

### Canonical rational form

If rational storage is used, normalize before equality/comparison:

```text
g = gcd(abs(numerator), denominator)
normalized = (numerator / g, denominator / g)
```

Examples:

```text
1/2 == 2/4 == 50/100
1/3 + 1/3 + 1/3 == 1 exactly
```

No epsilon comparison is permitted.

### Aggregate invariant

For a given `(campaign, asset, ownership-right class, T)`:

```text
0 < each active share <= 1
SUM(active compatible title shares at T) <= 1
```

For assets/policies requiring complete allocation after an ownership-establishing operation:

```text
SUM(active title shares at T) == 1
```

Whether temporary unowned remainder is legal is an explicit domain policy; it must never occur accidentally through arithmetic loss.

### Deterministic ordering

Queries returning multiple co-owners must have deterministic ordering, e.g. normalized stable owner UID then ownership record UID. Storage iteration order is not an observable contract.

---

## 6. Full transfer oracle

Initial state:

```text
R1: owner=A, asset=X, share=1/1, validFrom=T0, validUntil=null
```

Operation at exact boundary `T1`:

```text
A transfers full ownership of X to B
```

Expected committed result:

```text
R1: owner=A, asset=X, share=1/1, validFrom=T0, validUntil=T1
R2: owner=B, asset=X, share=1/1, validFrom=T1, validUntil=null
```

Required semantics:

- one atomic commit closes A and opens B,
- no instant where both A and B have overlapping full title under `[from, until)`,
- no destructive replacement of R1,
- R1 remains historical evidence,
- transfer source event/provenance causally links closure and successor creation,
- possession/equipment are unchanged unless the same higher-level transaction explicitly includes separate legal mutations for those domains.

---

## 7. Mandatory sale oracle: A sells X to B

Assume before sale:

```text
T0 < T_sale
R_A = A owns X 1/1 on [T0, +inf)
```

At `T_sale`, a valid completed sale explicitly transfers title from A to B.

Expected ownership state after commit:

```text
R_A.validUntil = T_sale
R_B.validFrom = T_sale
R_B.validUntil = null
R_B.share = 1/1
```

Historical query expectations:

```text
ownersAt(X, T_sale - epsilon/logical predecessor instant) => A 1/1
ownersAt(X, T_sale)                             => B 1/1
ownersAt(X, time after T_sale)                  => B 1/1
history(X)                                      => [R_A, R_B]
currentOwners(X)                                => B 1/1
```

If campaign time is discrete rather than continuously ordered, the implementation must expose an equivalent deterministic commit boundary so that "before transfer" and "at/after transfer" cannot both resolve to the same ambiguous interval.

A sale must not be inferred from money movement alone unless the sale domain operation explicitly includes ownership transfer semantics. Phase 13 economy is later in the roadmap; Phase 12 must not prematurely treat any financial transaction as title authority.

---

## 8. Partial transfer oracle

Initial:

```text
A owns X = 1/1
```

At T1, A transfers `1/4` to B.

Expected current allocation:

```text
A = 3/4
B = 1/4
SUM = 1
```

History must preserve the pre-T1 `A=1/1` interval. A safe representation is to close the old full-share record and create new successor share records at T1:

```text
R0 A 1/1 [T0,T1)
R1 A 3/4 [T1,+inf)
R2 B 1/4 [T1,+inf)
```

An implementation may use equivalent event-sourced decomposition, but historical queries must return the same result.

Invalid operations:

```text
A transfers 5/4 -> reject atomically
A transfers 0 -> reject
negative share -> reject
partial transfer whose exact arithmetic would exceed aggregate 1 -> reject
```

No truncation, rounding or hidden renormalization.

---

## 9. Co-ownership oracle

Valid example:

```text
A owns 3/5 of X
B owns 2/5 of X
```

Expected:

```text
currentOwners(X) => {A:3/5, B:2/5}
SUM == 1 exactly
```

Co-ownership is not represented as one owner string, one mutable `ownerUid`, a comma-separated label, or a presentation array with no durable records.

A full-owner invariant must therefore be policy-sensitive. The generic Phase-12 domain cannot globally enforce "exactly one current owner per asset" because MASTER explicitly requires ownership share semantics.

---

## 10. Theft oracle — mandatory semantic proof

Initial state:

```text
legal owner: A owns X 1/1
physical possession: A possesses X
```

Event:

```text
B steals X
```

Expected post-event state:

```text
LEGAL OWNERSHIP:      A owns X 1/1
PHYSICAL POSSESSION: B possesses X
EQUIPMENT:            independent
```

Hard rule:

```text
THEFT / InventoryStore.transferUnique(A,B,X,...)
DOES NOT AUTOMATICALLY CREATE
Ownership transfer A->B
```

Historical ownership of A remains open unless a later explicit ownership adjudication/transfer/abandonment rule changes it.

Any Phase-12 implementation that derives `currentOwner(X)=B` solely because `B` is the `PlayerInventoryUnique.characterUid` fails this oracle.

---

## 11. Loan and custody oracle

Initial:

```text
A owns X 1/1
A possesses X
```

Loan/custody event:

```text
A lends/entrusts X to B
```

Expected:

```text
ownership: A remains 1/1
possession/custody: B may hold X
loan/obligation: separate causal relation
```

Returning the loan changes possession/custody, not title.

If a world rule creates a special right such as beneficial/trust ownership, it must be an explicit ownership-type record and must not be silently generated from generic custody.

---

## 12. Lost / abandoned / destroyed asset oracle

### Lost

```text
A owns X
X disappears from known inventory / location unknown
```

Expected ownership:

```text
A remains legal owner
```

Loss of possession is not title closure.

### Abandoned

Abandonment may end title only through an explicit ownership-domain mutation supported by governing rules.

Expected after valid abandonment at T1:

```text
A's active ownership closes at T1
historical A record remains queryable
no replacement owner is invented
```

If unowned assets are not allowed by policy, abandonment must reject or route to a defined recipient; it may not silently assign "world", inventory holder, finder, or null-like fake owner.

### Destroyed

Destruction is primarily asset lifecycle state. Historical ownership must survive destruction.

At destruction T1, if rules close active title:

```text
owner record validUntil = T1
history(X) still contains prior ownership
currentOwners(X) may be empty because asset no longer exists as an active ownable object
```

Deleting OwnershipRecords because the asset is destroyed is a semantic failure.

---

## 13. Legacy / migration semantic oracle

Legacy sources are not sufficient to create canonical ownership by themselves.

Forbidden automatic migrations:

```text
character_inventory.character/entity row -> owner
PlayerInventoryUnique.characterUid -> owner
PlayerInventoryStack.characterUid -> owner
PlayerEquipment.characterUid -> owner
CharacterPanel "equipment" label -> owner
item display name -> owner
property_value / investment_value summary -> per-asset owner
organization membership/role -> organization asset ownership
```

The accepted Phase-10 `LegacyInventoryMapping` establishes item identity/possession reconciliation, not title. Therefore Phase 12 MUST NOT reinterpret a Phase-10 mapping as ownership provenance.

Safe legacy result when no explicit ownership evidence exists:

```text
ownership remains UNKNOWN / no canonical OwnershipRecord created
legacy evidence remains lossless and queryable through its existing domain
```

"No OwnershipRecord" is semantically superior to invented ownership.

---

## 14. Historical query oracle

Minimum observable query semantics must support equivalent answers to:

```text
currentOwners(asset)
ownersAt(asset, time)
ownershipHistory(asset)
ownershipByOwner(owner, time/current)
```

Required examples:

Given:

```text
A 1/1 [10,20)
B 1/1 [20,30)
C 1/1 [30,+inf)
```

Expected:

```text
ownersAt(X, 9)  = {}
ownersAt(X, 10) = {A:1}
ownersAt(X, 19) = {A:1}
ownersAt(X, 20) = {B:1}
ownersAt(X, 29) = {B:1}
ownersAt(X, 30) = {C:1}
currentOwners(X)= {C:1}
history(X)      = A then B then C in deterministic temporal order
```

A query that filters only `validUntil IS NULL` is insufficient for historical truth. A query that destructively overwrites previous records cannot pass.

---

## 15. Concurrency oracle

Phase 12 ownership is integrity-critical. Results of competing writes must be equivalent to some valid serialization and must not violate shares/history.

### 15.1 A->B versus A->C full-transfer race

Initial:

```text
A owns X 1/1, record/version V
```

Concurrent operations:

```text
Tx1: A -> B full transfer based on V
Tx2: A -> C full transfer based on V
```

Allowed result:

```text
exactly one transfer commits
other operation rejects as stale/not-current-owner/conflict
```

Forbidden result:

```text
B owns 1/1 AND C owns 1/1
A closed twice with two independent successor full owners
last-writer-wins with silent loss of first committed history
```

### 15.2 Share race

Initial:

```text
A owns 1/1
```

Concurrent:

```text
Tx1 transfers 3/4 A->B
Tx2 transfers 3/4 A->C
```

At most one can commit against the same source state unless the second revalidates the post-first remainder and then correctly rejects because A has only 1/4.

Aggregate title must never become `B=3/4, C=3/4`.

For a legal pair such as concurrent `1/4` and `1/4`, an implementation may serialize both if the second revalidates current state and conservation remains exact. It may also reject stale input. It may not apply both against an obsolete `A=1` snapshot without conflict validation.

### 15.3 Stale-owner transfer

Sequence:

```text
A owns X
A->B commits
stale request A->C arrives afterward
```

Expected:

```text
stale A->C rejects atomically
B remains owner
history unchanged by rejected request
```

Source owner identity must be validated against authoritative active share at commit time, not merely at UI/precheck time.

### 15.4 Temporal overlap race

Two writes must not create incompatible active intervals whose shares exceed 1 at the same instant.

Example forbidden state:

```text
A 1/1 [T0,T2)
B 1/1 [T1,+inf)
with T0 < T1 < T2
```

Any overlap guard must be enforced transactionally/authoritatively, not only by a read-before-write check vulnerable to TOCTOU.

### 15.5 Transfer-vs-close race

Initial:

```text
A owns X 1/1 open-ended
```

Concurrent:

```text
Tx1: transfer A->B at T1
Tx2: close A record at T2 / abandonment/destruction-related close
```

Result must be equivalent to one valid ordering and must not mutate a stale predecessor after it has been superseded.

Possible serialized outcomes depend on business rules:

1. close wins first: transfer validates source at its boundary and either rejects or uses only remaining legally active interval;
2. transfer wins first: stale close targeting A's formerly-open record must reject unless it exactly matches/acknowledges the already committed end state.

Forbidden:

```text
transfer creates B successor, then stale close rewrites A's validUntil to a later time causing overlap with B
```

A record version/CAS predicate, transactional current-state predicate, database constraint/trigger, or equivalent authoritative guard is required to make this deterministic.

---

## 16. Atomic failure oracle

For every invalid ownership mutation:

```text
validation failure
stale owner
share overflow
invalid interval
unknown asset identity
unknown/invalid owner identity under active policy
concurrency conflict
provenance failure
```

Expected:

```text
no partial closure
no partial successor insertion
no share drift
no historical rewrite
no orphan successor
no possession/equipment side effect
```

If a full transfer closes A but insertion of B fails, the transaction MUST rollback A's closure.

---

## 17. Identity and campaign isolation oracle

Given two campaigns `C1` and `C2` both containing textual `assetUid=X` and `ownerUid=A`, their ownership histories must remain isolated by campaign identity.

Expected:

```text
C1:A->X does not appear in C2 queries
C2 transfer does not close/alter C1 record
```

Stable UID means identity inside its defined namespace/scope; it does not license cross-campaign joins that ignore `campaignId`.

---

## 18. Equipment / possession non-coupling regression oracle

The eventual Phase-12 runtime must preserve accepted Phase-10/11 behavior.

Required regression cases:

```text
1. A owns X; A equips X -> ownership unchanged.
2. A owns X; A unequips X -> ownership unchanged.
3. A owns X; InventoryStore.transferUnique(A,B,X,"theft") -> ownership unchanged unless explicit ownership operation also executes.
4. B physically possesses X; B equips X if equipment rules allow -> this still does not establish B title.
5. Ownership A->B transfer alone -> does not automatically mutate inventory holder or equipment binding unless an explicit composite domain transaction includes those operations.
```

The ownership store must not use `PlayerEquipment.characterUid` or inventory holder as a fallback owner when no OwnershipRecord exists.

---

## 19. Required semantic test matrix for WORK-051 revalidation

A candidate Phase-12 runtime cannot receive semantic PASS unless the implementation/evidence supports at least the following observable cases:

| ID | Scenario | Expected |
|---|---|---|
| O-01 | stable owner rename | ownership follows UID, not name |
| O-02 | duplicate display asset names | identities stay separate by stable asset UID |
| O-03 | full A->B transfer | A historical closed; B current full owner |
| O-04 | historical query before/at transfer | before=A, boundary/after=B |
| O-05 | partial 1/4 transfer | A=3/4, B=1/4 exactly |
| O-06 | co-ownership 3/5+2/5 | exact sum=1 |
| O-07 | share overflow | atomic reject |
| O-08 | equivalent rationals | 1/2 == 2/4 deterministically |
| O-09 | theft | owner A, possessor B |
| O-10 | loan/custody | owner A remains |
| O-11 | lost item | ownership remains |
| O-12 | abandonment | only explicit legal mutation closes title |
| O-13 | destroyed asset | history preserved |
| O-14 | inventory/equipment legacy label | no automatic ownership seed |
| O-15 | full-transfer race A->B/A->C | exactly one valid winner |
| O-16 | share race | no over-allocation |
| O-17 | stale-owner transfer | reject |
| O-18 | temporal overlap | prevent incompatible overlap |
| O-19 | transfer-vs-close | serializable/no stale interval rewrite |
| O-20 | failed successor insertion | predecessor closure rolls back |
| O-21 | campaign isolation | no cross-campaign leakage |
| O-22 | ownership-only transfer | inventory/equipment unchanged |
| O-23 | possession-only transfer | ownership unchanged |
| O-24 | deterministic query ordering | repeatable stable order |
| O-25 | nonblank provenance/source | authoritative mutation traceable |

---

## 20. Semantic blockers for final revalidation

Any one of the following is a reproducible semantic blocker and requires `PHASE 12 SEMANTIC REVALIDATION: FAIL`:

- ownership is inferred automatically from Inventory/Equipment/legacy/presentation state;
- theft or possession transfer automatically changes title;
- owner or asset identity uses display name instead of stable identity;
- history is destructively overwritten on transfer;
- historical query cannot distinguish before vs at/after transfer boundary;
- floating-point share arithmetic permits nondeterministic conservation;
- partial transfer can exceed source share or aggregate share can exceed 1;
- co-ownership is impossible despite generic share semantics;
- race A->B versus A->C can commit two incompatible full owners;
- stale owner can transfer after losing title;
- temporal overlap permits incompatible active ownership intervals;
- transfer-vs-close can rewrite an already superseded interval into overlap;
- invalid transfer leaves partial closure/successor state;
- destroyed/lost asset deletes historical ownership evidence;
- ownership mutation leaks across campaigns;
- ownership-only change silently moves/equips an item, or possession/equipment-only change silently creates title.

---

## 21. Revalidation protocol for ETAP 2

When repository history contains the explicit `FINAL WORK-20260810-051 RESULT COMMIT`:

1. refresh `master`;
2. identify the exact runtime result SHA published by WORK-051;
3. inspect that exact commit and relevant parent baseline, not merely later master state;
4. inspect Phase-12 model/store/migration/repository APIs and tests;
5. execute/reason through O-01..O-25 against the exact candidate;
6. verify concurrency protections are authoritative, not only prechecks;
7. verify no automatic legacy ownership seed from Inventory/Equipment/name;
8. verify historical interval and exact-share semantics;
9. treat CI as supporting evidence only, never as semantic proof;
10. publish one verdict:

```text
PHASE 12 SEMANTIC REVALIDATION: PASS
```

or

```text
PHASE 12 SEMANTIC REVALIDATION: FAIL
```

A FAIL report must name a concrete reproducible scenario, initial state, operation sequence, actual result, and expected oracle result. No hotfix implementation belongs to WORK-20260810-052.

---

## 22. Etap 1 verdict

The independent ownership oracle is defined and grounded in MASTER, Roadmap, WORK-049, and the accepted Phase-10/11 identity/possession/loadout APIs.

# PHASE 12 SEMANTIC ORACLE READY
