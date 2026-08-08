package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Durable authorization boundary for organization knowledge.
 *
 * Organization knowledge may only be promoted from membership/publication records already
 * stored in campaign.db. The model can reference their IDs, but cannot manufacture clearance
 * or publication authority inside a turn proposal.
 */
object OrganizationKnowledgeAuthorizationSchema141 {
    const val MIGRATION_ID = "GM-141-ORGANIZATION-KNOWLEDGE-AUTH-V1"

    fun ensure(db: SQLiteDatabase) {
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS rpgos_schema_migrations(
                    migration_id TEXT PRIMARY KEY,
                    applied_at INTEGER NOT NULL,
                    notes TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS gm_organization_memberships(
                    membership_id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    npc_id TEXT NOT NULL,
                    organization_id TEXT NOT NULL,
                    clearance INTEGER NOT NULL CHECK(clearance >= 0),
                    valid_from_turn INTEGER NOT NULL CHECK(valid_from_turn >= 0),
                    valid_until_turn INTEGER,
                    created_at INTEGER NOT NULL,
                    CHECK(valid_until_turn IS NULL OR valid_until_turn >= valid_from_turn)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_gm_org_memberships_npc_turn
                ON gm_organization_memberships(campaign_id, npc_id, organization_id, valid_from_turn, valid_until_turn)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS gm_organization_fact_publications(
                    publication_id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    organization_id TEXT NOT NULL,
                    truth_id TEXT NOT NULL,
                    subject_id TEXT NOT NULL,
                    predicate TEXT NOT NULL,
                    minimum_clearance INTEGER NOT NULL CHECK(minimum_clearance >= 0),
                    valid_from_turn INTEGER NOT NULL CHECK(valid_from_turn >= 0),
                    valid_until_turn INTEGER,
                    created_at INTEGER NOT NULL,
                    CHECK(valid_until_turn IS NULL OR valid_until_turn >= valid_from_turn)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_gm_org_publications_org_turn
                ON gm_organization_fact_publications(campaign_id, organization_id, valid_from_turn, valid_until_turn)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes)
                VALUES(?,?,?)
                """.trimIndent(),
                arrayOf(
                    MIGRATION_ID,
                    System.currentTimeMillis(),
                    "GM141 durable organization memberships and fact publication authority"
                )
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }
}

interface OrganizationKnowledgeAuthorizationStore141 {
    suspend fun appendMembership(campaignUid: EntityUid, membership: OrganizationMembership141)
    suspend fun appendPublication(campaignUid: EntityUid, publication: OrganizationFactPublication141)

    suspend fun membershipsForNpc(
        campaignUid: EntityUid,
        npcUid: EntityUid,
        atTurnId: Long
    ): List<OrganizationMembership141>

    suspend fun publicationsForOrganization(
        campaignUid: EntityUid,
        organizationUid: EntityUid,
        atTurnId: Long
    ): List<OrganizationFactPublication141>

    suspend fun membershipByUid(
        campaignUid: EntityUid,
        membershipUid: EntityUid,
        atTurnId: Long
    ): OrganizationMembership141?

    suspend fun publicationByUid(
        campaignUid: EntityUid,
        publicationUid: EntityUid,
        atTurnId: Long
    ): OrganizationFactPublication141?
}

class SQLiteOrganizationKnowledgeAuthorizationStore141(
    private val db: SQLiteDatabase
) : OrganizationKnowledgeAuthorizationStore141 {
    private data class SourceFactMeta(
        val kind: String,
        val subjectId: String?,
        val predicate: String,
        val validFromTurn: Long,
        val validUntilTurn: Long?
    )

    init { OrganizationKnowledgeAuthorizationSchema141.ensure(db) }

    override suspend fun appendMembership(campaignUid: EntityUid, membership: OrganizationMembership141) {
        val values = ContentValues().apply {
            put("membership_id", membership.membershipUid.value)
            put("campaign_id", campaignUid.value)
            put("npc_id", membership.npcUid.value)
            put("organization_id", membership.organizationUid.value)
            put("clearance", membership.clearance)
            put("valid_from_turn", membership.validFromTurn)
            membership.validUntilTurn?.let { put("valid_until_turn", it) }
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("gm_organization_memberships", null, values)
    }

    override suspend fun appendPublication(campaignUid: EntityUid, publication: OrganizationFactPublication141) {
        validateSourceFact(campaignUid, publication)
        val values = ContentValues().apply {
            put("publication_id", publication.publicationUid.value)
            put("campaign_id", campaignUid.value)
            put("organization_id", publication.organizationUid.value)
            put("truth_id", publication.truthUid.value)
            put("subject_id", publication.subjectUid.value)
            put("predicate", publication.predicate)
            put("minimum_clearance", publication.minimumClearance)
            put("valid_from_turn", publication.validFromTurn)
            publication.validUntilTurn?.let { put("valid_until_turn", it) }
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("gm_organization_fact_publications", null, values)
    }

    override suspend fun membershipsForNpc(
        campaignUid: EntityUid,
        npcUid: EntityUid,
        atTurnId: Long
    ): List<OrganizationMembership141> {
        require(atTurnId >= 0L) { "atTurnId nie może być ujemny." }
        val out = mutableListOf<OrganizationMembership141>()
        db.rawQuery(
            """
            SELECT membership_id,organization_id,clearance,valid_from_turn,valid_until_turn
            FROM gm_organization_memberships
            WHERE campaign_id=? AND npc_id=?
              AND valid_from_turn<=?
              AND (valid_until_turn IS NULL OR valid_until_turn>=?)
            ORDER BY organization_id ASC, clearance DESC, membership_id ASC
            """.trimIndent(),
            arrayOf(campaignUid.value, npcUid.value, atTurnId.toString(), atTurnId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += OrganizationMembership141(
                    membershipUid = EntityUid(c.getString(0)),
                    npcUid = npcUid,
                    organizationUid = EntityUid(c.getString(1)),
                    clearance = c.getInt(2),
                    validFromTurn = c.getLong(3),
                    validUntilTurn = if (c.isNull(4)) null else c.getLong(4)
                )
            }
        }
        return out
    }

    override suspend fun publicationsForOrganization(
        campaignUid: EntityUid,
        organizationUid: EntityUid,
        atTurnId: Long
    ): List<OrganizationFactPublication141> {
        require(atTurnId >= 0L) { "atTurnId nie może być ujemny." }
        val out = mutableListOf<OrganizationFactPublication141>()
        db.rawQuery(
            """
            SELECT publication_id,truth_id,subject_id,predicate,minimum_clearance,valid_from_turn,valid_until_turn
            FROM gm_organization_fact_publications
            WHERE campaign_id=? AND organization_id=?
              AND valid_from_turn<=?
              AND (valid_until_turn IS NULL OR valid_until_turn>=?)
            ORDER BY publication_id ASC
            """.trimIndent(),
            arrayOf(campaignUid.value, organizationUid.value, atTurnId.toString(), atTurnId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += OrganizationFactPublication141(
                    publicationUid = EntityUid(c.getString(0)),
                    organizationUid = organizationUid,
                    truthUid = EntityUid(c.getString(1)),
                    subjectUid = EntityUid(c.getString(2)),
                    predicate = c.getString(3),
                    minimumClearance = c.getInt(4),
                    validFromTurn = c.getLong(5),
                    validUntilTurn = if (c.isNull(6)) null else c.getLong(6)
                )
            }
        }
        return out
    }

    override suspend fun membershipByUid(
        campaignUid: EntityUid,
        membershipUid: EntityUid,
        atTurnId: Long
    ): OrganizationMembership141? {
        require(atTurnId >= 0L) { "atTurnId nie może być ujemny." }
        db.rawQuery(
            """
            SELECT npc_id,organization_id,clearance,valid_from_turn,valid_until_turn
            FROM gm_organization_memberships
            WHERE campaign_id=? AND membership_id=?
              AND valid_from_turn<=?
              AND (valid_until_turn IS NULL OR valid_until_turn>=?)
            LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid.value, membershipUid.value, atTurnId.toString(), atTurnId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return OrganizationMembership141(
                membershipUid = membershipUid,
                npcUid = EntityUid(c.getString(0)),
                organizationUid = EntityUid(c.getString(1)),
                clearance = c.getInt(2),
                validFromTurn = c.getLong(3),
                validUntilTurn = if (c.isNull(4)) null else c.getLong(4)
            )
        }
    }

    override suspend fun publicationByUid(
        campaignUid: EntityUid,
        publicationUid: EntityUid,
        atTurnId: Long
    ): OrganizationFactPublication141? {
        require(atTurnId >= 0L) { "atTurnId nie może być ujemny." }
        db.rawQuery(
            """
            SELECT organization_id,truth_id,subject_id,predicate,minimum_clearance,valid_from_turn,valid_until_turn
            FROM gm_organization_fact_publications
            WHERE campaign_id=? AND publication_id=?
              AND valid_from_turn<=?
              AND (valid_until_turn IS NULL OR valid_until_turn>=?)
            LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid.value, publicationUid.value, atTurnId.toString(), atTurnId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return OrganizationFactPublication141(
                publicationUid = publicationUid,
                organizationUid = EntityUid(c.getString(0)),
                truthUid = EntityUid(c.getString(1)),
                subjectUid = EntityUid(c.getString(2)),
                predicate = c.getString(3),
                minimumClearance = c.getInt(4),
                validFromTurn = c.getLong(5),
                validUntilTurn = if (c.isNull(6)) null else c.getLong(6)
            )
        }
    }

    private fun validateSourceFact(
        campaignUid: EntityUid,
        publication: OrganizationFactPublication141
    ) {
        val source = db.rawQuery(
            """
            SELECT truth_kind,subject_id,predicate,valid_from_turn,valid_until_turn
            FROM gm_facts
            WHERE campaign_id=? AND fact_id=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid.value, publication.truthUid.value)
        ).use { c ->
            if (!c.moveToFirst()) null
            else SourceFactMeta(
                kind = c.getString(0),
                subjectId = if (c.isNull(1)) null else c.getString(1),
                predicate = c.getString(2),
                validFromTurn = c.getLong(3),
                validUntilTurn = if (c.isNull(4)) null else c.getLong(4)
            )
        } ?: error(
            "Organization publication ${publication.publicationUid.value} wskazuje nieistniejący truth_id=${publication.truthUid.value}."
        )

        require(source.kind == TruthKind.FACT.name) {
            "Organization publication ${publication.publicationUid.value} może wskazywać wyłącznie FACT, a znaleziono ${source.kind}."
        }
        require(source.subjectId == publication.subjectUid.value) {
            "Organization publication ${publication.publicationUid.value} ma subject=${publication.subjectUid.value}, ale FACT ${publication.truthUid.value} ma subject=${source.subjectId}."
        }
        require(source.predicate == publication.predicate) {
            "Organization publication ${publication.publicationUid.value} ma predicate=${publication.predicate}, ale FACT ${publication.truthUid.value} ma predicate=${source.predicate}."
        }
        require(publication.validFromTurn >= source.validFromTurn) {
            "Organization publication ${publication.publicationUid.value} zaczyna się przed ważnością FACT ${publication.truthUid.value}."
        }
        if (source.validUntilTurn != null) {
            require(publication.validUntilTurn != null && publication.validUntilTurn <= source.validUntilTurn) {
                "Organization publication ${publication.publicationUid.value} wykracza poza ważność FACT ${publication.truthUid.value}."
            }
        }
    }
}
