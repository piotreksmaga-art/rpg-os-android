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
class PlayerCommandNumericScalarHotfix3Test {
    private val registry = PlayerCommandKindRegistry.core()

    private fun command() = PlayerCommand(
        commandUid = "HOTFIX3-CMD-1",
        campaignUid = "C",
        actor = CommandActorRef("CHARACTER", "ACTOR-1"),
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STRENGTH"), 10, "METHOD"),
        provenance = CommandProvenance("PLAYER_UI", "REQUEST-1", "phase16-hotfix3"),
        causationUid = "CAUSE-1",
        correlationUid = "CORR-1",
        requestedEffectiveOrder = 42,
        preconditions = listOf(ExpectedRecordVersion(DomainRef("PLAYER", "ACTOR-1"), 7)),
        extensions = listOf(NamespacedTextCommandExtension("TEST:EXT", 1, "typed"))
    )

    @Test fun p16Hotfix3_01_rootSchemaVersionQuotedNumericRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_NUMERIC_TYPE") {
            registry.decode(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"", ignoreCase = false))
        }
    }

    @Test fun p16Hotfix3_02_requestedEffectiveOrderQuotedNumericRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_NUMERIC_TYPE") {
            registry.decode(encoded.replace("\"requestedEffectiveOrder\":42", "\"requestedEffectiveOrder\":\"42\""))
        }
    }

    @Test fun p16Hotfix3_03_payloadLongQuotedNumericRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_NUMERIC_TYPE") {
            registry.decode(encoded.replace("\"effortUnits\":10", "\"effortUnits\":\"10\""))
        }
    }

    @Test fun p16Hotfix3_04_preconditionExpectedVersionQuotedNumericRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_NUMERIC_TYPE") {
            registry.decode(encoded.replace("\"expectedVersion\":7", "\"expectedVersion\":\"7\""))
        }
    }

    @Test fun p16Hotfix3_05_extensionSchemaVersionQuotedNumericRejected() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_NUMERIC_TYPE") {
            registry.decode(encoded.replace("\"extensionKindUid\":\"TEST:EXT\",\"schemaVersion\":1", "\"extensionKindUid\":\"TEST:EXT\",\"schemaVersion\":\"1\""))
        }
    }

    @Test fun p16Hotfix3_06_numericBooleanRejectedAsWrongType() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_NUMERIC_TYPE") {
            registry.decode(encoded.replace("\"requestedEffectiveOrder\":42", "\"requestedEffectiveOrder\":true"))
        }
    }

    @Test fun p16Hotfix3_07_numericObjectRejectedAsWrongType() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_NUMERIC_TYPE") {
            registry.decode(encoded.replace("\"effortUnits\":10", "\"effortUnits\":{}"))
        }
    }

    @Test fun p16Hotfix3_08_numericArrayRejectedAsWrongType() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_NUMERIC_TYPE") {
            registry.decode(encoded.replace("\"effortUnits\":10", "\"effortUnits\":[]"))
        }
    }

    @Test fun p16Hotfix3_09_requiredNumericNullRejectedAsMissingValue() {
        val encoded = registry.encode(command())
        fails("MISSING_schemaVersion") {
            registry.decode(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":null"))
        }
    }

    @Test fun p16Hotfix3_10_optionalNumericAbsentPreservesExistingSemantics() {
        val encoded = registry.encode(command())
        val absent = encoded.replace("\"requestedEffectiveOrder\":42,", "")
        val decoded = registry.decode(absent)
        assertEquals(null, decoded.requestedEffectiveOrder)
    }

    @Test fun p16Hotfix3_11_optionalNumericNullPreservesExistingCanonicalSemantics() {
        val cmd = command().copy(requestedEffectiveOrder = null)
        val encoded = registry.encode(cmd)
        val decoded = registry.decode(encoded)
        assertEquals(null, decoded.requestedEffectiveOrder)
        assertEquals(encoded, registry.encode(decoded))
    }

    @Test fun p16Hotfix3_12_validIntNumericPrimitiveAccepted() {
        val decoded = registry.decode(registry.encode(command()))
        assertEquals(PLAYER_COMMAND_SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test fun p16Hotfix3_13_validLongNumericPrimitiveAcceptedAcrossRootPayloadAndPrecondition() {
        val decoded = registry.decode(registry.encode(command()))
        assertEquals(42L, decoded.requestedEffectiveOrder)
        assertEquals(10L, (decoded.payload as TrainCommandPayload).effortUnits)
        assertEquals(7L, (decoded.preconditions.single() as ExpectedRecordVersion).expectedVersion)
    }

    @Test fun p16Hotfix3_14_numericBoundsAndOverflowRejectedDeterministically() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_NUMERIC_VALUE") {
            registry.decode(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2147483648"))
        }
        fails("INVALID_JSON_NUMERIC_VALUE") {
            registry.decode(encoded.replace("\"effortUnits\":10", "\"effortUnits\":9223372036854775808"))
        }
    }

    @Test fun p16Hotfix3_15_canonicalNumericEncodeDecodeEncodeRemainsByteDeterministic() {
        val encoded = registry.encode(command())
        assertEquals(encoded, registry.encode(registry.decode(encoded)))
    }

    @Test fun p16Hotfix3_16_quotedNumericInputCannotReachTypedFingerprintBoundary() {
        val encoded = registry.encode(command())
        val quoted = encoded.replace("\"effortUnits\":10", "\"effortUnits\":\"10\"")
        assertNotEquals(encoded, quoted)
        fails("INVALID_JSON_NUMERIC_TYPE") { registry.decode(quoted) }
        val canonicalFingerprint = registry.fingerprint(command())
        assertEquals(canonicalFingerprint, registry.fingerprint(registry.decode(encoded)))
    }

    @Test fun p16Hotfix3_17_extensionNumericValueOneAccepted() {
        val encoded = registry.encode(command())
        val decoded = registry.decode(encoded)
        assertEquals(listOf(NamespacedTextCommandExtension("TEST:EXT", 1, "typed")), decoded.extensions)
        assertEquals(encoded, registry.encode(decoded))
    }

    @Test fun p16Hotfix3_18_extensionUnsupportedNumericValuesStillRejected() {
        val encoded = registry.encode(command())
        listOf(-1, 0, 2, 999, Int.MAX_VALUE).forEach { version ->
            val malformed = encoded.replace(
                "\"extensionKindUid\":\"TEST:EXT\",\"schemaVersion\":1",
                "\"extensionKindUid\":\"TEST:EXT\",\"schemaVersion\":$version"
            )
            fails("UNSUPPORTED_EXTENSION_SCHEMA_VERSION") { registry.decode(malformed) }
        }
    }

    @Test fun p16Hotfix3_19_previousStrictStringTypingStillActive() {
        val encoded = registry.encode(command())
        fails("INVALID_JSON_STRING_TYPE") {
            registry.decode(encoded.replace("\"commandUid\":\"HOTFIX3-CMD-1\"", "\"commandUid\":123"))
        }
    }

    @Test fun p16Hotfix3_20_previousDuplicateKeyRejectionStillActive() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("{", "{\"commandUid\":\"ATTACKER\","))
        }
    }

    @Test fun p16Hotfix3_21_previousUnknownFieldRejectionStillActive() {
        val encoded = registry.encode(command())
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.decode(encoded.replaceFirst("{", "{\"unknownSemanticField\":1,"))
        }
    }

    @Test fun p16Hotfix3_22_commandOperationsStillCauseZeroAuthoritativeMutation() {
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
            PlayerCommandIdentity.compare(cmd, decoded, registry)
            assertEquals(before, counts(db))
        } finally {
            db.close()
        }
    }

    private fun counts(db: SQLiteDatabase): List<Long> = listOf(
        "campaign_truth_records", "player_stats", "player_skills_v2", "player_techniques_v2", "item_instances",
        "financial_ledger_transactions", "asset_records", "development_projects", "project_work_records"
    ).map { table ->
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
    }

    private fun fails(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: PlayerCommandStructuralException) {
            assertEquals(code, e.code)
        }
    }
}
