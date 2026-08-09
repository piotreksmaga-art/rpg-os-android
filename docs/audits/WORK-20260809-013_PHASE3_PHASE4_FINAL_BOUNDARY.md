# WORK-20260809-013 — FINAL PHASE 3/4 BOUNDARY RE-CHECK

Role: CHAT-5 — READ-ONLY Phase 3/4 boundary auditor  
Audited runtime commit: `91763b733d9ed3eaa3d804c77394fb7f87b7be3b`  
Runtime commit message: `WORK-20260809-006 — add lossless Phase 4 legacy read-through`  
CI evidence: GitHub Actions run #128 — SUCCESS  
Scope: boundary integrity only. This report does not validate all Phase 4 Definition-of-Done requirements and does not authorize Phase 5 implementation.

## Executive result

The Phase 4 compatibility projection introduced by WORK-006 does not mutate or replace the Phase 3 active-player identity, does not reintroduce per-turn player guessing, does not promote max/effective/regeneration status fields into current resources, and does not rewrite Phase 3 legacy player state. Existing Phase 3 read paths remain intact.

A separate mixed legacy/new semantic-reconciliation debt remains: `StatResourceStore` merges typed and legacy values by stable definition UID, not by semantic key. Therefore a legacy `strength` and a newly persisted World-Pack `strength` may coexist as two typed values if they use different UIDs. This is a Phase 4/Phase 5 input-contract concern that must be decided by the Phase 4 revalidator / Phase 5 compatibility auditor. It does not by itself constitute a regression of the existing Phase 3 contract because Phase 3 legacy authoritative rows are neither overwritten nor removed and `PlayerStateStore` continues to expose them unchanged.

## 1. ActivePlayerRef remains authoritative — PASS

`ActivePlayerStore` remains the persisted authority for active player identity. WORK-006 did not modify `ActivePlayerStore`, `ActivePlayerRef`, or the Phase 3 migration table.

`LocalGameStore.playerStats()` and `playerResources()` resolve the active player UID through `ActivePlayerStore` and pass that UID into `StatResourceStore`. The compatibility layer itself does not persist or reseed ActivePlayerRef.

## 2. Legacy stats use source entity_uid, not an active-player heuristic — PASS

`LegacyStatResourceCompatibility.playerStats(db, campaignId, characterUid)` queries:

`SELECT stat_key,current_value FROM character_stats WHERE entity_uid=?`

using the supplied `characterUid`.

It does not call a player-selection heuristic and does not choose a first row. Every entity remains addressable independently. Production tests cover PLAYER-A and PLAYER-B with different legacy values.

## 3. Status snapshot without entity_uid is only used when unambiguous — PASS

For `character_status_snapshot` without `entity_uid`, compatibility behavior is:

1. zero rows -> empty compatibility result,
2. more than one row -> fail loudly as ambiguous,
3. exactly one row -> read only when persisted `ActivePlayerStore.active()?.playerUid` equals the requested character UID.

It never writes ActivePlayerRef and never chooses an arbitrary first character. WORK-006 includes a production test proving that unresolved multi-player identity remains unresolved and that after explicitly selecting PLAYER-B only PLAYER-B can see the unscoped snapshot.

## 4. PlayerStat.baseValue does not receive derived/runtime status fields — PASS WITH CONTRACT NOTE

Legacy stat compatibility reads only `character_stats(entity_uid, stat_key, current_value)`. Phase 3 already treats the `character_stats` collection as persistent player state. WORK-006 does not source PlayerStat values from `character_status_snapshot`, temporary effects, injury modifiers, effective values, max values, or regeneration fields.

Therefore the compatibility adapter preserves the pre-existing Phase 3 authority interpretation of `character_stats.current_value` when exposing it as the Phase 4 legacy-compatible `PlayerStat.baseValue`.

Contract note: whether a particular historical `character_stats.stat_key` was semantically derived in some old content cannot be inferred safely from its arbitrary key. WORK-006 correctly does not invent such semantics; it preserves the Phase 3 representation rather than reclassifying it.

## 5. PlayerResource.currentValue excludes max/regeneration/effective derived fields — PASS

`safeCurrentResourceKey()` first rejects every field classified by Phase 3 as DERIVED. Phase 3 classifies `effective_*`, `derived_*`, `max_*`, `regeneration*`, `net_worth`, and combat-rating-like fields as derived.

The compatibility policy only promotes field shapes that explicitly or structurally indicate a current resource, including generic `current_resource_<key>`, `resource_<key>_current`, or current/bare forms with sufficient structural evidence. Max/effective/regeneration columns themselves are not emitted as PlayerResource values.

WORK-006 production tests verify that `max_aether`, `regeneration_aether`, and `effective_guard` are excluded while generic current values such as aether/void_flux/echo are retained.

## 6. PlayerStateStore still reads legacy state without loss — PASS

WORK-006 did not modify `PlayerStateStore`. Phase 3 continues to read:

- `character_stats` into PERSISTENT,
- legacy status fields through `PlayerStatePolicy` into PERSISTENT / DERIVED / RUNTIME,
- active-player-scoped skills, techniques, finances, organizations, goals, position and injuries.

The compatibility layer is read-through only. It leaves legacy `character_stats` and `character_status_snapshot` bytes unchanged. Production tests explicitly assert that the compatibility path does not copy legacy truth into `player_stats` / `player_resources` and that original rows remain present.

## 7. CharacterPanel remains scoped by active Player UID — PASS

WORK-006 did not modify `CharacterPanelReader`. The reader still requires a resolved player UID. Entity tables are queried with `WHERE entity_uid=?`; missing player UID returns the unresolved panel. The no-UID legacy status path is only accepted for a provably single-row snapshot, not a random first entity.

No global/first-character fallback was reintroduced by WORK-006.

## 8. Reserved legacy namespace cannot be claimed through normal World Pack registration — PASS

Compatibility reserves:

- World Pack UID `RPGOS-LEGACY-COMPAT`,
- definition prefixes `RPGOS-LEGACY-STAT-` and `RPGOS-LEGACY-RESOURCE-`.

`StatResourceStore.registerStatDefinitions()` and `registerResourceDefinitions()` reject the reserved World Pack namespace and reserved definition UID prefixes. Reads also fail loudly if persisted normal definitions/values already occupy the reserved compatibility namespace.

A production test covers attempted registration under `RPGOS-LEGACY-COMPAT`.

## 9. Campaign isolation — PASS

Phase 4 persisted values remain scoped by `(campaign_id, character_uid, definition_uid)`. Legacy compatibility reads legacy rows from the currently opened campaign DB and stamps returned typed projections with the caller's campaign ID.

WORK-006 includes a separate-physical-database test using the same character UID with different legacy values in campaign A and campaign B and verifies different results. Existing typed-value tests also cover campaign + character isolation.

The unscoped single-row status compatibility path consults `ActivePlayerStore` for the same campaign ID; it cannot borrow another campaign's ActivePlayerRef.

## 10. Mixed legacy/new state and Phase 3 truth semantics — PASS AT PHASE-3 BOUNDARY; PHASE-4/5 RECONCILIATION DEBT

WORK-006 leaves legacy rows authoritative in legacy storage and exposes them as a compatibility projection. Persisted Phase 4 values are stored separately. The merge functions deduplicate by stable definition UID.

Because legacy UIDs are deterministic hashes in a reserved namespace, a normal typed World Pack definition cannot collide with them by UID. This protects identity but does not establish semantic-key reconciliation. Example:

- legacy compatibility definition key `strength`, UID `RPGOS-LEGACY-STAT-<hash>`,
- new World Pack definition key `strength`, UID `STAT-WORLD-X-STRENGTH`.

Both may be returned by an unfiltered typed read because their UIDs differ. WORK-006 has no explicit semantic precedence rule saying that the new representation supersedes, aliases, or replaces the legacy representation.

This is important technical debt / potentially a Phase 4 or Phase 5 gate. It is **not classified as a Phase 3 regression in this boundary audit** for the following reasons:

1. no existing Phase 3 authoritative legacy row is deleted, rewritten or reclassified;
2. `ActivePlayerRef` is unchanged;
3. `PlayerStateStore` is unchanged and continues to expose the legacy Phase 3 state exactly as before;
4. Phase 4 compatibility is a read projection, not a write-back into Phase 3;
5. the conflict is introduced only when a consumer asks the Phase 4 typed model to combine an old semantic key with a separately-created new World Pack definition.

The Phase 4 final revalidator and Phase 5 input-compatibility auditor should decide whether semantic-key coexistence blocks Phase 4 COMPLETE or Phase 5 READY. A future resolver must not silently add both values as if they were independent if they represent the same logical stat/resource.

## Regression matrix

| Boundary guarantee | Result | Notes |
|---|---|---|
| ActivePlayerRef authoritative | PASS | No new active-player store or write path |
| Legacy stats scoped by source entity_uid | PASS | Direct `WHERE entity_uid=?` |
| No per-turn player heuristic | PASS | Compatibility does not seed/select player |
| Unscoped status snapshot deterministic | PASS | Exactly one row + persisted active UID gate |
| ActivePlayerRef unchanged by Phase 4 | PASS | Read-through only |
| Base stat does not receive derived status fields | PASS | Source limited to legacy character_stats |
| Current resource excludes max | PASS | Derived classification / shape policy |
| Current resource excludes regeneration | PASS | Derived classification |
| Current resource excludes effective values | PASS | Derived classification |
| PlayerStateStore legacy reads preserved | PASS | Store unchanged; legacy rows untouched |
| CharacterPanel player scoping preserved | PASS | Reader unchanged |
| Reserved legacy namespace protected | PASS | Registration + read integrity guards |
| Campaign isolation | PASS | Campaign-scoped typed storage + physical legacy DB isolation test |
| Mixed legacy/new semantic reconciliation | DEBT / EXTERNAL GATE | UID-safe but semantic-key precedence absent; Phase 4/5 decision required |

## CI / tests

The audited runtime is exactly `91763b733d9ed3eaa3d804c77394fb7f87b7be3b`.

GitHub Actions run #128 (`Build & Release RPG OS ALPHA`) completed with `success` for this exact head SHA.

WORK-006 expanded `StatResourcePersistenceTest` to cover legacy typed visibility, unknown keys, player isolation, reopen stability, unscoped snapshot behavior, physical campaign isolation, fail-loud duplicate legacy rows, reserved namespace protection, >1000 values, idempotency, integrity and FK checks, and safe resource-field classification.

## Final verdict

**PHASE 3/4 FINAL BOUNDARY: PASS**

This PASS means WORK-006 did not regress Phase 3 guarantees. It is not a declaration that Phase 4 is COMPLETE and it does not authorize Phase 5 implementation. The mixed legacy/new semantic-key reconciliation question remains explicitly delegated to the Phase 4 revalidation and Phase 5 input-contract audits before the coordinator opens Phase 5 implementation.
