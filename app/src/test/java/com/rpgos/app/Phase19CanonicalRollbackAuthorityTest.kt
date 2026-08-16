package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase19CanonicalRollbackAuthorityTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val a1 = WorldPackRuleBinding("WORLD-A", "1")
    private val a2 = WorldPackRuleBinding("WORLD-A", "2")

    @Before fun resetProbe() { Probe.calls = 0 }

    @Test fun P19_COHERENCE_06_failedWorldPackTransactionCannotExposeFailedNewContentAsAuthority() {
        val fixture = fixture()
        val prepared = worldPack(File(fixture.worldpacks, ".A.worldpack.prepared-normal"), "WORLD-A", "2")
        runCatching {
            CanonicalPackageReplacement.activatePrepared(prepared, fixture.target, ::validWorldPack) {
                error("saveInstalled failed")
            }
        }.onSuccess { fail("callback failure must fail replacement") }

        assertEquals("1", PackageValidator().validateWorldPack(fixture.target).version)
        assertEquals(a1, fixture.source.currentAuthority().binding)
    }

    @Test fun P19_COHERENCE_07_failedNewQuarantineFailureCannotMakeUncommittedWorldPackAuthoritative() {
        val fixture = fixture()
        createRollbackFailure(fixture)
        assertEquals("2", PackageValidator().validateWorldPack(fixture.target).version)
        assertTrue(fixture.worldpacks.listFiles().orEmpty().any { it.name.startsWith(".A.worldpack.rollback-") })

        try {
            fixture.source.currentAuthority()
            fail("unsettled replacement must fail closed")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("unsettled"))
        }
    }

    @Test fun P19_COHERENCE_08_nextResolutionAfterFailedReplacementSeesOldCommittedOrFailsClosed() {
        val fixture = fixture()
        createRollbackFailure(fixture)
        val resolver = CurrentSelectionWorldPackAuthorityResolver(fixture.source)
        val engine = engine(resolver, CountingProvider(a2))

        try {
            engine.resolve(command("COH08"), context(a2))
            fail("uncommitted authority must fail closed")
        } catch (e: PlayerDomainEngineStructuralException) {
            assertEquals("WORLD_RULE_AUTHORITY_READ_FAILED", e.code)
        }
    }

    @Test fun P19_COHERENCE_09_providerInvocationCountUnderUncommittedAuthorityIsZero() {
        val fixture = fixture()
        createRollbackFailure(fixture)
        val resolver = CurrentSelectionWorldPackAuthorityResolver(fixture.source)
        val engine = engine(resolver, CountingProvider(a2))

        runCatching { engine.resolve(command("COH09"), context(a2)) }
        assertEquals(0, Probe.calls)
    }

    private fun createRollbackFailure(fixture: Fixture) {
        val prepared = worldPack(File(fixture.worldpacks, ".A.worldpack.prepared-fail"), "WORLD-A", "2")
        val ops = object : CanonicalPackageFileOps {
            var renames = 0
            override fun rename(source: File, target: File): Boolean {
                renames++
                return when (renames) {
                    1, 2 -> source.renameTo(target)
                    3 -> false
                    else -> source.renameTo(target)
                }
            }
            override fun deleteRecursively(target: File): Boolean = target.deleteRecursively()
        }
        runCatching {
            CanonicalPackageAuthorityGate.mutate {
                CanonicalPackageReplacement.activatePreparedUnderGate(prepared, fixture.target, ops) {
                    error("saveInstalled failed")
                }
            }
        }
    }

    private fun fixture(): Fixture {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "rpgos").deleteRecursively()
        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val root = File(app.filesDir, "rpgos")
        val saves = File(root, "saves")
        val worldpacks = File(root, "worldpacks")
        val campaign = campaign(File(saves, "C1.campaign"), "C1")
        val target = worldPack(File(worldpacks, "A.worldpack"), "WORLD-A", "1")
        prefs.edit().putString("active_campaign", campaign.name).putString("active_worldpack", target.name).commit()
        return Fixture(worldpacks, target, CanonicalSelectionWorldPackAuthoritySource(prefs, saves, worldpacks))
    }

    private fun engine(authority: WorldPackAuthorityResolver, provider: WorldRuleProvider) = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider)),
        worldPackAuthority = authority
    )

    private fun command(suffix: String) = PlayerCommand(
        commandUid = "CMD-P19-$suffix",
        campaignUid = "C1",
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("P19-CANONICAL")
    )

    private fun context(binding: WorldPackRuleBinding) = PlayerResolutionContext.create(
        "C1",
        actor,
        setOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef("STAT", "STR"))
        ),
        worldRuleMode = WorldRuleMode.Bound(binding)
    )

    private object Probe { var calls = 0 }

    private class CountingProvider(binding: WorldPackRuleBinding) :
        WorldRuleProvider("P19-ROLLBACK-${binding.worldPackVersion}", "1", binding.worldPackUid, binding.worldPackVersion) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            Probe.calls++
            return WorldRuleDecision.Allowed.create("P19-CANONICAL-RULE")
        }
    }

    private class TrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "P19-CANONICAL-ROLLBACK-TRAIN",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext) =
            PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(
                    changes = listOf(
                        PlayerDomainChange.create(
                            "P19-ROLLBACK-CHANGE",
                            PlayerChangeKinds.STAT,
                            StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
                        )
                    )
                )
            )
    }

    private fun validWorldPack(file: File): Boolean =
        runCatching { PackageValidator().validateWorldPack(file).ok }.getOrDefault(false)

    private fun campaign(dir: File, id: String): File {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "campaign.db"), null).close()
        File(dir, "campaign.json").writeText("""{"id":"$id","version":"1","core_api":"1"}""")
        return dir
    }

    private fun worldPack(dir: File, id: String, version: String): File {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText("""{"id":"$id","version":"$version","engine_api":"1"}""")
        return dir
    }

    private data class Fixture(
        val worldpacks: File,
        val target: File,
        val source: CanonicalSelectionWorldPackAuthoritySource
    )
}
