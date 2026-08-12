# CHAT-3 — Phase 15 DevelopmentProject Final Migration / Integrity Revalidation

Status: FINAL MIGRATION / PERSISTENCE / INTEGRITY REVALIDATION — FAIL

Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `47f85c1689fb78cfd5c7edd9d82f897485357dab`
Exact CI: GitHub Actions `#311`, run ID `31572017265`, head SHA `47f85c1689fb78cfd5c7edd9d82f897485357dab`, `SUCCESS`
Role: CHAT-3 / PHASE 15 MIGRATION / INTEGRITY REVALIDATION AUDITOR
Allowed write scope: this report only.

# PHASE 15 INTEGRITY REVALIDATION: FAIL

The exact candidate is migration-safe across the covered V14 -> V15/bootstrap/reopen/restore/campaign-switch paths, passes the required six real multi-connection race fixtures, protects Phase-15 tables from generic StatePatch, preserves >1000 work-history records across reopen, and produces clean Phase-15 FK/integrity results in the test fixtures.

However, one release-blocking authoritative-history defect remains in `project_milestone_achievements`: a milestone achievement can cite a `source_work_record_uid` that belongs to another project or whose `effective_order` is later than the milestone achievement's `achieved_order`. The current FK verifies only that the work row exists in the same campaign. `trg_p15_achievement_insert` does not require the source work to belong to the same project and does not enforce temporal causality.

This is a Phase-15 persistence/integrity invariant, not a missing Phase-16 orchestration feature. No runtime/schema/test correction was implemented by this audit. Phase 16 was not started.

---

## 1. Candidate freshness / report-only master movement — PASS

At validation start, fresh master resolved to the requested runtime candidate:

`47f85c1689fb78cfd5c7edd9d82f897485357dab`

During the audit master advanced only through report commits for the same runtime:

- `ad73b275e830d0ec419a8f54237b3c5d18a5a995` — Phase-15 semantic revalidation report only;
- `0286be6ccb064cf376c88c282ca48a2cf27ec487` — Phase-15 adversarial validation report only.

No newer Phase-15 runtime commit appeared. Validation therefore remains pinned exactly to `47f85c1689fb78cfd5c7edd9d82f897485357dab`.

Result: **PASS**.

## 2. Exact CI — PASS

GitHub Actions run `31572017265`, run number `311`, is completed with conclusion `SUCCESS` and exact head SHA `47f85c1689fb78cfd5c7edd9d82f897485357dab`.

The workflow completed project validation, JVM unit tests and signed ALPHA APK build. Green CI is supporting evidence only and is not used as a substitute for invariant inspection.

Result: **PASS**.

---

# SCHEMA / MIGRATION

## 3. `RPGOS-15.0-DEVELOPMENT-PROJECTS` — PASS

`PHASE15_MIGRATION_ID` is exactly:

`RPGOS-15.0-DEVELOPMENT-PROJECTS`

`ensureV15()` chains from `ensureV14Hardening()` and additively creates the generic Phase-15 authority:

- `project_type_definitions`;
- `development_projects`;
- `project_status_history`;
- `project_requirements`;
- `project_requirement_satisfactions`;
- `project_milestone_definitions`;
- `project_milestone_achievements`;
- `project_work_records`;
- `project_dependencies`;
- `project_outcomes`.

Core project types are seeded with `INSERT OR IGNORE`. The migration marker is likewise inserted idempotently. No destructive Phase 3–14 migration is performed.

The migration notes explicitly preserve zero legacy synthesis, and the persistence/routing fixtures demonstrate that ambiguous `legacy_projects(title, progress)` data remains legacy evidence instead of becoming fabricated canonical project/history rows.

Result: **PASS**.

## 4. `RPGOS-15.1-DEVELOPMENT-PROJECT-GUARDS` — PARTIAL / blocker elsewhere

`PHASE15_HARDENING_MIGRATION_ID` is exactly:

`RPGOS-15.1-DEVELOPMENT-PROJECT-GUARDS`

`ensureV15Hardening()` chains through V15 guards and installs target-reference, stable UID and hardened lifecycle/temporal guards. It adds authoritative target resolution for supported project targets and strengthens status ordering against later work/achievement/outcome facts.

The hardening does not repair the milestone source-work causality defect described in the release blocker section below.

## 5. CurrentSchema routing — PASS

`CurrentSchema.ensure()` routes directly through `MigrationManager().ensureV15Hardening(...)`, preserving the full earlier migration chain beneath it.

Result: **PASS**.

## 6. Clean bootstrap — PASS

`Phase15ProductionRoutingTest.bootstrapRoutesBundledCampaignThroughV15()` verifies production bootstrap reaches V15, all ten canonical Phase-15 tables exist, Phase-15 triggers are installed, and Phase-15 FK/integrity checks are clean.

Result: **PASS**.

## 7. V14 -> V15 — PASS

Production routing tests explicitly construct a V14 database with `ensureV14Hardening()` and then route it through normal application/CurrentSchema paths into V15.

The migration is additive and preserves accepted Phase-14 authority.

Result: **PASS**.

## 8. Repeated ensure / migration idempotency — PASS

Repeated `CurrentSchema.ensure()` leaves exactly one `RPGOS-15.0-DEVELOPMENT-PROJECTS` migration marker and does not synthesize project authority.

Both V15 schema creation and hardening trigger installation are rerunnable.

Result: **PASS**.

## 9. Restore — PASS

`restoreRoutesV14ThroughV15WithoutLegacyProjectSynthesis()` restores a V14 backup under the V15 runtime, preserves ambiguous legacy project-like evidence, creates zero canonical DevelopmentProject rows from that ambiguity, and reaches V15 successfully.

Result: **PASS**.

## 10. Campaign switch — PASS

Production routing validates campaign switching through V15. Persistence tests additionally create the same project UID `LONG` independently in campaigns C and D and verify one canonical project per campaign.

Result: **PASS**.

## 11. Phase 3–14 chain preservation — PASS

The V15 routing remains layered above accepted V14 and does not replace Inventory, Equipment, Ownership, Financial Ledger or Asset/Liability authorities. Exact CI #311 reruns the complete JVM suite on the merged runtime.

No Phase-15 migration code destructively modifies Phase 3–14 canonical tables.

Result: **PASS**.

---

# REFERENCE INTEGRITY

## 12. Campaign / project party refs — PASS

`development_projects` uses campaign-scoped FKs into `ownership_party_registry` for initiator and optional beneficiary. SQLite insert guards additionally require these parties to be ACTIVE. Party retirement is blocked while an active/nonterminal project references the party.

`project_work_records` similarly requires a campaign-scoped actor FK and active actor at write time.

Result: **PASS**.

## 13. ProjectTypeDefinition — PASS

Project creation requires a registered ACTIVE `project_type_definitions` row. Definition identity is stable and meaning is immutable by SQLite UPDATE guard.

Result: **PASS**.

## 14. Project target refs — PASS for supported target resolver

V15.1 hardening validates explicit project targets for supported domains:

- TECHNIQUE;
- SKILL;
- ITEM_INSTANCE;
- ASSET;
- PROJECT.

Item/Asset/Project resolution is campaign-scoped; Asset targets must be active. Unsupported explicit target domains are rejected.

Result: **PASS** for the implemented supported resolver.

## 15. FinancialTransaction evidence — PASS

`project_work_records.financial_transaction_uid` is an FK to same-campaign `financial_ledger_transactions`. `trg_p15_work_insert` additionally requires that the referenced financial transaction's `effective_order` is not later than the work record.

The project does not create or mutate money as a side effect. Persistence tests verify a ghost transaction is rejected and that referencing a real funding transaction does not alter the account balance.

Result: **PASS**.

## 16. Dependency project refs / cycles — PASS

Both sides of a dependency are campaign-scoped project FKs. Self-dependency is blocked by schema CHECK. `trg_p15_dependency_insert` uses recursive reachability to reject cycles at SQLite write boundary.

The dependency-cycle regression demonstrates rejection after one valid edge remains canonical.

Result: **PASS**.

## 17. Durable outcome existence — PASS for the four currently supported output kinds

`trg_p15_outcome_insert` requires READY_TO_COMPLETE state, output kind matching the project's intended output, and an existing canonical result for TECHNIQUE, SKILL, ITEM_INSTANCE or ASSET.

A ghost ItemInstance outcome is rejected; an existing canonical ItemInstance can be linked and then the project can complete.

Separate semantic audit has identified that the declared `PROJECT_OUTPUT_TRUTH` path is not supported by this whitelist. That is a separate semantic release blocker; this integrity report does not reinterpret it as an FK failure.

---

# SQLITE WRITE BOUNDARY / HISTORY

## 18. Lifecycle / terminal state — PASS

Lifecycle legality is enforced in SQLite `trg_p15_status_insert`, not only Kotlin. It guards:

- initial IDEA requirement;
- monotonic status order;
- legal transition graph;
- required requirements before PROTOTYPE;
- required milestones before READY_TO_COMPLETE;
- required outcome before COMPLETED where an intended output is declared;
- valid SUPERSEDED successor;
- no backdated status before later committed work/achievement/outcome facts.

Terminal state cannot silently reopen through the permitted transition set.

Result: **PASS**.

## 19. Progress cap / overflow / underflow — PASS

`progress_delta_units` is SQLite INTEGER/Kotlin Long and is constrained nonnegative. `trg_p15_work_insert` prevents additions beyond `progress_cap_units` and protects Long overflow before inserting the work record.

P15-RACE-02 confirms two concurrent +60 writes against cap 100 cannot produce 120.

Result: **PASS**.

## 20. Requirement / milestone completion gates — PASS

Required requirements gate PROTOTYPE. Required milestones gate READY_TO_COMPLETE. These checks are performed by the authoritative status trigger.

Result: **PASS**, subject to the source-work integrity blocker for milestone evidence itself.

## 21. Append-only / immutable history — PASS for mutation protection

SQLite guards reject UPDATE/DELETE on canonical project identity/history as appropriate, including project row, status history, work history, requirements, milestone definitions/achievements, dependencies and outcomes. Generic delete guards cover the Phase-15 history tables.

The blocker below is not an UPDATE/DELETE bypass; it is an invalid fact that is currently accepted at INSERT time.

## 22. StatePatch / SourceOfTruth — PASS

`SourceOfTruthRegistry.TYPED_ONLY_TABLES` includes all ten Phase-15 canonical tables. Generic StatePatch therefore cannot create projects, mutate progress/lifecycle, append history or bypass requirements/milestone/outcome gates.

Result: **PASS**.

---

# STABLE UID / IDEMPOTENCY

## 23. Exact replay equality — PASS for typed-store paths

`DevelopmentProjectStore` reconstructs persisted canonical records and compares complete data-class payloads for:

- project type;
- project creation plus initial status;
- status event;
- requirement;
- satisfaction;
- milestone definition;
- milestone achievement;
- work record;
- dependency;
- outcome.

Same UID + exact immutable payload is accepted as replay; changed payload is rejected as semantic conflict. The persistence suite explicitly checks project/type/work replay and conflict fields; multi-connection tests cover exact/conflicting work replay.

Result: **PASS** for the typed-store replay contract.

## 24. Process synchronization note — non-blocking for tested runtime, not sole DB protection

The store uses process-level striped synchronization for same-domain UID operations. SQLite PK/UNIQUE constraints still protect canonical uniqueness and DB triggers protect race-sensitive lifecycle/cap semantics.

The required supplied multi-connection race fixtures execute successfully. This audit does not treat the Kotlin lock alone as proof of DB invariants; the cap/status/dependency protections were separately verified in SQLite triggers.

---

# CONCURRENCY GATES

The exact candidate's `DevelopmentProjectConcurrencyTest` uses two independent `SQLiteDatabase` connections, two independent `DevelopmentProjectStore` instances, two executor workers and `CountDownLatch` synchronization. Tests inspect final persisted DB state.

## P15-RACE-01 — PASS

Competing exact project creation:

- 2 logical successes;
- 0 failures;
- 1 canonical project;
- 1 canonical initial status.

## P15-RACE-02 — PASS

Competing progress at cap:

- one +60 work commits;
- one is rejected;
- final progress = 60;
- final work count = 1.

## P15-RACE-03 — PASS

Progress vs CANCELLED transition yields exactly one coherent final branch:

- either work committed and cancel absent;
- or cancel committed and work absent.

## P15-RACE-04 — PASS

Concurrent exact stable-UID work replay:

- 2 logical successes;
- 0 failures;
- 1 canonical work row;
- progress applied once.

## P15-RACE-05 — PASS

Concurrent conflicting stable-UID work replay:

- 1 success;
- 1 rejection;
- 1 canonical work row;
- persisted immutable value is exactly one contender, never a merge.

## P15-RACE-06 — PASS

Competing lifecycle transitions at the same effective order yield exactly one status event at that order.

## Dependency-cycle write-boundary gate — PASS

Sequential cycle construction is rejected by authoritative SQLite recursive trigger. No separate simultaneous two-edge race fixture is present, but SQLite trigger enforcement—not a Kotlin-only graph precheck—protects the insert boundary.

---

# CROSS-DOMAIN ATOMICITY

## 25. Finance evidence is reference-only — PASS

Project work cannot create FinancialTransaction facts. It may only reference an existing same-campaign transaction valid by order. A failure to insert project work therefore leaves Finance unchanged.

## 26. Durable outcome is link-only — PASS for supported kinds

Project outcome insertion does not create Technique/Skill/Item/Asset state. The final domain object must pre-exist. Project COMPLETED is a later lifecycle fact requiring the link.

This is a recoverable two-step protocol: final domain object -> project outcome link -> COMPLETED. A failed outcome link leaves the project not completed rather than fabricating a project-owned output.

Result: **PASS** for the implemented supported output kinds.

---

# SCALE / REOPEN

## 27. >1000 history — PASS

The persistence fixture records 1001 `project_work_records` for one project.

Required/observed fixture state:

- historyCount = 1001;
- derived progress = 1001;
- no authoritative LIMIT/truncation in the aggregation;
- close/reopen;
- CurrentSchema.ensure again;
- historyCount remains 1001;
- derived progress remains 1001.

Result: **PASS**.

---

# DATABASE INTEGRITY

## 28. `PRAGMA integrity_check` — PASS

Phase-15 persistence, concurrency and routing fixtures execute `PRAGMA integrity_check` and require:

`ok`

Exact CI #311 passes these assertions.

Result: **PASS**.

## 29. `PRAGMA foreign_key_check` — PASS for authoritative Phase-15 tables

Persistence/routing fixtures run `PRAGMA foreign_key_check(table)` over each Phase-15 campaign/history table and require zero rows.

No Phase-15 FK violation is created by the tested migration/persistence paths.

The release blocker below is not an FK violation: the cited work row exists, but its project/time semantics are wrong. This is why FK clean status alone does not prove Phase-15 history integrity.

Result: **PASS** for represented Phase-15 FKs.

---

# RELEASE BLOCKER — P15-INT-01

## Milestone source-work reference lacks same-project and temporal-causality enforcement

### Violated invariant

A canonical `ProjectMilestoneAchievement` that names `sourceWorkRecordUid` must reference work belonging to the same project and that work must occur no later than the achievement.

Required relationship:

```text
sourceWork.campaign_id = achievement.campaign_id
sourceWork.project_uid = achievement.project_uid
sourceWork.work_record_uid = achievement.source_work_record_uid
sourceWork.effective_order <= achievement.achieved_order
```

The Phase-15 architecture requires authoritative references to resolve, milestone achievements to be append-preserved evidence, and temporal ordering to remain causally reconstructable. A milestone cannot be evidenced by another project's work or by a work fact from the future.

### Exact schema/runtime path

`DevelopmentProjectStore.achieveMilestone()`

-> INSERT into `project_milestone_achievements`

-> FK `(campaign_id, source_work_record_uid)` references `project_work_records(campaign_id, work_record_uid)`

-> `Phase15Migration.installPhase15Triggers()`

-> `trg_p15_achievement_insert`.

The FK establishes only same campaign + existing work UID. It does **not** establish same project.

`trg_p15_achievement_insert` currently checks:

- matching milestone belongs to the achievement project;
- `achieved_order >= development_projects.created_order`.

It does **not** check the source work's `project_uid` or `effective_order`.

### Minimal reproducer A — future work evidence

1. Create project P at order 1.
2. Legally advance P through REQUIREMENTS(2), PROTOTYPE(3), ACTIVE_WORK(4).
3. Define milestone M for P.
4. Record work W for P with `effective_order = 10`.
5. Call `achieveMilestone` with:

```text
achievementUid = A
projectUid = P
milestoneUid = M
achievedOrder = 5
sourceWorkRecordUid = W
```

### Expected

Deterministic reject at SQLite authoritative boundary because work W occurs at order 10 and cannot evidence an achievement at order 5.

### Actual

The row satisfies the current FK and trigger and is accepted: W exists in campaign C, M belongs to P, and 5 is after P.created_order=1. Canonical history therefore permits a milestone at order 5 whose evidence occurs at order 10.

### Minimal reproducer B — cross-project work evidence

1. Create projects P and Q in campaign C.
2. Define milestone M for P.
3. Record work WQ for Q.
4. Insert an achievement for P/M with `sourceWorkRecordUid = WQ` and an otherwise legal achieved order.

### Expected

Reject because WQ belongs to Q, not P.

### Actual

The composite FK is only `(campaign_id, source_work_record_uid)`, so WQ resolves. `trg_p15_achievement_insert` does not compare source work's project UID. The achievement can therefore cite another project's work as canonical evidence.

### Why CI does not catch it

Existing lifecycle test uses a correct same-project earlier work source (`W3`) and therefore proves the positive case only. The race suite does not attack milestone source evidence identity/time.

`PRAGMA foreign_key_check` remains clean because the referenced work row genuinely exists; this is a semantic referential/temporal integrity hole above plain FK existence.

### Minimal correction scope

Phase 15 only. No Phase 16 changes.

Strengthen `trg_p15_achievement_insert` (and any duplicated/hardening installation path if applicable) so that when `source_work_record_uid IS NOT NULL`, an exact matching row must exist with:

```sql
w.campaign_id = NEW.campaign_id
AND w.project_uid = NEW.project_uid
AND w.work_record_uid = NEW.source_work_record_uid
AND w.effective_order <= NEW.achieved_order
```

Add regressions for:

1. same-project earlier/equal work -> accept;
2. same-project future work -> reject;
3. cross-project work -> reject;
4. direct SQL and typed-store paths;
5. reopen/integrity checks unchanged.

Do not redesign project lifecycle, Finance, Inventory, Ownership, Asset/Liability or Phase 16 command orchestration.

Result: **FAIL / RELEASE BLOCKER**.

---

# REGRESSION

## 30. Phase 3–14 regression — PASS for inspected/CI-covered scope

The candidate preserves the accepted authority boundaries:

```text
DevelopmentProject
!= Inventory
!= Equipment
!= OwnershipRecord
!= Financial Ledger
!= Asset/Liability
!= Skill/Technique final authority
```

V15 migration chains from V14, StatePatch protection preserves earlier typed-only domains, restore/campaign switching remains routed through CurrentSchema, and exact CI #311 passes the full JVM test suite.

No inspected Phase-15 code directly mutates earlier canonical domain state as an implicit project side effect.

Result: **PASS**.

---

# FINAL VERDICT

# PHASE 15 INTEGRITY REVALIDATION: FAIL

for exactly:

`47f85c1689fb78cfd5c7edd9d82f897485357dab`

The candidate's migration/routing, race suite, stable replay, StatePatch protection, scale/reopen and represented FK/integrity checks are strong, but canonical milestone evidence can currently point to another project's work or to future work. Phase 15 remains blocked until this Phase-15-only SQLite boundary defect is corrected and a new runtime SHA is revalidated.

Phase 15 is not marked COMPLETE/ACCEPTED. Phase 16 was not started.

---

## Requested final summary

```text
Validated runtime SHA: 47f85c1689fb78cfd5c7edd9d82f897485357dab
Exact CI: GitHub Actions #311 / run ID 31572017265 / SUCCESS
Verdict: PHASE 15 INTEGRITY REVALIDATION: FAIL
Migration result: PASS — additive V14->V15, clean bootstrap, repeated ensure, restore, campaign switch, zero legacy synthesis
P15-RACE-01: PASS
P15-RACE-02: PASS
P15-RACE-03: PASS
P15-RACE-04: PASS
P15-RACE-05: PASS
P15-RACE-06: PASS
Replay integrity: PASS for typed-store exact/conflicting replay gates; overall integrity blocked by milestone source-work reference/temporal defect
Scale/reopen: PASS — 1001 work records and derived progress preserved after reopen
PRAGMA integrity_check: PASS — ok
PRAGMA foreign_key_check: PASS — zero Phase-15 authoritative-table violations in tested paths
Phase 3–14 regression: PASS for inspected/CI-covered scope
Report path: docs/audits/WORK-20260812-P15_CHAT3_FINAL_INTEGRITY_REVALIDATION_47F85C.md
```
