package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PlayerDomainEngineInheritedStateTest {
    @Test fun inheritedWriterStateMustBeRejectedBeforeResolution() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")
            try {
                PlayerResolutionComponentRegistry.of(listOf(InheritedDbComponent(db)))
                fail("inherited writable capability must be rejected")
            } catch (e: PlayerDomainEngineStructuralException) {
                assertEquals("UNSAFE_RESOLUTION_COMPONENT_STATE", e.code)
            }
            val value = db.rawQuery("SELECT value FROM authority_fixture WHERE uid='A'", null).use {
                it.moveToFirst()
                it.getLong(0)
            }
            assertEquals(7L, value)
        } finally {
            db.close()
        }
    }

    private abstract class DbBackedBaseComponent(
        protected val authority: SQLiteDatabase
    ) : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:INHERITED-DB",
        "1"
    )

    private class InheritedDbComponent(
        authority: SQLiteDatabase
    ) : DbBackedBaseComponent(authority) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome {
            authority.execSQL("UPDATE authority_fixture SET value=99 WHERE uid='A'")
            throw AssertionError("unsupported component must never execute")
        }
    }
}
