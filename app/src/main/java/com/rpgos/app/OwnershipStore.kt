package com.rpgos.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

class OwnershipStore(private val db: SQLiteDatabase, private val campaignId: String) {
    init {
        require(campaignId.isNotBlank()) { "campaignId must not be blank" }
        MigrationManager().ensureV12(db, campaignId)
    }

    /** Explicit ownership establishment only. Inventory/Equipment never call this implicitly. */
    fun acquire(record: OwnershipRecord): OwnershipRecord {
        OwnershipPolicy.validateRecord(record)
        require(record.campaignId == campaignId) { "OwnershipRecord belongs to another campaign" }
        db.beginTransaction()
        try {
            insertRecord(record)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return record
    }

    fun currentOwnership(asset: OwnedAssetRef, ownershipTypeUid: String? = null): List<OwnershipRecord> {
        OwnershipPolicy.validateAsset(asset)
        require(ownershipTypeUid == null || ownershipTypeUid.isNotBlank()) { "ownershipTypeUid must be null or nonblank" }
        val typeClause = if (ownershipTypeUid == null) "" else " AND ownership_type_uid=?"
        val args = mutableListOf(campaignId, asset.assetKindUid, asset.assetUid).apply { if (ownershipTypeUid != null) add(ownershipTypeUid) }
        return queryRecords(
            """SELECT $COLUMNS FROM ownership_records
               WHERE campaign_id=? AND asset_kind_uid=? AND asset_uid=?
                 AND record_status='ACTIVE' AND valid_until_order IS NULL$typeClause
               ORDER BY owner_kind_uid,owner_uid,ownership_record_uid""".trimIndent(),
            args.toTypedArray()
        )
    }

    fun ownershipAt(asset: OwnedAssetRef, atOrder: Long, ownershipTypeUid: String? = null): List<OwnershipRecord> {
        OwnershipPolicy.validateAsset(asset)
        require(ownershipTypeUid == null || ownershipTypeUid.isNotBlank()) { "ownershipTypeUid must be null or nonblank" }
        val typeClause = if (ownershipTypeUid == null) "" else " AND ownership_type_uid=?"
        val args = mutableListOf(campaignId, asset.assetKindUid, asset.assetUid, atOrder.toString(), atOrder.toString()).apply {
            if (ownershipTypeUid != null) add(ownershipTypeUid)
        }
        return queryRecords(
            """SELECT $COLUMNS FROM ownership_records
               WHERE campaign_id=? AND asset_kind_uid=? AND asset_uid=?
                 AND valid_from_order<=? AND (valid_until_order IS NULL OR ?<valid_until_order)$typeClause
               ORDER BY owner_kind_uid,owner_uid,ownership_record_uid""".trimIndent(),
            args.toTypedArray()
        )
    }

    fun history(asset: OwnedAssetRef, ownershipTypeUid: String? = null): List<OwnershipRecord> {
        OwnershipPolicy.validateAsset(asset)
        require(ownershipTypeUid == null || ownershipTypeUid.isNotBlank()) { "ownershipTypeUid must be null or nonblank" }
        val typeClause = if (ownershipTypeUid == null) "" else " AND ownership_type_uid=?"
        val args = mutableListOf(campaignId, asset.assetKindUid, asset.assetUid).apply { if (ownershipTypeUid != null) add(ownershipTypeUid) }
        return queryRecords(
            """SELECT $COLUMNS FROM ownership_records
               WHERE campaign_id=? AND asset_kind_uid=? AND asset_uid=?$typeClause
               ORDER BY valid_from_order,ownership_record_uid""".trimIndent(),
            args.toTypedArray()
        )
    }

    fun ownershipByOwner(owner: OwnershipOwnerRef): List<OwnershipRecord> {
        OwnershipPolicy.validateOwner(owner)
        return queryRecords(
            """SELECT $COLUMNS FROM ownership_records
               WHERE campaign_id=? AND owner_kind_uid=? AND owner_uid=?
               ORDER BY asset_kind_uid,asset_uid,ownership_type_uid,valid_from_order,ownership_record_uid""".trimIndent(),
            arrayOf(campaignId, owner.ownerKindUid, owner.ownerUid)
        )
    }

    fun fullTransfer(
        operationUid: String,
        fromOwner: OwnershipOwnerRef,
        toOwner: OwnershipOwnerRef,
        asset: OwnedAssetRef,
        ownershipTypeUid: String,
        effectiveAt: Long,
        sourceEventUid: String,
        provenance: String
    ): OwnershipTransferResult = transferShare(
        operationUid, fromOwner, toOwner, asset, ownershipTypeUid, OwnershipShare.full(), effectiveAt, sourceEventUid, provenance
    )

    /**
     * Atomic share transfer. All authoritative reads happen after beginTransaction(), then CAS close(s)
     * and successor inserts commit together. SQLite's serialized writer boundary prevents TOCTOU winners.
     */
    fun transferShare(
        operationUid: String,
        fromOwner: OwnershipOwnerRef,
        toOwner: OwnershipOwnerRef,
        asset: OwnedAssetRef,
        ownershipTypeUid: String,
        share: OwnershipShare,
        effectiveAt: Long,
        sourceEventUid: String,
        provenance: String
    ): OwnershipTransferResult {
        validateOperation(operationUid, fromOwner, asset, ownershipTypeUid, sourceEventUid, provenance)
        OwnershipPolicy.validateOwner(toOwner)
        require(fromOwner != toOwner) { "source and destination owner must differ" }

        db.beginTransaction()
        try {
            loadTransferOperation(operationUid,fromOwner,toOwner,asset,ownershipTypeUid,share,effectiveAt,sourceEventUid,provenance)?.let {
                db.setTransactionSuccessful()
                return it
            }

            val source = requireCurrentSource(fromOwner, asset, ownershipTypeUid)
            require(effectiveAt > source.validFrom) { "transfer boundary must be after source validFrom" }
            require(share.units <= source.share.units) { "transferred share exceeds source share" }
            val destination = currentForOwner(toOwner, asset, ownershipTypeUid)
            val destinationShare = if (destination == null) share else destination.share.add(share)
            val remaining = source.share.subtract(share)

            closeRecordCas(source, effectiveAt, sourceEventUid, provenance)
            if (destination != null) {
                require(effectiveAt > destination.validFrom) { "transfer boundary must be after destination validFrom" }
                closeRecordCas(destination, effectiveAt, sourceEventUid, provenance)
            }

            val sourceSuccessor = remaining?.let {
                OwnershipRecord(
                    campaignId=campaignId,
                    ownershipRecordUid=newRecordUid(),
                    owner=fromOwner,
                    asset=asset,
                    ownershipTypeUid=ownershipTypeUid,
                    share=it,
                    validFrom=effectiveAt,
                    sourceEventUid=sourceEventUid,
                    supersedesRecordUid=source.ownershipRecordUid,
                    provenance=provenance
                ).also(::insertRecord)
            }
            val destinationSuccessor = OwnershipRecord(
                campaignId=campaignId,
                ownershipRecordUid=newRecordUid(),
                owner=toOwner,
                asset=asset,
                ownershipTypeUid=ownershipTypeUid,
                share=destinationShare,
                validFrom=effectiveAt,
                sourceEventUid=sourceEventUid,
                supersedesRecordUid=destination?.ownershipRecordUid ?: source.ownershipRecordUid,
                provenance=provenance
            ).also(::insertRecord)

            db.execSQL(
                """INSERT INTO ownership_operations(campaign_id,operation_uid,operation_kind,asset_kind_uid,asset_uid,ownership_type_uid,source_record_uid,source_successor_uid,destination_successor_uid,effective_order,source_event_uid,provenance)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                arrayOf<Any?>(campaignId,operationUid,"TRANSFER",asset.assetKindUid,asset.assetUid,ownershipTypeUid,source.ownershipRecordUid,sourceSuccessor?.ownershipRecordUid,destinationSuccessor.ownershipRecordUid,effectiveAt,sourceEventUid,provenance)
            )
            val result = OwnershipTransferResult(operationUid, requireRecord(source.ownershipRecordUid), sourceSuccessor, destinationSuccessor)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    /** Explicitly ends title/right without inventing a successor owner. */
    fun close(
        operationUid: String,
        owner: OwnershipOwnerRef,
        asset: OwnedAssetRef,
        ownershipTypeUid: String,
        effectiveAt: Long,
        sourceEventUid: String,
        provenance: String
    ): OwnershipRecord {
        validateOperation(operationUid, owner, asset, ownershipTypeUid, sourceEventUid, provenance)
        db.beginTransaction()
        try {
            loadCloseOperation(operationUid,owner,asset,ownershipTypeUid,effectiveAt,sourceEventUid,provenance)?.let {
                db.setTransactionSuccessful()
                return it
            }
            val source = requireCurrentSource(owner, asset, ownershipTypeUid)
            require(effectiveAt > source.validFrom) { "close boundary must be after source validFrom" }
            closeRecordCas(source,effectiveAt,sourceEventUid,provenance)
            db.execSQL(
                """INSERT INTO ownership_operations(campaign_id,operation_uid,operation_kind,asset_kind_uid,asset_uid,ownership_type_uid,source_record_uid,source_successor_uid,destination_successor_uid,effective_order,source_event_uid,provenance)
                   VALUES(?,?,?,?,?,?,?,NULL,NULL,?,?,?)""".trimIndent(),
                arrayOf(campaignId,operationUid,"CLOSE",asset.assetKindUid,asset.assetUid,ownershipTypeUid,source.ownershipRecordUid,effectiveAt,sourceEventUid,provenance)
            )
            val closed = requireRecord(source.ownershipRecordUid)
            db.setTransactionSuccessful()
            return closed
        } finally { db.endTransaction() }
    }

    /** Maps explicit proven legacy ownership evidence only; never scans inventory/equipment/name fields. */
    fun registerLegacyMapping(evidenceUid: String, ownershipRecordUid: String, mappingVersion: Long, provenance: String) {
        require(evidenceUid.isNotBlank() && ownershipRecordUid.isNotBlank() && provenance.isNotBlank())
        require(mappingVersion >= 1L)
        requireRecord(ownershipRecordUid)
        db.execSQL(
            "INSERT INTO legacy_ownership_mappings(campaign_id,legacy_evidence_uid,ownership_record_uid,mapping_version,provenance) VALUES(?,?,?,?,?)",
            arrayOf(campaignId,evidenceUid,ownershipRecordUid,mappingVersion,provenance)
        )
    }

    private fun validateOperation(operationUid:String, owner:OwnershipOwnerRef, asset:OwnedAssetRef, ownershipTypeUid:String, sourceEventUid:String, provenance:String) {
        require(operationUid.isNotBlank()) { "operationUid must not be blank" }
        OwnershipPolicy.validateOwner(owner)
        OwnershipPolicy.validateAsset(asset)
        require(ownershipTypeUid.isNotBlank()) { "ownershipTypeUid must not be blank" }
        require(sourceEventUid.isNotBlank()) { "ownership transfer/close sourceEventUid must not be blank" }
        require(provenance.isNotBlank()) { "ownership transfer/close provenance must not be blank" }
    }

    private fun currentSource(owner:OwnershipOwnerRef, asset:OwnedAssetRef, ownershipTypeUid:String): OwnershipRecord =
        currentForOwner(owner,asset,ownershipTypeUid) ?: error("active source ownership not found")

    private fun requireCurrentSource(owner:OwnershipOwnerRef, asset:OwnedAssetRef, ownershipTypeUid:String): OwnershipRecord = currentSource(owner,asset,ownershipTypeUid)

    private fun currentForOwner(owner:OwnershipOwnerRef, asset:OwnedAssetRef, ownershipTypeUid:String): OwnershipRecord? {
        return queryRecords(
            """SELECT $COLUMNS FROM ownership_records
               WHERE campaign_id=? AND owner_kind_uid=? AND owner_uid=? AND asset_kind_uid=? AND asset_uid=? AND ownership_type_uid=?
                 AND record_status='ACTIVE' AND valid_until_order IS NULL
               ORDER BY ownership_record_uid""".trimIndent(),
            arrayOf(campaignId,owner.ownerKindUid,owner.ownerUid,asset.assetKindUid,asset.assetUid,ownershipTypeUid)
        ).singleOrNull()
    }

    private fun closeRecordCas(record:OwnershipRecord, effectiveAt:Long, eventUid:String, provenance:String) {
        val statement = db.compileStatement(
            """UPDATE ownership_records SET valid_until_order=?,closed_by_event_uid=?,record_version=record_version+1,record_status='CLOSED',closure_provenance=?
               WHERE campaign_id=? AND ownership_record_uid=? AND owner_kind_uid=? AND owner_uid=?
                 AND asset_kind_uid=? AND asset_uid=? AND ownership_type_uid=? AND share_units=?
                 AND record_status='ACTIVE' AND valid_until_order IS NULL AND record_version=?""".trimIndent()
        )
        statement.use {
            var i=1
            it.bindLong(i++,effectiveAt);it.bindString(i++,eventUid);it.bindString(i++,provenance)
            it.bindString(i++,campaignId);it.bindString(i++,record.ownershipRecordUid);it.bindString(i++,record.owner.ownerKindUid);it.bindString(i++,record.owner.ownerUid)
            it.bindString(i++,record.asset.assetKindUid);it.bindString(i++,record.asset.assetUid);it.bindString(i++,record.ownershipTypeUid);it.bindLong(i++,record.share.units);it.bindLong(i,record.recordVersion)
            require(it.executeUpdateDelete()==1) { "stale ownership source: ${record.ownershipRecordUid}" }
        }
    }

    private fun insertRecord(record:OwnershipRecord) {
        OwnershipPolicy.validateRecord(record)
        require(record.campaignId==campaignId) { "OwnershipRecord belongs to another campaign" }
        db.execSQL(
            """INSERT INTO ownership_records(campaign_id,ownership_record_uid,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid,share_units,valid_from_order,valid_until_order,source_event_uid,supersedes_record_uid,closed_by_event_uid,record_version,record_status,provenance,closure_provenance,metadata_json)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            arrayOf<Any?>(record.campaignId,record.ownershipRecordUid,record.owner.ownerKindUid,record.owner.ownerUid,record.asset.assetKindUid,record.asset.assetUid,record.ownershipTypeUid,record.share.units,record.validFrom,record.validUntil,record.sourceEventUid,record.supersedesRecordUid,record.closedByEventUid,record.recordVersion,record.status.name,record.provenance,record.closureProvenance,record.metadataJson)
        )
    }

    private fun requireRecord(uid:String):OwnershipRecord = queryRecords("SELECT $COLUMNS FROM ownership_records WHERE campaign_id=? AND ownership_record_uid=?",arrayOf(campaignId,uid)).singleOrNull()
        ?: error("OwnershipRecord not found: $uid")

    private fun loadTransferOperation(
        operationUid:String,fromOwner:OwnershipOwnerRef,toOwner:OwnershipOwnerRef,asset:OwnedAssetRef,ownershipTypeUid:String,share:OwnershipShare,effectiveAt:Long,sourceEventUid:String,provenance:String
    ):OwnershipTransferResult? {
        return db.rawQuery(
            "SELECT asset_kind_uid,asset_uid,ownership_type_uid,source_record_uid,source_successor_uid,destination_successor_uid,effective_order,source_event_uid,provenance FROM ownership_operations WHERE campaign_id=? AND operation_uid=? AND operation_kind='TRANSFER'",
            arrayOf(campaignId,operationUid)
        ).use { c ->
            if(!c.moveToFirst()) return@use null
            require(c.getString(0)==asset.assetKindUid && c.getString(1)==asset.assetUid && c.getString(2)==ownershipTypeUid && c.getLong(6)==effectiveAt && c.getString(7)==sourceEventUid && c.getString(8)==provenance) { "operationUid was already committed with different transfer semantics" }
            val closed=requireRecord(c.getString(3));val sourceSuccessor=if(c.isNull(4))null else requireRecord(c.getString(4));val destination=if(c.isNull(5))null else requireRecord(c.getString(5))
            require(destination!=null && closed.owner==fromOwner && destination.owner==toOwner) { "operationUid was already committed with different transfer owners" }
            val transferredUnits=closed.share.units-(sourceSuccessor?.share?.units ?: 0L)
            require(transferredUnits==share.units) { "operationUid was already committed with a different transfer share" }
            OwnershipTransferResult(operationUid,closed,sourceSuccessor,destination)
        }
    }

    private fun loadCloseOperation(operationUid:String,owner:OwnershipOwnerRef,asset:OwnedAssetRef,ownershipTypeUid:String,effectiveAt:Long,sourceEventUid:String,provenance:String):OwnershipRecord? {
        return db.rawQuery(
            "SELECT asset_kind_uid,asset_uid,ownership_type_uid,source_record_uid,effective_order,source_event_uid,provenance FROM ownership_operations WHERE campaign_id=? AND operation_uid=? AND operation_kind='CLOSE'",arrayOf(campaignId,operationUid)
        ).use { c ->
            if(!c.moveToFirst()) return@use null
            require(c.getString(0)==asset.assetKindUid && c.getString(1)==asset.assetUid && c.getString(2)==ownershipTypeUid && c.getLong(4)==effectiveAt && c.getString(5)==sourceEventUid && c.getString(6)==provenance) { "operationUid was already committed with different close semantics" }
            requireRecord(c.getString(3)).also { require(it.owner==owner) { "operationUid was already committed for a different owner" } }
        }
    }

    private fun queryRecords(sql:String,args:Array<String>):List<OwnershipRecord> {
        val out=mutableListOf<OwnershipRecord>()
        db.rawQuery(sql,args).use { c -> while(c.moveToNext()) out += c.toOwnershipRecord() }
        return out
    }

    private fun Cursor.toOwnershipRecord() = OwnershipRecord(
        campaignId=getString(0),
        ownershipRecordUid=getString(1),
        owner=OwnershipOwnerRef(getString(2),getString(3)),
        asset=OwnedAssetRef(getString(4),getString(5)),
        ownershipTypeUid=getString(6),
        share=OwnershipShare.ofUnits(getLong(7)),
        validFrom=getLong(8),
        validUntil=if(isNull(9))null else getLong(9),
        sourceEventUid=if(isNull(10))null else getString(10),
        supersedesRecordUid=if(isNull(11))null else getString(11),
        closedByEventUid=if(isNull(12))null else getString(12),
        recordVersion=getLong(13),
        status=OwnershipRecordStatus.valueOf(getString(14)),
        provenance=getString(15),
        closureProvenance=if(isNull(16))null else getString(16),
        metadataJson=if(isNull(17))null else getString(17)
    )

    private fun newRecordUid()="OWN-${UUID.randomUUID()}"

    companion object {
        private const val COLUMNS="campaign_id,ownership_record_uid,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid,share_units,valid_from_order,valid_until_order,source_event_uid,supersedes_record_uid,closed_by_event_uid,record_version,record_status,provenance,closure_provenance,metadata_json"
    }
}
