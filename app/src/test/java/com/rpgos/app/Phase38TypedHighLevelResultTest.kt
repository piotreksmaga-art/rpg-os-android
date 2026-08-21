package com.rpgos.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase38TypedHighLevelResultTest {
    private lateinit var context:Context
    private lateinit var concrete:UnifiedGameRepository
    private lateinit var repository:CampaignRepository
    private lateinit var campaignUid:String
    private lateinit var audience:AudienceContext
    private lateinit var purpose:PurposeContext
    private lateinit var root:File

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit()
        root=File(context.filesDir,"rpgos").also{it.deleteRecursively()}
        concrete=UnifiedGameRepository(context)
        concrete.bootstrap()
        repository=concrete
        campaignUid=repository.activeCampaignRef().campaignId
        audience=VisibilityAudienceFactory.player(campaignUid)
        purpose=PurposeContext(campaignUid,VisibilityPurposeKinds.PLAYER_UI)
    }

    @After fun cleanup() {
        context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    @Test fun canonicalFacadePreservesDeniedAndLegalEmptyCollectionStates() {
        val denied=repository.relationshipsProjection(audience,purpose)
        assertEquals(ProjectionDataState.DENIED,denied.dataState)
        assertNull(denied.value)

        val legalEmpty=repository.npcsProjection("__P38_AUD002_NO_MATCH__",audience,purpose)
        assertEquals(ProjectionDataState.NO_DATA,legalEmpty.dataState)
        assertNotNull(legalEmpty.value)
        assertTrue(legalEmpty.value!!.isEmpty())
        assertNotEquals("DENY and legal absence must remain distinguishable",denied.dataState,legalEmpty.dataState)
    }

    @Test fun allRequiredCanonicalFacadeDomainsExposeTypedResults() {
        assertEquals(ProjectionDataState.DENIED,repository.relationshipsProjection(audience,purpose).dataState)
        assertEquals(ProjectionDataState.DENIED,repository.organizationsProjection(audience,purpose).dataState)
        assertEquals(ProjectionDataState.DENIED,repository.politicsProjection(audience,purpose).dataState)
        assertEquals(ProjectionDataState.DENIED,repository.relationEdgesProjection(audience,purpose).dataState)
        assertEquals(ProjectionDataState.DENIED,repository.economiesProjection(audience,purpose).dataState)

        assertTrue(repository.activeWorldEventsProjection(audience,purpose).dataState in setOf(ProjectionDataState.DISCLOSED,ProjectionDataState.NO_DATA))
        assertTrue(repository.warsProjection(audience,purpose).dataState in setOf(ProjectionDataState.DISCLOSED,ProjectionDataState.NO_DATA))
        assertEquals(ProjectionDataState.NO_DATA,repository.npcsProjection("__P38_AUD002_NO_MATCH_2__",audience,purpose).dataState)
    }

    @Test fun authorizedCorruptionRemainsTypedInNpcDetailInsteadOfBecomingAbsence() {
        val columns=concrete.infrastructureOpenWorldDb().use { db ->
            db.rawQuery("PRAGMA table_info(canon_characters_v2)",null).use { c ->
                buildList { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
        }
        assertFalse("legacy bundled World Pack fixture intentionally has no sex column",columns.contains("sex"))

        val detail=repository.npcDetailProjection("__P38_AUD002_CORRUPT_PROFILE__",audience,purpose)
        assertEquals(ProjectionDataState.CORRUPTION,detail.profile.dataState)
        assertNull(detail.profile.value)
        assertEquals(ProjectionDataState.DENIED,detail.memories.dataState)
        assertEquals(ProjectionDataState.DENIED,detail.beliefs.dataState)
        assertEquals(ProjectionDataState.DENIED,detail.schedules.dataState)
        assertEquals(ProjectionDataState.DENIED,detail.decisions.dataState)
        assertTrue("presentation conversion may flatten only after typed states exist",detail.toPresentation().fields.isEmpty())
    }

    @Test fun unknownAndNotDisclosedRemainDistinctThroughCanonicalFacade() {
        val unknownKind="P38_AUD002_UNKNOWN_POLICY"
        var reads=0
        val unknown:ProtectedReadResult<List<String>> = repository.protectedReads().protectedRows(
            audience,purpose,unknownKind,"UNKNOWN-SUBJECT"
        ) { reads++; listOf("must-not-disclose") }
        assertTrue(unknown is ProtectedReadResult.Unknown)
        assertEquals(0,reads)
        val unknownRequest=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignUid,unknownKind,"UNKNOWN-SUBJECT"))
        assertEquals(ProjectionDataState.UNKNOWN,unknown.toVisibilityProjection(unknownRequest).dataState)

        val playerUid=requireNotNull(repository.activePlayerRef()?.playerUid)
        val notNecessaryPurpose=PurposeContext(campaignUid,VisibilityPurposeKinds.INTERNAL_SIMULATION)
        val notDisclosed=repository.protectedReads().playerState(audience,notNecessaryPurpose,playerUid)
        assertTrue(notDisclosed is ProtectedReadResult.NotDisclosed)
        val playerStateRequest=VisibilityRequest(audience,notNecessaryPurpose,VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.PLAYER_STATE,playerUid))
        assertEquals(ProjectionDataState.NOT_DISCLOSED,notDisclosed.toVisibilityProjection(playerStateRequest).dataState)
    }

    @Test fun presentationCompatibilityFlattensOnlyAfterTypedReaderBoundary() {
        val typedDenied=repository.relationshipsProjection(audience,purpose)
        val typedNoData=repository.npcsProjection("__P38_AUD002_PRESENTATION_EMPTY__",audience,purpose)
        assertEquals(ProjectionDataState.DENIED,typedDenied.dataState)
        assertEquals(ProjectionDataState.NO_DATA,typedNoData.dataState)

        val presentation=LocalGameStore(context)
        assertTrue(presentation.relationships(audience,purpose).isEmpty())
        assertTrue(presentation.npcs("__P38_AUD002_PRESENTATION_EMPTY__",audience,purpose).isEmpty())

        val socialSource=source("app/src/main/java/com/rpgos/app/SocialReader.kt")
        val npcSource=source("app/src/main/java/com/rpgos/app/NpcWorldDashboardReader.kt")
        val facadeSource=source("app/src/main/java/com/rpgos/app/GameRepository.kt")
        assertTrue(socialSource.contains("relationshipsProjection(audience, purpose).value ?: emptyList()"))
        assertTrue(npcSource.contains("npcsProjection(search, audience, purpose).value ?: emptyList()"))
        assertTrue(facadeSource.contains("fun relationshipsProjection("))
        assertFalse("canonical facade must not expose the legacy flattened relationship list",facadeSource.contains("fun relationships(audience:"))
    }

    private fun repoRoot():File {
        var f=File(System.getProperty("user.dir")).canonicalFile
        repeat(8){if(File(f,"app/src/main/java").isDirectory)return f;f=f.parentFile?:return@repeat}
        error("repo root not found")
    }
    private fun source(path:String)=File(repoRoot(),path).readText()
}
