package com.rpgos.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class AssetLiabilityStore(private val db: SQLiteDatabase, private val campaignId: String) {
    init { require(campaignId.isNotBlank()); MigrationManager().ensureV14ContractGuards(db, campaignId) }

    fun registerAssetKind(definition: AssetKindDefinition) {
        AssetLiabilityPolicy.validateAssetKind(definition)
        writeTransaction {
            OwnershipReferenceRegistry(db,campaignId).registerAssetKind(definition.assetKindUid, definition.provenance)
            val existing = db.rawQuery("SELECT asset_class,display_name,world_pack_uid,definition_status,definition_version,provenance FROM asset_kind_definitions WHERE asset_kind_uid=?", arrayOf(definition.assetKindUid)).use { c ->
                if(!c.moveToFirst()) null else listOf(c.getString(0),c.getString(1),if(c.isNull(2)) null else c.getString(2),c.getString(3),c.getLong(4),c.getString(5))
            }
            if(existing == null) db.execSQL("INSERT INTO asset_kind_definitions(asset_kind_uid,asset_class,display_name,world_pack_uid,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(definition.assetKindUid,definition.assetClass.name,definition.displayName,definition.worldPackUid,definition.status,definition.version,definition.provenance))
            else require(existing == listOf(definition.assetClass.name,definition.displayName,definition.worldPackUid,definition.status,definition.version,definition.provenance)) { "asset kind UID already has different semantics" }
        }
    }

    /** Asset identity is owner-independent; title is established separately through OwnershipStore. */
    fun createAsset(asset: AssetRecord): AssetRecord {
        AssetLiabilityPolicy.validateAsset(asset); require(asset.campaignId==campaignId)
        writeTransaction {
            assetByRef(asset.ref)?.let { require(it==asset){"asset UID already has different immutable semantics"}; return@writeTransaction }
            OwnershipReferenceRegistry(db,campaignId).registerAsset(asset.ref, asset.provenance)
            db.execSQL("""INSERT INTO asset_records(campaign_id,asset_uid,asset_kind_uid,lifecycle_status,created_order,retired_order,source_event_uid,record_version,provenance,metadata_json)
                VALUES(?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any?>(campaignId,asset.assetUid,asset.assetKindUid,asset.lifecycle.name,asset.createdOrder,asset.retiredOrder,asset.sourceEventUid,asset.version,asset.provenance,asset.metadataJson))
        }
        return assetByRef(asset.ref)!!
    }

    fun retireAsset(asset: OwnedAssetRef, effectiveOrder: Long, lifecycle: AssetLifecycle, provenance: String): AssetRecord {
        require(lifecycle != AssetLifecycle.ACTIVE && provenance.isNotBlank())
        writeTransaction {
            val current=requireAsset(asset); require(current.lifecycle==AssetLifecycle.ACTIVE)
            val s=db.compileStatement("UPDATE asset_records SET lifecycle_status=?,retired_order=?,record_version=record_version+1 WHERE campaign_id=? AND asset_kind_uid=? AND asset_uid=? AND lifecycle_status='ACTIVE' AND retired_order IS NULL AND record_version=?")
            s.use { it.bindString(1,lifecycle.name);it.bindLong(2,effectiveOrder);it.bindString(3,campaignId);it.bindString(4,asset.assetKindUid);it.bindString(5,asset.assetUid);it.bindLong(6,current.version);require(it.executeUpdateDelete()==1){"stale asset lifecycle"} }
            OwnershipReferenceRegistry(db,campaignId).retireAsset(asset, provenance)
        }
        return requireAsset(asset)
    }

    fun recordValuation(v: AssetValuation): AssetValuation {
        AssetLiabilityPolicy.validateValuation(v); require(v.campaignId==campaignId)
        valuationByUid(v.valuationUid)?.let { require(it==v){"valuation UID already has different immutable semantics"};return it }
        db.execSQL("""INSERT INTO asset_valuations(campaign_id,valuation_uid,asset_kind_uid,asset_uid,currency_uid,amount_minor,valuation_type,effective_order,valid_until_order,source_event_uid,confidence_ppm,valuation_version,provenance)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf<Any?>(campaignId,v.valuationUid,v.asset.assetKindUid,v.asset.assetUid,v.currencyUid,v.amountMinor,v.valuationType.name,v.effectiveOrder,v.validUntilOrder,v.sourceEventUid,v.confidencePpm,v.version,v.provenance))
        return valuationByUid(v.valuationUid)!!
    }

    fun createObligation(o: ObligationRecord, initialStatusEventUid: String): ObligationRecord {
        AssetLiabilityPolicy.validateObligation(o); require(o.campaignId==campaignId && initialStatusEventUid.isNotBlank())
        writeTransaction {
            obligationByUid(o.obligationUid)?.let { require(it==o){"obligation UID already has different immutable semantics"}; return@writeTransaction }
            db.execSQL("""INSERT INTO obligation_records(campaign_id,obligation_uid,obligation_type_uid,obligation_class,obligor_kind_uid,obligor_uid,beneficiary_kind_uid,beneficiary_uid,currency_uid,principal_minor,asset_kind_uid,asset_uid,created_order,due_order,valid_until_order,source_event_uid,source_contract_uid,record_version,provenance,metadata_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf<Any?>(campaignId,o.obligationUid,o.obligationTypeUid,o.obligationClass.name,o.obligor.ownerKindUid,o.obligor.ownerUid,o.beneficiary.ownerKindUid,o.beneficiary.ownerUid,o.currencyUid,o.principalMinor,o.asset?.assetKindUid,o.asset?.assetUid,o.createdOrder,o.dueOrder,o.validUntilOrder,o.sourceEventUid,o.sourceContractUid,o.version,o.provenance,o.metadataJson))
            db.execSQL("INSERT INTO obligation_status_history(campaign_id,status_event_uid,obligation_uid,status,effective_order,source_event_uid,provenance) VALUES(?,?,?,'ACTIVE',?,?,?)",arrayOf<Any?>(campaignId,initialStatusEventUid,o.obligationUid,o.createdOrder,o.sourceEventUid,o.provenance))
        }
        return obligationByUid(o.obligationUid)!!
    }

    fun settle(s: ObligationSettlement): ObligationSettlement {
        require(s.campaignId==campaignId && s.settlementUid.isNotBlank() && s.obligationUid.isNotBlank() && s.provenance.isNotBlank())
        require(s.amountMinor==null || s.amountMinor>0L)
        settlementByUid(s.settlementUid)?.let { require(it==s){"settlement UID already has different immutable semantics"};return it }
        db.execSQL("""INSERT INTO obligation_settlements(campaign_id,settlement_uid,obligation_uid,settlement_kind,amount_minor,financial_transaction_uid,ownership_operation_uid,effective_order,source_event_uid,provenance)
            VALUES(?,?,?,?,?,?,?,?,?,?)""",arrayOf<Any?>(campaignId,s.settlementUid,s.obligationUid,s.kind.name,s.amountMinor,s.financialTransactionUid,s.ownershipOperationUid,s.effectiveOrder,s.sourceEventUid,s.provenance))
        return settlementByUid(s.settlementUid)!!
    }

    fun changeObligationStatus(obligationUid:String,statusEventUid:String,status:ObligationStatus,effectiveOrder:Long,provenance:String,sourceEventUid:String?=null) {
        require(obligationUid.isNotBlank()&&statusEventUid.isNotBlank()&&provenance.isNotBlank())
        db.execSQL("INSERT INTO obligation_status_history(campaign_id,status_event_uid,obligation_uid,status,effective_order,source_event_uid,provenance) VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>(campaignId,statusEventUid,obligationUid,status.name,effectiveOrder,sourceEventUid,provenance))
    }

    fun outstandingMinor(obligationUid:String, asOfOrder:Long=Long.MAX_VALUE): Long? {
        val o=requireObligation(obligationUid);val principal=o.principalMinor ?: return null
        var settled=0L
        db.rawQuery("SELECT amount_minor FROM obligation_settlements WHERE campaign_id=? AND obligation_uid=? AND amount_minor IS NOT NULL AND effective_order<=? ORDER BY effective_order,settlement_uid",arrayOf(campaignId,obligationUid,asOfOrder.toString())).use { c->while(c.moveToNext())settled=Math.addExact(settled,c.getLong(0)) }
        return Math.subtractExact(principal,settled)
    }

    fun currentStatus(obligationUid:String,asOfOrder:Long=Long.MAX_VALUE):ObligationStatus? = db.rawQuery("SELECT status FROM obligation_status_history WHERE campaign_id=? AND obligation_uid=? AND effective_order<=? ORDER BY effective_order DESC,status_event_uid DESC LIMIT 1",arrayOf(campaignId,obligationUid,asOfOrder.toString())).use{c->if(c.moveToFirst())ObligationStatus.valueOf(c.getString(0)) else null}

    fun addEncumbrance(uid:String,asset:OwnedAssetRef,obligationUid:String,typeUid:String,priority:Int,validFrom:Long,provenance:String) {
        require(uid.isNotBlank()&&typeUid.isNotBlank()&&provenance.isNotBlank())
        db.execSQL("INSERT INTO asset_encumbrances(campaign_id,encumbrance_uid,asset_kind_uid,asset_uid,obligation_uid,encumbrance_type_uid,priority,valid_from_order,record_version,provenance) VALUES(?,?,?,?,?,?,?,?,1,?)",arrayOf(campaignId,uid,asset.assetKindUid,asset.assetUid,obligationUid,typeUid,priority,validFrom,provenance))
    }

    fun releaseEncumbrance(uid:String,effectiveOrder:Long,provenance:String) {
        require(uid.isNotBlank()&&provenance.isNotBlank());val s=db.compileStatement("UPDATE asset_encumbrances SET released_order=?,record_version=record_version+1,release_provenance=? WHERE campaign_id=? AND encumbrance_uid=? AND released_order IS NULL")
        s.use{it.bindLong(1,effectiveOrder);it.bindString(2,provenance);it.bindString(3,campaignId);it.bindString(4,uid);require(it.executeUpdateDelete()==1){"active encumbrance not found"}}
    }

    /** Derived projection only. No net-worth row is persisted or writable. */
    fun netWorth(party:OwnershipOwnerRef,currencyUid:String,asOfOrder:Long):NetWorthProjection {
        OwnershipPolicy.validateOwner(party);require(currencyUid.isNotBlank())
        var assets=0L
        db.rawQuery("""SELECT r.asset_kind_uid,r.asset_uid,r.share_units FROM ownership_records r JOIN asset_records a ON a.campaign_id=r.campaign_id AND a.asset_kind_uid=r.asset_kind_uid AND a.asset_uid=r.asset_uid
            WHERE r.campaign_id=? AND r.owner_kind_uid=? AND r.owner_uid=? AND r.ownership_type_uid=? AND r.valid_from_order<=? AND (r.valid_until_order IS NULL OR ?<r.valid_until_order) AND a.created_order<=? AND (a.retired_order IS NULL OR ?<a.retired_order)""",arrayOf(campaignId,party.ownerKindUid,party.ownerUid,OWNERSHIP_TYPE_ECONOMIC,asOfOrder.toString(),asOfOrder.toString(),asOfOrder.toString(),asOfOrder.toString())).use { c->
            while(c.moveToNext()) { val v=valuationAt(OwnedAssetRef(c.getString(0),c.getString(1)),currencyUid,asOfOrder) ?: continue; assets=Math.addExact(assets,AssetLiabilityPolicy.exactShareValue(v.amountMinor,c.getLong(2))) }
        }
        val cash=cashAt(party,currencyUid,asOfOrder)
        var recv=0L;var debt=0L
        db.rawQuery("SELECT obligation_uid,obligor_kind_uid,obligor_uid,beneficiary_kind_uid,beneficiary_uid FROM obligation_records WHERE campaign_id=? AND currency_uid=? AND created_order<=? AND (valid_until_order IS NULL OR ?<valid_until_order)",arrayOf(campaignId,currencyUid,asOfOrder.toString(),asOfOrder.toString())).use { c->while(c.moveToNext()){
            val uid=c.getString(0);val st=currentStatus(uid,asOfOrder) ?: return@while
            if(st in setOf(ObligationStatus.SETTLED,ObligationStatus.CANCELLED,ObligationStatus.EXPIRED)) return@while
            val out=outstandingMinor(uid,asOfOrder) ?: return@while
            if(c.getString(1)==party.ownerKindUid&&c.getString(2)==party.ownerUid) debt=Math.addExact(debt,out)
            if(c.getString(3)==party.ownerKindUid&&c.getString(4)==party.ownerUid) recv=Math.addExact(recv,out)
        }}
        val net=Math.subtractExact(Math.addExact(Math.addExact(assets,cash),recv),debt)
        return NetWorthProjection(campaignId,party,currencyUid,asOfOrder,assets,cash,recv,debt,net)
    }

    fun assetCount():Long=scalar("SELECT COUNT(*) FROM asset_records WHERE campaign_id=?",arrayOf(campaignId))
    fun obligationCount():Long=scalar("SELECT COUNT(*) FROM obligation_records WHERE campaign_id=?",arrayOf(campaignId))

    private fun valuationAt(asset:OwnedAssetRef,currencyUid:String,at:Long):AssetValuation?=db.rawQuery("""SELECT campaign_id,valuation_uid,asset_kind_uid,asset_uid,currency_uid,amount_minor,valuation_type,effective_order,valid_until_order,source_event_uid,confidence_ppm,valuation_version,provenance FROM asset_valuations WHERE campaign_id=? AND asset_kind_uid=? AND asset_uid=? AND currency_uid=? AND effective_order<=? AND (valid_until_order IS NULL OR ?<valid_until_order) ORDER BY effective_order DESC,valuation_uid DESC LIMIT 1""",arrayOf(campaignId,asset.assetKindUid,asset.assetUid,currencyUid,at.toString(),at.toString())).use{c->if(c.moveToFirst())valuationFrom(c) else null}
    private fun cashAt(p:OwnershipOwnerRef,currency:String,at:Long):Long { var total=0L;db.rawQuery("SELECT account_uid FROM financial_accounts WHERE campaign_id=? AND holder_kind_uid=? AND holder_uid=? AND currency_uid=? AND opened_order<=? AND (closed_order IS NULL OR ?<closed_order)",arrayOf(campaignId,p.ownerKindUid,p.ownerUid,currency,at.toString(),at.toString())).use{a->while(a.moveToNext()){val uid=a.getString(0);var bal=0L;db.rawQuery("SELECT from_account_uid,to_account_uid,amount_minor FROM financial_ledger_transactions WHERE campaign_id=? AND (from_account_uid=? OR to_account_uid=?) AND effective_order<=? ORDER BY effective_order,financial_transaction_uid",arrayOf(campaignId,uid,uid,at.toString())).use{t->while(t.moveToNext()){if(!t.isNull(0)&&t.getString(0)==uid)bal=Math.subtractExact(bal,t.getLong(2));if(!t.isNull(1)&&t.getString(1)==uid)bal=Math.addExact(bal,t.getLong(2))}};total=Math.addExact(total,bal)}};return total }

    private fun requireAsset(ref:OwnedAssetRef)=assetByRef(ref)?:error("Phase14 asset not found in campaign")
    private fun assetByRef(ref:OwnedAssetRef):AssetRecord?=db.rawQuery("SELECT campaign_id,asset_uid,asset_kind_uid,lifecycle_status,created_order,retired_order,source_event_uid,record_version,provenance,metadata_json FROM asset_records WHERE campaign_id=? AND asset_kind_uid=? AND asset_uid=?",arrayOf(campaignId,ref.assetKindUid,ref.assetUid)).use{c->if(c.moveToFirst())assetFrom(c) else null}
    private fun valuationByUid(uid:String):AssetValuation?=db.rawQuery("SELECT campaign_id,valuation_uid,asset_kind_uid,asset_uid,currency_uid,amount_minor,valuation_type,effective_order,valid_until_order,source_event_uid,confidence_ppm,valuation_version,provenance FROM asset_valuations WHERE campaign_id=? AND valuation_uid=?",arrayOf(campaignId,uid)).use{c->if(c.moveToFirst())valuationFrom(c) else null}
    private fun obligationByUid(uid:String):ObligationRecord?=db.rawQuery("SELECT campaign_id,obligation_uid,obligation_type_uid,obligation_class,obligor_kind_uid,obligor_uid,beneficiary_kind_uid,beneficiary_uid,currency_uid,principal_minor,asset_kind_uid,asset_uid,created_order,due_order,valid_until_order,source_event_uid,source_contract_uid,record_version,provenance,metadata_json FROM obligation_records WHERE campaign_id=? AND obligation_uid=?",arrayOf(campaignId,uid)).use{c->if(c.moveToFirst())obligationFrom(c) else null}
    private fun requireObligation(uid:String)=obligationByUid(uid)?:error("obligation not found in campaign")
    private fun settlementByUid(uid:String):ObligationSettlement?=db.rawQuery("SELECT campaign_id,settlement_uid,obligation_uid,settlement_kind,amount_minor,financial_transaction_uid,ownership_operation_uid,effective_order,source_event_uid,provenance FROM obligation_settlements WHERE campaign_id=? AND settlement_uid=?",arrayOf(campaignId,uid)).use{c->if(c.moveToFirst())settlementFrom(c) else null}
    private fun scalar(sql:String,args:Array<String>):Long=db.rawQuery(sql,args).use{c->c.moveToFirst();c.getLong(0)}

    private fun assetFrom(c:Cursor)=AssetRecord(c.getString(0),c.getString(1),c.getString(2),c.getLong(4),c.getString(8),AssetLifecycle.valueOf(c.getString(3)),if(c.isNull(5))null else c.getLong(5),if(c.isNull(6))null else c.getString(6),c.getLong(7),if(c.isNull(9))null else c.getString(9))
    private fun valuationFrom(c:Cursor)=AssetValuation(c.getString(0),c.getString(1),OwnedAssetRef(c.getString(2),c.getString(3)),c.getString(4),c.getLong(5),ValuationType.valueOf(c.getString(6)),c.getLong(7),c.getString(12),if(c.isNull(8))null else c.getLong(8),if(c.isNull(9))null else c.getString(9),if(c.isNull(10))null else c.getLong(10),c.getLong(11))
    private fun obligationFrom(c:Cursor)=ObligationRecord(c.getString(0),c.getString(1),c.getString(2),ObligationClass.valueOf(c.getString(3)),OwnershipOwnerRef(c.getString(4),c.getString(5)),OwnershipOwnerRef(c.getString(6),c.getString(7)),c.getLong(12),c.getString(18),if(c.isNull(8))null else c.getString(8),if(c.isNull(9))null else c.getLong(9),if(c.isNull(10))null else OwnedAssetRef(c.getString(10),c.getString(11)),if(c.isNull(13))null else c.getLong(13),if(c.isNull(14))null else c.getLong(14),if(c.isNull(15))null else c.getString(15),if(c.isNull(16))null else c.getString(16),c.getLong(17),if(c.isNull(19))null else c.getString(19))
    private fun settlementFrom(c:Cursor)=ObligationSettlement(c.getString(0),c.getString(1),c.getString(2),SettlementKind.valueOf(c.getString(3)),c.getLong(7),c.getString(9),if(c.isNull(4))null else c.getLong(4),if(c.isNull(5))null else c.getString(5),if(c.isNull(6))null else c.getString(6),if(c.isNull(8))null else c.getString(8))
    private fun writeTransaction(block:()->Unit){if(db.inTransaction()){block();return};db.beginTransaction();try{block();db.setTransactionSuccessful()}finally{db.endTransaction()}}
}
