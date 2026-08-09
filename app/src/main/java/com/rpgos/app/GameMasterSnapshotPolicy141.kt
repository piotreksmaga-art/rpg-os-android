package com.rpgos.app

/**
 * Bounded checkpoint policy for long-running campaigns.
 *
 * Snapshots are maintenance, never part of the canonical turn transaction.
 * A failed checkpoint must not make an already committed turn look failed.
 */
object GameMasterSnapshotPolicy141 {
    const val TURN_INTERVAL: Long = 500L
    const val KEEP_NEWEST_AUTOMATIC: Int = 6

    suspend fun maintain(
        repository: UnifiedCampaignRepository,
        campaignUid: EntityUid,
        integrityGate: suspend () -> Unit = {},
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

        runCatching { integrityGate() }
            .getOrElse {
                onFailure(it)
                return null
            }

        val created = runCatching {
            repository.createSnapshot(campaignUid, currentTurn)
        }.getOrElse {
            onFailure(it)
            return null
        }

        if (repository is SnapshotRetention141) {
            runCatching {
                repository.pruneSnapshots(campaignUid, KEEP_NEWEST_AUTOMATIC)
            }.onFailure(onFailure)
        }

        return created
    }
}
