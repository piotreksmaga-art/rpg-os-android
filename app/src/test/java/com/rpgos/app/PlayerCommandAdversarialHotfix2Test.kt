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
class PlayerCommandAdversarialHotfix2Test {
    private val registry = PlayerCommandKindRegistry.core()

    private fun command() = PlayerCommand(
        commandUid = "HOTFIX2-CMD-1",
        campaignUid = "C",
        actor = CommandActorRef("CHARACTER", "ACTOR-1"),
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STRENGTH"), 10, "METHOD"),
        provenance = CommandProvenance("PLAYER_UI", "REQUEST-1", "phase16-hotfix2"),
        causationUid = "CAUSE-1",
        correlationUid = "CORR-1",
        requestedEffectiveOrder = 42,
        preconditions = listOf(ExpectedRecordVersion(DomainRef("PLAYER", "ACTOR-1"), 3)),
        extensions = listOf(NamespacedTextCommandExtension("TEST:EXT", 1, "typed"))
    )

    @Test fun p16Hotfix2_01_rootRequiredStringAsNumberRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_STRING_TYPE") {
            registry.decode(encoded.replace("\"commandUid\":\"HOTFIX2-CMD-1\"", "\"commandUid\":123"))
        }
    }

    @Test fun p16Hotfix2_02_rootStringAsBooleanObjectOrArrayRejected() {
        val encoded = registry.encode(command())
        listOf("true", "{}", "[]").forEach { malformed ->
            fails("INVALID_JSON_STRING_TYPE") {
                registry.decode(encoded.replace("\"campaignUid\":\"C\"", "\"campaignUid\":$malformed"))
            }
        }
    }

    @Test fun p16Hotfix2_03_actorStringWrongScalarTypeRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_STRING_TYPE") {
            registry.decode(encoded.replace("\"actorUid\":\"ACTOR-1\"", "\"actorUid\":123"))
        }
    }

    @Test fun p16Hotfix2_04_provenanceStringWrongScalarTypeRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_STRING_TYPE") {
            registry.decode(encoded.replace("\"sourceKindUid\":\"PLAYER_UI\"", "\"sourceKindUid\":false"))
        }
    }

    @Test fun p16Hotfix2_05_domainRefStringWrongScalarTypeRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_STRING_TYPE") {
            registry.decode(encoded.replace("\"kindUid\":\"STAT\"", "\"kindUid\":7"))
        }
    }

    @Test fun p16Hotfix2_06_payloadStringWrongScalarTypeRejected() {
        val cmd = PlayerCommand(
            commandUid = "PAYLOAD-STRING",
            campaignUid = "C",
            actor = CommandActorRef("CHARACTER", "ACTOR-1"),
            commandKindUid = PlayerCommandKinds.LEARN_SKILL,
            payload = LearnSkillCommandPayload("SKILL-1", "METHOD"),
            provenance = CommandProvenance("PLAYER_UI")
        )
        val encoded = registry.encode(cmd)
        fails("INVALID_JSON_STRING_TYPE") {
            registry.decode(encoded.replace("\"skillUid\":\"SKILL-1\"", "\"skillUid\":123"))
        }
    }

    @Test fun p16Hotfix2_07_preconditionStringWrongScalarTypeRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_STRING_TYPE") {
            registry.decode(encoded.replace("\"kind\":\"EXPECTED_RECORD_VERSION\"", "\"kind\":123"))
        }
    }

    @Test fun p16Hotfix2_08_extensionStringWrongScalarTypeRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_STRING_TYPE") {
            registry.decode(encoded.replace("\"extensionKindUid\":\"TEST:EXT\"", "\"extensionKindUid\":false"))
        }
    }

    @Test fun p16Hotfix2_09_optionalStringPresentAsNonStringRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_STRING_TYPE") {
            registry.decode(encoded.replace("\"causationUid\":\"CAUSE-1\"", "\"causationUid\":123"))
        }
        val canonicalNull = registry.encode(command().copy(causationUid = null))
        assertEquals(canonicalNull, registry.encode(registry.decode(canonicalNull)))
    }

    @Test fun p16Hotfix2_10_duplicateRootKnownKeyRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("{", "{\"commandUid\":\"ATTACKER-A\","))
        }
    }

    @Test fun p16Hotfix2_11_duplicateRootUnknownKeyRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("{", "{\"futureSemantic\":1,\"futureSemantic\":2,"))
        }
    }

    @Test fun p16Hotfix2_12_duplicatePayloadKnownKeyRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("\"payload\":{", "\"payload\":{\"effortUnits\":999,"))
        }
    }

    @Test fun p16Hotfix2_13_duplicateActorKeyRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("\"actor\":{", "\"actor\":{\"actorUid\":\"ATTACKER\","))
        }
    }

    @Test fun p16Hotfix2_14_duplicateProvenanceKeyRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("\"provenance\":{", "\"provenance\":{\"sourceKindUid\":\"ATTACKER\","))
        }
    }

    @Test fun p16Hotfix2_15_duplicateDomainRefKeyRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("\"focus\":{", "\"focus\":{\"uid\":\"ATTACKER\","))
        }
    }

    @Test fun p16Hotfix2_16_duplicatePreconditionKeyRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("\"preconditions\":[{", "\"preconditions\":[{\"kind\":\"EXPECTED_RECORD_VERSION\","))
        }
    }

    @Test fun p16Hotfix2_17_duplicateExtensionKeyRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("\"extensions\":[{", "\"extensions\":[{\"value\":\"ATTACKER\","))
        }
    }

    @Test fun p16Hotfix2_18_duplicateIdenticalAndEscapedEquivalentKeyRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("{", "{\"\\u0063ommandUid\":\"HOTFIX2-CMD-1\","))
        }
    }

    @Test fun p16Hotfix2_19_deeplyNestedDuplicateRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(
                encoded.replaceFirst(
                    "\"target\":{\"kindUid\":\"PLAYER\",\"uid\":\"ACTOR-1\"}",
                    "\"target\":{\"kindUid\":\"PLAYER\",\"uid\":\"DEEP-ATTACKER\",\"uid\":\"ACTOR-1\"}"
                )
            )
        }
    }

    @Test fun p16Hotfix2_20_validCanonicalCommandRemainsByteDeterministic() {
        val encoded = registry.encode(command())
        assertEquals(encoded, registry.encode(registry.decode(encoded)))
    }

    @Test fun p16Hotfix2_21_validFingerprintRemainsDeterministic() {
        val cmd = command()
        val fingerprint = registry.fingerprint(cmd)
        repeat(64) { assertEquals(fingerprint, registry.fingerprint(cmd)) }
        assertEquals(fingerprint, registry.fingerprint(registry.decode(registry.encode(cmd))))
    }

    @Test fun p16Hotfix2_22_supportedExtensionSchemaVersionOneStillPasses() {
        val cmd = command().copy(extensions = listOf(NamespacedTextCommandExtension("TEST:EXT", 1, "typed")))
        registry.validate(cmd)
        val encoded = registry.encode(cmd)
        assertEquals(encoded, registry.encode(registry.decode(encoded)))
    }

    @Test fun p16Hotfix2_23_unsupportedExtensionVersionMatrixStillRejected() {
        val encoded = registry.encode(command())
        listOf(-1, 0, 2, 999, Int.MAX_VALUE).forEach { version ->
            val malformed = encoded.replace(
                "\"extensionKindUid\":\"TEST:EXT\",\"schemaVersion\":1",
                "\"extensionKindUid\":\"TEST:EXT\",\"schemaVersion\":$version"
            )
            assertNotEquals(encoded, malformed)
            fails("UNSUPPORTED_EXTENSION_SCHEMA_VERSION") { registry.decode(malformed) }
            fails("UNSUPPORTED_EXTENSION_SCHEMA_VERSION") {
                registry.validate(command().copy(extensions = listOf(NamespacedTextCommandExtension("TEST:EXT", version, "typed"))))
            }
        }
    }

    @Test fun p16Hotfix2_24_unknownDistinctSemanticFieldStillRejected() {
        val encoded = registry.encode(command())
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.decode(encoded.replaceFirst("{", "{\"requestedCanonicalOutcome\":\"FORBIDDEN\","))
        }
    }

    @Test fun p16Hotfix2_25_commandOperationsStillCauseZeroAuthoritativeMutation() {
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
