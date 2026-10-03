package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.SecureRandom
import kotlinx.serialization.json.*

data class NativeWorldCreationSpec(val name:String,val description:String,val era:String,val startingPlace:String,
    val startingCategory:String?=null) {
    init {
        require(name.isNotBlank() && name.length<=120)
        require(description.isNotBlank() && description.length<=4096)
        require(era.isNotBlank() && era.length<=120 && startingPlace.isNotBlank() && startingPlace.length<=240)
        require(startingCategory==null || startingCategory in LatentWorldGeography.nativeCategories)
    }
}

/** Campaign-native authority is an explicit v2 source, never a fictional World Pack. */
internal object NativeCampaignRuleManifest {
    const val RULE_VERSION="CORE-NATIVE-1"
    fun binding(manifest:File,campaignUid:String):WorldPackRuleBinding? {
        if(!manifest.isFile)return null
        val root=Json.parseToJsonElement(manifest.readText()).jsonObject
        val source=root["rule_source"]?.jsonObject?:return null
        Phase63WorldCodec.keys(JsonObject(source-"starting_category"),"contract","kind","uid","version","seed","description","era","starting_place")
        require(Phase63WorldCodec.number(source,"contract")==2L)
        require(Phase63WorldCodec.text(source,"kind")==CampaignRuleSourceKind.CAMPAIGN_NATIVE.name)
        require(Phase63WorldCodec.text(source,"uid")==campaignUid && Phase63WorldCodec.text(source,"version")==RULE_VERSION)
        require(Phase63WorldCodec.text(source,"seed").matches(Regex("[0-9a-f]{64}")))
        NativeWorldCreationSpec(Phase63WorldCodec.text(root,"name"),Phase63WorldCodec.text(source,"description"),
            Phase63WorldCodec.text(source,"era"),Phase63WorldCodec.text(source,"starting_place"),source["starting_category"]?.let(Phase63WorldCodec::string))
        // Bind the actual immutable configuration, not merely its human-readable rule version.
        return WorldPackRuleBinding(campaignUid,"$RULE_VERSION:${phase63Hash(canonicalSource(source).toString())}",CampaignRuleSourceKind.CAMPAIGN_NATIVE)
    }
    private fun canonicalSource(source:JsonObject)=JsonObject(source.toSortedMap())
    fun manifest(campaignUid:String,spec:NativeWorldCreationSpec)=buildJsonObject {
        put("id",campaignUid);put("name",spec.name);put("version","1");put("core_api","1")
        put("rule_source",buildJsonObject {
            put("contract",2);put("kind",CampaignRuleSourceKind.CAMPAIGN_NATIVE.name)
            put("uid",campaignUid);put("version",RULE_VERSION)
            put("seed",ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) })
            put("description",spec.description);put("era",spec.era);put("starting_place",spec.startingPlace)
            spec.startingCategory?.let { put("starting_category",it) }
        })
    }
    fun skeleton(manifest:File,campaignUid:String):CampaignWorldSkeleton {
        val binding=requireNotNull(binding(manifest,campaignUid))
        val source=Json.parseToJsonElement(manifest.readText()).jsonObject.getValue("rule_source").jsonObject
        val anchor=DomainRef("PLACE","P63-START-${phase63Hash(campaignUid).take(24).uppercase()}")
        val category=source["starting_category"]?.let(Phase63WorldCodec::string)
        return CampaignWorldSkeleton(campaignUid,Phase63WorldCodec.text(source,"seed"),1,binding.ruleSource,
            Phase63WorldCodec.text(source,"era"),anchor,setOf(anchor.uid),buildMap {
                put("WORLD_PREMISE",Phase63WorldCodec.text(source,"description"))
                category?.let { put("INITIAL_CATEGORY",it) }
            },"P63:EXPLICIT_NEW_WORLD_CONFIGURATION",CoreLatentWorldRules.initial(),LatentWorldGeography.nativeRules(category))
    }
}

/** Only used in an unpublished staging DB; ordinary opens never manufacture a starting world. */
internal object NativeCampaignBootstrap {
    fun ensureBaseSchema(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,
            last_updated_day INTEGER NOT NULL DEFAULT 0,updated_chapter INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER NOT NULL,
            year_number INTEGER,year_label TEXT,era_key TEXT,era_name TEXT,season TEXT,hour INTEGER,minute INTEGER,
            canon_anchor_event_uid TEXT,updated_chapter INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE active_combat_effects(active_effect_uid TEXT PRIMARY KEY,entity_uid TEXT NOT NULL,
            effect_key TEXT NOT NULL,magnitude REAL NOT NULL,started_chapter INTEGER NOT NULL,status TEXT NOT NULL,
            remaining_duration_sec INTEGER DEFAULT 0)""")
    }
    fun initialize(db:SQLiteDatabase,manifest:File,campaignUid:String) {
        check(db.rawQuery("SELECT 1 FROM ${Phase63WorldSchema.ROOTS} LIMIT 1",null).use { !it.moveToFirst() })
        val skeleton=NativeCampaignRuleManifest.skeleton(manifest,campaignUid)
        val config=Json.parseToJsonElement(manifest.readText()).jsonObject.getValue("rule_source").jsonObject
        withAdministrativeMutationAuthority(db,campaignUid) {
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO ${Phase63WorldSchema.ROOTS} VALUES(?,?,?,?,?)",arrayOf(campaignUid,1L,
                    Phase63WorldCodec.skeleton(skeleton).toString(),skeleton.fingerprint,0L))
                db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,?,'CAMPAIGN_NATIVE',?,'unspecified',8,0,NULL,0)",
                    arrayOf<Any?>("Rok 1",skeleton.era))
                val facts=linkedMapOf(CampaignWorldFacts.KIND to "PLACE",CampaignWorldFacts.NAME to Phase63WorldCodec.text(config,"starting_place"),
                    CampaignWorldFacts.CATEGORY to (config["starting_category"]?.let(Phase63WorldCodec::string)?:"STARTING_PLACE"),CampaignWorldFacts.TOPOLOGY to "LOCAL_SITE",
                    CampaignWorldFacts.AUDIENCE_SCOPE to CampaignWorldAudience.PLAYER_VISIBLE,
                    CampaignWorldFacts.MATERIALIZATION_LEVEL to "PARTIAL",CampaignWorldFacts.SOURCE_CLASSIFICATION to WorldEvidenceClassification.CAMPAIGN_FACT.name)
                facts.forEach { (predicate,value)->CampaignTruthStore(db,campaignUid).record(TruthKind.FACT,predicate,
                    Provenance(ProvenanceSourceType.PLAYER_ACTION,sourceId="P63:WORLD_CREATOR",createdTurn=0,
                        verified=true,method="EXPLICIT_CAMPAIGN_BOOTSTRAP",engineVersion="RPGOS-P63:1"),
                    subjectUid=skeleton.initialAnchor.uid,objectValue=value,
                    truthUid="P63-BOOT:${phase63Hash("$campaignUid|$predicate").take(32)}",createdAt=0) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
}

/** Compatibility view for read-only legacy UI consumers. It is disposable, not a pack.
 * Technique presentation comes from this campaign's registered neutral definitions, never
 * from Naruto's catalog or generated prose. A malformed authoritative table still fails. */
internal object NativeWorldReadDatabase {
    fun open(save:SQLiteDatabase,campaignUid:String):SQLiteDatabase=SQLiteDatabase.create(null).also { view->
        view.execSQL("CREATE TABLE map_locations_v2(location_uid TEXT PRIMARY KEY,name TEXT,location_type TEXT,region_uid TEXT,description TEXT,presentation_priority INTEGER NOT NULL DEFAULT 0)")
        view.execSQL("CREATE TABLE map_regions_v2(region_uid TEXT PRIMARY KEY,name TEXT,region_type TEXT,description TEXT)")
        view.execSQL("CREATE TABLE canon_characters_v2(character_uid TEXT PRIMARY KEY,name TEXT NOT NULL)")
        view.execSQL("CREATE TABLE canon_technique_index(technique_uid TEXT PRIMARY KEY,name TEXT NOT NULL,category TEXT NOT NULL,rank TEXT,element_key TEXT,wiki_url TEXT,verification_status TEXT)")
        save.rawQuery("SELECT technique_uid,display_name,category FROM technique_definitions_v2 WHERE world_pack_uid=? AND definition_status='ACTIVE' ORDER BY technique_uid LIMIT 500",
            arrayOf(campaignUid)).use { cursor->
            while(cursor.moveToNext())view.execSQL("INSERT INTO canon_technique_index VALUES(?,?,?,NULL,NULL,NULL,'REGISTERED_CORE')",
                arrayOf(cursor.getString(0),cursor.getString(1),cursor.getString(2)))
        }
        val projection=CampaignWorldProjectionStore(save,campaignUid)
        // A bounded UI page must never hide the current/start anchor just because many
        // DYN-* identities sort before it. Exact owner reads remain first and public-only.
        val anchorUids=buildSet {
            Phase63WorldStore(save,campaignUid).root()?.skeleton?.initialAnchor?.uid?.let(::add)
            ActivePlayerStore(save,campaignUid).active()?.let { player->
                save.rawQuery("SELECT location_uid FROM entity_positions WHERE entity_uid=?",arrayOf(player.playerUid)).use { cursor->
                    if(cursor.moveToFirst() && !cursor.isNull(0))add(cursor.getString(0))
                }
            }
        }
        val anchors=anchorUids.mapNotNull(projection::canonicalElement).filter { it.audienceScopeUid==CampaignWorldAudience.PLAYER_VISIBLE }
        (anchors+projection.canonicalPublicElements()).distinctBy { it.element }.forEach { element->
            when(element.element.kindUid) {
                "PLACE"->view.execSQL("INSERT INTO map_locations_v2 VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(element.element.uid,element.displayName,element.categoryUid,element.parentAnchorUid.orEmpty(),"",if(element.element.uid in anchorUids)1 else 0))
                "ACTOR"->view.execSQL("INSERT INTO canon_characters_v2 VALUES(?,?)",arrayOf(element.element.uid,element.displayName))
            }
        }
        view.execSQL("PRAGMA query_only=ON")
    }
}
