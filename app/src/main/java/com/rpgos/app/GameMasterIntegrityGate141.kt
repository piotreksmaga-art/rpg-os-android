package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File

data class GameMasterIntegrityGateReport141(
    val ok: Boolean,
    val issues: List<GameMasterIntegrityIssue141>
)

/**
 * Read-only integrity gate for durable persistence boundaries.
 *
 * It never repairs campaign data. A failed gate must be surfaced explicitly
 * before creating a checkpoint/backup or before replacing a live database.
 */
class GameMasterIntegrityGate141(private val db: SQLiteDatabase) {
    fun check(): GameMasterIntegrityGateReport141 {
        val issues = buildList {
            addAll(GameMasterIntegrity141(db).check().issues)
            addAll(KnowledgeTransmissionIntegrity141(db).check().issues)
            addAll(NpcKnowledgeIntegrity141(db).check().issues)
            addAll(TruthSupersessionIntegrity141(db).check().issues)
        }
        return GameMasterIntegrityGateReport141(
            ok = issues.none { it.severity == ValidationSeverity.ERROR },
            issues = issues
        )
    }

    fun requireHealthy(boundary: String) {
        val normalizedBoundary = boundary.trim().uppercase().ifBlank { "PERSISTENCE" }
        val report = check()
        if (report.ok) return

        val codes = report.issues
            .asSequence()
            .filter { it.severity == ValidationSeverity.ERROR }
            .map { it.code }
            .distinct()
            .sorted()
            .joinToString(",")

        throw IllegalStateException(
            "GM141_INTEGRITY_GATE_FAILED[$normalizedBoundary]:$codes"
        )
    }

    companion object {
        fun checkFile(file: File): GameMasterIntegrityGateReport141 {
            require(file.isFile) { "Plik bazy nie istnieje: ${file.absolutePath}" }
            return SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                GameMasterIntegrityGate141(db).check()
            }
        }

        fun requireHealthyFile(file: File, boundary: String) {
            require(file.isFile) { "Plik bazy nie istnieje: ${file.absolutePath}" }
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                GameMasterIntegrityGate141(db).requireHealthy(boundary)
            }
        }
    }
}
