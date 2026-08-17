# WORK-20260817-025 — Phase 26–29 Cross-Boundary Post-Repair Audit

Status: POST-REPAIR / EVIDENCE-ONLY / READ-ONLY RUNTIME

Role: CHAT-5 — independent cross-boundary / source-of-truth / transaction-integrity reviewer

Repository: `piotreksmaga-art/rpg-os-android`

Exact runtime SHA audited: `29b1e1822636e004baac363a5ade9991ca9c19b8`

Failed pre-repair SHA: `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`

Repair work: `WORK-20260817-023`

Previous cross-boundary audit: `WORK-20260817-022`

## 1. Executive verdict

**FAIL — FIX REQUIRED BEFORE PHASE 26–29 ACCEPTANCE**

The repair closes the two defects identified in WORK-20260817-022 at the ordinary Kotlin transaction API level:

1. callers can no longer provide an arbitrary transaction block and receive a receipt for a different admitted proposal; `TurnTransaction.commit()` now invokes a canonical full `PlayerChangeSet` applier itself;
2. the receipt/recovery schema no longer invents historical `commitOrder` for V1 receipts, and recovery readers no longer perform lazy DDL/migration.

However the repaired runtime still does **not** establish one unforgeable campaign mutation boundary at the database capability layer.

The new DB guards trust rows in the ordinary persistent table `rpgos_gameplay_mutation_context`. Any caller with the writable `SQLiteDatabase` can insert `('campaign','TURN')` or `('campaign','ADMIN')` directly and thereby satisfy every authoritative-table trigger without possessing the canonical Kotlin seal, without `CampaignMutationBoundary`, without `TurnTransaction`, and without a committed receipt. The production repository publicly exposes such a writable database through `UnifiedGameRepository.openSaveDb()`.

Therefore a committed receipt is now trustworthy **when mutation is actually executed through `TurnTransaction.commit()`**, but the runtime still permits semantically equivalent authoritative gameplay state to be committed without any receipt. Group A remains a parallel path beside forgeable direct authority rather than the sole trusted mutation path.

A second cross-phase issue remains: `PlayerDomainEngine` Phase-20/21 progression augmentation emits `PROGRESSION` ledger intents together with generated stat/skill/technique changes, while `CanonicalPlayerChangeApplier.preflight()` rejects every `PROGRESSION` ledger intent before commit. This preserves safety (no false receipt), but it means legitimate progression-bearing proposals admitted by the existing legality pipeline are not end-to-end commit-capable under Group A.

No Phase 26–29 item is declared ACCEPTED by this audit. Phase 30 is not started.

---

## 2. Exact SHA / delta / CI evidence

Direct compare:

`2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5..29b1e1822636e004baac363a5ade9991ca9c19b8`

Result:

- repaired SHA is 55 commits ahead;
- merge base is exactly failed pre-repair SHA `2eba3b2f...`;
- runtime-relevant changed files are:
  - `CampaignMutationBoundary.kt`
  - `CampaignTruthStore.kt`
  - `EquipmentStore.kt`
  - `GameplayMutationGate.kt` (new)
  - `OwnershipStore.kt`
  - `Phase12Migration.kt`
  - `PlayerChangeSetCodec.kt`
  - `PlayerChangeSetModel.kt`
  - `PlayerDomainEngine.kt`
  - `TurnTransaction.kt`
  - `TurnTransactionReceiptStore.kt`
  - `UnifiedGameRepository.kt`
  - `WorldRuleProvider.kt`
- test changes/additions include the dedicated WORK-023 boundary, ownership, forgeability, idempotency and crash-recovery suites;
- documentation/TEST-GM changes in the compare are context only and are not treated as runtime evidence.

CI independently verified:

- workflow: `Validate RPG OS ALPHA`
- run number: **#697**
- run ID: **32024921741**
- `head_sha`: **`29b1e1822636e004baac363a5ade9991ca9c19b8`**
- status: **completed**
- conclusion: **success**
- artifact: `RPG-OS-VALIDATION-1.2.0-alpha5-hybrid145-29b1e1822636e004baac363a5ade9991ca9c19b8`
- artifact ID: `9286851265`
- artifact digest: `sha256:0c0788ab20b5079731ee89242e29069612470af8174ca7562250c4064e03da49`
- artifact `workflow_run.head_sha` matches the audited runtime SHA.

Green CI is accepted as corroborating evidence only; it does not override the architectural bypass below.

---

## 3. Mutation authority map

### 3.1 GAMEPLAY_AUTHORITATIVE

Current gameplay authority families covered by the new DB guard list:

- inventory current state:
  - `player_inventory_stacks`
  - `player_inventory_unique`
  - `item_instances`
- finance authoritative ledger:
  - `financial_ledger_transactions`
- ownership temporal authority:
  - `ownership_records`
  - `ownership_operations`
- campaign truth:
  - `campaign_truth_records`
- player state:
  - `player_stats`
  - `player_resources`
  - `player_skills_v2`
  - `player_techniques_v2`
  - `player_equipment`
  - `player_equipment_slots`
- development project authority/history:
  - `development_projects`
  - `project_status_history`
  - `project_requirements`
  - `project_requirement_satisfactions`
  - `project_milestone_definitions`
  - `project_milestone_achievements`
  - `project_work_records`
  - `project_dependencies`
  - `project_outcomes`

Domain ownership remains correctly distributed: the canonical applier calls the existing typed stores rather than reimplementing their persistence semantics.

### 3.2 APPEND_ONLY_COMMIT_EVIDENCE

`turn_transaction_receipts` is transaction identity / committed-turn evidence only. It stores:

- campaign identity;
- turn identity;
- command identity;
- transaction identity;
- semantic proposal fingerprint;
- result/receipt fingerprint;
- nullable campaign-scoped `commit_order`;
- receipt version;
- fixed `COMMITTED` state.

It does not contain player state, finance entries, ownership records, project history, narrative payload, Event Store payload, or snapshots.

### 3.3 ADMIN / MIGRATION / INSTALL / RECOVERY

Separate privileged families remain:

- schema/migrations and migration markers;
- bootstrap/package installation and canonical package replacement;
- backup/restore;
- definition/reference setup (currencies, account setup, ownership registries, item/skill/technique/project definitions);
- active campaign/player selection where treated as administrative application state.

These should remain outside ordinary gameplay transactions, but their capability must not be forgeable by gameplay callers.

### 3.4 DERIVED / CACHE / PRESENTATION

Still non-authoritative:

- finance balance projection (ledger remains winner);
- Character Panel / Player Snapshot read models;
- status/context projections;
- visual/presentation records where already classified as such;
- backups/snapshots as recovery artifacts, not commit truth;
- chapter/chronicle presentation outputs.

---

## 4. Finding B-01 — DB gameplay/admin capability is forgeable — BLOCKER

### Evidence

`GameplayMutationDatabaseGuards.ensureInstalled()` creates:

```sql
CREATE TABLE IF NOT EXISTS rpgos_gameplay_mutation_context(
    campaign_uid TEXT PRIMARY KEY,
    capability_kind TEXT NOT NULL CHECK(capability_kind IN ('TURN','ADMIN'))
)
```

Every authoritative-table trigger then authorizes INSERT/UPDATE/DELETE whenever that table contains a matching row with `capability_kind IN ('TURN','ADMIN')`.

The intended Kotlin path is sealed correctly:

`TurnTransaction.commit()`
→ `withCanonicalGameplayMutationForTurn(...)`
→ `TurnTransactionBoundary.acceptsCanonicalSeal(seal)`
→ `GameplayMutationDatabaseGuards.enterTurn(...)`.

But the SQL trigger cannot distinguish a row inserted by `enterTurn()` from a row inserted by any other writable DB caller.

Production exposes the writable database directly:

```kotlin
override fun openSaveDb(): SQLiteDatabase {
    val db = store.openSaveDb()
    ...
    GameplayMutationDatabaseGuards.ensureInstalled(db)
    return db
}
```

Therefore this sequence is possible without any canonical proposal or TurnTransaction:

```text
repo.openSaveDb()
→ INSERT INTO rpgos_gameplay_mutation_context(campaign_uid, capability_kind)
  VALUES(<campaign>, 'TURN')
→ InventoryStore / FinancialStore / OwnershipStore / CampaignTruthStore / ... direct write
→ local store transaction COMMIT
→ no turn_transaction_receipt
```

The same applies to forged `ADMIN` capability.

No secret/token/transaction UID/canonical seal is stored in or required by the trigger condition. There is also no trigger preventing direct writes to the capability table itself.

### Impact

This defeats the primary Phase-26 invariant. The new layer is mandatory only for callers that voluntarily refrain from minting DB capability rows.

A caller can produce authoritative gameplay reality with:

- no `PlayerCommand` legality chain;
- no `PlayerDomainEngine.resolve()`;
- no WorldRule checks;
- no Phase-22 invariant validation;
- no opaque canonical proposal;
- no semantic fingerprint verification;
- no outer TurnTransaction;
- no committed receipt;
- no campaign commitOrder.

This is exactly the forbidden category “trusted/direct gameplay writer outside TurnTransaction”, except the trust is now represented by an ordinary mutable database row.

### Required repair property

The DB enforcement capability must not be forgeable by arbitrary SQL through the same writable database handle. Possible designs include a connection/transaction-local mechanism not writable through public gameplay APIs, removal of raw writable DB exposure from gameplay callers, a writer facade whose mutation connection is not handed out, or equivalent capability enforcement that cannot be manufactured by executing SQL.

The audit does not prescribe one implementation, but the invariant is mandatory: **no production caller outside the canonical transaction owner may be able to make an authoritative-table trigger believe it owns TURN/ADMIN authority.**

---

## 5. Finding H-01 — guard installation is not a global schema invariant — HIGH

The guard installation is performed by:

- `UnifiedGameRepository.openSaveDb()`; and
- `TurnTransactionBoundary.create()`.

`LocalGameStore.openSaveDb()` itself returns the writable save DB directly. Its bootstrap/current-schema path calls `CurrentSchema.ensure(...)`, while WORK-023 tests explicitly perform setup writes after `CurrentSchema.ensure(...)` and only later call test helper `arm()` (`TurnTransactionReceiptSchema.ensureReady + GameplayMutationDatabaseGuards.ensureInstalled`). This proves guard readiness is not intrinsically equivalent to ordinary current-schema readiness.

Even ignoring B-01 SQL forgery, there is a pre-arming/API-surface window in which public typed store writers can operate on the same authoritative tables without the new guards.

This should be closed by making guard readiness part of the canonical writable-DB opening/schema readiness contract, or by otherwise preventing unguarded authoritative writer access.

---

## 6. Proposal → commit causal chain

For calls that use the repaired canonical path, the chain is materially correct:

```text
PlayerCommand
→ CampaignMutationBoundary.resolveAndAdmit(...)
→ PlayerDomainEngine.resolve(...)
→ command validation / canonicalization
→ reference validation
→ WorldRule COMMAND_PRECHECK
→ typed domain component
→ Phase 20/21 progression augmentation where applicable
→ augmented reference validation
→ one final DRAFT_EFFECT_CHECK
→ PlayerChangeSet assembly + structural validation
→ Phase-22 PlayerInvariantValidator
→ opaque CanonicalCampaignMutationProposal (private module seal)
→ TurnTransactionBoundary.create(...)
→ sealed TurnTransaction
→ TurnTransaction.commit()
→ CanonicalPlayerChangeApplier.preflight()
→ outer SQLite transaction
→ sealed canonical gameplay mutation scope
→ CanonicalPlayerChangeApplier.applyAll()
→ existing typed domain authorities
→ exact applied-change UID completeness check
→ append committed receipt
→ SQLite COMMIT
```

The earlier WORK-022 arbitrary-block defect is fixed: there is no `execute { callerBlock }` API and no `authoritativeWrite` escape surface on TurnTransaction.

Canonical proposal construction is also stronger than the failed candidate. The public production admission API calls `PlayerDomainEngine.resolve()` itself; it does not accept a caller-created `PlayerResolutionOutcome.Resolved`. `CanonicalCampaignMutationProposal` checks a private identity seal and `TurnTransaction` checks a separate private seal. Dedicated forgeability tests confirm fake seals fail closed.

**Result:** the Kotlin proposal→receipt chain itself is now trustworthy. B-01 fails the wider claim that this is the only path to committed authoritative reality.

---

## 7. Receipt semantics

### What a repaired V2 receipt proves on the canonical path

For a successful `TurnTransaction.commit()` it proves:

- the proposal passed `CanonicalPlayerChangeApplier.preflight()`;
- every supported `PlayerDomainChange` in the admitted `PlayerChangeSet` was dispatched by the canonical applier;
- the returned `appliedChangeUids` exactly equal the proposal change UID list in order;
- child domain mutations completed without throwing;
- the receipt was appended inside the same outer SQLite transaction;
- campaign/command/transaction identity was bound;
- the semantic fingerprint is `PlayerChangeSetCodec.fingerprint(full PlayerChangeSet)`;
- `resultFingerprint` binds receipt version semantics, campaign commitOrder, turn, command, transaction and semantic fingerprint;
- the receipt and all authoritative effects became durable together at the single SQLite COMMIT.

### Zero/subset/altered/unsupported/failed-child cases

- **zero effects for a non-empty supported changeset:** no caller block exists; canonical applier owns dispatch;
- **subset:** failure injection after an earlier change rolls back the earlier effect and leaves no receipt;
- **altered semantics on retry:** semantic-fingerprint conflict fails closed;
- **unsupported change:** preflight fails before authoritative writes;
- **event intent:** fails closed before writes;
- **progression ledger intent:** fails closed before writes (see H-02 below);
- **failed child authority mutation:** exception unwinds outer transaction; no durable receipt;
- **crash after receipt INSERT but before COMMIT:** receipt and effects roll back together.

The earlier receipt semantic blocker from WORK-022 is therefore repaired **inside the canonical path**.

### What a receipt still cannot prove globally

Because B-01 allows authority outside the canonical path, absence of a receipt does not prove absence of committed authoritative gameplay mutation. This prevents `turn_transaction_receipts` from serving as complete campaign commit evidence for all gameplay reality.

---

## 8. Finding H-02 — progression-bearing canonical proposals are not commit-capable — HIGH

`PlayerDomainEngine.augmentWithProgression()` preserves the accepted Phase-20/21 architecture by invoking `ProgressionEngine`, generating authoritative stat/skill/technique `PlayerDomainChange`s and adding the engine's `PROGRESSION` ledger intents to the same draft.

The repaired `CanonicalPlayerChangeApplier.preflight()` then explicitly does:

```text
PlayerLedgerIntentKinds.PROGRESSION -> throw UnsupportedCanonicalIntentException
```

Therefore a legitimate proposal containing progression stimuli may pass:

- PlayerDomainEngine;
- WorldRule gates;
- DRAFT_EFFECT_CHECK;
- PlayerChangeSet validation;
- PlayerInvariantValidator;
- CampaignMutationBoundary admission;

and still be categorically non-committable by Group A solely because the accepted progression evidence intent is present.

This is safe/fail-closed and does **not** create duplicate progression mechanics. The transaction layer does not recalculate progression. However it is an end-to-end cross-phase integration regression for progression-bearing player proposals and conflicts with the Phase-26–29 goal of coordinating authoritative progression-related writes.

Required property: Group A must either safely consume/preserve the already-resolved progression intent without becoming a second ProgressionEngine, or provide another architecture-consistent treatment that allows the authoritative generated changes to commit while retaining Phase-23 provenance semantics. It must not fabricate a new progression ledger/event authority.

---

## 9. Source-of-truth preservation

### Finance

PASS within canonical path.

`CanonicalPlayerChangeApplier.applyFinancial()` delegates to `FinancialStore.commit(FinancialTransaction(...))`. The financial ledger remains authoritative; TurnTransaction does not implement balance mathematics or replace finance transaction identity/idempotency. Balance remains derived/rebuildable.

### Ownership

PASS within canonical path.

`applyOwnership()` reads the current record from `OwnershipStore` and delegates to `transferShare()`. Existing temporal close/open, CAS, share and reference rules survive. TurnTransaction does not become a second ownership ledger.

### Inventory

PASS within canonical path.

Inventory writes delegate to `InventoryStore`; TurnTransaction does not introduce a competing inventory table or snapshot.

### Campaign truth

PASS within canonical path.

`CampaignTruthStore` remains the authority and still validates `TruthKind` policy. FACT/BELIEF/NARRATIVE remain structurally distinct. Receipt rows contain none of that semantic truth payload.

### Development projects

PASS within canonical path.

`DevelopmentProjectStore.recordWork()` remains project/work-history authority. Project lifecycle/reference triggers remain active. Receipt does not become project history.

### Receipts

PASS in semantic classification.

Receipts remain **APPEND_ONLY_COMMIT_EVIDENCE**. They are not player state, finance ledger, ownership history, project history, snapshot authority, narrative authority or Event Store.

---

## 10. Recovery / LAST VALID COMMIT

The WORK-022 recovery-reader defect is repaired.

`TurnRecoveryReader` now:

- performs no schema creation;
- performs no DDL;
- performs no migration;
- fails closed with `RPGOS-TURN-RECOVERY:SCHEMA_NOT_READY` if receipt schema is not ready.

`TurnTransactionReceiptStore.lastValidCommit(campaignUid)` uses only:

```sql
WHERE campaign_uid=?
  AND commit_state='COMMITTED'
  AND commit_order IS NOT NULL
ORDER BY commit_order DESC
LIMIT 1
```

It does not use:

- wall clock;
- `created_at`;
- filesystem mtime;
- snapshot/backup order;
- UUID lexical order;
- narrative state.

### Legacy G28/V1 ordering

The repaired migration rebuilds an old receipt table into the current shape but copies historical rows with:

`commit_order = NULL`

when the old table had no commit-order column.

That is the correct representation of **UNKNOWN / NOT RECORDED historical order**. Such a receipt is still durable evidence that its transaction was committed, and replay by transaction/command can still recognize it, but it cannot claim a historical position in `LAST VALID COMMIT` ordering.

New V2 receipts allocate `MAX(non-null commit_order)+1` per campaign inside the same outer write transaction. Failed turns do not consume durable order values because receipt insertion/order allocation roll back together.

No historical commandUid/transactionUid/event/provenance sequence is synthesized for pre-Transactional-Core history.

---

## 11. Crash atomicity

PASS for canonical TurnTransaction.

Outer transaction order is:

1. preflight before mutation;
2. `db.beginTransaction()`;
3. canonical capability scope;
4. all supported domain authority writes;
5. exact applied-change completeness check;
6. receipt append + campaign commitOrder allocation;
7. optional post-receipt failure point;
8. `setTransactionSuccessful()`;
9. `endTransaction()`.

Any exception before final commit causes `endTransaction()` without success and rolls back:

- earlier inventory/finance/ownership/truth/project/player writes;
- later writes;
- receipt;
- allocated commitOrder.

Domain stores that detect an existing outer SQLite transaction join it rather than independently committing. Dedicated repair tests cover subset rollback and receipt-after-write crash injection.

Derived/cache/presentation failure after authoritative COMMIT is outside this durability boundary and does not invalidate already committed truth, consistent with the existing authority direction.

Again, this result is scoped to the canonical path; B-01 permits non-canonical local commits.

---

## 12. Reader / migration boundary

PASS for `TurnRecoveryReader` itself.

Receipt schema preparation now belongs to explicit write/setup boundaries:

- `UnifiedGameRepository.openSaveDb()` prepares Phase-15 schema, receipt schema and gameplay DB guards before returning its DB;
- `TurnTransactionBoundary.create()` ensures receipt schema and guards before creating the transaction owner.

This is materially better than lazy reader DDL and is compatible with Phase 36's future responsibility to formalize one settled schema/version/migration gate.

However H-01 remains: this readiness discipline is not universal across every writable DB acquisition path (`LocalGameStore.openSaveDb()` exists separately), so Phase 26 enforcement is not yet globally closed.

---

## 13. Ownership NULL provenance repair

PASS.

The domain model already allowed absent `sourceEventUid`. The repair aligns `ownership_operations.source_event_uid` with that model:

- column becomes nullable;
- check remains `NULL OR non-blank`;
- existing values are copied unchanged during table rebuild;
- no event UID is fabricated;
- close/open records preserve `NULL` when event provenance was genuinely absent;
- `closure_provenance` remains mandatory for legal close transitions;
- valid-time ordering, CAS record version, share and reference triggers remain intact;
- operation replay still checks stored event semantics, including `NULL == NULL`.

The migration helper rebuilds only when `PRAGMA table_info` proves the old column was `NOT NULL`. If gameplay DB guards were already installed, the ownership-operation guard triggers are dropped for the controlled schema rebuild and reinstalled afterward.

The dedicated canonical ownership integration test demonstrates:

- original record closes legally;
- successor opens legally;
- both known null source-event fields remain null;
- no event is invented;
- later failure rolls the entire ownership operation back with no receipt.

---

## 14. Project reference boundary / A08

PASS.

`project_work_records` references `(campaign_id, actor_kind_uid, actor_uid)` against `ownership_party_registry`. The canonical applier constructs project-work actor from `PlayerChangeSet.actor`, which is `PLAYER:P1`, not `CHARACTER:P1`.

The old failing fixture had registered the character identity but not the legally distinct `PLAYER:P1` owner reference. The repair adds both:

- `CHARACTER:P1` for the project initiator/domain identity where required;
- `PLAYER:P1` for the canonical PlayerCommand actor recorded in work history.

The project-domain foreign key/reference trigger is not weakened. No fallback equates PLAYER and CHARACTER. No broad “same UID means same party” rule was introduced.

Therefore A08 is correctly classified as fixture/reference completion, not source-of-truth relaxation.

---

## 15. Phase-30 boundary

PASS.

No canonical Event Store is introduced by WORK-023.

`turn_transaction_receipts` contains transaction commit evidence only. It has no:

- gameplay event kind/payload schema;
- event actor/targets payload;
- causal event edge model;
- event replay payload;
- semantic event versioning;
- narrative event authority.

`CanonicalPlayerChangeApplier.preflight()` currently rejects non-empty `eventIntents`, which is conservative and prevents Group A from pretending receipt evidence is an Event Store.

Intentionally deferred to Phase 30 remain:

- canonical gameplay event records;
- event payload/schema/version contract;
- replay-oriented event history;
- causal event identity/links as designed by the roadmap;
- atomic event-to-authority coupling once Event Store exists.

This audit does not penalize Group A for missing those Phase-30 features.

---

## 16. Earlier phase boundaries

### Phase 19 WorldRuleProvider

Preserved. The admission path still calls `PlayerDomainEngine.resolve()`, and the engine still owns WorldRule authority validation/evaluation. Transaction code does not evaluate World Packs or recalculate mechanics.

### One final DRAFT_EFFECT_CHECK

Preserved. PlayerDomainEngine performs COMMAND_PRECHECK, domain/progression resolution, augmented reference closure, then one `DRAFT_EFFECT_CHECK` on the final augmented draft before proposal assembly.

### Phase 20–21 progression semantics

Mechanics ownership is preserved: `ProgressionEngine` remains inside PlayerDomainEngine and TurnTransaction does not reproduce progression formulas. However H-02 prevents progression-bearing proposals from committing because their `PROGRESSION` ledger intent is rejected.

### Phase 22 invariant gate

Preserved and mandatory before `Resolved`: proposal structural validation is followed by `PlayerInvariantValidator`; `CampaignMutationBoundary.resolveAndAdmit()` invokes the engine itself and only seals a successful `Resolved` proposal.

### Phase 23 provenance

Preserved in the proposal/fingerprint and propagated to domain writes. No new global writable player ledger is introduced. H-02 must be repaired without inventing one.

### Phase 24/25 derived read models

Preserved. CharacterPanel/PlayerSnapshot remain read/derived roles; TurnTransaction does not persist them as authority.

### FACT / BELIEF / NARRATIVE

Preserved by CampaignTruthStore policy/schema. Receipt does not collapse truth layers.

### Competing engines

No second Player Engine, WorldRuleProvider or ProgressionEngine was found in the repair. `CanonicalPlayerChangeApplier` is a persistence/application coordinator over already-resolved typed changes, not a rules engine.

---

## 17. Schema / migration delta review

### Gameplay mutation DB guard

New runtime schema object:

- `rpgos_gameplay_mutation_context`
- BEFORE INSERT/UPDATE/DELETE triggers on listed gameplay-authoritative tables.

Purpose is correct, but capability-table mutability causes B-01.

### Turn receipt G28 → G29 rebuild

Repair changes receipt evolution from synthetic historical ordering to additive rebuild with nullable order:

- current table accepts receipt versions 1 and 2;
- `commit_order` nullable with positive check when present;
- unique `(campaign_uid, commit_order)` index applies only where order is non-null;
- old V1 rows retain transaction/command/fingerprint/result fields;
- rows without historical order migrate as `NULL`;
- new V2 rows receive real prospective order;
- migration markers:
  - `RPGOS-28.0-TURN-IDEMPOTENCY`
  - `RPGOS-29.0-CRASH-RECOVERY`.

This is additive/rebuild-safe in the inspected implementation and does not fabricate historical order.

### Ownership sourceEventUid compatibility

New marker:

- `RPGOS-12.1-OWNERSHIP-OPTIONAL-SOURCE-EVENT`

The controlled table rebuild preserves every existing operation row and only relaxes `source_event_uid` from NOT NULL to nullable/nonblank-if-present. It does not manufacture source events or provenance.

### Other runtime model/codec deltas

`PlayerChangeSetModel` / codec changes extend typed canonical payload support needed by the full applier (including campaign truth/project handling) and keep the semantic fingerprint over the canonical serialized full PlayerChangeSet.

No Event Store, snapshot schema or Phase-30+ authority is introduced.

---

## 18. Finding summary

| ID | Severity | Finding | Acceptance impact |
|---|---|---|---|
| B-01 | **BLOCKER** | `rpgos_gameplay_mutation_context` TURN/ADMIN capability can be forged by direct SQL through publicly exposed writable save DB; guarded authoritative stores can then commit without TurnTransaction/receipt | Phase 26 single mutation path not closed; global receipt authority incomplete |
| H-01 | **HIGH** | guard readiness is not a universal writable-schema invariant; `LocalGameStore.openSaveDb()` / current-schema path can exist before guard arming | additional direct-writer window / API boundary not closed |
| H-02 | **HIGH** | accepted Phase-20/21 progression proposals with `PROGRESSION` ledger intents are rejected by TurnTransaction preflight | cross-phase progression path is not end-to-end commit-capable |

Positive repaired properties:

- arbitrary transaction block removed;
- canonical full change applier owns supported effect dispatch;
- applied-change completeness checked;
- child writes + receipt + commitOrder are one SQLite durability boundary;
- retries bind full proposal semantics;
- rollback/crash before COMMIT leaves neither effects nor receipt;
- post-COMMIT unknown outcome is resolved by durable receipt;
- recovery reader is read-only and schema-not-ready fails closed;
- V1 historical commit order remains UNKNOWN (`NULL`);
- ownership null provenance is preserved without fabrication;
- A08 project reference distinction remains strict;
- receipts are not Event Store;
- earlier domain/rule/invariant ownership is not duplicated.

---

# Final verdict

**FAIL — FIX REQUIRED BEFORE PHASE 26–29 ACCEPTANCE**

This verdict applies **only** to runtime SHA:

`29b1e1822636e004baac363a5ade9991ca9c19b8`

Phase 26–29 is **not** declared ACCEPTED.

Phase 30 must **not** begin on the basis of this audit.
