package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class DevelopmentProjectStore(private val db: SQLiteDatabase, private val campaignId: String) {
    init { MigrationManager().ensureV15Guards(db, campaignId) }

    fun registerProjectType(d: ProjectTypeDefinition): ProjectTypeDefinition = stableUidWrite("type", d.projectTypeUid) {
        val existing = existingType(d.projectTypeUid)
        if (existing != null) { require(existing == d) { "project type UID semantic conflict" }; return@stableUidWrite existing }
        db.execSQL("INSERT INTO project_type_definitions(project_type_uid,generic_category_uid,lifecycle_policy_uid,world_pack_uid,definition_status,definition_version,provenance,metadata_json) VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any?>(d.projectTypeUid,d.genericCategoryUid,d.lifecyclePolicyUid,d.worldPackUid,d.definitionStatus,d.definitionVersion,d.provenance,d.metadataJson))
        existingType(d.projectTypeUid)!!
    }

    fun createProject(p: DevelopmentProject, initialStatusEventUid: String): DevelopmentProject = stableUidWrite("project", p.projectUid) {
        require(p.campaignId == campaignId)
        inTx {
            val existing = existingProject(p.projectUid)
            if (existing != null) {
                require(existing == p) { "project UID semantic conflict" }
                require(statusEventMatches(ProjectStatusEvent(campaignId,initialStatusEventUid,p.projectUid,ProjectStatus.IDEA,p.createdOrder,sourceEventUid=p.sourceEventUid,provenance=p.provenance))) { "project initial status UID semantic conflict" }
                return@inTx existing
            }
            db.execSQL("""INSERT INTO development_projects(campaign_id,project_uid,project_type_uid,initiator_kind_uid,initiator_uid,beneficiary_kind_uid,beneficiary_uid,title,objective_summary,target_domain_uid,target_kind_uid,target_uid,intended_output_kind_uid,progress_cap_units,created_order,started_order,project_version,source_event_uid,provenance,metadata_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf<Any?>(p.campaignId,p.projectUid,p.projectTypeUid,p.initiator.ownerKindUid,p.initiator.ownerUid,p.beneficiary?.ownerKindUid,p.beneficiary?.ownerUid,p.title,p.objectiveSummary,p.targetDomainUid,p.targetKindUid,p.targetUid,p.intendedOutputKindUid,p.progressCapUnits,p.createdOrder,p.startedOrder,p.projectVersion,p.sourceEventUid,p.provenance,p.metadataJson))
            val initial = ProjectStatusEvent(campaignId,initialStatusEventUid,p.projectUid,ProjectStatus.IDEA,p.createdOrder,sourceEventUid=p.sourceEventUid,provenance=p.provenance)
            insertStatus(initial)
            existingProject(p.projectUid)!!
        }
    }

    fun changeStatus(e: ProjectStatusEvent): ProjectStatusEvent = stableUidWrite("status", e.statusEventUid) {
        require(e.campaignId == campaignId)
        inTx {
            val old = existingStatus(e.statusEventUid)
            if (old != null) { require(old == e) { "status event UID semantic conflict" }; return@inTx old }
            insertStatus(e)
            existingStatus(e.statusEventUid)!!
        }
    }

    fun addRequirement(r: ProjectRequirement): ProjectRequirement = stableUidWrite("requirement", r.requirementUid) {
        require(r.campaignId == campaignId)
        inTx {
            val old = existingRequirement(r.requirementUid)
            if(old!=null){ require(old==r){"requirement UID semantic conflict"}; return@inTx old }
            db.execSQL("INSERT INTO project_requirements(campaign_id,requirement_uid,project_uid,requirement_type_uid,target_kind_uid,target_uid,comparator_uid,threshold_value,quantity_value,required,required_from_order,requirement_version,provenance,metadata_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(r.campaignId,r.requirementUid,r.projectUid,r.requirementTypeUid,r.targetKindUid,r.targetUid,r.comparatorUid,r.thresholdValue,r.quantityValue,if(r.required)1 else 0,r.requiredFromOrder,r.requirementVersion,r.provenance,r.metadataJson))
            existingRequirement(r.requirementUid)!!
        }
    }

    fun satisfyRequirement(x: ProjectRequirementSatisfaction): ProjectRequirementSatisfaction = stableUidWrite("satisfaction", x.satisfactionUid) {
        require(x.campaignId==campaignId)
        inTx {
            val old=existingSatisfaction(x.satisfactionUid); if(old!=null){require(old==x){"satisfaction UID semantic conflict"};return@inTx old}
            db.execSQL("INSERT INTO project_requirement_satisfactions(campaign_id,satisfaction_uid,project_uid,requirement_uid,satisfied_order,evidence_kind_uid,evidence_uid,source_event_uid,provenance) VALUES(?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(x.campaignId,x.satisfactionUid,x.projectUid,x.requirementUid,x.satisfiedOrder,x.evidenceKindUid,x.evidenceUid,x.sourceEventUid,x.provenance))
            existingSatisfaction(x.satisfactionUid)!!
        }
    }

    fun addMilestone(m: ProjectMilestoneDefinition): ProjectMilestoneDefinition = stableUidWrite("milestone",m.milestoneUid){
        require(m.campaignId==campaignId); inTx { val old=existingMilestone(m.milestoneUid);if(old!=null){require(old==m){"milestone UID semantic conflict"};return@inTx old};db.execSQL("INSERT INTO project_milestone_definitions(campaign_id,milestone_uid,project_uid,sequence_order,milestone_type_uid,success_criteria,required,provenance) VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(m.campaignId,m.milestoneUid,m.projectUid,m.sequenceOrder,m.milestoneTypeUid,m.successCriteria,if(m.required)1 else 0,m.provenance));existingMilestone(m.milestoneUid)!! }
    }

    fun achieveMilestone(a: ProjectMilestoneAchievement): ProjectMilestoneAchievement = stableUidWrite("achievement",a.achievementUid){
        require(a.campaignId==campaignId);inTx{val old=existingAchievement(a.achievementUid);if(old!=null){require(old==a){"achievement UID semantic conflict"};return@inTx old};db.execSQL("INSERT INTO project_milestone_achievements(campaign_id,achievement_uid,project_uid,milestone_uid,achieved_order,source_work_record_uid,source_event_uid,provenance) VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(a.campaignId,a.achievementUid,a.projectUid,a.milestoneUid,a.achievedOrder,a.sourceWorkRecordUid,a.sourceEventUid,a.provenance));existingAchievement(a.achievementUid)!!}
    }

    fun recordWork(w: ProjectWorkRecord): ProjectWorkRecord = stableUidWrite("work",w.workRecordUid){
        require(w.campaignId==campaignId);inTx{val old=existingWork(w.workRecordUid);if(old!=null){require(old==w){"work UID semantic conflict"};return@inTx old};db.execSQL("INSERT INTO project_work_records(campaign_id,work_record_uid,project_uid,work_kind_uid,actor_kind_uid,actor_uid,effective_order,result_kind,progress_delta_units,effort_units,financial_transaction_uid,command_uid,source_event_uid,provenance,metadata_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(w.campaignId,w.workRecordUid,w.projectUid,w.workKindUid,w.actor.ownerKindUid,w.actor.ownerUid,w.effectiveOrder,w.result.name,w.progressDeltaUnits,w.effortUnits,w.financialTransactionUid,w.commandUid,w.sourceEventUid,w.provenance,w.metadataJson));existingWork(w.workRecordUid)!!}
    }

    fun addDependency(d: ProjectDependency): ProjectDependency = stableUidWrite("dependency",d.dependencyUid){
        require(d.campaignId==campaignId);inTx{val old=existingDependency(d.dependencyUid);if(old!=null){require(old==d){"dependency UID semantic conflict"};return@inTx old};db.execSQL("INSERT INTO project_dependencies(campaign_id,dependency_uid,project_uid,depends_on_project_uid,dependency_type_uid,milestone_uid,valid_from_order,provenance) VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(d.campaignId,d.dependencyUid,d.projectUid,d.dependsOnProjectUid,d.dependencyTypeUid,d.milestoneUid,d.validFromOrder,d.provenance));existingDependency(d.dependencyUid)!!}
    }

    fun commitOutcome(o: ProjectOutcome): ProjectOutcome = stableUidWrite("outcome",o.outcomeUid){
        require(o.campaignId==campaignId);inTx{val old=existingOutcome(o.outcomeUid);if(old!=null){require(old==o){"outcome UID semantic conflict"};return@inTx old};db.execSQL("INSERT INTO project_outcomes(campaign_id,outcome_uid,project_uid,output_kind_uid,output_ref_kind_uid,output_uid,committed_order,source_event_uid,command_uid,provenance) VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(o.campaignId,o.outcomeUid,o.projectUid,o.outputKindUid,o.outputRefKindUid,o.outputUid,o.committedOrder,o.sourceEventUid,o.commandUid,o.provenance));existingOutcome(o.outcomeUid)!!}
    }

    fun project(projectUid:String)=existingProject(projectUid)
    fun currentStatus(projectUid:String):ProjectStatus = db.rawQuery("SELECT status FROM project_status_history WHERE campaign_id=? AND project_uid=? ORDER BY effective_order DESC LIMIT 1", arrayOf(campaignId,projectUid)).use{c->require(c.moveToFirst()){ "project status missing"};ProjectStatus.valueOf(c.getString(0))}
    fun progress(projectUid:String):ProjectProgressSnapshot {
        val p=requireNotNull(existingProject(projectUid)){"project not found"}
        val units=scalar("SELECT COUNT(*) FROM project_work_records WHERE campaign_id=? AND project_uid=?",arrayOf(campaignId,projectUid),sumSql="SELECT COALESCE(SUM(progress_delta_units),0) FROM project_work_records WHERE campaign_id=? AND project_uid=?")
        val count=count("SELECT COUNT(*) FROM project_work_records WHERE campaign_id=? AND project_uid=?",arrayOf(campaignId,projectUid))
        val req=count("SELECT COUNT(*) FROM project_milestone_definitions WHERE campaign_id=? AND project_uid=? AND required=1",arrayOf(campaignId,projectUid)).toInt()
        val achieved=count("SELECT COUNT(*) FROM project_milestone_definitions m WHERE m.campaign_id=? AND m.project_uid=? AND m.required=1 AND EXISTS(SELECT 1 FROM project_milestone_achievements a WHERE a.campaign_id=m.campaign_id AND a.project_uid=m.project_uid AND a.milestone_uid=m.milestone_uid)",arrayOf(campaignId,projectUid)).toInt()
        return ProjectProgressSnapshot(projectUid,currentStatus(projectUid),units,p.progressCapUnits,req,achieved,count)
    }
    fun historyCount(projectUid:String)=count("SELECT COUNT(*) FROM project_work_records WHERE campaign_id=? AND project_uid=?",arrayOf(campaignId,projectUid))

    private fun insertStatus(e:ProjectStatusEvent){db.execSQL("INSERT INTO project_status_history(campaign_id,status_event_uid,project_uid,status,effective_order,successor_project_uid,source_event_uid,provenance) VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(e.campaignId,e.statusEventUid,e.projectUid,e.status.name,e.effectiveOrder,e.successorProjectUid,e.sourceEventUid,e.provenance))}

    private fun existingType(uid:String):ProjectTypeDefinition?=db.rawQuery("SELECT generic_category_uid,lifecycle_policy_uid,world_pack_uid,definition_status,definition_version,provenance,metadata_json FROM project_type_definitions WHERE project_type_uid=?",arrayOf(uid)).use{c->if(!c.moveToFirst())null else ProjectTypeDefinition(uid,c.getString(0),c.getString(1),c.nstr(2),c.getString(3),c.getInt(4),c.getString(5),c.nstr(6))}
    private fun existingProject(uid:String):DevelopmentProject?=db.rawQuery("SELECT project_type_uid,initiator_kind_uid,initiator_uid,beneficiary_kind_uid,beneficiary_uid,title,objective_summary,target_domain_uid,target_kind_uid,target_uid,intended_output_kind_uid,progress_cap_units,created_order,started_order,project_version,source_event_uid,provenance,metadata_json FROM development_projects WHERE campaign_id=? AND project_uid=?",arrayOf(campaignId,uid)).use{c->if(!c.moveToFirst())null else DevelopmentProject(campaignId,uid,c.getString(0),OwnershipOwnerRef(c.getString(1),c.getString(2)),if(c.isNull(3))null else OwnershipOwnerRef(c.getString(3),c.getString(4)),c.getString(5),c.getString(6),c.getString(7),c.nstr(8),c.nstr(9),c.nstr(10),c.nlong(11),c.getLong(12),c.nlong(13),c.getInt(14),c.nstr(15),c.getString(16),c.nstr(17))}
    private fun existingStatus(uid:String):ProjectStatusEvent?=db.rawQuery("SELECT project_uid,status,effective_order,successor_project_uid,source_event_uid,provenance FROM project_status_history WHERE campaign_id=? AND status_event_uid=?",arrayOf(campaignId,uid)).use{c->if(!c.moveToFirst())null else ProjectStatusEvent(campaignId,uid,c.getString(0),ProjectStatus.valueOf(c.getString(1)),c.getLong(2),c.nstr(3),c.nstr(4),c.getString(5))}
    private fun statusEventMatches(e:ProjectStatusEvent)=existingStatus(e.statusEventUid)==e
    private fun existingRequirement(uid:String):ProjectRequirement?=db.rawQuery("SELECT project_uid,requirement_type_uid,target_kind_uid,target_uid,comparator_uid,threshold_value,quantity_value,required,required_from_order,requirement_version,provenance,metadata_json FROM project_requirements WHERE campaign_id=? AND requirement_uid=?",arrayOf(campaignId,uid)).use{c->if(!c.moveToFirst())null else ProjectRequirement(campaignId,uid,c.getString(0),c.getString(1),c.nstr(2),c.nstr(3),c.nstr(4),c.nlong(5),c.nlong(6),c.getInt(7)==1,c.getLong(8),c.getInt(9),c.getString(10),c.nstr(11))}
    private fun existingSatisfaction(uid:String):ProjectRequirementSatisfaction?=db.rawQuery("SELECT project_uid,requirement_uid,satisfied_order,evidence_kind_uid,evidence_uid,source_event_uid,provenance FROM project_requirement_satisfactions WHERE campaign_id=? AND satisfaction_uid=?",arrayOf(campaignId,uid)).use{c->if(!c.moveToFirst())null else ProjectRequirementSatisfaction(campaignId,uid,c.getString(0),c.getString(1),c.getLong(2),c.nstr(3),c.nstr(4),c.nstr(5),c.getString(6))}
    private fun existingMilestone(uid:String):ProjectMilestoneDefinition?=db.rawQuery("SELECT project_uid,sequence_order,milestone_type_uid,success_criteria,required,provenance FROM project_milestone_definitions WHERE campaign_id=? AND milestone_uid=?",arrayOf(campaignId,uid)).use{c->if(!c.moveToFirst())null else ProjectMilestoneDefinition(campaignId,uid,c.getString(0),c.getLong(1),c.getString(2),c.getString(3),c.getInt(4)==1,c.getString(5))}
    private fun existingAchievement(uid:String):ProjectMilestoneAchievement?=db.rawQuery("SELECT project_uid,milestone_uid,achieved_order,source_work_record_uid,source_event_uid,provenance FROM project_milestone_achievements WHERE campaign_id=? AND achievement_uid=?",arrayOf(campaignId,uid)).use{c->if(!c.moveToFirst())null else ProjectMilestoneAchievement(campaignId,uid,c.getString(0),c.getString(1),c.getLong(2),c.nstr(3),c.nstr(4),c.getString(5))}
    private fun existingWork(uid:String):ProjectWorkRecord?=db.rawQuery("SELECT project_uid,work_kind_uid,actor_kind_uid,actor_uid,effective_order,result_kind,progress_delta_units,effort_units,financial_transaction_uid,command_uid,source_event_uid,provenance,metadata_json FROM project_work_records WHERE campaign_id=? AND work_record_uid=?",arrayOf(campaignId,uid)).use{c->if(!c.moveToFirst())null else ProjectWorkRecord(campaignId,uid,c.getString(0),c.getString(1),OwnershipOwnerRef(c.getString(2),c.getString(3)),c.getLong(4),ProjectWorkResult.valueOf(c.getString(5)),c.getLong(6),c.nlong(7),c.nstr(8),c.nstr(9),c.nstr(10),c.getString(11),c.nstr(12))}
    private fun existingDependency(uid:String):ProjectDependency?=db.rawQuery("SELECT project_uid,depends_on_project_uid,dependency_type_uid,milestone_uid,valid_from_order,provenance FROM project_dependencies WHERE campaign_id=? AND dependency_uid=?",arrayOf(campaignId,uid)).use{c->if(!c.moveToFirst())null else ProjectDependency(campaignId,uid,c.getString(0),c.getString(1),c.getString(2),c.nstr(3),c.getLong(4),c.getString(5))}
    private fun existingOutcome(uid:String):ProjectOutcome?=db.rawQuery("SELECT project_uid,output_kind_uid,output_ref_kind_uid,output_uid,committed_order,source_event_uid,command_uid,provenance FROM project_outcomes WHERE campaign_id=? AND outcome_uid=?",arrayOf(campaignId,uid)).use{c->if(!c.moveToFirst())null else ProjectOutcome(campaignId,uid,c.getString(0),c.getString(1),c.nstr(2),c.getString(3),c.getLong(4),c.nstr(5),c.nstr(6),c.getString(7))}

    private fun count(sql:String,args:Array<String>)=db.rawQuery(sql,args).use{c->c.moveToFirst();c.getLong(0)}
    private fun scalar(sql:String,args:Array<String>,sumSql:String)=db.rawQuery(sumSql,args).use{c->c.moveToFirst();c.getLong(0)}
    private fun android.database.Cursor.nstr(i:Int)=if(isNull(i))null else getString(i)
    private fun android.database.Cursor.nlong(i:Int)=if(isNull(i))null else getLong(i)
    private fun <T> inTx(block:()->T):T { if(db.inTransaction()) return block(); db.beginTransaction();try{val r=block();db.setTransactionSuccessful();return r}finally{db.endTransaction()} }
    private fun <T> stableUidWrite(domain:String,uid:String,block:()->T):T { val lock=LOCKS[((campaignId+"|"+domain+"|"+uid).hashCode() and Int.MAX_VALUE)%LOCKS.size]; synchronized(lock){return block()} }
    companion object { private val LOCKS=Array(128){Any()} }
}
