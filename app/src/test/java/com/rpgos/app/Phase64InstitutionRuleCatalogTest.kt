package com.rpgos.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class Phase64InstitutionRuleCatalogTest {
    private val scope=TemporalScope("C1","G1",7,"STATE")
    private val actor=DomainRef("NPC","N1")
    private val player=DomainRef("PLAYER","P1")
    private val recipient=DomainRef("NPC","N2")
    private val organization=DomainRef("ORGANIZATION","O1")
    private val channel=DomainRef("INFORMATION_CHANNEL","REGISTERED_COURIER")
    private val disclosure=DomainRef("DISCLOSURE_POLICY","REGISTERED_NAMED_DELIVERY")
    private val agenda=Phase64InstitutionAgendaRecipeSnapshot(organization,"EXISTING_AGENDA",3,
        "Maintain the existing watch.","REGISTERED_AGENDA_POLICY")
    private val duty=NpcDutyRule("EXISTING_WATCH",2,organization.uid,"GUARD","EXISTING_DEADLINE",WorldTimeTick(10000),"ASSIGN_WATCH")
    private val dutyActivity=NpcActivityContract("GUARD","EXISTING_DUTY_ACTIVITY",4,ActionDuration(1000),"DUTY_EFFORT",duty=duty)
    private val source=KnowledgeAcquisition("C1","EXISTING_ACQUISITION","EXISTING_CLAIM",
        KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,player.uid,"C1"),KnowledgeAcquisitionMethods.DIRECT_OBSERVATION,
        KnowledgeScope.PERSONAL,null,null,null,null,"TRANSACTION","TURN","EVENT",KnowledgeProvenanceStatus.RECORDED,7)
    private val brain=NpcBrainOwner.initialize("C1",actor,"SEED").let { genesis->
        genesis.copy(revision=2,goals=listOf(NpcGoal("OWN_GOAL",genesis.motivations.first().uid,"Maintain my watch",
            NpcWeight(7000),NpcGoalLifecycle.ACTIVE,NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,genesis.motivations.first().uid))))
    }
    private val known=NpcKnownRecord("OWN_RECORD",KnowledgeEpistemicState.BELIEVED,"I believe the bridge is safe.",
        "OWN_ACQUISITION",1,setOf(recipient),7)
    private val option=NpcActionOption("EXISTING_OPTION","WAIT",actor,AcceptedActionTiming(ActionDuration(1000),"WAIT_RULE",1),
        "OWN_GOAL",emptyList(),setOf(known.uid),routine=true)
    private val context=NpcDecisionContextEnvelope(NpcDecisionScope(scope,actor,brain.revision,WorldTimeTick(0),0,player.uid),
        NpcTrigger("SELF",NpcTriggerKind.SELF_REFLECTION,WorldTimeTick(0),brain.goals.single().cause),brain,listOf(known),listOf(option),8192)
    private fun registration(operation:String,npc:Boolean=false)=Phase64InstitutionRuleRegistration("C1","IMPORTED:$operation",2,
        "ACTIVATE:$operation",1000,publicAction=!npc,npcAction=npc,npcActivationPolicyUid=if(npc)"EXISTING_NPC_POLICY" else null)
    private fun access(principal:DomainRef,carrier:DomainRef=channel):EffectiveAccessDecision {
        val trusted=TrustedPrincipalContext("C1",VisibilityPrincipalRef(principal.kindUid,principal.uid),AudienceKinds.WORLD_ACTOR)
        val path=Phase38AccessRuntimeAuthority.issuePath(trusted,InformationCarrierRef("C1",carrier.kindUid,carrier.uid),
            "EXISTING_OWNER_MECHANISM","EXISTING_EVIDENCE:${principal.uid}:${carrier.uid}",false,CarrierAccessStage.entries.toSet())
        return EffectiveAccessDecision.granted("OWNER_CAPTURE",path,path.resolvedStages)
    }
    private fun delivery(sender:DomainRef=player)=Phase64InstitutionDeliveryRecipeSnapshot(scope,sender,recipient,channel,disclosure,
        access(sender),access(recipient),1000,"EXISTING_MESSAGE","The sender asserts that the bridge is safe.")
    private fun ready(result:Phase64InstitutionRulePreparation)=
        (result as Phase64InstitutionRulePreparation.Ready).definition
    private fun reason(result:Phase64InstitutionRulePreparation)=
        (result as Phase64InstitutionRulePreparation.Unavailable).reasonUid

    @Test fun completeVersionedRecipesRegisterAllEightOperationsWithoutExecutingAnything() {
        val allocation=Phase64AllocationOwnerSnapshot(scope,true,organization,recipient,1,itemInstanceUid="EXISTING_ITEM")
        val reading=NpcActivityContract("READ","EXISTING_READING_ACTIVITY",3,ActionDuration(1000),"READ_EFFORT",reading=
            NpcReadingRule(DomainRef("ITEM_INSTANCE","EXISTING_REPORT"),"EXISTING_CARRIER_POLICY",
                KnowledgeClaim("CARRIER_CLAIM","PLACE","BRIDGE","CONDITION","SAFE",domainUid="WORLD")))
        val readingRule=requireNotNull(reading.reading)
        val carrier=Phase64InstitutionEspionageCapture(access(actor,readingRule.carrier),readingRule.claim,
            KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,recipient.uid,"C1"))
        val definitions=listOf(
            ready(Phase64InstitutionRuleCatalog.agenda(registration("AGENDA"),agenda)),
            ready(Phase64InstitutionRuleCatalog.assign(registration("ASSIGN"),Phase64InstitutionDutyRecipeSnapshot(organization,dutyActivity))),
            ready(Phase64InstitutionRuleCatalog.revoke(registration("REVOKE"),Phase64InstitutionDutyRecipeSnapshot(organization,dutyActivity,
                revokeReasonUid="OWNER_WITHDRAWAL"))),
            ready(Phase64InstitutionRuleCatalog.decision(registration("DECISION",true),
                Phase64InstitutionDecisionRecipeSnapshot(agenda,context,option.uid,"EXISTING_DECISION_POLICY"))),
            ready(Phase64InstitutionRuleCatalog.allocate(registration("ALLOCATE"),
                Phase64InstitutionAllocationRecipeSnapshot(allocation,DomainRef("ITEM_INSTANCE","EXISTING_ITEM"),1,"EXISTING_ALLOCATION_POLICY"))),
            ready(Phase64InstitutionRuleCatalog.report(registration("REPORT"),Phase64InstitutionReportRecipeSnapshot(delivery(),source))),
            ready(Phase64InstitutionRuleCatalog.diplomacy(registration("DIPLOMACY"),Phase64InstitutionDiplomacyRecipeSnapshot(delivery(),organization))),
            ready(Phase64InstitutionRuleCatalog.espionage(registration("ESPIONAGE",true),
                Phase64InstitutionEspionageRecipeSnapshot(delivery(actor),reading,carrier))))
        assertEquals(setOf("AGENDA","ASSIGN","REVOKE","DECISION","ALLOCATE","REPORT","DIPLOMACY","ESPIONAGE"),definitions.map { it.operation }.toSet())
        definitions.forEach { definition->
            assertEquals(2,definition.version)
            assertEquals("ACTIVATE:${definition.operation}",definition.parameters[Phase64ProcessActivation.ACTION_KEY])
            assertEquals(definition,Phase64BackgroundCodec.readDefinition(Json.parseToJsonElement(
                Phase64BackgroundCodec.definition(definition).toString()).jsonObject))
            assertFalse(definition.parameters.keys.any { it.startsWith("p64_") })
            assertFalse(definition.parameters.values.any { it=="@PROCESS_UID" || it=="@MESSAGE_LITERAL" })
        }
        assertEquals(1L,allocation.availableUnits)
        assertEquals(KnowledgeEpistemicState.BELIEVED,known.epistemicState)
        assertTrue(brain.plans.isEmpty())
    }

    @Test fun missingOwnerCapturesAreTypedUnavailableRatherThanExecutableBareDefinitions() {
        val outcomes=listOf(
            Phase64InstitutionRuleCatalog.agenda(registration("AGENDA"),null),
            Phase64InstitutionRuleCatalog.assign(registration("ASSIGN"),null),
            Phase64InstitutionRuleCatalog.revoke(registration("REVOKE"),null),
            Phase64InstitutionRuleCatalog.decision(registration("DECISION",true),null),
            Phase64InstitutionRuleCatalog.allocate(registration("ALLOCATE"),null),
            Phase64InstitutionRuleCatalog.report(registration("REPORT"),null),
            Phase64InstitutionRuleCatalog.diplomacy(registration("DIPLOMACY"),null),
            Phase64InstitutionRuleCatalog.espionage(registration("ESPIONAGE",true),null))
        assertTrue(outcomes.all { it is Phase64InstitutionRulePreparation.Unavailable })
        assertTrue(outcomes.map(::reason).all { it.startsWith("P64:CATALOG_") && it.endsWith("_MISSING") })
    }

    @Test fun dutyRecipesCopyTheActualRegisteredVersionPolicyAndDeadlineAndRequireARealContract() {
        val snapshot=Phase64InstitutionDutyRecipeSnapshot(organization,dutyActivity,issuerRoleUid="COMMANDER")
        val definition=ready(Phase64InstitutionRuleCatalog.assign(registration("ASSIGN"),snapshot))
        assertEquals(duty.dutyUid,definition.parameters["duty_uid"])
        assertEquals(duty.version.toString(),definition.parameters["duty_version"])
        assertEquals(dutyActivity.ruleUid,definition.parameters["activity_rule_uid"])
        assertEquals(dutyActivity.version.toString(),definition.parameters["activity_rule_version"])
        assertEquals(duty.assignmentPolicyUid,definition.parameters["assignment_policy_uid"])
        assertEquals(duty.due.milliseconds.toString(),definition.parameters["deadline_ms"])
        assertEquals("@TARGET_UID",definition.parameters["assignee_uid"])
        assertEquals("P64:CATALOG_REGISTERED_DUTY_MISSING",reason(Phase64InstitutionRuleCatalog.assign(registration("ASSIGN"),
            snapshot.copy(activity=dutyActivity.copy(duty=null)))))
        assertEquals("P64:CATALOG_DUTY_ORGANIZATION_SCOPE",reason(Phase64InstitutionRuleCatalog.assign(registration("ASSIGN"),
            snapshot.copy(organization=DomainRef("ORGANIZATION","OTHER")))))
    }

    @Test fun allocationRequiresActualCustodyAuthorityAndQuantityInsteadOfCreatingAResourcePool() {
        val owner=Phase64AllocationOwnerSnapshot(scope,true,organization,recipient,1,itemInstanceUid="EXISTING_ITEM")
        val captured=Phase64InstitutionAllocationRecipeSnapshot(owner,DomainRef("ITEM_INSTANCE","EXISTING_ITEM"),1,"POLICY")
        assertEquals("P64:CATALOG_ALLOCATION_AUTHORITY_DENIED",reason(Phase64InstitutionRuleCatalog.allocate(registration("ALLOCATE"),
            captured.copy(owner=owner.copy(authorized=false)))))
        assertEquals("P64:CATALOG_ALLOCATION_RESOURCE_INSUFFICIENT",reason(Phase64InstitutionRuleCatalog.allocate(registration("ALLOCATE"),
            captured.copy(owner=owner.copy(availableUnits=null)))))
        assertEquals("P64:CATALOG_ALLOCATION_ITEM_SCOPE",reason(Phase64InstitutionRuleCatalog.allocate(registration("ALLOCATE"),
            captured.copy(resource=DomainRef("ITEM_INSTANCE","INVENTED_ITEM")))))
        assertEquals("P64:CATALOG_ALLOCATION_RESOURCE_OWNER_UNAVAILABLE",reason(Phase64InstitutionRuleCatalog.allocate(registration("ALLOCATE"),
            captured.copy(resource=DomainRef("RESOURCE","FICTIONAL_POOL")))))
    }

    @Test fun decisionRecipeRequiresTheActualProtectedOptionAndCannotControlThePlayer() {
        val captured=Phase64InstitutionDecisionRecipeSnapshot(agenda,context,option.uid,"POLICY")
        val definition=ready(Phase64InstitutionRuleCatalog.decision(registration("DECISION",true),captured))
        assertEquals(actor.uid,definition.parameters["decision_actor_uid"])
        assertEquals(option.uid,definition.parameters["option_uid"])
        assertEquals("P64:CATALOG_LEGAL_OPTION_MISSING",reason(Phase64InstitutionRuleCatalog.decision(registration("DECISION",true),
            captured.copy(optionUid="UNPROJECTED_OPTION"))))
        assertEquals("P64:CATALOG_NPC_DECISION_ONLY",reason(Phase64InstitutionRuleCatalog.decision(registration("DECISION"),captured)))
    }

    @Test fun financialRecipeKeepsTheExistingDestinationAccountRequiredByTheProductionOwner() {
        val owner=Phase64AllocationOwnerSnapshot(scope,true,organization,recipient,10,
            sourceAccountUid="SOURCE_ACCOUNT",recipientAccountUid="RECIPIENT_ACCOUNT",currencyUid="EXISTING_CURRENCY")
        val captured=Phase64InstitutionAllocationRecipeSnapshot(owner,DomainRef("FINANCIAL_ACCOUNT","SOURCE_ACCOUNT"),2,"POLICY")
        val definition=ready(Phase64InstitutionRuleCatalog.allocate(registration("ALLOCATE"),captured))
        assertEquals("RECIPIENT_ACCOUNT",definition.parameters["recipient_account_uid"])
        val result=Phase64OrganizationsInformationOwners.prepareAllocation(definition.parameters,
            BackgroundProcessEvaluationScope(scope,"SEED","RULES"),owner)
        assertEquals(FinancialChange("SOURCE_ACCOUNT","RECIPIENT_ACCOUNT",2,"EXISTING_CURRENCY","RPGOS-FIN-TYPE:TRANSFER"),result.changes.single())
    }

    @Test fun reportUsesFixedLegalContentAndOnlyOwnProjectedSourceForNpcBinding() {
        val captured=Phase64InstitutionReportRecipeSnapshot(delivery(actor),ownContext=context,ownRecordUid=known.uid)
        val definition=ready(Phase64InstitutionRuleCatalog.report(registration("REPORT",true),captured))
        assertEquals(Phase64ProcessActivation.OWN_ACQUISITION,definition.parameters["source_acquisition_uid"])
        assertEquals(delivery(actor).messageText,definition.parameters["message_text"])
        assertEquals("P64:CATALOG_REPORT_OWN_RECORD_MISSING",reason(Phase64InstitutionRuleCatalog.report(registration("REPORT",true),
            captured.copy(ownRecordUid="SOMEBODY_ELSES_MEMORY"))))
        assertEquals("P64:CATALOG_REPORT_SOURCE_SCOPE",reason(Phase64InstitutionRuleCatalog.report(registration("REPORT"),
            Phase64InstitutionReportRecipeSnapshot(delivery(),source.copy(campaignUid="OTHER")))))
    }

    @Test fun communicationRecipeRequiresBothExactComprehendedOwnerPathsAndScheduledDelay() {
        val captured=Phase64InstitutionDiplomacyRecipeSnapshot(delivery(),organization)
        assertEquals("P64:CATALOG_DELIVERY_OWNER_ACCESS_DENIED",reason(Phase64InstitutionRuleCatalog.diplomacy(registration("DIPLOMACY"),
            captured.copy(delivery=delivery().copy(recipientAccess=access(player))))))
        val incompletePath=Phase38AccessRuntimeAuthority.issuePath(
            TrustedPrincipalContext("C1",VisibilityPrincipalRef(player.kindUid,player.uid),AudienceKinds.WORLD_ACTOR),
            InformationCarrierRef("C1",channel.kindUid,channel.uid),"EXISTING_MECHANISM","INCOMPLETE_EVIDENCE",false,
            setOf(CarrierAccessStage.AVAILABLE))
        val apparent=EffectiveAccessDecision.granted("CLAIMED_COMPREHENSION",incompletePath,setOf(CarrierAccessStage.COMPREHENDED))
        assertEquals("P64:CATALOG_DELIVERY_OWNER_ACCESS_DENIED",reason(Phase64InstitutionRuleCatalog.diplomacy(registration("DIPLOMACY"),
            captured.copy(delivery=delivery().copy(senderAccess=apparent)))))
        val wrongCarrier=access(player,DomainRef("INFORMATION_CHANNEL","PRIVATE_CHANNEL"))
        assertEquals("P64:CATALOG_DELIVERY_OWNER_ACCESS_DENIED",reason(Phase64InstitutionRuleCatalog.diplomacy(registration("DIPLOMACY"),
            captured.copy(delivery=delivery().copy(senderAccess=wrongCarrier)))))
        assertEquals("P64:CATALOG_DELIVERY_DELAY_NOT_SCHEDULED",reason(Phase64InstitutionRuleCatalog.diplomacy(registration("DIPLOMACY"),
            captured.copy(delivery=delivery().copy(delayMillis=1001)))))
        assertTrue(apparent.accessible)
    }

    @Test fun catalogCannotIntroduceExtraDynamicInputsOrNpcCapabilityAndPolicyByNamingThem() {
        assertEquals("P64:CATALOG_NPC_ACTIVATION_POLICY_MISSING",reason(Phase64InstitutionRuleCatalog.agenda(
            registration("AGENDA",true).copy(npcActivationPolicyUid=null),agenda)))
        assertEquals("P64:CATALOG_AGENDA_OBJECTIVE_INVALID",reason(Phase64InstitutionRuleCatalog.agenda(registration("AGENDA"),
            agenda.copy(objective="@MESSAGE_LITERAL"))))
        val definition=ready(Phase64InstitutionRuleCatalog.agenda(registration("AGENDA",true),agenda))
        val body=MechanicalActorView("C1",actor,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,
            emptyMap(),emptyList(),emptySet(),generationProvenanceUid="EXISTING_BODY")
        assertTrue(Phase64NpcInitiation.options(brain,listOf(known),body,definition).isEmpty())
    }

    @Test fun aRegisteredCommunicationRecipeStillRequiresLiveAuthorityBeforePreparingAnyAcquisition() {
        val definition=ready(Phase64InstitutionRuleCatalog.report(registration("REPORT"),
            Phase64InstitutionReportRecipeSnapshot(delivery(),source)))
        val reads=object:BackgroundWorldReadPort {
            override fun available(resource:DomainRef,staged:List<PlayerDomainChangePayload>):Long?=null
            override fun exists(ref:DomainRef)=true
            override fun route(actor:DomainRef,destination:DomainRef,at:WorldTimeTick):String?=null
            override fun authorize(actor:DomainRef,purpose:String,refs:List<DomainRef>)=false
            override fun prepareOwnedEffect(operation:String,actor:DomainRef,parameters:Map<String,String>,
                scope:BackgroundProcessEvaluationScope,staged:List<PlayerDomainChangePayload>):WorldConsequencePlan=
                error("Catalog registration cannot grant owner execution")
        }
        val instance=BackgroundProcessInstance("EXISTING_START",definition.uid,definition.version,player,1,WorldTimeTick(0),WorldTimeTick(1000))
        val result=Phase64OrganizationsInformationAdapter().evaluate(definition,instance,
            BackgroundProcessEvaluationScope(scope,"SEED","REGISTERED_RULES"),WorldTimeTick(1000),reads,emptyList())
        assertEquals(BackgroundProcessStatus.BLOCKED,result.status)
        assertEquals("P64:INFORMATION_SENDER_ACCESS_DENIED",result.reasonUid)
        assertTrue(result.changes.isEmpty());assertTrue(result.effects.isEmpty())
    }

    @Test fun reportFactoryCanConsumeExactScopedCarrierOwnerPathsWithoutTurningRegistrationIntoAGrant() {
        val definition=ready(Phase64InstitutionRuleCatalog.report(registration("REPORT"),
            Phase64InstitutionReportRecipeSnapshot(delivery(),source)))
        val anchor=DomainRef("LOCATION","ACTUAL_POST")
        fun principal(ref:DomainRef,kind:MechanicalActorKind)=Phase64CommunicationPrincipalCapture(ref,
            KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,ref.uid,"C1"),
            MechanicalActorView("C1",ref,kind,1,MechanicalStateMaterialization.FULL,emptyMap(),emptyList(),emptySet(),
                locationRef=anchor,generationProvenanceUid="CANONICAL_BODY"))
        fun records(ref:DomainRef,purpose:String):List<AccessAuthorityRecord> {
            val exact=VisibilityPrincipalRef(ref.kindUid,ref.uid)
            fun record(uid:String,operation:AccessOperation,value:String,subject:DomainRef)=
                AccessAuthorityRecord("${ref.uid}:$uid",operation,exact,AccessGrantKind.EXPLICIT.name,value,
                    subject.kindUid,subject.uid,0,null,7)
            return listOf(record("CHANNEL",AccessOperation.GRANT,purpose,channel),
                record("DISCLOSURE",AccessOperation.GRANT,purpose,disclosure))+CarrierAccessStage.entries.map {
                    record(it.name,AccessOperation.SET_CARRIER_ACCESS,it.name,channel)
                }
        }
        val capture=Phase64ScopedCommunicationSnapshot(scope,7,setOf(player,recipient,channel,disclosure,anchor),
            principal(player,MechanicalActorKind.ACTIVE_PLAYER),principal(recipient,MechanicalActorKind.NPC),
            records(player,"P64:INFO_SEND:REPORT"),records(recipient,"P64:INFO_RECEIVE"))
        val process=BackgroundProcessInstance("ACTUAL_REPORT",definition.uid,definition.version,player,1,WorldTimeTick(0),WorldTimeTick(1000))
        val evaluation=BackgroundProcessEvaluationScope(scope,"SEED","REGISTERED_RULES")
        val authorized=Phase64ScopedCommunicationOwner.prepare(definition,process,evaluation,WorldTimeTick(1000),capture)
            as Phase64ScopedCommunicationPreparation.Ready
        val capturedDelivery=delivery().copy(senderAccess=authorized.senderAccess,recipientAccess=authorized.recipientAccess)
        assertEquals(definition,ready(Phase64InstitutionRuleCatalog.report(registration("REPORT"),
            Phase64InstitutionReportRecipeSnapshot(capturedDelivery,source))))
        val revoke=AccessAuthorityChange(AccessOperation.REVOKE_GRANT,"WITHDRAW_DISCLOSURE",recipient.kindUid,recipient.uid,
            AccessGrantKind.EXPLICIT.name,"P64:INFO_RECEIVE",disclosure.kindUid,disclosure.uid,8)
        val unavailable=Phase64ScopedCommunicationOwner.prepare(definition,process,evaluation,WorldTimeTick(1000),capture,listOf(revoke))
            as Phase64ScopedCommunicationPreparation.Unavailable
        assertEquals("P64:COMMUNICATION_PURPOSE_GRANT_REQUIRED",unavailable.reasonUid)
        assertEquals("P64:CATALOG_DELIVERY_OWNER_ACCESS_DENIED",reason(Phase64InstitutionRuleCatalog.report(registration("REPORT"),
            Phase64InstitutionReportRecipeSnapshot(capturedDelivery.copy(recipientAccess=null),source))))
    }
}
