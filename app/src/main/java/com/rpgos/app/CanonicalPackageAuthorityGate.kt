package com.rpgos.app

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Process-local coherence boundary for canonical campaign/World Pack authority.
 *
 * Authority observations hold the read side from the selection snapshot through all package
 * content reads that determine campaignUid + World Pack uid/version. Supported live selection
 * changes and package replacement/import paths hold the write side while mutating those inputs.
 *
 * This is synchronization only: it stores no authority and exposes no mutation capability to
 * PlayerDomainEngine.
 */
internal object CanonicalPackageAuthorityGate {
    private val lock = ReentrantReadWriteLock(true)

    fun <T> observe(block: () -> T): T = lock.read(block)

    fun <T> mutate(block: () -> T): T = lock.write(block)
}
