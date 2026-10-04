package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Scope-bound owner adapter. It owns no balances, inventories, cognition or clock. A
 * database is opened only for a bounded read and never retained by a temporal worker. */
internal class Phase64ProductionReads(
    private val scope:BackgroundProcessEvaluationScope,
    private val open:()->SQLiteDatabase,
    private val current:()->TemporalScope,
    private val through:WorldTimeTick,
    private val institution:(TemporalOwnerInput,()->Boolean)->Phase64InstitutionDecisionCallbacks? = {_,_->null},
    private val evaluationInput:TemporalOwnerInput? = null,
    private val cancelled:()->Boolean = {false},
    private val principalRouteRead:((DomainRef,DomainRef,DomainRef,WorldTimeTick)->WorldTravelPlan?)? = null,
    private val routeRead:(DomainRef,DomainRef,WorldTimeTick)->WorldTravelPlan?
):BackgroundWorldReadPort {
    constructor(scope:BackgroundProcessEvaluationScope,open:()->SQLiteDatabase,current:()->TemporalScope,
        through:WorldTimeTick,routeRead:(DomainRef,DomainRef,WorldTimeTick)->WorldTravelPlan?):
        this(scope,open,current,through,{_,_->null},null,{false},null,routeRead)
    override fun forEvaluation(input:TemporalOwnerInput):BackgroundWorldReadPort =
        forEvaluation(input,{false})
    override fun forEvaluation(input:TemporalOwnerInput,cancelled:()->Boolean):BackgroundWorldReadPort =
        Phase64ProductionReads(scope,open,current,through,institution,input,cancelled,principalRouteRead,routeRead)
    private fun <T> read(block:(SQLiteDatabase)->T):T {
        require(current()==scope.temporal){"P64:STALE_HISTORY"}
        return open().use { db->
            require(HistoryGenerationStore(db,scope.temporal.campaignUid).current().value==scope.temporal.historyGenerationUid){"P64:STALE_HISTORY"}
            block(db).also { require(current()==scope.temporal){"P64:STALE_HISTORY"} }
        }
    }
    override fun available(resource:DomainRef,staged:List<PlayerDomainChangePayload>):Long?=read { db->
        val campaign=scope.temporal.campaignUid
        val holding=phase64InventoryHolding(resource)
        val mechanical=phase64MechanicalResourceHolding(resource)
        when {
            holding!=null -> inventoryQuantity(db,campaign,holding.holder.uid,holding.itemInstanceUid,staged)
            mechanical!=null -> resourceCapacity(db,mechanical.holder,mechanical.resourceUid,staged)
            resource.kindUid=="ITEM_INSTANCE" -> {
                val initial=db.rawQuery("SELECT COUNT(*) FROM player_inventory_unique WHERE campaign_id=? AND item_instance_uid=?",arrayOf(campaign,resource.uid)).use { it.moveToFirst();it.getLong(0) }
                staged.filterIsInstance<InventoryChange>().filter { it.itemInstanceUid==resource.uid }.fold(initial){n,c->Math.addExact(n,c.quantityDelta.units)}
            }
            resource.kindUid=="FINANCIAL_ACCOUNT" -> {
                if(!existsIn(db,"financial_accounts","campaign_id",campaign,"account_uid",resource.uid))null else
                    staged.filterIsInstance<FinancialChange>().fold(FinancialStore(db,campaign).balance(resource.uid)) { n,c->
                        Math.addExact(n,(if(c.toAccountUid==resource.uid)c.amountMinor else 0L)-(if(c.fromAccountUid==resource.uid)c.amountMinor else 0L))
                    }
            }
            resource.kindUid=="LABOUR_CAPACITY" -> {
                // Labour is a registered existing resource, not an unlimited synthetic pool.
                // A UID encodes its holder using the same scoped resource contract.
                val encoded=phase64MechanicalResourceHolding(DomainRef("MECHANICAL_RESOURCE",resource.uid))
                encoded?.let { h->resourceCapacity(db,h.holder,h.resourceUid,staged) }
            }
            resource.kindUid in setOf("GROUP","UNIT") -> Phase64PopulationProductionReads.captureBody(db,campaign,resource,staged)?.aggregatePopulation?.activeCount
            else -> null
        }?.also { require(it>=0){"P64:NEGATIVE_STAGED_CAPACITY"} }
    }
    override fun exists(ref:DomainRef)=read { db->canonicalReferenceExists(db,ref) }
    override fun exists(ref:DomainRef,staged:List<PlayerDomainChangePayload>):Boolean = read { db->
        ref.kindUid=="KNOWLEDGE_ACQUISITION" && staged.filterIsInstance<KnowledgeAcquisitionChange>().any {
            it.acquisition.acquisitionUid==ref.uid && it.acquisition.holder.campaignUid==scope.temporal.campaignUid
        } || canonicalReferenceExists(db,ref)
    }
    private fun canonicalReferenceExists(db:SQLiteDatabase,ref:DomainRef):Boolean {
        val c=scope.temporal.campaignUid
        return when(ref.kindUid) {
            "CAMPAIGN"->ref.uid==c
            "WORLD_PROCESS"->Phase64BackgroundStore(db,c).process(ref.uid)!=null
            "FINANCIAL_ACCOUNT"->existsIn(db,"financial_accounts","campaign_id",c,"account_uid",ref.uid)
            "CURRENCY"->unscopedExists(db,"currency_definitions","currency_uid",ref.uid)
            "ITEM_INSTANCE"->existsIn(db,"item_instances","campaign_id",c,"item_instance_uid",ref.uid)
            "PROJECT"->DevelopmentProjectStore(db,c).project(ref.uid)!=null
            "PROJECT_RESULT_EVIDENCE"->existsIn(db,"project_work_records","campaign_id",c,"work_record_uid",ref.uid)
            "KNOWLEDGE_ACQUISITION"->existsIn(db,Phase37KnowledgeSchema.ACQUISITIONS,"campaign_uid",c,"acquisition_uid",ref.uid)
            "MECHANICAL_RESOURCE","INVENTORY_HOLDING","LABOUR_CAPACITY"->available(ref,emptyList())!=null
            "RESOURCE"->unscopedExists(db,"resource_definitions","resource_uid",ref.uid)
            else->MechanicalActorStateStore(db,c).actor(ref)!=null ||
                db.rawQuery("SELECT 1 FROM campaign_truth_records WHERE campaign_id=? AND subject_uid=? AND predicate IN (?,?) AND object_value=? AND active=1 AND truth_kind='FACT' LIMIT 1",
                    arrayOf(c,ref.uid,CampaignWorldFacts.KIND,"KIND",ref.kindUid)).use { it.moveToFirst() }
        }
    }
    override fun route(actor:DomainRef,destination:DomainRef,at:WorldTimeTick)=routeRead(actor,destination,at)?.fingerprint
    override fun authorizedRoute(principal:DomainRef,subject:DomainRef,destination:DomainRef,at:WorldTimeTick)=
        (if(principal==subject)routeRead(subject,destination,at) else principalRouteRead?.invoke(principal,subject,destination,at))?.fingerprint
    override fun authorize(actor:DomainRef,purpose:String,refs:List<DomainRef>)=authorize(actor,purpose,refs,emptyList())
    override fun authorize(actor:DomainRef,purpose:String,refs:List<DomainRef>,staged:List<PlayerDomainChangePayload>)=read { db->
        phase64Authorize(db,scope.temporal.campaignUid,actor,purpose,refs,scope.temporal.baseCommitOrder,staged)
    }
    override fun communicationAccess(actor:DomainRef,purpose:String,refs:List<DomainRef>,definition:BackgroundProcessDefinition,
        process:BackgroundProcessInstance,at:WorldTimeTick,staged:List<PlayerDomainChangePayload>):EffectiveAccessDecision? = read { db->
        val campaign=scope.temporal.campaignUid
        val parameters=backgroundParameters(definition,process)
        val recipient=DomainRef(parameters["recipient_kind"]?:return@read null,parameters["recipient_uid"]?:return@read null)
        fun body(ref:DomainRef):MechanicalActorView? {
            val captured=Phase64PopulationProductionReads.captureBody(db,scope.temporal.campaignUid,ref,staged)?:return null
            if(captured.locationRef!=null)return captured
            val anchor=db.rawQuery("SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1",arrayOf(ref.uid)).use { c->
                if(c.moveToFirst() && !c.isNull(0))c.getString(0) else null }?:return null
            val element=CampaignWorldProjectionStore(db,scope.temporal.campaignUid).canonicalElement(anchor)?.element
                ?:listOf("PLACE","LOCATION").map { DomainRef(it,anchor) }.singleOrNull { canonicalReferenceExists(db,it) }
                ?:return null
            return captured.copy(locationRef=element)
        }
        val senderBody=body(process.actor);val recipientBody=body(recipient)
        val destination=recipientBody?.locationRef
        val route=if(destination!=null && senderBody?.locationRef!=destination)routeRead(process.actor,destination,at) else null
        if(Phase64NeutralCommunicationOwner.matchesDefinition(definition))return@read Phase64NeutralCommunicationOwner.authorize(actor,purpose,refs,scope,at,
            Phase64NeutralCommunicationSnapshot(current(),definition,process,senderBody,recipientBody,route))
        if(definition.domain!="INFORMATION" || definition.operation !in setOf("MESSAGE","REPORT","DIPLOMACY","ESPIONAGE"))return@read null
        if(refs.any { !exists(it,staged) })return@read null
        if(!phase64Authorize(db,campaign,actor,purpose,refs,scope.temporal.baseCommitOrder,staged))return@read null
        fun principal(ref:DomainRef,captured:MechanicalActorView?):Phase64CommunicationPrincipalCapture {
            val holderKind=if(ref.kindUid in setOf("NPC","ACTOR","CHARACTER","PLAYER"))KnowledgeHolderKinds.CHARACTER else ref.kindUid
            var anchor=captured?.locationRef
            var evidence:String?=null
            if(anchor==null && captured==null) {
                // Only an explicit canonical mailbox establishes an institution's delivery
                // endpoint. Membership, containment and a member's position are not mailboxes.
                val mailboxes=db.rawQuery("SELECT truth_uid,object_value FROM campaign_truth_records WHERE campaign_id=? AND subject_uid=? AND predicate=? AND truth_kind='FACT' AND active=1 ORDER BY truth_uid LIMIT 2",
                    arrayOf(campaign,ref.uid,"P64:MAILBOX_ANCHOR")).use { c->buildList {
                        while(c.moveToNext())if(!c.isNull(1))add(c.getString(0) to c.getString(1))
                    } }
                if(mailboxes.size==1) {
                    val (truthUid,anchorUid)=mailboxes.single()
                    anchor=listOf("PLACE","LOCATION").map { DomainRef(it,anchorUid) }.singleOrNull { canonicalReferenceExists(db,it) }
                    evidence="P64:MAILBOX:$truthUid:${scope.temporal.authoritativeFingerprint}"
                }
            }
            return Phase64CommunicationPrincipalCapture(ref,KnowledgeHolderRef(holderKind,ref.uid,campaign),captured,anchor,evidence)
        }
        val senderCapture=principal(process.actor,senderBody)
        val recipientCapture=principal(recipient,recipientBody)
        val channel=DomainRef("INFORMATION_CHANNEL",parameters["channel_uid"]?:return@read null)
        val disclosure=DomainRef("DISCLOSURE_POLICY",parameters["disclosure_policy_uid"]?:return@read null)
        val captures=listOfNotNull(process.actor,recipient,channel,disclosure,senderCapture.anchor,recipientCapture.anchor)
            .filter { canonicalReferenceExists(db,it) }.toSet()
        // A source-owner projection for a modified mailbox/channel is not yet available.
        // Do not certify access from its pre-change canonical value in the same transaction.
        if(staged.filterIsInstance<CampaignTruthChange>().any { it.subjectUid in captures.map { ref->ref.uid } })return@read null
        val destinationAnchor=recipientCapture.anchor
        val capturedRoute=if(destinationAnchor!=null && senderCapture.anchor?.let { !WorldTopologyAnchor.same(it,destinationAnchor) }==true)
            routeRead(process.actor,destinationAnchor,at)?.let { plan->Phase64CommunicationRouteCapture(scope.temporal,process.actor,plan,
                "P64:AUTHORIZED_ROUTE:${scope.temporal.authoritativeFingerprint}:${plan.fingerprint}") } else null
        val authority=AccessAuthorityStore(db,campaign)
        val snapshot=Phase64ScopedCommunicationSnapshot(current(),scope.temporal.baseCommitOrder,captures,senderCapture,recipientCapture,
            authority.effective(VisibilityPrincipalRef(process.actor.kindUid,process.actor.uid),scope.temporal.baseCommitOrder),
            authority.effective(VisibilityPrincipalRef(recipient.kindUid,recipient.uid),scope.temporal.baseCommitOrder),capturedRoute)
        when(val prepared=Phase64ScopedCommunicationOwner.prepare(definition,process,scope,at,snapshot,staged)) {
            is Phase64ScopedCommunicationPreparation.Ready->prepared.accessFor(actor,purpose)
            is Phase64ScopedCommunicationPreparation.Unavailable->EffectiveAccessDecision.denied(prepared.reasonUid)
        }
    }
    override fun prepareOwnedEffect(operation:String,actor:DomainRef,parameters:Map<String,String>,
        scope:BackgroundProcessEvaluationScope,staged:List<PlayerDomainChangePayload>):WorldConsequencePlan {
        if(scope!=this.scope)return backgroundBlocked("P64:OWNER_SCOPE_MISMATCH")
        if(cancelled())return backgroundBlocked("P64:CANCELLED")
        if(operation==Phase64OrganizationsInformationAdapter.OWNER_DECISION) {
            // Validate the persisted issuing policy/process first. The potentially remote
            // Phase62 selection and action preparation run with no database held open.
            val preflight=read { db->preparePhase64InstitutionOwnedEffect(db,scope.temporal.campaignUid,
                operation,actor,parameters,scope,staged) }
            if(preflight.reasonUid!="P64:INSTITUTIONAL_DECISION_CONTEXT_UNAVAILABLE")return preflight
            val callbacks=evaluationInput?.let { institution(it.copy(stagedChanges=staged),cancelled) }
                ?:return preflight
            val captured=when(val result=callbacks.capture(actor,parameters,scope,staged)) {
                is Phase64InstitutionDecisionProjection.Unavailable->return backgroundBlocked(result.reasonUid)
                is Phase64InstitutionDecisionProjection.Ready->result.capture
            }
            if(cancelled())return backgroundBlocked("P64:CANCELLED")
            var admittedAction:WorldConsequencePlan?=null
            val prepared=Phase64InstitutionDecisionOwner.prepare(parameters,scope,actor,captured) { context,selected->
                callbacks.prepareSelectedAction(context,selected).also { admittedAction=it }
            }
            if(prepared.status!=BackgroundProcessStatus.COMPLETED)return prepared
            if(cancelled())return backgroundBlocked("P64:CANCELLED")
            // Recheck current canonical ownership after model latency, using only the frozen
            // sealed selection/action. No model or external action runs inside this read.
            return read { db->preparePhase64InstitutionOwnedEffect(db,scope.temporal.campaignUid,
                operation,actor,parameters,scope,staged,captureDecision={_,_,_,_->captured},
                prepareSelectedAction={context,selected->
                    if(context!=captured.context || selected!=captured.selected && captured.selected!=null)
                        backgroundBlocked("P64:INSTITUTIONAL_SELECTED_ACTION_BINDING")
                    else admittedAction?:backgroundBlocked("P64:INSTITUTIONAL_ACTION_NOT_ADMITTED")
                }) }
        }
        return read { db->
            when {
                operation.startsWith("P64:OWNER:")->preparePhase64EconomyOwnedEffect(db,scope.temporal.campaignUid,operation,actor,parameters,scope,staged)
                operation.startsWith("P64:ORG_") || operation==Phase64OrganizationsInformationAdapter.OWNER_ESPIONAGE -> {
                    val carrierCapture=if(operation==Phase64OrganizationsInformationAdapter.OWNER_ESPIONAGE) {
                        val refs=listOf(actor,
                            DomainRef(backgroundRequired(parameters,"recipient_kind"),backgroundRequired(parameters,"recipient_uid")),
                            DomainRef(backgroundRequired(parameters,"carrier_kind"),backgroundRequired(parameters,"carrier_uid")))
                            .filter { canonicalReferenceExists(db,it) }.toSet()
                        when(val projection=Phase64InstitutionProductionReads.captureRegisteredEspionage(db,
                            scope.temporal.campaignUid,actor,parameters,scope,current(),refs,staged)) {
                            is Phase64InstitutionCarrierProjection.Ready->projection.capture
                            is Phase64InstitutionCarrierProjection.Unavailable->return@read backgroundBlocked(projection.reasonUid)
                        }
                    } else null
                    preparePhase64InstitutionOwnedEffect(db,scope.temporal.campaignUid,operation,actor,parameters,scope,staged,
                        deadlineAdmission={ plan->plan.deadlineAdds.all { it.ownerUid==NpcDutyDeadlineProcess.OWNER && it.due>through } &&
                            plan.deadlineRemovals.all { uid->Phase60TemporalStateStore(db,scope.temporal.campaignUid).read().deadlines.any { it.uid==uid && it.ownerUid==NpcDutyDeadlineProcess.OWNER } ||
                                staged.filterIsInstance<BackgroundProcessChange>().any { c->c.deadlineAdds.any { it.uid==uid && it.ownerUid==NpcDutyDeadlineProcess.OWNER } } } },
                        deadlineView=phase64StagedDeadlines(Phase60TemporalStateStore(db,scope.temporal.campaignUid).read().deadlines,staged),
                        captureEspionage=carrierCapture?.let { captured->{_,_,_,_->captured} })
                }
                else-> {
                    val target=parameters["destination_uid"]?.let { DomainRef(parameters["destination_kind_uid"]?:"LOCATION",it) }
                    val rule=parameters["p64_rule_uid"]?.let { uid->parameters["p64_rule_version"]?.toIntOrNull()?.let { version->Phase64BackgroundStore(db,scope.temporal.campaignUid).definition(uid,version) } }
                    val subject=rule?.let { Phase64PopulationRuleCatalog.subject(it,parameters,actor) }?:actor
                    val route=target?.let { destination->
                        val at=WorldTimeTick(backgroundRequired(parameters,"p64_event_at_ms").toLong())
                        if(actor==subject)routeRead(subject,destination,at) else principalRouteRead?.invoke(actor,subject,destination,at)
                    }
                    Phase64PopulationProductionReads.dispatch(db,scope.temporal.campaignUid,operation,actor,parameters,scope,staged,route,route?.fingerprint,input=evaluationInput)
                }
            }
        }
    }
    private fun inventoryQuantity(db:SQLiteDatabase,campaign:String,holder:String,item:String,staged:List<PlayerDomainChangePayload>):Long {
        val base=db.rawQuery("SELECT COUNT(*) FROM player_inventory_unique WHERE campaign_id=? AND character_uid=? AND item_instance_uid=?",arrayOf(campaign,holder,item)).use { it.moveToFirst();it.getLong(0) }
        return staged.filterIsInstance<InventoryChange>().filter { it.subject.uid==holder && it.itemInstanceUid==item }.fold(base){n,c->Math.addExact(n,c.quantityDelta.units)}
    }
    private fun resourceCapacity(db:SQLiteDatabase,holder:DomainRef,pool:String,staged:List<PlayerDomainChangePayload>):Long? {
        val campaign=scope.temporal.campaignUid
        val base=if(holder.kindUid=="PLAYER")StatResourceStore(db,campaign).playerResources(holder.uid)
            .singleOrNull { it.resourceUid==pool }?.currentValue?.let(::phase64ExactResourceUnits)
        else MechanicalActorStateStore(db,campaign).actor(holder)?.resources?.singleOrNull { it.resourceUid==pool }?.current
        return base?.let { value->staged.filterIsInstance<ResourceChange>().filter { it.subject==holder && it.resourceUid==pool }
            .fold(value) { n,p->Math.addExact(n,p.delta.units) } }
    }
    private fun existsIn(db:SQLiteDatabase,table:String,campaignColumn:String,campaign:String,column:String,uid:String)=
        tableExists(db,table) && db.rawQuery("SELECT 1 FROM $table WHERE $campaignColumn=? AND $column=? LIMIT 1",arrayOf(campaign,uid)).use { it.moveToFirst() }
    private fun unscopedExists(db:SQLiteDatabase,table:String,column:String,uid:String)=
        tableExists(db,table) && db.rawQuery("SELECT 1 FROM $table WHERE $column=? LIMIT 1",arrayOf(uid)).use { it.moveToFirst() }
    private fun tableExists(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use { it.moveToFirst() }
}

/** Integral owner pools only. Preserve range checking on API28 without truncating fractions. */
internal fun phase64ExactResourceUnits(value:Double):Long? = try {
    java.math.BigDecimal.valueOf(value).toBigIntegerExact().toExactLongCompat()
} catch (_:ArithmeticException) {
    null
} catch (_:NumberFormatException) {
    null
}

internal fun phase64StagedDeadlines(base:List<WorldProcessDeadline>,staged:List<PlayerDomainChangePayload>):List<WorldProcessDeadline> {
    val map=base.associateBy { it.uid }.toMutableMap()
    staged.filterIsInstance<BackgroundProcessChange>().forEach { p->p.deadlineRemovals.forEach(map::remove);p.deadlineAdds.forEach { map[it.uid]=it } }
    return map.values.sortedWith(compareBy<WorldProcessDeadline>{it.due}.thenBy { it.uid })
}

/** Explicit, audience-scoped grants. Being in the same village or organization alone never
 * authorizes access. A staged revocation is effective for later work in this transaction. */
internal fun phase64Authorize(db:SQLiteDatabase,campaign:String,actor:DomainRef,purpose:String,refs:List<DomainRef>,order:Long,
    staged:List<PlayerDomainChangePayload>):Boolean {
    if(refs.size>128 || !Phase38AccessAuthoritySchema.isReady(db))return false
    val principal=VisibilityPrincipalRef(actor.kindUid,actor.uid)
    val proposedOrder=Math.addExact(order,1)
    val records=Phase64OrganizationsInformationOwners.effectiveOverlay(
        AccessAuthorityStore(db,campaign).effective(principal,order),actor,order,staged)
        .filter { it.validUntilOrder==null || it.validUntilOrder>=proposedOrder }
    return refs.all { ref->
        ref==actor || records.any { grant->grant.operation in setOf(AccessOperation.GRANT,AccessOperation.SET_CARRIER_ACCESS) && grant.valueUid==purpose &&
            (grant.subjectKindUid==null || grant.subjectKindUid==ref.kindUid && grant.subjectUid==ref.uid) }
    } && (refs.isNotEmpty() || records.any { it.valueUid==purpose && it.operation==AccessOperation.GRANT })
}
