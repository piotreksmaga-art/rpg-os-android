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
class DevelopmentProjectReleaseBlockerHotfixTest {
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("p15-release-hotfix-", ".db")
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun db(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
    private fun owner(uid: String) = OwnershipOwnerRef("CHARACTER", uid)

    private fun store(d: SQLiteDatabase, campaignId: String = "C"): DevelopmentProjectStore {
        CurrentSchema.ensure(d, campaignId)
        val refs = OwnershipReferenceRegistry(d, campaignId)
        listOf("A", "B").forEach { uid ->
            runCatching { refs.registerOwner(owner(uid), "p15-release-hotfix") }
        }
        return DevelopmentProjectStore(d, campaignId)
    }

    private fun project(
        uid: String,
        campaignId: String = "C",
        outputKind: String? = null
    ) = DevelopmentProject(
        campaignId = campaignId,
        projectUid = uid,
        projectTypeUid = PROJECT_TYPE_RESEARCH,
        initiator = owner("A"),
        beneficiary = owner("B"),
        title = "Project $uid",
        objectiveSummary = "Phase 15 release blocker fixture",
        targetDomainUid = "RESEARCH",
        intendedOutputKindUid = outputKind,
        createdOrder = 1,
        provenance = "p15-release-hotfix"
    )

    private fun activeProject(
        d: SQLiteDatabase,
        uid: String,
        campaignId: String = "C",
        outputKind: String? = null,
        withMilestone: Boolean = false
    ): DevelopmentProjectStore {
        val s = store(d, campaignId)
        s.createProject(project(uid, campaignId, outputKind), "$uid-I")
        s.changeStatus(ProjectStatusEvent(campaignId, "$uid-R", uid, ProjectStatus.REQUIREMENTS, 2, provenance = "requirements"))
        s.changeStatus(ProjectStatusEvent(campaignId, "$uid-P", uid, ProjectStatus.PROTOTYPE, 3, provenance = "prototype"))
        if (withMilestone) {
            s.addMilestone(ProjectMilestoneDefinition(campaignId, "$uid-M", uid, 1, "RPGOS-MILESTONE:EVIDENCE", "Evidence milestone", true, "p15-release-hotfix"))
        }
        s.changeStatus(ProjectStatusEvent(campaignId, "$uid-A", uid, ProjectStatus.ACTIVE_WORK, 4, provenance = "active"))
        return s
    }

    private fun readyTruthProject(d: SQLiteDatabase, uid: String, campaignId: String = "C"): DevelopmentProjectStore {
        val s = activeProject(d, uid, campaignId, PROJECT_OUTPUT_TRUTH)
        s.changeStatus(ProjectStatusEvent(campaignId, "$uid-S", uid, ProjectStatus.STABILIZATION, 5, provenance = "stabilization"))
        s.changeStatus(ProjectStatusEvent(campaignId, "$uid-Q", uid, ProjectStatus.READY_TO_COMPLETE, 6, provenance = "ready"))
        return s
    }

    private fun truth(d: SQLiteDatabase, campaignId: String, uid: String, kind: TruthKind = TruthKind.FACT): CampaignTruthRecord =
        CampaignTruthStore(d, campaignId).record(
            kind = kind,
            predicate = "discovered.fact",
            objectValue = "stable-value",
            provenance = Provenance(ProvenanceSourceType.RESEARCH, sourceId = "P15-HOTFIX"),
            truthUid = uid
        )

    private fun fail(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Throwable) {
            failed = true
        }
        assertTrue("expected authoritative write rejection", failed)
    }

    private fun n(d: SQLiteDatabase, sql: String): Long =
        d.rawQuery(sql, null).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun truthSnapshot(d: SQLiteDatabase, uid: String): String =
        d.rawQuery(
            "SELECT campaign_id,truth_kind,predicate,COALESCE(object_value,''),source_type,confidence,verified,created_at,active FROM campaign_truth_records WHERE truth_uid=?",
            arrayOf(uid)
        ).use { c ->
            assertTrue(c.moveToFirst())
            (0..8).joinToString("|") { i -> if (c.isNull(i)) "<null>" else c.getString(i) }
        }

    private fun checks(d: SQLiteDatabase) {
        d.rawQuery("PRAGMA integrity_check", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("ok", c.getString(0))
        }
        d.rawQuery("PRAGMA foreign_key_check", null).use { c ->
            assertFalse("foreign_key_check must have zero violations", c.moveToFirst())
        }
    }

    // P15-TRUTH-01
    @Test
    fun p15Truth01RealCanonicalTruthCanBeCommittedAsOutcome() {
        db().use { d ->
            val s = readyTruthProject(d, "T01")
            truth(d, "C", "TRUTH-01")
            val out = ProjectOutcome("C", "OUT-01", "T01", PROJECT_OUTPUT_TRUTH, "FACT", "TRUTH-01", 7, provenance = "link-only")
            assertEquals(out, s.commitOutcome(out))
            assertEquals(1L, n(d, "SELECT COUNT(*) FROM project_outcomes WHERE outcome_uid='OUT-01'"))
            checks(d)
        }
    }

    // P15-TRUTH-02 + direct SQLite/write-boundary bypass
    @Test
    fun p15Truth02GhostTruthIsRejectedAtSQLiteBoundary() {
        db().use { d ->
            readyTruthProject(d, "T02")
            fail {
                d.execSQL(
                    "INSERT INTO project_outcomes(campaign_id,outcome_uid,project_uid,output_kind_uid,output_ref_kind_uid,output_uid,committed_order,provenance) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>("C", "OUT-GHOST", "T02", PROJECT_OUTPUT_TRUTH, "FACT", "GHOST-TRUTH", 7, "direct-sql")
                )
            }
            assertEquals(0L, n(d, "SELECT COUNT(*) FROM project_outcomes WHERE outcome_uid='OUT-GHOST'"))
            checks(d)
        }
    }

    // P15-TRUTH-03
    @Test
    fun p15Truth03WrongCampaignTruthIsRejected() {
        db().use { d ->
            val s = readyTruthProject(d, "T03", "C")
            store(d, "D")
            truth(d, "D", "TRUTH-D")
            fail { s.commitOutcome(ProjectOutcome("C", "OUT-WRONG-CAMPAIGN", "T03", PROJECT_OUTPUT_TRUTH, "FACT", "TRUTH-D", 7, provenance = "bad")) }
            assertEquals(0L, n(d, "SELECT COUNT(*) FROM project_outcomes WHERE outcome_uid='OUT-WRONG-CAMPAIGN'"))
            checks(d)
        }
    }

    // P15-TRUTH-04
    @Test
    fun p15Truth04TruthOutcomeCannotBypassDifferentIntendedOutputKind() {
        db().use { d ->
            val s = activeProject(d, "T04", outputKind = PROJECT_OUTPUT_ITEM_INSTANCE)
            s.changeStatus(ProjectStatusEvent("C", "T04-S", "T04", ProjectStatus.STABILIZATION, 5, provenance = "s"))
            s.changeStatus(ProjectStatusEvent("C", "T04-Q", "T04", ProjectStatus.READY_TO_COMPLETE, 6, provenance = "q"))
            truth(d, "C", "TRUTH-04")
            fail { s.commitOutcome(ProjectOutcome("C", "OUT-04", "T04", PROJECT_OUTPUT_TRUTH, "FACT", "TRUTH-04", 7, provenance = "bad-kind")) }
            checks(d)
        }
    }

    // P15-TRUTH-05
    @Test
    fun p15Truth05ValidTruthOutcomeAllowsReadyToCompleteToCompleted() {
        db().use { d ->
            val s = readyTruthProject(d, "T05")
            truth(d, "C", "TRUTH-05")
            s.commitOutcome(ProjectOutcome("C", "OUT-05", "T05", PROJECT_OUTPUT_TRUTH, "FACT", "TRUTH-05", 7, provenance = "link"))
            s.changeStatus(ProjectStatusEvent("C", "T05-D", "T05", ProjectStatus.COMPLETED, 8, provenance = "done"))
            assertEquals(ProjectStatus.COMPLETED, s.currentStatus("T05"))
            checks(d)
        }
    }

    // P15-TRUTH-06
    @Test
    fun p15Truth06OutcomeLinkDoesNotCreateOrModifyCanonicalTruth() {
        db().use { d ->
            val s = readyTruthProject(d, "T06")
            truth(d, "C", "TRUTH-06")
            val beforeCount = n(d, "SELECT COUNT(*) FROM campaign_truth_records")
            val before = truthSnapshot(d, "TRUTH-06")
            s.commitOutcome(ProjectOutcome("C", "OUT-06", "T06", PROJECT_OUTPUT_TRUTH, "FACT", "TRUTH-06", 7, provenance = "link-only"))
            assertEquals(beforeCount, n(d, "SELECT COUNT(*) FROM campaign_truth_records"))
            assertEquals(before, truthSnapshot(d, "TRUTH-06"))
            checks(d)
        }
    }

    @Test
    fun truthOutputRefKindMustMatchCanonicalTruthKindWhenProvided() {
        db().use { d ->
            val s = readyTruthProject(d, "T-KIND")
            truth(d, "C", "TRUTH-KIND", TruthKind.FACT)
            fail { s.commitOutcome(ProjectOutcome("C", "OUT-KIND", "T-KIND", PROJECT_OUTPUT_TRUTH, "BELIEF", "TRUTH-KIND", 7, provenance = "wrong-ref-kind")) }
            checks(d)
        }
    }

    // P15-MILESTONE-EVIDENCE-01
    @Test
    fun p15MilestoneEvidence01SameProjectEarlierWorkPasses() {
        db().use { d ->
            val s = activeProject(d, "M01", withMilestone = true)
            s.recordWork(ProjectWorkRecord("C", "M01-W", "M01", "TEST", owner("A"), 5, ProjectWorkResult.SUCCESS, provenance = "work"))
            val a = ProjectMilestoneAchievement("C", "M01-ACH", "M01", "M01-M", 6, "M01-W", provenance = "evidence")
            assertEquals(a, s.achieveMilestone(a))
            checks(d)
        }
    }

    // P15-MILESTONE-EVIDENCE-02
    @Test
    fun p15MilestoneEvidence02SameProjectEqualOrderPasses() {
        db().use { d ->
            val s = activeProject(d, "M02", withMilestone = true)
            s.recordWork(ProjectWorkRecord("C", "M02-W", "M02", "TEST", owner("A"), 5, ProjectWorkResult.SUCCESS, provenance = "work"))
            val a = ProjectMilestoneAchievement("C", "M02-ACH", "M02", "M02-M", 5, "M02-W", provenance = "same-order")
            assertEquals(a, s.achieveMilestone(a))
            checks(d)
        }
    }

    // P15-MILESTONE-EVIDENCE-03
    @Test
    fun p15MilestoneEvidence03FutureSourceWorkIsRejected() {
        db().use { d ->
            val s = activeProject(d, "M03", withMilestone = true)
            s.recordWork(ProjectWorkRecord("C", "M03-W", "M03", "TEST", owner("A"), 10, ProjectWorkResult.SUCCESS, provenance = "future"))
            fail { s.achieveMilestone(ProjectMilestoneAchievement("C", "M03-ACH", "M03", "M03-M", 5, "M03-W", provenance = "illegal-future")) }
            checks(d)
        }
    }

    // P15-MILESTONE-EVIDENCE-04
    @Test
    fun p15MilestoneEvidence04SourceWorkFromAnotherProjectIsRejected() {
        db().use { d ->
            val p1 = activeProject(d, "M04-A", withMilestone = true)
            val p2 = activeProject(d, "M04-B")
            p2.recordWork(ProjectWorkRecord("C", "M04-B-W", "M04-B", "TEST", owner("A"), 5, ProjectWorkResult.SUCCESS, provenance = "other-project"))
            fail { p1.achieveMilestone(ProjectMilestoneAchievement("C", "M04-ACH", "M04-A", "M04-A-M", 6, "M04-B-W", provenance = "bad-project")) }
            checks(d)
        }
    }

    // P15-MILESTONE-EVIDENCE-05
    @Test
    fun p15MilestoneEvidence05SourceWorkFromWrongCampaignIsRejected() {
        db().use { d ->
            val c = activeProject(d, "M05-C", "C", withMilestone = true)
            val other = activeProject(d, "M05-D", "D")
            other.recordWork(ProjectWorkRecord("D", "M05-D-W", "M05-D", "TEST", owner("A"), 5, ProjectWorkResult.SUCCESS, provenance = "other-campaign"))
            fail { c.achieveMilestone(ProjectMilestoneAchievement("C", "M05-ACH", "M05-C", "M05-C-M", 6, "M05-D-W", provenance = "bad-campaign")) }
            checks(d)
        }
    }

    // P15-MILESTONE-EVIDENCE-06
    @Test
    fun p15MilestoneEvidence06NonexistentSourceWorkIsRejected() {
        db().use { d ->
            val s = activeProject(d, "M06", withMilestone = true)
            fail { s.achieveMilestone(ProjectMilestoneAchievement("C", "M06-ACH", "M06", "M06-M", 6, "GHOST-WORK", provenance = "ghost")) }
            checks(d)
        }
    }

    // P15-MILESTONE-EVIDENCE-07
    @Test
    fun p15MilestoneEvidence07DirectSqlBypassIsRejectedBySQLite() {
        db().use { d ->
            val s = activeProject(d, "M07", withMilestone = true)
            s.recordWork(ProjectWorkRecord("C", "M07-W", "M07", "TEST", owner("A"), 10, ProjectWorkResult.SUCCESS, provenance = "future"))
            fail {
                d.execSQL(
                    "INSERT INTO project_milestone_achievements(campaign_id,achievement_uid,project_uid,milestone_uid,achieved_order,source_work_record_uid,provenance) VALUES(?,?,?,?,?,?,?)",
                    arrayOf<Any?>("C", "M07-ACH", "M07", "M07-M", 5, "M07-W", "direct-sql")
                )
            }
            assertEquals(0L, n(d, "SELECT COUNT(*) FROM project_milestone_achievements WHERE achievement_uid='M07-ACH'"))
            checks(d)
        }
    }

    // P15-MILESTONE-EVIDENCE-08
    @Test
    fun p15MilestoneEvidence08ReopenPreviouslyMigratedDatabaseReinstallsCorrectGuard() {
        db().use { d ->
            val s = activeProject(d, "M08", withMilestone = true)
            s.recordWork(ProjectWorkRecord("C", "M08-W", "M08", "TEST", owner("A"), 10, ProjectWorkResult.SUCCESS, provenance = "future"))
            d.execSQL("DROP TRIGGER IF EXISTS trg_p15_achievement_insert")
            d.execSQL(
                """CREATE TRIGGER trg_p15_achievement_insert BEFORE INSERT ON project_milestone_achievements WHEN
                  NOT EXISTS(SELECT 1 FROM project_milestone_definitions m WHERE m.campaign_id=NEW.campaign_id AND m.project_uid=NEW.project_uid AND m.milestone_uid=NEW.milestone_uid) OR
                  NEW.achieved_order < (SELECT created_order FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid)
                  BEGIN SELECT RAISE(ABORT,'legacy Phase15 milestone guard'); END"""
            )
            d.execSQL("DELETE FROM rpgos_schema_migrations WHERE migration_id=?", arrayOf(PHASE15_RELEASE_BLOCKER_HOTFIX_MIGRATION_ID))
        }

        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            CurrentSchema.ensure(d, "C")
            CurrentSchema.ensure(d, "C")
            fail {
                d.execSQL(
                    "INSERT INTO project_milestone_achievements(campaign_id,achievement_uid,project_uid,milestone_uid,achieved_order,source_work_record_uid,provenance) VALUES(?,?,?,?,?,?,?)",
                    arrayOf<Any?>("C", "M08-ACH", "M08", "M08-M", 5, "M08-W", "reopen-direct-sql")
                )
            }
            assertEquals(1L, n(d, "SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='$PHASE15_RELEASE_BLOCKER_HOTFIX_MIGRATION_ID'"))
            val triggerSql = d.rawQuery("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='trg_p15_achievement_insert'", null).use { c ->
                assertTrue(c.moveToFirst())
                c.getString(0)
            }
            assertTrue(triggerSql.contains("w.project_uid=NEW.project_uid"))
            assertTrue(triggerSql.contains("w.effective_order<=NEW.achieved_order"))
            checks(d)
        }
    }
}
