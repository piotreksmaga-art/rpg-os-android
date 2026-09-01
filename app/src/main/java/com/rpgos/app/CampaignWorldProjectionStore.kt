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

    fun searchPlayerVisible(phrase:String,shape:WorldReferenceShape,limit:Int=128):List<CampaignWorldElement>{
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
        return db.rawQuery("""SELECT element_kind_uid,element_uid,display_name,category_uid,parent_anchor_uid,
            affordance_uids,topology_class_uid,source_classification_uid,audience_scope_uid,source_version
            FROM ${CampaignWorldProjectionSchema.TABLE} WHERE ${clauses.joinToString(" AND ")}
            ORDER BY CASE WHEN parent_anchor_uid IS NULL THEN 1 ELSE 0 END,parent_anchor_uid,element_uid LIMIT ${limit.coerceIn(1,512)}""",
            args.toTypedArray()).use{cursor->buildList{
                while(cursor.moveToNext()){
                    val affordances=cursor.getString(5).split('\u001f').filter(String::isNotBlank).toSet()
                    val exactIdentity=worldNamesEquivalent(cursor.getString(2),phrase)
                    val compatibleCategory=shape.categoryUid==cursor.getString(3)
                    if(!exactIdentity&&!compatibleCategory)continue
                    // A stable player-visible name is stronger identity evidence than a provider's
                    // non-authoritative affordance wording. Affordances only narrow category matches.
                    if(!exactIdentity&&!affordances.containsAll(shape.affordanceUids))continue
                    val classification=runCatching{WorldEvidenceClassification.valueOf(cursor.getString(7))}.getOrDefault(WorldEvidenceClassification.CAMPAIGN_FACT)
                    add(CampaignWorldElement(
                        DomainRef(cursor.getString(0),cursor.getString(1)),cursor.getString(2),cursor.getString(3),
                        if(cursor.isNull(4))null else cursor.getString(4),affordances,cursor.getString(6),classification,
                        cursor.getString(8),cursor.getLong(9)
                    ))
                }
            }}
    }
}
