package com.rpgos.app

/**
 * Bounded checkpoint policy for long-running campaigns.
 *
 * Snapshots are maintenance, never part of the canonical turn transaction.
 * A failed checkpoint must not make an already committed turn look failed.
 */
object GameMasterSnapshotPolicy141 {
    const val TURN_INTERVAL: Long = 500L

    suspend fun maintain(
        repository: UnifiedCampaignRepository,
        campaignUid: EntityUid,
        onFailure: (Throwable) -> Unit = {}
    ): CampaignSnapshotRef? {
        val currentTurn = repository.currentTurnId(campaignUid)
        if (currentTurn <= 0L) return null

        val latest = runCatching { repository.latestSnapshot(campaignUid) }
            .getOrElse {
                onFailure(it)
                return null
            }

        val due = latest == null || currentTurn - latest.throughTurnId >= TURN_INTERVAL
        if (!due) return null

        return runCatching {
            repository.createSnapshot(campaignUid, currentTurn)
        }.getOrElse {
            onFailure(it)
            null
        }
    }
}
