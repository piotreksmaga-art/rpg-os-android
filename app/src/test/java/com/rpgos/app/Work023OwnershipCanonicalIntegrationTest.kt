package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Work023OwnershipCanonicalIntegrationTest {
    private lateinit var file:File

    @Before fun setUp(){file=File.createTempFile("work023-own-",".db").also{it.delete()}}
    @After fun tearDown(){file.delete()}

    @Test fun canonicalTransferLegallyClosesAOpensBAndPreservesUnknownSourceEvent(){
        db().use{d->
            setupOwnership(d)
            val proposal=ownershipProposal("CMD-OWN-COMMIT")
            assertNull(proposal.playerChangeSet.provenance.sourceEventUid)
            val result=TurnTransactionBoundary.create(
                d,TurnTransactionIdentity("C1","TURN-OWN-COMMIT","CMD-OWN-COMMIT","TX-OWN-COMMIT"),proposal
            ).commit()
            assertTrue(result is TurnExecutionResult.Committed)

            val store=OwnershipStore(d,"C1")
            val history=store.history(assetRef())
            assertEquals(2,history.size)
            val closed=history.single{it.ownershipRecordUid=="OWN-1"}
            assertEquals(OwnershipRecordStatus.CLOSED,closed.status)
            assertEquals(10L,closed.validUntil)
            assertNull(closed.closedByEventUid)
            assertEquals("TURN:TX-OWN-COMMIT:CH-OWN",closed.closureProvenance)
            val opened=history.single{it.status==OwnershipRecordStatus.ACTIVE}
            assertEquals(ownerRef("P2"),opened.owner)
            assertEquals(10L,opened.validFrom)
            assertNull(opened.sourceEventUid)
            assertEquals("OWN-1",opened.supersedesRecordUid)
            assertEquals(ownerRef("P2"),store.currentOwnership(assetRef()).single().owner)
            d.rawQuery("SELECT source_event_uid FROM ownership_operations WHERE campaign_id='C1' AND operation_uid='TX-OWN-COMMIT:CH-OWN'",null).use{c->
                assertTrue(c.moveToFirst());assertTrue(c.isNull(0))
            }
            assertEquals(1L,receiptCount(d))
        }
    }

    @Test fun ownershipWriteRollsBackWithLaterTurnFailureAndNoReceiptSurvives(){
        db().use{d->
            setupOwnership(d)
            val proposal=ownershipProposal("CMD-OWN-ROLLBACK")
            val tx=TurnTransactionBoundary.create(
                d,TurnTransactionIdentity("C1","TURN-OWN-ROLLBACK","CMD-OWN-ROLLBACK","TX-OWN-ROLLBACK"),proposal,
                TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("later-effect-failure")}
            )
            assertTrue(runCatching{tx.commit()}.isFailure)
            val store=OwnershipStore(d,"C1")
            assertEquals(ownerRef("P1"),store.currentOwnership(assetRef()).single().owner)
            val history=store.history(assetRef())
            assertEquals(1,history.size)
            assertEquals(OwnershipRecordStatus.ACTIVE,history.single().status)
            assertNull(history.single().validUntil)
            assertEquals(0L,d.rawQuery("SELECT COUNT(*) FROM ownership_operations WHERE campaign_id='C1'",null).use{it.moveToFirst();it.getLong(0)})
            assertEquals(0L,receiptCount(d))
        }
    }

    private fun ownershipProposal(commandUid:String):CanonicalCampaignMutationProposal{
        val actor=CommandActorRef("PLAYER","P1")
        val command=PlayerCommand(
            commandUid=commandUid,campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,
            payload=TransferFundsCommandPayload("A","B",5,"CUR"),provenance=CommandProvenance("WORK-023-OWNERSHIP"),requestedEffectiveOrder=10
        )
        val refs=setOf(
            scoped("PLAYER","P1"),
            scoped(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"A"),
            scoped(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"B"),
            scoped(PlayerResolutionReferenceKinds.CURRENCY,"CUR"),
            scoped("ASSET","A1"),scoped("CHARACTER","P1"),scoped("CHARACTER","P2")
        )
        val context=PlayerResolutionContext.createUnboundGeneric("C1",actor,refs)
        val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(OwnershipComponent())))
        return when(val admission=CampaignMutationBoundary.resolveAndAdmit("C1",engine,command,context)){
            is CampaignMutationAdmission.Accepted->admission.proposal
            is CampaignMutationAdmission.Rejected->error("ownership admission rejected: ${admission.reasonUid}")
        }
    }

    private class OwnershipComponent:PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,TransferFundsCommandPayload::class,"RPGOS-COMPONENT:WORK-023-OWNERSHIP","1"
    ){
        override fun resolve(command:PlayerCommand<TransferFundsCommandPayload>,context:PlayerResolutionContext)=
            PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(changes=listOf(
                    PlayerDomainChange.create(
                        "CH-OWN",PlayerChangeKinds.OWNERSHIP,
                        OwnershipChange("OWN-1",asset(),owner("P1"),owner("P2"),OwnershipShare.full())
                    )
                ))
            )
    }

    private fun setupOwnership(d:SQLiteDatabase){
        CurrentSchema.ensure(d,"C1")
        val refs=OwnershipReferenceRegistry(d,"C1")
        refs.registerAssetKind("ASSET","WORK-023")
        refs.registerOwner(ownerRef("P1"),"WORK-023")
        refs.registerOwner(ownerRef("P2"),"WORK-023")
        refs.registerAsset(assetRef(),"WORK-023")
        OwnershipStore(d,"C1").acquire(
            OwnershipRecord("C1","OWN-1",ownerRef("P1"),assetRef(),"OWNER",OwnershipShare.full(),1,provenance="WORK-023")
        )
        GameplayRuntimeBootstrap.initialize(d,"C1")
    }

    private fun ownerRef(uid:String)=OwnershipOwnerRef("CHARACTER",uid)
    private fun assetRef()=OwnedAssetRef("ASSET","A1")
    private fun scoped(kind:String,uid:String)=CampaignScopedDomainRef("C1",DomainRef(kind,uid))
    private fun receiptCount(d:SQLiteDatabase)=d.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts",null).use{it.moveToFirst();it.getLong(0)}
    private fun db()=SQLiteDatabase.openOrCreateDatabase(file,null)
}
