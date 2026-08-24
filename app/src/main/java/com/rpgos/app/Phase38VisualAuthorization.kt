package com.rpgos.app

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

object VisualInputOrigins { const val CAMPAIGN_PROJECTION="CAMPAIGN_PROJECTION"; const val USER_STANDALONE="USER_STANDALONE" }
object VisualRequestKinds { const val GENERATE="GENERATE"; const val EDIT="EDIT" }

data class VisualSemanticRequest(
    val campaignUid:String,
    val audienceKindUid:String,
    val principalUid:String?,
    val purposeUid:String,
    val subjectKindUid:String,
    val subjectUid:String,
    val requestUid:String,
    val requestKindUid:String,
    val promptOrInstruction:String,
    val relatedEntityUid:String?=null,
    val sourceVisualUid:String?=null,
    val sourceImageSha256:String?=null
) {
    init {
        require(campaignUid.isNotBlank()&&audienceKindUid.isNotBlank()&&purposeUid.isNotBlank()&&subjectKindUid.isNotBlank()&&subjectUid.isNotBlank()&&requestUid.isNotBlank())
        require(requestKindUid in setOf(VisualRequestKinds.GENERATE,VisualRequestKinds.EDIT))
        if(requestKindUid==VisualRequestKinds.EDIT){require(!sourceVisualUid.isNullOrBlank());require(sourceImageSha256?.matches(Regex("[0-9a-f]{64}"))==true)}
    }
    fun semanticDigest():String=Phase38VisualAuthorization.digest(listOf(campaignUid,audienceKindUid,principalUid?:"",purposeUid,subjectKindUid,subjectUid,requestUid,requestKindUid,promptOrInstruction,relatedEntityUid?:"",sourceVisualUid?:"",sourceImageSha256?:"").joinToString("\u001f"))
}

data class Phase38VisualAuthorization(
    val campaignUid:String,val audienceKindUid:String,val audienceUid:String?,val purposeUid:String,
    val projectionAuthorityUid:String,val projectionVersionUid:String,val disclosureCeiling:DisclosureLevel,val payloadDisclosure:DisclosureLevel,
    val subjectKindUid:String,val subjectUid:String,val requestUid:String,val requestKindUid:String,
    val payloadSha256:String,val semanticRequestSha256:String,val inputOriginUid:String,
    val relatedEntityUid:String?=null,val sourceVisualUid:String?=null,val sourceImageSha256:String?=null
){
    init{
        require(projectionAuthorityUid==VisibilityAuthorityService.AUTHORITY_UID);require(projectionVersionUid==VisibilityAuthorityService.PROJECTION_VERSION_UID)
        require(audienceKindUid in setOf(AudienceKinds.PLAYER,AudienceKinds.PLAYER_CHARACTER));require(purposeUid in visualPurposes)
        require(disclosureCeiling!=DisclosureLevel.DENY&&disclosureCeiling.canReduceTo(payloadDisclosure));require(payloadSha256.matches(Regex("[0-9a-f]{64}")));require(semanticRequestSha256.matches(Regex("[0-9a-f]{64}")))
        if(requestKindUid==VisualRequestKinds.EDIT){require(!sourceVisualUid.isNullOrBlank());require(sourceImageSha256?.matches(Regex("[0-9a-f]{64}"))==true)}
    }
    fun requireRequest(request:VisualSemanticRequest){
        if(campaignUid!=request.campaignUid)throw VisibilityAuthorityFailure.CrossCampaign()
        require(audienceKindUid==request.audienceKindUid&&audienceUid==request.principalUid){"RPGOS-VISIBILITY:VISUAL_PRINCIPAL_MISMATCH"}
        require(purposeUid==request.purposeUid&&subjectKindUid==request.subjectKindUid&&subjectUid==request.subjectUid){"RPGOS-VISIBILITY:VISUAL_SUBJECT_OR_PURPOSE_MISMATCH"}
        require(requestUid==request.requestUid&&requestKindUid==request.requestKindUid){"RPGOS-VISIBILITY:VISUAL_REQUEST_IDENTITY_MISMATCH"}
        require(payloadSha256==digest(request.promptOrInstruction)){"RPGOS-VISIBILITY:VISUAL_PAYLOAD_SUBSTITUTION"}
        require(semanticRequestSha256==request.semanticDigest()){"RPGOS-VISIBILITY:VISUAL_SEMANTIC_SUBSTITUTION"}
        require(relatedEntityUid==request.relatedEntityUid&&sourceVisualUid==request.sourceVisualUid&&sourceImageSha256==request.sourceImageSha256){"RPGOS-VISIBILITY:VISUAL_SOURCE_SUBSTITUTION"}
    }
    fun requireRequest(campaignUid:String,expectedPurpose:String,payload:String){
        require(requestKindUid==VisualRequestKinds.GENERATE){"RPGOS-VISIBILITY:LEGACY_CHECK_FORBIDDEN_FOR_EDIT"}
        if(this.campaignUid!=campaignUid)throw VisibilityAuthorityFailure.CrossCampaign();require(purposeUid==expectedPurpose);require(payloadSha256==digest(payload))
    }
    fun toJson()=JSONObject().apply{
        put("campaign_uid",campaignUid);put("audience_kind_uid",audienceKindUid);put("audience_uid",audienceUid);put("purpose_uid",purposeUid);put("authority_uid",projectionAuthorityUid);put("projection_version_uid",projectionVersionUid)
        put("disclosure_ceiling",disclosureCeiling.name);put("payload_disclosure",payloadDisclosure.name);put("subject_kind_uid",subjectKindUid);put("subject_uid",subjectUid);put("request_uid",requestUid);put("request_kind_uid",requestKindUid)
        put("payload_sha256",payloadSha256);put("semantic_request_sha256",semanticRequestSha256);put("input_origin_uid",inputOriginUid);put("related_entity_uid",relatedEntityUid);put("source_visual_uid",sourceVisualUid);put("source_image_sha256",sourceImageSha256)
    }
    companion object{
        val visualPurposes=setOf(VisibilityPurposeKinds.SCENE_VISUALIZATION,VisibilityPurposeKinds.CHARACTER_VISUALIZATION,VisibilityPurposeKinds.LOCATION_VISUALIZATION,VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION)
        fun authorize(envelope:VisibilityProjectionEnvelope,expectedPurpose:String,subjectKindUid:String,subjectUid:String,payload:String,inputOriginUid:String=VisualInputOrigins.CAMPAIGN_PROJECTION,requestUid:String=UUID.randomUUID().toString(),payloadDisclosure:DisclosureLevel=envelope.maximumDisclosure,relatedEntityUid:String?=null,sourceVisualUid:String?=null,sourceImageSha256:String?=null):Phase38VisualAuthorization{
            envelope.requirePurpose(expectedPurpose);if(envelope.maximumDisclosure==DisclosureLevel.DENY)throw VisibilityAuthorityFailure.Escalation()
            val kind=if(expectedPurpose==VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION)VisualRequestKinds.EDIT else VisualRequestKinds.GENERATE
            val req=VisualSemanticRequest(envelope.campaignUid,envelope.audience.audienceKindUid,envelope.audience.principal?.uid,expectedPurpose,subjectKindUid,subjectUid,requestUid,kind,payload,relatedEntityUid,sourceVisualUid,sourceImageSha256)
            return Phase38VisualAuthorization(envelope.campaignUid,envelope.audience.audienceKindUid,envelope.audience.principal?.uid,expectedPurpose,envelope.authorityUid,envelope.projectionVersionUid,envelope.maximumDisclosure,payloadDisclosure,subjectKindUid,subjectUid,requestUid,kind,digest(payload),req.semanticDigest(),inputOriginUid,relatedEntityUid,sourceVisualUid,sourceImageSha256)
        }
        fun digest(payload:String)=digestBytes(payload.toByteArray(Charsets.UTF_8))
        fun digestBytes(payload:ByteArray)=MessageDigest.getInstance("SHA-256").digest(payload).joinToString(""){"%02x".format(it)}
    }
}
