package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class SocialReader(
    private val worldDb: SQLiteDatabase,
    private val saveDb: SQLiteDatabase,
    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService()
) {
    fun relationships(audience: AudienceContext, purpose: PurposeContext): List<RelationshipItem> =
        relationshipsProjection(audience, purpose).value ?: emptyList()

    fun relationshipsProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<RelationshipItem>> =
        projected(audience, purpose, VisibilitySubjectKinds.RELATIONSHIP_DATA, "RELATIONSHIPS") {
            val out = mutableListOf<RelationshipItem>()
            saveDb.rawQuery("""SELECT other_entity_uid,relationship_type,relationship_score
                   FROM relationships_v2 ORDER BY ABS(relationship_score) DESC LIMIT 100""", null).use { c ->
                while (c.moveToNext()) out += RelationshipItem(c.getString(0), c.getString(1), c.getString(2))
            }
            out
        }

    fun organizations(audience: AudienceContext, purpose: PurposeContext): List<OrganizationItem> =
        organizationsProjection(audience, purpose).value ?: emptyList()

    fun organizationsProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<OrganizationItem>> =
        projected(audience, purpose, VisibilitySubjectKinds.ORGANIZATION_DATA, "ORGANIZATIONS") {
            val out = mutableListOf<OrganizationItem>()
            worldDb.rawQuery("""SELECT organization_uid,name,organization_type,active_status
                   FROM organization_definitions_v3 ORDER BY name""", null).use { c ->
                while (c.moveToNext()) out += OrganizationItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
            }
            out
        }

    fun politics(audience: AudienceContext, purpose: PurposeContext): List<PoliticalItem> =
        politicsProjection(audience, purpose).value ?: emptyList()

    fun politicsProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<PoliticalItem>> =
        projected(audience, purpose, VisibilitySubjectKinds.POLITICS_DATA, "POLITICS") {
            val out = mutableListOf<PoliticalItem>()
            saveDb.rawQuery("""SELECT political_uid,display_name,legitimacy,influence,stability
                   FROM political_entities ORDER BY influence DESC""", null).use { c ->
                while (c.moveToNext()) out += PoliticalItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
            }
            out
        }

    private fun <T> projected(
        audience: AudienceContext,
        purpose: PurposeContext,
        subjectKind: String,
        uid: String,
        read: () -> List<T>
    ): VisibilityProjection<List<T>> {
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(audience.campaignUid, subjectKind, uid))
        return visibility.projectList(request, read)
    }
}
