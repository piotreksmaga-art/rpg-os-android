package com.rpgos.app

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ActiveGameMasterRepository(
    val campaignUid: EntityUid,
    val worldPackUid: EntityUid,
    val repository: SQLiteUnifiedCampaignRepository
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
        return try {
            MigrationManager().ensureV1(db)
            val campaignUid = CampaignIdentityResolver.ensure(db)
            val worldPackUid = resolveWorldPackUid()
            ActiveGameMasterRepository(
                campaignUid = campaignUid,
                worldPackUid = worldPackUid,
                repository = SQLiteUnifiedCampaignRepository(
                    context = context,
                    db = db,
                    campaignUid = campaignUid,
                    worldPackUid = worldPackUid,
                    ownsDatabase = true
                )
            )
        } catch (t: Throwable) {
            db.close()
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
