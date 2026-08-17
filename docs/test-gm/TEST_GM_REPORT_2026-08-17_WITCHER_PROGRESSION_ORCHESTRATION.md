# TEST GM REPORT — WITCHER CAMPAIGN

**REPORT TYPE:** INFORMATIONAL / RUNTIME & ARCHITECTURE FINDINGS  
**CAMPAIGN:** NEW GAME — THE WITCHER  
**PLAYER CHARACTER:** SMAGI  
**DATE:** 2026-08-17  
**BOUNDARY:** TEST GM evidence only; this report does not modify runtime, roadmap, acceptance status, schema, migrations, or canonical architecture.

## 1. P0 — Progression pipeline requires deterministic execution

During play the campaign accumulated clear causal evidence from meaningful training: herbalism, reading, mathematics, and increasingly difficult magical experiments.

The GM initially failed to settle progression even though training had occurred. Progression was awarded only after player intervention.

After settlement the session state included changes such as:

- Magic Control 9 → 11
- Concentration 17 → 18
- Willpower 16 → 17
- Controlled shaping 12 → 18/100
- Herbalism 8 → 10/100
- Reading 9 → 10/100
- new Mathematics 3/100

The important architectural finding is not that these exact numbers are mechanically authoritative. The finding is that TEST GM was still partially acting as a manual ProgressionEngine by deciding numerical +X values.

Desired direction:

`completed meaningful training → structured causal evidence → ProgressionEngine resolution → validated ChangeSet → committed state → rebuilt projection/snapshot`

GM should provide evidence and interpretation, not arbitrarily choose a permanent numerical progression result when a mechanical resolution path exists.

## 2. P1 — Discovery/evidence/hypothesis separation behaved well

The player explored magic experimentally rather than declaring elemental skills. The sequence included air, water, earth, manipulation of an existing flame, and later ignition without a second flame.

The session correctly avoided converting a single manifestation directly into a permanent skill such as `Water Magic` or `Earth Magic`.

This supports preserving distinctions such as:

`observation != hypothesis != classified capability != trained skill != mastery`

The Air/Water/Earth/Fire sequence should be retained as a future regression scenario for capability/discovery classification.

## 3. P1 — Capability classification remains intentionally unresolved

Observed effects were compatible with multiple explanations, including several elemental affinities, one more general manipulation capability, or another world-specific mechanism.

The system should not force classification before sufficient evidence and world rules justify it.

## 4. P1 — Manipulation and creation were correctly separated

Directing an existing flame onto a wick established evidence of flame manipulation and ignition of the wick, but did not prove that fire itself had been transferred or created.

A later experiment without a second flame produced a progression of smoke → ember → glowing wick → sustained flame. This supported only a first controlled evidence of ignition/initiation, not an unrestricted ability to create fire.

This epistemic conservatism should be preserved.

## 5. P1 — Failure → adaptation → breakthrough needs structural evidence

The fire experiment produced a useful sequence:

`failure → method adjustment → partial observable effect → repetition → success`

This is more informative than simple repetition and should eventually be representable as structured progression/discovery evidence. Novelty, failed attempts, adaptation and successful refinement should influence mechanics only through architecture-approved deterministic rules rather than GM fiat.

## 6. P2 — Emergent skill creation

Mathematics emerged naturally from play: the player requested instruction, performed counting/addition/subtraction/grouping exercises, and later applied them during herbalism.

This is a useful regression case for:

`player intent → world interaction → training evidence → competency emergence`

A new skill should arise only through a legal architecture-defined mechanism, not because narration alone names it.

## 7. P0 — Progression/state/projection synchronization

The TEST GM snapshot can lag behind narration when progression resolution and snapshot update are separate manual operations.

Desired logical ordering:

`resolve progression → validate/commit authoritative state → rebuild derived projection → update test snapshot`

A stale or failed derived snapshot must never become authority or overwrite committed campaign state.

## 8. Primary architectural finding

The largest observed gap is the orchestration boundary between well-collected narrative/training evidence and deterministic numerical progression.

TEST GM can already collect evidence such as:

- duration
- difficulty
- repetition
- novelty
- mentor
- failure
- adaptation
- successful outcome

But the GM must not become the numerical progression calculator.

**Target invariant:**

`AI/GM interprets and supplies structured evidence; engine calculates; validators decide legality; transaction/commit determines reality; projections display the committed result.`

## 9. Coordinator priorities from this playtest

- **P0:** automatic/mandatory progression resolution for completed meaningful training blocks when an accepted mechanical path exists.
- **P0:** atomic authoritative progression commit semantics followed by deterministic projection/snapshot rebuild; derived state must remain rebuildable.
- **P1:** preserve discovery-before-classification and epistemic safeguards.
- **P1:** retain failure → adaptation → breakthrough as structured evidence for future mechanics.
- **P1:** retain Air/Water/Earth/Fire experiments as a regression fixture for capability classification.
- **P2:** support architecture-legal emergent skill creation, with Mathematics as a test scenario.

## 10. Scope decision

These findings do **not** authorize changing the currently active Phase 26–29 implementation scope. They are reference material for the coordinator and for the later phase(s) owning GM/evidence orchestration, progression invocation, discovery/knowledge handling, and derived-context synchronization.

No acceptance status is changed by this report.
