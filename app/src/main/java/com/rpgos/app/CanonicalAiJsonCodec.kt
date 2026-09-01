package com.rpgos.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Strict provider wire schema. JSON is transport data only; every decoded value is revalidated by Core. */
class CanonicalAiJsonCodec:AiStructuredCodec{
    override fun encodeIntent(request:AiIntentRequest)=JSONObject()
        .put("contract","RPGOS_INTENT_DOCUMENT_V2")
        .put("request",JSONObject().put("campaign_uid",request.campaignUid).put("actor_kind_uid",request.actor.actorKindUid)
            .put("actor_uid",request.actor.actorUid).put("raw_input",request.rawInput).put("locale_uid",request.localeUid))
        .put("registered_semantic_families",JSONArray(UniversalIntentFamilies.REGISTERED.sorted()))
        .put("requirements",JSONArray(listOf(
            "Preserve the exact request identity and raw input","Return semantic families, never canonical action IDs",
            "semantic_family_uid must be from registered_semantic_families; for another player verb use OPEN_WORLD_ACTION and put its normalized uppercase token in attributes.provider_action",
            "Map localized verbs to the closest registered semantic family regardless of language; for example odkładam/upuszczam -> DROP and biorę/podnoszę -> TAKE. Use OPEN_WORLD_ACTION only when no registered family preserves the meaning",
            "References remain unresolved descriptors; never invent world IDs",
            "For every world reference provide descriptor_hints.shape as NAMED_INSTANCE/CATEGORY/QUANTITY/ROLE/AFFORDANCE/UNKNOWN, world_base_kind as PLACE/ACTOR/OBJECT/GROUP/ORGANIZATION/EVENT/PROCESS/CONCEPT, a normalized category, comma-separated affordances and topology as SETTLEMENT_FACILITY/SERVICE_VENUE/INTERIOR/LOCAL_SITE/NATURAL_FEATURE/REGION/OCEAN/SEA/CONTINENT/REMOTE_LANDMARK when known",
            "Use TARGET for the world element an action operates on. A route question such as 'how do I reach X' targets X; do not create a separate TARGET reference for the words 'how to get there'",
            "When one action applies to several inseparable aspects joined by and, such as practicing footwork and posture, keep one intent node; CATEGORY targets may remain separate because Core can select each category deterministically. Do not report ambiguity merely because the action has several compatible category targets",
            "A purpose clause such as 'I go to X so that I arrive on time' is an intended_result or constraint of that action, not a second executable movement node",
            "For combat explicitly limited to touch/contact without injury, encode a HARD QUALITATIVE constraint with value_canonical NO_DAMAGE and intended_result.semantic_type_uid TOUCH_ONLY; never omit this player restriction",
            "Use PLAN_FUTURE only for an explicit future intention such as 'pójdę' or 'zamierzam'. A completed contextual lead-in followed by a present action, such as 'po zajęciach idę', is ATTEMPT_NOW",
            "Represent sequence, negation, condition, correction and ambiguity explicitly"
        )))
        .put("response_schema",JSONObject().put("schema_version",PHASE43_INTENT_SCHEMA_VERSION).put("meaning_state","UNDERSTOOD|PARTIAL|UNINTERPRETABLE")
            .put("nodes","array").put("references","array").put("uncertainties","array").put("player_context_claims","array"))
        .put("response_example",JSONObject()
            .put("schema_version",PHASE43_INTENT_SCHEMA_VERSION).put("campaign_uid",request.campaignUid)
            .put("actor_kind_uid",request.actor.actorKindUid).put("actor_uid",request.actor.actorUid).put("raw_input",request.rawInput)
            .put("meaning_state","UNDERSTOOD")
            .put("nodes",JSONArray().put(JSONObject().put("node_uid","N1").put("form","DIRECT_ACTION")
                .put("semantic_action",JSONObject().put("semantic_family_uid","TRAVEL").put("raw_phrase","idę")
                    .put("attributes",JSONObject()).put("confidence_uid",JSONObject.NULL))
                .put("participants",JSONArray().put(JSONObject().put("role_uid","TARGET").put("reference_uid","R1")
                    .put("future_result",JSONObject.NULL).put("literal_value",JSONObject.NULL)))
                .put("conditions",JSONArray()).put("dependencies",JSONArray()).put("intended_result",JSONObject.NULL)
                .put("polarity","AFFIRMATIVE").put("modality","ATTEMPT_NOW").put("commitment_state","ACTIVE")
                .put("constraints",JSONArray()).put("preferences",JSONArray()).put("termination_condition_uid",JSONObject.NULL)
                .put("confidence_uid",JSONObject.NULL)))
            .put("references",JSONArray().put(JSONObject().put("reference_uid","R1").put("kind","DESCRIPTIVE")
                .put("raw_phrase","opis celu z wypowiedzi").put("role_uid","TARGET").put("semantic_type_hints",JSONArray())
                .put("descriptor_hints",JSONObject()).put("confidence_uid",JSONObject.NULL)))
            .put("global_constraints",JSONArray()).put("global_preferences",JSONArray()).put("uncertainties",JSONArray())
            .put("player_context_claims",JSONArray()).put("provider_uid","AI_PROVIDER").put("model_version","1"))
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
                claim.stringOrNull("epistemic_role_uid")?:"PLAYER_ASSERTION",claim.stringOrNull("linked_intent_node_uid")
            )},
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,root.stringOrNull("provider_uid")?:"AI_PROVIDER",root.stringOrNull("model_version")?:"1",phase43InputHash(root.reqString("raw_input")))
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
            .put("mechanics_owner_uid",step.mechanicsOwnerUid)
            .put("allowed_effect_kind_uids",JSONArray(allowedProposalEffectKinds(request.plan,step)))
            .put("dependencies",JSONArray(step.dependencyNodeUids))}))
        .put("projected_context",encodeContext(request.context))
        .put("strategic_guidance",request.strategicGuidance?.let(::encodeDirectorGuidance)?:JSONObject.NULL)
        .put("requirements",JSONArray(listOf(
            "Proposal is not reality and cannot commit","Preserve actor/action/target/modality/player agency",
            "For every node, copy action_semantic_uid exactly from intent.nodes[].action_semantic_uid; canonical_action_uid is informational and already settled by Core",
            "For every node, copy target_projected_refs exactly from intent.nodes[].target_projected_refs; unresolved descriptive references are evidence for interpretation but are not canonical projected targets, so never return INTENT_REFERENCE or TARGET placeholders and never invent a projected target",
            "Every factual claim cites projected supporting record UIDs","Mechanics effects only request the registered owner from the plan",
            "mechanics_effects is optional: effect_kind_uid must occur in plan.allowed_effect_kind_uids; an empty list means return no effect, and a legal target is still required",
            "For a PROPOSED_WORLD_EFFECT node with one allowed_effect_kind_uid and PROPOSED_SUCCESS, emit exactly one mechanics effect; when the intent has no target because it is a self action, use the intent actor as the effect target without adding it to node_proposal.target_projected_refs",
            "For one or more successful TALK/QUERY nodes addressed to the same ACTOR/NPC, include exactly one proposed_claim for that actor across the whole proposal. Bind it to the final TALK/QUERY node for that actor; use claim_kind NARRATIVE_COLOR, predicate_uid RPGOS-NARRATIVE:NPC_UTTERANCE, that actor UID as subject_projected_uid, and one concise in-world reply covering all the player's questions in value_canonical. Return the spoken content without surrounding quotation marks. This records only what the NPC says, never promotes it to FACT, and must not invent player choices, mechanics results or hidden knowledge",
            "An omission with cause PROVIDER_NO_DATA is informational after Core completed context assembly; never request clarification solely because semantic memory or World Pack returned no data",
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
                claim.reqString("claim_uid"),claim.reqString("node_uid"),enumValue(claim.reqString("claim_kind")),claim.stringOrNull("subject_projected_uid"),
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
            )},providerUid=root.stringOrNull("provider_uid")?:"TRANSPORT_PROVIDER",modelUid=root.stringOrNull("model_uid")?:"TRANSPORT_MODEL",
            intentFingerprint=root.reqString("intent_fingerprint"),
            requestedPlayerVolitionalActionUids=root.array("requested_player_volitional_action_uids").strings(),
            playerDecisionPointUid=root.stringOrNull("player_decision_point_uid")
        )
    }

    override fun encodeRepair(request:AiRepairRequest)=JSONObject()
        .put("contract","RPGOS_GM_PROPOSAL_REPAIR_V1")
        .put("attempt",request.attempt).put("rejection_reason_uids",JSONArray(request.rejectionReasonUids))
        .put("immutable_identity",JSONObject().put("campaign_uid",request.original.plan.campaignUid).put("plan_uid",request.original.plan.planUid)
            .put("intent_fingerprint",request.original.plan.intent.canonicalFingerprint()))
        .put("original_request",JSONObject(encodeProposal(request.original)))
        .put("rejected_candidate",encodeCandidate(request.rejectedCandidate))
        .put("requirements",JSONArray(listOf(
            "Do not reroll mechanics","Do not add mechanics entitlement","Do not change player intent or context scope",
            "For every node, copy target_projected_refs exactly from original_request.intent.nodes[].target_projected_refs; never replace an empty list with an unresolved reference UID or an invented canonical target",
            "A node whose original_request.plan.match_state is EXACT, COMPOSED or GENERIC has already passed Core ambiguity, reference and capability adjudication; return PROPOSED_SUCCESS or PROPOSED_FAILURE, never NEEDS_CLARIFICATION or REQUIRES_ADJUDICATION",
            "For all successful TALK/QUERY nodes to the same ACTOR/NPC preserve or produce one combined legal RPGOS-NARRATIVE:NPC_UTTERANCE NARRATIVE_COLOR claim, bound to that actor's final conversation node, so the NPC actually answers every question; it remains NARRATIVE, never FACT",
            "If an effect was rejected as TARGET_REQUIRED or UNSUPPORTED_OR_UNVERIFIABLE_EFFECT, remove it unless original_request explicitly provides an exact allowed effect kind and legal target",
            "Return full corrected proposal JSON"
        )))
        .toString()

    override fun encodeNarrative(request:AiNarrativeRequest)=JSONObject()
        .put("contract","RPGOS_COMMITTED_NARRATIVE_V2")
        .put("campaign_uid",request.context.campaignUid).put("commit_order",request.context.committedOrder)
        .put("stop_point_uid",request.context.stopPointUid).put("locale_uid",request.localeUid)
        .apply{request.playerInput?.let{put("player_input",it)}}
        .put("authorized_context",JSONArray(request.authorizedContext.map{entry->JSONObject()
            .put("record_uid",entry.recordUid).put("record_kind_uid",entry.recordKindUid)
            .put("epistemic_state_uid",entry.epistemicStateUid).put("projected_text",entry.projectedText)}))
        .put("player_snapshot",JSONObject(request.context.playerSnapshot))
        .put("legal_facts",JSONArray(request.context.legalFacts.map{fact->JSONObject().put("fact_uid",fact.factUid).put("kind",fact.kind.name)
            .put("subject_projected_uid",fact.subjectProjectedUid).put("predicate_uid",fact.predicateUid).put("value_canonical",fact.valueCanonical)}))
        .put("presentation_consequences",JSONArray(request.context.presentationConsequences))
        .put("requirements",JSONArray(listOf("Return natural player-visible GM prose","The supplied player_input is the action already attempted in this committed turn, not new player volition","Describe that submitted action in second person without replacing it with a new gerund/action; do not append a question asking what the player does next","Use authorized_context only as player-visible scene evidence","Prefer natural presentation_consequences over raw legal_facts when describing mechanics","Never mention counters, tracks, numeric bookkeeping, schemas, identifiers or internal mechanics vocabulary","claims may be empty; never create a claim for player_input or authorized_context","Every returned claim must copy support_fact_uid, predicate_uid and value_canonical exactly from one entry in legal_facts; never use an authorized_context record_uid as support_fact_uid","Render an NPC_UTTERANCE narrative-color fact as the NPC's spoken answer","Do not invent facts, mechanics, player speech or a new player decision","Match the committed order and stop point")))
        .toString()

    override fun encodeNarrativeRepair(request:AiNarrativeRepairRequest)=JSONObject()
        .put("contract","RPGOS_COMMITTED_NARRATIVE_REPAIR_V1").put("attempt",request.attempt)
        .put("rejection_reason_uids",JSONArray(request.rejectionReasonUids)).put("original_request",JSONObject(encodeNarrative(request.original)))
        .put("rejected_text",request.rejected.text)
        .put("requirements",JSONArray(listOf("Use only supplied committed facts","Do not change mechanics","Use natural presentation consequences; remove counters, tracks, numeric bookkeeping, schemas, identifiers and internal mechanics vocabulary","Describe the already submitted player_input in second person without a new gerund/action and do not append a next-action question","Do not invent player volition","claims may be empty; never create a claim for player_input or authorized_context","Every returned claim must copy support_fact_uid, predicate_uid and value_canonical exactly from one entry in original_request.legal_facts","Return full corrected narrative JSON")))
        .toString()

    override fun decodeNarrative(payload:String):RenderedNarrative{
        val root=strictObject(payload)
        return RenderedNarrative(
            root.reqString("text"),root.reqString("stop_reason_uid"),root.reqLong("committed_order"),
            root.array("claims").objects().map{claim->NarrativeSemanticClaim(
                claim.reqString("claim_uid"),enumValue(claim.reqString("kind")),claim.stringOrNull("support_fact_uid"),
                claim.stringOrNull("predicate_uid"),claim.stringOrNull("value_canonical")
            )},root.optBoolean("asserts_player_volition",false)
        )
    }

    override fun encodeCharacterCreation(request:AiCharacterCreationRequest)=JSONObject()
        .put("contract","RPGOS_CHARACTER_CREATION_V1")
        .put("request_uid",request.requestUid).put("campaign_uid",request.campaignUid).put("locale_uid",request.localeUid)
        .put("interaction_mode",request.localCharacterInteractionMode())
        .put("conversation",JSONArray(request.conversation.map{JSONObject().put("role",it.role.name).put("text",it.text)}))
        .put("allowed_definitions",JSONArray(request.catalog.options.map{option->JSONObject()
            .put("kind",option.kind.name).put("definition_uid",option.definitionUid).put("display_name",option.displayName)
            .put("minimum_value",option.minimumValue).put("maximum_value",option.maximumValue).put("dimension_uid",option.dimensionUid)}))
        .put("requirements",JSONArray(listOf(
            "Ask one concise question when player choices are incomplete",
            "Use only allowed definition UIDs and preserve the player's choices",
            "Assign complete starting stats, resources, talent, potential, skills and techniques without inventing definitions",
            "When interaction_mode is RANDOM, choose every unspecified field without asking another question; preserve every explicit player choice",
            "gender_uid must be MALE, FEMALE, NON_BINARY or UNSPECIFIED; Core owns final creation_uid and player_uid in RANDOM mode",
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
                    choice.reqString("definition_uid"),choice.getDouble("value"),choice.stringOrNull("dimension_uid")
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

    override fun decodeCharacterCreation(payload:String,request:AiCharacterCreationRequest):CharacterCreationGmCandidate{
        if(request.localCharacterInteractionMode()!="RANDOM")return decodeCharacterCreation(payload)

        // RANDOM is an explicit player command, not a suggestion to the provider. A cloud model
        // may still ask about an omitted field or fill unspecified numeric values with their
        // minimums. Neither result should force another round-trip or produce an unusable hero.
        // Re-materialize the candidate through the same Core-owned, deterministic catalog path as
        // the compact local provider. AI can rank legal options, but Core owns values, completeness
        // and the random seed. The result is still only a draft awaiting explicit confirmation.
        val compact=JSONObject().put("s","R").put("n","").put("g","").put("i",JSONObject())
            .put("pick",JSONArray()).put("sum","")
        val root=strictObject(payload)
        when(root.reqString("state")){
            "NEEDS_PLAYER_CHOICE"->{ /* Core completes every unspecified RANDOM field. */ }
            "READY_FOR_CONFIRMATION"->{
                val draft=root.optJSONObject("draft")?:JSONObject()
                val gender=draft.optString("gender_uid").trim().uppercase().takeIf{
                    it in setOf("MALE","FEMALE","NON_BINARY","UNSPECIFIED")
                }.orEmpty()
                fun suggested(key:String)=draft.optJSONArray(key)?.objects()?.mapNotNull{choice->
                    choice.optString("definition_uid").trim().takeIf(String::isNotBlank)
                }.orEmpty()
                val picks=buildList{
                    addAll(suggested("skills"));addAll(suggested("techniques"))
                    addAll(draft.optJSONArray("origin_uids")?.strings().orEmpty())
                    addAll(draft.optJSONArray("innate_feature_uids")?.strings().orEmpty())
                    draft.optString("starting_location_uid").trim().takeIf(String::isNotBlank)?.let(::add)
                }.distinct()
                compact.put("n",draft.optString("display_name")).put("g",gender)
                    .put("i",draft.optJSONObject("identity_choices")?:JSONObject())
                    .put("pick",JSONArray(picks)).put("sum",root.optString("player_facing_summary"))
            }
            else->throw IllegalArgumentException("CHARACTER_CREATION_STATE_UNSUPPORTED")
        }
        return LocalCompactAiJsonCodec(this).decodeCharacterCreation(compact.toString(),request)
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
            "Candidate kind must be one of: ${DirectorCandidateKind.entries.joinToString{it.name}}",
            "Materialization owner must be one of: PHASE55_MEMORY, PHASE61_NPC, PHASE63_WORLD, PHASE64_WORLD_PROCESS, PHASE65_DIRECTOR, PHASE66_PROMISE, PHASE67_PACING",
            "Cloud/local unavailability must not affect normal turns"
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
                candidate.reqString("proposed_owner_phase_uid"),
                if(candidate.isNull("direct_mutation_payload"))null else candidate.reqString("direct_mutation_payload")
            )},createdAgainstFingerprint=root.reqString("created_against_fingerprint")
        )
    }

    private fun decodeReference(ref:JSONObject)=IntentReference(
        referenceUid=ref.reqString("reference_uid"),kind=enumValue(ref.reqString("kind")),rawPhrase=ref.stringOrNull("raw_phrase"),
        roleUid=ref.reqString("role_uid"),semanticTypeHints=ref.array("semantic_type_hints").strings().toSet(),
        descriptorHints=ref.obj("descriptor_hints").stringMap(),state=IntentReferenceState.UNRESOLVED,
        confidenceUid=ref.stringOrNull("confidence_uid")
    )

    private fun decodeNode(node:JSONObject)=IntentNode(
        nodeUid=node.reqString("node_uid"),form=enumValue(node.reqString("form")),
        semanticAction=node.reqObject("semantic_action").let{action->SemanticAction(
            canonicalActionUid=null,semanticFamilyUid=action.stringOrNull("semantic_family_uid"),rawPhrase=action.reqString("raw_phrase"),
            attributes=action.obj("attributes").stringMap(),confidenceUid=action.stringOrNull("confidence_uid")
        )},
        participants=node.array("participants").objects().map{part->
            val future=part.optObject("future_result")?.let{FutureResultReference(it.reqString("result_uid"),it.reqString("role_uid"),it.optBoolean("resource",false))}
            IntentParticipant(part.reqString("role_uid"),part.stringOrNull("reference_uid"),future,part.stringOrNull("literal_value"))
        },
        conditions=node.array("conditions").objects().map{condition->IntentCondition(
            condition.reqString("condition_uid"),condition.reqString("predicate_uid"),condition.array("argument_reference_uids").strings(),
            enumValue(condition.stringOrNull("polarity")?:IntentPolarity.AFFIRMATIVE.name),condition.stringOrNull("evaluation_timing_uid")?:"WHEN_REACHED"
        )},
        dependencies=node.array("dependencies").objects().map{IntentDependency(it.reqString("predecessor_node_uid"),enumValue(it.reqString("kind")))},
        intendedResult=node.optObject("intended_result")?.let{IntendedResult(it.reqString("result_uid"),it.stringOrNull("semantic_type_uid"),it.reqString("description"))},
        polarity=enumValue(node.stringOrNull("polarity")?:IntentPolarity.AFFIRMATIVE.name),
        modality=enumValue(node.stringOrNull("modality")?:IntentModality.ATTEMPT_NOW.name),
        commitmentState=enumValue(node.stringOrNull("commitment_state")?:IntentCommitmentState.ACTIVE.name),
        constraints=node.array("constraints").objects().map(::decodeDirective),preferences=node.array("preferences").objects().map(::decodeDirective),
        terminationConditionUid=node.stringOrNull("termination_condition_uid"),confidenceUid=node.stringOrNull("confidence_uid")
    )

    private fun decodeDirective(value:JSONObject)=IntentDirective(
        value.reqString("directive_uid"),enumValue(value.reqString("kind")),enumValue(value.reqString("strength")),
        value.reqString("value_canonical"),value.stringOrNull("scope_node_uid")
    )

    private fun encodeIntentForProposal(intent:IntentDocument)=JSONObject()
        .put("raw_input",intent.rawInput).put("actor_kind_uid",intent.actor.actorKindUid).put("actor_uid",intent.actor.actorUid)
        .put("nodes",JSONArray(intent.nodes.map{node->JSONObject().put("node_uid",node.nodeUid).put("form",node.form.name)
            .put("semantic_family_uid",node.semanticAction.semanticFamilyUid)
            .put("canonical_action_uid",node.semanticAction.canonicalActionUid)
            .put("action_semantic_uid",node.semanticAction.canonicalActionUid?:node.semanticAction.semanticFamilyUid)
            .put("raw_phrase",node.semanticAction.rawPhrase)
            .put("modality",node.modality.name).put("polarity",node.polarity.name)
            .put("target_projected_refs",JSONArray(projectedTargetRefs(intent,node).map{ref->JSONObject().put("kind_uid",ref.kindUid).put("uid",ref.uid)}))
            .put("unresolved_target_reference_uids",JSONArray(unresolvedTargetReferenceUids(intent,node)))
            .put("participants",JSONArray(node.participants.map{part->JSONObject().put("role_uid",part.roleUid).put("reference_uid",part.referenceUid)
                .put("literal_value",part.literalValue).put("future_result_uid",part.futureResult?.resultUid)}))}))

    private fun allowedProposalEffectKinds(plan:CanonicalTurnPlan,step:CanonicalTurnPlanStep):List<String>{
        val action=plan.intent.nodes.singleOrNull{it.nodeUid==step.nodeUid}?.let{
            it.semanticAction.canonicalActionUid?:it.semanticAction.semanticFamilyUid
        }?.uppercase()?:return emptyList()
        return when(step.mechanicsOwnerUid){
            "UNIVERSAL_ACTION"->when(action){
                "TRAIN","PRACTICE","LEARN"->listOf("TRAINING")
                "TAKE"->listOf("INVENTORY_ADD")
                "DROP"->listOf("INVENTORY_REMOVE")
                else->listOf("INTERACTION")
            }
            "UNIVERSAL_MOVEMENT"->listOf(if(action=="PUSH")"DISPLACEMENT" else "LOCATION_TRANSITION")
            "UNIVERSAL_COMBAT"->listOf("COMBAT_RESOLUTION")
            else->emptyList()
        }
    }

    private fun encodeContext(context:BudgetedCanonicalContext)=JSONObject()
        .put("safe_for_ai",context.safeForAi).put("final_serialized_units",context.finalSerializedUnits)
        .put("records",JSONArray(context.includedSegments.flatMap{it.records}.distinctBy{it.record.recordUid}.map{record->JSONObject()
            .put("record_uid",record.record.recordUid).put("epistemic_state",record.epistemicState.name)
            .put("source_requirement_uid",record.sourceRequirementUid).put("values",JSONObject(record.record.values))}))
        .put("omissions",JSONArray(context.omissions.map{JSONObject().put("requirement_uid",it.requirementUid).put("cause",it.cause.name)}))

    private fun encodeDirectorGuidance(guidance:DirectorGuidanceEnvelope)=JSONObject()
        .put("contract","RPGOS_DIRECTOR_GUIDANCE_V1")
        .put("campaign_uid",guidance.campaignUid).put("bundle_uid",guidance.bundleUid)
        .put("context_version",guidance.contextVersion).put("as_of_committed_order",guidance.asOfCommittedOrder)
        .put("authority","READ_ONLY_OPTIONAL_GUIDANCE")
        .put("candidates",JSONArray(guidance.candidates.map{candidate->JSONObject()
            .put("candidate_uid",candidate.candidateUid).put("kind",candidate.kind.name)
            .put("title",candidate.title).put("summary",candidate.summary)
            .put("supporting_projected_record_uids",JSONArray(candidate.supportingProjectedRecordUids))
            .put("horizon_uid",candidate.horizonUid).put("pacing_tags",JSONArray(candidate.pacingTags.sorted()))}))

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
    private inline fun <reified T:Enum<T>> enumValue(value:String):T=
        enumValues<T>().singleOrNull{it.name==value}
            ?:throw IllegalArgumentException("STRUCTURED_ENUM_INVALID:${T::class.java.simpleName}:$value")
    private fun phase43InputHash(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

/** Token-efficient character-creation wire format for small on-device models. */
class LocalCompactAiJsonCodec(
    private val canonical:CanonicalAiJsonCodec=CanonicalAiJsonCodec()
):AiStructuredCodec by canonical{
    override fun encodeIntent(request:AiIntentRequest)=JSONObject()
        .put("v","RPGOS_INTENT_LOCAL_9").put("u",request.rawInput).put("locale",request.localeUid)
        .put("segments",JSONArray(localIntentSegments(request.rawInput)))
        .put("reply","JSON steps opisuje wszystkie czynności wyłącznie z u. segments to niewiążące fragmenty pomocnicze; każdy może zawierać osobną czynność. "+
            "Każdy step ma action będące prostym czasownikiem znaczeniowo obecnym w u i kind: MOVE, COMBAT, TRAIN, QUERY, TALK albo ACTION. "+
            "Role z u trafiają do destination (dokąd), where (gdzie lub skąd), who (kto) i what (bezpośredni obiekt czynności); step może mieć kilka ról. "+
            "Dla 'biorę miecz ze stojaka' what to 'miecz', a where to 'stojaka'. "+
            "locality ma wartość L dla celu lokalnego, R dla odległego albo U przy braku danych. "+
            "Słowa techniczne tej instrukcji nie są czynnościami gracza. Bez nowych celów, faktów świata i pustych pól.")
        .toString()

    internal fun localIntentSegments(input:String):List<String>{
        val compact=input.trim().replace(Regex("\\s+")," ")
        if(compact.isBlank())return emptyList()
        return compact.split(Regex("(?iu)\\s*(?:[.!?;]+|,?\\s+(?:i\\s+potem|a\\s+potem|następnie|potem|then|and\\s+then|after\\s+that)\\s+)\\s*"))
            .flatMap{part->part.split(Regex("(?iu)\\s+(?:i|and)\\s+")).map(String::trim)}
            .filter(String::isNotBlank).take(16).ifEmpty{listOf(compact)}
    }

    override fun decodeIntent(payload:String,request:AiIntentRequest):IntentDocument{
        var root=if(payload.trimStart().startsWith('{'))JSONObject(payload.trim()) else lineIntentRoot(payload,request)
        if(root.opt("steps") is JSONArray)root=compactV9IntentRoot(root,request)
        if(root.opt("actions") is JSONArray)root=compactV8IntentRoot(root,request)
        if(root.opt("a") is JSONArray)root=compactV7IntentRoot(root,request)
        root.optString("reply").trim().takeIf{it.startsWith('{')&&it.endsWith('}')}?.let{root=JSONObject(it)}
        if(root.has("schema_version"))return canonical.decodeIntent(payload)
        val meaning=when(root.optString("s").uppercase()){
            "U","UNDERSTOOD"->MeaningState.UNDERSTOOD
            "P","PARTIAL"->MeaningState.PARTIAL
            "X","UNINTERPRETABLE"->MeaningState.UNINTERPRETABLE
            else->throw IllegalArgumentException("LOCAL_INTENT_STATE_REQUIRED")
        }
        val encodedNodes=root.array("n")
        val rawNodes=if(encodedNodes.length()>0)(0 until minOf(encodedNodes.length(),16)).map{index->
            when(val encoded=encodedNodes.get(index)){
                is JSONObject->encoded
                is JSONArray->compactIntentRow(encoded,index)
                else->throw IllegalArgumentException("LOCAL_INTENT_NODE_OBJECT_REQUIRED")
            }
        } else if(meaning==MeaningState.UNINTERPRETABLE)emptyList() else listOf(root)
        val providerIds=rawNodes.mapIndexed{index,node->node.optString("id").trim().ifBlank{"N$index"}}.distinct()
        require(providerIds.size==rawNodes.size){"LOCAL_INTENT_NODE_IDS_NOT_UNIQUE"}
        val references=mutableListOf<IntentReference>()
        fun safeToken(value:String)=Normalizer.normalize(value.trim(),Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"),"").uppercase().replace(Regex("[^A-Z0-9_:-]"),"_").take(96)
        val parsedNodes=rawNodes.mapIndexed{nodeIndex,node->
            val action=safeToken(node.optString("a"))
            require(action.isNotBlank()){"LOCAL_INTENT_ACTION_REQUIRED"}
            val participantRefs=mutableListOf<String>()
            val targets=node.array("t")
            for(targetIndex in 0 until targets.length()){
                val item=targets.get(targetIndex)
                val targetObject=item as? JSONObject
                val surface=(targetObject?.optString("x")?:item.toString()).trim().take(160)
                if(surface.isBlank())continue
                val referenceUid="LOCAL-REF:$nodeIndex:$targetIndex"
                val descriptor=buildMap{
                    put("surface",surface)
                    targetObject?.optString("shape")?.takeIf(String::isNotBlank)?.let{value->
                        safeToken(value).takeIf{it in WorldReferenceShapeKind.entries.map(WorldReferenceShapeKind::name)}?.let{put("shape",it)}
                    }
                    (targetObject?.optString("k")?.takeIf(String::isNotBlank)?.let(::compactWorldKind)
                        ?:targetObject?.optString("kind")?.takeIf(String::isNotBlank)?.let(::safeToken))
                        ?.takeIf{it in WorldElementBaseKind.entries.map(WorldElementBaseKind::name)}?.let{put("world_base_kind",it)}
                    (targetObject?.optString("c")?.takeIf(String::isNotBlank)
                        ?:targetObject?.optString("category")?.takeIf(String::isNotBlank))?.let{put("category",safeToken(it))}
                    (targetObject?.optString("o")?.takeIf(String::isNotBlank)
                        ?:targetObject?.optString("topo")?.takeIf(String::isNotBlank))?.let{put("topology",safeToken(it))}
                    (targetObject?.optString("scope")?.takeIf(String::isNotBlank))?.let{put("spatial_scope",safeToken(it))}
                    (targetObject?.array("f")?.strings()?.takeIf(List<String>::isNotEmpty)
                        ?:targetObject?.array("aff")?.strings())?.map(::safeToken)?.filter(String::isNotBlank)?.takeIf(List<String>::isNotEmpty)
                        ?.let{put("affordances",it.joinToString(","))}
                    when(get("spatial_scope")){
                        "LOCAL"->{
                            val base=get("world_base_kind")
                            if(base!=null){putIfAbsent("category","GENERIC_$base");putIfAbsent("shape","CATEGORY")}
                            putIfAbsent("topology","LOCAL_SITE")
                            putIfAbsent("affordances",action)
                        }
                        "REMOTE"->{
                            val base=get("world_base_kind")
                            if(base!=null){putIfAbsent("category","GENERIC_$base");putIfAbsent("shape","CATEGORY")}
                            putIfAbsent("topology","REMOTE_LANDMARK")
                            putIfAbsent("affordances",action)
                        }
                    }
                    if(!containsKey("shape")&&containsKey("category")){
                        put("shape","CATEGORY")
                    }
                }
                references+=IntentReference(referenceUid,
                    if(surface.lowercase() in setOf("tam","tutaj","tu"))IntentReferenceKind.DEICTIC
                    else if(descriptor["shape"]=="CATEGORY")IntentReferenceKind.SET else IntentReferenceKind.DESCRIPTIVE,
                    surface,"TARGET",descriptorHints=descriptor,state=IntentReferenceState.UNRESOLVED)
                participantRefs+=referenceUid
            }
            val dependencies=(node.array("d").strings()+node.array("after").strings()).mapNotNull{providerId->providerIds.indexOf(providerId).takeIf{it in 0 until nodeIndex}}
                .distinct().map{IntentDependency("LOCAL-NODE:$it",IntentDependencyKind.AFTER_SUCCESS)}
            val routeHint=when(safeToken(node.optString("r"))){
                "M"->"MOVEMENT";"C"->"COMBAT";"T"->"TRAINING";"Q"->"QUERY";"D"->"COMMUNICATION";"A"->"ACTION"
                else->safeToken(node.optString("r")).takeIf(String::isNotBlank)
            }
            val routedFamily=UniversalIntentFamilies.routedFamily(action,routeHint)
            IntentNode(
                nodeUid="LOCAL-NODE:$nodeIndex",form=runCatching{IntentForm.valueOf(node.optString("f").ifBlank{"DIRECT_ACTION"}.uppercase())}.getOrDefault(IntentForm.DIRECT_ACTION),
                semanticAction=SemanticAction(semanticFamilyUid=routedFamily,
                    rawPhrase=node.optString("p").trim().takeIf(String::isNotBlank)?:request.rawInput.take(160),
                    attributes=if(routedFamily!=action)mapOf(UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE to action)else emptyMap()),
                participants=participantRefs.map{IntentParticipant("TARGET",referenceUid=it)},dependencies=dependencies,
                polarity=runCatching{IntentPolarity.valueOf(node.optString("pol").ifBlank{"AFFIRMATIVE"}.uppercase())}.getOrDefault(IntentPolarity.AFFIRMATIVE),
                modality=runCatching{IntentModality.valueOf(node.optString("m").ifBlank{"ATTEMPT_NOW"}.uppercase())}.getOrDefault(IntentModality.ATTEMPT_NOW)
            )
        }
        val nodes=parsedNodes.map{node->
            if(node.participants.isNotEmpty())node
            else node.dependencies.firstOrNull{it.kind==IntentDependencyKind.AFTER_SUCCESS}?.let{dependency->
                parsedNodes.firstOrNull{it.nodeUid==dependency.predecessorNodeUid}?.participants?.takeIf(List<IntentParticipant>::isNotEmpty)
            }?.let{node.copy(participants=it)}?:node
        }
        return IntentDocument(
            campaignUid=request.campaignUid,actor=request.actor,rawInput=request.rawInput,meaningState=meaning,nodes=nodes,references=references,
            uncertainties=root.array("q").strings(),provenance=IntentInterpretationProvenance(
                IntentInterpretationSource.AI_PROVIDER,"LOCAL_COMPACT_INTENT","1",semanticSha256(request.rawInput)
            )
        )
    }

    private fun compactWorldKind(value:String)=when(value.trim().uppercase()){
        "P"->"PLACE";"A"->"ACTOR";"O"->"OBJECT";"G"->"GROUP";"N"->"ORGANIZATION"
        "E"->"EVENT";"R"->"PROCESS";"C"->"CONCEPT";else->value.trim().uppercase()
    }

    private fun compactIntentRow(row:JSONArray,index:Int):JSONObject{
        require(row.length()>=7){"LOCAL_INTENT_ROW_TOO_SHORT"}
        fun text(position:Int)=row.optString(position).trim()
        val target=text(2)
        return JSONObject().put("id",index.toString()).put("a",text(0)).put("r",text(1))
            .put("t",JSONArray().apply{if(target.isNotBlank())put(JSONObject().put("x",target).put("k",text(3))
                .put("c",text(4)).put("f",JSONArray().apply{text(5).takeIf(String::isNotBlank)?.let(::put)})
                .put("o",text(6)))})
            .put("d",JSONArray().apply{text(7).takeIf(String::isNotBlank)?.let(::put)})
    }

    private fun compactV7IntentRoot(root:JSONObject,request:AiIntentRequest):JSONObject{
        val groundedInput=normalizedWorldText(request.rawInput)
        val nodes=JSONArray()
        val actions=root.array("a")
        for(index in 0 until minOf(actions.length(),16)){
            val row=actions.optJSONArray(index)?:continue
            if(row.length()!=5)continue
            val route=row.optString(0).trim().uppercase()
            val action=Regex("[A-Za-z_]+").findAll(row.optString(1)).lastOrNull()?.value?.uppercase().orEmpty()
            val target=row.optString(2).trim().take(160)
            val kind=row.optString(3).trim().uppercase()
            val scope=when(row.optString(4).trim().uppercase()){"L","LOCAL"->"LOCAL";"R","REMOTE"->"REMOTE";"U","UNKNOWN"->"UNKNOWN";else->""}
            if(action.isBlank()||route !in setOf("M","C","A"))continue
            if(target.isNotBlank()&&(kind !in setOf("P","A","O","G","N","E","R","C")||scope.isBlank()))continue
            if(target.isBlank()&&(kind.isNotBlank()||row.optString(4).isNotBlank()))continue
            val groundedTarget=normalizedWorldText(target)
            if(target.isNotBlank()&&(groundedTarget.isBlank()||!groundedInput.contains(groundedTarget)))continue
            val nodeIndex=nodes.length()
            nodes.put(JSONObject().put("id",nodeIndex.toString()).put("a",action).put("r",route)
                .put("t",JSONArray().apply{target.takeIf(String::isNotBlank)?.let{put(JSONObject().put("x",it).put("k",kind).put("scope",scope))}})
                .put("d",JSONArray().apply{if(nodeIndex>0)put((nodeIndex-1).toString())}))
        }
        require(nodes.length()>0){"LOCAL_INTENT_ACTIONS_REQUIRED"}
        return JSONObject().put("s","U").put("n",nodes).put("q",JSONArray())
    }

    private fun compactV8IntentRoot(root:JSONObject,request:AiIntentRequest):JSONObject{
        val groundedInput=normalizedWorldText(request.rawInput)
        val nodes=JSONArray()
        val actions=root.array("actions")
        for(index in 0 until minOf(actions.length(),16)){
            val row=actions.optJSONObject(index)?:continue
            val route=when(normalizedWorldToken(row.optString("route"))){
                "MOVEMENT","MOVE","TRAVEL","RUCH","PODROZ","PODRÓŻ"->"M"
                "COMBAT","FIGHT","WALKA"->"C"
                "ACTION","OTHER","AKCJA","INNE"->"A"
                else->""
            }
            val action=Regex("[A-Za-z_]+").findAll(row.optString("verb")).lastOrNull()?.value?.uppercase().orEmpty()
            val target=row.optString("target").trim().take(160)
            val kind=when(normalizedWorldToken(row.optString("kind"))){
                "PLACE","LOCATION","MIEJSCE","LOKACJA"->"P";"PERSON","ACTOR","CHARACTER","POSTAC","POSTAĆ","OSOBA"->"A"
                "OBJECT","ITEM","PRZEDMIOT"->"O";"GROUP","GRUPA"->"G";"ORGANIZATION","ORGANISATION","ORGANIZACJA"->"N"
                "EVENT","WYDARZENIE"->"E";"PROCESS","PROCES"->"R";"CONCEPT","POJECIE","POJĘCIE"->"C";else->""
            }
            val scope=when(normalizedWorldToken(row.optString("locality"))){
                "LOCAL","NEARBY","LOKALNY","BLISKO"->"LOCAL";"REMOTE","DISTANT","ODLEGLY","ODLEGŁY"->"REMOTE"
                "UNKNOWN","VERIFY","UNCERTAIN","NIEZNANY","SPRAWDZ"->"UNKNOWN";else->""
            }
            if(action.isBlank()||route !in setOf("M","C","A"))continue
            if(target.isNotBlank()&&(kind.isBlank()||scope.isBlank()))continue
            if(target.isBlank()&&(kind.isNotBlank()||scope.isNotBlank()))continue
            val groundedTarget=normalizedWorldText(target)
            if(target.isNotBlank()&&(groundedTarget.isBlank()||!groundedInput.contains(groundedTarget)))continue
            val nodeIndex=nodes.length()
            nodes.put(JSONObject().put("id",nodeIndex.toString()).put("a",action).put("r",route)
                .put("t",JSONArray().apply{target.takeIf(String::isNotBlank)?.let{put(JSONObject().put("x",it).put("k",kind).put("scope",scope))}})
                .put("d",JSONArray().apply{if(nodeIndex>0)put((nodeIndex-1).toString())}))
        }
        require(nodes.length()>0){"LOCAL_INTENT_ACTIONS_REQUIRED"}
        return JSONObject().put("s","U").put("n",nodes).put("q",JSONArray())
    }

    /**
     * Small local models are markedly more reliable at naming semantic roles than at selecting
     * abstract route/kind enums.  V9 therefore lets the model extract open-vocabulary actions and
     * grounded phrases while Core derives the trusted route and world base kind from the field
     * carrying the phrase.  The field vocabulary is domain-independent and works for every world.
     */
    private fun compactV9IntentRoot(root:JSONObject,request:AiIntentRequest):JSONObject{
        val groundedInput=normalizedWorldText(request.rawInput)
        val nodes=JSONArray()
        val steps=root.array("steps")
        val movementActions=setOf("GO","MOVE","TRAVEL","WALK","RUN","ENTER","LEAVE","REACH","IDE","IDĘ","ISC","IŚĆ","IDZ","IDŹ","BIEGNE","BIEGNĘ","WEJDZ","WEJDŹ","WYJDZ","WYJDŹ")
        val combatActions=setOf("ATTACK","COMBAT","FIGHT","STRIKE","DEFEND","ATAKUJ","ATAKOWAC","ATAKOWAĆ","WALCZ","WALCZYC","WALCZYĆ","BRON","BROŃ")
        val actionTokens=(0 until steps.length()).mapNotNull{steps.optJSONObject(it)?.optString("action")?.let(::normalizedWorldText)?.takeIf(String::isNotBlank)}.toSet()
        val inputTokens=groundedInput.split(' ').filter(String::isNotBlank)
        val rawInputTokens=Regex("[\\p{L}\\p{N}_:-]+").findAll(request.rawInput).map{it.value}.toList()
        val normalizedInputTokens=rawInputTokens.map(::normalizedWorldText)
        fun editDistance(left:String,right:String):Int{
            if(left==right)return 0
            if(left.isEmpty())return right.length
            if(right.isEmpty())return left.length
            var previous=IntArray(right.length+1){it}
            left.forEachIndexed{leftIndex,leftChar->
                val current=IntArray(right.length+1);current[0]=leftIndex+1
                right.forEachIndexed{rightIndex,rightChar->
                    current[rightIndex+1]=minOf(current[rightIndex]+1,previous[rightIndex+1]+1,previous[rightIndex]+if(leftChar==rightChar)0 else 1)
                }
                previous=current
            }
            return previous[right.length]
        }
        fun alignToPlayerText(value:String):String?{
            val normalized=normalizedWorldText(value)
            if(normalized.isBlank())return null
            val targetTokens=normalized.split(' ').filter(String::isNotBlank)
            if(targetTokens.isEmpty()||targetTokens.size>normalizedInputTokens.size)return null
            val targetCompact=targetTokens.joinToString("")
            var bestDistance=Int.MAX_VALUE;var bestStart=-1;var bestCandidate=""
            for(start in 0..normalizedInputTokens.size-targetTokens.size){
                val candidate=normalizedInputTokens.subList(start,start+targetTokens.size).joinToString("")
                val distance=editDistance(targetCompact,candidate)
                if(distance<bestDistance){bestDistance=distance;bestStart=start;bestCandidate=candidate}
            }
            val sharedPrefix=targetCompact.zip(bestCandidate).takeWhile{(left,right)->left==right}.size
            val allowed=maxOf(1,targetCompact.length/3)
            return if(bestStart>=0&&sharedPrefix>=minOf(4,targetCompact.length,bestCandidate.length)&&bestDistance<=allowed)
                rawInputTokens.subList(bestStart,bestStart+targetTokens.size).joinToString(" ") else null
        }
        fun alignActionToPlayerToken(value:String):String{
            val normalizedText=normalizedWorldText(value)
            val normalized=normalizedText.replace(" ","")
            if(normalized.isBlank())return value
            val exactIndex=normalizedInputTokens.indexOf(normalized)
            if(exactIndex>=0)return rawInputTokens[exactIndex]
            // A model may copy the whole grounded clause instead of returning its verb.  The old
            // suffix recovery then selected the final noun ("poligonie" in "Rozglądam się po
            // poligonie") and Core persisted that noun as the action.  For a multi-word clause,
            // prefer the token immediately preceding the Polish reflexive marker, otherwise the
            // first grounded token.  Suffix recovery remains limited to genuinely joined tokens.
            if(' ' in normalizedText){
                val words=normalizedText.split(' ').filter(String::isNotBlank)
                val reflexive=words.indexOfFirst{it in setOf("SIE","SIĘ")}
                val head=words.getOrNull(if(reflexive>0)reflexive-1 else 0)
                val groundedIndex=head?.let(normalizedInputTokens::indexOf)?:-1
                if(groundedIndex>=0)return rawInputTokens[groundedIndex]
            }
            // Small models occasionally join an adverb and a verb (for example
            // "spokojniećwiczę").  Prefer the final grounded token because it is the
            // action head, while never introducing a word absent from player input.
            val suffixIndex=normalizedInputTokens.indices
                .filter{normalizedInputTokens[it].length>=3&&normalized.endsWith(normalizedInputTokens[it])}
                .maxByOrNull{normalizedInputTokens[it].length}
            return suffixIndex?.let(rawInputTokens::get)?:value
        }
        val instructionOnlyActions=setOf(
            "KOPIUJ","SKOPIUJ","COPY","RETURN","ZWROC","ZWRÓĆ","WPISZ","DOKONCZ","DOKOŃCZ",
            "ZAMKNIJ","POMIN","POMIŃ","KLASYFIKUJ","ANALIZUJ","OPISZ"
        )
        fun actionGroundedInPlayerText(action:String):Boolean{
            val normalized=normalizedWorldText(action)
            if(normalized.isBlank())return false
            return inputTokens.any{token->
                val common=token.zip(normalized).takeWhile{(left,right)->left==right}.size
                common>=minOf(4,token.length,normalized.length)
            }
        }
        val emittedEquivalentSteps=mutableMapOf<String,Int>()
        fun surfaces(step:JSONObject)=listOf("destination","opponent","person","place","thing","group","organization","event","process","topic","description")
            .flatMap{field->when(val value=step.opt(field)){is JSONArray->(0 until value.length()).map{value.optString(it)};is String->listOf(value);else->emptyList()}}
            .map(::normalizedWorldText).filter(String::isNotBlank).toSet()
        val futureSurfaces=(0 until steps.length()).map{from->(from+1 until steps.length()).flatMap{index->steps.optJSONObject(index)?.let(::surfaces).orEmpty()}.toSet()}
        val movementRoleFields=listOf(
            Triple("destination","P","DESTINATION"),Triple("where","P","PLACE"),Triple("who","A","PERSON"),Triple("what","O","THING"),
            Triple("opponent","A","OPPONENT"),
            Triple("person","A","PERSON"),Triple("place","P","PLACE"),Triple("thing","O","THING"),
            Triple("group","G","GROUP"),Triple("organization","N","ORGANIZATION"),
            Triple("event","E","EVENT"),Triple("process","R","PROCESS"),Triple("topic","C","TOPIC"),
            Triple("description","C","DESCRIPTION")
        )
        val combatRoleFields=listOf(
            Triple("opponent","A","OPPONENT"),Triple("who","A","PERSON"),Triple("person","A","PERSON"),Triple("group","G","GROUP"),
            Triple("what","O","THING"),Triple("thing","O","THING"),Triple("where","P","PLACE"),Triple("place","P","PLACE"),
            Triple("destination","P","DESTINATION"),Triple("organization","N","ORGANIZATION"),Triple("event","E","EVENT"),
            Triple("process","R","PROCESS"),Triple("topic","C","TOPIC"),Triple("description","C","DESCRIPTION")
        )
        val actionRoleFields=listOf(
            // For an action with multiple arguments, the directly affected object/person must
            // precede contextual places and sources. Mechanics consume the first projected
            // target; the old movement-first order made "biorę kunai ze stojaka" acquire the
            // rack even when the provider correctly emitted what=kunai and where=stojak.
            Triple("what","O","THING"),Triple("thing","O","THING"),Triple("who","A","PERSON"),Triple("person","A","PERSON"),
            Triple("opponent","A","OPPONENT"),Triple("group","G","GROUP"),Triple("organization","N","ORGANIZATION"),
            Triple("event","E","EVENT"),Triple("process","R","PROCESS"),Triple("topic","C","TOPIC"),Triple("description","C","DESCRIPTION"),
            Triple("where","P","PLACE"),Triple("place","P","PLACE"),Triple("destination","P","DESTINATION")
        )
        val communicationActions=setOf(
            "ASK","QUERY","QUESTION","PYTAJ","PYTAM","ZAPYTAJ","TALK","SPEAK","ROZMAWIAJ","ROZMAWIAM","MOW","MÓW"
        )
        val communicationRoleFields=listOf(
            Triple("who","A","PERSON"),Triple("person","A","PERSON"),Triple("opponent","A","OPPONENT"),
            Triple("what","O","THING"),Triple("thing","O","THING"),Triple("topic","C","TOPIC"),
            Triple("where","P","PLACE"),Triple("place","P","PLACE"),Triple("destination","P","DESTINATION")
        )
        for(index in 0 until minOf(steps.length(),16)){
            val step=steps.optJSONObject(index)?:continue
            val modelAction=step.optString("action").trim()
            val rawAction=alignActionToPlayerToken(modelAction)
            val action=normalizedWorldToken(rawAction).takeIf{it.matches(Regex("[\\p{L}A-Z0-9_:-]{1,96}"))}.orEmpty()
            if(action.isBlank())continue
            if(action in instructionOnlyActions&&!actionGroundedInPlayerText(action))continue
            val movementAction=action in movementActions
            val combatAction=action in combatActions
            val communicationAction=action in communicationActions
            val modelActionToken=normalizedWorldToken(modelAction)
            val trainingStems=listOf("TRAIN","PRACT","LEARN","TRENUJ","TRENOW","CWICZ","ĆWICZ","STUDIUJ")
            val trainingAction=trainingStems.any{action.startsWith(it)||modelActionToken.contains(it)}||action in setOf("UCZE","UCZĘ","UCZE_SIE","UCZĘ_SIĘ")
            val semanticKind=normalizedWorldToken(step.optString("kind"))
            val locality=normalizedWorldToken(step.optString("locality"))
            val knownLocality=locality in setOf(
                "L","LOCAL","NEARBY","LOKALNY","LOKALNA","LOKALNE","BLISKO","NA_MIEJSCU",
                "R","REMOTE","DISTANT","JOURNEY","ODLEGLY","ODLEGŁY","PODROZ","PODRÓŻ",
                "U","UNKNOWN","VERIFY","UNCERTAIN","NIEZNANY","SPRAWDZ"
            )||listOf("POBLISK","BLISK","LOKAL","TUTAJ","NA_MIEJSCU","ODLEGL","DALEK","ZDAL","PODROZ","JOURNEY","DISTANT","REMOTE")
                .any(locality::contains)
            val scope=when{
                locality in setOf("L","LOCAL","NEARBY","LOKALNY","LOKALNA","LOKALNE","BLISKO","NA_MIEJSCU")->"LOCAL"
                locality in setOf("R","REMOTE","DISTANT","JOURNEY","ODLEGLY","ODLEGŁY","PODROZ","PODRÓŻ")->"REMOTE"
                locality in setOf("U","UNKNOWN","VERIFY","UNCERTAIN","NIEZNANY","SPRAWDZ")->"UNKNOWN"
                // Small local models sometimes copy a grounded proximity phrase into the enum.
                // These are spatial qualifiers, not names of world-specific locations.
                listOf("POBLISK","BLISK","LOKAL","TUTAJ","NA_MIEJSCU").any(locality::contains)->"LOCAL"
                listOf("ODLEGL","DALEK","ZDAL","PODROZ","JOURNEY","DISTANT","REMOTE").any(locality::contains)->"REMOTE"
                else->"UNKNOWN"
            }
            val references=JSONArray()
            var hasDestination=false
            var hasPlace=false
            var hasOpponent=false
            val roleFields=when{
                movementAction->movementRoleFields
                combatAction->combatRoleFields
                communicationAction->communicationRoleFields
                else->actionRoleFields
            }
            roleFields.forEach{(field,kind,role)->
                val values=when(val value=step.opt(field)){
                    is JSONArray->(0 until value.length()).map{value.optString(it)}
                    is String->listOf(value)
                    else->emptyList()
                }
                values.map(String::trim).filter(String::isNotBlank).take(4).forEach{surfaceValue->
                    val contextualTopic=field in setOf("what","topic","description")&&(
                        trainingAction||semanticKind in setOf("TRAIN","TRAINING","PRACTICE","LEARN","TRENING","CWICZENIE","ĆWICZENIE","QUERY","ASK","QUESTION","PYTANIE","TALK","COMMUNICATION","SPEAK","ROZMOWA")
                    )
                    if(contextualTopic)return@forEach
                    val surface=alignToPlayerText(surfaceValue.take(160))?:surfaceValue.take(160)
                    val groundedTarget=normalizedWorldText(surface)
                    val compactGrounded=groundedTarget.replace(" ","")
                    val appearsInInput=groundedTarget.isNotBlank()&&(groundedInput.contains(groundedTarget)||groundedInput.replace(" ","").contains(compactGrounded))
                    val copiedAction=groundedTarget in actionTokens
                    val belongsToLaterStep=movementAction&&field!="destination"&&groundedTarget in futureSurfaces[index]
                    val roleFitsMovement=!movementAction||field in setOf("destination","where")
                    if(appearsInInput&&!copiedAction&&!belongsToLaterStep&&roleFitsMovement){
                        val trustedKind=if(field=="destination"&&!movementAction)"O" else kind
                        references.put(JSONObject().put("x",surface).put("k",trustedKind).put("scope",scope).put("role",role))
                        if(field=="destination")hasDestination=true
                        if(field in setOf("where","place"))hasPlace=true
                        if(field=="opponent")hasOpponent=true
                    }
                }
            }
            // Some small-model outputs copy a grounded place into `locality` instead
            // of `where`. Recover it as a place reference only for an action that can
            // actually use a world target. Training/query topics remain context, not
            // materializable world objects.
            if(!knownLocality&&!trainingAction&&semanticKind !in setOf("TRAIN","TRAINING","QUERY","ASK","QUESTION","TALK","COMMUNICATION")&&
                locality.isNotBlank()&&references.length()==0){
                alignToPlayerText(step.optString("locality").take(160))?.let{surface->
                    references.put(JSONObject().put("x",surface).put("k","P").put("scope","UNKNOWN").put("role","PLACE"))
                    hasPlace=true
                }
            }
            val route=when{
                trainingAction->"T"
                semanticKind in setOf("MOVE","MOVEMENT","TRAVEL")&&(movementAction||hasDestination)->"M"
                semanticKind in setOf("COMBAT","FIGHT","ATTACK")->"C"
                semanticKind in setOf("TRAIN","TRAINING","PRACTICE","LEARN","TRENING","CWICZENIE","ĆWICZENIE")->"T"
                semanticKind in setOf("QUERY","ASK","QUESTION","PYTANIE")->"Q"
                semanticKind in setOf("TALK","COMMUNICATION","SPEAK","ROZMOWA")->"D"
                movementAction&&(hasDestination||hasPlace)->"M"
                combatAction||hasOpponent->"C"
                else->"A"
            }
            val referenceKey=(0 until references.length()).joinToString("|"){position->
                val reference=references.getJSONObject(position)
                listOf(reference.optString("x"),reference.optString("k"),reference.optString("scope"),reference.optString("role")).joinToString(":")
            }
            val equivalentKey="$action|$route|$referenceKey"
            val groundedAction=normalizedWorldText(rawAction)
            val maximumGroundedOccurrences=groundedInput.split(' ').count{it==groundedAction}.coerceAtLeast(1)
            val alreadyEmitted=emittedEquivalentSteps[equivalentKey]?:0
            if(alreadyEmitted>=maximumGroundedOccurrences)continue
            emittedEquivalentSteps[equivalentKey]=alreadyEmitted+1
            val nodeIndex=nodes.length()
            nodes.put(JSONObject().put("id",nodeIndex.toString()).put("a",action).put("r",route).put("t",references)
                .put("d",JSONArray().apply{if(nodeIndex>0)put((nodeIndex-1).toString())}))
        }
        require(nodes.length()>0){"LOCAL_INTENT_STEPS_REQUIRED"}
        return JSONObject().put("s","U").put("n",nodes).put("q",JSONArray())
    }

    private fun lineIntentRoot(payload:String,request:AiIntentRequest):JSONObject{
        val nodes=JSONArray()
        val groundedInput=normalizedWorldText(request.rawInput)
        payload.substringBefore("<|im_end|>").lineSequence()
            .map(String::trim).filter{it.isNotBlank()&&it.uppercase()!="ACTIONS"&&it.uppercase()!="END"}
            .take(16).forEach{line->
                val fields=line.removePrefix("-").trim().trim('|').split('|').map(String::trim)
                if(fields.size<5)return@forEach
                val action=Regex("[A-Za-z_]+").findAll(fields[0]).lastOrNull()?.value?.uppercase().orEmpty()
                val route=fields[1].uppercase()
                val target=fields[2].take(160)
                val kind=fields[3].uppercase()
                val scope=when(fields[4].uppercase()){"L","LOCAL"->"LOCAL";"R","REMOTE"->"REMOTE";"U","UNKNOWN"->"UNKNOWN";else->""}
                if(action.isBlank()||route !in setOf("M","C","A"))return@forEach
                if(target.isNotBlank()&&(kind !in setOf("P","A","O","G","N","E","R","C")||scope.isBlank()))return@forEach
                if(target.isBlank()&&(kind.isNotBlank()||fields[4].isNotBlank()))return@forEach
                val groundedTarget=normalizedWorldText(target)
                if(target.isNotBlank()&&(groundedTarget.isBlank()||!groundedInput.contains(groundedTarget)))return@forEach
                val index=nodes.length()
                nodes.put(JSONObject().put("id",index.toString()).put("a",action).put("r",route)
                    .put("t",JSONArray().apply{target.takeIf(String::isNotBlank)?.let{put(JSONObject()
                        .put("x",it).put("k",kind).put("scope",scope))}})
                    .put("d",JSONArray().apply{if(index>0)put((index-1).toString())}))
            }
        require(nodes.length()>0){"LOCAL_INTENT_ROWS_REQUIRED"}
        return JSONObject().put("s","U").put("n",nodes).put("q",JSONArray())
    }

    override fun decodeIntent(payload:String):IntentDocument=canonical.decodeIntent(payload)

    override fun encodeProposal(request:AiGmProposalRequest):String{
        val nodes=JSONArray(request.plan.steps.map{step->
            val intent=request.plan.intent.nodes.single{it.nodeUid==step.nodeUid}
            val targets=projectedTargetRefs(request.plan.intent,intent)
            JSONObject().put("id",step.nodeUid)
                .put("a",intent.semanticAction.canonicalActionUid?:intent.semanticAction.semanticFamilyUid)
                .put("phrase",intent.semanticAction.rawPhrase).put("modality",intent.modality.name)
                .put("targets",JSONArray(targets.map{JSONObject().put("k",it.kindUid).put("u",it.uid)}))
                .put("owner",step.mechanicsOwnerUid?:JSONObject.NULL).put("effect",step.sideEffectClass?.name?:"NONE")
        })
        val records=request.context.includedSegments.flatMap{segment->segment.records}.distinctBy{it.record.recordUid}
        val authoritative=records.filter{it.record.values["candidate_only"]!=true}
        val rankedCandidates=records.filter{it.record.values["candidate_only"]==true}
            .sortedWith(compareByDescending<CanonicalContextRecord>{(it.record.values["semantic_score"] as? Number)?.toDouble()?:-1.0}
                .thenBy{it.record.recordUid})
        val selected=(authoritative+rankedCandidates.take((12-authoritative.size).coerceAtLeast(0))).take(24)
        val context=JSONArray(selected.map{record->
            JSONObject().put("id",record.record.recordUid).put("v",JSONObject(compactProposalMap(record.record.values)))
        })
        return JSONObject().put("v","RPGOS_GM_LOCAL_1").put("c",request.plan.campaignUid).put("p",request.plan.planUid)
            .put("nodes",nodes).put("context",context)
            .put("guidance",request.strategicGuidance?.let{guidance->JSONArray(guidance.candidates.map{candidate->
                JSONObject().put("id",candidate.candidateUid).put("kind",candidate.kind.name)
                    .put("hint",candidate.summary.take(240))
            })}?:JSONArray())
            .put("reply","Dla każdego nodes zwróć status bez zmiany id: OK=można wykonać na podstawie context, F=nie udało się, Q=trzeba zapytać gracza. "+
                "Nie wymyślaj mechaniki ani faktów. Format tylko: {\"n\":[{\"id\":\"dokładne id\",\"s\":\"OK/F/Q\",\"x\":\"krótkie polskie podsumowanie\",\"q\":[\"czego brakuje\"]}]}")
            .toString()
    }

    private fun compactProposalMap(source:Map<String,Any?>):Map<String,Any?> = source.toSortedMap().mapNotNull{(key,value)->
        compactProposalValue(value)?.let{key to it}
    }.toMap(linkedMapOf())

    private fun compactProposalValue(value:Any?):Any?=when(value){
        null->null
        is Map<*,*>->{
            val compact=value.entries.mapNotNull{(key,item)->
                val name=key as? String?:return@mapNotNull null
                compactProposalValue(item)?.let{name to it}
            }.sortedBy{it.first}.toMap(linkedMapOf())
            compact.takeIf{it.isNotEmpty()}
        }
        is Iterable<*>->{
            val compact=value.mapNotNull(::compactProposalValue)
            compact.takeIf{it.isNotEmpty()}
        }
        is Array<*>->{
            val compact=value.mapNotNull(::compactProposalValue)
            compact.takeIf{it.isNotEmpty()}
        }
        is String->value.takeIf{it.isNotBlank()}
        else->value
    }

    override fun encodeRepair(request:AiRepairRequest):String{
        val root=JSONObject(encodeProposal(request.original))
        root.put("v","RPGOS_GM_LOCAL_REPAIR_1").put("reasons",JSONArray(request.rejectionReasonUids))
            .put("reply",root.getString("reply")+" Popraw odpowiedź zgodnie z reasons; zwróć cały obiekt n.")
        return root.toString()
    }

    override fun decodeProposal(payload:String,request:AiGmProposalRequest):GmProposalCandidate{
        val root=JSONObject(payload.trim())
        if(root.has("schema_version"))return canonical.decodeProposal(payload)
        val outputs=when{
            root.has("n")->root.array("n").objects()
            request.plan.steps.all{root.opt(it.nodeUid) is JSONObject}->request.plan.steps.map{step->
                JSONObject((root.getJSONObject(step.nodeUid)).toString()).put("id",step.nodeUid)
            }
            root.has("id")&&root.has("s")->listOf(root)
            request.plan.steps.size==1->listOf(root)
            else->throw IllegalArgumentException("LOCAL_PROPOSAL_NODE_ARRAY_REQUIRED")
        }.associateBy{it.optString("id")}
        val executable=request.plan.steps.filter{it.matchState in setOf(CapabilityMatchState.EXACT,CapabilityMatchState.COMPOSED,CapabilityMatchState.GENERIC)}
        val nodeProposals=executable.map{step->
            val intent=request.plan.intent.nodes.single{it.nodeUid==step.nodeUid}
            val output=outputs[step.nodeUid]?:outputs[""]?.takeIf{executable.size==1}?:run{
                // The 1.5B transport model can truncate a multi-node envelope after
                // the first valid row. Core may restore an omitted row only when it
                // already owns a deterministic effect for that planned capability;
                // no claim, target or mechanics result is invented here.
                if(localEffectKind(step,intent)==null)throw IllegalArgumentException("LOCAL_PROPOSAL_NODE_MISSING")
                JSONObject().put("id",step.nodeUid).put("s","OK")
                    .put("x","Czynność może zostać rozstrzygnięta przez Core.").put("q",JSONArray())
            }
            val requested=when(output.optString("s").uppercase()){
                "OK","SUCCESS"->GmNodeOutcomeState.PROPOSED_SUCCESS
                "F","FAIL","FAILURE"->GmNodeOutcomeState.PROPOSED_FAILURE
                "Q","CLARIFY"->GmNodeOutcomeState.NEEDS_CLARIFICATION
                else->throw IllegalArgumentException("LOCAL_PROPOSAL_STATE_REQUIRED")
            }
            val supportedEffect=localEffectKind(step,intent)!=null||step.sideEffectClass!=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT
            val outcome=if(requested==GmNodeOutcomeState.PROPOSED_SUCCESS&&!supportedEffect)GmNodeOutcomeState.REQUIRES_ADJUDICATION else requested
            val targets=projectedTargetRefs(request.plan.intent,intent)
            val summary=output.optString("x").trim().takeIf(String::isNotBlank)?:when(outcome){
                GmNodeOutcomeState.NEEDS_CLARIFICATION,GmNodeOutcomeState.REQUIRES_ADJUDICATION->"Potrzebuję doprecyzowania przed rozstrzygnięciem tej czynności."
                GmNodeOutcomeState.PROPOSED_FAILURE->"Ta próba nie może zostać wykonana w obecnej sytuacji."
                else->"Czynność może zostać rozstrzygnięta przez Core."
            }
            GmNodeProposal(step.nodeUid,"LOCAL-OUTCOME:${step.nodeUid}",summary,request.plan.intent.actor,
                intent.semanticAction.canonicalActionUid?:requireNotNull(intent.semanticAction.semanticFamilyUid),targets,intent.modality,outcome,
                uncertaintyUids=output.array("q").strings())
        }
        val byNode=nodeProposals.associateBy{it.nodeUid}
        val effects=executable.mapNotNull{step->
            val outcome=byNode.getValue(step.nodeUid)
            if(outcome.outcomeState!=GmNodeOutcomeState.PROPOSED_SUCCESS||step.sideEffectClass!=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT)return@mapNotNull null
            val intent=request.plan.intent.nodes.single{it.nodeUid==step.nodeUid}
            val kind=localEffectKind(step,intent)?:return@mapNotNull null
            val target=outcome.targetProjectedRefs.firstOrNull()
                ?:DomainRef(request.plan.intent.actor.actorKindUid,request.plan.intent.actor.actorUid)
            MechanicsEffectRequest("LOCAL-EFFECT:${step.nodeUid}",step.nodeUid,requireNotNull(step.mechanicsOwnerUid),kind,target)
        }
        return GmProposalCandidate(
            proposalUid="LOCAL-PROPOSAL:${semanticSha256(request.requestUid+payload).take(24)}",campaignUid=request.plan.campaignUid,
            planUid=request.plan.planUid,nodeProposals=nodeProposals,mechanicsEffects=effects,
            narrativeBlueprint=NarrativeBlueprint(listOf("RESOLVE_ACCEPTED_PLAYER_INTENT"),stopPointUid="PLAYER_DECISION_POINT"),
            providerUid="TRANSPORT_PROVIDER",modelUid="TRANSPORT_MODEL",intentFingerprint=request.plan.intent.canonicalFingerprint()
        )
    }

    override fun decodeProposal(payload:String):GmProposalCandidate=canonical.decodeProposal(payload)

    private fun localEffectKind(step:CanonicalTurnPlanStep,node:IntentNode):String?=when(step.mechanicsOwnerUid){
            "UNIVERSAL_MOVEMENT"->if(node.participants.isNotEmpty())"LOCATION_TRANSITION" else "MOVEMENT"
            "UNIVERSAL_COMBAT"->"WOUND"
            "UNIVERSAL_ACTION"->when(node.semanticAction.semanticFamilyUid?.uppercase()){
                "TRAIN","PRACTICE","LEARN"->"TRAINING"
                "TAKE"->"INVENTORY_ADD"
                "DROP"->"INVENTORY_REMOVE"
                else->"INTERACTION"
            }
            else->when(node.semanticAction.semanticFamilyUid?.uppercase()){
            "MOVE","TRAVEL","REACH","PUSH","ESCAPE"->if(node.participants.isNotEmpty())"LOCATION_TRANSITION" else "MOVEMENT"
            "ATTACK","COMBAT","STRIKE","FIGHT"->"WOUND"
            "TRAIN","PRACTICE","LEARN"->"TRAINING"
            else->"INTERACTION"
        }
    }

    override fun encodeNarrative(request:AiNarrativeRequest):String{
        // ACTION:* counters are internal replay evidence.  They are legal facts, but their
        // stock presentation sentence is not player-facing prose and small models tend to
        // copy it verbatim even when instructed to paraphrase it.
        val visibleResults=request.context.presentationConsequences.filterNot{value->
            normalizedWorldText(value).let{it.startsWith("czynność ")&&it.endsWith(" została wykonana")}
        }
        return JSONObject()
            .put("v","RPGOS_NARRATIVE_LOCAL_1").put("order",request.context.committedOrder).put("stop",request.context.stopPointUid)
            .apply{request.playerInput?.let{put("player_action",it)}}
            .put("scene",JSONArray(request.authorizedContext.map{entry->JSONObject()
                .put("id",entry.recordUid).put("k",entry.recordKindUid).put("e",entry.epistemicStateUid)
                .put("text",entry.projectedText)}))
            .put("facts",JSONArray(request.context.legalFacts.map{fact->JSONObject().put("id",fact.factUid)
                .put("p",fact.predicateUid).put("v",fact.valueCanonical).put("k",fact.kind.name)}))
            .put("results",JSONArray(visibleResults))
            .put("reply","Odpowiedź to wyłącznie JSON {\"t\":\"1-2 naturalne polskie zdania Mistrza Gry\",\"vol\":false}. "+
                "player_action jest działaniem już podjętym przez gracza w tej turze. Przekształć jego pierwszą osobę na drugą: np. Rozglądam się -> Rozglądasz się. "+
                "scene zawiera wyłącznie widoczne FACT dopuszczone przez Core. Opisz działanie oraz konkretne skutki z results, używając scene tylko jako tła. "+
                "Bez identyfikatorów, nagłówków, list i słów czynność, postęp, mechanika, aktualizacja lub stan akcji. Nie pisz w pierwszej osobie. "+
                "Nie dodawaj nowych osób, miejsc, zdarzeń, wyników, decyzji ani wypowiedzi gracza.")
            .toString()
    }

    override fun encodeNarrativeRepair(request:AiNarrativeRepairRequest):String{
        val root=JSONObject(encodeNarrative(request.original))
        root.put("v","RPGOS_NARRATIVE_LOCAL_REPAIR_1").put("reasons",JSONArray(request.rejectionReasonUids))
            .put("reply",root.getString("reply")+" Popraw tekst zgodnie z reasons.")
        return root.toString()
    }

    override fun decodeNarrative(payload:String,request:AiNarrativeRequest):RenderedNarrative{
        val root=JSONObject(payload.trim())
        if(root.has("text"))return canonical.decodeNarrative(payload)
        val generated=(root.optJSONArray("w")?.let{words->
            (0 until words.length()).map{words.optString(it).trim()}.filter(String::isNotBlank).joinToString(" ")
        }?:root.optString("t")).trim().takeIf(String::isNotBlank)
            ?:throw IllegalArgumentException("LOCAL_NARRATIVE_TEXT_REQUIRED")
        val text=generated.replace(
            Regex("(?iu)(?:^|\\n)\\s*Czynność\\s+[„\\\"]?[^\\n”\\\"]+[”\\\"]?\\s+została\\s+wykonana\\.?\\s*"),"\n"
        ).trim().takeIf(String::isNotBlank)?:throw IllegalArgumentException("LOCAL_NARRATIVE_TEXT_REQUIRED")
        val placeholder=normalizedWorldText(text) in setOf(
            "narracja","tekst","opis","odpowiedz","odpowiedź","wynik","pierwsze zdanie drugie zdanie"
        )
        require(text.length>=24&&!placeholder){"LOCAL_NARRATIVE_PLACEHOLDER_REJECTED"}
        val claims=request.context.legalFacts.take(8).map{fact->NarrativeSemanticClaim(
            "LOCAL-NARRATIVE:${fact.factUid}",
            if(fact.kind==CommittedNarrativeFactKind.MECHANICAL_RESULT)NarrativeClaimKind.MECHANICAL_RESULT else NarrativeClaimKind.FACT,
            fact.factUid,fact.predicateUid,fact.valueCanonical
        )}
        return RenderedNarrative(text,request.context.stopPointUid,request.context.committedOrder,claims,root.optBoolean("vol",false))
    }

    override fun decodeNarrative(payload:String):RenderedNarrative=canonical.decodeNarrative(payload)

    override fun encodeCharacterCreation(request:AiCharacterCreationRequest):String{
        val groups=JSONObject()
        val codes=mapOf(
            CharacterCreationDefinitionKind.STAT to "S",CharacterCreationDefinitionKind.RESOURCE to "R",
            CharacterCreationDefinitionKind.TALENT to "T",CharacterCreationDefinitionKind.POTENTIAL to "P",
            CharacterCreationDefinitionKind.SKILL to "K",CharacterCreationDefinitionKind.TECHNIQUE to "X",
            CharacterCreationDefinitionKind.ORIGIN to "O",CharacterCreationDefinitionKind.INNATE_FEATURE to "I",
            CharacterCreationDefinitionKind.STARTING_LOCATION to "L"
        )
        request.catalog.options.groupBy{it.kind}.forEach{(kind,options)->groups.put(codes.getValue(kind),JSONArray(options.map{option->
            JSONArray().put(option.definitionUid).put(option.displayName).put(option.minimumValue?:JSONObject.NULL)
                .put(option.maximumValue?:JSONObject.NULL).put(option.dimensionUid?:JSONObject.NULL)
        }))}
        val mode=request.localCharacterInteractionMode()
        return JSONObject().put("v","RPGOS_CC_LOCAL_1").put("c",request.campaignUid).put("mode",mode)
            .put("x",JSONArray(request.conversation.map{entry->JSONArray().put(if(entry.role==CharacterCreationConversationRole.PLAYER)"P" else "G").put(entry.text)}))
            .put("d",groups)
            .put("legend","d:S stat,R resource,T talent,P potential,K skill,X technique,O origin,I innate,L location; rows=[uid,name,min,max,dimension]")
            .put("reply",when(mode){
                "CATALOG_QUESTION"->"""Odpowiedz krótko po polsku na ostatnie pytanie gracza, używając tylko nazw z d. Format: {"s":"Q","q":"odpowiedź i jedno pytanie co wybrać","m":[]}. Nie kopiuj d."""
                "RANDOM"->"""Przygotuj krótki opis losowego szablonu. Zachowaj imię i wszystkie jawne wymagania gracza; losuj tylko elementy, których nie określił. Core wybierze legalne UID z pełnego katalogu. Format wyłącznie: {"s":"R","n":"imię","g":"MALE/FEMALE/NON_BINARY/UNSPECIFIED","i":{},"pick":[],"sum":"krótkie polskie podsumowanie"}."""
                else->"""Zinterpretuj opis gracza. W pick podaj tylko pasujące UID z d; nie przepisuj katalogu. Format wyłącznie: {"s":"R","n":"imię","g":"MALE/FEMALE/NON_BINARY/UNSPECIFIED","i":{"AGE":"wiek","ROLE":"rola"},"pick":["uid"],"sum":"krótkie polskie podsumowanie"}. Core uzupełni statystyki i zweryfikuje wybory."""
            })
            .toString()
    }

    override fun decodeCharacterCreation(payload:String):CharacterCreationGmCandidate{
        val trimmed=payload.trim();require(trimmed.startsWith('{')&&trimmed.endsWith('}'))
        var root=runCatching{JSONObject(trimmed)}.getOrElse{
            return malformedLocalQuestion(trimmed)?:throw it
        }
        root.optString("reply").trim().takeIf{it.startsWith('{')&&it.endsWith('}')}?.let{nested->
            root=runCatching{JSONObject(nested)}.getOrElse{
                return malformedLocalQuestion(nested)?:throw it
            }
        }
        val state=root.optString("s").ifBlank{root.optString("status")}.uppercase()
        if(state.isBlank())return canonical.decodeCharacterCreation(payload)
        return when(state){
            "Q"->CharacterCreationGmCandidate.NeedsPlayerChoice(
                playerFacingLocalQuestion(root.optString("q").ifBlank{root.optString("question")}),
                root.localQuestionMissingCategories()
            )
            "R"->{
                fun choices(key:String,potential:Boolean=false)=root.array(key).arrays().map{row->CharacterCreationValueChoice(
                    row.getString(0),row.getDouble(1),if(potential&&row.length()>2&&!row.isNull(2))row.getString(2) else null
                )}
                val creationUid=root.optString("id").takeIf{it.isNotBlank()}?:"CHARACTER-CREATION:${java.util.UUID.randomUUID()}"
                val playerUid=root.optString("p").takeIf{it.isNotBlank()}?:"PLAYER:${java.util.UUID.randomUUID()}"
                CharacterCreationGmCandidate.ReadyForConfirmation(PlayerCharacterCreationDraft(
                    creationUid=creationUid,campaignUid=root.reqString("c"),playerUid=playerUid,
                    displayName=root.reqString("n"),genderUid=root.reqString("g"),identityChoices=root.obj("i").stringMap(),
                    stats=choices("st"),resources=choices("rs"),talents=choices("ta"),potentials=choices("po",true),
                    skills=choices("sk"),techniques=choices("te"),originUids=root.array("or").strings(),
                    innateFeatureUids=root.array("inn").strings(),startingLocationUid=root.reqString("loc")
                ),root.reqString("sum"))
            }
            else->throw IllegalArgumentException("CHARACTER_CREATION_STATE_UNSUPPORTED")
        }
    }

    override fun decodeCharacterCreation(payload:String,request:AiCharacterCreationRequest):CharacterCreationGmCandidate{
        val trimmed=payload.trim()
        val root=runCatching{
            require(trimmed.startsWith('{')&&trimmed.endsWith('}'))
            JSONObject(trimmed)
        }.getOrElse{failure->
            // The compact R contract delegates every mechanical value and UID to Core. If a
            // small local model reaches its token cap after starting that compact object, its
            // malformed tail has no authority and can be discarded safely. Full legacy drafts
            // (st/rs/ta/...) remain strict and still fail closed.
            val compactReady=Regex("^\\{\\s*\"s\"\\s*:\\s*\"R\"").containsMatchIn(trimmed)&&!trimmed.contains("\"st\"")
            if(!compactReady)throw failure
            JSONObject().put("s","R").put("n","").put("g","").put("i",JSONObject())
                .put("pick",JSONArray()).put("sum","")
        }
        val state=root.optString("s").ifBlank{root.optString("status")}.uppercase()
        if(state!="R"||root.has("st"))return decodeCharacterCreation(payload)
        val full=request.authorityCatalog
        val conversationText=request.conversation.filter{it.role==CharacterCreationConversationRole.PLAYER}.joinToString("\n"){it.text}
        val latestPlayerText=request.conversation.lastOrNull{it.role==CharacterCreationConversationRole.PLAYER}?.text.orEmpty()
        val random=request.localCharacterInteractionMode()=="RANDOM"
        val seed=root.optString("seed").takeIf{it.isNotBlank()}?:semanticSha256(conversationText)
        val requested=root.array("pick").strings().toSet()
        fun randomIndex(kind:CharacterCreationDefinitionKind,size:Int)=
            (semanticSha256("$seed:${kind.name}").take(8).toLong(16)%size).toInt()
        fun randomUnit(option:CharacterCreationDefinitionOption):Double=
            semanticSha256("$seed:${option.kind}:${option.definitionUid}:${option.dimensionUid.orEmpty()}")
                .take(8).toLong(16).toDouble()/0xffffffffL.toDouble()
        fun ranked(kind:CharacterCreationDefinitionKind)=request.catalog.options.filter{it.kind==kind}
        fun all(kind:CharacterCreationDefinitionKind)=full.options.filter{it.kind==kind}.sortedBy{it.definitionUid}
        val playerRequestedSections=latestPlayerText.characterCreationRequestedSections()
        fun section(kind:CharacterCreationDefinitionKind)=when(kind){
            CharacterCreationDefinitionKind.STAT,CharacterCreationDefinitionKind.RESOURCE,
            CharacterCreationDefinitionKind.TALENT,CharacterCreationDefinitionKind.POTENTIAL->CharacterCreationDraftSection.PROGRESSION
            CharacterCreationDefinitionKind.SKILL->CharacterCreationDraftSection.SKILLS
            CharacterCreationDefinitionKind.TECHNIQUE->CharacterCreationDraftSection.TECHNIQUES
            CharacterCreationDefinitionKind.ORIGIN->CharacterCreationDraftSection.ORIGIN
            CharacterCreationDefinitionKind.INNATE_FEATURE->CharacterCreationDraftSection.INNATE_FEATURES
            CharacterCreationDefinitionKind.STARTING_LOCATION->CharacterCreationDraftSection.STARTING_LOCATION
        }
        fun matchWords(value:String)=value.lowercase()
            .replace(Regex("(?iu)\\b(?:epoce|epoki|era|ery|erze)\\s+[\\p{L}\\p{N}_-]+")," ")
            .replace(Regex("(?iu)\\b(?:mam\\s+na\\s+imi[eę]|jestem|nazywam\\s+si[eę])\\s+[\\p{L}'-]+")," ")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter{it.length>=4}
        val playerWords=matchWords(latestPlayerText)
        fun explicitPlayerScore(option:CharacterCreationDefinitionOption):Int{
            if(section(option.kind) !in playerRequestedSections)return 0
            val optionWords=matchWords("${option.definitionUid} ${option.displayName}")
            var score=0
            playerWords.forEach{playerWord->optionWords.forEach{optionWord->
                score+=when{
                    playerWord==optionWord->100
                    playerWord.length>=5&&optionWord.length>=5&&playerWord.commonPrefixWith(optionWord).length>=4->
                        40+playerWord.commonPrefixWith(optionWord).length
                    else->0
                }
            }}
            return score
        }
        fun selected(kind:CharacterCreationDefinitionKind,required:Boolean=true,maximum:Int=1):List<CharacterCreationDefinitionOption>{
            val legal=all(kind)
            // A model may suggest only legal UIDs, but it must not override a concrete choice
            // written by the player. RANDOM applies only to the unspecified remainder. Matching
            // is catalog-driven; identity phrases are removed so an era label cannot accidentally
            // select an unrelated place with the same word.
            val playerExplicit=legal.map{it to explicitPlayerScore(it)}.filter{it.second>0}
                .sortedWith(compareByDescending<Pair<CharacterCreationDefinitionOption,Int>>{it.second}.thenBy{it.first.definitionUid})
                .take(maximum).map{it.first}
            if(playerExplicit.isNotEmpty())return playerExplicit
            val explicit=legal.filter{it.definitionUid in requested}.take(maximum)
            if(explicit.isNotEmpty())return explicit
            if(random&&legal.isNotEmpty())return listOf(legal[randomIndex(kind,legal.size)])
            val projected=ranked(kind).take(maximum)
            return if(projected.isNotEmpty()||!required)projected else legal.firstOrNull()?.let(::listOf).orEmpty()
        }
        fun defaultValue(option:CharacterCreationDefinitionOption):Double{
            val minimum=option.minimumValue?:0.0
            if(option.kind==CharacterCreationDefinitionKind.RESOURCE)
                return option.maximumValue?:minimum.coerceAtLeast(1.0)
            if(random){
                val maximum=option.maximumValue?:when(option.kind){
                    CharacterCreationDefinitionKind.TALENT->minimum+10.0
                    CharacterCreationDefinitionKind.POTENTIAL->100.0.coerceAtLeast(minimum)
                    CharacterCreationDefinitionKind.SKILL,CharacterCreationDefinitionKind.TECHNIQUE->minimum+20.0
                    else->minimum
                }
                // A random starting character is not a random point anywhere in the entire
                // lifetime mastery range. Sampling 0..100 independently produced Academy pupils
                // with near-legendary power and fully mastered techniques. These fractions are
                // genre-neutral starting-profile bands; World Pack min/max values remain the hard
                // authority and potentials deliberately retain a wider future-growth range.
                val (lowerFraction,upperFraction)=when(option.kind){
                    CharacterCreationDefinitionKind.STAT->0.15 to 0.40
                    CharacterCreationDefinitionKind.TALENT->0.10 to 0.40
                    CharacterCreationDefinitionKind.POTENTIAL->0.35 to 1.00
                    CharacterCreationDefinitionKind.SKILL,CharacterCreationDefinitionKind.TECHNIQUE->0.05 to 0.30
                    else->0.0 to 1.0
                }
                val fraction=lowerFraction+(upperFraction-lowerFraction)*randomUnit(option)
                val value=minimum+(maximum-minimum)*fraction
                return (kotlin.math.round(value*100.0)/100.0).coerceIn(minimum,maximum)
            }
            return when(option.kind){
                CharacterCreationDefinitionKind.POTENTIAL->option.maximumValue?:50.0
                CharacterCreationDefinitionKind.STAT,CharacterCreationDefinitionKind.SKILL,CharacterCreationDefinitionKind.TECHNIQUE->{
                    val maximum=option.maximumValue?:minimum
                    (minimum+(maximum-minimum)*0.1).coerceIn(minimum,maximum)
                }
                CharacterCreationDefinitionKind.TALENT->{
                    val maximum=option.maximumValue?:minimum+10.0
                    (minimum+(maximum-minimum)*0.1).coerceIn(minimum,maximum)
                }
                else->minimum
            }
        }
        fun choice(option:CharacterCreationDefinitionOption)=CharacterCreationValueChoice(option.definitionUid,defaultValue(option),option.dimensionUid)
        val name=latestPlayerText.explicitLocalCharacterNameOrNull()
            ?:root.optString("n").trim().takeIf{it.isNotBlank()}
            ?:latestPlayerText.localCharacterName()
        val generatedGender=root.optString("g").trim().uppercase().takeIf{
            it in setOf("MALE","FEMALE","NON_BINARY","UNSPECIFIED")
        }
        val gender=latestPlayerText.explicitLocalCharacterGenderOrNull()
            ?:generatedGender?.takeUnless{random&&it=="UNSPECIFIED"}
            ?:latestPlayerText.localCharacterGender(seed,random)
        val identity=buildMap{
            putAll(root.obj("i").stringMap())
            Regex("(?i)\\b(\\d{1,3})\\s*(?:lat|lata|years?)\\b").find(latestPlayerText)?.groupValues?.get(1)?.let{put("AGE",it)}
            if(Regex("(?i)akadem|academy").containsMatchIn(latestPlayerText))put("ROLE","ACADEMY_STUDENT")
            if(Regex("(?i)naruto").containsMatchIn(latestPlayerText))put("ERA","NARUTO")
            if(random)put("RANDOM_SEED",seed)
        }
        data class SkillIntent(val playerLabel:String,val query:Regex,val catalogTerms:Set<String>)
        val skillIntents=listOf(
            SkillIntent("kontrola chakry",Regex("(?iu)kontrol\\p{L}* chakry|chakra control"),setOf("chakra control","control chakra","kontrola chakry")),
            SkillIntent("skradanie",Regex("(?iu)skrad|stealth"),setOf("skrad","stealth")),
            SkillIntent("walka wręcz",Regex("(?iu)walka wr[eę]cz|taijutsu|melee|hand.?to.?hand"),setOf("taijutsu","melee","hand to hand","walka wręcz","walka wrecz"))
        ).filter{it.query.containsMatchIn(latestPlayerText)}
        fun normalized(option:CharacterCreationDefinitionOption)="${option.definitionUid} ${option.displayName}".lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+")," ")
        val matchedSkills=skillIntents.mapNotNull{intent->ranked(CharacterCreationDefinitionKind.SKILL).firstOrNull{option->
            val searchable=normalized(option);intent.catalogTerms.any(searchable::contains)
        }}.distinctBy{it.definitionUid}
        val missingSkillIntents=skillIntents.filter{intent->matchedSkills.none{option->
            val searchable=normalized(option);intent.catalogTerms.any(searchable::contains)
        }}.map{it.playerLabel}
        val skills=(matchedSkills.ifEmpty{selected(CharacterCreationDefinitionKind.SKILL)}).map(::choice)
        val techniques=selected(CharacterCreationDefinitionKind.TECHNIQUE).map(::choice)
        require(skills.isNotEmpty()&&techniques.isNotEmpty())
        val origins=selected(CharacterCreationDefinitionKind.ORIGIN,false).map{it.definitionUid}
        val rejectsInnate=Regex("(?iu)\\b(bez|nie chc[eę]|żadn|zadn)\\b.{0,30}\\b(kekkei|genkai|cech[ay] wrodzon)\\b").containsMatchIn(latestPlayerText)||
            Regex("(?iu)\\b(zwykł|zwykl|normaln)\\p{L}*\\s+(?:postać|postac|ucz\\p{L}*|człowiek\\p{L}*|czlowiek\\p{L}*|bohater\\p{L}*)\\b").containsMatchIn(latestPlayerText)
        val wantsInnate=!rejectsInnate&&(random||requested.any{uid->all(CharacterCreationDefinitionKind.INNATE_FEATURE).any{it.definitionUid==uid}}||
            Regex("(?i)kekkei|genkai|klan|clan|wrodzon").containsMatchIn(latestPlayerText))
        val innate=if(wantsInnate)selected(CharacterCreationDefinitionKind.INNATE_FEATURE,false).map{it.definitionUid} else emptyList()
        val location=selected(CharacterCreationDefinitionKind.STARTING_LOCATION).firstOrNull()?.definitionUid?:"START"
        val draft=PlayerCharacterCreationDraft(
            creationUid=root.optString("id").takeIf{it.isNotBlank()}
                ?:"CHARACTER-CREATION:${semanticSha256("${request.campaignUid}|${request.requestUid}|CREATION").take(32)}",
            campaignUid=request.campaignUid,playerUid=root.optString("p").takeIf{it.isNotBlank()}
                ?:"PLAYER:${semanticSha256("${request.campaignUid}|${request.requestUid}|PLAYER").take(32)}",
            displayName=name,genderUid=gender,identityChoices=identity,
            stats=all(CharacterCreationDefinitionKind.STAT).map(::choice),resources=all(CharacterCreationDefinitionKind.RESOURCE).map(::choice),
            talents=all(CharacterCreationDefinitionKind.TALENT).map(::choice),
            potentials=all(CharacterCreationDefinitionKind.POTENTIAL).groupBy{it.definitionUid}.toSortedMap().values.map{variants->
                choice(variants.firstOrNull{it.dimensionUid=="MAXIMUM"}?:variants.first())
            },skills=skills,techniques=techniques,originUids=origins,innateFeatureUids=innate,startingLocationUid=location
        )
        fun label(uid:String)=full.options.firstOrNull{it.definitionUid==uid}?.displayName?:uid
        val role=when(identity["ROLE"]){"ACADEMY_STUDENT"->"uczeń Akademii";null->"postać";else->identity.getValue("ROLE")}
        val summary=buildString{
            append(name)
            identity["AGE"]?.let{append(", $it lat")}
            append(" — $role")
            origins.firstOrNull()?.let{append(" z ${label(it)}")}
            append(". Umiejętność: ${label(skills.first().definitionUid)}; technika: ${label(techniques.first().definitionUid)}")
            append("; start: ${label(location)}.")
            if(missingSkillIntents.isNotEmpty())append(" Brak legalnej definicji w tym World Packu: ${missingSkillIntents.joinToString(", ")}.")
        }
        return CharacterCreationGmCandidate.ReadyForConfirmation(draft,summary)
    }
}

/**
 * A Q response cannot mutate canonical state. Small local models sometimes emit one missing
 * category as a scalar (for example `"m":"academy"`) even though the compact contract asks for
 * an array. Normalising that safe question-only field avoids rejecting a useful model response;
 * malformed R drafts still pass through the strict typed decoder unchanged.
 */
private fun JSONObject.localQuestionMissingCategories():List<String>{
    val value=when{
        has("m")&&!isNull("m")->opt("m")
        has("missing_categories")&&!isNull("missing_categories")->opt("missing_categories")
        else->null
    }
    return when(value){
        is JSONArray->value.strings()
        is String->value.split(',').map(String::trim).filter(String::isNotBlank)
        else->emptyList()
    }
}

private fun AiCharacterCreationRequest.localCharacterInteractionMode():String{
    val latest=conversation.lastOrNull{it.role==CharacterCreationConversationRole.PLAYER}?.text.orEmpty().lowercase()
    if(Regex("\\b(losuj|wylosuj|random|przerzuć|przerzuc|reroll)\\b").containsMatchIn(latest))return "RANDOM"
    if('?' in latest||Regex("^\\s*(jakie|jaki|jaka|co |czym |które|ktore|pokaż|pokaz|wymień|wymien)").containsMatchIn(latest))return "CATALOG_QUESTION"
    return "DRAFT"
}

private fun String.localCharacterName():String{
    val named=explicitLocalCharacterNameOrNull()
    if(!named.isNullOrBlank())return named.replaceFirstChar{it.uppercase()}
    val first=trim().split(Regex("\\s+")).firstOrNull()?.trim(' ','.',',',';',':').orEmpty()
    val commandWords=setOf("losuj","wylosuj","wygeneruj","przerzuć","przerzuc","zmień","zmien","pokaż","pokaz")
    return first.takeIf{it.length in 2..40&&it.lowercase() !in commandWords}?.replaceFirstChar{it.uppercase()}?:"Bohater"
}

private fun String.explicitLocalCharacterNameOrNull():String?=
    Regex("(?iu)(?:mam\\s+na\\s+imi[eę]|jestem|nazywam\\s+si[eę])\\s+([\\p{L}][\\p{L}'-]{1,39})")
        .find(this)?.groupValues?.get(1)?.replaceFirstChar{it.uppercase()}

private fun String.explicitLocalCharacterGenderOrNull():String?=when{
    Regex("(?i)chłopiec|chlopiec|mężczyzn|mezczyzn|male").containsMatchIn(this)->"MALE"
    Regex("(?i)dziewczyn|kobiet|female").containsMatchIn(this)->"FEMALE"
    Regex("(?i)niebinar|non.?binary").containsMatchIn(this)->"NON_BINARY"
    else->null
}

private fun String.localCharacterGender(seed:String,random:Boolean):String=when{
    explicitLocalCharacterGenderOrNull()!=null->requireNotNull(explicitLocalCharacterGenderOrNull())
    random->listOf("MALE","FEMALE","NON_BINARY")[(semanticSha256("$seed:GENDER").take(8).toLong(16)%3).toInt()]
    else->"UNSPECIFIED"
}

/**
 * Small on-device models sometimes escape an array incorrectly inside a Q reply envelope.
 * Recovering a question is safe because it cannot create a draft or mutate canonical state;
 * malformed R replies deliberately remain fail-closed and must pass the full typed decoder.
 */
private fun malformedLocalQuestion(payload:String):CharacterCreationGmCandidate.NeedsPlayerChoice?{
    val readable=payload.replace("\\\"","\"")
    if(!Regex("(?i)\"(?:s|status)\"\\s*:\\s*\"Q\"").containsMatchIn(readable))return null
    val generated=Regex("(?i)\"(?:q|question)\"\\s*:\\s*\"([^\"]+)\"").find(readable)?.groupValues?.get(1).orEmpty()
    val question=playerFacingLocalQuestion(generated)
    return CharacterCreationGmCandidate.NeedsPlayerChoice(question,emptyList())
}

private fun playerFacingLocalQuestion(generated:String)=generated.takeIf{it.count(Char::isWhitespace)>=2}
    ?:"Jaką płeć, pochodzenie, talent i najważniejsze umiejętności ma mieć Twoja postać?"

private fun JSONObject.reqString(key:String):String{require(has(key)&&!isNull(key));return getString(key).also{require(it.isNotBlank())}}
private fun JSONObject.reqInt(key:String):Int{require(has(key)&&!isNull(key));return getInt(key)}
private fun JSONObject.reqLong(key:String):Long{require(has(key)&&!isNull(key));return getLong(key)}
private fun JSONObject.optLongOrZero(key:String):Long=if(has(key)&&!isNull(key))getLong(key) else 0L
private fun JSONObject.reqObject(key:String):JSONObject{require(has(key)&&!isNull(key));return getJSONObject(key)}
private fun JSONObject.optObject(key:String):JSONObject?=if(has(key)&&!isNull(key))getJSONObject(key) else null
private fun JSONObject.stringOrNull(key:String):String?=if(has(key)&&!isNull(key))getString(key).takeIf(String::isNotBlank) else null
private fun JSONObject.obj(key:String):JSONObject=optObject(key)?:JSONObject()
private fun JSONObject.array(key:String):JSONArray=if(has(key)&&!isNull(key))getJSONArray(key) else JSONArray()
private fun JSONArray.objects():List<JSONObject> = buildList{for(index in 0 until length())add(getJSONObject(index))}
private fun JSONArray.arrays():List<JSONArray> = buildList{for(index in 0 until length())add(getJSONArray(index))}
private fun JSONArray.strings():List<String> = buildList{for(index in 0 until length())getString(index).takeIf{it.isNotBlank()}?.let(::add)}
private fun JSONObject.stringMap():Map<String,String> = buildMap{
    keys().forEach{key->if(!isNull(key))put(key,getString(key))}
}
