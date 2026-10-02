package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase61NpcWorldResultsTest {
    @Test fun boundedMultiEffectCriteriaRoundTripWithoutTruncatingRequiredPredicates() {
        val criteria=(1..20).map{NpcWorldResultCriterion(NpcWorldResultKind.CONDITION_REMOVED,DomainRef("NPC","N"),"CONDITION:$it")}
        val value=NpcWorldResultContract(NpcActivityMechanics.OWNER,phase60Hash("MULTI_EFFECT"),criteria)
        assertEquals(value,NpcWorldResultCodec.decode(NpcWorldResultCodec.encode(value)))
        assertThrows(IllegalArgumentException::class.java){value.copy(criteria=(1..33).map{criteria.first().copy(valueUid="C:$it")})}
    }
    @get:Rule val folder=TemporaryFolder()
    private val actor=DomainRef("NPC","N")
    private val contract=NpcActivityContract("TRAIN","LEARN",1,ActionDuration(1000),"EFFORT",
        learning=NpcLearningRule("SKILL","SK","XP",2))
    private fun setup(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        GroupATransactionTestFixtures.setupFinance(db)
        withAdministrativeMutationAuthority(db,"C1") {
            MechanicalActorStateStore(db,"C1").materializeIfMissing(MechanicalActorSeed(actor,MechanicalActorKind.NPC,"T","S","TEST",
                mapOf("DEFENCE" to 10),listOf(MechanicalResource("HEALTH",10,10)),setOf("TRAIN")))
            SkillStore(db,"C1").registerDefinitions("W",listOf(SkillDefinition("SK","W","sk","Skill","GENERAL",provenance="TEST")))
            SkillStore(db,"C1").savePlayerSkill(PlayerSkill("C1","N","SK",10.0,0.0,"XP",provenance="TEST"))
        }
    }
    private fun scope(db:SQLiteDatabase)=TemporalScope("C1",HistoryGenerationStore(db,"C1").current().value,
        TurnTransactionReceiptStore(db).lastValidCommit("C1")?.commitOrder?:0,AuthoritativeStateDigest.compute(db))
    private fun context(db:SQLiteDatabase,b:NpcBrainState,at:Long=0,goal:String?=null):NpcContextResult.Ready {
        val result=NpcWorldResultContract(NpcActivityMechanics.OWNER,contract.fingerprint,
            listOf(NpcWorldResultCriterion(NpcWorldResultKind.SKILL_PROGRESS,actor,"SK",2.0)))
        val option=NpcActionOption("O","TRAIN",actor,AcceptedActionTiming(contract.duration,NpcActivityMechanics.TIMING_RULE,contract.version),goal,
            emptyList(),emptySet(),parameters=mapOf("npc_activity_contract" to contract.fingerprint),
            mechanicsOwnerUid=NpcActivityMechanics.OWNER,mechanicalEffectKindUid="INTERACTION",worldResult=result)
        val reads=object:NpcProjectionReadPort {
            override fun brain(a:AudienceContext,p:PurposeContext,r:DomainRef,h:KnowledgeHolderRef)=ProtectedReadResult.Allow(b,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(a:AudienceContext,p:PurposeContext,h:KnowledgeHolderRef,o:Long,l:Int)=ProtectedReadResult.Allow(emptyList<NpcKnownRecord>(),DisclosureLevel.DISCLOSE_FULL,"SELF")
        }
        val trigger=NpcTrigger("T",NpcTriggerKind.SELF_REFLECTION,WorldTimeTick(at),NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,b.motivations.first().uid))
        return NpcDecisionContextProjector(reads).project(NpcDecisionScope(scope(db),actor,b.revision,WorldTimeTick(at),0,"P"),trigger,
            b.knowledgeHolder,ContextRuntimeProfile("TEST",8192,64,64,512)){_,_->listOf(option)} as NpcContextResult.Ready
    }
    private fun commit(db:SQLiteDatabase,uid:String,brains:List<NpcBrainChange>,effects:List<VerifiedMechanicsCommandEffect> = emptyList()):TurnExecutionResult<TurnCommitAppliedResult> {
        val order=scope(db).baseCommitOrder+1
        val player=CommandActorRef("PLAYER","P")
        val command=PlayerCommand(commandUid=uid,campaignUid="C1",actor=player,commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN:$uid",effects,npcBrains=brains),provenance=CommandProvenance("TEST"),requestedEffectiveOrder=order)
        val refs=setOf(actor,DomainRef("PLAYER","P"),DomainRef("SKILL","SK"),DomainRef("CHARACTER","N"),DomainRef("CAMPAIGN","C1"))
            .map{CampaignScopedDomainRef("C1",it)}.toSet()
        val result=CampaignMutationBoundary.resolveAndAdmit("C1",productionMechanicsPlayerDomainEngine(),command,
            PlayerResolutionContext.createUnboundGeneric("C1",player,refs)) as CampaignMutationAdmission.Accepted
        return TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","T:$uid",uid,"TX:$uid"),result.proposal).commit()
    }
    private fun start(db:SQLiteDatabase):String {
        val initial=NpcBrainOwner.initialize("C1",actor,"S")
        val generation=scope(db).historyGenerationUid
        commit(db,"GEN",listOf(NpcBrainChange("C1",actor,generation,0,null,NpcBrainCodec.encode(initial),NpcBrainRules.GENESIS.uid,1,
            listOf(NpcCauseRef(NpcCauseKind.GENESIS,"P61:GENESIS:${initial.seedFingerprint}")))))
        val projected=context(db,initial)
        val planning=NpcBrainDynamics.considerGoals(projected.context,listOf(NpcGoalCandidate("G",initial.motivations.first().uid,"ignored",emptySet(),executionOptionUid="O")))!!
        commit(db,"GOAL",listOf(planning))
        val ready=context(db,NpcBrainStore(db,"C1").read(actor)!!,goal="G")
        val selected=NpcDecisionEngine().select(ready.context,NpcDecisionProposal("D",ready.context.contextFingerprint,listOf(NpcDecisionCandidate("O"))),ready.context.scope) as NpcDecisionResult.Selected
        val started=NpcBrainDynamics.beginPlan(ready.context,selected,"START",ready.context.options.single().timing)
        commit(db,"START",listOf(started))
        return selected.authorization.decisionUid
    }
    private fun finish(db:SQLiteDatabase,plan:String,withReward:Boolean) {
        val b=NpcBrainStore(db,"C1").read(actor)!!
        val ready=context(db,b,1000,"G")
        val ended=NpcBrainDynamics.finishPlan(ready.context,plan,"FINISH",true)
        val fields=mapOf("track_uid" to "EFFORT","magnitude" to "1","p60_core_duration_ms" to "1000","npc_activity_contract" to contract.fingerprint)+
            NpcLearningApplication.fields("C1",actor,contract,NpcLearningState(10.0,0.0,"XP",1,1))
        val e=VerifiedMechanicsCommandEffect("E","NODE",NpcActivityMechanics.OWNER,"INTERACTION",actor,1,fields,
            "P62:ACTIVITY:${contract.fingerprint}:E","IN","OUT")
        assertTrue(commit(db,"FINISH",listOf(ended),if(withReward)listOf(e) else emptyList()) is TurnExecutionResult.Committed)
    }
    @Test fun planCompletionWithoutRewardDoesNotAchieveWorldGoal()=SQLiteDatabase.create(null).use { db->
        setup(db);val p=start(db);finish(db,p,false)
        val b=NpcBrainStore(db,"C1").read(actor)!!
        assertEquals(NpcPlanLifecycle.COMPLETED,b.plans.single().lifecycle)
        assertEquals(NpcGoalLifecycle.ACTIVE,b.goals.single().lifecycle)
        assertNull(NpcCanonicalResultOwner(db,"C1").reconcile(b,scope(db)))
    }
    @Test fun committedOwnerAndLedgerConfirmGoalExactlyOnceAndRejectForeignOrForgedProof()=SQLiteDatabase.create(null).use { db->
        setup(db);val p=start(db);finish(db,p,true)
        val b=NpcBrainStore(db,"C1").read(actor)!!
        assertNull(NpcCanonicalResultOwner(db,"OTHER").proof(b,b.goals.single(),scope(db).baseCommitOrder))
        val confirmed=NpcCanonicalResultOwner(db,"C1").reconcile(b,scope(db))!!
        val bad=confirmed.copy(causes=listOf(NpcCauseRef(NpcCauseKind.COMMITTED_EVENT,"FORGED")))
        assertTrue(runCatching{commit(db,"BAD",listOf(bad))}.isFailure)
        assertTrue(commit(db,"CONFIRM",listOf(confirmed)) is TurnExecutionResult.Committed)
        val after=NpcBrainStore(db,"C1").read(actor)!!
        assertEquals(NpcGoalLifecycle.ACHIEVED,after.goals.single().lifecycle)
        assertNull(NpcCanonicalResultOwner(db,"C1").reconcile(after,scope(db)))
        assertEquals(10.0,SkillStore(db,"C1").playerSkills("N").single().baseMastery,0.0)
    }

    @Test fun resultConfirmationReplaysAndUndoCanConfirmAgainInNewHistory() {
        val file=File(folder.root,"goals.db");val snapshots=File(folder.root,"snapshots")
        var db=SQLiteDatabase.openOrCreateDatabase(file,null)
        try {
            setup(db);CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.UNDO_BASELINE,true)
            val plan=start(db);finish(db,plan,true)
            val before=NpcBrainStore(db,"C1").read(actor)!!
            val confirmation=NpcCanonicalResultOwner(db,"C1").reconcile(before,scope(db))!!
            assertTrue(commit(db,"CONFIRM",listOf(confirmation)) is TurnExecutionResult.Committed)
            val undo=DestructiveTurnUndoCoordinator(db,"C1",snapshots,file)
            val preview=undo.previewLastTurn();assertTrue(preview.toString(),preview.canConfirm)
            val result=undo.confirm(preview);assertTrue(result.toString(),result is DestructiveUndoResult.Completed)
            db=SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
            val restored=NpcBrainStore(db,"C1").read(actor)!!
            assertEquals(NpcGoalLifecycle.ACTIVE,restored.goals.single().lifecycle)
            assertEquals(2.0,SkillStore(db,"C1").playerSkills("N").single().progressValue!!,0.0)
            assertTrue(commit(db,"ALTERNATE_CONFIRM",listOf(NpcCanonicalResultOwner(db,"C1").reconcile(restored,scope(db))!!)) is TurnExecutionResult.Committed)
        } finally {if(db.isOpen)db.close()}
    }
}
