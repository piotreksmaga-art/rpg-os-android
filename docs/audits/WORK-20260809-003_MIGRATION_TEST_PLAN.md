# WORK-20260809-003 — Phase 4 Migration / Integrity Test Plan

Work ID: `WORK-20260809-003`
Role: CHAT-3 — READ-ONLY MIGRATION / INTEGRITY AUDITOR
Phase: 4 validation
Audit baseline (fresh master at write time): `ace51fa7cb635a4dcd6801865c300bc8c34f52cf`
Registered work-item baseline: `82b030271e5b7d653da457a2e9b2522e21234457`
Allowed write scope: this report only.
Runtime/schema/migrations/production tests/CampaignRepository: READ-ONLY.

## Executive conclusion

Phase 4 can be migration-safe and additive, but only if the implementation treats every pre-existing stat/resource value as authoritative information that must survive independently of whether the current World Pack already knows its definition. The migration must never collapse legacy values into a fixed enum, infer identity from display names when a stable key is available, silently discard unknown/custom rows, or replace legacy storage before equivalence is proven.

The safest architecture is additive: create generic definition/value storage, preserve legacy tables/columns during the compatibility window, migrate/backfill in one transaction per database, make the operation idempotent, and prove authoritative equality before any legacy path is retired. Unknown legacy keys must become preserved opaque/generic definitions or remain readable through an explicit compatibility bridge; they must not be dropped.

The current code base already has useful safety properties: schema migrations are recorded in `rpgos_schema_migrations`; V2/V3 use SQLite transactions; Player State reads `character_stats` by `entity_uid`; active player identity is persisted per `campaign_id`; backup/restore operates on full `campaign.db` copies and restricts restore to the active campaign. However, the migration framework is intentionally minimal, migration seeding outside the V3 DDL transaction already exists (`seedFromLegacyIfMissing()`), and Player State currently considers `character_stats` persistent authoritative data. Phase 4 must therefore be stricter than a simple `CREATE TABLE IF NOT EXISTS + INSERT OR IGNORE` change.

---

# 1. Current schema risks

## 1.1 Legacy fixed/flat stat storage is already authoritative

`PlayerStateStore` currently loads all rows from `character_stats` where `entity_uid = active player UID` and exposes them under persistent Player State. It orders by `stat_key`, not by a World Pack definition table. Therefore every existing `stat_key`/value row is campaign information, including keys unknown to a future generic registry.

Risk: a Phase 4 migration that seeds only a hardcoded list of standard definitions can preserve common stats while silently orphaning custom/old keys.

Required rule: data migration must be driven by actual stored rows first, not only by the currently installed World Pack's known definitions.

## 1.2 Resource-like state is not yet one canonical table

The roadmap explicitly classifies Phase 4 as PARTIAL because `character_stats` and resource-like state exist but a generic definition architecture is not yet confirmed. Player State also splits legacy `character_status_snapshot` fields into PERSISTENT / DERIVED / RUNTIME. Resource-like information may therefore be distributed across legacy fixed columns and/or other tables.

Risk: implementing `PlayerResource` only from one currently convenient source can lose resource values that are still represented in legacy status fields.

Required rule: before migration code is accepted, produce a column-level inventory from representative legacy databases and classify every HP/energy/stamina/chakra/reiatsu-like field as authoritative current value, authoritative base/max input, derived-only, runtime-only, duplicate mirror, or obsolete presentation.

## 1.3 Definition UID collision risk

A generic definition layer introduces a new identity namespace. Display name, `stat_key`, localized label, World Pack identifier, and stable UID are not interchangeable.

Risks:
- two World Packs define the same human label for different semantics;
- two definitions accidentally reuse one UID;
- the same legacy key differs only by case/spacing;
- generated UIDs change between migration runs;
- a Core default definition collides with a custom pack definition.

Required rule: definition identity must be deterministic and collision-detectable. Duplicate UID with non-identical semantic definition must fail loudly; it must not use last-write-wins or `INSERT OR IGNORE` as conflict resolution.

## 1.4 PlayerStat duplicate risk

Legacy schema may not enforce the future uniqueness contract. A migration can encounter multiple rows that map to the same `(campaign/player, definitionUid)`.

Required rule: duplicate source rows must be detected before destructive consolidation. If duplicates are semantically identical, the migration may deduplicate only under an explicit documented rule. If values differ, migration must fail/rollback and preserve the original DB unchanged.

## 1.5 Resource bounds risk

Generic resources normally introduce current/min/max semantics. Legacy values may violate newly introduced constraints because old schema did not enforce the same rules.

Examples: current > max, negative max, null max, negative current allowed by old game logic, max represented in a fixed status column, or max being derived rather than authoritative.

Required rule: migration cannot silently clamp old campaign values. It must either preserve them verbatim with a compatibility/validation state or fail the migration with actionable diagnostics. Clamping is a domain mutation, not a data migration.

## 1.6 Migration ledger is global per campaign DB

`rpgos_schema_migrations.migration_id` is a primary key. This supports one-time migration semantics, but safety depends on writing the ledger row in the same successful transaction as every schema/backfill change.

Required rule: the Phase 4 migration marker must be committed only after all Phase 4 DDL, backfill, indexes, constraints, and integrity checks succeed.

## 1.7 V3 demonstrates a transaction-boundary warning

`ensureV3()` creates the table and records `RPGOS-3.0-PLAYER-STATE` in a transaction, then performs `seedFromLegacyIfMissing()` after `endTransaction()`.

This is acceptable for optional identity seeding but is not a safe template for Phase 4 authoritative stat/resource migration. Phase 4 backfill must not leave "migration applied" while data conversion failed afterward.

---

# 2. Legacy compatibility risks

## 2.1 Unknown legacy stat

An old campaign may contain a `character_stats.stat_key` no longer present in Naruto/Bleach/current World Pack content.

Expected behavior: value survives with stable identity and remains loadable. Unknown does not mean invalid.

## 2.2 Unknown legacy resource

A legacy fixed column or row may represent a resource not known by the active World Pack after update/restore.

Expected behavior: resource survives as preserved campaign data; loading the campaign must not erase it because definition metadata is unavailable.

## 2.3 Custom World Pack data

World Packs are extension points by MASTER contract. A custom pack can define stats/resources Core has never seen.

Expected behavior: no Core enum/switch may be required to persist or reload those values.

## 2.4 Legacy fixed columns -> dynamic definitions

This is the highest-risk transformation.

For each legacy fixed field, migration must answer:
1. Is the field authoritative, derived, runtime, cache, or presentation?
2. What exact definition UID does it map to?
3. What numeric/string precision and null semantics must be preserved?
4. Is there a separate current/max/base value?
5. Does a row already exist in another table for the same fact?
6. Which representation wins if duplicates disagree?

No field may be deleted or ignored merely because the new model has no immediate equivalent.

## 2.5 Restore of pre-Phase-4 backup

`RestoreManager` copies a selected old DB over the active `campaign.db`; `LocalGameStore.restoreBackup()` then immediately calls `ensureCurrentSchema()`.

Therefore restore compatibility is also migration compatibility. Every pre-Phase-4 backup that was valid before the change must either migrate successfully on restore or fail without destroying the pre-restore safety copy.

---

# 3. Migration invariants

The following invariants are mandatory for Phase 4.

### I-01 — Authoritative value preservation
For every legacy authoritative stat/resource record `L`, there exists exactly one semantically equivalent post-migration authoritative value `N` for the same campaign/player and definition identity, with no precision loss.

### I-02 — No unknown-data loss
Unknown/custom legacy keys survive. Lack of a recognized definition is never permission to delete data.

### I-03 — Stable deterministic identity
The same legacy key + namespace/World Pack identity maps to the same definition UID on every run/device.

### I-04 — No cross-player contamination
Rows belonging to player A never appear in player B's migrated state.

### I-05 — No cross-campaign contamination
Each campaign DB migrates independently; no definition/value backfill copies campaign-specific values between save directories.

### I-06 — Definition uniqueness
A definition UID identifies one semantic definition. Same UID + incompatible metadata is an integrity error.

### I-07 — Player value uniqueness
At most one authoritative PlayerStat/PlayerResource exists for the intended uniqueness key. Conflicting duplicates cause rollback/failure, never arbitrary overwrite.

### I-08 — Resource limits are not rewritten silently
Migration never clamps or normalizes campaign truth without a domain command.

### I-09 — Idempotency
Running Phase 4 migration twice produces the same rows, values, counts, UIDs, migration ledger and load result as running once.

### I-10 — Atomicity
Any failure during DDL/backfill/validation leaves the database at the last valid pre-migration state and does not record Phase 4 as applied.

### I-11 — Reopen equality
After migration, close/reopen yields authoritative stat/resource equality.

### I-12 — Backup/restore equality
Backup before/after migration and restore preserve authoritative Phase 4 data according to the backup's own point in time.

### I-13 — World Pack independence
Persisted player values remain loadable even when the corresponding World Pack is missing, updated, or temporarily cannot provide metadata.

### I-14 — Legacy compatibility window
Until equivalence is proven and all runtime readers are switched, legacy data must remain available or be represented by a lossless compatibility path. No destructive drop in Phase 4 migration.

---

# 4. Required fixtures

Create fixtures as real SQLite databases, not only mocked repository objects.

1. `legacy_rpgos_1_minimal.db` — pre-TRUTH/pre-Player-State campaign with player stats and fixed resource-like fields.
2. `legacy_rpgos_2_truth.db` — migration ledger through `RPGOS-2.0-TRUTH`, old player/stat/resource layout.
3. `legacy_rpgos_3_player_state.db` — migration ledger through `RPGOS-3.0-PLAYER-STATE`, persisted active player and stat rows.
4. `legacy_unknown_stat.db` — includes unknown `stat_key` with non-default value and precision edge case.
5. `legacy_unknown_resource.db` — includes a resource-like key/column unsupported by current World Pack.
6. `custom_worldpack.db` + minimal custom pack metadata — one custom stat and one custom resource absent from Core/Naruto.
7. `two_players.db` — player A/B with same definition keys but distinct values; active player switchable.
8. `two_campaigns/` — two separate campaign directories with overlapping player UIDs/definition UIDs but different values.
9. `duplicate_definition_uid.db` — two incompatible definitions with one UID.
10. `duplicate_player_stat.db` — two source rows mapping to one future PlayerStat uniqueness key, with conflicting values.
11. `invalid_definition.db` — malformed type/bounds/required metadata.
12. `invalid_resource_limits.db` — current > max, max < min, null/negative edge cases matching actual contract.
13. `partial_failure.db` — fault-injection fixture that fails after some Phase 4 inserts but before commit.
14. `post_phase4_custom_data.db` — valid dynamic custom definitions/values for backup/restore compatibility.
15. `large_stats_resources.db` — hundreds/thousands of definitions/values to catch truncation or LIMIT-based data loss.

Every fixture needs a machine-readable expected authoritative snapshot before migration: campaign ID, player UID, legacy source identity, semantic key, exact value, bounds if authoritative, and expected post-migration definition UID.

---

# 5. Concrete test cases

## T-01 — Old campaign -> migration -> valid load
Given each supported legacy fixture, run `ensureCurrentSchema`, then load canonical Player State.

Expected: migration marker exists once; all legacy authoritative stats/resources are represented; no exception; SQLite `integrity_check` returns `ok`.

## T-02 — Stat values preserved exactly
Seed integers, zero, negatives if legacy permits, large values, floating precision if schema permits, and null semantics if permitted.

Expected: exact equality according to storage type/declared conversion contract. No clamping/rounding unless explicitly lossless.

## T-03 — Resource values preserved exactly
Seed current/base/max-like legacy fields across boundary values.

Expected: exact authoritative values after migration; derived max must not be accidentally persisted as base truth unless architecture explicitly classifies it authoritative.

## T-04 — Custom World Pack stat
Install/use a minimal pack defining `custom.luck_flux`; seed player value.

Expected: definition persists with stable UID; player value survives close/reopen and pack reload; no Core source change required.

## T-05 — Custom World Pack resource
Define `custom.aether` with valid min/max semantics.

Expected: same as T-04 plus exact current value and limits semantics.

## T-06 — Unknown legacy stat
Seed `stat_key='forgotten_mod_stat'` absent from current definitions.

Expected: migration preserves it through a generic/legacy definition or explicit compatibility representation. Test fails if value disappears from authoritative read.

## T-07 — Unknown legacy resource
Seed a legacy resource unsupported by current pack.

Expected: preserved and discoverable; no silent delete/default-to-zero.

## T-08 — Duplicate definition UID, identical definition
Attempt insertion/migration of semantically identical duplicate definition.

Expected: deterministic idempotent no-op or one canonical row, according to contract.

## T-09 — Duplicate definition UID, conflicting definition
Same UID but different key/type/limits/source namespace.

Expected: loud integrity failure and rollback. Existing canonical definition remains unchanged.

## T-10 — Duplicate PlayerStat, equal values
Two legacy rows map to one target identity with same value.

Expected: only if an explicit deduplication rule exists, one target row and audit evidence; otherwise fail loudly. Never create two authoritative duplicates.

## T-11 — Duplicate PlayerStat, conflicting values
Same target identity with different values.

Expected: migration fails/rolls back; neither value is arbitrarily chosen.

## T-12 — Invalid definition
Malformed key/UID/type or impossible definition constraints.

Expected: rejected before any player value is committed; migration marker absent.

## T-13 — Invalid resource limits
Test current > max, max < min, and any other invalid relation.

Expected: no silent clamp. Either legacy-compatible preservation with explicit invalid/legacy status or transaction failure, based on final contract.

## T-14 — Migration rerun/idempotency
Run Phase 4 migration, snapshot tables/rows/Uids/counts, run again, compare.

Expected: byte-equivalent semantic dataset; no duplicate rows; one Phase 4 ledger entry.

## T-15 — Partial migration failure
Inject failure after definitions are created and after some player values are inserted but before completion.

Expected: transaction rollback removes all partial Phase 4 effects; migration ledger does not contain Phase 4 marker; legacy data remains unchanged and readable.

## T-16 — Failure after ledger write attempt
Fault immediately after attempted migration-ledger insertion but before transaction success.

Expected: ledger insertion rolls back with data changes.

## T-17 — Retry after failure
After T-15/T-16, remove fault and rerun.

Expected: full successful migration exactly once, proving failed attempts do not poison idempotency.

## T-18 — Save -> close -> reopen
Migrate, mutate values only through the legal Phase 4 persistence API once available, close DB, reopen.

Expected: exact definitions/player values and active-player scoped read.

## T-19 — Many players in one DB
Seed A/B/C with same definitions but unique values.

Expected: switching `ActivePlayerStore` returns only selected player's values. Migration does not use "first entity wins" heuristics.

## T-20 — Player identity ambiguity
Use a legacy DB where active player cannot be unambiguously seeded.

Expected: Phase 4 does not attach all stat/resource rows to a guessed player. It must block player-specific backfill or preserve rows using original entity UID until identity is resolved.

## T-21 — Many campaigns
Create campaign A and B in separate save directories with overlapping player UIDs and definition keys.

Expected: each DB preserves its own values and ledger independently.

## T-22 — Campaign switch
Migrate campaign A, switch to B, migrate B, switch back.

Expected: no values/definitions that are campaign-local leak through global singleton/cache state.

## T-23 — Backup before migration -> migrate -> restore old backup
Create old-format backup, migrate active DB, then restore old backup through normal restore flow.

Expected: pre-restore safety backup is created; restored old DB automatically migrates once; authoritative values equal the old backup's pre-migration snapshot.

## T-24 — Backup after migration -> mutate -> restore
Backup post-Phase-4 DB, change values, restore.

Expected: restored definitions/player values match backup exactly and remain loadable without duplicate migration side effects.

## T-25 — Restore wrong-campaign backup
Attempt path outside active campaign backup directory.

Expected: current RestoreManager guard still rejects it; Phase 4 must not weaken isolation.

## T-26 — World Pack removed after migration
Migrate custom definitions/values, then load campaign with custom pack unavailable.

Expected: player authoritative data remains in DB and is inspectable; failure to render rich metadata must not delete values.

## T-27 — World Pack updated/renamed labels
Same stable UID but changed display label/description.

Expected: player value remains attached to UID; label update does not create a second stat/resource.

## T-28 — World Pack reuses UID incompatibly
Updated pack attempts same UID for different semantic type/key.

Expected: compatibility validation rejects conflict; campaign values remain attached to original definition and unchanged.

## T-29 — No data truncation
Seed >100 and preferably >1000 stats/resources.

Expected: canonical persistence/read returns all authoritative values. This mirrors the Phase 3 rule that Player State must not silently truncate collections.

## T-30 — CharacterPanel/Context equality during compatibility window
Compare legacy/current Player State projection before migration with dynamic projection after migration for the same player.

Expected: same semantic stat/resource set and values; only representation/metadata may differ.

## T-31 — SQLite integrity/foreign-key checks
After every migration/restore fixture execute `PRAGMA integrity_check` and, if foreign keys are used, `PRAGMA foreign_key_check`.

Expected: no violations.

## T-32 — Migration ordering RPGOS-1.0 -> 2.0 -> 3.0 -> Phase 4
Test DBs at each ledger state.

Expected: `ensureCurrentSchema` reaches the same final schema/data regardless of starting supported version.

## T-33 — Already-current DB
Open a clean Phase 4 DB repeatedly through bootstrap/buildContext/status/playerState paths.

Expected: schema checks are cheap/idempotent and do not mutate definitions/values after first successful migration.

## T-34 — Bootstrap failure safety
Use a corrupt/invalid migration fixture during `LocalGameStore.bootstrap()`.

Expected: migration failure is logged and never leaves partial Phase 4 state. Important: because current bootstrap wraps schema/repair in `runCatching`, tests must prove that a failed migration is not mistaken for a valid initialized campaign later.

## T-35 — Restore failure safety
Restore a legacy backup whose Phase 4 migration is intentionally made to fail.

Expected: migration failure is surfaced; pre-restore safety DB exists and can restore the prior valid state. No partially migrated restored DB may be treated as healthy.

---

# 6. Expected results / equality oracle

Do not validate migration only by "app opens" or row counts. Use an authoritative equality oracle.

For each player/campaign construct a normalized snapshot before migration:

```text
StatFact = (campaign, playerUid, semanticKey/legacyKey, exactValue, sourceNamespace)
ResourceFact = (campaign, playerUid, semanticKey/legacyKey, exactCurrent, exactAuthoritativeBounds?, sourceNamespace)
```

Construct the equivalent normalized snapshot after migration from `StatDefinition + PlayerStat` and `ResourceDefinition + PlayerResource` (or final naming). Compare sets exactly.

Required assertions:
- every before fact has exactly one after fact;
- no after fact appears for another player/campaign;
- no before fact changes numeric/string value;
- all custom/unknown keys are present;
- definition UIDs are stable across rerun/reopen;
- duplicate/incompatible cases fail as specified;
- migration ledger reflects success only after authoritative equality is possible.

A successful SQL transaction without equality is not a successful migration.

---

# 7. Failure / rollback cases

Phase 4 implementation must support fault injection in tests at these boundaries:

1. after creation of first definition table;
2. after creation of all new tables but before backfill;
3. after first definition backfill batch;
4. after first PlayerStat insert;
5. between stat and resource migration;
6. after resource backfill but before validation;
7. during duplicate collision validation;
8. immediately before migration ledger insert;
9. immediately after ledger insert but before `setTransactionSuccessful()`;
10. during post-migration integrity validation.

At every failure point expected state is the same:
- no Phase 4 migration marker;
- no partial authoritative conversion visible;
- legacy rows/columns unchanged;
- DB remains openable;
- retry can succeed.

If the implementation performs any authoritative backfill after `endTransaction()`, Phase 4 must be rejected until that work is moved into the atomic boundary or protected by a separate resumable state machine with explicit recovery semantics.

---

# 8. Multi-player isolation

Phase 3 already establishes a persisted active player per campaign and tests player isolation for skills. Phase 4 must extend that standard to stats/resources.

Mandatory matrix:
- players A/B have same definition UID, different values;
- players A/B have disjoint custom definitions;
- one player's value is absent/null while another's is present;
- active player switches A -> B -> A across close/reopen;
- migration runs before and after active player seeding;
- ambiguous legacy identity must not merge entity rows.

The target value key must include player identity (directly or through a stable character FK). A definition row may be shared metadata; a PlayerStat/PlayerResource value must not be shared state.

---

# 9. Multi-campaign isolation

Current physical model stores campaigns in separate save directories/databases. Tests must nevertheless protect against process-level caches, static registries, World Pack registries, and reused UIDs.

Mandatory matrix:
- same player UID in two campaigns, different values;
- same custom definition UID in two campaigns;
- campaign A migrated, B still legacy;
- switch A/B repeatedly;
- restore A while B exists;
- backup pruning in A must not touch B;
- migration ledger in A must not mark B migrated.

Expected: zero cross-campaign value leakage.

---

# 10. World Pack compatibility

MASTER requires Core to remain universe-agnostic. Therefore Phase 4 fails architecture review if adding a stat/resource requires a new Core enum constant or schema column.

Required compatibility contract:
- World Pack can supply definitions with stable UID/key/type/metadata without Core source changes;
- player value persistence references stable definition identity;
- missing pack metadata does not erase player values;
- pack update with same UID preserves player bindings;
- conflicting UID reuse is rejected;
- custom/unknown definitions survive backup/restore and migration rerun;
- no Naruto/Bleach-specific key is required by Core migration logic except an explicit legacy mapping table used only for compatibility, and even that mapping cannot drop unmapped keys.

Critical test: install a synthetic test World Pack with names and resources never used by Naruto/Bleach. If full persistence/migration cannot pass without editing Core, Phase 4 is not truly dynamic.

---

# 11. Restore / backup compatibility

Current backup is a full file copy of `campaign.db`; this is favorable for additive Phase 4 because unknown/new tables are automatically preserved in post-migration backups.

Current restore:
- accepts only `.db` files;
- requires backup path inside active campaign `backups` directory;
- creates `pre_restore_<timestamp>.db` safety copy;
- copies backup over active DB;
- `LocalGameStore` then calls schema migration.

Phase 4 requirements:
1. pre-Phase-4 DB backups must remain restorable and auto-migratable;
2. post-Phase-4 backups preserve custom definition/value tables exactly;
3. failed migration after restore must never destroy the pre-restore safety DB;
4. restore must not duplicate definitions/values on subsequent open;
5. `integrity_check` must pass after restore + migration;
6. backup tests must include custom World Pack definitions and unknown legacy values;
7. file-copy backup must occur with DB in a safe state in integration tests; tests should catch WAL/transaction-related loss if journaling mode changes in future.

---

# 12. MUST HAVE tests before Phase 4 can be declared COMPLETE

These are release gates, not optional coverage.

**MH-01** old `RPGOS-1.0` campaign -> Phase 4 -> valid load + authoritative equality.

**MH-02** `RPGOS-2.0-TRUTH` campaign -> Phase 4 -> valid load + equality.

**MH-03** `RPGOS-3.0-PLAYER-STATE` campaign -> Phase 4 -> valid load + equality.

**MH-04** exact preservation of all existing `character_stats` values for active and non-active players.

**MH-05** exhaustive inventory + preservation test for every legacy resource-like authoritative field discovered in representative campaign DBs.

**MH-06** unknown legacy stat preserved.

**MH-07** unknown legacy resource preserved.

**MH-08** synthetic custom World Pack stat survives migrate -> close -> reopen -> backup -> restore.

**MH-09** synthetic custom World Pack resource survives the same lifecycle.

**MH-10** duplicate conflicting definition UID fails loudly with rollback.

**MH-11** duplicate conflicting PlayerStat/PlayerResource fails loudly or follows a documented non-lossy reconciliation rule; no arbitrary overwrite.

**MH-12** invalid definition rejected without partial migration.

**MH-13** invalid resource limits never silently clamp authoritative legacy data.

**MH-14** migration rerun is idempotent: stable UIDs, stable row counts, stable values, one ledger entry.

**MH-15** injected partial migration failure rolls back all Phase 4 schema/backfill effects that form one migration unit and leaves ledger unmarked.

**MH-16** retry after failed migration succeeds cleanly.

**MH-17** save -> close -> reopen authoritative stat/resource equality.

**MH-18** multi-player isolation with same definitions/different values.

**MH-19** ambiguous legacy player identity does not attach data to a guessed player.

**MH-20** multi-campaign isolation and campaign switching.

**MH-21** restore of pre-Phase-4 backup auto-migrates and equals backup truth.

**MH-22** restore of post-Phase-4 backup preserves custom data exactly.

**MH-23** failed restore-migration leaves usable pre-restore safety backup and no partial valid-looking Phase 4 state.

**MH-24** `PRAGMA integrity_check` (and `foreign_key_check` if applicable) passes for every migration/restore fixture.

**MH-25** no truncation with >100/>1000 dynamic stat/resource values.

**MH-26** missing/updated World Pack does not delete persisted player values.

**MH-27** CharacterPanel/Context/Player State semantic equality before vs after migration for the same campaign snapshot.

---

# Critical risks to report to coordinator / CHAT-1

1. **Do not use `INSERT OR IGNORE` to hide semantic UID conflicts.** It is safe for a known migration-ledger duplicate; it is unsafe as general definition collision handling.
2. **Do not seed dynamic definitions only from a hardcoded Naruto/Bleach list.** Every actual legacy key/value must be preserved, including unknown/custom data.
3. **Do not clamp invalid-looking legacy resources during migration.** That changes campaign truth.
4. **Do not record the Phase 4 migration before all authoritative backfill and validation is committed.** The V3 post-transaction seed pattern must not be copied for Phase 4 authoritative values.
5. **Do not infer player ownership by current active player when source rows already contain `entity_uid`.** Migrate by source identity; ambiguous identity must fail/preserve, not guess.
6. **Do not drop legacy columns/tables in the first Phase 4 migration.** Make the change additive and retain a compatibility window until equality tests prove all readers/writers are transitioned.
7. **Resource-like legacy data needs a real column-level audit of campaign fixtures before implementation can be certified.** The current repository evidence proves the risk exists but does not by itself enumerate every resource field stored inside packaged SQLite assets.

# Assessment: can Phase 4 be safely additive?

**YES, conditionally.** The existing architecture favors an additive migration because campaigns are isolated in their own SQLite DB files, the migration ledger already exists, V2/V3 demonstrate transactional DDL patterns, Player State preserves `character_stats` as persistent data, and backups copy the complete DB file.

It is safe only if Phase 4 follows these conditions:
- new definition/value tables are added without dropping legacy storage;
- actual legacy rows/fields are backfilled losslessly;
- unknown/custom keys receive durable preservation instead of deletion;
- definition/value uniqueness and collision validation are explicit;
- all authoritative conversion plus migration marker are atomic;
- rerun is idempotent;
- equality is tested across old versions, players, campaigns, World Packs, close/reopen, backup and restore;
- destructive legacy cleanup is deferred to a later, separately audited migration after production evidence proves compatibility.

Until MH-01 through MH-27 pass on the result commit of `WORK-20260809-001`, Phase 4 should remain PARTIAL/under review rather than COMPLETE.

---

# ADDENDUM — Independent validation of Phase 4 implementation

Validation role: CHAT-3 — READ-ONLY PHASE 4 INTEGRITY VALIDATOR  
Implementation commit under audit: `a33514524ccdf8a51ee672f1fbf79616600b8d82` (`Implement Phase 4 dynamic stats and resources`)  
Fresh validation baseline: `cbadc98dad55360d3bcecfa3c99a998168c48261`  
Commits after implementation at validation start: one documentation-only Phase 3 audit commit; no runtime delta after `a3351452`.  
CI on validation baseline: GitHub Actions run #119, `Build & Release RPG OS ALPHA`, SUCCESS; project validation, JVM unit tests, signed ALPHA APK and artifact steps passed.

## Validation summary

The Phase 4 implementation creates a coherent generic schema and typed persistence surface for new dynamic data, but it does **not yet integrate authoritative legacy stat/resource data into that new read model**. `ensureV4()` creates `stat_definitions`, `player_stats`, `resource_definitions`, and `player_resources`, records `RPGOS-4.0-DYNAMIC-STATS-RESOURCES`, and deliberately leaves legacy tables untouched. That is additive and non-destructive, but only half of the compatibility contract.

The blocking behavior is concrete: `PlayerStateStore` still exposes existing `character_stats`, while `LocalGameStore.playerStats()` resolves the active Player UID and then reads only `player_stats`. `ensureV4()` performs no backfill and `StatResourceStore.playerStats()` has no legacy read-through. Therefore an old campaign can contain authoritative legacy stats while the new Phase 4 API returns an empty list. Resource-like legacy state has the same unresolved integration problem and is even less completely inventoried.

This produces two parallel representations with no authoritative reconciliation rule: legacy Player State can say “stat exists”, while Phase 4 typed read path can say “no stat”. Preserving legacy bytes is necessary but is not sufficient to satisfy old campaign -> migration -> valid authoritative equality.

## PASS / FAIL / NOT TESTED / BLOCKER matrix

| Area | Result | Evidence / assessment |
|---|---|---|
| Four Phase 4 tables exist | PASS | `ensureV4()` creates `stat_definitions`, `player_stats`, `resource_definitions`, `player_resources`. |
| Definition primary keys | PASS | `stat_uid` / `resource_uid` are PKs. |
| `(world_pack_uid,key)` uniqueness | PASS | UNIQUE constraints exist for stat/resource keys within a World Pack. |
| Player value uniqueness | PASS | Composite PK `(campaign_id,character_uid,definition_uid)` prevents duplicate dynamic rows. |
| Required indexes | PASS | World Pack lookup and campaign+character indexes are created. |
| Migration marker | PASS | `RPGOS-4.0-DYNAMIC-STATS-RESOURCES` is written in the V4 transaction. |
| `ensureV4()` calls `ensureV3()` | PASS | Phase 3 identity/schema path remains prerequisite. |
| DDL idempotency | PASS | `CREATE TABLE/INDEX IF NOT EXISTS` plus migration ledger PK; existing test calls `ensureV4()` twice. |
| Migration idempotency of legacy conversion | BLOCKER | There is no legacy conversion/backfill to validate. |
| Transaction safety of V4 DDL + marker | PASS | V4 table/index creation and marker are inside one SQLite transaction. |
| Transaction safety of authoritative legacy migration | BLOCKER | No authoritative stat/resource migration exists. |
| Legacy `character_stats` physically preserved | PASS | Existing test proves table row remains unchanged. |
| Legacy `character_stats` represented by Phase 4 typed API | BLOCKER | `playerStats()` reads only `player_stats`; no backfill/read-through exists. |
| Legacy resource-like data physically preserved | PASS / LIMITED | V4 is additive and drops nothing, but no exhaustive resource fixture/inventory exists. |
| Legacy resource-like data represented by Phase 4 typed API | BLOCKER | No backfill/read-through/mapping exists for legacy resource-like state. |
| Old DB -> `ensureV4()` -> authoritative equality | FAIL | Existing test asserts only that a legacy row remains in `character_stats`, not that new typed read returns it. |
| ActivePlayerRef remains authoritative | PASS | `LocalGameStore.playerStats/playerResources()` obtain the player UID from `ActivePlayerStore` after `ensureV4()`. |
| Player isolation in dynamic tables | PASS | Store queries require campaign + character; persistence test covers PLAYER-A vs PLAYER-B. |
| Campaign isolation in dynamic tables | PASS / LIMITED | Same physical DB test covers different `campaign_id`; separate save-directory lifecycle is not tested. |
| World Pack genericity | PASS | Contract contains generic keys/categories/UIDs; no Naruto/Bleach-specific stat/resource hardcoding found in Phase 4 core. |
| Same key across different World Packs | PASS | Test covers same key with different UIDs in WORLD-A/WORLD-B. |
| UID hijacking across World Packs | PASS | `rejectUidHijack()` rejects same UID owned by another World Pack; test exists for stat definitions. |
| UID semantic replacement inside same World Pack | NOT TESTED | Registration uses UPSERT by UID and can change key/category/unit/bounds/rule UIDs for an existing same-owner definition; no compatibility/version policy test proves when such replacement is legal. |
| Duplicate `(world_pack_uid,key)` collision | PASS / NOT TESTED | DB UNIQUE constraint should fail transactionally, but no explicit production test was found. |
| Definition min/max ordering | PASS | Kotlin policy rejects non-finite bounds and `min > max`; SQL CHECK also covers ordering. |
| NaN / Infinity definition bounds | PASS | `isFinite()` validation rejects them before registration. |
| NaN / Infinity player values | PASS | `isFinite()` validation rejects them before save. |
| Player value respects static definition min/max | FAIL | `savePlayerStat/savePlayerResource` check finite value and definition existence but do not compare value with definition bounds. |
| FK declared in schema | PASS | `player_stats.stat_uid` and `player_resources.resource_uid` reference definition tables. |
| FK runtime enforcement | NOT TESTED | No evidence that `PRAGMA foreign_keys=ON` is guaranteed on opened campaign DBs; explicit store-side `requireDefinition()` protects inserts but delete/update orphan behavior is unproven. |
| Definition deletion behavior | NOT TESTED | No public Phase 4 delete API; FK delete behavior is not exercised. |
| Definition update behavior | PASS / RISK | Same-owner UID is mutable by UPSERT; player binding remains on UID, but semantic replacement safeguards are incomplete. |
| Close -> reopen persistence | PASS | Existing persistence test reopens SQLite DB and checks stat/resource values and versions. |
| Definitions persist after reopen | NOT TESTED DIRECTLY | Values reopen; test does not assert full definition metadata equality after reopen. |
| Unknown/custom World Pack definition registration | PASS STATIC | Arbitrary WORLD-A/WORLD-B definitions work without Core enum. Full package lifecycle is not tested. |
| Unknown legacy stat | BLOCKER | Legacy row survives physically but is not surfaced by Phase 4 typed API. |
| Unknown legacy resource | BLOCKER | No generic preservation/read-through proof exists. |
| No silent truncation in store query | PASS STATIC | `StatResourceStore` queries contain no `LIMIT`. |
| >100 / >1000 values | NOT TESTED | Required stress-style persistence test is absent. |
| No silent SQL failure in Phase 4 store | PASS | Store methods do not wrap SQL in broad catch-and-empty behavior; SQL failures propagate. |
| Restore pre-Phase-4 backup -> V4 equality | NOT TESTED | Existing restore architecture will call current schema, but equality fixture is absent and current legacy integration gap would fail typed equality. |
| Backup post-Phase-4 custom data | NOT TESTED | Full DB copy is structurally favorable, but lifecycle test is absent. |
| `PRAGMA integrity_check` / `foreign_key_check` fixtures | NOT TESTED | Not present in Phase 4 production tests reviewed. |
| CharacterPanel/Context semantic equality | BLOCKER | CharacterPanel/Player State still rely on legacy representation while typed Phase 4 API relies on new tables; no reconciliation/equality layer exists. |
| CI/build | PASS | Run #119 completed successfully including JVM tests and signed ALPHA APK build. |

## Test-plan gate status

The current implementation satisfies important portions of MH-10/MH-14/MH-17/MH-18 and generic World Pack behavior, but the release-gating set is not complete. Most importantly, MH-01 through MH-07 and MH-27 are not satisfied because preserving legacy storage is not the same as migrating or compatibly exposing it through the new authoritative typed model. MH-21 through MH-25 are also not covered by current Phase 4 tests.

The existing `migrationPreservesLegacyCharacterStatsUnchanged()` test is valuable regression protection, but it proves only non-destruction. It does **not** prove the key invariant from this report: every legacy authoritative fact must have a semantically equivalent Phase 4 authoritative representation or compatibility read path.

## Minimal fixes required from CHAT-1

1. **Integrate legacy stats into Phase 4 typed reads.** Choose one explicit lossless strategy: transactional deterministic backfill from every `character_stats` row into a legacy/generic `StatDefinition + PlayerStat`, or a compatibility read-through that merges legacy rows into `playerStats()` without duplication until a later audited migration. Preserve source `entity_uid`; do not bind all rows to only the currently active player.
2. **Integrate legacy resource-like data equivalently.** First enumerate actual authoritative legacy resource locations/fields, then provide lossless backfill or compatibility read-through. Unknown keys must survive without Naruto/Bleach hardcoding.
3. **Add authoritative equality tests, not only physical-preservation tests.** At minimum: old DB -> `ensureV4()` -> typed equality, unknown legacy stat/resource, active/non-active players, two campaigns, restore of old backup, and no duplicate effects on rerun.
4. **Enforce or explicitly define static bounds semantics on saves.** If `minValue/maxValue` are legal bounds, reject a `PlayerStat.baseValue` / `PlayerResource.currentValue` outside them. If they are metadata only and may be exceeded, document that contract and add tests proving the intended semantics; do not leave it ambiguous.
5. **Harden definition replacement semantics.** Same World Pack + same UID currently permits semantic metadata replacement. Define which fields are mutable versus identity-critical and reject incompatible same-UID changes that could silently reinterpret existing player values.
6. **Add integrity/FK/no-truncation tests.** Cover duplicate `(world_pack_uid,key)`, `PRAGMA integrity_check`, `foreign_key_check` or explicit FK-enforcement assumptions, and >100 definitions/values.

No Phase 5 work is required to fix these issues; all six items are Phase 4 migration/persistence hardening.

# Final validation verdict

**PHASE 4 VALIDATION: FAIL**

Primary blocker: existing campaigns are not actually integrated into the new typed stat/resource model. The implementation creates valid new empty stores next to legacy authoritative data and preserves the old data, but the new public read path can return an empty model for an old campaign that still has real stats/resources. Until legacy authoritative equality is provided by backfill or a documented lossless compatibility bridge and the corresponding tests pass, Phase 4 must not be declared COMPLETE.

---

# FOLLOW-UP — WORK-20260809-008 Final Phase 4 Revalidation

Revalidation baseline/result under audit: `91763b733d9ed3eaa3d804c77394fb7f87b7be3b` (`WORK-20260809-006 — add lossless Phase 4 legacy read-through`).  
Previous validation result: `6f97d495cb03759f35ce128dfea1ea5498a1d67a` — FAIL.  
CI evidence: GitHub Actions run #128 for `91763b733d9ed3eaa3d804c77394fb7f87b7be3b` completed SUCCESS.

## What WORK-006 fixed

The previous empty-read blocker for pure legacy campaigns is fixed. `LegacyStatResourceCompatibility` projects every valid `character_stats` row by source `entity_uid`, derives deterministic SHA-256 based reserved legacy definition UIDs, preserves unknown/custom keys, fails loudly on conflicting duplicate legacy rows, and leaves legacy bytes authoritative in their original tables. `StatResourceStore.playerStats()` and definition reads now merge that projection into typed Phase 4 reads. Tests cover active/non-active players, unknown stat keys, reopen stability, repeated `ensureV4()`, >1000 legacy stats, physical campaign isolation, and fail-loud duplicate rows.

Resource compatibility is generic rather than Naruto/Bleach specific. Only columns whose structural naming can safely mean current resource are projected. `max`, `effective`, and regeneration-classified fields are excluded. A snapshot without `entity_uid` is accepted only when exactly one row exists and only for the persisted active player; it does not pick a first row or mutate `ActivePlayerRef`. Tests cover custom resource names, multiple players, reopen stability, exclusion of derived fields, and ambiguous/unscoped behavior.

WORK-006 also closes several prior hardening gaps: the reserved `RPGOS-LEGACY-COMPAT` namespace and legacy UID prefixes cannot be registered by World Packs; same UID with incompatible metadata is rejected; pack-local duplicate key ownership is rejected; static min/max bounds are enforced on saves; >1000 typed definitions/values are tested; SQL errors propagate; and `PRAGMA integrity_check` / `foreign_key_check` are exercised on mixed state.

## Revalidation matrix

| Gate | Result | Evidence / assessment |
|---|---|---|
| Old campaign -> ensureV4 -> typed legacy stats equality | PASS | Typed read-through now exposes original `character_stats` values without copying truth. |
| All legacy `entity_uid` values preserved | PASS | Projection queries by requested source entity UID; no active-player rebinding for stat rows. |
| Active/non-active player stat isolation | PASS | Explicit tests cover PLAYER-A and PLAYER-B independently. |
| Unknown/custom legacy stat key | PASS | Deterministic compatibility definition generated from any valid nonblank key. |
| Deterministic legacy stat/resource UIDs | PASS | SHA-256 of the legacy key under reserved stat/resource prefixes; reopen tests prove stability. |
| Duplicate conflicting legacy rows | PASS | Fail-loud instead of arbitrary selection. |
| ensureV4 repeated | PASS | Read-through is non-copying and tests confirm no duplicate conversion/state. |
| Close/reopen | PASS | Legacy definitions and values compare equal after reopen. |
| >1000 records / no truncation | PASS | Tests cover 1005 typed values and 1005 legacy stats. |
| Legacy resource genericity | PASS | Structural current-resource recognition contains no Naruto/Bleach literals. |
| Max/effective/regeneration not promoted | PASS | Compatibility filter excludes Phase-3 DERIVED fields and explicit tests verify exclusion. |
| Ambiguous resource fields remain unresolved | PASS | Only structurally safe current-resource shapes are promoted. |
| Snapshot without `entity_uid` avoids first-row selection | PASS | Multi-row is rejected; single-row projection is gated by persisted active player identity. |
| Reserved legacy namespace hijack | PASS | World Pack registration rejects reserved world-pack UID and reserved legacy definition prefixes. |
| Campaign isolation | PASS | Separate physical DB fixture proves same player/key can hold different values without leakage. |
| Player isolation | PASS | Both typed and legacy paths are character scoped. |
| Bounds | PASS | Save path checks definition min/max and rejects out-of-bounds values. |
| Immutable identity-critical definition metadata | PASS | Identical re-registration is idempotent; same UID with changed metadata fails. |
| Duplicate `(world_pack_uid,key)` | PASS | Explicit pack-local key collision checks and tests reject ownership changes. |
| SQL fail-loud | PASS | No broad catch-and-empty in compatibility/store read paths. |
| SQLite integrity/FK check | PASS | Mixed-state test asserts `integrity_check = ok` and empty `foreign_key_check`. |
| CI #128 | PASS | Workflow for the audited runtime commit completed successfully. |
| Mixed legacy + new same semantic stat key | **BLOCKER** | Merge identity is UID only. Legacy `strength` receives `RPGOS-LEGACY-STAT-<sha256(strength)>`; a normal World Pack may register a different UID with key `strength`. `statDefinitions()` and `playerStats()` return both because their UIDs differ. There is no semantic-key reconciliation or typed-preferred rule. |
| Mixed legacy + new same semantic resource key | **BLOCKER** | Same mechanism: a legacy current resource such as `flux` has a synthetic compatibility UID while a typed `flux` can have another UID. Both definitions/values survive UID-only merge. |

## Reproducible blockers

### B-01 — mixed legacy + typed stat duplicates one logical fact

Fixture:
1. `character_stats('PLAYER-A','strength',10.0)`.
2. `ensureV4('campaign-a')`.
3. Register `StatDefinition(statUid='WORLD-STAT-STRENGTH', key='strength', worldPackUid='WORLD-A', ...)`.
4. Save `PlayerStat('campaign-a','PLAYER-A','WORLD-STAT-STRENGTH',20.0)`.
5. Call `statDefinitions()` and `playerStats('PLAYER-A')`.

Observed by code contract: the legacy row uses deterministic reserved UID `RPGOS-LEGACY-STAT-<sha256('strength')>` while the new row uses `WORLD-STAT-STRENGTH`. `mergeDefinitionsByUid()` / `mergeValuesByUid()` see no UID collision, so both are returned. The caller now has two entries with semantic key `strength` and no authoritative precedence rule.

Expected Phase 4 behavior: exactly one logical authoritative representation must be surfaced for a semantic stat, with an explicit reconciliation policy. A safe policy could prefer a matching typed definition/value and suppress only the equivalent compatibility projection, or require an explicit mapping before coexistence; the validator does not prescribe implementation.

### B-02 — mixed legacy + typed resource duplicates one logical fact

Fixture:
1. `character_status_snapshot(entity_uid='PLAYER-A', current_resource_flux=7.0)`.
2. `ensureV4('campaign-a')`.
3. Register typed `ResourceDefinition(resourceUid='WORLD-RES-FLUX', key='flux', worldPackUid='WORLD-A', ...)`.
4. Save typed `PlayerResource(... resourceUid='WORLD-RES-FLUX', currentValue=9.0)`.
5. Call `resourceDefinitions()` and `playerResources('PLAYER-A')`.

Observed by code contract: legacy and typed entries use distinct UIDs, therefore both survive UID-only merge and represent the same semantic resource key `flux` with potentially different current values.

Expected Phase 4 behavior: no two simultaneous authoritative current values for one logical resource. The compatibility bridge must have an explicit non-lossy reconciliation/precedence rule.

These blockers are independent of Phase 5. A resolver must not be asked to guess which of two Phase 4 inputs is authoritative.

# Final follow-up verdict

**PHASE 4 REVALIDATION: FAIL**

WORK-006 successfully fixes the original old-campaign empty-read failure and most migration/integrity hardening requirements, but mixed old+new campaigns can still expose duplicate semantic stats/resources because reconciliation is UID-only. Phase 4 cannot be declared COMPLETE until this mixed-state ambiguity is resolved and covered by explicit stat and resource tests.