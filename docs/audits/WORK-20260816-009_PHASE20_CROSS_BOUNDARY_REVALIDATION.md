# WORK-20260816-009 — Phase 20 Exact-SHA Cross-Boundary Revalidation

## Audit identity

- Work ID: `WORK-20260816-009`
- Role: `CHAT-5 — independent cross-boundary / source-of-truth revalidator`
- Mode: `READ-ONLY`
- Repository: `piotreksmaga-art/rpg-os-android`
- Exact runtime SHA audited: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- Previous candidate: `a09e22e6505be7849e34fbd27faf2cc36d5bceef`
- Previous CHAT-5 audit: `WORK-20260816-006` — PASS on `a09e22e...`
- Targeted fix: `WORK-20260816-007`
- Current master immediately before report write: `b2b2b8582a3258a988e607ec92adffdfe4cfab18`
- Exact CI: `Validate RPG OS ALPHA` run `#578`, ID `31961047982`, `head_sha=38dafe5cc48c87f16218e346d9c0f9a96b6cee50`, `completed / success`

This report revalidates only the exact runtime SHA above. Later documentation/test-GM commits are context/evidence only.

## Exact runtime diff

Comparison `a09e22e... -> 38dafe5c...` contains one production runtime modification and one new regression test, plus later documentation accumulated between the historical candidate and final candidate:

- `app/src/main/java/com/rpgos/app/ProgressionEngine.kt` — targeted canonical ordering fix;
- `app/src/test/java/com/rpgos/app/Phase20FactorCanonicalizationRegressionTest.kt` — targeted regression coverage.

The runtime fix commit `f514ddd43f003f526225f4cad2111682fef51594` changes only `ProgressionEngine.kt` (`+20/-5`). The final candidate commit `38dafe5...` adds only the regression test (`+87`). No schema, migration, persistence, World Pack, PlayerChangeSet schema, transaction, frontend, or other runtime file changed as part of the targeted fix.

Current master is two documentation-only commits ahead of the exact runtime candidate:

- `docs/audits/WORK-20260816-007_PHASE20_DETERMINISM_FIX.md`;
- `docs/test-gm/TEST_GM_FINDING_2026-08-16_WITCHER_NEW_CAMPAIGN.md`.

Therefore no newer runtime is substituted into this verdict.

## Source-of-truth revalidation

Previous conclusion remains unchanged: RPG OS still has one authoritative current-state path for player progression.

The targeted change adds `ProgressionCalculationFactorCanonicalOrder` and routes three existing factor-ordering sites through it. It does not add any persistence or mutation capability.

- `ProgressionEngine` remains a deterministic proposal calculator.
- Current Stat/Skill/Technique state remains owned by existing typed state stores.
- `ProgressionResult` and `ProgressionGrant` remain derived proposal objects.
- `ProgressionLedgerIntentPayload` remains proposal/evidence carried by the existing `PlayerLedgerIntent` envelope.
- No progression ledger store/table/writer was added.
- No direct progression writer was added.
- No second `PlayerDomainEngine` or parallel command-resolution path was added.
- No alternative `PlayerChangeSet` was added.

Result: **PASS — no duplicate current-state authority introduced.**

## Identity / provenance revalidation

### New canonical ordering

The centralized comparator orders `ProgressionCalculationFactor` by:

1. `factorKindUid`;
2. `evidenceUid`;
3. `sourceValue.scaledUnits`;
4. `appliedFactor.scaledUnits`;
5. full deterministic factor `fingerprint()`.

The factor fingerprint itself depends only on:

- factor kind UID;
- evidence UID;
- source fixed-point units;
- applied fixed-point units;
- `ProgressionNumericPolicy.POLICY_UID`;
- `ProgressionNumericPolicy.POLICY_VERSION`.

It does not depend on list position, object identity, runtime hash code, UUID, clock, randomness, progression/result UID, comparator output, or mutable external state.

### Recursion / circularity

No recursion or circular identity dependency exists.

`fingerprint()` does not call the canonicalizer and does not depend on `progressionUid`, `grantUid`, `ledgerIntentUid`, `changeUid`, `inputFingerprint`, `computationFingerprint`, or `resultFingerprint`. The comparator may call the factor fingerprint, but the fingerprint is a leaf calculation over factor fields and constants.

### Collection-order independence

All three factor canonicalization sites now use the same centralized rule:

- `ProgressionStimulus.calculationFactors`;
- `ProgressionEvaluationInput.calculationFactors`;
- assembled factor list inside `ProgressionEngine.evaluate(...)`.

Therefore insertion order is not authoritative. A semantic multiset of factors canonicalizes consistently before entering the downstream identity chain.

### Stable-ID chain

The new regression test exercises permutations containing two factors that previously tied on `(factorKindUid, evidenceUid)` while differing in numeric semantics. It asserts stable equality for:

- arithmetic grant;
- `inputFingerprint`;
- `progressionUid`;
- computation fingerprint;
- computation UID;
- `grantUid`;
- causal `changeUid`;
- `ledgerIntentUid`;
- `resultFingerprint`;
- complete `ProgressionResult`.

This is the required identity chain for Phase-20 proposal determinism.

### Compatibility / identity churn

The old comparator's first two keys remain the first two keys of the new comparator. Therefore any factor sequence previously unambiguous under `(factorKindUid, evidenceUid)` keeps the same relative order and therefore the same downstream identities.

Only previously ambiguous ties — same kind UID and same evidence UID but distinct semantic numeric values — acquire a deterministic relative order. Exact semantic duplicates remain interchangeable because all semantic keys, including fingerprint, are equal.

No broad identity churn was found.

### Future retry/idempotency

The change improves future retry/idempotency feasibility because semantically identical factor multisets no longer produce different proposal identities solely from insertion order. It does not prematurely implement transaction-level or commit-level idempotency.

Result: **PASS.**

## Phase-19 boundary revalidation

The targeted diff does not modify `PlayerDomainEngine`, `WorldRuleProvider`, World Pack authority resolution, effect snapshots, reference closure, or proposal assembly.

The previously passed resolution ordering therefore remains:

`COMMAND_PRECHECK`
`-> base resolution`
`-> progression augmentation`
`-> augmented draft`
`-> augmented reference closure`
`-> one final DRAFT_EFFECT_CHECK`
`-> PlayerChangeSet proposal`

The canonicalization occurs wholly inside progression factor preparation/evaluation and does not rebind or re-read World Pack authority.

`ONE RESOLUTION = ONE PINNED WORLD PACK AUTHORITY` remains intact.

Result: **PASS — no Phase-19 regression.**

## Core / World Pack revalidation

The new canonicalizer is Core-owned, generic, and parameterized only by generic progression-factor fields and the Core numeric policy identity.

No Naruto-specific logic, Bleach-specific logic, World-Pack-specific comparator, provider-owned identity service, or World Pack progression orchestrator was introduced.

World Pack binding semantics are unchanged.

Result: **PASS.**

## Phase ownership revalidation

No new scope leakage was introduced.

- Phase 21: no diminishing returns, novelty/adaptation, passive progression, passive scheduler, time-skip gains, fatigue-recovery loop.
- Phase 22: no global no-retrogression engine and no second global PlayerChangeSet validator.
- Phase 23: no committed unified ledger, no authoritative provenance database, no ledger persistence.
- Later phases: no TurnTransaction, Event Store authority, snapshot authority, commit idempotency, retry ledger, or replay system.

The added canonicalizer is properly Phase-20 deterministic proposal machinery.

Result: **PASS.**

## Legacy / compatibility

The comparator change does not read, migrate, reinterpret, or materialize legacy XP/mastery/progression history. No old record becomes historical truth because of this fix.

Ordinary previously unambiguous Phase-20 identities remain stable. Only the previously undefined ordering of semantic ties is corrected.

Result: **PASS.**

## Previous LOW finding — P20-CB-01

`P20-CB-01` remains unchanged:

`ProgressionStimulus.evidenceRefs` participate in reference closure but are not copied into `ProgressionEvaluationInput` or `ProgressionLedgerIntentPayload`.

The targeted canonicalization fix neither fixes nor worsens this boundary. No new evidence raises its severity.

Status: **LOW / DEFERRED — unchanged.**

Phase 23 should still make an explicit provenance decision without retroactively fabricating historical authority.

## Focused cross-boundary delta matrix

| Area | Previous `a09e22e` verdict | Change in `38dafe5c` | Authority impact | Regression? | Verdict |
|---|---|---|---|---|---|
| ProgressionEngine | PASS; proposal-only central calculator | Factor ordering centralized | None; no writer/store added | No | PASS |
| ProgressionCalculationFactor | Deterministic fields but incomplete ordering tie | Total semantic ordering added | Improves deterministic proposal identity | No | PASS |
| Stable IDs | PASS except ordering ambiguity identified by separate audit | Ambiguous factor permutations now converge | Positive; insertion order removed from identity | No | PASS |
| ProgressionLedgerIntent | Proposal/evidence only | Payload/authority model unchanged | None | No | PASS |
| PlayerChangeSet | Existing proposal boundary | Unchanged | None | No | PASS |
| WorldRuleProvider | Final legality authority sees augmented draft | Unchanged | None | No | PASS |
| World Pack binding | One pinned binding per resolution | Unchanged | None | No | PASS |
| Legacy | Evidence/compatibility only; no fabricated history | Unchanged | None | No | PASS |
| Phase 21 boundary | No active Phase-21 mechanics | Unchanged | None | No | PASS |
| Phase 22 boundary | No second global invariant authority | Unchanged | None | No | PASS |
| Phase 23 boundary | Ledger intent only, no committed ledger | Unchanged; P20-CB-01 still LOW | None | No | PASS |

## Findings / severity

- BLOCKER: none.
- HIGH: none.
- MEDIUM: none.
- LOW: `P20-CB-01` unchanged/deferred from WORK-20260816-006.
- DEFERRED / EXPECTED: transaction-level idempotency, committed provenance/ledger integration, global invariants, passive/diminishing progression, Event Store and snapshots remain future-phase responsibilities.

No new cross-boundary regression finding was identified.

## CI evidence

Exact candidate CI independently verified:

- workflow: `Validate RPG OS ALPHA`;
- run: `#578`;
- ID: `31961047982`;
- `head_sha`: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`;
- status: `completed`;
- conclusion: `success`.

The final candidate commit contains the targeted factor-permutation regression test.

## Final verdict

**PASS — CROSS-BOUNDARY REVALIDATION PASSED — READY FOR COORDINATOR ACCEPTANCE REVIEW**

This PASS is explicitly and exclusively bound to exact runtime SHA:

`38dafe5cc48c87f16218e346d9c0f9a96b6cee50`

This report does **not** declare `PHASE 20 ACCEPTED`; only the coordinator may issue global acceptance.
