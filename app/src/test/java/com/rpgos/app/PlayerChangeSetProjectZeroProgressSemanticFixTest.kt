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
class PlayerChangeSetProjectZeroProgressSemanticFixTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val owner = OwnershipOwnerRef("PLAYER", "P1")
    private val provenance = ChangeSetProvenance("CMD-PROJECT-ZERO", "RPGOS-RESOLVER:PROJECT-WORK", "1")

    @Test fun p17ProjectZero01_zeroProjectProgressAccepted() {
        assertEquals(0L, ProjectProgressDelta.of(0).units)
    }

    @Test fun p17ProjectZero02_positiveProjectProgressAccepted() {
        assertEquals(1L, ProjectProgressDelta.of(1).units)
        assertEquals(Long.MAX_VALUE, ProjectProgressDelta.of(Long.MAX_VALUE).units)
    }

    @Test fun p17ProjectZero03_negativeProjectProgressRejected() {
        fails("NEGATIVE_PROJECT_PROGRESS_DELTA") { ProjectProgressDelta.of(-1) }
        fails("NEGATIVE_PROJECT_PROGRESS_DELTA") { ProjectProgressDelta.of(Long.MIN_VALUE) }
    }

    @Test fun p17ProjectZero04_copyCannotCreateNegativeProjectProgress() {
        val zero = ProjectProgressDelta.of(0)
        fails("NEGATIVE_PROJECT_PROGRESS_DELTA") { zero.copy(units = -1) }
        assertEquals(0L, zero.units)
    }

    @Test fun p17ProjectZero05_failureWithZeroProgressAccepted() {
        val change = projectChange("CH-FAILURE-ZERO", "PROJECT:P1", ProjectWorkResult.FAILURE, 0)
        val payload = change.payload as DevelopmentProjectChange
        assertEquals(ProjectWorkResult.FAILURE.name, payload.workResultKindUid)
        assertEquals(0L, payload.progressDelta.units)
    }

    @Test fun p17ProjectZero06_phase15ZeroProgressWorkSemanticsAreRepresentable() {
        val phase15Failure = ProjectWorkRecord(
            campaignId = "C1",
            workRecordUid = "WORK-FAILURE",
            projectUid = "PROJECT:P1",
            workKindUid = "EXPERIMENT",
            actor = owner,
            effectiveOrder = 10,
            result = ProjectWorkResult.FAILURE,
            progressDeltaUnits = 0,
            effortUnits = 5,
            provenance = "phase15 zero-progress failure"
        )
        val phase15NoProgress = phase15Failure.copy(
            workRecordUid = "WORK-NO-PROGRESS",
            effectiveOrder = 11,
            result = ProjectWorkResult.NO_PROGRESS,
            provenance = "phase15 explicit no-progress work"
        )

        val failureProposal = projectChange(
            "CH-P15-FAILURE",
            phase15Failure.projectUid,
            phase15Failure.result,
            phase15Failure.progressDeltaUnits
        )
        val noProgressProposal = projectChange(
            "CH-P15-NO-PROGRESS",
            phase15NoProgress.projectUid,
            phase15NoProgress.result,
            phase15NoProgress.progressDeltaUnits
        )

        val proposal = set("CS-P15-ZERO-HANDOFF", listOf(failureProposal, noProgressProposal), allowSameProject = false)
        assertEquals(2, proposal.changes.size)
        assertTrue(proposal.changes.all { (it.payload as DevelopmentProjectChange).progressDelta.units == 0L })
        assertEquals(
            setOf(ProjectWorkResult.FAILURE.name, ProjectWorkResult.NO_PROGRESS.name),
            proposal.changes.map { (it.payload as DevelopmentProjectChange).workResultKindUid }.toSet()
        )
    }

    @Test fun p17ProjectZero07_zeroProgressEncodeDecodeEncodeDeterministic() {
        val proposal = set("CS-ZERO-ROUNDTRIP", listOf(projectChange("CH-ZERO", "PROJECT:P1", ProjectWorkResult.FAILURE, 0)))
        val first = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(first)
        assertEquals(proposal, decoded)
        assertEquals(first, PlayerChangeSetCodec.encode(decoded))
        assertTrue(first.contains("\"progressDeltaUnits\":0"))
    }

    @Test fun p17ProjectZero08_zeroProgressFingerprintDeterministicAndSemantic() {
        val failureZero = set("CS-PROJECT-FP", listOf(projectChange("CH-PROJECT", "PROJECT:P1", ProjectWorkResult.FAILURE, 0)))
        val successOne = set("CS-PROJECT-FP", listOf(projectChange("CH-PROJECT", "PROJECT:P1", ProjectWorkResult.SUCCESS, 1)))

        val first = PlayerChangeSetCodec.fingerprint(failureZero)
        val roundTripped = PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(failureZero))
        assertEquals(first, PlayerChangeSetCodec.fingerprint(roundTripped))
        assertNotEquals(first, PlayerChangeSetCodec.fingerprint(successOne))
    }

    @Test fun p17ProjectZero09_quotedZeroRejected() {
        val encoded = PlayerChangeSetCodec.encode(
            set("CS-QUOTED-ZERO", listOf(projectChange("CH-ZERO", "PROJECT:P1", ProjectWorkResult.FAILURE, 0)))
        )
        val malicious = encoded.replaceFirst("\"progressDeltaUnits\":0", "\"progressDeltaUnits\":\"0\"")
        fails("INVALID_CHANGESET_JSON_NUMERIC_TYPE") { PlayerChangeSetCodec.decode(malicious) }
    }

    @Test fun p17ProjectZero10_negativeSerializedProgressRejected() {
        val encoded = PlayerChangeSetCodec.encode(
            set("CS-NEGATIVE-SERIALIZED", listOf(projectChange("CH-ZERO", "PROJECT:P1", ProjectWorkResult.FAILURE, 0)))
        )
        val malicious = encoded.replaceFirst("\"progressDeltaUnits\":0", "\"progressDeltaUnits\":-1")
        fails("NEGATIVE_PROJECT_PROGRESS_DELTA") { PlayerChangeSetCodec.decode(malicious) }
    }

    @Test fun p17ProjectZero11_exactLongDeltaZeroStillRejected() {
        fails("ZERO_DELTA") { ExactLongDelta.of(0) }
    }

    @Test fun p17ProjectZero12_exactLongDeltaCopyZeroStillRejected() {
        val legal = ExactLongDelta.of(1)
        fails("ZERO_DELTA") { legal.copy(units = 0) }
        assertEquals(1L, legal.units)
    }

    @Test fun p17ProjectZero13_ownershipShareCopyRangeInvariantStillPasses() {
        val full = OwnershipShare.full()
        failsIllegalArgument { full.copy(units = 0) }
        failsIllegalArgument { full.copy(units = OWNERSHIP_SHARE_SCALE + 1) }
        assertEquals(full, full.copy())
    }

    @Test fun p17ProjectZero14_compositeConflictIdentityRegression() {
        val a = change(
            "CH-A",
            PlayerChangeKinds.STAT,
            StatChange(DomainRef("PLAYER", "X:Y"), "Z", ExactLongDelta.of(1))
        )
        val b = change(
            "CH-B",
            PlayerChangeKinds.STAT,
            StatChange(DomainRef("PLAYER", "X"), "Y:Z", ExactLongDelta.of(1))
        )
        PlayerChangeSetValidator.validate(set("CS-COMPOSITE", listOf(a, b)))
        assertNotEquals(TypedPlayerChangeRegistry.core().conflictKeys(a), TypedPlayerChangeRegistry.core().conflictKeys(b))
    }

    @Test fun p17ProjectZero15_assetIdentityRegression() {
        val property = change(
            "CH-PROPERTY",
            PlayerChangeKinds.ASSET,
            AssetChange(OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY", "BUSINESS:A-1"), "ACTIVE")
        )
        val business = change(
            "CH-BUSINESS",
            PlayerChangeKinds.ASSET,
            AssetChange(OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY:BUSINESS", "A-1"), "ACTIVE")
        )
        PlayerChangeSetValidator.validate(set("CS-ASSET", listOf(property, business)))
        assertNotEquals(TypedPlayerChangeRegistry.core().conflictKeys(property), TypedPlayerChangeRegistry.core().conflictKeys(business))
    }

    @Test fun p17ProjectZero16_financialLedgerRegression() {
        val financial = change(
            "CH-FIN",
            PlayerChangeKinds.FINANCIAL,
            FinancialChange("ACCOUNT:A", "ACCOUNT:B", 100L, "CUR:PLN", "TRANSFER")
        )
        val ledger = PlayerLedgerIntent.create(
            ledgerIntentUid = "LED-1",
            ledgerKindUid = PlayerLedgerIntentKinds.FINANCIAL_TRANSFER,
            causalChangeUids = listOf("CH-FIN"),
            payload = FinancialTransferLedgerIntentPayload("ACCOUNT:A", "ACCOUNT:B", 100L, "CUR:PLN", "TRANSFER")
        )
        val proposal = PlayerChangeSet.create(
            changeSetUid = "CS-FIN",
            campaignUid = "C1",
            sourceCommandUid = "CMD-PROJECT-ZERO",
            actor = actor,
            changes = listOf(financial),
            ledgerIntents = listOf(ledger),
            provenance = provenance
        )
        assertEquals(proposal, PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(proposal)))
    }

    @Test fun p17ProjectZero17_zeroAuthoritativeMutation() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")
            val before = scalar(db)
            val proposal = set(
                "CS-PROJECT-ZERO-NO-MUTATION",
                listOf(projectChange("CH-ZERO", "PROJECT:P1", ProjectWorkResult.NO_PROGRESS, 0))
            )
            val encoded = PlayerChangeSetCodec.encode(proposal)
            val decoded = PlayerChangeSetCodec.decode(encoded)
            PlayerChangeSetCodec.fingerprint(decoded)
            assertEquals(before, scalar(db))
        } finally {
            db.close()
        }
    }

    @Test fun p17ProjectZero18_phase3to16Regression() {
        val registry = PlayerCommandKindRegistry.core()
        val command = PlayerCommand(
            commandUid = "P17-PROJECT-ZERO-P16-CMD",
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRAIN,
            payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10, "METHOD"),
            provenance = CommandProvenance("TEST")
        )
        val encodedCommand = registry.encode(command)
        assertEquals(encodedCommand, registry.encode(registry.decode(encodedCommand)))

        val phase15Zero = ProjectWorkRecord(
            campaignId = "C1",
            workRecordUid = "WORK-P15-REGRESSION",
            projectUid = "PROJECT:P1",
            workKindUid = "EXPERIMENT",
            actor = owner,
            effectiveOrder = 1,
            result = ProjectWorkResult.FAILURE,
            progressDeltaUnits = 0,
            provenance = "phase15 regression"
        )
        assertEquals(0L, phase15Zero.progressDeltaUnits)
        assertEquals(OWNERSHIP_SHARE_SCALE, OwnershipShare.full().units)
    }

    private fun projectChange(
        changeUid: String,
        projectUid: String,
        result: ProjectWorkResult,
        progressUnits: Long
    ): PlayerDomainChange = change(
        changeUid,
        PlayerChangeKinds.DEVELOPMENT_PROJECT,
        DevelopmentProjectChange.create(
            projectUid = projectUid,
            workResultKindUid = result.name,
            progressDelta = ProjectProgressDelta.of(progressUnits)
        )
    )

    private fun change(uid: String, kind: String, payload: PlayerDomainChangePayload): PlayerDomainChange =
        PlayerDomainChange.create(uid, kind, payload)

    private fun set(
        uid: String,
        changes: List<PlayerDomainChange>,
        allowSameProject: Boolean = true
    ): PlayerChangeSet {
        val actualChanges = if (allowSameProject) changes else changes.mapIndexed { index, change ->
            if (change.payload !is DevelopmentProjectChange || index == 0) change
            else {
                val payload = change.payload as DevelopmentProjectChange
                projectChange(change.changeUid, "${payload.projectUid}:$index", ProjectWorkResult.valueOf(payload.workResultKindUid), payload.progressDelta.units)
            }
        }
        return PlayerChangeSet.create(
            changeSetUid = uid,
            campaignUid = "C1",
            sourceCommandUid = "CMD-PROJECT-ZERO",
            actor = actor,
            changes = actualChanges,
            provenance = provenance
        )
    }

    private fun fails(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected PlayerChangeSetStructuralException($code)")
        } catch (e: PlayerChangeSetStructuralException) {
            assertEquals(code, e.code)
        }
    }

    private fun failsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun scalar(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT value FROM authority_fixture WHERE uid='A'",
        null
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getLong(0)
    }
}
