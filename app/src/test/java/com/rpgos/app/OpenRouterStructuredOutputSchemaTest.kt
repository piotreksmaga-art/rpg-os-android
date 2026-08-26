package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class OpenRouterStructuredOutputSchemaTest{
    @Test fun everyWorkloadUsesNamedStrictJsonSchemaWithClosedRoot(){
        AiWorkload.entries.forEach{workload->
            val format=OpenRouterStructuredOutputSchema.responseFormat(workload)
            assertEquals("json_schema",format.getString("type"))
            val envelope=format.getJSONObject("json_schema")
            assertEquals("rpgos_${workload.name.lowercase()}",envelope.getString("name"))
            assertTrue(envelope.getBoolean("strict"))
            val schema=envelope.getJSONObject("schema")
            if(schema.has("type")){
                assertEquals("object",schema.getString("type"))
                assertFalse(schema.getBoolean("additionalProperties"))
            }else assertTrue(schema.has("oneOf"))
        }
    }

    @Test fun statusAndAbilityRemainOutsideProviderSchemaAuthority(){
        val proposal=OpenRouterStructuredOutputSchema.schema(AiWorkload.GM_PROPOSAL)
        val effect=proposal.getJSONObject("properties").getJSONObject("mechanics_effects")
            .getJSONObject("items").getJSONObject("properties")
        assertTrue(effect.has("effect_kind_uid"))
        assertTrue(effect.has("parameters"))
        assertFalse(proposal.toString().contains("BURNING"))
        assertFalse(proposal.toString().contains("FIREBALL"))
    }
}
