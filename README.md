# RPG OS Android

RPG OS is an Android RPG runtime with local campaign persistence, canonical gameplay mutation boundaries, campaign history/evidence systems, world/domain state, import/export tooling, and supporting GM/AI architecture work.

## Canonical project documents

Use these documents as the current source of architectural and planning truth:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/PARALLEL_WORK_COORDINATION.md`
- `docs/audits/` — implementation, validation, acceptance, and post-audit evidence

Historical early-alpha notes are retained under `docs/archive/legacy-alpha/` and are not current contracts.

## Current development baseline

The repository history contains the implementation and recovery chain for Phase 1–32. Later phases may evolve those files, but earlier states remain recoverable through Git commits and audit records.

Runtime assets under `app/src/main/assets/` must not be treated as disposable legacy files merely because they originated in earlier phases; several remain part of production/bootstrap and compatibility paths.

## Validation

The project uses GitHub Actions for Android/JVM validation and immutable validation artifacts. Point-in-time generated validation JSON files are not canonical architecture documents and should not be used as current project status.

## Security

Secrets and signing material must remain outside version control. Local `.env`, keystores, generated APK/AAB files, Gradle/IDE state, and build outputs are excluded by `.gitignore`.
