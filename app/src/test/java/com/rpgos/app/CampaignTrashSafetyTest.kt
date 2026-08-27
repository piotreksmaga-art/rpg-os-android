package com.rpgos.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CampaignTrashSafetyTest {
    private lateinit var context:Context
    private lateinit var saves:File

    @Before fun setUp(){
        context=RuntimeEnvironment.getApplication().applicationContext
        File(context.filesDir,"rpgos").deleteRecursively()
        context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit()
        saves=File(context.filesDir,"rpgos/saves").apply{mkdirs()}
        File(saves,ActiveCampaignRef.DEFAULT_DIRECTORY).mkdirs()
    }

    @After fun tearDown(){
        File(context.filesDir,"rpgos").deleteRecursively()
        context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun inactiveCampaignIsMovedToHiddenRecoverableTrash(){
        val source=File(saves,"old-save.campaign").apply{mkdirs();File(this,"campaign.db").writeText("test")}

        val destination=CampaignSelectionManager(context).moveCampaignToTrash(source.name)

        assertFalse(source.exists())
        assertTrue(destination.isDirectory)
        assertTrue(destination.parentFile?.name==".trash")
        assertTrue(File(destination,"campaign.db").isFile)
        assertTrue(RpgPackageManager(context).listCampaigns().none{it.path==destination.absolutePath})
    }

    @Test fun activeAndSystemCampaignsAreProtected(){
        val manager=CampaignSelectionManager(context)
        val active=File(saves,"active.campaign").apply{mkdirs()}
        manager.setActiveCampaign(active.name)

        val activeFailure=runCatching{manager.moveCampaignToTrash(active.name)}.exceptionOrNull()
        val systemFailure=runCatching{manager.moveCampaignToTrash(ActiveCampaignRef.DEFAULT_DIRECTORY)}.exceptionOrNull()

        assertTrue(activeFailure?.message.orEmpty().contains("aktywnej kampanii"))
        assertTrue(systemFailure?.message.orEmpty().contains("systemowej"))
        assertTrue(active.isDirectory)
        assertTrue(File(saves,ActiveCampaignRef.DEFAULT_DIRECTORY).isDirectory)
    }

    @Test fun pathTraversalIsRejected(){
        val failure=runCatching{CampaignSelectionManager(context).moveCampaignToTrash("../outside.campaign")}.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Nieprawidłowa"))
    }
}
