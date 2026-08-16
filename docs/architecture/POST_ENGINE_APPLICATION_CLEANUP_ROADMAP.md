# RPG OS — Post-Engine Application Cleanup Roadmap

Status: CANONICAL DEFERRED ROADMAP ITEM

Decision date: 2026-08-16

## Coordinator decision

The currently observed application bootstrap behavior that creates/restores a bundled `Naruto_Default` campaign is **not to be changed now**.

The project priority remains completion of the full RPG OS game engine according to `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`.

This item is deliberately deferred until the engine implementation roadmap is complete. It must not interrupt Phase 20 or later engine phases merely to clean development/bootstrap campaign content.

## Deferred cleanup target

After completion of the full engine roadmap, perform a dedicated application/product cleanup pass covering at minimum:

1. Fresh production install must start with **zero user campaigns**.
2. A bundled World Pack may remain available as content, but a Campaign must be created only through the supported new-game/campaign-creation flow.
3. Remove production bootstrap behavior that automatically materializes `Naruto_Default.campaign` solely to make the application immediately runnable.
4. Remove development/test campaign content from release-facing application state where it is not required by the final product.
5. Preserve developer fixtures and automated tests outside production user data paths where still useful.
6. Verify upgrade behavior separately from fresh-install behavior; cleanup must not delete legitimate existing user campaigns.
7. Add fresh-install/first-start regression coverage proving that no campaign is created before the user creates one.
8. Revalidate package selection, World Pack availability, campaign creation, Continue/Saves screens and release APK behavior after cleanup.

## Ordering

Canonical ordering for this issue:

`complete game engine roadmap -> post-engine application/bootstrap cleanup -> final product/release hardening`

Do **not** pull this cleanup forward unless the coordinator explicitly changes priority or the existing bootstrap behavior becomes a blocker for engine correctness/testing.

## Current engine status at decision point

- Phase 19 / `PlayerDomainEngine + WorldRuleProvider`: **ACCEPTED / COMPLETE**.
- Phase 20 / progression: next engine phase; implementation is not part of this roadmap update.
- The bundled/default-campaign cleanup described above: **DEFERRED UNTIL FULL ENGINE COMPLETION**.

## Scope note

This decision records scheduling only. It intentionally makes **no production runtime change**, no database migration, no package-format change, no campaign deletion, and no frontend redesign.
