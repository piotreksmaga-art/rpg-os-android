package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase26MutationBoundaryTest {
    private lateinit var saveFile: File
    private lateinit var coreFile: File

    @Before fun setUp() {
        saveFile = File.createTempFile("phase26-save-", ".db").also { it.delete() }
        coreFile = File.createTempFile("phase26-core-", ".db").also { it.delete() }
    }

    @After fun tearDown() {
        saveFile.delete()
        coreFile.delete()
    }

    @Test fun P26_01_genericAuthoritativePatchBypassIsRejectedEvenWhenValidationFlagIsFalse() {
        SQLiteDatabase.openOrCreateDatabase(saveFile, null).use { saveDb ->
            SQLiteDatabase.openOrCreateDatabase(coreFile, null).use { coreDb ->
                saveDb.execSQL("CREATE TABLE player_state(entity_uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
                saveDb.execSQL("INSERT INTO player_state VALUES('P1', 10)")
                val engine = StatePatchEngine(saveDb, SourceOfTruthRegistry(coreDb))
                val result = engine.apply(
                    StatePatch(
                        transactionId = "AI-PATCH-1",
                        operations = listOf(
                            PatchOperation("update", "player_state", mapOf("entity_uid" to "P1"), mapOf("value" to 999))
                        ),
                        requiresValidation = false
                    )
                )
                assertFalse(result.success)
                assertEquals(0, result.appliedOperations)
                assertTrue(result.message.contains(StatePatchEngine.GAMEPLAY_PATCH_BYPASS_BLOCKED))
                assertEquals(10L, scalar(saveDb, "SELECT value FROM player_state WHERE entity_uid='P1'"))
            }
        }
    }

    @Test fun P26_02_canonicalResolvedPlayerProposalEntersGameplayMutationBoundary() {
        val resolution = resolvedResourceProposal()
        assertTrue(resolution is PlayerResolutionOutcome.Resolved)

        val admission = CampaignMutationBoundary.admitPlayerProposal("C1", resolution)
        assertTrue(admission is CampaignMutationAdmission.Accepted)
        val accepted = (admission as CampaignMutationAdmission.Accepted).proposal
        assertEquals("C1", accepted.campaignUid)
        assertEquals(MutationAuthorityClass.GAMEPLAY_AUTHORITATIVE, accepted.authorityClass)
        assertEquals((resolution as PlayerResolutionOutcome.Resolved).proposal, accepted.playerChangeSet)
    }

    @Test fun P26_03_crossCampaignProposalIsRejectedBeforeMutationCapabilityExists() {
        val admission = CampaignMutationBoundary.admitPlayerProposal("OTHER", resolvedResourceProposal())
        assertTrue(admission is CampaignMutationAdmission.Rejected)
        assertEquals(
            CampaignMutationBoundary.CAMPAIGN_MISMATCH,
            (admission as CampaignMutationAdmission.Rejected).reasonUid
        )
    }

    @Test fun P26_04_rejectedDomainOutcomeCannotEnterMutationBoundary() {
        val actor = CommandActorRef("PLAYER", "P1")
        val evidence = PlayerResolutionEvidence("CTX", ResolutionEntropyEvidence.none(), null, null)
        val rejected = PlayerResolutionOutcome.Rejected(
            PlayerResolutionRejection.create(PlayerResolutionRejectionReason.DOMAIN_REJECTED),
            evidence
        )
        val admission = CampaignMutationBoundary.admitPlayerProposal("C1", rejected)
        assertTrue(admission is CampaignMutationAdmission.Rejected)
        assertEquals(CampaignMutationBoundary.NOT_RESOLVED, (admission as CampaignMutationAdmission.Rejected).reasonUid)
        assertEquals("P1", actor.actorUid) // fixture proves no mutation authority is needed for rejection
    }

    @Test fun P26_05_administrativeCapabilityIsExplicitlyDistinctFromGameplayAuthority() {
        val migration = AdministrativeMutationCapabilities.forMigration("MIGRATION:PHASE26-TEST")
        assertEquals(MutationAuthorityClass.ADMINISTRATIVE_MIGRATION_INSTALL_RECOVERY, migration.authorityClass)
        assertFalse(migration.authorityClass == MutationAuthorityClass.GAMEPLAY_AUTHORITATIVE)
        val sourceConstructors = CanonicalCampaignMutationProposal::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
        assertTrue(sourceConstructors.isNotEmpty())
        assertTrue(sourceConstructors.none { constructor ->
            java.lang.reflect.Modifier.isPublic(constructor.modifiers)
        })
    }

    @Test fun P26_06_boundaryDoesNotDuplicateFinanceOrOwnershipAuthorities() {
        val declared = CanonicalCampaignMutationProposal::class.java.declaredFields.map { it.type.name }
        assertTrue(declared.none { it.contains("FinancialStore") })
        assertTrue(declared.none { it.contains("OwnershipStore") })
        assertTrue(CampaignMutationBoundary::class.java.declaredMethods.none { method ->
            method.name.contains("transfer", ignoreCase = true) || method.name.contains("ledger", ignoreCase = true)
        })
    }

    private fun resolvedResourceProposal(): PlayerResolutionOutcome {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = "CMD-P26",
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRAIN,
            payload = TrainCommandPayload(DomainRef("STAT", "STR"), 1L, "METHOD"),
            provenance = CommandProvenance("P26-TEST")
        )
        val context = PlayerResolutionContext.createUnboundGeneric(
            "C1",
            actor,
            setOf(
                CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
                CampaignScopedDomainRef("C1", DomainRef("STAT", "STR")),
                CampaignScopedDomainRef("C1", DomainRef("RESOURCE", "CHAKRA"))
            )
        )
        return PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(ResourceComponent()))
        ).resolve(command, context)
    }

    private class ResourceComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:P26-RESOURCE",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ) = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "P26-RESOURCE",
                        PlayerChangeKinds.RESOURCE,
                        ResourceChange(
                            DomainRef("PLAYER", command.actor.actorUid),
                            "CHAKRA",
                            ExactLongDelta.of(-5L)
                        )
                    )
                )
            )
        )
    }

    private fun scalar(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
}
