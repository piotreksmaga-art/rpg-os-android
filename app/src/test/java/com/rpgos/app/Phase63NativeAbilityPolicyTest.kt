package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63NativeAbilityPolicyTest {
    @Test fun aWorldDescriptionOrSemanticFamilyCannotGiveThePlayerExceptionalMechanics() {
        assertTrue(Phase63NativeAbilityPolicy.admitted("ATTACK",emptySet(),false))
        for(ability in listOf("BLAST","EXPLOSION","AOE","TELEPORT","MAGIC")) {
            assertFalse(Phase63NativeAbilityPolicy.admitted(ability,emptySet(),false))
            assertFalse(Phase63NativeAbilityPolicy.admitted(ability,setOf(ability),false))
            assertFalse(Phase63NativeAbilityPolicy.admitted(ability,emptySet(),true))
            assertTrue(Phase63NativeAbilityPolicy.admitted(ability,setOf(ability),true))
        }
        assertFalse(CombatAbilityContractPort.UNIVERSAL_FALLBACK.isRegistered("BLAST"))
        assertTrue(CombatAbilityContractPort.registered(listOf(CombatAbilityContract("BLAST"))).isRegistered("BLAST"))
    }
}
