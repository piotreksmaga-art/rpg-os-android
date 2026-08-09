# WORK-20260810-024 — Phase 6 Adversarial / Domain-Integrity Matrix

Status: READ-ONLY RUNTIME / PRE-IMPLEMENTATION ADVERSARIAL MATRIX

Work ID: `WORK-20260810-024`
Owner: `CHAT-5`
Role: PHASE 6 ADVERSARIAL / DOMAIN-INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Audited master at matrix creation: `387a0c331eaa11863529a4eababa8dd580c30ff2`
Accepted Phase 5 runtime: `44011bc0177df846a34fa12d0009d33e887f6c23`
Phase 6 implementation work item: `WORK-20260810-020`
WORK-020 result commit at matrix creation: NOT FOUND

This report is read-only. It does not implement TalentProfile, PotentialProfile, schema, migrations, ProgressionEngine, Skill, Technique, PlayerDomainEngine or CharacterPanelSnapshot v2.

Canonical source contracts:
- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/audits/WORK-20260809-018_PHASE6_FINAL_ARCHITECTURE.md`
- `docs/audits/WORK-20260809-009_PHASE6_TEST_MIGRATION_CONTRACT.md`

## 1. Release invariants under attack

Phase 6 must preserve all of the following under malformed, extreme, ambiguous and cross-scope input:

1. Talent is persistent learning/development efficiency input, not Skill Level/current stat/current power/Potential.
2. Potential is persistent long-horizon headroom/scale input, not current stat/mastery/current power/Talent.
3. Talent and Potential remain independently writable/readable; all four high/low quadrants remain legal.
4. Stable domain UID defines identity; label/key/display name never globally defines equivalence.
5. World Pack ownership is explicit and cannot be hijacked.
6. Campaign, character and domain scoping are strict.
7. Persistent Talent/Potential cannot be overwritten by Phase 5 temporary modifiers or derived outputs.
8. Legacy data is never semantically guessed from label text alone.
9. Ambiguous legacy evidence remains preserved but mechanically unresolved until explicit mapping exists.
10. No profile read/write may mutate Phase 3/4/5 authoritative state outside the Phase 6 profile domain.
11. NaN/Infinity and invalid profile numbers fail loudly.
12. No world-specific literal is required in Core correctness logic.

## 2. Numeric / profile-value adversarial matrix

| ID | Case | Required outcome |
|---|---|---|
| N-01 | Talent = `0` | Legal only if production scale explicitly permits zero; persists exactly; no automatic Skill/stat mutation. |
| N-02 | Potential = `0` | Legal only if scale permits zero; no current stat/mastery mutation. |
| N-03 | Negative Talent | Deterministic reject unless explicit production scale documents negative values. |
| N-04 | Negative Potential | Deterministic reject unless explicitly legal. |
| N-05 | Talent = NaN | Reject before persistence/read model acceptance. |
| N-06 | Potential = NaN | Reject. |
| N-07 | Talent = +Infinity / -Infinity | Reject. |
| N-08 | Potential = +Infinity / -Infinity | Reject. |
| N-09 | Extremely large finite Talent | Explicit bounds policy; never silent wrap/clamp unless contract declares it. |
| N-10 | Extremely large finite Potential | Same. |
| N-11 | `-0.0` | Canonical numeric identity/fingerprint/serialization policy required if byte-sensitive. |
| N-12 | Version < 1 / invalid version | Reject. |
| N-13 | Missing provenance | Reject for authoritative canonical profile entry. |
| N-14 | Repeated identical update | Must not accidentally inflate semantic version/history without a legal update event. |

The implementation must explicitly freeze the production numeric scale. The architecture does not canonically force `0..1`; normalized values in design documents are fixtures only.

## 3. Four-quadrant semantic attacks

Required legal fixtures for the same domain:

- Q-01 high Talent + low Potential
- Q-02 low Talent + high Potential
- Q-03 high Talent + high Potential
- Q-04 low Talent + low Potential

For every quadrant:

- persisted values must survive reopen exactly,
- creating/reading the profile must not alter Skill mastery or PlayerStat.baseValue,
- no normalizer may infer one axis from the other,
- no combined `growthRating` may replace both authoritative values.

Blocking failure examples:

- high Talent automatically forces Potential high,
- Potential update rewrites Talent,
- Talent update writes Skill XP/mastery,
- Potential update writes PlayerStat.baseValue.

## 4. Domain identity / cross-domain attacks

| ID | Case | Required outcome |
|---|---|---|
| D-01 | Same domain label under World Pack A and B, different stable UIDs | Remain separate. |
| D-02 | Same textual key under A/B | No auto-merge. |
| D-03 | Duplicate exact domain UID, different owner | Reject hijack. |
| D-04 | Duplicate UID, same owner but incompatible semantic metadata | Reject or require explicit versioned migration; never silent reinterpretation. |
| D-05 | Profile entry targets missing domain | Fail/quarantine according to migration policy; never synthesize definition from label. |
| D-06 | Domain deleted after profile exists | Fail-loud unresolved/dependency state or explicit migration path; no silent remap. |
| D-07 | Domain ownership changed after persistence | Integrity violation unless explicit versioned migration exists. |
| D-08 | Talent domain A update | Does not change Talent B or any Potential entry. |
| D-09 | Potential domain A update | Does not change Potential B or any Talent entry. |
| D-10 | Potential dimension A vs B under same domain | Must remain distinct stable identities if dimensions are part of model. |
| D-11 | Parent/child domains both present | No implicit double-application merely because hierarchy exists. |

Talent/Potential are per-domain unless a World Pack explicitly defines a global domain UID. A domain named “general” is still an explicit domain identity, not an implicit global fallback.

## 5. Campaign / player / World Pack isolation attacks

| ID | Case | Required outcome |
|---|---|---|
| I-01 | Campaign A profile supplied to campaign B read/write | Reject / invisible. |
| I-02 | Player A profile supplied to Player B | Reject / invisible. |
| I-03 | Same character UID string in two campaigns | Profiles remain isolated by campaign. |
| I-04 | World Pack A attempts update of B-owned domain | Reject. |
| I-05 | World Pack B attempts same UID as A | Reject deterministic ownership hijack. |
| I-06 | 1000 entries across multiple players/campaigns | No truncation/cross-scope leakage. |
| I-07 | ActivePlayerRef changes from A→B | Profile APIs scoped by requested/active character must not silently retain A data. |

## 6. Phase 5 temporary-effect attacks

Phase 5 is the single generic derived/modifier foundation. Phase 6 must not create a parallel Talent/Potential modifier engine.

Adversarial fixtures:

### T-01 temporary learning bonus

Persistent Talent = X. Apply temporary learning/effective-progression modifier through Phase 5 integration.

Required:
- persistent Talent remains byte/semantic-equal before/during/after effect,
- derived learning parameter may change,
- expiry/removal removes only derived contribution.

### T-02 temporary breakthrough bonus

Persistent Potential = Y. Temporary breakthrough condition changes only a derived progression/breakthrough input.

Required:
- persistent Potential remains Y,
- no profile write from resolver execution.

### T-03 injury learning penalty

Injury may reduce a derived learning parameter but cannot write lower persistent Talent.

### T-04 environment / mentor synergy

Contextual effect must not permanently change Talent/Potential unless a later legal committed domain mutation explicitly does so.

### T-05 malicious modifier target

If Phase 5 runtime cannot directly target persistent Talent/Potential entries, that is acceptable and preferred. If later integration introduces generic progression-parameter targets, the target must be derived-only and never profile-storage identity.

Automatic blocker: any direct path `Modifier/DerivedValueResolver -> TalentEntry.baseValue/PotentialEntry.baseValue`.

## 7. Legacy semantic attacks

Bare legacy labels are not canonical profile identity.

Default hostile fixtures:

| Legacy evidence | Expected default |
|---|---|
| `talent` | requires explicit World Pack/domain/scale mapping |
| `aptitude` | requires explicit mapping |
| `learning_rate` | requires explicit mapping |
| `maximum_potential` | requires explicit mapping |
| `gifted` | ambiguous / opaque |
| `growth_rate` | ambiguous / opaque |
| `affinity` | ambiguous / opaque |
| `adaptation` | ambiguous / opaque |
| `evolution_potential` | requires explicit semantic/domain/dimension mapping or remains unresolved |

Adversarial cases:

- L-01 ambiguous `gifted=yes` creates no canonical Talent/Potential entry.
- L-02 ambiguous `affinity=0.9` creates no canonical entry merely because numeric.
- L-03 same legacy label under two World Packs does not auto-map to one domain.
- L-04 explicit mapping target missing -> fail/quarantine, no guessing.
- L-05 explicit mapping target owner changed -> integrity failure.
- L-06 mapping version mismatch -> explicit migration/invalidation error; no silent use.
- L-07 unsupported source schema/version -> do not auto-map.
- L-08 unresolved evidence survives migration/reopen without becoming mechanical input.
- L-09 canonical typed profile already exists + mapped legacy evidence -> exactly one canonical logical profile entry; evidence retained for provenance, no duplicate authoritative node.
- L-10 high current stat/mastery/rare technique/evolution stage must not be inferred into missing Talent/Potential.

SAFE AUTO-MAP is legal only with proven axis, exact canonical domain/dimension UID, scale/unit conversion, owner, source version, campaign/character identity and proof that the source is base authoritative profile data.

## 8. Provenance / version attacks

| ID | Case | Required outcome |
|---|---|---|
| P-01 | Empty provenance canonical profile | Reject. |
| P-02 | Version zero/negative | Reject. |
| P-03 | Profile update changes value but not version/provenance where contract requires change tracking | Integrity failure. |
| P-04 | Same persisted value loaded after reopen | Same semantic value/version/provenance. |
| P-05 | Rule/provider version changes | May change derived outputs/cache, must not rewrite base profile. |
| P-06 | Legacy mapping version changes | Mapping provenance/version must distinguish result; persistent canonical entry only changes via legal migration. |
| P-07 | Duplicate entry same campaign/player/domain with conflicting values | Deterministic reject; never row-order winner. |

## 9. Migration / existing-campaign attacks

When WORK-020 appears, verify:

1. old campaign with no Phase 6 tables opens normally,
2. migration is additive/idempotent,
3. no synthetic Talent/Potential rows are invented from bare legacy labels,
4. existing ActivePlayerRef unchanged,
5. existing PlayerStat.baseValue unchanged,
6. PlayerResource.currentValue unchanged,
7. Phase 5 modifiers unchanged,
8. LegacyStatAlias/LegacyResourceAlias unchanged,
9. explicit Phase 6 legacy mapping is stable across reopen,
10. ambiguous evidence remains unresolved and losslessly preserved,
11. 100 and >1000 domain/profile rows no silent truncation,
12. `PRAGMA integrity_check == ok`,
13. FK policy is explicit and passes `foreign_key_check` if FK is used.

## 10. No hidden Skill/Stat coupling attacks

Phase 6 does not implement Phase 7 Skill redesign or ProgressionEngine.

Tests after WORK-020 must prove:

- creating/updating Talent does not alter any `character_skills`/future Skill mastery row,
- creating/updating Potential does not alter Skill mastery,
- creating/updating Talent does not alter `PlayerStat.baseValue`,
- creating/updating Potential does not alter `PlayerStat.baseValue`,
- reading profiles repeatedly produces no progression side effects,
- high Skill or high stat does not auto-backfill Talent/Potential.

## 11. Core world-agnostic attack

Search Phase 6 runtime for branches/literals requiring universe mechanics. Core implementation must not depend on names such as Naruto, Bleach, chakra, reiatsu, genjutsu, raiton, kido, zanjutsu, sonido, reishi or equivalent pack-specific mechanics.

World-specific examples are allowed only in fixtures/World Pack data, not Core correctness logic.

## 12. Scale / pathological dataset attacks

After WORK-020:

- 100 domain definitions + profile entries: exact count, deterministic ordering if ordered API exists.
- >1000 definitions/profile entries: no hidden LIMIT 100/truncation.
- duplicate UID near tail: still fail-loud.
- same label repeated across hundreds of distinct stable UIDs/packs: no accidental key-only merge.
- reopen after large dataset: exact equality.

## 13. Automatic FAIL conditions for final validation

`PHASE 6 ADVERSARIAL VALIDATION: FAIL` is mandatory for any reproducible case where current WORK-020 runtime:

- collapses Talent and Potential into one authoritative value,
- infers one from the other,
- writes Skill/stat/current power as a side effect of profile storage/read,
- permits NaN/Infinity canonical profile values,
- allows campaign/player/World Pack/domain cross-scope leakage,
- lets temporary Phase 5 effects persistently rewrite Talent/Potential,
- auto-migrates ambiguous legacy labels without explicit semantics,
- guesses semantic equivalence by label/key,
- permits domain UID ownership hijack,
- silently drops unresolved legacy evidence,
- introduces world-specific Core branches,
- truncates canonical profile reads silently,
- damages Phase 3/4/5 authoritative state during migration.

A missing future ProgressionEngine integration is NOT a Phase 6 failure if Phase 6 correctly stores authoritative profiles and exposes them as future inputs.

## 14. Current runtime validation status

At matrix creation, `master = 387a0c331eaa11863529a4eababa8dd580c30ff2` and no commit matching `WORK-20260810-020` was present.

Therefore final runtime adversarial validation cannot yet be performed.

Current status:

`PHASE 6 ADVERSARIAL MATRIX READY`

After WORK-020 resultCommit appears, this document must be extended with actual code/test/CI evidence and exactly one final verdict:

`PHASE 6 ADVERSARIAL VALIDATION: PASS`

or

`PHASE 6 ADVERSARIAL VALIDATION: FAIL`

No runtime implementation changes are authorized under WORK-20260810-024.
