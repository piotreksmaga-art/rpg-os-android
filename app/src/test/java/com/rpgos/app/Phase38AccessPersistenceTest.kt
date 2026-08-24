package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Phase38AccessPersistenceTest {
    private lateinit var root:File
    private lateinit var dbFile:File
    private lateinit var snapshots:File

    @Before fun setUp(){
        changesByCommand.clear()
        root=kotlin.io.path.createTempDirectory("p38-access-persistence-").toFile()
        dbFile=File(root,"campaign.db")
        snapshots=File(root,"snapshots")
    }

    @After fun tearDown(){changesByCommand.clear();root.deleteRecursively()}

    @Test fun rollbackLeavesNoPhantomGrantOrReplayPayload(){withDb{db->
        init(db)
        val proposal=proposal("ROLLBACK",grant("G-ROLLBACK"))
        val result=runCatching{
            TurnTransactionBoundary.create(
                db,identity("ROLLBACK"),proposal,
                TurnFailureInjector{if(it==TurnFailurePoint.BEFORE_COMMIT)error("injected")}
            ).commit()
        }
        assertTrue(result.isFailure)
        assertTrue(AccessAuthorityStore(db,CAMPAIGN).effective(PRINCIPAL).isEmpty())
        assertEquals(0L,count(db,Phase38AccessAuthoritySchema.RECORDS))
        assertEquals(0L,count(db,CampaignSnapshotSchema.REPLAY))
    }}

    @Test fun snapshotReplayRestoresRevocationWithoutPhantomVisibility(){withDb{db->
        init(db)
        assertTrue(commit(db,"GRANT",grant("G")).isCommitted())
        assertEquals(1,AccessAuthorityStore(db,CAMPAIGN).effective(PRINCIPAL).size)
        CampaignSnapshotManager(db,CAMPAIGN,snapshots).create()

        assertTrue(commit(db,"REVOKE",revoke("R")).isCommitted())
        assertTrue(AccessAuthorityStore(db,CAMPAIGN).effective(PRINCIPAL).isEmpty())
        val liveDigest=AuthoritativeStateDigest.compute(db)

        val staged=CampaignSnapshotManager(db,CAMPAIGN,snapshots).reconstructToVerifiedStaging()
        SQLiteDatabase.openDatabase(staged.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{restored->
            assertTrue(AccessAuthorityStore(restored,CAMPAIGN).effective(PRINCIPAL).isEmpty())
            assertEquals(2L,count(restored,Phase38AccessAuthoritySchema.RECORDS))
            assertEquals(liveDigest,AuthoritativeStateDigest.compute(restored))
        }
    }}

    private fun TurnExecutionResult<TurnCommitAppliedResult>.isCommitted()=this is TurnExecutionResult.Committed
    private fun withDb(block:(SQLiteDatabase)->Unit)=SQLiteDatabase.openOrCreateDatabase(dbFile,null).use(block)
    private fun init(db:SQLiteDatabase)=GameplayRuntimeBootstrap.initialize(db,CAMPAIGN)
    private fun identity(command:String)=TurnTransactionIdentity(CAMPAIGN,"TURN-$command",command,"TX-$command")
    private fun grant(uid:String)=AccessAuthorityChange(
        AccessOperation.SET_CARRIER_ACCESS,uid,PRINCIPAL.kindUid,PRINCIPAL.uid,AccessGrantKind.EXPLICIT.name,
        ProtectedSubjectAccessRegistry.ORGANIZATION_READ_POLICY_UID,VisibilitySubjectKinds.ORGANIZATION_DATA,"ORGANIZATIONS",1
    )
    private fun revoke(uid:String)=AccessAuthorityChange(
        AccessOperation.REVOKE_GRANT,uid,PRINCIPAL.kindUid,PRINCIPAL.uid,AccessGrantKind.EXPLICIT.name,
        ProtectedSubjectAccessRegistry.ORGANIZATION_READ_POLICY_UID,VisibilitySubjectKinds.ORGANIZATION_DATA,"ORGANIZATIONS",2
    )
    private fun commit(db:SQLiteDatabase,command:String,change:AccessAuthorityChange)=
        TurnTransactionBoundary.create(db,identity(command),proposal(command,change)).commit()

    private fun proposal(command:String,change:AccessAuthorityChange):CanonicalCampaignMutationProposal{
        changesByCommand[command]=change
        val actor=CommandActorRef("PLAYER","P1")
        val cmd=PlayerCommand(
            commandUid=command,
            campaignUid=CAMPAIGN,
            actor=actor,
            commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,
            payload=TransferFundsCommandPayload("A","B",1,"CUR"),
            provenance=CommandProvenance("P38-ACCESS-PERSISTENCE"),
            requestedEffectiveOrder=command.hashCode().toLong().let{if(it==Long.MIN_VALUE)1 else kotlin.math.abs(it)+10}
        )
        val refs=setOf(
            CampaignScopedDomainRef(CAMPAIGN,DomainRef("PLAYER","P1")),
            CampaignScopedDomainRef(CAMPAIGN,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"A")),
            CampaignScopedDomainRef(CAMPAIGN,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"B")),
            CampaignScopedDomainRef(CAMPAIGN,DomainRef(PlayerResolutionReferenceKinds.CURRENCY,"CUR")),
            CampaignScopedDomainRef(CAMPAIGN,DomainRef(PRINCIPAL.kindUid,PRINCIPAL.uid)),
            CampaignScopedDomainRef(CAMPAIGN,DomainRef(VisibilitySubjectKinds.ORGANIZATION_DATA,"ORGANIZATIONS"))
        )
        val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(AccessComponent())))
        return when(val admission=CampaignMutationBoundary.resolveAndAdmit(CAMPAIGN,engine,cmd,PlayerResolutionContext.createUnboundGeneric(CAMPAIGN,actor,refs))){
            is CampaignMutationAdmission.Accepted->admission.proposal
            is CampaignMutationAdmission.Rejected->error("admission rejected: ${admission.reasonUid}")
        }
    }

    private fun count(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getLong(0)}

    private class AccessComponent:PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,TransferFundsCommandPayload::class,"P38-ACCESS-COMPONENT","1"
    ){
        override fun resolve(command:PlayerCommand<TransferFundsCommandPayload>,context:PlayerResolutionContext):PlayerResolutionComponentOutcome{
            val access=requireNotNull(changesByCommand[command.commandUid])
            val changeUid="CHANGE-${command.commandUid}"
            val principal=DomainRef(access.principalKindUid,access.principalUid)
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(
                changes=listOf(PlayerDomainChange.create(changeUid,PlayerChangeKinds.ACCESS_AUTHORITY,access)),
                eventIntents=listOf(PlayerEventIntent.create(
                    "EVENT-${command.commandUid}",PlayerEventIntentKinds.DOMAIN_EFFECT,DomainRef(command.actor.actorKindUid,command.actor.actorUid),
                    listOf(principal),listOf(changeUid),DomainEffectEventIntentPayload(principal,"RPGOS-EFFECT:ACCESS_AUTHORITY_CHANGE")
                ))
            ))
        }
    }

    companion object{
        private const val CAMPAIGN="C1"
        private val PRINCIPAL=VisibilityPrincipalRef(AudienceKinds.PLAYER,"HUMAN_PLAYER")
        private val changesByCommand=mutableMapOf<String,AccessAuthorityChange>()
    }
}
