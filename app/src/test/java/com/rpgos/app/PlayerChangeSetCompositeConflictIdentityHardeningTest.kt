package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
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
class PlayerChangeSetCompositeConflictIdentityHardeningTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val provenance = ChangeSetProvenance("CMD-CONFLICT-HARDEN", "RPGOS-RESOLVER:CONFLICT-HARDEN", "1")
    private val registry = TypedPlayerChangeRegistry.core()

    private data class Family(
        val name: String,
        val arity: Int,
        val make: (String, List<String>) -> PlayerDomainChange,
        val selectIdentityKey: (Set<String>) -> String = { it.single() }
    )

    private val families: List<Family> = listOf(
        Family("STAT", 3, { uid, c -> change(uid, PlayerChangeKinds.STAT, StatChange(DomainRef(c[0], c[1]), c[2], ExactLongDelta.of(1))) }),
        Family("RESOURCE", 3, { uid, c -> change(uid, PlayerChangeKinds.RESOURCE, ResourceChange(DomainRef(c[0], c[1]), c[2], ExactLongDelta.of(1))) }),
        Family("SKILL", 3, { uid, c -> change(uid, PlayerChangeKinds.SKILL, SkillChange(DomainRef(c[0], c[1]), c[2], ExactLongDelta.of(1))) }),
        Family("TECHNIQUE", 3, { uid, c -> change(uid, PlayerChangeKinds.TECHNIQUE, TechniqueChange(DomainRef(c[0], c[1]), c[2], ExactLongDelta.of(1))) }),
        Family("INNATE", 3, { uid, c -> change(uid, PlayerChangeKinds.INNATE, InnateChange(DomainRef(c[0], c[1]), c[2], "ACTIVE")) }),
        Family("INVENTORY", 3, { uid, c -> change(uid, PlayerChangeKinds.INVENTORY, InventoryChange(DomainRef(c[0], c[1]), c[2], ExactLongDelta.of(1))) }),
        Family("EQUIPMENT", 3, { uid, c -> change(uid, PlayerChangeKinds.EQUIPMENT, EquipmentChange(DomainRef(c[0], c[1]), c[2], EquipmentOperation.UNEQUIP)) }),
        Family("ASSET", 2, { uid, c -> change(uid, PlayerChangeKinds.ASSET, AssetChange(OwnedAssetRef(c[0], c[1]), "ACTIVE")) }),
        Family(
            "OWNED_ASSET",
            2,
            { uid, c ->
                change(
                    uid,
                    PlayerChangeKinds.OWNERSHIP,
                    OwnershipChange(
                        ownershipRecordUid = "OWN-$uid",
                        asset = OwnedAssetRef(c[0], c[1]),
                        fromOwner = OwnershipOwnerRef("PLAYER", "FROM-$uid"),
                        toOwner = OwnershipOwnerRef("PLAYER", "TO-$uid"),
                        share = OwnershipShare.full()
                    )
                )
            },
            selectIdentityKey = { keys -> keys.single { !it.startsWith("OWNERSHIP:") } }
        ),
        Family("CONDITION", 3, { uid, c -> change(uid, PlayerChangeKinds.CONDITION, ConditionChange(DomainRef(c[0], c[1]), c[2], ConditionOperation.ADD)) }),
        Family("RUNTIME", 3, { uid, c -> change(uid, PlayerChangeKinds.RUNTIME, RuntimeChange(DomainRef(c[0], c[1]), c[2], ExactLongDelta.of(1))) })
    )

    @Test fun p17Composite_01_exactStatAliasReproducerAcceptedAsDistinctTargets() {
        val a = change("CH-A", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X:Y"), "Z", ExactLongDelta.of(1)))
        val b = change("CH-B", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X"), "Y:Z", ExactLongDelta.of(1)))

        val proposal = set(changes = listOf(a, b))
        PlayerChangeSetValidator.validate(proposal)
        assertEquals(2, proposal.changes.size)
        assertNotEquals(registry.conflictKeys(a), registry.conflictKeys(b))
    }

    @Test fun p17Composite_02_identicalStatTupleStillConflicts() {
        val a = change("CH-A", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X:Y"), "Z:Q", ExactLongDelta.of(1)))
        val b = change("CH-B", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X:Y"), "Z:Q", ExactLongDelta.of(2)))
        fails("CONFLICTING_CHANGE_TARGET") { set(changes = listOf(a, b)) }
    }

    @Test fun p17Composite_03_allCompositeFamiliesAreInjectiveAcrossLegalAdversarialComponents() {
        families.forEach { family ->
            val vectors = adversarialVectors(family.arity)
            val keys = vectors.mapIndexed { index, components -> identityKey(family, "CH-${family.name}-$index", components) }
            assertEquals("${family.name} must not collide across distinct legal tuples", keys.size, keys.toSet().size)

            vectors.forEachIndexed { index, components ->
                val first = identityKey(family, "CH-${family.name}-SAME-A-$index", components)
                val second = identityKey(family, "CH-${family.name}-SAME-B-$index", components)
                assertEquals("${family.name} same tuple must produce same conflict identity", first, second)
            }
        }
    }

    @Test fun p17Composite_04_eachCompositeFamilyAcceptsLegacyAliasShapeAsDistinctTuple() {
        families.forEach { family ->
            val (left, right) = aliasPair(family.arity)
            val leftChange = family.make("CH-${family.name}-LEFT", left)
            val rightChange = family.make("CH-${family.name}-RIGHT", right)
            val leftKey = identityKey(family, "CH-${family.name}-LEFT-K", left)
            val rightKey = identityKey(family, "CH-${family.name}-RIGHT-K", right)
            assertNotEquals("${family.name} old delimiter alias must be separated", leftKey, rightKey)

            // Ownership has an independent ownershipRecordUid conflict key. The builder makes those records distinct.
            PlayerChangeSetValidator.validate(set(uid = "CS-${family.name}", changes = listOf(leftChange, rightChange)))
        }
    }

    @Test fun p17Composite_05_eachCompositeFamilyStillConflictsForSameTuple() {
        families.forEach { family ->
            val components = if (family.arity == 2) listOf("KIND:α | \\ ", "UID:β | \\ ") else listOf("PLAYER:α | \\ ", "SUBJECT:β | \\ ", "TARGET:γ | \\ ")
            val a = family.make("CH-${family.name}-A", components)
            val b = family.make("CH-${family.name}-B", components)
            if (family.name == "OWNED_ASSET") {
                // Distinct ownership record UIDs ensure any conflict comes from the identical asset tuple.
                fails("CONFLICTING_CHANGE_TARGET") { set(uid = "CS-SAME-${family.name}", changes = listOf(a, b)) }
            } else {
                fails("CONFLICTING_CHANGE_TARGET") { set(uid = "CS-SAME-${family.name}", changes = listOf(a, b)) }
            }
        }
    }

    @Test fun p17Composite_06_assetHotfix3AliasReproducerStillPasses() {
        val a = change(
            "CH-A",
            PlayerChangeKinds.ASSET,
            AssetChange(OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY", "BUSINESS:A-1"), "ACTIVE")
        )
        val b = change(
            "CH-B",
            PlayerChangeKinds.ASSET,
            AssetChange(OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY:BUSINESS", "A-1"), "ACTIVE")
        )
        PlayerChangeSetValidator.validate(set(changes = listOf(a, b)))
        assertNotEquals(registry.conflictKeys(a), registry.conflictKeys(b))
    }

    @Test fun p17Composite_07_originalAssetHotfixPropertyAndBusinessSameUidStillPasses() {
        val property = change(
            "CH-P",
            PlayerChangeKinds.ASSET,
            AssetChange(OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY", "A-1"), "ACTIVE")
        )
        val business = change(
            "CH-B",
            PlayerChangeKinds.ASSET,
            AssetChange(OwnedAssetRef("RPGOS-ASSET-KIND:BUSINESS", "A-1"), "ACTIVE")
        )
        PlayerChangeSetValidator.validate(set(changes = listOf(property, business)))
        assertNotEquals(registry.conflictKeys(property), registry.conflictKeys(business))
    }

    @Test fun p17Composite_08_financialAccountKeysRemainSingleComponentAndHotfix2UniquenessPasses() {
        val financial = change(
            "CH-FIN",
            PlayerChangeKinds.FINANCIAL,
            FinancialChange("ACCOUNT:A:B", "ACCOUNT:C:D", 100L, "CUR:PLN", "TRANSFER:TYPE")
        )
        assertEquals(
            setOf("FIN_ACCOUNT:ACCOUNT:A:B", "FIN_ACCOUNT:ACCOUNT:C:D"),
            registry.conflictKeys(financial)
        )

        fun ledger(uid: String) = PlayerLedgerIntent.create(
            ledgerIntentUid = uid,
            ledgerKindUid = PlayerLedgerIntentKinds.FINANCIAL_TRANSFER,
            causalChangeUids = listOf("CH-FIN"),
            payload = FinancialTransferLedgerIntentPayload("ACCOUNT:A:B", "ACCOUNT:C:D", 100L, "CUR:PLN", "TRANSFER:TYPE")
        )
        fails("DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE") {
            PlayerChangeSet.create(
                changeSetUid = "CS-FIN",
                campaignUid = "C1",
                sourceCommandUid = "CMD-CONFLICT-HARDEN",
                actor = actor,
                changes = listOf(financial),
                ledgerIntents = listOf(ledger("LED-1"), ledger("LED-2")),
                provenance = provenance
            )
        }
    }

    @Test fun p17Composite_09_singleComponentOwnershipAndProjectKeysRemainUnambiguous() {
        val ownership = change(
            "CH-OWN",
            PlayerChangeKinds.OWNERSHIP,
            OwnershipChange(
                "REC:WITH:DELIMITERS | \\ 東京",
                OwnedAssetRef("KIND", "UID"),
                OwnershipOwnerRef("PLAYER", "A"),
                OwnershipOwnerRef("PLAYER", "B"),
                OwnershipShare.full()
            )
        )
        assertTrue(registry.conflictKeys(ownership).contains("OWNERSHIP:REC:WITH:DELIMITERS | \\ 東京"))

        val project = change(
            "CH-PROJECT",
            PlayerChangeKinds.DEVELOPMENT_PROJECT,
            DevelopmentProjectChange.create("PROJECT:WITH:DELIMITERS | \\ 東京", "RESULT", ExactLongDelta.of(1))
        )
        assertEquals(setOf("PROJECT:PROJECT:WITH:DELIMITERS | \\ 東京"), registry.conflictKeys(project))
    }

    @Test fun p17Composite_10_canonicalSerializationAndFingerprintRemainDeterministic() {
        val proposal = set(
            uid = "CS-DETERMINISTIC",
            changes = listOf(
                change("CH-STAT", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X:Y | 東京 \\"), "Z:Q | łódź", ExactLongDelta.of(7))),
                change("CH-ASSET", PlayerChangeKinds.ASSET, AssetChange(OwnedAssetRef("KIND:A:B", "UID:C:D | 東京 \\"), "ACTIVE"))
            )
        )
        val encoded = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(encoded, PlayerChangeSetCodec.encode(decoded))
        assertEquals(PlayerChangeSetCodec.fingerprint(proposal), PlayerChangeSetCodec.fingerprint(decoded))
        assertEquals(PlayerChangeSetIdentityRelation.SAME_LOGICAL_CHANGE_SET, PlayerChangeSetIdentity.compare(proposal, decoded))
    }

    @Test fun p17Composite_11_zeroAuthoritativeMutation() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")
            val before = scalar(db)
            val proposal = set(
                uid = "CS-ZERO-MUTATION",
                changes = listOf(
                    change("CH-A", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X:Y"), "Z", ExactLongDelta.of(1))),
                    change("CH-B", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X"), "Y:Z", ExactLongDelta.of(1)))
                )
            )
            PlayerChangeSetValidator.validate(proposal)
            val encoded = PlayerChangeSetCodec.encode(proposal)
            val decoded = PlayerChangeSetCodec.decode(encoded)
            PlayerChangeSetCodec.fingerprint(decoded)
            decoded.changes.forEach { registry.conflictKeys(it) }
            assertEquals(before, scalar(db))
        } finally {
            db.close()
        }
    }

    @Test fun p17Composite_12_phase3to16RegressionRepresentativeChecks() {
        val commandRegistry = PlayerCommandKindRegistry.core()
        val command = PlayerCommand(
            commandUid = "CONFLICT-P16-CMD",
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRAIN,
            payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10, "METHOD"),
            provenance = CommandProvenance("TEST")
        )
        val encoded = commandRegistry.encode(command)
        assertEquals(encoded, commandRegistry.encode(commandRegistry.decode(encoded)))
        assertEquals(OWNERSHIP_SHARE_SCALE, OwnershipShare.full().units)
        FinancialPolicy.validateTransaction(
            FinancialTransaction(
                campaignId = "C1",
                financialTransactionUid = "TX-CONFLICT-HARDEN",
                fromAccountUid = "A",
                toAccountUid = "B",
                currencyUid = "CUR",
                amountMinor = 1L,
                transactionTypeUid = "TRANSFER",
                flowKind = FinancialFlowKind.INTERNAL,
                reason = "phase 17 composite conflict regression",
                effectiveOrder = 1L,
                provenance = "P17-CONFLICT-HARDEN"
            )
        )
    }

    private fun adversarialVectors(arity: Int): List<List<String>> = if (arity == 2) {
        listOf(
            listOf("FIRST:WITH:COLON", "SECOND"),
            listOf("FIRST", "SECOND:WITH:COLON"),
            listOf("A:B:C", "D:E:F"),
            listOf("Unicode 東京 łódź αβ", "whitespace value with spaces"),
            listOf("pipe|value\\backslash", "other|pipe\\slash"),
            listOf("CK1|4:FAKE|2|3:abc|", "CK1|7:LOOKING|2|"),
            listOf("  leading and trailing  ", "\tlegal whitespace\t")
        )
    } else {
        listOf(
            listOf("FIRST:WITH:COLON", "SECOND", "THIRD"),
            listOf("FIRST", "SECOND:WITH:COLON", "THIRD"),
            listOf("FIRST", "SECOND", "THIRD:WITH:COLON"),
            listOf("A:B:C", "D:E:F", "G:H:I"),
            listOf("Unicode 東京 łódź αβ", "whitespace value with spaces", "żółć 東京"),
            listOf("pipe|value\\backslash", "other|pipe\\slash", "third|\\value"),
            listOf("CK1|4:FAKE|3|", "7:LOOKING|", "9:ENCODED:Q"),
            listOf("  leading and trailing  ", "\tlegal whitespace\t", " end ")
        )
    }

    private fun aliasPair(arity: Int): Pair<List<String>, List<String>> = if (arity == 2) {
        listOf("A", "B:C") to listOf("A:B", "C")
    } else {
        listOf("A", "B:C", "D") to listOf("A:B", "C", "D")
    }

    private fun identityKey(family: Family, uid: String, components: List<String>): String =
        family.selectIdentityKey(registry.conflictKeys(family.make(uid, components)))

    private fun change(uid: String, kind: String, payload: PlayerDomainChangePayload): PlayerDomainChange =
        PlayerDomainChange.create(uid, kind, payload)

    private fun set(uid: String = "CS-CONFLICT-HARDEN", changes: List<PlayerDomainChange>): PlayerChangeSet =
        PlayerChangeSet.create(
            changeSetUid = uid,
            campaignUid = "C1",
            sourceCommandUid = "CMD-CONFLICT-HARDEN",
            actor = actor,
            changes = changes,
            provenance = provenance
        )

    private fun scalar(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT value FROM authority_fixture WHERE uid='A'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun fails(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected PlayerChangeSetStructuralException($code)")
        } catch (e: PlayerChangeSetStructuralException) {
            assertEquals(code, e.code)
        }
    }
}
