package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase55To58MemoryContractTest {
    @Test
    fun additiveMemoryMigrationCreatesGenerationWithoutChangingCanonicalDigest() {
        val dbFile = File.createTempFile("phase55-memory-migration", ".db").apply { delete() }
        val campaign = "C-MIGRATION"
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                val before = AuthoritativeStateDigest.compute(db)

                Phase55To58MemorySchema.ensureReady(db, campaign)

                assertTrue(Phase55To58MemorySchema.isReady(db))
                assertEquals("HGEN-$campaign-0", HistoryGenerationStore(db, campaign).current().value)
                assertEquals(before, AuthoritativeStateDigest.compute(db))
                assertTrue(
                    Phase55To58MemorySchema.tables.none {
                        it in RuntimeTruthLayerRegistry.authoritativePersistentTables()
                    }
                )
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun typedScoreClassesEnforceRangesAndStaySeparatedByType() {
        assertEquals(0.73, CampaignImportance(0.73).value, 0.0)
        assertEquals(0.24, HolderSalience(0.24).value, 0.0)
        assertEquals(0.92, QueryRelevance(0.92).value, 0.0)
        assertEquals(0.58, RecallStrength(0.58).value, 0.0)
        assertEquals(0.31, MemoryAccuracy(0.31).value, 0.0)
        assertEquals(0.79, BeliefConfidence(0.79).value, 0.0)
        assertEquals(0.95, SourceReliability(0.95).value, 0.0)
        assertEquals(0.12f, SemanticSimilarityScore(0.12f).value, 0.0f)

        assertFails { CampaignImportance(-0.01) }
        assertFails { CampaignImportance(1.01) }
        assertFails { HolderSalience(Double.NaN) }
        assertFails { QueryRelevance(Double.POSITIVE_INFINITY) }
        assertFails { RecallStrength(-1.0) }
        assertFails { MemoryAccuracy(Double.NEGATIVE_INFINITY) }
        assertFails { BeliefConfidence(1.01) }
        assertFails { SourceReliability(-0.5) }
        assertFails { SemanticSimilarityScore(-1.1f) }
        assertFails { SemanticSimilarityScore(1.2f) }
        assertTrue(CampaignImportance::class != HolderSalience::class)
        assertTrue(HolderSalience::class != QueryRelevance::class)
    }

    @Test
    fun memoryLeafFingerprintIsCanonicalAndIdentityRequiresIt() {
        val campaign = "C1"
        val generation = HistoryGenerationUid("HGEN-$campaign-0")
        val leaves = listOf(
            MemorySourceLeafRef("EVENT", "E-2", 2, 42L, "FP-B"),
            MemorySourceLeafRef("EVENT", "E-1", 1, 41L, "FP-A")
        )
        val fingerprint = memoryLeafFingerprint(leaves)
        val identity = MemoryArtifactIdentity(
            campaignUid = campaign,
            historyGenerationUid = generation,
            logicalArtifactUid = "LOGICAL:$campaign:1",
            artifactRevisionUid = "REV",
            artifactKind = MemoryArtifactKind.EPISODE_MANIFEST,
            sourceLeafRefs = leaves,
            sourceLeafSetFingerprint = fingerprint,
            derivationRuleUid = "RULE",
            derivationVersion = 1,
            asOfCommittedOrder = 42L,
            createdFromOrder = 41L,
            createdThroughOrder = 42L
        )

        assertEquals(fingerprint, memoryLeafFingerprint(identity.sourceLeafRefs))
        assertEquals(fingerprint, identity.sourceLeafSetFingerprint)
        assertEquals("LOGICAL:$campaign:1", identity.logicalArtifactUid)
        assertFails { identity.copy(sourceLeafSetFingerprint = "BAD") }
    }

    @Test
    fun semanticAssertionRequiresEvidenceOrExplicitUnknownPolarity() {
        val generation = HistoryGenerationUid("HGEN-C1-0")
        val source = listOf(MemorySourceLeafRef("EVENT", "E-1", 1, 1L, "FP"))
        val sourceFingerprint = memoryLeafFingerprint(source)
        val identity = MemoryArtifactIdentity(
            "C1",
            generation,
            "ASSERT:SUBJECT",
            "REV-A",
            MemoryArtifactKind.SEMANTIC_ASSERTION,
            source,
            sourceFingerprint,
            "RULE-ASSERT",
            1,
            1L,
            1L,
            1L
        )

        assertFails {
            SemanticMemoryAssertion(
                identity = identity,
                assertionUid = "A-EMPTY",
                holder = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, "H1", "C1"),
                subjectRef = DomainRef("SUBJECT", "S1"),
                predicateUid = "HAS",
                objectValue = "V1",
                polarity = SemanticAssertionPolarity.AFFIRMED,
                epistemicKind = SemanticAssertionEpistemicKind.BELIEF,
                validFromOrder = 1L,
                validUntilOrder = null,
                lifecycleState = SemanticAssertionLifecycle.ACTIVE,
                supportingLeafRefs = emptyList(),
                contradictingLeafRefs = emptyList(),
            )
        }

        assertFails {
            SemanticMemoryAssertion(
                identity = identity,
                assertionUid = "A-UNKNOWN-WITHOUT-RULE",
                holder = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, "H1", "C1"),
                subjectRef = DomainRef("SUBJECT", "S1"),
                predicateUid = "HAS",
                objectValue = "V1",
                polarity = SemanticAssertionPolarity.UNKNOWN,
                epistemicKind = SemanticAssertionEpistemicKind.BELIEF,
                validFromOrder = 1L,
                validUntilOrder = null,
                lifecycleState = SemanticAssertionLifecycle.ACTIVE,
                supportingLeafRefs = emptyList(),
                contradictingLeafRefs = emptyList(),
            )
        }

        val unknown = SemanticMemoryAssertion(
            identity = identity,
            assertionUid = "A-CLOSED-WORLD",
            holder = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, "H1", "C1"),
            subjectRef = DomainRef("SUBJECT", "S1"),
            predicateUid = "HAS",
            objectValue = "V1",
            polarity = SemanticAssertionPolarity.UNKNOWN,
            epistemicKind = SemanticAssertionEpistemicKind.BELIEF,
            validFromOrder = 1L,
            validUntilOrder = null,
            lifecycleState = SemanticAssertionLifecycle.ACTIVE,
            supportingLeafRefs = emptyList(),
            contradictingLeafRefs = emptyList(),
            closedWorldRuleUid = "RULE:CLOSED-WORLD:CHARACTER-CATALOG-V1",
        )

        assertEquals(SemanticAssertionPolarity.UNKNOWN, unknown.polarity)
        assertEquals("A-CLOSED-WORLD", unknown.assertionUid)
    }

    @Test
    fun episodeSegmentationIsDeterministicAndSplitsAtConfiguredBoundaries() {
        val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 20, maximumEvents = 128, ruleUid = "RULE")
        val campaign = "C1"
        val generation = HistoryGenerationUid("HGEN-C1-0")
        val twoTurnBoundaryEvents = (1..21).map { index ->
            eventLeaf(campaign, "T-$index", index.toLong(), "E-$index")
        }
        val byTurns = segmenter.segment(campaign, generation, twoTurnBoundaryEvents)
        assertEquals(2, byTurns.size)
        assertEquals(20, byTurns[0].eventUids.size)
        assertEquals(1, byTurns[1].eventUids.size)
        assertEquals("E-1", byTurns[0].eventUids.first())
        assertEquals("E-21", byTurns[1].eventUids.first())

        val packedEvents = (1..129).map { index ->
            eventLeaf(campaign, "SINGLE", index.toLong(), "EV-$index")
        }
        val byEvents = segmenter.segment(campaign, generation, packedEvents)
        assertEquals(2, byEvents.size)
        assertEquals(128, byEvents[0].eventUids.size)
        assertEquals(1, byEvents[1].eventUids.size)

        val shuffled = byTurns[0].eventUids.zip(byTurns[0].eventUids.reversed()).map { (uid, _) ->
            eventLeaf(campaign, "T-${uid.substringAfter("E-")}", uid.substringAfter("E-").toLong(), uid)
        }
        val replay = segmenter.segment(campaign, generation, shuffled + listOf(eventLeaf(campaign, "T-21", 21L, "E-21")))
        assertEquals(byTurns.size, replay.size)
        assertEquals(byTurns[0].identity.logicalArtifactUid, replay[0].identity.logicalArtifactUid)
        assertEquals(byTurns[0].identity.artifactRevisionUid, replay[0].identity.artifactRevisionUid)
        assertEquals(byTurns[1].identity.logicalArtifactUid, replay[1].identity.logicalArtifactUid)
    }

    @Test
    fun sourceLeafFingerprintChangeChangesRevisionButKeepsLogicalId() {
        val segmenter = DeterministicEpisodeSegmenter(ruleUid = "RULE")
        val campaign = "C2"
        val generation = HistoryGenerationUid("HGEN-C2-0")
        val baselineEvents = listOf(
            eventLeaf(campaign, "T-1", 1L, "E-1", fingerprint = "FP-1"),
            eventLeaf(campaign, "T-1", 2L, "E-2", fingerprint = "FP-2"),
            eventLeaf(campaign, "T-1", 3L, "E-3", fingerprint = "FP-3")
        )
        val baseline = segmenter.segment(campaign, generation, baselineEvents)
        assertEquals(1, baseline.size)
        val changed = segmenter.segment(
            campaign,
            generation,
            baselineEvents.map { event ->
                if (event.eventUid == "E-2") event.copy(fingerprint = "FP-2-CHANGED") else event
            }
        )

        assertEquals(1, changed.size)
        assertEquals(baseline[0].identity.logicalArtifactUid, changed[0].identity.logicalArtifactUid)
        assertNotEquals(baseline[0].identity.artifactRevisionUid, changed[0].identity.artifactRevisionUid)
    }

    @Test
    fun historyGenerationStorePersistsGenerationAdvanceAcrossReopen() {
        val dbFile = File.createTempFile("phase55-memory", ".db").apply { delete() }
        val campaign = "C1"
        try {
            val firstGeneration = SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                Phase55To58MemorySchema.ensureReady(db, campaign)
                assertTrue(Phase55To58MemorySchema.isReady(db))
                val store = HistoryGenerationStore(db, campaign)
                val baseline = store.current()
                val advanced = store.advance("UNDO")
                assertNotEquals(baseline.value, advanced.value)
                assertEquals(1L, scalar(db, "SELECT generation_ordinal FROM ${Phase55To58MemorySchema.GENERATIONS} WHERE campaign_uid=?", campaign))
                assertEquals("UNDO", text(db, "SELECT change_reason_uid FROM ${Phase55To58MemorySchema.GENERATIONS} WHERE campaign_uid=?", campaign))
                advanced.value
            }

            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { reopened ->
                val reopenedStore = HistoryGenerationStore(reopened, campaign)
                assertEquals(firstGeneration, reopenedStore.current().value)
                assertTrue(Phase55To58MemorySchema.isReady(reopened))
            }
        } finally {
            dbFile.delete()
        }
    }

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
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

    private fun text(db: SQLiteDatabase, query: String, arg: String) =
        db.rawQuery(query, arrayOf(arg)).use { c ->
            c.moveToFirst()
            c.getString(0)
        }

    private fun scalar(db: SQLiteDatabase, query: String, arg: String) =
        db.rawQuery(query, arrayOf(arg)).use { c ->
            c.moveToFirst()
            c.getLong(0)
        }
}
