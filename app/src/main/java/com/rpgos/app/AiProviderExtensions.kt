package com.rpgos.app

import java.util.concurrent.ConcurrentHashMap

/**
 * Optional composition extensions are registered by variant-owned code before the application UI
 * is created.  Production/release installs no extension and therefore keeps its previous provider
 * graph and persisted settings unchanged.
 */
interface AiProviderExtension {
    val extensionUid:String
    fun providers():List<AiProvider> = emptyList()
    fun overrideConfiguration(base:AiSystemConfiguration):AiSystemConfiguration = base
    fun modelOptions():List<AiModelOptionUi> = emptyList()
    /** Return true when the assignment belongs to this extension and was handled out of band. */
    fun assign(role:AiRole,selection:AiModelSelection?):Boolean = false
    fun onCampaignOpened(campaignUid:String) = Unit
    fun onCanonicalCommit(receipt:TurnCommitReceipt) = Unit
    fun onCharacterCreated(campaignUid:String,playerUid:String) = Unit
    fun directorGuidancePort():DirectorGuidancePort = DirectorGuidancePort.NONE
}

object AiProviderExtensionRegistry {
    private val extensions=ConcurrentHashMap<String,AiProviderExtension>()

    fun register(extension:AiProviderExtension){
        require(extension.extensionUid.isNotBlank())
        extensions[extension.extensionUid]=extension
    }

    fun unregister(extensionUid:String){extensions.remove(extensionUid)}

    fun providers():List<AiProvider> = extensions.values.sortedBy{it.extensionUid}.flatMap{it.providers()}

    fun configuration(base:AiSystemConfiguration):AiSystemConfiguration =
        extensions.values.sortedBy{it.extensionUid}.fold(base){current,extension->extension.overrideConfiguration(current)}

    fun modelOptions():List<AiModelOptionUi> = extensions.values.sortedBy{it.extensionUid}.flatMap{it.modelOptions()}

    fun assign(role:AiRole,selection:AiModelSelection?):Boolean =
        extensions.values.sortedBy{it.extensionUid}.any{it.assign(role,selection)}

    fun onCampaignOpened(campaignUid:String){extensions.values.forEach{it.onCampaignOpened(campaignUid)}}
    fun onCanonicalCommit(receipt:TurnCommitReceipt){extensions.values.forEach{it.onCanonicalCommit(receipt)}}
    fun onCharacterCreated(campaignUid:String,playerUid:String){extensions.values.forEach{it.onCharacterCreated(campaignUid,playerUid)}}

    fun directorGuidancePort():DirectorGuidancePort{
        val ports=extensions.values.sortedBy{it.extensionUid}.map{it.directorGuidancePort()}.filter{it!==DirectorGuidancePort.NONE}
        return if(ports.isEmpty())DirectorGuidancePort.NONE else DirectorGuidancePort{campaignUid,asOfOrder,authorizedRecordUids->
            ports.asSequence().mapNotNull{it.guidance(campaignUid,asOfOrder,authorizedRecordUids)}.firstOrNull()
        }
    }

    internal fun clearForTest(){extensions.clear()}
}

/** Dynamic providers (for example a host bridge) report their own liveness to the router. */
fun interface AiProviderAvailabilityReporter {
    fun currentAvailability():AiProviderAvailability
}
