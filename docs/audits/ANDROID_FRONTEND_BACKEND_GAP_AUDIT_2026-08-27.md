# Android frontend ↔ backend gap audit — 2026-08-27

## Scope

This audit compares production Android presentation in `MainActivity.kt` and `RpgOsViewModel.kt`
with already implemented application/core capabilities. It does not classify roadmap-only future
phases as frontend defects.

Phase 45 is not an AI settings phase. It owns context integrity/projection. The production AI
provider boundary and provider-center contracts belong to Phase 48.

## Closed in `1.3.0-alpha8-core54`

- The existing provider-independent `AiProviderCenterScreen` is reachable from main Settings,
  before a campaign has been created. The campaign menu and Settings reuse the same component and
  persisted configuration.
- New-campaign creation is asynchronous, disables repeated submission, navigates only after
  success, reports failures in the screen, and records diagnostics instead of crashing the app.
- A failed post-clone migration/bootstrap restores the preceding active campaign and quarantines
  the incomplete clone.
- Non-active user campaigns have a confirmation-gated removal action. Removal is recoverable: the
  campaign is moved atomically to hidden `saves/.trash`. The active campaign and bundled system
  template are protected.
- Home counters now use current World Pack, user-campaign and active-backup state instead of fixed
  demonstration values. The displayed alpha label follows `BuildConfig.VERSION_NAME`.

## Closed in `1.3.0-alpha9-core54`

- Campaign and World Pack import now use Android's document picker, copy the selected package into
  private staging, run the existing validated import boundary, choose a collision-free package
  name, and activate only a successful import. Campaign export uses the system save-document picker.
- Backup and snapshot recovery are user-accessible. The screen lists backups and valid recoverable
  snapshots, requires restore confirmation, reports progress/results, and can create a pinned manual
  snapshot. Existing verified staging/activation remains the only snapshot restore path.
- A dedicated universal character-creation route opens for a campaign without an active player.
  It delegates the conversation, missing-information loop, draft and explicit confirmation to the
  existing Phase 48+ application contract and derives available character fields from the active
  World Pack instead of hard-coding Naruto data.
- Android now consumes `CharacterPanelSnapshotV2` through a production read-only adapter and the
  existing visibility gate. The character screen renders talent, potential, innate/evolution,
  inventory, equipment slots, ownership/assets, economy, progression, projects, relationships and
  goals, with the narrow legacy panel retained only as a compatibility fallback.
- Visual suggestions are actionable, saved library entries open an edit flow, and image operation
  progress/error state is visible.
- The AI panel identifies the exact local artifact contract: a ZIP containing an ExecuTorch `.pte`
  model and tokenizer. It links to the official Bielik source page without presenting raw GGUF as a
  compatible Android artifact.

## Closed in `1.3.0-alpha10-core54`

- A cloned Naruto campaign can already contain Phase-32 administrative guards. New-campaign,
  activation, World Pack change, backup restore and snapshot restore preparation now share one
  lifecycle-serialized ADMIN boundary before gameplay readiness is re-established. The real clone
  regression test closes the phone-observed
  `RPGOS-G32:MECHANICS_DEFINITION_REQUIRES_ADMIN / SQLITE_CONSTRAINT_TRIGGER[1811]` failure.
- Same-campaign turns now share one canonical write order at the process lifecycle boundary. An
  identical concurrent retry therefore becomes one commit plus one replay instead of surfacing a
  transient SQLite lock; turns in different campaigns remain independent.
- The AI panel defaults to the verified Bielik 1.5B mobile profile (2048-token exported context),
  offers the complete release package, and no longer presents the legacy 4.5B/3433-MB profile as
  the default.
- The OpenRouter loopback page reports success only after code exchange and encrypted credential
  storage. Exact provider reasons remain visible, and an online-validated manual API-key route is
  available as a supported recovery path.

## Remaining external product gate

The earlier missing-artifact gate is closed for the mobile default: this run exported and verified
the complete `RPG-OS-Bielik-1.5B-v3-ExecuTorch-XNNPACK.zip` package containing a compatible
923,083,008-byte `.pte` model and tokenizer. The model SHA-256 is
`4e5a6b8e6684e94d794a609a2f76cfb56f3b3ddef3dfc96904cd10f40244457e`; the ZIP SHA-256 is
`d79d42d6a0bea8b21e9bcd4e00424be451c4167d5329d7bd9515987bdbb3181a`.

Host-side ExecuTorch loading is green. Physical-device load, real inference, sustained memory,
thermal and performance measurements remain the external product gate. Raw Transformers or GGUF
downloads are still not presented as installable packages for the ExecuTorch Android path.

## Not classified as current frontend gaps

Features whose production backend is not complete yet, and future roadmap phases such as broader
living-world automation or optional player interaction proposals, are excluded. A placeholder in a
roadmap is not treated as an implemented backend capability.
