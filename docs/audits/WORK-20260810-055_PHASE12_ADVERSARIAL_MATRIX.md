# WORK-20260810-055 — Phase 12 Adversarial Matrix

Status: MATRIX READY / FINAL VALIDATION PENDING

Work ID: `WORK-20260810-055`
Worker: `CHAT-5`
Role: READ-ONLY PHASE 12 ADVERSARIAL AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-11 runtime: `c87193a69136a6680102779e4f0cd3d90a616d41`
Fresh master observed at matrix preparation: `6c31afaab2d6d72f246655e07fe0cb2f74e88b8f`
Phase-12 implementation work: `WORK-20260810-051` — not present on master at matrix preparation time.
Architecture input: `docs/audits/WORK-20260810-049_PHASE12_OWNERSHIP_ARCHITECTURE.md`

This artifact defines the adversarial validation matrix only. It does not issue Phase-12 PASS/FAIL and does not implement or repair runtime/schema.

## 1. Canonical invariants under attack

The matrix treats these separations as hard invariants:

```text
Inventory possession != Equipment state != OwnershipRecord
Ownership identity = stable UID identity, never display name
Ownership history is durable and temporally queryable
Current ownership is derived from valid active records
Campaign/entity/asset scopes cannot leak
Transfers/close operations must preserve share conservation atomically
```

For a unique exclusive asset, two simultaneous active FULL owners are invalid. For shareable assets, aggregate active share must never exceed 100% (1/1), and transfer must conserve the exact share representation.

## 2. Core adversarial matrix

| ID | Attack | Minimal adversarial setup/action | Required result |
|---|---|---|---|
| OWN-ADV-001 | ownership vs possession | A owns X; possession X moves A -> B without ownership command | Owner remains A; only possession changes |
| OWN-ADV-002 | ownership vs Equipment | A owns X; B possesses X; equip/unequip X | Ownership unchanged by equipment mutation |
| OWN-ADV-003 | ownership vs custody/loan | A owns X; loan/custody gives X to B | A remains legal owner unless explicit ownership transfer commits |
| OWN-ADV-004 | name-based identity | two assets/owners share same display name but different UID | No cross-resolution by name; exact UID target only |
| OWN-ADV-005 | duplicate record UID | insert/create second record with same ownershipRecordUid | Reject/constraint failure; existing record unchanged |
| OWN-ADV-006 | wrong owner | transfer/close using owner UID that is not active source owner | Reject with no mutation |
| OWN-ADV-007 | wrong asset | transfer using valid owner but different asset UID | Reject; unrelated asset unchanged |
| OWN-ADV-008 | cross-campaign leakage | same owner/asset UID strings in campaigns A/B | Query/mutation in A cannot read/change B |
| OWN-ADV-009 | cross-entity leakage | player/NPC/org identities collide by label or partial key | Exact entity UID/scope required; no leakage |
| OWN-ADV-010 | missing/deleted asset | ownership references missing/deleted/retired asset | Runtime must follow declared integrity policy; no silent reassignment or history deletion |
| OWN-ADV-011 | history corruption | transfer A -> B | Prior A record remains historical; new B record is successor, not destructive owner rewrite |
| OWN-ADV-012 | validFrom/validUntil inversion | create/close with until < from | Reject atomically |
| OWN-ADV-013 | temporal overlap | create overlapping active rights beyond allowed share | Reject/serialize; history remains valid |
| OWN-ADV-014 | double active full ownership | active FULL A; create FULL B without closing A | Impossible at authoritative boundary |
| OWN-ADV-015 | invalid shares | numerator/denominator malformed or unsupported | Reject before commit and at DB boundary where applicable |
| OWN-ADV-016 | sum >100% | active A=70%, B=30%; add/transfer creating extra >0% without source conservation | Reject; aggregate <=100% |
| OWN-ADV-017 | negative/zero shares | 0%, negative numerator, denominator <=0 | Reject |
| OWN-ADV-018 | precision | repeated exact partial transfers and recombination | Exact conservation; no floating drift |
| OWN-ADV-019 | overflow | near Long/int numeric limits in numerator/denominator/arithmetic | Reject safely; no wraparound producing valid-looking ownership |
| OWN-ADV-020 | NaN/Infinity | if any floating/parser path exists, inject NaN/+Inf/-Inf | Reject; never persist non-finite share |
| OWN-ADV-021 | full transfer atomicity | A=100% X -> B with induced failure between close/create | Entire operation commits or rolls back; never zero-owner/dual-owner partial state caused by failure |
| OWN-ADV-022 | partial transfer atomicity | A=100%; transfer 40% to B with induced failure | Either A=60/B=40 committed together or original A=100 remains |
| OWN-ADV-023 | double-close | close same active record twice | Second close rejected/idempotent per contract; no corrupted temporal bounds |
| OWN-ADV-024 | stale transfer | read A active; transfer A->B; then use stale A state to transfer A->C | Second stale transfer cannot commit |
| OWN-ADV-025 | provenance loss | transfer/close/create successor | Required provenance/source linkage preserved on all committed records |
| OWN-ADV-026 | legacy auto-synthesis | migrate legacy inventory/equipment/property summary | Must not invent legal ownership without explicit supported evidence |
| OWN-ADV-027 | inventory transfer changing ownership | possession transfer A->B | Ownership unchanged unless explicit same transaction ownership mutation is requested/authorized |
| OWN-ADV-028 | equip/unequip changing ownership | equip/unequip owned or borrowed item | Ownership unchanged |
| OWN-ADV-029 | theft changing legal ownership | theft changes possession A->B | Legal ownership remains A unless separate legal ownership event commits |
| OWN-ADV-030 | loan changing legal ownership | loan A->B possession/custody | Legal ownership remains A |
| OWN-ADV-031 | reopen | close DB/app and reopen after ownership history/transfers | Current and historical ownership identical to pre-close committed state |
| OWN-ADV-032 | deleted presentation/cache | delete/rebuild non-authoritative projection if present | Authoritative ownership/history survives |

## 3. Concurrency / TOCTOU — automatic release blockers

Sequential tests are insufficient for this section. Each gate must execute competing operations against the same authoritative database boundary using separate concurrent callers/connections/transactions where runtime architecture permits. A Kotlin-only precheck performed before the authoritative write is not sufficient protection if the DB write can race after both callers pass the precheck.

### OWN-RACE-01 — competing full transfers

Initial state:

```text
A owns 100% X
T1: A -> B 100%
T2: A -> C 100%
```

Required: at most one transfer commits. Final current ownership is exactly one valid outcome, B or C, never both; loser observes conflict/stale state/constraint failure and cannot leave partial history.

Automatic FAIL if both callers can pass precheck and commit contradictory active FULL successors.

### OWN-RACE-02 — concurrent partial transfers

Initial state example:

```text
A owns 100% X
T1 transfers 60% A -> B
T2 transfers 60% A -> C
```

Required: serialization/conflict prevents aggregate committed share >100%. It is insufficient that each caller independently saw A=100% before writing.

Automatic FAIL if committed active shares can exceed 1/1.

### OWN-RACE-03 — stale current-owner read

Sequence with overlap:

```text
T1 reads A current owner
T2 transfers A -> B and commits
T1 attempts A -> C using stale read
```

Required: T1 commit is rejected by authoritative write boundary/version/CAS/constraint/transaction serialization. A former owner cannot transfer after ownership has changed.

### OWN-RACE-04 — close/end vs transfer

Initial A active. Race:

```text
T1 closes/ends A ownership
T2 transfers A -> B
```

Required: one coherent serial outcome only. No successor may be created from a source right that was concurrently ended unless transaction ordering legally observes it active. No double-close or temporal inversion.

### OWN-RACE-05 — duplicate active record / temporal-overlap race

Two callers concurrently create active records that individually appear legal against the same prior snapshot but together violate exclusivity/share/temporal constraints.

Required: authoritative boundary rejects one or otherwise serializes to a legal aggregate. Kotlin prechecks alone are a blocker if DB state admits both commits.

### Concurrency evidence required

For each race gate capture:

- exact initial rows,
- caller/connection/transaction arrangement,
- synchronization point/barrier proving overlap,
- results/exceptions from both operations,
- final authoritative rows,
- `integrity_check` and `foreign_key_check`,
- whether protection is DB constraint, transactional conditional update/CAS, locking/serialization, or only application precheck.

## 4. Migration / scale matrix

| ID | Scenario | Required result |
|---|---|---|
| OWN-MIG-001 | migrate campaign with >1000 ownership-relevant/legacy records | No truncation, deterministic completion, all authoritative preexisting data preserved |
| OWN-MIG-002 | history after multiple transfers/partial transfers | Historical chain remains queryable and current projection correct |
| OWN-MIG-003 | close/reopen | Durable equality after reopen |
| OWN-MIG-004 | backup/restore | Ownership records, history, provenance, active projection restored exactly |
| OWN-MIG-005 | campaign switch A -> B -> A | No cross-campaign cache/state leakage; exact A state restored |
| OWN-MIG-006 | CurrentSchema latest routing | New/opened DB routes through Phase-12 latest schema/migration exactly once as designed |
| OWN-MIG-007 | migration idempotency | Re-running schema initialization/migration path does not duplicate records or corrupt history |
| OWN-MIG-008 | Phase 3–11 regression | Existing Player State, stats/resources, modifiers, Talent/Potential, Skill, Technique, Innate, Inventory, Equipment tests remain green |
| OWN-MIG-009 | SQLite integrity | `PRAGMA integrity_check` returns `ok` |
| OWN-MIG-010 | foreign keys | `PRAGMA foreign_key_check` returns no violations |
| OWN-MIG-011 | no authoritative truncation | No `LIMIT`, bounded reader, summary, cache or presentation path is used as migration source for complete authoritative ownership data |
| OWN-MIG-012 | unsupported legacy evidence | Ambiguous inventory/equipment/property totals remain unsynthesized rather than becoming invented OwnershipRecords |

## 5. Authoritative write-boundary inspection

Final validation must identify every Phase-12 writer and trace it to the actual SQLite mutation boundary. For each writer verify:

1. campaign scope is part of lookup and mutation predicates,
2. owner/asset identity uses stable UID, not name,
3. stale source state is detected at write time,
4. close + successor creation is one transaction,
5. partial-share arithmetic is exact and overflow-safe,
6. exclusivity/share/temporal invariants survive concurrent callers,
7. provenance is persisted in the same committed operation,
8. no inventory/equipment mutation has an implicit OwnershipRecord side effect,
9. no generic/direct SQL bypass exposed to AI/UI can violate the invariants.

Potential blocker pattern:

```text
read current ownership
-> Kotlin validate
-> later unconditional UPDATE/INSERT
```

If competing callers can both validate the same stale snapshot and both write, Phase 12 fails even when all sequential unit tests pass.

## 6. Final validation protocol

Final PASS/FAIL is deferred until `FINAL WORK-20260810-051 RESULT COMMIT` appears in the repository.

Then CHAT-5 must:

1. refresh master,
2. identify and validate exactly the reported Phase-12 runtime SHA,
3. verify exact CI head SHA and result,
4. inspect the runtime/schema/migration/write boundary,
5. execute this full matrix against the actual runtime,
6. execute OWN-RACE-01..05 as real overlapping concurrency tests,
7. verify migration/scale/regression/integrity gates,
8. record minimal reproducers for every failure,
9. issue exactly `PHASE 12 ADVERSARIAL VALIDATION: PASS` or `PHASE 12 ADVERSARIAL VALIDATION: FAIL`.

A FAIL report must identify the violated invariant, minimal reproducer, concrete runtime/schema path, and minimal required correction scope. CHAT-5 must not implement that correction.

## 7. Current readiness

At matrix preparation time master does not yet contain a `WORK-20260810-051` result commit, so executing final Phase-12 validation would be premature. `WORK-20260810-052` and `WORK-20260810-053` reports were also not yet available at their expected audit paths.

No Phase-12 PASS/FAIL is issued by this artifact.

# PHASE 12 ADVERSARIAL MATRIX READY
