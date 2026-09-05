package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase58MemoryConsolidationTest {
    @Test
    fun postCommitWritesManifestAndInterpretationArtifactsWithMembership() {
        val dbFile = File.createTempFile("phase58-consolidation", ".db").apply { delete() }
        val campaignUid = "C-58-A"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 20, maximumEvents = 2, ruleUid = "RPGOS-TEST")
                val consolidation = Phase58MemoryConsolidation(db, campaignUid, segmenter)

                val leaves = (1..5).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-$index")
                }
                val receipt = consolidation.postCommit(leaves, "en-US")

                assertEquals(ConsolidationStatus.COMMITTED, receipt.status)
                assertEquals(5L, receipt.resultingWatermark)
                assertEquals(3, intScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=? AND artifact_kind_uid=?", campaignUid, MemoryArtifactKind.EPISODE_MANIFEST.name))
                assertEquals(3, intScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=? AND artifact_kind_uid=?", campaignUid, MemoryArtifactKind.EPISODE_INTERPRETATION.name))
                assertEquals(5, intScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.EPISODE_MEMBERSHIP} WHERE campaign_uid=?", campaignUid))
                assertEquals(1, intScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.RECEIPTS} WHERE campaign_uid=?", campaignUid))
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun postCommitIsIdempotentForIdenticalLeafInput() {
        val dbFile = File.createTempFile("phase58-consolidation-idempotent", ".db").apply { delete() }
        val campaignUid = "C-58-B"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 20, maximumEvents = 3, ruleUid = "RPGOS-TEST")
                val consolidation = Phase58MemoryConsolidation(db, campaignUid, segmenter)

                val leaves = (1..6).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-$index")
                }
                val first = consolidation.postCommit(leaves, "en-US")
                val afterFirstArtifactCount = intScalar(
                    db,
                    "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=?",
                    campaignUid
                )
                val firstWatermark = longScalar(db, "SELECT watermark_order FROM ${Phase55To58MemorySchema.STATE} WHERE campaign_uid=?", campaignUid)

                val second = consolidation.postCommit(leaves, "en-US")
                val afterSecondArtifactCount = intScalar(
                    db,
                    "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=?",
                    campaignUid
                )
                val secondWatermark = longScalar(db, "SELECT watermark_order FROM ${Phase55To58MemorySchema.STATE} WHERE campaign_uid=?", campaignUid)

                assertEquals(afterFirstArtifactCount, afterSecondArtifactCount)
                assertEquals(firstWatermark, secondWatermark)
                assertEquals(first.status, second.status)
                assertTrue(second.producedArtifactRevisionUids.isEmpty())
                assertTrue(second.updatedArtifactRevisionUids.isEmpty())
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun openResumesPartialRunsByResumeCursor() {
        val dbFile = File.createTempFile("phase58-consolidation-resume", ".db").apply { delete() }
        val campaignUid = "C-58-C"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 20, maximumEvents = 1, ruleUid = "RPGOS-TEST")
                val consolidation = Phase58MemoryConsolidation(
                    db,
                    campaignUid,
                    segmenter
                )
                val leaves = (1..4).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-$index")
                }
                val budget = ConsolidationWorkBudget(maxLeafRecords = 256, maxOutputArtifacts = 2, maxWorkUnits = 10_000, maxWallClockMillis = 500)

                val first = consolidation.postCommit(leaves, "en-US", budget)
                assertEquals(ConsolidationStatus.COMMITTED, first.status)
                assertNotNull(first.resumeCursor)
                assertEquals(1L, first.resultingWatermark)

                val second = consolidation.open(leaves, localeUid = "en-US", budget = budget)
                assertEquals(ConsolidationStatus.COMMITTED, second.status)
                assertEquals(2L, second.resultingWatermark)
                assertNotNull(second.resumeCursor)

                val third = consolidation.open(leaves, localeUid = "en-US", budget = budget)
                assertEquals(ConsolidationStatus.COMMITTED, third.status)
                assertEquals(3L, third.resultingWatermark)
                assertNotNull(third.resumeCursor)

                val fourth = consolidation.open(leaves, localeUid = "en-US", budget = budget)
                assertEquals(ConsolidationStatus.COMMITTED, fourth.status)
                assertEquals(4L, fourth.resultingWatermark)
                assertNull(fourth.resumeCursor)

                assertEquals(8, intScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=?", campaignUid))
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun tooSmallBudgetFailsClosedAndClearsNoProgressResume() {
        val dbFile = File.createTempFile("phase58-consolidation-no-progress", ".db").apply { delete() }
        val campaignUid = "C-58-NO-PROGRESS"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val consolidation = Phase58MemoryConsolidation(
                    db,
                    campaignUid,
                    DeterministicEpisodeSegmenter(maximumEvents = 1, ruleUid = "RPGOS-TEST")
                )
                val leaves = (1..2).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-NP-$index")
                }
                val progressing = ConsolidationWorkBudget(maxOutputArtifacts = 2, maxWallClockMillis = 500)
                val tooSmall = ConsolidationWorkBudget(maxOutputArtifacts = 1, maxWallClockMillis = 500)

                val first = consolidation.postCommit(leaves, "en-US", progressing)
                assertEquals(1L, first.resultingWatermark)
                assertNotNull(first.resumeCursor)

                val failed = consolidation.open(leaves, "en-US", tooSmall)
                assertEquals(ConsolidationStatus.FAILED, failed.status)
                assertEquals("RPGOS-MEMORY:CONSOLIDATION_EXCEEDED_WORK_BUDGET", failed.reasonUid)
                assertEquals(1L, failed.resultingWatermark)
                assertNull(failed.resumeCursor)
                assertNull(textOrNull(
                    db,
                    "SELECT resume_cursor FROM ${Phase55To58MemorySchema.STATE} WHERE campaign_uid=?",
                    campaignUid
                ))

                val retry = consolidation.open(
                    leaves,
                    "en-US",
                    ConsolidationWorkBudget(maxOutputArtifacts = 4, maxWallClockMillis = 500)
                )
                assertEquals(ConsolidationStatus.COMMITTED, retry.status)
                assertEquals(2L, retry.resultingWatermark)
                assertNull(retry.resumeCursor)
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun enrichmentPortCanOverrideInterpretationTitleAndSummary() {
        val dbFile = File.createTempFile("phase58-consolidation-enrichment", ".db").apply { delete() }
        val campaignUid = "C-58-D"
        val enrichmentPort = object : MemoryEnrichmentPort {
            override fun enrich(request: MemoryEnrichmentRequest, cancellation: AiCancellationSignal): MemoryEnrichmentResult {
                return MemoryEnrichmentResult.Success(
                    title = "Custom title ${request.requestUid.takeLast(6)}",
                    summary = "Custom summary for ${request.manifest.identity.logicalArtifactUid}",
                    tags = setOf("custom", "tag", request.localeUid.lowercase())
                )
            }
        }
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 20, maximumEvents = 3, ruleUid = "RPGOS-TEST")
                val consolidation = Phase58MemoryConsolidation(
                    db,
                    campaignUid,
                    segmenter,
                    enrichmentPort = enrichmentPort
                )
                val leaves = listOf(eventLeaf(campaignUid, "TURN-1", 1L, "EV-1"))
                val receipt = consolidation.postCommit(leaves, "PL")

                val interpretationUid = receipt.producedArtifactRevisionUids
                    .first { it.startsWith("RPGOS-P58-INT") }
                val payload = text(
                    db,
                    "SELECT payload_json FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=? AND artifact_revision_uid=?",
                    campaignUid,
                    interpretationUid
                )
                val payloadJson = JSONObject(payload)
                assertEquals("custom", payloadJson.getJSONArray("tags").getString(0))
                assertTrue(payloadJson.getString("summary").contains("EPISODE:C-58-D:RPGOS-TEST"))
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test(timeout = 2_000)
    fun enrichmentDeadlineCancelsSlowPortAndCommitsDeterministicFallback() {
        val dbFile = File.createTempFile("phase58-consolidation-enrichment-deadline", ".db").apply { delete() }
        val campaignUid = "C-58-ENRICHMENT-DEADLINE"
        val cancellationObserved = java.util.concurrent.atomic.AtomicBoolean(false)
        val slowPort = MemoryEnrichmentPort { _, cancellation ->
            while (!cancellation.isCancelled()) {
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    cancellationObserved.set(true)
                    return@MemoryEnrichmentPort MemoryEnrichmentResult.Failure("INTERRUPTED")
                }
            }
            cancellationObserved.set(true)
            MemoryEnrichmentResult.Failure("DEADLINE")
        }
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val consolidation = Phase58MemoryConsolidation(
                    db,
                    campaignUid,
                    DeterministicEpisodeSegmenter(maximumEvents = 1, ruleUid = "RPGOS-TEST"),
                    enrichmentPort = slowPort
                )
                val started = System.nanoTime()
                val receipt = consolidation.postCommit(
                    listOf(eventLeaf(campaignUid, "TURN-1", 1L, "EV-DEADLINE")),
                    "en-US",
                    ConsolidationWorkBudget(maxWallClockMillis = 500)
                )
                val elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

                assertEquals(ConsolidationStatus.COMMITTED, receipt.status)
                assertEquals(1L, receipt.resultingWatermark)
                assertTrue("Slow enrichment exceeded bounded fallback: ${elapsedMillis}ms", elapsedMillis < 1_000L)
                repeat(20) {
                    if (cancellationObserved.get()) return@repeat
                    Thread.sleep(5)
                }
                assertTrue(cancellationObserved.get())
                val interpretationUid = receipt.producedArtifactRevisionUids.first { it.startsWith("RPGOS-P58-INT") }
                val payload = JSONObject(text(
                    db,
                    "SELECT payload_json FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=? AND artifact_revision_uid=?",
                    campaignUid,
                    interpretationUid
                ))
                assertTrue(payload.getString("title").startsWith("Episode "))
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun serializedBudgetCounterIncludesHolderAndAssertionPayloads() {
        val dbFile = File.createTempFile("phase58-consolidation-payload-bytes", ".db").apply { delete() }
        val campaignUid = "C-58-PAYLOAD-BYTES"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                val leaf = MemorySourceLeafRef("KNOWLEDGE_ACQUISITION", "ACQ-1", 1, 1, "FP-ACQ-1")
                val fingerprint = memoryLeafFingerprint(listOf(leaf))
                fun identity(kind: MemoryArtifactKind, suffix: String) = MemoryArtifactIdentity(
                    campaignUid = campaignUid,
                    historyGenerationUid = HistoryGenerationUid("HGEN-$campaignUid"),
                    logicalArtifactUid = "LOGICAL-$suffix",
                    artifactRevisionUid = "REV-$suffix",
                    artifactKind = kind,
                    sourceLeafRefs = listOf(leaf),
                    sourceLeafSetFingerprint = fingerprint,
                    derivationRuleUid = "RPGOS-TEST",
                    derivationVersion = 1,
                    asOfCommittedOrder = 1,
                    createdFromOrder = 1,
                    createdThroughOrder = 1
                )
                val holder = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, "HOLDER-1", campaignUid)
                val holderMemory = HolderEpisodeMemory(
                    identity(MemoryArtifactKind.HOLDER_EPISODE_MEMORY, "HOLDER"),
                    "EPISODE-1",
                    holder,
                    listOf("EV-1"),
                    emptyList(),
                    MemoryAccuracy(1.0),
                    HolderSalience(1.0),
                    RecallStrength(1.0),
                    listOf("ACQ-1")
                )
                val assertion = SemanticMemoryAssertion(
                    identity(MemoryArtifactKind.SEMANTIC_ASSERTION, "ASSERTION"),
                    "ASSERTION-1",
                    holder,
                    DomainRef("TARGET", "SUBJECT-1"),
                    "predicate",
                    "value",
                    SemanticAssertionPolarity.AFFIRMED,
                    SemanticAssertionEpistemicKind.KNOWLEDGE,
                    1,
                    null,
                    SemanticAssertionLifecycle.ACTIVE,
                    listOf(leaf),
                    emptyList()
                )
                val projector = Phase37EpisodeMemoryProjector(db, campaignUid)

                val holderBytes = projector.serializedPayloadBytes(Phase37EpisodeMemoryDerivation(listOf(holderMemory), emptyList()))
                val assertionBytes = projector.serializedPayloadBytes(Phase37EpisodeMemoryDerivation(emptyList(), listOf(assertion)))
                val combinedBytes = projector.serializedPayloadBytes(Phase37EpisodeMemoryDerivation(listOf(holderMemory), listOf(assertion)))

                assertTrue(holderBytes > 0)
                assertTrue(assertionBytes > 0)
                assertEquals(holderBytes + assertionBytes, combinedBytes)
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun historyGenerationChangeKeepsConsolidationStateIsolated() {
        val dbFile = File.createTempFile("phase58-consolidation-generation", ".db").apply { delete() }
        val campaignUid = "C-58-G"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 20, maximumEvents = 3, ruleUid = "RPGOS-TEST")
                val consolidation = Phase58MemoryConsolidation(db, campaignUid, segmenter)

                val firstLeaves = (1..3).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-$index", fingerprint = "FP-A-$index")
                }
                val firstReceipt = consolidation.postCommit(firstLeaves, "en-US")
                val generationBefore = firstReceipt.historyGenerationUid

                val generationAfterAdvance = HistoryGenerationStore(db, campaignUid).advance("UNDO")
                val secondLeaves = (4..6).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-$index", fingerprint = "FP-B-$index")
                }
                val secondReceipt = consolidation.postCommit(secondLeaves, "en-US")

                assertNotEquals(generationBefore, generationAfterAdvance)
                assertEquals(generationAfterAdvance, secondReceipt.historyGenerationUid)
                assertEquals(0L, secondReceipt.previousWatermark)
                assertTrue(secondReceipt.resultingWatermark >= 6L)

                val stateGeneration = text(db, "SELECT history_generation_uid FROM ${Phase55To58MemorySchema.STATE} WHERE campaign_uid=?", campaignUid)
                assertEquals(generationAfterAdvance.value, stateGeneration)
                assertEquals(generationAfterAdvance, HistoryGenerationStore(db, campaignUid).current())
                assertEquals(2, longScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=? AND history_generation_uid=?", campaignUid, generationBefore.value).toInt())
                assertEquals(2, longScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=? AND history_generation_uid=?", campaignUid, generationAfterAdvance.value).toInt())
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun faultInjectionBetweenArtifactWriteAndReceiptAllowsIdempotentRetry() {
        val dbFile = File.createTempFile("phase58-consolidation-fault", ".db").apply { delete() }
        val campaignUid = "C-58-F"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 20, maximumEvents = 1, ruleUid = "RPGOS-TEST")
                val consolidation = Phase58MemoryConsolidation(db, campaignUid, segmenter)
                val leaves = (1..4).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-$index", fingerprint = "FP-$index")
                }

                db.execSQL("CREATE TABLE IF NOT EXISTS phase58_fault_injection(flag INTEGER NOT NULL)")
                db.execSQL("DELETE FROM phase58_fault_injection")
                db.execSQL("INSERT INTO phase58_fault_injection(flag) VALUES (1)")
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS phase58_fail_receipt_once
                    BEFORE INSERT ON ${Phase55To58MemorySchema.RECEIPTS}
                    WHEN (SELECT flag FROM phase58_fault_injection LIMIT 1) = 1
                    BEGIN
                      UPDATE phase58_fault_injection SET flag = 0;
                      SELECT RAISE(ABORT, 'RPGOS-MEMORY:FAULT_INJECTION_RECEIPT_WRITE');
                    END;
                    """.trimIndent()
                )

                try {
                    try {
                        consolidation.postCommit(leaves, "en-US")
                        fail("Expected consolidation failure after forced receipt write fault")
                    } catch (_: Exception) {
                        // expected
                    }

                    assertEquals(
                        0,
                        intScalar(
                            db,
                            "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.RECEIPTS} WHERE campaign_uid=?",
                            campaignUid
                        )
                    )

                    val artifactCountAfterFailure = intScalar(
                        db,
                        "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=?",
                        campaignUid
                    )
                    assertTrue(artifactCountAfterFailure > 0)

                    db.execSQL("DROP TRIGGER IF EXISTS phase58_fail_receipt_once")

                    val retry = consolidation.postCommit(leaves, "en-US")
                    assertEquals(ConsolidationStatus.COMMITTED, retry.status)
                    assertEquals(
                        artifactCountAfterFailure,
                        intScalar(
                            db,
                            "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=?",
                            campaignUid
                        )
                    )
                    assertEquals(4L, retry.resultingWatermark)
                    assertEquals(1, intScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.RECEIPTS} WHERE campaign_uid=?", campaignUid))
                } finally {
                    db.execSQL("DROP TRIGGER IF EXISTS phase58_fail_receipt_once")
                    db.execSQL("DROP TABLE IF EXISTS phase58_fault_injection")
                }
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun outputCapDoesNotLoseProgressAfter128Artifacts() {
        val dbFile = File.createTempFile("phase58-consolidation-cap", ".db").apply { delete() }
        val campaignUid = "C-58-O"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 1, maximumEvents = 1, ruleUid = "RPGOS-TEST")
                val consolidation = Phase58MemoryConsolidation(db, campaignUid, segmenter)
                val budget = ConsolidationWorkBudget(maxLeafRecords = 256, maxOutputArtifacts = 128, maxEpisodes = 1_000, maxWallClockMillis = 500)
                val leaves = (1..256).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-O-$index")
                }

                var receipt = consolidation.postCommit(leaves, "en-US", budget)
                var previous = 0L
                var calls = 0
                while (true) {
                    assertEquals(ConsolidationStatus.COMMITTED, receipt.status)
                    assertTrue(receipt.resultingWatermark in (previous + 1)..256L)
                    previous = receipt.resultingWatermark
                    calls++
                    assertTrue("Consolidation did not converge", calls <= 256)
                    if (receipt.resumeCursor == null) break
                    receipt = consolidation.open(leaves, localeUid = "en-US", budget = budget)
                }
                assertEquals(256L, receipt.resultingWatermark)
                assertNull(receipt.resumeCursor)
                assertEquals(
                    512,
                    intScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=?", campaignUid)
                )
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun largePendingInputProgressesBy256LeafChunksWithoutMaterializingAll() {
        val dbFile = File.createTempFile("phase58-consolidation-scale", ".db").apply { delete() }
        val campaignUid = "C-58-S"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 1, maximumEvents = 1, ruleUid = "RPGOS-TEST")
                val consolidation = Phase58MemoryConsolidation(db, campaignUid, segmenter)
                val budget = ConsolidationWorkBudget(
                    maxLeafRecords = 256,
                    maxOutputArtifacts = 128,
                    maxEpisodes = 1_000,
                    maxWallClockMillis = 500
                )
                val firstSlice = (1..256).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-S-$index", fingerprint = "S-FP-$index")
                }
                val secondSlice = (257..512).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-S-$index", fingerprint = "S-FP-$index")
                }
                val thirdSlice = (513..768).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-S-$index", fingerprint = "S-FP-$index")
                }
                val finalSlice = (769..900).map { index ->
                    eventLeaf(campaignUid, "TURN-$index", index.toLong(), "EV-S-$index", fingerprint = "S-FP-$index")
                }

                fun openUntil(slice: List<MemoryEventLeaf>, target: Long): ConsolidationReceipt {
                    var receipt = consolidation.open(slice, localeUid = "en-US", budget = budget)
                    repeat(12) {
                        if (receipt.resultingWatermark >= target) return receipt
                        receipt = consolidation.open(slice, localeUid = "en-US", budget = budget)
                    }
                    fail("Expected progress to $target, got ${receipt.resultingWatermark}")
                    return receipt
                }

                val first = consolidation.postCommit(firstSlice, "en-US", budget)
                assertEquals(ConsolidationStatus.COMMITTED, first.status)
                assertTrue(first.resultingWatermark in 1L..255L)
                assertNotNull(first.resumeCursor)

                val blockedByResume = consolidation.postCommit(firstSlice, "en-US", budget)
                assertTrue(blockedByResume.resultingWatermark in (first.resultingWatermark+1)..255L)
                val boundedCursor = JSONObject(requireNotNull(blockedByResume.resumeCursor))
                assertEquals(256, boundedCursor.getInt("slice_leaf_count"))
                assertEquals(blockedByResume.resultingWatermark.toInt(), boundedCursor.getInt("next_leaf_index"))
                assertTrue(!boundedCursor.has("candidate_leaves"))
                assertNotNull(blockedByResume.resumeCursor)

                val firstCompleted = openUntil(firstSlice, 256L)
                assertEquals(256L, firstCompleted.resultingWatermark)
                assertNull(firstCompleted.resumeCursor)

                val second = consolidation.postCommit(secondSlice, "en-US", budget)
                assertTrue(second.resultingWatermark in 257L..511L)
                assertNotNull(second.resumeCursor)
                val secondCompleted = openUntil(secondSlice, 512L)
                assertEquals(512L, secondCompleted.resultingWatermark)
                assertNull(secondCompleted.resumeCursor)

                val third = consolidation.postCommit(thirdSlice, "en-US", budget)
                assertTrue(third.resultingWatermark in 513L..767L)
                assertNotNull(third.resumeCursor)
                val thirdCompleted = openUntil(thirdSlice, 768L)
                assertEquals(768L, thirdCompleted.resultingWatermark)
                assertNull(thirdCompleted.resumeCursor)

                val finalChunkStart = consolidation.postCommit(finalSlice, "en-US", budget)
                assertTrue(finalChunkStart.resultingWatermark in 769L..899L)
                assertNotNull(finalChunkStart.resumeCursor)
                val finalChunk = openUntil(finalSlice, 900L)
                assertEquals(900L, finalChunk.resultingWatermark)
                assertEquals(
                    1800,
                    intScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=?", campaignUid)
                )
                assertNull(finalChunk.resumeCursor)
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun resumeCursorIsImmutableAndDoesNotAbsorbLaterCommits() {
        val dbFile = File.createTempFile("phase58-consolidation-frozen-resume", ".db").apply { delete() }
        val campaignUid = "C-58-E"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaignUid)
                val consolidation = Phase58MemoryConsolidation(
                    db,campaignUid,DeterministicEpisodeSegmenter(maximumEvents=1,ruleUid="RPGOS-TEST")
                )
                val firstSlice=(1..4).map{index->eventLeaf(campaignUid,"TURN-$index",index.toLong(),"EV-$index")}
                val laterSlice=(5..6).map{index->eventLeaf(campaignUid,"TURN-$index",index.toLong(),"EV-$index")}
                val budget=ConsolidationWorkBudget(maxOutputArtifacts=2,maxWallClockMillis=500)

                val first=consolidation.postCommit(firstSlice,"en-US",budget)
                val second=consolidation.open(firstSlice+laterSlice,"en-US",budget)

                assertEquals(2L,second.resultingWatermark)
                val frozen=JSONObject(requireNotNull(second.resumeCursor))
                assertEquals(4,frozen.getInt("slice_leaf_count"))
                assertEquals(2,frozen.getInt("next_leaf_index"))
                assertEquals(4L,frozen.getLong("slice_through_order"))
                assertTrue(!frozen.has("candidate_leaves"))

                consolidation.open(firstSlice+laterSlice,localeUid="en-US",budget=budget)
                val completed=consolidation.open(firstSlice+laterSlice,localeUid="en-US",budget=budget)
                assertNull(completed.resumeCursor)
                val next=consolidation.open(laterSlice,"en-US",budget)
                assertEquals(5L,next.resultingWatermark)
                assertNotNull(next.resumeCursor)
            }
        } finally { dbFile.delete() }
    }

    private fun eventLeaf(
        campaignUid: String,
        turnUid: String,
        committedOrder: Long,
        eventUid: String,
        eventOrdinal: Int = 0,
        fingerprint: String = "FP-$eventUid"
    ) = MemoryEventLeaf(
        eventUid = eventUid,
        turnUid = turnUid,
        committedOrder = committedOrder,
        eventOrdinal = eventOrdinal,
        participantRefs = listOf(DomainRef("CHARACTER", campaignUid)),
        locationRefs = listOf(DomainRef("LOCATION", "${campaignUid}-LOC")),
        fingerprint = fingerprint
    )

    private fun intScalar(db: SQLiteDatabase, query: String, campaignUid: String, vararg args: String): Int =
        longScalar(db, query, campaignUid, *args).toInt()

    private fun longScalar(db: SQLiteDatabase, query: String, campaignUid: String, vararg args: String): Long =
        db.rawQuery(query, arrayOf(campaignUid, *args)).use { c ->
            c.moveToFirst()
            c.getLong(0)
        }

    private fun text(db: SQLiteDatabase, query: String, campaignUid: String, vararg args: String) =
        db.rawQuery(query, arrayOf(campaignUid, *args)).use { c ->
            c.moveToFirst()
            c.getString(0)
        }

    private fun textOrNull(db: SQLiteDatabase, query: String, campaignUid: String, vararg args: String): String? =
        db.rawQuery(query, arrayOf(campaignUid, *args)).use { c ->
            c.moveToFirst()
            if (c.isNull(0)) null else c.getString(0)
        }
}
