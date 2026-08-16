# TEST GM REPORT — WITCHER CAMPAIGN

REPORT TYPE: INFORMATIONAL / RUNTIME & ARCHITECTURE FINDINGS  
PRIORITY: P0–P2  
CAMPAIGN: NEW GAME — THE WITCHER  
PLAYER CHARACTER: SMAGI  
DATE: 2026-08-16

## 0. Repository / acceptance context

Repository: `piotreksmaga-art/rpg-os-android`  
Branch: `master`  
Last HEAD checked by TEST GM: `b9c97024fd95a5750d41730a78e73399a16dc33c`

**Important acceptance information:** Phase 21–25 are NOT ACCEPTED / NOT COMPLETE for TEST GM runtime use in this report context. Presence of implementation, tests, audit documents, revalidation documents, or commits describing completion must not be interpreted by TEST GM as formal phase acceptance.

For mechanics belonging to Phase 21–25, TEST GM must continue to use ARCHITECTURE FALLBACK according to MASTER Architecture and previously accepted invariants until formal coordinator acceptance.

No Phase 21–25 implementation was intentionally treated as accepted runtime during the findings described below.

## 1. P0 — Authoritative campaign fact / NPC identity drift

**Severity:** P0 — potential long-campaign state corruption.

Observed case: during the campaign the player asked whether GM still remembered the names of the mother and sister. TEST GM answered:

- Mother: Alina
- Older sister: Mira

The currently available authoritative campaign state did not provide sufficiently strong evidence to confirm those names with that certainty. TEST GM therefore potentially converted uncertain contextual recall into campaign fact.

Expected principles:

- `FACT != BELIEF != NARRATIVE`
- `AI OUTPUT != COMMITTED REALITY`

Expected behavior if an NPC identity/property cannot be recovered from authoritative state:

1. retrieve authoritative state if available;
2. distinguish confirmed fact from uncertain recollection;
3. never invent a missing permanent value merely to preserve narrative fluency;
4. ask for recovery/confirmation if necessary.

Recommendation: add this scenario as a future regression/acceptance case for persistent campaign facts and NPC identity continuity. A long-running campaign must recover NPC identity using stable state rather than LLM conversational recollection.

## 2. P1 — Discovery / evidence / hypothesis / capability

**Severity:** P1 — important for emergent abilities and discovery gameplay.

Smagi demonstrated several magical effects. Earlier cases included direct movement of objects and manipulation of a leaf associated by the player with imagined wind. Later controlled observation involved intent to create/imagine a local vortex of air. A feather and another independent lightweight object reacted, and local air movement was observed. A subsequent controlled test removed the primary target object; Smagi focused on an empty area and several lightweight surrounding objects reacted in a pattern consistent with local air movement.

TEST GM intentionally did not convert this directly into `Smagi has Air Manipulation skill`.

Observed lifecycle:

`PLAYER INTENT -> OBSERVATION -> HYPOTHESIS -> CONTROLLED TEST -> STRONGER EVIDENCE`

but not automatically:

`CONFIRMED PERMANENT CAPABILITY`

Expected principles:

- observation != hypothesis;
- hypothesis != discovered skill;
- successful manifestation != mastered technique.

Recommendation: future RPG OS discovery provenance should be able to represent intermediate states such as `OBSERVED`, `SUSPECTED`, `SUPPORTED`, `CONFIRMED`, `TRAINED`, `MASTERED`, or an architecture-consistent equivalent. Binary known/unknown capability would lose important campaign information.

## 3. P1 — Knowledge != practice != progress != mastery

### Magic

NPC instructor Celene taught Smagi a conceptual control model:

`START -> PATH -> TARGET`

Smagi successfully applied it to `A -> B` and later `A -> B -> C`. This is evidence of understanding and practical application, but does not automatically justify a mastered permanent technique.

### Reading

Smagi spent substantial in-world time learning to read with his father. The performed training must not be lost, but TEST GM did not have sufficient accepted mechanics to arbitrarily declare `READING +1` or invent a mastery value.

Expected principle:

`EXPOSURE / LEARNING != PRACTICE != PROGRESS != MASTERY`

Recommendation: preserve this sequence as a future progression regression case.

## 4. P1 — Pending state must not become committed reality

Smagi was evaluated as a possible candidate for magical education. His father authorized forwarding the evaluation/documentation for further consideration.

Committed fact:

- permission was given to forward candidacy/documentation.

Not committed:

- admission to Ban Ard;
- tuition/cost;
- patronage;
- financing;
- travel requirement;
- transfer of guardianship;
- formal enrollment;
- beginning magical education.

Conceptual state transition:

`REQUESTED -> AUTHORIZED -> PENDING -> ACCEPTED / REJECTED -> COMMITTED`

Exact runtime representation may differ. The invariant is that narrative inference must not skip legal state transitions.

Risk: without explicit pending/committed separation an LLM GM may later remember `Smagi was being considered for Ban Ard` as `Smagi was accepted to Ban Ard`, creating permanent campaign divergence without legal cause.

## 5. P2 — NPC knowledge provenance

Celene could legitimately state an observation such as local air movement consistent with Smagi's intent was observed. She could not legitimately know from that alone that Smagi possesses confirmed elemental air specialization.

Likewise Roderik could discuss submitting Smagi's candidacy but could not promise unknown admission, financing, or school conditions.

Expected principle:

`GM KNOWLEDGE != NPC KNOWLEDGE`

NPC conclusions should depend on what the NPC observed, was told, documents received, expertise, and uncertainty of evidence.

Recommendation: preserve provenance of important NPC knowledge where practical, especially for mysteries, research, politics, and discovery systems.

## 6. P1 — Acceptance authority must override implementation presence

During the session new repository commits appeared concerning Phase 21–25. Repository contained Phase-25 player snapshot profiles and Phase-21–25 audit/revalidation material.

Project owner clarification at report time: Phase 21–25 were not accepted.

Required invariant:

- `IMPLEMENTED != ACCEPTED`
- `TESTED != ACCEPTED`
- `DOCUMENTED AS COMPLETE != FORMALLY ACCEPTED`

Only the project's authoritative acceptance mechanism may promote a phase into runtime mechanics available to TEST GM.

## 7. Positive observation — architecture fallback behavior

Conservative fallback behavior prevented unsupported permanent state in multiple cases:

- no arbitrary numerical stat gain after training;
- no automatic mastery after one successful exercise;
- no automatic Air Manipulation skill after an interesting experiment;
- no automatic Ban Ard admission;
- no invented financing terms;
- uncertain magical sensitivity remained an observation rather than confirmed capability.

This supports the architecture's conservative permanence rules during natural GM play. The NPC-name incident demonstrates that narrative continuity remains a high-risk boundary.

## 8. Recommended future test cases

**TEST CASE A — NPC identity continuity:** NPC introduced early in a long campaign; hundreds of turns later ask GM for NPC name. Expected: retrieve authoritative identity, never regenerate it.

**TEST CASE B — unexplained supernatural observation:** character accidentally produces unexplained effect. Expected: observation recorded without automatic skill creation.

**TEST CASE C — repeated controlled experiment:** evidence can strengthen without automatically creating mastery.

**TEST CASE D — instruction + successful exercise:** knowledge/practice may change according to legal mechanics; mastery is not invented.

**TEST CASE E — pending application:** application to organization/school remains distinct from acceptance.

**TEST CASE F — implementation without acceptance:** code for a NOT ACCEPTED phase exists and tests pass. Expected: TEST GM still uses ARCHITECTURE FALLBACK until formal acceptance.

## 9. Priority summary

P0:

- authoritative campaign fact / NPC identity continuity.

P1:

- discovery / evidence / hypothesis separation;
- pending vs committed reality;
- knowledge / practice / progress / mastery separation;
- formal acceptance authority over implementation presence.

P2:

- NPC knowledge provenance.

## 10. TEST GM boundary compliance

No runtime fix was attempted. No Kotlin/runtime/database/schema/migration/world-pack/roadmap/acceptance-record modification was performed by TEST GM. No acceptance status was changed.

Any future written TEST GM report must remain exclusively inside:

`docs/test-gm/`
