package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

enum class AccessBindingKind { ROLE, ORGANIZATION, CLEARANCE, CONTROL }
enum class AccessGrantKind { EXPLICIT, DELEGATED, TEMPORARY, WORLD_RULE }
enum class AccessOperation { UPSERT_BINDING, REVOKE_BINDING, GRANT, REVOKE_GRANT, SET_CARRIER_ACCESS, BIND_COGNITION }

data class InformationCarrierRef(val campaignUid:String,val carrierKindUid:String,val carrierUid:String){init{require(campaignUid.isNotBlank()&&carrierKindUid.isNotBlank()&&carrierUid.isNotBlank())}}
data class AccessRequirement(
    val policyUid:String,
    val requiredRoleUids:Set<String> = emptySet(),
    val requiredOrganizationUids:Set<String> = emptySet(),
    val requiredClearanceUids:Set<String> = emptySet(),
    val explicitGrantRequired:Boolean=false,
    val carrier:InformationCarrierRef?=null
){init{require(policyUid.isNotBlank())}}

data class AccessPath(val mechanismUid:String,val evidenceUid:String,val authorized:Boolean,val effectiveNow:Boolean){init{require(mechanismUid.isNotBlank()&&evidenceUid.isNotBlank())}}
data class AuthorizationDecision(val authorized:Boolean,val reasonCode:String)
data class EffectiveAccessDecision(val accessible:Boolean,val reasonCode:String,val path:AccessPath?=null)

data class AccessAuthorityChange(
    val operation:AccessOperation,
    val recordUid:String,
    val principalKindUid:String,
    val principalUid:String,
    val bindingOrGrantKindUid:String,
    val valueUid:String,
    val subjectKindUid:String?=null,
    val subjectUid:String?=null,
    val validFromOrder:Long,
    val validUntilOrder:Long?=null,
    val delegatedByPrincipalUid:String?=null
):PlayerDomainChangePayload{
    init{
        require(recordUid.isNotBlank()&&principalKindUid.isNotBlank()&&principalUid.isNotBlank()&&bindingOrGrantKindUid.isNotBlank()&&valueUid.isNotBlank())
        require(validFromOrder>=0L);require(validUntilOrder==null||validUntilOrder>=validFromOrder)
        require(subjectKindUid?.isBlank()!=true&&subjectUid?.isBlank()!=true)
    }
}

object Phase38AccessAuthoritySchema{
    const val VERSION=1
    const val RECORDS="phase38_access_authority_records"
    fun ensureReady(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE IF NOT EXISTS $RECORDS(
            campaign_uid TEXT NOT NULL,record_uid TEXT NOT NULL,operation_uid TEXT NOT NULL,principal_kind_uid TEXT NOT NULL,principal_uid TEXT NOT NULL,
            binding_or_grant_kind_uid TEXT NOT NULL,value_uid TEXT NOT NULL,subject_kind_uid TEXT,subject_uid TEXT,
            valid_from_order INTEGER NOT NULL,valid_until_order INTEGER,delegated_by_principal_uid TEXT,transaction_uid TEXT NOT NULL,turn_uid TEXT NOT NULL,change_uid TEXT NOT NULL,
            created_order INTEGER NOT NULL,PRIMARY KEY(campaign_uid,record_uid,created_order))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p38_access_principal ON $RECORDS(campaign_uid,principal_kind_uid,principal_uid,created_order)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p38_access_subject ON $RECORDS(campaign_uid,subject_kind_uid,subject_uid,created_order)")
    }
    fun isReady(db:SQLiteDatabase)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(RECORDS)).use{it.moveToFirst()}
}

data class AccessAuthorityRecord(
    val recordUid:String,val operation:AccessOperation,val principal:VisibilityPrincipalRef,val kindUid:String,val valueUid:String,
    val subjectKindUid:String?,val subjectUid:String?,val validFromOrder:Long,val validUntilOrder:Long?,val createdOrder:Long
)

class AccessAuthorityStore(private val db:SQLiteDatabase,private val campaignUid:String){
    init{require(campaignUid.isNotBlank())}
    internal fun apply(identity:TurnTransactionIdentity,changeUid:String,p:AccessAuthorityChange,createdOrder:Long){
        require(db.inTransaction());require(identity.campaignUid==campaignUid)
        db.execSQL("""INSERT INTO ${Phase38AccessAuthoritySchema.RECORDS}(
            campaign_uid,record_uid,operation_uid,principal_kind_uid,principal_uid,binding_or_grant_kind_uid,value_uid,subject_kind_uid,subject_uid,
            valid_from_order,valid_until_order,delegated_by_principal_uid,transaction_uid,turn_uid,change_uid,created_order)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(campaignUid,p.recordUid,p.operation.name,p.principalKindUid,p.principalUid,p.bindingOrGrantKindUid,p.valueUid,p.subjectKindUid,p.subjectUid,p.validFromOrder,p.validUntilOrder,p.delegatedByPrincipalUid,identity.transactionUid,identity.turnUid,changeUid,createdOrder))
    }
    fun effective(principal:VisibilityPrincipalRef,atOrder:Long=Long.MAX_VALUE):List<AccessAuthorityRecord>{
        check(Phase38AccessAuthoritySchema.isReady(db)){"RPGOS-P38-ACCESS:SCHEMA_NOT_READY"}
        return db.rawQuery("""SELECT record_uid,operation_uid,binding_or_grant_kind_uid,value_uid,subject_kind_uid,subject_uid,valid_from_order,valid_until_order,created_order
            FROM ${Phase38AccessAuthoritySchema.RECORDS} WHERE campaign_uid=? AND principal_kind_uid=? AND principal_uid=? AND valid_from_order<=? ORDER BY created_order""",
            arrayOf(campaignUid,principal.kindUid,principal.uid,atOrder.toString())).use{c->
            val timeline=mutableListOf<AccessAuthorityRecord>();while(c.moveToNext()) timeline+=AccessAuthorityRecord(c.getString(0),AccessOperation.valueOf(c.getString(1)),principal,c.getString(2),c.getString(3),if(c.isNull(4))null else c.getString(4),if(c.isNull(5))null else c.getString(5),c.getLong(6),if(c.isNull(7))null else c.getLong(7),c.getLong(8))
            val revoked=timeline.filter{it.operation in setOf(AccessOperation.REVOKE_BINDING,AccessOperation.REVOKE_GRANT)}.map{it.valueUid}.toSet()
            timeline.filter{it.operation in setOf(AccessOperation.UPSERT_BINDING,AccessOperation.GRANT,AccessOperation.SET_CARRIER_ACCESS,AccessOperation.BIND_COGNITION)&&it.valueUid !in revoked&&(it.validUntilOrder==null||atOrder<=it.validUntilOrder)}
        }
    }
}

class UniversalAccessAuthority(private val store:AccessAuthorityStore){
    fun trustedContext(audience:AudienceContext,atOrder:Long=Long.MAX_VALUE,cognitionResolver:TrustedCognitionResolver=TrustedCognitionResolver{_,_->emptySet()}):TrustedPrincipalContext?{
        val principal=audience.principal?:return null
        val records=store.effective(principal,atOrder)
        val roles=records.filter{it.kindUid==AccessBindingKind.ROLE.name}.map{it.valueUid}.toSet()
        val orgs=records.filter{it.kindUid==AccessBindingKind.ORGANIZATION.name}.map{it.valueUid}.toSet()
        val clearances=records.filter{it.kindUid==AccessBindingKind.CLEARANCE.name}.map{it.valueUid}.toSet()
        val controls=records.filter{it.kindUid==AccessBindingKind.CONTROL.name}.mapNotNull{it.subjectUid}.toSet()
        val principal=audience.principal?:return null
        if(audience.audienceKindUid in setOf(AudienceKinds.GM_RUNTIME,AudienceKinds.INTERNAL_SYSTEM,AudienceKinds.DEVELOPER_DIAGNOSTIC))return null
        return TrustedPrincipalContext(audience.campaignUid,principal,audience.audienceKindUid,controls,roles,orgs,clearances,cognitionResolver.holdersFor(audience.campaignUid,principal))
    }
    fun authorize(trusted:TrustedPrincipalContext,requirement:AccessRequirement):AuthorizationDecision{
        if(requirement.requiredRoleUids.any{it !in trusted.roleUids})return AuthorizationDecision(false,"ROLE_REQUIRED")
        if(requirement.requiredOrganizationUids.any{it !in trusted.organizationUids})return AuthorizationDecision(false,"ORGANIZATION_REQUIRED")
        if(requirement.requiredClearanceUids.any{it !in trusted.clearanceUids})return AuthorizationDecision(false,"CLEARANCE_REQUIRED")
        val hasExplicit=store.effective(trusted.principal).any{it.kindUid in AccessGrantKind.entries.map(AccessGrantKind::name)&&it.valueUid==requirement.policyUid}
        if(requirement.explicitGrantRequired&&!hasExplicit)return AuthorizationDecision(false,"EXPLICIT_GRANT_REQUIRED")
        return AuthorizationDecision(true,"AUTHORIZED")
    }
    fun effectiveAccess(authorization:AuthorizationDecision,path:AccessPath?=null):EffectiveAccessDecision{
        if(authorization.authorized)return EffectiveAccessDecision(true,"AUTHORIZED_ACCESS",path)
        if(path?.effectiveNow==true)return EffectiveAccessDecision(true,"EFFECTIVE_BYPASS:${path.mechanismUid}",path)
        return EffectiveAccessDecision(false,"NO_EFFECTIVE_ACCESS",path)
    }
}
