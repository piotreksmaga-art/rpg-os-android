# WORK-20260810-028 — Phase 7 Migration / Legacy Integrity Plan

Status: READ-ONLY RUNTIME / PRE-IMPLEMENTATION VALIDATION PLAN

Work ID: `WORK-20260810-028`
Owner: `CHAT-3`
Role: PHASE 7 MIGRATION / LEGACY INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master at plan creation: `b08e8861fa3d3095584f7cd1bf8cdf827b3cd373`
Accepted Phase 6 runtime: `52af00e441131cc8e7beb4a8036e43d250f35848`
Phase 7 implementation work item: `WORK-20260810-026`
WORK-026 resultCommit at plan creation: NOT FOUND
Allowed write scope: this report only.

This document defines the independent migration, compatibility, reconciliation and no-regression gates for Phase 7 (`SkillDefinition + PlayerSkill`). It does not implement runtime, schema, migration, resolver changes, Technique, ProgressionEngine or Phase 8.

---

## 1. Canonical authority contract

Phase 7 must establish:

```text
Skill
= current learned competence / proficiency

Skill != Talent
Skill != Potential
Skill != Technique
Skill != Stat
```

Persistent authority:

```text
PlayerSkill.baseMastery
```

Derived/rebuildable state:

```text
PlayerSkill effective mastery
```

Temporary injury/equipment/buff state may alter effective mastery only. It must never rewrite persistent mastery merely because a modifier is active, expires or is removed.

Logical player-skill identity must be campaign + character + stable Skill UID. Display name/key alone is never global identity.

---

## 2. Existing legacy Skill inventory observed before implementation

The current project already has real Skill persistence and therefore Phase 7 is an integration/migration phase, not greenfield persistence.

Confirmed runtime contract from existing audit/code:

### `character_skills`

At least:

```text
entity_uid
skill_uid
mastery
xp
updated_chapter
```

Interpretation frozen for validation:

- `entity_uid` = legacy character identity and must be preserved exactly;
- `skill_uid` = existing identity evidence and must never be discarded, even if no current definition resolves it;
- `mastery` = persistent learned competence in current runtime and must survive Phase 7 losslessly unless an explicit versioned conversion proves otherwise;
- `xp` = persisted progress-like state whose exact semantic scale is not yet proven by Core; preserve exactly and do not silently reinterpret;
- `updated_chapter` = historical metadata only; it is not sufficient provenance to invent training history.

### `skill_definitions`

Existing CharacterPanel joins `character_skills.skill_uid` to `skill_definitions.skill_uid` and exposes at least:

```text
skill_uid
name
category
```

The final validator must inspect the real bundled schema/data on WORK-026 rather than assume additional columns.

### Existing readers

CharacterPanel currently reads Skills directly from legacy SQL.

ContextBuilder currently reads active-player Skill rows using the authoritative player UID and includes:

```sql
ORDER BY mastery DESC,xp DESC
LIMIT 50
```

The `LIMIT 50` is a context/presentation limit only. Any new authoritative Skill store/read that silently returns at most 50 is a release blocker.

Backend prompt semantics already treat player Skills and Techniques as learned abilities, but prompt/UI output is not Skill authority.

---

## 3. Legacy integrity risks

### R7-01 — empty typed read beside populated legacy state

Release blocker if a pre-Phase-7 campaign contains `character_skills` but the new typed Skill API returns empty merely because canonical Phase 7 rows do not yet exist.

### R7-02 — dual authoritative mastery

Release blocker if legacy Skill and typed PlayerSkill for the same logical Skill can both surface as independent authoritative entries without explicit reconciliation.

### R7-03 — global key/name merge

Core must not assume same key/name globally means same logical Skill. Different World Packs may legally use equal textual keys/names with different stable UIDs.

### R7-04 — orphan Skill UID loss

A legacy `skill_uid` with no resolvable `skill_definitions` row must remain visible/preserved as compatibility evidence/state. It must not disappear merely because metadata is missing.

### R7-05 — XP semantic invention

Migration must not convert legacy XP into mastery, Talent, Potential, stat progression or another scale unless an explicit versioned mapping proves the semantics.

### R7-06 — Technique leakage

Legacy ambiguity must never cause a Technique row/concept to be silently imported as Skill authority.

### R7-07 — player/campaign leakage

Migration/read correctness must not depend on ActivePlayerRef except when an active-player convenience API is explicitly requested. All eligible legacy rows for every character must remain scoped and available.

### R7-08 — production migration entrypoint regression

Phase 7 must be reachable through the real latest-schema application path. The Phase 6 entrypoint defect must not recur.

---

## 4. Required compatibility/reconciliation contract

The final implementation must adopt one explicit, deterministic contract.

Preferred acceptable pattern:

```text
legacy Skill evidence/compat identity
    -> explicit alias/supersession mapping
    -> canonical typed Skill UID
```

Requirements:

- mapping is explicit, versioned and provenance-bearing;
- mapping is campaign-safe and World-Pack-safe;
- mapping cannot be hijacked by another owner;
- after confirmed mapping, typed representation is the canonical read identity;
- legacy bytes remain intact for compatibility/history;
- mapped compatibility projection is suppressed so canonical read yields exactly one logical Skill;
- unmapped legacy Skills remain visible/preserved;
- same-looking legacy + typed state without explicit mapping must fail loudly as semantic ambiguity or expose a clearly non-authoritative unresolved compatibility state; it must not silently pick one.

Forbidden reconciliation:

- global `same key == same Skill`;
- global `same display name == same Skill`;
- row-order precedence;
- always prefer typed because it is newer;
- delete legacy rows after typed registration;
- merge/average mastery values.

---

## 5. Migration gates

### M7-01 — old campaign opens

Fixture contains Phase 3/4/5/6 state and legacy Skills but no Phase 7 objects.

Normal application current-schema path must open it successfully.

### M7-02 — typed equality for legacy Skill

For each legacy row with semantically safe representation, typed read after migration/compatibility must preserve:

- character identity,
- logical Skill identity/evidence,
- mastery exactly under declared conversion,
- XP exactly if semantics are preserved as opaque progress,
- source metadata needed for audit.

### M7-03 — unknown/orphan UID

Legacy unknown Skill UID remains represented losslessly and does not disappear because definition metadata is missing.

### M7-04 — all players

Migration/compatibility processes or exposes all relevant entity UIDs, not only active player.

### M7-05 — idempotency

Repeated latest-schema ensure and reopen must produce:

- one Phase 7 migration marker per migration identity,
- no duplicate Skill definitions/values/aliases,
- unchanged mastery/XP,
- unchanged versions unless a real authoritative mutation occurred.

### M7-06 — transaction safety

Partial migration failure must not leave a success marker beside missing required objects or half-materialized canonical Skill state.

### M7-07 — no truncation

1000+ Skill definitions/player values and 1000+ legacy Skill rows must remain readable without hidden LIMIT behavior.

---

## 6. Production entrypoint gates

This is release-blocking.

The final candidate must prove that the latest schema chain includes Phase 7 from the real application path.

Required routing evidence:

- bootstrap / normal open;
- reopen;
- restore;
- campaign switch.

If these all route through one production `ensureCurrentSchema()` equivalent, code proof plus representative production-path tests are sufficient.

Required old-campaign reproduction:

```text
Phase 6 campaign DB + legacy character_skills
-> real LocalGameStore/current-schema flow
-> Phase 7 tables/objects exist
-> Phase 7 migration marker exists exactly once
-> typed Skill read sees semantically equivalent legacy state
```

A test that calls `MigrationManager.ensureV7()` directly but bypasses LocalGameStore/current schema is not sufficient by itself.

---

## 7. No-regression snapshot

Before applying Phase 7 migration, capture semantic snapshots and row counts/checksums where practical for:

### Phase 3

- `active_player_ref`.

### Phase 4

- `stat_definitions`,
- `player_stats` / all base values,
- `resource_definitions`,
- `player_resources` / all current values,
- `legacy_stat_aliases`,
- `legacy_resource_aliases`,
- legacy stat/resource bytes.

### Phase 5

- `modifiers` including lifecycle/source/value/version fields.

### Phase 6

- `progression_domain_definitions`,
- `talent_profile_entries`,
- `potential_profile_entries`,
- `legacy_progression_evidence`,
- `legacy_progression_mappings`.

After migration, all must remain semantically identical except for independent Phase 7 additions.

Any Phase 7 migration that changes ActivePlayerRef, stat/resource authority, modifiers, Talent/Potential or their legacy mappings is a release blocker unless separately authorized.

---

## 8. Required legacy fixtures

At minimum:

1. normal legacy Skill with definition, mastery and XP;
2. unknown/orphan legacy `skill_uid`;
3. duplicate legacy rows if schema permits malformed historical data;
4. same Skill UID under two players;
5. same character UID string in two campaigns;
6. mastery = 0;
7. high finite mastery;
8. negative legacy mastery corruption fixture;
9. NaN/Infinity injection where SQLite/runtime permits corruption fixture;
10. XP = 0;
11. large XP;
12. unknown XP semantics fixture;
13. same display name/key under two World Packs, distinct stable UIDs;
14. mixed legacy + typed same logical Skill without mapping;
15. mixed legacy + typed with explicit mapping;
16. mapping target missing;
17. mapping owner mismatch;
18. mapping version mismatch;
19. 1000+ legacy Skills;
20. legacy Skill-like versus Technique-like ambiguity fixture.

Malformed/corrupt legacy fixtures may be quarantined/fail-loud rather than promoted, but source data must not be silently deleted.

---

## 9. Concrete validation matrix after WORK-026

### Definitions / ownership

- V7-01 register typed SkillDefinition;
- V7-02 duplicate exact UID + incompatible metadata fails;
- V7-03 duplicate same-pack key under another UID follows explicit policy/fails;
- V7-04 same label/key across different World Packs can coexist when identities differ;
- V7-05 ownership hijack fails;
- V7-06 missing progression domain binding fails or remains explicitly absent according to contract;
- V7-07 domain binding never mutates Talent/Potential.

### Player Skill

- V7-10 learn/create PlayerSkill;
- V7-11 baseMastery persists across reopen;
- V7-12 XP/progress persists exactly under declared semantics;
- V7-13 player isolation;
- V7-14 campaign isolation;
- V7-15 absence means unlearned if implementation adopts that contract;
- V7-16 invalid/non-finite mastery fails loudly;
- V7-17 no hidden 0..100 assumption unless explicitly defined;
- V7-18 1000+ values no truncation.

### Legacy compatibility

- V7-20 legacy-only Skill visible through typed contract;
- V7-21 unknown/orphan UID preserved;
- V7-22 all players visible, independent of active player;
- V7-23 mixed legacy+typed same logical Skill without mapping fails loudly/unresolved;
- V7-24 explicit alias produces exactly one canonical Skill;
- V7-25 typed canonical value precedence after explicit alias is deterministic;
- V7-26 legacy bytes remain present after alias;
- V7-27 unrelated unmapped legacy Skills remain visible;
- V7-28 same text key across World Packs is not auto-merged;
- V7-29 mapping cannot be hijacked by another World Pack;
- V7-30 repeated migration/reopen preserves reconciliation.

### Effective mastery / Phase 5 boundary

If WORK-026 extends Phase 5 with `SKILL_EFFECTIVE` or equivalent:

- V7-40 baseMastery 80 + injury -30 => effective 50, base remains 80;
- V7-41 remove injury => effective 80, base unchanged;
- V7-42 equipment effect changes derived mastery only;
- V7-43 temporary buff changes derived mastery only;
- V7-44 insertion/order determinism matches accepted Phase 5 semantics;
- V7-45 player/campaign isolation for Skill modifiers;
- V7-46 stat modifier cannot silently target Skill UID and Skill modifier cannot silently target stat UID;
- V7-47 duplicate modifier UID policy remains unchanged;
- V7-48 resolver execution never writes PlayerSkill;
- V7-49 reopen with active modifier preserves base mastery.

### Talent/Potential and Technique boundaries

- V7-50 Talent update cannot write mastery;
- V7-51 Potential update cannot write mastery or hard-set a mastery cap in Phase 7;
- V7-52 Skill write cannot mutate PlayerStat.baseValue;
- V7-53 Skill creation cannot automatically create Technique rows;
- V7-54 Technique ownership cannot be silently imported as Skill ownership by name/key matching.

### Schema / migration / integrity

- V7-60 old Phase 6 DB opens through production latest-schema path;
- V7-61 Phase 7 marker/object creation is idempotent;
- V7-62 bootstrap reaches latest schema;
- V7-63 restore reaches latest schema;
- V7-64 campaign switch reaches latest schema;
- V7-65 upstream Phase 3/4/5/6 state remains unchanged;
- V7-66 `PRAGMA integrity_check` = `ok`;
- V7-67 `PRAGMA foreign_key_check` returns no violation under adopted FK policy;
- V7-68 no silent SQL failure becomes an empty legal Skill profile.

---

## 10. XP-specific acceptance rule

The final validator must not approve a migration that merely assumes legacy `xp` semantics from the column name.

Acceptable outcomes:

1. implementation proves legacy XP semantics and preserves/maps it deterministically under a versioned rule; or
2. typed contract carries it as opaque/preserved legacy progress evidence until a later explicit rule maps it.

Unacceptable outcomes:

- discard XP;
- set XP to zero;
- infer mastery from XP without a proven rule;
- infer Talent/Potential from XP;
- reinterpret XP by World-specific Core literals.

---

## 11. Orphan and duplicate policy

### Orphan Skill UID

Missing definition is not permission for data loss. Final implementation must either:

- expose unresolved compatibility Skill evidence; or
- provide a deterministic compatibility definition identity clearly separated from canonical World-Pack ownership.

It must not invent semantic metadata beyond what legacy data proves.

### Duplicate legacy rows

If historical DB permits duplicate `(entity_uid, skill_uid)` rows, final migration must fail loudly/quarantine or use an explicit deterministic versioned reconciliation rule. SQLite row order/latest arbitrary row must not define authority.

---

## 12. Restore / backup compatibility

Because backups preserve campaign DB state, final validation must verify:

- restoring a pre-Phase-7 backup triggers current schema and makes legacy Skills visible typed;
- restoring a post-Phase-7 backup preserves typed Skills, mappings and legacy bytes;
- restore does not change ActivePlayerRef or unrelated Phase 3/4/5/6 authority;
- repeated restore/open does not duplicate migration output.

---

## 13. MUST-HAVE release blockers before Phase 7 can pass CHAT-3

CHAT-3 will issue FAIL if any of these are reproduced:

1. legacy `character_skills` exists but typed Skill read is empty;
2. unknown/orphan legacy Skill disappears;
3. mixed legacy+typed same logical Skill yields two authoritative mastery values;
4. semantic reconciliation happens globally by name/key;
5. legacy bytes are destructively removed;
6. XP is silently discarded or reinterpreted without proven mapping;
7. migration only handles active player;
8. player/campaign isolation fails;
9. production current-schema path does not execute Phase 7;
10. bootstrap/restore/campaign switch can leave old schema;
11. migration mutates ActivePlayerRef, stats/resources, modifiers or Talent/Potential;
12. temporary modifier rewrites baseMastery;
13. Talent/Potential directly rewrite mastery;
14. Skill persistence automatically creates Technique authority;
15. hidden LIMIT truncates authoritative Skill reads;
16. migration is not idempotent;
17. integrity/FK checks fail;
18. exact candidate CI fails.

---

## 14. Evidence required for final verdict

After WORK-026 appears, CHAT-3 will inspect the exact resultCommit, diff, schema/migration chain, production `LocalGameStore.ensureCurrentSchema()` routing, typed Skill store/repository/API, legacy reconciliation logic, Phase-5 target extension, tests and exact CI.

The final verdict must be exactly one of:

`PHASE 7 INTEGRITY VALIDATION: PASS`

or

`PHASE 7 INTEGRITY VALIDATION: FAIL`

No runtime PASS/FAIL is issued by this pre-implementation plan.

---

# Current status

**PHASE 7 MIGRATION / LEGACY INTEGRITY PLAN READY — WAITING FOR WORK-20260810-026 RESULT COMMIT**
