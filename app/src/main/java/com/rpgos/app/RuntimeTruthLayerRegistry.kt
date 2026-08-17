package com.rpgos.app

enum class RuntimeTruthLayer { AUTHORITATIVE, AUTHORITATIVE_DOMAIN_HISTORY, DERIVED, CACHE, PRESENTATION, DERIVED_PRESENTATION, DERIVED_PROJECTION, APPEND_ONLY_COMMIT_EVIDENCE, APPEND_ONLY_HISTORICAL_EVIDENCE, ADMINISTRATIVE_MIGRATION_RECOVERY, OPERATIONAL_INFRASTRUCTURE }
enum class RuntimeMutationCapability { CANONICAL_TURN, DERIVED_REBUILD, CACHE_REBUILD, PRESENTATION_ONLY, ADMINISTRATIVE }
data class RuntimeStateFamily(val uid:String,val layers:Set<RuntimeTruthLayer>,val persistentTables:Set<String> = emptySet()) { init { require(uid.isNotBlank()&&layers.isNotEmpty()) }; val isAuthoritative get()=RuntimeTruthLayer.AUTHORITATIVE in layers||RuntimeTruthLayer.AUTHORITATIVE_DOMAIN_HISTORY in layers }
object RuntimeTruthLayerRegistry {
 private fun f(uid:String,layer:RuntimeTruthLayer,vararg t:String)=RuntimeStateFamily(uid,setOf(layer),t.toSet())
 val families=listOf(
  f("CAMPAIGN_TRUTH",RuntimeTruthLayer.AUTHORITATIVE,"campaign_truth_records"),
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
  f("RESOLVED_EFFECTIVE_VALUES",RuntimeTruthLayer.DERIVED),
  f("TURN_RECEIPTS",RuntimeTruthLayer.APPEND_ONLY_COMMIT_EVIDENCE,"turn_transaction_receipts"),
  RuntimeStateFamily("EVENT_STORE",setOf(RuntimeTruthLayer.APPEND_ONLY_COMMIT_EVIDENCE,RuntimeTruthLayer.APPEND_ONLY_HISTORICAL_EVIDENCE),setOf("canonical_gameplay_events")),
  RuntimeStateFamily("CAUSAL_GRAPH",setOf(RuntimeTruthLayer.APPEND_ONLY_COMMIT_EVIDENCE,RuntimeTruthLayer.APPEND_ONLY_HISTORICAL_EVIDENCE),setOf("canonical_causal_relations")),
  f("CHARACTER_PANEL_SNAPSHOT_V2",RuntimeTruthLayer.DERIVED_PRESENTATION),
  f("PLAYER_SNAPSHOT_PROFILES",RuntimeTruthLayer.DERIVED_PROJECTION),
  RuntimeStateFamily("CONTEXT_BUNDLE",setOf(RuntimeTruthLayer.DERIVED,RuntimeTruthLayer.PRESENTATION)),
  RuntimeStateFamily("LEGACY_RECONCILIATION_METADATA",setOf(RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY,RuntimeTruthLayer.APPEND_ONLY_HISTORICAL_EVIDENCE),setOf("legacy_stat_aliases","legacy_resource_aliases","legacy_progression_evidence","legacy_progression_mappings","legacy_skill_mappings","legacy_technique_mappings","legacy_technique_resource_cost_mappings","legacy_inventory_mappings","legacy_ownership_mappings","legacy_financial_evidence")),
  RuntimeStateFamily("GAMEPLAY_READINESS_METADATA",setOf(RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY,RuntimeTruthLayer.OPERATIONAL_INFRASTRUCTURE),setOf("campaign_intelligence_activation","rpgos_writer_contract_context","rpgos_gameplay_mutation_context")),
  RuntimeStateFamily("CHAPTER_MANIFESTS_SUMMARIES",setOf(RuntimeTruthLayer.PRESENTATION,RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY),setOf("chapter_manifests_v2")),
  f("REBUILDABLE_INDEXES_MATERIALIZATIONS",RuntimeTruthLayer.CACHE,"narrative_memory_index"),
  f("UI_STATE",RuntimeTruthLayer.PRESENTATION),
  f("BACKUP_PACKAGES",RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY),
  f("SCHEMA_MIGRATION_REPAIR",RuntimeTruthLayer.ADMINISTRATIVE_MIGRATION_RECOVERY,"rpgos_schema_migrations")
 )
 private val byUid=families.associateBy{it.uid};private val byTable=families.flatMap{a->a.persistentTables.map{it to a}}.toMap()
 fun requireFamily(uid:String)=requireNotNull(byUid[uid]){"RPGOS-G32:UNCLASSIFIED_STATE_FAMILY:$uid"};fun classificationForTable(t:String)=byTable[t];fun requireClassifiedTable(t:String)=requireNotNull(byTable[t]){"RPGOS-G32:UNCLASSIFIED_PERSISTENT_FAMILY:$t"}
 fun classifiedPersistentTables():Set<String> = byTable.keys.toSortedSet()
 fun authoritativePersistentTables():Set<String> = families.asSequence().filter{it.isAuthoritative}.flatMap{it.persistentTables.asSequence()}.toSortedSet()
 fun requireAuthoritativeMutation(uid:String,c:RuntimeMutationCapability){if(requireFamily(uid).isAuthoritative)require(c==RuntimeMutationCapability.CANONICAL_TURN||c==RuntimeMutationCapability.ADMINISTRATIVE){"RPGOS-G32:NON_AUTHORITATIVE_LAYER_CANNOT_WRITE_AUTHORITY"}}
 fun requireGameplayCapability(c:RuntimeMutationCapability){require(c==RuntimeMutationCapability.CANONICAL_TURN){"RPGOS-G32:GAMEPLAY_CANNOT_INVOKE_ADMIN_AUTHORITY"}}
 fun validateCanonicalInventory(){require(families.map{it.uid}.toSet().size==families.size);require(byTable.size==families.sumOf{it.persistentTables.size}){"RPGOS-G32:DUPLICATE_TABLE_CLASSIFICATION"};require(authoritativePersistentTables().all{requireClassifiedTable(it).isAuthoritative})}
}
