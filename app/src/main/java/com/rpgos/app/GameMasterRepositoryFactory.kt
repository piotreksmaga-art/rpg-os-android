package com.rpgos.app

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ActiveGameMasterRepository(
    val campaignUid: EntityUid,
    val worldPackUid: EntityUid,
    val repository: SQLiteUnifiedCampaignRepository,
    val knowledgeStore: KnowledgeTransmissionStore141,
    val npcKnowledgeStores: SQLiteNpcKnowledgeStores141? = null,
    val organizationAuthorizationStore: OrganizationKnowledgeAuthorizationStore141? = null,
    val truthSupersessionStore: TruthSupersession141? = null,
    val semanticMemoryStore: SemanticMemoryStore141? = null
) : AutoCloseable {
    override fun close() = repository.close()
}

/**
 * Bridges the legacy LocalGameStore to the GM Engine 141 repository boundary.
 * The same campaign.db used by the rest of RPG OS becomes the durable GM store.
 *
 * openActiveSession() is intentionally the low-level repository/diagnostic path:
 * integrity tests and repair tooling may need to inspect an already-invalid DB.
 * Production GM execution must enter through openRuntimeSession(), which performs
 * the fail-closed CAMPAIGN_OPEN gate after additive initialization and before the
 * session is exposed to narration, simulation or turn processing.
 */
class GameMasterRepositoryFactory(
    private val context: Context,
    private val store: LocalGameStore
) {
    fun openActive(): SQLiteUnifiedCampaignRepository = openRuntimeSession().repository

    fun openActiveSession(): ActiveGameMasterRepository =
        openSession(requireOpenIntegrity = false)

    fun openRuntimeSession(): ActiveGameMasterRepository =
        openSession(requireOpenIntegrity = true)

    private fun openSession(requireOpenIntegrity: Boolean): ActiveGameMasterRepository {
        val db = store.openSaveDb()
        var repository: SQLiteUnifiedCampaignRepository? = null
        return try {
            MigrationManager().ensureV1(db)

            // Identity may be newly generated when this campaign has never been
            // opened by GM141. Constructing the repository first persists
            // gm_campaign_meta through ensureMeta(). Only after that durable
            // identity exists may legacy bootstrap attach imported state to it.
            val campaignUid = CampaignIdentityResolver.ensure(db)
            val worldPackUid = resolveWorldPackUid()
            repository = SQLiteUnifiedCampaignRepository(
                context = context,
                db = db,
                campaignUid = campaignUid,
                worldPackUid = worldPackUid,
                ownsDatabase = true
            )

            // Knowledge, truth lifecycle and memory provenance use additive migrations
            // in the same campaign.db. No parallel source of truth is introduced.
            KnowledgeTransmissionSchema141.ensure(db)
            NpcKnowledgePersistenceSchema141.ensure(db)
            OrganizationKnowledgeAuthorizationSchema141.ensure(db)
            TruthSupersessionSchema141.ensure(db)
            SemanticMemoryProvenanceSchema141.ensure(db)
            val knowledgeStore = SQLiteKnowledgeTransmissionStore141(db, campaignUid)
            val npcKnowledgeStores = SQLiteNpcKnowledgeStores141(db, campaignUid)
            val organizationAuthorizationStore = SQLiteOrganizationKnowledgeAuthorizationStore141(db)
            val truthSupersessionStore = SQLiteTruthSupersessionStore141(
                db = db,
                repository = repository,
                campaignUid = campaignUid
            )
            val semanticMemoryStore = SQLiteSemanticMemoryStore141(db, campaignUid)
            GameMasterLegacyBootstrap141.ensure(db, campaignUid)

            if (requireOpenIntegrity) {
                // Recovery has already been attempted by LocalGameStore.openSaveDb().
                // Semantic provenance is checked separately so a derived memory can
                // never bypass the primary Source-of-Truth integrity boundary.
                SemanticMemoryIntegrity141(db, campaignUid).requireHealthy("CAMPAIGN_OPEN")
                GameMasterIntegrityGate141(db).requireHealthy("CAMPAIGN_OPEN")
            }

            ActiveGameMasterRepository(
                campaignUid = campaignUid,
                worldPackUid = worldPackUid,
                repository = repository,
                knowledgeStore = knowledgeStore,
                npcKnowledgeStores = npcKnowledgeStores,
                organizationAuthorizationStore = organizationAuthorizationStore,
                truthSupersessionStore = truthSupersessionStore,
                semanticMemoryStore = semanticMemoryStore
            )
        } catch (t: Throwable) {
            if (repository != null) repository.close() else db.close()
            throw t
        }
    }

    private fun resolveWorldPackUid(): EntityUid {
        val dirName = store.activeWorldPackDirName()
        val manifest = File(
            context.filesDir,
            "rpgos/worldpacks/$dirName/worldpack.json"
        )
        val id = runCatching {
            JSONObject(manifest.readText()).optString("id").trim()
        }.getOrNull().orEmpty()

        return EntityUid(
            if (id.isNotBlank()) "WORLDPACK-$id"
            else "WORLDPACK-${dirName.substringBefore(".worldpack")}"
        )
    }
}
