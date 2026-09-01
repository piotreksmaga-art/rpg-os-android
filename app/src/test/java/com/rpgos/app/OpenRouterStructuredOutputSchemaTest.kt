package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterStructuredOutputSchemaTest{
    @Test fun everyWorkloadUsesNamedStrictJsonSchemaWithClosedRoot(){
        AiWorkload.entries.forEach{workload->
            val format=OpenRouterStructuredOutputSchema.responseFormat(workload)
            assertEquals("json_schema",format.getString("type"))
            val envelope=format.getJSONObject("json_schema")
            assertEquals("rpgos_${workload.name.lowercase()}",envelope.getString("name"))
            assertTrue(envelope.getBoolean("strict"))
            val schema=envelope.getJSONObject("schema")
            assertEquals("object",schema.getString("type"))
            assertFalse(schema.getBoolean("additionalProperties"))
            assertCodexStrict(schema)
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

    @Test fun committedNarrativeClaimsRequireCompleteCanonicalFactEvidence(){
        listOf(AiWorkload.NARRATIVE_RENDER,AiWorkload.NARRATIVE_REPAIR).forEach{workload->
            val claim=OpenRouterStructuredOutputSchema.schema(workload)
                .getJSONObject("properties").getJSONObject("claims")
                .getJSONObject("items").getJSONObject("properties")
            listOf("support_fact_uid","predicate_uid","value_canonical").forEach{field->
                assertEquals("string",claim.getJSONObject(field).getString("type"))
                assertFalse(claim.getJSONObject(field).has("anyOf"))
            }
        }
    }

    @Test fun canonicalIntentDecoderPreservesJsonNullsAndSingletonArrays(){
        val decoded=CanonicalAiJsonCodec().decodeIntent("""{
            "schema_version":2,"campaign_uid":"C","actor_kind_uid":"PLAYER","actor_uid":"P",
            "raw_input":"Rozglądam się.","meaning_state":"UNDERSTOOD",
            "nodes":[{"node_uid":"N1","form":"DIRECT_ACTION","semantic_action":{
                "semantic_family_uid":"OBSERVE","raw_phrase":"Rozglądam się","attributes":{"provider_action":null},"confidence_uid":null},
                "participants":[{"role_uid":"AREA","reference_uid":"R1","future_result":null,"literal_value":null}],
                "conditions":[],"dependencies":[],"intended_result":null,"polarity":"AFFIRMATIVE","modality":"ATTEMPT_NOW",
                "commitment_state":"ACTIVE","constraints":[],"preferences":[],"termination_condition_uid":null,"confidence_uid":null}],
            "references":[{"reference_uid":"R1","kind":"DESCRIPTIVE","raw_phrase":"okolica","role_uid":"AREA",
                "semantic_type_hints":["AREA"],"descriptor_hints":{"surface":null},"confidence_uid":null}],
            "global_constraints":[],"global_preferences":[],"uncertainties":[],"player_context_claims":[],
            "provider_uid":"AI_PROVIDER","model_version":"1"
        }""".trimIndent())

        assertEquals(setOf("AREA"),decoded.references.single().semanticTypeHints)
        assertNull(decoded.nodes.single().semanticAction.confidenceUid)
        assertNull(decoded.nodes.single().participants.single().literalValue)
        assertTrue(decoded.nodes.single().semanticAction.attributes.isEmpty())
        assertTrue(decoded.references.single().descriptorHints.isEmpty())
    }

    @Test fun intentRequestPublishesTheClosedCoreSemanticVocabularyAndOpenActionEscapeHatch(){
        val encoded=org.json.JSONObject(CanonicalAiJsonCodec().encodeIntent(AiIntentRequest(
            "R","C",CommandActorRef("PLAYER","P"),"Ukrywam się.","pl-PL"
        )))
        val families=encoded.getJSONArray("registered_semantic_families").let{array->
            (0 until array.length()).map(array::getString).toSet()
        }

        assertTrue("HIDE" in families)
        assertTrue("OPEN_WORLD_ACTION" in families)
        assertTrue(encoded.toString().contains("attributes.provider_action"))
    }

    private fun assertCodexStrict(schema:org.json.JSONObject){
        assertFalse(schema.has("oneOf"))
        schema.optJSONArray("anyOf")?.let{array->
            for(index in 0 until array.length())assertCodexStrict(array.getJSONObject(index))
        }
        when(schema.optString("type")){
            "object"->{
                assertFalse(schema.optBoolean("additionalProperties",true))
                val properties=schema.getJSONObject("properties")
                val keys=properties.keys().asSequence().toSet()
                val required=schema.getJSONArray("required").let{array->(0 until array.length()).map(array::getString).toSet()}
                assertEquals(keys,required)
                keys.forEach{assertCodexStrict(properties.getJSONObject(it))}
            }
            "array"->assertCodexStrict(schema.getJSONObject("items"))
        }
    }
}
