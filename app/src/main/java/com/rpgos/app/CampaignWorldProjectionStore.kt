package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val CAMPAIGN_WORLD_PROJECTION_MIGRATION_ID = "RPGOS-WORLD-MODEL-1.0"

/**
 * Rebuildable, player-safe read model of universal world elements. CampaignTruth remains the
 * authority; this table only makes exact lookup scale independently of the number of truth rows.
 */
internal object CampaignWorldProjectionSchema {
    const val TABLE = "campaign_world_elements_projection"

    fun ensureReady(db:SQLiteDatabase,campaignUid:String?=null) {
        val requiresRebuild=!isReady(db)||!db.rawQuery(
            "SELECT 1 FROM rpgos_schema_migrations WHERE migration_id=? LIMIT 1",arrayOf(CAMPAIGN_WORLD_PROJECTION_MIGRATION_ID)
        ).use{it.moveToFirst()}
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE(
            campaign_id TEXT NOT NULL,
            element_uid TEXT NOT NULL,
            element_kind_uid TEXT,
            display_name TEXT,
            normalized_display_name TEXT,
            category_uid TEXT,
            parent_anchor_uid TEXT,
            affordance_uids TEXT NOT NULL DEFAULT '',
            topology_class_uid TEXT,
            source_classification_uid TEXT,
            audience_scope_uid TEXT,
            materialization_level_uid TEXT,
            source_version INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(campaign_id,element_uid))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_world_projection_name ON $TABLE(campaign_id,audience_scope_uid,normalized_display_name,element_kind_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_world_projection_category ON $TABLE(campaign_id,audience_scope_uid,category_uid,parent_anchor_uid,element_kind_uid)")
        db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$CAMPAIGN_WORLD_PROJECTION_MIGRATION_ID',strftime('%s','now'),'Rebuildable indexed public Campaign World Model derived only from typed CampaignTruth facts')")
        if(requiresRebuild&&!campaignUid.isNullOrBlank())CampaignWorldProjectionStore(db,campaignUid).rebuild()
    }

    fun isReady(db:SQLiteDatabase):Boolean=db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(TABLE)
    ).use{it.moveToFirst()}
}

internal class CampaignWorldProjectionStore(
    private val db:SQLiteDatabase,
    private val campaignUid:String
) {
    init{require(campaignUid.isNotBlank())}

    /** A cache hit is only a UID. Identity, visibility and kind are rehydrated from the
     * canonical owner; cache corruption cannot promote BELIEF or disclose a hidden element. */
    fun canonicalElement(subjectUid:String):CampaignWorldElement? {
        val facts=db.rawQuery("""SELECT predicate,object_value,created_at FROM campaign_truth_records
            WHERE campaign_id=? AND subject_uid=? AND truth_kind='FACT' AND active=1
              AND predicate LIKE 'RPGOS-WORLD:%' ORDER BY created_at,truth_uid""",
            arrayOf(campaignUid,subjectUid)).use { c->buildList {
                while(c.moveToNext())add(Triple(c.getString(0),if(c.isNull(1))null else c.getString(1),c.getLong(2)))
            } }
        fun latest(key:String)=facts.lastOrNull { it.first==key }?.second
        val kind=latest(CampaignWorldFacts.KIND)?:return null
        if(latest(CampaignWorldFacts.AUDIENCE_SCOPE)!=CampaignWorldAudience.PLAYER_VISIBLE)return null
        val name=latest(CampaignWorldFacts.NAME)?:return null
        val category=latest(CampaignWorldFacts.CATEGORY)?:return null
        val topology=latest(CampaignWorldFacts.TOPOLOGY)?:return null
        val classification=latest(CampaignWorldFacts.SOURCE_CLASSIFICATION)?.let {
            runCatching { WorldEvidenceClassification.valueOf(it) }.getOrNull()
        }?:return null
        return CampaignWorldElement(DomainRef(kind,subjectUid),name,category,latest(CampaignWorldFacts.PARENT),
            facts.filter { it.first==CampaignWorldFacts.AFFORDANCE }.mapNotNull { it.second }.toSet(),topology,
            classification,CampaignWorldAudience.PLAYER_VISIBLE,facts.maxOf { it.third })
    }

    /** Bounded canonical enumeration for rebuilding disposable native-world presentation.
     * No read repairs a table or grants actor knowledge. */
    fun canonicalPublicElements(limit:Int=512):List<CampaignWorldElement> {
        require(limit in 1..512)
        val uids=db.rawQuery("""SELECT DISTINCT subject_uid FROM campaign_truth_records
            WHERE campaign_id=? AND truth_kind='FACT' AND active=1 AND predicate=? AND object_value=?
              AND subject_uid IS NOT NULL ORDER BY subject_uid LIMIT ?""",
            arrayOf(campaignUid,CampaignWorldFacts.AUDIENCE_SCOPE,CampaignWorldAudience.PLAYER_VISIBLE,limit.toString()))
            .use { c->buildList { while(c.moveToNext())add(c.getString(0)) } }
        return uids.mapNotNull(::canonicalElement)
    }

    /** Must be called in the same transaction that committed the canonical truth record. */
    fun refreshSubject(subjectUid:String) {
        if(!CampaignWorldProjectionSchema.isReady(db))return
        val facts=db.rawQuery(
            """SELECT predicate,object_value,created_at FROM campaign_truth_records
                WHERE campaign_id=? AND subject_uid=? AND truth_kind='FACT' AND active=1
                  AND predicate LIKE 'RPGOS-WORLD:%'
                ORDER BY created_at,truth_uid""",arrayOf(campaignUid,subjectUid)
        ).use{cursor->buildList{
            while(cursor.moveToNext())add(Triple(cursor.getString(0),if(cursor.isNull(1))null else cursor.getString(1),cursor.getLong(2)))
        }}
        val relevant=facts.filter{it.first in CampaignWorldFacts.ALL}
        if(relevant.isEmpty()){
            db.delete(CampaignWorldProjectionSchema.TABLE,"campaign_id=? AND element_uid=?",arrayOf(campaignUid,subjectUid));return
        }
        fun latest(predicate:String)=relevant.lastOrNull{it.first==predicate}?.second
        val kind=latest(CampaignWorldFacts.KIND)
        val name=latest(CampaignWorldFacts.NAME)
        val category=latest(CampaignWorldFacts.CATEGORY)
        val affordances=relevant.filter{it.first==CampaignWorldFacts.AFFORDANCE}.mapNotNull{it.second}.filter(String::isNotBlank).distinct().sorted()
        db.execSQL("""INSERT OR IGNORE INTO ${CampaignWorldProjectionSchema.TABLE}
            (campaign_id,element_uid,affordance_uids,source_version) VALUES(?,?,?,?)""",
            arrayOf<Any?>(campaignUid,subjectUid,"",0L))
        db.execSQL("""UPDATE ${CampaignWorldProjectionSchema.TABLE} SET
            element_kind_uid=?,display_name=?,normalized_display_name=?,category_uid=?,parent_anchor_uid=?,
            affordance_uids=?,topology_class_uid=?,source_classification_uid=?,audience_scope_uid=?,
            materialization_level_uid=?,source_version=? WHERE campaign_id=? AND element_uid=?""",
            arrayOf<Any?>(
                kind,name,name?.let(::normalizedWorldText),category,latest(CampaignWorldFacts.PARENT),
                affordances.joinToString("\u001f"),latest(CampaignWorldFacts.TOPOLOGY),
                latest(CampaignWorldFacts.SOURCE_CLASSIFICATION),latest(CampaignWorldFacts.AUDIENCE_SCOPE),
                latest(CampaignWorldFacts.MATERIALIZATION_LEVEL),relevant.maxOf{it.third},campaignUid,subjectUid
            ))
    }

    fun rebuild() {
        check(CampaignWorldProjectionSchema.isReady(db))
        db.delete(CampaignWorldProjectionSchema.TABLE,"campaign_id=?",arrayOf(campaignUid))
        var after=""
        while(true){
            val subjects=db.rawQuery("""SELECT DISTINCT subject_uid FROM campaign_truth_records
                WHERE campaign_id=? AND subject_uid IS NOT NULL AND subject_uid>? AND truth_kind='FACT' AND active=1
                  AND predicate LIKE 'RPGOS-WORLD:%' ORDER BY subject_uid LIMIT 500""",arrayOf(campaignUid,after)).use{cursor->
                buildList{while(cursor.moveToNext())add(cursor.getString(0))}
            }
            if(subjects.isEmpty())break
            subjects.forEach(::refreshSubject)
            after=subjects.last()
        }
    }

    fun searchPlayerVisible(phrase:String,shape:WorldReferenceShape,limit:Int=128,requireAffordances:Boolean=true):List<CampaignWorldElement>{
        check(CampaignWorldProjectionSchema.isReady(db))
        val normalized=normalizedWorldText(phrase)
        val firstWord=normalized.substringBefore(' ')
        val lookupPrefix=when{
            firstWord.length>=6->firstWord.dropLast(2)
            firstWord.length>=4->firstWord.dropLast(1)
            else->firstWord
        }
        val clauses=mutableListOf(
            "campaign_id=?","audience_scope_uid=?","element_kind_uid IS NOT NULL","display_name IS NOT NULL",
            "category_uid IS NOT NULL","topology_class_uid IS NOT NULL"
        )
        val args=mutableListOf(campaignUid,CampaignWorldAudience.PLAYER_VISIBLE)
        clauses+=if(shape.categoryUid!=null){
            args+=normalized;args+="$lookupPrefix%";args+=shape.categoryUid
            "(normalized_display_name=? OR normalized_display_name LIKE ? OR category_uid=?)"
        }else{
            args+=normalized;args+="$lookupPrefix%"
            "(normalized_display_name=? OR normalized_display_name LIKE ?)"
        }
        clauses+="element_kind_uid=?";args+=shape.baseKind.name
        val cached=db.rawQuery("""SELECT element_kind_uid,element_uid,display_name,category_uid,parent_anchor_uid,
            affordance_uids,topology_class_uid,source_classification_uid,audience_scope_uid,source_version
            FROM ${CampaignWorldProjectionSchema.TABLE} WHERE ${clauses.joinToString(" AND ")}
            ORDER BY CASE WHEN parent_anchor_uid IS NULL THEN 1 ELSE 0 END,parent_anchor_uid,element_uid LIMIT ${limit.coerceIn(1,512)}""",
            args.toTypedArray()).use{cursor->buildList{
                while(cursor.moveToNext()){
                    val canonical=canonicalElement(cursor.getString(1))?:continue
                    if(canonical.element.kindUid!=shape.baseKind.name ||
                        (!worldNamesEquivalent(canonical.displayName,phrase) &&
                            WorldCategoryVocabulary.canonical(canonical.categoryUid)!=shape.categoryUid))continue
                    if(requireAffordances && !worldNamesEquivalent(canonical.displayName,phrase) &&
                        !canonical.affordanceUids.containsAll(shape.affordanceUids))continue
                    add(canonical)
                }
            }}
        // Cache annihilation cannot make established elements disappear and create duplicates.
        // This bounded canonical fallback never writes a cache or grants actor knowledge.
        val categories=shape.categoryUid?.let(WorldCategoryVocabulary::equivalentCategories).orEmpty().sorted()
        val matchArgs=mutableListOf(campaignUid,CampaignWorldFacts.AUDIENCE_SCOPE,CampaignWorldAudience.PLAYER_VISIBLE,
            CampaignWorldFacts.NAME,"$lookupPrefix%")
        val categoryClause=if(categories.isEmpty())"" else {
            matchArgs+=CampaignWorldFacts.CATEGORY;matchArgs+=categories
            " OR (m.predicate=? AND m.object_value IN (${categories.joinToString(",") { "?" }}))"
        }
        matchArgs+=limit.coerceIn(1,512).toString()
        val canonicalUids=db.rawQuery("""SELECT DISTINCT p.subject_uid FROM campaign_truth_records p
            JOIN campaign_truth_records m ON m.campaign_id=p.campaign_id AND m.subject_uid=p.subject_uid
            WHERE p.campaign_id=? AND p.truth_kind='FACT' AND p.active=1 AND p.predicate=? AND p.object_value=?
              AND m.truth_kind='FACT' AND m.active=1 AND ((m.predicate=? AND lower(m.object_value) LIKE lower(?))$categoryClause)
            ORDER BY p.subject_uid LIMIT ?""",matchArgs.toTypedArray()).use { c->buildList { while(c.moveToNext())add(c.getString(0)) } }
        return (cached+canonicalUids.mapNotNull(::canonicalElement)).distinctBy { it.element }.filter { canonical->
            val exact=worldNamesEquivalent(canonical.displayName,phrase)
            canonical.element.kindUid==shape.baseKind.name &&
                (exact || shape.categoryUid==WorldCategoryVocabulary.canonical(canonical.categoryUid)) &&
                (!requireAffordances || exact || canonical.affordanceUids.containsAll(shape.affordanceUids))
        }.sortedBy { it.element.uid }.take(limit.coerceIn(1,512))
    }
}
