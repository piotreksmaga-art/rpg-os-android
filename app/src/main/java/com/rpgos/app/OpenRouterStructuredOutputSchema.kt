package com.rpgos.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-side grammar for OpenRouter Structured Outputs.
 *
 * This is only an early rejection boundary. CanonicalAiJsonCodec and the Phase43-54 validators
 * remain authoritative and must reject semantically invalid values even when the provider emitted
 * JSON that conforms to this structural schema.
 */
object OpenRouterStructuredOutputSchema {
    fun responseFormat(workload:AiWorkload):JSONObject = JSONObject()
        .put("type","json_schema")
        .put("json_schema",JSONObject()
            .put("name","rpgos_${workload.name.lowercase()}")
            .put("strict",true)
            .put("schema",schema(workload)))

    fun schema(workload:AiWorkload):JSONObject = when(workload){
        AiWorkload.INTENT_INTERPRETATION->intent()
        AiWorkload.GM_PROPOSAL,AiWorkload.PROPOSAL_REPAIR->proposal()
        AiWorkload.NARRATIVE_RENDER,AiWorkload.NARRATIVE_REPAIR->narrative()
        AiWorkload.CHARACTER_CREATION->characterCreation()
        AiWorkload.DIRECTOR_STRATEGY->director()
    }

    private fun intent():JSONObject = obj(linkedMapOf(
        "schema_version" to integer(),
        "campaign_uid" to text(),
        "actor_kind_uid" to text(),
        "actor_uid" to text(),
        "raw_input" to text(),
        "meaning_state" to enumText("UNDERSTOOD","PARTIAL","UNINTERPRETABLE"),
        "nodes" to array(obj(linkedMapOf(
            "node_uid" to text(),
            "form" to text(),
            "semantic_action" to obj(linkedMapOf(
                "semantic_family_uid" to nullableText(),
                "raw_phrase" to text(),
                "attributes" to stringMap(),
                "confidence_uid" to nullableText()
            )),
            "participants" to array(obj(linkedMapOf(
                "role_uid" to text(),
                "reference_uid" to nullableText(),
                "future_result" to nullable(obj(linkedMapOf(
                    "result_uid" to text(),"role_uid" to text(),"resource" to bool()
                ))),
                "literal_value" to nullableText()
            ))),
            "conditions" to array(obj(linkedMapOf(
                "condition_uid" to text(),"predicate_uid" to text(),"argument_reference_uids" to stringArray(),
                "polarity" to text(),"evaluation_timing_uid" to text()
            ))),
            "dependencies" to array(obj(linkedMapOf("predecessor_node_uid" to text(),"kind" to text()))),
            "intended_result" to nullable(obj(linkedMapOf(
                "result_uid" to text(),"semantic_type_uid" to nullableText(),"description" to text()
            ))),
            "polarity" to text(),"modality" to text(),"commitment_state" to text(),
            "constraints" to array(directive()),"preferences" to array(directive()),
            "termination_condition_uid" to nullableText(),"confidence_uid" to nullableText()
        ))),
        "references" to array(obj(linkedMapOf(
            "reference_uid" to text(),"kind" to text(),"raw_phrase" to nullableText(),"role_uid" to text(),
            "semantic_type_hints" to stringArray(),"descriptor_hints" to stringMap(),"confidence_uid" to nullableText()
        ))),
        "global_constraints" to array(directive()),
        "global_preferences" to array(directive()),
        "uncertainties" to stringArray(),
        "player_context_claims" to array(obj(linkedMapOf(
            "claim_uid" to text(),"surface_text" to text(),"meaning_canonical" to text(),
            "epistemic_role_uid" to text(),"linked_intent_node_uid" to nullableText()
        ))),
        "provider_uid" to text(),"model_version" to text()
    ))

    private fun proposal():JSONObject = obj(linkedMapOf(
        "schema_version" to integer(),"proposal_uid" to text(),"campaign_uid" to text(),"plan_uid" to text(),
        "node_proposals" to array(obj(linkedMapOf(
            "node_uid" to text(),"outcome_uid" to text(),"player_facing_summary" to text(),
            "actor_kind_uid" to text(),"actor_uid" to text(),"action_semantic_uid" to text(),
            "target_projected_refs" to array(domainRef()),"modality" to text(),"outcome_state" to text(),
            "uncertainty_uids" to stringArray(),"materialized_result_uids" to stringArray()
        ))),
        "proposed_claims" to array(obj(linkedMapOf(
            "claim_uid" to text(),"node_uid" to text(),"claim_kind" to text(),"subject_projected_uid" to nullableText(),
            "predicate_uid" to text(),"value_canonical" to text(),"supporting_record_uids" to stringArray(),
            "supporting_player_claim_uids" to stringArray()
        ))),
        "mechanics_effects" to array(obj(linkedMapOf(
            "effect_uid" to text(),"node_uid" to text(),"mechanics_owner_uid" to text(),"effect_kind_uid" to text(),
            "target_projected_ref" to nullable(domainRef()),"parameters" to stringMap()
        ))),
        "narrative_blueprint" to obj(linkedMapOf(
            "beat_uids" to stringArray(),"tone_hint_uids" to stringArray(),"stop_point_uid" to text(),
            "forbidden_disclosure_uids" to stringArray()
        )),
        "provider_uid" to text(),"model_uid" to text(),"intent_fingerprint" to text(),
        "requested_player_volitional_action_uids" to stringArray(),"player_decision_point_uid" to nullableText()
    ))

    private fun narrative():JSONObject = obj(linkedMapOf(
        "text" to text(),"stop_reason_uid" to text(),"committed_order" to integer(),
        "claims" to array(obj(linkedMapOf(
            "claim_uid" to text(),"kind" to text(),"support_fact_uid" to nullableText(),
            "predicate_uid" to nullableText(),"value_canonical" to nullableText()
        ))),
        "asserts_player_volition" to bool()
    ))

    private fun characterCreation():JSONObject = oneOf(
        obj(linkedMapOf(
            "state" to enumText("NEEDS_PLAYER_CHOICE"),"question" to text(),"missing_category_uids" to stringArray()
        )),
        obj(linkedMapOf(
            "state" to enumText("READY_FOR_CONFIRMATION"),
            "draft" to obj(linkedMapOf(
                "creation_uid" to text(),"campaign_uid" to text(),"player_uid" to text(),"display_name" to text(),
                "gender_uid" to text(),"identity_choices" to stringMap(),"stats" to array(valueChoice()),
                "resources" to array(valueChoice()),"talents" to array(valueChoice()),"potentials" to array(valueChoice()),
                "skills" to array(valueChoice()),"techniques" to array(valueChoice()),"origin_uids" to stringArray(),
                "innate_feature_uids" to stringArray(),"starting_location_uid" to text(),
                "starting_x_millimetres" to integer(),"starting_y_millimetres" to integer()
            )),
            "player_facing_summary" to text()
        ))
    )

    private fun director():JSONObject = obj(linkedMapOf(
        "schema_version" to integer(),"bundle_uid" to text(),"job_uid" to text(),"campaign_uid" to text(),
        "trigger_uid" to text(),"context_version" to text(),"as_of_committed_order" to integer(),
        "provider_uid" to text(),"model_uid" to text(),
        "candidates" to array(obj(linkedMapOf(
            "candidate_uid" to text(),"kind" to text(),"title" to text(),"summary" to text(),
            "supporting_projected_record_uids" to stringArray(),"horizon_uid" to text(),"pacing_tags" to stringArray(),
            "proposed_owner_phase_uid" to text(),"direct_mutation_payload" to nullableText()
        ))),
        "created_against_fingerprint" to text()
    ))

    private fun directive()=obj(linkedMapOf(
        "directive_uid" to text(),"kind" to text(),"strength" to text(),"value_canonical" to text(),
        "scope_node_uid" to nullableText()
    ))
    private fun domainRef()=obj(linkedMapOf("kind_uid" to text(),"uid" to text()))
    private fun valueChoice()=obj(linkedMapOf(
        "definition_uid" to text(),"value" to number(),"dimension_uid" to nullableText()
    ))
    private fun obj(properties:LinkedHashMap<String,JSONObject>)=JSONObject()
        .put("type","object").put("properties",JSONObject(properties as Map<*,*>))
        .put("required",JSONArray(properties.keys.toList())).put("additionalProperties",false)
    private fun array(items:JSONObject)=JSONObject().put("type","array").put("items",items)
    private fun stringArray()=array(text())
    private fun stringMap()=JSONObject().put("type","object").put("additionalProperties",text())
    private fun text()=JSONObject().put("type","string")
    private fun integer()=JSONObject().put("type","integer")
    private fun number()=JSONObject().put("type","number")
    private fun bool()=JSONObject().put("type","boolean")
    private fun enumText(vararg values:String)=text().put("enum",JSONArray(values.toList()))
    private fun nullable(schema:JSONObject)=JSONObject().put("anyOf",JSONArray().put(schema).put(nullSchema()))
    private fun nullableText()=nullable(text())
    private fun oneOf(vararg schemas:JSONObject)=JSONObject().put("oneOf",JSONArray(schemas.toList()))

    private fun nullSchema()=JSONObject().put("type","null")
}
