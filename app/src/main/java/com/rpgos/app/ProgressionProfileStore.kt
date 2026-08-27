package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

internal class ProgressionProfileStore(
    private val db: SQLiteDatabase,
    private val campaignId: String
) {
    init { require(campaignId.isNotBlank()) { "campaignId must not be blank" } }

    fun registerDomains(worldPackUid: String, definitions: List<ProgressionDomainDefinition>) {
        require(worldPackUid.isNotBlank())
        definitions.forEach { definition ->
            ProgressionProfilePolicy.validate(definition)
            require(definition.worldPackUid == worldPackUid) { "Domain belongs to another World Pack: ${definition.domainUid}" }
            val existing = domain(definition.domainUid)
            require(existing == null || existing == definition) { "Domain UID ${definition.domainUid} already exists with incompatible metadata" }
            db.rawQuery("SELECT domain_uid FROM progression_domain_definitions WHERE world_pack_uid=? AND domain_key=? AND domain_uid<>? LIMIT 1", arrayOf(worldPackUid, definition.key, definition.domainUid)).use {
                require(!it.moveToFirst()) { "Duplicate progression domain key '${definition.key}' in World Pack $worldPackUid" }
            }
            definition.parentDomainUid?.let { parentUid ->
                val parent = domain(parentUid) ?: error("Parent progression domain does not exist: $parentUid")
                require(parent.worldPackUid == worldPackUid) { "Parent domain belongs to another World Pack" }
            }
        }
        inTransaction {
            definitions.forEach { d -> if (domain(d.domainUid) == null) {
                db.execSQL("""INSERT INTO progression_domain_definitions(domain_uid,world_pack_uid,domain_key,display_name,category,parent_domain_uid,applies_to_talent,applies_to_potential,definition_version,provenance) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    arrayOf<Any?>(d.domainUid,d.worldPackUid,d.key,d.displayName,d.category,d.parentDomainUid,if(d.appliesToTalent)1 else 0,if(d.appliesToPotential)1 else 0,d.definitionVersion,d.provenance))
            } }
        }
    }

    fun domains(worldPackUid: String? = null): List<ProgressionDomainDefinition> {
        val sql = if (worldPackUid == null) "SELECT domain_uid,world_pack_uid,domain_key,display_name,category,parent_domain_uid,applies_to_talent,applies_to_potential,definition_version,provenance FROM progression_domain_definitions ORDER BY world_pack_uid,domain_uid"
        else "SELECT domain_uid,world_pack_uid,domain_key,display_name,category,parent_domain_uid,applies_to_talent,applies_to_potential,definition_version,provenance FROM progression_domain_definitions WHERE world_pack_uid=? ORDER BY domain_uid"
        val args = if (worldPackUid == null) null else arrayOf(worldPackUid)
        val out = mutableListOf<ProgressionDomainDefinition>()
        db.rawQuery(sql,args).use { c -> while(c.moveToNext()) out += ProgressionDomainDefinition(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),if(c.isNull(5))null else c.getString(5),c.getInt(6)!=0,c.getInt(7)!=0,c.getLong(8),c.getString(9)) }
        return out
    }

    fun talentProfile(characterUid: String): TalentProfile {
        require(characterUid.isNotBlank())
        val out = mutableListOf<TalentEntry>()
        db.rawQuery("SELECT campaign_id,character_uid,domain_uid,base_value,entry_version,provenance FROM talent_profile_entries WHERE campaign_id=? AND character_uid=? ORDER BY domain_uid", arrayOf(campaignId,characterUid)).use { c ->
            while(c.moveToNext()) out += TalentEntry(c.getString(0),c.getString(1),c.getString(2),normalizeZero(c.getDouble(3)),c.getLong(4),c.getString(5))
        }
        return TalentProfile(campaignId,characterUid,out)
    }

    fun potentialProfile(characterUid: String): PotentialProfile {
        require(characterUid.isNotBlank())
        val out = mutableListOf<PotentialEntry>()
        db.rawQuery("SELECT campaign_id,character_uid,domain_uid,dimension_uid,base_value,entry_version,provenance FROM potential_profile_entries WHERE campaign_id=? AND character_uid=? ORDER BY domain_uid,dimension_uid", arrayOf(campaignId,characterUid)).use { c ->
            while(c.moveToNext()) out += PotentialEntry(c.getString(0),c.getString(1),c.getString(2),c.getString(3),normalizeZero(c.getDouble(4)),c.getLong(5),c.getString(6))
        }
        return PotentialProfile(campaignId,characterUid,out)
    }

    internal fun saveTalent(entry: TalentEntry) {
        ProgressionProfilePolicy.validate(entry)
        require(entry.campaignId == campaignId) { "TalentEntry belongs to another campaign" }
        val d = domain(entry.domainUid) ?: error("Progression domain does not exist: ${entry.domainUid}")
        require(d.appliesToTalent) { "Domain ${entry.domainUid} does not support Talent" }
        db.updateOrInsertCompat(
            "UPDATE talent_profile_entries SET base_value=?,entry_version=?,provenance=? WHERE campaign_id=? AND character_uid=? AND domain_uid=?",
            arrayOf<Any?>(normalizeZero(entry.baseValue),entry.entryVersion,entry.provenance,entry.campaignId,entry.characterUid,entry.domainUid),
            "INSERT INTO talent_profile_entries(campaign_id,character_uid,domain_uid,base_value,entry_version,provenance) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(entry.campaignId,entry.characterUid,entry.domainUid,normalizeZero(entry.baseValue),entry.entryVersion,entry.provenance)
        )
    }

    internal fun savePotential(entry: PotentialEntry) {
        ProgressionProfilePolicy.validate(entry)
        require(entry.campaignId == campaignId) { "PotentialEntry belongs to another campaign" }
        val d = domain(entry.domainUid) ?: error("Progression domain does not exist: ${entry.domainUid}")
        require(d.appliesToPotential) { "Domain ${entry.domainUid} does not support Potential" }
        db.updateOrInsertCompat(
            "UPDATE potential_profile_entries SET base_value=?,entry_version=?,provenance=? WHERE campaign_id=? AND character_uid=? AND domain_uid=? AND dimension_uid=?",
            arrayOf<Any?>(normalizeZero(entry.baseValue),entry.entryVersion,entry.provenance,entry.campaignId,entry.characterUid,entry.domainUid,entry.dimensionUid),
            "INSERT INTO potential_profile_entries(campaign_id,character_uid,domain_uid,dimension_uid,base_value,entry_version,provenance) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(entry.campaignId,entry.characterUid,entry.domainUid,entry.dimensionUid,normalizeZero(entry.baseValue),entry.entryVersion,entry.provenance)
        )
    }

    fun preserveLegacyEvidence(evidence: LegacyProgressionEvidence) {
        ProgressionProfilePolicy.validate(evidence)
        require(evidence.campaignId == campaignId) { "Legacy evidence belongs to another campaign" }
        db.execSQL("INSERT OR IGNORE INTO legacy_progression_evidence(evidence_uid,campaign_id,character_uid,legacy_key,raw_value,source_type,source_uid,source_version,provenance) VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(evidence.evidenceUid,evidence.campaignId,evidence.characterUid,evidence.legacyKey,evidence.rawValue,evidence.sourceType,evidence.sourceUid,evidence.sourceVersion,evidence.provenance))
    }

    fun registerLegacyMapping(mapping: LegacyProgressionMapping) {
        ProgressionProfilePolicy.validate(mapping)
        require(mapping.campaignId == campaignId) { "Legacy mapping belongs to another campaign" }
        val evidence = legacyEvidence(mapping.evidenceUid) ?: error("Legacy progression evidence does not exist: ${mapping.evidenceUid}")
        val d = domain(mapping.domainUid) ?: error("Progression domain does not exist: ${mapping.domainUid}")
        require(d.worldPackUid == mapping.worldPackUid) { "Mapping target owner mismatch" }
        require(if(mapping.axis==ProgressionProfileAxis.TALENT)d.appliesToTalent else d.appliesToPotential) { "Mapping axis not supported by domain" }
        val existing = legacyMapping(mapping.evidenceUid)
        require(existing == null || existing == mapping) { "Legacy progression evidence already mapped incompatibly" }
        if (existing == null) db.execSQL("INSERT INTO legacy_progression_mappings(campaign_id,evidence_uid,axis,domain_uid,dimension_uid,world_pack_uid,mapping_version,provenance) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(mapping.campaignId,mapping.evidenceUid,mapping.axis.name,mapping.domainUid,mapping.dimensionUid,mapping.worldPackUid,mapping.mappingVersion,mapping.provenance))
        require(evidence.characterUid.isNotBlank())
    }

    fun materializeMappedEvidence(evidenceUid: String, parsedBaseValue: Double, entryVersion: Long = 1, provenance: String) {
        require(parsedBaseValue.isFinite() && parsedBaseValue >= 0.0) { "mapped profile value must be finite and >= 0" }
        require(provenance.isNotBlank())
        val evidence = legacyEvidence(evidenceUid) ?: error("Legacy progression evidence does not exist: $evidenceUid")
        val mapping = legacyMapping(evidenceUid) ?: error("Explicit legacy progression mapping required: $evidenceUid")
        when(mapping.axis) {
            ProgressionProfileAxis.TALENT -> saveTalent(TalentEntry(campaignId,evidence.characterUid,mapping.domainUid,parsedBaseValue,entryVersion,provenance))
            ProgressionProfileAxis.POTENTIAL -> savePotential(PotentialEntry(campaignId,evidence.characterUid,mapping.domainUid,mapping.dimensionUid!!,parsedBaseValue,entryVersion,provenance))
        }
    }

    fun unresolvedLegacyEvidence(characterUid: String): List<LegacyProgressionEvidence> {
        val out = mutableListOf<LegacyProgressionEvidence>()
        db.rawQuery("""SELECT e.evidence_uid,e.campaign_id,e.character_uid,e.legacy_key,e.raw_value,e.source_type,e.source_uid,e.source_version,e.provenance FROM legacy_progression_evidence e LEFT JOIN legacy_progression_mappings m ON m.campaign_id=e.campaign_id AND m.evidence_uid=e.evidence_uid WHERE e.campaign_id=? AND e.character_uid=? AND m.evidence_uid IS NULL ORDER BY e.evidence_uid""", arrayOf(campaignId,characterUid)).use { c ->
            while(c.moveToNext()) out += LegacyProgressionEvidence(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getLong(7),c.getString(8))
        }
        return out
    }

    private fun domain(uid:String): ProgressionDomainDefinition? = domains().singleOrNull { it.domainUid==uid }
    private fun legacyEvidence(uid:String): LegacyProgressionEvidence? {
        var out:LegacyProgressionEvidence?=null
        db.rawQuery("SELECT evidence_uid,campaign_id,character_uid,legacy_key,raw_value,source_type,source_uid,source_version,provenance FROM legacy_progression_evidence WHERE campaign_id=? AND evidence_uid=?", arrayOf(campaignId,uid)).use{c->if(c.moveToFirst()) out=LegacyProgressionEvidence(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getLong(7),c.getString(8))}
        return out
    }
    private fun legacyMapping(uid:String): LegacyProgressionMapping? {
        var out:LegacyProgressionMapping?=null
        db.rawQuery("SELECT campaign_id,evidence_uid,axis,domain_uid,dimension_uid,world_pack_uid,mapping_version,provenance FROM legacy_progression_mappings WHERE campaign_id=? AND evidence_uid=?", arrayOf(campaignId,uid)).use{c->if(c.moveToFirst()) out=LegacyProgressionMapping(c.getString(0),c.getString(1),ProgressionProfileAxis.valueOf(c.getString(2)),c.getString(3),if(c.isNull(4))null else c.getString(4),c.getString(5),c.getLong(6),c.getString(7))}
        return out
    }
    private fun normalizeZero(v:Double)=if(v==0.0)0.0 else v
    private inline fun inTransaction(block:()->Unit){db.beginTransaction();try{block();db.setTransactionSuccessful()}finally{db.endTransaction()}}
}
