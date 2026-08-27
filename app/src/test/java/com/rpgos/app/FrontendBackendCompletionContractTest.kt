package com.rpgos.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendBackendCompletionContractTest {
    private fun source(name:String):String{
        val direct=File("src/main/java/com/rpgos/app/$name")
        val root=File("app/src/main/java/com/rpgos/app/$name")
        return (if(direct.isFile)direct else root).readText()
    }

    @Test fun packageTransferAndRecoveryControlsReachProductionActions(){
        val ui=source("MainActivity.kt")
        val vm=source("RpgOsViewModel.kt")
        assertTrue(ui.contains("ActivityResultContracts.OpenDocument"))
        assertTrue(ui.contains("ActivityResultContracts.CreateDocument(\"application/zip\")"))
        assertTrue(ui.contains("vm::importCampaign"))
        assertTrue(ui.contains("vm::importWorldPack"))
        assertTrue(ui.contains("vm::exportActiveCampaign"))
        assertTrue(ui.contains("vm::createManualSnapshot"))
        assertTrue(ui.contains("vm.restoreSnapshot(snapshot.snapshotUid)"))
        assertTrue(ui.contains("vm.restoreBackup(path)"))
        assertTrue(vm.contains("validatedImportCampaign"))
        assertTrue(vm.contains("validatedImportWorldPack"))
        assertTrue(vm.contains("exportCampaign"))
        assertFalse(ui.contains("Button(onClick={},modifier=Modifier.weight(1f)){Text(\"Import Save\")}"))
    }

    @Test fun universalCreatorIsASeparateConfirmationGatedRoute(){
        val ui=source("MainActivity.kt")
        val vm=source("RpgOsViewModel.kt")
        assertTrue(ui.contains("CHARACTER_CREATOR"))
        assertTrue(ui.contains("private fun CharacterCreatorScreen"))
        assertTrue(ui.contains("vm.send(text)"))
        assertTrue(ui.contains("vm::confirmCharacterCreation"))
        assertTrue(ui.contains("if(hasActivePlayer)onEnterCampaign()"))
        assertTrue(vm.contains("characterCreationApplication.play"))
        assertTrue(vm.contains("characterCreationApplication.confirm"))
    }

    @Test fun v2CharacterAndVisualActionsAreVisible(){
        val ui=source("MainActivity.kt")
        val vm=source("RpgOsViewModel.kt")
        assertTrue(source("LocalGameStore.kt").contains("ProductionCharacterPanelV2ReadSource"))
        assertTrue(vm.contains("val characterPanelV2:StateFlow<CharacterPanelSnapshotV2?>"))
        listOf("Talenty","Potencjał","Własność i aktywa","Finanse","Projekty").forEach{assertTrue(ui.contains(it))}
        assertTrue(ui.contains("vm.generateSuggestedVisual(context,suggestion)"))
        assertTrue(ui.contains("vm.editVisual(context,source,editInstruction.trim())"))
        assertTrue(ui.contains("val imageStatus by vm.imageStatus.collectAsState()"))
    }
}
