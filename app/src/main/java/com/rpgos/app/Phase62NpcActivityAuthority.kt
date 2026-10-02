package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Administrative World Pack definitions, not AI output and not a second activity engine. */
internal object Phase62ActivitySchema {
    const val TABLE="rpgos_npc_activity_definitions"
    val tables=setOf(TABLE)
    fun ensureReady(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE(
            campaign_uid TEXT NOT NULL,rule_uid TEXT NOT NULL,rule_version INTEGER NOT NULL CHECK(rule_version>0),
            capability_uid TEXT NOT NULL,contract_json TEXT NOT NULL,contract_fingerprint TEXT NOT NULL,
            active INTEGER NOT NULL CHECK(active IN(0,1)),provenance_uid TEXT NOT NULL,
            PRIMARY KEY(campaign_uid,rule_uid,rule_version))""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_npc_activity_capability ON $TABLE(campaign_uid,capability_uid,active)")
        db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES(?,strftime('%s','now'),?)",
            arrayOf("RPGOS-62.2-NPC-ACTIVITY-DEFINITIONS","Versioned domain-owned NPC activity contracts; administrative World Pack import only"))
    }
    fun isReady(db:SQLiteDatabase)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(TABLE)).use{it.moveToFirst()}
}

internal object NpcActivityContractCodec {
    fun encode(c:NpcActivityContract):String=JSONObject().apply {
        put("capability",c.capabilityUid);put("rule",c.ruleUid);put("version",c.version)
        put("duration_ms",c.duration.milliseconds);put("effort_track",c.effortTrackUid);put("effort_units",c.effortUnits)
        put("eligibility",c.eligibility.name)
        put("traits",JSONArray().apply{c.traitPreferences.sortedBy{it.traitUid}.forEach{t->put(JSONObject().apply{
            put("uid",t.traitUid);put("preferred",t.preferred.basisPoints);put("importance",t.weight.basisPoints)})}})
        put("motivations",JSONObject(c.motivationalDomains.mapValues{it.value.basisPoints}))
        put("values",JSONObject(c.valuePreferences.mapValues{it.value.basisPoints}))
        put("requirements",JSONObject().apply {
            put("costs",JSONObject(c.requirements.resourceCosts));put("tools",JSONArray(c.requirements.toolInstanceUids.sorted()))
            put("knowledge",JSONArray(c.requirements.knowledgeClaimUids.sorted()))
            c.requirements.teacher?.let{put("teacher_kind",it.kindUid);put("teacher",it.uid)}
        })
        c.resourceRecovery?.let{put("recovery",JSONObject().put("resource",it.resourceUid).put("units",it.maximumUnits))}
        c.learning?.let{put("learning",JSONObject().put("kind",it.targetKindUid).put("target",it.targetUid)
            .put("semantics",it.progressSemanticsUid).put("effort",it.effortUnits).put("minimum",it.minimumMastery))}
        c.reading?.let{r->put("reading",JSONObject().put("carrier_kind",r.carrier.kindUid).put("carrier",r.carrier.uid)
            .put("policy",r.accessPolicyUid).put("reliability",r.sourceReliability).put("claim",JSONObject().apply{
                put("uid",r.claim.claimUid);put("subject_kind",r.claim.subjectKindUid);put("subject",r.claim.subjectUid)
                put("predicate",r.claim.predicateUid);put("value",r.claim.valueCanonical);put("domain",r.claim.domainUid)
                r.claim.objectKindUid?.let{put("object_kind",it);put("object",r.claim.objectUid)}
            }))}
        c.treatment?.let{r->put("treatment",JSONObject().put("resources",JSONObject(r.resourceRecovery)).put("wound",r.woundHealingUnits)
            .put("conditions",JSONArray(r.removedConditionUids.sorted())).put("range_mm",r.maximumRangeMillimetres)
            .put("threshold",r.successThreshold).apply{r.stabilizationConditionUid?.let{put("stabilization",it)};r.successAttributeUid?.let{put("attribute",it)}})}
        c.duty?.let{d->put("duty",JSONObject().put("uid",d.dutyUid).put("version",d.version).put("organization",d.organizationUid)
            .put("role",d.roleUid).put("deadline",d.deadlineUid).put("due_ms",d.due.milliseconds).put("policy",d.assignmentPolicyUid))}
        if(c.resultCriteria.isNotEmpty())put("results",JSONArray().apply{c.resultCriteria.forEach{r->put(JSONObject().put("kind",r.kind.name)
            .put("target_kind",r.target.kindUid).put("target",r.target.uid).put("value",r.valueUid).put("minimum",r.minimum))}})
    }.toString()
    fun decode(text:String):NpcActivityContract {
        require(text.length<=32768){"P62:ACTIVITY_DEFINITION_BUDGET"}
        val o=JSONObject(text)
        fun affects(key:String):Map<String,NpcAffect> = o.optJSONObject(key)?.let{m->m.keys().asSequence().associateWith{NpcAffect(m.getInt(it))}}?:emptyMap()
        val traits=o.optJSONArray("traits")?.let{a->(0 until a.length()).map{i->a.getJSONObject(i).let{
            NpcTraitPreference(it.getString("uid"),NpcWeight(it.getInt("preferred")),NpcWeight(it.getInt("importance")))}}}?:emptyList()
        val q=o.optJSONObject("requirements")
        fun strings(key:String)=q?.optJSONArray(key)?.let{a->(0 until a.length()).map{a.getString(it)}.toSet()}?:emptySet()
        val costs=q?.optJSONObject("costs")?.let{m->m.keys().asSequence().associateWith{m.getLong(it)}}?:emptyMap()
        return NpcActivityContract(o.getString("capability"),o.getString("rule"),o.getInt("version"),
            ActionDuration(o.getLong("duration_ms")),o.getString("effort_track"),o.getLong("effort_units"),
            traits,affects("motivations"),affects("values"),NpcActivityEligibility.valueOf(o.getString("eligibility")),
            o.optJSONObject("recovery")?.let{NpcActivityResourceRecovery(it.getString("resource"),it.getLong("units"))},
            o.optJSONObject("learning")?.let{NpcLearningRule(it.getString("kind"),it.getString("target"),it.getString("semantics"),it.getLong("effort"),it.getDouble("minimum"))},
            o.optJSONObject("reading")?.let{r->val claim=r.getJSONObject("claim")
                NpcReadingRule(DomainRef(r.getString("carrier_kind"),r.getString("carrier")),r.getString("policy"),
                    KnowledgeClaim(claim.getString("uid"),claim.getString("subject_kind"),claim.getString("subject"),claim.getString("predicate"),claim.getString("value"),
                        claim.optString("object_kind").takeIf{it.isNotBlank()},claim.optString("object").takeIf{it.isNotBlank()},claim.getString("domain")),r.getDouble("reliability"))},
            NpcActivityRequirements(costs,strings("tools"),strings("knowledge"),q?.optString("teacher")?.takeIf{it.isNotBlank()}?.let{
                DomainRef(requireNotNull(q).getString("teacher_kind"),it)}),
            o.optJSONObject("treatment")?.let{t->val r=t.getJSONObject("resources");val conditions=t.getJSONArray("conditions")
                NpcTreatmentRule(r.keys().asSequence().associateWith{r.getLong(it)},t.getLong("wound"),
                    (0 until conditions.length()).map{conditions.getString(it)}.toSet(),t.optString("stabilization").takeIf{it.isNotBlank()},
                    t.getLong("range_mm"),t.optString("attribute").takeIf{it.isNotBlank()},t.getLong("threshold"))},
            o.optJSONObject("duty")?.let{d->NpcDutyRule(d.getString("uid"),d.getInt("version"),d.getString("organization"),d.getString("role"),
                d.getString("deadline"),WorldTimeTick(d.getLong("due_ms")),d.getString("policy"))},
            o.optJSONArray("results")?.let{a->require(a.length()<=8);(0 until a.length()).map{i->a.getJSONObject(i).let{r->
                NpcWorldResultCriterion(NpcWorldResultKind.valueOf(r.getString("kind")),DomainRef(r.getString("target_kind"),r.getString("target")),
                    r.getString("value"),r.getDouble("minimum"))}}}?:emptyList())
    }
}

internal class SqliteNpcActivityContractPort(private val db:SQLiteDatabase,
    private val fallback:NpcActivityContractPort=NpcActivityContractPort.STANDARD):NpcActivityContractPort {
    override fun contract(campaignUid:String,capabilityUid:String)=forCapability(campaignUid,capabilityUid).singleOrNull()
    override fun inherent(campaignUid:String)=fallback.inherent(campaignUid)
    override fun forCapability(campaignUid:String,capabilityUid:String):List<NpcActivityContract> {
        if(!Phase62ActivitySchema.isReady(db))return fallback.forCapability(campaignUid,capabilityUid)
        val rules=db.rawQuery("SELECT rule_uid,rule_version,contract_json,contract_fingerprint FROM ${Phase62ActivitySchema.TABLE} WHERE campaign_uid=? AND capability_uid=? AND active=1 ORDER BY rule_uid,rule_version",
            arrayOf(campaignUid,capabilityUid)).use{c->buildList{while(c.moveToNext()){
                val rule=NpcActivityContractCodec.decode(c.getString(2))
                require(rule.ruleUid==c.getString(0) && rule.version==c.getInt(1) && rule.capabilityUid==capabilityUid && rule.fingerprint==c.getString(3)){"P62:ACTIVITY_DEFINITION_BINDING"}
                add(rule)
            }}}
        require(rules.size<=64){"P62:ACTIVITY_CATALOG_BUDGET"}
        // Explicit World Pack rules replace the effort-only fallback for the same capability.
        return rules.ifEmpty{fallback.forCapability(campaignUid,capabilityUid)}
    }
}

/** Invoked only by the production administrative pack bootstrap. A missing table supplies no
 * reward rule. A same-version changed definition fails rather than silently changing a contract. */
internal object NpcActivityDefinitionImport {
    fun importPack(save:SQLiteDatabase,world:SQLiteDatabase,campaign:String,binding:WorldPackRuleBinding) {
        val exists=world.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='npc_activity_definitions'",null).use{it.moveToFirst()}
        if(!exists)return
        world.rawQuery("SELECT contract_json,rule_uid,rule_version FROM npc_activity_definitions ORDER BY rule_uid,rule_version LIMIT 1025",null).use{c->
            var count=0
            while(c.moveToNext()) {
                require(++count<=1024){"P62:PACK_ACTIVITY_BUDGET"}
                val contract=NpcActivityContractCodec.decode(c.getString(0))
                require(contract.ruleUid==c.getString(1) && contract.version==c.getInt(2)){"P62:PACK_ACTIVITY_ROW_BINDING"}
                val old=save.rawQuery("SELECT contract_fingerprint FROM ${Phase62ActivitySchema.TABLE} WHERE campaign_uid=? AND rule_uid=? AND rule_version=?",
                    arrayOf(campaign,contract.ruleUid,contract.version.toString())).use{if(it.moveToFirst())it.getString(0) else null}
                require(old==null || old==contract.fingerprint){"P62:PACK_ACTIVITY_VERSION_REUSED"}
                save.execSQL("UPDATE ${Phase62ActivitySchema.TABLE} SET active=0 WHERE campaign_uid=? AND rule_uid=? AND rule_version<?",
                    arrayOf(campaign,contract.ruleUid,contract.version))
                val newer=save.rawQuery("SELECT 1 FROM ${Phase62ActivitySchema.TABLE} WHERE campaign_uid=? AND rule_uid=? AND rule_version>? LIMIT 1",
                    arrayOf(campaign,contract.ruleUid,contract.version.toString())).use{it.moveToFirst()}
                save.execSQL("INSERT OR IGNORE INTO ${Phase62ActivitySchema.TABLE}(campaign_uid,rule_uid,rule_version,capability_uid,contract_json,contract_fingerprint,active,provenance_uid) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(campaign,contract.ruleUid,contract.version,contract.capabilityUid,NpcActivityContractCodec.encode(contract),contract.fingerprint,if(newer)0 else 1,
                        "WORLD-PACK:${binding.worldPackUid}:${binding.worldPackVersion}"))
            }
        }
    }
}
