package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.util.Collections

/** Exhaustive application-owned SQLite table inventory. Unknown application tables fail closed. */
internal object RuntimePersistentTableInventory {
    private val frameworkExclusions = setOf("android_metadata")

    fun applicationOwnedTables(db: SQLiteDatabase): Set<String> = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null
    ).use { c -> buildSet { while (c.moveToNext()) { val name=c.getString(0); if(name !in frameworkExclusions) add(name) } } }

    fun unclassifiedTables(db: SQLiteDatabase): List<String> =
        applicationOwnedTables(db).filter { RuntimeTruthLayerRegistry.classificationForTable(it) == null }.sorted()

    fun requireComplete(db: SQLiteDatabase) {
        val missing = unclassifiedTables(db)
        require(missing.isEmpty()) { "RPGOS-G32:UNCLASSIFIED_APPLICATION_PERSISTENT_TABLES:${missing.joinToString(",")}" }
    }
}

enum class PersistentWriterCapability {
    CANONICAL_TURN,
    ADMINISTRATIVE,
    PRESENTATION_ONLY,
    READ_ONLY_NON_AUTHORITATIVE,
    PROTECTED_PROJECTED_READ,
    GAMEPLAY_UNREACHABLE
}

data class PersistentWriterContract(
    val methodUid: String,
    val targetFamilyUids: Set<String>,
    val capability: PersistentWriterCapability
) {
    init {
        require(methodUid.isNotBlank())
        targetFamilyUids.forEach { RuntimeTruthLayerRegistry.requireFamily(it) }
    }
}

object RuntimePersistentWriterRegistry {
    private fun c(method:String, capability:PersistentWriterCapability, vararg families:String)=
        PersistentWriterContract(method, Collections.unmodifiableSet(families.toSet()), capability)

    val canonicalTurnTargetFamilies: Set<String> = setOf(
        "BASE_STATS_RESOURCES",
        "SKILLS_TECHNIQUES",
        "INVENTORY",
        "EQUIPMENT_LOADOUT",
        "OWNERSHIP_HISTORY",
        "FINANCE_AUTHORITY",
        "CAMPAIGN_TRUTH",
        "CANON_DIVERGENCE",
        "NPC_KNOWLEDGE_STATE",
        "DEVELOPMENT_PROJECTS",
        "TURN_RECEIPTS",
        "EVENT_STORE",
        "CAUSAL_GRAPH",
        "COMMITTED_REPLAY_MATERIAL",
        "ACCESS_AUTHORITY"
    )

    val campaignRepositoryContracts: Map<String, PersistentWriterContract> = listOf(
        c("bootstrap",PersistentWriterCapability.ADMINISTRATIVE,"SCHEMA_MIGRATION_REPAIR","GAMEPLAY_READINESS_METADATA"),
        c("activeCampaignRef",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("activePlayerRef",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE,"ACTIVE_PLAYER_IDENTITY"),
        c("setActivePlayer",PersistentWriterCapability.ADMINISTRATIVE,"ACTIVE_PLAYER_IDENTITY"),
        c("protectedReads",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("playerState",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("statDefinitions",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE,"STAT_RESOURCE_DEFINITIONS"),
        c("resourceDefinitions",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE,"STAT_RESOURCE_DEFINITIONS"),
        c("registerStatDefinitions",PersistentWriterCapability.ADMINISTRATIVE,"STAT_RESOURCE_DEFINITIONS"),
        c("registerResourceDefinitions",PersistentWriterCapability.ADMINISTRATIVE,"STAT_RESOURCE_DEFINITIONS"),
        c("playerStats",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE,"BASE_STATS_RESOURCES"),
        c("playerResources",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE,"BASE_STATS_RESOURCES"),
        c("activeCampaignDirName",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("activeWorldPackDirName",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("setActiveCampaign",PersistentWriterCapability.ADMINISTRATIVE,"SCHEMA_MIGRATION_REPAIR","GAMEPLAY_READINESS_METADATA"),
        c("setActiveWorldPack",PersistentWriterCapability.ADMINISTRATIVE),
        c("createCampaign",PersistentWriterCapability.ADMINISTRATIVE,"SCHEMA_MIGRATION_REPAIR","GAMEPLAY_READINESS_METADATA"),
        c("openWorldDb",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("openCoreDb",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        PersistentWriterContract("commitTurn", Collections.unmodifiableSet(canonicalTurnTargetFamilies), PersistentWriterCapability.CANONICAL_TURN),
        c("buildContext",PersistentWriterCapability.PROTECTED_PROJECTED_READ,"CONTEXT_BUNDLE"),
        c("fullCharacterPanel",PersistentWriterCapability.PROTECTED_PROJECTED_READ,"CHARACTER_PANEL_SNAPSHOT_V2"),
        c("status",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("time",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("chronicle",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE,"CHAPTER_MANIFESTS_SUMMARIES"),
        c("truthRecords",PersistentWriterCapability.PROTECTED_PROJECTED_READ,"CAMPAIGN_TRUTH"),
        c("canonDivergences",PersistentWriterCapability.PROTECTED_PROJECTED_READ,"CANON_DIVERGENCE"),
        c("npcs",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("npcDetail",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("relationEdges",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("economies",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("wars",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("relationships",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("organizations",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("politics",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("npcsProjection",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("npcDetailProjection",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("relationEdgesProjection",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("economiesProjection",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("warsProjection",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("relationshipsProjection",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("organizationsProjection",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("politicsProjection",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("syncCheck",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("dbTables",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("diagnostics",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("worldRegions",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("worldLocations",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("activeWorldEventsProjection",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("activeWorldEvents",PersistentWriterCapability.PROTECTED_PROJECTED_READ),
        c("techniqueBrowser",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("missionBrowser",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE),
        c("visualLibrary",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE,"UI_STATE"),
        c("addVisual",PersistentWriterCapability.PRESENTATION_ONLY,"UI_STATE"),
        c("packageManager",PersistentWriterCapability.ADMINISTRATIVE),
        c("backups",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE,"BACKUP_PACKAGES"),
        c("restoreBackup",PersistentWriterCapability.ADMINISTRATIVE,"BACKUP_PACKAGES","SCHEMA_MIGRATION_REPAIR","GAMEPLAY_READINESS_METADATA"),
        c("createSnapshot",PersistentWriterCapability.ADMINISTRATIVE,"CAMPAIGN_SNAPSHOTS"),
        c("snapshots",PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE,"CAMPAIGN_SNAPSHOTS"),
        c("restoreLatestSnapshot",PersistentWriterCapability.ADMINISTRATIVE,"CAMPAIGN_SNAPSHOTS","COMMITTED_REPLAY_MATERIAL","SCHEMA_MIGRATION_REPAIR","GAMEPLAY_READINESS_METADATA"),
        c("finalizeChapter",PersistentWriterCapability.ADMINISTRATIVE,"CHAPTER_MANIFESTS_SUMMARIES","BACKUP_PACKAGES")
    ).associateBy { it.methodUid }

    fun requireContract(methodUid:String):PersistentWriterContract =
        requireNotNull(campaignRepositoryContracts[methodUid]) { "RPGOS-G32:UNCLASSIFIED_PERSISTENT_WRITER_METHOD:$methodUid" }
}