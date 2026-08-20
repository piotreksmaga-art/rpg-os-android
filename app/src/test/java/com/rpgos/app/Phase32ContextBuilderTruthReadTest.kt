package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32ContextBuilderTruthReadTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(
            System.getProperty("java.io.tmpdir"),
            "rpgos-g32-context-${System.nanoTime()}/saves/C.campaign"
        )
        root.mkdirs()
        File(root, "campaign.json").writeText("{\"id\":\"C\"}")
    }

    @After
    fun tearDown() {
        root.parentFile?.parentFile?.deleteRecursively()
    }

    @Test
    fun productionContextBuilderReadsCanonicalTruthAndPlayerStateWithoutReverseAuthority() {
        val saveFile = File(root, "campaign.db")
        val worldFile = File(root, "world.db")
        SQLiteDatabase.openOrCreateDatabase(saveFile, null).use { save ->
            SQLiteDatabase.openOrCreateDatabase(worldFile, null).use { world ->
                Phase32ProductionReadyTestFixture.setup(save, "C")

                val truthStore = CampaignTruthStore(save, "C")
                withAdministrativeMutationAuthority(save, "C") {
                    save.execSQL(
                        "INSERT OR REPLACE INTO active_player_ref(campaign_id,player_uid,updated_at) VALUES('C','P',1)"
                    )
                    truthStore.record(
                        kind = TruthKind.FACT,
                        predicate = "context.fact",
                        objectValue = "canonical",
                        subjectUid = "P",
                        provenance = Provenance(
                            sourceType = ProvenanceSourceType.MANUAL_IMPORT,
                            sourceId = "g32-context"
                        ),
                        truthUid = "TRUTH:G32:CONTEXT"
                    )
                }

                val expectedTruth = truthStore.activeForContext()
                val expectedPlayerState = PlayerStateStore(save, "C").load()?.toContextMap()
                assertNotNull(expectedPlayerState)

                val bundle = run { Phase38LegacyContextFixtureSchema.ensure(save, world); ContextBuilder(save,world).build("look",1,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }
                assertEquals(expectedTruth, bundle.campaignTruth)
                assertEquals(expectedPlayerState, bundle.playerState)
                assertEquals("FACT", bundle.campaignTruth.single()["truth_kind"])
                @Suppress("UNCHECKED_CAST")
                val activePlayer = bundle.playerState["active_player"] as Map<String, Any?>
                assertEquals("P", activePlayer["player_uid"])

                // Freshness/content of a derived ContextBundle has no authority path. A caller may
                // construct contradictory or newer in-memory context, but canonical stores remain
                // the only source read by the next production build.
                val contradictory = bundle.copy(
                    campaignTruth = listOf(
                        mapOf(
                            "truth_uid" to "TRUTH:FAKE",
                            "truth_kind" to "FACT",
                            "predicate" to "context.fact",
                            "object_value" to "contradictory",
                            "created_at" to Long.MAX_VALUE
                        )
                    ),
                    playerState = mapOf(
                        "active_player" to mapOf("campaign_id" to "C", "player_uid" to "ATTACKER"),
                        "freshness" to Long.MAX_VALUE
                    )
                )
                assertTrue(contradictory.campaignTruth != expectedTruth)
                assertTrue(contradictory.playerState != expectedPlayerState)

                assertEquals(expectedTruth, truthStore.activeForContext())
                assertEquals(expectedPlayerState, PlayerStateStore(save, "C").load()?.toContextMap())

                val rebuilt = run { Phase38LegacyContextFixtureSchema.ensure(save, world); ContextBuilder(save,world).build("look again",2,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }
                assertEquals(expectedTruth, rebuilt.campaignTruth)
                assertEquals(expectedPlayerState, rebuilt.playerState)
            }
        }
    }
}
