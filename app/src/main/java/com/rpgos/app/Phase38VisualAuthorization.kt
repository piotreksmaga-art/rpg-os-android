package com.rpgos.app

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

object VisualInputOrigins {
    const val CAMPAIGN_PROJECTION = "CAMPAIGN_PROJECTION"
    const val USER_STANDALONE = "USER_STANDALONE"
}

data class Phase38VisualAuthorization(
    val campaignUid: String,
    val audienceKindUid: String,
    val audienceUid: String?,
    val purposeUid: String,
    val projectionAuthorityUid: String,
    val projectionVersionUid: String,
    val disclosureCeiling: DisclosureLevel,
    val payloadDisclosure: DisclosureLevel,
    val subjectKindUid: String,
    val subjectUid: String,
    val requestUid: String,
    val payloadSha256: String,
    val inputOriginUid: String
) {
    init {
        require(campaignUid.isNotBlank() && purposeUid.isNotBlank())
        require(subjectKindUid.isNotBlank() && subjectUid.isNotBlank() && requestUid.isNotBlank())
        require(projectionAuthorityUid == VisibilityAuthorityService.AUTHORITY_UID) { "RPGOS-VISIBILITY:INVALID_PROJECTION_AUTHORITY" }
        require(projectionVersionUid == VisibilityAuthorityService.PROJECTION_VERSION_UID) { "RPGOS-VISIBILITY:INVALID_PROJECTION_VERSION" }
        require(audienceKindUid in setOf(AudienceKinds.PLAYER, AudienceKinds.PLAYER_CHARACTER)) { "RPGOS-VISIBILITY:UNSUPPORTED_VISUAL_AUDIENCE" }
        require(purposeUid in visualPurposes) { "RPGOS-VISIBILITY:UNSUPPORTED_VISUAL_PURPOSE" }
        require(disclosureCeiling != DisclosureLevel.DENY) { "RPGOS-VISIBILITY:PROJECTION_DENIED" }
        require(disclosureCeiling.canReduceTo(payloadDisclosure)) { "RPGOS-VISIBILITY:VISUAL_DISCLOSURE_ESCALATION" }
        require(payloadSha256.matches(Regex("[0-9a-f]{64}"))) { "RPGOS-VISIBILITY:INVALID_VISUAL_PAYLOAD_DIGEST" }
        require(inputOriginUid in setOf(VisualInputOrigins.CAMPAIGN_PROJECTION, VisualInputOrigins.USER_STANDALONE))
    }

    fun requireRequest(campaignUid: String, expectedPurpose: String, payload: String) {
        if (this.campaignUid != campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        require(purposeUid == expectedPurpose) { "RPGOS-VISIBILITY:VISUAL_PURPOSE_MISMATCH" }
        require(payloadSha256 == digest(payload)) { "RPGOS-VISIBILITY:VISUAL_PAYLOAD_SUBSTITUTION" }
        if (!disclosureCeiling.canReduceTo(payloadDisclosure)) throw VisibilityAuthorityFailure.Escalation()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("campaign_uid", campaignUid)
        put("audience_kind_uid", audienceKindUid)
        put("audience_uid", audienceUid)
        put("purpose_uid", purposeUid)
        put("authority_uid", projectionAuthorityUid)
        put("projection_version_uid", projectionVersionUid)
        put("disclosure_ceiling", disclosureCeiling.name)
        put("payload_disclosure", payloadDisclosure.name)
        put("subject_kind_uid", subjectKindUid)
        put("subject_uid", subjectUid)
        put("request_uid", requestUid)
        put("payload_sha256", payloadSha256)
        put("input_origin_uid", inputOriginUid)
    }

    companion object {
        val visualPurposes = setOf(
            VisibilityPurposeKinds.SCENE_VISUALIZATION,
            VisibilityPurposeKinds.CHARACTER_VISUALIZATION,
            VisibilityPurposeKinds.LOCATION_VISUALIZATION,
            VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION
        )

        fun authorize(
            envelope: VisibilityProjectionEnvelope,
            expectedPurpose: String,
            subjectKindUid: String,
            subjectUid: String,
            payload: String,
            inputOriginUid: String = VisualInputOrigins.CAMPAIGN_PROJECTION,
            requestUid: String = UUID.randomUUID().toString(),
            payloadDisclosure: DisclosureLevel = envelope.maximumDisclosure
        ): Phase38VisualAuthorization {
            envelope.requirePurpose(expectedPurpose)
            if (envelope.maximumDisclosure == DisclosureLevel.DENY) throw VisibilityAuthorityFailure.Escalation()
            return Phase38VisualAuthorization(
                campaignUid = envelope.campaignUid,
                audienceKindUid = envelope.audience.audienceKindUid,
                audienceUid = envelope.audience.principal?.uid,
                purposeUid = expectedPurpose,
                projectionAuthorityUid = envelope.authorityUid,
                projectionVersionUid = envelope.projectionVersionUid,
                disclosureCeiling = envelope.maximumDisclosure,
                payloadDisclosure = payloadDisclosure,
                subjectKindUid = subjectKindUid,
                subjectUid = subjectUid,
                requestUid = requestUid,
                payloadSha256 = digest(payload),
                inputOriginUid = inputOriginUid
            )
        }

        fun digest(payload: String): String = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
