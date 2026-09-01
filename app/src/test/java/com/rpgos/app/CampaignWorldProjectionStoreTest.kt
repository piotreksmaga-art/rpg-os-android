package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CampaignWorldProjectionStoreTest {
    private val provenance=Provenance(ProvenanceSourceType.PLAYER_ACTION,"TEST",createdTurn=1)
    private val shape=WorldReferenceShape(
        WorldReferenceShapeKind.CATEGORY,WorldElementBaseKind.PLACE,"CRAFTING_VENUE",setOf("CRAFTING"),"SETTLEMENT_FACILITY"
    )

    @Test fun projectionIsTransactionallyDerivedAndInvisibleUntilExplicitAudienceFact(){
        SQLiteDatabase.create(null).use{db->
            MigrationManager().ensureV2(db);CampaignWorldProjectionSchema.ensureReady(db,"C")
            val truth=CampaignTruthStore(db,"C")
            fact(truth,"K",CampaignWorldFacts.KIND,"PLACE",1)
            fact(truth,"N",CampaignWorldFacts.NAME,"lokalny warsztat",2)
            fact(truth,"C",CampaignWorldFacts.CATEGORY,"CRAFTING_VENUE",3)
            fact(truth,"P",CampaignWorldFacts.PARENT,"VILLAGE",4)
            fact(truth,"F",CampaignWorldFacts.AFFORDANCE,"CRAFTING",5)
            fact(truth,"T",CampaignWorldFacts.TOPOLOGY,"SETTLEMENT_FACILITY",6)
            fact(truth,"S",CampaignWorldFacts.SOURCE_CLASSIFICATION,"GENERATED_PLAUSIBLE",7)
            assertTrue(CampaignWorldProjectionStore(db,"C").searchPlayerVisible("warsztat",shape).isEmpty())

            fact(truth,"A",CampaignWorldFacts.AUDIENCE_SCOPE,CampaignWorldAudience.PLAYER_VISIBLE,8)
            val projected=CampaignWorldProjectionStore(db,"C").searchPlayerVisible("lokalny warsztat",shape).single()
            assertEquals("WORLD-1",projected.element.uid)
            assertEquals("VILLAGE",projected.parentAnchorUid)
            assertEquals(setOf("CRAFTING"),projected.affordanceUids)
            assertEquals(WorldEvidenceClassification.GENERATED_PLAUSIBLE,projected.sourceClassification)
            assertFalse(SourceOfTruthRegistry(db).canWrite(CampaignWorldProjectionSchema.TABLE))
        }
    }

    @Test fun migrationRebuildsExistingFactsAndKeepsCampaignsIsolated(){
        SQLiteDatabase.create(null).use{db->
            MigrationManager().ensureV2(db)
            listOf("C","D").forEach{campaign->
                val truth=CampaignTruthStore(db,campaign)
                fact(truth,"$campaign-K",CampaignWorldFacts.KIND,"PLACE",1)
                fact(truth,"$campaign-N",CampaignWorldFacts.NAME,"warsztat $campaign",2)
                fact(truth,"$campaign-C",CampaignWorldFacts.CATEGORY,"CRAFTING_VENUE",3)
                fact(truth,"$campaign-F",CampaignWorldFacts.AFFORDANCE,"CRAFTING",4)
                fact(truth,"$campaign-T",CampaignWorldFacts.TOPOLOGY,"SETTLEMENT_FACILITY",5)
                fact(truth,"$campaign-S",CampaignWorldFacts.SOURCE_CLASSIFICATION,"CAMPAIGN_FACT",6)
                fact(truth,"$campaign-A",CampaignWorldFacts.AUDIENCE_SCOPE,CampaignWorldAudience.PLAYER_VISIBLE,7)
            }
            CampaignWorldProjectionSchema.ensureReady(db,"C")
            assertEquals(listOf("WORLD-1"),CampaignWorldProjectionStore(db,"C").searchPlayerVisible("warsztat C",shape).map{it.element.uid})
            assertTrue(CampaignWorldProjectionStore(db,"C").searchPlayerVisible("warsztat D",shape).all{it.displayName=="warsztat C"})
            CampaignWorldProjectionStore(db,"D").rebuild()
            assertTrue(CampaignWorldProjectionStore(db,"D").searchPlayerVisible("warsztat D",shape).all{it.displayName=="warsztat D"})
        }
    }

    @Test fun playerVisibleLookupResolvesInflectedSurfaceWithoutCrossingAudienceBoundary(){
        SQLiteDatabase.create(null).use{db->
            MigrationManager().ensureV2(db);CampaignWorldProjectionSchema.ensureReady(db,"C")
            val truth=CampaignTruthStore(db,"C")
            fact(truth,"K",CampaignWorldFacts.KIND,"PLACE",1)
            fact(truth,"N",CampaignWorldFacts.NAME,"Poligon",2)
            fact(truth,"C",CampaignWorldFacts.CATEGORY,"GENERIC_PLACE",3)
            fact(truth,"P",CampaignWorldFacts.PARENT,"VIL-KONOHA",4)
            fact(truth,"T",CampaignWorldFacts.TOPOLOGY,"LOCAL_SITE",5)
            fact(truth,"S",CampaignWorldFacts.SOURCE_CLASSIFICATION,"GENERATED_PLAUSIBLE",6)
            fact(truth,"A",CampaignWorldFacts.AUDIENCE_SCOPE,CampaignWorldAudience.PLAYER_VISIBLE,7)
            val unknownPlace=WorldReferenceShape(WorldReferenceShapeKind.UNKNOWN,WorldElementBaseKind.PLACE,null,emptySet(),null)

            assertEquals("WORLD-1",CampaignWorldProjectionStore(db,"C").searchPlayerVisible("poligonu",unknownPlace).single().element.uid)
            assertTrue(CampaignWorldProjectionStore(db,"C").searchPlayerVisible("morza",unknownPlace).isEmpty())
        }
    }

    @Test fun exactVisibleNameWinsOverDifferentProviderAffordanceVocabulary(){
        SQLiteDatabase.create(null).use{db->
            MigrationManager().ensureV2(db);CampaignWorldProjectionSchema.ensureReady(db,"C")
            val truth=CampaignTruthStore(db,"C")
            fact(truth,"K",CampaignWorldFacts.KIND,"OBJECT",1)
            fact(truth,"N",CampaignWorldFacts.NAME,"stojak",2)
            fact(truth,"C",CampaignWorldFacts.CATEGORY,"RACK",3)
            fact(truth,"F",CampaignWorldFacts.AFFORDANCE,"STORAGE",4)
            fact(truth,"T",CampaignWorldFacts.TOPOLOGY,"LOCAL_SITE",5)
            fact(truth,"S",CampaignWorldFacts.SOURCE_CLASSIFICATION,"GENERATED_PLAUSIBLE",6)
            fact(truth,"A",CampaignWorldFacts.AUDIENCE_SCOPE,CampaignWorldAudience.PLAYER_VISIBLE,7)
            val providerShape=WorldReferenceShape(
                WorldReferenceShapeKind.NAMED_INSTANCE,WorldElementBaseKind.OBJECT,"RACK",setOf("MOVABLE","PLACEABLE"),"LOCAL_SITE"
            )

            assertEquals("WORLD-1",CampaignWorldProjectionStore(db,"C").searchPlayerVisible("stojaka",providerShape).single().element.uid)
        }
    }

    @Test fun deterministicTruthUidIsIdempotentOnlyForTheSameCanonicalFact(){
        SQLiteDatabase.create(null).use{db->
            MigrationManager().ensureV2(db);CampaignWorldProjectionSchema.ensureReady(db,"C")
            val store=CampaignTruthStore(db,"C")
            val first=store.record(
                TruthKind.FACT,CampaignWorldFacts.CATEGORY,provenance,
                subjectUid="WORLD-1",objectValue="ACADEMY_TEACHER",truthUid="WORLD-CATEGORY",createdAt=1
            )
            val repeated=store.record(
                TruthKind.FACT,CampaignWorldFacts.CATEGORY,provenance.copy(sourceId="LATER-COMMAND",createdTurn=2),
                subjectUid="WORLD-1",objectValue="ACADEMY_TEACHER",truthUid="WORLD-CATEGORY",createdAt=2
            )

            assertEquals(first,repeated)
            assertEquals(1,store.active(subjectUid="WORLD-1").size)
            val collision=assertThrows(IllegalArgumentException::class.java){
                store.record(
                    TruthKind.FACT,CampaignWorldFacts.CATEGORY,provenance,
                    subjectUid="WORLD-1",objectValue="UNRELATED_ACTOR",truthUid="WORLD-CATEGORY",createdAt=3
                )
            }
            assertTrue(collision.message.orEmpty().contains("RPGOS-TRUTH:UID_COLLISION:WORLD-CATEGORY"))
            assertEquals("ACADEMY_TEACHER",store.active(subjectUid="WORLD-1").single().objectValue)
        }
    }

    @Test fun committedVisibleCampaignActorGetsDeterministicMechanicalStateOnAdministrativePrepare(){
        SQLiteDatabase.create(null).use{db->
            MigrationManager().ensureV2(db);CampaignWorldProjectionSchema.ensureReady(db,"C")
            val truth=CampaignTruthStore(db,"C")
            fact(truth,"K",CampaignWorldFacts.KIND,"ACTOR",1)
            fact(truth,"N",CampaignWorldFacts.NAME,"koleżanka z klasy",2)
            fact(truth,"C",CampaignWorldFacts.CATEGORY,"CLASSMATE",3)
            fact(truth,"P",CampaignWorldFacts.PARENT,"ACADEMY-YARD",4)
            fact(truth,"F",CampaignWorldFacts.AFFORDANCE,"CAN_PARTICIPATE_IN_SPARRING",5)
            fact(truth,"T",CampaignWorldFacts.TOPOLOGY,"LOCAL_SITE",6)
            fact(truth,"S",CampaignWorldFacts.SOURCE_CLASSIFICATION,"GENERATED_PLAUSIBLE",7)
            fact(truth,"A",CampaignWorldFacts.AUDIENCE_SCOPE,CampaignWorldAudience.PLAYER_VISIBLE,8)
            Phase50MechanicalSchema.ensureReady(db)

            WorldActorMechanicalBootstrap.materializeCampaignProjectionActors(db,"C")
            val actor=MechanicalActorStateStore(db,"C").actor(DomainRef("ACTOR","WORLD-1"))
            WorldActorMechanicalBootstrap.materializeCampaignProjectionActors(db,"C")

            assertEquals(MechanicalActorKind.NPC,actor?.kind)
            assertEquals(setOf("ATTACK","DEFEND","STRIKE"),actor?.executableAbilityUids)
            assertEquals(1,db.rawQuery("SELECT count(*) FROM mechanical_actor_states WHERE campaign_id='C' AND entity_uid='WORLD-1'",null).use{it.moveToFirst();it.getInt(0)})
        }
    }

    private fun fact(store:CampaignTruthStore,uid:String,predicate:String,value:String,order:Long)=store.record(
        TruthKind.FACT,predicate,provenance,subjectUid="WORLD-1",objectValue=value,truthUid=uid,createdAt=order
    )
}
