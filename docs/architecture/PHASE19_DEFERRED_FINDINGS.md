# Phase 19 Deferred Findings Ledger

Status: CANONICAL COORDINATION LEDGER — DEFERRED, NOT FIXED

Purpose: preserve technically valid discoveries made during the historical expanded Phase 19 without treating future-roadmap functionality as part of the accepted clean WorldRuleProvider contract.

Canonical Phase-19 scope is defined in `PHASE19_WORLD_RULE_PROVIDER_CANONICAL_SCOPE.md`. Acceptance evidence is summarized in `PHASE19_ACCEPTANCE.md`.

## Rules

- Every entry below is `DEFERRED, NOT FIXED` unless a future coordinator decision explicitly reclassifies it.
- Phase-19 acceptance does not imply that the target future phase is implemented.
- An item returns to Phase 19 only if a direct path is demonstrated from the defect to acceptance of WRONG, STALE, MIXED, or UNCOMMITTED World Pack authority by a Phase-19 resolution.
- Post-acceptance cleanup may remove redundant historical tests from the current tree. Git history at accepted runtime `5754f28ccd4f7c1f3522c0af6c34bcaf65e2dcf8` remains permanent evidence, and the historical paths below are retained for recovery.

## Deferred findings

| Historical defect ID / area | Concise description | Why it does not block accepted Phase 19 | Future roadmap phase / area | Evidence / historical material | Status |
|---|---|---|---|---|---|
| `P19-CHAT5-CREATE-CAMPAIGN-LIVE-SQLITE-SNAPSHOT-01` | `createCampaign()` can copy a mutable live SQLite campaign without a full transactional/WAL-aware snapshot model. | Campaign clone consistency is outside the accepted WorldRuleProvider authority contract unless it causes wrong Phase-19 World Pack authority. | Phase 27 transaction integrity; Phase 33 Snapshot System; Phase 71–73 Save/Branching/Backup as final architecture dictates. | Historical test path `app/src/test/java/com/rpgos/app/Phase19CreateCampaignCoherenceTest.kt` recoverable at accepted SHA `5754f28c…`; historical Phase-19 createCampaign audits/branches. | DEFERRED, NOT FIXED |
| `P19-C3-CREATE-CAMPAIGN-RESTORE-TORN-CLONE-02` | Concurrent restore/clone paths may produce a torn campaign clone under broader recovery semantics. | Concerns campaign clone/restore coherence, not the single-resolution World Pack authority observation. | Phase 27/29/33 and Phase 71–73 depending on final transaction/recovery/snapshot design. | Historical `Phase19CreateCampaignCoherenceTest.kt` plus expanded Phase-19 restore/coherence audit history at `5754f28c…`. | DEFERRED, NOT FIXED |
| WAL-aware campaign cloning | General requirement for correct cloning of live SQLite/WAL state. | Accepted Phase 19 has no campaign snapshot/branching responsibility and no database migration delta. | Snapshot/Save/Branching architecture (Phase 33, 71, 72). | Historical `Phase19CreateCampaignCoherenceTest.kt` and createCampaign hardening history at accepted SHA. | DEFERRED, NOT FIXED |
| General `createCampaign()` transactional snapshotting | Full atomic snapshot of campaign state during clone/create operations. | Not required to bind one Phase-19 resolution to one World Pack authority observation. | Phase 27/33/71/72. | Historical `Phase19CreateCampaignCoherenceTest.kt` and related audit history. | DEFERRED, NOT FIXED |
| General `RestoreManager` synchronization | Full synchronization/transaction semantics between restore and all gameplay writers/readers. | Restore correctness is broader than the read-only WorldRuleProvider authority boundary. | Phase 27/29/71/73. | Historical recovery/restore audits and expanded Phase-19 test history; recover from accepted SHA. | DEFERRED, NOT FIXED |
| Global `LAST VALID COMMIT` | Application-wide recovery to the last committed campaign truth after process interruption. | Explicit Phase 29 responsibility; accepted Phase 19 protects only World Pack authority coherence. | Phase 29 Crash recovery / LAST VALID COMMIT. | Historical `Phase19WriterCrashRecoveryHardeningTest.kt` and `Phase19ConsolidatedRecoveryHardeningTest.kt` at accepted SHA. | DEFERRED, NOT FIXED |
| General process-crash recovery | Full recovery of all canonical state after arbitrary process failure. | Broader than the provider authority contract. Only a path making wrong/stale/mixed/uncommitted World Pack content authoritative belonged to Phase 19. | Phase 29 and later integrity work. | Historical `Phase19WriterCrashRecoveryHardeningTest.kt`, `Phase19ConsolidatedRecoveryHardeningTest.kt`, and related audits at accepted SHA. | DEFERRED, NOT FIXED |
| General Snapshot System | Persistent snapshot lifecycle, retention, replay, restoration and equality. | Roadmap assigns Snapshot System after the player-domain phases. | Phase 33 Snapshot System; Phase 34 retention. | Historical expanded Phase-19 snapshot/recovery discussions and audit history. | DEFERRED, NOT FIXED |
| Save/Load | Durable save/load architecture and authoritative equality after load. | Explicit Phase 71 responsibility; not needed for WorldRuleProvider resolution authority. | Phase 71 Save/Load integration. | Cross-cutting roadmap test gaps and historical Phase-19 reports. | DEFERRED, NOT FIXED |
| Branching without DB duplication | Campaign branching architecture independent of full database duplication. | Explicitly later roadmap functionality. | Phase 72. | Historical campaign-clone findings, including `Phase19CreateCampaignCoherenceTest.kt` at accepted SHA. | DEFERRED, NOT FIXED |
| General Backup System | Backup creation/retention/restore semantics. | Explicitly later roadmap functionality and not required for provider authority. | Phase 73. | Historical backup/restore findings and audits. | DEFERRED, NOT FIXED |
| Broad recovery/cleanup availability | Failures to clean stale temporary/rollback metadata where a valid canonical package remains authoritative. | Cleanup inconvenience alone does not imply wrong Phase-19 authority. Reclassify only if stale metadata can actually be accepted as canonical World Pack content. | Phase 29 / Phase 76 integrity suite as appropriate. | Historical `Phase19WriterCrashRecoveryHardeningTest.kt`, `Phase19ConsolidatedRecoveryHardeningTest.kt`, `Phase19FinalIntegrityHardeningTest.kt` and related audits at accepted SHA. | DEFERRED, NOT FIXED |
| Historical full-system transaction infrastructure proposals | General TurnTransaction/atomic commit infrastructure discovered while hardening Phase 19. | MASTER requires it eventually, but roadmap schedules it after Phase 19; implementing it in Phase 19 would be scope creep. | Phase 27 Turn Transaction; Phase 28 idempotency. | Historical expanded hotfix/audit material. | DEFERRED, NOT FIXED |

## Post-acceptance cleanup provenance

The following historical Phase-19 regression/reproduction suites may be removed from the CURRENT canonical tree because their accepted clean-scope coverage is consolidated into the five `Phase19Canonical*` files, or because they belong to the deferred findings above. Their exact contents remain recoverable from Git history at accepted runtime SHA `5754f28ccd4f7c1f3522c0af6c34bcaf65e2dcf8`:

- `Phase19ConsolidatedRecoveryHardeningTest.kt`
- `Phase19CreateCampaignCoherenceTest.kt`
- `Phase19FinalIntegrityHardeningTest.kt`
- `Phase19WriterCrashRecoveryHardeningTest.kt`
- `WorldRuleProviderPhase19AtomicAuthorityTest.kt`
- `WorldRuleProviderPhase19AuthorityFreshnessTest.kt`
- `WorldRuleProviderPhase19BlockerReproductionTest.kt`
- `WorldRuleProviderPhase19FinalHotfixTest.kt`
- `WorldRuleProviderPhase19HardeningTest.kt`
- `WorldRuleProviderPhase19PackageContentAuthorityTest.kt`
- `WorldRuleProviderPhase19Test.kt`

Historical `docs/audits/` reports are intentionally retained in the current repository as provenance and may continue to contain statements that were true for their historical target SHA.

## Phase-19 exception rule

A recovery/package defect is IN SCOPE only when it can directly cause a subsequent Phase-19 resolution to accept a World Pack that is:

- WRONG,
- STALE,
- MIXED across authority generations, or
- UNCOMMITTED.

The accepted Phase-19 implementation contains only the minimum synchronization/validation required to prevent those outcomes. It does not implement the future general recovery architecture.
