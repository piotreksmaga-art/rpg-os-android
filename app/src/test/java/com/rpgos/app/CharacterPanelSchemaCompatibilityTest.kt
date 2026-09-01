package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterPanelSchemaCompatibilityTest {
    @Test fun bundledNarutoLabelsSelectTheirRealSchemaInsteadOfObsoleteNameColumns(){
        val bundled=setOf("skill_uid","skill_key","display_name","category_key","description","max_mastery")

        assertEquals(
            CharacterPanelDefinitionShape.BUNDLED_WORLD,
            characterPanelSkillDefinitionShape(bundled,emptySet())
        )
    }

    @Test fun absentLegacyTechniqueDefinitionsAreSkippedInsteadOfCompilingAnInvalidJoin(){
        assertEquals(
            CharacterPanelDefinitionShape.NONE,
            characterPanelTechniqueDefinitionShape(emptySet(),emptySet())
        )
        assertEquals(
            CharacterPanelDefinitionShape.TYPED_V2,
            characterPanelTechniqueDefinitionShape(emptySet(),setOf("technique_uid","display_name","category"))
        )
    }
}
