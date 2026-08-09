# RPG OS — Canonical Project Work Protocol

Status: ACTIVE / CANONICAL

Purpose: this file is the operational memory for future RPG OS development sessions. Read it before making project changes.

## 1. Session startup — mandatory

At the beginning of every RPG OS development session:

1. Read `docs/PROJECT_WORK_PROTOCOL.md`.
2. Read `docs/GM_ENGINE_TARGET_ARCHITECTURE.md` for GM/data/backend work.
3. Inspect current `master` HEAD and recent commits. Repository state is authoritative for what is actually implemented.
4. Check the latest relevant GitHub Actions build before assuming the current version works.
5. Recover the last unfinished backend/GM task from project history/conversation context when available.
6. Compare that task with current repository state. Never assume an old plan is still unimplemented.
7. Continue the current backend/GM development track unless the user explicitly changes priority.

## 2. Sources of truth for development decisions

When sources disagree, use this priority:

1. User's latest explicit instruction.
2. Current repository implementation and current `master` state.
3. This operational protocol.
4. `GM_ENGINE_TARGET_ARCHITECTURE.md` for target GM architecture.
5. Confirmed decisions from previous project conversations.
6. Older plans/specifications.
7. Assistant inference.

An old TODO is not proof that a feature is missing. Inspect code first.

## 3. Never restart or duplicate implemented systems

Before implementing any planned stage:

- search the repository for existing classes, tables, migrations, tests, backend endpoints and related infrastructure;
- determine whether the feature is absent, partial, obsolete, or already complete;
- extend/refactor the existing implementation instead of creating a parallel subsystem;
- preserve stable UIDs, schemas and compatibility whenever possible.

The project has a long history. Many advanced GM/data features existed before later Android UI work.

## 4. Current product model

RPG OS is a generic RPG operating system.

- Core engine is universe-agnostic.
- Naruto, Bleach and future settings are World Packs/content modules.
- Campaign data must survive application and content updates.
- AI is Game Master, not database, memory, mechanics engine or source of truth.
- Android is the primary client target.

## 5. Frontend status — ACCEPTED AND FROZEN

The current frontend style shown by the user on 2026-08-09 is accepted.

Current accepted baseline includes the existing RPG OS home screen style and current visual language. Previous plans to continue Elemental visual refinements are superseded for now.

Until the user explicitly re-enables frontend work:

- do not redesign screens;
- do not continue five-elements visual polishing;
- do not add proactive UI/visual features;
- do not change the accepted style;
- do not refactor working UI unless a backend/core change absolutely requires a minimal compatibility adjustment;
- preserve the current frontend as-is as much as possible.

The former Elemental visual task is DEFERRED, not active.

## 6. Active development tracks

### ACTIVE — GM Engine/data integrity
CampaignRepository, Source of Truth, transactions, events, snapshots, knowledge, temporal state, retrieval, validation, memory, simulation and Director.

### ACTIVE WHEN REQUIRED — Distribution/update infrastructure
Builds, GitHub Actions, migrations, compatibility, content update plumbing and anything necessary to safely ship/test backend/core changes.

### LIMITED — Content/World Packs
Only add content needed to validate the engine. Do not aggressively expand Naruto/Bleach databases until GM foundations are reliable.

### FROZEN — Android frontend/UI
No proactive visual/interface development until the user explicitly re-enables this track.

## 7. GM Engine target order

The canonical target architecture is defined in `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`.

Target dependency order:

1. Unified Repository + stable UIDs
2. Campaign Source of Truth
3. Turn Transaction
4. Event Store
5. Snapshot System
6. Canon Divergence
7. NPC Knowledge
8. Temporal Engine
9. SQL Retriever
10. Context Builder
11. Structured GM Output
12. Validator + Counterfactual Guard
13. AI Adapter
14. Memory Engine
15. Semantic Retrieval
16. Progression Engine
17. Time Skip Processor
18. NPC Brain / Decision Engine
19. World Simulation LOD
20. Director Engine
21. Narrative Promise + Pacing
22. Anti-Repetition
23. Debug / Replay
24. Stress & Integrity Tests
25. Performance / AI cost optimization

IMPORTANT: this is a dependency order, not a blind checklist. Before each stage, audit the existing implementation and implement only the missing delta.

## 8. Snapshot and backup rule

Automatic chapter snapshots are bounded cache/restore points.

- Keep at most 6 newest automatic chapter snapshots per campaign.
- Automatic retention must not delete manual backups/exports.
- `pre_restore_*` safety backups are not part of automatic snapshot retention.
- Snapshot logic must use the active campaign; it must not be hard-coded to `Naruto_Default.campaign`.
- Immutable event history is not discarded just because old automatic snapshots are pruned.

## 9. Safe change rule

For every change:

1. Identify the smallest relevant surface.
2. Preserve unrelated working behavior.
3. Avoid broad rewrites unless architecture requires them.
4. Make schema changes migration-safe.
5. Never destroy campaign data during content/app updates.
6. Never reset GitHub authorization, repository state, signing configuration or user data unless explicitly requested.
7. Do not revert `master` to an old milestone merely because a previous conversation ended there.
8. Do not touch frozen frontend styling unless required for compatibility or explicitly requested.

## 10. Build and verification loop

After a code change:

1. Record exactly what changed.
2. Let the configured GitHub Actions pipeline run.
3. Check build status.
4. If failed, inspect the failing job/log and fix the actual cause before continuing.
5. If successful, treat that commit as the new technical baseline.
6. For data/GM changes, add or update integrity tests where practical.
7. Do not stack many unrelated changes on top of a known failing build.

## 11. Version/update discipline

- Preserve compatibility with installed campaigns whenever possible.
- Content Update must not overwrite campaign state.
- World Pack version, campaign schema, event schema, memory schema and engine version must be migratable.
- App binary updates and content updates are separate concerns.
- A documentation-only change is not proof of functional completion.

## 12. GM implementation invariants

Never violate these while developing GM Engine:

- FACT, BELIEF and NARRATIVE are distinct.
- Narrative does not automatically become fact.
- Campaign divergence overrides attempts to force canon back into place.
- Durable objects use stable UIDs.
- Important facts carry provenance.
- History is append-only; current working state may be mutable.
- A turn must ultimately become atomic: narration and authoritative changes commit together or roll back together.
- NPCs know only what they observed, were told, researched, inferred or received through an organization.
- Mechanics are resolved by engines/local rules, not invented ad hoc by narration AI.
- Durable progression does not regress without an explicit supported cause.
- Counterfactual/unsupported history must be rejected or repaired.
- Chronicle should derive from committed structured events/state.

## 13. Long-campaign constraints

Design for campaigns measured in years and millions of words/events.

Therefore:

- never require rereading the whole narrative to know current truth;
- use current working state + immutable event history + bounded retrieval;
- use snapshots for load speed, not as the only history;
- keep context sent to AI bounded;
- use simulation LOD;
- consolidate memory without recursive summary-of-summary loss;
- preserve causal links so the engine can explain why the world reached its current state;
- maintain explicit NPC knowledge to prevent information leakage.

## 14. How to choose the next task

At each milestone:

1. Is the build broken? Fix it before feature work.
2. Is there a data-loss/integrity risk? Fix it first.
3. Continue the current GM/backend development track.
4. For GM Engine, choose the earliest dependency in the target order whose missing portion blocks later work.
5. Do not expand World Pack databases aggressively until the core foundations are reliable.
6. Do not return to frontend work unless the user explicitly re-enables it.

## 15. Required end-of-session handoff

Before ending a meaningful development session, leave a concise handoff containing:

- current `master` commit/baseline;
- last successful build or current failure;
- what was completed;
- what remains unfinished;
- exact next recommended backend/GM task;
- any user decision required for that task.

If the handoff materially changes project direction, update this protocol or another canonical repo document rather than relying only on chat memory.

## 16. Immediate project sequence from this point

Unless the user changes priority:

1. Verify the current build after recent GM/snapshot/documentation changes.
2. Keep the accepted frontend frozen.
3. Audit current GM/data implementation against stages 1–5.
4. Remove hard-coded campaign assumptions, especially backup/snapshot paths, as part of Unified Repository work.
5. Complete the missing delta for Campaign Source of Truth, Turn Transaction, Event Store and Snapshot System in dependency order.
6. Continue later GM Engine stages only after those foundations are verified.
7. Expand Naruto/Bleach content only when needed for engine validation or after the relevant foundations are stable.

This protocol remains active until explicitly superseded by the user or by a newer canonical protocol committed to the repository.
