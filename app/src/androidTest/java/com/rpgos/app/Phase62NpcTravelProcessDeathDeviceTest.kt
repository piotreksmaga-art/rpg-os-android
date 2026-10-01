package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host CI executes [seedPendingTravel], force-stops the target package, then invokes
 * [resumePendingTravelAfterProcessDeath] in a new instrumentation process.
 */
@RunWith(AndroidJUnit4::class)
class Phase62NpcTravelProcessDeathDeviceTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val campaign="DEVICE-R1"
    private val actor=DomainRef("NPC","DEVICE-R1-NPC")
    private val command="CMD:DEVICE-R1-TRAVEL"
    private val checkpointDir get()=File(context.noBackupFilesDir,"r1-process-death-checkpoint")
    private val dbFile get()=File(context.noBackupFilesDir,"r1-process-death.db")
    private val scope=TemporalScope(campaign,"DEVICE-HISTORY",0,phase60Hash("DEVICE-R1-STATE"))

    private fun pending()=NpcPendingAction(
        actor=actor,
        planUid="DEVICE-R1-PLAN",
        optionUid="DEVICE-R1-OPTION",
        startedAt=WorldTimeTick(0),
        due=WorldTimeTick(60_000),
        timingRuleUid=NpcTravelMechanics.TIMING_RULE,
        timingRuleVersion=1
    )

    @Test fun seedPendingTravel() {
        checkpointDir.deleteRecursively();dbFile.delete()
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db->
            db.execSQL("CREATE TABLE rpgos_schema_migrations(migration_id TEXT PRIMARY KEY,applied_at INTEGER,notes TEXT)")
            db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
            Phase50MechanicalSchema.ensureReady(db)
            MechanicalActorStateStore(db,campaign).materializeIfMissing(MechanicalActorSeed(
                actor,MechanicalActorKind.NPC,"DEVICE-TEMPLATE","DEVICE-SEED","DEVICE-PROVENANCE",
                mapOf("POWER" to 10),
                listOf(MechanicalResource("HEALTH",100,100),MechanicalResource("STAMINA",20,20)),
                setOf("TRAVEL")
            ))
            db.execSQL("INSERT INTO entity_positions VALUES(?,?,?,?,0,0)",arrayOf<Any?>(actor.uid,"ORIGIN",0.0,0.0))
            assertEquals(DomainRef("LOCATION","ORIGIN"),MechanicalActorStateStore(db,campaign).actor(actor)!!.locationRef)
        }

        val action=TimedActionNode(
            uid="DEVICE-R1-WINDOW",
            ownerUid=NpcActionProcess.OWNER,
            timing=AcceptedActionTiming(ActionDuration(60_000),NpcTravelMechanics.TIMING_RULE,1)
        )
        val schedule=Phase60ActionPlanner.schedule(WorldTimeTick(0),listOf(action))
        val row=pending()
        val owner=TemporalOwnerState(NpcActionProcess.OWNER,1,NpcActionProcess.encode(listOf(row)))
        val deadline=WorldProcessDeadline(row.deadlineUid,NpcActionProcess.OWNER,row.due)
        val checkpoint=TemporalExecutionCheckpoint(
            scope=scope,
            commandUid=command,
            startedAt=WorldTimeTick(0),
            reached=WorldTimeTick(0),
            schedule=schedule,
            deadlines=listOf(deadline),
            ownerStates=mapOf(NpcActionProcess.OWNER to owner),
            initialDeadlines=listOf(deadline),
            initialOwnerStates=mapOf(NpcActionProcess.OWNER to owner)
        )
        FileTemporalCheckpointStore(checkpointDir).save(checkpoint)
        val loaded=requireNotNull(FileTemporalCheckpointStore(checkpointDir).load(campaign,command))
        assertEquals(row,NpcActionProcess.decode(loaded.ownerStates[NpcActionProcess.OWNER]).single())
    }

    @Test fun resumePendingTravelAfterProcessDeath() {
        val checkpoint=requireNotNull(FileTemporalCheckpointStore(checkpointDir).load(campaign,command))
        assertEquals(scope,checkpoint.scope)
        val row=NpcActionProcess.decode(checkpoint.ownerStates[NpcActionProcess.OWNER]).single()
        assertEquals(pending(),row)
        assertEquals(WorldTimeTick(60_000),row.due)

        SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { db->
            val store=MechanicalActorStateStore(db,campaign)
            // Process death cannot advance world position or charge resources.
            assertEquals(DomainRef("LOCATION","ORIGIN"),store.actor(actor)!!.locationRef)
            assertEquals(20L,store.actor(actor)!!.resources.single{it.resourceUid=="STAMINA"}.current)

            db.beginTransaction()
            try {
                store.applySpatial(
                    TurnTransactionIdentity(campaign,"TURN:DEVICE-R1","CMD:DEVICE-R1-COMMIT","TX:DEVICE-R1"),
                    "DEVICE-R1-ARRIVAL",
                    SpatialChange(actor,0,0,DomainRef("LOCATION","DESTINATION")),
                    1
                )
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            assertEquals(DomainRef("LOCATION","DESTINATION"),store.actor(actor)!!.locationRef)
        }
        FileTemporalCheckpointStore(checkpointDir).remove(campaign,command)
        dbFile.delete()
    }
}
