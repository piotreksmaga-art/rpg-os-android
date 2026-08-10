package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Bounded GM-context projection. The ledger remains unbounded authority. */
class FinancialContextReader(private val db: SQLiteDatabase, private val campaignId: String) {
    init { MigrationManager().ensureV13BalanceGuards(db, campaignId) }

    fun forPlayerUid(playerUid: String): Map<String, Any?> {
        require(playerUid.isNotBlank()) { "playerUid must not be blank" }
        val accounts = mutableListOf<Map<String, Any?>>()
        db.rawQuery(
            """SELECT a.account_uid,a.holder_kind_uid,a.holder_uid,a.account_type_uid,a.currency_uid,
                      b.balance_minor,a.opened_order,a.closed_order
               FROM financial_accounts a
               JOIN financial_account_balances b ON b.campaign_id=a.campaign_id AND b.account_uid=a.account_uid
               WHERE a.campaign_id=? AND a.holder_uid=? AND a.closed_order IS NULL
               ORDER BY a.holder_kind_uid,a.currency_uid,a.account_uid LIMIT 64""".trimIndent(),
            arrayOf(campaignId, playerUid)
        ).use { c ->
            while (c.moveToNext()) {
                accounts += linkedMapOf(
                    "account_uid" to c.getString(0),
                    "holder_kind_uid" to c.getString(1),
                    "holder_uid" to c.getString(2),
                    "account_type_uid" to c.getString(3),
                    "currency_uid" to c.getString(4),
                    "balance_minor" to c.getLong(5),
                    "opened_order" to c.getLong(6),
                    "closed_order" to if (c.isNull(7)) null else c.getLong(7)
                )
            }
        }
        val recent = mutableListOf<Map<String, Any?>>()
        if (accounts.isNotEmpty()) {
            db.rawQuery(
                """SELECT financial_transaction_uid,from_account_uid,to_account_uid,currency_uid,amount_minor,
                          transaction_type_uid,flow_kind,reason,effective_order,source_event_uid,command_uid,reversal_of_uid,provenance
                   FROM financial_ledger_transactions
                   WHERE campaign_id=? AND (from_account_uid IN (SELECT account_uid FROM financial_accounts WHERE campaign_id=? AND holder_uid=?)
                                          OR to_account_uid IN (SELECT account_uid FROM financial_accounts WHERE campaign_id=? AND holder_uid=?))
                   ORDER BY effective_order DESC,financial_transaction_uid DESC LIMIT 40""".trimIndent(),
                arrayOf(campaignId,campaignId,playerUid,campaignId,playerUid)
            ).use { c ->
                while(c.moveToNext()) recent += linkedMapOf(
                    "financial_transaction_uid" to c.getString(0),
                    "from_account_uid" to if(c.isNull(1)) null else c.getString(1),
                    "to_account_uid" to if(c.isNull(2)) null else c.getString(2),
                    "currency_uid" to c.getString(3),
                    "amount_minor" to c.getLong(4),
                    "transaction_type_uid" to c.getString(5),
                    "flow_kind" to c.getString(6),
                    "reason" to c.getString(7),
                    "effective_order" to c.getLong(8),
                    "source_event_uid" to if(c.isNull(9)) null else c.getString(9),
                    "command_uid" to if(c.isNull(10)) null else c.getString(10),
                    "reversal_of_uid" to if(c.isNull(11)) null else c.getString(11),
                    "provenance" to c.getString(12)
                )
            }
        }
        return linkedMapOf(
            "authority_source" to "FINANCIAL_LEDGER",
            "accounts" to accounts,
            "recent_transactions" to recent,
            "legacy_character_finances_authoritative" to false
        )
    }
}
