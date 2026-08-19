package com.rpgos.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Process-wide campaign lifecycle lock: turns share READ; file-level recovery/activation owns WRITE. */
internal object CampaignRuntimeLifecycleLock {
    private val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    private fun lock(campaignUid: String): ReentrantReadWriteLock =
        locks.computeIfAbsent(campaignUid) { ReentrantReadWriteLock(true) }

    fun <T> withTurn(campaignUid: String, block: () -> T): T {
        require(campaignUid.isNotBlank())
        val read = lock(campaignUid).readLock()
        read.lock()
        return try { block() } finally { read.unlock() }
    }

    fun <T> withRecovery(campaignUid: String, block: () -> T): T {
        require(campaignUid.isNotBlank())
        val lifecycle = lock(campaignUid)
        require(lifecycle.readHoldCount == 0) { "RPGOS-G32:GAMEPLAY_CANNOT_INVOKE_ADMIN_AUTHORITY" }
        val write = lifecycle.writeLock()
        write.lock()
        return try { block() } finally { write.unlock() }
    }
}
