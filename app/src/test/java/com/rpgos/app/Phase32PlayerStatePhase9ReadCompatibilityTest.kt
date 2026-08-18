package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32PlayerStatePhase9ReadCompatibilityTest {
    @Test
    fun mutationFreePlayerStatePhase9ProjectionMatchesCanonicalPhase9SnapshotShape() {
        SQLiteDatabase.create(null).use { db ->
            CurrentSchema.ensure(db, "C1")
            db.execSQL(
                "INSERT OR REPLACE INTO active_player_ref(campaign_id,player_uid,updated_at) VALUES('C1','P1',1)"
            )
            db.execSQL(
                "INSERT INTO player_origins_v2(campaign_id,character_uid,origin_uid,relationship_kind,entry_version,provenance) VALUES('C1','P1','ORIGIN-G32','PRIMARY',1,'G32')"
            )
            db.execSQL(
                "INSERT INTO player_innate_features(campaign_id,character_uid,feature_uid,acquired_chapter,entry_version,provenance) VALUES('C1','P1','FEATURE-G32',7,1,'G32')"
            )
            db.execSQL(
                "INSERT INTO player_evolution_states(campaign_id,character_uid,path_uid,current_stage_uid,state_version,provenance) VALUES('C1','P1','PATH-G32','STAGE-G32',1,'G32')"
            )
            db.execSQL(
                "INSERT INTO player_evolution_stages(campaign_id,character_uid,stage_uid,attained_via_transition_uid,attained_chapter,entry_version,provenance) VALUES('C1','P1','STAGE-G32','TRANSITION-G32',8,1,'G32')"
            )
            db.execSQL(
                "INSERT INTO player_form_unlocks(campaign_id,character_uid,form_uid,entry_version,provenance) VALUES('C1','P1','FORM-G32',1,'G32')"
            )
            db.execSQL(
                "INSERT INTO player_active_forms(campaign_id,character_uid,form_uid,activated_at,state_version,provenance) VALUES('C1','P1','FORM-G32',9,1,'G32')"
            )

            val expected = Phase9Store(db, "C1").snapshot("P1").toContextMap()
            GameplayRuntimeBootstrap.ensureReady(db, "C1")
            GameplayRuntimeBootstrap.requireReady(db, "C1")

            val state = PlayerStateStore(db, "C1").load()
            assertNotNull(state)
            assertEquals(expected, state!!.persistent["phase9"])
        }
    }
}
