package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64NpcTargetActivationTest {
    private val temporal=TemporalScope("C1","G1",7,"STATE")
    private val actor=DomainRef("NPC","SENDER")
    private val action="SEND_REGISTERED_REPORT"
    private val genesis=NpcBrainOwner.initialize(temporal.campaignUid,actor,"SEED")
    private val intrinsic=NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,genesis.motivations.first().uid)
    private val brain=genesis.copy(revision=2,goals=listOf(NpcGoal("GOAL",intrinsic.uid,"Report what I believe",
        NpcWeight(7000),NpcGoalLifecycle.ACTIVE,intrinsic)))
    private val body=MechanicalActorView(temporal.campaignUid,actor,MechanicalActorKind.NPC,1,
        MechanicalStateMaterialization.FULL,mapOf("DEFENCE" to 10),listOf(MechanicalResource("HEALTH",10,10)),
        setOf(action),generationProvenanceUid="OWNER_SEED")
    private val fixed=BackgroundProcessDefinition("REGISTERED_REPORT",1,"INFORMATION","REPORT",1000,
        parameters=mapOf(Phase64ProcessActivation.ACTION_KEY to action,"activation_npc" to "true",
            "activation_policy_uid" to "REPORT_POLICY","recipient_kind" to "@ACTOR_KIND","recipient_uid" to "@ACTOR_UID"))
    private val targeted=fixed.copy(parameters=fixed.parameters+mapOf(
        "recipient_kind" to "@TARGET_KIND","recipient_uid" to "@TARGET_UID"))
    private fun record(uid:String,target:DomainRef?,acquisition:String="ACQUISITION:$uid")=NpcKnownRecord(uid,
        KnowledgeEpistemicState.BELIEVED,"A personally projected report.",acquisition,1,
        setOfNotNull(target),temporal.baseCommitOrder)
    private fun options(records:List<NpcKnownRecord>,definition:BackgroundProcessDefinition=targeted)=
        Phase64NpcInitiation.options(brain,records,body,definition)
    private fun context(records:List<NpcKnownRecord>,options:List<NpcActionOption>)=NpcDecisionContextEnvelope(
        NpcDecisionScope(temporal,actor,brain.revision,WorldTimeTick(0),0,"PLAYER"),
        NpcTrigger("SELF",NpcTriggerKind.SELF_REFLECTION,WorldTimeTick(0),intrinsic),brain,records,options,8192)
    private fun projected(records:List<NpcKnownRecord>,definition:BackgroundProcessDefinition):NpcContextResult.Ready {
        val reads=object:NpcProjectionReadPort {
            override fun brain(audience:AudienceContext,purpose:PurposeContext,actor:DomainRef,holder:KnowledgeHolderRef)=
                ProtectedReadResult.Allow(brain,DisclosureLevel.DISCLOSE_FULL,"OWN_BRAIN")
            override fun knowledge(audience:AudienceContext,purpose:PurposeContext,holder:KnowledgeHolderRef,order:Long,limit:Int)=
                ProtectedReadResult.Allow(records,DisclosureLevel.DISCLOSE_FULL,"OWN_RECORDS")
        }
        val envelope=context(records,emptyList())
        return NpcDecisionContextProjector(reads).project(envelope.scope,envelope.trigger,brain.knowledgeHolder,
            ContextRuntimeProfile("TEST",8192,64,64,512)) { current,known->
            Phase64NpcInitiation.options(current,known,body,definition)
        } as NpcContextResult.Ready
    }

    @Test fun fixedRecipePreservesSelfFacadeWithoutInventingASource() {
        val projected=options(emptyList(),fixed)
        assertEquals(1,projected.size)
        assertEquals(actor,projected.single().target)
        assertTrue(projected.single().supportingRecordUids.isEmpty())
        assertTrue(projected.single().parameters.isEmpty())
        assertTrue(projected.single().resourceCosts.isEmpty())
        assertEquals(projected.single(),Phase64NpcInitiation.option(brain,emptyList(),body,fixed))
        assertEquals(setOf(action),body.executableAbilityUids)
    }

    @Test fun targetPlaceholdersRequireAnActualProjectedSubjectIncludingKindOnlyRecipes() {
        assertTrue(options(emptyList()).isEmpty())
        assertNull(Phase64NpcInitiation.option(brain,emptyList(),body,targeted))
        assertTrue(options(listOf(record("NO_SUBJECT",null))).isEmpty())
        val kindOnly=fixed.copy(parameters=fixed.parameters+("recipient_kind" to "@TARGET_KIND"))
        assertTrue(options(emptyList(),kindOnly).isEmpty())
        val known=record("KNOWN",DomainRef("NPC","RECIPIENT"))
        assertEquals(known.subjectRefs.single(),options(listOf(known),kindOnly).single().target)
    }

    @Test fun targetsAreBoundedDeterministicAndGroundedInTheirConcreteRecords() {
        val records=(1..6).map { record("RECORD:$it",DomainRef("NPC","RECIPIENT:$it")) }
        val projected=options(records.reversed())
        assertEquals(4,projected.size)
        assertEquals(projected,options(records))
        assertEquals(records.take(4).map { it.subjectRefs.single() },projected.map { it.target })
        projected.forEach { option->
            val support=records.single { it.uid in option.supportingRecordUids }
            assertTrue(option.target in support.subjectRefs)
            assertTrue(option.parameters.isEmpty())
            assertEquals(action,option.capabilityUid)
            assertEquals("GOAL",option.goalUid)
        }
        assertEquals(4,context(records,projected).options.size)
    }

    @Test fun multipleSubjectsAndDuplicateTargetsDoNotCreateDuplicateOptions() {
        val first=record("FIRST",DomainRef("NPC","A"),"A_ACQUISITION").copy(
            subjectRefs=setOf(DomainRef("NPC","B"),DomainRef("NPC","A")))
        val duplicate=record("SECOND",DomainRef("NPC","A"),"Z_ACQUISITION")
        val projected=options(listOf(duplicate,first))
        assertEquals(listOf(DomainRef("NPC","A"),DomainRef("NPC","B")),projected.map { it.target })
        assertTrue(projected.all { it.supportingRecordUids==setOf(first.uid) })
        assertEquals(projected,options(listOf(first,duplicate)))
    }

    @Test fun registeredTargetFiltersRunBeforeTheFourOptionLimitAndNeverCreateAnUnseenTarget() {
        val irrelevant=(1..5).map { record("NPC:$it",DomainRef("NPC","N:$it")) }
        val project=record("PROJECT",DomainRef("PROJECT","ACTUAL_PROJECT"))
        val scoped=targeted.copy(parameters=targeted.parameters+mapOf(
            "activation_target_kind" to "PROJECT","activation_target_uid" to "ACTUAL_PROJECT"))
        val projected=options(irrelevant+project,scoped)
        assertEquals(1,projected.size)
        assertEquals(project.subjectRefs.single(),projected.single().target)
        assertEquals(setOf(project.uid),projected.single().supportingRecordUids)
        assertTrue(options(irrelevant,scoped).isEmpty())
        assertTrue(options(irrelevant+project,scoped.copy(parameters=scoped.parameters+
            ("activation_target_uid" to "MISSING_PROJECT"))).isEmpty())
    }

    @Test fun fixedProjectRecipeStillRequiresItsProtectedTargetRatherThanInventingIt() {
        val project=record("PROJECT",DomainRef("PROJECT","ACTUAL_PROJECT"))
        val unrelated=record("OTHER",DomainRef("PROJECT","ANOTHER_PROJECT"))
        val completion=fixed.copy(parameters=fixed.parameters+mapOf(
            "projectUid" to "ACTUAL_PROJECT","activation_target_kind" to "PROJECT",
            "activation_target_uid" to "ACTUAL_PROJECT"))
        assertTrue(options(emptyList(),completion).isEmpty())
        assertTrue(options(listOf(unrelated),completion).isEmpty())
        val selected=options(listOf(unrelated,project),completion).single()
        assertEquals(project.subjectRefs.single(),selected.target)
        assertEquals(setOf(project.uid),selected.supportingRecordUids)
    }

    @Test fun ownAcquisitionPlaceholderUsesOnlyTheSelectedProjectedRecord() {
        val report=targeted.copy(parameters=targeted.parameters+("source_acquisition_uid" to "@OWN_ACQUISITION_UID"))
        val target=DomainRef("NPC","RECIPIENT")
        val records=listOf(record("A",target,"OWN_A"),record("B",target,"OWN_B"))
        val projected=options(records,report)
        assertEquals(2,projected.size)
        assertEquals(setOf("OWN_A","OWN_B"),projected.map { it.parameters.getValue("p64_source_acquisition_uid") }.toSet())
        projected.forEach { option->
            val support=records.single { it.uid in option.supportingRecordUids }
            assertEquals(support.acquisitionUid,option.parameters.getValue("p64_source_acquisition_uid"))
            assertEquals(target,option.target)
            assertEquals(1,option.parameters.size)
        }
        assertTrue(options(emptyList(),report).isEmpty())
        assertNotEquals(projected[0].uid,projected[1].uid)
    }

    @Test fun selfSourceRecipeNeedsOwnRecordButDoesNotRequireAWorldSubject() {
        val report=fixed.copy(parameters=fixed.parameters+("source_acquisition_uid" to "@OWN_ACQUISITION_UID"))
        val known=record("OWN",null)
        assertTrue(options(emptyList(),report).isEmpty())
        val projected=options(listOf(known),report).single()
        assertEquals(actor,projected.target)
        assertEquals(setOf(known.uid),projected.supportingRecordUids)
        assertEquals(known.acquisitionUid,projected.parameters["p64_source_acquisition_uid"])
    }

    @Test fun sourcePlaceholderDoesNotOfferUnsupportedNonReportBindings() {
        val known=record("OWN",DomainRef("NPC","RECIPIENT"))
        val unsupported=targeted.copy(operation="DIPLOMACY",parameters=targeted.parameters+
            ("source_acquisition_uid" to "@OWN_ACQUISITION_UID"))
        assertTrue(options(listOf(known),unsupported).isEmpty())
    }

    @Test fun knowledgeGoalKeepsItsOwnSourceAlongsideTheTargetSource() {
        val goalSource=record("GOAL_SOURCE",null)
        val targetSource=record("TARGET_SOURCE",DomainRef("NPC","RECIPIENT"))
        val informed=brain.copy(goals=brain.goals.map { it.copy(cause=
            NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,goalSource.acquisitionUid)) })
        assertTrue(Phase64NpcInitiation.options(informed,listOf(targetSource),body,targeted).isEmpty())
        val projected=Phase64NpcInitiation.options(informed,listOf(targetSource,goalSource),body,targeted).single()
        assertEquals(setOf(goalSource.uid,targetSource.uid),projected.supportingRecordUids)
        assertEquals(targetSource.subjectRefs.single(),projected.target)
    }

    @Test fun identitiesUseLengthPrefixedRuleActorTargetAndAcquisition() {
        val known=record("SOURCE",DomainRef("NPC","RECIPIENT|:1"),"SOURCE|:2")
        val projected=options(listOf(known)).single()
        val identity=listOf("P64:OPTION:2",targeted.uid,targeted.version.toString(),actor.kindUid,actor.uid,
            known.subjectRefs.single().kindUid,known.subjectRefs.single().uid,known.acquisitionUid)
            .joinToString("") { "${it.length}:$it" }
        assertEquals("P64:OPTION:${phase63Hash(identity)}",projected.uid)
        assertNotEquals(projected.uid,options(listOf(known.copy(acquisitionUid="OTHER_SOURCE"))).single().uid)
        assertNotEquals(projected.uid,options(listOf(known.copy(subjectRefs=setOf(DomainRef("NPC","OTHER"))))).single().uid)
        assertNotEquals(projected.uid,options(listOf(known),targeted.copy(version=2)).single().uid)
    }

    @Test fun sealedDecisionBindsBothTargetAndOwnAcquisitionWithoutGrantingAuthority() {
        val report=targeted.copy(parameters=targeted.parameters+("source_acquisition_uid" to "@OWN_ACQUISITION_UID"))
        val known=record("KNOWN",DomainRef("NPC","RECIPIENT"))
        val projected=context(listOf(known),options(listOf(known),report))
        val selected=NpcDecisionEngine().select(projected,NpcDecisionProposal("REPORT",projected.contextFingerprint,
            listOf(NpcDecisionCandidate(projected.options.single().uid))),projected.scope) as NpcDecisionResult.Selected
        assertTrue(selected.authorization.matches(projected.scope,projected.contextFingerprint,selected.option))
        assertFalse(selected.authorization.matches(projected.scope,projected.contextFingerprint,
            selected.option.copy(target=DomainRef("NPC","UNSEEN"))))
        assertFalse(selected.authorization.matches(projected.scope,projected.contextFingerprint,
            selected.option.copy(parameters=mapOf("p64_source_acquisition_uid" to "SOMEBODY_ELSES_SOURCE"))))
        assertThrows(IllegalArgumentException::class.java) {
            context(listOf(known),listOf(selected.option.copy(target=DomainRef("NPC","UNSEEN"))))
        }
        assertTrue(selected.brainChanges.isEmpty())
        assertEquals(1L,body.stateVersion)
    }

    @Test fun exactSelectedTargetAndSourceSurviveTheOrdinaryMechanicalActivationProof() {
        val report=targeted.copy(parameters=targeted.parameters+("source_acquisition_uid" to "@OWN_ACQUISITION_UID"))
        val known=record("KNOWN",DomainRef("NPC","RECIPIENT"))
        val projected=projected(listOf(known),report)
        val envelope=projected.context
        val selected=NpcDecisionEngine().select(envelope,NpcDecisionProposal("REPORT",envelope.contextFingerprint,
            listOf(NpcDecisionCandidate(envelope.options.single().uid))),envelope.scope) as NpcDecisionResult.Selected
        var calls=0
        val mechanics=NpcMechanicalActionApplication(MechanicsRuleResolver { request,resolution->
            calls++
            assertEquals(known.subjectRefs.single(),request.targetProjectedRef)
            assertEquals(mapOf("p64_source_acquisition_uid" to known.acquisitionUid),request.parameters)
            val node=resolution.plan.intent.nodes.single()
            assertSame(selected.authorization,resolution.npcAuthorization)
            assertTrue(selected.authorization.authorizesMechanics(temporal,resolution.plan,node,request))
            Phase64ProcessActivation.resolve(report,"REGISTERED_POLICY",request,resolution,node,actor)
        },{temporal})
        val result=mechanics.resolve(projected,selected)
        assertTrue(result.toString(),result is NpcMechanicalResult.Resolved)
        val completed=(result as NpcMechanicalResult.Resolved).effects.single().let { effect->
            effect.copy(canonicalPayload=effect.canonicalPayload+mapOf(
                "source_actor_kind_uid" to actor.kindUid,"source_actor_uid" to actor.uid))
        }
        val started=Phase64ProcessActivation.start(temporal,"COMMAND",report,completed,WorldTimeTick(1000))
        assertEquals(known.subjectRefs.single().uid,started.process.parameters["recipient_uid"])
        assertEquals(known.acquisitionUid,started.process.parameters["source_acquisition_uid"])
        assertEquals(known.acquisitionUid,started.process.parameters["p64_start_source_acquisition_uid"])
        assertEquals(WorldTimeTick(2000),started.process.due)
        assertThrows(IllegalArgumentException::class.java) {
            Phase64ProcessActivation.start(temporal,"COMMAND",report,completed.copy(canonicalPayload=
                completed.canonicalPayload+("p64_start_source_acquisition_uid" to "ANOTHER_SOURCE")),WorldTimeTick(1000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Phase64ProcessActivation.start(temporal,"COMMAND",report,completed.copy(canonicalPayload=
                completed.canonicalPayload+("p64_start_target_uid" to "ANOTHER_RECIPIENT")),WorldTimeTick(1000))
        }
        assertEquals(NpcMechanicalResult.Unavailable("P62:ACTION_AUTHORIZATION_MISMATCH"),mechanics.resolve(projected,
            selected.copy(option=selected.option.copy(parameters=mapOf("p64_source_acquisition_uid" to "ANOTHER_SOURCE")))))
        assertEquals(1,calls)
    }

    @Test fun missingCapabilitiesWrongIdentityInactiveGoalsAndAmbiguousRecordsOfferNothing() {
        val known=record("KNOWN",DomainRef("NPC","RECIPIENT"))
        assertTrue(Phase64NpcInitiation.options(brain,listOf(known),body.copy(executableAbilityUids=emptySet()),targeted).isEmpty())
        assertTrue(Phase64NpcInitiation.options(brain,listOf(known),body.copy(actor=DomainRef("NPC","OTHER")),targeted).isEmpty())
        assertTrue(Phase64NpcInitiation.options(brain,listOf(known),body.copy(campaignUid="OTHER"),targeted).isEmpty())
        assertTrue(Phase64NpcInitiation.options(brain,listOf(known),body.copy(kind=MechanicalActorKind.ACTIVE_PLAYER),targeted).isEmpty())
        assertTrue(Phase64NpcInitiation.options(brain.copy(goals=brain.goals.map { it.copy(lifecycle=NpcGoalLifecycle.SUSPENDED) }),
            listOf(known),body,targeted).isEmpty())
        assertTrue(options(listOf(known,known.copy(acquisitionUid="DIFFERENT"))).isEmpty())
        assertTrue(options((1..65).map { record("R:$it",DomainRef("NPC","N:$it")) }).isEmpty())
    }
}
