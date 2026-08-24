package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

enum class AccessBindingKind { ROLE, ORGANIZATION, CLEARANCE, CONTROL, COGNITION }
enum class AccessGrantKind { EXPLICIT, DELEGATED, TEMPORARY, WORLD_RULE }
enum class AccessOperation { UPSERT_BINDING, REVOKE_BINDING, GRANT, REVOKE_GRANT, SET_CARRIER_ACCESS, BIND_COGNITION }
enum class CarrierAccessStage { REACHABLE, AVAILABLE, OPENED, DECODED, COMPREHENDED }

data class InformationCarrierRef(val campaignUid:String,val carrierKindUid:String,val carrierUid:String){init{require(campaignUid.isNotBlank()&&carrierKindUid.isNotBlank()&&carrierUid.isNotBlank())}}
data class AccessRequirement(
    val policyUid:String,
    val requiredRoleUids:Set<String> = emptySet(),
    val requiredOrganizationUids:Set<String> = emptySet(),
    val requiredClearanceUids:Set<String> = emptySet(),
    val explicitGrantRequired:Boolean=false,
    val carrier:InformationCarrierRef?=null,
    val requiredCarrierStages:Set<CarrierAccessStage> = emptySet()
){init{
    require(policyUid.isNotBlank())
    requiredRoleUids.forEach{require(it.isNotBlank())};requiredOrganizationUids.forEach{require(it.isNotBlank())};requiredClearanceUids.forEach{require(it.isNotBlank())}
    require(carrier != null || requiredCarrierStages.isEmpty()) { "RPGOS-P38-ACCESS:CARRIER_REQUIRED_FOR_STAGED_ACCESS" }
}}

private val ACCESS_PATH_SEAL = Any()
class AccessPath private constructor(
    val campaignUid:String,
    val principal:VisibilityPrincipalRef,
    val carrier:InformationCarrierRef,
    val mechanismUid:String,
    val evidenceUid:String,
    val worldRulePermitsAccess:Boolean,
    val resolvedStages:Set<CarrierAccessStage>,
    seal:Any
){
    init{
        require(seal===ACCESS_PATH_SEAL)
        require(campaignUid.isNotBlank()&&campaignUid==carrier.campaignUid)
        require(mechanismUid.isNotBlank()&&evidenceUid.isNotBlank())
    }
    internal companion object{
        fun issue(campaignUid:String,principal:VisibilityPrincipalRef,carrier:InformationCarrierRef,mechanismUid:String,evidenceUid:String,worldRulePermitsAccess:Boolean,resolvedStages:Set<CarrierAccessStage>)=
            AccessPath(campaignUid,principal,carrier,mechanismUid,evidenceUid,worldRulePermitsAccess,resolvedStages.toSet(),ACCESS_PATH_SEAL)
    }
}

/** Trusted runtime issuer. A caller may request access, but cannot manufacture an effective bypass. */
internal object Phase38AccessRuntimeAuthority{
    fun issuePath(trusted:TrustedPrincipalContext,carrier:InformationCarrierRef,mechanismUid:String,evidenceUid:String,worldRulePermitsAccess:Boolean,resolvedStages:Set<CarrierAccessStage>):AccessPath{
        require(trusted.campaignUid==carrier.campaignUid){"RPGOS-P38-ACCESS:CROSS_CAMPAIGN_CARRIER_PATH"}
        return AccessPath.issue(trusted.campaignUid,trusted.principal,carrier,mechanismUid,evidenceUid,worldRulePermitsAccess,resolvedStages)
    }
}
class AuthorizationDecision private constructor(val authorized:Boolean,val reasonCode:String){
    init{require(reasonCode.isNotBlank())}
    internal companion object{
        fun allow(reasonCode:String="AUTHORIZED")=AuthorizationDecision(true,reasonCode)
        fun deny(reasonCode:String)=AuthorizationDecision(false,reasonCode)
    }
}
class EffectiveAccessDecision private constructor(val accessible:Boolean,val reasonCode:String,val path:AccessPath?,val resolvedStages:Set<CarrierAccessStage>){
    init{require(reasonCode.isNotBlank())}
    internal companion object{
        fun granted(reasonCode:String,path:AccessPath?=null,resolvedStages:Set<CarrierAccessStage> = emptySet())=EffectiveAccessDecision(true,reasonCode,path,resolvedStages.toSet())
        fun denied(reasonCode:String,path:AccessPath?=null,resolvedStages:Set<CarrierAccessStage> = emptySet())=EffectiveAccessDecision(false,reasonCode,path,resolvedStages.toSet())
    }
}

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
    init{ AccessAuthorityChangeValidator.requireValid(this) }
}

/** One canonical semantic validator for every durable Slice-C access mutation. */
object AccessAuthorityChangeValidator {
    const val INVALID = "RPGOS-P38-ACCESS:INVALID_ACCESS_AUTHORITY_CHANGE"
    const val INVALID_DELEGATION = "RPGOS-P38-ACCESS:INVALID_DELEGATION"
    private val ordinaryBindings = setOf(
        AccessBindingKind.ROLE.name, AccessBindingKind.ORGANIZATION.name,
        AccessBindingKind.CLEARANCE.name, AccessBindingKind.CONTROL.name
    )
    private val grantKinds = AccessGrantKind.entries.map { it.name }.toSet()

    fun errors(p: AccessAuthorityChange): List<String> = buildList {
        if (p.recordUid.isBlank() || p.principalKindUid.isBlank() || p.principalUid.isBlank() || p.bindingOrGrantKindUid.isBlank() || p.valueUid.isBlank()) add(INVALID)
        if (p.validFromOrder < 0L || (p.validUntilOrder != null && p.validUntilOrder < p.validFromOrder)) add(INVALID)
        val pairedSubject = (p.subjectKindUid == null) == (p.subjectUid == null)
        if (!pairedSubject || p.subjectKindUid?.isBlank() == true || p.subjectUid?.isBlank() == true) add(INVALID)
        when (p.operation) {
            AccessOperation.UPSERT_BINDING, AccessOperation.REVOKE_BINDING -> {
                if (p.bindingOrGrantKindUid !in ordinaryBindings) add(INVALID)
                if (p.bindingOrGrantKindUid == AccessBindingKind.CONTROL.name && p.subjectUid == null) add(INVALID)
            }
            AccessOperation.GRANT, AccessOperation.REVOKE_GRANT -> if (p.bindingOrGrantKindUid !in grantKinds) add(INVALID)
            AccessOperation.SET_CARRIER_ACCESS -> {
                if (p.bindingOrGrantKindUid !in grantKinds || p.subjectUid == null) add(INVALID)
            }
            AccessOperation.BIND_COGNITION -> {
                if (p.bindingOrGrantKindUid != AccessBindingKind.COGNITION.name || p.subjectUid == null) add(INVALID)
            }
        }
        val delegated = p.bindingOrGrantKindUid == AccessGrantKind.DELEGATED.name &&
            p.operation in setOf(AccessOperation.GRANT, AccessOperation.SET_CARRIER_ACCESS)
        if (delegated) {
            if (p.delegatedByPrincipalUid.isNullOrBlank() || p.delegatedByPrincipalUid == p.principalUid) add(INVALID_DELEGATION)
        } else if (p.delegatedByPrincipalUid != null) add(INVALID_DELEGATION)
    }.distinct()

    fun requireValid(p: AccessAuthorityChange) {
        val first = errors(p).firstOrNull() ?: return
        throw IllegalArgumentException(first)
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
    val subjectKindUid:String?,val subjectUid:String?,val validFromOrder:Long,val validUntilOrder:Long?,val createdOrder:Long,
    val delegatedByPrincipalUid:String?=null
)

class AccessAuthorityStore(private val db:SQLiteDatabase,private val campaignUid:String){
    init{require(campaignUid.isNotBlank())}

    internal fun apply(identity:TurnTransactionIdentity,changeUid:String,p:AccessAuthorityChange,createdOrder:Long){
        require(db.inTransaction());require(identity.campaignUid==campaignUid)
        AccessAuthorityChangeValidator.requireValid(p)
        if (p.bindingOrGrantKindUid == AccessGrantKind.DELEGATED.name && p.operation in setOf(AccessOperation.GRANT, AccessOperation.SET_CARRIER_ACCESS)) {
            requireDelegableAuthority(p, createdOrder)
        }
        db.execSQL("""INSERT INTO ${Phase38AccessAuthoritySchema.RECORDS}(
            campaign_uid,record_uid,operation_uid,principal_kind_uid,principal_uid,binding_or_grant_kind_uid,value_uid,subject_kind_uid,subject_uid,
            valid_from_order,valid_until_order,delegated_by_principal_uid,transaction_uid,turn_uid,change_uid,created_order)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(campaignUid,p.recordUid,p.operation.name,p.principalKindUid,p.principalUid,p.bindingOrGrantKindUid,p.valueUid,p.subjectKindUid,p.subjectUid,p.validFromOrder,p.validUntilOrder,p.delegatedByPrincipalUid,identity.transactionUid,identity.turnUid,changeUid,createdOrder))
    }

    fun effective(principal:VisibilityPrincipalRef,atOrder:Long=Long.MAX_VALUE):List<AccessAuthorityRecord>{
        check(Phase38AccessAuthoritySchema.isReady(db)){"RPGOS-P38-ACCESS:SCHEMA_NOT_READY"}
        val timeline = records(principal, atOrder)
        val active = linkedMapOf<String, AccessAuthorityRecord>()
        timeline.forEach { record ->
            when (record.operation) {
                AccessOperation.UPSERT_BINDING, AccessOperation.BIND_COGNITION,
                AccessOperation.GRANT, AccessOperation.SET_CARRIER_ACCESS -> {
                    if (record.validUntilOrder == null || atOrder <= record.validUntilOrder) active[key(record)] = record
                }
                AccessOperation.REVOKE_BINDING, AccessOperation.REVOKE_GRANT -> active.remove(key(record))
            }
        }
        return active.values.filter { it.validUntilOrder == null || atOrder <= it.validUntilOrder }
    }

    fun cognitionHolders(principal:VisibilityPrincipalRef,atOrder:Long=Long.MAX_VALUE):Set<KnowledgeHolderRef> =
        effective(principal, atOrder)
            .filter { it.operation == AccessOperation.BIND_COGNITION && it.kindUid == AccessBindingKind.COGNITION.name }
            .mapNotNull { record ->
                val kind = record.subjectKindUid ?: return@mapNotNull null
                val uid = record.subjectUid ?: return@mapNotNull null
                KnowledgeHolderRef(kind, uid, campaignUid)
            }.toSet()

    fun currentCanonicalOrder():Long {
        val receiptOrder = TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder
        if (receiptOrder != null) return receiptOrder
        if (!Phase38AccessAuthoritySchema.isReady(db)) return 0L
        return db.rawQuery("SELECT COALESCE(MAX(created_order),0) FROM ${Phase38AccessAuthoritySchema.RECORDS} WHERE campaign_uid=?", arrayOf(campaignUid)).use { c -> c.moveToFirst(); c.getLong(0) }
    }

    private fun records(principal:VisibilityPrincipalRef,atOrder:Long):List<AccessAuthorityRecord> =
        db.rawQuery("""SELECT record_uid,operation_uid,binding_or_grant_kind_uid,value_uid,subject_kind_uid,subject_uid,valid_from_order,valid_until_order,created_order,delegated_by_principal_uid
            FROM ${Phase38AccessAuthoritySchema.RECORDS} WHERE campaign_uid=? AND principal_kind_uid=? AND principal_uid=? AND valid_from_order<=? ORDER BY created_order,record_uid""",
            arrayOf(campaignUid,principal.kindUid,principal.uid,atOrder.toString())).use{c->
            buildList {
                while(c.moveToNext()) add(AccessAuthorityRecord(c.getString(0),AccessOperation.valueOf(c.getString(1)),principal,c.getString(2),c.getString(3),if(c.isNull(4))null else c.getString(4),if(c.isNull(5))null else c.getString(5),c.getLong(6),if(c.isNull(7))null else c.getLong(7),c.getLong(8),if(c.isNull(9))null else c.getString(9)))
            }
        }

    private fun key(record:AccessAuthorityRecord):String {
        val family = when(record.operation){
            AccessOperation.UPSERT_BINDING,AccessOperation.REVOKE_BINDING,AccessOperation.BIND_COGNITION -> "BINDING"
            AccessOperation.GRANT,AccessOperation.REVOKE_GRANT,AccessOperation.SET_CARRIER_ACCESS -> "GRANT"
        }
        return listOf(family,record.kindUid,record.valueUid,record.subjectKindUid.orEmpty(),record.subjectUid.orEmpty()).joinToString("\u001f")
    }

    private fun requireDelegableAuthority(change:AccessAuthorityChange,atOrder:Long){
        val delegator = VisibilityPrincipalRef(change.principalKindUid, requireNotNull(change.delegatedByPrincipalUid))
        val delegableKinds = setOf(AccessGrantKind.EXPLICIT.name,AccessGrantKind.DELEGATED.name,AccessGrantKind.WORLD_RULE.name)
        val allowed = effective(delegator,atOrder).any { record ->
            record.operation in setOf(AccessOperation.GRANT,AccessOperation.SET_CARRIER_ACCESS) &&
                record.kindUid in delegableKinds && record.valueUid == change.valueUid &&
                (record.subjectKindUid == null || (record.subjectKindUid == change.subjectKindUid && record.subjectUid == change.subjectUid))
        }
        require(allowed){"RPGOS-P38-ACCESS:DELEGATOR_AUTHORITY_REQUIRED"}
    }
}

class UniversalAccessAuthority(private val store:AccessAuthorityStore){
    fun trustedContext(audience:AudienceContext,atOrder:Long=store.currentCanonicalOrder(),cognitionResolver:TrustedCognitionResolver?=null):TrustedPrincipalContext?{
        val principal=audience.principal?:return null
        if(audience.audienceKindUid in setOf(AudienceKinds.GM_RUNTIME,AudienceKinds.INTERNAL_SYSTEM,AudienceKinds.DEVELOPER_DIAGNOSTIC))return null
        val records=store.effective(principal,atOrder)
        val roles=records.filter{it.operation==AccessOperation.UPSERT_BINDING&&it.kindUid==AccessBindingKind.ROLE.name}.map{it.valueUid}.toSet()
        val orgs=records.filter{it.operation==AccessOperation.UPSERT_BINDING&&it.kindUid==AccessBindingKind.ORGANIZATION.name}.map{it.valueUid}.toSet()
        val clearances=records.filter{it.operation==AccessOperation.UPSERT_BINDING&&it.kindUid==AccessBindingKind.CLEARANCE.name}.map{it.valueUid}.toSet()
        val controls=records.filter{it.operation==AccessOperation.UPSERT_BINDING&&it.kindUid==AccessBindingKind.CONTROL.name}.mapNotNull{it.subjectUid}.toSet()
        val cognition=(cognitionResolver?.holdersFor(audience.campaignUid,principal)?:store.cognitionHolders(principal,atOrder))
        return TrustedPrincipalContext(audience.campaignUid,principal,audience.audienceKindUid,controls,roles,orgs,clearances,cognition)
    }

    fun authorize(trusted:TrustedPrincipalContext,requirement:AccessRequirement,atOrder:Long=store.currentCanonicalOrder()):AuthorizationDecision{
        if(requirement.carrier?.campaignUid != null && requirement.carrier.campaignUid != trusted.campaignUid)return AuthorizationDecision.deny("CROSS_CAMPAIGN_CARRIER")
        if(requirement.requiredRoleUids.any{it !in trusted.roleUids})return AuthorizationDecision.deny("ROLE_REQUIRED")
        if(requirement.requiredOrganizationUids.any{it !in trusted.organizationUids})return AuthorizationDecision.deny("ORGANIZATION_REQUIRED")
        if(requirement.requiredClearanceUids.any{it !in trusted.clearanceUids})return AuthorizationDecision.deny("CLEARANCE_REQUIRED")
        val grantKinds=AccessGrantKind.entries.map(AccessGrantKind::name).toSet()
        val hasExplicit=store.effective(trusted.principal,atOrder).any{ record ->
            record.operation in setOf(AccessOperation.GRANT,AccessOperation.SET_CARRIER_ACCESS) && record.kindUid in grantKinds && record.valueUid==requirement.policyUid &&
                (requirement.carrier == null || (record.subjectKindUid==requirement.carrier.carrierKindUid&&record.subjectUid==requirement.carrier.carrierUid))
        }
        if(requirement.explicitGrantRequired&&!hasExplicit)return AuthorizationDecision.deny("EXPLICIT_GRANT_REQUIRED")
        return AuthorizationDecision.allow()
    }
    fun effectiveAccess(trusted:TrustedPrincipalContext,requirement:AccessRequirement,authorization:AuthorizationDecision,path:AccessPath?=null):EffectiveAccessDecision{
        val carrier=requirement.carrier
        if(requirement.requiredCarrierStages.isEmpty()){
            if(authorization.authorized)return EffectiveAccessDecision.granted("AUTHORIZED_ACCESS")
            return EffectiveAccessDecision.denied("NO_EFFECTIVE_ACCESS")
        }
        if(carrier==null)return EffectiveAccessDecision.denied("CARRIER_REQUIRED")
        if(path==null)return EffectiveAccessDecision.denied("CARRIER_PATH_REQUIRED")
        if(path.campaignUid!=trusted.campaignUid||path.principal!=trusted.principal||path.carrier!=carrier)
            return EffectiveAccessDecision.denied("CARRIER_PATH_BINDING_MISMATCH",path,path.resolvedStages)
        val missing=requirement.requiredCarrierStages-path.resolvedStages
        if(missing.isNotEmpty())return EffectiveAccessDecision.denied("CARRIER_STAGE_REQUIRED:${missing.map{it.name}.sorted().joinToString(",")}",path,path.resolvedStages)
        if(authorization.authorized)return EffectiveAccessDecision.granted("AUTHORIZED_EFFECTIVE_ACCESS",path,path.resolvedStages)
        if(path.worldRulePermitsAccess)return EffectiveAccessDecision.granted("EFFECTIVE_WORLD_RULE_ACCESS:${path.mechanismUid}",path,path.resolvedStages)
        return EffectiveAccessDecision.denied("NO_EFFECTIVE_ACCESS",path,path.resolvedStages)
    }
}
