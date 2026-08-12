package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE15_MIGRATION_ID = "RPGOS-15.0-DEVELOPMENT-PROJECTS"

fun MigrationManager.ensureV15(db: SQLiteDatabase, campaignId: String) {
    ensureV14Hardening(db, campaignId)
    db.beginTransaction()
    try {
        db.execSQL("""CREATE TABLE IF NOT EXISTS project_type_definitions(
            project_type_uid TEXT PRIMARY KEY,
            generic_category_uid TEXT NOT NULL CHECK(length(trim(generic_category_uid))>0),
            lifecycle_policy_uid TEXT NOT NULL CHECK(length(trim(lifecycle_policy_uid))>0),
            world_pack_uid TEXT,
            definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),
            definition_version INTEGER NOT NULL CHECK(typeof(definition_version)='integer' AND definition_version>=1),
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0), metadata_json TEXT,
            CHECK(world_pack_uid IS NULL OR length(trim(world_pack_uid))>0))""")
        listOf(
            PROJECT_TYPE_RESEARCH to "RESEARCH", PROJECT_TYPE_TECHNIQUE to "TECHNIQUE_DEVELOPMENT",
            PROJECT_TYPE_SKILL to "SKILL_DEVELOPMENT", PROJECT_TYPE_CRAFTING to "CRAFTING",
            PROJECT_TYPE_INFRASTRUCTURE to "INFRASTRUCTURE", PROJECT_TYPE_ADAPTATION to "ADAPTATION"
        ).forEach { (uid, cat) ->
            db.execSQL("INSERT OR IGNORE INTO project_type_definitions(project_type_uid,generic_category_uid,lifecycle_policy_uid,definition_status,definition_version,provenance) VALUES(?,?,'RPGOS-PROJECT-LIFECYCLE:STANDARD','ACTIVE',1,'RPGOS-15 core generic project type')", arrayOf(uid,cat))
        }

        db.execSQL("""CREATE TABLE IF NOT EXISTS development_projects(
            campaign_id TEXT NOT NULL, project_uid TEXT NOT NULL, project_type_uid TEXT NOT NULL,
            initiator_kind_uid TEXT NOT NULL, initiator_uid TEXT NOT NULL,
            beneficiary_kind_uid TEXT, beneficiary_uid TEXT,
            title TEXT NOT NULL CHECK(length(trim(title))>0), objective_summary TEXT NOT NULL CHECK(length(trim(objective_summary))>0),
            target_domain_uid TEXT NOT NULL CHECK(length(trim(target_domain_uid))>0), target_kind_uid TEXT, target_uid TEXT,
            intended_output_kind_uid TEXT, progress_cap_units INTEGER,
            created_order INTEGER NOT NULL CHECK(typeof(created_order)='integer'), started_order INTEGER,
            project_version INTEGER NOT NULL CHECK(typeof(project_version)='integer' AND project_version>=1),
            source_event_uid TEXT, provenance TEXT NOT NULL CHECK(length(trim(provenance))>0), metadata_json TEXT,
            PRIMARY KEY(campaign_id,project_uid),
            FOREIGN KEY(project_type_uid) REFERENCES project_type_definitions(project_type_uid),
            FOREIGN KEY(campaign_id,initiator_kind_uid,initiator_uid) REFERENCES ownership_party_registry(campaign_id,owner_kind_uid,owner_uid),
            FOREIGN KEY(campaign_id,beneficiary_kind_uid,beneficiary_uid) REFERENCES ownership_party_registry(campaign_id,owner_kind_uid,owner_uid),
            CHECK((beneficiary_kind_uid IS NULL AND beneficiary_uid IS NULL) OR (beneficiary_kind_uid IS NOT NULL AND beneficiary_uid IS NOT NULL)),
            CHECK((target_kind_uid IS NULL AND target_uid IS NULL) OR (target_kind_uid IS NOT NULL AND target_uid IS NOT NULL)),
            CHECK(progress_cap_units IS NULL OR (typeof(progress_cap_units)='integer' AND progress_cap_units>0)),
            CHECK(started_order IS NULL OR (typeof(started_order)='integer' AND started_order>=created_order)),
            CHECK(source_event_uid IS NULL OR length(trim(source_event_uid))>0),
            CHECK(intended_output_kind_uid IS NULL OR length(trim(intended_output_kind_uid))>0))""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS project_status_history(
            campaign_id TEXT NOT NULL, status_event_uid TEXT NOT NULL, project_uid TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('IDEA','REQUIREMENTS','PROTOTYPE','ACTIVE_WORK','STABILIZATION','READY_TO_COMPLETE','PAUSED','COMPLETED','ABANDONED','FAILED','SUPERSEDED','CANCELLED')),
            effective_order INTEGER NOT NULL CHECK(typeof(effective_order)='integer'), successor_project_uid TEXT, source_event_uid TEXT,
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
            PRIMARY KEY(campaign_id,status_event_uid), FOREIGN KEY(campaign_id,project_uid) REFERENCES development_projects(campaign_id,project_uid),
            FOREIGN KEY(campaign_id,successor_project_uid) REFERENCES development_projects(campaign_id,project_uid),
            CHECK((status='SUPERSEDED' AND successor_project_uid IS NOT NULL) OR (status<>'SUPERSEDED' AND successor_project_uid IS NULL)),
            CHECK(source_event_uid IS NULL OR length(trim(source_event_uid))>0))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_p15_project_status_order ON project_status_history(campaign_id,project_uid,effective_order)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS project_requirements(
            campaign_id TEXT NOT NULL, requirement_uid TEXT NOT NULL, project_uid TEXT NOT NULL,
            requirement_type_uid TEXT NOT NULL CHECK(length(trim(requirement_type_uid))>0), target_kind_uid TEXT, target_uid TEXT,
            comparator_uid TEXT, threshold_value INTEGER, quantity_value INTEGER,
            required INTEGER NOT NULL CHECK(required IN (0,1)), required_from_order INTEGER NOT NULL CHECK(typeof(required_from_order)='integer'),
            requirement_version INTEGER NOT NULL CHECK(requirement_version>=1), provenance TEXT NOT NULL CHECK(length(trim(provenance))>0), metadata_json TEXT,
            PRIMARY KEY(campaign_id,requirement_uid), FOREIGN KEY(campaign_id,project_uid) REFERENCES development_projects(campaign_id,project_uid),
            CHECK((target_kind_uid IS NULL AND target_uid IS NULL) OR (target_kind_uid IS NOT NULL AND target_uid IS NOT NULL)),
            CHECK(threshold_value IS NULL OR typeof(threshold_value)='integer'), CHECK(quantity_value IS NULL OR (typeof(quantity_value)='integer' AND quantity_value>=0)))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS project_requirement_satisfactions(
            campaign_id TEXT NOT NULL, satisfaction_uid TEXT NOT NULL, project_uid TEXT NOT NULL, requirement_uid TEXT NOT NULL,
            satisfied_order INTEGER NOT NULL CHECK(typeof(satisfied_order)='integer'), evidence_kind_uid TEXT, evidence_uid TEXT, source_event_uid TEXT,
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0), PRIMARY KEY(campaign_id,satisfaction_uid),
            FOREIGN KEY(campaign_id,project_uid) REFERENCES development_projects(campaign_id,project_uid),
            FOREIGN KEY(campaign_id,requirement_uid) REFERENCES project_requirements(campaign_id,requirement_uid),
            CHECK((evidence_kind_uid IS NULL AND evidence_uid IS NULL) OR (evidence_kind_uid IS NOT NULL AND evidence_uid IS NOT NULL)))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_p15_requirement_satisfied ON project_requirement_satisfactions(campaign_id,project_uid,requirement_uid)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS project_milestone_definitions(
            campaign_id TEXT NOT NULL, milestone_uid TEXT NOT NULL, project_uid TEXT NOT NULL, sequence_order INTEGER NOT NULL,
            milestone_type_uid TEXT NOT NULL CHECK(length(trim(milestone_type_uid))>0), success_criteria TEXT NOT NULL CHECK(length(trim(success_criteria))>0),
            required INTEGER NOT NULL CHECK(required IN (0,1)), provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
            PRIMARY KEY(campaign_id,milestone_uid), FOREIGN KEY(campaign_id,project_uid) REFERENCES development_projects(campaign_id,project_uid))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_p15_milestone_sequence ON project_milestone_definitions(campaign_id,project_uid,sequence_order)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS project_milestone_achievements(
            campaign_id TEXT NOT NULL, achievement_uid TEXT NOT NULL, project_uid TEXT NOT NULL, milestone_uid TEXT NOT NULL,
            achieved_order INTEGER NOT NULL CHECK(typeof(achieved_order)='integer'), source_work_record_uid TEXT, source_event_uid TEXT,
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0), PRIMARY KEY(campaign_id,achievement_uid),
            FOREIGN KEY(campaign_id,project_uid) REFERENCES development_projects(campaign_id,project_uid),
            FOREIGN KEY(campaign_id,milestone_uid) REFERENCES project_milestone_definitions(campaign_id,milestone_uid),
            FOREIGN KEY(campaign_id,source_work_record_uid) REFERENCES project_work_records(campaign_id,work_record_uid))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_p15_milestone_achievement ON project_milestone_achievements(campaign_id,project_uid,milestone_uid)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS project_work_records(
            campaign_id TEXT NOT NULL, work_record_uid TEXT NOT NULL, project_uid TEXT NOT NULL, work_kind_uid TEXT NOT NULL CHECK(length(trim(work_kind_uid))>0),
            actor_kind_uid TEXT NOT NULL, actor_uid TEXT NOT NULL, effective_order INTEGER NOT NULL CHECK(typeof(effective_order)='integer'),
            result_kind TEXT NOT NULL CHECK(result_kind IN ('SUCCESS','PARTIAL','FAILURE','BREAKTHROUGH','NO_PROGRESS','INCIDENT')),
            progress_delta_units INTEGER NOT NULL DEFAULT 0 CHECK(typeof(progress_delta_units)='integer' AND progress_delta_units>=0), effort_units INTEGER,
            financial_transaction_uid TEXT, command_uid TEXT, source_event_uid TEXT, provenance TEXT NOT NULL CHECK(length(trim(provenance))>0), metadata_json TEXT,
            PRIMARY KEY(campaign_id,work_record_uid), FOREIGN KEY(campaign_id,project_uid) REFERENCES development_projects(campaign_id,project_uid),
            FOREIGN KEY(campaign_id,actor_kind_uid,actor_uid) REFERENCES ownership_party_registry(campaign_id,owner_kind_uid,owner_uid),
            FOREIGN KEY(campaign_id,financial_transaction_uid) REFERENCES financial_ledger_transactions(campaign_id,financial_transaction_uid),
            CHECK(effort_units IS NULL OR (typeof(effort_units)='integer' AND effort_units>=0)), CHECK(command_uid IS NULL OR length(trim(command_uid))>0))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_p15_work_command ON project_work_records(campaign_id,command_uid) WHERE command_uid IS NOT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p15_work_project_order ON project_work_records(campaign_id,project_uid,effective_order,work_record_uid)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS project_dependencies(
            campaign_id TEXT NOT NULL, dependency_uid TEXT NOT NULL, project_uid TEXT NOT NULL, depends_on_project_uid TEXT NOT NULL,
            dependency_type_uid TEXT NOT NULL CHECK(length(trim(dependency_type_uid))>0), milestone_uid TEXT, valid_from_order INTEGER NOT NULL,
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0), PRIMARY KEY(campaign_id,dependency_uid),
            FOREIGN KEY(campaign_id,project_uid) REFERENCES development_projects(campaign_id,project_uid),
            FOREIGN KEY(campaign_id,depends_on_project_uid) REFERENCES development_projects(campaign_id,project_uid),
            FOREIGN KEY(campaign_id,milestone_uid) REFERENCES project_milestone_definitions(campaign_id,milestone_uid),
            CHECK(project_uid<>depends_on_project_uid))""")

        db.execSQL("""CREATE TABLE IF NOT EXISTS project_outcomes(
            campaign_id TEXT NOT NULL, outcome_uid TEXT NOT NULL, project_uid TEXT NOT NULL, output_kind_uid TEXT NOT NULL,
            output_ref_kind_uid TEXT, output_uid TEXT NOT NULL CHECK(length(trim(output_uid))>0), committed_order INTEGER NOT NULL,
            source_event_uid TEXT, command_uid TEXT, provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
            PRIMARY KEY(campaign_id,outcome_uid), FOREIGN KEY(campaign_id,project_uid) REFERENCES development_projects(campaign_id,project_uid))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_p15_project_output ON project_outcomes(campaign_id,project_uid,output_kind_uid,output_uid)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_p15_outcome_command ON project_outcomes(campaign_id,command_uid) WHERE command_uid IS NOT NULL")

        installPhase15Triggers(db)
        db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE15_MIGRATION_ID',strftime('%s','now'),'Generic DevelopmentProject authority: append-only lifecycle/work/requirements/milestones/dependencies/outcome links; derived progress; finance evidence only; zero legacy synthesis')")
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}

fun MigrationManager.ensureV15Guards(db: SQLiteDatabase, campaignId: String) {
    ensureV15(db, campaignId)
    db.beginTransaction(); try { installPhase15Triggers(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
}

private fun installPhase15Triggers(db: SQLiteDatabase) {
    listOf("trg_p15_type_immutable","trg_p15_project_insert","trg_p15_project_immutable","trg_p15_project_delete","trg_p15_party_retire","trg_p15_status_insert","trg_p15_status_immutable","trg_p15_status_delete","trg_p15_requirement_insert","trg_p15_requirement_immutable","trg_p15_satisfaction_insert","trg_p15_milestone_immutable","trg_p15_achievement_insert","trg_p15_work_insert","trg_p15_work_immutable","trg_p15_dependency_insert","trg_p15_dependency_immutable","trg_p15_outcome_insert","trg_p15_outcome_immutable").forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
    db.execSQL("CREATE TRIGGER trg_p15_type_immutable BEFORE UPDATE ON project_type_definitions BEGIN SELECT RAISE(ABORT,'project type meaning is immutable; use a new stable UID'); END")
    db.execSQL("""CREATE TRIGGER trg_p15_project_insert BEFORE INSERT ON development_projects WHEN
      NOT EXISTS(SELECT 1 FROM project_type_definitions t WHERE t.project_type_uid=NEW.project_type_uid AND t.definition_status='ACTIVE') OR
      NOT EXISTS(SELECT 1 FROM ownership_party_registry p WHERE p.campaign_id=NEW.campaign_id AND p.owner_kind_uid=NEW.initiator_kind_uid AND p.owner_uid=NEW.initiator_uid AND p.reference_status='ACTIVE') OR
      (NEW.beneficiary_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM ownership_party_registry p WHERE p.campaign_id=NEW.campaign_id AND p.owner_kind_uid=NEW.beneficiary_kind_uid AND p.owner_uid=NEW.beneficiary_uid AND p.reference_status='ACTIVE'))
      BEGIN SELECT RAISE(ABORT,'project type/party reference unresolved or inactive'); END""")
    db.execSQL("CREATE TRIGGER trg_p15_project_immutable BEFORE UPDATE ON development_projects BEGIN SELECT RAISE(ABORT,'DevelopmentProject identity/intent is immutable; use history/superseding project'); END")
    db.execSQL("CREATE TRIGGER trg_p15_project_delete BEFORE DELETE ON development_projects BEGIN SELECT RAISE(ABORT,'DevelopmentProject history is append-preserved'); END")
    db.execSQL("""CREATE TRIGGER trg_p15_party_retire BEFORE UPDATE OF reference_status ON ownership_party_registry WHEN OLD.reference_status='ACTIVE' AND NEW.reference_status='RETIRED' AND EXISTS(
      SELECT 1 FROM development_projects p WHERE p.campaign_id=OLD.campaign_id AND ((p.initiator_kind_uid=OLD.owner_kind_uid AND p.initiator_uid=OLD.owner_uid) OR (p.beneficiary_kind_uid=OLD.owner_kind_uid AND p.beneficiary_uid=OLD.owner_uid))
      AND COALESCE((SELECT s.status FROM project_status_history s WHERE s.campaign_id=p.campaign_id AND s.project_uid=p.project_uid ORDER BY s.effective_order DESC LIMIT 1),'IDEA') NOT IN ('COMPLETED','ABANDONED','FAILED','SUPERSEDED','CANCELLED'))
      BEGIN SELECT RAISE(ABORT,'cannot retire party while DevelopmentProject is active'); END""")

    db.execSQL("""CREATE TRIGGER trg_p15_status_insert BEFORE INSERT ON project_status_history WHEN
      NOT EXISTS(SELECT 1 FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) OR
      NEW.effective_order < (SELECT created_order FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) OR
      EXISTS(SELECT 1 FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid AND s.effective_order>=NEW.effective_order) OR
      (NOT EXISTS(SELECT 1 FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid) AND NEW.status<>'IDEA') OR
      (EXISTS(SELECT 1 FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid) AND NOT (
        (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='IDEA' AND NEW.status IN ('REQUIREMENTS','CANCELLED')) OR
        (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='REQUIREMENTS' AND NEW.status IN ('PROTOTYPE','PAUSED','FAILED','CANCELLED')) OR
        (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='PROTOTYPE' AND NEW.status IN ('ACTIVE_WORK','PAUSED','FAILED','CANCELLED')) OR
        (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='ACTIVE_WORK' AND NEW.status IN ('STABILIZATION','PAUSED','FAILED','CANCELLED')) OR
        (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='STABILIZATION' AND NEW.status IN ('READY_TO_COMPLETE','ACTIVE_WORK','PAUSED','FAILED','CANCELLED')) OR
        (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='READY_TO_COMPLETE' AND NEW.status IN ('COMPLETED','ACTIVE_WORK','FAILED','CANCELLED')) OR
        (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='PAUSED' AND NEW.status IN ('REQUIREMENTS','PROTOTYPE','ACTIVE_WORK','STABILIZATION','READY_TO_COMPLETE','ABANDONED','CANCELLED'))
      )) OR
      (NEW.status='PROTOTYPE' AND EXISTS(SELECT 1 FROM project_requirements r WHERE r.campaign_id=NEW.campaign_id AND r.project_uid=NEW.project_uid AND r.required=1 AND r.required_from_order<=NEW.effective_order AND NOT EXISTS(SELECT 1 FROM project_requirement_satisfactions x WHERE x.campaign_id=r.campaign_id AND x.project_uid=r.project_uid AND x.requirement_uid=r.requirement_uid AND x.satisfied_order<=NEW.effective_order))) OR
      (NEW.status='READY_TO_COMPLETE' AND EXISTS(SELECT 1 FROM project_milestone_definitions m WHERE m.campaign_id=NEW.campaign_id AND m.project_uid=NEW.project_uid AND m.required=1 AND NOT EXISTS(SELECT 1 FROM project_milestone_achievements a WHERE a.campaign_id=m.campaign_id AND a.project_uid=m.project_uid AND a.milestone_uid=m.milestone_uid AND a.achieved_order<=NEW.effective_order))) OR
      (NEW.status='COMPLETED' AND (SELECT intended_output_kind_uid FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) IS NOT NULL AND NOT EXISTS(SELECT 1 FROM project_outcomes o WHERE o.campaign_id=NEW.campaign_id AND o.project_uid=NEW.project_uid AND o.committed_order<=NEW.effective_order)) OR
      (NEW.status='SUPERSEDED' AND (NEW.successor_project_uid=NEW.project_uid OR NOT EXISTS(SELECT 1 FROM development_projects q WHERE q.campaign_id=NEW.campaign_id AND q.project_uid=NEW.successor_project_uid)))
      BEGIN SELECT RAISE(ABORT,'illegal DevelopmentProject lifecycle transition'); END""")
    db.execSQL("CREATE TRIGGER trg_p15_status_immutable BEFORE UPDATE ON project_status_history BEGIN SELECT RAISE(ABORT,'project status history is immutable'); END")
    db.execSQL("CREATE TRIGGER trg_p15_status_delete BEFORE DELETE ON project_status_history BEGIN SELECT RAISE(ABORT,'project status history is append-only'); END")

    db.execSQL("""CREATE TRIGGER trg_p15_requirement_insert BEFORE INSERT ON project_requirements WHEN NEW.required_from_order < (SELECT created_order FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) BEGIN SELECT RAISE(ABORT,'requirement predates project'); END""")
    db.execSQL("CREATE TRIGGER trg_p15_requirement_immutable BEFORE UPDATE ON project_requirements BEGIN SELECT RAISE(ABORT,'project requirement definition is immutable'); END")
    db.execSQL("""CREATE TRIGGER trg_p15_satisfaction_insert BEFORE INSERT ON project_requirement_satisfactions WHEN
      NOT EXISTS(SELECT 1 FROM project_requirements r WHERE r.campaign_id=NEW.campaign_id AND r.project_uid=NEW.project_uid AND r.requirement_uid=NEW.requirement_uid AND r.required_from_order<=NEW.satisfied_order)
      BEGIN SELECT RAISE(ABORT,'requirement satisfaction reference/order invalid'); END""")
    db.execSQL("CREATE TRIGGER trg_p15_milestone_immutable BEFORE UPDATE ON project_milestone_definitions BEGIN SELECT RAISE(ABORT,'project milestone definition is immutable'); END")
    db.execSQL("""CREATE TRIGGER trg_p15_achievement_insert BEFORE INSERT ON project_milestone_achievements WHEN
      NOT EXISTS(SELECT 1 FROM project_milestone_definitions m WHERE m.campaign_id=NEW.campaign_id AND m.project_uid=NEW.project_uid AND m.milestone_uid=NEW.milestone_uid) OR
      NEW.achieved_order < (SELECT created_order FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid)
      BEGIN SELECT RAISE(ABORT,'milestone achievement reference/order invalid'); END""")

    db.execSQL("""CREATE TRIGGER trg_p15_work_insert BEFORE INSERT ON project_work_records WHEN
      COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'MISSING') NOT IN ('PROTOTYPE','ACTIVE_WORK','STABILIZATION') OR
      NOT EXISTS(SELECT 1 FROM ownership_party_registry p WHERE p.campaign_id=NEW.campaign_id AND p.owner_kind_uid=NEW.actor_kind_uid AND p.owner_uid=NEW.actor_uid AND p.reference_status='ACTIVE') OR
      NEW.effective_order < COALESCE((SELECT MAX(effective_order) FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid),(SELECT created_order FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid)) OR
      (NEW.financial_transaction_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM financial_ledger_transactions f WHERE f.campaign_id=NEW.campaign_id AND f.financial_transaction_uid=NEW.financial_transaction_uid AND f.effective_order<=NEW.effective_order)) OR
      ((SELECT progress_cap_units FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) IS NOT NULL AND NEW.progress_delta_units > (SELECT progress_cap_units FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) - COALESCE((SELECT SUM(w.progress_delta_units) FROM project_work_records w WHERE w.campaign_id=NEW.campaign_id AND w.project_uid=NEW.project_uid),0)) OR
      NEW.progress_delta_units > 9223372036854775807 - COALESCE((SELECT SUM(w.progress_delta_units) FROM project_work_records w WHERE w.campaign_id=NEW.campaign_id AND w.project_uid=NEW.project_uid),0)
      BEGIN SELECT RAISE(ABORT,'project work violates lifecycle/reference/progress boundary'); END""")
    db.execSQL("CREATE TRIGGER trg_p15_work_immutable BEFORE UPDATE ON project_work_records BEGIN SELECT RAISE(ABORT,'project work history is immutable'); END")

    db.execSQL("""CREATE TRIGGER trg_p15_dependency_insert BEFORE INSERT ON project_dependencies WHEN
      NOT EXISTS(SELECT 1 FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) OR
      NOT EXISTS(SELECT 1 FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.depends_on_project_uid) OR
      EXISTS(WITH RECURSIVE reach(x) AS (SELECT depends_on_project_uid FROM project_dependencies WHERE campaign_id=NEW.campaign_id AND project_uid=NEW.depends_on_project_uid UNION ALL SELECT d.depends_on_project_uid FROM project_dependencies d JOIN reach r ON d.project_uid=r.x WHERE d.campaign_id=NEW.campaign_id) SELECT 1 FROM reach WHERE x=NEW.project_uid)
      BEGIN SELECT RAISE(ABORT,'project dependency unresolved or cyclic'); END""")
    db.execSQL("CREATE TRIGGER trg_p15_dependency_immutable BEFORE UPDATE ON project_dependencies BEGIN SELECT RAISE(ABORT,'project dependency history is immutable'); END")

    db.execSQL("""CREATE TRIGGER trg_p15_outcome_insert BEFORE INSERT ON project_outcomes WHEN
      COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'MISSING')<>'READY_TO_COMPLETE' OR
      NEW.output_kind_uid<>(SELECT intended_output_kind_uid FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) OR
      (NEW.output_kind_uid='$PROJECT_OUTPUT_TECHNIQUE' AND NOT EXISTS(SELECT 1 FROM development_projects p JOIN player_techniques_v2 t ON t.campaign_id=p.campaign_id AND t.character_uid=COALESCE(p.beneficiary_uid,p.initiator_uid) AND t.technique_uid=NEW.output_uid WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid AND COALESCE(p.beneficiary_kind_uid,p.initiator_kind_uid)='CHARACTER')) OR
      (NEW.output_kind_uid='$PROJECT_OUTPUT_SKILL' AND NOT EXISTS(SELECT 1 FROM development_projects p JOIN player_skills_v2 s ON s.campaign_id=p.campaign_id AND s.character_uid=COALESCE(p.beneficiary_uid,p.initiator_uid) AND s.skill_uid=NEW.output_uid WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid AND COALESCE(p.beneficiary_kind_uid,p.initiator_kind_uid)='CHARACTER')) OR
      (NEW.output_kind_uid='$PROJECT_OUTPUT_ITEM_INSTANCE' AND NOT EXISTS(SELECT 1 FROM item_instances i WHERE i.campaign_id=NEW.campaign_id AND i.item_instance_uid=NEW.output_uid)) OR
      (NEW.output_kind_uid='$PROJECT_OUTPUT_ASSET' AND (NEW.output_ref_kind_uid IS NULL OR NOT EXISTS(SELECT 1 FROM asset_records a WHERE a.campaign_id=NEW.campaign_id AND a.asset_kind_uid=NEW.output_ref_kind_uid AND a.asset_uid=NEW.output_uid))) OR
      NEW.output_kind_uid NOT IN ('$PROJECT_OUTPUT_TECHNIQUE','$PROJECT_OUTPUT_SKILL','$PROJECT_OUTPUT_ITEM_INSTANCE','$PROJECT_OUTPUT_ASSET')
      BEGIN SELECT RAISE(ABORT,'project outcome unresolved, wrong kind, or project not ready'); END""")
    db.execSQL("CREATE TRIGGER trg_p15_outcome_immutable BEFORE UPDATE ON project_outcomes BEGIN SELECT RAISE(ABORT,'project outcome history is immutable'); END")

    listOf("project_status_history","project_requirements","project_requirement_satisfactions","project_milestone_definitions","project_milestone_achievements","project_work_records","project_dependencies","project_outcomes").forEach { table ->
        db.execSQL("CREATE TRIGGER IF NOT EXISTS trg_p15_${table}_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT,'Phase15 project history is append-only'); END")
    }
}
