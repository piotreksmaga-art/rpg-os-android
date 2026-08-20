package com.rpgos.app

enum class RuntimeTruthLayer {
    AUTHORITATIVE, AUTHORITATIVE_DOMAIN_HISTORY, MECHANICS_DEFINITION_AUTHORITY, DERIVED, CACHE,
    PRESENTATION, DERIVED_PRESENTATION, DERIVED_PROJECTION, APPEND_ONLY_COMMIT_EVIDENCE,
    APPEND_ONLY_HISTORICAL_EVIDENCE, ADMINISTRATIVE_MIGRATION_RECOVERY, OPERATIONAL_INFRASTRUCTURE
}
enum class RuntimeMutationCapability { CANONICAL_TURN, DERIVED_REBUILD, CACHE_REBUILD, PRESENTATION_ONLY, ADMINISTRATIVE }
data class RuntimeStateFamily(val uid:String,val layers:Set<RuntimeTruthLayer>,val persistentTables:Set<String> = emptySet()) {
    init { require(uid.isNotBlank()&&layers.isNotEmpty()) }
    val isAuthoritative get()=RuntimeTruthLayer.AUTHORITATIVE in layers||RuntimeTruthLayer.AUTHORITATIVE_DOMAIN_HISTORY in layers
    val isMechanicsDefinitionAuthority get()=RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY in layers
    val isAdministrativeOnlyPersistentAuthority get()=isMechanicsDefinitionAuthority
}
object RuntimeTruthLayerRegistry {
    private fun f(uid:String,layer:RuntimeTruthLayer,vararg t:String)=RuntimeStateFamily(uid,setOf(layer),t.toSet())
    val families=listOf(
        RuntimeStateFamily("ACCESS_AUTHORITY",setOf(RuntimeTruthLayer.AUTHORITATIVE,RuntimeTruthLayer.AUTHORITATIVE_DOMAIN_HISTORY),setOf(Phase38AccessAuthoritySchema.RECORDS)),
        f("CAMPAIGN_TRUTH",RuntimeTruthLayer.AUTHORITATIVE,"campaign_truth_records"),
        f("CANON_DIVERGENCE",RuntimeTruthLayer.AUTHORITATIVE,"campaign_canon_divergences"),
        f("ACTIVE_PLAYER_IDENTITY",RuntimeTruthLayer.AUTHORITATIVE,"active_player_ref"),
        f("BASE_STATS_RESOURCES",RuntimeTruthLayer.AUTHORITATIVE,"player_stats","player_resources"),
        f("PROGRESSION_PROFILES",RuntimeTruthLayer.AUTHORITATIVE,"talent_profile_entries","potential_profile_entries"),
        f("SKILLS_TECHNIQUES",RuntimeTruthLayer.AUTHORITATIVE,"player_skills_v2","player_techniques_v2"),
        f("INNATE_EVOLUTION",RuntimeTruthLayer.AUTHORITATIVE,"player_origins_v2","player_innate_features","player_evolution_states","player_evolution_stages","player_form_unlocks","player_active_forms"),
        f("INVENTORY",RuntimeTruthLayer.AUTHORITATIVE,"player_inventory_stacks","player_inventory_unique","item_instances"),
        f("EQUIPMENT_LOADOUT",RuntimeTruthLayer.AUTHORITATIVE,"player_equipment","player_equipment_slots"),
        f("MODIFIER_INPUTS",RuntimeTruthLayer.AUTHORITATIVE,"modifiers"),
        f("OWNERSHIP_REFERENCE_STATE",RuntimeTruthLayer.AUTHORITATIVE,"ownership_party_registry","ownership_asset_registry"),
        f("OWNERSHIP_HISTORY",RuntimeTruthLayer.AUTHORITATIVE_DOMAIN_HISTORY,"ownership_records","ownership_operations"),
        RuntimeStateFamily("FINANCE_AUTHORITY",setOf(RuntimeTruthLayer.AUTHORITATIVE,RuntimeTruthLayer.AUTHORITATIVE_DOMAIN_HISTORY),setOf("financial_accounts","financial_ledger_transactions")),
        f("FINANCE_BALANCE_PROJECTION",RuntimeTruthLayer.DERIVED,"financial_account_balances"),
        RuntimeStateFamily("ASSET_LIABILITY_AUTHORITY",setOf(RuntimeTruthLayer.AUTHORITATIVE,RuntimeTruthLayer.AUTHORITATIVE_DOMAIN_HISTORY),setOf("asset_records","asset_valuations","obligation_records","obligation_status_history","obligation_settlements","asset_encumbrances")),
        RuntimeStateFamily("DEVELOPMENT_PROJECTS",setOf(RuntimeTruthLayer.AUTHORITATIVE,RuntimeTruthLayer.AUTHORITATIVE_DOMAIN_HISTORY),setOf("development_projects","project_status_history","project_requirements","project_requirement_satisfactions","project_milestone_definitions","project_milestone_achievements","project_work_records","project_dependencies","project_outcomes")),

        RuntimeStateFamily("STAT_RESOURCE_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("stat_definitions","resource_definitions")),
        RuntimeStateFamily("PROGRESSION_DOMAIN_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("progression_domain_definitions")),
        RuntimeStateFamily("SKILL_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("skill_definitions_v2","skill_definition_domains")),
        RuntimeStateFamily("TECHNIQUE_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("technique_definitions_v2","technique_skill_requirements","technique_resource_costs")),
        RuntimeStateFamily("INNATE_EVOLUTION_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("origin_definitions_v2","innate_feature_definitions","evolution_path_definitions","evolution_stage_definitions","evolution_transition_definitions","form_definitions","form_modifier_bindings","legacy_phase9_mappings")),
        RuntimeStateFamily("ITEM_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("item_definitions_v2")),
        RuntimeStateFamily("EQUIPMENT_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("equipment_slot_definitions","equipment_compatibility_rules","equipment_rule_slots")),
        RuntimeStateFamily("OWNERSHIP_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("ownership_owner_kinds","ownership_asset_kinds")),
        RuntimeStateFamily("FINANCE_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("currency_definitions","financial_account_type_definitions","financial_transaction_type_definitions")),
        RuntimeStateFamily("ASSET_LIABILITY_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("asset_kind_definitions","obligation_type_definitions")),
        RuntimeStateFamily("PROJECT_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("project_type_definitions")),
        RuntimeStateFamily("LEGACY_MECHANICS_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),setOf("technique_definitions")),

        RuntimeStateFamily("CURRENT_WORLD_AUTHORITY",setOf(RuntimeTruthLayer.AUTHORITATIVE),BundledCampaignPersistentFamilies.CURRENT_WORLD_AUTHORITY),
        RuntimeStateFamily("HISTORICAL_WORLD_EVIDENCE",setOf(RuntimeTruthLayer.APPEND_ONLY_HISTORICAL_EVIDENCE),BundledCampaignPersistentFamilies.HISTORICAL_WORLD_EVIDENCE),
        RuntimeStateFamily("BUNDLED_MECHANICS_DEFINITIONS",setOf(RuntimeTruthLayer.MECHANICS_DEFINITION_AUTHORITY),BundledCampaignPersistentFamilies.MECHANICS_DEFINITION_AUTHORITY),
        RuntimeStateFamily("DERIVED_SIMULATION_STATE",setOf(RuntimeTruthLayer.DERIVED),BundledCampaignPersistentFamilies.DERIVED_SIMULATION_STATE),
        RuntimeStateFamily("PRESENTATION_UI_STATE",setOf(RuntimeTruthLayer.PRESENTATION),BundledCampaignPersistentFamilies.PRESENTATION_UI_STATE),
        RuntimeStateFamily("OPERATIONAL_REPAIR_STATE",setOf(RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY,RuntimeTruthLayer.OPERATIONAL_INFRASTRUCTURE),BundledCampaignPersistentFamilies.OPERATIONAL_REPAIR_STATE),
        RuntimeStateFamily("NPC_KNOWLEDGE_STATE",setOf(RuntimeTruthLayer.AUTHORITATIVE),BundledCampaignPersistentFamilies.NPC_KNOWLEDGE_STATE),
        RuntimeStateFamily("NARRATIVE_PLANNING_STATE",setOf(RuntimeTruthLayer.AUTHORITATIVE),BundledCampaignPersistentFamilies.NARRATIVE_PLANNING_STATE),
        RuntimeStateFamily("TEMPORAL_SCHEDULE_STATE",setOf(RuntimeTruthLayer.AUTHORITATIVE),BundledCampaignPersistentFamilies.TEMPORAL_SCHEDULE_STATE),

        f("RESOLVED_EFFECTIVE_VALUES",RuntimeTruthLayer.DERIVED),
        f("TURN_RECEIPTS",RuntimeTruthLayer.APPEND_ONLY_COMMIT_EVIDENCE,"turn_transaction_receipts"),
        RuntimeStateFamily("EVENT_STORE",setOf(RuntimeTruthLayer.APPEND_ONLY_COMMIT_EVIDENCE,RuntimeTruthLayer.APPEND_ONLY_HISTORICAL_EVIDENCE),setOf("canonical_gameplay_events")),
        RuntimeStateFamily("CAUSAL_GRAPH",setOf(RuntimeTruthLayer.APPEND_ONLY_COMMIT_EVIDENCE,RuntimeTruthLayer.APPEND_ONLY_HISTORICAL_EVIDENCE),setOf("canonical_causal_relations")),
        RuntimeStateFamily("CAMPAIGN_SNAPSHOTS",setOf(RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY),setOf("campaign_snapshots")),
        RuntimeStateFamily("COMMITTED_REPLAY_MATERIAL",setOf(RuntimeTruthLayer.APPEND_ONLY_COMMIT_EVIDENCE),setOf("canonical_turn_replay_payloads")),
        f("CHARACTER_PANEL_SNAPSHOT_V2",RuntimeTruthLayer.DERIVED_PRESENTATION),
        f("PLAYER_SNAPSHOT_PROFILES",RuntimeTruthLayer.DERIVED_PROJECTION),
        RuntimeStateFamily("CONTEXT_BUNDLE",setOf(RuntimeTruthLayer.DERIVED,RuntimeTruthLayer.PRESENTATION)),
        RuntimeStateFamily("LEGACY_RECONCILIATION_METADATA",setOf(RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY,RuntimeTruthLayer.APPEND_ONLY_HISTORICAL_EVIDENCE),setOf("chapter_events","character_finances","character_inventory","character_skills","character_stats","character_status_snapshot","character_techniques","consequence_links","financial_transactions","legacy_projects","legacy_stat_aliases","legacy_resource_aliases","legacy_progression_evidence","legacy_progression_mappings","legacy_skill_mappings","legacy_technique_mappings","legacy_technique_resource_cost_mappings","legacy_inventory_mappings","legacy_ownership_mappings","legacy_financial_evidence")),
        RuntimeStateFamily("GAMEPLAY_READINESS_METADATA",setOf(RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY,RuntimeTruthLayer.OPERATIONAL_INFRASTRUCTURE),setOf("campaign_intelligence_activation","rpgos_writer_contract_context","rpgos_gameplay_mutation_context")),
        RuntimeStateFamily("CHAPTER_MANIFESTS_SUMMARIES",setOf(RuntimeTruthLayer.PRESENTATION,RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY),setOf("chapter_manifests_v2")),
        f("REBUILDABLE_INDEXES_MATERIALIZATIONS",RuntimeTruthLayer.CACHE,"narrative_memory_index"),
        f("UI_STATE",RuntimeTruthLayer.PRESENTATION,"campaign_visual_library"),
        f("BACKUP_PACKAGES",RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY),
        f("SCHEMA_MIGRATION_REPAIR",RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY,"rpgos_schema_migrations"),
        RuntimeStateFamily("SCHEMA_VERSION_STATE",setOf(RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY,RuntimeTruthLayer.OPERATIONAL_INFRASTRUCTURE),setOf("rpgos_schema_family_versions","rpgos_migration_attempts"))
    )
    private val byUid=families.associateBy{it.uid}; private val byTable=families.flatMap{a->a.persistentTables.map{it to a}}.toMap()
    fun requireFamily(uid:String)=requireNotNull(byUid[uid]){"RPGOS-G32:UNCLASSIFIED_STATE_FAMILY:$uid"}
    fun classificationForTable(t:String)=byTable[t]
    fun requireClassifiedTable(t:String)=requireNotNull(byTable[t]){"RPGOS-G32:UNCLASSIFIED_PERSISTENT_FAMILY:$t"}
    fun classifiedPersistentTables():Set<String> = byTable.keys.toSortedSet()
    fun authoritativePersistentTables():Set<String> = families.asSequence().filter{it.isAuthoritative}.flatMap{it.persistentTables.asSequence()}.toSortedSet()
    fun administrativeOnlyPersistentTables():Set<String> = families.asSequence().filter{it.isAdministrativeOnlyPersistentAuthority}.flatMap{it.persistentTables.asSequence()}.toSortedSet()
    fun requireAuthoritativeMutation(uid:String,c:RuntimeMutationCapability){val family=requireFamily(uid);if(family.isAuthoritative)require(c==RuntimeMutationCapability.CANONICAL_TURN||c==RuntimeMutationCapability.ADMINISTRATIVE){"RPGOS-G32:NON_AUTHORITATIVE_LAYER_CANNOT_WRITE_AUTHORITY"};if(family.isMechanicsDefinitionAuthority)require(c==RuntimeMutationCapability.ADMINISTRATIVE){"RPGOS-G32:MECHANICS_DEFINITION_REQUIRES_ADMIN"}}
    fun requireGameplayCapability(c:RuntimeMutationCapability){require(c==RuntimeMutationCapability.CANONICAL_TURN){"RPGOS-G32:GAMEPLAY_CANNOT_INVOKE_ADMIN_AUTHORITY"}}
    fun validateCanonicalInventory(){require(families.map{it.uid}.toSet().size==families.size);require(byTable.size==families.sumOf{it.persistentTables.size}){"RPGOS-G32:DUPLICATE_TABLE_CLASSIFICATION"};require(authoritativePersistentTables().all{requireClassifiedTable(it).isAuthoritative});require(administrativeOnlyPersistentTables().all{requireClassifiedTable(it).isMechanicsDefinitionAuthority})}
}
