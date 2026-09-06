package com.rpgos.app

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Phase59CanonicalRegressionTest {
    private lateinit var context: Context
    private lateinit var repository: UnifiedGameRepository
    private lateinit var filesRoot: File

    @Before fun setUp() {
        filesRoot = File.createTempFile("phase59-regressions-", ".tmp").apply { delete(); mkdirs() }
        val base = RuntimeEnvironment.getApplication()
        base.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesRoot
        }
        repository = UnifiedGameRepository(context).also { it.bootstrap() }
    }

    @After fun tearDown() {
        repository.closeBackgroundWorkForTest()
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        filesRoot.deleteRecursively()
    }

    @Test fun consolidatedMemoryProjectionRejectsNpcAndOrganizationKindsForControlledPlayer() {
        val campaign = repository.activeCampaignRef().campaignId
        val audience = VisibilityAudienceFactory.player(campaign)
        val purpose = PurposeContext(campaign, VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        val payload = JSONObject().apply {
            put("holder_uid", "PLAYER-1")
            put("subject_kind_uid", "WORLD")
            put("subject_uid", "WORLD-1")
            put("predicate_uid", "OWES")
            put("object_value", "FAVOR")
            put("polarity", SemanticAssertionPolarity.AFFIRMED.name)
            put("epistemic_kind", SemanticAssertionEpistemicKind.MEMORY.name)
            put("lifecycle", SemanticAssertionLifecycle.ACTIVE.name)
        }

        val npcArtifact = ActiveMemoryArtifactRevision(
            campaign, HistoryGenerationUid("GEN-REGRESSION"), "ASSERT", "ASSERT-REV",
            MemoryArtifactKind.SEMANTIC_ASSERTION, "LEAF", 1L, 7, payload.apply {
                put("holder_kind_uid", "NPC")
            }.toString()
        )
        val orgArtifact = npcArtifact.copy(
            payloadJson = JSONObject(payload.toString()).apply {
                put("holder_kind_uid", KnowledgeHolderKinds.ORGANIZATION)
            }.toString()
        )

        val projector = ConsolidatedMemorySemanticProjector(activePlayerUid = { "PLAYER-1" })
        assertTrue(projector.project(npcArtifact, audience, purpose).isEmpty())
        assertTrue(projector.project(orgArtifact, audience, purpose).isEmpty())
    }

    @Test fun worldPackProjectionForPlayerDoesNotExposeSourceInternalFields() {
        val campaign = repository.activeCampaignRef().campaignId
        val option = repository.characterCreationCatalog().options.firstOrNull { it.kind == CharacterCreationDefinitionKind.STAT }
            ?: throw AssertionError("World Pack stats must contain at least one option in test fixture")

        val projection = semanticWorldPackProjection(campaign, AudienceKinds.PLAYER, VisibilityPurposeKinds.GAMEPLAY_NARRATION, option)
        listOf(
            "SOURCE_URI",
            "SOURCE_HASH",
            "SOURCE_REVISION",
            "SOURCE_CLASSIFICATION",
            "MATERIALIZATION_LEVEL"
        ).forEach { forbidden ->
            assertFalse("Projection must not expose technical field $forbidden", projection.text.contains(forbidden))
        }
    }

    @Test fun semanticMemoryAssertionWithExpiredValidUntilCannotPassForLaterAsOf() {
        val campaign = repository.activeCampaignRef().campaignId
        val audience = AudienceContext(campaign, AudienceKinds.GM_RUNTIME, VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME, "LOCAL_GM"))
        val purpose = PurposeContext(campaign, VisibilityPurposeKinds.INTERNAL_SIMULATION)
        val assertionPayload = JSONObject().apply {
            put("holder_kind_uid", "NPC")
            put("holder_uid", "ANY")
            put("subject_kind_uid", "NPC")
            put("subject_uid", "NPC-1")
            put("predicate_uid", "ALLY")
            put("object_value", "TRUE")
            put("polarity", SemanticAssertionPolarity.AFFIRMED.name)
            put("epistemic_kind", SemanticAssertionEpistemicKind.MEMORY.name)
            put("lifecycle", SemanticAssertionLifecycle.ACTIVE.name)
            put("valid_until_order", 7)
        }
        val artifact = ActiveMemoryArtifactRevision(
            campaign,
            HistoryGenerationUid("GEN-REGRESSION"),
            "ASSERT-LIMIT",
            "ASSERT-LIMIT-REV",
            MemoryArtifactKind.SEMANTIC_ASSERTION,
            "LEAF", 2L, 3, assertionPayload.toString()
        )
        val asOfLater = ConsolidatedMemorySemanticProjector(evaluationOrder = { 10L }, accessPolicyVersion = 1L)
            .project(artifact, audience, purpose)
        val asOfEarlier = ConsolidatedMemorySemanticProjector(evaluationOrder = { 3L }, accessPolicyVersion = 1L)
            .project(artifact, audience, purpose)

        assertTrue(asOfLater.isEmpty())
        assertEquals(1, asOfEarlier.size)
        assertEquals("MEMORY:ASSERT-LIMIT-REV", asOfEarlier.single().canonicalRecordUid)
    }

    @Test fun worldPackFingerprintRefreshAndStaleUidCleanupUseCurrentCatalogSnapshot() {
        val campaign = repository.activeCampaignRef().campaignId
        val option = repository.characterCreationCatalog().options.firstOrNull { it.kind == CharacterCreationDefinitionKind.STAT }
            ?: throw AssertionError("World Pack stats must contain at least one option in test fixture")
        val targetUid = semanticWorldPackRecordUid(option)
        val staleUid = "WORLDPACK:DEPRECATED-UID"

        val index = WorldPackTrackingIndex()
        val coordinator = ImmediateSemanticIndexCoordinator(repository, FakeEmbeddingProvider(), index, campaignUid = campaign)
        val trackedCatalog = repository.characterCreationCatalog().options.asSequence().map(::semanticWorldPackRecordUid).toMutableSet().also { it.add(staleUid) }
        index.setAuthorized(SEMANTIC_NAMESPACE_WORLD_PACK, AudienceKinds.PLAYER, VisibilityPurposeKinds.GAMEPLAY_NARRATION, trackedCatalog)
        index.setAuthorized(SEMANTIC_NAMESPACE_WORLD_PACK, AudienceKinds.GM_RUNTIME, VisibilityPurposeKinds.INTERNAL_SIMULATION, trackedCatalog)

        val firstStatus = coordinator.catchUp()
        assertTrue("World Pack first catch-up should become ready", firstStatus.ready)
        assertEquals(
            "One namespace-wide removal must clear the stale uid from every audience projection",
            1,index.removeCalls.count { it.canonicalRecordUid == staleUid }
        )

        val playerUpsertsBefore = index.upsertsByUid(targetUid).count { it.projection.audienceUid == AudienceKinds.PLAYER }
        val gmUpsertsBefore = index.upsertsByUid(targetUid).count { it.projection.audienceUid == AudienceKinds.GM_RUNTIME }

        LocalGameStore(context).openGameplaySaveDb().use { db ->
            db.beginTransaction()
            GameplayMutationDatabaseGuards.enterAdmin(db,campaign)
            try{
                db.execSQL("UPDATE stat_definitions SET stat_key=? WHERE stat_uid=?", arrayOf("${option.displayName}-UPDATED", option.definitionUid))
                db.setTransactionSuccessful()
            }finally{
                GameplayMutationDatabaseGuards.leaveAdmin(db,campaign)
                db.endTransaction()
            }
        }

        val secondStatus = coordinator.catchUp()
        assertTrue("World Pack second catch-up should become ready", secondStatus.ready)

        val playerUpsertsAfter = index.upsertsByUid(targetUid).count { it.projection.audienceUid == AudienceKinds.PLAYER }
        val gmUpsertsAfter = index.upsertsByUid(targetUid).count { it.projection.audienceUid == AudienceKinds.GM_RUNTIME }
        assertTrue("Updated source content should refresh player world-pack fingerprint", playerUpsertsAfter > playerUpsertsBefore)
        assertTrue("Updated source content should refresh GM world-pack fingerprint", gmUpsertsAfter > gmUpsertsBefore)
    }

    private class WorldPackTrackingIndex : SemanticIndexPort {
        override val version = SemanticIndexVersion()
        private val checkpointByCampaign = mutableMapOf<String, Long>()
        private val authorizedByScope = mutableMapOf<String, LinkedHashSet<String>>()
        private val upsertLog = mutableListOf<SemanticIndexedDocument>()
        private val projections = mutableMapOf<String, SemanticSourceProjectionState>()

        val removeCalls = mutableListOf<RemoveCall>()
        data class RemoveCall(val campaignUid: String, val namespaceUid: String, val canonicalRecordUid: String)

        fun setAuthorized(namespaceUid: String, audienceUid: String, purposeUid: String, uids: Set<String>) {
            authorizedByScope[key(namespaceUid, audienceUid, purposeUid)] = LinkedHashSet(uids)
        }

        fun upsertsByUid(uid: String): List<SemanticIndexedDocument> = upsertLog.filter { it.projection.canonicalRecordUid == uid }

        override fun upsertBatch(documents: List<SemanticIndexedDocument>) {
            upsertLog += documents
            documents.forEach { document ->
                val projection = document.projection
                projections[key(document)] = SemanticSourceProjectionState(
                    sourceAsOfOrder = projection.asOfOrder,
                    sourceVersion = projection.sourceVersion,
                    sourceFingerprint = projection.sourceFingerprint,
                    principalUid = projection.principalUid,
                    holderSetFingerprint = projection.holderSetFingerprint,
                    historyGenerationUid = projection.historyGenerationUid ?: HistoryGenerationUid("GLOBAL"),
                    accessPolicyVersion = projection.accessPolicyVersion,
                    activePlayerUid = projection.activePlayerUid,
                    projectionVersionUid = projection.projectionVersionUid
                )
            }
        }

        override fun remove(campaignUid: String, namespaceUid: String, canonicalRecordUid: String) {
            removeCalls += RemoveCall(campaignUid, namespaceUid, canonicalRecordUid)
            val stalePrefix = "$namespaceUid|"
            projections.keys.removeAll { it.startsWith(stalePrefix) && it.endsWith("|$canonicalRecordUid") }
        }

        override fun authorizedRecordUids(
            campaignUid: String,
            namespaceUid: String,
            audienceUid: String,
            purposeUid: String,
            asOfOrder: Long
        ): Set<String> = when (namespaceUid) {
            SEMANTIC_NAMESPACE_WORLD_PACK -> authorizedByScope[key(namespaceUid, audienceUid, purposeUid)]?.toSet() ?: emptySet()
            else -> emptySet()
        }

        override fun searchAuthorized(request: SemanticSearchRequest): List<SemanticCandidate> = emptyList()

        override fun currentProjections(request: SemanticSearchRequest): Map<String, SemanticSourceProjectionState> {
            val current = linkedMapOf<String, SemanticSourceProjectionState>()
            request.authorizedRecordUids.forEach { uid ->
                projections[key(request.namespaceUid, request.audienceUid, request.purposeUid, uid)]?.let { current[uid] = it }
            }
            return current
        }

        override fun checkpoint(campaignUid: String): Long = checkpointByCampaign.getOrDefault(campaignUid, 0L)

        override fun advanceCheckpoint(campaignUid: String, committedOrder: Long) {
            val current = checkpoint(campaignUid)
            require(committedOrder >= current) { "SEMANTIC_CHECKPOINT_REGRESSION" }
            checkpointByCampaign[campaignUid] = committedOrder
        }

        override fun status(campaignUid: String): SemanticIndexStatus = SemanticIndexStatus(
            ready = true,
            recordCount = projections.keys.map { it.substringAfterLast("|") }.distinct().size.toLong(),
            chunkCount = projections.size.toLong(),
            lastIndexedCommitOrder = checkpoint(campaignUid),
            version = version
        )

        override fun clear(campaignUid: String) {
            projections.keys.removeAll { it.contains(campaignUid) }
            upsertLog.removeAll { it.projection.campaignUid == campaignUid }
            checkpointByCampaign.remove(campaignUid)
        }

        override fun close() = Unit

        private fun key(namespaceUid: String, audienceUid: String, purposeUid: String) = "$namespaceUid|$audienceUid|$purposeUid"
        private fun key(namespaceUid: String, audienceUid: String, purposeUid: String, canonicalRecordUid: String) =
            "$namespaceUid|$audienceUid|$purposeUid|$canonicalRecordUid"
        private fun key(document: SemanticIndexedDocument) = key(
            document.projection.namespaceUid,
            document.projection.audienceUid,
            document.projection.purposeUid,
            document.projection.canonicalRecordUid
        )
    }

    private class FakeEmbeddingProvider : EmbeddingProviderPort {
        override val capabilities = EmbeddingCapabilities(
            providerUid = "TEST",
            modelUid = "TEST",
            modelRevision = "1",
            sourceDimensions = 256,
            supportedDimensions = setOf(256),
            maximumContextUnits = 8192,
            maximumBatchSize = 8,
            supportedBackends = setOf(EmbeddingBackend.CPU)
        )

        override fun availability() = EmbeddingAvailability(EmbeddingAvailabilityState.READY, "READY")
        override fun open() = availability()
        override fun embedBatch(request: EmbeddingRequest): EmbeddingBatchResult {
            return EmbeddingBatchResult.Success(
                request.texts.map { _ ->
                    FloatArray(256) { index -> (index % 17).toFloat() / 17f }
                },
                request.requestUid
            )
        }

        override fun cancel(requestUid: String) = Unit
        override fun close() = Unit
    }
}

private fun <T, V> Iterable<T>.associateWithNotNull(transform: (T) -> V?): Map<T, V> =
    associateWith(transform).filterValues { it != null }.mapValues { it.value!! }
