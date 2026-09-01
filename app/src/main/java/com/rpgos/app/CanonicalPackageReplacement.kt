package com.rpgos.app

import java.io.File
import java.util.UUID

internal interface CanonicalPackageFileOps {
    fun rename(source: File, target: File): Boolean
    fun deleteRecursively(target: File): Boolean
}

private object RealCanonicalPackageFileOps : CanonicalPackageFileOps {
    override fun rename(source: File, target: File): Boolean {
        // Android's rename is atomic inside one package root. A just-closed SQLite verifier can,
        // however, keep a transient file handle alive for a few milliseconds (especially on
        // Windows-hosted validation/emulators). Retry only that same atomic rename; never copy a
        // canonical package into place and never weaken the failure-atomic rollback contract.
        repeat(5) { attempt ->
            if (source.renameTo(target)) return true
            if (attempt < 4) {
                try { Thread.sleep(20L * (attempt + 1)) }
                catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }
    override fun deleteRecursively(target: File): Boolean = target.deleteRecursively()
}

/**
 * Failure-atomic live package transition plus deterministic recovery for interrupted transitions.
 * Rollback/prepared/failed siblings are transient recovery metadata only; canonical authority remains
 * the validated live target selected by CampaignSelectionManager.
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

    /** Bounded discovery: inspect only direct transient siblings in one canonical package root. */
    fun reconcileRoot(root: File, isValidPackage: (File) -> Boolean) {
        CanonicalPackageAuthorityGate.mutate {
            root.mkdirs()
            val targetNames = root.listFiles().orEmpty().mapNotNull(::targetNameForRecoveryArtifact).toSortedSet()
            targetNames.forEach { targetName ->
                reconcileUnderGate(File(root, targetName), isValidPackage)
            }
        }
    }

    private fun targetNameForRecoveryArtifact(file: File): String? {
        if (!file.name.startsWith(".")) return null
        val body = file.name.substring(1)
        val markers = listOf(".rollback-", ".prepared-", ".failed-")
        val matches = markers.mapNotNull { marker ->
            val index = body.indexOf(marker)
            if (index > 0) body.substring(0, index) else null
        }.distinct()
        return matches.singleOrNull()
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
        val failed = parent.listFiles()
            ?.filter { it.name.startsWith(".${target.name}.failed-") }
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
            cleanup(rollbacks + prepared + failed)
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
            rollbacks.isNotEmpty() || prepared.isNotEmpty() ->
                throw IllegalStateException("PACKAGE_REPLACEMENT_RECOVERY_NO_VALID_PACKAGE")
            else -> {
                cleanup(failed)
                return
            }
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
        cleanup((rollbacks + prepared + failed).filter { it != recoverySource })
    }

    fun activatePrepared(prepared: File, target: File) {
        require(prepared.isDirectory) { "Prepared package replacement is missing." }
        CanonicalPackageAuthorityGate.mutate {
            activatePreparedUnderGate(prepared, target)
        }
    }

    fun activatePrepared(prepared: File, target: File, isValidPackage: (File) -> Boolean) {
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
                // Stale rollback is transient metadata; reconciliation removes it later.
            }
            return result
        } catch (activationFailure: Throwable) {
            var failedNew: File? = null
            if (target.exists()) {
                val failed = File(target.parentFile, ".${target.name}.failed-${UUID.randomUUID()}")
                if (!fileOps.rename(target, failed)) {
                    throw IllegalStateException("PACKAGE_REPLACEMENT_ROLLBACK_FAILED", activationFailure)
                }
                failedNew = failed
            }
            if (oldMoved && backup.exists()) {
                if (!fileOps.rename(backup, target)) {
                    throw IllegalStateException("PACKAGE_REPLACEMENT_ROLLBACK_FAILED", activationFailure)
                }
            }
            if (failedNew != null && failedNew.exists() && !fileOps.deleteRecursively(failedNew)) {
                throw IllegalStateException("PACKAGE_REPLACEMENT_ROLLBACK_CLEANUP_FAILED", activationFailure)
            }
            throw activationFailure
        } finally {
            if (prepared.exists()) fileOps.deleteRecursively(prepared)
        }
    }
}
