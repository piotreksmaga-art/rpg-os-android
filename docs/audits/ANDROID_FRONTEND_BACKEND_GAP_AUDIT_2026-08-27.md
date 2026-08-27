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

## Remaining verified gaps

### P1 — visible controls without an application action

`PackagesScreen` renders `Import Save`, `Import World`, and `Eksportuj aktywny Save`, but their
`onClick` handlers are empty. `RpgPackageManager` already owns validated campaign/World Pack import
and campaign export primitives. Android Storage Access Framework selection, progress/error state,
confirmation, and end-to-end validation are still required. These controls must not be considered
functional until that vertical slice exists.

### P1 — backup and snapshot recovery is not user-accessible

The backend exposes backup listing/restoration and snapshot create/list/latest-restore operations
through `LocalGameStore`/`UnifiedGameRepository`. Android exposes only backup counts. There is no
backup browser, restore confirmation, snapshot history, or recovery result UI.

### P1 — universal character creation has no dedicated onboarding screen

The Phase 48+ application path can gather a universal character draft in chat, request missing
information, and require explicit confirmation before committing it. A new campaign currently
enters chat directly; there is no standalone creation wizard showing the selected identity,
gender, stats, talent, potential, skills, techniques, innate features, and World Pack constraints
before confirmation.

### P1 — Android character sheet still consumes the narrow legacy projection

`CharacterPanelSnapshotV2` already projects talent, potential, innate/evolution traits, inventory,
equipment slots, ownership/assets, economy, progression, projects, relationships, and goals. The
ViewModel/UI still consume `CharacterPanelSnapshot`, which displays only identity, stats,
resources, skills, techniques, equipment, relationships, and goals. The V2 projection must be
wired through a visibility-safe repository read and rendered in the character screen.

### P2 — visual actions and operation status are hidden

The ViewModel implements `editVisual`, `generateSuggestedVisual`, `imageStatus`, and generated-image
state. Android shows suggestions and library records but provides no suggestion action, edit flow,
or visible progress/error result for those operations.

## Not classified as current frontend gaps

Features whose production backend is not complete yet, and future roadmap phases such as broader
living-world automation or optional player interaction proposals, are excluded. A placeholder in a
roadmap is not treated as an implemented backend capability.
