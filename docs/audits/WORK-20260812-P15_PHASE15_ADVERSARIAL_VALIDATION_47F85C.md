# CHAT-5 — Phase 15 DevelopmentProject Final Adversarial Validation

Status: FINAL ADVERSARIAL VALIDATION — FAIL

Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `47f85c1689fb78cfd5c7edd9d82f897485357dab`
Exact CI: GitHub Actions `#311`, run ID `31572017265`, head SHA `47f85c1689fb78cfd5c7edd9d82f897485357dab`, `SUCCESS`
Role: READ-ONLY QUALITY / ADVERSARIAL AUDITOR

# PHASE 15 ADVERSARIAL VALIDATION: FAIL

The candidate passes the mandatory multi-connection race suite and most identity/reference/history/domain-separation gates, but one release-blocking temporal-causality defect remains at the authoritative SQLite boundary: a `ProjectMilestoneAchievement` may cite a `sourceWorkRecordUid` whose `effective_order` is later than the achievement's `achieved_order`.

The Phase-15 schema provides an FK from `project_milestone_achievements.source_work_record_uid` to `project_work_records`, but `trg_p15_achievement_insert` validates only milestone identity and `achieved_order >= project.created_order`. It does not enforce `sourceWorkRecord.effective_order <= achieved_order`. Therefore a future work fact can be used as evidence for an earlier milestone achievement.

This is not a missing Phase-16 feature; it is Phase-15 history integrity.

---

## Candidate / CI / freshness

Fresh repository history was checked. The requested SHA is the newest Phase-15 runtime candidate; no later Phase-15 runtime commit was present at validation start.

Exact GitHub Actions run `31572017265` / run number `311` is `SUCCESS` with the exact requested head SHA. Green CI is not treated as proof of the adversarial temporal invariant.

---

# Mandatory P15 race gates

The exact candidate's `DevelopmentProjectConcurrencyTest` opens two separate SQLite connections, constructs independent `DevelopmentProjectStore` instances, synchronizes competing calls with `CountDownLatch`, and validates final persisted state.

## P15-RACE-01 — exact/competing project creation: PASS

Two concurrent exact creations of the same stable project UID both return logical success and converge to:

```text
development_projects: 1 canonical project
project_status_history: 1 canonical initial status
```

Conflicting immutable payload reuse is rejected by store replay equality plus SQLite primary identity.

## P15-RACE-02 — competing progress/cap: PASS

Initial cap 100; concurrent +60 / +60.

Final persisted state:

```text
committed work rows = 1
progress = 60
one caller success / one caller failure
```

The cap/overflow check is in SQLite `trg_p15_work_insert`, not only a Kotlin precheck.

## P15-RACE-03 — progress vs terminal transition: PASS

Concurrent work at order 6 vs cancellation at order 5 produces one coherent outcome only:

```text
(work=1, cancelled=0)
OR
(work=0, cancelled=1)
```

The final DB cannot contain both illegal outcomes.

## P15-RACE-04 — exact stable-UID work replay: PASS

Two concurrent identical work submissions converge to:

```text
2 logical successes
1 canonical work row
progress applied once
```

## P15-RACE-05 — conflicting stable-UID work replay: PASS

Same work UID with different progress payload:

```text
1 success
1 rejection
1 canonical work fact
```

## P15-RACE-06 — competing lifecycle transitions: PASS

Concurrent `STABILIZATION` vs `CANCELLED` at the same effective order yields exactly one committed status event at that order.

---

# Release blocker — milestone evidence temporal inversion

## Violated invariant

Canonical history evidence cannot point backward in time from a future work fact. If `ProjectMilestoneAchievement.sourceWorkRecordUid` is present, that work record must exist in the same campaign/project and must have occurred no later than `achievedOrder`.

This follows the Phase-15 temporal/history contract: milestone achievements are append-preserved evidence facts and cannot predate the work used as their source.

## Minimal reproducer

1. Create project `P` at order `1`.
2. Advance legally through `REQUIREMENTS` order `2`, `PROTOTYPE` order `3`, `ACTIVE_WORK` order `4`.
3. Define required milestone `M`.
4. Record work `W` with `effective_order = 10`.
5. Insert `ProjectMilestoneAchievement`:

```text
achievementUid = A
projectUid = P
milestoneUid = M
achievedOrder = 5
sourceWorkRecordUid = W
```

## Expected

Reject at the authoritative SQLite write boundary because source work occurs at order `10` while claimed achievement occurs at order `5`.

## Actual

The insert is structurally accepted by the current schema/trigger contract because:

- FK proves `W` exists;
- milestone FK/reference is valid;
- `5 >= project.created_order (1)`;
- no predicate compares `W.effective_order` with `NEW.achieved_order`.

The resulting canonical history can therefore state that milestone `M` was achieved at order 5 using work that did not occur until order 10.

## Exact runtime/schema path

`DevelopmentProjectStore.achieveMilestone()`
-> `INSERT INTO project_milestone_achievements`
-> `Phase15Migration.installPhase15Triggers()`
-> `trg_p15_achievement_insert`.

The missing predicate is equivalent to:

```sql
NEW.source_work_record_uid IS NULL
OR EXISTS (
  SELECT 1
  FROM project_work_records w
  WHERE w.campaign_id = NEW.campaign_id
    AND w.project_uid = NEW.project_uid
    AND w.work_record_uid = NEW.source_work_record_uid
    AND w.effective_order <= NEW.achieved_order
)
```

The FK alone is insufficient because it validates identity, not temporal causality.

## Kotlin-side vs SQLite-side

The invariant is protected by neither Kotlin nor SQLite in the exact candidate. The existing SQLite trigger protects project creation-order and milestone identity but not source-work ordering.

## Minimal correction scope

Phase 15 only:

- extend `trg_p15_achievement_insert` (and hardening copy if duplicated) with same-campaign/project source-work + `effective_order <= achieved_order` validation;
- add direct-SQL and typed-store regressions for future-work evidence rejection;
- add a positive equal-order/earlier-work case;
- preserve all existing Phase-15 and Phase 3–14 behavior.

No Phase-16 command/orchestration work is required.

---

# Other adversarial results

## Requirements / milestones

PASS except for the temporal source-work blocker above.

Required requirements that exist must be satisfied before PROTOTYPE. Required milestones that exist must be achieved before READY_TO_COMPLETE. Completion with a declared durable output requires a committed valid outcome. The gate is DB-side in status triggers.

The current contract does not require every project to define at least one requirement/milestone; the architecture phrases these as typed project content and requires all *required* entries to be satisfied/achieved. I did not treat empty optional sets as a blocker.

## Finance evidence

PASS.

`project_work_records.financial_transaction_uid` is an FK scoped by `(campaign_id, financial_transaction_uid)`, and `trg_p15_work_insert` additionally requires financial evidence effective order not later than the work record. A nonexistent or wrong-campaign financial transaction is rejected. Project work references finance evidence and does not create ledger movement.

## Outcome integrity

PASS for supported durable outcome kinds.

`trg_p15_outcome_insert` requires the project to be `READY_TO_COMPLETE`, requires output kind to match intended kind, and resolves Technique/Skill/ItemInstance/Asset against their authoritative domains. Caller-declared ghost outputs are rejected. Completion with `intended_output_kind_uid` requires an outcome committed by completion order.

## Dependencies

PASS for tested graph integrity.

Self-dependency is blocked by schema CHECK. Direct and longer cycles are rejected by the recursive SQLite trigger. Dependencies are campaign-scoped FKs. The cycle check is authoritative SQLite behavior.

## Project/work identity and replay

PASS.

Exact stable UID replay returns canonical data; conflicting immutable payload reuse is rejected. Concurrent exact work replay applies progress once. Duplicate project identity cannot create two canonical project rows.

## Progress arithmetic

PASS.

`progress_delta_units` is SQLite INTEGER/Long authority, nonnegative, and cap/overflow checks are enforced in `trg_p15_work_insert`. No Float/Double canonical progress authority was found. The 1001-work persistence test proves unbounded authoritative history use for the tested scale.

## History mutation

PASS for protected Phase-15 tables.

Project/status/work and other canonical history tables have UPDATE and/or DELETE blockers appropriate to their immutable semantics. Status/work history cannot be silently overwritten by replay.

## StatePatch

PASS.

Phase-15 canonical tables are excluded from generic StatePatch authority via `SourceOfTruthRegistry`; typed project store remains the canonical writer.

## Legacy synthesis

PASS.

A legacy project-like table does not synthesize DevelopmentProject, work, requirements, milestones, provenance, or completion history during schema ensure.

## Campaign isolation

PASS for tested canonical identities and references.

Project rows and most dependent records use campaign-scoped composite FKs. Same stable UID in campaigns C and D remains separate.

## Domain separation

PASS.

The implementation maintains:

```text
DevelopmentProject
!= Financial Ledger
!= Inventory
!= Equipment
!= OwnershipRecord
!= Asset/Liability
!= Skill/Technique authority
```

Project work only references FinancialTransaction evidence. Outcomes link to preexisting authoritative domain objects. Project creation/completion does not fabricate money, inventory, ownership, assets, skills, or techniques.

## Reopen / scale / routing / migration

PASS for covered fixtures.

The persistence suite retains 1001 work records, exact derived progress and stable project state after reopen. CurrentSchema routes through Phase-15 hardening. Migration is additive and legacy-zero-synthesis behavior is preserved.

## SQLite integrity

PASS in the mandatory concurrency/persistence fixtures:

```text
PRAGMA integrity_check = ok
PRAGMA foreign_key_check = zero Phase-15 violations
```

The reported temporal blocker does not necessarily create an FK violation because the source-work row exists; it is a semantic/temporal integrity hole, which is why `foreign_key_check` alone cannot detect it.

## Phase-16 boundary

PASS.

The Phase-15 delta introduces DevelopmentProject domain files/migration/tests only. No new Phase-16 `PlayerCommand`, `PlayerChangeSet`, `PlayerDomainEngine`, `WorldRuleProvider` or `ProgressionEngine` substitute was introduced by this candidate.

---

# Final result

```text
Validated runtime SHA: 47f85c1689fb78cfd5c7edd9d82f897485357dab
Exact CI: GitHub Actions #311 / run 31572017265 / SUCCESS
Verdict: PHASE 15 ADVERSARIAL VALIDATION: FAIL

P15-RACE-01: PASS
P15-RACE-02: PASS
P15-RACE-03: PASS
P15-RACE-04: PASS
P15-RACE-05: PASS
P15-RACE-06: PASS

Requirements/milestones: FAIL — future work can evidence earlier milestone achievement
Finance evidence: PASS
Outcome integrity: PASS
Dependencies: PASS
History/replay: FAIL — temporal causality hole in milestone evidence; replay immutability otherwise PASS
StatePatch: PASS
Scale/reopen: PASS
Integrity/FK: PASS (semantic temporal blocker is not an FK violation)
Regression: PASS for inspected/CI-covered Phase 3–14 domains
```

Phase 15 is not marked COMPLETE/ACCEPTED. Phase 16 was not started.
