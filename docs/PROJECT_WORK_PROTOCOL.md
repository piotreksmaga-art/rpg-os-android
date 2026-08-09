# RPG OS — Canonical Project Work Protocol

Status: ACTIVE / CANONICAL

Purpose: this file is the operational memory for future RPG OS development sessions. Read it before making project changes.

## 1. Session startup — mandatory

At the beginning of every RPG OS development session:

1. Read `docs/PROJECT_WORK_PROTOCOL.md`.
2. Read `docs/GM_ENGINE_TARGET_ARCHITECTURE.md` for all GM/data/backend/player-domain work.
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
4. `GM_ENGINE_TARGET_ARCHITECTURE.md` as the single canonical architecture for GM Engine + Player Domain.
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

The canonical implementation order is a dependency order, not a blind checklist. Audit first, implement only the missing delta.

## 4. Current product model

RPG OS is a generic RPG operating system.

- Core engine is universe-agnostic.
- Naruto, Bleach and future settings are World Packs/content modules.
- Campaign data must survive application and content updates.
- AI is Game Master, not database, memory, mechanics calculator or source of truth.
- Player mechanics are resolved by Player Domain/local rules and committed transactionally.
- Android is the primary client target.

## 5. Frontend status — ACCEPTED AND FROZEN

The current frontend style shown by the user on 2026-08-09 is accepted.

Until the user explicitly re-enables frontend work:

- do not redesign screens;
- do not continue five-elements visual polishing;
- do not add proactive UI/visual features;
- do not change the accepted style;
- do not refactor working UI unless a backend/core/domain change absolutely requires a minimal compatibility adjustment;
- preserve the current frontend as-is as much as possible.

CharacterPanelSnapshot v2 is currently a backend/domain contract. Do not turn this task into a frontend redesign.

## 6. Active development tracks

### ACTIVE — GM Engine / Player Domain / data integrity
CampaignRepository, Source of Truth, Player State, PlayerDomainEngine, progression, skills, techniques, talent/potential, inventory, economy, assets, transactions, events, snapshots, knowledge, temporal state, retrieval, validation, memory, simulation and Director.

### ACTIVE WHEN REQUIRED — Distribution/update infrastructure
Builds, GitHub Actions, migrations, compatibility, content update plumbing and anything necessary to safely ship/test core changes.

### LIMITED — Content/World Packs
Add only the content/rules needed to validate the engine. Do not aggressively expand Naruto/Bleach databases until foundations are reliable.

### FROZEN — Android frontend/UI
No proactive visual/interface development until the user explicitly re-enables this track.

## 7. Canonical architecture and implementation order

The only canonical architecture/order is maintained in `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`.

Current high-level dependency groups are:

FOUNDATION
1. Unified Repository + stable UIDs
2. Campaign Source of Truth + provenance + FACT/BELIEF/NARRATIVE

PLAYER DOMAIN FOUNDATION
3. Player State Contract
4. Dynamic Stat & Resource definitions
5. Talent & Potential
6. Skill / Technique / Innate Ability model
7. Inventory / Equipment / Ownership
8. Economy / Financial Ledger / Assets
9. DevelopmentProject model
10. Player commands + PlayerChangeSet
11. PlayerDomainEngine + WorldRuleProvider
12. Progression + diminishing returns + passive progression
13. Player Invariant Validator / No-Retrogression
14. Player Ledgers + provenance
15. CharacterPanelSnapshot v2 + PlayerSnapshotBuilder

TRANSACTIONAL CAMPAIGN CORE
16. Turn Transaction
17. Event Store
18. Causal Graph
19. Snapshot System
20. Canon Divergence

Then continue with knowledge/time/retrieval, structured GM output/validation/AI, memory/simulation/director, and finally hardening/optimization exactly as defined in the architecture document.

Before implementing any item, audit existing implementation and mark complete/partial/missing.

## 8. CharacterPanelSnapshot rule

`CharacterPanelSnapshot` is a read model, not authoritative storage and not a mechanics engine.

Authoritative Player State + Derived Values + Runtime Conditions + relevant ledger summaries -> PlayerSnapshotBuilder -> CharacterPanelSnapshot.

It must be rebuildable and versioned.

Do not directly mutate player progression by writing arbitrary snapshot values.

## 9. Player mechanics rule

Authoritative player changes follow:

PlayerCommand -> PlayerDomainEngine -> Rule Pipeline + WorldRuleProvider -> InvariantValidator -> PlayerChangeSet -> TurnTransaction -> State/Events/Ledgers -> COMMIT.

AI may propose actions/results but does not directly assign stat growth, mastery, money, items, assets or finished techniques.

## 10. Snapshot and backup rule

Automatic chapter snapshots are bounded cache/restore points.

- Keep at most 6 newest automatic chapter snapshots per campaign.
- Automatic retention must not delete manual backups/exports.
- `pre_restore_*` safety backups are not part of automatic snapshot retention.
- Snapshot logic must use the active campaign; it must not be hard-coded to `Naruto_Default.campaign`.
- Immutable event history is not discarded just because old automatic snapshots are pruned.

## 11. Safe change rule

For every change:

1. Identify the smallest relevant surface.
2. Preserve unrelated working behavior.
3. Avoid broad rewrites unless architecture requires them.
4. Make schema changes migration-safe.
5. Never destroy campaign data during content/app updates.
6. Never reset GitHub authorization, repository state, signing configuration or user data unless explicitly requested.
7. Do not revert `master` to an old milestone merely because a previous conversation ended there.
8. Do not touch frozen frontend styling unless required for compatibility or explicitly requested.

## 12. Build and verification loop

After a code change:

1. Record exactly what changed.
2. Let the configured GitHub Actions pipeline run.
3. Check build status.
4. If failed, inspect the failing job/log and fix the actual cause before continuing.
5. If successful, treat that commit as the new technical baseline.
6. For data/GM/player-domain changes, add or update integrity tests where practical.
7. Do not stack many unrelated changes on top of a known failing build.

## 13. Version/update discipline

- Preserve compatibility with installed campaigns whenever possible.
- Content Update must not overwrite campaign state.
- World Pack, campaign, player, event, memory and snapshot schemas must be migratable.
- App binary updates and content updates are separate concerns.
- A documentation-only change is not proof of functional completion.

## 14. Core invariants

Never violate these while developing RPG OS:

- FACT, BELIEF and NARRATIVE are distinct.
- Narrative does not automatically become fact.
- Campaign divergence overrides attempts to force canon back into place.
- Durable objects use stable UIDs.
- Important facts and changes carry provenance.
- History is append-only; current working state may be mutable.
- A turn ultimately commits authoritative narration/state together or rolls back.
- NPC knowledge must have a plausible acquisition path.
- Player mechanics are resolved by local/domain rules, not ad hoc narration.
- Durable progression does not regress without an explicit supported cause.
- Counterfactual/unsupported history must be rejected or repaired.
- Chronicle derives from committed structured reality.
- Inventory, finance and ownership must remain auditable.
- Created techniques require a valid DevelopmentProject path rather than arbitrary AI granting.

## 15. Long-campaign constraints

Design for campaigns measured in years and millions of words/events.

Therefore:

- never require rereading the whole narrative to know current truth;
- use current working state + immutable event history + bounded retrieval;
- use snapshots for load speed, not as the only history;
- keep context sent to AI bounded;
- use simulation LOD;
- consolidate memory without recursive summary-of-summary loss;
- preserve causal links;
- maintain explicit NPC knowledge;
- preserve progression/financial/ownership ledgers;
- use snapshot profiles so AI receives only relevant player state.

## 16. How to choose the next task

At each milestone:

1. Is the build broken? Fix it before feature work.
2. Is there a data-loss/integrity risk? Fix it first.
3. Continue the current GM/Player Domain backend track.
4. Choose the earliest dependency in the canonical architecture whose missing portion blocks later work.
5. Audit before implementing.
6. Do not expand World Packs aggressively until the required core foundation is reliable.
7. Do not return to frontend work unless explicitly re-enabled by the user.

## 17. Required end-of-session handoff

Before ending a meaningful development session, leave a concise handoff containing:

- current `master` commit/baseline;
- last successful build or current failure;
- what was completed;
- what remains unfinished;
- exact next recommended backend/GM/Player Domain task;
- any user decision required for that task.

If direction materially changes, update the canonical repo documents rather than relying only on chat memory.

## 18. Immediate project sequence from this point

Unless the user changes priority:

1. Verify the current build after recent documentation/snapshot changes.
2. Keep the accepted frontend frozen.
3. Audit existing implementation against canonical stages 1–15, especially current player tables, CharacterPanelReader, progression data, inventory/economy tables and existing rule logic.
4. Produce a gap map: COMPLETE / PARTIAL / MISSING for each stage.
5. Fix remaining Unified Repository / active-campaign hard-coding first if it blocks safe domain work.
6. Implement Player State Contract and Player Domain foundations by missing dependency delta.
7. Migrate `CharacterPanelSnapshot` toward v2 only after the underlying authoritative models/rules are defined.
8. Continue Turn Transaction/Event Store/Snapshot and later GM stages according to dependencies.
9. Expand Naruto/Bleach only as required to exercise WorldRuleProvider and engine tests.

This protocol remains active until explicitly superseded by the user or by a newer canonical protocol committed to the repository.
