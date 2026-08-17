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
class Phase26MutationBoundaryTest{
 private lateinit var saveFile:File;private lateinit var coreFile:File
 @Before fun setUp(){saveFile=File.createTempFile("phase26-save-",".db").also{it.delete()};coreFile=File.createTempFile("phase26-core-",".db").also{it.delete()}}
 @After fun tearDown(){saveFile.delete();coreFile.delete()}

 @Test fun P26_01_genericAuthoritativePatchBypassIsRejected(){SQLiteDatabase.openOrCreateDatabase(saveFile,null).use{save->SQLiteDatabase.openOrCreateDatabase(coreFile,null).use{core->save.execSQL("CREATE TABLE player_state(entity_uid TEXT PRIMARY KEY,value INTEGER NOT NULL)");save.execSQL("INSERT INTO player_state VALUES('P1',10)");val r=StatePatchEngine(save,SourceOfTruthRegistry(core)).apply(StatePatch(transactionId="AI",operations=listOf(PatchOperation("update","player_state",mapOf("entity_uid" to "P1"),mapOf("value" to 999))),chapterManifest=emptyMap(),requiresValidation=false));assertFalse(r.success);assertEquals(10L,save.rawQuery("SELECT value FROM player_state",null).use{it.moveToFirst();it.getLong(0)})}}}

 @Test fun P26_02_canonicalEngineResolutionEntersBoundary(){val p=GroupATransactionTestFixtures.admittedFinancialProposal();assertEquals("C1",p.campaignUid);assertEquals(MutationAuthorityClass.GAMEPLAY_AUTHORITATIVE,p.authorityClass)}

 @Test fun P26_03_crossCampaignAdmissionRejectedBeforeCapability(){val actor=CommandActorRef("PLAYER","P1");val cmd=PlayerCommand(commandUid="CMD",campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,payload=TransferFundsCommandPayload("A","B",1,"CUR"),provenance=CommandProvenance("T"),requestedEffectiveOrder=1);val ctx=PlayerResolutionContext.createUnboundGeneric("C1",actor,emptySet());val admission=CampaignMutationBoundary.resolveAndAdmit("OTHER",PlayerDomainEngine(PlayerResolutionComponentRegistry.empty()),cmd,ctx);assertTrue(admission is CampaignMutationAdmission.Rejected);assertEquals(CampaignMutationBoundary.CAMPAIGN_MISMATCH,(admission as CampaignMutationAdmission.Rejected).reasonUid)}

 @Test fun P26_04_manualResolvedCannotBeAdmitted(){assertTrue(CampaignMutationBoundary::class.java.declaredMethods.none{it.name=="admitPlayerProposal"});assertTrue(CanonicalCampaignMutationProposal::class.java.declaredConstructors.none{java.lang.reflect.Modifier.isPublic(it.modifiers)})}

 @Test fun P26_05_administrativeCapabilityRemainsDistinct(){val migration=AdministrativeMutationCapabilities.forMigration("MIGRATION:TEST");assertEquals(MutationAuthorityClass.ADMINISTRATIVE_MIGRATION_INSTALL_RECOVERY,migration.authorityClass)}

 @Test fun P26_06_boundaryDoesNotDuplicateFinanceOwnershipInventory(){val fields=CanonicalCampaignMutationProposal::class.java.declaredFields.map{it.type.name};assertTrue(fields.none{it.contains("FinancialStore")||it.contains("OwnershipStore")||it.contains("InventoryStore")})}
}
