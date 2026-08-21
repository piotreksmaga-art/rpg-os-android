package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase38VisibilityBoundaryTest {
    private lateinit var root: File
    private lateinit var save: SQLiteDatabase
    private lateinit var world: SQLiteDatabase
    private val authority=VisibilityAuthorityService()
    private val campaign="C1"
    private val player get()=VisibilityAudienceFactory.player(campaign)
    private val playerUi get()=PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)

    @Before fun setUp(){
        root=kotlin.io.path.createTempDirectory("p38-").toFile()
        save=SQLiteDatabase.openOrCreateDatabase(File(root,"save.db"),null)
        world=SQLiteDatabase.openOrCreateDatabase(File(root,"world.db"),null)
        createReaderSchema()
    }
    @After fun tearDown(){save.close();world.close();root.deleteRecursively()}

    @Test fun playerCannotReadGmOnlyTruth(){
        val d=authority.decide(req(player,playerUi,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T"))
        assertEquals(DisclosureLevel.DENY,d.level)
    }

    @Test fun worldActorACannotReadActorBPrivateState(){
        val a=AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"))
        val p=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        assertEquals(DisclosureLevel.DENY,authority.decide(req(a,p,VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_MEMORY,"B")).level)
    }

    @Test fun playerAndPlayerCharacterAreStructurallyDistinct(){
        val fixture=Phase38TrustedTestAuthority.playerCharacter(campaign,"PC")
        val pc=fixture.audience
        assertNotEquals(player.audienceKindUid,pc.audienceKindUid)
        val holder=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC",campaign)
        val subject=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC",holder=holder)
        val reasoning=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,reasoning,subject)).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pc,reasoning,subject),fixture.trusted).level)
    }

    @Test fun playerDisclosureDoesNotCreatePcAcquisition(){
        Phase37KnowledgeSchema.ensureReady(save)
        val before=count(Phase37KnowledgeSchema.ACQUISITIONS)
        authority.project(req(player,playerUi,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E")){"public"}
        assertEquals(before,count(Phase37KnowledgeSchema.ACQUISITIONS))
    }

    @Test fun gmInternalAuthorityDoesNotImplyPlayerDisclosure(){
        val gm=AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef("GM","RUNTIME"))
        val gmPurpose=PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
        assertEquals(DisclosureLevel.DENY,authority.decide(req(gm,gmPurpose,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T")).level)
        val trusted=Phase38RuntimeAuthority.privileged(gm,Phase38RuntimeAuthority.PRIV_GM)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(req(gm,gmPurpose,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T"),trusted).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(req(player,playerUi,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T")).level)
    }

    @Test fun missingPublicSummaryNeverFallsBackToGmSummary(){
        save.execSQL("INSERT INTO active_world_events(event_type,status,public_summary,gm_summary,started_day) VALUES('event','active',NULL,'SECRET',1)")
        val rows=WorldReader(world,save).activeEvents(player,playerUi)
        assertEquals(1,rows.size);assertEquals("",rows.single().summary);assertFalse(rows.single().summary.contains("SECRET"))
    }

    @Test fun playerWarsProjectionNeverExposesGmSummary(){
        save.execSQL("INSERT INTO active_world_events(event_type,status,public_summary,gm_summary,started_day) VALUES('war-major','active',NULL,'WAR_SECRET',1)")
        val rows=NpcWorldDashboardReader(world,save).wars(player,playerUi)
        assertEquals(1,rows.size);assertEquals("",rows.single().summary);assertFalse(rows.single().summary.contains("WAR_SECRET"))
    }

    @Test fun playerNpcDetailExcludesAllPrivateActorCollections(){
        world.execSQL("INSERT INTO canon_characters_v2(character_uid,name,sex,status) VALUES('B','Beta','x','active')")
        save.execSQL("INSERT INTO npc_memories_v2(entity_uid,summary,importance,chapter) VALUES('B','MEM_SECRET',1,1)")
        save.execSQL("INSERT INTO npc_beliefs(entity_uid,content_summary,confidence) VALUES('B','BELIEF_SECRET',1)")
        save.execSQL("INSERT INTO npc_schedules(entity_uid,summary,start_day) VALUES('B','SCHEDULE_SECRET',1)")
        save.execSQL("INSERT INTO npc_decisions(entity_uid,action_type,reason_summary,day) VALUES('B','ACT','DECISION_SECRET',1)")
        val d=NpcWorldDashboardReader(world,save).npcDetail("B",player,playerUi)
        assertTrue(d.memories.isEmpty());assertTrue(d.beliefs.isEmpty());assertTrue(d.schedules.isEmpty());assertTrue(d.decisions.isEmpty())
    }

    @Test fun callerConstructedDiagnosticAudienceCannotReceivePrivateProjection(){
        world.execSQL("INSERT INTO canon_characters_v2(character_uid,name,sex,status) VALUES('B','Beta','x','active')")
        save.execSQL("INSERT INTO npc_memories_v2(entity_uid,summary,importance,chapter) VALUES('B','MEM_SECRET',1,1)")
        val a=VisibilityAudienceFactory.diagnostic(campaign);val p=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        assertTrue(NpcWorldDashboardReader(world,save).npcDetail("B",a,p).memories.isEmpty())
    }

    @Test fun deniedProjectionDoesNotExecuteProtectedReadBlock(){
        var touched=false
        val projection=authority.project(req(player,playerUi,VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_MEMORY,"B")){touched=true;"SECRET"}
        assertFalse(touched);assertNull(projection.value);assertEquals(DisclosureLevel.DENY,projection.decision.level)
    }

    @Test fun downstreamReductionLegalAndEscalationRejected(){
        val p=authority.project(req(player,playerUi,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E")){"full"}
        val reduced=p.reduceTo(DisclosureLevel.DISCLOSE_REDACTED){"redacted"}
        assertEquals("redacted",reduced.value)
        assertTrue(runCatching{reduced.reduceTo(DisclosureLevel.DISCLOSE_FULL){it}}.isFailure)
        val envelope=authority.envelope(player,playerUi).reduceTo(DisclosureLevel.DISCLOSE_PARTIAL)
        assertTrue(runCatching{envelope.reduceTo(DisclosureLevel.DISCLOSE_FULL)}.isFailure)
    }

    @Test fun unknownAudienceAndPurposeFailClosed(){
        val ua=AudienceContext(campaign,"FUTURE_UNKNOWN",VisibilityPrincipalRef("X","1"))
        assertEquals(DisclosureLevel.DENY,authority.decide(req(ua,playerUi,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E")).level)
        val up=PurposeContext(campaign,"FUTURE_UNKNOWN_PURPOSE")
        assertEquals(DisclosureLevel.DENY,authority.decide(req(player,up,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E")).level)
    }

    @Test fun malformedAndCrossCampaignBindingsFailClosed(){
        assertTrue(runCatching{VisibilityRequest(player,PurposeContext("C2",VisibilityPurposeKinds.PLAYER_UI),VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E"))}.isFailure)
        assertTrue(runCatching{AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,null)}.isFailure)
        assertTrue(runCatching{AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"),listOf(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,"A","C2")))}.isFailure)
    }

    @Test fun malformedAuthorityEnvelopeFailsClosed(){
        assertTrue(runCatching{VisibilityProjectionEnvelope(campaign,player,playerUi,DisclosureLevel.DISCLOSE_FULL,"FORGED")}.isFailure)
    }

    @Test fun identicalActorUidAcrossCampaignsCannotCrossLeak(){
        val a=AudienceContext("C1",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","SAME"),listOf(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,"SAME","C1")))
        val p=PurposeContext("C1",VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        assertTrue(runCatching{VisibilityRequest(a,p,VisibilitySubjectRef("C2",VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"SAME",holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,"SAME","C2")))}.isFailure)
    }

    @Test fun organizationNonHumanAndCollectiveUseSameGenericAudienceContract(){
        listOf("ORGANIZATION","NON_HUMAN","COLLECTIVE","TECH_OBSERVER").forEach { kind ->
            val a=AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(kind,"A-$kind"))
            assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(req(a,PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING),VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E")).level)
        }
    }

    @Test fun projectionDoesNotRefreshOrMutatePhase37Knowledge(){
        Phase37KnowledgeSchema.ensureReady(save)
        val before=listOf(Phase37KnowledgeSchema.CLAIMS,Phase37KnowledgeSchema.ACQUISITIONS,Phase37KnowledgeSchema.STATES).associateWith(::count)
        authority.project(req(player,playerUi,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E")){"x"}
        val after=before.keys.associateWith(::count)
        assertEquals(before,after)
    }

    @Test fun visualPromptRequiresSceneVisualizationProjectionAndContainsNoHardcodedWorld(){
        val env=authority.envelope(player,PurposeContext(campaign,VisibilityPurposeKinds.SCENE_VISUALIZATION))
        val bundle=emptyBundle(env)
        val prompt=VisualPromptBuilder().buildScenePrompt("look",bundle).lowercase()
        listOf("naruto","shinobi","witcher","bleach").forEach{assertFalse(prompt.contains(it))}
        val wrong=emptyBundle(authority.envelope(player,playerUi))
        assertTrue(runCatching{VisualPromptBuilder().buildScenePrompt("look",wrong)}.isFailure)
    }

    @Test fun cloudSerializationCannotBeBroaderThanLocalProjection(){
        val env=authority.envelope(player,PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION)).reduceTo(DisclosureLevel.DISCLOSE_PARTIAL)
        val bundle=emptyBundle(env).copy(scene=mapOf("public" to "ok"))
        val json=JsonCodec.contextToJson(bundle)
        assertEquals("DISCLOSE_PARTIAL",json.getJSONObject("visibility_envelope").getString("maximum_disclosure"))
        assertEquals("ok",json.getJSONObject("scene").getString("public"))
        assertFalse(json.toString().contains("gm_summary"))
    }

    @Test fun localGameStoreAndContextBuilderSourcesHaveNoPostProjectionTruthExpansion(){
        val local=source("app/src/main/java/com/rpgos/app/LocalGameStore.kt")
        val context=source("app/src/main/java/com/rpgos/app/ContextBuilder.kt")
        assertFalse(local.contains("base.copy(campaignTruth = truth"))
        assertFalse(local.contains("activeForContext(limit = 80)"))
        assertTrue(context.contains("visibility.project"))
        assertFalse(context.contains("catch(_:Throwable){emptyList()}"))
    }

    @Test fun visibilityConsumerInventoryIsRepositoryWideAndFailClosed(){
        VisibilityConsumerInventory.validateUnique()
        val root=repoRoot()
        val productionFiles = sequenceOf(
            File(root,"app/src/main/java").walkTopDown().filter{it.isFile&&it.extension=="kt"},
            File(root,"backend").walkTopDown().filter{it.isFile&&it.extension=="py"}
        ).flatten()
        val violations=productionFiles.mapNotNull{f->
            val path=f.relativeTo(root).invariantSeparatorsPath
            val sourceText=f.readText()
            if(!VisibilityConsumerInventory.looksProtected(sourceText)) null
            else runCatching{VisibilityConsumerInventory.requireClassifiedIfProtected(path,sourceText)}.exceptionOrNull()?.let{path}
        }.toList()
        assertTrue("unclassified/forbidden protected consumers: $violations",violations.isEmpty())
        val gatewayPath="app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt"
        val gatewaySource=source(gatewayPath)
        assertEquals(ProtectedEntryPointClassification.TRUSTED_GATEWAY,VisibilityConsumerInventory.entryPointClassification(gatewayPath,gatewaySource))
        val direct="class X { val x = CampaignTruthStore(db, c) }"
        assertEquals(ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER,VisibilityConsumerInventory.entryPointClassification("app/src/main/java/com/rpgos/app/NewHiddenConsumer.kt",direct))
        assertTrue(runCatching{VisibilityConsumerInventory.requireClassifiedIfProtected("app/src/main/java/com/rpgos/app/NewHiddenConsumer.kt",direct)}.isFailure)
        val gatewayWithUnrelatedDirectRead=gatewaySource+"\nfun unrelatedBypass() = CampaignTruthStore(db, c)\n"
        assertEquals(ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER,VisibilityConsumerInventory.entryPointClassification(gatewayPath,gatewayWithUnrelatedDirectRead))
        assertTrue(runCatching{VisibilityConsumerInventory.requireClassifiedIfProtected(gatewayPath,gatewayWithUnrelatedDirectRead)}.isFailure)
    }

    @Test fun contextBuilderUsesProtectedGatewayWithoutDirectProtectedEntryPoints(){
        val contextPath="app/src/main/java/com/rpgos/app/ContextBuilder.kt"
        val contextSource=source(contextPath)
        assertEquals(ProtectedConsumerCapability.PROJECTED_CONSUMER,VisibilityConsumerInventory.contractForSource(contextPath)?.capability)
        assertFalse(VisibilityConsumerInventory.hasForbiddenDirectProtectedEntryPoint(contextSource))
        assertEquals(ProtectedEntryPointClassification.PROJECTED_CONSUMER,VisibilityConsumerInventory.entryPointClassification(contextPath,contextSource))
        assertTrue(contextSource.contains("ProtectedCampaignReadRepository.borrowed"))
        assertTrue(contextSource.contains("campaign_truth_state"))
        assertTrue(contextSource.contains("player_state_state"))

        val gatewayPath="app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt"
        assertEquals(ProtectedEntryPointClassification.TRUSTED_GATEWAY,VisibilityConsumerInventory.entryPointClassification(gatewayPath,source(gatewayPath)))
        val ordinaryDirect="class NormalProjectedConsumer { val x = CampaignTruthStore(db, campaign) }"
        assertEquals(ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER,VisibilityConsumerInventory.entryPointClassification("app/src/main/java/com/rpgos/app/NormalProjectedConsumer.kt",ordinaryDirect))
    }

    @Test fun universalCoreContainsNoWorldSpecificSemanticBranches(){
        val files=listOf("app/src/main/java/com/rpgos/app/Phase38Visibility.kt","app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt","app/src/main/java/com/rpgos/app/VisualPromptBuilder.kt")
        val banned=listOf("if Naruto","if Witcher","if Bleach","if ninja","if shinobi","if wizard","if dragon","if sciFi","Naruto-inspired")
        files.forEach{path->val text=source(path);banned.forEach{token->assertFalse("$path contains $token",text.contains(token,true))}}
    }

    @Test fun legacyGmSummaryIsNeverAuthorityForPlayer(){
        assertEquals(DisclosureLevel.DENY,authority.decide(req(player,playerUi,VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,"E")).level)
    }

    private fun req(a:AudienceContext,p:PurposeContext,k:String,u:String)=VisibilityRequest(a,p,VisibilitySubjectRef(a.campaignUid,k,u))
    private fun count(table:String)=save.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getLong(0)}

    private fun emptyBundle(env:VisibilityProjectionEnvelope)=ContextBundle(emptyMap(),emptyMap(),emptyMap(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),visibilityEnvelope=env)

    private fun createReaderSchema(){
        world.execSQL("""CREATE TABLE canon_characters_v2(character_uid TEXT PRIMARY KEY,name TEXT,sex TEXT,clan_uid TEXT,village_uid TEXT,rank_title TEXT,affiliation_summary TEXT,status TEXT)""")
        save.execSQL("CREATE TABLE timeline_events(timeline_uid TEXT PRIMARY KEY,name TEXT)")
        save.execSQL("""CREATE TABLE active_world_events(timeline_uid TEXT,event_type TEXT,status TEXT,public_summary TEXT,gm_summary TEXT,started_day INTEGER)""")
        save.execSQL("CREATE TABLE npc_memories_v2(entity_uid TEXT,summary TEXT,importance INTEGER,chapter INTEGER)")
        save.execSQL("CREATE TABLE npc_beliefs(entity_uid TEXT,content_summary TEXT,confidence REAL)")
        save.execSQL("CREATE TABLE npc_schedules(entity_uid TEXT,summary TEXT,start_day INTEGER)")
        save.execSQL("CREATE TABLE npc_decisions(entity_uid TEXT,action_type TEXT,reason_summary TEXT,day INTEGER)")
        save.execSQL("CREATE TABLE relationships_v2(entity_a_uid TEXT,entity_b_uid TEXT,relationship_type TEXT,relationship_score REAL)")
        save.execSQL("CREATE TABLE country_economies(country_uid TEXT,treasury TEXT,prosperity TEXT,stability TEXT)")
    }

    private fun repoRoot():File{
        var f=File(System.getProperty("user.dir")).canonicalFile
        repeat(6){if(File(f,"app/src/main/java").isDirectory)return f;f=f.parentFile?:return@repeat}
        error("repo root not found from ${System.getProperty("user.dir")}")
    }
    private fun source(path:String)=File(repoRoot(),path).readText()
}
