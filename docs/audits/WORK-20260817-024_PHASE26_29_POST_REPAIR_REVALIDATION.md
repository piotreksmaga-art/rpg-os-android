# WORK-20260817-024 — Phase 26–29 Post-Repair Revalidation

## 1. Audit identity

- **Work ID:** `WORK-20260817-024`
- **Role:** CHAT-4 — independent test / invariant / compatibility reviewer
- **Mode:** READ-ONLY RUNTIME; evidence-only report commit permitted
- **Repository:** `piotreksmaga-art/rpg-os-android`
- **Exact runtime SHA audited:** `29b1e1822636e004baac363a5ade9991ca9c19b8`
- **Previous failed runtime:** `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`
- **Repair context:** `WORK-20260817-023`
- **Previous audits revalidated:** `WORK-20260817-021`, `WORK-20260817-022`
- **Current master observed before this evidence-only write:** `efc7d4c9e6c29aed343fb77d02fab97490292564`
- **Candidate -> observed master drift:** one docs-only commit adding `WORK-20260817-023_PHASE26_29_POST_AUDIT_BLOCKER_REPAIR.md`; no runtime/schema/migration/test drift.

This audit applies only to runtime/schema/test semantics represented by `29b1e1822636e004baac363a5ade9991ca9c19b8`.

## 2. Final verdict

**FAIL — FIX REQUIRED BEFORE PHASE 26–29 ACCEPTANCE**

The repair successfully closes the previously reported false-receipt, G28->G29 compatibility, recovery-reader DDL, ownership null-event, project-fixture, and concurrency-test gaps. However, the core Phase-26 capability/exclusivity contract remains bypassable in three concrete ways. Therefore Group A is not yet acceptance-ready.

No Phase 30 work is declared or authorized by this report.

## 3. Exact repair delta inspected

Comparison `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5..29b1e1822636e004baac363a5ade9991ca9c19b8` shows repair changes concentrated in:

- `CampaignMutationBoundary.kt`
- `GameplayMutationGate.kt`
- `TurnTransaction.kt`
- `TurnTransactionReceiptStore.kt`
- `CampaignTruthStore.kt`
- `EquipmentStore.kt`
- `OwnershipStore.kt`
- `Phase12Migration.kt`
- `PlayerChangeSetModel.kt`
- `PlayerChangeSetCodec.kt`
- `WorldRuleProvider.kt`
- `UnifiedGameRepository.kt`
- Phase26–29 blocker/regression tests and ownership/truth integration tests

The implementation report was used only as context; all findings below come from the exact runtime/test source and exact-SHA CI evidence.

## 4. Findings summary

| ID | Severity | Area | Result |
|---|---|---|---|
| `P26-29-C4-024-001` | **BLOCKER** | Single Truth Mutation Path bootstrap | Direct authoritative store writes are not fail-closed until receipt schema + DB guards are explicitly armed. The production stores/migrations do not universally install those guards before gameplay-authoritative writers become callable; the blocker tests call a test-only `arm()` first. |
| `P26-29-C4-024-002` | **BLOCKER** | Capability forgery / DB guard | The durable `rpgos_gameplay_mutation_context` table is itself an unguarded capability source. A caller with the campaign `SQLiteDatabase` can insert `TURN` or `ADMIN`, satisfying every authoritative-table trigger without `CampaignMutationBoundary` or `TurnTransaction`. |
| `P26-29-C4-024-003` | **BLOCKER** | Canonical seal / reflection | Canonical proposal and TurnTransaction seals are process-local private top-level `Any` singletons. JVM reflection can read those private static fields and invoke the module-visible constructors with the real seal. Existing semantic tests only use `Any()` fake values and do not exercise the required reflective attack path. |

No additional acceptance blocker was found in the repaired receipt completeness, G28->G29 migration, recovery read, ownership, project, CampaignTruthChange, or concurrency logic.

---

# A. SINGLE TRUTH MUTATION PATH

## A1. Generic patch route

PASS.

The generic `StatePatchEngine` gameplay route remains fail-closed and was not reopened by the repair.

## A2. Canonical transaction-bound domain application

PASS when the guard system is installed and a real canonical TurnTransaction is used.

`TurnTransaction.commit()` now owns the effect program. The caller can no longer provide an arbitrary `execute {}` block. `CanonicalPlayerChangeApplier` preflights supported intents/changes, applies the admitted `PlayerChangeSet`, checks the exact applied change UID sequence, and appends the receipt only after complete application.

The canonical applier routes supported effects to existing authorities:

- Stat/Resource -> `StatResourceStore`
- Skill -> `SkillStore`
- Technique -> `TechniqueStore`
- Inventory -> `InventoryStore`
- Equipment -> `EquipmentStore`
- Finance -> `FinancialStore`
- Ownership -> `OwnershipStore`
- Campaign truth -> `CampaignTruthStore`
- Development project work -> `DevelopmentProjectStore`

No second finance/ownership/inventory/project/truth current-state authority was introduced.

## A3. BLOCKER — guards are not mandatory from authoritative-store availability

`GameplayMutationDatabaseGuards.ensureInstalled(db)` is invoked by `TurnTransactionBoundary.create(...)`. The blocker writer-matrix test does not prove fail-closed behavior before that point: every A01–A08 test explicitly calls helper `arm(d)`, which executes:

`TurnTransactionReceiptSchema.ensureReady(d)`
`GameplayMutationDatabaseGuards.ensureInstalled(d)`

before attempting the direct writer.

This matters because authoritative stores are available before this arm step. Examples:

- `InventoryStore` constructor runs `MigrationManager().ensureV10(...)` and its public authoritative methods write directly / start local transactions.
- `CampaignTruthStore.record()` starts its own transaction if no outer transaction exists.
- `StatResourceStore.savePlayerStat/savePlayerResource` write the authoritative tables directly.
- analogous typed stores remain normal table owners.

`requireCanonicalGameplayMutation(...)` also explicitly returns without enforcing anything when `TurnTransactionReceiptSchema.isReady(db)` is false, and the inspected authoritative store implementations are primarily protected by database triggers, not by an unconditional capability check in each mutator.

Therefore a newly upgraded/created database can have authoritative tables and callable typed gameplay writers while `turn_transaction_receipts`/guard triggers have not yet been installed by the first `TurnTransactionBoundary.create(...)`.

Concrete semantic counterexample:

1. create/open campaign schema with inventory/truth/stat authority tables;
2. do **not** call `TurnTransactionReceiptSchema.ensureReady` and do not create a TurnTransaction;
3. call a direct authoritative store writer;
4. no gameplay guard trigger exists yet;
5. the store's local transaction can commit without canonical admission or receipt.

This violates the required invariant that gameplay-authoritative store writes outside an active canonical transaction fail closed.

**Severity: BLOCKER.**

---

# B. CANONICAL PROPOSAL / CAPABILITY FORGEABILITY

## B1. Public admission surface

PASS at ordinary source-level API shape.

`CampaignMutationBoundary` no longer accepts an externally supplied `PlayerResolutionOutcome.Resolved`; `resolveAndAdmit(...)` invokes `PlayerDomainEngine.resolve(...)` internally. The old direct `Resolved -> Accepted` admission API is gone.

## B2. Fake `Any()` seal tests

PASS but insufficient for the requested adversarial contract.

`CanonicalCampaignMutationProposal` rejects an arbitrary fake seal with `RPGOS-MUTATION-GATE:FORGED_CANONICAL_PROPOSAL`.

`TurnTransaction` rejects an arbitrary fake transaction seal with `RPGOS-TURN-TRANSACTION:FORGED_CAPABILITY` before effects/receipt.

## B3. BLOCKER — persistent DB context row is forgeable

`GameplayMutationDatabaseGuards` creates persistent table:

`rpgos_gameplay_mutation_context(campaign_uid PRIMARY KEY, capability_kind CHECK IN ('TURN','ADMIN'))`

Every authoritative-table guard trigger authorizes the write solely by checking that a matching row exists in this table with `capability_kind IN ('TURN','ADMIN')`.

The context table itself has no trigger/capability protection. `enterTurn()` simply inserts the same ordinary row. Consequently code holding the campaign `SQLiteDatabase` can manufacture the database-level capability directly:

`INSERT INTO rpgos_gameplay_mutation_context(campaign_uid,capability_kind) VALUES('C1','TURN')`

or `ADMIN`, then invoke direct authoritative store writers. Those triggers see the forged row and allow the mutations. No canonical proposal, PlayerDomainEngine result, TurnTransaction seal, applied-change verification, or receipt is required.

Because the row is in a normal persisted table, an externally inserted row can also outlive the intended process-local/thread-local scope until explicitly deleted.

This is a semantic capability forgery, not merely a Kotlin visibility concern.

**Severity: BLOCKER.**

## B4. BLOCKER — real private seals are recoverable by JVM reflection

Both canonical capability identities are private top-level process-local objects:

- `CANONICAL_PROPOSAL_SEAL = Any()`
- `TURN_TRANSACTION_SEAL = Any()`

The semantic tests only pass a new `Any()` and prove identity mismatch. They do not test the user-required reflection/JVM attack.

On JVM/Android app-owned classes, private fields are reflectively discoverable. An in-process caller can obtain the private static top-level field with `getDeclaredField(...)`, enable reflective accessibility, read the **real object identity**, then call the module-visible/internal constructor using that real seal. The same applies to the TurnTransaction seal. Kotlin `internal` is not a runtime security boundary.

Therefore the protection is tamper resistance by source convention, not an unforgeable semantic capability under the explicitly required reflection attack model.

Expected required behavior was:

- forged canonical proposal -> `FORGED_CANONICAL_PROPOSAL`
- valid canonical proposal + forged transaction capability -> `FORGED_CAPABILITY`

The current implementation guarantees that only for *different objects*, not for an attacker that obtains the actual singleton via reflection.

**Severity: BLOCKER.**

---

# C. RECEIPT MUST PROVE FULL EFFECT

PASS.

The previous false-receipt blocker is closed.

`TurnTransaction.commit()`:

1. replays committed identity if present;
2. preflights the full admitted change set;
3. begins the outer SQLite transaction;
4. rechecks replay inside the transaction;
5. executes the fixed `CanonicalPlayerChangeApplier.applyAll(...)` program;
6. requires exact equality between `appliedChangeUids` and admitted change UIDs;
7. appends the receipt;
8. commits effects + receipt atomically.

A caller cannot pass arbitrary/no-op application code anymore. Unsupported changes/intents fail closed. A failure after a subset or after receipt staging rolls back effects and receipt. Identical committed retry returns `AlreadyCommitted` and does not reapply effects.

The old counterexample `non-empty proposal + no-op execute {}` is no longer expressible through the public commit API.

---

# D. AUTHORITATIVE DOMAIN INTEGRATION

PASS for canonical applier routing, subject to the Phase-26 exclusivity blockers above.

The repair reuses existing domain stores rather than creating parallel authorities.

| Domain | Canonical applier target | Authority assessment |
|---|---|---|
| Finance | `FinancialStore.commit(...)` | Existing finance ledger remains authority |
| Ownership | `OwnershipStore.transferShare(...)` | Existing temporal ownership history remains authority |
| Inventory | `InventoryStore` | Existing inventory tables remain authority |
| Campaign truth | `CampaignTruthStore.record(...)` | Existing FACT/BELIEF/NARRATIVE authority remains sole truth store |
| Equipment | `EquipmentStore` | Existing equipment authority reused |
| Projects | `DevelopmentProjectStore.recordWork(...)` | Existing Phase-15 authority reused |
| Stats/resources | `StatResourceStore` | Existing player state authority reused |
| Skills/techniques | `SkillStore` / `TechniqueStore` | Existing typed mastery authorities reused |

Canonical effects run under the same outer SQLite transaction and participating store local transaction helpers join it where applicable.

---

# E. OWNERSHIP REPAIR

PASS.

The Phase12 schema/model repair makes ownership operation `source_event_uid` nullable and permits a legal CLOSED ownership record with null `closed_by_event_uid` while still requiring closure provenance/order semantics.

`Work023OwnershipCanonicalIntegrationTest` verifies:

- preexisting A ownership;
- canonical A->B transfer;
- A record closed at the expected order;
- B successor active at that order;
- `closedByEventUid == null` and successor `sourceEventUid == null` when no event exists;
- predecessor/supersession/history continuity;
- operation `source_event_uid` remains SQL NULL;
- rollback restores A-only history and leaves no operation or receipt.

No Event Store identity is fabricated. Phase 30 remains unimplemented.

---

# F. REAL G28 -> G29 MIGRATION

PASS.

The previous `CHECK(receipt_version = 1)` blocker is fixed by a real table rebuild when the existing receipt table lacks `commit_order` or has the V1-only CHECK.

Repaired behavior:

- current schema permits receipt versions 1 and 2;
- `commit_order` is nullable;
- migrated V1 rows are copied with `commit_order = NULL` if historical order was not recorded;
- semantic/result fingerprints are preserved;
- no fabricated commit order is assigned to old V1 receipts;
- new V2 receipts receive prospective campaign-scoped positive commit order;
- last-valid-commit excludes NULL-order historical V1 receipts.

`D01_real_g28_v1_schema_rebuild_preserves_history_and_allows_v2` constructs a real V1-only table and verifies the above rather than only testing a fresh DB.

---

# G. RECOVERY READER READ-ONLY

PASS.

`TurnRecoveryReader` only accepts a schema already satisfying `TurnTransactionReceiptSchema.isReady(...)` and fails closed with `RPGOS-TURN-RECOVERY:SCHEMA_NOT_READY` otherwise.

It does not invoke `ensureReady`, CREATE TABLE, ALTER TABLE, CREATE INDEX, or migration metadata writes during construction/read.

The blocker suite compares `sqlite_master` and migration metadata before/after recovery reads and verifies no changes.

---

# H. CRASH / RETRY / CONCURRENCY

PASS for the repaired canonical transaction path, subject to capability-exclusivity blockers.

Coverage/source inspection confirms:

- rollback before authoritative writes;
- rollback after first effect;
- rollback after multiple effects;
- rollback after receipt staging / before physical commit;
- response-loss + reopen replay;
- retry after rollback;
- same command/same semantics dedupe;
- changed semantics conflict;
- cross-campaign transaction identity conflict;
- duplicate finance/inventory/ownership suppression through one committed receipt;
- two real SQLite connections competing on identical semantics;
- conflicting semantics fail closed.

The new two-connection tests close the prior MEDIUM evidence gap from `WORK-20260817-021`.

---

# I. PROJECT DOMAIN REGRESSION

PASS.

The final A08 repair is fixture/reference setup only. The canonical applier still writes `ProjectWorkRecord` through `DevelopmentProjectStore.recordWork(...)` and Phase15 triggers remain authoritative.

The fixture now registers the actual canonical actor namespace `PLAYER:P1` in addition to `CHARACTER:P1`. This satisfies the existing actor-reference trigger instead of weakening it.

No evidence was found that lifecycle, effective order, progress cap/boundaries, project triggers, or DevelopmentProjectStore authority were relaxed.

---

# J. CAMPAIGN TRUTH CHANGE

PASS.

`CampaignTruthChange` is a typed payload with direct semantic fields rather than an invented `DomainRef`.

Both `PlayerChangeSetCodec` and `WorldRuleEffectSnapshot` canonical serialization include:

- truthUid
- truth kind
- subjectUid
- predicate
- objectValue
- perspectiveUid
- narrativeText
- supersedesTruthUid

Therefore these fields participate in deterministic proposal identity and final DRAFT_EFFECT_CHECK fingerprinting.

Canonical commit routes the payload to `CampaignTruthStore.record(...)`. No Event Store append semantics are introduced.

FACT/BELIEF/NARRATIVE remain distinct through `TruthKind` and existing truth validation.

---

# K. REGRESSION / COMPATIBILITY

Focused source and exact-SHA test evidence did not identify a new regression in accepted Phase 19–25 behavior:

- pinned WorldRuleProvider architecture remains present;
- final effect snapshot still owns one `DRAFT_EFFECT_CHECK` stage;
- Phase20 progression remains proposal/deterministic;
- Phase22 invariant validation remains upstream of canonical admission;
- Phase23 provenance boundary remains distinct from committed turn receipt;
- Phase24/25 snapshots/profiles remain derived;
- FACT != BELIEF != NARRATIVE remains preserved;
- finance, ownership and inventory authorities remain the same stores.

No Phase30 Event Store implementation was introduced by this repair.

---

# L. CI / ARTIFACT EVIDENCE

Independently verified exact-SHA workflow:

- Workflow: `Validate RPG OS ALPHA`
- Run: `#697`
- Run ID: `32024921741`
- `head_sha = 29b1e1822636e004baac363a5ade9991ca9c19b8`
- status: `completed`
- conclusion: `success`

Job evidence confirms success for:

- Validate project
- Run JVM unit tests
- Build signed validation APK
- Prepare immutable validation artifact
- Upload immutable Actions artifact

Artifact independently verified:

- name: `RPG-OS-VALIDATION-1.2.0-alpha5-hybrid145-29b1e1822636e004baac363a5ade9991ca9c19b8`
- artifact ID: `9286851265`
- digest: `sha256:0c0788ab20b5079731ee89242e29069612470af8174ca7562250c4064e03da49`
- artifact head SHA: exact candidate SHA

Green CI is not treated as semantic proof. The three blockers above are outside the cases actually exercised by the existing exact-SHA test suite.

## Test execution limitation

The audit environment could not clone GitHub over the container network, so no extra local Gradle execution was possible. The full exact-SHA suite and artifact evidence were instead independently verified through the GitHub Actions API, and the adversarial findings are derived directly from exact-SHA runtime/test source inspection. No runtime or test file was modified to demonstrate them.

---

# 5. Required repair focus

This audit does not prescribe an implementation, but acceptance requires all of the following properties to become true simultaneously:

1. authoritative gameplay guards are installed/enforced before any gameplay-authoritative typed store can commit after upgrade/open, not only after first TurnTransaction creation;
2. no ordinary DB row/SQL operation can manufacture `TURN`/`ADMIN` authority;
3. the semantic capability cannot be recovered/forged through the explicitly required reflection/JVM attack model;
4. administrative/migration/install/recovery writes remain explicitly separate and auditable;
5. repaired receipt/migration/recovery/ownership/project/truth behavior remains unchanged.

---

# 6. Final verdict

**FAIL — FIX REQUIRED BEFORE PHASE 26–29 ACCEPTANCE**

The verdict is bound **only** to runtime SHA:

`29b1e1822636e004baac363a5ade9991ca9c19b8`

This report does **not** declare Phase 26–29 ACCEPTED and does **not** start Phase 30.
