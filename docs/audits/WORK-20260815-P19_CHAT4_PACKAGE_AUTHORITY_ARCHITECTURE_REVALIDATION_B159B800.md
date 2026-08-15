# Phase 19 — CHAT-4 Package Authority Architecture Revalidation

Role: CHAT-4 — READ-ONLY ARCHITECTURE REVALIDATION

Validated SHA: `b159b8001de9b1e57caa11fcc070a0a9dadfe5be`

Audit result: PASS

## Canonical bootstrap

Reviewed canonical `RPG_OS_MASTER_ARCHITECTURE.md`, `RPG_OS_IMPLEMENTATION_ROADMAP.md`, and `PARALLEL_WORK_COORDINATION.md` from the exact target. MASTER requires a single legal truth-mutation path and keeps `PlayerChangeSet` proposal-only until later transaction/COMMIT. Roadmap still marks Phase 19 BLOCKED pending final acceptance and Phase 20 MISSING/BLOCKED by dependency.

## Authority architecture

`CampaignSelectionManager` remains the canonical app-level selection authority. `CanonicalPackageAuthorityGate` stores no authority; it is a process-local `ReentrantReadWriteLock` used only to keep canonical selection/package observations coherent with supported selection/package mutations.

`CanonicalSelectionWorldPackAuthoritySource.currentAuthority()` holds the gate read side while it captures one `SharedPreferences.all` snapshot and reads/validates the campaign and World Pack package content needed to construct immutable `CurrentWorldPackAuthority(campaignUid, WorldPackRuleBinding(worldPackUid, worldPackVersion))`.

`CurrentSelectionWorldPackAuthorityResolver` receives only `WorldPackAuthoritySource` and exposes only `bindingForCampaign()`. `PlayerDomainEngine` therefore receives a narrow read-only authority capability and no package mutation, selection mutation, repository writer, DB writer, StatePatch, transaction or COMMIT authority.

No second persisted authority was introduced. `CurrentWorldPackAuthority`, `WorldPackAuthoritySource`, resolver and gate are transient process structures. Persisted selection remains `rpgos_selection` preferences plus existing package manifests/databases.

## Package mutation boundary

Supported authority-relevant writers are serialized at the package boundary:

- `CampaignSelectionManager.setActiveCampaign()` and `setActiveWorldPack()` use gate write side.
- `RpgPackageManager.importCampaign/importWorldPack/validatedImportCampaign/validatedImportWorldPack` use gate write side and unlocked private helpers to avoid nested gate acquisition.
- `ContentUpdateManager.install()` downloads, hashes, stages and validates outside the gate, then holds gate write side only for target activation/replacement, registry save and rollback window.

This is the correct boundary for package-content TOCTOU. The gate is not a global system transaction lock: it does not cover PlayerDomainEngine resolution itself, DB gameplay operations, network download, staging unzip, UI, GM, or unrelated Core work. ContentUpdate activation uses the same gate for all package types, which is slightly broader than strictly necessary for World Pack authority but bounded to activation and is not an architectural blocker.

## Lock scope / ordering

PASS. One gate exists and the audited production paths do not establish a multi-lock ordering protocol around it. `RpgPackageManager` avoids recursive public wrappers through `*Unlocked` helpers. The lock is reentrant/fair and authority reads never attempt package mutation. No lock cycle or reverse ordering with PlayerDomainEngine was found.

The direct package-manager import path performs unzip while holding the package-authority write lock; this can increase contention for large imports, but it serializes the exact mutable target and does not lock the whole RPG OS. This is a performance refinement opportunity, not a correctness blocker.

## Package identity

PASS. Logical World Pack identity remains manifest-based: `PackageValidator.validateWorldPack()` returns `worldpack.json.id` and `version`; directory name remains a locator/alias. Existing regression explicitly proves `friendly-name.worldpack` can carry logical ID `A` without the directory overriding manifest identity.

Package validation format is unchanged from the previous target: `PackageValidation.kt` retains the same blob SHA. No package schema/manifest contract migration was added by this hotfix.

## Canonical pipeline

The production pipeline remains:

`canonical command validation -> Phase-18 command reference validation -> coherent current World Pack authority -> COMMAND_PRECHECK -> resolution component -> Phase-18 draft references -> DRAFT_EFFECT_CHECK -> engine-owned PlayerChangeSet -> Phase-17 PlayerChangeSetValidator`.

The package gate changes only how current authority is observed; it does not move reference/scope semantics into Phase 19 and does not add legality to Phase 18.

## Layering

Phase 18 remains the reference/scope layer. Phase 19 remains World Pack legality. `PlayerChangeSet` remains proposal-only; transaction/COMMIT are still downstream. `PlayerDomainEngine` production file is unchanged by the package-content hotfix line.

Core remains world-agnostic. The gate/source/resolver use generic campaign/World Pack identities and no Naruto/Bleach rule semantics. Existing default package directory compatibility (`Naruto.worldpack`) remains app-level legacy selection behavior and was not introduced as Phase-19 Core legality.

## Schema / migrations / future phase

- PLAYERCHANGESET SCHEMA DELTA: NONE (`PLAYER_CHANGE_SET_SCHEMA_VERSION = 1`).
- DATABASE/MIGRATION DELTA: NONE.
- PACKAGE FORMAT DELTA: NONE.
- SELECTION FORMAT DELTA: NONE; existing preference keys are retained.
- SECOND PERSISTED AUTHORITY: NONE.
- PHASE-20 RUNTIME DELTA: NONE; repository search finds planning/audit references only, not a production `ProgressionEngine`.

Phase-20 readiness is preserved. A future ProgressionEngine can continue consuming Phase-19 legality evidence/fingerprints downstream of the same coherent authority observation; this package synchronization mechanism does not require Phase 20 to know about locks or persistence.

## Tests and CI

`WorldRuleProviderPhase19PackageContentAuthorityTest` covers controlled selection/package replacement interleavings, C1/A1 -> package A2, attempted C1+B hybrid through import, long-lived engine across package/campaign switches, read failure fail-closed, one authority observation per resolution, shared pinned binding across both Phase-19 stages, and directory alias != manifest logical identity.

Exact GitHub Actions verified:

- run number: `#523`
- run ID: `31895532681`
- head SHA: `b159b8001de9b1e57caa11fcc070a0a9dadfe5be`
- status/conclusion: `completed/success`
- `Run JVM unit tests`: success
- signed validation APK: success
- immutable artifact preparation/upload: success

## Final matrix

- AUTHORITY ARCHITECTURE: PASS
- PACKAGE GATE DESIGN: PASS
- CANONICAL SOURCE: PASS
- READ-ONLY DEPENDENCY: PASS
- LOCK SCOPE: PASS
- LOCK ORDERING: PASS
- ENGINE LIFECYCLE: PASS
- CROSS-CAMPAIGN: PASS
- PHASE-18 LAYERING: PASS
- PHASE-19 RESPONSIBILITY: PASS
- PLAYERCHANGESET SCHEMA DELTA: NONE
- DATABASE/MIGRATION DELTA: NONE
- PACKAGE FORMAT DELTA: NONE
- SELECTION FORMAT DELTA: NONE
- SECOND PERSISTED AUTHORITY: NONE
- WORLD-AGNOSTIC: PASS
- PHASE-20 RUNTIME DELTA: NONE
- PHASE-20 READINESS: PASS
- FULL JVM: PASS (exact CI)
- EXACT CI: PASS
- NEW ARCHITECTURE BLOCKERS: NONE

FINAL CHAT-4 VERDICT: PASS

This report does not globally accept Phase 19 and does not begin Phase 20.