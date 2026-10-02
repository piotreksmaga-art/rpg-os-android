package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** A duty is an existing organization/role assignment plus an existing Phase60 deadline.
 * Neither an AI suggestion nor Brain.roleUids can create or authorize it. */
data class NpcDutyRule(val dutyUid:String,val version:Int,val organizationUid:String,val roleUid:String,
    val deadlineUid:String,val due:WorldTimeTick,val assignmentPolicyUid:String) {
    init { listOf(dutyUid,organizationUid,roleUid,deadlineUid,assignmentPolicyUid).forEach(::npcUid);require(version>0) }
    internal val fingerprint get()=phase60Hash("P62:DUTY:1|$dutyUid|$version|$organizationUid|$roleUid|$deadlineUid|${due.milliseconds}|$assignmentPolicyUid")
    internal val completionTrack get()="P62:DUTY:$dutyUid:$version"
}
fun interface NpcDutyAssignmentPort {
    fun admitted(campaignUid:String,actor:DomainRef,rule:NpcDutyRule,at:WorldTimeTick):Boolean
    companion object { val NONE=NpcDutyAssignmentPort{_,_,_,_->false} }
}
internal class SqliteNpcDutyAssignmentPort(private val db:SQLiteDatabase,private val campaign:String):NpcDutyAssignmentPort {
    override fun admitted(campaignUid:String,actor:DomainRef,rule:NpcDutyRule,at:WorldTimeTick):Boolean {
        if(campaignUid!=campaign || at>rule.due)return false
        val order=TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder?:AccessAuthorityStore(db,campaign).currentCanonicalOrder()
        val authority=UniversalAccessAuthority(AccessAuthorityStore(db,campaign))
        val trusted=authority.trustedContext(AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(actor.kindUid,actor.uid)),order)?:return false
        val requirement=AccessRequirement(rule.assignmentPolicyUid,requiredRoleUids=setOf(rule.roleUid),requiredOrganizationUids=setOf(rule.organizationUid),
            explicitGrantRequired=true,carrier=InformationCarrierRef(campaign,"NPC_DUTY",rule.dutyUid))
        if(!authority.authorize(trusted,requirement,order).authorized)return false
        val deadline=Phase60TemporalStateStore(db,campaign).read().deadlines.singleOrNull{it.uid==rule.deadlineUid}
        if(deadline?.ownerUid!=NpcDutyDeadlineProcess.OWNER || deadline.due!=rule.due)return false
        val count=db.rawQuery("SELECT current_value FROM mechanical_actor_tracks WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND track_uid=?",
            arrayOf(campaign,actor.kindUid,actor.uid,rule.completionTrack)).use{if(it.moveToFirst())it.getLong(0) else 0L}
        return count==0L
    }
}

/** Expiry acknowledges time only. It never invents punishments, new orders or organization state. */
internal object NpcDutyDeadlineProcess {
    const val OWNER="P62:NPC_DUTIES"
    fun extension()=TemporalProcessExtension(listOf(RegisteredTemporalOwner("1",object:WorldProcessOwnerPort {
        override val ownerUid=OWNER
        override fun evaluate(input:TemporalOwnerInput):TemporalOwnerResult {
            if(input.actions.isNotEmpty() || input.deadlines.any{it.ownerUid!=OWNER || it.due!=input.through})
                return TemporalOwnerResult.Unsupported("P62:DUTY_DEADLINE_SCOPE")
            // Phase60 consumes evaluated deadlines in its ordinary temporal transaction.
            return TemporalOwnerResult.Evaluated(input.previous?:TemporalOwnerState(OWNER,1,"{}"))
        }
    })),TemporalExternalEvaluationPort{_,_->TemporalEvaluationResponse.Unavailable("P62:DUTIES_HAVE_NO_AI_EVALUATION")})
}

/** Optional, explicit World Pack assignments enter at the same administrative bootstrap as
 * initial roles. No read creates a duty, no model can call this importer, and reopening never
 * restores a revoked assignment. Later role/grant mutations still belong to Phase38. */
internal object NpcDutyAssignmentImport {
    fun importPack(save:SQLiteDatabase,world:SQLiteDatabase,campaign:String,binding:WorldPackRuleBinding) {
        val exists=world.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='npc_duty_assignments'",null).use{it.moveToFirst()}
        if(!exists)return
        require(save.inTransaction()){"P62:DUTY_IMPORT_REQUIRES_ADMIN_TRANSACTION"}
        Phase38AccessAuthoritySchema.ensureReady(save);Phase60TemporalSchema.ensureReady(save)
        val access=AccessAuthorityStore(save,campaign)
        val temporal=Phase60TemporalStateStore(save,campaign)
        world.rawQuery("SELECT actor_kind_uid,actor_uid,rule_uid,rule_version,assignment_uid FROM npc_duty_assignments ORDER BY assignment_uid LIMIT 1025",null).use { c->
            var count=0
            while(c.moveToNext()) {
                require(++count<=1024){"P62:DUTY_IMPORT_BUDGET"}
                val actor=DomainRef(c.getString(0),c.getString(1));val assignment=c.getString(4);npcUid(assignment)
                require(MechanicalActorStateStore(save,campaign).actor(actor)?.kind in setOf(MechanicalActorKind.NPC,MechanicalActorKind.FORMER_PLAYER)){
                    "P62:DUTY_ASSIGNEE_NOT_MATERIALIZED"}
                val ruleUid=c.getString(2);val version=c.getInt(3)
                val contract=save.rawQuery("SELECT contract_json FROM ${Phase62ActivitySchema.TABLE} WHERE campaign_uid=? AND rule_uid=? AND rule_version=? AND active=1",
                    arrayOf(campaign,ruleUid,version.toString())).use{if(it.moveToFirst())NpcActivityContractCodec.decode(it.getString(0)) else null}
                if(contract==null) {
                    val superseded=save.rawQuery("SELECT 1 FROM ${Phase62ActivitySchema.TABLE} WHERE campaign_uid=? AND rule_uid=? AND rule_version>? LIMIT 1",
                        arrayOf(campaign,ruleUid,version.toString())).use{it.moveToFirst()}
                    if(superseded)continue // Older pack assignments never revive a changed duty.
                    error("P62:DUTY_DEFINITION_NOT_REGISTERED")
                }
                val rule=contract.duty?:error("P62:DUTY_DEFINITION_REQUIRED")
                // A version-qualified record survives expiry/revocation. Do not infer an order
                // merely because a matching live grant or deadline is currently absent.
                val record="P62:DUTY:${phase60Hash("${binding.worldPackUid}|$assignment|${rule.fingerprint}")}"
                val already=save.rawQuery("SELECT 1 FROM ${Phase38AccessAuthoritySchema.RECORDS} WHERE campaign_uid=? AND record_uid=? LIMIT 1",arrayOf(campaign,record)).use{it.moveToFirst()}
                if(already)continue
                val principal=VisibilityPrincipalRef(actor.kindUid,actor.uid)
                val order=access.currentCanonicalOrder()
                val authority=UniversalAccessAuthority(access)
                val trusted=authority.trustedContext(AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,principal),order)?:error("P62:DUTY_PRINCIPAL_REQUIRED")
                require(authority.authorize(trusted,AccessRequirement("P62:DUTY_ROLE",requiredRoleUids=setOf(rule.roleUid),
                    requiredOrganizationUids=setOf(rule.organizationUid)),order).authorized){"P62:DUTY_ROLE_OR_ORGANIZATION_MISSING"}
                val state=temporal.read()
                require(rule.due>state.time){"P62:DUTY_DEADLINE_ALREADY_EXPIRED"}
                val existing=state.deadlines.singleOrNull{it.uid==rule.deadlineUid}
                require(existing==null || existing==WorldProcessDeadline(rule.deadlineUid,NpcDutyDeadlineProcess.OWNER,rule.due)){
                    "P62:DUTY_DEADLINE_IDENTITY_REUSED"}
                val identity=TurnTransactionIdentity(campaign,"WORLD-PACK:$assignment","WORLD-PACK:$assignment","WORLD-PACK:$record")
                access.apply(identity,record,AccessAuthorityChange(AccessOperation.GRANT,record,actor.kindUid,actor.uid,
                    AccessGrantKind.WORLD_RULE.name,rule.assignmentPolicyUid,"NPC_DUTY",rule.dutyUid,order),order)
                temporal.apply(identity,TemporalStateChange(campaign,state.version,state.time,state.time,
                    Phase60ProcessStateCodec.encode(state.processStates),Phase60DeadlineCodec.encode(
                        state.deadlines+if(existing==null)listOf(WorldProcessDeadline(rule.deadlineUid,NpcDutyDeadlineProcess.OWNER,rule.due)) else emptyList())))
            }
        }
    }
}
