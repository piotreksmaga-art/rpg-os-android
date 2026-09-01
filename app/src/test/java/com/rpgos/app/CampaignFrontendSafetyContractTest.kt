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
        assertTrue(ui.contains("vm.createAndActivateCampaign(newCampaignName)"))
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

    @Test fun continueCampaignIsAsyncGuardedAndRollsBackFailedSelection(){
        val ui=source("MainActivity.kt")
        val viewModel=source("RpgOsViewModel.kt")
        val store=source("LocalGameStore.kt")
        assertTrue(ui.contains("LaunchedEffect(managementUi.activatedCampaignDir)"))
        assertTrue(ui.contains("Przygotowywanie zapisu…"))
        assertTrue(viewModel.contains("val alreadyActive=dirName==previousCampaign"))
        assertTrue(viewModel.contains("if(!alreadyActive){"))
        assertTrue(viewModel.contains("store.setActiveCampaign(dirName)"))
        assertTrue(viewModel.contains("resetConversationForActiveCampaign(dirName)"))
        assertTrue(viewModel.contains("DiagnosticLogger.log(app,\"CAMPAIGN_ACTIVATION_FAILED\",t)"))
        assertTrue(viewModel.contains("activatedCampaignDir=dirName"))
        assertTrue(store.contains("CAMPAIGN_ACTIVATION_ROLLBACK_FAILED"))
        assertTrue(store.contains("selection.setActiveCampaign(previousCampaign)"))
    }

    @Test fun campaignStorageTransitionsStopEverySemanticWorkerBeforeSelectionOrReplacement(){
        val store=source("LocalGameStore.kt")
        val semantic=source("BekkoSemanticApplication.kt")
        assertTrue(semantic.contains("SemanticCampaignTransitionRegistry"))
        assertTrue(semantic.contains("CopyOnWriteArrayList<WeakReference<()->Unit>>"))
        assertTrue(semantic.contains("init{SemanticCampaignTransitionRegistry.register(campaignTransitionListener)}"))
        assertTrue(semantic.contains("SemanticCampaignTransitionRegistry.unregister(campaignTransitionListener)"))
        assertTrue(store.contains("if(dirName!=previousCampaign)SemanticCampaignTransitionRegistry.beforeCampaignStorageTransition()"))
        assertTrue(store.contains("if(dirName!=selection.activeWorldPackDirName())SemanticCampaignTransitionRegistry.beforeCampaignStorageTransition()"))
        assertTrue(store.substringAfter("fun createCampaign(name: String)")
            .substringBefore("fun moveCampaignToTrash").contains("SemanticCampaignTransitionRegistry.beforeCampaignStorageTransition()"))
        assertTrue(store.substringAfter("fun restoreSnapshot(snapshotUid:String?)")
            .substringBefore("fun finalizeChapter").contains("SemanticCampaignTransitionRegistry.beforeCampaignStorageTransition()"))
    }

    @Test fun startupGateClosesBeforeModelAndSemanticHydration(){
        val viewModel=source("RpgOsViewModel.kt")
        val startup=viewModel.substringAfter("private fun beginStartup()")
            .substringBefore("private fun hydrateAfterLaunch()")
        assertTrue(startup.contains("store.bootstrap()"))
        assertTrue(startup.contains("refreshLaunchState()"))
        assertTrue(startup.contains("_startupUi.value=AppStartupUiState(inProgress=false)"))
        assertTrue(startup.contains("hydrateAfterLaunch()"))
        assertTrue(
            startup.indexOf("_startupUi.value=AppStartupUiState(inProgress=false)") <
                startup.indexOf("hydrateAfterLaunch()")
        )
        val hydration=viewModel.substringAfter("private fun hydrateAfterLaunch()")
            .substringBefore("private fun buildStartupContext()")
        assertTrue(hydration.contains("providerCenterApplication.initialState"))
        assertTrue(hydration.contains("semanticApplication.onCampaignOpened()"))
        assertTrue(hydration.contains("chatApplication.pendingRecovery()"))
        assertTrue(hydration.contains("runCatching{refresh()}"))
    }

    @Test fun ordinaryBootstrapVerifiesReadinessBeforeAdministrativeMigration(){
        val store=source("LocalGameStore.kt")
        val bootstrap=store.substringAfter("fun bootstrap()")
            .substringBefore("private fun reconcileCanonicalPackageRoots()")
        assertTrue(bootstrap.contains("GameplayRuntimeBootstrap.requireReady(save,campaignUid)"))
        assertTrue(bootstrap.contains("if(!alreadyReady){"))
        assertTrue(bootstrap.contains("AutoRepairEngine().repair(save)"))
        assertTrue(bootstrap.contains("GameplayRuntimeBootstrap.initialize(save, campaignUid)"))
        assertTrue(
            bootstrap.indexOf("GameplayRuntimeBootstrap.requireReady(save,campaignUid)") <
                bootstrap.indexOf("if(!alreadyReady){")
        )
    }

    @Test fun gameplayContextUsesTheCurrentCanonicalInjuryColumns(){
        val context=source("ContextBuilder.kt")
        assertTrue(context.contains("pain_level,bleeding_rate,status,chapter_received FROM injuries_v2"))
        assertTrue(!context.contains("severity,pain,bleeding,status,created_chapter FROM injuries_v2"))
    }

    @Test fun legacySkillReadSupportsTheBundledWorldPackLabelSchema(){
        val skills=source("SkillStore.kt")
        assertTrue(skills.contains("columnExists(\"skill_definitions\",\"display_name\")"))
        assertTrue(skills.contains("columnExists(\"skill_definitions\",\"category_key\")"))
        assertTrue(skills.contains("sd.display_name"))
        assertTrue(skills.contains("sd.category_key"))
    }
}
