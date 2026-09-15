package com.rpgos.app

import kotlinx.serialization.json.*

/** Closed, bounded codec shared by canonical changes, replay and diagnostic readback. */
internal object NpcBrainCodec {
    const val VERSION=1
    fun encode(state:NpcBrainState):String=buildJsonObject {
        put("schema",VERSION);put("campaign",state.campaignUid);put("actor",ref(state.actor));put("revision",state.revision);put("seed",state.seedFingerprint)
        put("holder",ref(DomainRef(state.knowledgeHolder.holderKindUid,state.knowledgeHolder.holderUid)))
        put("appraisal_order",state.lastAppraisedAcquisitionOrder)
        put("personality",weights(state.personality));put("values",weights(state.values))
        put("roles",JsonArray(state.roleUids.sorted().map(::JsonPrimitive)))
        put("motivations",JsonArray(state.motivations.sortedBy{it.uid}.map { m -> buildJsonObject {
            put("uid",m.uid);put("kind",m.kind.name);put("domain",m.domainUid);put("strength",m.strength.basisPoints);put("subject",m.subject?.let(::ref)?:JsonNull)
        } }))
        put("goals",JsonArray(state.goals.sortedBy{it.uid}.map { g -> buildJsonObject {
            put("uid",g.uid);put("motivation",g.motivationUid);put("objective",g.objective);put("priority",g.priority.basisPoints)
            put("lifecycle",g.lifecycle.name);put("cause",cause(g.cause));put("deadline",g.deadline?.let{JsonPrimitive(it.milliseconds)}?:JsonNull)
            g.executionObjective?.let{put("execution_objective",buildJsonObject {
                put("capability",it.capabilityUid);put("owner",it.mechanicsOwnerUid);put("effect",it.effectKindUid)
                put("target",ref(it.target));put("binding",it.actionBinding);put("version",it.version)
            })}
        } }))
        put("emotions",JsonArray(state.emotions.sortedBy{it.uid}.map { e -> buildJsonObject {
            put("uid",e.uid);put("intensity",e.intensity.basisPoints);put("at",e.updatedAt.milliseconds);put("cause",cause(e.cause))
        } }))
        put("dispositions",JsonArray(state.dispositions.sortedWith(compareBy<NpcDisposition>{it.subject.kindUid}.thenBy{it.subject.uid}).map { d -> buildJsonObject {
            put("subject",ref(d.subject));put("trust",d.trust.basisPoints);put("attachment",d.attachment.basisPoints);put("grievance",d.grievance.basisPoints);put("cause",cause(d.cause))
        } }))
        put("plans",JsonArray(state.plans.sortedBy{it.uid}.map { p -> buildJsonObject {
            put("uid",p.uid);put("goal",p.goalUid);put("action",p.actionUid);put("lifecycle",p.lifecycle.name)
            put("started",p.startedAt?.let{JsonPrimitive(it.milliseconds)}?:JsonNull)
            put("next",p.nextEvaluationAt?.let{JsonPrimitive(it.milliseconds)}?:JsonNull);put("cause",cause(p.cause))
            // Optional additive fields keep already committed v1 single-action payloads exact.
            if(p.nextActionUids.isNotEmpty())put("next_actions",JsonArray(p.nextActionUids.map(::JsonPrimitive)))
            p.previousPlanUid?.let{put("previous_plan",it)}
            p.onUnavailableOptionUid?.let{put("on_unavailable",it)}
        } }))
    }.toString().also{require(it.length<=131_072){"P61:PAYLOAD_BUDGET"}}

    fun decode(value:String):NpcBrainState {
        require(value.length<=131_072){"P61:PAYLOAD_BUDGET"}
        val o=Json.parseToJsonElement(value).jsonObject
        keys(o,"schema","campaign","actor","revision","seed","holder","appraisal_order","personality","values","roles","motivations","goals","emotions","dispositions","plans")
        require(number(o,"schema")==VERSION.toLong()) { "P61:SCHEMA_UNSUPPORTED" }
        return NpcBrainState(text(o,"campaign"),readRef(o.getValue("actor")),number(o,"revision"),text(o,"seed"),
            readWeights(o.getValue("personality")),readWeights(o.getValue("values")),
            rows(o,"motivations",64).map{ m -> keys(m,"uid","kind","domain","strength","subject")
                NpcMotivation(text(m,"uid"),NpcMotivationKind.valueOf(text(m,"kind")),text(m,"domain"),NpcWeight(integer(m,"strength")),m["subject"]?.takeUnless{it==JsonNull}?.let(::readRef)) },
            rows(o,"goals",64).map{ g -> keys(JsonObject(g.filterKeys{it!="execution_objective"}),"uid","motivation","objective","priority","lifecycle","cause","deadline")
                val objective=g["execution_objective"]?.jsonObject?.let{e->keys(e,"capability","owner","effect","target","binding","version")
                    NpcExecutionObjective(text(e,"capability"),text(e,"owner"),text(e,"effect"),readRef(e.getValue("target")),text(e,"binding"),integer(e,"version"))}
                NpcGoal(text(g,"uid"),text(g,"motivation"),text(g,"objective"),NpcWeight(integer(g,"priority")),NpcGoalLifecycle.valueOf(text(g,"lifecycle")),readCause(g.getValue("cause")),tick(g,"deadline"),objective) },
            rows(o,"emotions",32).map{ e -> keys(e,"uid","intensity","at","cause")
                NpcEmotion(text(e,"uid"),NpcAffect(integer(e,"intensity")),WorldTimeTick(number(e,"at")),readCause(e.getValue("cause"))) },
            rows(o,"dispositions",128).map{ d -> keys(d,"subject","trust","attachment","grievance","cause")
                NpcDisposition(readRef(d.getValue("subject")),NpcAffect(integer(d,"trust")),NpcAffect(integer(d,"attachment")),NpcWeight(integer(d,"grievance")),readCause(d.getValue("cause"))) },
            o.getValue("roles").jsonArray.let{roles->require(roles.size<=32);roles.map{it.jsonPrimitive.let{v->require(v.isString);v.content}}.also{require(it.distinct().size==it.size)}.toSet()},
            rows(o,"plans",32).map{ p -> keys(JsonObject(p.filterKeys{it !in setOf("next_actions","previous_plan","on_unavailable")}),"uid","goal","action","lifecycle","started","next","cause")
                val next=p["next_actions"]?.jsonArray?.also{require(it.size in 1..3)}?.map{it.jsonPrimitive.also{v->require(v.isString)}.content}.orEmpty()
                NpcPlan(text(p,"uid"),text(p,"goal"),text(p,"action"),NpcPlanLifecycle.valueOf(text(p,"lifecycle")),tick(p,"started"),tick(p,"next"),readCause(p.getValue("cause")),
                    next,if("previous_plan" in p)text(p,"previous_plan") else null,if("on_unavailable" in p)text(p,"on_unavailable") else null) },
            readRef(o.getValue("holder")).let{KnowledgeHolderRef(it.kindUid,it.uid,text(o,"campaign"))},number(o,"appraisal_order")
        )
    }
    fun fingerprint(state:NpcBrainState)=phase60Hash(encode(state))
    fun ref(ref:DomainRef)=buildJsonObject{put("kind",ref.kindUid);put("uid",ref.uid)}
    fun readRef(value:JsonElement):DomainRef=value.jsonObject.let{keys(it,"kind","uid");DomainRef(text(it,"kind"),text(it,"uid"))}
    fun cause(value:NpcCauseRef)=buildJsonObject{put("kind",value.kind.name);put("uid",value.uid)}
    fun readCause(value:JsonElement):NpcCauseRef=value.jsonObject.let{keys(it,"kind","uid");NpcCauseRef(NpcCauseKind.valueOf(text(it,"kind")),text(it,"uid"))}
    fun keys(obj:JsonObject,vararg names:String){require(obj.keys==names.toSet()){ "P61:UNKNOWN_OR_MISSING_FIELD" }}
    fun text(obj:JsonObject,key:String)=obj.getValue(key).jsonPrimitive.let{require(it.isString);it.content}
    fun number(obj:JsonObject,key:String)=obj.getValue(key).jsonPrimitive.let{require(!it.isString);it.long}
    fun integer(obj:JsonObject,key:String)=number(obj,key).let{require(it in Int.MIN_VALUE..Int.MAX_VALUE);it.toInt()}
    private fun tick(obj:JsonObject,key:String)=obj.getValue(key).takeUnless{it==JsonNull}?.let{WorldTimeTick(number(obj,key))}
    private fun weights(values:Map<String,NpcWeight>)=JsonObject(values.toSortedMap().mapValues{JsonPrimitive(it.value.basisPoints)})
    private fun readWeights(value:JsonElement)=value.jsonObject.also{require(it.size<=64)}.mapValues{(_,v)->v.jsonPrimitive.let{require(!it.isString);NpcWeight(it.int)}}
    private fun rows(obj:JsonObject,key:String,limit:Int)=obj.getValue(key).jsonArray.also{require(it.size<=limit)}.map{it.jsonObject}
}
