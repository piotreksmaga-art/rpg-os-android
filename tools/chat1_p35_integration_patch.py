from pathlib import Path

def rep(path, old, new):
    p=Path(path); s=p.read_text(); n=s.count(old)
    if n!=1: raise SystemExit(f'{path}: expected 1 match, got {n}')
    p.write_text(s.replace(old,new))

rep('app/src/main/java/com/rpgos/app/WorldRuleProvider.kt',
'''    internal abstract fun evaluate(request: WorldRuleRequest): WorldRuleDecision
}''',
'''    internal open fun canonicalExpectation(reference: CanonReference): CanonicalWorldExpectation? = null
    internal abstract fun evaluate(request: WorldRuleRequest): WorldRuleDecision
}''')

rep('app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt',
'''                val effectSnapshot = WorldRuleEffectSnapshot.create(augmentedDraft)
                evaluateWorldRules(''',
'''                validateCanonDivergenceAuthority(context, augmentedDraft)
                val effectSnapshot = WorldRuleEffectSnapshot.create(augmentedDraft)
                evaluateWorldRules(''')

marker='''    private fun validatePlayerInvariants(
        proposal: PlayerChangeSet,
        resolutionEvidence: PlayerResolutionEvidence
    ): PlayerResolutionOutcome {'''
method='''    private fun validateCanonDivergenceAuthority(context:PlayerResolutionContext,draft:PlayerResolutionDraft){
        val divergent=draft.changes.mapNotNull{change->
            val truth=change.payload as? CampaignTruthChange ?: return@mapNotNull null
            truth.canonDivergence?.let{truth to it}
        }
        if(divergent.isEmpty())return
        val binding=(context.worldRuleMode as? WorldRuleMode.Bound)?.binding?:fail("CANON_DIVERGENCE_REQUIRES_BOUND_WORLD_PACK")
        val authoritative=try{worldPackAuthority.bindingForCampaign(context.campaignUid)}catch(e:Throwable){throw PlayerDomainEngineStructuralException("WORLD_RULE_AUTHORITY_READ_FAILED",e)}
            ?:fail("CANON_DIVERGENCE_WORLD_PACK_AUTHORITY_MISSING")
        if(authoritative!=binding)fail("CANON_DIVERGENCE_WORLD_PACK_AUTHORITY_MISMATCH")
        val provider=worldRuleRegistry.providerFor(binding)?:fail("CANON_DIVERGENCE_WORLD_RULE_PROVIDER_MISSING")
        divergent.forEach{(truth,spec)->
            if(spec.provenanceStatus!=HistoricalProvenanceStatus.RECORDED)fail("CANON_DIVERGENCE_GAMEPLAY_PROVENANCE_NOT_RECORDED")
            if(spec.worldPackUid!=binding.worldPackUid)fail("CANON_DIVERGENCE_WORLD_PACK_UID_MISMATCH")
            if(spec.worldPackVersion!=binding.worldPackVersion)fail("CANON_DIVERGENCE_WORLD_PACK_VERSION_MISMATCH")
            val expectation=try{provider.canonicalExpectation(spec.canonicalReference)}catch(e:Throwable){throw PlayerDomainEngineStructuralException("CANON_DIVERGENCE_EXPECTATION_LOOKUP_FAILED",e)}
                ?:fail("CANON_DIVERGENCE_EXPECTATION_NOT_FOUND")
            if(expectation.canonicalReference!=spec.canonicalReference)fail("CANON_DIVERGENCE_EXPECTATION_REFERENCE_MISMATCH")
            if(expectation.kind!=spec.kind)fail("CANON_DIVERGENCE_EXPECTATION_KIND_MISMATCH")
            if(expectation.expectedCanonicalValue!=spec.expectedCanonicalValue)fail("CANON_DIVERGENCE_EXPECTED_VALUE_MISMATCH")
            val actual=truth.objectValue?:fail("CANON_DIVERGENCE_ACTUAL_VALUE_NOT_BINDABLE")
            if(actual!=spec.actualCampaignValue)fail("CANON_DIVERGENCE_ACTUAL_VALUE_MISMATCH")
        }
    }

'''
rep('app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt',marker,method+marker)

rep('app/src/main/java/com/rpgos/app/TurnTransaction.kt',
'''            val receipt=withCanonicalCommitEvidenceForTurn(db,identity.campaignUid,seal){
                CommittedReplayPayloadStore(db).append(
                    identity,commitOrder,semanticFingerprint,requiredManifest.summary,proposal.playerChangeSet,
                    causalRelationIntents,eventBoundaryUid
                )
                val committed=receiptStore.appendCommitted(identity,semanticFingerprint,commitOrder,requiredManifest.summary)
                failureInjector.failIfRequested(TurnFailurePoint.AFTER_RECEIPT_BEFORE_COMMIT)
                committed
            }
            db.setTransactionSuccessful()''',
'''            val receipt=withCanonicalCommitEvidenceForTurn(db,identity.campaignUid,seal){
                CommittedReplayPayloadStore(db).append(
                    identity,commitOrder,semanticFingerprint,requiredManifest.summary,proposal.playerChangeSet,
                    causalRelationIntents,eventBoundaryUid
                )
                val committed=receiptStore.appendCommitted(identity,semanticFingerprint,commitOrder,requiredManifest.summary)
                failureInjector.failIfRequested(TurnFailurePoint.AFTER_RECEIPT_BEFORE_COMMIT)
                committed
            }
            val divergenceAuthorizations=CanonicalPlayerChangeApplier.divergenceAuthorizations(db,identity,proposal.playerChangeSet)
            if(divergenceAuthorizations.isNotEmpty()){
                withCanonicalGameplayMutationForTurn(db,identity.campaignUid,seal){
                    withCanonicalDivergenceCommitAuthorityForTurn(db,identity,seal,divergenceAuthorizations){
                        CanonicalPlayerChangeApplier.recordCommittedDivergences(db,identity,divergenceAuthorizations)
                    }
                }
            }
            db.setTransactionSuccessful()''')

rep('app/src/main/java/com/rpgos/app/TurnTransaction.kt',
'''        changeSet.changes.forEach{change->
            when(change.payload){
                is StatChange,is ResourceChange,is SkillChange,is TechniqueChange,is InventoryChange,
                is EquipmentChange,is FinancialChange,is OwnershipChange,is CampaignTruthChange,
                is DevelopmentProjectChange -> Unit
                else -> throw UnsupportedCanonicalChangeException(change.changeKindUid)
            }
        }''',
'''        changeSet.changes.forEach{change->
            when(change.payload){
                is StatChange,is ResourceChange,is SkillChange,is TechniqueChange,is InventoryChange,
                is EquipmentChange,is FinancialChange,is OwnershipChange,is CampaignTruthChange,
                is DevelopmentProjectChange -> Unit
                else -> throw UnsupportedCanonicalChangeException(change.changeKindUid)
            }
            val truth=change.payload as? CampaignTruthChange
            if(truth?.canonDivergence!=null)require(changeSet.eventIntents.count{change.changeUid in it.causalChangeUids}==1){"RPGOS-CANON:DIVERGENCE_REQUIRES_EXACT_EVENT"}
        }''')

marker2='''    private fun effectiveOrder(changeSet:PlayerChangeSet):Long=
        changeSet.requestedEffectiveOrder?:error("RPGOS-TURN-APPLIER:MISSING_EFFECTIVE_ORDER")'''
methods2='''    fun divergenceAuthorizations(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet):List<CanonicalDivergenceCommitAuthorization> =
        changeSet.changes.mapNotNull{change->
            val truth=change.payload as? CampaignTruthChange ?: return@mapNotNull null
            val spec=truth.canonDivergence ?: return@mapNotNull null
            val intent=changeSet.eventIntents.singleOrNull{change.changeUid in it.causalChangeUids}?:error("RPGOS-CANON:DIVERGENCE_REQUIRES_EXACT_EVENT")
            CanonicalDivergenceCommitAuthorization(spec,CampaignEventStore(db,identity.campaignUid).eventUid(identity,changeSet,intent))
        }

    fun recordCommittedDivergences(db:SQLiteDatabase,identity:TurnTransactionIdentity,items:List<CanonicalDivergenceCommitAuthorization>){
        val store=CanonDivergenceStore(db,identity.campaignUid);items.forEach{store.recordCommitted(it.spec,identity,it.eventUid)}
    }

'''
rep('app/src/main/java/com/rpgos/app/TurnTransaction.kt',marker2,methods2+marker2)

rep('app/src/main/java/com/rpgos/app/TurnTransaction.kt',
'''        p.canonDivergence?.let { divergence ->
            val intent = changeSet.eventIntents.singleOrNull { changeUid in it.causalChangeUids }
                ?: error("RPGOS-CANON:DIVERGENCE_REQUIRES_EXACT_EVENT")
            val eventUid = CampaignEventStore(db, identity.campaignUid).eventUid(identity, changeSet, intent)
            CanonDivergenceStore(db, identity.campaignUid).recordCommitted(divergence, identity, eventUid)
        }
''','')

rep('app/src/main/java/com/rpgos/app/TurnTransaction.kt',
'''            withCanonicalCommitEvidenceForTurn(db,payload.identity.campaignUid,TURN_TRANSACTION_SEAL){
                CommittedReplayPayloadStore(db).append(payload.identity,payload.commitOrder,payload.semanticFingerprint,payload.eventManifest,payload.changeSet,payload.causalPlan,boundary)
                TurnTransactionReceiptStore(db).appendCommitted(payload.identity,payload.semanticFingerprint,payload.commitOrder,payload.eventManifest)
            }
            db.setTransactionSuccessful()''',
'''            withCanonicalCommitEvidenceForTurn(db,payload.identity.campaignUid,TURN_TRANSACTION_SEAL){
                CommittedReplayPayloadStore(db).append(payload.identity,payload.commitOrder,payload.semanticFingerprint,payload.eventManifest,payload.changeSet,payload.causalPlan,boundary)
                TurnTransactionReceiptStore(db).appendCommitted(payload.identity,payload.semanticFingerprint,payload.commitOrder,payload.eventManifest)
            }
            val divergenceAuthorizations=CanonicalPlayerChangeApplier.divergenceAuthorizations(db,payload.identity,payload.changeSet)
            if(divergenceAuthorizations.isNotEmpty())withCanonicalGameplayMutationForTurn(db,payload.identity.campaignUid,TURN_TRANSACTION_SEAL){
                withCanonicalDivergenceCommitAuthorityForTurn(db,payload.identity,TURN_TRANSACTION_SEAL,divergenceAuthorizations){CanonicalPlayerChangeApplier.recordCommittedDivergences(db,payload.identity,divergenceAuthorizations)}
            }
            db.setTransactionSuccessful()''')
print('Phase35 integration patched')
