# RPG OS — Unified GM Engine & Player Domain Architecture

Status: CANONICAL TARGET ARCHITECTURE

This document is the single architectural source of truth for GM Engine, player mechanics, campaign state, persistence, simulation, memory, validation and AI orchestration. Older plans are historical sketches and are superseded where they differ from this document.

## 1. Core principle

AI is not campaign memory, not the database, not the mechanics calculator and not the source of truth.

AI is the Game Master operating on a controlled RPG OS world model.

All authoritative changes must be produced or validated by deterministic/local systems and committed through controlled transactions.

## 2. Six system layers

1. SOURCE OF TRUTH
   - world canon
   - schemas
   - stable UIDs
   - provenance
   - rule definitions
   - World Pack definitions

2. CAMPAIGN STATE
   - player state
   - NPC state
   - relationships
   - missions
   - inventory
   - economy
   - assets
   - locations
   - organizations
   - current world state

3. CAMPAIGN INTELLIGENCE
   - immutable events
   - ledgers
   - memories
   - beliefs
   - causal graph
   - narrative promises
   - snapshots
   - chronology

4. SIMULATION / RULE ENGINE
   - Player Domain Engine
   - combat
   - progression
   - time
   - travel
   - NPC decisions
   - economy
   - projects
   - research
   - crafting
   - world simulation

5. CONTEXT & DIRECTOR
   - retrieval
   - temporal filtering
   - knowledge filtering
   - memory
   - context budgeting
   - pacing
   - anti-repetition
   - narrative direction

6. AI GAME MASTER
   - narration
   - dialogue
   - high-level NPC decisions
   - scene composition
   - style
   - structured proposals

## 3. Unified Repository

All GM-facing data access converges behind one logical CampaignRepository.

CampaignRepository
- CanonRepository
- PlayerRepository
- StateRepository
- EventRepository
- MemoryRepository
- KnowledgeRepository
- TimelineRepository
- EconomyRepository
- InventoryRepository
- AssetRepository
- ProjectRepository
- SnapshotRepository

GM, AI adapter and higher-level engines must not directly depend on arbitrary SQLite tables.

The repository layer may physically use WORLD.DB, CAMPAIGN.DB, EVENTS.DB, MEMORY.DB, vector indexes and snapshot files, but the rest of the application sees one coherent domain interface.

## 4. Truth model

Every durable information item belongs to one of three categories:

FACT
Objective campaign reality.

BELIEF
What a specific actor believes to be true.

NARRATIVE
What has been described to the player.

Narrative never becomes fact automatically.

Current campaign reality is:
WORLD CANON + EXPLICIT CAMPAIGN DIVERGENCES + COMMITTED CAMPAIGN STATE.

Once the campaign diverges, the engine must not force history back toward canon.

## 5. Stable identity and provenance

Every durable entity uses a stable UID.

Examples:
CHAR-THIRD-RAIKAGE
NPC-AIKO-000013
TECH-HELL-STAB
SKILL-KENJUTSU
LOC-KONOHA-GATE
EVENT-000001284
MISSION-000034
ITEM-000918
ASSET-000042
PROJECT-000912

Human-readable names are labels, not identity.

Important facts and changes carry provenance:
- sourceType
- sourceId
- createdTurn
- sourceEventUid
- confidence
- canonStatus
- verified
- engineVersion

## 6. Immutable history + mutable working state

Significant history is append-only.

Examples:
SKILL_LEARNED
TECHNIQUE_CREATED
STAT_CHANGED
ITEM_GAINED
PAYMENT_RECEIVED
PROPERTY_PURCHASED
NPC_KILLED
MISSION_COMPLETED
TIME_SKIP

Current state is mutable and optimized for fast play:
- current HP
- current location
- current money balance cache
- current relationships
- current mission status
- current inventory state
- active conditions

History answers: how did we get here?
Working state answers: what is true now?

## 7. Player Domain — role

Player Domain is a first-class subsystem inside the Simulation / Rule Engine.

Its job is to resolve all authoritative player-state changes.

CharacterPanelSnapshot is NOT the source of truth and does not calculate mechanics.

Authoritative Player State
+ Derived Values
+ Runtime Conditions
+ Relevant Ledger Summaries
= CharacterPanelSnapshot

The snapshot must be disposable and rebuildable from authoritative state.

## 8. Player Domain architecture

PLAYER / WORLD ACTION
-> PlayerCommand
-> PlayerDomainEngine
-> Rule Pipeline
-> WorldRuleProvider
-> InvariantValidator
-> PlayerChangeSet
-> TurnTransaction
-> State + Events + Ledgers + Provenance
-> COMMIT
-> PlayerSnapshotBuilder
-> CharacterPanelSnapshot

### PlayerDomainEngine
Single entry point for player mechanics.

It coordinates domain rules but should not become a monolithic class containing every formula.

### Rule Pipeline
Composable rule modules:
- progression
- stats/resources
- skills
- techniques
- projects
- inventory/equipment
- economy/assets
- health/conditions
- relationships/reputation where relevant
- World Pack-specific rules

### WorldRuleProvider
Core remains universe-agnostic.

NarutoRulePack can define:
- chakra rules
- elemental affinity
- kekkei genkai
- ninja ranks
- technique requirements

BleachRulePack can define:
- reiryoku
- reiatsu
- Hollow evolution
- Zanpakuto/racial abilities
- spiritual technique requirements

World Packs provide definitions and rules, not parallel player engines.

## 9. Player commands

No external system should directly mutate authoritative player values.

Use structured commands such as:
- TrainCommand
- LearnSkillCommand
- PracticeTechniqueCommand
- CreateTechniqueCommand
- ModifyTechniqueCommand
- UseTechniqueCommand
- PurchaseCommand
- SellCommand
- GainRewardCommand
- EquipItemCommand
- UnequipItemCommand
- TransferItemCommand
- ApplyInjuryCommand
- HealCommand
- StartProjectCommand
- AdvanceTimeCommand
- GainAssetCommand
- LoseAssetCommand

Commands describe intent and cause, not arbitrary target values.

## 10. PlayerChangeSet

PlayerDomainEngine returns a proposed PlayerChangeSet.

PlayerChangeSet may contain:
- statChanges
- resourceChanges
- skillChanges
- techniqueChanges
- talent/potential state changes when explicitly allowed
- inventoryChanges
- equipmentChanges
- moneyTransactions
- assetChanges
- conditions
- projectChanges
- reputation/relationship changes
- generatedEvents
- ledgerEntries
- provenance

Nothing becomes authoritative until TurnTransaction commits it.

## 11. Player State model

Player state is separated into three levels.

### Persistent State
- identity
- base stats
- learned skills
- learned techniques
- innate abilities
- talent profile
- potential profile
- inventory ownership
- assets
- money accounts
- long-term relationships
- permanent conditions

### Derived State
Calculated deterministically from persistent state and rule definitions:
- effective stats
- max resources
- regeneration
- combat-derived values
- carry capacity
- net worth estimate
- effective mastery modifiers

### Runtime State
Short-lived current state:
- current HP
- current stamina
- current chakra/reiryoku/mana
- fatigue
- wounds
- buffs/debuffs
- cooldowns if supported
- temporary seals/conditions

## 12. Dynamic stats

Core must not hard-code every universe statistic.

StatDefinition:
- statUid
- key
- category
- unit
- minValue
- maxValue if applicable
- growthRuleUid
- derivationRuleUid if derived
- worldPackUid

PlayerStat:
- characterUid
- statUid
- baseValue
- version

Examples may include generic physical stats plus World Pack additions such as chakra_control, yin, yang, reiatsu_density or reishi_control.

## 13. Base, permanent, equipment and temporary modifiers

Effective values are resolved rather than destructively overwriting base progression.

Example:
Base Strength 72
Permanent bonus +8
Equipment +4
Injury -3
Temporary buff +6
Effective Strength 87

DerivedValueResolver calculates the effective value.

This prevents temporary injuries, equipment or buffs from corrupting long-term progression history.

## 14. Talent and potential

Talent and potential are distinct systems.

### Talent
Represents ease, efficiency and aptitude for learning/performing a domain.

TalentProfile may include:
- generalLearning
- physical
- combat
- perception
- energyControl
- creativity
- technicalLearning
- social if used
- worldPackDomains[]

World Pack examples:
- genjutsu
- raiton
- medical_ninjutsu
- zanjutsu
- sonido
- reishi_control

Talent modifies difficulty, effective practice and learning rate. Talent does not grant progress without cause.

### Potential
Represents long-term growth properties.

PotentialProfile may include:
- growthRatePotential
- maximumPotential
- adaptationPotential
- innovationPotential
- evolutionPotential

Potential is not automatically a visible hard ceiling. Rules may use soft caps, diminishing returns, breakthroughs, evolutions or domain-specific limits.

## 15. Progression system

Every durable gain needs a cause.

ProgressionEntry records:
- progressionUid
- characterUid
- sourceType
- sourceEventUid
- duration
- intensity
- difficulty
- mentorUid
- environmentUid
- methodUid
- talentMultiplier
- potentialModifier
- fatigue
- injuryImpact
- novelty
- diminishingReturns
- effectiveTraining
- result
- createdTurn

Sources may include:
- TRAINING
- COMBAT
- RESEARCH
- PRACTICE
- ENVIRONMENT
- PASSIVE_ADAPTATION
- TIME_SKIP
- EVOLUTION
- MENTORSHIP
- PROJECT

ProgressionEngine calculates results. AI does not directly assign gains.

## 16. Diminishing returns and scaling

Long campaigns require controlled scaling.

Progress depends on:
- current level
- training quality
- novelty
- difficulty
- adaptation
- talent
- potential
- environment
- fatigue/injury
- duration

Repeating trivial training at extreme mastery should produce small gains unless new difficulty, methods, conditions or breakthroughs justify more.

## 17. No-Retrogression and invariants

InvariantValidator protects the player domain.

Examples:
- no unexplained permanent stat loss
- no lost learned skill without explicit supported cause
- no mastery regression without explicit cause
- no negative inventory quantities
- no duplicated unique item ownership
- no impossible equipment configuration
- no invalid ownership
- no impossible technique usage
- no dead character training unless world rules explicitly support it
- no negative currency unless debt/credit rules allow it
- no change unsupported by an event, command or rule result

Allowed regression requires explicit mechanics such as injury, seal, curse, disease, aging, memory loss, temporary debuff or other supported cause.

## 18. Skills and techniques

Skill and Technique are different concepts.

### Skill
General competence/domain.
Examples:
- Kenjutsu
- Genjutsu
- Medical Ninjutsu
- Tracking
- Smithing

SkillState:
- skillUid
- mastery
- experience/progress
- learnedAt
- source
- mentorUid
- permanent
- prerequisites

### Technique
Specific executable method/ability.
Examples:
- Chidori
- Mystical Palm
- Hell Stab

TechniqueState:
- techniqueUid
- mastery
- energyCost overrides
- learnedAt
- source
- creatorUid
- variantOfUid
- limitations
- prerequisites
- creationProjectUid if player-created

A technique may require multiple skills and stats.

## 19. Creating and modifying techniques

Technique creation is modeled as a DevelopmentProject, not a free AI mutation.

DevelopmentProject types may include:
- TECHNIQUE_CREATION
- TECHNIQUE_MODIFICATION
- SKILL_DEVELOPMENT
- RESEARCH
- CRAFTING
- BODY_ADAPTATION
- ENERGY_CONTROL

DevelopmentProject:
- projectUid
- ownerUid
- type
- goal
- requirements
- dependencies
- difficulty
- risk
- resources
- requiredTime
- progress
- experiments
- milestones
- failures
- resultUid
- status

Pipeline:
idea
-> prerequisite validation
-> project creation
-> experiments/training
-> partial prototypes
-> failures/risks
-> milestones
-> stabilization
-> TECHNIQUE_CREATED event
-> stable technique UID
-> initial mastery derived from actual development process

AI may propose ideas or describe experiments, but cannot directly grant the finished ability.

## 20. Innate, racial and bloodline abilities

These are not ordinary skills.

Model supports:
- bloodline abilities
- racial abilities
- mutations
- awakenings
- staged evolution
- transformations
- inherited traits

State includes:
- abilityUid
- unlocked state
- stage
- mastery/control if applicable
- discoveredAt
- activation conditions
- evolution history
- restrictions

WorldRuleProvider controls domain-specific behavior.

## 21. Resources and health

Resource definitions are dynamic per world.

Examples:
- HP
- stamina
- chakra
- reiryoku
- mana
- fatigue

ResourceEngine handles:
- current/max
- regeneration
- depletion
- recovery
- wounds
- illness
- poison
- injuries
- long-term conditions

Temporary conditions modify effective state without rewriting unrelated permanent progression.

## 22. Inventory and equipment

Inventory item identity is UID-based.

Item state may include:
- itemUid
- definitionUid
- ownerUid
- quantity
- quality
- durability
- location/containerUid
- equippedSlot
- estimatedValue
- provenance/sourceEvent
- unique flag

InventoryEngine handles possession and quantity.
Equipment rules handle actual loadout.

Inventory != equipped loadout.

## 23. Ownership and assets

A shared ownership model is used for items and larger assets where practical.

OwnershipRecord:
- ownerUid
- assetUid
- ownershipType
- share
- validFrom
- validUntil
- sourceEventUid

Assets may include:
- land
- house
- laboratory
- workshop
- business
- vehicle
- organization shares
- rare artifacts
- productive property

This supports co-ownership and ownership history.

## 24. Economy and finance

Money is ledger-based.

FinancialTransaction:
- transactionUid
- fromUid
- toUid
- currencyUid
- amount
- category
- reason
- eventUid
- timestamp/turn

Sources of income may include:
- missions
- salary
- trade
- businesses
- investments
- loot
- rewards
- rent

Expenses may include:
- purchases
- maintenance
- healing
- travel
- training
- project materials
- wages
- taxes
- repairs

Balances may be cached for performance, but the ledger is the authoritative explanation of how the balance changed.

## 25. Wealth, debts and obligations

Player financial state may include:
- wallets/accounts by currency
- receivables
- debts
- recurring income
- recurring expenses
- owned assets
- liabilities
- netWorthEstimate

Personal wealth must remain distinguishable from organization-owned resources unless ownership rules explicitly merge them.

## 26. Relationships, reputation and organizations

Player state may reference:
- key personal relationships
- family
- allies
- enemies
- faction reputation
- fame/infamy
- fear/trust where mechanically relevant
- organization memberships
- ranks/positions
- obligations

Personal relationship state and public/faction reputation are separate concepts.

## 27. Goals, missions and projects

Player projections include active:
- goals
- missions
- training plans
- development projects
- research
- construction
- contracts
- obligations
- deadlines

Full history remains in event/project stores; snapshots expose active and relevant summaries.

## 28. CharacterPanelSnapshot v2

CharacterPanelSnapshot becomes the canonical read model for current player state.

It is NOT a monolithic flat class.

Suggested structure:

CharacterPanelSnapshot
- schemaVersion
- generatedAtTurn
- characterUid
- identity: IdentitySnapshot
- stats: StatsSnapshot
- resources: ResourcesSnapshot
- talents: TalentSnapshot
- potential: PotentialSnapshot
- skills: SkillSnapshot[]
- techniques: TechniqueSnapshot[]
- innateAbilities: InnateAbilitySnapshot[]
- progression: ProgressionSummary
- inventory: InventorySnapshot
- equipment: EquipmentSnapshot
- economy: EconomySnapshot
- assets: AssetSnapshot
- relationships: RelationshipSnapshot
- reputation: ReputationSnapshot
- organizations: OrganizationSnapshot
- goals: GoalSnapshot[]
- projects: ProjectSnapshot[]
- missions: MissionSnapshot[]
- conditions: ConditionSnapshot[]

The current older snapshot fields must be migrated compatibly rather than breaking the accepted application unnecessarily.

## 29. Snapshot profiles

Not every consumer needs the full player state.

PlayerSnapshotBuilder supports profiles such as:
- FULL
- COMBAT
- PROGRESSION
- ECONOMY
- SOCIAL
- GM_CONTEXT

ContextBuilder requests only the relevant profile/sections to control latency and token budget.

## 30. PlayerSnapshotBuilder

PlayerSnapshotBuilder is the only component responsible for assembling CharacterPanelSnapshot from repositories and derived resolvers.

It does not mutate data.

It may use:
- PlayerRepository
- InventoryRepository
- EconomyRepository
- AssetRepository
- ProjectRepository
- RelationshipRepository
- DerivedValueResolver
- WorldRuleProvider

## 31. Event Store

All meaningful changes produce durable events.

Core event categories include:
- NPC_CREATED
- NPC_KILLED
- NPC_MOVED
- RELATION_CHANGED
- STAT_CHANGED
- SKILL_LEARNED
- SKILL_PROGRESS
- TECHNIQUE_LEARNED
- TECHNIQUE_CREATED
- TECHNIQUE_MODIFIED
- ITEM_GAINED
- ITEM_LOST
- ITEM_TRANSFERRED
- MONEY_TRANSFERRED
- ASSET_ACQUIRED
- ASSET_LOST
- MISSION_STARTED
- MISSION_COMPLETED
- PROJECT_STARTED
- PROJECT_MILESTONE
- PROJECT_COMPLETED
- LOCATION_DISCOVERED
- SECRET_LEARNED
- TIME_SKIP
- WORLD_EVENT
- POLITICAL_CHANGE

Event fields include:
- eventUid
- turnUid
- timestamp
- actorUid
- targetUid
- causeEventUid
- oldState where useful
- newState where useful
- source
- provenance

## 32. Causal graph

Events may link causally:
A caused B
B enabled C
C triggered D

This allows the engine to answer why later situations exist without rereading the narrative.

## 33. Atomic Turn Transaction

Each player turn is atomic.

TURN START
- Input
- Intent parsing
- Retrieval
- Rule/simulation precheck
- AI proposal if needed
- Mechanics resolution
- Validation
- PlayerChangeSet/world changes
- Events
- Ledgers
- State changes
- NPC knowledge
- Memory
- Chronicle
- COMMIT

If any required stage fails before commit:
ROLLBACK TURN

The player must not see narration as committed reality unless its authoritative changes were committed consistently.

## 34. Working State

Current state is stored for speed.

Do not reconstruct the whole world from all events on every turn.

History is for audit, causality, temporal questions and recovery.
Working state is for current play.

## 35. Snapshot System

Campaign snapshots accelerate loading:
latest snapshot + events after snapshot.

Automatic snapshot retention on Android:
- keep at most 6 newest automatic chapter snapshots per campaign
- do not delete manual backups/exports
- do not delete pre-restore safety backups through automatic retention
- immutable event history is not removed when old automatic snapshots are pruned
- snapshot paths must use the active campaign, not a hard-coded Naruto campaign

## 36. Memory model

Only three primary memory levels:

Working Memory
Current scene and recent turns.

Episodic Memory
Important events.

Semantic Campaign Memory
Durable conclusions and learned campaign-level knowledge.

Memory consolidation:
- deduplicates
- detects conflicts
- updates importance
- derives semantic facts
- never deletes immutable event history
- avoids recursive summary-of-summary degradation

## 37. NPC knowledge

Each NPC has explicit knowledge state:
- knownFacts
- suspectedFacts
- falseBeliefs
- rumours
- secrets
- observedEvents
- inferences

Knowledge can enter only through:
- observation
- communication
- research
- inference
- organization channels

AI memory alone may never grant NPC knowledge.

## 38. NPC Brain and Decision Engine

Important NPC state may include:
- identity
- personality
- goals
- fears
- values
- loyalties
- relationships
- knowledge
- resources
- abilities
- location
- current task
- long-term plan

Low-impact decisions prefer deterministic/local logic.
Strong AI is reserved for decisions that benefit from it.

## 39. Temporal Engine

Time-sensitive state supports validity ranges:
- validFrom
- validUntil

Applicable to:
- location
- rank
- relationships
- membership
- injuries
- wars
- alliances
- ownership
- positions

Retrieval asks what was true at the relevant historical time.

## 40. Scheduler

Future events may be scheduled:
- mission deadlines
- project completion windows
- war escalation
- births
- NPC training
- recovery
- travel arrival
- recurring financial obligations

## 41. Time Skip Processor

A time skip is not date += N.

Pipeline:
advance time
-> scheduled events
-> player progression
-> passive progression
-> NPC progression
-> aging/family
-> injuries/recovery
-> projects
-> economy/recurring finance
-> wars/politics
-> world simulation
-> memory consolidation
-> snapshot when required

Player progression during time skips must use the same Player Domain rules and ledgers as normal turns.

## 42. Simulation Level of Detail

LOD 0 — current scene, full detail
LOD 1 — nearby region, detailed
LOD 2 — important organizations, strategic
LOD 3 — rest of world, major events only

This keeps large worlds computationally manageable.

## 43. Other mechanics outside AI

Explicit rule domains include:
- CombatEngine
- PlayerDomainEngine
- TravelEngine
- RelationshipEngine
- Economy rules within/alongside Player Domain
- Research/Project rules
- Crafting rules
- TimeEngine
- WorldSimulationEngine

AI narrates and proposes; it is not the calculator of record.

## 44. Intent Parser and Turn Planner

Player input is normalized into structured intent:
- actionType
- targets
- methods
- intent
- location
- timeScope
- entitiesMentioned
- requiredSystems

Turn Planner decides:
- repositories to query
- rules/mechanics to execute
- relevant NPCs
- canon/history requirements
- player snapshot profile
- retrieval strategy

Do not invoke all systems on every turn.

## 45. Iterative Retrieval

Retrieval is iterative:
initial retrieval
-> detect missing data
-> follow-up retrieval
-> context complete

Possible domain APIs:
- getCharacter()
- getPlayerSnapshot(profile)
- getTechnique()
- getNpcKnowledge()
- searchMemories()
- getLocation()
- getRelations()
- getTimeline()
- getCanonEvent()
- getProject()
- getFinancialHistory()

## 46. Context Budget Manager

Do not send all known state to AI.

Context budgeting selects only relevant records.

A combat turn may emphasize player combat state, opponents and local environment.
An economic negotiation may emphasize wealth, assets, reputation and relationships.

## 47. Context Bundle

Final context may contain:
- SYSTEM RULES
- CAMPAIGN STYLE
- CURRENT TIME
- CURRENT LOCATION
- PLAYER SNAPSHOT / selected profile
- VISIBLE WORLD
- NPC STATE
- NPC KNOWLEDGE
- ACTIVE THREADS
- RELEVANT HISTORY
- RELEVANT CANON
- SIMULATION RESULTS
- PLAYER ACTION
- OUTPUT CONTRACT

## 48. AI Adapter

Model provider is replaceable.

AiProvider may route:
- local code for deterministic rules
- small model for classification when useful
- medium model for selected analysis
- strong model for narration/high-value decisions

Architecture must not depend on one specific API/model.

## 49. Structured GM Output

AI output is structured, not narration-only.

Target contract may include:
- narrative
- choices
- proposedEvents[]
- proposedPlayerCommands[] or requested mechanic resolutions
- stateChanges[] where allowed as proposals
- knowledgeChanges[]
- relationshipChanges[]
- memoryWrites[]
- chronicleEntries[]
- newThreads[]
- resolvedThreads[]
- timeAdvance

Authoritative mechanics are resolved locally after/before proposal as appropriate.

## 50. Validator and Counterfactual Guard

Before commit validate:
- canon/divergence rules
- timeline
- dead/unknown entities
- NPC knowledge
- player stats
- skill/technique prerequisites
- inventory
- ownership
- finance
- location
- causality
- unsupported history
- Player Domain invariants

Counterfactual Guard rejects invented prior history.

## 51. Repair Pass

Small AI errors should be repaired locally or with a targeted repair call.

Do not regenerate an otherwise valid entire turn because of one localized inconsistency.

## 52. Conflict priority

When sources conflict:
1. Campaign current state
2. Immutable event history
3. Explicit player state / ledgers
4. Campaign divergence
5. Canon worldpack
6. Persistent memory
7. Recent narrative
8. AI inference

## 53. Director Engine

Director manages:
- pacing
- variety
- tension
- open threads
- world reactions
- character arcs
- stagnation

Director never overrides world physics or authoritative mechanics.

## 54. Narrative Promise Ledger

Track:
- mysteries
- rivalries
- unresolved threats
- promises
- prophecies
- unfinished projects
- relationship tensions

Fields may include:
- introducedAt
- importance
- status
- relatedEntities
- possibleResolutionWindow

## 55. Pacing and anti-repetition

Metrics may include:
- turnsSinceCombat
- turnsSinceProgress
- turnsSinceDiscovery
- turnsSinceMajorEvent
- turnsInLocation
- turnsOnProject
- turnsSinceWorldReaction

Anti-repetition tracks recent scene/conflict/opening/ending/resolution patterns.

## 56. Narrative style profile

Campaign style is explicit configuration:
- tone
- length
- choiceStyle
- difficulty
- brutality
- progressionSpeed
- canonStrictness
- researchFocus
- combatFrequency

Do not rely on AI remembering style from old prose.

## 57. Chronicle Engine

Chronicle is generated from committed structured events/state, not only narrative text.

Chronicle must reflect actual database reality.

## 58. Save / branching

A save references:
- snapshotUid
- eventUid
- turnUid

This enables alternate branches without copying an enormous history database for every save point.

## 59. Schema versioning

Track versions for:
- engine
- worldPack
- campaignSchema
- playerSchema
- memorySchema
- eventSchema
- snapshotSchema

Updates require explicit migrations.

CharacterPanelSnapshot includes schemaVersion and generatedAtTurn.

## 60. Campaign backups

Campaign data is more important than application binaries.

Support:
- autosave
- bounded automatic snapshots
- manual backups
- manual export
- optional cloud backup later

Content Update must never destroy existing campaign state.

## 61. Debug / observability

Developer diagnostics should expose per-turn data such as:
- Intent
- Retrieved records
- Player snapshot profile
- Context size
- Simulation results
- PlayerChangeSet
- Validation
- Events
- Ledger entries
- Latency

## 62. Replay Debugger

A historical turn should be inspectable through:
- input
- retrieval
- context
- AI raw structured output
- simulation
- player commands/change set
- validator
- state changes
- events
- ledgers

## 63. Evaluation Suite

Automated campaigns should test:
- 10k turns
- 100k turns
- 1M events
- multi-million-word histories

Integrity questions include:
- Who killed X?
- Who knows secret Y?
- Why did war Z start?
- Where is item A?
- When did relationship B change?
- Why did stat C increase?
- How was technique D learned/created?
- Why does the player have current money balance E?
- Who owns asset F?

Metrics:
- fact recall
- false-memory rate
- contradiction rate
- temporal accuracy
- NPC knowledge leakage
- progression integrity
- financial integrity
- ownership integrity
- retrieval latency

## 64. Performance budget

Design for Android and huge campaigns.

Targets should keep:
- local retrieval bounded
- context construction bounded
- memory candidates bounded
- final AI context bounded
- active NPC set bounded
- canon record set bounded

Player snapshots should support selective profiles rather than always constructing/serializing everything.

## 65. AI cost budget

Preferred execution hierarchy:
local deterministic code
-> small AI only if useful
-> local simulation/validation
-> strong GM AI only where high value
-> local commit/validation

## 66. Final target turn pipeline

PLAYER INPUT
-> INPUT NORMALIZER
-> INTENT PARSER
-> TURN PLANNER
-> INITIAL RETRIEVAL
-> MISSING-CONTEXT CHECK
-> FOLLOW-UP RETRIEVAL
-> PLAYER SNAPSHOT PROFILE BUILD
-> KNOWLEDGE FILTER
-> TEMPORAL FILTER
-> RULE / SIMULATION PRECHECK
-> DIRECTOR CONTEXT
-> CONTEXT BUDGET
-> CONTEXT BUNDLE
-> AI GAME MASTER
-> STRUCTURED PROPOSAL
-> PLAYER COMMANDS / WORLD COMMANDS
-> MECHANICS RESOLUTION
-> PlayerDomainEngine and other rule engines
-> PlayerChangeSet / WorldChangeSet
-> CONSISTENCY VALIDATOR
-> COUNTERFACTUAL GUARD
-> REPAIR PASS IF NEEDED
-> TURN TRANSACTION
   - events
   - state
   - player ledgers
   - NPC knowledge
   - relations
   - progression
   - inventory/finance/assets
   - narrative threads
   - chronicle
   - memory
-> COMMIT
-> PLAYER SNAPSHOT REFRESH
-> PLAYER SEES COMMITTED NARRATIVE
-> BACKGROUND CONSOLIDATION
-> SNAPSHOT WHEN REQUIRED

## 67. Canonical implementation order

This is a dependency order, not a blind checklist. Before each stage, audit existing code and implement only the missing delta.

FOUNDATION
1. Unified Repository + stable UIDs
2. Campaign Source of Truth + provenance + FACT/BELIEF/NARRATIVE

PLAYER DOMAIN FOUNDATION
3. Player State Contract (Persistent / Derived / Runtime)
4. Dynamic Stat & Resource definitions
5. Talent & Potential model
6. Skill / Technique / Innate Ability model
7. Inventory / Equipment / Ownership model
8. Economy / Financial Ledger / Assets model
9. DevelopmentProject model for technique creation, research and related development
10. Player Domain commands + PlayerChangeSet
11. PlayerDomainEngine + WorldRuleProvider
12. Progression rules + diminishing returns + passive/time-based progression
13. Player Invariant Validator / No-Retrogression
14. Player Ledgers and provenance
15. CharacterPanelSnapshot v2 + PlayerSnapshotBuilder + snapshot profiles

TRANSACTIONAL CAMPAIGN CORE
16. Turn Transaction
17. Event Store
18. Causal Graph
19. Snapshot System
20. Canon Divergence

WORLD KNOWLEDGE / TIME / RETRIEVAL
21. NPC Knowledge
22. Temporal Engine + Scheduler
23. SQL/structured Retriever
24. Context Builder + iterative retrieval + context budgets

GM CONTRACT / VALIDATION / AI
25. Structured GM Output
26. Validator + Counterfactual Guard + Repair Pass
27. AI Adapter

MEMORY / ADVANCED SIMULATION
28. Memory Engine
29. Semantic Retrieval
30. Time Skip Processor
31. Combat/Travel/Relationship and remaining rule integration
32. NPC Brain / Decision Engine
33. World Simulation LOD
34. Director Engine
35. Narrative Promise + Pacing
36. Anti-Repetition

OBSERVABILITY / HARDENING
37. Debug / Replay
38. Stress & Integrity Tests
39. Performance optimization
40. AI cost optimization

Only after these foundations are reliable should Naruto, Bleach and future World Pack databases be expanded aggressively beyond what is needed for engine validation.

## 68. Current frontend policy

The current Android frontend style is accepted and frozen.

This architecture work concerns backend/core/domain behavior. Do not proactively redesign or visually extend the frontend unless the user explicitly re-enables frontend development.

CharacterPanelSnapshot v2 is a data/domain contract first. A future UI may render it, but UI design is not part of the current implementation phase.
