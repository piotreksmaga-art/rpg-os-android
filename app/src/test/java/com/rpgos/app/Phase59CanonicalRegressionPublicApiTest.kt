package com.rpgos.app

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase59CanonicalRegressionPublicApiTest {
    private lateinit var context: Context
    private lateinit var repository: UnifiedGameRepository
    private lateinit var filesRoot: File

    @Before
    fun setUp() {
        filesRoot = File.createTempFile("phase59-public-regressions-", ".tmp").apply {
            delete()
            mkdirs()
        }
        val base = RuntimeEnvironment.getApplication()
        base.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesRoot
        }
        repository = UnifiedGameRepository(context).also { it.bootstrap() }
    }

    @After
    fun tearDown() {
        repository.closeBackgroundWorkForTest()
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        filesRoot.deleteRecursively()
    }

    @Test
    fun consolidatedMemoryHolderNpcAndOrganizationMatchingActivePlayerUidIsNotVisibleToPlayerAudience() {
        val campaign = repository.activeCampaignRef().campaignId
        val audience = VisibilityAudienceFactory.player(campaign)
        val purpose = PurposeContext(campaign, VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        val projector = ConsolidatedMemorySemanticProjector(activePlayerUid = { "PLAYER-1" })

        val payload = JSONObject().apply {
            put("holder_uid", "PLAYER-1")
            put("subject_kind_uid", "WORLD")
            put("subject_uid", "WORLD-1")
            put("predicate_uid", "OWES")
            put("object_value", "FAVOR")
            put("polarity", SemanticAssertionPolarity.AFFIRMED.name)
            put("epistemic_kind", SemanticAssertionEpistemicKind.MEMORY.name)
            put("lifecycle", SemanticAssertionLifecycle.ACTIVE.name)
            put("valid_until_order", 99L)
        }

        listOf("NPC", KnowledgeHolderKinds.ORGANIZATION).forEach { holderKind ->
            val artifactPayload = JSONObject(payload.toString()).apply {
                put("holder_kind_uid", holderKind)
            }
            val artifact = ActiveMemoryArtifactRevision(
                campaignUid = campaign,
                historyGenerationUid = HistoryGenerationUid("GEN-PUBLIC-REGRESSION"),
                logicalArtifactUid = "ASSERT-$holderKind",
                artifactRevisionUid = "ASSERT-$holderKind-REV",
                artifactKind = MemoryArtifactKind.SEMANTIC_ASSERTION,
                sourceLeafSetFingerprint = "LEAF-FP",
                derivationVersion = 1,
                asOfCommittedOrder = 7,
                payloadJson = artifactPayload.toString()
            )

            val projection = projector.project(artifact, audience, purpose)
            assertTrue("Holder kind $holderKind should be denied for player audience", projection.isEmpty())
        }
    }

    @Test
    fun playerWorldPackProjectionTextDoesNotLeakSourceTechnicalMetadata() {
        val projection = semanticWorldPackProjection(
            campaignUid = "C1",
            audienceUid = AudienceKinds.PLAYER,
            purposeUid = VisibilityPurposeKinds.GAMEPLAY_NARRATION,
            option = CharacterCreationDefinitionOption(
                kind = CharacterCreationDefinitionKind.STAT,
                definitionUid = "REG-STAT-1",
                displayName = "Siła",
                minimumValue = 1.0,
                maximumValue = 12.0
            )
        )
        listOf(
            "SOURCE_URI",
            "SOURCE_HASH",
            "SOURCE_REVISION",
            "SOURCE_CLASSIFICATION",
            "MATERIALIZATION_LEVEL"
        ).forEach { forbidden ->
            assertFalse("Projection for player should not expose $forbidden", projection.text.contains(forbidden))
        }
    }

    @Test
    fun expiredSemanticMemoryAssertionCannotPassForLaterAsOf() {
        val campaign = repository.activeCampaignRef().campaignId
        val audience = AudienceContext(campaign, AudienceKinds.GM_RUNTIME, VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME, "LOCAL_GM"))
        val purpose = PurposeContext(campaign, VisibilityPurposeKinds.INTERNAL_SIMULATION)
        val payload = JSONObject().apply {
            put("holder_kind_uid", "NPC")
            put("holder_uid", "NPC-1")
            put("subject_kind_uid", "NPC")
            put("subject_uid", "NPC-1")
            put("predicate_uid", "ALLY")
            put("object_value", "TRUE")
            put("polarity", SemanticAssertionPolarity.AFFIRMED.name)
            put("epistemic_kind", SemanticAssertionEpistemicKind.MEMORY.name)
            put("lifecycle", SemanticAssertionLifecycle.ACTIVE.name)
            put("valid_until_order", 7L)
        }
        val assertion = ActiveMemoryArtifactRevision(
            campaignUid = campaign,
            historyGenerationUid = HistoryGenerationUid("GEN-PUBLIC-REGRESSION"),
            logicalArtifactUid = "ASSERT-LIMIT",
            artifactRevisionUid = "ASSERT-LIMIT-REV",
            artifactKind = MemoryArtifactKind.SEMANTIC_ASSERTION,
            sourceLeafSetFingerprint = "LEAF-FP",
            derivationVersion = 2,
            asOfCommittedOrder = 3,
            payloadJson = payload.toString()
        )

        val asOfLater = ConsolidatedMemorySemanticProjector(
            evaluationOrder = { 10L },
            accessPolicyVersion = 1L
        ).project(assertion, audience, purpose)
        val asOfEarlier = ConsolidatedMemorySemanticProjector(
            evaluationOrder = { 3L },
            accessPolicyVersion = 1L
        ).project(assertion, audience, purpose)

        assertTrue(asOfLater.isEmpty())
        assertEquals(1, asOfEarlier.size)
        assertEquals("MEMORY:ASSERT-LIMIT-REV", asOfEarlier.single().canonicalRecordUid)
    }

    @Test
    fun worldPackFingerprintRefreshAndStaleUidRemovalUseCurrentCatalogSnapshot() {
        val campaign = repository.activeCampaignRef().campaignId
        val initialPackRoot = File(filesRoot, "worldpacks-import")
        val targetPackDir = "Naruto.worldpack"
        val packageManager = RpgPackageManager(context)
        val packManagerName = "PHASE59_WORLD_PACK"
        val firstPackDir = File(initialPackRoot, "first").also { it.mkdirs() }
        val secondPackDir = File(initialPackRoot, "second").also { it.mkdirs() }

        val firstStartLocationUid = "PH59-LOC-ALIVE"
        val firstDeadLocationUid = "PH59-LOC-REMOVED"
        writeWorldPack(
            firstPackDir,
            worldPackUid = packManagerName,
            version = "1",
            locations = listOf(
                WorldLocationRow(firstStartLocationUid, "Start 1", "initial"),
                WorldLocationRow(firstDeadLocationUid, "Removed", "to be removed")
            )
        )
        assertTrue(packageManager.validatedImportWorldPack(packageZip(firstPackDir), targetPackDir).ok)

        val firstCatalog = repository.characterCreationCatalog().options.filter { it.kind == CharacterCreationDefinitionKind.STARTING_LOCATION }
        require(firstCatalog.isNotEmpty()) { "World Pack should contain starting locations in test fixture" }
        val targetUid = semanticWorldPackRecordUid(firstCatalog.first { it.definitionUid == firstStartLocationUid })
        val staleUid = semanticWorldPackRecordUid(firstCatalog.first { it.definitionUid == firstDeadLocationUid })

        val index = RegressionWorldPackIndex()
        val coordinator = ImmediateSemanticIndexCoordinator(
            repository,
            RegressionEmbeddingProvider(),
            index,
            campaignUid = campaign
        )
        try {
            val initialCatalogUids = repository.characterCreationCatalog().options.map(::semanticWorldPackRecordUid).toMutableSet()
            index.setAuthorized(
                namespaceUid = SEMANTIC_NAMESPACE_WORLD_PACK,
                audienceUid = AudienceKinds.PLAYER,
                purposeUid = VisibilityPurposeKinds.GAMEPLAY_NARRATION,
                uids = initialCatalogUids
            )
            index.setAuthorized(
                namespaceUid = SEMANTIC_NAMESPACE_WORLD_PACK,
                audienceUid = AudienceKinds.GM_RUNTIME,
                purposeUid = VisibilityPurposeKinds.INTERNAL_SIMULATION,
                uids = initialCatalogUids
            )

            val first = coordinator.catchUp()
            assertTrue(first.ready)
            assertEquals(
                0,
                index.removeCalls.count { it.canonicalRecordUid == staleUid }
            )

            val firstPlayerFingerprint = index.lastFingerprintFor(targetUid, AudienceKinds.PLAYER)
            val firstGmFingerprint = index.lastFingerprintFor(targetUid, AudienceKinds.GM_RUNTIME)
            assertNotNull(firstPlayerFingerprint)
            assertNotNull(firstGmFingerprint)

            writeWorldPack(
                secondPackDir,
                worldPackUid = packManagerName,
                version = "2",
                locations = listOf(
                    WorldLocationRow(firstStartLocationUid, "Start 1 - UPDATED", "updated")
                )
            )
            assertTrue(packageManager.validatedImportWorldPack(packageZip(secondPackDir), targetPackDir).ok)

            val second = coordinator.catchUp()
            assertTrue(second.ready)

            val secondPlayerFingerprint = index.lastFingerprintFor(targetUid, AudienceKinds.PLAYER)
            val secondGmFingerprint = index.lastFingerprintFor(targetUid, AudienceKinds.GM_RUNTIME)
            assertNotEquals(firstPlayerFingerprint, secondPlayerFingerprint)
            assertNotEquals(firstGmFingerprint, secondGmFingerprint)
            assertEquals(1, index.removeCalls.count { it.canonicalRecordUid == staleUid })
        } finally {
            coordinator.close()
        }
    }

    private data class WorldLocationRow(val uid: String, val name: String, val description: String)

    private fun writeWorldPack(dir: File, worldPackUid: String, version: String, locations: List<WorldLocationRow>) {
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "worldpack.json").writeText("""{"id":"$worldPackUid","version":"$version","engine_api":"1"}""")
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).use { db ->
            db.execSQL("CREATE TABLE map_locations_v2(location_uid TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, location_type TEXT, region_uid TEXT, description TEXT)")
            locations.forEach { row ->
                db.execSQL(
                    "INSERT INTO map_locations_v2(location_uid,name,location_type,region_uid,description) VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(row.uid, row.name, "VILLAGE", null, row.description)
                )
            }
        }
    }

    private fun packageZip(sourceDir: File): File {
        val zip = File(filesRoot, "phase59-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { zipStream ->
            listOf("worldpack.json", "world.db").forEach { name ->
                zipStream.putNextEntry(ZipEntry(name))
                File(sourceDir, name).inputStream().use { input -> input.copyTo(zipStream) }
                zipStream.closeEntry()
            }
        }
        return zip
    }

    private class RegressionWorldPackIndex : SemanticIndexPort {
        override val version = SemanticIndexVersion()
        private val checkpointByCampaign = mutableMapOf<String, Long>()
        private val authorizedByScope = mutableMapOf<String, LinkedHashSet<String>>()
        private val upsertLog = mutableListOf<SemanticIndexedDocument>()
        private val projections = mutableMapOf<String, SemanticSourceProjectionState>()
        val removeCalls = mutableListOf<RemoveCall>()

        data class RemoveCall(
            val campaignUid: String,
            val namespaceUid: String,
            val audienceUid: String,
            val purposeUid: String,
            val canonicalRecordUid: String
        )

        fun setAuthorized(namespaceUid: String, audienceUid: String, purposeUid: String, uids: Set<String>) {
            authorizedByScope[key(namespaceUid, audienceUid, purposeUid)] = LinkedHashSet(uids)
        }

        fun upsertCountFor(uid: String, audience: String): Int =
            upsertLog.count { it.projection.canonicalRecordUid == uid && it.projection.audienceUid == audience }

        fun lastFingerprintFor(uid: String, audience: String): String? =
            upsertLog.asReversed().firstOrNull {
                it.projection.canonicalRecordUid == uid && it.projection.audienceUid == audience
            }?.projection?.sourceFingerprint

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
            removeCalls += RemoveCall(campaignUid, namespaceUid, "*", "*", canonicalRecordUid)
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
                projections[key(request, uid)]?.let { current[uid] = it }
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
        private fun key(document: SemanticIndexedDocument) = key(
            document.projection.namespaceUid,
            document.projection.audienceUid,
            document.projection.purposeUid,
            document.projection.canonicalRecordUid
        )
        private fun key(request: SemanticSearchRequest, canonicalRecordUid: String) = key(
            request.namespaceUid,
            request.audienceUid,
            request.purposeUid,
            canonicalRecordUid
        )
        private fun key(namespaceUid: String, audienceUid: String, purposeUid: String, canonicalRecordUid: String) =
            "$namespaceUid|$audienceUid|$purposeUid|$canonicalRecordUid"
    }

    private class RegressionEmbeddingProvider : EmbeddingProviderPort {
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
                    FloatArray(256) { index -> ((index + request.texts.joinToString().length) % 17).toFloat() / 17f }
                },
                request.requestUid
            )
        }

        override fun cancel(requestUid: String) = Unit

        override fun close() = Unit
    }
}
