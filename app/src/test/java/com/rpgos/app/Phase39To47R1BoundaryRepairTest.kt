package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase39To47R1BoundaryRepairTest {

    @Test fun r1B01A_playerCanReadOnlyOwnLegalHistoricalAuthorityView(){
        SQLiteDatabase.create(null).use{db->
            val player=VisibilityAudienceFactory.player("C")
            putBinding(db,"C",player.principal!!,"SELF",5)
            val result=temporal(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),player.principal!!,10)
            assertTrue(result is TemporalResult.Value)
            val records=(result as TemporalResult.Value).records
            assertEquals(listOf("SELF"),records.map{it.recordUid})
            assertEquals("ROLE",records.single().values["kind_uid"])
        }
    }

    @Test fun r1B01B_playerCannotInspectAnotherPrincipalsAuthorityHistory(){
        SQLiteDatabase.create(null).use{db->
            val player=VisibilityAudienceFactory.player("C")
            val other=VisibilityPrincipalRef("ENTITY","B")
            putBinding(db,"C",other,"OTHER",5)
            val result=temporal(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),other,10)
            assertTrue(result is TemporalResult.Denied || result is TemporalResult.NotDisclosed)
        }
    }

    @Test fun r1B01C_worldActorCannotInspectAnotherPrincipalsAuthorityHistory(){
        SQLiteDatabase.create(null).use{db->
            val actor=AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"))
            val other=VisibilityPrincipalRef("ENTITY","B")
            putBinding(db,"C",other,"OTHER",5)
            val result=temporal(db,"C",actor,PurposeContext("C",VisibilityPurposeKinds.WORLD_ACTOR_REASONING),other,10)
            assertTrue(result is TemporalResult.Denied || result is TemporalResult.NotDisclosed)
        }
    }

    @Test fun r1B01D_forgedDiagnosticDescriptorDoesNotAuthorizeHistory(){
        SQLiteDatabase.create(null).use{db->
            val target=VisibilityPrincipalRef("ENTITY","B")
            putBinding(db,"C",target,"OTHER",5)
            val forged=VisibilityAudienceFactory.diagnostic("C")
            val result=temporal(db,"C",forged,PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),target,10)
            assertTrue(result is TemporalResult.Denied || result is TemporalResult.NotDisclosed)
        }
    }

    @Test fun r1B01E_runtimeIssuedTrustedDiagnosticCanInspectHistory(){
        SQLiteDatabase.create(null).use{db->
            val target=VisibilityPrincipalRef("ENTITY","B")
            putBinding(db,"C",target,"OTHER",5)
            val fixture=Phase38TrustedTestAuthority.diagnostic("C")
            val reads=ProtectedCampaignReadRepository.borrowedTrusted(db,"C",{null},fixture.trusted)
            val result=temporalWithReads(reads,fixture.audience,PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),target,10)
            assertTrue(result is TemporalResult.Value)
            assertEquals("OTHER",(result as TemporalResult.Value).records.single().recordUid)
        }
    }

    @Test fun r1B01F_crossCampaignHistoryFailsClosed(){
        SQLiteDatabase.create(null).use{db->
            val target=VisibilityPrincipalRef(AudienceKinds.PLAYER,"HUMAN_PLAYER")
            putBinding(db,"C1",target,"SELF",5)
            val audience=VisibilityAudienceFactory.player("C2")
            val reads=ProtectedCampaignReadRepository.borrowed(db,"C1"){null}
            val result=temporalWithReads(reads,audience,PurposeContext("C2",VisibilityPurposeKinds.PLAYER_UI),target,10,"C2")
            assertFalse(result is TemporalResult.Value)
            assertTrue(result is TemporalResult.Corruption || result is TemporalResult.Denied || result is TemporalResult.NotDisclosed)
        }
    }

    @Test fun r1B01G_missingHistoricalRecordNeverFabricatesCurrentState(){
        SQLiteDatabase.create(null).use{db->
            val player=VisibilityAudienceFactory.player("C")
            putBinding(db,"C",player.principal!!,"FUTURE",10)
            val result=temporal(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),player.principal!!,5)
            assertTrue(result is TemporalResult.NoData)
        }
    }

    @Test fun r1B01H_accessHistoryReadCreatesNoPhase37Acquisition(){
        SQLiteDatabase.create(null).use{db->
            Phase37KnowledgeSchema.ensureReady(db)
            val player=VisibilityAudienceFactory.player("C")
            putBinding(db,"C",player.principal!!,"SELF",5)
            fun count()=db.rawQuery("SELECT COUNT(*) FROM ${Phase37KnowledgeSchema.ACQUISITIONS}",null).use{c->c.moveToFirst();c.getLong(0)}
            val before=count()
            val result=temporal(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),player.principal!!,10)
            assertTrue(result is TemporalResult.Value)
            assertEquals(before,count())
        }
    }

    @Test fun r1B02A_legalPublicSafeCausalRelationIsVisible(){
        graphDb().use{db->
            event(db,"E1",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"S1")
            event(db,"E2",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"S2")
            relation(db,"R1","E1","E2",1)
            val player=VisibilityAudienceFactory.player("C")
            grantRelation(db,"C",player.principal!!,"R1",2)
            val value=graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"E1") as StructuredRetrievalResult.Value
            val row=value.records.single()
            assertEquals("E1",row.values["source_event_uid"])
            assertEquals("E2",row.values["target_event_uid"])
            assertEquals(CausalRelationKinds.CAUSES,row.values["relation_kind_uid"])
        }
    }

    @Test fun r1B02B_hiddenEndpointIdentityIsNotLeaked(){
        graphDb().use{db->
            event(db,"E1",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"PUBLIC")
            event(db,"E2",VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,"SECRET")
            relation(db,"R1","E1","E2",1)
            val player=VisibilityAudienceFactory.player("C")
            grantRelation(db,"C",player.principal!!,"R1",2)
            val row=((graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"E1") as StructuredRetrievalResult.Value).records.single())
            assertEquals("DENIED",row.values["target_disclosure_state"])
            assertFalse(row.values.containsKey("target_event_uid"))
            assertFalse(row.values.containsKey("target_subject_uid"))
            assertFalse(row.values.toString().contains("SECRET"))
        }
    }

    @Test fun r1B02C_visibleRelationDoesNotPromoteHiddenEndpointDisclosure(){
        graphDb().use{db->
            event(db,"E1",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"PUBLIC")
            event(db,"E2",VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,"OBJECTIVE-HIDDEN")
            relation(db,"R1","E1","E2",1)
            val player=VisibilityAudienceFactory.player("C")
            grantRelation(db,"C",player.principal!!,"R1",2)
            val row=(graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"E1") as StructuredRetrievalResult.Value).records.single()
            assertEquals("R1",row.recordUid)
            assertEquals(CausalRelationKinds.CAUSES,row.values["relation_kind_uid"])
            assertEquals("DENIED",row.values["target_disclosure_state"])
            assertNull(row.values["target_event_uid"])
        }
    }

    @Test fun r1B02D_playerCannotTraverseInternalCausalMetadataWithoutGrant(){
        graphDb().use{db->
            event(db,"E1",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"S1")
            event(db,"E2",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"S2")
            relation(db,"R1","E1","E2",1)
            val player=VisibilityAudienceFactory.player("C")
            assertTrue(graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"E1") is StructuredRetrievalResult.Denied)
        }
    }

    @Test fun r1B02E_worldActorCannotReceiveHiddenObjectiveEndpointIdentity(){
        graphDb().use{db->
            event(db,"E1",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"OBSERVED")
            event(db,"E2",VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,"OBJECTIVE")
            relation(db,"R1","E1","E2",1)
            val actor=AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"))
            grantRelation(db,"C",actor.principal!!,"R1",2)
            val row=(graph(db,"C",actor,PurposeContext("C",VisibilityPurposeKinds.WORLD_ACTOR_REASONING),"E1") as StructuredRetrievalResult.Value).records.single()
            assertEquals("DENIED",row.values["target_disclosure_state"])
            assertFalse(row.values.containsKey("target_event_uid"))
            assertFalse(row.values.toString().contains("OBJECTIVE"))
        }
    }

    @Test fun r1B02F_trustedDiagnosticCanInspectProtectedGraphProjection(){
        graphDb().use{db->
            event(db,"E1",VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,"SECRET-A")
            event(db,"E2",VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,"SECRET-B")
            relation(db,"R1","E1","E2",1)
            val fixture=Phase38TrustedTestAuthority.diagnostic("C")
            val result=graph(db,"C",fixture.audience,PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),"E1",trusted=fixture.trusted)
            val row=(result as StructuredRetrievalResult.Value).records.single()
            assertEquals("E1",row.values["source_event_uid"])
            assertEquals("E2",row.values["target_event_uid"])
            assertEquals("SECRET-A",row.values["source_subject_uid"])
        }
    }

    @Test fun r1B02G_forgedDiagnosticDescriptorCannotInspectGraph(){
        graphDb().use{db->
            event(db,"E1",VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,"SECRET-A")
            event(db,"E2",VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,"SECRET-B")
            relation(db,"R1","E1","E2",1)
            val forged=VisibilityAudienceFactory.diagnostic("C")
            assertTrue(graph(db,"C",forged,PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),"E1") is StructuredRetrievalResult.Denied)
        }
    }

    @Test fun r1B02H_crossCampaignGraphRequestFailsClosed(){
        graphDb().use{db->
            event(db,"E1",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"S1")
            event(db,"E2",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"S2")
            relation(db,"R1","E1","E2",1)
            val foreign=VisibilityAudienceFactory.player("C2")
            val failure=runCatching{graph(db,"C",foreign,PurposeContext("C2",VisibilityPurposeKinds.PLAYER_UI),"E1",requestCampaign="C2")}.exceptionOrNull()
            assertTrue(failure is VisibilityAuthorityFailure.CrossCampaign)
        }
    }

    @Test fun r1B02I_sameTextUidDifferentSubjectKindsStayTyped(){
        graphDb().use{db->
            event(db,"E1",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"SAME")
            event(db,"E2",VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,"SAME")
            relation(db,"R1","E1","E2",1)
            val player=VisibilityAudienceFactory.player("C")
            grantRelation(db,"C",player.principal!!,"R1",2)
            val row=(graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"E1") as StructuredRetrievalResult.Value).records.single()
            assertEquals("SAME",row.values["source_subject_uid"])
            assertEquals("SAME",row.values["target_subject_uid"])
            assertEquals(VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,row.values["source_subject_kind_uid"])
            assertEquals(VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,row.values["target_subject_kind_uid"])
        }
    }

    @Test fun r1B02J_ownerTraversalPreservesDepthAndEdgeBounds(){
        graphDb().use{db->
            (1..4).forEach{event(db,"E$it",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"S$it")}
            relation(db,"R1","E1","E2",1);relation(db,"R2","E2","E3",2);relation(db,"R3","E3","E4",3)
            val player=VisibilityAudienceFactory.player("C")
            listOf("R1","R2","R3").forEachIndexed{i,r->grantRelation(db,"C",player.principal!!,r,(i+4).toLong())}
            val depth1=graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"E1",depth=1,limit=10) as StructuredRetrievalResult.Value
            assertEquals(listOf("R1"),depth1.records.map{it.recordUid})
            val edge1=graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"E1",depth=8,limit=1) as StructuredRetrievalResult.Value
            assertEquals(1,edge1.records.size)
        }
    }

    @Test fun r1B02K_cycleRemainsBoundedAndDeterministic(){
        graphDb().use{db->
            event(db,"E1",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"S1")
            event(db,"E2",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"S2")
            relation(db,"R1","E1","E2",1,CausalRelationClass.NARRATIVE,CausalRelationKinds.NARRATIVE_ASSOCIATION)
            relation(db,"R2","E2","E1",2,CausalRelationClass.NARRATIVE,CausalRelationKinds.NARRATIVE_ASSOCIATION)
            val player=VisibilityAudienceFactory.player("C")
            grantRelation(db,"C",player.principal!!,"R1",3);grantRelation(db,"C",player.principal!!,"R2",4)
            val first=(graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"E1",direction="BOTH",depth=8,limit=10) as StructuredRetrievalResult.Value).records.map{it.recordUid}
            val second=(graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"E1",direction="BOTH",depth=8,limit=10) as StructuredRetrievalResult.Value).records.map{it.recordUid}
            assertEquals(listOf("R1","R2"),first)
            assertEquals(first,second)
        }
    }

    @Test fun r1InventoryGuardsNewDirectAuthorityAndGraphBypasses(){
        val accessBypass="class X { val x = AccessAuthorityStore(db, campaign) }"
        val graphBypass="class X { val x = CampaignCausalGraph(db, campaign) }"
        val schemaBypass="class X { val x = CampaignCausalGraphSchema.TABLE }"
        listOf(accessBypass,graphBypass,schemaBypass).forEach{source->
            assertEquals(ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER,VisibilityConsumerInventory.entryPointClassification("app/src/main/java/com/rpgos/app/NewR1Bypass.kt",source))
        }
        val p39="app/src/main/java/com/rpgos/app/Phase39TemporalAndPhase40Scheduler.kt"
        val p42="app/src/main/java/com/rpgos/app/Phase41StructuredAndPhase42GraphRetrieval.kt"
        assertEquals(ProtectedConsumerCapability.PROJECTED_CONSUMER,VisibilityConsumerInventory.contractForSource(p39)?.capability)
        assertEquals(ProtectedConsumerCapability.PROJECTED_CONSUMER,VisibilityConsumerInventory.contractForSource(p42)?.capability)
    }

    private fun temporal(
        db:SQLiteDatabase,campaign:String,audience:AudienceContext,purpose:PurposeContext,target:VisibilityPrincipalRef,atOrder:Long
    ):TemporalResult = temporalWithReads(ProtectedCampaignReadRepository.borrowed(db,campaign){null},audience,purpose,target,atOrder,campaign)

    private fun temporalWithReads(
        reads:ProtectedCampaignReadRepository,audience:AudienceContext,purpose:PurposeContext,target:VisibilityPrincipalRef,atOrder:Long,campaign:String=audience.campaignUid
    ):TemporalResult{
        val engine=TemporalEngine(listOf(TemporalSourceBinding("ACCESS",AccessAuthorityTemporalSource(reads))))
        return engine.query(TemporalQuery(campaign,"ACCESS","VISIBILITY_PRINCIPAL","${target.kindUid}:${target.uid}",atOrder,audience,purpose))
    }

    private fun putBinding(db:SQLiteDatabase,campaign:String,principal:VisibilityPrincipalRef,recordUid:String,order:Long){
        Phase38AccessAuthoritySchema.ensureReady(db)
        val change=AccessAuthorityChange(AccessOperation.UPSERT_BINDING,recordUid,principal.kindUid,principal.uid,AccessBindingKind.ROLE.name,"ROLE",validFromOrder=order)
        applyAccess(db,campaign,change,order)
    }

    private fun grantRelation(db:SQLiteDatabase,campaign:String,principal:VisibilityPrincipalRef,relationUid:String,order:Long){
        Phase38AccessAuthoritySchema.ensureReady(db)
        val change=AccessAuthorityChange(
            AccessOperation.GRANT,"GRANT:$relationUid:${principal.kindUid}:${principal.uid}",principal.kindUid,principal.uid,
            AccessGrantKind.EXPLICIT.name,ProtectedSubjectAccessRegistry.CAUSAL_RELATION_READ_POLICY_UID,
            VisibilitySubjectKinds.CAUSAL_RELATION,relationUid,0
        )
        applyAccess(db,campaign,change,order)
    }

    private fun applyAccess(db:SQLiteDatabase,campaign:String,change:AccessAuthorityChange,order:Long){
        val identity=TurnTransactionIdentity(campaign,"TURN:${change.recordUid}","CMD:${change.recordUid}","TX:${change.recordUid}")
        db.beginTransaction();try{
            AccessAuthorityStore(db,campaign).apply(identity,"CHANGE:${change.recordUid}",change,order)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }

    private fun graphDb():SQLiteDatabase=SQLiteDatabase.create(null).also{db->
        Phase38AccessAuthoritySchema.ensureReady(db)
        db.execSQL("CREATE TABLE canonical_gameplay_events(campaign_uid TEXT,event_uid TEXT,subject_ref_kind_uid TEXT,subject_ref_uid TEXT)")
        db.execSQL("CREATE TABLE canonical_causal_relations(campaign_uid TEXT,relation_uid TEXT,relation_class_uid TEXT,relation_kind_uid TEXT,source_event_uid TEXT,target_event_uid TEXT,committed_order INTEGER,relation_ordinal INTEGER,semantic_fingerprint TEXT)")
    }

    private fun event(db:SQLiteDatabase,eventUid:String,subjectKind:String,subjectUid:String,campaign:String="C"){
        db.execSQL("INSERT INTO canonical_gameplay_events VALUES(?,?,?,?)",arrayOf(campaign,eventUid,subjectKind,subjectUid))
    }

    private fun relation(
        db:SQLiteDatabase,relationUid:String,source:String,target:String,order:Long,
        relationClass:CausalRelationClass=CausalRelationClass.CAUSAL,kind:String=CausalRelationKinds.CAUSES,campaign:String="C"
    ){
        db.execSQL("INSERT INTO canonical_causal_relations VALUES(?,?,?,?,?,?,?,?,?)",arrayOf(campaign,relationUid,relationClass.name,kind,source,target,order,0,"FP:$relationUid"))
    }

    private fun graph(
        db:SQLiteDatabase,campaign:String,audience:AudienceContext,purpose:PurposeContext,start:String,
        direction:String="OUTGOING",depth:Int=3,limit:Int=100,trusted:TrustedPrincipalContext?=null,requestCampaign:String=campaign
    ):StructuredRetrievalResult{
        val reads=if(trusted==null)ProtectedCampaignReadRepository.borrowed(db,campaign){null}
            else ProtectedCampaignReadRepository.borrowedTrusted(db,campaign,{null},trusted)
        val provider=Phase42CausalQueryProvider(reads,campaign)
        val retriever=StructuredSqlRetriever(listOf(StructuredProviderBinding("GRAPH",setOf("TRAVERSE_CAUSAL"),provider)))
        return retriever.retrieve(StructuredRetrievalRequest(
            "R",requestCampaign,"GRAPH","TRAVERSE_CAUSAL",
            mapOf("start_event_uid" to start,"direction_uid" to direction,"max_depth" to depth.toString()),
            limit,audience,purpose
        ))
    }
}
