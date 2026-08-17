package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

enum class TurnTransactionState { PROPOSED, VALIDATED, IN_PROGRESS, COMMITTED, ROLLED_BACK }
data class TurnTransactionIdentity(val campaignUid:String,val turnUid:String,val commandUid:String,val transactionUid:String){init{require(campaignUid.isNotBlank());require(turnUid.isNotBlank());require(commandUid.isNotBlank());require(transactionUid.isNotBlank())}}
enum class TurnFailurePoint{BEFORE_FIRST_WRITE,AFTER_FIRST_WRITE,AFTER_SECOND_DOMAIN_WRITE,BEFORE_COMMIT,AFTER_RECEIPT_BEFORE_COMMIT}
fun interface TurnFailureInjector{fun failIfRequested(point:TurnFailurePoint);companion object{val NONE=TurnFailureInjector{}}}
data class TurnCommitAppliedResult(val appliedChangeUids:List<String>)
class UnsupportedCanonicalChangeException(val changeKindUid:String):IllegalStateException("RPGOS-TURN-APPLIER:UNSUPPORTED_CHANGE:$changeKindUid")
private val TURN_TRANSACTION_SEAL=Any()

class TurnTransaction internal constructor(private val db:SQLiteDatabase,val identity:TurnTransactionIdentity,private val proposal:CanonicalCampaignMutationProposal,private val failureInjector:TurnFailureInjector,private val seal:Any){
 init{require(seal===TURN_TRANSACTION_SEAL){"RPGOS-TURN-TRANSACTION:FORGED_CAPABILITY"};require(proposal.isCanonical()){"RPGOS-TURN-TRANSACTION:FORGED_PROPOSAL"}}
 private val semanticFingerprint=TurnSemanticFingerprint.forProposal(proposal);private val receiptStore=TurnTransactionReceiptStore(db)
 var state:TurnTransactionState=TurnTransactionState.VALIDATED;private set
 fun commit():TurnExecutionResult<TurnCommitAppliedResult>{
  check(state==TurnTransactionState.VALIDATED){"turn transaction can execute exactly once"};check(!db.inTransaction()){"nested outer TurnTransaction is forbidden"}
  receiptStore.replay(identity,semanticFingerprint)?.let{state=TurnTransactionState.COMMITTED;return TurnExecutionResult.AlreadyCommitted(it)}
  failureInjector.failIfRequested(TurnFailurePoint.BEFORE_FIRST_WRITE);db.beginTransaction();state=TurnTransactionState.IN_PROGRESS
  return try{
   receiptStore.replay(identity,semanticFingerprint)?.let{existing->db.setTransactionSuccessful();db.endTransaction();state=TurnTransactionState.COMMITTED;return TurnExecutionResult.AlreadyCommitted(existing)}
   val applied=withCanonicalGameplayMutationForTurn(db,identity.campaignUid,seal){CanonicalPlayerChangeApplier.applyAll(db,identity,proposal.playerChangeSet,failureInjector)}
   require(applied.appliedChangeUids.size==proposal.playerChangeSet.changes.size){"RPGOS-TURN-APPLIER:INCOMPLETE_CHANGESET_APPLICATION"}
   failureInjector.failIfRequested(TurnFailurePoint.BEFORE_COMMIT);val receipt=receiptStore.appendCommitted(identity,semanticFingerprint);failureInjector.failIfRequested(TurnFailurePoint.AFTER_RECEIPT_BEFORE_COMMIT)
   db.setTransactionSuccessful();db.endTransaction();state=TurnTransactionState.COMMITTED;TurnExecutionResult.Committed(applied,receipt)
  }catch(failure:Throwable){if(db.inTransaction())db.endTransaction();state=TurnTransactionState.ROLLED_BACK;throw failure}
 }
}

private object CanonicalPlayerChangeApplier{
 fun applyAll(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,injector:TurnFailureInjector):TurnCommitAppliedResult{
  val applied=mutableListOf<String>();changeSet.changes.forEach{change->when(val payload=change.payload){is FinancialChange->applyFinancial(db,identity,changeSet,change.changeUid,payload);else->throw UnsupportedCanonicalChangeException(change.changeKindUid)};applied+=change.changeUid;if(applied.size==1)injector.failIfRequested(TurnFailurePoint.AFTER_FIRST_WRITE);if(applied.size==2)injector.failIfRequested(TurnFailurePoint.AFTER_SECOND_DOMAIN_WRITE)};return TurnCommitAppliedResult(applied.toList())
 }
 private fun applyFinancial(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:FinancialChange){
  require(p.amountMinor>0);val effectiveOrder=changeSet.requestedEffectiveOrder?:error("RPGOS-TURN-APPLIER:MISSING_EFFECTIVE_ORDER")
  FinancialStore(db,identity.campaignUid).commit(FinancialTransaction(identity.campaignUid,"${identity.transactionUid}:$changeUid",p.fromAccountUid,p.toAccountUid,p.currencyUid,p.amountMinor,p.transactionTypeUid,FinancialFlowKind.INTERNAL,"Canonical PlayerChangeSet ${changeSet.changeSetUid}",effectiveOrder,"TURN:${identity.transactionUid}:$changeUid",changeSet.provenance.sourceEventUid,identity.commandUid))
 }
}

object TurnTransactionBoundary{
 const val CAMPAIGN_MISMATCH="RPGOS-TURN-TRANSACTION:CAMPAIGN_MISMATCH";const val COMMAND_MISMATCH="RPGOS-TURN-TRANSACTION:COMMAND_MISMATCH"
 internal fun acceptsCanonicalSeal(value:Any)=value===TURN_TRANSACTION_SEAL
 fun create(db:SQLiteDatabase,identity:TurnTransactionIdentity,proposal:CanonicalCampaignMutationProposal,failureInjector:TurnFailureInjector=TurnFailureInjector.NONE):TurnTransaction{
  require(proposal.isCanonical()){"RPGOS-TURN-TRANSACTION:FORGED_PROPOSAL"};require(identity.campaignUid==proposal.campaignUid){CAMPAIGN_MISMATCH};require(identity.commandUid==proposal.playerChangeSet.sourceCommandUid){COMMAND_MISMATCH}
  TurnTransactionReceiptSchema.ensureReady(db);GameplayMutationDatabaseGuards.ensureInstalled(db)
  return TurnTransaction(db,identity,proposal,failureInjector,TURN_TRANSACTION_SEAL)
 }
}
