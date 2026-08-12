package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE15_HARDENING_MIGRATION_ID = "RPGOS-15.1-DEVELOPMENT-PROJECT-GUARDS"
const val PHASE15_RELEASE_BLOCKER_HOTFIX_MIGRATION_ID = "RPGOS-15.2-DEVELOPMENT-PROJECT-RELEASE-BLOCKER-HOTFIX"

/** Release-boundary guards for target identity, temporal ordering and complete standard lifecycle. */
fun MigrationManager.ensureV15Hardening(db:SQLiteDatabase,campaignId:String){
    ensureV15Guards(db,campaignId)
    db.beginTransaction();try{
        listOf("trg_p15_target_reference_guard","trg_p15_uid_guard_project","trg_p15_uid_guard_status","trg_p15_uid_guard_work").forEach{db.execSQL("DROP TRIGGER IF EXISTS $it")}
        db.execSQL("""CREATE TRIGGER trg_p15_target_reference_guard BEFORE INSERT ON development_projects WHEN NEW.target_uid IS NOT NULL AND (
          (NEW.target_domain_uid='TECHNIQUE' AND NOT EXISTS(SELECT 1 FROM technique_definitions_v2 t WHERE t.technique_uid=NEW.target_uid AND t.definition_status='ACTIVE')) OR
          (NEW.target_domain_uid='SKILL' AND NOT EXISTS(SELECT 1 FROM skill_definitions_v2 s WHERE s.skill_uid=NEW.target_uid AND s.definition_status='ACTIVE')) OR
          (NEW.target_domain_uid='ITEM_INSTANCE' AND NOT EXISTS(SELECT 1 FROM item_instances i WHERE i.campaign_id=NEW.campaign_id AND i.item_instance_uid=NEW.target_uid)) OR
          (NEW.target_domain_uid='ASSET' AND (NEW.target_kind_uid IS NULL OR NOT EXISTS(SELECT 1 FROM asset_records a WHERE a.campaign_id=NEW.campaign_id AND a.asset_kind_uid=NEW.target_kind_uid AND a.asset_uid=NEW.target_uid AND a.lifecycle_status='ACTIVE'))) OR
          (NEW.target_domain_uid='PROJECT' AND NOT EXISTS(SELECT 1 FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.target_uid)) OR
          NEW.target_domain_uid NOT IN ('TECHNIQUE','SKILL','ITEM_INSTANCE','ASSET','PROJECT')
        ) BEGIN SELECT RAISE(ABORT,'DevelopmentProject target reference unresolved or unsupported'); END""")
        db.execSQL("CREATE TRIGGER trg_p15_uid_guard_project BEFORE INSERT ON development_projects WHEN length(trim(NEW.project_uid))=0 BEGIN SELECT RAISE(ABORT,'project UID must be nonblank'); END")
        db.execSQL("CREATE TRIGGER trg_p15_uid_guard_status BEFORE INSERT ON project_status_history WHEN length(trim(NEW.status_event_uid))=0 BEGIN SELECT RAISE(ABORT,'project status event UID must be nonblank'); END")
        db.execSQL("CREATE TRIGGER trg_p15_uid_guard_work BEFORE INSERT ON project_work_records WHEN length(trim(NEW.work_record_uid))=0 BEGIN SELECT RAISE(ABORT,'project work UID must be nonblank'); END")

        db.execSQL("DROP TRIGGER IF EXISTS trg_p15_status_insert")
        db.execSQL("""CREATE TRIGGER trg_p15_status_insert BEFORE INSERT ON project_status_history WHEN
          NOT EXISTS(SELECT 1 FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) OR
          NEW.effective_order < (SELECT created_order FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) OR
          EXISTS(SELECT 1 FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid AND s.effective_order>=NEW.effective_order) OR
          EXISTS(SELECT 1 FROM project_work_records w WHERE w.campaign_id=NEW.campaign_id AND w.project_uid=NEW.project_uid AND w.effective_order>=NEW.effective_order) OR
          EXISTS(SELECT 1 FROM project_milestone_achievements a WHERE a.campaign_id=NEW.campaign_id AND a.project_uid=NEW.project_uid AND a.achieved_order>NEW.effective_order) OR
          EXISTS(SELECT 1 FROM project_outcomes o WHERE o.campaign_id=NEW.campaign_id AND o.project_uid=NEW.project_uid AND o.committed_order>NEW.effective_order) OR
          (NOT EXISTS(SELECT 1 FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid) AND NEW.status<>'IDEA') OR
          (EXISTS(SELECT 1 FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid) AND NOT (
            (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='IDEA' AND NEW.status IN ('REQUIREMENTS','SUPERSEDED','CANCELLED')) OR
            (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='REQUIREMENTS' AND NEW.status IN ('PROTOTYPE','PAUSED','FAILED','SUPERSEDED','CANCELLED')) OR
            (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='PROTOTYPE' AND NEW.status IN ('ACTIVE_WORK','PAUSED','FAILED','SUPERSEDED','CANCELLED')) OR
            (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='ACTIVE_WORK' AND NEW.status IN ('STABILIZATION','PAUSED','FAILED','SUPERSEDED','CANCELLED')) OR
            (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='STABILIZATION' AND NEW.status IN ('READY_TO_COMPLETE','ACTIVE_WORK','PAUSED','FAILED','SUPERSEDED','CANCELLED')) OR
            (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='READY_TO_COMPLETE' AND NEW.status IN ('COMPLETED','ACTIVE_WORK','FAILED','SUPERSEDED','CANCELLED')) OR
            (COALESCE((SELECT status FROM project_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.project_uid=NEW.project_uid ORDER BY effective_order DESC LIMIT 1),'')='PAUSED' AND NEW.status IN ('REQUIREMENTS','PROTOTYPE','ACTIVE_WORK','STABILIZATION','READY_TO_COMPLETE','ABANDONED','SUPERSEDED','CANCELLED'))
          )) OR
          (NEW.status='PROTOTYPE' AND EXISTS(SELECT 1 FROM project_requirements r WHERE r.campaign_id=NEW.campaign_id AND r.project_uid=NEW.project_uid AND r.required=1 AND r.required_from_order<=NEW.effective_order AND NOT EXISTS(SELECT 1 FROM project_requirement_satisfactions x WHERE x.campaign_id=r.campaign_id AND x.project_uid=r.project_uid AND x.requirement_uid=r.requirement_uid AND x.satisfied_order<=NEW.effective_order))) OR
          (NEW.status='READY_TO_COMPLETE' AND EXISTS(SELECT 1 FROM project_milestone_definitions m WHERE m.campaign_id=NEW.campaign_id AND m.project_uid=NEW.project_uid AND m.required=1 AND NOT EXISTS(SELECT 1 FROM project_milestone_achievements a WHERE a.campaign_id=m.campaign_id AND a.project_uid=m.project_uid AND a.milestone_uid=m.milestone_uid AND a.achieved_order<=NEW.effective_order))) OR
          (NEW.status='COMPLETED' AND (SELECT intended_output_kind_uid FROM development_projects p WHERE p.campaign_id=NEW.campaign_id AND p.project_uid=NEW.project_uid) IS NOT NULL AND NOT EXISTS(SELECT 1 FROM project_outcomes o WHERE o.campaign_id=NEW.campaign_id AND o.project_uid=NEW.project_uid AND o.committed_order<=NEW.effective_order)) OR
          (NEW.status='SUPERSEDED' AND (NEW.successor_project_uid=NEW.project_uid OR NOT EXISTS(SELECT 1 FROM development_projects q WHERE q.campaign_id=NEW.campaign_id AND q.project_uid=NEW.successor_project_uid)))
          BEGIN SELECT RAISE(ABORT,'illegal DevelopmentProject lifecycle transition'); END""")

        db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE15_HARDENING_MIGRATION_ID',strftime('%s','now'),'Phase15 target identity, stable UID and temporal lifecycle hardening; no Phase16 command/orchestration implementation')")
        db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE15_RELEASE_BLOCKER_HOTFIX_MIGRATION_ID',strftime('%s','now'),'Phase15 release-blocker hotfix: canonical Truth outcome links plus milestone source-work same-project and temporal-causality guards; no Phase16 implementation')")
        db.setTransactionSuccessful()
    }finally{db.endTransaction()}
}
