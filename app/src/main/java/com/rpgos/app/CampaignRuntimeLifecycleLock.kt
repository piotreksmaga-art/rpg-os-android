package com.rpgos.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Process-wide campaign lifecycle lock. A campaign has one canonical commit order, so turns for the
 * same campaign are serialized; different campaigns still progress independently. Recovery and
 * activation share the same exclusive boundary and may re-enter only from another admin operation.
 */
internal object CampaignRuntimeLifecycleLock {
    private val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()
    private val activeTurnDepth = ThreadLocal<Int>()

    private fun lock(campaignUid: String): ReentrantReadWriteLock =
        locks.computeIfAbsent(campaignUid) { ReentrantReadWriteLock(true) }

    fun <T> withTurn(campaignUid: String, block: () -> T): T {
        require(campaignUid.isNotBlank())
        val write = lock(campaignUid).writeLock()
        write.lock()
        val previousDepth = activeTurnDepth.get() ?: 0
        activeTurnDepth.set(previousDepth + 1)
        return try { block() } finally {
            if (previousDepth == 0) activeTurnDepth.remove() else activeTurnDepth.set(previousDepth)
            write.unlock()
        }
    }

    fun <T> withRecovery(campaignUid: String, block: () -> T): T {
        require(campaignUid.isNotBlank())
        val lifecycle = lock(campaignUid)
        require((activeTurnDepth.get() ?: 0) == 0) { "RPGOS-G32:GAMEPLAY_CANNOT_INVOKE_ADMIN_AUTHORITY" }
        val write = lifecycle.writeLock()
        write.lock()
        return try { block() } finally { write.unlock() }
    }
}
