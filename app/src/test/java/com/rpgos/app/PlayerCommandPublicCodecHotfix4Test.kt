package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerCommandPublicCodecHotfix4Test {
    private val registry = PlayerCommandKindRegistry.core()

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun trainPayload(extra: String = ""): JsonObject = obj(
        """{"focus":{"kindUid":"STAT","uid":"STRENGTH"},"effortUnits":10,"methodUid":"METHOD"$extra}"""
    )

    private fun command(payload: PlayerCommandPayload = TrainCommandPayload(DomainRef("STAT", "STRENGTH"), 10, "METHOD")) =
        PlayerCommand(
            commandUid = "HOTFIX4-CMD-1",
            campaignUid = "C",
            actor = CommandActorRef("CHARACTER", "ACTOR-1"),
            commandKindUid = PlayerCommandKinds.TRAIN,
            payload = payload,
            provenance = CommandProvenance("PLAYER_UI", "REQUEST-1", "phase16-hotfix4"),
            requestedEffectiveOrder = 42,
            preconditions = listOf(ExpectedRecordVersion(DomainRef("PLAYER", "ACTOR-1"), 7)),
            extensions = listOf(NamespacedTextCommandExtension("TEST:EXT", 1, "typed"))
        )

    @Test fun p16Hotfix4_01_directTrainCodecUnknownPayloadFieldRejected() {
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.codec(PlayerCommandKinds.TRAIN).decode(
                trainPayload(",\"requestedCanonicalOutcome\":\"FORBIDDEN\"")
            )
        }
    }

    @Test fun p16Hotfix4_02_directFinanceCodecUnknownFieldRejected() {
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.codec(PlayerCommandKinds.TRANSFER_FUNDS).decode(
                obj("""{"fromAccountUid":"A","toAccountUid":"B","amountMinor":100,"currencyUid":"USD","finalBalance":999}""")
            )
        }
    }

    @Test fun p16Hotfix4_03_directOwnershipCodecUnknownFieldRejected() {
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.codec(PlayerCommandKinds.TRANSFER_OWNERSHIP).decode(
                obj("""{"subject":{"kindUid":"ASSET","uid":"A"},"toParty":{"kindUid":"PARTY","uid":"B"},"requestedShareBasisPoints":1000,"finalOwner":true}""")
            )
        }
    }

    @Test fun p16Hotfix4_04_directDevelopmentProjectCodecUnknownFieldRejected() {
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.codec(PlayerCommandKinds.START_PROJECT).decode(
                obj("""{"projectTypeUid":"BUILD","titleIntent":"T","objectiveIntent":"O","beneficiaryRef":null,"targetDomainUid":"WORLD","targetRef":null,"intendedOutputKindUid":null,"requestedProgressCapUnits":10,"progressDelta":99}""")
            )
        }
    }

    @Test fun p16Hotfix4_05_directCodecUnknownNullFieldRejected() {
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.codec(PlayerCommandKinds.TRAIN).decode(trainPayload(",\"unknownSemanticField\":null"))
        }
    }

    @Test fun p16Hotfix4_06_directCodecUnknownObjectFieldRejected() {
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.codec(PlayerCommandKinds.TRAIN).decode(trainPayload(",\"unknownSemanticField\":{}"))
        }
    }

    @Test fun p16Hotfix4_07_directCodecUnknownArrayFieldRejected() {
        fails("UNKNOWN_COMMAND_FIELD") {
            registry.codec(PlayerCommandKinds.TRAIN).decode(trainPayload(",\"unknownSemanticField\":[]"))
        }
    }

    @Test fun p16Hotfix4_08_directCodecValidPayloadAccepted() {
        val decoded = registry.codec(PlayerCommandKinds.TRAIN).decode(trainPayload()) as TrainCommandPayload
        assertEquals(DomainRef("STAT", "STRENGTH"), decoded.focus)
        assertEquals(10L, decoded.effortUnits)
        assertEquals("METHOD", decoded.methodUid)
    }

    @Test fun p16Hotfix4_09_registryDecodeValidPayloadAccepted() {
        val encoded = registry.encode(command())
        assertEquals(encoded, registry.encode(registry.decode(encoded)))
    }

    @Test fun p16Hotfix4_10_registryDecodeUnknownPayloadFieldRejected() {
        val encoded = registry.encode(command())
        val malformed = encoded.replace("\"methodUid\":\"METHOD\"", "\"methodUid\":\"METHOD\",\"requestedCanonicalOutcome\":\"FORBIDDEN\"")
        fails("UNKNOWN_COMMAND_FIELD") { registry.decode(malformed) }
    }

    @Test fun p16Hotfix4_11_directCodecStrictStringChecksRemainActive() {
        fails("INVALID_JSON_STRING_TYPE") {
            registry.codec(PlayerCommandKinds.LEARN_SKILL).decode(
                obj("""{"skillUid":123,"methodUid":null}""")
            )
        }
    }

    @Test fun p16Hotfix4_12_directCodecStrictNumericChecksRemainActive() {
        fails("INVALID_JSON_NUMERIC_TYPE") {
            registry.codec(PlayerCommandKinds.TRAIN).decode(
                obj("""{"focus":{"kindUid":"STAT","uid":"STRENGTH"},"effortUnits":"10","methodUid":"METHOD"}""")
            )
        }
    }

    @Test fun p16Hotfix4_13_canonicalEncodeAndFingerprintDeterministicAfterValidDirectDecode() {
        val payload = registry.codec(PlayerCommandKinds.TRAIN).decode(trainPayload())
        val cmd = command(payload)
        val encoded = registry.encode(cmd)
        assertEquals(encoded, registry.encode(cmd))
        assertEquals(encoded, registry.encode(registry.decode(encoded)))
        assertEquals(registry.fingerprint(cmd), registry.fingerprint(cmd))
        assertEquals(registry.fingerprint(cmd), registry.fingerprint(registry.decode(encoded)))
    }

    @Test fun p16Hotfix4_14_unknownFieldCannotDisappearBeforeTypedPayloadConstruction() {
        var typedPayloadConstructed = false
        try {
            registry.codec(PlayerCommandKinds.TRAIN).decode(
                trainPayload(",\"requestedCanonicalOutcome\":\"FORBIDDEN\"")
            )
            typedPayloadConstructed = true
            fail("unknown semantic field reached typed payload construction")
        } catch (e: PlayerCommandStructuralException) {
            assertEquals("UNKNOWN_COMMAND_FIELD", e.code)
        }
        assertEquals(false, typedPayloadConstructed)
    }

    @Test fun p16Hotfix4_15_kindPayloadMismatchStillRejected() {
        val mismatched = command(LearnSkillCommandPayload("SKILL-1", "METHOD"))
        fails("COMMAND_PAYLOAD_TYPE_MISMATCH") { registry.encode(mismatched) }
    }

    @Test fun p16Hotfix4_16_unknownCommandKindStillRejected() {
        fails("UNKNOWN_COMMAND_KIND") { registry.codec("RPGOS-COMMAND:UNKNOWN") }
    }

    @Test fun p16Hotfix4_17_duplicateKeyCanonicalSerializedPathStillRejected() {
        val encoded = registry.encode(command())
        fails(DUPLICATE_JSON_OBJECT_KEY) {
            registry.decode(encoded.replaceFirst("{", "{\"commandUid\":\"ATTACKER\","))
        }
    }

    @Test fun p16Hotfix4_18_extensionVersionContractStillPasses() {
        val supported = command().copy(extensions = listOf(NamespacedTextCommandExtension("TEST:EXT", 1, "typed")))
        registry.validate(supported)
        val encoded = registry.encode(supported)
        assertEquals(encoded, registry.encode(registry.decode(encoded)))
        listOf(-1, 0, 2, 999, Int.MAX_VALUE).forEach { version ->
            fails("UNSUPPORTED_EXTENSION_SCHEMA_VERSION") {
                registry.validate(supported.copy(extensions = listOf(NamespacedTextCommandExtension("TEST:EXT", version, "typed"))))
            }
        }
    }

    @Test fun p16Hotfix4_19_publicCodecOperationsCauseZeroAuthoritativeDbMutation() {
        val db = SQLiteDatabase.create(null)
        try {
            CurrentSchema.ensure(db, "C")
            val before = counts(db)
            val directPayload = registry.codec(PlayerCommandKinds.TRAIN).decode(trainPayload())
            val cmd = command(directPayload)
            registry.validate(cmd)
            val encoded = registry.encode(cmd)
            registry.decode(encoded)
            registry.fingerprint(cmd)
            assertEquals(before, counts(db))
        } finally {
            db.close()
        }
    }

    @Test fun p16Hotfix4_20_phase3To15RegressionBaselineRemainsAccessibleAndUnchanged() {
        val db = SQLiteDatabase.create(null)
        try {
            CurrentSchema.ensure(db, "C")
            val before = counts(db)
            assertTrue(before.all { it == 0L })
            registry.codec(PlayerCommandKinds.TRAIN).decode(trainPayload())
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
