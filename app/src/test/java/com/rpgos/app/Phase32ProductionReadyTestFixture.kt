package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

internal object Phase32ProductionReadyTestFixture {
    fun setup(db: SQLiteDatabase, campaignUid: String = "C1", openingBalance: Long = 100L) {
        GroupATransactionTestFixtures.setupFinance(db, campaignUid, openingBalance)
        GameplayRuntimeBootstrap.ensureReady(db, campaignUid)
        GameplayRuntimeBootstrap.requireReady(db, campaignUid)
    }
}
