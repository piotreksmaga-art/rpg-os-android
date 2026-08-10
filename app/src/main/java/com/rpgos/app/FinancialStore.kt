package com.rpgos.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class FinancialStore(private val db: SQLiteDatabase, private val campaignId: String) {
    init {
        require(campaignId.isNotBlank()) { "campaignId must not be blank" }
        MigrationManager().ensureV13(db, campaignId)
    }

    fun registerCurrency(definition: CurrencyDefinition) {
        FinancialPolicy.validateCurrency(definition)
        db.execSQL(
            """INSERT INTO currency_definitions(currency_uid,currency_key,display_name,minor_unit_scale,definition_status,provenance)
               VALUES(?,?,?,?,?,?)""".trimIndent(),
            arrayOf(definition.currencyUid, definition.currencyKey, definition.displayName, definition.minorUnitScale, definition.status, definition.provenance)
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
                arrayOf<Any?>(campaignId, account.accountUid, account.holder.ownerKindUid, account.holder.ownerUid, account.accountTypeUid, account.currencyUid, account.openedAt, null, account.version, account.provenance)
            )
            db.execSQL(
                "INSERT INTO financial_account_balances(campaign_id,account_uid,balance_minor,balance_version,last_effective_order) VALUES(?,?,0,1,NULL)",
                arrayOf(campaignId, account.accountUid)
            )
        }
        return account
    }

    fun closeAccount(accountUid: String, effectiveOrder: Long): FinancialAccount {
        require(accountUid.isNotBlank()) { "accountUid must not be blank" }
        writeTransaction {
            val s = db.compileStatement(
                """UPDATE financial_accounts
                   SET closed_order=?, account_version=account_version+1
                   WHERE campaign_id=? AND account_uid=? AND closed_order IS NULL AND opened_order<?
                     AND account_version < 9223372036854775807""".trimIndent()
            )
            s.use {
                it.bindLong(1, effectiveOrder)
                it.bindString(2, campaignId)
                it.bindString(3, accountUid)
                it.bindLong(4, effectiveOrder)
                require(it.executeUpdateDelete() == 1) { "open zero-balance financial account not found" }
            }
        }
        return requireAccount(accountUid)
    }

    fun transfer(
        financialTransactionUid: String,
        fromAccountUid: String,
        toAccountUid: String,
        amountMinor: Long,
        effectiveOrder: Long,
        reason: String,
        provenance: String,
        sourceEventUid: String? = null,
        commandUid: String? = null,
        transactionTypeUid: String = "RPGOS-FIN-TYPE:TRANSFER"
    ): FinancialCommitResult {
        val currency = requireSameOpenCurrency(fromAccountUid, toAccountUid)
        return commit(
            FinancialTransaction(campaignId, financialTransactionUid, fromAccountUid, toAccountUid, currency, amountMinor,
                transactionTypeUid, FinancialFlowKind.INTERNAL, reason, effectiveOrder, provenance, sourceEventUid, commandUid)
        )
    }

    fun creditExternal(
        financialTransactionUid: String,
        toAccountUid: String,
        amountMinor: Long,
        effectiveOrder: Long,
        reason: String,
        provenance: String,
        sourceEventUid: String? = null,
        commandUid: String? = null,
        transactionTypeUid: String = "RPGOS-FIN-TYPE:EXTERNAL_CREDIT"
    ): FinancialCommitResult {
        val currency = requireAccount(toAccountUid).currencyUid
        return commit(FinancialTransaction(campaignId, financialTransactionUid, null, toAccountUid, currency, amountMinor,
            transactionTypeUid, FinancialFlowKind.SOURCE, reason, effectiveOrder, provenance, sourceEventUid, commandUid))
    }

    fun debitExternal(
        financialTransactionUid: String,
        fromAccountUid: String,
        amountMinor: Long,
        effectiveOrder: Long,
        reason: String,
        provenance: String,
        sourceEventUid: String? = null,
        commandUid: String? = null,
        transactionTypeUid: String = "RPGOS-FIN-TYPE:EXTERNAL_DEBIT"
    ): FinancialCommitResult {
        val currency = requireAccount(fromAccountUid).currencyUid
        return commit(FinancialTransaction(campaignId, financialTransactionUid, fromAccountUid, null, currency, amountMinor,
            transactionTypeUid, FinancialFlowKind.SINK, reason, effectiveOrder, provenance, sourceEventUid, commandUid))
    }

    fun migrationOpeningBalance(
        financialTransactionUid: String,
        accountUid: String,
        amountMinor: Long,
        effectiveOrder: Long,
        legacyEvidenceUid: String,
        provenance: String
    ): FinancialCommitResult {
        require(legacyEvidenceUid.isNotBlank()) { "legacyEvidenceUid must not be blank" }
        val currency = requireAccount(accountUid).currencyUid
        val tx = FinancialTransaction(campaignId, financialTransactionUid, null, accountUid, currency, amountMinor,
            "RPGOS-FIN-TYPE:MIGRATION_OPENING_BALANCE", FinancialFlowKind.SOURCE,
            "Explicit legacy opening balance", effectiveOrder, provenance, commandUid = "LEGACY:$legacyEvidenceUid")
        val result = commit(tx)
        if (!result.idempotentReplay) {
            db.execSQL(
                """INSERT INTO legacy_financial_evidence(campaign_id,legacy_evidence_uid,evidence_kind,mapped_account_uid,mapped_transaction_uid,mapping_version,provenance)
                   VALUES(?,?,'OPENING_BALANCE',?,?,1,?)""".trimIndent(),
                arrayOf(campaignId, legacyEvidenceUid, accountUid, financialTransactionUid, provenance)
            )
        }
        return result
    }

    fun reverse(
        originalTransactionUid: String,
        reversalTransactionUid: String,
        effectiveOrder: Long,
        reason: String,
        provenance: String,
        sourceEventUid: String? = null,
        commandUid: String? = null
    ): FinancialCommitResult {
        val original = requireTransaction(originalTransactionUid)
        val reversed = FinancialTransaction(
            campaignId = campaignId,
            financialTransactionUid = reversalTransactionUid,
            fromAccountUid = original.toAccountUid,
            toAccountUid = original.fromAccountUid,
            currencyUid = original.currencyUid,
            amountMinor = original.amountMinor,
            transactionTypeUid = "RPGOS-FIN-TYPE:REVERSAL",
            flowKind = FinancialFlowKind.REVERSAL,
            reason = reason,
            effectiveOrder = effectiveOrder,
            provenance = provenance,
            sourceEventUid = sourceEventUid,
            commandUid = commandUid,
            reversalOfUid = original.financialTransactionUid
        )
        return commit(reversed)
    }

    fun commit(transaction: FinancialTransaction): FinancialCommitResult {
        FinancialPolicy.validateTransaction(transaction)
        require(transaction.campaignId == campaignId) { "financial transaction belongs to another campaign" }
        return writeTransactionResult {
            transactionByUid(transaction.financialTransactionUid)?.let { existing ->
                require(existing == transaction) { "financial transaction UID already exists with different immutable content" }
                return@writeTransactionResult FinancialCommitResult(existing, true)
            }
            if (transaction.commandUid != null) {
                transactionByCommand(transaction.commandUid)?.let { existing ->
                    require(existing == transaction) { "financial command UID already committed by another transaction" }
                    return@writeTransactionResult FinancialCommitResult(existing, true)
                }
            }
            insertTransaction(transaction)
            transaction.fromAccountUid?.let { debit(it, transaction.amountMinor, transaction.effectiveOrder) }
            transaction.toAccountUid?.let { credit(it, transaction.amountMinor, transaction.effectiveOrder) }
            FinancialCommitResult(transaction, false)
        }
    }

    fun balance(accountUid: String): Long {
        requireAccount(accountUid)
        return db.rawQuery(
            "SELECT balance_minor FROM financial_account_balances WHERE campaign_id=? AND account_uid=?",
            arrayOf(campaignId, accountUid)
        ).use { c -> require(c.moveToFirst()) { "financial balance projection missing" }; c.getLong(0) }
    }

    fun reconcile(accountUid: String): Long {
        requireAccount(accountUid)
        var exact = 0L
        db.rawQuery(
            """SELECT from_account_uid,to_account_uid,amount_minor FROM financial_ledger_transactions
               WHERE campaign_id=? AND (from_account_uid=? OR to_account_uid=?)
               ORDER BY effective_order,financial_transaction_uid""".trimIndent(),
            arrayOf(campaignId, accountUid, accountUid)
        ).use { c ->
            while (c.moveToNext()) {
                val from = if (c.isNull(0)) null else c.getString(0)
                val to = if (c.isNull(1)) null else c.getString(1)
                val amount = c.getLong(2)
                if (from == accountUid) exact = Math.subtractExact(exact, amount)
                if (to == accountUid) exact = Math.addExact(exact, amount)
            }
        }
        require(exact == balance(accountUid)) { "financial balance projection diverges from authoritative ledger" }
        return exact
    }

    fun rebuildBalance(accountUid: String): Long {
        requireAccount(accountUid)
        var exact = 0L
        var lastOrder: Long? = null
        db.rawQuery(
            """SELECT from_account_uid,to_account_uid,amount_minor,effective_order FROM financial_ledger_transactions
               WHERE campaign_id=? AND (from_account_uid=? OR to_account_uid=?)
               ORDER BY effective_order,financial_transaction_uid""".trimIndent(),
            arrayOf(campaignId, accountUid, accountUid)
        ).use { c ->
            while (c.moveToNext()) {
                val from = if (c.isNull(0)) null else c.getString(0)
                val to = if (c.isNull(1)) null else c.getString(1)
                val amount = c.getLong(2)
                if (from == accountUid) exact = Math.subtractExact(exact, amount)
                if (to == accountUid) exact = Math.addExact(exact, amount)
                lastOrder = c.getLong(3)
            }
        }
        writeTransaction {
            val s = db.compileStatement(
                "UPDATE financial_account_balances SET balance_minor=?,balance_version=balance_version+1,last_effective_order=? WHERE campaign_id=? AND account_uid=? AND balance_version < 9223372036854775807"
            )
            s.use {
                it.bindLong(1, exact)
                if (lastOrder == null) it.bindNull(2) else it.bindLong(2, lastOrder!!)
                it.bindString(3, campaignId)
                it.bindString(4, accountUid)
                require(it.executeUpdateDelete() == 1) { "financial balance projection version overflow/missing" }
            }
        }
        return exact
    }

    fun recentTransactions(accountUid: String, beforeOrder: Long? = null, limit: Int = 50): List<FinancialTransaction> {
        require(accountUid.isNotBlank()) { "accountUid must not be blank" }
        require(limit in 1..500) { "limit must be in 1..500" }
        val timeClause = if (beforeOrder == null) "" else " AND effective_order<?"
        val args = mutableListOf(campaignId, accountUid, accountUid).apply { if (beforeOrder != null) add(beforeOrder.toString()) }
        return queryTransactions(
            """SELECT $TX_COLUMNS FROM financial_ledger_transactions
               WHERE campaign_id=? AND (from_account_uid=? OR to_account_uid=?)$timeClause
               ORDER BY effective_order DESC,financial_transaction_uid DESC LIMIT $limit""".trimIndent(),
            args.toTypedArray()
        )
    }

    fun historyCount(): Long = db.rawQuery(
        "SELECT COUNT(*) FROM financial_ledger_transactions WHERE campaign_id=?",
        arrayOf(campaignId)
    ).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun debit(accountUid: String, amount: Long, effectiveOrder: Long) {
        val s = db.compileStatement(
            """UPDATE financial_account_balances
               SET balance_minor=balance_minor-?,balance_version=balance_version+1,last_effective_order=?
               WHERE campaign_id=? AND account_uid=? AND balance_minor>=? AND balance_version < 9223372036854775807""".trimIndent()
        )
        s.use {
            it.bindLong(1, amount); it.bindLong(2, effectiveOrder); it.bindString(3, campaignId); it.bindString(4, accountUid); it.bindLong(5, amount)
            require(it.executeUpdateDelete() == 1) { "insufficient funds or balance version overflow" }
        }
    }

    private fun credit(accountUid: String, amount: Long, effectiveOrder: Long) {
        val maxBefore = Math.subtractExact(Long.MAX_VALUE, amount)
        val s = db.compileStatement(
            """UPDATE financial_account_balances
               SET balance_minor=balance_minor+?,balance_version=balance_version+1,last_effective_order=?
               WHERE campaign_id=? AND account_uid=? AND balance_minor<=? AND balance_version < 9223372036854775807""".trimIndent()
        )
        s.use {
            it.bindLong(1, amount); it.bindLong(2, effectiveOrder); it.bindString(3, campaignId); it.bindString(4, accountUid); it.bindLong(5, maxBefore)
            require(it.executeUpdateDelete() == 1) { "financial balance overflow or missing projection" }
        }
    }

    private fun insertTransaction(t: FinancialTransaction) {
        db.execSQL(
            """INSERT INTO financial_ledger_transactions(campaign_id,financial_transaction_uid,from_account_uid,to_account_uid,currency_uid,amount_minor,transaction_type_uid,flow_kind,reason,effective_order,source_event_uid,command_uid,reversal_of_uid,provenance,transaction_status)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'COMMITTED')""".trimIndent(),
            arrayOf<Any?>(campaignId,t.financialTransactionUid,t.fromAccountUid,t.toAccountUid,t.currencyUid,t.amountMinor,t.transactionTypeUid,t.flowKind.name,t.reason,t.effectiveOrder,t.sourceEventUid,t.commandUid,t.reversalOfUid,t.provenance)
        )
    }

    private fun requireSameOpenCurrency(a: String, b: String): String {
        val aa = requireAccount(a)
        val bb = requireAccount(b)
        require(aa.currencyUid == bb.currencyUid) { "currency mismatch requires explicit exchange operation" }
        require(aa.closedAt == null && bb.closedAt == null) { "financial account is closed" }
        return aa.currencyUid
    }

    private fun requireAccount(accountUid: String): FinancialAccount = db.rawQuery(
        """SELECT campaign_id,account_uid,holder_kind_uid,holder_uid,account_type_uid,currency_uid,opened_order,closed_order,account_version,provenance
           FROM financial_accounts WHERE campaign_id=? AND account_uid=?""".trimIndent(),
        arrayOf(campaignId, accountUid)
    ).use { c -> require(c.moveToFirst()) { "financial account not found in campaign" }; accountFrom(c) }

    private fun requireTransaction(uid: String): FinancialTransaction =
        requireNotNull(transactionByUid(uid)) { "financial transaction not found in campaign" }

    private fun transactionByUid(uid: String): FinancialTransaction? = queryTransactions(
        "SELECT $TX_COLUMNS FROM financial_ledger_transactions WHERE campaign_id=? AND financial_transaction_uid=?",
        arrayOf(campaignId, uid)
    ).singleOrNull()

    private fun transactionByCommand(uid: String): FinancialTransaction? = queryTransactions(
        "SELECT $TX_COLUMNS FROM financial_ledger_transactions WHERE campaign_id=? AND command_uid=?",
        arrayOf(campaignId, uid)
    ).singleOrNull()

    private fun queryTransactions(sql: String, args: Array<String>): List<FinancialTransaction> {
        val out = mutableListOf<FinancialTransaction>()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) out += transactionFrom(c) }
        return out
    }

    private fun accountFrom(c: Cursor) = FinancialAccount(
        campaignId=c.getString(0), accountUid=c.getString(1), holder=OwnershipOwnerRef(c.getString(2),c.getString(3)),
        accountTypeUid=c.getString(4), currencyUid=c.getString(5), openedAt=c.getLong(6),
        closedAt=if(c.isNull(7)) null else c.getLong(7), version=c.getLong(8), provenance=c.getString(9)
    )

    private fun transactionFrom(c: Cursor) = FinancialTransaction(
        campaignId=c.getString(0), financialTransactionUid=c.getString(1),
        fromAccountUid=if(c.isNull(2)) null else c.getString(2), toAccountUid=if(c.isNull(3)) null else c.getString(3),
        currencyUid=c.getString(4), amountMinor=c.getLong(5), transactionTypeUid=c.getString(6),
        flowKind=FinancialFlowKind.valueOf(c.getString(7)), reason=c.getString(8), effectiveOrder=c.getLong(9),
        sourceEventUid=if(c.isNull(10)) null else c.getString(10), commandUid=if(c.isNull(11)) null else c.getString(11),
        reversalOfUid=if(c.isNull(12)) null else c.getString(12), provenance=c.getString(13)
    )

    private fun writeTransaction(block: () -> Unit) {
        writeTransactionResult { block(); Unit }
    }

    private fun <T> writeTransactionResult(block: () -> T): T {
        if (db.inTransaction()) return block()
        db.beginTransaction()
        return try {
            val result = block()
            db.setTransactionSuccessful()
            result
        } finally { db.endTransaction() }
    }

    companion object {
        private const val TX_COLUMNS = "campaign_id,financial_transaction_uid,from_account_uid,to_account_uid,currency_uid,amount_minor,transaction_type_uid,flow_kind,reason,effective_order,source_event_uid,command_uid,reversal_of_uid,provenance"
    }
}
