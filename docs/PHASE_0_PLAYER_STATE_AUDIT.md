# RPG OS — PHASE 0 PLAYER STATE AUDIT

Status: EVIDENCE / PHASE 0

## 1. Current player read models

Confirmed runtime player-facing/read-context components:
- `CharacterPanelSnapshot` + `CharacterPanelReader`
- `StatusSnapshot`
- `ContextBuilder.playerStatus`
- direct reads of `character_skills`, `character_techniques`, `character_finances`, `injuries_v2`, `organization_memberships_v3`, `entity_positions`.

The project therefore already contains meaningful player state data. The target architecture must migrate/integrate it rather than create a second independent player database.

## 2. CharacterPanelSnapshot v1

Current snapshot has only:
- identity: `List<StatLine>`
- stats: `List<StatLine>`
- resources: `List<StatLine>`
- skills: `List<SkillLine>`
- techniques: `List<TechniqueLine>`
- equipment: `List<String>`
- relationships: `List<String>`
- goals: `List<String>`

It has no:
- schemaVersion,
- generatedAtTurn/campaignTime,
- characterUid,
- Talent/Potential section,
- innate/racial/bloodline section,
- progression section,
- inventory vs equipment distinction,
- economy/assets/ownership sections,
- projects/missions/conditions sections,
- snapshot profiles,
- authoritative/derived/runtime classification.

Classification: Roadmap 24 CharacterPanelSnapshot v2 = PARTIAL through legacy v1 read model.

## 3. Player identity is not authoritative yet

### CharacterPanelReader
Reads:
`SELECT * FROM character_status_snapshot LIMIT 1`

No character UID filter is used.

### LocalGameStore.status()
Reads the first `entity_positions` row and uses default `StatusSnapshot.name = "Smagi"`.

### ContextBuilder.resolvePlayerUid()
Tries several heuristic queries in order:
1. entity with most character_skills rows,
2. entity with most character_techniques rows,
3. first character_finances row,
4. latest entity_positions row.

This is not a canonical Player State identity contract.

Risk:
- wrong entity can become 'player' in multi-character data,
- different readers can resolve different entities,
- player context can diverge from CharacterPanel,
- active campaign does not by itself identify player character.

Required target:
Campaign authoritative metadata must explicitly store/resolve the active player character UID through repository contract.

## 4. Stats and resources

Confirmed:
- `character_stats(stat_key,current_value)` is read by CharacterPanel.
- `character_status_snapshot` contributes identity/resource-like values.
- ContextBuilder reads injuries and finance summaries.

Not confirmed in runtime code:
- generic `StatDefinition` contract,
- generic `ResourceDefinition` contract,
- base/permanent/equipment/injury/temporary modifier separation,
- `DerivedValueResolver`,
- source/provenance of each stat change,
- no-retrogression enforcement.

Classification:
- Roadmap 3 Player State Contract: PARTIAL.
- Roadmap 4 Dynamic Stats & Resources: PARTIAL.
- Roadmap 5 DerivedValueResolver: MISSING in audited Kotlin runtime.

## 5. Skills

Confirmed:
- `character_skills` persistence exists and has at least `entity_uid`, `skill_uid`, `mastery`, `xp`, `updated_chapter`.
- `skill_definitions` is joined by CharacterPanel.
- ContextBuilder sends player skills to backend.
- backend system prompt treats player_skills as authoritative learned abilities.

Missing/unconfirmed:
- canonical LearnSkillCommand,
- requirements validation,
- progression source ledger,
- mastery no-retrogression invariant,
- mentor/source provenance,
- atomic linkage to Event Store.

Classification: Roadmap 7 Skill model = PARTIAL.

## 6. Techniques

Confirmed:
- `character_techniques` persistence exists with mastery/xp/learned chapter/usage/success/failure/equipped/notes fields consumed by ContextBuilder.
- world/campaign definitions exist in `technique_definitions` / `canon_technique_index` pathways.
- ContextBuilder sends learned techniques to backend as authoritative abilities.

Missing/unconfirmed:
- technique requirements engine,
- learning/creation command pathway,
- DevelopmentProject integration,
- provenance/ledger,
- invariant enforcement,
- distinction between canonical definition and player-created technique definition lifecycle.

Classification: Roadmap 8 Technique model = PARTIAL.

## 7. Inventory vs equipment defect

CharacterPanelReader currently executes:
`SELECT item_name FROM character_inventory ORDER BY item_name`

and places all returned names into the field named `equipment`.

Therefore current CharacterPanel does not actually distinguish:
- owned/carried inventory,
- equipped loadout.

Classification:
- Roadmap 10 Inventory model = PARTIAL (table/read exists).
- Roadmap 11 Equipment model = MISSING/UNCONFIRMED for general items.

`character_techniques.is_equipped` exists, but this is not a general equipment/loadout system.

## 8. Economy

Confirmed:
- `character_finances` stores/returns at least ryo, monthly_income, monthly_expenses, debt, property_value, investment_value, updated_chapter.
- `financial_transactions` is explicitly listed as a writable runtime table in SourceOfTruthRegistry.
- mission data includes `reward_ryo`.

Missing/unconfirmed:
- FinancialTransaction as sole authoritative money mutation path,
- balance derivation/reconciliation from ledger,
- conservation invariant,
- recurring income/expense scheduler integration,
- debts/receivables contracts,
- atomic link between purchase/reward event and finance ledger.

Classification:
- Roadmap 13 Financial Ledger/Economy = PARTIAL.
- Roadmap 14 Assets/debts/net-worth = PARTIAL only as summary fields; canonical ownership/asset ledger not confirmed.

## 9. Ownership/assets

Current audited readers expose `property_value` and `investment_value`, but no general ownership repository/record implementation has been confirmed in Kotlin runtime.

A location/container relation must not be treated as proof of ownership.

Classification: Roadmap 12 Ownership model = AUDIT REQUIRED / likely MISSING as canonical domain contract.

## 10. Talent and Potential

No `TalentProfile`, `PotentialProfile`, `TalentEngine`, or equivalent Kotlin component appears in the current application tree audited in Phase 0.

Bundled database may contain legacy/talent-related tables that still require schema-level inspection.

Classification: Roadmap 6 = AUDIT REQUIRED at DB level; no integrated runtime implementation confirmed.

## 11. Progression

No `ProgressionEngine`, `ProgressionLedger`, `DerivedValueResolver`, `PlayerDomainEngine`, `PlayerCommand`, `PlayerChangeSet`, or `WorldRuleProvider` implementation file is present in the current Android runtime tree under those target concepts.

Existing backend StatePatch can directly propose table mutations, meaning progression rules are not yet isolated behind Player Domain.

Classification:
- Roadmap 16 PlayerCommand = MISSING in audited runtime.
- Roadmap 17 PlayerChangeSet = MISSING as domain contract (StatePatch is a different, lower-level DB mutation format).
- Roadmap 18 PlayerDomainEngine = MISSING.
- Roadmap 19 WorldRuleProvider = MISSING as target abstraction.
- Roadmap 20 ProgressionEngine/Ledger = MISSING/DB audit still required for legacy ledger tables.
- Roadmap 21 Diminishing Returns/passive hooks = MISSING in audited runtime.
- Roadmap 22 Player Invariant Validator = MISSING as dedicated domain validator; SourceOfTruth guard is not sufficient.

## 12. Innate/racial/bloodline/evolution

No integrated target abstraction has yet been found in Android runtime code.

World Pack data may already contain canon-specific bloodline/racial definitions; database audit is still required before marking fully MISSING.

Classification: Roadmap 9 = AUDIT REQUIRED / no canonical runtime domain contract confirmed.

## 13. Relationships, organizations, goals

Confirmed persisted/read data:
- `relationships_v2`,
- `organization_memberships_v3`,
- `character_goals`,
- world `organization_definitions_v3`.

These are meaningful existing state domains and should be incorporated into future CharacterPanelSnapshot v2 rather than re-created.

## 14. CharacterPanel architectural target after audit

Do NOT make CharacterPanel v1 authoritative.

Target path:
explicit activePlayerUid
-> PlayerRepository authoritative state
-> DerivedValueResolver/runtime state
-> ledger summaries
-> PlayerSnapshotBuilder(profile)
-> CharacterPanelSnapshot v2.

The legacy `CharacterPanelReader` should eventually become an adapter/migration bridge or be replaced behind the new read-model builder only after authoritative Player State exists.

## 15. Earliest Player Domain gaps

Before expanding CharacterPanel fields, the safest dependency order remains:
1. explicit authoritative active player UID,
2. repository/player state contract,
3. dynamic stat/resource model and modifier semantics,
4. talent/potential model,
5. skill/technique/innate contracts,
6. inventory/equipment/ownership,
7. economy ledger/assets,
8. commands/changesets/domain engine,
9. progression/invariants/ledgers,
10. CharacterPanelSnapshot v2 builder.

This confirms the MASTER roadmap ordering is appropriate: expanding presentation before authoritative player mechanics would create another source-of-truth problem.
