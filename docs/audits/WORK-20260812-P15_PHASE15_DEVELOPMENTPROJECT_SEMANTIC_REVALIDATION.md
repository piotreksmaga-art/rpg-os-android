# WORK-20260812-P15 — Phase 15 DevelopmentProject Semantic Revalidation

Status: FINAL SEMANTIC REVALIDATION — FAIL

Role: CHAT-2 / Phase 15 Semantic Revalidation Auditor
Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `47f85c1689fb78cfd5c7edd9d82f897485357dab`
Exact CI: GitHub Actions `#311`, run ID `31572017265`, head SHA `47f85c1689fb78cfd5c7edd9d82f897485357dab`, `SUCCESS`
Allowed write scope: this report only.

# PHASE 15 SEMANTIC REVALIDATION: FAIL

The exact candidate implements a substantial generic DevelopmentProject domain and passes most semantic gates: stable campaign-scoped project identity, generic project type definitions, append-only status/work/requirement/milestone/dependency/outcome history, derived progress from exact work deltas, finance-as-evidence, pre-existing durable output requirements, StatePatch isolation, zero legacy synthesis, scale/reopen, campaign isolation and Phase 3–14 authority separation. One release blocker remains in the canonical outcome surface: the domain model declares `PROJECT_OUTPUT_TRUTH` and the accepted Phase-15 architecture explicitly requires world-research projects to be able to link to committed truth/knowledge authority, but the SQLite outcome write boundary rejects every output kind except TECHNIQUE, SKILL, ITEM_INSTANCE and ASSET. Therefore a valid world-research project with a real committed truth result cannot record its canonical outcome and cannot complete when `intendedOutputKindUid=PROJECT_OUTPUT_TRUTH`.

No runtime/schema/test correction was implemented. Phase 16 was not started.

---

## 1. Candidate freshness / exact CI — PASS

Fresh master immediately before report write resolved exactly to `47f85c1689fb78cfd5c7edd9d82f897485357dab`. No later Phase-15 runtime candidate existed.

Exact Actions run `31572017265`, run number `#311`, completed `SUCCESS` with exact head SHA `47f85c1689fb78cfd5c7edd9d82f897485357dab`.

Result: **PASS**.

## 2. Canonical source contract

Revalidation used current MASTER, Roadmap, Parallel Work Coordination, `WORK-20260810-064_PHASE15_DEVELOPMENT_PROJECT_ARCHITECTURE.md`, the exact candidate source/schema/tests and accepted Phase 3–14 authorities.

The architecture requires a universe-agnostic DevelopmentProject domain covering technique development, skill development, research, crafting, adaptation, infrastructure and world research. It explicitly states that durable outcomes belong to their own authoritative domain and gives world research -> knowledge/truth record reference as a valid outcome example.

## 3. Domain boundary — PASS

The candidate preserves:

```text
DevelopmentProject
!= Financial Ledger
!= Inventory
!= Equipment
!= OwnershipRecord
!= Asset/Liability
!= Skill/Technique mastery authority
```

Project work can reference a Phase-13 `financial_transaction_uid`, but project writes do not fabricate ledger transactions or balances. Durable item/asset/skill/technique outcomes must pre-exist in their canonical authority before `project_outcomes` accepts the link. No project operation mutates Inventory, Equipment, Ownership, Assets/Liabilities or Skill/Technique mastery as a side effect.

Result: **PASS**.

## 4. Stable project identity / generic types / party scope — PASS

`DevelopmentProject` uses stable `projectUid`, campaign scope, validated generic initiator/beneficiary `OwnershipOwnerRef`, stable `projectTypeUid`, target domain/kind/UID, intended output kind, created order, version and provenance. `ProjectTypeDefinition` is extensible and world-pack-aware. SQLite guards require active project type and active campaign-scoped party references.

Result: **PASS**.

## 5. Lifecycle / terminal behavior — PASS

Status is append-only in `project_status_history`; the project row does not store a mutable authoritative percentage or current-status field. SQLite transition guards enforce ordered lifecycle progression, requirements before PROTOTYPE, required milestones before READY_TO_COMPLETE, outcome existence before COMPLETED when an intended durable output is declared, and terminal-state non-reentry. Work is accepted only in PROTOTYPE / ACTIVE_WORK / STABILIZATION.

Result: **PASS**.

## 6. Progress exactness / failures / scale — PASS

Canonical progress is derived from `SUM(project_work_records.progress_delta_units)`. Work deltas use SQLite INTEGER / Kotlin Long semantics, cannot be negative, respect an optional positive cap and have explicit overflow protection at the write boundary. `FAILURE`, `NO_PROGRESS` and `INCIDENT` are preserved as work-history result kinds and can contribute zero progress without disappearing from history.

The persistence fixture writes 1001 work records, verifies count and derived progress, closes/reopens the database and obtains the same result without authoritative truncation.

Result: **PASS**.

## 7. Stable UID / exact replay — PASS for inspected canonical paths

Project type, project creation, status events, requirements, requirement satisfactions, milestone definitions/achievements, work records, dependencies and outcomes use stable UID keyed writes. Exact persisted payload replay is accepted and conflicting payload under the same stable UID is rejected. Creation/work tests explicitly verify canonical replay and semantic conflict rejection.

Result: **PASS**.

## 8. Requirements / milestones — PASS with a non-blocking future-hardening note

Required requirement definitions block PROTOTYPE until a corresponding satisfaction fact exists; required milestones block READY_TO_COMPLETE until an achievement exists. Definitions and history are append-preserved. External evidence fields are optional by model contract, so the current domain can represent manually/authoritatively attested satisfaction with provenance as well as linked evidence. This audit does not classify the absence of a universal Phase-16/WorldRuleProvider requirement evaluator as a Phase-15 failure, because that orchestration is explicitly later roadmap scope.

Result: **PASS** for Phase-15 scope.

## 9. Dependencies — PASS

Dependencies are campaign-scoped stable project references, self-dependency is rejected, referenced projects must exist and the SQLite recursive reachability guard rejects cycles. Dependency history is immutable/append-preserved.

Result: **PASS**.

## 10. Finance evidence / outcome authority — PARTIAL

Finance linkage is correct: work may reference an existing same-campaign `financial_ledger_transactions` fact at or before the work order, and the project does not mutate finance.

Outcome authority is correct for supported output kinds: TECHNIQUE, SKILL, ITEM_INSTANCE and ASSET require the referenced durable result to already exist in the appropriate accepted authority before the project records the link.

However the generic outcome contract is incomplete in one release-blocking case described below.

---

# RELEASE BLOCKER P15-SEM-01 — WORLD-RESEARCH / TRUTH OUTCOME IS DECLARED BUT UNCOMMITTABLE

## Violated invariant

DevelopmentProject must remain generic and universe-agnostic and must support world research without replacing the final Truth/Knowledge authority. The Phase-15 architecture explicitly allows world research to produce a stable link to a pre-existing knowledge/truth record. The runtime model itself declares:

```text
PROJECT_OUTPUT_TRUTH = "RPGOS-PROJECT-OUTPUT:TRUTH"
```

A declared supported canonical output kind must be linkable to the actual durable authority rather than rejected unconditionally by the project write boundary.

## Exact runtime path

`DevelopmentProjectStore.commitOutcome()` inserts into `project_outcomes`.

SQLite trigger `trg_p15_outcome_insert` in `Phase15Migration.kt` accepts only:

```text
PROJECT_OUTPUT_TECHNIQUE
PROJECT_OUTPUT_SKILL
PROJECT_OUTPUT_ITEM_INSTANCE
PROJECT_OUTPUT_ASSET
```

and contains the unconditional guard:

```text
NEW.output_kind_uid NOT IN (...four kinds...)
=> RAISE(ABORT,'project outcome unresolved, wrong kind, or project not ready')
```

`PROJECT_OUTPUT_TRUTH` is therefore rejected even if `outputUid` points to a real committed Phase-2 truth record.

Because `trg_p15_status_insert` requires an existing project outcome before `COMPLETED` whenever `intended_output_kind_uid` is non-null, a project whose intended output is `PROJECT_OUTPUT_TRUTH` becomes permanently unable to complete.

## Minimal reproducer

1. Bootstrap campaign C and register a valid project initiator.
2. Commit a real canonical truth/knowledge fact in its existing authority and retain its stable UID, e.g. `TRUTH-1`.
3. Create a RESEARCH/world-research DevelopmentProject with:
   - `intendedOutputKindUid = PROJECT_OUTPUT_TRUTH`.
4. Advance legally through REQUIREMENTS -> PROTOTYPE -> ACTIVE_WORK -> STABILIZATION -> READY_TO_COMPLETE, satisfying required gates if any.
5. Call:

```text
commitOutcome(
  ProjectOutcome(
    campaignId = "C",
    outcomeUid = "OUT-TRUTH",
    projectUid = "P",
    outputKindUid = PROJECT_OUTPUT_TRUTH,
    outputUid = "TRUTH-1",
    ...
  )
)
```

## Expected

The project records a stable evidence/link to the already-existing canonical truth fact without creating or mutating Truth authority. The project can then transition to COMPLETED.

## Actual

`trg_p15_outcome_insert` rejects `PROJECT_OUTPUT_TRUTH` solely because its kind is not one of the four hardcoded accepted kinds. No outcome can be recorded, and subsequent COMPLETED is also rejected because the declared intended output has no accepted outcome row.

## Minimal correction scope

Phase 15 only:

- either add authoritative `PROJECT_OUTPUT_TRUTH` resolution against the existing Phase-2 truth authority at the SQLite/write boundary;
- or replace the hardcoded output-kind whitelist with a typed/registered output resolver contract that includes the already-declared Truth output while preserving strict target validation.

Do not create Truth facts from DevelopmentProject. Do not implement PlayerCommand, PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider or Phase 16+ orchestration.

Result: **FAIL / RELEASE BLOCKER**.

---

## 11. Legacy / StatePatch — PASS

Migration is additive and explicitly records zero legacy synthesis. Persistence tests show ambiguous `legacy_projects(title, progress)` does not create DevelopmentProject or work history. Canonical Phase-15 tables are blocked from generic StatePatch through `SourceOfTruthRegistry`.

Result: **PASS**.

## 12. Phase 16+ boundary — PASS

The candidate introduces typed Phase-15 model/store/migration/guards/tests only. It does not implement PlayerCommand, PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider or ProgressionEngine as Phase-15 substitutes.

Result: **PASS**.

## 13. Phase 3–14 regression — PASS

The inspected implementation consumes accepted party, finance, item, skill/technique and asset authorities by reference and does not replace them. Inventory, Equipment, Ownership, Financial Ledger and Assets/Liabilities remain independent authorities. StatePatch protections for prior domains are preserved. Exact CI #311 reruns the project JVM suite/build successfully.

Result: **PASS**.

---

# FINAL VERDICT

# PHASE 15 SEMANTIC REVALIDATION: FAIL

for exactly:

`47f85c1689fb78cfd5c7edd9d82f897485357dab`

The candidate is semantically strong across lifecycle, derived progress, replay, dependency, finance-evidence, persistence and domain-separation gates, but the hardcoded outcome whitelist contradicts the declared/architected generic world-research -> Truth outcome path. Phase 15 must remain blocked until that Phase-15-only outcome-resolution defect is corrected and a new runtime SHA is revalidated.

Phase 15 is not marked COMPLETE/ACCEPTED. Phase 16 was not started.
