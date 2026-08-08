package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CampaignTruthIdentity141Test {
    private lateinit var db: SQLiteDatabase
    private lateinit var repository: SQLiteUnifiedCampaignRepository

    private val campaignUid = EntityUid("CAMPAIGN-truth-identity")
    private val subjectUid = EntityUid("SUBJECT-truth-identity")
    private val truthUid = EntityUid("FACT-truth-identity")

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        db = SQLiteDatabase.create(null)
        repository = SQLiteUnifiedCampaignRepository(
            context = context,
            db = db,
            campaignUid = campaignUid,
            worldPackUid = EntityUid("WORLDPACK-truth-identity"),
            ownsDatabase = false
        )
    }

    @After
    fun tearDown() {
        repository.close()
        db.close()
    }

    @Test
    fun identicalTruthUidRewriteIsIdempotent() = runBlocking {
        val truth = originalTruth()

        repository.writeTruth(truth)
        repository.writeTruth(truth)

        val count = db.rawQuery(
            "SELECT COUNT(*) FROM gm_facts WHERE campaign_id=? AND fact_id=?",
            arrayOf(campaignUid.value, truthUid.value)
        ).use { c ->
            c.moveToFirst()
            c.getLong(0)
        }
        assertEquals(1L, count)

        val stored = repository.getTruth(
            campaignUid = campaignUid,
            subjectUid = subjectUid,
            predicate = truth.predicate,
            atTurnId = 0L
        ).single()
        assertEquals(truth.copy(validFromTurn = 0L), stored)
    }

    @Test
    fun sameTruthUidCannotBeReboundToDifferentDurableContent() = runBlocking {
        val truth = originalTruth()
        repository.writeTruth(truth)

        val conflicts = listOf(
            truth.copy(value = "TAMPERED"),
            truth.copy(subjectUid = EntityUid("SUBJECT-other")),
            truth.copy(predicate = "classified.other"),
            truth.copy(validUntilTurn = 11L),
            truth.copy(
                provenance = truth.provenance.copy(
                    confidence = 0.40,
                    verified = false
                )
            ),
            truth.copy(
                kind = TruthKind.BELIEF,
                holderUid = EntityUid("NPC-other")
            )
        )

        conflicts.forEach { conflicting ->
            val failure = runCatching {
                repository.writeTruth(conflicting)
            }.exceptionOrNull()
            assertTrue(
                failure?.message.orEmpty(),
                failure?.message.orEmpty().contains("TRUTH_UID_CONFLICT")
            )
        }

        val stored = repository.getTruth(
            campaignUid = campaignUid,
            subjectUid = subjectUid,
            predicate = truth.predicate,
            atTurnId = 0L
        ).single()
        assertEquals(truth.copy(validFromTurn = 0L), stored)
    }

    private fun originalTruth() = CampaignTruth(
        uid = truthUid,
        kind = TruthKind.FACT,
        subjectUid = subjectUid,
        predicate = "classified.location",
        value = "VAULT-7",
        holderUid = null,
        validFromTurn = null,
        validUntilTurn = 10L,
        provenance = ProvenanceRecord(
            type = ProvenanceType.CAMPAIGN_EVENT,
            sourceUid = EntityUid("EVENT-source"),
            turnId = null,
            confidence = 0.95,
            canonStatus = "CAMPAIGN",
            verified = true
        )
    )
}
