package com.rpgos.app

enum class CandidateConsistencyDomain { CAMPAIGN, INVENTORY, OWNERSHIP, FINANCE, PROGRESSION, CHARACTER_STATE, LOCATION, EFFECT_EXCLUSIVITY, TEMPORAL, WORLD_RULE }
data class CandidateConsistencyViolation(
    val violationUid:String,val domain:CandidateConsistencyDomain,val subject:DomainRef?,val detailUid:String
){init{require(violationUid.isNotBlank()&&detailUid.isNotBlank())}}

data class CandidateInventoryQuantity(val item:DomainRef,val owner:DomainRef,val quantity:Long){init{require(quantity>=0)}}
data class CandidateOwnershipShare(val subject:DomainRef,val owner:DomainRef,val basisPoints:Long){init{require(basisPoints in 0..10_000)}}
data class CandidateFinancialBalance(val accountUid:String,val currencyUid:String,val balanceMinor:Long){init{require(accountUid.isNotBlank()&&currencyUid.isNotBlank())}}
data class CandidateProgressionValue(val subject:DomainRef,val trackUid:String,val value:Long,val minimum:Long=0,val maximum:Long=Long.MAX_VALUE){init{require(trackUid.isNotBlank()&&minimum<=maximum)}}
data class CandidateLocationState(val subject:DomainRef,val location:DomainRef,val effectiveOrder:Long){init{require(effectiveOrder>=0)}}
data class CandidateExclusiveEffect(val subject:DomainRef,val exclusivityGroupUid:String,val effectUid:String){init{require(exclusivityGroupUid.isNotBlank()&&effectUid.isNotBlank())}}

data class CrossDomainCandidateState(
    val campaignUid:String,
    val baseOrder:Long?,
    val inventory:List<CandidateInventoryQuantity> = emptyList(),
    val ownership:List<CandidateOwnershipShare> = emptyList(),
    val finances:List<CandidateFinancialBalance> = emptyList(),
    val expectedConservedCurrencyTotals:Map<String,Long> = emptyMap(),
    val progression:List<CandidateProgressionValue> = emptyList(),
    val locations:List<CandidateLocationState> = emptyList(),
    val exclusiveEffects:List<CandidateExclusiveEffect> = emptyList(),
    val worldRuleViolationUids:List<String> = emptyList()
){init{require(campaignUid.isNotBlank()&&baseOrder?.let{it>=0}!=false&&worldRuleViolationUids.none{it.isBlank()})}}

class CrossDomainCandidateStateValidator{
    fun validate(state:CrossDomainCandidateState):List<CandidateConsistencyViolation>{
        val violations=mutableListOf<CandidateConsistencyViolation>()
        state.inventory.filter{it.quantity<0}.forEach{violations+=v(CandidateConsistencyDomain.INVENTORY,it.item,"NEGATIVE_INVENTORY")}
        state.inventory.groupBy{it.item to it.owner}.filterValues{it.size>1}.forEach{(key,_)->violations+=v(CandidateConsistencyDomain.INVENTORY,key.first,"DUPLICATE_INVENTORY_SLOT")}
        state.ownership.groupBy{it.subject}.forEach{(subject,shares)->
            val total=shares.sumOf{it.basisPoints};if(total>10_000)violations+=v(CandidateConsistencyDomain.OWNERSHIP,subject,"OWNERSHIP_SHARE_EXCEEDS_100_PERCENT")
            if(shares.groupBy{it.owner}.any{it.value.size>1})violations+=v(CandidateConsistencyDomain.OWNERSHIP,subject,"DUPLICATE_OWNER_SHARE")
        }
        state.finances.groupBy{it.accountUid to it.currencyUid}.filterValues{it.size>1}.forEach{violations+=v(CandidateConsistencyDomain.FINANCE,null,"DUPLICATE_FINANCIAL_BALANCE:${it.key.first}")}
        state.expectedConservedCurrencyTotals.forEach{(currency,total)->
            if(state.finances.filter{it.currencyUid==currency}.sumOf{it.balanceMinor}!=total)violations+=v(CandidateConsistencyDomain.FINANCE,null,"CURRENCY_CONSERVATION:$currency")
        }
        state.progression.filter{it.value !in it.minimum..it.maximum}.forEach{violations+=v(CandidateConsistencyDomain.PROGRESSION,it.subject,"PROGRESSION_OUT_OF_RANGE:${it.trackUid}")}
        state.locations.groupBy{it.subject}.forEach{(subject,values)->
            if(values.groupBy{it.effectiveOrder}.any{(_,same)->same.map{it.location}.distinct().size>1})violations+=v(CandidateConsistencyDomain.LOCATION,subject,"MUTUALLY_EXCLUSIVE_LOCATION")
            if(values.sortedBy{it.effectiveOrder}.zipWithNext().any{(left,right)->right.effectiveOrder<left.effectiveOrder})violations+=v(CandidateConsistencyDomain.TEMPORAL,subject,"LOCATION_ORDER_REGRESSION")
        }
        state.exclusiveEffects.groupBy{it.subject to it.exclusivityGroupUid}.filterValues{values->values.map{it.effectUid}.distinct().size>1}
            .forEach{violations+=v(CandidateConsistencyDomain.EFFECT_EXCLUSIVITY,it.key.first,"MUTUALLY_EXCLUSIVE_EFFECTS:${it.key.second}")}
        state.worldRuleViolationUids.forEach{violations+=v(CandidateConsistencyDomain.WORLD_RULE,null,it)}
        return violations.distinctBy{it.domain to it.subject to it.detailUid}.sortedWith(compareBy<CandidateConsistencyViolation>{it.domain.ordinal}.thenBy{it.detailUid})
    }
    private fun v(domain:CandidateConsistencyDomain,subject:DomainRef?,detail:String)=CandidateConsistencyViolation("P51:${domain.name}:$detail",domain,subject,detail)
}

fun interface CandidateStateProjectionPort{
    /** Read-only simulation of verified candidate ChangeSets. It cannot commit or silently repair. */
    fun project(proposal:ResolvedGmProposal,request:AiGmProposalRequest):CrossDomainCandidateState
}

fun interface CandidateStateConsistencyPort{
    fun rejectionReasons(proposal:ResolvedGmProposal,request:AiGmProposalRequest):List<String>
    companion object{
        val NONE=CandidateStateConsistencyPort{_,_->emptyList()}
        fun validating(projection:CandidateStateProjectionPort,validator:CrossDomainCandidateStateValidator=CrossDomainCandidateStateValidator())=
            CandidateStateConsistencyPort{proposal,request->
                val state=projection.project(proposal,request)
                if(state.campaignUid!=proposal.campaignUid)listOf("P51:CROSS_CAMPAIGN_CANDIDATE_STATE")
                else validator.validate(state).map{"P51:${it.domain.name}:${it.detailUid}"}
            }
    }
}
