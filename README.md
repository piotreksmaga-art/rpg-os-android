# RPG OS Android

RPG OS is an Android RPG runtime with local campaign persistence, canonical gameplay mutation boundaries, campaign history/evidence systems, world/domain state, import/export tooling, and supporting GM/AI architecture work.

## Canonical project documents

Use these documents as the current source of architectural and planning truth:

- `docs/Architektura projektu.md` — single canonical project architecture
- `docs/Roadmap.md` — single canonical roadmap
- `docs/Mapa plików faz 1-32.md` — technical navigation/recovery index for completed phases
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/PARALLEL_WORK_COORDINATION.md`
- `docs/audits/` — implementation, validation, acceptance, and post-audit evidence

Historical early-alpha notes are retained under `docs/archive/legacy-alpha/` and are not current contracts. If archived material conflicts with current canonical architecture or an accepted runtime contract, the current canonical source wins.

## Current development baseline

The repository history contains the implementation and recovery chain for Phase 1–32. Later phases may evolve those files, but earlier states remain recoverable through Git commits and audit records.

Runtime assets under `app/src/main/assets/` must not be treated as disposable legacy files merely because they originated in earlier phases; several remain part of production/bootstrap and compatibility paths.

## Repository hygiene and recovery

Point-in-time generated validation snapshots are not current project truth. Obsolete generated outputs may be removed from the current HEAD after reference verification because their original bytes remain recoverable from Git history. Historical human-readable design/release notes are archived rather than destroyed.

The current cleanup classification and evidence are recorded in `docs/audits/REPO_HYGIENE_AUDIT_2026-08-18.md`.

## Validation

The project uses GitHub Actions for Android/JVM validation and immutable validation artifacts. Point-in-time generated validation JSON files should not be used as a substitute for exact-SHA CI evidence or the canonical architecture/roadmap.

## Security

Secrets and signing material must remain outside version control. Local `.env`, keystores, generated APK/AAB files, Gradle/IDE state, and build outputs are excluded by `.gitignore`.
