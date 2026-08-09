# WORK-20260810-021 — Phase 6 Semantic Invariant Oracle

Status: READ-ONLY RUNTIME / SEMANTIC ORACLE

Work ID: `WORK-20260810-021`
Owner: `CHAT-2`
Role: PHASE 6 SEMANTIC INVARIANT AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Baseline inspected: `387a0c331eaa11863529a4eababa8dd580c30ff2`
Accepted Phase 5 runtime: `44011bc0177df846a34fa12d0009d33e887f6c23`
Primary architecture source: `docs/audits/WORK-20260809-018_PHASE6_FINAL_ARCHITECTURE.md`
Primary migration/test source: `docs/audits/WORK-20260809-009_PHASE6_TEST_MIGRATION_CONTRACT.md`

This document is an independent semantic oracle for the Phase 6 implementation. It does not implement `TalentProfile`, `PotentialProfile`, progression, skills, schema, migrations, repository APIs, or runtime behavior.

---

## 1. Canonical semantic separation

Phase 6 must preserve four distinct concepts:

- `Talent` = ease/efficiency of learning and development in a specific progression domain.
- `Potential` = long-term possible growth headroom/scale/ceiling-like capacity in a specific domain/dimension.
- `Skill Level` = current learned competence/mastery in a skill domain.
- `Stat` = current authoritative persistent statistic base, with any effective value derived separately by Phase 5.

No implementation is semantically valid if it collapses any two of these concepts into one persisted scalar.

Absolute invariants:

```text
Talent != Potential
Talent != Skill Level
Talent != Stat
Talent != current power
Potential != Skill Level
Potential != Stat
Potential != current power
Skill Level != Stat
```

A high value in one axis must never be used as evidence that another axis is high.

---

## 2. Semantic matrix — what may influence what

Legend:

- YES = concept may legally be an input to the target calculation/update, subject to an explicit future rule.
- NO = direct semantic assignment or automatic inference is forbidden.
- DERIVED ONLY = influence may exist only through a derived/rule layer and must not rewrite the authoritative source.
- FUTURE ENGINE = relationship belongs to ProgressionEngine, not Phase 6 persistence.

| Source | Persistent Talent | Persistent Potential | Current Skill Level | PlayerStat.baseValue | Phase 5 effective stat/resource | Future progression gain | Future long-horizon headroom | Derived progression parameter |
|---|---|---|---|---|---|---|---|---|
| Persistent Talent | self only | NO | NO | NO | NO unless explicit unrelated rule | YES — FUTURE ENGINE | possibly indirect only through explicit future rule | YES — DERIVED ONLY |
| Persistent Potential | NO | self only | NO | NO | NO unless explicit unrelated rule | possibly indirect through future rule | YES — FUTURE ENGINE | YES — DERIVED ONLY |
| Current Skill Level | NO | NO | self/domain mutation only | NO | may be read by explicit rule | YES — FUTURE ENGINE | may affect diminishing-return context, not Potential itself | YES — DERIVED ONLY |
| PlayerStat.baseValue | NO | NO | NO | self/domain mutation only | YES through Phase 5 | YES — FUTURE ENGINE | may be contextual input only | YES — DERIVED ONLY |
| Phase 5 Modifier | NO direct write | NO direct write | NO direct write | NO direct write | YES | YES via effective parameter | YES via effective parameter | YES |
| Mentor/environment/injury/buff context | NO | NO | NO direct write | NO direct write | possibly | YES via derived parameter | possibly via derived parameter | YES |
| Causal training/action | NO automatic Talent change | NO automatic Potential change | YES only via future progression mutation | YES only via future progression mutation | indirect | YES — required cause | indirect | YES |

Release-blocking interpretation:

1. Talent is a persistent input to future learning/progression calculations; it is not the progression result.
2. Potential is a persistent input to future long-horizon scaling/headroom calculations; it is not a current achieved value.
3. Skill Level and stat state may be inputs to future progression rules but do not back-infer Talent or Potential.
4. Phase 5 temporary/permanent modifiers can alter derived/effective progression parameters, but cannot write `TalentEntry.baseValue` or `PotentialEntry.baseValue` as a resolver side effect.

---

## 3. Four-quadrant canonical oracle

Use neutral fixture domain:

```text
worldPackUid = WP-A
domainUid = WP-A:DOMAIN:X
potentialDimensionUid = WP-A:POTENTIAL:GROWTH-SCALE
character = PLAYER-A
```

For test readability only:

```text
LOW = 0.25
HIGH = 0.85
```

These fixture numbers do not mandate the production scale.

### Q1 — high Talent + low Potential

Input:

```text
Talent(X) = 0.85
Potential(X,growth-scale) = 0.25
SkillLevel(X) = 40
StatBase(X-related) = 100
```

Expected persistent state after create/read/reopen:

```text
Talent = 0.85
Potential = 0.25
SkillLevel = 40
StatBase = 100
```

Semantic expectation:

- future equal training may gain more efficiently because Talent is high,
- long-horizon headroom may remain limited because Potential is low,
- no automatic Skill or Stat increase occurs merely because profile values exist.

### Q2 — low Talent + high Potential

Input:

```text
Talent = 0.25
Potential = 0.85
SkillLevel = 40
StatBase = 100
```

Expected:

- persistent values remain exactly low/high,
- future learning may be slower,
- long-term possible scale may remain high,
- no normalization may convert this into a medium/medium profile.

This quadrant is essential proof that Talent and Potential are not one hidden `growthRating`.

### Q3 — high Talent + high Potential

Input:

```text
Talent = 0.85
Potential = 0.85
```

Expected:

- both remain independent high values,
- no free XP/stat gain is generated,
- future progression still requires a causal training/action/event.

### Q4 — low Talent + low Potential

Input:

```text
Talent = 0.25
Potential = 0.25
```

Expected:

- both remain independently low,
- implementation does not secretly promote one axis to compensate for the other,
- no current Skill/Stat is rewritten.

### Quadrant persistence test

Persist all four combinations across four characters, close/reopen, reload, and assert exact axis equality. Sorting/order must not couple axes across rows or characters.

---

## 4. Update isolation oracle

### U1 — Talent update does not change Potential

Before:

```text
Talent(X) = 0.40
Potential(X) = 0.70
```

Operation:

```text
Talent(X) -> 0.80
```

Expected:

```text
Talent(X) = 0.80
Potential(X) = 0.70
```

Potential value, potential entry version, and potential provenance must remain unchanged unless implementation has an outer aggregate profile version that intentionally changes as container metadata. No semantic Potential update may be recorded.

### U2 — Potential update does not change Talent

Symmetric requirement.

### U3 — domain isolation

Updating Talent domain A does not update Talent domain B and changes no Potential entry in any domain.

### U4 — player isolation

Updating PLAYER-A profile cannot alter PLAYER-B profile even when domain UIDs are equal.

### U5 — campaign isolation

Same `characterUid` and same `domainUid` in two campaigns remain independent.

---

## 5. Skill/Stat non-interference oracle

### SS1 — Talent does not set Skill Level

Given Skill mastery 60 and Talent changes 0.40 -> 0.90:

```text
Skill mastery after profile write/read = 60
```

No XP event or progress mutation may be created by profile persistence itself.

### SS2 — Skill Level does not infer Talent

Given mastery changes 60 -> 90 through a legal future progression mechanism, persistent Talent remains unchanged unless a separate explicit authoritative Talent change is committed with provenance.

### SS3 — Potential does not set PlayerStat.baseValue

Given base stat 100 and Potential changes 0.30 -> 0.90:

```text
PlayerStat.baseValue remains 100
```

### SS4 — PlayerStat.baseValue does not infer Potential

Stat growth 100 -> 200 does not back-write Potential. A high stat cannot be used as migration evidence for high Potential.

### SS5 — profile reads are pure

Repeated profile reads never mutate skills, stats, resources, modifiers, aliases, or profile versions.

---

## 6. Relationship to accepted Phase 5 runtime

Accepted Phase 5 runtime is a pure derived projection over canonical typed identities. It validates campaign/player scope, finite numeric inputs, canonical non-legacy targets, deterministic modifier ordering, rule versions, and dependency cycles. It does not mutate authoritative stat/resource values.

Current Phase 5 runtime target kinds are stat/resource oriented (`STAT_EFFECTIVE`, `RESOURCE_MAXIMUM`, `RESOURCE_REGENERATION`). Therefore Phase 6 must not force persistent Talent/Potential into those existing target kinds merely to reuse the resolver.

Correct integration principle:

```text
TalentEntry.baseValue / PotentialEntry.baseValue
    = authoritative profile state

Phase 5-compatible future generic progression parameter target/rule
    = derived contextual projection
```

If Phase 6 requires temporary learning/breakthrough effects before a generic progression-parameter target exists, Phase 6 should persist the profiles and defer that derived calculation rather than invent `TalentModifierEngine` or misuse `STAT_EFFECTIVE`.

---

## 7. Temporary/contextual effects oracle

Persistent profile values before, during, and after any temporary effect must be byte/semantic-equal unless an explicit authoritative profile mutation occurs separately.

### T1 — temporary learning buff

Given:

```text
persistent Talent = 0.50
learning effect = +20%
```

Legal outcome:

```text
persistent Talent = 0.50
effective learning parameter = rule-defined derived value
```

Forbidden outcome:

```text
Talent persisted as 0.60
```

Expiry/removal of the source must only remove the derived contribution.

### T2 — injury learning penalty

An injury may reduce a derived learning parameter. It cannot reduce persistent Talent.

### T3 — environment bonus

Environment may increase a derived learning parameter. Leaving the environment restores the derived parameter without a Talent rollback because Talent was never rewritten.

### T4 — mentor synergy

Mentor compatibility/synergy is context, not innate Talent. It may be a future rule input or modifier source only.

### T5 — breakthrough condition

Temporary breakthrough/evolution condition may alter a derived breakthrough parameter. Persistent Potential remains unchanged.

### T6 — source removal

Removing a temporary/permanent contextual source cannot restore persistent profile state from a cached effective value. Authoritative profile is always read from profile storage.

---

## 8. Provenance/version semantic oracle

Every persistent profile entry must be explainable as authoritative state.

Minimum invariants:

1. entry has stable domain UID identity;
2. entry has version >= 1;
3. entry has non-empty provenance describing semantic origin;
4. provenance is not fabricated for ambiguous legacy data;
5. profile update increments/changes only the changed axis/domain according to the implementation's version contract;
6. rule/provider version changes invalidate/recompute derived projections but do not rewrite profile base values;
7. temporary modifier lifetime/version does not become Talent/Potential entry version.

Valid provenance examples:

- World Pack seed,
- character creation,
- canonical trait assignment,
- explicit migration mapping,
- later explicit authoritative domain change.

Invalid behavior:

- inferring provenance from `gifted`, high mastery, rank, current stat, or combat results without a canonical mapping.

---

## 9. Stable UID / World Pack oracle

### I1 — stable UID beats label

Two World Packs may each define display label `Focus`:

```text
WP-A:DOMAIN:FOCUS
WP-B:DOMAIN:FOCUS
```

They remain separate identities.

### I2 — same text label does not merge profiles

Talent/Potential entries are keyed/scoped by stable UID, not display name.

### I3 — ownership hijack fails

World Pack B cannot redefine a domain UID owned by World Pack A.

### I4 — missing domain fails or remains quarantined migration evidence

A canonical `TalentEntry`/`PotentialEntry` cannot silently synthesize a domain from text.

### I5 — Potential dimension identity

If Potential uses `(domainUid, dimensionUid)`, two dimensions in the same domain remain independent; updating one cannot rewrite another.

---

## 10. Legacy semantic oracle

No bare legacy label is sufficient evidence for canonical profile creation by itself.

Default classifications:

| Legacy label | Oracle classification |
|---|---|
| `talent` | EXPLICIT WORLD PACK MAPPING REQUIRED |
| `aptitude` | EXPLICIT WORLD PACK MAPPING REQUIRED |
| `learning_rate` | EXPLICIT WORLD PACK MAPPING REQUIRED |
| `maximum_potential` | EXPLICIT WORLD PACK MAPPING REQUIRED |
| `gifted` | AMBIGUOUS / PRESERVE UNRESOLVED |
| `growth_rate` | AMBIGUOUS / PRESERVE UNRESOLVED |
| `affinity` | AMBIGUOUS / PRESERVE UNRESOLVED |
| `adaptation` | AMBIGUOUS / PRESERVE UNRESOLVED |
| `evolution_potential` | EXPLICIT WORLD PACK MAPPING REQUIRED unless source schema already proves exact axis/domain/dimension/scale |

A value can be SAFE AUTO-MAP only when source metadata proves all of:

- TALENT vs POTENTIAL axis,
- exact World Pack/domain UID,
- Potential dimension if required,
- source numeric scale/unit and conversion,
- source schema/version compatibility,
- campaign/character identity,
- source is authoritative profile evidence rather than current/derived observation,
- mapping version,
- provenance.

Unmapped ambiguous evidence remains preserved and mechanically unresolved. It must not enter future ProgressionEngine inputs.

---

## 11. Semantic anti-patterns that must fail review

Phase 6 implementation is semantically invalid if any of the following appears:

1. one persisted `growthRating` stores both Talent and Potential;
2. Talent is initialized from Skill mastery without explicit canonical migration evidence;
3. Potential is initialized from current/base stat magnitude;
4. Talent update writes Skill XP/mastery;
5. Potential update writes PlayerStat.baseValue;
6. Skill/stat growth back-writes Talent/Potential automatically;
7. temporary learning modifier persists Talent;
8. temporary breakthrough modifier persists Potential;
9. same display label across World Packs is auto-merged;
10. legacy labels are converted by name alone;
11. profile values are stored as ordinary Phase 5 modifier rows instead of authoritative profile state;
12. a second Talent/Potential-specific modifier resolver is introduced;
13. unresolved legacy evidence is silently dropped;
14. current evolution/race/bloodline stage is treated as Potential without explicit World Pack semantics;
15. `affinity` is globally treated as Talent.

---

## 12. Required semantic test set for WORK-020 recheck

The implementation should be considered semantically conformant only if the following observable tests pass:

- high Talent + low Potential persists exactly;
- low Talent + high Potential persists exactly;
- high/high persists exactly;
- low/low persists exactly;
- Talent-only update leaves Potential unchanged;
- Potential-only update leaves Talent unchanged;
- Talent write/read leaves Skill Level unchanged;
- Talent write/read leaves stats/resources unchanged;
- Potential write/read leaves Skill Level unchanged;
- Potential write/read leaves stats/resources unchanged;
- temporary learning context does not change persistent Talent;
- temporary breakthrough context does not change persistent Potential;
- same label/different domain UID remains separate;
- same character/domain in different campaigns remains separate;
- same campaign/domain across players remains separate;
- ambiguous legacy labels create no canonical profile;
- explicit mapping creates exactly the mapped canonical entry and retains source evidence/provenance;
- rule/provider or derived-context changes do not increment profile base values;
- reopen returns semantically identical profiles;
- invalid finite/numeric input policy is fail-loud.

---

## 13. Post-WORK-020 semantic recheck procedure

When CHAT-1 publishes WORK-020, perform a short read-only recheck against this oracle:

1. identify actual domain/profile models and storage;
2. inspect whether Talent/Potential are separate tables/types/axes;
3. inspect profile write paths for side effects into skill/stat/resource/modifier state;
4. inspect migration for legacy guessing;
5. inspect World Pack ownership and stable UID validation;
6. inspect tests for all four quadrants and update isolation;
7. inspect any Phase 5 integration target and verify it is derived-only;
8. issue either semantic PASS or a concrete reproducible semantic conflict.

The recheck must not require Phase 6 to implement ProgressionEngine. Absence of progression calculation is correct at this phase.

---

# Final status

`PHASE 6 SEMANTIC ORACLE READY`
