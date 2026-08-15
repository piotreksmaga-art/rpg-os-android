package com.rpgos.app

import java.io.File
import java.util.UUID

/**
 * Prepares replacement bytes outside the authority gate and exposes only the final live-target
 * transition while holding the canonical package write side. Readers therefore observe either the
 * previous complete package or the next complete package, never a partial extraction.
 */
internal object CanonicalPackageReplacement {
    fun prepareCopy(source: File, target: File): File {
        require(source.isDirectory) { "Prepared package source is not a directory." }
        target.parentFile?.mkdirs()
        val prepared = File(target.parentFile, ".${target.name}.prepared-${UUID.randomUUID()}")
        prepared.deleteRecursively()
        require(source.copyRecursively(prepared, overwrite = true)) { "Cannot prepare package replacement." }
        return prepared
    }

    fun activatePrepared(prepared: File, target: File) {
        require(prepared.isDirectory) { "Prepared package replacement is missing." }
        CanonicalPackageAuthorityGate.mutate {
            activatePreparedUnderGate(prepared, target)
        }
    }

    fun activatePreparedUnderGate(prepared: File, target: File) {
        target.parentFile?.mkdirs()
        val backup = File(target.parentFile, ".${target.name}.rollback-${UUID.randomUUID()}")
        backup.deleteRecursively()
        var oldMoved = false
        try {
            if (target.exists()) {
                require(target.renameTo(backup)) { "PACKAGE_REPLACEMENT_BACKUP_FAILED" }
                oldMoved = true
            }
            require(prepared.renameTo(target)) { "PACKAGE_REPLACEMENT_ACTIVATION_FAILED" }
            if (backup.exists() && !backup.deleteRecursively()) {
                // A stale backup is safe: canonical target is already complete. Cleanup can be retried later.
            }
        } catch (activationFailure: Throwable) {
            if (target.exists()) target.deleteRecursively()
            if (oldMoved && backup.exists()) {
                val restored = runCatching { backup.renameTo(target) }.getOrDefault(false)
                if (!restored) {
                    throw IllegalStateException("PACKAGE_REPLACEMENT_ROLLBACK_FAILED", activationFailure)
                }
            }
            throw activationFailure
        } finally {
            if (prepared.exists()) prepared.deleteRecursively()
        }
    }
}
