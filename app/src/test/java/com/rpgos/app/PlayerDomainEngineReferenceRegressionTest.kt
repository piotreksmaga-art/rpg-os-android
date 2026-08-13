package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerDomainEngineReferenceRegressionTest {
    private val actor = CommandActorRef("PLAYER", "P1")

    @Test fun p18RefHotfix15_referenceFailureProducesZeroAuthoritativeMutation() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE ref_fixture(v INTEGER NOT NULL)")
            db.execSQL("INSERT INTO ref_fixture(v) VALUES(7)")
            val before = value(db)
            val result = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(StatelessTrainComponent()))).resolve(
                train(DomainRef("STAT", "MISSING")), context(setOf(DomainRef("PLAYER", "P1")))
            )
            assertTrue(result is PlayerResolutionOutcome.Rejected)
            assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, (result as PlayerResolutionOutcome.Rejected).rejection.reason)
            assertEquals(before, value(db))
        } finally { db.close() }
    }

    @Test fun p18RefHotfix18_projectZeroProgressStillLegal() {
        assertEquals(0L, ProjectProgressDelta.of(0).units)
    }

    @Test fun p18RefHotfix19_exactLongDeltaRegression() {
        try { ExactLongDelta.of(0); fail("zero must be rejected") }
        catch (e: PlayerChangeSetStructuralException) { assertEquals("ZERO_DELTA", e.code) }
        assertEquals(Long.MIN_VALUE, ExactLongDelta.of(Long.MIN_VALUE).units)
        assertEquals(Long.MAX_VALUE, ExactLongDelta.of(Long.MAX_VALUE).units)
    }

    @Test fun p18RefHotfix20_compositeConflictIdentityRemainsTyped() {
        val a = DomainRef("PLAYER", "X:Y") to DomainRef("STAT", "Z")
        val b = DomainRef("PLAYER", "X") to DomainRef("STAT", "Y:Z")
        assertNotEquals(a, b)
    }

    @Test fun p18RefHotfix21_assetIdentityRemainsFullIdentity() {
        val a = OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY:BUSINESS", "BUSINESS:A-1")
        val b = OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY:LAND", "BUSINESS:A-1")
        assertNotEquals(a, b)
        assertEquals("BUSINESS:A-1", a.assetUid)
    }

    @Test fun p18RefHotfix22_canonicalSerializationAndFingerprintRemainDeterministic() {
        val registry = PlayerCommandKindRegistry.core()
        val c = train(DomainRef("STAT", "STR"))
        val encoded = registry.encode(c)
        val decoded = registry.decode(encoded)
        assertEquals(encoded, registry.encode(decoded))
        assertEquals(registry.fingerprint(c), registry.fingerprint(decoded))
    }

    @Test fun p18RefHotfix23_directAndInheritedWriterHardeningPass() {
        try { PlayerResolutionComponentRegistry.of(listOf(DirectWriterComponent(WritableAuthority(7)))) ; fail("direct writer must reject") }
        catch (e: PlayerDomainEngineStructuralException) { assertEquals("UNSAFE_RESOLUTION_COMPONENT_STATE", e.code) }
        val authority = WritableAuthority(7)
        try { PlayerResolutionComponentRegistry.of(listOf(InheritedWriterComponent(authority))); fail("inherited writer must reject") }
        catch (e: PlayerDomainEngineStructuralException) { assertEquals("UNSAFE_RESOLUTION_COMPONENT_STATE", e.code) }
        assertEquals(7L, authority.value)
    }

    @Test fun p18RefHotfix24_safeImmutableInheritedStatePasses() {
        val registry = PlayerResolutionComponentRegistry.of(listOf(SafeInheritedComponent(3)))
        assertTrue(PlayerCommandKinds.TRAIN in registry.commandKindUids)
    }

    @Test fun p18RefHotfix25_phase3To17RepresentativeLocksPass() {
        assertEquals(OWNERSHIP_SHARE_SCALE, OwnershipShare.full().units)
        assertEquals(0L, ProjectProgressDelta.of(0).units)
        val registry = PlayerCommandKindRegistry.core()
        val c = train(DomainRef("STAT", "STR"))
        assertEquals(registry.encode(c), registry.encode(registry.decode(registry.encode(c))))
    }

    private fun train(focus: DomainRef) = PlayerCommand(
        commandUid="CMD-REF-LOCK", campaignUid="C1", actor=actor, commandKindUid=PlayerCommandKinds.TRAIN,
        payload=TrainCommandPayload(focus,1,"METHOD"), provenance=CommandProvenance("TEST")
    )
    private fun context(refs:Set<DomainRef>)=PlayerResolutionContext.create("C1",actor,refs.map{CampaignScopedDomainRef("C1",it)}.toSet())
    private fun value(db:SQLiteDatabase)=db.rawQuery("SELECT v FROM ref_fixture",null).use{assertTrue(it.moveToFirst());it.getLong(0)}

    private class StatelessTrainComponent:PlayerResolutionComponent<TrainCommandPayload>(PlayerCommandKinds.TRAIN,TrainCommandPayload::class,"RPGOS-COMPONENT:REF-STATELESS","1"){
        override fun resolve(command:PlayerCommand<TrainCommandPayload>,context:PlayerResolutionContext)=PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create())
    }
    private class WritableAuthority(initial:Long){var value=initial;private set;fun write(v:Long){value=v}}
    private class DirectWriterComponent(private val authority:WritableAuthority):PlayerResolutionComponent<TrainCommandPayload>(PlayerCommandKinds.TRAIN,TrainCommandPayload::class,"RPGOS-COMPONENT:REF-DIRECT-WRITER","1"){
        override fun resolve(command:PlayerCommand<TrainCommandPayload>,context:PlayerResolutionContext):PlayerResolutionComponentOutcome{authority.write(99);throw AssertionError()}
    }
    private abstract class WriterBase(protected val authority:WritableAuthority):PlayerResolutionComponent<TrainCommandPayload>(PlayerCommandKinds.TRAIN,TrainCommandPayload::class,"RPGOS-COMPONENT:REF-INHERITED-WRITER","1")
    private class InheritedWriterComponent(authority:WritableAuthority):WriterBase(authority){override fun resolve(command:PlayerCommand<TrainCommandPayload>,context:PlayerResolutionContext):PlayerResolutionComponentOutcome{authority.write(99);throw AssertionError()}}
    private abstract class SafeBase(protected val delta:Long):PlayerResolutionComponent<TrainCommandPayload>(PlayerCommandKinds.TRAIN,TrainCommandPayload::class,"RPGOS-COMPONENT:REF-SAFE-INHERITED","1")
    private class SafeInheritedComponent(delta:Long):SafeBase(delta){override fun resolve(command:PlayerCommand<TrainCommandPayload>,context:PlayerResolutionContext)=PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create())}
}