# RPG OS — Repository Hygiene Audit

Date: 2026-08-18
Scope: `master` after merge of PR #46
Mode: conservative / recovery-first

## Goal

Reduce ambiguity and obsolete root-level artifacts without risking loss of runtime code, phase 1–32 implementation evidence, recovery data, or audit history.

## Classification

### KEEP — runtime / recovery critical

- `app/src/main/assets/Naruto_Default.campaign.zip`
- `app/src/main/assets/Naruto.worldpack.zip`
- `app/src/main/assets/rpg_core.db`
- `app/src/main/assets/api_contract.json`
- `app/**` production and test sources
- `backend/**`
- `.github/workflows/**`
- `docs/audits/**`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/PARALLEL_WORK_COORDINATION.md`

These files participate in runtime/build/recovery or form the evidence chain for completed project work.

### UPDATE

- `README.md` — useful canonical entry point, but its RPG OS Android 1.0 description predates the current phase 1–32 architecture.
- `.gitignore` — missing on `master`; should be added before further development to prevent local Gradle/IDE/build outputs from entering Git.

### ARCHIVE candidates

The following are useful as historical evidence but should not remain mixed with current canonical documentation in the repository root:

- `UI_V0_4.md`
- `V0_5_FEATURES.md`
- `README_ALPHA_FIRST.txt`
- `ALPHA_RELEASE.md`
- `ALPHA3_GITHUB_UPDATER.md`
- `ALPHA4_UI_REFRESH.md`
- `DRAGON_UI_HOTFIX_126.md`
- `GUARDIAN_128_FULL_DRAGON.md`
- `RELEASE_NOTES_1_0.md`
- `RELEASE_WORKFLOW.md`
- `CONTEXT_BUNDLE_V1_ALPHA2.md`

Recommended destination: `docs/archive/legacy-alpha/`.

### DELETE-FROM-HEAD candidates (history remains recoverable in Git)

Generated point-in-time validation artifacts whose results are obsolete as descriptions of the current repository:

- `ALPHA_VALIDATION.json`
- `ALPHA2_VALIDATION.json`
- `ALPHA3_STATIC_VALIDATION.json`
- `ALPHA4_STATIC_VALIDATION.json`
- `STATIC_AUDIT.json`
- `FULL_PROJECT_VALIDATION.json`
- `RPG_OS_1_0_VALIDATION_REPORT.json`
- `LONG_CAMPAIGN_STRESS_REPORT.json`

Before deletion, confirm no current workflow/script consumes each exact path.

### REVIEW / REGENERATE

- `PROJECT_MANIFEST.json`
- `FINAL_MANIFEST_SHA256.json`

These are valuable only if they represent the current repository. Existing versions are historical snapshots. Prefer regenerating them from current `master`; otherwise archive them with the legacy release evidence.

## Safety rules for cleanup PR

1. Never delete runtime assets solely because they are old.
2. Never remove `docs/audits/**`; audit documents are part of phase recovery evidence.
3. Never rewrite Git history for cleanup.
4. Move historical human-readable documents to an archive rather than destroying them.
5. Delete generated validation snapshots from HEAD only after confirming zero live references.
6. Keep cleanup isolated from feature/GM-engine work.
7. Require CI green before merge.
8. Use a normal merge/squash workflow so every removed file remains recoverable from Git history.

## Recommended execution order

1. Add `.gitignore`.
2. Verify references to every DELETE candidate.
3. Create `docs/archive/legacy-alpha/` and move confirmed historical docs.
4. Regenerate or archive the two manifests.
5. Update `README.md` to point to the current architecture and roadmap.
6. Run build/tests/static validation.
7. Review diff for accidental runtime/schema/assets changes.
8. Merge only after CI is green.

## Current conclusion

Cleanup is justified, but a destructive bulk deletion is not. The repository contains both obsolete release artifacts and still-live legacy assets. The safe strategy is archive-first, reference-check-before-delete, and preservation of the full Git/audit recovery chain.