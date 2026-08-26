package com.rpgos.app

import android.content.Context

data class RpgOsSettings(
    val backendUrl: String,
    val updateFeedUrl: String,
    val worldPackId: String,
    val campaignId: String,
    val showGmDiagnostics: Boolean,
    val autoBackup: Boolean,
    val ai:AiSystemConfiguration=AiSystemConfiguration()
)

class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("rpgos_settings", Context.MODE_PRIVATE)

    fun load(): RpgOsSettings {
        val storedBackend =
            prefs.getString("backend_url", BuildConfig.RPGOS_BACKEND_URL)
                ?: BuildConfig.RPGOS_BACKEND_URL

        // alpha1 temporarily used the backend field as the GitHub release URL.
        // From alpha3 these are separate settings.
        val migratedBackend =
            if (storedBackend.contains(
                    "api.github.com/repos/piotreksmaga-art/rpg-os-android/releases",
                    ignoreCase = true
                )
            ) {
                BuildConfig.RPGOS_BACKEND_URL
            } else storedBackend

        val activeCampaignId = CampaignSelectionManager(context).activeCampaignId()

        return RpgOsSettings(
            backendUrl = migratedBackend,
            updateFeedUrl =
                prefs.getString("update_feed_url", BuildConfig.RPGOS_UPDATE_FEED_URL)
                    ?: BuildConfig.RPGOS_UPDATE_FEED_URL,
            worldPackId = prefs.getString("worldpack_id", "naruto") ?: "naruto",
            campaignId = activeCampaignId,
            showGmDiagnostics = prefs.getBoolean("show_gm_diagnostics", true),
            autoBackup = prefs.getBoolean("auto_backup", true),
            ai=loadAi()
        )
    }

    fun save(settings: RpgOsSettings) {
        prefs.edit()
            .putString("backend_url", settings.backendUrl)
            .putString("update_feed_url", settings.updateFeedUrl)
            .putString("worldpack_id", settings.worldPackId)
            // Kept only as a compatibility mirror for older builds. Runtime identity
            // is resolved exclusively through CampaignSelectionManager/ActiveCampaignRef.
            .putString("campaign_id", CampaignSelectionManager(context).activeCampaignId())
            .putBoolean("show_gm_diagnostics", settings.showGmDiagnostics)
            .putBoolean("auto_backup", settings.autoBackup)
            .putString("ai_gm_assignment",encodeAssignment(settings.ai.gameMaster))
            .putString("ai_director_assignment",encodeAssignment(settings.ai.director))
            .putBoolean("ai_cloud_allowed",settings.ai.privacy.cloudAllowed)
            .putBoolean("ai_cloud_player_text_allowed",settings.ai.privacy.cloudAllowedForPlayerText)
            .putBoolean("ai_cloud_director_allowed",settings.ai.privacy.cloudAllowedForDirector)
            .also{editor->settings.ai.localModelSettings?.let{local->
                editor.putString("ai_local_model_uid",local.modelUid).putString("ai_local_variant_uid",local.variantUid)
                    .putInt("ai_local_context_units",local.contextUnits).putLong("ai_local_kv_bytes_per_unit",local.kvBytesPerContextUnit)
                    .putString("ai_local_backend",local.backend.name).putInt("ai_local_threads",local.threads?:-1)
                    .putInt("ai_local_prefill_batch",local.prefillBatchUnits?:-1).putBoolean("ai_local_recommended",local.recommended)
            }}
            .apply()
    }

    private fun loadAi():AiSystemConfiguration{
        val localModel=prefs.getString("ai_local_model_uid",null)?.let{modelUid->
            val profile=BielikLocalModelProfiles.BIELIK_4_5B_V3
            runCatching{LocalModelSettings(
                modelUid,prefs.getString("ai_local_variant_uid",profile.variants.first().variantUid)!!,
                prefs.getInt("ai_local_context_units",profile.recommendedContextUnits),
                prefs.getLong("ai_local_kv_bytes_per_unit",profile.recommendedKvBytesPerContextUnit),
                runCatching{LocalRuntimeBackend.valueOf(prefs.getString("ai_local_backend",LocalRuntimeBackend.AUTO.name)!!)}.getOrDefault(LocalRuntimeBackend.AUTO),
                prefs.getInt("ai_local_threads",-1).takeIf{it>0},prefs.getInt("ai_local_prefill_batch",-1).takeIf{it>0},
                prefs.getBoolean("ai_local_recommended",true)
            )}.getOrNull()
        }
        return AiSystemConfiguration(
            decodeAssignment(AiRole.GAME_MASTER,prefs.getString("ai_gm_assignment",null)),
            decodeAssignment(AiRole.DIRECTOR_SCENARIST,prefs.getString("ai_director_assignment",null)),
            AiPrivacyPolicy(
                prefs.getBoolean("ai_cloud_allowed",true),prefs.getBoolean("ai_cloud_player_text_allowed",true),
                prefs.getBoolean("ai_cloud_director_allowed",true)
            ),localModel
        )
    }
    private fun encodeAssignment(value:AiRoleAssignment)=if(value.kind==AiAssignmentKind.AUTO)"AUTO" else "PINNED|${value.pinned!!.providerUid}|${value.pinned.modelUid}"
    private fun decodeAssignment(role:AiRole,raw:String?):AiRoleAssignment{
        if(raw.isNullOrBlank()||raw=="AUTO")return AiRoleAssignment(role)
        val parts=raw.split('|',limit=3)
        return if(parts.size==3&&parts[0]=="PINNED"&&parts[1].isNotBlank()&&parts[2].isNotBlank())AiRoleAssignment(role,AiAssignmentKind.PINNED,AiModelSelection(parts[1],parts[2])) else AiRoleAssignment(role)
    }
}
