# TEST GM SNAPSHOT — WITCHER / SMAGI

Date: 2026-08-17
Campaign: NEW GAME — THE WITCHER
Player Character: Smagi
Scope: `docs/test-gm/**` only
Status: TEST SESSION SNAPSHOT / DERIVED PRESENTATION / NON-AUTHORITATIVE

## 0. Snapshot semantics
CharacterPanelSnapshot is treated as a rebuildable presentation/projection, not authoritative truth. Architecture basis: Authoritative Player State + Derived Values + Runtime State + Ledger Summaries. TEST GM may synthesize missing fallback inputs/values from project architecture, but every such element must be explicitly provenance-marked and must not masquerade as accepted-runtime output.

Preserve: `FACT != BELIEF != NARRATIVE`, `AI OUTPUT != COMMITTED REALITY`, `IMPLEMENTED != ACCEPTED`, `TESTED != ACCEPTED`.

### Provenance classes used in this snapshot
- `RUNTIME_RECONCILED` — previously reconciled TEST SESSION numeric state / accepted presentation basis.
- `ARCHITECTURE_DERIVED` — calculated directly from known facts using architectural rules.
- `ARCHITECTURE_SYNTHETIC` — missing numeric/mechanical value conservatively created by TEST GM because architecture defines the concept but accepted runtime does not currently provide the full resolver.
- `OBSERVATION` — played/observed campaign fact.
- `HYPOTHESIS` — interpretation not yet classified as capability/skill/technique.

Architecture rule for synthetic values: time alone is not power. Synthesis considers duration, difficulty, mentor, environment, method, repetition, novelty, failures, adaptation, outcome, fatigue, current level, quality and diminishing-return intent. Synthetic values are TEST GM playtest projections and are migration/reconciliation candidates if a future accepted resolver becomes available.

## 1. Identity / runtime presentation
- Name: Smagi
- Birth year: 1173
- Age: 6
- Universe: The Witcher
- Campaign date/time: 1179-08-23 06:27
- Location: family home
- Runtime condition: rested after sleep
- Current scene: waking after an intensive 22 August training/education day
- Education thread: Ban Ard — `AUTHORIZED FOR FURTHER CONSIDERATION / PENDING RESPONSE`

# 2. PLAYER PANEL — FULL / PROGRESSION

## 2.1 Stats
Scale: 0–100.

| Stat | RUNTIME_RECONCILED basis | TEST GM current projection | Provenance |
|---|---:|---:|---|
| Strength | 7 | 7 | RUNTIME_RECONCILED |
| Endurance | 9 | 9 | RUNTIME_RECONCILED |
| Dexterity | 11 | 11 | RUNTIME_RECONCILED |
| Speed | 10 | 10 | RUNTIME_RECONCILED |
| Perception | 15 | 15 | RUNTIME_RECONCILED; no sufficient new physical/perceptual progression evidence |
| Intelligence | 16 | 17 | ARCHITECTURE_SYNTHETIC — sustained mathematics, reading comprehension, herbal classification and self-designed experiments |
| Willpower | 16 | 17 | ARCHITECTURE_SYNTHETIC — repeated failure/adaptation cycles and sustained deliberate practice |
| Concentration | 17 | 19 | ARCHITECTURE_SYNTHETIC — long deliberate-focus blocks, closed-eye control, start/stop discrimination, reading and mathematics |
| Magic Control | 9 | 12 | ARCHITECTURE_SYNTHETIC — repeated multi-material control, range refinement, complex shaping, ignition refinement, interoceptive control work |

**Magical potential:** EXISTS; magnitude `UNKNOWN / NOT ASSESSED`. No synthetic numeric potential is created because evidence describes current performance rather than long-term ceiling.

### Synthetic stat delta provenance
The fallback projection deliberately uses small integer changes despite very high training volume. This is a conservative diminishing-return interpretation: 21–22 August contained many hours of practice, but fatigue, beginner instability, repeated failures and overlapping training targets prevent converting raw time directly into large permanent stat growth.

## 2.2 Skills / competencies
Scale: 0–100 where a numeric projection is meaningful.

| Skill / competency | RUNTIME_RECONCILED basis | TEST GM current projection | Provenance |
|---|---:|---:|---|
| Reading | 9 | 14 | ARCHITECTURE_SYNTHETIC |
| Herbalism | 8 | 13 | ARCHITECTURE_SYNTHETIC |
| Controlled shaping of magical manifestation | 12 | 21 | ARCHITECTURE_SYNTHETIC |
| Mathematics | BEGINNER / unresolved | 7 | ARCHITECTURE_SYNTHETIC |

### Reading — evidence behind 14/100
- repeated evening literacy practice;
- 22 Aug 13:04–16:42 difficult herbal text with active decoding, questions after independent attempt, rereading and recall;
- 22 Aug 18:37–20:06 book about Ban Ard, sentence rereading and own-word summaries;
- familiar words increasingly recognized without letter-by-letter decoding;
- unfamiliar text still slow.

### Herbalism — evidence behind 13/100
- repeated identification/sorting/preparation;
- practical mentoring from mother;
- multi-feature identification rather than one visual cue;
- observed mistake on a similar plant and self-correction using stem/smell;
- integration of written herbarium knowledge with physical specimens and recall tests.

### Controlled shaping of magical manifestation — evidence behind 21/100
- stable basic bladder forms already established;
- animal-form progression: fish -> snake -> bird -> quadruped;
- failure of part-by-part control followed by holistic-form adaptation;
- repeatable water transfer and range extension work;
- temporary soil barrier refinement;
- prepared-wick ignition and existing-flame size modulation;
- interoceptive start/stop practice and partially blinded control attempts.

This remains ONE broad TEST-GM competency projection. It does **not** classify Air, Water, Earth and Fire as four skills or affinities.

### Mathematics — evidence behind 7/100
- concrete counting/addition/subtraction;
- grouping and intuitive division;
- extended mentored 22 Aug block;
- examples solved include 18 -> 6 equal groups -> 3 each, 3×4 -> 12, 12 grouped by 3 -> 4 groups;
- increasing mental calculation without physical aids;
- larger operations still require deliberate counting.

## 2.3 Confirmed observations vs classifications
### OBSERVATION
- deliberate household-scale effects involving air, water, loose earth and flame;
- repeatable water transfer A -> B;
- temporary earth barrier creation/refinement;
- prepared-wick ignition without a second immediate flame source;
- enlargement/reduction of an existing candle flame;
- complex contained-air shaping;
- repeatable subjective internal difference associated imperfectly with active manifestation.

### HYPOTHESES — unresolved
- four elemental affinities;
- one broad manipulation/control faculty;
- another common magical mechanism;
- internal sensation as a precursor to a future magic-sensing/control capability.

### NOT YET LEGALLY CLASSIFIED
- Water Magic skill;
- Earth Magic skill;
- Fire Magic skill;
- Air Magic affinity;
- Magic Sense skill;
- Telekinesis technique;
- any mastered elemental technique.

Preserve: `OBSERVATION != HYPOTHESIS != CONFIRMED CAPABILITY != SKILL != TECHNIQUE != MASTERY`.

## 2.4 Techniques / innate / potential
- Formal techniques: NONE legally classified from the recent experiments.
- Innate abilities: magical capability exists observationally; exact canonical/system classification unresolved.
- Magical potential: UNKNOWN.
- Talent profile: no legal numeric profile currently available; TEST GM does not fabricate one solely from fast early progress.

## 2.5 Runtime condition
- HP/resources: not numerically modeled in this TEST SESSION panel.
- Fatigue: recovered after overnight sleep; prior-day fatigue retained historically as training evidence, not as current penalty.
- Injuries: none documented.
- Temporary buffs/debuffs: none documented.

## 3. Progression fallback ledger summary
The following played blocks are preserved and have been used to construct the `ARCHITECTURE_SYNTHETIC` presentation values above. They are NOT claimed to be accepted-runtime ProgressionGrant records and no fabricated eventUid/transactionUid/ledgerUid is created.

1. 21 Aug intensive water/earth/fire experimentation.
2. 21 Aug mathematics/herbalism practice and evening reading.
3. 22 Aug 07:16–09:06 interoception and controlled magic experiments.
4. 22 Aug 09:06–12:18 mathematics + herbalism mentored block.
5. 22 Aug 13:04–16:42 herbal-book reading + herbalism integration.
6. 22 Aug 16:42–17:31 animal-form air shaping.
7. 22 Aug 17:31–18:37 water-range / earth-wall / fire-refinement block.
8. 22 Aug 18:37–20:06 Ban Ard reading practice.

### Synthetic resolution note
Accepted `ProgressionEngine` requires resolved causal numeric inputs such as `effortUnits`; the missing evidence->input resolver is not supplied by the currently usable accepted pipeline. Under the player's updated directive, TEST GM therefore created conservative presentation deltas from the MASTER architecture factors. These numbers are explicitly `ARCHITECTURE_SYNTHETIC`, not `RUNTIME_RESOLVED`, and must remain distinguishable for future reconciliation.

No raw time-to-power conversion was used. Relative weighting favored deliberate practice, novelty, method correction, mentor-supported learning, repeatability and demonstrated outcome; it discounted fatigue, overlap among targets, beginner instability and repetition/diminishing-return effects.

## 4. Magical evidence chronology
### Earlier retained
Knife intent effect; home leaf/wind effect; parchment/feather manipulation; target selectivity; START->PATH->TARGET / A->B->C routing; Vengerberg controlled local-air test; contained-air bladder deformation; near-spherical form; sphere/elongated/flattened transitions; dual bulges; approximate loaf/bowl/spoon forms; whole-form mental model.

### 21 Aug — water
Initial spill -> repeated practice -> repeatable full transfer A -> B without visible spill. Method: coherent stream / whole path.

### 21 Aug — earth
Single clod movement -> unstable multi-clod control -> larger loose-soil whole-form attempt -> short low barrier that partially collapsed.

### 21 Aug — fire
Existing flame directed to ignite another wick; later independent prepared-wick ignition after failures and method change. Sequence: failure -> smoke -> repeated smoke -> ember -> glowing wick -> sustained flame.

### 22 Aug — interoception
Baseline/concentration-only/active-magic/recovery comparisons; closed-eye attempts; hits and ambiguous results; start/stop pulses; hard nonmagical concentration control. H1 strengthened but not confirmed as Magic Sense.

### 22 Aug — refinement
Air animal shapes; water range extension; earth wall narrowing/heightening via inward gathering; ignition repetition and faster initiation attempts; existing flame enlargement/reduction.

## 5. Items / learning resources
- prepared animal bladder/membrane filled with air;
- two cups for water-transfer tests;
- loose earth/clods;
- candles;
- old household herbal book/herbarium with drawings/notes;
- book about Ban Ard.

## 6. NPC / relationship continuity
- Mirog — father; observed multiple magical training sessions and assists with reading.
- Mother — mentor in herbalism and basic mathematics; name not securely recovered in this snapshot.
- Older sister — exists; name not securely recovered in this snapshot.
- Other retained names: Elvar, Odran, Celene, Roderik.

GM knowledge != NPC knowledge. NPCs may only use observed/told/plausibly known information.

## 7. Current goals
- continue literacy, herbalism and mathematics;
- continue controlled magic experiments;
- improve reliability/precision/range without prematurely classifying elemental affinities;
- investigate the internal sensation associated with magic;
- await Ban Ard response;
- preserve distinction between synthetic TEST GM progression and future accepted-runtime reconciliation.

## 8. Phase / write boundary
TEST GM treats formally accepted Phase 1–25 runtime as direct mechanics where usable. Missing transformations use MASTER ARCHITECTURE fallback. TEST GM writes only under `docs/test-gm/` and does not modify runtime, Kotlin, schemas, migrations, roadmap, acceptance records, canonical architecture, production tests or World Packs.

END SNAPSHOT
