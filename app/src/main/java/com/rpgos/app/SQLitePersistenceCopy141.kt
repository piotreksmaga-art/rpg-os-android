package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class WalCheckpointResult141(
    val busy: Int,
    val logFrames: Int,
    val checkpointedFrames: Int
) {
    val complete: Boolean
        get() = busy == 0 && (logFrames < 0 || checkpointedFrames >= logFrames)
}

class WalCheckpointException141(
    val boundary: String,
    val result: WalCheckpointResult141
) : IllegalStateException(
    "GM141_WAL_CHECKPOINT_FAILED[$boundary]:" +
        "busy=${result.busy},log=${result.logFrames},checkpointed=${result.checkpointedFrames}"
)

/**
 * File-copy boundary for a live SQLite campaign database.
 *
 * Copying only campaign.db is safe only after every committed WAL frame has
 * reached the main database file. This helper therefore checkpoints first,
 * fails closed on a busy/incomplete checkpoint, validates the logical source,
 * copies it, and validates the resulting standalone artifact before returning.
 */
object SQLitePersistenceCopy141 {
    fun checkpointAndValidate(
        source: File,
        boundary: String
    ): WalCheckpointResult141 {
        require(source.isFile) { "Plik bazy nie istnieje: ${source.absolutePath}" }
        val normalizedBoundary = boundary.trim().uppercase().ifBlank { "PERSISTENCE_SOURCE" }

        return SQLiteDatabase.openDatabase(
            source.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            val checkpoint = checkpointFull(db)
            if (!checkpoint.complete) {
                throw WalCheckpointException141(normalizedBoundary, checkpoint)
            }
            GameMasterIntegrityGate141(db).requireHealthy(normalizedBoundary)
            checkpoint
        }
    }

    fun copyLiveDatabase(
        source: File,
        target: File,
        sourceBoundary: String,
        artifactBoundary: String
    ): File {
        checkpointAndValidate(source, sourceBoundary)
        target.parentFile?.mkdirs()

        try {
            source.copyTo(target, overwrite = true)
            GameMasterIntegrityGate141.requireHealthyFile(target, artifactBoundary)
            return target
        } catch (t: Throwable) {
            runCatching { if (target.exists()) target.delete() }
            throw t
        }
    }

    fun stageStandaloneDatabase(
        source: File,
        staged: File,
        artifactBoundary: String
    ): File {
        require(source.isFile) { "Plik bazy nie istnieje: ${source.absolutePath}" }
        staged.parentFile?.mkdirs()
        try {
            source.copyTo(staged, overwrite = true)
            GameMasterIntegrityGate141.requireHealthyFile(staged, artifactBoundary)
            return staged
        } catch (t: Throwable) {
            runCatching { if (staged.exists()) staged.delete() }
            throw t
        }
    }

    fun replaceDatabaseWithStaged(staged: File, target: File) {
        require(staged.isFile) { "Brak przygotowanej bazy: ${staged.absolutePath}" }
        target.parentFile?.mkdirs()
        deleteWalSidecars(target)

        try {
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        deleteWalSidecars(target)
    }

    fun deleteWalSidecars(database: File) {
        listOf(
            File(database.absolutePath + "-wal"),
            File(database.absolutePath + "-shm")
        ).forEach { sidecar ->
            if (sidecar.exists()) {
                check(sidecar.delete()) {
                    "Nie można usunąć SQLite sidecar: ${sidecar.absolutePath}"
                }
            }
        }
    }

    private fun checkpointFull(db: SQLiteDatabase): WalCheckpointResult141 {
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            check(cursor.moveToFirst()) { "PRAGMA wal_checkpoint(FULL) nie zwrócił wyniku." }
            return WalCheckpointResult141(
                busy = cursor.getInt(0),
                logFrames = cursor.getInt(1),
                checkpointedFrames = cursor.getInt(2)
            )
        }
    }
}
