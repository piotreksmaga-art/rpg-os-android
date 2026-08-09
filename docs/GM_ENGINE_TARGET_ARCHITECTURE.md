# RPG OS — GM Engine Target Architecture

Status: canonical target architecture for future GM Engine development.

## Core principle

AI is not campaign memory, not the database, and not the source of truth. AI is the Game Master operating on a controlled RPG OS world model.

## Six layers

1. Source of Truth — world canon, schemas, stable UIDs, provenance.
2. Campaign State — player, NPCs, relationships, missions, world, knowledge, locations, items.
3. Campaign Intelligence — immutable events, memories, beliefs, causal graph, narrative promises, snapshots.
4. Simulation / Rule Engine — combat, progression, time, travel, NPC decisions, economy, projects, world simulation.
5. Context & Director — retrieval, temporal/knowledge filtering, memory, pacing, context budget.
6. AI Game Master — narration, dialogue, high-level NPC decisions and style.

## Unified repository

All GM-facing data access should converge behind a single logical CampaignRepository composed of canon, state, event, memory, knowledge, timeline and snapshot repositories. GM code must not depend on arbitrary SQLite tables directly.

## Truth model

Every durable piece of information belongs to one of three categories:
- FACT — objective campaign reality.
- BELIEF — what a specific entity believes.
- NARRATIVE — what has been described to the player.

Narrative never becomes fact automatically.

Current campaign reality is WORLD CANON + explicit CAMPAIGN DIVERGENCES. Once the campaign diverges, the engine must not force history back toward canon.

## Identity and provenance

Durable entities use stable UIDs. Human-readable names are labels, not engine identity.

Important facts carry provenance such as sourceType, sourceId, createdTurn, confidence, canonStatus and verification state.

## Immutable history + mutable working state

Significant history is append-only in an event store. Current location, HP, relationships, missions, inventory and similar values are maintained as mutable working state for fast play.

Events capture meaningful world changes such as creation/death/movement, relationship changes, learned skills, stat changes, inventory changes, missions, discoveries, secrets, time skips, political/world events and project milestones.

Events may form a causal graph so the engine can explain why later situations exist.

## Atomic turns

Each player turn is one atomic transaction:
input -> retrieval -> simulation -> AI proposal -> validation -> events -> state -> memory -> chronicle -> commit.

Failure before commit rolls back the turn. The player must never see a narration that was not committed together with its authoritative state changes.

## Snapshots and retention

Snapshots accelerate loading by combining the latest snapshot with events after it. Automatic snapshots are a cache/restore mechanism, not the immutable history itself.

Android retention policy: keep at most the 6 newest automatic chapter snapshots. Manual backups/exports and pre-restore safety backups are not deleted by automatic snapshot retention.

## Memory

Only three primary memory layers:
- Working memory — current scene and recent turns.
- Episodic memory — important events.
- Semantic campaign memory — durable conclusions and relationships.

Memory consolidation deduplicates and derives semantic knowledge without deleting original immutable event history and without recursive summary-of-summary degradation.

## NPC knowledge and agency

NPC knowledge is explicit: known facts, suspected facts, false beliefs, rumours, secrets, observed events and inferences. Knowledge may enter only through observation, communication, research, inference or organization channels.

Important NPCs have persistent identity, personality, goals, fears, values, loyalties, relationships, knowledge, resources, abilities, location, current task and long-term plan.

Low-impact NPC decisions should prefer deterministic/local logic. Strong AI is reserved for decisions that benefit from it.

## Temporal and scheduled world

Time-sensitive state supports validity ranges. Retrieval asks what was true at the relevant historical time.

Scheduled future events and time skips are processed through progression, NPC changes, aging/family, projects, war/politics, economy, world simulation, memory consolidation and snapshotting.

## Simulation LOD

LOD 0 — current scene, full detail.
LOD 1 — nearby region, detailed.
LOD 2 — important organizations, strategic.
LOD 3 — rest of world, major events only.

## Mechanics outside AI

Rules are resolved by explicit engines such as Combat, Progression, Travel, Relationship, Economy, Research, Crafting, Project, Time and World Simulation engines. AI narrates or proposes; it does not become the calculator of record.

Progression uses a ledger of training duration, intensity, mentor, environment, method, fatigue, injury and talent. Durable mastery cannot regress without an explicit supported cause.

## Intent, planning and retrieval

Player input is normalized into a structured intent. A Turn Planner chooses repositories, mechanics, NPCs, canon/history and retrieval strategy required for the turn.

Retrieval is iterative and combines structured SQL/graph access, temporal filtering and semantic search. A Context Budget Manager selects only the most relevant records for AI.

## Structured GM output

The GM contract is structured rather than narration-only. Target fields include narrative, proposed events, state changes, knowledge changes, relationship changes, memory writes, chronicle entries, thread changes and time advance.

## Validation and repair

Before commit, validation checks canon divergence rules, timeline consistency, dead/unknown entities, techniques, NPC knowledge, stats, inventory, location, causality and unsupported history.

Counterfactual Guard rejects invented prior history. Small output errors should be repaired locally instead of regenerating an otherwise valid full turn.

Conflict priority:
1. Campaign current state
2. Immutable event history
3. Explicit player state
4. Campaign divergence
5. Canon worldpack
6. Persistent memory
7. Recent narrative
8. AI inference

## Director and narrative continuity

Director Engine manages pacing, variety, tension, open threads, world reactions, character arcs and stagnation without altering world physics.

Narrative Promise Ledger persists mysteries, rivalries, threats, promises, prophecies, projects and relationship tensions. Anti-repetition tracks recent scene/conflict/resolution patterns. Narrative style is explicit campaign configuration rather than inferred memory.

## Chronicle, saves and branching

Chronicle is generated from structured committed events/state, not only prose.

A save references snapshotId, eventId and turnId, enabling alternate branches without copying an entire giant campaign database.

## Versioning, backup and observability

Schema/version identifiers cover engine, worldpack, campaign, memory and event formats. Updates require migrations.

Campaign data is more important than application binaries: autosave, snapshots, manual export and optional cloud backup must be isolated from content updates.

Developer tooling should support turn diagnostics and replay with input, retrieval, context, AI output, simulation, validation, state changes and events.

## Evaluation targets

Stress campaigns should cover 10k/100k turns, 1M events and multi-million-word histories. Integrity questions include who did what, who knows what, why an event occurred, where an item is and when a relationship changed.

Primary metrics: factual recall, false-memory rate, contradiction rate, temporal accuracy, knowledge leakage and retrieval latency.

Android performance targets should keep local retrieval and context construction bounded and avoid feeding unbounded world state to AI. Expensive models are used only where local code or smaller models are insufficient.

## Target turn pipeline

PLAYER INPUT
-> Input Normalizer
-> Intent Parser
-> Turn Planner
-> Initial Retrieval
-> Missing-context Check
-> Follow-up Retrieval
-> Knowledge Filter
-> Temporal Filter
-> Rule/Simulation Precheck
-> Director Context
-> Context Budget
-> Context Bundle
-> AI Game Master
-> Structured Proposal
-> Mechanics Resolution
-> Consistency Validator
-> Counterfactual Guard
-> Repair Pass when needed
-> Turn Transaction (events/state/knowledge/relations/progression/threads/chronicle/memory)
-> COMMIT
-> Player sees committed narrative
-> Consolidation
-> Snapshot when required

## Implementation order

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

Only after these foundations are reliable should Naruto, Bleach and future world databases be expanded aggressively.
