# WORK-20260810-027 — Phase 7 Semantic / Mastery Oracle

Status: READ-ONLY RUNTIME / SEMANTIC ORACLE

Work ID: `WORK-20260810-027`
Owner: `CHAT-2`
Role: PHASE 7 SEMANTIC / MASTERY ORACLE AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master inspected before write: `cbcce81a2c911f40817e577b9082f6c43688c8f6`
Accepted Phase 6 runtime: `52af00e441131cc8e7beb4a8036e43d250f35848`
Accepted Phase 5 runtime: `44011bc0177df846a34fa12d0009d33e887f6c23`
Primary architecture source: `docs/audits/WORK-20260810-023_PHASE7_SKILL_ARCHITECTURE.md`

This document is an independent semantic oracle. It does not modify application runtime, schema, migrations, repository APIs, Phase 7 implementation, Technique design, or any other worker scope.

---

## 1. Canonical semantic separation

Phase 7 must keep the following concepts distinct:

| Concept | Authority | Meaning | May directly rewrite another concept? |
|---|---|---|---|
| `PlayerSkill.baseMastery` | AUTHORITATIVE / PERSISTENT | learned competence currently retained by the character | No temporary effect may rewrite it |
| `effectiveMastery` | DERIVED / REBUILDABLE | mastery after valid contextual modifiers/rules | Must never persist back into base mastery |
| Skill XP/progress | PERSISTENT RAW PROGRESS STATE if retained | accumulated progress-like state whose exact conversion semantics must be explicit | Must not be guessed into mastery |
| Talent | AUTHORITATIVE / PERSISTENT Phase 6 profile | ease/efficiency of learning | Cannot directly set mastery |
| Potential | AUTHORITATIVE / PERSISTENT Phase 6 profile | long-term growth headroom/scale | Cannot directly set or cap current mastery in Phase 7 |
| `PlayerStat.baseValue` | AUTHORITATIVE / PERSISTENT | character stat progression | Not Skill mastery |
| resolved/effective stat | DERIVED | contextual/effective stat value | Not Skill mastery |
| Technique learned/mastery | separate future Technique domain | concrete executable method/action/ability | Must not be stored as Skill merely due to similarity |

Hard equations:

```text
Skill != Talent
Skill != Potential
Skill != Technique
Skill != Stat
baseMastery != effectiveMastery
```

A later ProgressionEngine may consume Talent, Potential, stats, context and Skill progress to propose legal progression. Phase 7 itself must not infer such progression merely from reads, modifiers or profile values.

---

## 2. Existing legacy Skill facts

The real legacy/current runtime exposes:

```text
character_skills.entity_uid
character_skills.skill_uid
character_skills.mastery
character_skills.xp
character_skills.updated_chapter
```

`ContextBuilder` reads the active player's rows using:

```sql
SELECT entity_uid,skill_uid,mastery,xp,updated_chapter
FROM character_skills
WHERE entity_uid=?
ORDER BY mastery DESC,xp DESC
LIMIT 50
```

Therefore:

- `skill_uid` is existing identity evidence and must be preserved;
- `mastery` is persisted learned-competence state in the legacy model;
- `xp` is persisted progress-like state;
- `updated_chapter` is historical update metadata, not sufficient provenance by itself;
- `LIMIT 50` is presentation/context truncation and must never become authoritative typed-store truncation.

The Phase-7 architecture audit also confirms that `CharacterPanel` joins `character_skills.skill_uid` to `skill_definitions.skill_uid` and presents definition name/category plus mastery. Panel and prompt surfaces are presentation/transport consumers, not mastery authority.

---

## 3. XP semantic decision

### Finding

The currently inspected runtime proves that `xp` is persisted and read, but does not prove one canonical conversion such as:

```text
XP == lifetime XP
XP == XP toward next mastery point
XP == fractional mastery progress
XP == cache derived from mastery
```

No such interpretation may be invented by Core.

### Oracle contract

Until a Skill definition/rule contract explicitly defines XP semantics:

1. legacy XP must be preserved losslessly;
2. migration must not normalize or recompute it from mastery;
3. mastery must not be recomputed from XP;
4. XP must not be recomputed from mastery;
5. Talent/Potential must not be used to retroactively reinterpret historical XP;
6. if typed `PlayerSkill` retains an XP/progress field, its semantic version/rule binding must make the meaning explicit;
7. if the implementation cannot prove semantic equivalence, raw legacy XP should remain compatibility/provenance evidence rather than be silently reclassified.

Release-gating semantic test:

```text
legacy mastery = M
legacy xp = X
migration/read-through
=> typed baseMastery semantically equals M
=> XP/progress preserves X exactly unless an explicit versioned mapping proves a conversion
```

---

## 4. Learned-state oracle

Preferred contract inherited from WORK-023:

```text
no PlayerSkill row => unlearned
PlayerSkill row present => learned/acquired
```

Temporary inability to use a Skill does not delete the row and does not make the Skill unlearned.

An injury, equipment state, temporary transformation, prerequisite loss or environmental condition may alter effective usability/mastery through derived semantics, but must not erase historical learned state or base mastery.

If implementation chooses an explicit learned flag instead, tests must prove it cannot conflict with row presence. Two simultaneous authoritative learned-state truths are forbidden.

---

## 5. Base mastery authority

`PlayerSkill.baseMastery` must mean persistent learned competence independent of transient conditions.

Mandatory invariants:

```text
injury active       -> baseMastery unchanged
equipment equipped  -> baseMastery unchanged
equipment removed   -> baseMastery unchanged
temporary buff      -> baseMastery unchanged
buff expiry         -> baseMastery unchanged
Talent change       -> baseMastery unchanged
Potential change    -> baseMastery unchanged
stat modifier       -> baseMastery unchanged
resolver replay     -> baseMastery unchanged
```

Only a future legal progression/domain mutation may change base mastery permanently.

---

## 6. Effective mastery and Phase-5 extension

Accepted Phase 5 defines deterministic modifier resolution by lifecycle:

```text
PERMANENT
-> EQUIPMENT
-> INJURY
-> TEMPORARY
```

Within each lifecycle the established operation order is:

```text
ADD_FLAT
-> ADD_PERCENT
-> MULTIPLY
-> OVERRIDE
-> MIN_FLOOR
-> MAX_CAP
```

with deterministic `(priority, modifierUid)` ordering and finite guards.

If Phase 7 extends the existing target model with `SKILL_EFFECTIVE` (or equivalent), the extension must inherit these semantics exactly. It must not create a second Skill-specific modifier engine.

Required conceptual resolution:

```text
PlayerSkill.baseMastery
+ valid canonical Phase-5 modifiers targeting canonical skill UID
= effectiveMastery
```

The resolver output is rebuildable and read-only with respect to `PlayerSkill`.

---

## 7. Mastery arithmetic oracle

### M01 — base only

```text
baseMastery = 80
modifiers = []
expected effectiveMastery = 80
```

Base remains 80.

### M02 — injury penalty

```text
baseMastery = 80
INJURY ADD_FLAT -30
expected effectiveMastery = 50
```

After injury removal:

```text
effectiveMastery = 80
baseMastery = 80
```

### M03 — equipment bonus

```text
baseMastery = 80
EQUIPMENT ADD_FLAT +15
expected effectiveMastery = 95
```

Removing equipment returns effective mastery to 80, never rewrites base.

### M04 — temporary buff

```text
baseMastery = 80
TEMPORARY ADD_FLAT +20
expected effectiveMastery = 100
```

Expiry/removal returns effective mastery to 80.

### M05 — multiple lifecycle modifiers

```text
base = 80
PERMANENT +5
EQUIPMENT +15
INJURY -30
TEMPORARY +10
expected = 80
```

Trace must preserve canonical lifecycle order even if input rows are inserted in reverse/random order.

### M06 — multiply after flat within one lifecycle

```text
base entering EQUIPMENT stage = 80
EQUIPMENT ADD_FLAT +20
EQUIPMENT MULTIPLY 1.5
expected after equipment stage = 150
```

Input-list reversal must not change the result.

### M07 — additive percent

Following accepted Phase-5 semantics, multiple `ADD_PERCENT` modifiers in the same lifecycle combine against the value entering the percentage stage.

Example:

```text
stage input 100
+10%
+20%
expected 130, not 132
```

### M08 — override

```text
base = 80
EQUIPMENT +20 -> 100
INJURY OVERRIDE 50
TEMPORARY +10
expected final = 60
```

This demonstrates that lifecycle order is external to operation order. The later temporary lifecycle may still operate on the injury override result.

Two overrides in the same stage obey explicit priority then stable modifier UID tie-break according to Phase 5; insertion order is irrelevant.

### M09 — floor

```text
pre-floor mastery = 20
MIN_FLOOR 30
expected = 30
```

### M10 — cap

```text
pre-cap mastery = 120
MAX_CAP 100
expected = 100
```

### M11 — definition mastery bounds

If `SkillDefinition` declares a mastery range, final derived effective mastery must obey the explicitly chosen Phase-7 contract for those bounds. The implementation must distinguish:

- persistent base mastery validation bounds, and
- effective derived caps/bounds.

It must not silently clamp-write the authoritative base when effective mastery exceeds a contextual bound.

### M12 — deterministic tie break

Same lifecycle, same operation, same priority, different modifier UIDs.

Expected: exact same result and trace order for all permutations according to stable UID comparator.

### M13 — inactive/expired/future source

Inactive, expired or future modifiers contribute zero. Their presence must not mutate base mastery.

### M14 — source removal

Removing a source removes only that source's derived contribution. No historical base-mastery rollback occurs.

---

## 8. No-retrogression tests

### NR-SKILL-01 injury

```text
baseMastery = 80
injury -30
resolve => effective 50
close/reopen
baseMastery still 80
remove injury
resolve => effective 80
```

### NR-SKILL-02 equipment

```text
baseMastery = 80
equipment +20
resolve => 100
remove equipment
resolve => 80
baseMastery always 80
```

### NR-SKILL-03 buff expiry

```text
baseMastery = 80
temporary +25
resolve => 105
expiry
resolve => 80
baseMastery always 80
```

### NR-SKILL-04 repeated resolution

Repeated resolution under the same penalty must produce the same effective result, never cumulative degradation.

```text
80 with -10 => 70
resolve again => 70
never 60, 50, ...
```

### NR-SKILL-05 cache/rebuild

If derived Skill state is cached later:

```text
delete cache -> rebuild same inputs -> identical effective mastery
```

Deleting derived state must not alter `PlayerSkill`.

---

## 9. Talent/Potential relationship

Phase 6 establishes independent persistent profile inputs.

Legal future relationship:

```text
causal training event
+ Skill state
+ Talent domain values
+ Potential domain values
+ stats/context
+ Phase-5 derived contextual parameters
+ versioned World Pack progression rule
-> future progression proposal
```

Illegal Phase-7 shortcuts:

```text
Talent 0.9 -> set mastery to 90
Potential 0.9 -> set mastery cap to 90
high Talent -> auto-learn Skill
high Potential -> auto-increase XP
Talent update -> direct mastery write
Potential update -> direct mastery write
```

A character may legally have:

- high Talent + low current Skill mastery,
- low Talent + high current Skill mastery due to long training,
- high Potential + low current mastery,
- low Potential + high current mastery if campaign history established it and rules permit that state.

Phase 7 must preserve current facts rather than retroactively normalize them to Talent/Potential.

---

## 10. Stat relationship

Skill is not a stat projection.

Tests must assert:

1. changing `PlayerStat.baseValue` does not directly rewrite `PlayerSkill.baseMastery`;
2. a temporary stat modifier does not persist Skill mastery;
3. Skill updates do not mutate stats;
4. future requirements/rules may read effective/base stats explicitly, but that creates a dependency, not identity equivalence.

Example:

```text
Stat A base = 100
Skill X baseMastery = 40
Stat A -> 120
```

Expected in Phase 7 without a legal progression action:

```text
Skill X baseMastery remains 40
```

---

## 11. Technique boundary

Canonical distinction:

```text
Skill = general acquired competence
Technique = concrete executable method/action/ability
```

Current runtime already stores `character_skills` and `character_techniques` separately; Phase 7 must not collapse this separation.

Required semantic tests:

- learning Skill X does not automatically create Technique Y;
- increasing Skill mastery does not automatically copy the same value to Technique mastery;
- Technique use counts/success/failure do not directly become Skill mastery without a future legal progression rule;
- a legacy row that cannot be confidently classified as Skill vs Technique must remain unresolved/preserved rather than be guessed into Skill.

---

## 12. Legacy identity/reconciliation oracle

Phase 4 precedent applies:

```text
stable UID != semantic equivalence by text key/name
```

Required states:

### L01 legacy only

Legacy `skill_uid=S`, mastery M, XP X.

Expected typed Skill read exposes one semantically equivalent Skill state; never empty.

### L02 typed only

One typed Skill state is returned normally.

### L03 mixed same-looking without mapping

Legacy and typed definitions appear semantically overlapping but have different stable identities and no explicit mapping.

Expected: deterministic fail-loud ambiguity before canonical mastery resolution, not silent double authority and not key/name-only merge.

### L04 explicit alias/supersession

Explicit versioned mapping proves legacy identity maps to canonical typed UID.

Expected: exactly one canonical Skill node/mastery input; legacy bytes remain preserved for history/compatibility.

### L05 unknown/orphan legacy UID

A `character_skills.skill_uid` with no definition must not disappear. It remains lossless compatibility evidence/state and cannot be silently invented into a World Pack definition.

### L06 same display label across World Packs

Distinct stable UIDs remain distinct. Core does not auto-merge by text.

---

## 13. Determinism oracle for `SKILL_EFFECTIVE`

For the same logical request snapshot, all of the following must be invariant:

- modifier input list order,
- SQLite row order,
- map iteration order,
- source insertion order,
- reopen/reload.

Result equality includes:

- `baseMastery`,
- `effectiveMastery`,
- ordered contribution trace,
- diagnostics,
- fingerprint if Phase-5 fingerprinting is extended to Skill.

Required fingerprint inputs include at minimum all semantic inputs that affect resolution:

- campaign/character identity,
- canonical skill definition UID/version,
- base mastery/version,
- active modifier identities/versions/lifetimes/provenance,
- rule/provider version if rules are used,
- reconciliation/alias version where legacy mapping affects canonical identity,
- resolution epoch where expiry/future activation depends on time.

---

## 14. Numeric safety

The accepted Phase-5 system uses `Double` with finite guards. Phase 7 must inherit equivalent numeric discipline.

Mandatory rejection:

- NaN mastery,
- +Infinity mastery,
- -Infinity mastery,
- NaN/Infinity modifier result,
- non-finite rule output.

Mastery scale/range must come from an explicit Skill/mastery-scale contract. Core must not assume all worlds use `0..100` merely because current UI examples resemble percentages.

`-0.0` should be canonicalized consistently with Phase-5 deterministic output/fingerprints when applicable.

Overflow must fail loudly at the arithmetic boundary rather than persist/carry non-finite effective mastery.

---

## 15. Semantic release-gate matrix

| Gate | Expected |
|---|---|
| Skill vs Talent separation | PASS required |
| Skill vs Potential separation | PASS required |
| Skill vs Stat separation | PASS required |
| Skill vs Technique separation | PASS required |
| persistent baseMastery | authoritative |
| effectiveMastery | derived/rebuildable |
| injury/equipment/buff | cannot mutate baseMastery |
| Talent/Potential change | cannot directly mutate mastery |
| legacy mastery | visible through typed contract |
| legacy XP | preserved without guessed conversion |
| orphan legacy Skill UID | preserved/fail-loud, never dropped |
| mixed legacy+typed | explicit reconciliation or fail-loud |
| `SKILL_EFFECTIVE` extension | must reuse Phase-5 resolver semantics |
| modifier order | deterministic lifecycle + operation + priority + UID |
| production reads | must not inherit presentation `LIMIT 50` |
| authoritative source | typed/compatibility Skill store, not UI/prompt history |

---

## 16. Required semantic recheck after WORK-026

When CHAT-1 publishes the Phase-7 runtime result, the narrow semantic recheck must verify:

1. actual `SkillDefinition` identity/ownership contract;
2. actual `PlayerSkill.baseMastery` authority;
3. learned/unlearned representation has one source of truth;
4. legacy mastery and XP preservation policy;
5. no guessed XP-to-mastery conversion;
6. `SKILL_EFFECTIVE` reuses Phase-5 lifecycle/operation/priority semantics;
7. resolver cannot mutate PlayerSkill;
8. Talent/Potential writes remain isolated from mastery;
9. stat writes remain isolated from mastery;
10. Technique remains a separate domain;
11. mixed legacy/typed reconciliation prevents duplicate authoritative mastery;
12. authoritative reads do not truncate at 50 entries.

Any violation of items 2, 4, 6, 7, 8, 10 or 11 is a release blocker for Phase 7.

---

# Final status

`PHASE 7 SEMANTIC ORACLE READY`
