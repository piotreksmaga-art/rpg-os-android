package com.rpgos.app

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class UnifiedGameRepositoryMemoryArtifactLookupTest{
    private lateinit var filesRoot:File
    private lateinit var context:Context
    private lateinit var repository:UnifiedGameRepository

    @Before fun setUp(){
        filesRoot=File.createTempFile("memory-artifact-lookup-","").apply{delete();mkdirs()}
        val base:Context=RuntimeEnvironment.getApplication()
        base.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit()
        context=object:ContextWrapper(base){
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=filesRoot
        }
        repository=UnifiedGameRepository(context).also{it.bootstrap()}
    }

    @After fun tearDown(){
        repository.closeBackgroundWorkForTest()
        context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit()
        filesRoot.deleteRecursively()
    }

    @Test fun revisionLookupIsBoundedAndKeepsCampaignGenerationCleanAndAsOfAuthority(){
        val campaignUid=repository.activeCampaignRef().campaignId
        val currentGeneration=repository.infrastructureHistoryGenerationUid()
        LocalGameStore(context).openGameplaySaveDb().use{db->
            Phase55To58MemorySchema.ensureReady(db,campaignUid)
            val store=MemoryArtifactStore(db)
            store.upsert(identity(campaignUid,currentGeneration,"ACTIVE",4),MemoryArtifactStatus.CLEAN,"{\"uid\":\"ACTIVE\"}")
            store.upsert(identity(campaignUid,currentGeneration,"UNREQUESTED",3),MemoryArtifactStatus.CLEAN,"{\"uid\":\"UNREQUESTED\"}")
            store.upsert(identity(campaignUid,currentGeneration,"DIRTY",2),MemoryArtifactStatus.DIRTY,"{\"uid\":\"DIRTY\"}")
            store.upsert(identity(campaignUid,currentGeneration,"FUTURE",9),MemoryArtifactStatus.CLEAN,"{\"uid\":\"FUTURE\"}")
            store.upsert(identity(campaignUid,HistoryGenerationUid("HGEN-OLD"),"OLD-GENERATION",1),MemoryArtifactStatus.CLEAN,"{\"uid\":\"OLD\"}")
        }

        val requested=setOf("REV-ACTIVE","REV-DIRTY","REV-FUTURE","REV-OLD-GENERATION")
        val result=repository.infrastructureActiveMemoryArtifacts(asOfOrder=5,revisionUids=requested)

        assertEquals(listOf("REV-ACTIVE"),result.map{it.artifactRevisionUid})
        assertEquals(currentGeneration,result.single().historyGenerationUid)
        assertEquals(campaignUid,result.single().campaignUid)

        val tooMany=(1..201).mapTo(linkedSetOf()){index->"REV-$index"}
        val failure=assertThrows(IllegalArgumentException::class.java){
            repository.infrastructureActiveMemoryArtifacts(asOfOrder=5,revisionUids=tooMany)
        }
        assertEquals("RPGOS-MEMORY:ARTIFACT_REVISION_LOOKUP_LIMIT",failure.message)
    }

    private fun identity(
        campaignUid:String,
        generation:HistoryGenerationUid,
        suffix:String,
        asOfOrder:Long
    ):MemoryArtifactIdentity{
        val leaf=MemorySourceLeafRef("EVENT","EVENT-$suffix",1,asOfOrder,"FP-$suffix")
        return MemoryArtifactIdentity(
            campaignUid,generation,"LOGICAL-$suffix","REV-$suffix",MemoryArtifactKind.EPISODE_MANIFEST,
            listOf(leaf),memoryLeafFingerprint(listOf(leaf)),"RPGOS-TEST",1,asOfOrder,asOfOrder,asOfOrder
        )
    }
}
