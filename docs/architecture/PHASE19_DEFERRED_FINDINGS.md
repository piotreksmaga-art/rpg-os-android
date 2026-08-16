# Phase 19 Deferred Findings Ledger

Status: CANONICAL COORDINATION LEDGER — DEFERRED, NOT FIXED

Purpose: preserve technically valid discoveries made during the historical expanded Phase 19 without treating future-roadmap functionality as a blocker for the clean canonical WorldRuleProvider contract.

Canonical Phase 19 scope is defined separately in `PHASE19_WORLD_RULE_PROVIDER_CANONICAL_SCOPE.md`.

## Rules

- Every entry below is `DEFERRED, NOT FIXED` unless a future coordinator decision explicitly reclassifies it.
- Deferral does not imply that the target future phase is implemented.
- An item returns to Phase 19 only if a direct path is demonstrated from the defect to acceptance of WRONG, STALE, MIXED, or UNCOMMITTED World Pack authority by a Phase-19 resolution.
- Historical reports/tests remain evidence until later cleanup; Git history remains permanent evidence after cleanup.

## Deferred findings

| Historical defect ID / area | Concise description | Why it does not block clean Phase 19 | Future roadmap phase / area | Evidence / historical material | Status |
|---|---|---|---|---|---|
| `P19-CHAT5-CREATE-CAMPAIGN-LIVE-SQLITE-SNAPSHOT-01` | `createCampaign()` can copy a mutable live SQLite campaign without a full transactional/WAL-aware snapshot model. | Campaign clone consistency is not part of the WorldRuleProvider authority contract unless it can be shown to cause a wrong Phase-19 World Pack authority decision. No such direct path is part of the clean scope decision. | Phase 27 transaction integrity; Phase 33 Snapshot System; Phase 71–73 Save/Branching/Backup as final architecture dictates. | Historical Phase-19 createCampaign audits/tests and expanded hotfix branches. | DEFERRED, NOT FIXED |
| `P19-C3-CREATE-CAMPAIGN-RESTORE-TORN-CLONE-02` | Concurrent restore/clone paths may produce a torn campaign clone under broader recovery semantics. | The defect concerns campaign clone/restore coherence, not the single-resolution World Pack authority observation. | Phase 27/29/33 and Phase 71–73 depending on final transaction/recovery/snapshot design. | Historical reproducer/audit material from expanded Phase 19. | DEFERRED, NOT FIXED |
| WAL-aware campaign cloning | General requirement for correct cloning of live SQLite/WAL state. | Clean Phase 19 has no campaign snapshot/branching responsibility and no database migration delta. | Snapshot/Save/Branching architecture (Phase 33, 71, 72). | Historical createCampaign hardening material. | DEFERRED, NOT FIXED |
| General `createCampaign()` transactional snapshotting | Full atomic snapshot of campaign state during clone/create operations. | Not required to bind one Phase-19 resolution to one World Pack authority observation. | Phase 27/33/71/72. | Historical createCampaign tests/reports. | DEFERRED, NOT FIXED |
| General `RestoreManager` synchronization | Full synchronization/transaction semantics between restore and all gameplay writers/readers. | Restore correctness is broader than the read-only WorldRuleProvider authority boundary. | Phase 27/29/71/73. | Historical recovery/restore reports and reproducers. | DEFERRED, NOT FIXED |
| Global `LAST VALID COMMIT` | Application-wide recovery to the last committed campaign truth after process interruption. | Explicitly belongs to roadmap Phase 29; clean Phase 19 protects only World Pack authority coherence. | Phase 29 Crash recovery / LAST VALID COMMIT. | Historical Phase-19 crash/recovery hardening suites. | DEFERRED, NOT FIXED |
| General process-crash recovery | Full recovery of all canonical state after arbitrary process failure. | Broader than the provider authority contract. Only a concrete path that makes wrong/stale/mixed/uncommitted World Pack content authoritative would be a Phase-19 blocker. | Phase 29 and later integrity work. | `Phase19WriterCrashRecoveryHardeningTest`, consolidated recovery material, related audits. | DEFERRED, NOT FIXED |
| General Snapshot System | Persistent snapshot lifecycle, retention, replay, restoration and equality. | Roadmap assigns Snapshot System after the player-domain phases. | Phase 33 Snapshot System; Phase 34 retention. | Historical expanded Phase-19 snapshot/recovery discussions. | DEFERRED, NOT FIXED |
| Save/Load | Durable save/load architecture and authoritative equality after load. | Explicit Phase 71 responsibility; not needed for WorldRuleProvider resolution authority. | Phase 71 Save/Load integration. | Cross-cutting roadmap test gaps and historical reports. | DEFERRED, NOT FIXED |
| Branching without DB duplication | Campaign branching architecture independent of full database duplication. | Explicitly later roadmap functionality. | Phase 72. | Historical campaign-clone findings. | DEFERRED, NOT FIXED |
| General Backup System | Backup creation/retention/restore semantics. | Explicitly later roadmap functionality and not required for provider authority. | Phase 73. | Historical backup/restore findings. | DEFERRED, NOT FIXED |
| Broad recovery/cleanup availability | Failures to clean stale temporary/rollback metadata where a valid canonical package remains authoritative. | Cleanup inconvenience alone does not imply wrong Phase-19 authority. Reclassify only if stale metadata can actually be accepted as canonical World Pack content. | Phase 29 / Phase 76 integrity suite as appropriate. | Historical recovery cleanup tests/audits. | DEFERRED, NOT FIXED |
| Historical full-system transaction infrastructure proposals | General TurnTransaction/atomic commit infrastructure discovered while hardening Phase 19. | MASTER requires it eventually, but roadmap schedules it after Phase 19; implementing it now would be scope creep. | Phase 27 Turn Transaction; Phase 28 idempotency. | Historical expanded hotfix/audit material. | DEFERRED, NOT FIXED |

## Phase-19 exception rule

A recovery/package defect is IN SCOPE only when it can directly cause a subsequent Phase-19 resolution to accept a World Pack that is:

- WRONG,
- STALE,
- MIXED across authority generations, or
- UNCOMMITTED.

The clean Phase-19 implementation may add only the minimum synchronization/validation required to prevent that outcome. It must not implement the future general recovery architecture while doing so.
