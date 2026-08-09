# WORK-20260810-022 — Phase 6 Migration / Integrity Plan

Status: READ-ONLY RUNTIME / PRE-IMPLEMENTATION MIGRATION-INTEGRITY PLAN

Work ID: `WORK-20260810-022`
Owner: `CHAT-3`
Role: PHASE 6 MIGRATION / INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Coordinator-issued baseline: `387a0c331eaa11863529a4eababa8dd580c30ff2`
Fresh master observed before plan write: `e07891d0ae4188a166fc46781ad698e8d4458175`
Accepted Phase 5 runtime: `44011bc0177df846a34fa12d0009d33e887f6c23`
Phase 6 implementation work item: `WORK-20260810-020`
WORK-020 resultCommit at plan creation: **NOT FOUND**
Allowed write scope: this report only.

This document is the independent migration/persistence/integrity oracle for Phase 6 (`TalentProfile + PotentialProfile`). It does not implement Phase 6 runtime, schema, migrations, ProgressionEngine, Skill, Technique, PlayerDomainEngine, PlayerChangeSet, CharacterPanelSnapshot v2, or Phase 7.

The final runtime verdict will be issued only after CHAT-1 publishes a concrete `WORK-20260810-020` resultCommit.

---

## 1. Canonical authority contract under validation

Phase 6 introduces two distinct authoritative persistent profile axes:

```text
Talent
= ease / efficiency of learning and development in a declared progression domain

Potential
= long-term growth headroom / scale / ceiling-like capacity in a declared progression domain/dimension
```

Hard separation:

```text
Talent != Potential
Talent != Skill Level
Talent != current/base stat
Talent != current power
Potential != Skill Level/mastery
Potential != current/base stat
Potential != current power
```

All four combinations are legal:

- high Talent + low Potential,
- low Talent + high Potential,
- high Talent + high Potential,
- low Talent + low Potential.

A temporary contextual effect may change only a derived future progression parameter through the accepted Phase 5 modifier/rule foundation. It may not rewrite authoritative `TalentProfile` or `PotentialProfile` state.

The migration validator therefore treats these as separate authority classes:

### AUTHORITATIVE

- progression-domain definitions owned by World Packs,
- Talent entries,
- Potential entries,
- domain/dimension stable identity,
- profile/entry version,
- provenance,
- explicit legacy mapping metadata if persisted.

### DERIVED / REBUILDABLE

- effective learning parameter,
- effective breakthrough parameter,
- effective adaptation/scaling parameter,
- temporary/contextual projections,
- presentation labels inferred from profile values.

Derived output must never become fallback authority for missing/corrupt profile rows.

---

## 2. Upstream baseline that must remain unchanged

A correct Phase 6 migration must leave existing authoritative state byte/semantic-equal except for explicitly new Phase 6 objects/markers.

Mandatory preserved state:

### Phase 3

- `active_player_ref`,
- selected player identity for every campaign.

### Phase 4

- `stat_definitions`,
- `player_stats`, including every `PlayerStat.baseValue`,
- `resource_definitions`,
- `player_resources`, including every `PlayerResource.currentValue`,
- `legacy_stat_aliases`,
- `legacy_resource_aliases`,
- legacy `character_stats`,
- legacy resource-like source fields/bytes.

### Phase 5

- `modifiers`, including UID, campaign/character scope, target, lifecycle, operation, value, priority, source identity, active/lifetime state, provenance and version,
- migration marker `RPGOS-5.0-DERIVED-MODIFIERS`,
- accepted semantics that resolver execution is pure with respect to authoritative state.

No Phase 6 migration may rewrite, normalize, clamp, infer, backfill or reinterpret any of the above merely to seed Talent/Potential.

---

## 3. Expected Phase 6 persistence shape to audit

The exact runtime names are implementation decisions, but the validator expects the final schema to express at least these logical identities.

### Progression domain definition

```text
domainUid
worldPackUid
stable key/presentation metadata
definitionVersion
capability/semantic metadata for Talent/Potential
```

Identity is stable UID, never display text.

### Talent logical key

```text
(campaignId, characterUid, domainUid)
```

### Potential logical key

```text
(campaignId, characterUid, domainUid, dimensionUid)
```

if the implementation adopts the architecture's dimension axis. If Potential dimensions are represented differently, equivalent collision-free identity is still mandatory.

Every canonical profile row must retain:

- finite declared numeric value,
- version >= 1 or equivalent valid version policy,
- non-empty semantic provenance,
- valid campaign/character scope,
- valid domain ownership.

A single generic `growthRating` replacing Talent + Potential is a blocker.

---

## 4. Migration invariants

### M6-01 — Additive migration

Old Phase 3/4/5 campaign DB opens through the normal current-schema entrypoint and gains only Phase 6 schema/markers required by the implementation.

Expected:

- no destructive table rebuild of upstream authority,
- no delete/update of stats/resources/modifiers/aliases/active-player merely because Phase 6 is added,
- no automatic generated profile values without proven semantic mapping.

### M6-02 — Idempotency

Run schema/migration ensure repeatedly and reopen.

Expected:

- one Phase 6 migration marker per migration identity,
- stable domain/profile/mapping row counts,
- no duplicate profile rows,
- no version inflation caused solely by migration retry,
- no repeated synthetic provenance,
- upstream state unchanged.

### M6-03 — Transaction safety

Phase 6 DDL/data initialization and its migration marker must not produce a false-complete state.

If failure is injected before migration commit:

- no applied marker may claim success while required objects/data rolled back,
- no partial canonical Talent/Potential backfill may remain valid-looking,
- upstream Phase 3/4/5 state remains unchanged.

### M6-04 — Existing campaign compatibility

Campaign with no Phase 6 tables/data must still open successfully and produce a valid empty canonical profile state when no mappings exist.

A legitimate empty profile must be distinguishable from SQL/migration/load failure.

### M6-05 — All players, not only ActivePlayerRef

If explicit legacy mappings cause migration, the migration must process all eligible characters in scope, not only whichever character is currently active.

Changing ActivePlayerRef before migration must not change which source records are semantically eligible.

### M6-06 — Canonical preexisting rows win

If a canonical Phase 6 row already exists for a semantic identity and mapped legacy evidence points at the same target:

- typed output contains exactly one canonical logical entry,
- canonical row remains authority,
- legacy evidence remains audit/provenance evidence,
- disagreement is diagnostic, never average/row-order selection/duplicate logical truth.

### M6-07 — No automatic profile synthesis from achievements

Never seed Talent/Potential from:

- current Skill mastery/XP,
- current `PlayerStat.baseValue`,
- current resource quantity,
- rank,
- rare technique ownership,
- evolution/bloodline stage,
- combat result,
- narrative adjectives.

---

## 5. Legacy evidence architecture

Phase 6 legacy compatibility requires a stricter two-layer model than Phase 4 stat/resource compatibility.

### Layer A — opaque source evidence

A legacy record may be preserved/identified using deterministic compatibility evidence identity containing, when available:

- campaign,
- character,
- source table/field/key,
- original source value/bytes,
- source schema/version,
- evidence UID,
- classification status,
- mapping status.

Opaque evidence is **not** a Talent entry, **not** a Potential entry, **not** a modifier, and **not** a progression input.

### Layer B — typed canonical mapping

Promotion to canonical profile is legal only after the mapping proves:

- axis = `TALENT` or `POTENTIAL`,
- exact World Pack/domain UID,
- Potential dimension if required,
- source scale/unit and deterministic conversion,
- supported source schema/version,
- campaign + character identity,
- source value is authoritative base profile semantics rather than current/derived observation,
- mapping version,
- provenance.

Stable evidence identity does not manufacture stable domain meaning.

---

## 6. Required legacy classification fixtures

The validation suite will instantiate each label in at least a bare/no-metadata fixture and, where useful, an explicitly mapped fixture.

| Legacy fixture | Bare/default classification | Validation rule |
|---|---|---|
| `talent` | **EXPLICIT MAPPING REQUIRED** | Talent-like family is plausible, but domain, scope, scale and owner are unknown. No canonical row without mapping. |
| `gifted` | **AMBIGUOUS** | May mean narrative praise, Talent, achievement, bloodline, reputation or another trait. Preserve unresolved. |
| `aptitude` | **EXPLICIT MAPPING REQUIRED** | Learning aptitude is plausible, but exact domain/scale/owner must be declared. |
| `growth_rate` | **AMBIGUOUS** | Could be learning speed, stat growth, biological growth, regen or Potential. Preserve unresolved. |
| `learning_rate` | **EXPLICIT MAPPING REQUIRED** | Strong Talent-side signal, but domain/scope/scale are still missing. |
| `maximum_potential` | **EXPLICIT MAPPING REQUIRED** | Strong Potential-side signal, but hard cap vs scale, domain and dimension are not proven. |
| `affinity` | **AMBIGUOUS** | May mean eligibility, compatibility, output/cost modifier or learning aptitude. Preserve unresolved. |
| `adaptation` | **AMBIGUOUS** | May represent current adaptation state/history rather than adaptation Potential. Preserve unresolved. |
| `evolution_potential` | **EXPLICIT MAPPING REQUIRED** | Potential-like semantics are plausible, but exact evolution domain/dimension/scale and distinction from current eligibility/stage require explicit mapping. |

No bare-label fixture is `SAFE AUTO-MAP`.

A **SAFE** fixture exists only when versioned source metadata itself proves the complete semantic mapping contract above. The validator will include positive SAFE examples to prove that the system supports exact automatic mapping when evidence is genuinely sufficient.

---

## 7. Legacy test matrix

### L6-01 — bare `talent`

No mapping -> zero canonical Talent/Potential rows from this evidence; source survives unresolved.

### L6-02 — bare `gifted`

No mapping -> opaque unresolved evidence only.

### L6-03 — bare `aptitude`

No mapping -> no canonical row.

### L6-04 — bare `growth_rate`

No mapping -> ambiguous, mechanically inert.

### L6-05 — bare `learning_rate`

No mapping -> no canonical row.

### L6-06 — bare `maximum_potential`

No mapping -> no canonical Potential row.

### L6-07 — bare `affinity`

Numeric value such as `0.9` still remains ambiguous and cannot become Talent automatically.

### L6-08 — bare `adaptation`

No automatic adaptation Potential.

### L6-09 — bare `evolution_potential`

No canonical Potential row until World Pack/domain/dimension/scale/version semantics are explicit.

### L6-10 — exact typed Talent SAFE mapping

Source metadata proves axis/domain/owner/scale/version/base-authority. Expected one canonical Talent row with migration provenance.

### L6-11 — exact typed Potential SAFE mapping

Source metadata additionally proves dimension if required. Expected one canonical Potential row.

### L6-12 — explicit World Pack mapping

Bare plausible evidence plus a valid versioned mapping produces exactly one canonical row.

### L6-13 — mapping target missing

Fail/quarantine; do not synthesize a domain from label/key.

### L6-14 — mapping target owner changed

Fail integrity validation; no silent ownership transfer.

### L6-15 — mapping version mismatch

Fail/invalidate according to explicit policy; no silent stale mapping.

### L6-16 — unsupported source schema/version

No auto-map even when label is familiar.

### L6-17 — canonical + mapped legacy same target

Exactly one canonical typed entry; legacy retained as evidence; disagreement diagnostic.

### L6-18 — later mapping introduced after canonical entry exists

Reconciliation detects existing canonical target and creates no duplicate.

### L6-19 — unresolved evidence survives reopen

Original source evidence remains available and mechanically unresolved after repeated migrations/reopens.

### L6-20 — source bytes preservation

Where source is legacy DB data, compare source values/bytes before and after Phase 6 migration. No destructive cleanup is allowed in this work item.

---

## 8. Persistence / reopen invariants

### P6-01 — Four quadrants survive reopen

Persist four characters or domains representing high/low combinations and reopen DB.

Expected exact Talent/Potential independence.

### P6-02 — Talent update isolation

Update one Talent entry.

Expected unchanged:

- every Potential entry,
- unrelated Talent domains,
- stats/resources,
- Skill data,
- modifiers.

### P6-03 — Potential update isolation

Symmetric to P6-02.

### P6-04 — provenance survives reopen

Every canonical profile row reloads with the same declared provenance/version.

### P6-05 — no profile mutation from read

Repeated profile reads produce no version/value/provenance changes.

### P6-06 — failed load != empty profile

Induce missing/corrupt expected Phase 6 object/query failure. Repository/store must surface an error/invalid state, not silently return a plausible empty profile.

---

## 9. Campaign / player / domain isolation

### I6-01 — Campaign isolation

Campaign A and B reuse the same character UID/domain UID strings but hold different profile values.

Expected no leakage in reads, writes, migration or reopen.

### I6-02 — Player isolation

PLAYER-A and PLAYER-B in one campaign share a domain definition.

Update/migrate A only. B remains byte/semantic-equal.

### I6-03 — ActivePlayerRef switching

A -> B -> A must not reuse stale profile rows from another player.

### I6-04 — Domain isolation

Talent/Potential for domain A cannot become a value for domain B through same display label or category.

### I6-05 — same text label, different UID

Two domains with display `Focus` but distinct stable UIDs remain separate.

### I6-06 — cross-World-Pack same display/key

Pack A and Pack B may define equivalent-looking labels under distinct UIDs. They remain isolated.

### I6-07 — duplicate domain UID

Different/incompatible definition under the same stable domain UID must fail loudly. No last-write-wins ownership hijack.

### I6-08 — ownership validation

A profile/mapping cannot target a domain owned by another World Pack under an incompatible declared owner.

### I6-09 — missing/deleted domain

Canonical profile row whose required domain definition is missing must surface explicit integrity/dependency failure or documented quarantine state. Never silently remap by label.

---

## 10. Numeric / version / provenance integrity

The implementation must declare its production numeric scale before PASS.

Required validation:

- legal lower boundary,
- legal upper boundary,
- below-min reject,
- above-max reject,
- NaN reject,
- +Infinity reject,
- -Infinity reject,
- extremely large finite value behavior,
- negative value behavior,
- `-0.0` canonicalization policy if fingerprints/byte identity care about it,
- deterministic serialization/rounding,
- profile/domain version validity,
- missing provenance reject,
- blank semantic provenance reject,
- unsupported mapping/definition version fail-loud.

Migration must never silently clamp an ambiguous legacy value into a seemingly valid profile value.

---

## 11. Phase 5 interaction / non-mutation tests

Accepted Phase 5 is the only generic modifier/derived foundation. Phase 6 must not introduce a second Talent/Potential modifier engine.

### F6-01 — temporary learning effect

Persistent Talent = X.

Apply a temporary learning/context effect through the Phase 5-compatible derived path.

Expected:

- effective parameter may differ,
- persisted Talent remains exactly X before/during/after,
- effect expiry/removal changes only derived output.

### F6-02 — temporary breakthrough effect

Persistent Potential = Y. Temporary breakthrough/context effect may change only a derived parameter; base Potential remains Y.

### F6-03 — injury learning penalty

Penalty never lowers stored Talent.

### F6-04 — environment/mentor synergy

Context never becomes persistent profile change without a separate future legal committed domain mutation.

### F6-05 — modifier table preservation

Creating/updating/migrating Talent/Potential must not rewrite existing Phase 5 modifier rows.

### F6-06 — resolver write attack

No path from `DerivedValueResolver` or generic modifier application may write Talent/Potential persistent tables.

### F6-07 — profile update is not represented as temporary modifier

A true authoritative profile update remains a profile-domain change with provenance/version, not an accidental TEMPORARY/PERMANENT modifier surrogate.

---

## 12. Upstream no-mutation oracle

For migration tests, capture normalized before/after snapshots.

### Active player

```text
campaign_id, player_uid, updated_at
```

### PlayerStat

```text
campaign_id, character_uid, stat_uid, base_value, version
```

### PlayerResource

```text
campaign_id, character_uid, resource_uid, current_value, version
```

### Stat/resource aliases

```text
campaign_id, legacy_uid, canonical_uid, world_pack_uid, mapping_version, provenance
```

### Modifier

```text
campaign_id, modifier_uid, character_uid, target_definition_uid, target_kind,
lifecycle, operation, modifier_value, priority, source_type, source_uid,
source_active, valid_from, valid_until, active, provenance, version
```

### Legacy source

Exact relevant row/value/byte representation for Talent/Potential-like evidence.

Any upstream difference without an independently authorized cause is a Phase 6 migration failure.

---

## 13. Required database fixtures

Use real SQLite campaign databases where persistence is involved.

1. `phase5-clean-no-phase6.db` — Phase 3/4/5 state, no Phase 6 tables.
2. `phase5-two-players.db` — two players with shared domain candidates.
3. `phase5-two-campaigns/` — overlapping UIDs across campaign DBs.
4. `legacy-profile-labels.db` — all nine requested bare labels.
5. `legacy-safe-talent.db` — fully typed source metadata proving Talent.
6. `legacy-safe-potential.db` — fully typed source metadata proving Potential.
7. `legacy-explicit-map.db` — mapping registry required.
8. `legacy-ambiguous.db` — ambiguous values retained unresolved.
9. `canonical-plus-mapped-legacy.db` — same semantic target in canonical + legacy evidence.
10. `mapping-target-missing.db`.
11. `mapping-owner-changed.db`.
12. `mapping-version-mismatch.db`.
13. `same-label-two-worldpacks.db`.
14. `duplicate-domain-uid.db`.
15. `missing-domain-profile.db`.
16. `four-quadrants.db`.
17. `temporary-learning-effect.db`.
18. `temporary-breakthrough-effect.db`.
19. `large-phase6.db` — >1000 domain/profile/mapping rows.
20. `post-phase6-backup.db` — valid canonical profiles for backup/reopen if implementation exposes backup lifecycle.

---

## 14. Scale / no-truncation validation

Minimum:

- 100 progression domains/profile values,
- preferably >1000 canonical profile rows across players/domains/dimensions,
- >1000 legacy evidence/mapping rows if mapping persistence exists.

Expected:

- no hidden SQL `LIMIT`,
- no list truncation in repository/profile composition,
- deterministic row set independent of insertion order,
- no cross-player/campaign leakage at scale.

The validator will count source rows, mapped rows, canonical rows and output entries separately so a plausible but truncated profile cannot pass.

---

## 15. SQLite integrity / FK validation

After migration and after legal Phase 6 writes used by tests:

```sql
PRAGMA integrity_check;
```

must return `ok`.

If Phase 6 schema declares foreign keys:

```sql
PRAGMA foreign_keys = ON;
PRAGMA foreign_key_check;
```

must report zero violations.

If cross-domain mapping/source evidence intentionally cannot use DB foreign keys, the implementation must document the policy and provide equivalent store/domain validation. The absence of a physical FK is not itself a failure; silent orphan acceptance is.

Important FK cases:

- profile -> domain definition,
- Potential dimension -> declared dimension/domain policy if persisted separately,
- mapping -> canonical domain,
- ownership relation if represented relationally.

---

## 16. Backup / restore / reopen checks

If normal backup/restore includes the campaign DB automatically, verify:

### BR6-01 — old backup restored after Phase 6 release

Restore pre-Phase-6 DB through normal restore/current-schema path.

Expected safe additive initialization and zero invented profiles.

### BR6-02 — post-Phase-6 backup

Persist canonical Talent/Potential, backup, mutate values, restore.

Expected restored profiles/version/provenance exactly match backup authoritative state.

### BR6-03 — unresolved legacy survives restore

Opaque legacy evidence remains unresolved and intact.

### BR6-04 — modifier separation survives restore

Phase 5 modifier rows return exactly to backed-up state and do not rewrite profiles during reopen/resolution.

---

## 17. Core universe-agnostic static audit

Phase 6 Core source must not require literal branches for:

- Naruto,
- Bleach,
- genjutsu,
- raiton,
- kido,
- zanjutsu,
- sonido,
- reishi,
- chakra,
- reiatsu.

World-specific vocabulary may exist only in World Pack/test fixture data where appropriate.

The migration/integrity validation will inspect the final implementation diff for prohibited Core hardcoding and for display-name-based identity logic.

---

## 18. ResultCommit audit procedure after WORK-020 appears

When CHAT-1 publishes a resultCommit, execute in this order:

1. Re-read current `master` and recent commits.
2. Identify exact `WORK-020` resultCommit and its parent/baseline.
3. Compare the implementation delta and confirm only authorized Phase 6 runtime/test/schema scope changed.
4. Inspect all new domain/profile/mapping models.
5. Inspect every migration/schema change.
6. Trace every real schema entrypoint used by:
   - app startup/open,
   - campaign switching,
   - restore,
   - normal repository reads,
   - backup reopen if applicable.
7. Verify Phase 6 migration cannot be skipped because of stale method names such as the existing `ensureV4()` debt.
8. Snapshot Phase 3/4/5 upstream authority before migration and compare after.
9. Execute all legacy classification fixtures.
10. Execute campaign/player/domain/World-Pack isolation fixtures.
11. Execute four-quadrant persistence/reopen fixtures.
12. Execute version/provenance/numeric failure fixtures.
13. Execute Phase 5 temporary-effect non-mutation attacks.
14. Execute 100 and >1000 scale fixtures.
15. Run `PRAGMA integrity_check` and adopted FK checks.
16. Run full JVM tests and build on the exact resultCommit.
17. Verify CI for the exact resultCommit; do not accept CI from a later unrelated documentation commit as substitute evidence.
18. Record reproducible blockers if any.
19. Update this report with actual runtime evidence only.

---

## 19. Required validation matrix after WORK-020

Mark every gate `PASS | FAIL | NOT TESTED | BLOCKER` with concrete file/test/runtime evidence.

| Gate | Pre-WORK-020 status |
|---|---|
| Phase 6 additive migration exists | PENDING |
| Old Phase 5 campaign opens | PENDING |
| ActivePlayerRef unchanged | PENDING |
| PlayerStat.baseValue unchanged | PENDING |
| PlayerResource.currentValue unchanged | PENDING |
| Phase 4 aliases unchanged | PENDING |
| Phase 5 modifiers unchanged | PENDING |
| Legacy source bytes unchanged | PENDING |
| No synthetic Talent/Potential | PENDING |
| Talent/Potential separate persistence | PENDING |
| Four quadrants persist/reopen | PENDING |
| Talent update leaves Potential | PENDING |
| Potential update leaves Talent | PENDING |
| Campaign isolation | PENDING |
| Player isolation | PENDING |
| Domain isolation | PENDING |
| World Pack ownership validation | PENDING |
| Duplicate domain UID fail-loud | PENDING |
| Same label/different UID separate | PENDING |
| Version/provenance enforced | PENDING |
| Invalid numeric fail-loud | PENDING |
| NaN/+Inf/-Inf rejected | PENDING |
| Bare `talent` unmapped | PENDING |
| Bare `gifted` ambiguous | PENDING |
| Bare `aptitude` unmapped | PENDING |
| Bare `growth_rate` ambiguous | PENDING |
| Bare `learning_rate` unmapped | PENDING |
| Bare `maximum_potential` unmapped | PENDING |
| Bare `affinity` ambiguous | PENDING |
| Bare `adaptation` ambiguous | PENDING |
| Bare `evolution_potential` unmapped | PENDING |
| SAFE exact Talent mapping | PENDING |
| SAFE exact Potential mapping | PENDING |
| Explicit World Pack mapping | PENDING |
| Mapping target missing fail/quarantine | PENDING |
| Mapping owner/version mismatch fail-loud | PENDING |
| Canonical + mapped legacy exactly once | PENDING |
| Unresolved evidence survives | PENDING |
| Temporary learning effect cannot persist Talent | PENDING |
| Temporary breakthrough effect cannot persist Potential | PENDING |
| No second modifier/resolver engine | PENDING |
| 100 records no truncation | PENDING |
| >1000 records no truncation | PENDING |
| migration idempotent | PENDING |
| partial migration rollback safe | PENDING |
| SQLite integrity_check | PENDING |
| FK policy/check | PENDING |
| full JVM tests | PENDING |
| build | PENDING |
| CI on exact WORK-020 resultCommit | PENDING |

---

## 20. Automatic release blockers

Final Phase 6 integrity validation is `FAIL` if any of the following is reproducible:

1. Migration changes ActivePlayerRef unexpectedly.
2. Migration changes `PlayerStat.baseValue`.
3. Migration changes `PlayerResource.currentValue`.
4. Migration changes Phase 4 legacy aliases.
5. Migration changes/deletes existing Phase 5 modifiers.
6. Talent and Potential are collapsed into one authoritative value.
7. Profile identity uses display label instead of stable UID.
8. World Pack ownership can be silently hijacked.
9. Duplicate domain UID silently overwrites/reinterprets an existing definition.
10. Bare legacy label creates canonical Talent/Potential without proven mapping.
11. Ambiguous evidence is discarded rather than preserved, or becomes a mechanical input.
12. Canonical + mapped legacy creates two logical profile nodes.
13. ActivePlayerRef determines which eligible legacy records are migrated.
14. Temporary Phase 5 effect writes persistent Talent/Potential.
15. Phase 6 adds a parallel Talent/Potential modifier engine/resolver.
16. Profile read/write automatically changes Skill mastery/XP or current/base stat.
17. Missing/corrupt profile schema is silently treated as a valid empty profile.
18. NaN/Infinity or invalid declared numeric values persist successfully.
19. Profile row lacks required version/provenance.
20. SQL `LIMIT` or bounded read silently drops valid profile/mapping records.
21. Migration marker can commit while required Phase 6 initialization/data mapping rolls back.
22. `PRAGMA integrity_check` fails.
23. Adopted FK policy permits silent invalid domain/mapping/profile references.
24. Existing old campaign cannot open after update.
25. Exact WORK-020 JVM/build/CI evidence is failing or unavailable when acceptance requires it.

---

## 21. Acceptance threshold

`PHASE 6 INTEGRITY VALIDATION: PASS`

may be issued only when the concrete WORK-020 runtime proves all of the following:

- additive/idempotent migration,
- safe open of pre-Phase-6 campaigns,
- Phase 3/4/5 authoritative state equality across migration,
- separate Talent/Potential authoritative storage,
- stable domain UID + World Pack ownership integrity,
- campaign/player/domain isolation,
- version/provenance preservation,
- numeric fail-loud policy,
- conservative legacy classification with no semantic guessing,
- explicit mapping/reconciliation exactly once,
- unresolved evidence preservation,
- Phase 5 temporary effects cannot mutate profiles,
- no parallel modifier engine,
- reopen equality,
- 100 and >1000 no-truncation coverage,
- SQLite integrity/FK policy checks,
- full JVM tests/build and CI on the exact runtime resultCommit.

A PASS for this work item does **not** declare global Phase 6 COMPLETE. That decision remains with the coordinator.

---

# Current status

**PHASE 6 MIGRATION / INTEGRITY PLAN READY — WAITING FOR WORK-20260810-020 RESULT COMMIT**
