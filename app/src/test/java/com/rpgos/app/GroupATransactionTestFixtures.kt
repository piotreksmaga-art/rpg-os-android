package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

internal object GroupATransactionTestFixtures {
    fun admittedFinancialProposal(
        campaignUid:String="C1",commandUid:String="CMD-1",amountMinor:Long=5L,effectiveOrder:Long=10L,
        fromAccountUid:String="A",toAccountUid:String="B",changeCount:Int=1,includeUnsupportedResource:Boolean=false
    ):CanonicalCampaignMutationProposal{
        require(changeCount in 1..2)
        val actor=CommandActorRef("PLAYER","P1")
        val command=PlayerCommand(commandUid=commandUid,campaignUid=campaignUid,actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,
            payload=TransferFundsCommandPayload(fromAccountUid,toAccountUid,amountMinor,"CUR"),provenance=CommandProvenance("GROUP-A-TEST"),requestedEffectiveOrder=effectiveOrder)
        val refs=mutableSetOf(
            CampaignScopedDomainRef(campaignUid,DomainRef("PLAYER","P1")),
            CampaignScopedDomainRef(campaignUid,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,fromAccountUid)),
            CampaignScopedDomainRef(campaignUid,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,toAccountUid)),
            CampaignScopedDomainRef(campaignUid,DomainRef(PlayerResolutionReferenceKinds.CURRENCY,"CUR")))
        if(changeCount==2){refs+=CampaignScopedDomainRef(campaignUid,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"C"));refs+=CampaignScopedDomainRef(campaignUid,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"D"))}
        if(includeUnsupportedResource){refs+=CampaignScopedDomainRef(campaignUid,DomainRef("RESOURCE","ENERGY"));refs+=CampaignScopedDomainRef(campaignUid,DomainRef("PLAYER","P1"))}
        val context=PlayerResolutionContext.createUnboundGeneric(campaignUid,actor,refs)
        val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(FinancialComponent(changeCount,includeUnsupportedResource))))
        return when(val admission=CampaignMutationBoundary.resolveAndAdmit(campaignUid,engine,command,context)){is CampaignMutationAdmission.Accepted->admission.proposal;is CampaignMutationAdmission.Rejected->error("canonical admission rejected: ${admission.reasonUid}")}
    }

    fun setupFinance(db:SQLiteDatabase,campaignUid:String="C1",openingBalance:Long=100L){
        GameplayRuntimeBootstrap.initialize(db,campaignUid)
        withAdministrativeMutationAuthority(db,campaignUid) {
            val owner=OwnershipOwnerRef("CHARACTER","P1")
            OwnershipReferenceRegistry(db,campaignUid).registerOwner(owner,"GROUP-A-TEST")
            val finance=FinancialStore(db,campaignUid)
            runCatching{finance.registerCurrency(CurrencyDefinition("CUR","coin","Coin",1L,"GROUP-A-TEST"))}
            listOf("A","B","C","D").forEach{accountUid->finance.openAccount(FinancialAccount(campaignUid,accountUid,owner,FINANCIAL_ACCOUNT_TYPE_DEFAULT,"CUR",1L,"GROUP-A-TEST"))}
            finance.creditExternal("OPEN-$campaignUid-A","A",openingBalance,2L,"opening","GROUP-A-TEST")
            finance.creditExternal("OPEN-$campaignUid-C","C",openingBalance,3L,"opening","GROUP-A-TEST")
        }
    }

    private class FinancialComponent(private val count:Int,private val unsupported:Boolean):PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS,TransferFundsCommandPayload::class,"RPGOS-COMPONENT:GROUP-A-FINANCIAL","1"){
        override fun resolve(command:PlayerCommand<TransferFundsCommandPayload>,context:PlayerResolutionContext):PlayerResolutionComponentOutcome{
            val changes=mutableListOf<PlayerDomainChange>();changes+=PlayerDomainChange.create("CHANGE-${command.commandUid}-1",PlayerChangeKinds.FINANCIAL,FinancialChange(command.payload.fromAccountUid,command.payload.toAccountUid,command.payload.amountMinor,command.payload.currencyUid,"RPGOS-FIN-TYPE:TRANSFER"));if(count==2)changes+=PlayerDomainChange.create("CHANGE-${command.commandUid}-2",PlayerChangeKinds.FINANCIAL,FinancialChange("C","D",command.payload.amountMinor,command.payload.currencyUid,"RPGOS-FIN-TYPE:TRANSFER"));if(unsupported)changes+=PlayerDomainChange.create("CHANGE-${command.commandUid}-RESOURCE",PlayerChangeKinds.RESOURCE,ResourceChange(DomainRef("PLAYER","P1"),"ENERGY",ExactLongDelta.of(-1)));return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes=changes))
        }
    }
}
