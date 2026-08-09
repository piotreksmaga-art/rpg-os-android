# PHASE 1 — UNIFIED REPOSITORY + STABLE CAMPAIGN IDENTITY

Status: COMPLETE
Roadmap item: `1. Unified Repository + stable UID`
Architecture authority: `docs/RPG_OS_MASTER_ARCHITECTURE.md`

## Scope completed

Phase 1 closes the first dependency identified after Phase 0: every runtime path that identifies the active campaign now resolves that identity through one canonical `ActiveCampaignRef`, while preserving the existing Naruto campaign as the compatibility/default identity.

## Authoritative identity

`ActiveCampaignRef` carries two different concepts explicitly:
- `directoryName` — physical campaign package directory,
- `campaignId` — stable logical campaign identity used by domain/backend layers.

Default compatibility mapping remains:
- `Naruto_Default.campaign` -> `naruto-default`.

A `campaign.json` manifest `id` overrides the directory-derived fallback, so renaming a campaign directory does not have to change its logical identity. Custom cloned campaigns rewrite the inherited manifest ID so a clone does not silently share its template identity.

## Unified repository boundary

`CampaignRepository` is the single logical repository contract required by the MASTER architecture. `UnifiedGameRepository` is its current Android/local implementation facade and delegates physical SQLite/file operations to the existing `LocalGameStore` adapter. `RpgOsApplication` exposes one canonical repository instance for application-layer adoption.

This phase intentionally does not migrate every direct SQL reader into specialized sub-repositories; that belongs to later roadmap items. Phase 1 establishes the logical boundary and campaign identity required before those migrations.

## Runtime paths aligned to ActiveCampaignRef

The following paths now derive campaign identity from the active campaign selection rather than independent hardcoded runtime values:
- `CampaignSelectionManager`,
- `LocalGameStore` save directory resolution,
- `BackupManager`,
- automatic chapter snapshot retention,
- `UpdateBackupManager`,
- `RestoreManager`,
- `ContextBuilder` metadata,
- `BackendClient` `campaign_id`,
- `AppSettings` compatibility mirror.

`RestoreManager` additionally rejects restoring a backup for a non-active campaign and rejects backup files located outside the active campaign's backup directory.

## Hardcoded legacy values

`Naruto_Default.campaign` and `naruto-default` remain only as explicit migration/default constants in `ActiveCampaignRef`. They are no longer independent runtime mutation/save identity sources.

## Tests

`app/src/test/java/com/rpgos/app/ActiveCampaignRefTest.kt` verifies:
1. legacy/default campaign backend ID compatibility,
2. deterministic fallback ID for custom campaigns,
3. stable manifest ID precedence over directory fallback,
4. database-path resolution of sibling campaign manifest identity,
5. rejection of directory traversal as a campaign identity.

GitHub Actions runs `:app:testDebugUnitTest` before the signed release build.

## CI evidence

- Build #54 proved the initial Phase 1 identity implementation with JVM tests and signed APK build passing.
- Build #62 proved the final `CampaignRepository` contract, unified facade, hardened restore boundary and expanded identity tests through JVM tests and signed APK build.

## Completion criteria

The five Phase 1 delta criteria from the canonical roadmap are satisfied:
- one authoritative active campaign reference exists in the repository layer,
- store/backup/snapshot/restore/backend campaign identity are aligned,
- independent hardcoded runtime campaign identities were removed,
- legacy Naruto campaign compatibility is preserved,
- repository/persistence identity tests exist and CI/build pass.

No frontend visual behavior was changed.
