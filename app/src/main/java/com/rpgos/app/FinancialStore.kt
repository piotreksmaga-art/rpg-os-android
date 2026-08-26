package com.rpgos.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class FinancialStore(private val db: SQLiteDatabase, private val campaignId: String) {
    init { require(campaignId.isNotBlank()) { "campaignId must not be blank" } }

    fun registerCurrency(definition: CurrencyDefinition) {
        FinancialPolicy.validateCurrency(definition)
        db.execSQL(
            """INSERT INTO currency_definitions(currency_uid,currency_key,display_name,minor_unit_scale,definition_status,provenance)
               VALUES(?,?,?,?,?,?)""".trimIndent(),
            arrayOf<Any?>(definition.currencyUid, definition.currencyKey, definition.displayName, definition.minorUnitScale, definition.status, definition.provenance)
        )
    }

    fun registerTransactionType(transactionTypeUid: String, flowKind: FinancialFlowKind, provenance: String) {
        require(transactionTypeUid.isNotBlank()) { "transactionTypeUid must not be blank" }
        require(provenance.isNotBlank()) { "transaction type provenance must not be blank" }
        db.execSQL(
            "INSERT INTO financial_transaction_type_definitions(transaction_type_uid,flow_kind,type_status,provenance) VALUES(?,?,'ACTIVE',?)",
            arrayOf(transactionTypeUid, flowKind.name, provenance)
        )
    }

    fun openAccount(account: FinancialAccount): FinancialAccount {
        FinancialPolicy.validateAccount(account)
        require(account.campaignId == campaignId) { "financial account belongs to another campaign" }
        require(account.closedAt == null) { "new financial account must be open" }
        writeTransaction {
            db.execSQL(
                """INSERT INTO financial_accounts(campaign_id,account_uid,holder_kind_uid,holder_uid,account_type_uid,currency_uid,opened_order,closed_order,account_version,provenance)
                   VALUES(?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                arrayOf<Any?>(campaignId,account.accountUid,account.holder.ownerKindUid,account.holder.ownerUid,account.accountTypeUid,account.currencyUid,account.openedAt,null,account.version,account.provenance)
            )
            db.execSQL("INSERT INTO financial_account_balances(campaign_id,account_uid,balance_minor,balance_version,last_effective_order) VALUES(?,?,0,1,NULL)",arrayOf(campaignId,account.accountUid))
        }
        return account
    }

    fun closeAccount(accountUid:String,effectiveOrder:Long):FinancialAccount{require(accountUid.isNotBlank()){"accountUid must not be blank"};writeTransaction{val s=db.compileStatement("UPDATE financial_accounts SET closed_order=?,account_version=account_version+1 WHERE campaign_id=? AND account_uid=? AND closed_order IS NULL AND opened_order<? AND account_version<9223372036854775807 AND EXISTS(SELECT 1 FROM financial_account_balances b WHERE b.campaign_id=financial_accounts.campaign_id AND b.account_uid=financial_accounts.account_uid AND b.balance_minor=0)");s.use{it.bindLong(1,effectiveOrder);it.bindString(2,campaignId);it.bindString(3,accountUid);it.bindLong(4,effectiveOrder);require(it.executeUpdateDelete()==1){"open zero-balance financial account not found"}}};return requireAccount(accountUid)}
    fun transfer(financialTransactionUid:String,fromAccountUid:String,toAccountUid:String,amountMinor:Long,effectiveOrder:Long,reason:String,provenance:String,sourceEventUid:String?=null,commandUid:String?=null,transactionTypeUid:String="RPGOS-FIN-TYPE:TRANSFER"):FinancialCommitResult=writeTransactionResult{
        val currency=requireSameOpenCurrency(fromAccountUid,toAccountUid)
        commit(FinancialTransaction(campaignId,financialTransactionUid,fromAccountUid,toAccountUid,currency,amountMinor,transactionTypeUid,FinancialFlowKind.INTERNAL,reason,effectiveOrder,provenance,sourceEventUid,commandUid))
    }
    fun creditExternal(financialTransactionUid:String,toAccountUid:String,amountMinor:Long,effectiveOrder:Long,reason:String,provenance:String,sourceEventUid:String?=null,commandUid:String?=null,transactionTypeUid:String="RPGOS-FIN-TYPE:EXTERNAL_CREDIT"):FinancialCommitResult=writeTransactionResult{
        val account=requireAccount(toAccountUid);require(account.closedAt==null){"financial account is closed"}
        commit(FinancialTransaction(campaignId,financialTransactionUid,null,toAccountUid,account.currencyUid,amountMinor,transactionTypeUid,FinancialFlowKind.SOURCE,reason,effectiveOrder,provenance,sourceEventUid,commandUid))
    }
    fun debitExternal(financialTransactionUid:String,fromAccountUid:String,amountMinor:Long,effectiveOrder:Long,reason:String,provenance:String,sourceEventUid:String?=null,commandUid:String?=null,transactionTypeUid:String="RPGOS-FIN-TYPE:EXTERNAL_DEBIT"):FinancialCommitResult=writeTransactionResult{
        val account=requireAccount(fromAccountUid);require(account.closedAt==null){"financial account is closed"}
        commit(FinancialTransaction(campaignId,financialTransactionUid,fromAccountUid,null,account.currencyUid,amountMinor,transactionTypeUid,FinancialFlowKind.SINK,reason,effectiveOrder,provenance,sourceEventUid,commandUid))
    }
    fun migrationOpeningBalance(financialTransactionUid:String,accountUid:String,amountMinor:Long,effectiveOrder:Long,legacyEvidenceUid:String,provenance:String):FinancialCommitResult{require(legacyEvidenceUid.isNotBlank()){"legacyEvidenceUid must not be blank"};val currency=requireAccount(accountUid).currencyUid;val tx=FinancialTransaction(campaignId,financialTransactionUid,null,accountUid,currency,amountMinor,"RPGOS-FIN-TYPE:MIGRATION_OPENING_BALANCE",FinancialFlowKind.SOURCE,"Explicit legacy opening balance",effectiveOrder,provenance,commandUid="LEGACY:$legacyEvidenceUid");return writeTransactionResult{val result=commit(tx);db.execSQL("INSERT OR IGNORE INTO legacy_financial_evidence(campaign_id,legacy_evidence_uid,evidence_kind,mapped_account_uid,mapped_transaction_uid,mapping_version,provenance) VALUES(?,?,'OPENING_BALANCE',?,?,1,?)",arrayOf(campaignId,legacyEvidenceUid,accountUid,financialTransactionUid,provenance));result}}
    fun reverse(originalTransactionUid:String,reversalTransactionUid:String,effectiveOrder:Long,reason:String,provenance:String,sourceEventUid:String?=null,commandUid:String?=null):FinancialCommitResult{val original=requireTransaction(originalTransactionUid);return commit(FinancialTransaction(campaignId,reversalTransactionUid,original.toAccountUid,original.fromAccountUid,original.currencyUid,original.amountMinor,"RPGOS-FIN-TYPE:REVERSAL",FinancialFlowKind.REVERSAL,reason,effectiveOrder,provenance,sourceEventUid,commandUid,original.financialTransactionUid))}

    fun commit(transaction:FinancialTransaction):FinancialCommitResult{
        FinancialPolicy.validateTransaction(transaction)
        require(transaction.campaignId==campaignId){"financial transaction belongs to another campaign"}
        try{return writeTransactionResult{
            duplicateOf(transaction)?.let{return@writeTransactionResult it}
            insertTransaction(transaction)
            FinancialCommitResult(transaction,false)
        }}catch(failure:Throwable){
            // A second SQLite connection may observe a lock/unique race even though the winning
            // transaction has already committed. Re-read after the write boundary and acknowledge
            // only byte-for-byte identical identity; every semantic conflict still fails closed.
            synchronized(writeLock(db.path,campaignId)){
                duplicateOf(transaction)?.let{return it}
            }
            throw failure
        }
    }
    fun balance(accountUid:String):Long{requireAccount(accountUid);return db.rawQuery("SELECT balance_minor FROM financial_account_balances WHERE campaign_id=? AND account_uid=?",arrayOf(campaignId,accountUid)).use{c->require(c.moveToFirst()){ "financial balance projection missing"};c.getLong(0)}}
    fun reconcile(accountUid:String):Long{requireAccount(accountUid);val exact=calculateLedgerBalance(accountUid);require(exact==balance(accountUid)){"financial balance projection diverges from authoritative ledger"};return exact}
    fun rebuildBalance(accountUid:String):Long{requireAccount(accountUid);val exact=calculateLedgerBalance(accountUid);val last=db.rawQuery("SELECT effective_order FROM financial_ledger_transactions WHERE campaign_id=? AND (from_account_uid=? OR to_account_uid=?) ORDER BY effective_order DESC,financial_transaction_uid DESC LIMIT 1",arrayOf(campaignId,accountUid,accountUid)).use{c->if(c.moveToFirst())c.getLong(0)else null};writeTransaction{db.execSQL("DELETE FROM financial_account_balances WHERE campaign_id=? AND account_uid=?",arrayOf(campaignId,accountUid));db.execSQL("INSERT INTO financial_account_balances(campaign_id,account_uid,balance_minor,balance_version,last_effective_order) VALUES(?,?,?,1,?)",arrayOf<Any?>(campaignId,accountUid,exact,last))};return exact}
    fun recentTransactions(accountUid:String,beforeOrder:Long?=null,limit:Int=50):List<FinancialTransaction>{require(accountUid.isNotBlank());require(limit in 1..500);val clause=if(beforeOrder==null)"" else " AND effective_order<?";val args=mutableListOf(campaignId,accountUid,accountUid).apply{if(beforeOrder!=null)add(beforeOrder.toString())};return queryTransactions("SELECT $TX_COLUMNS FROM financial_ledger_transactions WHERE campaign_id=? AND (from_account_uid=? OR to_account_uid=?)$clause ORDER BY effective_order DESC,financial_transaction_uid DESC LIMIT $limit",args.toTypedArray())}
    fun historyCount():Long=db.rawQuery("SELECT COUNT(*) FROM financial_ledger_transactions WHERE campaign_id=?",arrayOf(campaignId)).use{c->c.moveToFirst();c.getLong(0)}

    private fun calculateLedgerBalance(accountUid:String):Long{var exact=0L;db.rawQuery("SELECT from_account_uid,to_account_uid,amount_minor FROM financial_ledger_transactions WHERE campaign_id=? AND (from_account_uid=? OR to_account_uid=?) ORDER BY effective_order,financial_transaction_uid",arrayOf(campaignId,accountUid,accountUid)).use{c->while(c.moveToNext()){val from=if(c.isNull(0))null else c.getString(0);val to=if(c.isNull(1))null else c.getString(1);val amount=c.getLong(2);if(from==accountUid)exact=Math.subtractExact(exact,amount);if(to==accountUid)exact=Math.addExact(exact,amount)}};return exact}
    private fun insertTransaction(t:FinancialTransaction){db.execSQL("INSERT INTO financial_ledger_transactions(campaign_id,financial_transaction_uid,from_account_uid,to_account_uid,currency_uid,amount_minor,transaction_type_uid,flow_kind,reason,effective_order,source_event_uid,command_uid,reversal_of_uid,provenance,transaction_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'COMMITTED')",arrayOf<Any?>(campaignId,t.financialTransactionUid,t.fromAccountUid,t.toAccountUid,t.currencyUid,t.amountMinor,t.transactionTypeUid,t.flowKind.name,t.reason,t.effectiveOrder,t.sourceEventUid,t.commandUid,t.reversalOfUid,t.provenance))}
    private fun requireSameOpenCurrency(a:String,b:String):String{val aa=requireAccount(a);val bb=requireAccount(b);require(aa.currencyUid==bb.currencyUid){"currency mismatch requires explicit exchange operation"};require(aa.closedAt==null&&bb.closedAt==null){"financial account is closed"};return aa.currencyUid}
    private fun requireAccount(uid:String):FinancialAccount=db.rawQuery("SELECT campaign_id,account_uid,holder_kind_uid,holder_uid,account_type_uid,currency_uid,opened_order,closed_order,account_version,provenance FROM financial_accounts WHERE campaign_id=? AND account_uid=?",arrayOf(campaignId,uid)).use{c->require(c.moveToFirst()){ "financial account not found in campaign"};accountFrom(c)}
    private fun requireTransaction(uid:String)=requireNotNull(transactionByUid(uid)){"financial transaction not found in campaign"}
    private fun transactionByUid(uid:String)=queryTransactions("SELECT $TX_COLUMNS FROM financial_ledger_transactions WHERE campaign_id=? AND financial_transaction_uid=?",arrayOf(campaignId,uid)).singleOrNull()
    private fun transactionByCommand(uid:String)=queryTransactions("SELECT $TX_COLUMNS FROM financial_ledger_transactions WHERE campaign_id=? AND command_uid=?",arrayOf(campaignId,uid)).singleOrNull()
    private fun duplicateOf(transaction:FinancialTransaction):FinancialCommitResult?{
        transactionByUid(transaction.financialTransactionUid)?.let{existing->
            require(existing==transaction){"financial transaction UID already exists with different immutable content"}
            return FinancialCommitResult(existing,true)
        }
        transaction.commandUid?.let{uid->transactionByCommand(uid)?.let{existing->
            require(existing==transaction){"financial command UID already committed by another transaction"}
            return FinancialCommitResult(existing,true)
        }}
        return null
    }
    private fun queryTransactions(sql:String,args:Array<String>):List<FinancialTransaction>{val out=mutableListOf<FinancialTransaction>();db.rawQuery(sql,args).use{c->while(c.moveToNext())out+=transactionFrom(c)};return out}
    private fun accountFrom(c:Cursor)=FinancialAccount(c.getString(0),c.getString(1),OwnershipOwnerRef(c.getString(2),c.getString(3)),c.getString(4),c.getString(5),c.getLong(6),c.getString(9),if(c.isNull(7))null else c.getLong(7),c.getLong(8))
    private fun transactionFrom(c:Cursor)=FinancialTransaction(c.getString(0),c.getString(1),if(c.isNull(2))null else c.getString(2),if(c.isNull(3))null else c.getString(3),c.getString(4),c.getLong(5),c.getString(6),FinancialFlowKind.valueOf(c.getString(7)),c.getString(8),c.getLong(9),c.getString(13),if(c.isNull(10))null else c.getString(10),if(c.isNull(11))null else c.getString(11),if(c.isNull(12))null else c.getString(12))
    private fun writeTransaction(block:()->Unit){writeTransactionResult{block();Unit}}
    private fun<T>writeTransactionResult(block:()->T):T=synchronized(writeLock(db.path,campaignId)){
        if(db.inTransaction())return@synchronized block()
        db.beginTransaction()
        try{val result=block();db.setTransactionSuccessful();result}finally{db.endTransaction()}
    }

    companion object{
        private const val TX_COLUMNS="campaign_id,financial_transaction_uid,from_account_uid,to_account_uid,currency_uid,amount_minor,transaction_type_uid,flow_kind,reason,effective_order,source_event_uid,command_uid,reversal_of_uid,provenance"
        private val writeLocks=java.util.concurrent.ConcurrentHashMap<String,Any>()
        private fun writeLock(databasePath:String,campaignUid:String):Any{
            val canonical=runCatching{java.io.File(databasePath).canonicalPath.lowercase()}.getOrElse{databasePath.lowercase()}
            return writeLocks.computeIfAbsent("$canonical\u0000$campaignUid"){Any()}
        }
    }
}
