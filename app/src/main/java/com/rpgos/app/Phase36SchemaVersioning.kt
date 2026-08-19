package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import java.security.MessageDigest

enum class SchemaFamilyUid {
    ENGINE, CAMPAIGN, WORLD_PACK, PLAYER, RECEIPT, EVENT, CAUSAL, SNAPSHOT, REPLAY,
    CANON_DIVERGENCE, FINANCE, INVENTORY, OWNERSHIP, DEVELOPMENT_PROJECT
}

enum class MigrationMateriality { STRUCTURAL_ADDITIVE, MATERIAL_DATA_MUTATION }

data class SchemaFamilyContract(
    val family: SchemaFamilyUid,
    val currentVersion: Int,
    val minimumSupportedVersion: Int,
    val dependencies: Set<SchemaFamilyUid> = emptySet(),
    val materiality: MigrationMateriality = MigrationMateriality.STRUCTURAL_ADDITIVE
)

class UnsupportedFutureSchemaException(val family: SchemaFamilyUid,val found:Int,val maximum:Int):
    IllegalStateException("RPGOS-SCHEMA:UNSUPPORTED_FUTURE:$family:$found:$maximum")
class MigrationPlanMismatchException(val stored:String,val current:String):
    IllegalStateException("RPGOS-SCHEMA:MIGRATION_PLAN_MISMATCH:$stored:$current")
class MigrationEvidenceCorruptException(code:String):IllegalStateException("RPGOS-SCHEMA:MIGRATION_EVIDENCE_CORRUPT:$code")

enum class MigrationAttemptState { PREPARED, RUNNING, APPLIED, FAILED }
enum class Phase36MigrationFailurePoint {
    BEFORE_STAGING_CREATE, AFTER_STAGING_CREATE, AFTER_COPY, BEFORE_DROP, AFTER_DROP, AFTER_RENAME,
    AFTER_SAFETY_REVALIDATION_BEFORE_RUNNING, BEFORE_FINAL_METADATA_APPLIED
}
fun interface Phase36MigrationFailureInjector {
    fun failIfRequested(point:Phase36MigrationFailurePoint)
    companion object { val NONE=Phase36MigrationFailureInjector{} }
}

data class MigrationEdge(
    val family:SchemaFamilyUid,
    val fromVersion:Int,
    val toVersion:Int,
    val implementationId:String,
    val materiality:MigrationMateriality,
    val migrate:(SQLiteDatabase,Phase36MigrationFailureInjector)->Unit = { _,_-> }
) {
    init {
        require(fromVersion>0&&toVersion>0&&fromVersion!=toVersion){"RPGOS-SCHEMA:INVALID_MIGRATION_EDGE"}
        require(implementationId.isNotBlank()){"RPGOS-SCHEMA:MISSING_IMPLEMENTATION_ID"}
    }
    internal fun semanticKey()="${family.name}:$fromVersion->$toVersion:$implementationId:${materiality.name}"
}

internal data class MigrationPlan(
    val sourceVector:Map<SchemaFamilyUid,Int>,
    val orderedEdges:List<MigrationEdge>
)

internal object MigrationPlanRegistry {
    fun buildPlan(
        contracts:List<SchemaFamilyContract>,
        sourceVector:Map<SchemaFamilyUid,Int>,
        edges:List<MigrationEdge>
    ):MigrationPlan {
        require(contracts.map{it.family}.toSet().size==contracts.size){"RPGOS-SCHEMA:DUPLICATE_FAMILY_CONTRACT"}
        validateEdgeGraph(edges)
        val byFamily=contracts.associateBy{it.family}
        require(sourceVector.keys==byFamily.keys){"RPGOS-SCHEMA:SOURCE_VECTOR_FAMILY_MISMATCH"}
        val routes=linkedMapOf<SchemaFamilyUid,List<MigrationEdge>>()
        contracts.forEach{c->
            val found=requireNotNull(sourceVector[c.family])
            if(found>c.currentVersion)throw UnsupportedFutureSchemaException(c.family,found,c.currentVersion)
            require(found>=c.minimumSupportedVersion){"RPGOS-SCHEMA:UNSUPPORTED_OLD:${c.family}:$found:${c.minimumSupportedVersion}"}
            if(found<c.currentVersion) routes[c.family]=route(c.family,found,c.currentVersion,edges)
        }
        val pending=routes.keys.toMutableSet();val familyOrder=mutableListOf<SchemaFamilyUid>()
        while(pending.isNotEmpty()){
            val ready=pending.filter{f->byFamily.getValue(f).dependencies.none{it in pending}}.sortedBy{it.name}
            require(ready.isNotEmpty()){"RPGOS-SCHEMA:MIGRATION_DEPENDENCY_CYCLE"}
            familyOrder+=ready;pending.removeAll(ready.toSet())
        }
        return MigrationPlan(sourceVector.toMap(),familyOrder.flatMap{routes.getValue(it)})
    }

    fun route(family:SchemaFamilyUid,from:Int,to:Int,edges:List<MigrationEdge>):List<MigrationEdge>{
        validateEdgeGraph(edges)
        val relevant=edges.filter{it.family==family}
        val paths=mutableListOf<List<MigrationEdge>>()
        fun walk(version:Int,path:List<MigrationEdge>){
            if(paths.size>1)return
            if(version==to){paths+=path;return}
            relevant.filter{it.fromVersion==version}.sortedWith(compareBy<MigrationEdge>{it.toVersion}.thenBy{it.implementationId}).forEach{e->
                walk(e.toVersion,path+e)
            }
        }
        walk(from,emptyList())
        require(paths.isNotEmpty()){"RPGOS-SCHEMA:MISSING_MIGRATION_EDGE:$family:$from:$to"}
        require(paths.size==1){"RPGOS-SCHEMA:AMBIGUOUS_MIGRATION_PATH:$family:$from:$to"}
        return paths.single()
    }

    fun fingerprint(plan:MigrationPlan):String {
        val material=buildString{
            append("planVersion=${Phase36SchemaVersioning.PLAN_VERSION}\n")
            append("source=${vectorCanonical(plan.sourceVector)}\n")
            plan.orderedEdges.forEach{append("edge=").append(it.semanticKey()).append('\n')}
        }
        return material.sha256()
    }

    fun manifest(edges:List<MigrationEdge>):String = edges.sortedWith(compareBy<MigrationEdge>{it.family.name}.thenBy{it.fromVersion}.thenBy{it.toVersion}.thenBy{it.implementationId})
        .joinToString("\n"){it.semanticKey()}

    private fun validateEdgeGraph(edges:List<MigrationEdge>){
        require(edges.map{listOf(it.family.name,it.fromVersion.toString(),it.toVersion.toString(),it.implementationId).joinToString(":")}.toSet().size==edges.size){"RPGOS-SCHEMA:DUPLICATE_MIGRATION_EDGE"}
        SchemaFamilyUid.entries.forEach{family->
            val relevant=edges.filter{it.family==family};if(relevant.isEmpty())return@forEach
            val states=mutableMapOf<Int,Int>()
            fun visit(v:Int){
                when(states[v]){1->error("RPGOS-SCHEMA:MIGRATION_EDGE_CYCLE:$family");2->return}
                states[v]=1;relevant.filter{it.fromVersion==v}.forEach{visit(it.toVersion)};states[v]=2
            }
            (relevant.flatMap{listOf(it.fromVersion,it.toVersion)}).distinct().forEach{visit(it)}
        }
    }

    internal fun vectorCanonical(vector:Map<SchemaFamilyUid,Int>)=vector.entries.sortedBy{it.key.name}.joinToString("|"){"${it.key.name}:${it.value}"}
}

internal object Phase36SchemaVersioning {
    const val VERSIONS="rpgos_schema_family_versions"
    const val ATTEMPTS="rpgos_migration_attempts"
    const val PLAN_VERSION=2

    val contracts=listOf(
        SchemaFamilyContract(SchemaFamilyUid.ENGINE,1,1),
        SchemaFamilyContract(SchemaFamilyUid.CAMPAIGN,1,1,setOf(SchemaFamilyUid.ENGINE)),
        SchemaFamilyContract(SchemaFamilyUid.WORLD_PACK,1,1,setOf(SchemaFamilyUid.ENGINE)),
        SchemaFamilyContract(SchemaFamilyUid.PLAYER,1,1,setOf(SchemaFamilyUid.CAMPAIGN)),
        SchemaFamilyContract(SchemaFamilyUid.RECEIPT,TURN_TRANSACTION_RECEIPT_VERSION,1,setOf(SchemaFamilyUid.CAMPAIGN)),
        SchemaFamilyContract(SchemaFamilyUid.EVENT,PHASE30_EVENT_SCHEMA_VERSION,1,setOf(SchemaFamilyUid.RECEIPT),MigrationMateriality.MATERIAL_DATA_MUTATION),
        SchemaFamilyContract(SchemaFamilyUid.CAUSAL,PHASE31_CAUSAL_SCHEMA_VERSION,1,setOf(SchemaFamilyUid.EVENT)),
        SchemaFamilyContract(SchemaFamilyUid.SNAPSHOT,CampaignSnapshotSchema.VERSION,1,setOf(SchemaFamilyUid.CAMPAIGN)),
        SchemaFamilyContract(SchemaFamilyUid.REPLAY,1,1,setOf(SchemaFamilyUid.SNAPSHOT,SchemaFamilyUid.EVENT)),
        SchemaFamilyContract(SchemaFamilyUid.CANON_DIVERGENCE,CANON_DIVERGENCE_SCHEMA_VERSION,1,setOf(SchemaFamilyUid.EVENT)),
        SchemaFamilyContract(SchemaFamilyUid.FINANCE,1,1,setOf(SchemaFamilyUid.PLAYER)),
        SchemaFamilyContract(SchemaFamilyUid.INVENTORY,1,1,setOf(SchemaFamilyUid.PLAYER)),
        SchemaFamilyContract(SchemaFamilyUid.OWNERSHIP,1,1,setOf(SchemaFamilyUid.INVENTORY)),
        SchemaFamilyContract(SchemaFamilyUid.DEVELOPMENT_PROJECT,1,1,setOf(SchemaFamilyUid.PLAYER))
    )

    val migrationManifest:List<MigrationEdge> = listOf(
        MigrationEdge(SchemaFamilyUid.RECEIPT,1,TURN_TRANSACTION_RECEIPT_VERSION,"RPGOS-P36-RECEIPT-V1-V3-R1",MigrationMateriality.MATERIAL_DATA_MUTATION){db,_->
            TurnTransactionReceiptSchema.migrateToCurrent(db)
        },
        MigrationEdge(SchemaFamilyUid.RECEIPT,2,TURN_TRANSACTION_RECEIPT_VERSION,"RPGOS-P36-RECEIPT-V2-V3-R1",MigrationMateriality.MATERIAL_DATA_MUTATION){db,_->
            TurnTransactionReceiptSchema.migrateToCurrent(db)
        },
        MigrationEdge(SchemaFamilyUid.EVENT,1,PHASE30_EVENT_SCHEMA_VERSION,"RPGOS-P36-EVENT-V1-V2-R1",MigrationMateriality.MATERIAL_DATA_MUTATION){db,injector->
            CampaignIntelligencePhase30Schema.migrateEventTableIfNeeded(db,injector)
        }
    )
    val migrationManifestCanonical:String get()=MigrationPlanRegistry.manifest(migrationManifest)

    fun ensureReady(db:SQLiteDatabase,campaignUid:String,safetySnapshotUid:String?=null,injector:Phase36MigrationFailureInjector=Phase36MigrationFailureInjector.NONE){
        require(!db.inTransaction()){"RPGOS-SCHEMA:TOP_LEVEL_MIGRATION_REQUIRED"}
        require(campaignUid.isNotBlank())
        CampaignRuntimeLifecycleLock.withRecovery(campaignUid){ensureReadyLocked(db,campaignUid,safetySnapshotUid,injector,false)}
    }

    internal fun ensureReadyForRecoveryStaging(db:SQLiteDatabase,campaignUid:String){
        require(!db.inTransaction()){"RPGOS-SCHEMA:TOP_LEVEL_MIGRATION_REQUIRED"}
        CampaignRuntimeLifecycleLock.withRecovery(campaignUid){ensureReadyLocked(db,campaignUid,null,Phase36MigrationFailureInjector.NONE,true)}
    }

    private fun ensureReadyLocked(db:SQLiteDatabase,campaignUid:String,safetySnapshotUid:String?,injector:Phase36MigrationFailureInjector,recoveryStaging:Boolean){
        inspectCompatibilityBeforeMutation(db)
        ensureMetadataTables(db)
        validateAttemptStateVocabulary(db)
        adoptMissingFamilyVersions(db,campaignUid)
        recoverInterrupted(db,campaignUid)
        val source=currentVector(db)
        val plan=MigrationPlanRegistry.buildPlan(contracts,source,migrationManifest)
        if(plan.orderedEdges.isEmpty()){
            administrativeWrite(db,campaignUid){Phase35CanonDivergenceSchema.ensureReady(db)}
            CanonDivergenceSqlAuthority.install(db)
            return
        }
        val planFingerprint=MigrationPlanRegistry.fingerprint(plan)
        val sourceCanonical=MigrationPlanRegistry.vectorCanonical(source)
        val target=targetVector();val targetCanonical=MigrationPlanRegistry.vectorCanonical(target)
        val firstSafety=if(recoveryStaging)null else MigrationSafetyPolicy.requireProtectedSnapshot(db,campaignUid,plan.orderedEdges,safetySnapshotUid)
        val attempt="MIG-$campaignUid-${UUID.randomUUID()}";val now=System.currentTimeMillis()
        administrativeWrite(db,campaignUid){
            db.execSQL("""INSERT INTO $ATTEMPTS(migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,source_vector_canonical,target_vector_canonical,plan_fingerprint,plan_version,safety_snapshot_uid,state,started_at_epoch_ms)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(attempt,campaignUid,sourceCanonical.sha256(),targetCanonical.sha256(),sourceCanonical,targetCanonical,planFingerprint,PLAN_VERSION,safetySnapshotUid,MigrationAttemptState.PREPARED.name,now))
        }
        val secondSafety=if(recoveryStaging)null else MigrationSafetyPolicy.requireProtectedSnapshot(db,campaignUid,plan.orderedEdges,safetySnapshotUid)
        if(!recoveryStaging)require(firstSafety==secondSafety){"RPGOS-SCHEMA:SAFETY_SNAPSHOT_CHANGED"}
        injector.failIfRequested(Phase36MigrationFailurePoint.AFTER_SAFETY_REVALIDATION_BEFORE_RUNNING)
        try{
            administrativeWrite(db,campaignUid){
                db.execSQL("UPDATE $ATTEMPTS SET state=? WHERE migration_attempt_uid=?",arrayOf(MigrationAttemptState.RUNNING.name,attempt))
                plan.orderedEdges.forEach{edge->
                    edge.migrate(db,injector)
                    db.execSQL("INSERT OR REPLACE INTO $VERSIONS(schema_family_uid,schema_version,migration_owner,updated_at_epoch_ms) VALUES(?,?,?,?)",arrayOf(edge.family.name,edge.toVersion,edge.implementationId,System.currentTimeMillis()))
                }
                Phase35CanonDivergenceSchema.ensureReady(db)
                require(currentVector(db)==target){"RPGOS-SCHEMA:POST_MIGRATION_VECTOR_MISMATCH"}
                injector.failIfRequested(Phase36MigrationFailurePoint.BEFORE_FINAL_METADATA_APPLIED)
                db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=? WHERE migration_attempt_uid=?",arrayOf(MigrationAttemptState.APPLIED.name,System.currentTimeMillis(),attempt))
            }
            CanonDivergenceSqlAuthority.install(db)
        }catch(t:Throwable){
            administrativeWrite(db,campaignUid){
                db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=?,failure_code=? WHERE migration_attempt_uid=? AND state=?",arrayOf(MigrationAttemptState.FAILED.name,System.currentTimeMillis(),"MIGRATION_STEP_FAILED",attempt,MigrationAttemptState.PREPARED.name))
            }
            throw t
        }
    }

    fun requireNoUnsupportedFuture(db:SQLiteDatabase)=inspectCompatibilityBeforeMutation(db)

    fun requireReady(db:SQLiteDatabase){
        check(table(db,VERSIONS)&&table(db,ATTEMPTS)&&Phase35CanonDivergenceSchema.isReady(db)){"RPGOS-SCHEMA:NOT_READY"}
        validateAttemptStateVocabulary(db)
        val vector=currentVector(db)
        contracts.forEach{c->
            val found=vector.getValue(c.family)
            if(found>c.currentVersion)throw UnsupportedFutureSchemaException(c.family,found,c.currentVersion)
            check(found==c.currentVersion){"RPGOS-SCHEMA:FAMILY_NOT_CURRENT:${c.family}:$found:${c.currentVersion}"}
        }
        check(db.rawQuery("SELECT 1 FROM $ATTEMPTS WHERE state IN (?,?) LIMIT 1",arrayOf(MigrationAttemptState.PREPARED.name,MigrationAttemptState.RUNNING.name)).use{!it.moveToFirst()}){"RPGOS-SCHEMA:INCOMPLETE_MIGRATION"}
    }

    fun activeSafetySnapshotUids(db:SQLiteDatabase,campaignUid:String):Set<String>{
        if(!table(db,ATTEMPTS))return emptySet()
        return db.rawQuery("SELECT safety_snapshot_uid FROM $ATTEMPTS WHERE campaign_uid=? AND state IN (?,?) AND safety_snapshot_uid IS NOT NULL",arrayOf(campaignUid,MigrationAttemptState.PREPARED.name,MigrationAttemptState.RUNNING.name)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
    }

    private fun ensureMetadataTables(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE IF NOT EXISTS $VERSIONS(schema_family_uid TEXT PRIMARY KEY,schema_version INTEGER NOT NULL CHECK(schema_version>0),migration_owner TEXT NOT NULL,updated_at_epoch_ms INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $ATTEMPTS(
            migration_attempt_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,source_vector_fingerprint TEXT NOT NULL,target_vector_fingerprint TEXT NOT NULL,
            source_vector_canonical TEXT,target_vector_canonical TEXT,plan_fingerprint TEXT NOT NULL,plan_version INTEGER NOT NULL,safety_snapshot_uid TEXT,
            state TEXT NOT NULL CHECK(state IN ('PREPARED','RUNNING','APPLIED','FAILED')),started_at_epoch_ms INTEGER NOT NULL,completed_at_epoch_ms INTEGER,failure_code TEXT)""")
        ensureColumn(db,ATTEMPTS,"source_vector_canonical","TEXT")
        ensureColumn(db,ATTEMPTS,"target_vector_canonical","TEXT")
    }

    private fun adoptMissingFamilyVersions(db:SQLiteDatabase,campaignUid:String){
        val missing=contracts.filter{currentMetadata(db,it.family)==null}
        if(missing.isEmpty()){
            validatePhysicalMetadataConsistency(db);return
        }
        administrativeWrite(db,campaignUid){
            missing.forEach{c->
                val adopted=when(c.family){
                    SchemaFamilyUid.EVENT -> CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db)?:c.currentVersion
                    SchemaFamilyUid.RECEIPT -> TurnTransactionReceiptSchema.physicalSchemaVersion(db)?:c.currentVersion
                    else -> c.currentVersion
                }
                db.execSQL("INSERT INTO $VERSIONS(schema_family_uid,schema_version,migration_owner,updated_at_epoch_ms) VALUES(?,?,?,?)",arrayOf(c.family.name,adopted,"RPGOS-P36-ADOPT-PHYSICAL",System.currentTimeMillis()))
            }
        }
        validatePhysicalMetadataConsistency(db)
    }

    private fun validatePhysicalMetadataConsistency(db:SQLiteDatabase){
        val eventPhysical=CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db)
        val eventMetadata=currentMetadata(db,SchemaFamilyUid.EVENT)
        if(eventPhysical!=null&&eventMetadata!=null)require(eventPhysical==eventMetadata){"RPGOS-SCHEMA:EVENT_PHYSICAL_METADATA_MISMATCH:$eventPhysical:$eventMetadata"}
        val receiptPhysical=TurnTransactionReceiptSchema.physicalSchemaVersion(db)
        val receiptMetadata=currentMetadata(db,SchemaFamilyUid.RECEIPT)
        if(receiptPhysical!=null&&receiptMetadata!=null)require(receiptPhysical==receiptMetadata){"RPGOS-SCHEMA:RECEIPT_PHYSICAL_METADATA_MISMATCH:$receiptPhysical:$receiptMetadata"}
    }

    private fun inspectCompatibilityBeforeMutation(db:SQLiteDatabase){
        if(!table(db,VERSIONS))return
        contracts.forEach{c->currentMetadata(db,c.family)?.let{found->
            if(found>c.currentVersion)throw UnsupportedFutureSchemaException(c.family,found,c.currentVersion)
            require(found>=c.minimumSupportedVersion){"RPGOS-SCHEMA:UNSUPPORTED_OLD:${c.family}:$found:${c.minimumSupportedVersion}"}
        }}
    }

    private data class AttemptEvidence(val uid:String,val state:String,val sourceFingerprint:String,val targetFingerprint:String,val sourceCanonical:String?,val targetCanonical:String?,val planFingerprint:String,val planVersion:Int)
    private fun recoverInterrupted(db:SQLiteDatabase,campaignUid:String){
        val active=db.rawQuery("""SELECT migration_attempt_uid,state,source_vector_fingerprint,target_vector_fingerprint,source_vector_canonical,target_vector_canonical,plan_fingerprint,plan_version
            FROM $ATTEMPTS WHERE campaign_uid=? AND state IN (?,?) ORDER BY started_at_epoch_ms,migration_attempt_uid""",arrayOf(campaignUid,MigrationAttemptState.PREPARED.name,MigrationAttemptState.RUNNING.name)).use{c->buildList{while(c.moveToNext())add(AttemptEvidence(c.getString(0),c.getString(1),c.getString(2),c.getString(3),if(c.isNull(4))null else c.getString(4),if(c.isNull(5))null else c.getString(5),c.getString(6),c.getInt(7)))}}
        if(active.isEmpty())return
        val currentCanonical=MigrationPlanRegistry.vectorCanonical(currentVector(db));val targetCanonical=MigrationPlanRegistry.vectorCanonical(targetVector())
        active.forEach{a->
            if(a.state !in setOf(MigrationAttemptState.PREPARED.name,MigrationAttemptState.RUNNING.name))throw MigrationEvidenceCorruptException("ILLEGAL_ACTIVE_STATE")
            if(a.planVersion!=PLAN_VERSION)throw MigrationEvidenceCorruptException("WRONG_PLAN_VERSION")
            val source=a.sourceCanonical?:throw MigrationEvidenceCorruptException("MISSING_SOURCE_VECTOR")
            val target=a.targetCanonical?:throw MigrationEvidenceCorruptException("MISSING_TARGET_VECTOR")
            if(source.sha256()!=a.sourceFingerprint)throw MigrationEvidenceCorruptException("SOURCE_FINGERPRINT")
            if(target.sha256()!=a.targetFingerprint)throw MigrationEvidenceCorruptException("TARGET_FINGERPRINT")
            if(target!=targetCanonical)throw MigrationEvidenceCorruptException("WRONG_TARGET_VECTOR")
            val sourceVector=parseVector(source)
            val expectedPlan=MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.buildPlan(contracts,sourceVector,migrationManifest))
            if(expectedPlan!=a.planFingerprint)throw MigrationPlanMismatchException(a.planFingerprint,expectedPlan)
            if(currentCanonical!=source)throw MigrationEvidenceCorruptException("IMPOSSIBLE_CURRENT_VECTOR")
        }
        administrativeWrite(db,campaignUid){
            db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=?,failure_code=? WHERE campaign_uid=? AND state IN (?,?)",arrayOf(MigrationAttemptState.FAILED.name,System.currentTimeMillis(),"INTERRUPTED_RESTART_SAFE",campaignUid,MigrationAttemptState.PREPARED.name,MigrationAttemptState.RUNNING.name))
        }
    }

    private fun validateAttemptStateVocabulary(db:SQLiteDatabase){
        if(!table(db,ATTEMPTS))return
        val legal=MigrationAttemptState.entries.map{it.name}.toSet()
        db.rawQuery("SELECT state FROM $ATTEMPTS",null).use{c->while(c.moveToNext())if(c.getString(0) !in legal)throw MigrationEvidenceCorruptException("MALFORMED_STATE")}
    }

    private fun currentVector(db:SQLiteDatabase):Map<SchemaFamilyUid,Int>{
        validatePhysicalMetadataConsistency(db)
        return contracts.associate{c->c.family to (currentMetadata(db,c.family)?:error("RPGOS-SCHEMA:MISSING_FAMILY:${c.family}"))}
    }
    private fun targetVector()=contracts.associate{it.family to it.currentVersion}
    private fun parseVector(value:String):Map<SchemaFamilyUid,Int>{
        val pairs=runCatching{value.split('|').associate{entry->val p=entry.split(':');require(p.size==2);SchemaFamilyUid.valueOf(p[0]) to p[1].toInt()}}.getOrElse{throw MigrationEvidenceCorruptException("MALFORMED_VECTOR")}
        if(pairs.keys!=contracts.map{it.family}.toSet())throw MigrationEvidenceCorruptException("VECTOR_FAMILY_SET")
        return pairs
    }

    private fun administrativeWrite(db:SQLiteDatabase,campaignUid:String,block:()->Unit){
        if(GameplayMutationDatabaseGuards.isInstalled(db))withAdministrativeMutationAuthority(db,campaignUid){block()}
        else{db.beginTransaction();try{block();db.setTransactionSuccessful()}finally{db.endTransaction()}}
    }
    private fun currentMetadata(db:SQLiteDatabase,family:SchemaFamilyUid):Int?=if(!table(db,VERSIONS))null else db.rawQuery("SELECT schema_version FROM $VERSIONS WHERE schema_family_uid=?",arrayOf(family.name)).use{if(it.moveToFirst())it.getInt(0)else null}
    private fun ensureColumn(db:SQLiteDatabase,table:String,column:String,type:String){if(!hasColumn(db,table,column))db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")}
    private fun hasColumn(db:SQLiteDatabase,table:String,column:String)=db.rawQuery("PRAGMA table_info($table)",null).use{c->var found=false;while(c.moveToNext())if(c.getString(1)==column)found=true;found}
    private fun table(db:SQLiteDatabase,name:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(name)).use{it.moveToFirst()}
}

internal data class SafetySnapshotIdentity(val snapshotUid:String,val payloadPath:String,val payloadSha256:String)
internal object MigrationSafetyPolicy {
    fun requireProtectedSnapshot(db:SQLiteDatabase,campaignUid:String,edges:List<MigrationEdge>,safetySnapshotUid:String?):SafetySnapshotIdentity?{
        if(edges.none{it.materiality==MigrationMateriality.MATERIAL_DATA_MUTATION})return null
        val uid=requireNotNull(safetySnapshotUid){"RPGOS-SCHEMA:MATERIAL_MIGRATION_REQUIRES_SAFETY_SNAPSHOT"}
        return CampaignSnapshotRecoveryPolicy.requireRecoverableSafetySnapshot(db,campaignUid,uid)
    }
}

private fun String.sha256():String=MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
