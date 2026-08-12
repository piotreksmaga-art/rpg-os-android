package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerCommandReleaseBlockerHotfixTest {
    private val registry = PlayerCommandKindRegistry.core()

    private fun command() = PlayerCommand(
        commandUid = "HOTFIX-CMD-1",
        campaignUid = "C",
        actor = CommandActorRef("CHARACTER", "ACTOR-1"),
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STRENGTH"), 10, "METHOD"),
        provenance = CommandProvenance("PLAYER_UI", "REQUEST-1", "phase16-hotfix"),
        causationUid = "CAUSE-1",
        correlationUid = "CORR-1",
        requestedEffectiveOrder = 42,
        preconditions = listOf(ExpectedRecordVersion(DomainRef("PLAYER", "ACTOR-1"), 3)),
        extensions = listOf(NamespacedTextCommandExtension("TEST:EXT", 1, "typed"))
    )

    @Test fun p16Hotfix01_unknownRootFieldRejected() {
        val encoded = registry.encode(command())
        fails("UNKNOWN_COMMAND_FIELD") { registry.decode(encoded.replaceFirst("{", "{\"unknownRoot\":\"x\",")) }
    }

    @Test fun p16Hotfix02_unknownPayloadFieldRejected() {
        val encoded = registry.encode(command())
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.decode(encoded.replaceFirst("\"payload\":{", "\"payload\":{\"unknownPayload\":\"x\","))
        }
    }

    @Test fun p16Hotfix03_unknownActorFieldRejected() {
        val encoded = registry.encode(command())
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.decode(encoded.replaceFirst("\"actor\":{", "\"actor\":{\"unknownActor\":\"x\","))
        }
    }

    @Test fun p16Hotfix04_unknownProvenanceFieldRejected() {
        val encoded = registry.encode(command())
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.decode(encoded.replaceFirst("\"provenance\":{", "\"provenance\":{\"unknownProvenance\":\"x\","))
        }
    }

    @Test fun p16Hotfix05_unknownDomainRefFieldRejected() {
        val encoded = registry.encode(command())
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.decode(encoded.replaceFirst("\"focus\":{", "\"focus\":{\"unknownRef\":\"x\","))
        }
    }

    @Test fun p16Hotfix06_unknownPreconditionFieldRejected() {
        val encoded = registry.encode(command())
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.decode(encoded.replaceFirst("\"preconditions\":[{", "\"preconditions\":[{\"unknownPrecondition\":\"x\","))
        }
    }

    @Test fun p16Hotfix07_unknownExtensionFieldRejected() {
        val encoded = registry.encode(command())
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.decode(encoded.replaceFirst("\"extensions\":[{", "\"extensions\":[{\"unknownExtension\":\"x\","))
        }
    }

    @Test fun p16Hotfix08_unsupportedExtensionSchemaVersionRejected() {
        val encoded = registry.encode(command())
        val unsupported = encoded.replace(
            "\"extensionKindUid\":\"TEST:EXT\",\"schemaVersion\":1",
            "\"extensionKindUid\":\"TEST:EXT\",\"schemaVersion\":999"
        )
        assertNotEquals(encoded, unsupported)
        fails("UNSUPPORTED_EXTENSION_SCHEMA_VERSION") { registry.decode(unsupported) }
        fails("UNSUPPORTED_EXTENSION_SCHEMA_VERSION") {
            registry.validate(command().copy(extensions = listOf(NamespacedTextCommandExtension("TEST:EXT", 999, "typed"))))
        }
    }

    @Test fun p16Hotfix09_supportedExtensionVersionAcceptsDeterministicRoundTrip() {
        val encoded = registry.encode(command())
        val decoded = registry.decode(encoded)
        assertEquals(listOf(NamespacedTextCommandExtension("TEST:EXT", 1, "typed")), decoded.extensions)
        assertEquals(encoded, registry.encode(decoded))
    }

    @Test fun p16Hotfix10_knownCommandEncodeDecodeEncodeRemainsByteDeterministic() {
        val first = registry.encode(command())
        val second = registry.encode(registry.decode(first))
        assertEquals(first, second)
    }

    @Test fun p16Hotfix11_knownCommandFingerprintRemainsDeterministic() {
        val cmd = command()
        val fingerprint = registry.fingerprint(cmd)
        repeat(32) { assertEquals(fingerprint, registry.fingerprint(cmd)) }
        assertEquals(fingerprint, registry.fingerprint(registry.decode(registry.encode(cmd))))
    }

    @Test fun p16Hotfix12_commandOperationsStillDoNotMutateAuthoritativeDbState() {
        val db = SQLiteDatabase.create(null)
        try {
            CurrentSchema.ensure(db, "C")
            val before = counts(db)
            val cmd = command()
            registry.validate(cmd)
            val encoded = registry.encode(cmd)
            val decoded = registry.decode(encoded)
            registry.fingerprint(cmd)
            registry.fingerprint(decoded)
            assertEquals(before, counts(db))
        } finally {
            db.close()
        }
    }

    private fun counts(db: SQLiteDatabase): List<Long> = listOf(
        "campaign_truth_records", "player_stats", "player_skills_v2", "player_techniques_v2", "item_instances",
        "financial_ledger_transactions", "asset_records", "development_projects", "project_work_records"
    ).map { table -> db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) } }

    private fun fails(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: PlayerCommandStructuralException) {
            assertEquals(code, e.code)
        }
    }
}
