# RPG OS — Repository Hygiene Audit

Date: 2026-08-18
Scope: `master` after merge of PR #46
Cleanup PR: #47
Mode: conservative / recovery-first

## Goal

Reduce ambiguity and obsolete root-level artifacts without risking loss of runtime code, phase 1–32 implementation evidence, recovery data, or audit history.

## KEEP — runtime / recovery critical

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

## UPDATE — COMPLETED

- `README.md` — refreshed so the root entry point points to the canonical architecture, roadmap, work protocol, coordination policy and audit evidence rather than the old RPG OS Android 1.0 feature snapshot.
- `.gitignore` — added and hardened for Gradle/IDE/build output, local secrets/signing material, APK/AAB output, Python cache, logs/temp files and generated local validation/stress outputs.

## ARCHIVE — COMPLETED FOR VERIFIED LEGACY DOCS

Historical early-alpha human-readable notes were moved under `docs/archive/legacy-alpha/` rather than destroyed:

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

The archive README explicitly marks these documents non-canonical and explains that their original paths/revisions remain recoverable from Git history.

## DELETE-FROM-HEAD — VERIFIED

The following obsolete point-in-time generated validation snapshots were removed from the current cleanup branch after exact-path/reference checks. Their original bytes remain recoverable from Git history:

- `ALPHA_VALIDATION.json`
- `ALPHA2_VALIDATION.json`
- `ALPHA3_STATIC_VALIDATION.json`
- `ALPHA4_STATIC_VALIDATION.json`
- `FULL_PROJECT_VALIDATION.json`
- `GUARDIAN_128_VALIDATION.json`
- `STATIC_AUDIT.json`
- `LONG_CAMPAIGN_STRESS_REPORT.json`
- `RPG_OS_1_0_VALIDATION_REPORT.json`

Specific evidence:

- `STATIC_AUDIT.json` was a generated output of `tools/static_audit.py`; the script writes the file and does not consume the committed old output as an input.
- `LONG_CAMPAIGN_STRESS_REPORT.json` was a historical committed stress-test result; the current stress tool writes its report independently.
- `RPG_OS_1_0_VALIDATION_REPORT.json` described the 2026-08-07 project state with 40 Kotlin files / 79 source files and explicitly stated that no Android build was executed, so it is not current validation truth.

## STALE MANIFESTS — REMOVED FROM HEAD

- `PROJECT_MANIFEST.json`
- `FINAL_MANIFEST_SHA256.json`

Both were historical snapshots, not current Phase 1–32 manifests. The project manifest still enumerated early root documents such as `UI_V0_4.md` and `V0_5_FEATURES.md`; the SHA manifest recorded hashes/sizes for the same obsolete release snapshot and the generated validation outputs removed above.

Exact-path search found no active runtime/build consumer of `PROJECT_MANIFEST.json`. `FINAL_MANIFEST_SHA256.json` was referenced by historical audit evidence, but it was not a runtime/build input. Its original version remains recoverable by commit SHA from Git history.

A future manifest should be generated deliberately from a validated exact-SHA release/build if the project still needs one; the old file must not be treated as current truth.

## Reference verification notes

- Exact-path search found no live references to `ALPHA_VALIDATION.json`, `ALPHA2_VALIDATION.json`, `ALPHA3_STATIC_VALIDATION.json`, `ALPHA4_STATIC_VALIDATION.json`, `FULL_PROJECT_VALIDATION.json`, `GUARDIAN_128_VALIDATION.json`, or the archived early-alpha root files.
- `STATIC_AUDIT.json`, `RPG_OS_1_0_VALIDATION_REPORT.json`, `LONG_CAMPAIGN_STRESS_REPORT.json`, `UI_V0_4.md`, `V0_5_FEATURES.md`, and other early release artifacts were linked only from the stale manifest/evidence layer, not required by active runtime/build code.
- Runtime assets were deliberately excluded from cleanup even when they originated in early phases, because production/bootstrap/compatibility paths still use them.

## Safety rules used

1. Never delete runtime assets solely because they are old.
2. Never remove `docs/audits/**`; audit documents are part of phase recovery evidence.
3. Never rewrite Git history for cleanup.
4. Move historical human-readable documents to an archive rather than destroying them.
5. Delete generated validation snapshots from HEAD only after confirming zero live input dependencies.
6. Keep cleanup isolated from feature/GM-engine work.
7. Require CI green before merge.
8. Use normal Git integration so every removed file remains recoverable from earlier commits.

## Current PR functional surface

PR #47 changes repository hygiene/documentation only:

- no production Kotlin changes;
- no test changes;
- no schema/migration changes;
- no workflow changes;
- no runtime asset changes;
- no Phase33 work;
- no acceptance semantics change.

## Final conclusion

The repository did contain obsolete early-alpha clutter in the root. The verified cleanup removes generated point-in-time snapshots, archives superseded human-readable alpha/release notes, removes stale manifests, adds ignore rules, and refreshes the root README while preserving the full Git/audit recovery chain and leaving all runtime/Phase 1–32 implementation material untouched.