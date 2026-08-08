package com.rpgos.app

/** Optional storage capability for pruning automatic GM checkpoints. */
interface SnapshotRetention141 {
    suspend fun pruneSnapshots(campaignUid: EntityUid, keepNewest: Int)
}
