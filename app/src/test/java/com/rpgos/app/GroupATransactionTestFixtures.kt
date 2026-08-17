package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

internal object GroupATransactionTestFixtures {
    fun admittedFinancialProposal(
        campaignUid:String="C1",
        commandUid:String="CMD-1",
        amountMinor:Long=5L,
        effectiveOrder:Long=10L,
        fromAccountUid:String="A",
        toAccountUid:String="B"
    ):CanonicalCampaignMutationProposal{
        val actor=CommandActorRef("PLAYER","P1")
        val command=PlayerCommand(
            commandUid=commandUid,campaignUid=campaignUid,actor=actor,
            commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,
            payload=TransferFundsCommandPayload(fromAccountUid,toAccountUid,amountMinor,"CUR"),
            provenance=CommandProvenance("GROUP-A-TEST"),requestedEffectiveOrder=effectiveOrder
        )
        val context=PlayerResolutionContext.createUnboundGeneric(
            campaignUid,actor,setOf(
                CampaignScopedDomainRef(campaignUid,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,fromAccountUid)),
                CampaignScopedDomainRef(campaignUid,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,toAccountUid)),
                CampaignScopedDomainRef(campaignUid,DomainRef(PlayerResolutionReferenceKinds.CURRENCY,"CUR"))
            )
        )
        val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(FinancialComponent())))
        return when(val admission=CampaignMutationBoundary.resolveAndAdmit(campaignUid,engine,command,context)){
            is CampaignMutationAdmission.Accepted->admission.proposal
            is CampaignMutationAdmission.Rejected->error("canonical admission rejected: ${admission.reasonUid}")
        }
    }

    fun setupFinance(db:SQLiteDatabase,campaignUid:String="C1",openingBalance:Long=100L){
        CurrentSchema.ensure(db,campaignUid)
        val owner=OwnershipOwnerRef("CHARACTER","P1")
        OwnershipReferenceRegistry(db,campaignUid).registerOwner(owner,"GROUP-A-TEST")
        val finance=FinancialStore(db,campaignUid)
        runCatching{finance.registerCurrency(CurrencyDefinition("CUR","coin","Coin",1L,"GROUP-A-TEST"))}
        finance.openAccount(FinancialAccount(campaignUid,"A",owner,FINANCIAL_ACCOUNT_TYPE_DEFAULT,"CUR",1L,"GROUP-A-TEST"))
        finance.openAccount(FinancialAccount(campaignUid,"B",owner,FINANCIAL_ACCOUNT_TYPE_DEFAULT,"CUR",1L,"GROUP-A-TEST"))
        finance.creditExternal("OPEN-$campaignUid","A",openingBalance,2L,"opening","GROUP-A-TEST")
    }

    private class FinancialComponent:PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,TransferFundsCommandPayload::class,"RPGOS-COMPONENT:GROUP-A-FINANCIAL","1"
    ){
        override fun resolve(command:PlayerCommand<TransferFundsCommandPayload>,context:PlayerResolutionContext)=
            PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes=listOf(
                PlayerDomainChange.create(
                    "CHANGE-${command.commandUid}",PlayerChangeKinds.FINANCIAL,
                    FinancialChange(command.payload.fromAccountUid,command.payload.toAccountUid,command.payload.amountMinor,command.payload.currencyUid,"RPGOS-FIN-TYPE:TRANSFER")
                )
            )))
    }
}
