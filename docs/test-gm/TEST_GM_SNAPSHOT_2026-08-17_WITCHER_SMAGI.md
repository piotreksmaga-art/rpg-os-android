# TEST GM SNAPSHOT — WITCHER / SMAGI

Date: 2026-08-17
Campaign: NEW GAME — THE WITCHER
Player Character: Smagi
Scope: `docs/test-gm/**` only
Status: TEST SESSION SNAPSHOT / DERIVED PRESENTATION / NON-AUTHORITATIVE

## 0. Runtime / provenance discipline
Current canonical roadmap confirms Phase 1–25 globally ACCEPTED. Accepted Player Core through Phase 25: `c028aa355d9b7e1663166a2fedb910c1a2dad795`; Phase 20 accepted runtime: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`.

TEST GM cannot execute Android/Kotlin runtime directly from chat. Therefore this snapshot distinguishes:
- `RUNTIME_CONTRACT_USED` — accepted runtime contract/function semantics inspected and followed;
- `ARCHITECTURE_SYNTHETIC_INPUT` — missing gameplay-to-engine input created by TEST GM under explicit player fallback directive;
- `CONTRACT_MIRRORED_RESULT` — deterministic result calculated according to inspected accepted function semantics, but not claimed as an actual Android runtime invocation/commit;
- `OBSERVATION`, `HYPOTHESIS`, `CANON_RESEARCH` retain prior meanings.

Preserve: FACT != BELIEF != NARRATIVE; AI OUTPUT != COMMITTED REALITY; snapshot != authority.

## 1. Identity / current campaign state
- Name: Smagi
- Birth year: 1173
- Age: 6
- Universe: The Witcher
- Current campaign period: early September 1179; exact day/time after the narrated multi-day blocks is NOT RECORDED precisely and is not fabricated here.
- Location: family home
- Condition: healthy; regular sleep/rest; no meaningful injury recorded
- Routine: 6 days education/training + 1 full play/rest day
- Ban Ard: `AUTHORIZED FOR FURTHER CONSIDERATION / PENDING RESPONSE`
- Magical education: no formal magical schooling; only recent self-directed discovery/practice.

# 2. PLAYER PANEL

## 2.1 Stats (0–100)
| Stat | Previous resolved projection | New gain | Current projection | Provenance |
|---|---:|---:|---:|---|
| Strength | 7 | +0 | 7 | no targeted training resolution |
| Endurance | 9 | +0 | 9 | no targeted training resolution |
| Dexterity | 11 | +0 | 11 | play evidence insufficient for deliberate durable grant |
| Speed | 10 | +0 | 10 | play evidence insufficient for deliberate durable grant |
| Perception | 15 | +0 | 15 | no separate durable grant |
| Intelligence | 18 | +1 | 19 | ARCHITECTURE_SYNTHETIC_INPUT -> CONTRACT_MIRRORED_RESULT |
| Willpower | 18 | +1 | 19 | ARCHITECTURE_SYNTHETIC_INPUT -> CONTRACT_MIRRORED_RESULT |
| Concentration | 21 | +2 | 23 | ARCHITECTURE_SYNTHETIC_INPUT -> CONTRACT_MIRRORED_RESULT |
| Magic Control | 15 | +2 | 17 | ARCHITECTURE_SYNTHETIC_INPUT -> CONTRACT_MIRRORED_RESULT |

Magical potential: `EXISTS observationally; MAGNITUDE UNKNOWN / NOT ASSESSED`.
No hidden Talent/Potential multiplier was applied because no accepted campaign value for Smagi is recorded.

## 2.2 Skills / competencies
| Skill / competency | Previous resolved projection | New gain | Current projection | Provenance |
|---|---:|---:|---:|---|
| Reading | 19 | +6 | 25/100 | SYNTHETIC INPUTS -> Phase20/21 contract-mirrored resolution |
| Herbalism | 17 | +4 | 21/100 | SYNTHETIC INPUTS -> Phase20/21 contract-mirrored resolution |
| Mathematics | 11 | +5 | 16/100 | SYNTHETIC INPUTS -> Phase20/21 contract-mirrored resolution |
| Controlled shaping / magical control practice | 28 | +6 | 34/100 | SYNTHETIC INPUTS -> Phase20/21 contract-mirrored resolution |
| Writing | unscored development candidate | initial 12 | 12/100 | first scored projection; SYNTHETIC INPUTS -> contract-mirrored resolution |

Writing is now a legitimate broad skill candidate because evidence includes repeated copying, own name, digits, words from memory, simple dictation, self-correction and practical herbal notes. This does not imply adult literacy.

No Air/Water/Earth/Fire skills, Magic Sense, Telekinesis, Source trait or separate technique/mastery is created.

# 3. NEW PROGRESSION RESOLUTION — POST 30 AUG EDUCATION/MAGIC BLOCKS

## 3.1 Causal evidence
After the last resolved 23–30 Aug block, played evidence includes:
- introduction and repeated practice of writing;
- copying letters, writing name/digits/known words, memory reproduction, simple dictation, detecting/correcting errors;
- integrating writing with mathematics and a child-made herbal notebook;
- continued reading and comprehension practice;
- continued basic arithmetic with decreasing reliance on physical counters;
- continued mentored herbalism;
- balloon/bladder shaping switched from animals to letters/numbers;
- comparison chain: visual model -> handwriting -> magical form;
- repeated local correction of one part of a magical shape while trying to preserve the rest;
- continued water precision/range, earth-barrier repeatability and supervised candle work;
- repeated interoception/control experiments with mixed results;
- methodological improvement: recognizing muscle tension/breath-holding as possible confounds and attempting relaxed trials;
- repeated failure -> correction -> repetition -> improved reliability;
- two narrated 6-training-day + 1-play/rest-day cycles plus the initial writing-introduction days; exact hour totals were not fully timestamped and are not retroactively fabricated.

## 3.2 Accepted Phase 20 contract used
`ProgressionEngine.evaluate()` requires causal `effortUnits`, canonical target evidence, source/channel, target kind/uid, policy identity and factors. It computes `base = effortUnits`, canonicalizes factors, then applies them deterministically using fixed 1e6 HALF_UP numeric semantics. This accepted behavior is treated as `RUNTIME_CONTRACT_USED`.

Chat TEST GM cannot truthfully claim that the Android/Kotlin method itself executed or committed state. Therefore the calculations below are `CONTRACT_MIRRORED_RESULT`, not fake runtime receipts/grant UIDs.

## 3.3 Accepted Phase 21 contract used
Phase 21 supplies factor vocabulary and a deterministic diminishing-returns function:
`factor = resistanceUnits / (resistanceUnits + repetitionCount)`, fixed-scale HALF_UP, subject to floorFactor.

Novelty/adaptation/repetition/environment evidence remain explicit external inputs. Where gameplay did not provide numeric source values, TEST GM supplied conservative values marked `ARCHITECTURE_SYNTHETIC_INPUT`.

Fallback policy for this resolution:
- preserve lower growth for repeated familiar material;
- reward genuinely new writing acquisition and local magical correction more than rote repetition;
- apply stronger diminishing-return compression to established reading/herbalism/basic magic than to new Writing;
- no Talent/Potential bonus;
- no injury penalty;
- normal fatigue handled conservatively through reduced effective effort rather than a bonus;
- rest day generates no direct skill grant but prevents treating seven days as uninterrupted high-intensity work.

## 3.4 Contract-mirrored grants
Because exact hour totals are missing, effort inputs are synthesized as bounded aggregate units rather than invented minute-level history. Final grants are intentionally conservative:
- Reading: +6 -> 25/100.
- Herbalism: +4 -> 21/100.
- Mathematics: +5 -> 16/100.
- Writing: initial +12 -> 12/100; high novelty but still beginner/child-level competence.
- Controlled shaping / magical control practice: +6 -> 34/100; established domain receives stronger repetition/diminishing-return compression despite meaningful precision improvements.
- Intelligence: +1 -> 19.
- Willpower: +1 -> 19.
- Concentration: +2 -> 23.
- Magic Control stat: +2 -> 17.

No physical stat grant from ordinary play is synthesized. No double-counting of block `23–30 Aug weekly routine` occurs.

## 3.5 Phase 22 invariant check — mirrored
No permanent stat/skill regresses. No learned capability is deleted. No unsupported mastery/technique is created. PASS under inspected no-retrogression intent.

## 3.6 Phase 23 provenance discipline — mirrored
This snapshot records causal evidence, synthetic-input provenance and result provenance. It does NOT fabricate accepted ledgerUid/eventUid/transactionUid/runtime commit identifiers.

## 3.7 Phase 24–25 projection discipline
Panel is treated as derived/presentation. Current projection is rebuilt from previous TEST GM resolved projection plus this single new resolved block. Snapshot is not used to overwrite newer campaign facts.

# 4. Magical epistemic state
OBSERVED:
- controlled effects involving contained air, water, loose earth and flame;
- repeatable letter/number shaping in the bladder/balloon exercise;
- increasing ability to attempt local correction while preserving the remainder of a shape;
- water transfer precision/range practice;
- repeatable small earth-barrier construction with method adaptation;
- supervised wick ignition/flame adjustment remains imperfect but increasingly familiar;
- subjective internal difference associated with magical action;
- some trials report the sensation without obvious visible effect, but results remain ambiguous;
- Smagi identified tension/breath-holding as a possible confound and tried more relaxed trials.

HYPOTHESIS:
- Smagi may instinctively draw Power without understanding it;
- internal sensation may correspond to drawing/channeling/control/shaping or another stage;
- possible Source OR exceptional innate aptitude remains GM hypothesis.

NOT CONFIRMED:
- Source;
- elemental affinities;
- source Element used for each effect;
- Magic Sense;
- Telekinesis;
- unrestricted fire creation;
- internal mana/Chaos reservoir.

# 5. Canon GM model
Keep distinct:
`SOURCE / ELEMENT -> DRAW POWER -> CONTROL / CHANNEL / SHAPE -> MAGIC / SPELL / EFFECT`.
Visible effect != source of Power. Smagi is six and does not know this theory unless taught in play.

# 6. Progression ledger — TEST GM continuity
Previously resolved once:
1–8. 21–22 Aug evidence incorporated into pre-week basis.
9. `23–30 Aug weekly routine` — resolved once.

Newly resolved once in this snapshot:
10. `post-30-Aug writing introduction + first 6+1 routine block`.
11. `second 6+1 routine block with writing/dictation/local magical correction refinement`.

Blocks 10–11 are now marked RESOLVED_FOR_TEST_GM_PROJECTION and must not be awarded again unless a future explicit reconciliation replaces these synthetic/contract-mirrored results.

# 7. Current goals
- continue age-appropriate reading, writing, mathematics and herbalism;
- continue 6 training days + 1 play/rest day rhythm;
- improve magical precision/reliability before raw scale;
- continue safe supervised fire practice;
- investigate internal sensation without prematurely naming it Power sensing/Chaos/mana;
- await Ban Ard response.

# 8. Write boundary
TEST GM writes only under `docs/test-gm/`. No runtime, Kotlin, schema, migration, roadmap or acceptance record was modified.

END SNAPSHOT
