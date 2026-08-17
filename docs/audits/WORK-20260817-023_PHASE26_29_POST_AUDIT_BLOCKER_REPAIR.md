# WORK-20260817-023 — Phase 26–29 post-audit blocker repair

## Final disposition

**Runtime/test candidate:** `29b1e1822636e004baac363a5ade9991ca9c19b8`

This document is a docs-only post-validation report. The runtime/test candidate remains the pre-report SHA above.

WORK-023 does **not** declare Phase 26–29 accepted and does **not** start Phase 30.

## Validation evidence

### Standard exact-SHA hard gate

Workflow: `Validate RPG OS ALPHA`

- Run: `#697`
- Run ID: `32024921741`
- Head SHA: `29b1e1822636e004baac363a5ade9991ca9c19b8`
- Status: `completed`
- Conclusion: `success`
- Project validation: PASS
- Full `:app:testDebugUnitTest`: PASS
- Signed validation APK build: PASS
- Immutable artifact preparation: PASS
- Immutable artifact upload: PASS
- Artifact: `RPG-OS-VALIDATION-1.2.0-alpha5-hybrid145-29b1e1822636e004baac363a5ade9991ca9c19b8`
- Artifact ID: `9286851265`
- Artifact digest: `sha256:0c0788ab20b5079731ee89242e29069612470af8174ca7562250c4064e03da49`

### Ordered targeted validation

A temporary validation branch was based on the exact runtime/test candidate. Its only delta from the candidate was `.github/workflows/work023-targeted-validation.yml`; no runtime, schema, migration, or test source differed from the candidate.

Workflow: `WORK-023 Targeted Validation`

- Run ID: `32024989260`
- Validation commit: `591657df3cf422d423f1e1c317cdc2b15e488f34`
- Result: `completed / success`

Ordered results:

1. A08 project writer-matrix case alone — PASS
2. Phase15 / DevelopmentProject regressions — PASS
3. `Phase26To29PostAuditBlockerRepairTest` — PASS
4. semantic canonical forgeability regression — PASS
5. ownership regressions — PASS
6. all Phase26–29 tests — PASS
7. `CampaignTruthChangeIntegrationTest` — PASS
8. Phase19–25 regressions — PASS
9. full `:app:testDebugUnitTest` — PASS

### Freshness

Immediately before this report was created, `master` pointed exactly at `29b1e1822636e004baac363a5ade9991ca9c19b8`. No later runtime/schema/migration/test drift existed.

A concurrent earlier drift commit, `c8869f3845d11b9442013dc205069fb075cbd2f4`, was inspected and classified as docs-only: it modified only `docs/test-gm/TEST_GM_SNAPSHOT_2026-08-17_WITCHER_SMAGI.md`.

---

## A. Typed writer bypass

**ROOT CAUSE**

Gameplay-authoritative domain stores still exposed writer methods that could be invoked directly by production gameplay code unless the canonical TurnTransaction mutation context was enforced at the database authority boundary. Type ownership alone did not prove that the write had passed canonical admission and TurnTransaction.

**FIX**

Canonical gameplay writes are protected by the gameplay mutation database guards/capability context. Direct gameplay writer use outside the canonical TurnTransaction fails closed with `CANONICAL_TURN_TRANSACTION_REQUIRED`; canonical TurnTransaction opens the guarded mutation scope and continues to use the existing domain stores as authorities.

**TEST**

`Phase26To29PostAuditBlockerRepairTest` writer-matrix cases A01–A08 exercise direct versus canonical writes for inventory, finance, ownership, campaign truth, stats/resources, skills/techniques, equipment, and DevelopmentProject work.

**RESULT**

PASS. Direct gameplay mutation is rejected; canonical admitted mutation succeeds through the existing store authority.

---

## B. Canonical proposal forgeability

**ROOT CAUSE**

The earlier regression test over-relied on JVM reflection/public bytecode visibility of Kotlin `internal`. That tests symbol visibility, not whether gameplay code can obtain a semantically valid canonical proposal/capability.

**FIX**

Production protection remains seal/capability based and was not weakened. `CanonicalCampaignMutationProposal` validates identity against the private canonical proposal seal, while `TurnTransaction` independently validates its private transaction capability and canonical proposal.

A dedicated semantic regression was added: `Work023CanonicalForgeabilitySemanticTest`.

**TEST**

- Construct a proposal with a real admitted change set but `Any()` as the fake canonical seal → must fail with `RPGOS-MUTATION-GATE:FORGED_CANONICAL_PROPOSAL`.
- Construct a TurnTransaction with a real canonical proposal but `Any()` as the fake transaction capability → must fail with `RPGOS-TURN-TRANSACTION:FORGED_CAPABILITY` before any authoritative write or receipt.

**RESULT**

PASS. JVM visibility does not confer semantic commit authority; a valid capability can only come from the canonical admission/transaction path.

---

## C. False receipt / unapplied proposal semantics

**ROOT CAUSE**

A receipt boundary is unsafe if a non-empty canonical proposal can be recorded as committed without proving that every admitted change was actually applied, or if a partially applied multi-effect proposal can survive a later failure.

**FIX**

`CanonicalPlayerChangeApplier` preflights supported intents/changes, returns the exact applied change UID sequence, and TurnTransaction requires that sequence to equal the admitted change-set sequence before appending the commit receipt. Effects and receipt are inside the same SQLite transaction.

**TEST**

`Phase26To29PostAuditBlockerRepairTest` C01–C05 cover:

- non-empty proposal requires real effect application;
- multi-effect partial failure rolls back all effects;
- unsupported effect fails closed before writes;
- failure after effect/receipt staging rolls back both;
- identical retry replays, conflicting semantics fail closed.

**RESULT**

PASS. No false committed receipt and no committed subset of an admitted proposal.

---

## D. G28 → G29 receipt migration

**ROOT CAUSE**

The G28 receipt schema admitted only receipt version 1, while G29 writes version 2 with commit ordering. A naive schema replacement risked rejecting old history or losing legacy receipt semantics.

**FIX**

Receipt readiness performs a compatibility rebuild that preserves existing v1 rows and permits v1/v2 receipts. Legacy rows remain readable with unknown/not-recorded commit order; new receipts use v2 and receive deterministic commit order.

**TEST**

`Phase26To29PostAuditBlockerRepairTest.D01_real_g28_v1_schema_rebuild_preserves_history_and_allows_v2` creates a real G28 v1 table/row, runs the migration/readiness path, verifies preserved semantic/result fingerprints and null legacy commit order, verifies replay of the legacy transaction, then commits a new v2 receipt and verifies recovery ordering.

**RESULT**

PASS. G28 receipt history is preserved and G29 receipts operate on the upgraded schema.

---

## E. Recovery-reader implicit DDL

**ROOT CAUSE**

A recovery/read path that silently calls schema creation or migration mutates database metadata during what is supposed to be a read-only recovery inspection and can mask an unprepared database.

**FIX**

`TurnRecoveryReader` requires the receipt schema to be ready and does not install/create/migrate it implicitly. Missing schema fails closed with `SCHEMA_NOT_READY`.

**TEST**

`Phase26To29PostAuditBlockerRepairTest` E01/E02 compare `sqlite_master` and migration metadata before/after ready reads and verify that missing-schema construction fails without creating objects.

**RESULT**

PASS. Recovery reads are DDL/metadata-free.

---

## F. Concurrency / TOCTOU

**ROOT CAUSE**

Checking idempotency only before beginning the authoritative write transaction leaves a race in which two connections can both observe no receipt and both attempt the same gameplay effect.

**FIX**

TurnTransaction re-checks replay/idempotency after entering the transaction, and the receipt uniqueness/idempotency contract is the final serialized authority. Identical concurrent semantics reduce to one commit plus replay; conflicting semantics fail closed.

**TEST**

`Phase26To29PostAuditBlockerRepairTest` F01/F02 use two real SQLite connections against the same database for identical and conflicting identities/semantics.

**RESULT**

PASS. Identical concurrent attempts apply at most once; conflicting semantics do not create a second authoritative result.

---

## G. CampaignTruthChange integration

**ROOT CAUSE**

Campaign truth had to participate in the same typed canonical mutation pipeline as the other authoritative gameplay domains; a compile-time or applier gap would make canonical truth effects unsupported or bypass the single TurnTransaction authority.

**FIX**

`CampaignTruthChange` remains integrated in canonical change preflight/application and is applied through `CampaignTruthStore` inside TurnTransaction. No separate truth write authority was introduced.

**TEST**

`CampaignTruthChangeIntegrationTest` plus campaign-truth writer-matrix/multi-effect rollback coverage in `Phase26To29PostAuditBlockerRepairTest`.

**RESULT**

PASS. Compile integration remains closed and GREEN; no new concrete regression appeared.

---

## H. Ownership close/open integration

**ROOT CAUSE**

TurnTransaction already mapped admitted `OwnershipChange` through `OwnershipStore.transferShare`; the failure was not a second ownership authority. The historical Phase12 SQL contract was narrower than the Ownership model/store contract: legal pre-Phase30 transfers with no real Event Store event were blocked because the operation schema/close trigger effectively required an Event UID even though `OwnershipRecord.sourceEventUid` / `closedByEventUid` can legitimately be null when the event is not recorded.

**FIX**

The Phase12 ownership schema contract was repaired minimally:

- `OwnershipStore` remains the sole ownership authority;
- canonical TurnTransaction still uses the existing legal transfer API;
- operation source event can be null;
- legal ACTIVE → CLOSED temporal transition can retain null `closed_by_event_uid` when no real event exists;
- closure provenance, version/order/history/share/reference invariants remain enforced;
- existing historical operation rows are preserved by the compatibility upgrade;
- no Event UID, cause, or historical provenance is fabricated.

**TEST**

`Work023OwnershipCanonicalIntegrationTest` verifies:

- A owns the asset;
- canonical transfer legally closes A and opens B;
- source/closed event UID remains null when absent;
- ownership history remains internally consistent;
- receipt commits atomically;
- injected later failure rolls back the transfer, leaves A owner, leaves no B survivor, no operation survivor, and no committed receipt.

Ownership writer-matrix and broader ownership regressions were also run in the ordered gate.

**RESULT**

PASS. Ownership close/open semantics are legal, atomic, history-preserving, and do not invent provenance.

---

## Final A08 diagnosis — DevelopmentProject writer matrix

The exact rejecting trigger was `trg_p15_work_insert`. Every predicate was evaluated against the canonical `ProjectWorkRecord` produced by `CanonicalPlayerChangeApplier.applyProject`.

| Trigger predicate | A08 canonical value | Result |
|---|---|---|
| Latest project status is one of `PROTOTYPE`, `ACTIVE_WORK`, `STABILIZATION` | `PROTOTYPE` after legal `IDEA → REQUIREMENTS → PROTOTYPE` | PASS |
| Active actor reference exists in `ownership_party_registry` for exact actor kind+UID | canonical actor = `PLAYER:P1`; fixture initially registered only `CHARACTER:P1` | **FAIL — root cause** |
| `effective_order` is not before latest project status/created order | work `10`; latest status `3`; created `1` | PASS |
| Non-null financial transaction reference resolves and is not from the future | `financialTransactionUid = null` | PASS |
| Progress delta does not exceed remaining progress cap | `progressCapUnits = null`; delta `7`; current progress `0` | PASS |
| Progress addition cannot overflow `Long` | `7 <= Long.MAX_VALUE - 0` | PASS |

Additional inspected fields `result=SUCCESS`, `effortUnits=null`, `commandUid=CMD-PROJ`, `sourceEventUid=null`, and `startedOrder=null` do not violate `trg_p15_work_insert`; `startedOrder` is not a predicate of that trigger.

**A08 fix:** fixture only. `setupProject` now registers the actual canonical work actor prerequisite `OwnershipOwnerRef("PLAYER", "P1")` in addition to the existing `CHARACTER:P1` project initiator. Phase12 already defines `PLAYER` as a legal owner namespace. `CanonicalPlayerChangeApplier.applyProject`, `DevelopmentProjectStore`, Phase15 lifecycle/reference/progress triggers, progress caps, and ordering invariants were not weakened.

**A08 result:** PASS alone, PASS in Phase15/project regressions, PASS in the blocker suite, PASS in the full JVM suite.

---

## Final verdict

**FIX COMPLETE — READY FOR EXACT-SHA REVALIDATION**
