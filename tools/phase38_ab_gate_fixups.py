from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def p(rel): return ROOT / rel

def write(rel, text):
    path = p(rel)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')

def rep(rel, old, new):
    path = p(rel)
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'missing A+B gate anchor {rel}: {old[:120]!r}')
    path.write_text(text.replace(old, new), encoding='utf-8')

# Canonical test-only authority harness. Tests ask this harness for the same runtime-owned
# trusted contexts production code requires; no test constructs capability tokens directly.
write('app/src/test/java/com/rpgos/app/Phase38TrustedTestAuthority.kt', r'''package com.rpgos.app

internal data class TrustedAudienceFixture(
    val audience: AudienceContext,
    val trusted: TrustedPrincipalContext
)

internal object Phase38TrustedTestAuthority {
    fun player(campaignUid: String, controlledSubjectUids: Set<String> = emptySet()): TrustedAudienceFixture {
        val audience = VisibilityAudienceFactory.player(campaignUid)
        return TrustedAudienceFixture(
            audience,
            requireNotNull(Phase38RuntimeAuthority.application(audience, controlledSubjectUids = controlledSubjectUids))
        )
    }

    fun playerCharacter(campaignUid: String, pcUid: String): TrustedAudienceFixture {
        val audience = AudienceContext(
            campaignUid,
            AudienceKinds.PLAYER_CHARACTER,
            VisibilityPrincipalRef("ENTITY", pcUid)
        )
        val holder = KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER, pcUid, campaignUid)
        val cognition = TrustedCognitionResolver { requestedCampaign, principal ->
            if (requestedCampaign == campaignUid && principal == audience.principal) setOf(holder) else emptySet()
        }
        return TrustedAudienceFixture(
            audience,
            requireNotNull(
                Phase38RuntimeAuthority.application(
                    audience,
                    controlledSubjectUids = setOf(pcUid),
                    cognitionResolver = cognition
                )
            )
        )
    }

    fun diagnostic(campaignUid: String): TrustedAudienceFixture {
        val audience = VisibilityAudienceFactory.diagnostic(campaignUid)
        return TrustedAudienceFixture(
            audience,
            Phase38RuntimeAuthority.privileged(audience, Phase38RuntimeAuthority.PRIV_DIAGNOSTIC)
        )
    }
}
''')

# A / two-PC knowledge isolation: caller holder lists are gone; trusted cognition resolver supplies exactly one holder.
rep('app/src/test/java/com/rpgos/app/Phase38FinalClosureTest.kt', r'''    @Test fun playerAndPlayerCharacterAndTwoPcKnowledgeRemainIsolated(){
        val holderA=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-A",campaign)
        val holderB=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-B",campaign)
        val pcA=AudienceContext(campaign,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","PC-A"),listOf(holderA))
        val pcB=AudienceContext(campaign,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","PC-B"),listOf(holderB))
        val reasoning=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        val subA=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-A",holder=holderA)
        val subB=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-B",holder=holderB)
        assertNotEquals(AudienceKinds.PLAYER,pcA.audienceKindUid)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pcA,reasoning,subA)).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(pcA,reasoning,subB)).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pcB,reasoning,subB)).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,reasoning,subA)).level)
        val c2Holder=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-A","C2")
        assertTrue(runCatching{VisibilityRequest(pcA,reasoning,VisibilitySubjectRef("C2",VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-A",holder=c2Holder))}.isFailure)
    }
''', r'''    @Test fun playerAndPlayerCharacterAndTwoPcKnowledgeRemainIsolated(){
        val holderA=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-A",campaign)
        val holderB=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-B",campaign)
        val fixtureA=Phase38TrustedTestAuthority.playerCharacter(campaign,"PC-A")
        val fixtureB=Phase38TrustedTestAuthority.playerCharacter(campaign,"PC-B")
        val pcA=fixtureA.audience
        val pcB=fixtureB.audience
        val reasoning=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        val subA=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-A",holder=holderA)
        val subB=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-B",holder=holderB)
        assertNotEquals(AudienceKinds.PLAYER,pcA.audienceKindUid)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pcA,reasoning,subA),fixtureA.trusted).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(pcA,reasoning,subB),fixtureA.trusted).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pcB,reasoning,subB),fixtureB.trusted).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(pcB,reasoning,subA),fixtureB.trusted).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,reasoning,subA)).level)
        val c2Holder=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-A","C2")
        assertTrue(runCatching{VisibilityRequest(pcA,reasoning,VisibilitySubjectRef("C2",VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-A",holder=c2Holder))}.isFailure)
    }
''')

# B / conservative protected domains: public player remains denied; only trusted diagnostic half is full.
rep('app/src/test/java/com/rpgos/app/Phase38FinalClosureTest.kt', r'''    @Test fun conservativeRelationshipPoliticsEconomyOrganizationAreNotImplicitlyPublic(){
        val ui=PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)
        listOf(VisibilitySubjectKinds.RELATIONSHIP_DATA,VisibilitySubjectKinds.POLITICS_DATA,VisibilitySubjectKinds.ECONOMY_DATA,VisibilitySubjectKinds.ORGANIZATION_DATA).forEach{
            assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,ui,VisibilitySubjectRef(campaign,it,"X"))).level)
        }
        val diagnostic=VisibilityAudienceFactory.diagnostic(campaign);val dp=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(diagnostic,dp,VisibilitySubjectRef(campaign,VisibilitySubjectKinds.POLITICS_DATA,"X"))).level)
    }
''', r'''    @Test fun conservativeRelationshipPoliticsEconomyOrganizationAreNotImplicitlyPublic(){
        val ui=PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)
        listOf(VisibilitySubjectKinds.RELATIONSHIP_DATA,VisibilitySubjectKinds.POLITICS_DATA,VisibilitySubjectKinds.ECONOMY_DATA,VisibilitySubjectKinds.ORGANIZATION_DATA).forEach{
            assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,ui,VisibilitySubjectRef(campaign,it,"X"))).level)
        }
        val diagnosticFixture=Phase38TrustedTestAuthority.diagnostic(campaign)
        val diagnostic=diagnosticFixture.audience;val dp=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        val politics=VisibilityRequest(diagnostic,dp,VisibilitySubjectRef(campaign,VisibilitySubjectKinds.POLITICS_DATA,"X"))
        assertEquals(DisclosureLevel.DENY,authority.decide(politics).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(politics,diagnosticFixture.trusted).level)
    }
''')

# C / diagnostic capability: descriptor alone denied; trusted capability full; trusted diagnostic context cannot authorize player request.
rep('app/src/test/java/com/rpgos/app/Phase38FinalClosureTest.kt', r'''    @Test fun diagnosticVisibilityDoesNotBecomePlayerVisibilityAndStrategicDisclosureDoesNotAcquireKnowledge(){
        val diagnostic=VisibilityAudienceFactory.diagnostic(campaign)
        val diagPurpose=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        val truth=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T")
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(diagnostic,diagPurpose,truth)).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI),truth)).level)
    }
''', r'''    @Test fun diagnosticVisibilityDoesNotBecomePlayerVisibilityAndStrategicDisclosureDoesNotAcquireKnowledge(){
        val diagnosticFixture=Phase38TrustedTestAuthority.diagnostic(campaign)
        val diagnostic=diagnosticFixture.audience
        val diagPurpose=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        val truth=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T")
        val diagnosticRequest=VisibilityRequest(diagnostic,diagPurpose,truth)
        assertEquals(DisclosureLevel.DENY,authority.decide(diagnosticRequest).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(diagnosticRequest,diagnosticFixture.trusted).level)
        val playerRequest=VisibilityRequest(player,PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI),truth)
        assertEquals(DisclosureLevel.DENY,authority.decide(playerRequest).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(playerRequest,diagnosticFixture.trusted).level)
    }
''')

# D / structural player-vs-PC distinction uses trusted cognition mapping, not caller-declared holder authority.
rep('app/src/test/java/com/rpgos/app/Phase38VisibilityBoundaryTest.kt', r'''    @Test fun playerAndPlayerCharacterAreStructurallyDistinct(){
        val pc=AudienceContext(campaign,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","PC"),listOf(KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC",campaign)))
        assertNotEquals(player.audienceKindUid,pc.audienceKindUid)
        val holder=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC",campaign)
        val subject=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC",holder=holder)
        val reasoning=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,reasoning,subject)).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pc,reasoning,subject)).level)
    }
''', r'''    @Test fun playerAndPlayerCharacterAreStructurallyDistinct(){
        val fixture=Phase38TrustedTestAuthority.playerCharacter(campaign,"PC")
        val pc=fixture.audience
        assertNotEquals(player.audienceKindUid,pc.audienceKindUid)
        val holder=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC",campaign)
        val subject=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC",holder=holder)
        val reasoning=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,reasoning,subject)).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pc,reasoning,subject),fixture.trusted).level)
    }
''')

# Visual fixture migration: legal edit authorization must bind exact source visual, source bytes and instruction.
rep('app/src/test/java/com/rpgos/app/Phase38FinalClosureTest.kt', r'''    @Test fun sceneEnvelopeCannotBeReusedForEditAndEditCannotGainHiddenActor(){
        val instruction="brighten the disclosed foreground"
        val edit=Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,"VISUAL","V1",instruction,VisualInputOrigins.USER_STANDALONE,requestUid="EDIT-1")
        edit.requireRequest(campaign,VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,instruction)
        assertTrue(runCatching{Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.SCENE_VISUALIZATION),VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,"VISUAL","V1",instruction)}.isFailure)
        val editClient=source("app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt")
        assertFalse(editClient.contains("CampaignTruth"));assertFalse(editClient.contains("gm_summary"));assertFalse(editClient.contains("npc_memories"))
    }
''', r'''    @Test fun sceneEnvelopeCannotBeReusedForEditAndEditCannotGainHiddenActor(){
        val instruction="brighten the disclosed foreground"
        val sourceBytes="source-image-v1".toByteArray()
        val sourceDigest=Phase38VisualAuthorization.digestBytes(sourceBytes)
        val edit=Phase38VisualAuthorization.authorize(
            env(VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
            "VISUAL","V1",instruction,VisualInputOrigins.USER_STANDALONE,requestUid="EDIT-1",
            sourceVisualUid="V1",sourceImageSha256=sourceDigest
        )
        val valid=VisualSemanticRequest(
            campaign,AudienceKinds.PLAYER,"HUMAN_PLAYER",VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
            "VISUAL","V1","EDIT-1",VisualRequestKinds.EDIT,instruction,
            sourceVisualUid="V1",sourceImageSha256=sourceDigest
        )
        edit.requireRequest(valid)
        assertTrue(runCatching{Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.SCENE_VISUALIZATION),VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,"VISUAL","V1",instruction,sourceVisualUid="V1",sourceImageSha256=sourceDigest)}.isFailure)
        assertTrue(runCatching{edit.requireRequest(valid.copy(sourceVisualUid="V2"))}.isFailure)
        assertTrue(runCatching{edit.requireRequest(valid.copy(sourceImageSha256=Phase38VisualAuthorization.digestBytes("source-image-v2".toByteArray())))}.isFailure)
        assertTrue(runCatching{edit.requireRequest(valid.copy(promptOrInstruction="darken the disclosed foreground"))}.isFailure)
        val editClient=source("app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt")
        assertFalse(editClient.contains("CampaignTruth"));assertFalse(editClient.contains("gm_summary"));assertFalse(editClient.contains("npc_memories"))
    }
''')

# Explicit symbol-level trusted gateway classification.
rel='app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt'
text=p(rel).read_text(encoding='utf-8')
needle='class ProtectedCampaignReadRepository internal constructor('
if needle not in text: raise SystemExit('missing ProtectedCampaignReadRepository anchor')
text=text.replace(needle, '''@Target(AnnotationTarget.CLASS)\n@Retention(AnnotationRetention.SOURCE)\ninternal annotation class TrustedProtectedReadGateway\n\n@TrustedProtectedReadGateway\nclass ProtectedCampaignReadRepository internal constructor(''', 1)
p(rel).write_text(text,encoding='utf-8')

rel='app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt'
text=p(rel).read_text(encoding='utf-8')
text=text.replace('enum class ProtectedConsumerCapability {\n', 'enum class ProtectedConsumerCapability {\n    TRUSTED_GATEWAY,\n', 1)
insert_after='''enum class ProtectedConsumerCapability {\n    TRUSTED_GATEWAY,\n    PROJECTION_AUTHORITY,\n    PROJECTED_CONSUMER,\n    DIAGNOSTIC_PROJECTED_CONSUMER,\n    AUTHORITY_INTERNAL,\n    PRESENTATION_AFTER_PROJECTION,\n    ADMINISTRATIVE_WRITE_ONLY,\n    AUTHORITY_METADATA\n}\n'''
if insert_after not in text: raise SystemExit('capability enum anchor missing')
text=text.replace(insert_after, insert_after + '''\nenum class ProtectedEntryPointClassification {\n    TRUSTED_GATEWAY, PROJECTED_CONSUMER, FORBIDDEN_DIRECT_CONSUMER\n}\n''', 1)
contract_anchor='''        c("visibility-consumer-inventory", "app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt", ProtectedConsumerCapability.AUTHORITY_METADATA,\n            VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)\n'''
gateway_contract='''        c("protected-read-gateway", "app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt", ProtectedConsumerCapability.TRUSTED_GATEWAY,\n            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,\n            VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),\n'''
if contract_anchor not in text: raise SystemExit('inventory contract anchor missing')
text=text.replace(contract_anchor, gateway_contract + contract_anchor, 1)
old_guard='''    private val forbiddenDirectSymbols = listOf("CampaignTruthStore(", "PlayerStateStore(", "KnowledgeStore(", ".openWorldDb()", ".openCoreDb()")\n    fun hasForbiddenDirectProtectedEntryPoint(source: String): Boolean = forbiddenDirectSymbols.any(source::contains)\n\n    fun looksProtected(source: String): Boolean = protectedMarkers.any(source::contains)\n    fun requireClassifiedIfProtected(sourcePath:String,sourceText:String):ProtectedConsumerContract? =\n        if(looksProtected(sourceText)) requireClassified(sourcePath) else null\n'''
new_guard='''    private val forbiddenDirectSymbols = listOf("CampaignTruthStore(", "PlayerStateStore(", "KnowledgeStore(", ".openWorldDb()", ".openCoreDb()")\n    fun hasForbiddenDirectProtectedEntryPoint(source: String): Boolean = forbiddenDirectSymbols.any(source::contains)\n\n    private fun trustedGatewayBody(source: String): IntRange? {\n        val annotation = source.indexOf("@TrustedProtectedReadGateway")\n        if (annotation < 0) return null\n        val classPos = source.indexOf("class ProtectedCampaignReadRepository", annotation)\n        if (classPos < 0) return null\n        val open = source.indexOf('{', classPos)\n        if (open < 0) return null\n        var depth = 0\n        for (i in open until source.length) {\n            when (source[i]) {\n                '{' -> depth++\n                '}' -> {\n                    depth--\n                    if (depth == 0) return open..i\n                }\n            }\n        }\n        return null\n    }\n\n    fun entryPointClassification(sourcePath: String, sourceText: String): ProtectedEntryPointClassification {\n        val directPositions = forbiddenDirectSymbols.flatMap { symbol ->\n            buildList {\n                var start = 0\n                while (true) {\n                    val at = sourceText.indexOf(symbol, start)\n                    if (at < 0) break\n                    add(at)\n                    start = at + symbol.length\n                }\n            }\n        }\n        if (directPositions.isEmpty()) return ProtectedEntryPointClassification.PROJECTED_CONSUMER\n        val contract = contractForSource(sourcePath) ?: return ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER\n        return when (contract.capability) {\n            ProtectedConsumerCapability.TRUSTED_GATEWAY -> {\n                val scope = trustedGatewayBody(sourceText) ?: return ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER\n                if (directPositions.all { it in scope }) ProtectedEntryPointClassification.TRUSTED_GATEWAY\n                else ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER\n            }\n            ProtectedConsumerCapability.PROJECTED_CONSUMER,\n            ProtectedConsumerCapability.DIAGNOSTIC_PROJECTED_CONSUMER,\n            ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION -> ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER\n            else -> ProtectedEntryPointClassification.PROJECTED_CONSUMER\n        }\n    }\n\n    fun looksProtected(source: String): Boolean = protectedMarkers.any(source::contains)\n    fun requireClassifiedIfProtected(sourcePath:String,sourceText:String):ProtectedConsumerContract? {\n        if (!looksProtected(sourceText)) return null\n        val contract = requireClassified(sourcePath)\n        require(entryPointClassification(sourcePath, sourceText) != ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER) {\n            "RPGOS-VISIBILITY:FORBIDDEN_DIRECT_PROTECTED_ENTRY_POINT:$sourcePath"\n        }\n        return contract\n    }\n'''
if old_guard not in text: raise SystemExit('post-hardening guard anchor missing')
text=text.replace(old_guard,new_guard,1)
p(rel).write_text(text,encoding='utf-8')

# Upgrade repository-wide inventory test: trusted gateway passes, ordinary direct consumer and out-of-scope gateway method fail.
rep('app/src/test/java/com/rpgos/app/Phase38VisibilityBoundaryTest.kt', r'''    @Test fun visibilityConsumerInventoryIsRepositoryWideAndFailClosed(){
        VisibilityConsumerInventory.validateUnique()
        val root=repoRoot()
        val productionFiles = sequenceOf(
            File(root,"app/src/main/java").walkTopDown().filter{it.isFile&&it.extension=="kt"},
            File(root,"backend").walkTopDown().filter{it.isFile&&it.extension=="py"}
        ).flatten()
        val unclassified=productionFiles.mapNotNull{f->
            val path=f.relativeTo(root).invariantSeparatorsPath
            if(VisibilityConsumerInventory.looksProtected(f.readText()) && VisibilityConsumerInventory.contractForSource(path)==null) path else null
        }.toList()
        assertTrue("unclassified protected consumers: $unclassified",unclassified.isEmpty())
        assertTrue(runCatching{VisibilityConsumerInventory.requireClassifiedIfProtected("app/src/main/java/com/rpgos/app/NewHiddenConsumer.kt","class X { val x = CampaignTruthStore(db, c) }")}.isFailure)
    }
''', r'''    @Test fun visibilityConsumerInventoryIsRepositoryWideAndFailClosed(){
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
''')

print('phase38 A+B gate closure fixups applied')
