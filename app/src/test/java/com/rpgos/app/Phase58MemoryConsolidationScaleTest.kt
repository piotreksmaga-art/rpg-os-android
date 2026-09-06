package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase58MemoryConsolidationScaleTest {
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        dbFile = File.createTempFile("phase58-consolidation-scale", ".db").apply { delete() }
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun oneMillionLeafInputCanBePlannedLazilyInBoundedChunks() {
        val campaignUid = "C-58-SCALE-PLAN"
        val totalLeaves = 1_000_000L
        val maxChunkSize = 256
        val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 1, maximumEvents = 1, ruleUid = "RPGOS-SCALE")

        var chunks = 0
        var plannedLeaves = 0L
        var maxChunkObserved = 0
        val source = generateLeafStream(campaignUid, 1L, totalLeaves).iterator()

        while (true) {
            val chunk = nextChunk(source, maxChunkSize)
            if (chunk.isEmpty()) break

            assertTrue("Lazy chunking must stay within plan budget", chunk.size in 1..maxChunkSize)
            maxChunkObserved = maxOf(maxChunkObserved, chunk.size)

            val episodes = segmenter.segment(
                campaignUid,
                HistoryGenerationUid("HGEN-$campaignUid"),
                chunk
            )
            val eventsAfterSegmentation = episodes.sumOf { it.eventUids.size }
            assertEquals("Segmenter preserves all input leaves", chunk.size, eventsAfterSegmentation)
            assertEquals(
                "With maximumEvents=1, each leaf is one episode",
                chunk.size,
                episodes.size
            )

            plannedLeaves += chunk.size.toLong()
            chunks++
        }

        assertEquals(totalLeaves, plannedLeaves)
        assertEquals(3907, chunks)
        assertEquals(256, maxChunkObserved)
    }

    @Test
    fun boundedProductionOnRepresentativeWindowIsCursorAwareAndIdempotent() {
        val campaignUid = "C-58-SCALE-PROD"
        val windowLeaves = 1024
        val budget = ConsolidationWorkBudget(
            maxLeafRecords = 256,
            maxOutputArtifacts = 1024,
            maxWallClockMillis = 500,
            maxEpisodes = 64
        )

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            Phase55To58MemorySchema.ensureReady(db, campaignUid)
            val segmenter = DeterministicEpisodeSegmenter(maximumTurns = 1, maximumEvents = 1, ruleUid = "RPGOS-SCALE")
            val consolidation = Phase58MemoryConsolidation(db, campaignUid, segmenter)

            val leaves = (1..windowLeaves).map { index ->
                eventLeaf(
                    campaignUid = campaignUid,
                    turnUid = "TURN-$index",
                    committedOrder = index.toLong(),
                    eventUid = "EV-$index",
                    fingerprint = "SCALE-FP-$index"
                )
            }

            var receipt: ConsolidationReceipt? = null
            var completedThrough = 0L
            var invocationCount = 0
            leaves.chunked(budget.maxLeafRecords).forEachIndexed { chunkIndex, chunk ->
                receipt = consolidation.postCommit(chunk, "en-US", budget)
                if (chunkIndex == 0) {
                    val cursorAfterFirst = JSONObject(requireNotNull(receipt!!.resumeCursor))
                    assertEquals(2, cursorAfterFirst.getInt("version"))
                    assertEquals(receipt!!.historyGenerationUid.value, cursorAfterFirst.getString("history_generation_uid"))
                    assertTrue(cursorAfterFirst.getString("slice_fingerprint").isNotBlank())
                    assertEquals(256, cursorAfterFirst.getInt("slice_leaf_count"))
                    assertEquals(receipt!!.resultingWatermark.toInt(), cursorAfterFirst.getInt("next_leaf_index"))
                    assertEquals(256L, cursorAfterFirst.getLong("slice_through_order"))
                    assertTrue(!cursorAfterFirst.has("candidate_leaves"))
                    assertTrue("Cursor must remain constant-size", receipt!!.resumeCursor!!.length < 512)
                }

                do {
                    val current = requireNotNull(receipt)
                    assertEquals(ConsolidationStatus.COMMITTED, current.status)
                    val processed = current.resultingWatermark - completedThrough
                    assertTrue("Every slice must make bounded progress, processed=$processed", processed in 1L..256L)
                    completedThrough = current.resultingWatermark
                    invocationCount++
                    assertTrue("Catch-up did not converge", invocationCount <= windowLeaves)
                    receipt = if (current.resumeCursor == null) null else {
                        consolidation.open(chunk, localeUid = "en-US", budget = budget)
                    }
                } while (receipt != null)
            }
            assertEquals(windowLeaves.toLong(), completedThrough)

            val artifactsAfterFirstPass = longScalar(
                db,
                "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=?",
                campaignUid
            )

            val replayReceipts = leaves.chunked(budget.maxLeafRecords).map { chunk ->
                consolidation.postCommit(chunk, "en-US", budget)
            }
            replayReceipts.forEach { replay ->
                assertEquals(ConsolidationStatus.COMMITTED, replay.status)
                assertEquals(1024L, replay.resultingWatermark)
                assertEquals(1024L, replay.previousWatermark)
                assertEquals(0, replay.producedArtifactRevisionUids.size)
                assertEquals(0, replay.updatedArtifactRevisionUids.size)
            }
            assertEquals(
                artifactsAfterFirstPass,
                longScalar(db, "SELECT COUNT(*) FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=?", campaignUid)
            )

            val reopened = consolidation.open(localeUid = "en-US", budget = budget)
            assertEquals(1024L, reopened.resultingWatermark)
            assertNull(reopened.resumeCursor)
            assertEquals(1024L, reopened.previousWatermark)
        }
    }

    private fun generateLeafStream(
        campaignUid: String,
        startOrder: Long,
        endInclusive: Long
    ): Sequence<MemoryEventLeaf> = sequence {
        for (order in startOrder..endInclusive) {
            yield(
                eventLeaf(
                    campaignUid = campaignUid,
                    turnUid = "TURN-$order",
                    committedOrder = order,
                    eventUid = "EV-$order"
                )
            )
        }
    }

    private fun nextChunk(source: Iterator<MemoryEventLeaf>, maxChunkSize: Int): List<MemoryEventLeaf> {
        require(maxChunkSize > 0)
        return buildList {
            repeat(maxChunkSize) {
                if (!source.hasNext()) return@buildList
                add(source.next())
            }
        }
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
        locationRefs = listOf(DomainRef("LOCATION", "LOC-$campaignUid")),
        explicitBoundaryBefore = false,
        fingerprint = fingerprint
    )

    private fun longScalar(db: SQLiteDatabase, query: String, campaignUid: String): Long =
        db.rawQuery(query, arrayOf(campaignUid)).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
}
