package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerChangeSetContractTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val subject = DomainRef("PLAYER", "P1")
    private val provenance = ChangeSetProvenance("CMD-1", "RPGOS-RESOLVER:TEST", "1")

    private fun change(
        uid: String = "CH-1",
        kind: String = PlayerChangeKinds.STAT,
        payload: PlayerDomainChangePayload = StatChange(subject, "STAT:STR", ExactLongDelta.of(10))
    ) = PlayerDomainChange.create(uid, kind, payload)

    private fun set(
        uid: String = "CS-1",
        changes: List<PlayerDomainChange> = listOf(change()),
        events: List<PlayerEventIntent> = emptyList(),
        ledgers: List<PlayerLedgerIntent> = emptyList(),
        warnings: List<ChangeSetWarning> = emptyList(),
        preconditions: List<ChangeSetPrecondition> = emptyList()
    ) = PlayerChangeSet.create(
        changeSetUid = uid,
        campaignUid = "C1",
        sourceCommandUid = "CMD-1",
        actor = actor,
        changes = changes,
        eventIntents = events,
        ledgerIntents = ledgers,
        preconditions = preconditions,
        provenance = provenance,
        causationUid = "CAUSE-1",
        correlationUid = "CORR-1",
        requestedEffectiveOrder = 17,
        warnings = warnings
    )

    @Test fun p17_01_immutableRootObject() {
        val pcs = set()
        assertTrue(PlayerChangeSet::class.java.declaredFields.none { java.lang.reflect.Modifier.isPublic(it.modifiers) && !java.lang.reflect.Modifier.isFinal(it.modifiers) })
        expect<UnsupportedOperationException> { (pcs.changes as MutableList).add(change("CH-X")) }
    }

    @Test fun p17_02_defensiveCopyList() {
        val original = mutableListOf(change())
        val pcs = set(changes = original)
        original.clear()
        assertEquals(1, pcs.changes.size)
    }

    @Test fun p17_03_nestedImmutability() {
        val targets = mutableListOf(DomainRef("ITEM", "I1"))
        val causal = mutableListOf("CH-1")
        val event = PlayerEventIntent.create(
            "EV-1", PlayerEventIntentKinds.DOMAIN_EFFECT,
            targetRefs = targets, causalChangeUids = causal,
            payload = DomainEffectEventIntentPayload(subject, "STAT_CHANGED")
        )
        val pcs = set(events = listOf(event))
        targets.clear(); causal.clear()
        assertEquals(1, pcs.eventIntents.single().targetRefs.size)
        assertEquals(listOf("CH-1"), pcs.eventIntents.single().causalChangeUids)
        expect<UnsupportedOperationException> { (pcs.eventIntents.single().targetRefs as MutableList).clear() }
    }

    @Test fun p17_04_stableTypedUidRefs() {
        val payload = pcsPayload(set().changes.single()) as StatChange
        assertEquals(DomainRef("PLAYER", "P1"), payload.subject)
        assertEquals("STAT:STR", payload.statUid)
    }

    @Test fun p17_05_worldAgnosticCore() {
        val forbidden = listOf("chakra", "reiatsu", "sharingan", "raiton", "hollow", "kido")
        allPayloadClasses().flatMap { it.declaredFields.toList() }.forEach { field ->
            forbidden.forEach { token -> assertFalse(field.name.lowercase().contains(token)) }
        }
    }

    @Test fun p17_06_noWorldSpecificFieldsOrTypes() {
        val forbidden = listOf("Naruto", "Bleach", "Chakra", "Reiatsu", "Sharingan", "Raiton", "Hollow", "Kido")
        allPayloadClasses().forEach { type -> forbidden.forEach { assertFalse(type.name.contains(it, ignoreCase = true)) } }
    }

    @Test fun p17_07_statChange() = assertRoundTrip(change(payload = StatChange(subject, "STAT:A", ExactLongDelta.of(3))))
    @Test fun p17_08_resourceChange() = assertRoundTrip(change(kind = PlayerChangeKinds.RESOURCE, payload = ResourceChange(subject, "RES:A", ExactLongDelta.of(-2))))
    @Test fun p17_09_skillChange() = assertRoundTrip(change(kind = PlayerChangeKinds.SKILL, payload = SkillChange(subject, "SK:A", ExactLongDelta.of(4))))
    @Test fun p17_10_techniqueChange() = assertRoundTrip(change(kind = PlayerChangeKinds.TECHNIQUE, payload = TechniqueChange(subject, "TECH:A", ExactLongDelta.of(5))))
    @Test fun p17_11_innateChange() = assertRoundTrip(change(kind = PlayerChangeKinds.INNATE, payload = InnateChange(subject, "INN:A", "STATE:2")))
    @Test fun p17_12_inventoryChange() = assertRoundTrip(change(kind = PlayerChangeKinds.INVENTORY, payload = InventoryChange(subject, "ITEM-I1", ExactLongDelta.of(2))))
    @Test fun p17_12b_dynamicInventoryAcquisitionRoundTrip() = assertRoundTrip(change(
        kind=PlayerChangeKinds.INVENTORY,
        payload=InventoryChange(
            subject,"DYN-OBJECT-KUNAI",ExactLongDelta.of(1),
            universalInventoryItemMaterialization()
        )
    ))
    @Test fun p17_13_equipmentChange() = assertRoundTrip(change(kind = PlayerChangeKinds.EQUIPMENT, payload = EquipmentChange(subject, "SLOT:MAIN", EquipmentOperation.EQUIP, "ITEM-I1")))

    @Test fun p17_14_financialChange() {
        assertRoundTrip(change(kind = PlayerChangeKinds.FINANCIAL, payload = FinancialChange("A1", "A2", 125, "CUR", "TRANSFER")))
        assertEquals(ChangeIntentClassification.LEDGER_APPEND_INTENT, TypedPlayerChangeRegistry.core().classificationFor(PlayerChangeKinds.FINANCIAL))
    }

    @Test fun p17_15_assetChange() = assertRoundTrip(change(
        kind = PlayerChangeKinds.ASSET,
        payload = AssetChange(OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY", "ASSET-1"), "ACTIVE")
    ))

    @Test fun p17_16_ownershipChange() = assertRoundTrip(change(
        kind = PlayerChangeKinds.OWNERSHIP,
        payload = OwnershipChange(
            "OWN-1", OwnedAssetRef("ITEM_INSTANCE", "I1"),
            OwnershipOwnerRef("PLAYER", "P1"), OwnershipOwnerRef("PLAYER", "P2"), OwnershipShare.ofFraction(1, 3)
        )
    ))

    @Test fun p17_17_conditionChange() = assertRoundTrip(change(kind = PlayerChangeKinds.CONDITION, payload = ConditionChange(subject, "COND:TIRED", ConditionOperation.ADD)))
    @Test fun p17_18_runtimeChange() = assertRoundTrip(change(kind = PlayerChangeKinds.RUNTIME, payload = RuntimeChange(subject, "RUNTIME:FOCUS", ExactLongDelta.of(-1))))

    @Test fun p17_19_proposedEvents() {
        val event = PlayerEventIntent.create(
            "EV-1", PlayerEventIntentKinds.DOMAIN_EFFECT, actorRef = subject,
            targetRefs = listOf(subject), causalChangeUids = listOf("CH-1"),
            payload = DomainEffectEventIntentPayload(subject, "STAT_CHANGED"), proposedEffectiveOrder = 18
        )
        val decoded = PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(set(events = listOf(event))))
        assertEquals(event, decoded.eventIntents.single())
    }

    @Test fun p17_20_proposedLedgerIntents() {
        val financial = change(kind = PlayerChangeKinds.FINANCIAL, payload = FinancialChange("A1", "A2", 99, "CUR", "TRANSFER"))
        val ledger = PlayerLedgerIntent.create(
            "LED-1", PlayerLedgerIntentKinds.FINANCIAL_TRANSFER, listOf("CH-1"),
            FinancialTransferLedgerIntentPayload("A1", "A2", 99, "CUR", "TRANSFER")
        )
        val decoded = PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(set(changes = listOf(financial), ledgers = listOf(ledger))))
        assertEquals(ledger, decoded.ledgerIntents.single())
    }

    @Test fun p17_21_provenance() {
        val decoded = PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(set()))
        assertEquals(provenance, decoded.provenance)
        assertEquals("CMD-1", decoded.sourceCommandUid)
    }

    @Test fun p17_22_warnings() {
        val warning = ChangeSetWarning("WARN:SOFT_CAP", "proposal only", "CH-1")
        assertEquals(warning, PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(set(warnings = listOf(warning)))).warnings.single())
    }

    @Test fun p17_23_noApplyCommitExecuteSaveMethods() {
        val forbidden = setOf("apply", "commit", "execute", "save", "persist", "writeToDb", "applyToRepository", "mutateState")
        val types = listOf(PlayerChangeSet::class.java, PlayerDomainChange::class.java, PlayerChangeSetCodec::class.java)
        assertTrue(types.flatMap { it.methods.toList() }.none { it.name in forbidden })
    }

    @Test fun p17_24_noRepositoryStoreWriterDependency() {
        val forbidden = listOf("Repository", "Store", "SQLiteDatabase", "Dao", "TransactionCallback")
        val types = listOf(PlayerChangeSet::class.java, PlayerDomainChange::class.java, TypedPlayerChangeRegistry::class.java)
        types.flatMap { it.declaredFields.toList() }.forEach { field -> forbidden.forEach { token -> assertFalse(field.type.name.contains(token, true)) } }
    }

    @Test fun p17_25_noGenericStatePatchLikePrimitive() {
        allPayloadClasses().forEach { type ->
            type.declaredFields.forEach { field ->
                assertFalse(Map::class.java.isAssignableFrom(field.type))
                assertFalse(field.type.name.contains("JsonElement"))
                assertFalse(field.name in setOf("table", "column", "path", "sql", "field", "value"))
            }
        }
        assertFalse(allPayloadClasses().any { it.name.contains("StatePatch") || it.name.contains("RawSql") })
    }

    @Test fun p17_26_duplicateProposalSemantics() {
        val c = change()
        fails("DUPLICATE_CHANGE_UID") { set(changes = listOf(c, c)) }
    }

    @Test fun p17_27_conflictingProposalSemantics() {
        val plus = change("CH-A", payload = StatChange(subject, "STAT:STR", ExactLongDelta.of(100)))
        val minus = change("CH-B", payload = StatChange(subject, "STAT:STR", ExactLongDelta.of(-100)))
        fails("CONFLICTING_CHANGE_TARGET") { set(changes = listOf(plus, minus)) }
        val equip = change("CH-C", PlayerChangeKinds.EQUIPMENT, EquipmentChange(subject, "SLOT", EquipmentOperation.EQUIP, "I1"))
        val unequip = change("CH-D", PlayerChangeKinds.EQUIPMENT, EquipmentChange(subject, "SLOT", EquipmentOperation.UNEQUIP))
        fails("CONFLICTING_CHANGE_TARGET") { set(changes = listOf(equip, unequip)) }
    }

    @Test fun p17_28_orderDeterminism() {
        val a = change("CH-A", PlayerChangeKinds.STAT, StatChange(subject, "STAT:A", ExactLongDelta.of(1)))
        val b = change("CH-B", PlayerChangeKinds.RESOURCE, ResourceChange(subject, "RES:B", ExactLongDelta.of(1)))
        val first = set(changes = listOf(a, b))
        val second = set(uid = "CS-1", changes = listOf(b, a))
        assertNotEquals(PlayerChangeSetCodec.fingerprint(first), PlayerChangeSetCodec.fingerprint(second))
    }

    @Test fun p17_29_numericOverflowRejection() {
        expect<ArithmeticException> { ExactLongDelta.of(Long.MAX_VALUE).plus(ExactLongDelta.of(1)) }
        expect<ArithmeticException> { ExactLongDelta.between(Long.MIN_VALUE, Long.MAX_VALUE) }
    }

    @Test fun p17_30_exactFinanceRepresentation() {
        val exact = 9_007_199_254_740_993L
        val financial = change(kind = PlayerChangeKinds.FINANCIAL, payload = FinancialChange("A1", "A2", exact, "CUR", "TRANSFER"))
        val decoded = pcsPayload(PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(set(changes = listOf(financial)))).changes.single()) as FinancialChange
        assertEquals(exact, decoded.amountMinor)
    }

    @Test fun p17_31_exactOwnershipShareRepresentation() {
        val share = OwnershipShare.ofFraction(1, 3)
        val ownership = change(kind = PlayerChangeKinds.OWNERSHIP, payload = OwnershipChange(
            "OWN-1", OwnedAssetRef("ASSET", "A1"), OwnershipOwnerRef("PLAYER", "P1"), OwnershipOwnerRef("PLAYER", "P2"), share
        ))
        val decoded = pcsPayload(PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(set(changes = listOf(ownership)))).changes.single()) as OwnershipChange
        assertEquals(share.units, decoded.share.units)
        assertEquals(1L, decoded.share.numerator)
        assertEquals(3L, decoded.share.denominator)
    }

    @Test fun p17_32_zeroAuthoritativeDbMutation() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")
            val before = scalar(db)
            val original = set()
            PlayerChangeSetValidator.validate(original)
            val encoded = PlayerChangeSetCodec.encode(original)
            val decoded = PlayerChangeSetCodec.decode(encoded)
            PlayerChangeSetCodec.fingerprint(decoded)
            assertEquals(before, scalar(db))
        } finally { db.close() }
    }

    @Test fun p17_33_playerCommandPhase16Regression() {
        val registry = PlayerCommandKindRegistry.core()
        val command = PlayerCommand(
            commandUid = "P17-P16-CMD", campaignUid = "C1", actor = actor,
            commandKindUid = PlayerCommandKinds.TRAIN,
            payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10, "METHOD"),
            provenance = CommandProvenance("TEST")
        )
        val encoded = registry.encode(command)
        assertEquals(encoded, registry.encode(registry.decode(encoded)))
    }

    @Test fun p17_34_phase3to15Regression() {
        assertEquals(OWNERSHIP_SHARE_SCALE, OwnershipShare.full().units)
        FinancialPolicy.validateTransaction(FinancialTransaction(
            campaignId = "C1", financialTransactionUid = "TX", fromAccountUid = "A1", toAccountUid = "A2",
            currencyUid = "CUR", amountMinor = 1, transactionTypeUid = "TRANSFER", flowKind = FinancialFlowKind.INTERNAL,
            reason = "regression", effectiveOrder = 1, provenance = "P17"
        ))
        val evidence = mutableListOf(DomainRef("EVIDENCE", "E1"))
        val project = DevelopmentProjectChange.create("PRJ", "SUCCESS", ExactLongDelta.of(1), evidence)
        evidence.clear()
        assertEquals(1, project.evidenceRefs.size)
    }

    @Test fun p17_ser_01_unknownFieldRejected() {
        val encoded = PlayerChangeSetCodec.encode(set())
        val malicious = encoded.dropLast(1) + ",\"unknownField\":1}"
        fails("UNKNOWN_CHANGESET_FIELD") { PlayerChangeSetCodec.decode(malicious) }
    }

    @Test fun p17_ser_02_duplicateKeyRejected() {
        val encoded = PlayerChangeSetCodec.encode(set())
        val malicious = encoded.replaceFirst("\"changeSetUid\":\"CS-1\"", "\"changeSetUid\":\"CS-1\",\"changeSetUid\":\"CS-1\"")
        fails("DUPLICATE_CHANGESET_JSON_OBJECT_KEY") { PlayerChangeSetCodec.decode(malicious) }
    }

    @Test fun p17_ser_03_wrongStringTypeRejected() {
        val encoded = PlayerChangeSetCodec.encode(set())
        val malicious = encoded.replaceFirst("\"changeSetUid\":\"CS-1\"", "\"changeSetUid\":123")
        fails("INVALID_CHANGESET_JSON_STRING_TYPE") { PlayerChangeSetCodec.decode(malicious) }
    }

    @Test fun p17_ser_04_quotedNumericRejected() {
        val encoded = PlayerChangeSetCodec.encode(set())
        val malicious = encoded.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")
        fails("INVALID_CHANGESET_JSON_NUMERIC_TYPE") { PlayerChangeSetCodec.decode(malicious) }
    }

    @Test fun p17_ser_05_unsupportedVersionRejected() {
        val encoded = PlayerChangeSetCodec.encode(set())
        fails("UNSUPPORTED_CHANGESET_SCHEMA_VERSION") { PlayerChangeSetCodec.decode(encoded.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")) }
    }

    @Test fun p17_ser_06_deterministicRoundTrip() {
        val original = set(preconditions = listOf(ChangeSetExpectedRecordVersion(subject, 9)))
        val encoded = PlayerChangeSetCodec.encode(original)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(encoded, PlayerChangeSetCodec.encode(decoded))
        assertEquals(PlayerChangeSetCodec.fingerprint(original), PlayerChangeSetCodec.fingerprint(decoded))
        assertEquals(PlayerChangeSetIdentityRelation.SAME_LOGICAL_CHANGE_SET, PlayerChangeSetIdentity.compare(original, decoded))
    }

    @Test fun p17_ser_07_noPublicCodecBypass() {
        assertTrue(TypedPlayerChangeRegistry::class.java.methods.none { it.name == "decode" || it.name == "encode" })
        assertTrue(PlayerChangeSetCodec::class.java.methods.any { it.name == "decode" })
        val encoded = PlayerChangeSetCodec.encode(set())
        val payloadInjection = encoded.replaceFirst("\"deltaUnits\":10", "\"deltaUnits\":10,\"finalAuthority\":999")
        fails("UNKNOWN_CHANGESET_FIELD") { PlayerChangeSetCodec.decode(payloadInjection) }
    }

    @Test fun identityConflictIsFailClosed() {
        val left = set(uid = "SAME")
        val right = PlayerChangeSet.create(
            changeSetUid = "SAME", campaignUid = "C1", sourceCommandUid = "CMD-1", actor = actor,
            changes = listOf(change("OTHER", PlayerChangeKinds.RESOURCE, ResourceChange(subject, "RES", ExactLongDelta.of(1)))),
            provenance = provenance
        )
        expect<PlayerChangeSetIdentityConflictException> { PlayerChangeSetIdentity.compare(left, right) }
    }

    @Test fun eventAndLedgerCausalRefsFailClosed() {
        val event = PlayerEventIntent.create("EV", PlayerEventIntentKinds.DOMAIN_EFFECT, causalChangeUids = listOf("MISSING"), payload = DomainEffectEventIntentPayload(subject, "E"))
        fails("INVALID_EVENT_INTENT") { set(events = listOf(event)) }
        val ledger = PlayerLedgerIntent.create("L", PlayerLedgerIntentKinds.FINANCIAL_TRANSFER, listOf("MISSING"), FinancialTransferLedgerIntentPayload("A", "B", 1, "CUR", "T"))
        fails("INVALID_LEDGER_INTENT") { set(ledgers = listOf(ledger)) }
    }

    private fun assertRoundTrip(domainChange: PlayerDomainChange) {
        val original = set(changes = listOf(domainChange))
        assertEquals(original, PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(original)))
    }

    private fun pcsPayload(change: PlayerDomainChange): PlayerDomainChangePayload = change.payload

    private fun allPayloadClasses(): List<Class<*>> = listOf(
        StatChange::class.java, ResourceChange::class.java, SkillChange::class.java, TechniqueChange::class.java,
        InnateChange::class.java, InventoryChange::class.java, EquipmentChange::class.java, FinancialChange::class.java,
        AssetChange::class.java, OwnershipChange::class.java, ConditionChange::class.java, RuntimeChange::class.java,
        DevelopmentProjectChange::class.java
    )

    private fun scalar(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT value FROM authority_fixture WHERE uid='A'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    private fun fails(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected PlayerChangeSetStructuralException($code)")
        } catch (e: PlayerChangeSetStructuralException) {
            assertEquals(code, e.code)
        }
    }

    private inline fun <reified T : Throwable> expect(noinline block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}
