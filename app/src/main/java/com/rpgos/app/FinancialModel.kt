package com.rpgos.app

const val FINANCIAL_ACCOUNT_TYPE_DEFAULT = "RPGOS-FIN-ACCOUNT:DEFAULT"

enum class FinancialFlowKind { INTERNAL, SOURCE, SINK, REVERSAL }

data class CurrencyDefinition(
    val currencyUid: String,
    val currencyKey: String,
    val displayName: String,
    val minorUnitScale: Long,
    val provenance: String,
    val status: String = "ACTIVE"
)

data class FinancialAccount(
    val campaignId: String,
    val accountUid: String,
    val holder: OwnershipOwnerRef,
    val accountTypeUid: String,
    val currencyUid: String,
    val openedAt: Long,
    val provenance: String,
    val closedAt: Long? = null,
    val version: Long = 1L
)

data class FinancialTransaction(
    val campaignId: String,
    val financialTransactionUid: String,
    val fromAccountUid: String?,
    val toAccountUid: String?,
    val currencyUid: String,
    val amountMinor: Long,
    val transactionTypeUid: String,
    val flowKind: FinancialFlowKind,
    val reason: String,
    val effectiveOrder: Long,
    val provenance: String,
    val sourceEventUid: String? = null,
    val commandUid: String? = null,
    val reversalOfUid: String? = null
)

data class FinancialCommitResult(
    val transaction: FinancialTransaction,
    val idempotentReplay: Boolean
)

object FinancialPolicy {
    fun validateCurrency(definition: CurrencyDefinition) {
        require(definition.currencyUid.isNotBlank()) { "currencyUid must not be blank" }
        require(definition.currencyKey.isNotBlank()) { "currencyKey must not be blank" }
        require(definition.displayName.isNotBlank()) { "displayName must not be blank" }
        require(definition.minorUnitScale > 0L) { "minorUnitScale must be positive" }
        require(definition.provenance.isNotBlank()) { "currency provenance must not be blank" }
        require(definition.status in setOf("ACTIVE", "RETIRED")) { "invalid currency status" }
    }

    fun validateAccount(account: FinancialAccount) {
        require(account.campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(account.accountUid.isNotBlank()) { "accountUid must not be blank" }
        OwnershipPolicy.validateOwner(account.holder)
        require(account.accountTypeUid.isNotBlank()) { "accountTypeUid must not be blank" }
        require(account.currencyUid.isNotBlank()) { "currencyUid must not be blank" }
        require(account.provenance.isNotBlank()) { "account provenance must not be blank" }
        require(account.version >= 1L) { "account version must be at least 1" }
        require(account.closedAt == null || account.closedAt > account.openedAt) { "account interval must be [openedAt, closedAt)" }
    }

    fun validateTransaction(transaction: FinancialTransaction) {
        require(transaction.campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(transaction.financialTransactionUid.isNotBlank()) { "financialTransactionUid must not be blank" }
        require(transaction.currencyUid.isNotBlank()) { "currencyUid must not be blank" }
        require(transaction.amountMinor > 0L) { "financial amount must be positive" }
        require(transaction.transactionTypeUid.isNotBlank()) { "transactionTypeUid must not be blank" }
        require(transaction.reason.isNotBlank()) { "financial reason must not be blank" }
        require(transaction.provenance.isNotBlank()) { "financial provenance must not be blank" }
        require(transaction.sourceEventUid == null || transaction.sourceEventUid.isNotBlank()) { "sourceEventUid must be null or nonblank" }
        require(transaction.commandUid == null || transaction.commandUid.isNotBlank()) { "commandUid must be null or nonblank" }
        require(transaction.reversalOfUid == null || transaction.reversalOfUid.isNotBlank()) { "reversalOfUid must be null or nonblank" }
        when (transaction.flowKind) {
            FinancialFlowKind.INTERNAL -> {
                require(transaction.fromAccountUid != null && transaction.toAccountUid != null) { "internal transfer requires source and destination accounts" }
                require(transaction.fromAccountUid != transaction.toAccountUid) { "source and destination accounts must differ" }
            }
            FinancialFlowKind.SOURCE -> require(transaction.fromAccountUid == null && transaction.toAccountUid != null) { "source flow requires only destination account" }
            FinancialFlowKind.SINK -> require(transaction.fromAccountUid != null && transaction.toAccountUid == null) { "sink flow requires only source account" }
            FinancialFlowKind.REVERSAL -> {
                require(transaction.reversalOfUid != null) { "reversal requires reversalOfUid" }
                require(transaction.fromAccountUid != transaction.toAccountUid) { "reversal endpoints must differ" }
            }
        }
    }
}
