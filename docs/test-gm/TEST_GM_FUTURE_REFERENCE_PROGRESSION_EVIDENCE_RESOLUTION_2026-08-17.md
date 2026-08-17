# TEST GM FUTURE REFERENCE — PROGRESSION EVIDENCE → MECHANICS RESOLUTION

**Date:** 2026-08-17  
**Source:** TEST GM — The Witcher / Smagi campaign  
**Type:** INFORMATIONAL / FUTURE REFERENCE / DEFERRED REQUIREMENT  
**Runtime context:** Phase 1–25 formally ACCEPTED  
**Current development context:** Transactional Campaign Core / Phase 26–29 work  
**Status:** TRACKED — NOT A CURRENT PHASE 26–29 BLOCKER

---

## 1. Purpose

Preserve a real-play finding concerning the boundary between structured causal training evidence and the accepted deterministic progression mechanics.

This document is reference material for future architecture/audit work. It does **not** reopen accepted Phase 20–25, does **not** authorize a runtime change, and does **not** alter the roadmap or acceptance state.

---

## 2. Observed TEST GM case

During the Witcher campaign, TEST GM accumulated meaningful causal evidence for multiple training blocks, including:

- duration / effective training time;
- method;
- mentor involvement;
- repetitions and attempts;
- successes and failures;
- novelty;
- adaptation / method changes;
- observable outcomes;
- fatigue;
- progressively harder magical experiments;
- reading, herbalism and mathematics practice.

When TEST GM attempted to avoid arbitrary manual `+X` progression and use the actual accepted progression implementation, an execution gap became visible.

`ProgressionEngine` can deterministically evaluate resolved progression input, but the gameplay evidence available to the GM does not by itself provide a legal deterministic method for deriving all required resolved numeric inputs such as `effortUnits` and resolved factor values.

The practical pipeline observed was:

```text
GAMEPLAY / TRAINING
        ↓
STRUCTURED CAUSAL EVIDENCE
        ↓
[ FUTURE MECHANICS / EVIDENCE RESOLUTION LAYER ]
        ↓
effortUnits + resolved factors
        ↓
ProgressionEngine
        ↓
ProgressionGrant / PlayerChangeSet
        ↓
transaction / committed state
        ↓
projection / snapshot
```

The missing middle layer must not be replaced by GM-selected arbitrary numbers, because that would merely move GM fiat from the final `+X` into progression-engine inputs.

---

## 3. Architectural interpretation after roadmap/master review

After review of the canonical RPG OS roadmap and MASTER Architecture, this finding is **not classified as a blocker for the current Phase 26–29 work**.

The accepted architecture already establishes the target principle:

> AI describes training; ProgressionEngine calculates the result.

The MASTER progression model expects causal dimensions including source, duration, intensity, difficulty, mentor, environment, method, talent, potential, fatigue, injury, current level, quality, novelty, adaptation, diminishing returns, modifiers and result.

However, later roadmap stages still include the higher-level runtime responsible for understanding a turn and integrating mechanics, notably:

- Phase 43 — Intent Parser;
- Phase 44 — Turn Planner;
- Phase 45 — Context Builder;
- Phase 49 — Structured GM Output contract;
- **Phase 50 — Mechanics Resolution integration**;
- Phase 51 — Consistency Validator;
- Phase 52 — Counterfactual Guard;
- Phase 53 — Repair Pass;
- Phase 54 — committed narrative only after valid transaction.

The MASTER also explicitly states that the Intent Parser recognizes intent structure but does not resolve mechanics, while the Turn Planner selects the required mechanics/repositories.

Therefore an `Evidence → Progression Inputs` resolver should **not** be inserted ad hoc into accepted Phase 20/21 or into current Transactional Core work without a future repository-first architecture audit.

---

## 4. Correct classification

Previous provisional wording such as **P0 blocker** should be interpreted narrowly as a blocker for *full TEST GM automatic progression use today*, not as a blocker for the project's current implementation sequence.

Canonical tracking classification for this document:

**P0 TEST-GM FUNCTIONAL GAP / FUTURE-PHASE REQUIREMENT — DEFERRED, TRACKED**

Meaning:

- does not block Phase 26–29;
- does not reopen Phase 20–25 acceptance;
- does not imply a defect in the deterministic `ProgressionEngine` itself;
- must be preserved for future audit/design of mechanics-resolution layers;
- must not be silently solved by GM-authored numeric inputs.

---

## 5. Future requirement to evaluate

A future repository-first audit should determine whether RPG OS needs a dedicated Core component such as a conceptual:

```text
TrainingEvidenceResolver.evaluate(evidence)
    -> ProgressionResolvedEvidence
```

or whether the responsibility belongs inside a more general Phase-50 Mechanics Resolution subsystem.

No class/API name in this report is normative.

A candidate normalized evidence contract may need fields conceptually similar to:

- duration;
- effective duration;
- attempt count;
- success count;
- failure count;
- difficulty band;
- mentor quality;
- method-change count;
- novelty class;
- fatigue state;
- injury state;
- environment quality;
- outcome class.

A deterministic resolver may then derive, where applicable:

- effort units;
- intensity;
- difficulty;
- quality;
- novelty;
- adaptation;
- repetition;
- fatigue impact;
- environment factor;
- other versioned progression factors.

Any such resolver should have deterministic/versioned identity such as policy UID/version, dependency versions and stable fingerprints sufficient for replay and auditability.

This is a **future design requirement**, not an approved implementation specification.

---

## 6. Critical epistemic boundary

Future mechanics resolution must **not** collapse discovery/classification into progression quantity.

The following distinction remains mandatory:

```text
OBSERVATION
!= HYPOTHESIS
!= CONFIRMED CAPABILITY
!= SKILL
!= TECHNIQUE
!= MASTERY
```

A progression evidence resolver may determine the legal amount/type of progression for an already legal progression target. It must not automatically create a new elemental affinity, skill, technique, innate ability or other permanent classification merely because an experiment succeeded.

The Witcher campaign's Air / Water / Earth / Fire experiments should remain useful regression fixtures for this boundary.

---

## 7. TEST GM fallback until future mechanics exist

Until an accepted runtime provides the missing resolution step, TEST GM should use conservative fallback:

```text
ProgressionEngine: AVAILABLE
Causal evidence: PRESERVE
Unresolved numeric progression inputs: DO NOT INVENT
ProgressionGrant: DO NOT FABRICATE
Permanent +X: DO NOT FABRICATE
Evidence already pending: DO NOT DOUBLE-COUNT
```

Training that occurred in the fiction must not disappear merely because it cannot yet be numerically resolved. Preserve the causal evidence/backlog so a future legal resolver can consume it without reconstructing or inventing history.

---

## 8. Future acceptance/regression scenarios

When the relevant future phases are audited, reuse at least these real-play cases:

1. **Reading training** — substantial time and repeated instruction with no arbitrary mastery award.
2. **Herbalism** — mentor-guided practical work with observable successful outcomes.
3. **Mathematics emergence** — player intent → instruction → practice → practical application → potential competency emergence.
4. **Magic experimentation** — repeated controlled tests across Air / Water / Earth / Fire without premature capability classification.
5. **Failure → adaptation → breakthrough** — failed attempts followed by method change, partial evidence, repetition and success.
6. **Backlog safety** — unresolved evidence survives save/load/restart and cannot be lost or counted twice.
7. **Replay determinism** — identical committed evidence plus identical policy/dependency versions yields identical resolved progression inputs and grant identity.
8. **No GM numeric fiat** — the GM supplies structured observations/evidence, not arbitrary `effortUnits`, multipliers or final `+X` values.

---

## 9. Recommended future audit point

Primary revisit window:

**Phase 43–50 architecture/pre-implementation audit**, with special attention to **Phase 50 — Mechanics Resolution integration**.

Also cross-check later integration with:

- TurnTransaction / committed reality;
- Event Store / causal provenance;
- Time Skip active/passive progression;
- snapshots/projections;
- GM structured output;
- WorldRuleProvider-specific progression semantics.

The audit should decide ownership before implementation so RPG OS does not end up with both a special training resolver and a competing general mechanics resolver.

---

## 10. Boundary compliance

This report is informational only.

It does **not**:

- modify runtime;
- modify schema/migrations;
- modify tests;
- modify canonical architecture;
- modify roadmap status;
- modify acceptance records;
- declare a new phase;
- reopen Phase 20–25;
- block current Phase 26–29 work.

It exists solely as future TEST GM reference evidence under `docs/test-gm/`.
