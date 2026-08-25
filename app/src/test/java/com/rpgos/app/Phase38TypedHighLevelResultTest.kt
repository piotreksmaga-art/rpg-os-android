package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

    @Test fun bundledWorldPackMissingOptionalProfileColumnsRemainsCompatible() {
        val (columns,existingUid)=concrete.infrastructureOpenWorldDb().use { db ->
            val names=db.rawQuery("PRAGMA table_info(canon_characters_v2)",null).use { c ->
                buildList { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            val uid=db.rawQuery("SELECT character_uid FROM canon_characters_v2 ORDER BY character_uid LIMIT 1",null).use { c ->
                assertTrue(c.moveToFirst())
                c.getString(0)
            }
            names to uid
        }
        assertFalse(columns.contains("sex"))
        assertFalse(columns.contains("affiliation_summary"))
        assertFalse(columns.contains("status"))

        val readerSource=source("app/src/main/java/com/rpgos/app/CanonCharacterProjectionReader.kt")
        assertTrue(readerSource.contains("optionalText(\"status\")"))
        assertTrue(readerSource.contains("val REQUIRED_COLUMNS = setOf(\"character_uid\", \"name\")"))

        val list=repository.npcsProjection("",audience,purpose)
        assertEquals(ProjectionDataState.DISCLOSED,list.dataState)
        assertTrue(list.value!!.isNotEmpty())
        assertTrue(list.value!!.all { it.status.isEmpty() })

        val detail=repository.npcDetailProjection(existingUid,audience,purpose)
        assertEquals(ProjectionDataState.DISCLOSED,detail.profile.dataState)
        val fields=detail.profile.value!!.associate { it.key to it.value }
        assertEquals(existingUid,fields["character_uid"])
        assertEquals("",fields["sex"])
        assertEquals("",fields["affiliation_summary"])
        assertEquals("",fields["status"])
        assertEquals(ProjectionDataState.DENIED,detail.memories.dataState)
        assertEquals(ProjectionDataState.DENIED,detail.beliefs.dataState)
        assertEquals(ProjectionDataState.DENIED,detail.schedules.dataState)
        assertEquals(ProjectionDataState.DENIED,detail.decisions.dataState)
    }

    @Test fun missingRequiredCanonCharacterColumnRemainsTypedCorruption() {
        SQLiteDatabase.create(null).use { world ->
            SQLiteDatabase.create(null).use { save ->
                world.execSQL("CREATE TABLE canon_characters_v2(character_uid TEXT PRIMARY KEY, status TEXT)")
                world.execSQL("INSERT INTO canon_characters_v2(character_uid,status) VALUES('BROKEN','active')")
                val projection=NpcWorldDashboardReader(world,save).npcsProjection(
                    "",VisibilityAudienceFactory.player("C"),PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI)
                )
                assertEquals(ProjectionDataState.CORRUPTION,projection.dataState)
                assertNull(projection.value)
            }
        }
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

        val playerUid=repository.activePlayerRef()?.playerUid ?: existingCanonicalPlayerUid().also { repository.setActivePlayer(it) }
        assertEquals(playerUid,repository.activePlayerRef()?.playerUid)
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

    private fun existingCanonicalPlayerUid():String = LocalGameStore(context).openGameplaySaveDb().use { db ->
        val sources=listOf(
            "character_status_snapshot" to "entity_uid",
            "character_stats" to "entity_uid",
            "character_skills" to "entity_uid",
            "character_techniques" to "entity_uid",
            "character_finances" to "entity_uid",
            "character_goals" to "entity_uid",
            "entity_positions" to "entity_uid",
            "organization_memberships_v3" to "character_uid"
        )
        sources.firstNotNullOfOrNull { (table,column) ->
            val exists=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(table)).use{it.moveToFirst()}
            if(!exists) return@firstNotNullOfOrNull null
            val hasColumn=db.rawQuery("PRAGMA table_info($table)",null).use { c ->
                val nameIndex=c.getColumnIndex("name")
                var found=false
                while(c.moveToNext()) if(nameIndex>=0&&c.getString(nameIndex).equals(column,ignoreCase=true)) found=true
                found
            }
            if(!hasColumn) return@firstNotNullOfOrNull null
            db.rawQuery("SELECT $column FROM $table WHERE $column IS NOT NULL AND trim($column)<>'' ORDER BY $column LIMIT 1",null).use { c ->
                if(c.moveToFirst()) c.getString(0) else null
            }
        } ?: error("Phase38 AUD-002 fixture requires one existing canonical player identity")
    }

    private fun repoRoot():File {
        var f=File(System.getProperty("user.dir")).canonicalFile
        repeat(8){if(File(f,"app/src/main/java").isDirectory)return f;f=f.parentFile?:return@repeat}
        error("repo root not found")
    }
    private fun source(path:String)=File(repoRoot(),path).readText()
}
