package com.rpgos.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CampaignFrontendSafetyContractTest {
    private fun source(relative:String):String{
        val direct=File("src/main/java/com/rpgos/app/$relative")
        val fromRoot=File("app/src/main/java/com/rpgos/app/$relative")
        return (if(direct.isFile)direct else fromRoot).readText()
    }

    @Test fun newCampaignNavigatesOnlyAfterSuccessfulCreation(){
        val ui=source("MainActivity.kt")
        val viewModel=source("RpgOsViewModel.kt")
        assertTrue(ui.contains("LaunchedEffect(creationUi.completedCampaignDir)"))
        assertTrue(ui.contains("enabled = !creationUi.inProgress"))
        assertTrue(ui.contains("creationUi.errorMessage"))
        assertTrue(viewModel.contains("DiagnosticLogger.log(app,\"CAMPAIGN_CREATE_FAILED\",t)"))
        assertTrue(viewModel.contains("withContext(Dispatchers.IO)"))
        assertTrue(source("LocalGameStore.kt").contains("CAMPAIGN_CREATE_ROLLBACK_SELECTION_FAILED"))
        assertTrue(source("LocalGameStore.kt").contains("CAMPAIGN_CREATE_QUARANTINE_FAILED"))
    }

    @Test fun campaignRemovalRequiresConfirmationAndUsesRecoverableBackend(){
        val ui=source("MainActivity.kt")
        val manager=source("CampaignSelectionManager.kt")
        assertTrue(ui.contains("Usunąć kampanię?"))
        assertTrue(ui.contains("Przenieś do kosza"))
        assertTrue(manager.contains("fun moveCampaignToTrash"))
        assertTrue(manager.contains("dirName != ActiveCampaignRef.DEFAULT_DIRECTORY"))
        assertTrue(manager.contains("dirName != activeCampaignDirName()"))
        assertTrue(manager.contains("File(saves, \".trash\")"))
    }
}
