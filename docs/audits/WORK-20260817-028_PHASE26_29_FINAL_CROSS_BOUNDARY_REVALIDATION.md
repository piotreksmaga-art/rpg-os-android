# WORK-20260817-028 — Phase 26–29 Final Cross-Boundary Revalidation

## Scope

- Role: CHAT-5 independent cross-boundary / source-of-truth / integrity reviewer
- Mode: READ-ONLY runtime; evidence-only report commit permitted
- Repository: `piotreksmaga-art/rpg-os-android`
- Exact runtime audited: `45ff53457bff16c4ff72a4cccdecac89124109c3`
- Previous failed cross-boundary candidate: `29b1e1822636e004baac363a5ade9991ca9c19b8`
- Repair: WORK-20260817-026

This verdict is bound only to the exact runtime SHA above. No Phase 30 work was evaluated as part of the runtime candidate.

## Executive result

WORK-026 closes the remaining supported-production mutation-path blocker from WORK-20260817-025 and the progression commit incompatibility identified there.

The supported production architecture now exposes one gameplay-authoritative durable mutation route:

`PlayerCommand -> PlayerDomainEngine.resolve -> WorldRule gates -> progression augmentation -> one final DRAFT_EFFECT_CHECK -> PlayerChangeSet structural validation -> PlayerInvariantValidator -> CampaignMutationBoundary canonical admission -> CampaignRepository.commitTurn -> TurnTransaction -> CanonicalPlayerChangeApplier -> existing domain authorities -> append-only commit receipt -> COMMIT`

`CampaignRepository` no longer exports a raw writable campaign DB, direct truth writer, or generic patch writer. `UnifiedGameRepository` owns its writable gameplay DB handle privately and exposes `commitTurn(...)` as the gameplay durable mutation facade. `LocalGameStore` and raw writable save opening are internal infrastructure rather than supported gameplay API.

Under the requested supported-production threat model, `rpgos_gameplay_mutation_context` is therefore coordination state, not an independently usable gameplay authority.

No BLOCKER/HIGH/MEDIUM finding remains from this revalidation.

## 1. Writable authority ownership map

| Area | Authority class | Runtime owner | Phase 26–29 interaction | Verdict |
|---|---|---|---|---|
| Stats/resources | GAMEPLAY_AUTHORITATIVE | `StatResourceStore` | canonical applier calls existing store inside outer turn transaction | preserved |
| Skills | GAMEPLAY_AUTHORITATIVE | `SkillStore` | canonical applier calls existing store | preserved |
| Techniques | GAMEPLAY_AUTHORITATIVE | `TechniqueStore` | canonical applier calls existing store | preserved |
| Inventory | GAMEPLAY_AUTHORITATIVE | `InventoryStore` | canonical applier coordinates mutations atomically | preserved |
| Equipment | GAMEPLAY_AUTHORITATIVE | `EquipmentStore` | canonical applier coordinates existing equipment authority | preserved |
| Finance | GAMEPLAY_AUTHORITATIVE | `FinancialStore` / finance ledger | TurnTransaction does not recalculate ledger semantics | preserved |
| Ownership | GAMEPLAY_AUTHORITATIVE | `OwnershipStore` temporal history | TurnTransaction coordinates close/open mutation atomically | preserved |
| Campaign truth | GAMEPLAY_AUTHORITATIVE | `CampaignTruthStore` | direct repository writer removed; canonical `CampaignTruthChange` applies through store | preserved |
| Development projects | GAMEPLAY_AUTHORITATIVE | `DevelopmentProjectStore` | canonical project work uses existing store | preserved |
| Turn receipts | APPEND_ONLY_COMMIT_EVIDENCE | `TurnTransactionReceiptStore` | atomic commit evidence / retry identity only | correct |
| Schema/migration/install/recovery | ADMIN/MIGRATION/INSTALL/RECOVERY | internal infrastructure scopes | explicit administrative authority remains separate | correct |
| snapshots/panels/profiles | DERIVED/CACHE/PRESENTATION | existing read-model builders | no write authority introduced | preserved |

There is no remaining supported normal-gameplay category equivalent to “trusted direct gameplay writer outside TurnTransaction.”

## 2. Normal gameplay API surface

WORK-026 removes `openSaveDb()`, direct `recordTruth(...)`, and `applyPatch(...)` from the `CampaignRepository` gameplay contract. `UnifiedGameRepository` no longer publicly re-exports a writable save DB and instead implements:

`commitTurn(identity, canonicalProposal, failureInjector) -> private openGameplaySaveDb() -> TurnTransactionBoundary.create(...).commit()`.

The repair also makes `LocalGameStore` internal. Raw writable DB ownership therefore remains in storage/infrastructure implementation rather than ordinary supported gameplay code.

The pre-existing `StatePatchEngine` remains fail-closed for gameplay.

## 3. Mutation context is not authority

The database guard still uses `rpgos_gameplay_mutation_context` with TURN/ADMIN rows to coordinate trigger authorization. WORK-026 explicitly distinguishes TURN and ADMIN scopes.

The prior WORK-025 blocker depended on ordinary gameplay obtaining a writable SQLite handle and manually fabricating the coordination row. That supported path has been removed from `CampaignRepository` / `UnifiedGameRepository`.

Under the explicit audit threat model — supported production architecture, excluding unrestricted reflection/native-process attacks — the context table is not a separate gameplay authority. It is internal coordination state behind repository/infrastructure DB ownership.

Administrative scope remains internal and is not a normal gameplay API.

## 4. Domain authorities remain separated

TurnTransaction remains a coordinator, not a mega-domain engine.

The canonical applier dispatches typed `PlayerDomainChange` payloads into existing typed stores. Finance ledger remains finance truth; ownership temporal records remain ownership truth; inventory tables remain inventory truth; CampaignTruthStore remains truth authority; DevelopmentProjectStore remains project authority; typed player stores remain stat/resource/skill/technique/equipment authority.

Receipts do not reconstruct any of those current states and therefore are not a duplicate authority.

## 5. Progression cross-phase semantics

The WORK-025 progression incompatibility is repaired.

`PlayerLedgerIntentKinds.PROGRESSION` is now accepted during turn preflight when it has a `ProgressionLedgerIntentPayload`, has causal change UIDs, and those causal UIDs refer to canonical Stat/Skill/Technique changes.

The intent is not persisted into a new progression ledger. The durable mutation is still the generated Stat/Skill/Technique change. The receipt fingerprint binds the complete `PlayerChangeSet`, so progression evidence contributes to semantic identity without becoming committed progression history.

The WORK-026 integration test demonstrates:

- Phase-20 ProgressionEngine generates a durable StatChange plus PROGRESSION ledger intent;
- the final WorldRule DRAFT_EFFECT_CHECK sees both;
- the Phase-22 invariant resolver runs;
- canonical commit applies the generated durable change once;
- retry does not duplicate it;
- rollback removes the state mutation and receipt;
- no `progression_ledger` or `progression_ledger_entries` table exists.

Phase-23 proposal-evidence semantics therefore remain intact and forward-compatible.

## 6. Full proposal ↔ receipt binding

`TurnTransaction.commit()` still has no caller-supplied arbitrary effect block.

It performs canonical preflight, opens the outer SQLite transaction, enables the internal TURN guard scope, runs `CanonicalPlayerChangeApplier.applyAll(...)`, verifies that the returned applied UID list exactly equals the proposal change UID sequence, appends the committed receipt, and only then marks the transaction successful.

Consequences:

- zero-effect receipt for a non-empty supported proposal: not available through canonical commit;
- subset-effect receipt: prevented by canonical full applier / applied UID completeness check;
- altered-effect receipt: caller cannot replace the canonical effect program;
- unsupported change/intent: preflight fails closed;
- failed child authority mutation: exception rolls back outer transaction;
- failure after receipt insertion but before SQLite COMMIT: receipt and effects roll back together.

Receipt semantic fingerprint remains `PlayerChangeSetCodec.fingerprint(proposal.playerChangeSet)` and result fingerprint additionally binds campaign, commit order, turn UID, command UID, transaction UID and semantic fingerprint.

## 7. Recovery / LAST VALID COMMIT

`TurnTransactionReceiptStore.lastValidCommit(campaignUid)` selects only committed receipts with non-null `commit_order`, ordered by `commit_order DESC` within the campaign.

It does not use timestamps, filesystem metadata, UUID lexical order, snapshots, backups, narrative state or derived presentation data.

V1/G28 receipts migrated without historical order retain `commit_order = NULL`. They remain usable as existing transaction/command evidence for replay identity, but they do not participate in invented LAST VALID COMMIT ordering.

This preserves UNKNOWN historical order instead of fabricating chronology.

`TurnRecoveryReader` remains read-only and requires schema readiness rather than lazily performing DDL/migration.

## 8. Legacy / migration

The Group-A receipt migration remains prospective:

- existing V1 receipts are rebuilt into the current table without inventing `commit_order`;
- receipt versions 1 and 2 remain representable;
- new commits receive prospective campaign-scoped order;
- no historical command/event/actor/provenance sequence is synthesized;
- unknown historical ordering remains NULL.

WORK-026 production gameplay opening prepares current schema, receipt schema, and DB mutation guards before returning the internal gameplay DB handle. On subsequent reopen, if guards are already installed, schema readiness work executes under explicit administrative authority. This removes the supported initialization window where gameplay could mutate first and enforcement would activate later.

This is compatible with future Phase-36 migration work: Group A establishes readiness/enforcement ordering but does not claim a generalized future migration architecture.

## 9. Phase 30 boundary

No Event Store was introduced.

Turn receipts remain transaction commit evidence, not semantic gameplay events. Group A still does not provide:

- canonical committed gameplay event records;
- canonical event payload/schema;
- replay-oriented semantic event history;
- the later event causal graph / event-to-authority linkage assigned to Phase 30+.

The transaction preflight remains free to reject unsupported event intents rather than treating receipts as substitute events.

Phase 30 therefore remains a genuine next phase.

## 10. Earlier source-of-truth boundaries

No repair delta introduces a second Player Engine, WorldRuleProvider, ProgressionEngine, finance ledger, ownership engine, inventory authority, CampaignTruth authority or snapshot authority.

The canonical legality path remains owned by `PlayerDomainEngine`: command precheck, domain resolution, progression augmentation, augmented reference closure, one final `DRAFT_EFFECT_CHECK`, structural `PlayerChangeSet` validation and mandatory `PlayerInvariantValidator` before a Resolved proposal may be admitted.

Phase-24/25 CharacterPanel / PlayerSnapshot layers remain derived/rebuildable and no mutation path was added through them. FACT / BELIEF / NARRATIVE remains separated by CampaignTruth semantics.

## 11. Production initialization

WORK-026 adds an explicit `openGameplaySaveDb()` boundary inside internal storage. Before it returns, it ensures:

1. current schema readiness;
2. receipt schema readiness;
3. gameplay mutation DB guards installed.

If guards are already present after reopen/restart, schema preparation occurs inside explicit administrative mutation authority so DB guards do not create an initialization deadlock.

A production-initialization regression test covers first open and a later recreated store/process-style reopen and verifies direct CampaignTruth mutation is rejected in both cases.

No supported enforcement-late window was found.

## 12. Temporary workflow cleanup

At exact SHA `45ff53457bff16c4ff72a4cccdecac89124109c3`, `.github/workflows/` contains only the normal `build-alpha.yml` and `publish-alpha.yml` workflows. The WORK-026 branch-only temporary validation workflow is absent.

## 13. CI / artifact evidence

Independent verification:

- workflow: `Validate RPG OS ALPHA`
- run number: `#703`
- run ID: `32038070404`
- head SHA: `45ff53457bff16c4ff72a4cccdecac89124109c3`
- status: `completed`
- conclusion: `success`

Artifact:

- ID: `9291371251`
- name includes exact SHA `45ff53457bff16c4ff72a4cccdecac89124109c3`
- digest: `sha256:3190611f761afe298653d6778f4e47957eb10b6646c0dfaee3d924dcd4d27ab4`

Green CI is corroborating evidence only; the verdict is based on the architecture/code-path review above.

## Findings

### BLOCKER

None.

### HIGH

None.

### MEDIUM

None.

### LOW

None requiring Phase 26–29 repair. Future Phase-30 event semantics and future generalized migration architecture remain intentionally deferred scope, not findings against Group A.

## Final verdict

**PASS — PHASE 26–29 FINAL CROSS-BOUNDARY REVALIDATION PASSED — READY FOR COORDINATOR ACCEPTANCE REVIEW**

This PASS applies ONLY to exact runtime SHA:

`45ff53457bff16c4ff72a4cccdecac89124109c3`

This report does **not** declare Phase 26–29 ACCEPTED. Only the coordinator may issue global acceptance. Phase 30 was not started.