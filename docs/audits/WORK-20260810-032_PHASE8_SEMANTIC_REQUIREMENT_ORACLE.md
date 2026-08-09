# WORK-20260810-032 — Phase 8 Semantic / Requirement Oracle

Status: READ-ONLY RUNTIME / SEMANTIC ORACLE

Work ID: `WORK-20260810-032`
Owner: `CHAT-2`
Role: PHASE 8 SEMANTIC / REQUIREMENT ORACLE AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master inspected before write: `0653c6c6fe03da3db98623112f7a0af4c3f88464`
Accepted Phase 7 runtime: `8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`
Accepted Phase 6 runtime: `52af00e441131cc8e7beb4a8036e43d250f35848`
Accepted Phase 5 runtime: `44011bc0177df846a34fa12d0009d33e887f6c23`
Primary Phase 8 architecture source: `docs/audits/WORK-20260810-029_PHASE8_TECHNIQUE_ARCHITECTURE.md`

This document is an independent semantic oracle for future WORK-031 validation. It does not modify application runtime, schema, migrations, repository APIs, Technique implementation, or any other worker scope.

---

## 1. Canonical semantic separation

Phase 8 must preserve the following concepts as distinct domains:

| Concept | Authority | Meaning | Forbidden identity collapse |
|---|---|---|---|
| `TechniqueDefinition` | World-Pack-owned definition | concrete executable learned/ownable method/ability | not Skill, not canon-index row by name |
| `PlayerTechnique` learned state | AUTHORITATIVE / PERSISTENT | character knows/owns this Technique | not `isEquipped`, not temporary usability |
| Technique `baseMastery` / proficiency | AUTHORITATIVE / PERSISTENT if retained | durable proficiency with this concrete Technique | not Skill mastery |
| Technique `effectiveMastery` | DERIVED / REBUILDABLE | contextual proficiency after legal modifiers | never persisted back into base mastery |
| Technique XP/progress | PERSISTED LEGACY/PROGRESS STATE | progress-like field with semantics not yet proven | must not be guessed into mastery |
| Skill base/effective mastery | separate Phase 7 domain | general learned competence | must not be copied into Technique mastery |
| Talent | separate Phase 6 persistent profile | learning efficiency | cannot directly grant/write Technique |
| Potential | separate Phase 6 persistent profile | long-term growth headroom | cannot directly grant/write/cap Technique in Phase 8 |
| Stat | Phase 4/5 stat domain | character attribute | requirement input, not Technique identity |
| Resource | Phase 4/5 resource domain | current quantity + derived max/regen | execution-cost target, not Technique identity |
| `canon_technique_index` | reference/browser data | canon/reference information | not automatically TechniqueDefinition |

Hard invariants:

```text
Technique != Skill
Technique != Talent
Technique != Potential
Technique != Stat
Technique != Resource
Technique learned state != equipped state
Technique baseMastery != Skill baseMastery
Technique baseMastery != Technique effectiveMastery
canon index name match != Technique identity
```

A requirement relationship is a dependency, not identity equivalence.

---

## 2. Real legacy Technique facts observed

The accepted architecture audit confirms that current runtime reads `character_techniques` fields including:

```text
entity_uid
technique_uid
mastery
xp
learned_chapter
last_used_chapter
usage_count
success_count
failure_count
is_equipped
notes
```

`technique_definitions` is currently joined using `technique_uid` and runtime-visible definition data includes at least:

```text
technique_uid
name
category
base_chakra_cost
```

The player row also has a `chakra_cost_override` used by the current CharacterPanel read path.

`canon_technique_index` is a separate World Pack/reference surface with fields observed such as:

```text
name
category
rank
element_key
wiki_url
verification_status
```

These facts lead to four mandatory migration semantics:

1. `technique_uid` is existing identity evidence and must never disappear during typed integration.
2. `mastery` cannot be discarded; if promoted to typed base mastery, semantic equivalence must be explicit.
3. XP/history/loadout/notes must be preserved even when their future normalized model is not yet finalized.
4. legacy chakra-specific cost facts cannot be silently reinterpreted as a generic Resource cost without a World Pack mapping.

The current ContextBuilder Technique query has `LIMIT 60`; that is a context budget only and must never become an authoritative repository limit.

---

## 3. Learned state oracle

Preferred canonical learned-state contract:

```text
absence of PlayerTechnique => not learned/owned
presence of PlayerTechnique => learned/owned
```

This contract must remain independent of execution availability.

The following must NOT unlearn/delete a Technique:

- injury,
- insufficient current Resource,
- temporary Skill penalty,
- missing equipment at this moment,
- `is_equipped = false`,
- temporary transformation ending,
- current execution requirement becoming false,
- definition deprecation.

A true permanent Technique loss, if a future World Pack allows one, must be an explicit authoritative mutation with provenance. It cannot be a side effect of requirement evaluation.

If WORK-031 chooses an explicit learned flag in addition to row presence, it must prove there is no second conflicting learned truth. The safer contract is row presence = learned.

---

## 4. Equipped state vs learned state

Legacy `is_equipped` is persisted but semantically distinct from ownership.

Oracle classification:

```text
PlayerTechnique learned/owned = authoritative Technique domain fact
is_equipped = current/prepared/loadout selection fact
```

`is_equipped` may be important persistent campaign state, but it is not evidence that the Technique exists or does not exist in the character's learned inventory.

Required behavior:

- learned + equipped => Technique remains learned;
- learned + unequipped => Technique remains learned;
- toggling equip state does not change base mastery;
- deleting/changing a future loadout does not delete `PlayerTechnique`;
- Phase 8 must preserve legacy `is_equipped` losslessly even if the final typed loadout model is deferred;
- Phase 8 must not redesign the general Equipment domain merely to normalize this one field.

If typed `PlayerTechnique` temporarily carries an `isEquipped` compatibility field, documentation must identify it as selection/loadout state, not learned-state authority.

---

## 5. Technique mastery oracle

The legacy runtime persists Technique `mastery`. The architecture target therefore allows a separate persistent Technique proficiency.

Canonical Phase-8 contract, if WORK-031 confirms the mapping:

```text
PlayerTechnique.baseMastery
= persistent proficiency with this specific Technique
```

This value must not be copied from Skill mastery.

Legal states include:

```text
high Skill mastery + low Technique mastery
low Skill mastery + high Technique mastery
high/high
low/low
```

subject to World Pack rules and existing campaign continuity.

### M01 — base only

```text
Technique baseMastery = 70
no Technique-targeted derived effects
=> effectiveTechniqueMastery = 70
```

### M02 — temporary injury

```text
baseMastery = 70
INJURY ADD_FLAT -20
=> effective = 50
base remains 70
```

After injury removal:

```text
effective = 70
base remains 70
```

### M03 — equipment assistance

```text
base = 70
EQUIPMENT +15
=> effective = 85
```

Removing equipment returns effective to 70, never base to 55/70 through rollback logic.

### M04 — temporary buff

```text
base = 70
TEMPORARY +25
=> effective = 95
```

Buff expiry removes only the derived contribution.

### M05 — multiple lifecycles

Using accepted Phase-5 ordering:

```text
base 70
PERMANENT +5
EQUIPMENT +15
INJURY -20
TEMPORARY +10
=> effective 80
```

Storage/list insertion order must not change the result.

### M06 — override/caps

If Technique effective mastery becomes a Phase-5 target, it must inherit Phase-5 operation ordering and deterministic `(priority, modifierUid)` tie breaking. Override and cap semantics must be identical to the generic resolver, not reimplemented in a Technique-specific engine.

### No-retrogression invariant

```text
resolve
close/reopen
remove temporary source
resolve again
```

must always leave original `PlayerTechnique.baseMastery` intact unless an explicit permanent progression mutation occurred.

---

## 6. Phase-5 extension rule

Accepted Phase 7 already established the precedent of extending the generic derived foundation with `SKILL_EFFECTIVE` rather than introducing a Skill-specific resolver.

Phase 8 must follow the same architecture if effective Technique mastery is required:

```text
TECHNIQUE_EFFECTIVE
```

or a semantically equivalent generic target extension.

Required inheritance from Phase 5:

- deterministic lifecycle order,
- deterministic operation order,
- explicit priority,
- stable UID tie-breaking,
- finite-number guards,
- campaign/player isolation,
- target-kind validation,
- source lifecycle/expiry semantics,
- cycle detection where rules participate in the graph,
- no mutation of authoritative base state.

Forbidden:

```text
TechniqueModifierEngine
TechniqueEffectiveResolver separate from generic Phase 5
```

A Skill modifier must not accidentally target a Technique UID and a Technique modifier must not target a Skill UID under the wrong target kind.

---

## 7. Skill requirement semantics — core decision

A Technique may depend on a Skill, but Phase 8 must distinguish requirement purpose and mastery view.

There are at least two semantically different requirement moments:

```text
ACQUISITION requirement
= may the character learn/acquire this Technique?

EXECUTION requirement
= may the already learned Technique be used right now?
```

They must not be assumed identical.

### Required generic requirement fields

A simple threshold requirement should carry enough explicit data equivalent to:

```text
techniqueUid
requiredSkillUid
requirementPhase = ACQUISITION | EXECUTION | BOTH
masteryView = BASE | EFFECTIVE
minimumMastery
version
provenance
```

More complex requirements may use a versioned rule UID, but that rule must still declare typed dependencies.

### Critical rule: no implicit mastery view

The implementation MUST NOT silently decide that all Skill requirements mean base mastery or all mean effective mastery.

The requirement itself (or its rule) must explicitly say which is intended.

Why:

- base mastery expresses durable learned competence;
- effective mastery expresses current contextual capability;
- an acquisition rule may reasonably care about durable competence;
- an execution rule may reasonably care about current effective competence;
- some World Packs may intentionally choose the opposite for a particular Technique.

Core cannot infer this from Technique name/category.

---

## 8. Skill requirement test oracle

### RQ01 — base acquisition threshold

```text
required Skill S
requirementPhase = ACQUISITION
masteryView = BASE
minimum = 60
Skill base = 65
Skill effective = 40 due injury
```

Expected acquisition threshold evaluation: PASS because the requirement explicitly selected BASE.

No Technique mastery is copied from the Skill.

### RQ02 — effective execution threshold

```text
requirementPhase = EXECUTION
masteryView = EFFECTIVE
minimum = 60
Skill base = 65
Skill effective = 40 due injury
```

Expected execution: FAIL while injury is active.

Technique remains learned. When injury disappears and Skill effective returns above threshold, execution may pass again without relearning.

### RQ03 — effective acquisition threshold

If a World Pack explicitly defines acquisition using EFFECTIVE mastery, Core must permit that too. Temporary conditions can then affect whether acquisition is currently possible, but a failed check still cannot erase already learned Techniques.

### RQ04 — base execution threshold

If a definition explicitly uses BASE for execution, a temporary Skill mastery penalty does not change that particular threshold result. Other execution rules may still block use.

### RQ05 — missing Skill definition/player Skill

A required `skillUid` missing from canonical typed Skill input must produce a deterministic unsatisfied/missing requirement result or validation error according to requirement contract. It must never match by display name.

### RQ06 — same Skill label, different UID

Two Skill definitions can share a label. Only the exact stable `requiredSkillUid` satisfies the requirement.

### RQ07 — Skill update isolation

Changing Skill base mastery does not automatically rewrite Technique base mastery. It can only change future requirement evaluations or future progression inputs.

### RQ08 — temporary Skill modifier

A Skill modifier may make an EFFECTIVE execution requirement pass/fail temporarily. It cannot mutate Technique learned state or Technique base mastery.

---

## 9. Technique mastery != Skill mastery

The following shortcut is forbidden:

```text
Technique.baseMastery = required Skill.baseMastery
```

and so is:

```text
Technique.effectiveMastery = Skill.effectiveMastery
```

unless a World Pack explicitly defines a Technique with no independent mastery and treats execution effectiveness as a rule output. Even then, persisted legacy Technique mastery must remain preserved until explicitly reconciled.

Canonical tests:

1. Skill 90, Technique 10 => remains 90/10.
2. Skill increases 90 -> 95 => Technique remains 10 without legal Technique progression.
3. Technique improves 10 -> 20 => Skill remains 90 unless a future legal progression rule separately changes it.
4. Skill injury effective 90 -> 50 => Technique base remains 10.
5. Technique buff effective 10 -> 30 => Skill base/effective are unaffected unless separately targeted.

---

## 10. Talent and Potential boundary

Talent and Potential are future progression inputs, not Technique state.

Forbidden Phase-8 mutations:

```text
Talent high -> auto-learn Technique
Potential high -> auto-learn Technique
Talent value -> Technique mastery
Potential value -> Technique mastery
Potential value -> hard Technique mastery cap
Technique usage -> automatic Talent/Potential rewrite
```

A future ProgressionEngine may use Talent/Potential while calculating legal Technique progression or creation research. Phase 8 does not implement that engine.

Changing Talent or Potential during a Phase-8 test must leave existing Technique learned state, base mastery, XP/raw progress and history unchanged.

---

## 11. Stat boundary

Stats may be requirement/rule inputs but do not define Technique identity or mastery.

Legal:

```text
Technique execution rule requires Stat X effective >= threshold
Technique effect rule reads Stat Y
```

Illegal:

```text
Stat value -> auto-learn Technique
Technique mastery = Stat value
Technique learned state = threshold satisfied
```

Temporary stat changes may alter execution eligibility if the explicit requirement chooses effective stat semantics. They must not delete or rewrite Technique authority.

---

## 12. Resource-cost semantics

The new Core contract must express Technique costs using stable `ResourceDefinition.resourceUid` identity.

A generic cost binding should carry semantics equivalent to:

```text
techniqueUid
resourceUid
costKind
baseAmount? OR costRuleUid?
requirement/use phase if needed
version
provenance
```

The cost is a definition/rule input describing what execution would consume. Resolving or displaying cost is not itself permission to mutate `PlayerResource.currentValue`.

Actual resource spending belongs to a legal execution/domain mutation path, not to a read-only Technique resolver.

### Cost test oracle

#### C01 — flat generic resource cost

Technique cost binds `RESOURCE-A` amount 25.

If current resource is 40, requirement/cost projection may report execution affordable. It must not reduce current to 15 merely because Technique data was read/resolved.

#### C02 — insufficient resource

Current = 10, cost = 25.

Execution eligibility may fail. Technique remains learned and mastery unchanged.

#### C03 — resource max/regen not current balance

A cost must not silently consume derived `maximumValue` or `regenerationRate`. Spending targets the authoritative current resource through a future legal execution mutation.

#### C04 — missing resource UID

Cost binding to a missing/deleted ResourceDefinition must fail deterministically. Core must not synthesize a resource from cost labels.

#### C05 — same resource label, different UID

Only exact stable resource UID satisfies the binding.

---

## 13. Legacy chakra-cost boundary

Legacy fields:

```text
technique_definitions.base_chakra_cost
character_techniques.chakra_cost_override
```

are real persisted/runtime facts but universe-specific.

Oracle contract:

- preserve both losslessly;
- do not create a generic Resource cost solely because the field name contains `chakra`;
- require explicit World Pack mapping from this legacy cost axis to a canonical `ResourceDefinition.resourceUid`;
- mapping must be versioned/provenance-bearing;
- if no mapping exists, expose legacy cost as unresolved compatibility evidence, not as a guessed Core cost;
- a player override is not automatically a new Technique definition; its future classification may be a persistent player-specific cost override or derived modifier according to explicit semantics.

### C06 — explicit cost mapping

Given an explicit mapping:

```text
legacy cost axis -> canonical Resource UID R
```

and compatible scale/unit semantics, a canonical cost projection may be emitted once.

### C07 — no mapping

Legacy chakra cost remains visible in compatibility/audit state but does not affect generic Resource current balance or requirement evaluation.

### C08 — wrong mapping owner

A World Pack cannot map another pack's Technique legacy cost into an unrelated resource it does not own/legally reference according to repository ownership policy.

---

## 14. XP ambiguity oracle

Current audited Kotlin behavior proves Technique `xp` is persisted, sorted and transported. It does NOT prove whether it means:

- lifetime XP,
- progress toward next mastery threshold,
- practice points,
- usage-weighted score,
- cache derived from mastery,
- another World Pack-specific unit.

Therefore Phase 8 must not invent a conversion.

Release contract:

```text
legacy mastery = M
legacy xp = X
-> typed/read-through base mastery preserves M when semantically mapped
-> XP/raw progress preserves X exactly unless explicit rule/mapping proves conversion
```

Forbidden:

- recompute mastery from XP during migration;
- recompute XP from mastery;
- copy Skill XP into Technique XP;
- use Talent/Potential to retroactively reinterpret historical XP;
- zero unknown XP just because the typed model lacks a finalized semantic UID.

If typed `PlayerTechnique` retains `progressValue`, it should also carry explicit progress semantics identity/version when known. Unknown legacy XP may remain raw compatibility evidence until mapped.

---

## 15. Usage / success / failure history classification

Legacy contains:

```text
last_used_chapter
usage_count
success_count
failure_count
```

Oracle classification:

- these fields are persistent historical telemetry/summaries;
- they are authoritative as recorded historical summary facts **only to the extent that no more authoritative event ledger exists to reconstruct them**;
- they are not Technique learned-state authority;
- they are not base mastery authority;
- they are not XP conversion rules;
- they must be preserved losslessly during Phase 8 integration;
- Phase 8 must not fabricate missing individual usage events from aggregate counts;
- a future event ledger may make the counters rebuildable summaries, but Phase 8 must not assume such a ledger exists.

Required invariant:

```text
usage_count = 100, success_count = 90
```

must not automatically set mastery to 90 or grant a Skill/Talent/Potential value.

Corrupted historical relationships such as success+failure > usage should be reported/validated according to legacy policy, not silently rewritten unless the schema already guarantees them.

---

## 16. `learned_chapter` semantics

`learned_chapter` is historical metadata indicating when legacy state says the Technique was learned.

It is not sufficient provenance for why/how the Technique was acquired.

Phase 8 must preserve it. It may be carried into `PlayerTechnique.learnedAtChapter`, but must not fabricate a missing event UID/source/provenance story.

A missing learned chapter does not necessarily mean the Technique is unlearned if the row itself is authoritative learned state.

---

## 17. Notes semantics

Legacy `notes` is opaque persisted metadata.

Phase 8 must preserve it losslessly but must not parse free text into:

- requirements,
- costs,
- Skill links,
- Technique identity,
- Talent/Potential,
- mastery rules,
- canon-index mapping.

Any structured conversion requires an explicit migration/mapping contract.

---

## 18. Canon-index boundary oracle

`canon_technique_index` is reference/browser data, not automatically `TechniqueDefinition` authority.

Forbidden auto-link:

```text
canon index name == technique definition name
=> same Technique
```

Required tests:

1. same name, no explicit mapping => remain separate/unlinked;
2. same name across different World Packs => no merge;
3. explicit stable mapping => linked according to mapping version/provenance;
4. canon index entry exists but player row absent => does not mean learned;
5. player legacy orphan Technique exists but canon index row absent => player Technique evidence remains preserved;
6. changing browser/reference metadata cannot rewrite player mastery.

---

## 19. Legacy reconciliation oracle

Phase 8 must follow the explicit-reconciliation precedent from Phase 4/7.

### L01 — legacy-only Technique

Legacy row exists with `technique_uid=L`, mastery/history/raw XP.

Expected typed/reconciled read: one semantically equivalent compatibility Technique state or unresolved evidence; never an empty authoritative view that loses the Technique.

### L02 — orphan legacy UID

No definition exists.

Expected: row remains losslessly visible as unresolved/orphan evidence. Do not drop it because definition lookup failed.

### L03 — typed-only Technique

Canonical typed definition/player state exists with no legacy duplicate.

Expected: one canonical Technique.

### L04 — mixed same logical Technique without mapping

Legacy and typed representations appear semantically colliding but have distinct UIDs.

Expected: deterministic ambiguity/fail-loud or explicit unresolved state before canonical Phase-8 use. Never silently choose based on name/key.

### L05 — explicit alias/supersession

Versioned mapping proves legacy UID -> canonical typed UID.

Expected: exactly one canonical Technique identity in canonical reads; legacy bytes remain preserved.

### L06 — same label, distinct concepts

Two typed Techniques share display text but distinct stable UIDs/World Pack ownership.

Expected: remain separate.

### L07 — legacy casing differences

Name/key casing similarity alone does not prove equivalence.

### L08 — 1000 Techniques

Authoritative typed/reconciled reads must return all applicable entries. UI/context may truncate presentation separately.

---

## 20. Requirement failure must not mutate ownership

This is a release-blocking invariant.

For an already learned Technique:

```text
Skill requirement fails now
Resource insufficient now
Stat requirement fails now
Equipment missing now
```

Expected:

- `PlayerTechnique` still exists;
- base Technique mastery unchanged;
- XP/history unchanged;
- only execution eligibility/diagnostics change.

The same rule applies when a requirement becomes true again: Technique is usable again without being re-granted.

Acquisition checks apply before a new legal acquisition mutation. Phase 8 must not create that acquisition/creation engine on its own.

---

## 21. Semantic matrix — what may influence what

Legend: YES = legal dependency when explicit rule exists; NO = direct identity/write forbidden.

| Source | Technique learned state | Technique base mastery | Technique effective mastery | Execution eligibility | Future progression |
|---|---:|---:|---:|---:|---:|
| Skill base mastery | NO | NO | only via explicit rule, not copy | YES if requirement selects BASE | YES later |
| Skill effective mastery | NO | NO | only via explicit rule | YES if requirement selects EFFECTIVE | contextual later |
| Talent | NO | NO | not direct in Phase 8 | only if explicit future rule | YES later |
| Potential | NO | NO | not direct in Phase 8 | only if explicit future rule | YES later |
| Stat base/effective | NO | NO | possible derived rule input | YES if explicitly required | YES later |
| Resource current | NO | NO | NO | YES for affordability/availability | contextual later |
| Technique modifier | NO | NO | YES | can affect derived execution | NO permanent write |
| `is_equipped` | NO | NO | possibly contextual only | may gate use if explicit | NO |
| usage counters | NO | NO | NO direct | NO direct | future rule may inspect, never implicit |
| XP/raw progress | NO | NO direct without progression rule | NO direct | NO | future ProgressionEngine only |
| canon-index entry | NO | NO | NO | NO | NO |

---

## 22. Mandatory semantic test set for WORK-031 recheck

The semantic recheck after runtime should verify at minimum:

1. `TechniqueDefinition` identity is stable UID + World Pack ownership.
2. `PlayerTechnique` identity is campaign + character + Technique UID.
3. learned state is distinct from equipped state.
4. legacy mastery is preserved without Skill-mastery copying.
5. base Technique mastery is not mutated by temporary effects.
6. if `TECHNIQUE_EFFECTIVE` exists, it uses generic Phase-5 deterministic semantics.
7. Skill requirement stores exact stable Skill UID.
8. requirement explicitly distinguishes acquisition vs execution.
9. requirement explicitly distinguishes BASE vs EFFECTIVE mastery view, or uses an explicit rule that does so.
10. temporary Skill penalty can block an EFFECTIVE execution requirement without unlearning Technique.
11. Skill mastery update does not rewrite Technique mastery.
12. Talent/Potential updates do not rewrite Technique mastery.
13. stats/resources are inputs, never identity substitutions.
14. generic Technique cost binds Resource UID, not textual chakra.
15. evaluating cost does not spend current resource.
16. legacy `base_chakra_cost`/`chakra_cost_override` survive unresolved without explicit mapping.
17. explicit cost mapping is versioned/provenance-bearing.
18. XP is preserved without invented conversion.
19. usage/success/failure/last-used history is preserved.
20. `is_equipped` is preserved without becoming learned state.
21. notes are preserved opaquely.
22. canon-index same-name record does not auto-link/create definition.
23. orphan legacy Technique remains visible.
24. mixed legacy+typed collision requires explicit reconciliation/fail-loud.
25. explicit alias yields one canonical Technique.
26. 1000 Technique records are not truncated in authoritative reads.
27. Phase 3–7 authoritative domains remain unchanged.

---

## 23. Explicit non-goals

This oracle does not authorize Phase 8 to implement:

- DevelopmentProject,
- Technique invention/creation workflow,
- arbitrary AI grants,
- ProgressionEngine,
- Skill progression,
- Talent/Potential progression,
- equipment redesign,
- general action execution transaction engine,
- resource spending transaction engine,
- Technique usage event ledger redesign,
- CharacterPanelSnapshot v2,
- next roadmap phase.

---

## 24. Final semantic contract

The safe Phase-8 model is:

```text
TechniqueDefinition
= stable World-Pack-owned concrete ability definition

PlayerTechnique
= persistent character ownership + durable Technique-specific state

baseTechniqueMastery
= persistent Technique proficiency when supported by the mapped data/model

effectiveTechniqueMastery
= rebuildable Phase-5-derived projection, never a second authority

Skill requirement
= explicit dependency on stable Skill UID + explicit requirement phase + explicit base/effective mastery view or typed rule

Resource cost
= generic stable Resource UID binding/rule; evaluation is not resource spending

legacy XP
= preserve, do not invent semantics

is_equipped
= selection/loadout state, not learned state

usage/success/failure
= preserved historical telemetry/summaries, not mastery authority

canon_technique_index
= reference data, not identity by name
```

No semantic conflict was found between this oracle, accepted Phase 3–7 contracts, and WORK-029 architecture.

# Final status

**PHASE 8 SEMANTIC ORACLE READY**
