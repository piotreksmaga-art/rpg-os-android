package com.rpgos.app

data class CampaignApplicationAccess141(
    val health: CampaignHealthReport141,
    val canReadCampaignData: Boolean,
    val canEnterGameMaster: Boolean,
    val statusMessage: String
)

/**
 * Application boundary between campaign integrity and presentation code.
 *
 * This class does not validate campaign data itself. It consumes the single
 * CampaignHealth contract and converts it into explicit UI/runtime permissions.
 * Presentation code must not infer that an empty panel means a healthy campaign.
 */
class CampaignApplicationGate141(
    private val store: LocalGameStore
) {
    fun inspect(): CampaignApplicationAccess141 {
        val health = store.campaignHealth()
        return when (health.state) {
            CampaignHealthState141.HEALTHY -> CampaignApplicationAccess141(
                health = health,
                canReadCampaignData = true,
                canEnterGameMaster = true,
                statusMessage = "Kampania gotowa."
            )

            CampaignHealthState141.RECOVERED -> CampaignApplicationAccess141(
                health = health,
                canReadCampaignData = true,
                canEnterGameMaster = true,
                statusMessage = "Kampania odzyskana i zweryfikowana."
            )

            CampaignHealthState141.BLOCKED -> CampaignApplicationAccess141(
                health = health,
                canReadCampaignData = false,
                canEnterGameMaster = false,
                statusMessage = buildString {
                    append("Kampania zablokowana przez kontrolę spójności")
                    health.errorBoundary?.let { append(" [").append(it).append(']') }
                    if (health.errorCodes.isNotEmpty()) {
                        append(": ").append(health.errorCodes.joinToString(", "))
                    }
                }
            )
        }
    }
}
