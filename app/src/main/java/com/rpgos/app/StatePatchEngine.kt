package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

class StatePatchEngine(
    private val saveDb: SQLiteDatabase,
    private val registry: SourceOfTruthRegistry
) {
    fun apply(patch: StatePatch): PatchResult {
        if (patch.requiresValidation) {
            for (op in patch.operations) {
                if (!registry.canWrite(op.table)) {
                    return PatchResult(false, 0, "Zapis do tabeli '${op.table}' jest zabroniony przez Source of Truth.")
                }
                if (op.op !in setOf("insert", "update", "delete")) {
                    return PatchResult(false, 0, "Nieznana operacja: ${op.op}")
                }
            }
        }

        var applied = 0
        saveDb.beginTransaction()
        return try {
            for (op in patch.operations) {
                when (op.op) {
                    "insert" -> insert(op)
                    "update" -> update(op)
                    "delete" -> delete(op)
                }
                applied++
            }
            saveDb.setTransactionSuccessful()
            PatchResult(true, applied, "StatePatch ${patch.transactionId} zapisany.")
        } catch (e: Exception) {
            PatchResult(false, applied, "Rollback: ${e.message}")
        } finally {
            saveDb.endTransaction()
        }
    }

    private fun insert(op: PatchOperation) {
        val values = ContentValues()
        op.key.forEach { (k, v) -> put(values, k, v) }
        op.values.forEach { (k, v) -> put(values, k, v) }
        val result = saveDb.insertOrThrow(op.table, null, values)
        if (result == -1L) error("INSERT failed: ${op.table}")
    }

    private fun update(op: PatchOperation) {
        require(op.key.isNotEmpty()) { "UPDATE wymaga klucza." }
        val values = ContentValues()
        op.values.forEach { (k, v) -> put(values, k, v) }
        val (where, args) = where(op.key)
        saveDb.update(op.table, values, where, args)
    }

    private fun delete(op: PatchOperation) {
        require(op.key.isNotEmpty()) { "DELETE wymaga klucza." }
        val (where, args) = where(op.key)
        saveDb.delete(op.table, where, args)
    }

    private fun where(key: Map<String, Any?>): Pair<String, Array<String>> {
        val clauses = key.keys.map { "$it=?" }
        val args = key.values.map { it?.toString() ?: "" }.toTypedArray()
        return clauses.joinToString(" AND ") to args
    }

    private fun put(cv: ContentValues, key: String, value: Any?) {
        when (value) {
            null -> cv.putNull(key)
            is Int -> cv.put(key, value)
            is Long -> cv.put(key, value)
            is Float -> cv.put(key, value)
            is Double -> cv.put(key, value)
            is Boolean -> cv.put(key, if (value) 1 else 0)
            is ByteArray -> cv.put(key, value)
            else -> cv.put(key, value.toString())
        }
    }
}
