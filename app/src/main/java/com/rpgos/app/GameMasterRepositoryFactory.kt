package com.rpgos.app

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ActiveGameMasterRepository(
    val campaignUid: EntityUid,
    val worldPackUid: EntityUid,
    val repository: SQLiteUnifiedCampaignRepository,
    val knowledgeStore: KnowledgeTransmissionStore141,
    val npcKnowledgeStores: SQLiteNpcKnowledgeStores141? = null
) : AutoCloseable {
    override fun close() = repository.close()
}

/**
 * Bridges the legacy LocalGameStore to the GM Engine 141 repository boundary.
 * The same campaign.db used by the rest of RPG OS becomes the durable GM store.
 */
class GameMasterRepositoryFactory(
    private val context: Context,
    private val store: LocalGameStore
) {
    fun openActive(): SQLiteUnifiedCampaignRepository = openActiveSession().repository

    fun openActiveSession(): ActiveGameMasterRepository {
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

            // Knowledge persistence uses additive migrations in the same campaign.db.
            // Generic transmission comes first, then the richer NPC lifecycle ledgers.
            KnowledgeTransmissionSchema141.ensure(db)
            NpcKnowledgePersistenceSchema141.ensure(db)
            val knowledgeStore = SQLiteKnowledgeTransmissionStore141(db, campaignUid)
            val npcKnowledgeStores = SQLiteNpcKnowledgeStores141(db, campaignUid)
            GameMasterLegacyBootstrap141.ensure(db, campaignUid)

            ActiveGameMasterRepository(
                campaignUid = campaignUid,
                worldPackUid = worldPackUid,
                repository = repository,
                knowledgeStore = knowledgeStore,
                npcKnowledgeStores = npcKnowledgeStores
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