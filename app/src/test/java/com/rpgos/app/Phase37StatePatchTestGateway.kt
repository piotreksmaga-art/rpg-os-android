package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Test-only adapter used to assert that generic StatePatch remains fail-closed for Phase-37 tables. */
internal object GenericStatePatchGateway {
    fun apply(db: SQLiteDatabase, campaignUid: String, table: String, values: Map<String, Any?>): PatchResult {
        require(campaignUid.isNotBlank())
        val result = StatePatchEngine(db, SourceOfTruthRegistry(db)).apply(
            StatePatch(
                transactionId = "P37-STATEPATCH-FORGE-$campaignUid",
                operations = listOf(PatchOperation("insert", table, emptyMap(), values))
            )
        )
        check(result.success) { result.message }
        return result
    }
}
