# RPG OS — PHASE 0 MUTATION PATH AUDIT

Status: EVIDENCE / PHASE 0
Architecture: `docs/RPG_OS_MASTER_ARCHITECTURE.md`
Roadmap: `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`

## 1. Current runtime GM turn path

Observed Android runtime path in `RpgOsViewModel.send()`:

PLAYER INPUT
-> append player chat message in memory
-> build ContextBundle
-> call BackendClient or SafeDemoGameMaster fallback
-> receive BackendTurnResult(narration, choices, patch, chapterEvents)
-> APPEND GM NARRATION TO CHAT IMMEDIATELY
-> if patch exists: StatePatchEngine.apply()
-> if patch succeeds: optional finalizeChapter()/backup
-> refresh read models

This means presentation currently precedes authoritative validation/commit.

## 2. Confirmed integrity defect — narration is shown before commit

`RpgOsViewModel.send()` appends `ChatMessage("gm", result.narration)` before applying `result.patch`.

If the patch is later rejected by SourceOfTruthRegistry, fails SQL, or throws, the player has already seen narration that may describe a state which never became authoritative reality.

Classification:
- Roadmap 26 Single Truth Mutation Path: PARTIAL
- Roadmap 27 Turn Transaction: PARTIAL
- Roadmap 54 Committed narrative delivery only after valid transaction: MISSING/VIOLATED BY CURRENT ORDER

Required target behavior:
GM proposal -> mechanics/validation -> transaction -> COMMIT -> only then committed narration is delivered.

Fallback narration with `patch=null` does not mutate state, but it should still be treated as presentation rather than committed world history unless separately recorded by a valid turn policy.

## 3. Confirmed integrity gap — chapter_events are not persisted

Backend contract returns `chapter_events`.

`BackendClient` parses each event and keeps it in `BackendTurnResult.chapterEvents`.

`RpgOsViewModel.send()` does not persist or otherwise consume `chapterEvents`.

Therefore:
- structured GM output exists,
- event proposals can arrive from backend,
- they do not currently form an Event Store.

Classification:
- Roadmap 30 Event Store append-only: PARTIAL/MISSING integration
- Roadmap 49 Structured GM Output: PARTIAL but real

## 4. StatePatchEngine is useful partial infrastructure

`StatePatchEngine.apply()`:
- checks allowed tables through `SourceOfTruthRegistry` when validation is required,
- validates operation verb,
- begins SQLite transaction,
- applies insert/update/delete operations,
- calls setTransactionSuccessful only after all operations,
- rolls back automatically on exception.

This is valuable infrastructure and should be incorporated into a future broader transaction boundary rather than discarded.

It does NOT currently atomically include:
- narration,
- chapter_events,
- canonical event entries,
- player/domain ledgers,
- NPC knowledge writes as a coordinated unit,
- memory writes,
- chronicle writes,
- chapter manifest,
- snapshot metadata.

Classification:
- Roadmap 27 Turn Transaction: PARTIAL.

## 5. Idempotency is not enforced

`StatePatch` contains `transactionId` and the backend generates one when missing.

Current `StatePatchEngine` only uses the transaction ID in its result message. No audited code persists a committed transaction ID and no duplicate check occurs before applying operations.

A network retry or repeated processing can therefore replay the same patch unless prevented elsewhere.

Classification:
- Roadmap 28 Idempotency/double-commit: MISSING in audited mutation path.

Target:
transactionUid/commandUid/turnUid persisted with unique semantics; repeated committed transaction => ALREADY_COMMITTED and zero duplicate effects.

## 6. Crash recovery boundary is not yet present

Current guarded try/catch blocks prevent many application crashes, but that is not equivalent to transactional crash recovery.

No audited mechanism establishes a persisted `LAST_VALID_COMMIT`, pending transaction record, recovery journal, or startup rollback/reconciliation of incomplete turns.

Classification:
- Roadmap 29 Crash recovery: MISSING in audited turn path.

SQLite protects the individual StatePatch transaction, but the larger turn lifecycle can still be split across narration, patch, chapter manifest and backup operations.

## 7. Campaign identity is still partially hard-coded

`BackendClient.sendTurn()` sends:
`campaign_id = "naruto-default"`

This ignores `CampaignSelectionManager.activeCampaignDirName()`.

`LocalGameStore` itself correctly resolves active campaign/worldpack for most local DB operations, but backend turn identity is not wired to the active campaign.

`BackupManager` separately hard-codes `saves/Naruto_Default.campaign` while `UpdateBackupManager` correctly uses active campaign selection.

Classification:
- Roadmap 1 Unified Repository + stable UID: PARTIAL.
- Multi-campaign runtime boundary is inconsistent.

## 8. Direct mutation paths found outside StatePatchEngine

Confirmed direct campaign DB writers:

### 8.1 StatePatchEngine
Purpose: authoritative/general AI patch mutations.
Status: controlled partial mutation path.

### 8.2 ChapterSaveManager
Directly writes/replaces `chapter_manifests_v2`.
Purpose: chapter metadata/manifest.
Risk: occurs after StatePatch and is not part of same atomic transaction.

### 8.3 VisualLibrary
Creates/updates schema and directly inserts `campaign_visual_library` rows.
Purpose: visual/presentation library.
Architecture classification should be PRESENTATION/auxiliary unless future game mechanics explicitly depend on visual records.
It should not be allowed to create FACT merely by adding a visual.

### 8.4 MigrationManager / AutoRepairEngine
Direct schema/system writes.
Purpose: migration/repair, not gameplay mutation.
These require a separate trusted system-transaction policy with migration provenance and safety checks.

### 8.5 RestoreManager / backup managers
File-level replacement/copy of `campaign.db`.
Purpose: recovery/backup boundary.
Must remain outside normal turn mutation but requires integrity/recovery semantics.

This confirms that 'one legal mutation path' should not literally mean one class. It means one controlled authoritative transaction policy with explicit trusted system paths for migration/restore and clearly classified presentation/cache writes.

## 9. Build pipeline does not execute tests

Current GitHub Actions workflow:
- validates Gradle tasks,
- builds signed release APK,
- packages/releases artifacts.

It does NOT run `test`, `connectedAndroidTest`, domain invariant tests, migration tests, or stress tests.

Current CI SUCCESS proves compilation/release packaging, not MASTER Definition of Done.

Classification:
- Phase 0 build baseline: COMPLETE as a build baseline.
- Roadmap 76 Integrity Test Suite: MISSING/PARTIAL only through ad-hoc tools.

## 10. Package validation is real but narrow

`PackageValidator` verifies:
- required campaign/world DB and manifest files,
- SQLite `PRAGMA integrity_check`,
- simple Core/Engine API compatibility version.

This is useful existing compatibility infrastructure but not full schema migration validation.

## 11. Current Source of Truth behavior

`SourceOfTruthRegistry`:
- loads active writable tables from core registry,
- marks reference/legacy/non-writable tables read-only,
- allows an explicit runtime table set.

This is a strong seed for authoritative write policy.

It does not yet provide the complete canonical distinctions:
FACT / BELIEF / NARRATIVE / DERIVED / CACHE / PRESENTATION plus common provenance/invariant enforcement.

## 12. Preliminary confirmed status updates

The following statuses are now supported by direct runtime evidence:

- `[-] 1. Unified Repository + stable UID`
- `[-] 2. Campaign Source of Truth`
- `[-] 26. Single Truth Mutation Path enforcement`
- `[-] 27. Turn Transaction atomic commit/rollback`
- `[ ] 28. Idempotency/double-commit protection` for the audited gameplay turn path
- `[ ] 29. Crash recovery/last valid commit` for the audited gameplay turn path
- `[-] 30. Event Store append-only` because event-like data exists but backend chapter_events are not persisted as a unified event store
- `[-] 33. Snapshot System`
- `[-] 34. Automatic snapshot retention max 6` due to active-campaign hard-code
- `[-] 36. Schema Versioning/migration safety`
- `[-] 45. Context Builder`
- `[-] 49. Structured GM Output`
- `[-] 51. Consistency Validator`
- `[ ] 54. Committed narrative delivery only after valid transaction` in current send() ordering
- `[-] 73. Backup System`
- `[-] 74. Observability`
- `[-] 76. Integrity Test Suite`

## 13. Earliest architectural blockers found

Before the future TurnTransaction can be declared correct, the project must eventually solve all of the following:

1. active campaign identity must be consistent for backend + backup + repositories,
2. proposed narration cannot be shown as committed narration before validation,
3. chapter_events must enter the same authoritative event/transaction process,
4. StatePatch transaction ID must become idempotency data rather than a label,
5. chapter manifest/event/ledger/state writes must share a coordinated commit boundary,
6. crash recovery must identify the last fully committed turn,
7. CI must execute domain/invariant tests, not only compile the APK.

These are findings only. Phase 0 does not yet implement the fixes.

## 14. Next audit work

Continue Phase 0 with:
1. inventory player-related database models through all runtime SQL references,
2. identify existing talent/potential/progression/ownership/project/equipment schemas,
3. inspect finance mutation paths and conservation semantics,
4. inspect NPC knowledge writes/acquisition paths,
5. inspect timeline/divergence readers/writers,
6. classify all 1–84 roadmap items conservatively,
7. update the canonical roadmap with evidence after the gap map is sufficiently complete.
