package com.rpgos.app

enum class IntentParseState { PARSED, AMBIGUOUS, UNSUPPORTED, EMPTY }
data class IntentRule(val actionUid:String,val verbs:Set<String>,val requiresTarget:Boolean=false){init{require(actionUid.isNotBlank()&&verbs.isNotEmpty());require(verbs.none{it.isBlank()})}}
data class NormalizedIntent(val campaignUid:String,val actor:CommandActorRef,val actionUid:String,val targetRefs:List<DomainRef>,val methodUid:String?,val timeScopeUid:String?,val rawInput:String,val confidenceUid:String){init{require(campaignUid.isNotBlank()&&actionUid.isNotBlank()&&rawInput.isNotBlank());require(targetRefs.distinct()==targetRefs)}}
sealed interface IntentParseResult{val state:IntentParseState;data class Parsed(val intent:NormalizedIntent):IntentParseResult{override val state=IntentParseState.PARSED};data class Ambiguous(val candidateActionUids:List<String>):IntentParseResult{override val state=IntentParseState.AMBIGUOUS};data class Unsupported(val token:String?):IntentParseResult{override val state=IntentParseState.UNSUPPORTED};data object Empty:IntentParseResult{override val state=IntentParseState.EMPTY}}

class IntentParser(rules:List<IntentRule> = defaultRules){
    private val rules=rules.sortedBy{it.actionUid};init{require(this.rules.map{it.actionUid}.distinct().size==this.rules.size)}
    fun parse(campaignUid:String,actor:CommandActorRef,input:String):IntentParseResult{
        val clean=input.trim();if(clean.isEmpty())return IntentParseResult.Empty
        val tokens=WORD.findAll(clean.lowercase()).map{it.value}.toList()
        val matches=rules.filter{rule->rule.verbs.any{it in tokens}}
        if(matches.isEmpty())return IntentParseResult.Unsupported(tokens.firstOrNull())
        if(matches.size>1)return IntentParseResult.Ambiguous(matches.map{it.actionUid})
        val rule=matches.single()
        val explicitTargets=TYPED_REFERENCE.findAll(clean).map{match->
            val kind=match.groups[1]?.value?.uppercase()?:"ENTITY"
            DomainRef(kind,match.groupValues[2].trimEnd('.',',',';',':','!','?'))
        }.filter{it.uid.isNotBlank()}.toList().distinct()
        val targets=if(explicitTargets.isNotEmpty())explicitTargets else naturalTarget(clean,rule)?.let{
            listOf(DomainRef(UNRESOLVED_TEXT_KIND,it))
        }.orEmpty()
        if(rule.requiresTarget&&targets.isEmpty())return IntentParseResult.Ambiguous(listOf(rule.actionUid))
        val method=INLINE_SETTING.findAll(clean).firstOrNull{it.groupValues[1].equals("method",true)}?.groupValues?.get(2)
        val time=INLINE_SETTING.findAll(clean).firstOrNull{it.groupValues[1].equals("time",true)}?.groupValues?.get(2)
        return IntentParseResult.Parsed(NormalizedIntent(campaignUid,actor,rule.actionUid,targets,method,time,clean,
            if(explicitTargets.isNotEmpty())"RULE_EXPLICIT_REFERENCE" else "RULE_NATURAL_LANGUAGE_FALLBACK"))
    }

    private fun naturalTarget(input:String,rule:IntentRule):String?{
        if(!rule.requiresTarget)return null
        val withoutSettings=input.replace(INLINE_SETTING," ").trim()
        if(rule.actionUid=="MOVE"){
            LOCATION_PREPOSITION.findAll(withoutSettings).lastOrNull()?.let{preposition->
                withoutSettings.substring(preposition.range.last+1).cleanTarget()?.let{return it}
            }
        }
        val verb=rule.verbs.sortedByDescending{it.length}.firstOrNull{candidate->
            Regex("(?iu)(?:^|\\s)${Regex.escape(candidate)}(?:\\s|$)").containsMatchIn(withoutSettings)
        }?:return null
        return Regex("(?iu)(?:^|\\s)${Regex.escape(verb)}(?:\\s+)(.+)$").find(withoutSettings)
            ?.groupValues?.get(1)?.removePrefix("do ")?.removePrefix("z ")?.cleanTarget()
    }

    private fun String.cleanTarget()=trim().trim('.',',',';',':','!','?').takeIf{it.isNotBlank()}?.take(160)

    companion object{
        const val UNRESOLVED_TEXT_KIND="UNRESOLVED_TEXT"
        private val WORD=Regex("(?iu)[@\\p{L}\\p{N}_:-]+")
        private val TYPED_REFERENCE=Regex("(?iu)@(?:(\\p{L}[\\p{L}\\p{N}_-]*):)?([\\p{L}\\p{N}_-]+)")
        private val INLINE_SETTING=Regex("(?iu)\\b(method|time):([^\\s,;]+)")
        private val LOCATION_PREPOSITION=Regex("(?iu)\\b(?:do|na|nad|w|we|pod|przed|za)\\s+")
        val defaultRules=listOf(
            IntentRule("LOOK",setOf("look","inspect","sprawdź","sprawdzam","oglądam","ogladam","obejrzyj")),
            IntentRule("MOVE",setOf("move","go","idź","ide","idę","jadę","jade","wyruszam","ruszam","rusz"),true),
            IntentRule("TALK",setOf("talk","ask","mówię","mowie","pytam","rozmawiam","porozmawiaj","zapytaj"),true),
            IntentRule("ATTACK",setOf("attack","strike","atakuję","atakuje","uderzam","atakuj","uderz"),true),
            IntentRule("USE",setOf("use","używam","uzywam","użyj","uzyj"),true),
            IntentRule("WAIT",setOf("wait","czekam","czekaj")),IntentRule("STATUS",setOf("status","stan"))
        )
    }
}

enum class PlanStepPriority { REQUIRED, SAFETY, QUALITY, OPTIONAL }
data class PlannerCapabilityRule(val actionUid:String,val providerUid:String,val operationUid:String,val priority:PlanStepPriority,val requiresTarget:Boolean=false){init{require(actionUid.isNotBlank()&&providerUid.isNotBlank()&&operationUid.isNotBlank())}}
data class TurnPlanStep(val stepUid:String,val request:StructuredRetrievalRequest,val priority:PlanStepPriority)
data class TurnPlan(val planUid:String,val campaignUid:String,val intent:NormalizedIntent,val audience:AudienceContext,val purpose:PurposeContext,val steps:List<TurnPlanStep>){init{require(planUid.isNotBlank()&&intent.campaignUid==campaignUid&&audience.campaignUid==campaignUid&&purpose.campaignUid==campaignUid);require(steps.map{it.stepUid}.distinct().size==steps.size)}}
class TurnPlanner(private val rules:List<PlannerCapabilityRule>){
    fun plan(intent:NormalizedIntent,audience:AudienceContext,purpose:PurposeContext,atOrder:Long?=null):TurnPlan{require(intent.campaignUid==audience.campaignUid&&intent.campaignUid==purpose.campaignUid){"RPGOS-P44:CROSS_CAMPAIGN"};val applicable=rules.filter{it.actionUid==intent.actionUid}.sortedWith(compareBy<PlannerCapabilityRule>{it.priority.ordinal}.thenBy{it.providerUid}.thenBy{it.operationUid});val steps=applicable.mapIndexedNotNull{i,rule->val target=intent.targetRefs.firstOrNull();if(rule.requiresTarget&&target==null)return@mapIndexedNotNull null;val filters=buildMap{target?.let{put("subject_kind_uid",it.kindUid);put("subject_uid",it.uid)}};val request=StructuredRetrievalRequest("REQ:${intent.actionUid}:$i",intent.campaignUid,rule.providerUid,rule.operationUid,filters,50,audience,purpose,atOrder);TurnPlanStep("STEP:${intent.actionUid}:$i",request,rule.priority)};return TurnPlan("PLAN:${intent.campaignUid}:${intent.actor.actorUid}:${intent.actionUid}:${steps.joinToString{it.stepUid}}",intent.campaignUid,intent,audience,purpose,steps)}
}
