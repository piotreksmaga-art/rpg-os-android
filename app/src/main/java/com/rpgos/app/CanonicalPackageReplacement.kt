package com.rpgos.app

import java.io.File
import java.util.UUID

internal interface CanonicalPackageFileOps {
    fun rename(source: File, target: File): Boolean
    fun deleteRecursively(target: File): Boolean
}

private object RealCanonicalPackageFileOps : CanonicalPackageFileOps {
    override fun rename(source: File, target: File): Boolean = source.renameTo(target)
    override fun deleteRecursively(target: File): Boolean = target.deleteRecursively()
}

/**
 * Failure-atomic live package transition plus deterministic recovery for interrupted transitions.
 * Rollback/prepared siblings are transient recovery metadata only; canonical authority remains the
 * validated live target selected by CampaignSelectionManager.
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

    fun reconcile(target: File, isValidPackage: (File) -> Boolean) {
        CanonicalPackageAuthorityGate.mutate {
            reconcileUnderGate(target, isValidPackage)
        }
    }

    internal fun reconcileUnderGate(
        target: File,
        isValidPackage: (File) -> Boolean,
        fileOps: CanonicalPackageFileOps = RealCanonicalPackageFileOps,
        ignoredPrepared: Set<File> = emptySet()
    ) {
        target.parentFile?.mkdirs()
        val parent = target.parentFile ?: throw IllegalStateException("PACKAGE_REPLACEMENT_RECOVERY_NO_PARENT")
        val rollbacks = parent.listFiles()
            ?.filter { it.name.startsWith(".${target.name}.rollback-") }
            .orEmpty()
        val prepared = parent.listFiles()
            ?.filter { it.name.startsWith(".${target.name}.prepared-") && it !in ignoredPrepared }
            .orEmpty()

        fun valid(file: File): Boolean = file.isDirectory && runCatching { isValidPackage(file) }.getOrDefault(false)
        fun cleanup(files: List<File>) {
            files.forEach { stale ->
                if (stale.exists() && !fileOps.deleteRecursively(stale)) {
                    throw IllegalStateException("PACKAGE_REPLACEMENT_RECOVERY_CLEANUP_FAILED")
                }
            }
        }

        if (target.exists() && valid(target)) {
            cleanup(rollbacks + prepared)
            return
        }

        val validRollbacks = rollbacks.filter(::valid)
        val validPrepared = prepared.filter(::valid)
        if (validRollbacks.size > 1 || (validRollbacks.isEmpty() && validPrepared.size > 1)) {
            throw IllegalStateException("PACKAGE_REPLACEMENT_RECOVERY_AMBIGUOUS")
        }

        val recoverySource = when {
            validRollbacks.size == 1 -> validRollbacks.single()
            validPrepared.size == 1 -> validPrepared.single()
            target.exists() || rollbacks.isNotEmpty() || prepared.isNotEmpty() ->
                throw IllegalStateException("PACKAGE_REPLACEMENT_RECOVERY_NO_VALID_PACKAGE")
            else -> return
        }

        if (target.exists() && !fileOps.deleteRecursively(target)) {
            throw IllegalStateException("PACKAGE_REPLACEMENT_RECOVERY_TARGET_DELETE_FAILED")
        }
        if (!fileOps.rename(recoverySource, target)) {
            throw IllegalStateException("PACKAGE_REPLACEMENT_RECOVERY_RESTORE_FAILED")
        }
        if (!valid(target)) {
            throw IllegalStateException("PACKAGE_REPLACEMENT_RECOVERY_RESTORED_INVALID")
        }
        cleanup((rollbacks + prepared).filter { it != recoverySource })
    }

    fun activatePrepared(prepared: File, target: File) {
        require(prepared.isDirectory) { "Prepared package replacement is missing." }
        CanonicalPackageAuthorityGate.mutate {
            activatePreparedUnderGate(prepared, target)
        }
    }

    fun activatePrepared(
        prepared: File,
        target: File,
        isValidPackage: (File) -> Boolean
    ) {
        require(prepared.isDirectory) { "Prepared package replacement is missing." }
        CanonicalPackageAuthorityGate.mutate {
            reconcileUnderGate(target, isValidPackage, ignoredPrepared = setOf(prepared))
            activatePreparedUnderGate(prepared, target)
        }
    }

    fun <T> activatePrepared(
        prepared: File,
        target: File,
        isValidPackage: (File) -> Boolean,
        afterActivation: () -> T
    ): T {
        require(prepared.isDirectory) { "Prepared package replacement is missing." }
        return CanonicalPackageAuthorityGate.mutate {
            reconcileUnderGate(target, isValidPackage, ignoredPrepared = setOf(prepared))
            activatePreparedUnderGate(prepared, target, RealCanonicalPackageFileOps, afterActivation)
        }
    }

    fun activatePreparedIf(
        prepared: File,
        target: File,
        isValidPackage: (File) -> Boolean,
        shouldActivate: () -> Boolean
    ): Boolean {
        require(prepared.isDirectory) { "Prepared package replacement is missing." }
        return CanonicalPackageAuthorityGate.mutate {
            reconcileUnderGate(target, isValidPackage, ignoredPrepared = setOf(prepared))
            if (!shouldActivate()) {
                if (prepared.exists()) prepared.deleteRecursively()
                false
            } else {
                activatePreparedUnderGate(prepared, target)
                true
            }
        }
    }

    internal fun activatePreparedUnderGate(
        prepared: File,
        target: File,
        fileOps: CanonicalPackageFileOps = RealCanonicalPackageFileOps
    ) {
        activatePreparedUnderGate(prepared, target, fileOps) { Unit }
    }

    internal fun <T> activatePreparedUnderGate(
        prepared: File,
        target: File,
        fileOps: CanonicalPackageFileOps = RealCanonicalPackageFileOps,
        afterActivation: () -> T
    ): T {
        target.parentFile?.mkdirs()
        val backup = File(target.parentFile, ".${target.name}.rollback-${UUID.randomUUID()}")
        if (backup.exists()) fileOps.deleteRecursively(backup)
        var oldMoved = false
        try {
            if (target.exists()) {
                require(fileOps.rename(target, backup)) { "PACKAGE_REPLACEMENT_BACKUP_FAILED" }
                oldMoved = true
            }
            require(fileOps.rename(prepared, target)) { "PACKAGE_REPLACEMENT_ACTIVATION_FAILED" }
            val result = afterActivation()
            if (backup.exists() && !fileOps.deleteRecursively(backup)) {
                // A stale rollback sibling is safe while the canonical target is complete.
                // Startup/reentry reconciliation removes it deterministically.
            }
            return result
        } catch (activationFailure: Throwable) {
            if (target.exists() && !fileOps.deleteRecursively(target)) {
                throw IllegalStateException("PACKAGE_REPLACEMENT_ROLLBACK_FAILED", activationFailure)
            }
            if (oldMoved && backup.exists()) {
                val restored = fileOps.rename(backup, target)
                if (!restored) {
                    throw IllegalStateException("PACKAGE_REPLACEMENT_ROLLBACK_FAILED", activationFailure)
                }
            }
            throw activationFailure
        } finally {
            if (prepared.exists()) fileOps.deleteRecursively(prepared)
        }
    }
}
