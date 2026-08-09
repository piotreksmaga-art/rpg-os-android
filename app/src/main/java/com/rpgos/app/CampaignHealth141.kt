package com.rpgos.app

import android.content.Context
import java.io.File

enum class CampaignHealthState141 {
    HEALTHY,
    RECOVERED,
    BLOCKED
}

data class CampaignHealthReport141(
    val state: CampaignHealthState141,
    val campaignDirName: String,
    val recoveryWasPending: Boolean,
    val errorBoundary: String? = null,
    val errorCodes: List<String> = emptyList(),
    val detail: String? = null
) {
    val canEnterRuntime: Boolean
        get() = state != CampaignHealthState141.BLOCKED
}

/**
 * Readable health contract for the active campaign runtime boundary.
 *
 * The report does not invent a second source of truth. It exercises the same
 * production open path as the GM. A pending restore journal is considered
 * RECOVERED only if openRuntimeSession() successfully performs recovery and the
 * resulting campaign passes CAMPAIGN_OPEN integrity. Any failure is BLOCKED.
 */
class CampaignHealthService141(
    private val context: Context,
    private val store: LocalGameStore
) {
    fun inspectActiveCampaign(): CampaignHealthReport141 {
        val campaignDirName = store.activeCampaignDirName()
        val campaignDir = File(context.filesDir, "rpgos/saves/$campaignDirName")
        val recoveryWasPending = RestoreRecovery141.hasPendingRecovery(campaignDir)

        return try {
            GameMasterRepositoryFactory(context, store).openRuntimeSession().use { }
            CampaignHealthReport141(
                state = if (recoveryWasPending) {
                    CampaignHealthState141.RECOVERED
                } else {
                    CampaignHealthState141.HEALTHY
                },
                campaignDirName = campaignDirName,
                recoveryWasPending = recoveryWasPending
            )
        } catch (gate: GameMasterIntegrityGateException141) {
            CampaignHealthReport141(
                state = CampaignHealthState141.BLOCKED,
                campaignDirName = campaignDirName,
                recoveryWasPending = recoveryWasPending,
                errorBoundary = gate.boundary,
                errorCodes = gate.errorCodes,
                detail = gate.message
            )
        } catch (t: Throwable) {
            CampaignHealthReport141(
                state = CampaignHealthState141.BLOCKED,
                campaignDirName = campaignDirName,
                recoveryWasPending = recoveryWasPending,
                errorBoundary = "CAMPAIGN_OPEN",
                errorCodes = listOf("CAMPAIGN_OPEN_FAILED"),
                detail = t.message ?: t::class.java.simpleName
            )
        }
    }
}
