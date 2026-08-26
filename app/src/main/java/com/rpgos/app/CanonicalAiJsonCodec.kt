package com.rpgos.app

import org.json.JSONArray
import org.json.JSONObject

/** Strict provider wire schema. JSON is transport data only; every decoded value is revalidated by Core. */
class CanonicalAiJsonCodec:AiStructuredCodec{
    override fun encodeIntent(request:AiIntentRequest)=JSONObject()
        .put("contract","RPGOS_INTENT_DOCUMENT_V2")
        .put("request",JSONObject().put("campaign_uid",request.campaignUid).put("actor_kind_uid",request.actor.actorKindUid)
            .put("actor_uid",request.actor.actorUid).put("raw_input",request.rawInput).put("locale_uid",request.localeUid))
        .put("requirements",JSONArray(listOf(
            "Preserve the exact request identity and raw input","Return semantic families, never canonical action IDs",
            "References remain unresolved descriptors; never invent world IDs","Represent sequence, negation, condition, correction and ambiguity explicitly"
        )))
        .put("response_schema",JSONObject().put("schema_version",PHASE43_INTENT_SCHEMA_VERSION).put("meaning_state","UNDERSTOOD|PARTIAL|UNINTERPRETABLE")
            .put("nodes","array").put("references","array").put("uncertainties","array").put("player_context_claims","array"))
        .toString()

    override fun decodeIntent(payload:String):IntentDocument{
        val root=strictObject(payload)
        require(root.reqInt("schema_version")==PHASE43_INTENT_SCHEMA_VERSION)
        val actor=CommandActorRef(root.reqString("actor_kind_uid"),root.reqString("actor_uid"))
        val references=root.array("references").objects().map{decodeReference(it)}
        val nodes=root.array("nodes").objects().map{decodeNode(it)}
        return IntentDocument(
            campaignUid=root.reqString("campaign_uid"),actor=actor,rawInput=root.reqString("raw_input"),
            meaningState=enumValue(root.reqString("meaning_state")),nodes=nodes,references=references,
            globalConstraints=root.array("global_constraints").objects().map(::decodeDirective),
            globalPreferences=root.array("global_preferences").objects().map(::decodeDirective),
            uncertainties=root.array("uncertainties").strings(),
            playerContextClaims=root.array("player_context_claims").objects().map{claim->PlayerContextClaim(
                claim.reqString("claim_uid"),claim.reqString("surface_text"),claim.reqString("meaning_canonical"),
                claim.optString("epistemic_role_uid")?:"PLAYER_ASSERTION",claim.optString("linked_intent_node_uid")
            )},
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,root.optString("provider_uid")?:"AI_PROVIDER",root.optString("model_version")?:"1",phase43InputHash(root.reqString("raw_input")))
        )
    }

    override fun encodeProposal(request:AiGmProposalRequest)=JSONObject()
        .put("contract","RPGOS_GM_PROPOSAL_V1")
        .put("identity",JSONObject().put("campaign_uid",request.plan.campaignUid).put("plan_uid",request.plan.planUid)
            .put("intent_fingerprint",request.plan.intent.canonicalFingerprint()))
        .put("intent",encodeIntentForProposal(request.plan.intent))
        .put("plan",JSONArray(request.plan.steps.map{step->JSONObject().put("node_uid",step.nodeUid)
            .put("capability_uid",step.capabilityUid).put("match_state",step.matchState.name)
            .put("execution_kind",step.executionKind?.name).put("side_effect_class",step.sideEffectClass?.name)
            .put("mechanics_owner_uid",step.mechanicsOwnerUid).put("dependencies",JSONArray(step.dependencyNodeUids))}))
        .put("projected_context",encodeContext(request.context))
        .put("requirements",JSONArray(listOf(
            "Proposal is not reality and cannot commit","Preserve actor/action/target/modality/player agency",
            "Every factual claim cites projected supporting record UIDs","Mechanics effects only request the registered owner from the plan",
            "Never supply mechanics outcomes; stop at the next player decision"
        )))
        .toString()

    override fun decodeProposal(payload:String):GmProposalCandidate{
        val root=strictObject(payload)
        return GmProposalCandidate(
            schemaVersion=root.reqInt("schema_version"),proposalUid=root.reqString("proposal_uid"),campaignUid=root.reqString("campaign_uid"),
            planUid=root.reqString("plan_uid"),nodeProposals=root.array("node_proposals").objects().map{node->GmNodeProposal(
                node.reqString("node_uid"),node.reqString("outcome_uid"),node.reqString("player_facing_summary"),
                CommandActorRef(node.reqString("actor_kind_uid"),node.reqString("actor_uid")),node.reqString("action_semantic_uid"),
                node.array("target_projected_refs").objects().map{DomainRef(it.reqString("kind_uid"),it.reqString("uid"))},
                enumValue(node.reqString("modality")),enumValue(node.reqString("outcome_state")),node.array("uncertainty_uids").strings(),
                node.array("materialized_result_uids").strings()
            )},
            proposedClaims=root.array("proposed_claims").objects().map{claim->ProposedWorldClaim(
                claim.reqString("claim_uid"),claim.reqString("node_uid"),enumValue(claim.reqString("claim_kind")),claim.optString("subject_projected_uid"),
                claim.reqString("predicate_uid"),claim.reqString("value_canonical"),claim.array("supporting_record_uids").strings(),
                claim.array("supporting_player_claim_uids").strings()
            )},
            mechanicsEffects=root.array("mechanics_effects").objects().map{effect->MechanicsEffectRequest(
                effect.reqString("effect_uid"),effect.reqString("node_uid"),effect.reqString("mechanics_owner_uid"),effect.reqString("effect_kind_uid"),
                effect.optObject("target_projected_ref")?.let{DomainRef(it.reqString("kind_uid"),it.reqString("uid"))},effect.obj("parameters").stringMap()
            )},
            narrativeBlueprint=root.reqObject("narrative_blueprint").let{blueprint->NarrativeBlueprint(
                blueprint.array("beat_uids").strings(),blueprint.array("tone_hint_uids").strings(),blueprint.reqString("stop_point_uid"),
                blueprint.array("forbidden_disclosure_uids").strings()
            )},providerUid=root.reqString("provider_uid"),modelUid=root.reqString("model_uid"),
            intentFingerprint=root.reqString("intent_fingerprint"),
            requestedPlayerVolitionalActionUids=root.array("requested_player_volitional_action_uids").strings(),
            playerDecisionPointUid=root.optString("player_decision_point_uid")
        )
    }

    override fun encodeRepair(request:AiRepairRequest)=JSONObject()
        .put("contract","RPGOS_GM_PROPOSAL_REPAIR_V1")
        .put("attempt",request.attempt).put("rejection_reason_uids",JSONArray(request.rejectionReasonUids))
        .put("immutable_identity",JSONObject().put("campaign_uid",request.original.plan.campaignUid).put("plan_uid",request.original.plan.planUid)
            .put("intent_fingerprint",request.original.plan.intent.canonicalFingerprint()))
        .put("original_request",JSONObject(encodeProposal(request.original)))
        .put("rejected_candidate",encodeCandidate(request.rejectedCandidate))
        .put("requirements",JSONArray(listOf("Do not reroll mechanics","Do not add mechanics entitlement","Do not change player intent or context scope","Return full corrected proposal JSON")))
        .toString()

    override fun encodeNarrative(request:AiNarrativeRequest)=JSONObject()
        .put("contract","RPGOS_COMMITTED_NARRATIVE_V2")
        .put("campaign_uid",request.context.campaignUid).put("commit_order",request.context.committedOrder)
        .put("stop_point_uid",request.context.stopPointUid).put("locale_uid",request.localeUid)
        .put("player_snapshot",JSONObject(request.context.playerSnapshot))
        .put("legal_facts",JSONArray(request.context.legalFacts.map{fact->JSONObject().put("fact_uid",fact.factUid).put("kind",fact.kind.name)
            .put("subject_projected_uid",fact.subjectProjectedUid).put("predicate_uid",fact.predicateUid).put("value_canonical",fact.valueCanonical)}))
        .put("presentation_consequences",JSONArray(request.context.presentationConsequences))
        .put("requirements",JSONArray(listOf("Return natural player-visible GM prose","Annotate every factual/mechanical claim with a supplied fact UID","Do not invent facts, mechanics, player speech or a new player decision","Match the committed order and stop point")))
        .toString()

    override fun encodeNarrativeRepair(request:AiNarrativeRepairRequest)=JSONObject()
        .put("contract","RPGOS_COMMITTED_NARRATIVE_REPAIR_V1").put("attempt",request.attempt)
        .put("rejection_reason_uids",JSONArray(request.rejectionReasonUids)).put("original_request",JSONObject(encodeNarrative(request.original)))
        .put("rejected_text",request.rejected.text)
        .put("requirements",JSONArray(listOf("Use only supplied committed facts","Do not change mechanics","Do not invent player volition","Return full corrected narrative JSON")))
        .toString()

    override fun decodeNarrative(payload:String):RenderedNarrative{
        val root=strictObject(payload)
        return RenderedNarrative(
            root.reqString("text"),root.reqString("stop_reason_uid"),root.reqLong("committed_order"),
            root.array("claims").objects().map{claim->NarrativeSemanticClaim(
                claim.reqString("claim_uid"),enumValue(claim.reqString("kind")),claim.optString("support_fact_uid"),
                claim.optString("predicate_uid"),claim.optString("value_canonical")
            )},root.optBoolean("asserts_player_volition",false)
        )
    }

    override fun encodeCharacterCreation(request:AiCharacterCreationRequest)=JSONObject()
        .put("contract","RPGOS_CHARACTER_CREATION_V1")
        .put("request_uid",request.requestUid).put("campaign_uid",request.campaignUid).put("locale_uid",request.localeUid)
        .put("conversation",JSONArray(request.conversation.map{JSONObject().put("role",it.role.name).put("text",it.text)}))
        .put("allowed_definitions",JSONArray(request.catalog.options.map{option->JSONObject()
            .put("kind",option.kind.name).put("definition_uid",option.definitionUid).put("display_name",option.displayName)
            .put("minimum_value",option.minimumValue).put("maximum_value",option.maximumValue).put("dimension_uid",option.dimensionUid)}))
        .put("requirements",JSONArray(listOf(
            "Ask one concise question when player choices are incomplete",
            "Use only allowed definition UIDs and preserve the player's choices",
            "Assign complete starting stats, resources, talent, potential, skills and techniques without inventing definitions",
            "Return READY only when a full draft can be shown for separate explicit user confirmation",
            "This response is a candidate and has no mutation authority"
        ))).toString()

    override fun decodeCharacterCreation(payload:String):CharacterCreationGmCandidate{
        val root=strictObject(payload)
        return when(root.reqString("state")){
            "NEEDS_PLAYER_CHOICE"->CharacterCreationGmCandidate.NeedsPlayerChoice(root.reqString("question"),root.array("missing_category_uids").strings())
            "READY_FOR_CONFIRMATION"->{
                val draft=root.reqObject("draft")
                fun choices(key:String)=draft.array(key).objects().map{choice->CharacterCreationValueChoice(
                    choice.reqString("definition_uid"),choice.getDouble("value"),choice.optString("dimension_uid")
                )}
                CharacterCreationGmCandidate.ReadyForConfirmation(PlayerCharacterCreationDraft(
                    creationUid=draft.reqString("creation_uid"),campaignUid=draft.reqString("campaign_uid"),playerUid=draft.reqString("player_uid"),
                    displayName=draft.reqString("display_name"),genderUid=draft.reqString("gender_uid"),identityChoices=draft.obj("identity_choices").stringMap(),
                    stats=choices("stats"),resources=choices("resources"),talents=choices("talents"),potentials=choices("potentials"),
                    skills=choices("skills"),techniques=choices("techniques"),originUids=draft.array("origin_uids").strings(),
                    innateFeatureUids=draft.array("innate_feature_uids").strings(),startingLocationUid=draft.reqString("starting_location_uid"),
                    startingXMillimetres=draft.optLongOrZero("starting_x_millimetres"),startingYMillimetres=draft.optLongOrZero("starting_y_millimetres")
                ),root.reqString("player_facing_summary"))
            }
            else->throw IllegalArgumentException("CHARACTER_CREATION_STATE_UNSUPPORTED")
        }
    }

    override fun encodeDirector(request:AiDirectorRequest)=JSONObject()
        .put("contract","RPGOS_DIRECTOR_BUNDLE_V1").put("job_uid",request.jobUid)
        .put("trigger",JSONObject().put("trigger_uid",request.trigger.triggerUid).put("kind",request.trigger.kind.name)
            .put("campaign_uid",request.trigger.campaignUid).put("at_committed_order",request.trigger.atCommittedOrder)
            .put("semantic_evidence_uids",JSONArray(request.trigger.semanticEvidenceUids)))
        .put("context",JSONObject().put("context_version",request.context.contextVersion).put("as_of_committed_order",request.context.asOfCommittedOrder)
            .put("projected_record_uids",JSONArray(request.context.projectedRecordUids.toList().sorted()))
            .put("strategic_summary_segments",JSONArray(request.context.strategicSummarySegments)))
        .put("requirements",JSONArray(listOf(
            "Return future strategic candidates only","Never assert current facts or direct mutations","Cite only projected record UIDs",
            "Use the correct later-phase owner for materialization","Cloud/local unavailability must not affect normal turns"
        ))).toString()

    override fun decodeDirector(payload:String):DirectorBundle{
        val root=strictObject(payload)
        return DirectorBundle(
            schemaVersion=root.reqInt("schema_version"),bundleUid=root.reqString("bundle_uid"),jobUid=root.reqString("job_uid"),
            campaignUid=root.reqString("campaign_uid"),triggerUid=root.reqString("trigger_uid"),contextVersion=root.reqString("context_version"),
            asOfCommittedOrder=root.reqLong("as_of_committed_order"),providerUid=root.reqString("provider_uid"),modelUid=root.reqString("model_uid"),
            candidates=root.array("candidates").objects().map{candidate->DirectorCandidate(
                candidate.reqString("candidate_uid"),enumValue(candidate.reqString("kind")),candidate.reqString("title"),candidate.reqString("summary"),
                candidate.array("supporting_projected_record_uids").strings(),candidate.reqString("horizon_uid"),candidate.array("pacing_tags").strings().toSet(),
                candidate.reqString("proposed_owner_phase_uid"),candidate.optString("direct_mutation_payload")
            )},createdAgainstFingerprint=root.reqString("created_against_fingerprint")
        )
    }

    private fun decodeReference(ref:JSONObject)=IntentReference(
        referenceUid=ref.reqString("reference_uid"),kind=enumValue(ref.reqString("kind")),rawPhrase=ref.optString("raw_phrase"),
        roleUid=ref.reqString("role_uid"),semanticTypeHints=ref.array("semantic_type_hints").strings().toSet(),
        descriptorHints=ref.obj("descriptor_hints").stringMap(),state=IntentReferenceState.UNRESOLVED,
        confidenceUid=ref.optString("confidence_uid")
    )

    private fun decodeNode(node:JSONObject)=IntentNode(
        nodeUid=node.reqString("node_uid"),form=enumValue(node.reqString("form")),
        semanticAction=node.reqObject("semantic_action").let{action->SemanticAction(
            canonicalActionUid=null,semanticFamilyUid=action.optString("semantic_family_uid"),rawPhrase=action.reqString("raw_phrase"),
            attributes=action.obj("attributes").stringMap(),confidenceUid=action.optString("confidence_uid")
        )},
        participants=node.array("participants").objects().map{part->
            val future=part.optObject("future_result")?.let{FutureResultReference(it.reqString("result_uid"),it.reqString("role_uid"),it.optBoolean("resource",false))}
            IntentParticipant(part.reqString("role_uid"),part.optString("reference_uid"),future,part.optString("literal_value"))
        },
        conditions=node.array("conditions").objects().map{condition->IntentCondition(
            condition.reqString("condition_uid"),condition.reqString("predicate_uid"),condition.array("argument_reference_uids").strings(),
            enumValue(condition.optString("polarity")?:IntentPolarity.AFFIRMATIVE.name),condition.optString("evaluation_timing_uid")?:"WHEN_REACHED"
        )},
        dependencies=node.array("dependencies").objects().map{IntentDependency(it.reqString("predecessor_node_uid"),enumValue(it.reqString("kind")))},
        intendedResult=node.optObject("intended_result")?.let{IntendedResult(it.reqString("result_uid"),it.optString("semantic_type_uid"),it.reqString("description"))},
        polarity=enumValue(node.optString("polarity")?:IntentPolarity.AFFIRMATIVE.name),
        modality=enumValue(node.optString("modality")?:IntentModality.ATTEMPT_NOW.name),
        commitmentState=enumValue(node.optString("commitment_state")?:IntentCommitmentState.ACTIVE.name),
        constraints=node.array("constraints").objects().map(::decodeDirective),preferences=node.array("preferences").objects().map(::decodeDirective),
        terminationConditionUid=node.optString("termination_condition_uid"),confidenceUid=node.optString("confidence_uid")
    )

    private fun decodeDirective(value:JSONObject)=IntentDirective(
        value.reqString("directive_uid"),enumValue(value.reqString("kind")),enumValue(value.reqString("strength")),
        value.reqString("value_canonical"),value.optString("scope_node_uid")
    )

    private fun encodeIntentForProposal(intent:IntentDocument)=JSONObject()
        .put("raw_input",intent.rawInput).put("actor_kind_uid",intent.actor.actorKindUid).put("actor_uid",intent.actor.actorUid)
        .put("nodes",JSONArray(intent.nodes.map{node->JSONObject().put("node_uid",node.nodeUid).put("form",node.form.name)
            .put("semantic_family_uid",node.semanticAction.semanticFamilyUid).put("raw_phrase",node.semanticAction.rawPhrase)
            .put("modality",node.modality.name).put("polarity",node.polarity.name)
            .put("participants",JSONArray(node.participants.map{part->JSONObject().put("role_uid",part.roleUid).put("reference_uid",part.referenceUid)
                .put("literal_value",part.literalValue).put("future_result_uid",part.futureResult?.resultUid)}))}))

    private fun encodeContext(context:BudgetedCanonicalContext)=JSONObject()
        .put("safe_for_ai",context.safeForAi).put("final_serialized_units",context.finalSerializedUnits)
        .put("records",JSONArray(context.includedSegments.flatMap{segment->segment.records.map{record->JSONObject()
            .put("record_uid",record.record.recordUid).put("epistemic_state",record.epistemicState.name)
            .put("source_requirement_uid",record.sourceRequirementUid).put("values",JSONObject(record.record.values))}}))
        .put("omissions",JSONArray(context.omissions.map{JSONObject().put("requirement_uid",it.requirementUid).put("cause",it.cause.name)}))

    private fun encodeCandidate(candidate:GmProposalCandidate)=JSONObject()
        .put("schema_version",candidate.schemaVersion).put("proposal_uid",candidate.proposalUid).put("campaign_uid",candidate.campaignUid)
        .put("plan_uid",candidate.planUid).put("provider_uid",candidate.providerUid).put("model_uid",candidate.modelUid)
        .put("intent_fingerprint",candidate.intentFingerprint)
        .put("requested_player_volitional_action_uids",JSONArray(candidate.requestedPlayerVolitionalActionUids))
        .put("player_decision_point_uid",candidate.playerDecisionPointUid)
        .put("node_proposals",JSONArray(candidate.nodeProposals.map{JSONObject().put("node_uid",it.nodeUid).put("outcome_uid",it.outcomeUid)
            .put("player_facing_summary",it.playerFacingSummary).put("actor_kind_uid",it.actor.actorKindUid).put("actor_uid",it.actor.actorUid)
            .put("action_semantic_uid",it.actionSemanticUid).put("target_projected_refs",JSONArray(it.targetProjectedRefs.map{ref->JSONObject().put("kind_uid",ref.kindUid).put("uid",ref.uid)}))
            .put("modality",it.modality.name).put("outcome_state",it.outcomeState.name).put("uncertainty_uids",JSONArray(it.uncertaintyUids))
            .put("materialized_result_uids",JSONArray(it.materializedResultUids))}))
        .put("proposed_claims",JSONArray(candidate.proposedClaims.map{JSONObject().put("claim_uid",it.claimUid).put("node_uid",it.nodeUid)
            .put("claim_kind",it.claimKind.name).put("subject_projected_uid",it.subjectProjectedUid).put("predicate_uid",it.predicateUid)
            .put("value_canonical",it.valueCanonical).put("supporting_record_uids",JSONArray(it.supportingRecordUids))
            .put("supporting_player_claim_uids",JSONArray(it.supportingPlayerClaimUids))}))
        .put("mechanics_effects",JSONArray(candidate.mechanicsEffects.map{effect->JSONObject().put("effect_uid",effect.effectUid).put("node_uid",effect.nodeUid)
            .put("mechanics_owner_uid",effect.mechanicsOwnerUid).put("effect_kind_uid",effect.effectKindUid)
            .put("target_projected_ref",effect.targetProjectedRef?.let{JSONObject().put("kind_uid",it.kindUid).put("uid",it.uid)})
            .put("parameters",JSONObject(effect.parameters))}))
        .put("narrative_blueprint",JSONObject().put("beat_uids",JSONArray(candidate.narrativeBlueprint.beatUids))
            .put("tone_hint_uids",JSONArray(candidate.narrativeBlueprint.toneHintUids)).put("stop_point_uid",candidate.narrativeBlueprint.stopPointUid)
            .put("forbidden_disclosure_uids",JSONArray(candidate.narrativeBlueprint.forbiddenDisclosureUids)))

    private fun strictObject(payload:String):JSONObject{
        val trimmed=payload.trim();require(trimmed.startsWith('{')&&trimmed.endsWith('}'))
        return JSONObject(trimmed)
    }
    private inline fun <reified T:Enum<T>> enumValue(value:String):T=enumValues<T>().single{it.name==value}
    private fun phase43InputHash(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

private fun JSONObject.reqString(key:String):String{require(has(key)&&!isNull(key));return getString(key).also{require(it.isNotBlank())}}
private fun JSONObject.reqInt(key:String):Int{require(has(key)&&!isNull(key));return getInt(key)}
private fun JSONObject.reqLong(key:String):Long{require(has(key)&&!isNull(key));return getLong(key)}
private fun JSONObject.optLongOrZero(key:String):Long=if(has(key)&&!isNull(key))getLong(key) else 0L
private fun JSONObject.reqObject(key:String):JSONObject{require(has(key)&&!isNull(key));return getJSONObject(key)}
private fun JSONObject.optObject(key:String):JSONObject?=if(has(key)&&!isNull(key))getJSONObject(key) else null
private fun JSONObject.obj(key:String):JSONObject=optObject(key)?:JSONObject()
private fun JSONObject.array(key:String):JSONArray=if(has(key)&&!isNull(key))getJSONArray(key) else JSONArray()
private fun JSONArray.objects():List<JSONObject> = buildList{for(index in 0 until length())add(getJSONObject(index))}
private fun JSONArray.strings():List<String> = buildList{for(index in 0 until length())getString(index).takeIf{it.isNotBlank()}?.let(::add)}
private fun JSONObject.stringMap():Map<String,String> = buildMap{keys().forEach{key->put(key,getString(key))}}
