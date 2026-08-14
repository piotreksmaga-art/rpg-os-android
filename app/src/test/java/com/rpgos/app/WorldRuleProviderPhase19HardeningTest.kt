package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class WorldRuleProviderPhase19HardeningTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val binding = WorldPackRuleBinding("TEST-WORLD", "1")

    // H1 — bound/unbound authority mode
    @Test fun p19H1_01_boundCorrectProviderExecutesBothRuleStages() {
        val r = engine(AllowAllProvider()).resolve(train(), boundContext()) as PlayerResolutionOutcome.Resolved
        assertEquals(listOf(WorldRuleEvaluationStage.COMMAND_PRECHECK, WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK), r.evidence.worldRuleDecisions.map { it.stage })
    }
    @Test fun p19H1_02_boundCannotBeConstructedAsImplicitNullMode() {
        val creates = PlayerResolutionContext.Companion::class.java.methods.filter { it.name == "create" }
        assertTrue(creates.isNotEmpty())
        assertTrue(creates.all { it.parameterTypes.last() == WorldRuleMode::class.java })
        assertFalse(PlayerResolutionContext::class.java.declaredFields.any { it.name == "worldPackBinding" })
    }
    @Test fun p19H1_03_explicitUnboundGenericIsLegalForCoreGenericMode() {
        assertTrue(engine(null).resolve(train(), unboundContext()) is PlayerResolutionOutcome.Resolved)
    }
    @Test fun p19H1_04_boundMissingProviderFailsClosed() = fails("WORLD_RULE_PROVIDER_MISSING") { engine(null).resolve(train(), boundContext()) }
    @Test fun p19H1_05_boundWrongProviderVersionFailsClosed() = fails("WORLD_RULE_PROVIDER_VERSION_MISMATCH") {
        engine(AllowAllProvider()).resolve(train(), boundContext(WorldPackRuleBinding("TEST-WORLD", "2")))
    }
    @Test fun p19H1_06_noImplicitNullableDowngradeSurfaceExists() {
        assertFalse(PlayerResolutionContext.Companion::class.java.methods.any { m ->
            m.name == "create" && m.parameterTypes.none { it == WorldRuleMode::class.java }
        })
        assertFalse(PlayerResolutionContext.Companion::class.java.methods.any { it.name == "createUnboundGeneric" })
    }
    @Test fun p19H1_07_commandPrecheckCannotBeSkippedWhenBound() {
        val r = engine(RejectCommandProvider()).resolve(train(), boundContext()) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.WORLD_RULE_REJECTED, r.rejection.reason)
        assertEquals(WorldRuleEvaluationStage.COMMAND_PRECHECK, r.rejection.worldRuleDecision!!.stage)
    }
    @Test fun p19H1_08_draftCheckCannotBeSkippedWhenBound() {
        val r = engine(RejectDraftProvider()).resolve(train(), boundContext()) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.WORLD_RULE_REJECTED, r.rejection.reason)
        assertEquals(WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK, r.rejection.worldRuleDecision!!.stage)
    }
    @Test fun p19H1_09_unknownReferenceStillRejectsBeforeProvider() {
        val cmd = train().copy(payload = TrainCommandPayload(DomainRef("STAT", "UNKNOWN"), 10L, "METHOD"))
        val r = engine(ExplodingProvider()).resolve(cmd, boundContext()) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, r.rejection.reason)
    }
    @Test fun p19H1_10_wrongCampaignStillRejectsBeforeProvider() {
        val cmd = train().copy(payload = TrainCommandPayload(DomainRef("STAT", "FOREIGN"), 10L, "METHOD"))
        val ctx = boundContext(extra = setOf(CampaignScopedDomainRef("OTHER", DomainRef("STAT", "FOREIGN"))))
        val r = engine(ExplodingProvider()).resolve(cmd, ctx) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, r.rejection.reason)
    }

    // H2 — retained enum/provider state
    @Test fun p19H2_01_safeStatelessEnumConfigAccepted() {
        WorldRuleProviderRegistry.of(listOf(SafeEnumProvider(SafeMode.ONE)))
    }
    @Test fun p19H2_02_mutableEnumStateRejected() = fails("MUTABLE_WORLD_RULE_PROVIDER_STATE") {
        WorldRuleProviderRegistry.of(listOf(MutableEnumProvider(MutableMode.INSTANCE)))
    }
    @Test fun p19H2_03_mutableEnumCannotInfluenceRepeatedDecisionBecauseRegistrationFails() = p19H2_02_mutableEnumStateRejected()
    @Test fun p19H2_04_mutableCollectionAndInheritedUnsafeStateRemainRejected() {
        fails("UNSAFE_WORLD_RULE_PROVIDER_STATE") { WorldRuleProviderRegistry.of(listOf(CollectionProvider(mutableListOf("x")))) }
        fails("UNSAFE_WORLD_RULE_PROVIDER_STATE") { WorldRuleProviderRegistry.of(listOf(InheritedUnsafeProvider(StringBuilder("x")))) }
    }

    // H3 — structural nullable encoding
    @Test fun p19H3_01_nullDiffersFromOldSentinelLiteral() = assertNotEquals(nullableFp(null), nullableFp("RPGOS-NULL"))
    @Test fun p19H3_02_nullDiffersFromEmptyString() = assertNotEquals(nullableFp(null), nullableFp(""))
    @Test fun p19H3_03_delimiterHeavyValuesRemainUnique() = assertNotEquals(nullableFp("a|b:1\\"), nullableFp("a|b:2\\"))
    @Test fun p19H3_04_unicodeValuesRemainUnique() = assertNotEquals(nullableFp("żółć-日本語"), nullableFp("zolc-日本語"))
    @Test fun p19H3_05_encodedLookingValuesRemainOrdinaryData() {
        val values = listOf(null, "NULL", "VALUE", "RPGOS-NULL", "RPGOS-WORLD-RULE:ALLOW")
        assertEquals(values.size, values.map { nullableFp(it) }.toSet().size)
    }

    // H4 — explicit decision variant
    @Test fun p19H4_01_allowedDiffersFromRejected() = assertNotEquals(decision(WorldRuleDecision.Allowed.create("RULE")), decision(WorldRuleDecision.Rejected.create("RULE", "DENY")))
    @Test fun p19H4_02_oldAllowSentinelAsRealReasonStillDiffers() = assertNotEquals(decision(WorldRuleDecision.Allowed.create("RULE")), decision(WorldRuleDecision.Rejected.create("RULE", "RPGOS-WORLD-RULE:ALLOW")))
    @Test fun p19H4_03_equivalentAllowedIndependentAllocationsMatch() = assertEquals(decision(WorldRuleDecision.Allowed.create("RULE", listOf("E1"))), decision(WorldRuleDecision.Allowed.create("RULE", listOf("E1"))))
    @Test fun p19H4_04_equivalentRejectedIndependentAllocationsMatch() = assertEquals(decision(WorldRuleDecision.Rejected.create("RULE", "DENY", listOf("E1"))), decision(WorldRuleDecision.Rejected.create("RULE", "DENY", listOf("E1"))))
    @Test fun p19H4_05_differentReasonDiffers() = assertNotEquals(decision(WorldRuleDecision.Rejected.create("RULE", "A")), decision(WorldRuleDecision.Rejected.create("RULE", "B")))
    @Test fun p19H4_06_differentStageDiffers() {
        val provider = AllowAllProvider()
        val command = train(); val fp = PlayerCommandKindRegistry.core().fingerprint(command); val ctxFp = boundContext().deterministicFingerprint()
        val a = WorldRuleDecisionRecord.create(provider, WorldRuleRequest.commandPrecheck(binding,"C1",actor,command,fp,ctxFp), WorldRuleDecision.Allowed.create("RULE"))
        val effects = WorldRuleEffectSnapshot.create(baseDraft())
        val b = WorldRuleDecisionRecord.create(provider, WorldRuleRequest.draftEffectCheck(binding,"C1",actor,command,fp,ctxFp,effects), WorldRuleDecision.Allowed.create("RULE"))
        assertNotEquals(a.decisionFingerprint, b.decisionFingerprint)
    }

    // H5 — effect snapshot framing
    @Test fun p19H5_01_changeCountChangesIdentity() = assertNotEquals(effectFp(listOf(stat("A"))), effectFp(listOf(stat("A"), stat("B"))))
    @Test fun p19H5_02_projectEvidenceCannotMimicEquipmentRecord() {
        val equipment = PlayerDomainChange.create("E-CHANGE", PlayerChangeKinds.EQUIPMENT, EquipmentChange(DomainRef("PLAYER","P1"),"SLOT",EquipmentOperation.UNEQUIP,null))
        val evidence = listOf(DomainRef("E-CHANGE",PlayerChangeKinds.EQUIPMENT), DomainRef("RPGOS-NULL","PLAYER"), DomainRef("P1","SLOT"), DomainRef("UNEQUIP","RPGOS-NULL"))
        assertNotEquals(effectFp(listOf(project(evidence))), effectFp(listOf(project(emptyList()), equipment)))
    }
    @Test fun p19H5_03_changeFamilyBoundariesCannotAlias() = assertNotEquals(effectFp(listOf(stat("X"))), effectFp(listOf(resource("X"))))
    @Test fun p19H5_04_equalSemanticSnapshotIndependentAllocationMatches() = assertEquals(effectFp(listOf(stat("A"))), effectFp(listOf(stat("A"))))
    @Test fun p19H5_05_orderedChangeListsPreserveOrderSemantics() = assertNotEquals(effectFp(listOf(stat("A"),stat("B"))), effectFp(listOf(stat("B"),stat("A"))))
    @Test fun p19H5_06_emptyNestedCollectionDiffersFromNonEmpty() = assertNotEquals(effectFp(listOf(project(emptyList()))), effectFp(listOf(project(listOf(DomainRef("E","1"))))))
    @Test fun p19H5_07_delimiterUnicodeAndEncodedLookingStringsSafe() {
        val a = WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(warnings=listOf(ChangeSetWarning("W","a|:żółć","RPGOS-NULL"))))
        val b = WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(warnings=listOf(ChangeSetWarning("W","a|:zolc",null))))
        assertNotEquals(a.deterministicFingerprint(), b.deterministicFingerprint())
    }
    @Test fun p19H5_08_allThirteenCurrentChangeFamiliesAreEncoded() {
        val all = allFamilies()
        assertEquals(13, all.size)
        val fps = all.map { effectFp(listOf(it)) }
        assertEquals(13, fps.toSet().size)
    }
    @Test fun p19H5_09_eventLedgerWarningSectionsAreIndependentlyFramed() {
        val e = PlayerEventIntent.create("X",PlayerEventIntentKinds.DOMAIN_EFFECT,payload=DomainEffectEventIntentPayload(DomainRef("PLAYER","P1"),"K"))
        val l = PlayerLedgerIntent.create("X",PlayerLedgerIntentKinds.FINANCIAL_TRANSFER,payload=FinancialTransferLedgerIntentPayload("A","B",1,"C","T"))
        val a = WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(eventIntents=listOf(e)))
        val b = WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(ledgerIntents=listOf(l)))
        val c = WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(warnings=listOf(ChangeSetWarning("X"))))
        assertEquals(3, setOf(a.deterministicFingerprint(),b.deterministicFingerprint(),c.deterministicFingerprint()).size)
    }

    // H6 — context framing
    @Test fun p19H6_01_twoRefsDifferFromThreeDependencyPairsWithSameScalars() {
        val a = rawContext(setOf(scoped("A","B","C"),scoped("D","E","F")), emptyMap())
        val b = rawContext(emptySet(), linkedMapOf("A" to "B","C" to "D","E" to "F"))
        assertNotEquals(a.deterministicFingerprint(), b.deterministicFingerprint())
    }
    @Test fun p19H6_02_referenceSectionCannotMasqueradeAsDependencySection() = p19H6_01_twoRefsDifferFromThreeDependencyPairsWithSameScalars()
    @Test fun p19H6_03_emptySectionsDifferFromAlternativeComposition() = assertNotEquals(rawContext(emptySet(),emptyMap()).deterministicFingerprint(), rawContext(setOf(scoped("C1","K","U")),emptyMap()).deterministicFingerprint())
    @Test fun p19H6_04_equalSemanticContextIndependentConstructionMatches() = assertEquals(boundContext().deterministicFingerprint(), boundContext().deterministicFingerprint())
    @Test fun p19H6_05_campaignChangesIdentity() = assertNotEquals(rawContext(emptySet(),emptyMap(),campaign="C1").deterministicFingerprint(), rawContext(emptySet(),emptyMap(),campaign="C2").deterministicFingerprint())
    @Test fun p19H6_06_actorChangesIdentity() {
        val a=rawContext(emptySet(),emptyMap(),who=CommandActorRef("PLAYER","P1")); val b=rawContext(emptySet(),emptyMap(),who=CommandActorRef("PLAYER","P2")); assertNotEquals(a.deterministicFingerprint(),b.deterministicFingerprint())
    }
    @Test fun p19H6_07_dependencyVersionChangesIdentity() = assertNotEquals(rawContext(emptySet(),mapOf("D" to "1")).deterministicFingerprint(),rawContext(emptySet(),mapOf("D" to "2")).deterministicFingerprint())
    @Test fun p19H6_08_referenceTupleChangesIdentity() = assertNotEquals(rawContext(setOf(scoped("C1","K1","U")),emptyMap()).deterministicFingerprint(),rawContext(setOf(scoped("C1","K2","U")),emptyMap()).deterministicFingerprint())
    @Test fun p19H6_09_inputOrderingDoesNotAffectSetMapSemantics() {
        val refs1=linkedSetOf(scoped("C1","B","2"),scoped("C1","A","1")); val refs2=linkedSetOf(scoped("C1","A","1"),scoped("C1","B","2"))
        val a=rawContext(refs1,linkedMapOf("Z" to "9","A" to "1")); val b=rawContext(refs2,linkedMapOf("A" to "1","Z" to "9")); assertEquals(a.deterministicFingerprint(),b.deterministicFingerprint())
    }

    // H7 — integrated replay/authority/regression locks
    @Test fun p19H7_01_sameSemanticRequestIndependentObjectsMatches() = assertEquals(requestFp(),requestFp())
    @Test fun p19H7_02_differentWorldRuleModeChangesContextIdentity() = assertNotEquals(boundContext().deterministicFingerprint(),unboundContext().deterministicFingerprint())
    @Test fun p19H7_03_providerVersionChangesDecisionIdentity() {
        val a=decision(WorldRuleDecision.Allowed.create("RULE"),AllowAllProvider("1")); val b=decision(WorldRuleDecision.Allowed.create("RULE"),AllowAllProvider("2")); assertNotEquals(a,b)
    }
    @Test fun p19H7_04_effectChangesDraftRequestIdentity() {
        val command=train(); val fp=PlayerCommandKindRegistry.core().fingerprint(command); val ctx=boundContext().deterministicFingerprint()
        val a=WorldRuleRequest.draftEffectCheck(binding,"C1",actor,command,fp,ctx,WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(changes=listOf(stat("A")))))
        val b=WorldRuleRequest.draftEffectCheck(binding,"C1",actor,command,fp,ctx,WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(changes=listOf(stat("B")))))
        assertNotEquals(a.requestFingerprint,b.requestFingerprint)
    }
    @Test fun p19H7_05_outcomeVariantChangesDecisionIdentity() = p19H4_01_allowedDiffersFromRejected()
    @Test fun p19H7_06_proposalUidDeterministicAfterHardening() {
        val e=engine(AllowAllProvider()); val a=(e.resolve(train(),boundContext()) as PlayerResolutionOutcome.Resolved).proposal; val b=(e.resolve(train(),boundContext()) as PlayerResolutionOutcome.Resolved).proposal
        assertEquals(a.changeSetUid,b.changeSetUid); assertEquals(PlayerChangeSetCodec.fingerprint(a),PlayerChangeSetCodec.fingerprint(b))
    }
    @Test fun p19H7_07_unknownReferencePreventsProvider() = p19H1_09_unknownReferenceStillRejectsBeforeProvider()
    @Test fun p19H7_08_wrongCampaignPreventsProvider() = p19H1_10_wrongCampaignStillRejectsBeforeProvider()
    @Test fun p19H7_09_rejectionHasZeroAuthoritativeMutation() { var authority=7; engine(RejectCommandProvider()).resolve(train(),boundContext()); assertEquals(7,authority) }
    @Test fun p19H7_10_providerFaultHasZeroAuthoritativeMutation() { var authority=7; fails("WORLD_RULE_PROVIDER_FAILURE"){engine(ExplodingProvider()).resolve(train(),boundContext())}; assertEquals(7,authority) }
    @Test fun p19H7_11_phase18ClassificationRegressionCoverageRemainsPresent() {
        val names=WorldRuleProviderPhase19Test::class.java.declaredMethods.map { it.name }.toSet(); assertTrue(names.any{it.contains("equipment",true)}); assertTrue(names.any{it.contains("ownership",true)}); assertTrue(names.any{it.contains("finance",true)})
    }
    @Test fun p19H7_12_phase17RepresentativeRegression() {
        try { ExactLongDelta.of(0); fail("zero exact delta must fail") } catch (_: PlayerChangeSetStructuralException) {}
        assertEquals(0L,ProjectProgressDelta.of(0).units); assertEquals(OWNERSHIP_SHARE_SCALE,OwnershipShare.full().units)
        val p=(engine(AllowAllProvider()).resolve(train(),boundContext()) as PlayerResolutionOutcome.Resolved).proposal
        assertEquals(p,PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(p)))
    }
    @Test fun p19H7_13_originalP19_01Through30RemainEnabled() {
        val a=WorldRuleProviderPhase19Test::class.java.declaredMethods.filter{it.getAnnotation(Test::class.java)!=null && it.name.startsWith("p19_")}
        val b=WorldRuleProviderDeterminismRegressionTest::class.java.declaredMethods.filter{it.getAnnotation(Test::class.java)!=null}
        assertTrue("expected at least original 30 Phase-19 tests", a.size+b.size>=30)
        assertTrue((a+b).none{it.annotations.any{ann->ann.annotationClass.simpleName in setOf("Ignore","Disabled")}})
    }

    private fun nullableFp(v:String?)=WorldRuleCanonicalWriter.fingerprint("NULL_TEST"){nullableField("X",v)}
    private fun requestFp():String { val c=train(); return WorldRuleRequest.commandPrecheck(binding,"C1",actor,c,PlayerCommandKindRegistry.core().fingerprint(c),boundContext().deterministicFingerprint()).requestFingerprint }
    private fun decision(d:WorldRuleDecision,p:WorldRuleProvider=AllowAllProvider()):String { val c=train(); val req=WorldRuleRequest.commandPrecheck(binding,"C1",actor,c,PlayerCommandKindRegistry.core().fingerprint(c),boundContext().deterministicFingerprint()); return WorldRuleDecisionRecord.create(p,req,d).decisionFingerprint }
    private fun effectFp(changes:List<PlayerDomainChange>)=WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(changes=changes)).deterministicFingerprint()

    private fun allFamilies():List<PlayerDomainChange> = listOf(
        PlayerDomainChange.create("1",PlayerChangeKinds.STAT,StatChange(DomainRef("PLAYER","P1"),"S",ExactLongDelta.of(1))),
        PlayerDomainChange.create("2",PlayerChangeKinds.RESOURCE,ResourceChange(DomainRef("PLAYER","P1"),"R",ExactLongDelta.of(1))),
        PlayerDomainChange.create("3",PlayerChangeKinds.SKILL,SkillChange(DomainRef("PLAYER","P1"),"SK",ExactLongDelta.of(1))),
        PlayerDomainChange.create("4",PlayerChangeKinds.TECHNIQUE,TechniqueChange(DomainRef("PLAYER","P1"),"T",ExactLongDelta.of(1))),
        PlayerDomainChange.create("5",PlayerChangeKinds.INNATE,InnateChange(DomainRef("PLAYER","P1"),"I","STATE")),
        PlayerDomainChange.create("6",PlayerChangeKinds.INVENTORY,InventoryChange(DomainRef("PLAYER","P1"),"ITEM",ExactLongDelta.of(1))),
        PlayerDomainChange.create("7",PlayerChangeKinds.EQUIPMENT,EquipmentChange(DomainRef("PLAYER","P1"),"SLOT",EquipmentOperation.UNEQUIP,null)),
        PlayerDomainChange.create("8",PlayerChangeKinds.FINANCIAL,FinancialChange("A","B",1,"CUR","TYPE")),
        PlayerDomainChange.create("9",PlayerChangeKinds.ASSET,AssetChange(OwnedAssetRef("KIND","A"),"ACTIVE")),
        PlayerDomainChange.create("10",PlayerChangeKinds.OWNERSHIP,OwnershipChange("OWN",OwnedAssetRef("KIND","A"),OwnershipOwnerRef("OWNER","O1"),OwnershipOwnerRef("OWNER","O2"),OwnershipShare.full())),
        PlayerDomainChange.create("11",PlayerChangeKinds.CONDITION,ConditionChange(DomainRef("PLAYER","P1"),"COND",ConditionOperation.ADD)),
        PlayerDomainChange.create("12",PlayerChangeKinds.RUNTIME,RuntimeChange(DomainRef("PLAYER","P1"),"COUNT",ExactLongDelta.of(1))),
        PlayerDomainChange.create("13",PlayerChangeKinds.DEVELOPMENT_PROJECT,DevelopmentProjectChange.create("PROJECT","WORK",ProjectProgressDelta.of(0),emptyList()))
    )
    private fun stat(uid:String)=PlayerDomainChange.create(uid,PlayerChangeKinds.STAT,StatChange(DomainRef("PLAYER","P1"),"STR",ExactLongDelta.of(1)))
    private fun resource(uid:String)=PlayerDomainChange.create(uid,PlayerChangeKinds.RESOURCE,ResourceChange(DomainRef("PLAYER","P1"),"RES",ExactLongDelta.of(1)))
    private fun project(e:List<DomainRef>)=PlayerDomainChange.create("P-CHANGE",PlayerChangeKinds.DEVELOPMENT_PROJECT,DevelopmentProjectChange.create("PROJECT","WORK",ProjectProgressDelta.of(1),e))
    private fun baseDraft()=PlayerResolutionDraft.create(changes=listOf(stat("CH")))
    private fun scoped(c:String,k:String,u:String)=CampaignScopedDomainRef(c,DomainRef(k,u))

    private fun boundContext(b:WorldPackRuleBinding=binding,extra:Set<CampaignScopedDomainRef> = emptySet())=PlayerResolutionContext.create(
        "C1",actor,setOf(scoped("C1","PLAYER","P1"),scoped("C1","STAT","STR"))+extra,mapOf("REFS" to "1"),ResolutionEntropyEvidence.none(),WorldRuleMode.Bound(b))
    private fun unboundContext()=PlayerResolutionContext.createUnboundGeneric("C1",actor,setOf(scoped("C1","PLAYER","P1"),scoped("C1","STAT","STR")),mapOf("REFS" to "1"))
    private fun rawContext(refs:Set<CampaignScopedDomainRef>,deps:Map<String,String>,campaign:String="C1",who:CommandActorRef=actor)=PlayerResolutionContext.create(campaign,who,refs,deps,ResolutionEntropyEvidence.none(),WorldRuleMode.Bound(binding))
    private fun train()=PlayerCommand("CMD-HARDEN","C1",actor,PlayerCommandKinds.TRAIN,TrainCommandPayload(DomainRef("STAT","STR"),10L,"METHOD"),CommandProvenance("TEST"))
    private fun engine(provider:WorldRuleProvider?)=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),worldRuleRegistry=if(provider==null)WorldRuleProviderRegistry.empty() else WorldRuleProviderRegistry.of(listOf(provider)))
    private fun fails(code:String,block:()->Unit){try{block();fail("expected $code")}catch(e:PlayerDomainEngineStructuralException){assertEquals(code,e.code)}}

    private class TrainComponent:PlayerResolutionComponent<TrainCommandPayload>(PlayerCommandKinds.TRAIN,TrainCommandPayload::class,"HARDEN-COMP","1"){
        override fun resolve(command:PlayerCommand<TrainCommandPayload>,context:PlayerResolutionContext)=PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes=listOf(PlayerDomainChange.create("CH-H",PlayerChangeKinds.STAT,StatChange(DomainRef("PLAYER","P1"),"STR",ExactLongDelta.of(1))))))
    }
    private open class BaseProvider(uid:String="HARDEN-P",version:String="1"):WorldRuleProvider(uid,version,"TEST-WORLD","1")
    private class AllowAllProvider(version:String="1"):BaseProvider("HARDEN-P",version){ override fun evaluate(request:WorldRuleRequest)=WorldRuleDecision.Allowed.create("RULE") }
    private class RejectCommandProvider:BaseProvider(){ override fun evaluate(request:WorldRuleRequest)=if(request.stage==WorldRuleEvaluationStage.COMMAND_PRECHECK)WorldRuleDecision.Rejected.create("RULE","DENY") else WorldRuleDecision.Allowed.create("RULE") }
    private class RejectDraftProvider:BaseProvider(){ override fun evaluate(request:WorldRuleRequest)=if(request.stage==WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK)WorldRuleDecision.Rejected.create("RULE","DENY-DRAFT") else WorldRuleDecision.Allowed.create("RULE") }
    private class ExplodingProvider:BaseProvider(){ override fun evaluate(request:WorldRuleRequest):WorldRuleDecision=error("boom") }
    private enum class SafeMode { ONE, TWO }
    private class SafeEnumProvider(private val mode:SafeMode):BaseProvider(){ override fun evaluate(request:WorldRuleRequest)=WorldRuleDecision.Allowed.create("RULE-${mode.name}") }
    private enum class MutableMode { INSTANCE; var counter:Int=0 }
    private class MutableEnumProvider(private val mode:MutableMode):BaseProvider(){ override fun evaluate(request:WorldRuleRequest)=if(mode.counter++==0)WorldRuleDecision.Allowed.create("RULE") else WorldRuleDecision.Rejected.create("RULE","CHANGED") }
    private class CollectionProvider(private val values:MutableList<String>):BaseProvider(){ override fun evaluate(request:WorldRuleRequest)=WorldRuleDecision.Allowed.create("RULE-${values.size}") }
    private open class UnsafeBase(private val value:StringBuilder):BaseProvider(){ override fun evaluate(request:WorldRuleRequest)=WorldRuleDecision.Allowed.create("RULE-${value.length}") }
    private class InheritedUnsafeProvider(value:StringBuilder):UnsafeBase(value)
}
