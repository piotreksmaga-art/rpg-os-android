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

## Remaining external product gate

The official Bielik repository does not publish the exact Android artifact accepted by this build.
Real local inference still requires a separately exported and device-tested ExecuTorch package
containing a compatible quantized `.pte` model and tokenizer. Raw Transformers or GGUF downloads
must not be presented as installable RPG OS local-model packages.

## Not classified as current frontend gaps

Features whose production backend is not complete yet, and future roadmap phases such as broader
living-world automation or optional player interaction proposals, are excluded. A placeholder in a
roadmap is not treated as an implemented backend capability.
