package com.rpgos.app

/** Prose and intent labels never grant powers in a campaign-native world. Physical baseline
 * interactions use Core; other abilities need both acquisition and a registered domain rule. */
internal object Phase63NativeAbilityPolicy {
    private val physicalBaseline=setOf("ATTACK","COMBAT","STRIKE","FIGHT","DEFEND")
    fun admitted(abilityUid:String,acquired:Set<String>,registered:Boolean):Boolean =
        abilityUid in physicalBaseline || (registered && abilityUid in acquired)
}
