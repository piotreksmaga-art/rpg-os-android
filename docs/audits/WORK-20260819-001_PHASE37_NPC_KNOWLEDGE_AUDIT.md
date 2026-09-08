# WORK-20260819-001 — Phase 37 NPC Knowledge + Acquisition Provenance — AUDIT FIRST

Status: **AUDIT COMPLETE — PARTIAL / IMPLEMENTATION REQUIRED**

Audit base: `22011f28f77eca63f8a5ab10a2cf0e0b4cb08b07`

Accepted dependency baseline remains Phase 35–36 runtime `7cb61d3cdc42e0c20f2688181d054e55eacbfd8f`.

## Canonical requirement

`docs/Architektura projektu.md` requires every important NPC to have actor-scoped knowledge including known/suspected/false beliefs/rumours/secrets/observations/inferences/organization knowledge, and requires every acquired item of knowledge to have a legal acquisition path such as observation, communication, research, inference, organization, espionage or a World Pack mechanic.

The global mutation invariant still applies: authoritative NPC knowledge may become committed campaign reality only through the canonical proposal -> validation -> TurnTransaction -> Event/evidence -> COMMIT path.

## Existing foundations — KEEP / EVOLVE

The current runtime is not missing NPC knowledge entirely.

### Existing persistent family

`BundledCampaignPersistentFamilies.NPC_KNOWLEDGE_STATE` already groups:

- `information_facts`
- `information_knowledge`
- `information_sharing_events`
- `npc_beliefs`
- `npc_memories_v2`
- `npc_observation_scope`
- `rumor_exposure`
- `secrets`
- intelligence / surveillance / deception support tables

`RuntimeTruthLayerRegistry` already classifies this family as `AUTHORITATIVE`.

### Existing readers / presentation

`ContextBuilder` already reads `information_knowledge` and exposes `holder_uid`, `info_uid`, `confidence`, `accuracy`, `acquisition_method`, `learned_chapter` plus joined fact presentation fields. It also reads NPC memories.

`NpcWorldDashboardReader` already renders legacy NPC beliefs and memories.

The backend GM prompt already states that NPC knowledge must not be invented when absent from NPC knowledge, memories or an NPC-owned BELIEF.

### Existing package schema

The exact accepted validation APK from workflow run `32241299329`, artifact `9361064715`, contains the bundled campaign schema with legacy Phase37 scaffolding. Relevant legacy columns include:

`information_knowledge(holder_uid, info_uid, confidence, accuracy, learned_chapter, acquisition_method, source_uid, can_share)`

`npc_beliefs(belief_uid, entity_uid, subject_uid, belief_type, content_summary, confidence, emotional_weight, source_info_uid, updated_chapter)`

`information_sharing_events(share_uid, info_uid, sender_uid, receiver_uid, chapter, method, distortion, intercepted, interceptor_uid, notes)`

`npc_observation_scope(entity_uid, fact_uid, observable_from_day, observable_to_day, source_type, source_uid)`

The default campaign package contains zero rows in these knowledge tables. The bundled core DB contains an `information_facts` definition, while campaign bootstrap `AutoRepairEngine` can create a smaller compatibility `information_facts` table in the campaign DB. This is legacy scaffolding, not a first-class Phase37 authority contract.

## Blocking gaps found

### G37-1 — no typed NPC Knowledge authority/store

There is no typed `NpcKnowledgeRecord` / `NpcKnowledgeStore` contract that owns actor-scoped committed knowledge identity, acquisition kind, confidence/accuracy, provenance and source evidence.

Current runtime reads legacy knowledge with raw SQL.

**Classification: MISSING inside otherwise PARTIAL phase.**

### G37-2 — no canonical acquisition validator

`acquisition_method` is currently a free-form legacy string. There is no validator requiring an allowed acquisition category and evidence appropriate to that category.

The architecture explicitly requires a legal acquisition path.

**Classification: MISSING.**

### G37-3 — authoritative knowledge is not reachable through canonical TurnTransaction

`NPC_KNOWLEDGE_STATE` is authoritative, but `RuntimePersistentWriterRegistry.canonicalTurnTargetFamilies` does not include it.

`PlayerChangeSet` has no NPC knowledge payload/change kind, and `CanonicalPlayerChangeApplier` rejects any unsupported change payload.

Therefore normal gameplay currently has no legal authoritative mutation path for Phase37.

**Classification: MISSING / BLOCKING.**

### G37-4 — replay coverage would fail closed

`CampaignReplayAuthorityMatrix.replayableFamilyUids` does not include `NPC_KNOWLEDGE_STATE`; authoritative families not in that set are classified `NON_REPLAYABLE_FAIL_CLOSED`.

If NPC knowledge were added to TurnTransaction without replay codec/applier support, Phase33 recovery guarantees would be broken.

**Classification: MISSING / BLOCKING.**

### G37-5 — perspective isolation is presentation convention, not a typed query invariant

The backend prompt tells the AI not to invent knowledge, but correctness must not depend on prompt obedience. `ContextBuilder` performs direct broad queries and manually groups rows by holder.

There is no repository API that guarantees `knowledgeForActor(actorUid)` returns only that actor's committed knowledge.

**Classification: PARTIAL.**

### G37-6 — legacy fact ownership is split / underspecified

The core package has a fuller `information_facts` definition while campaign AutoRepair can create a minimal campaign-local compatibility table. Existing `ContextBuilder` joins `information_knowledge` to campaign-local `information_facts`.

Phase37 must not silently reinterpret or delete this legacy data. A new first-class authority must coexist additively, and legacy rows without canonical provenance must remain explicitly legacy/unknown rather than receiving invented history.

**Classification: PARTIAL / MIGRATION REQUIRED.**

## Phase37 verdict

**PHASE 37 = PARTIAL.**

It is not BLOCKED: the repository already has useful schema/table-family foundations, canonical transaction infrastructure, Event Store, replay material, snapshot recovery and schema migration safety. The correct action is to extend those systems rather than create a parallel knowledge database.

## Minimal implementation boundary

The Phase37 implementation should be the smallest delta that closes the blocking invariants:

1. Add a versioned typed NPC knowledge record/store using a dedicated campaign-scoped table with stable record UID and actor UID.
2. Define a closed acquisition-kind enum covering at least observation, communication, research, inference, organization, espionage, World Pack mechanic, verified import and legacy unknown.
3. Require typed acquisition provenance: source/evidence UIDs plus committed transaction/turn/Event identity for normal gameplay; do not fabricate missing legacy provenance.
4. Add `NpcKnowledgeChange` to the existing typed change-set contract and codec.
5. Apply it only inside `CanonicalPlayerChangeApplier` / TurnTransaction and add `NPC_KNOWLEDGE_STATE` to the canonical writer contract.
6. Include Phase37 knowledge in replayable authority and prove snapshot -> replay reconstructs the same knowledge records.
7. Add actor-scoped typed repository/context reads and stop using raw legacy `information_knowledge` as the canonical GM knowledge source.
8. Add an additive compatibility migration/import path for old `information_knowledge` rows. Legacy rows with insufficient evidence remain `LEGACY_UNKNOWN` provenance and must not be retroactively assigned a fake Event/turn.
9. Keep old support tables until compatibility tests prove they can remain legacy-only; do not destructively migrate user campaigns in Phase37.

## Required adversarial tests before PR acceptance

At minimum:

- actor A's knowledge is never visible in actor B's typed query/context;
- normal gameplay knowledge cannot be recorded outside TurnTransaction;
- observation acquisition requires observation evidence;
- communication acquisition requires source actor/evidence;
- inference is distinguishable from observed fact and preserves confidence;
- failed turn rolls back knowledge and Event/evidence atomically;
- same-command retry produces no duplicate knowledge;
- committed record is bound to transaction/turn/Event identity;
- forged raw/admin SQL cannot impersonate committed Phase37 provenance;
- legacy import preserves unknown provenance instead of inventing history;
- old `information_knowledge` rows remain readable/migratable without loss;
- snapshot + replay reproduces typed knowledge exactly;
- cross-campaign knowledge UID/actor leakage fails closed;
- ContextBuilder consumes the typed actor-scoped read path;
- full JVM suite remains green.

## Next action

Implement the minimal Phase37 boundary above on a feature branch. Do not mark Phase37 COMPLETE until targeted tests, compatibility checks, full JVM, PR review/merge, exact-SHA CI, immutable artifact freshness and coordinator acceptance all pass.
