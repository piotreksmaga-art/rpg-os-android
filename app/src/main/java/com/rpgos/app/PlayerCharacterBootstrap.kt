package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.util.UUID

enum class CharacterCreationDefinitionKind { STAT, RESOURCE, TALENT, POTENTIAL, SKILL, TECHNIQUE, ORIGIN, INNATE_FEATURE, STARTING_LOCATION }

data class CharacterCreationDefinitionOption(
    val kind:CharacterCreationDefinitionKind,
    val definitionUid:String,
    val displayName:String,
    val minimumValue:Double?=null,
    val maximumValue:Double?=null,
    val dimensionUid:String?=null
){init{
    require(definitionUid.isNotBlank()&&displayName.isNotBlank())
    require(minimumValue?.isFinite()!=false&&maximumValue?.isFinite()!=false)
    require(minimumValue==null||maximumValue==null||minimumValue<=maximumValue)
    require((kind==CharacterCreationDefinitionKind.POTENTIAL)==(dimensionUid!=null))
}}

data class CharacterCreationCatalog(val campaignUid:String,val options:List<CharacterCreationDefinitionOption>){
    init{require(campaignUid.isNotBlank());require(options.map{it.kind to (it.definitionUid to it.dimensionUid)}.distinct().size==options.size)}
}

data class CharacterCreationValueChoice(val definitionUid:String,val value:Double,val dimensionUid:String?=null){
    init{require(definitionUid.isNotBlank()&&value.isFinite()&&value>=0&&dimensionUid?.isBlank()!=true)}
}

data class PlayerCharacterCreationDraft(
    val creationUid:String,
    val campaignUid:String,
    val playerUid:String,
    val displayName:String,
    val genderUid:String,
    val identityChoices:Map<String,String> = emptyMap(),
    val stats:List<CharacterCreationValueChoice>,
    val resources:List<CharacterCreationValueChoice>,
    val talents:List<CharacterCreationValueChoice>,
    val potentials:List<CharacterCreationValueChoice>,
    val skills:List<CharacterCreationValueChoice>,
    val techniques:List<CharacterCreationValueChoice>,
    val originUids:List<String> = emptyList(),
    val innateFeatureUids:List<String> = emptyList(),
    val startingLocationUid:String,
    val startingXMillimetres:Long=0,
    val startingYMillimetres:Long=0
){init{
    require(listOf(creationUid,campaignUid,playerUid,displayName,genderUid,startingLocationUid).none{it.isBlank()})
    require(displayName.length<=200&&genderUid.length<=120&&identityChoices.keys.none{it.isBlank()}&&identityChoices.values.none{it.isBlank()})
    require(stats.isNotEmpty()&&resources.isNotEmpty()&&talents.isNotEmpty()&&potentials.isNotEmpty()&&skills.isNotEmpty()&&techniques.isNotEmpty())
    listOf(stats,resources,talents,potentials,skills,techniques).forEach{choices->
        require(choices.map{it.definitionUid to it.dimensionUid}.distinct().size==choices.size){"RPGOS-CHARACTER-CREATION:DUPLICATE_CHOICE"}
    }
    require(originUids.none{it.isBlank()}&&originUids.distinct().size==originUids.size)
    require(innateFeatureUids.none{it.isBlank()}&&innateFeatureUids.distinct().size==innateFeatureUids.size)
}}

data class PlayerCharacterCreationConfirmation(val draftFingerprint:String,val explicitUserActionUid:String){
    init{require(draftFingerprint.isNotBlank()&&explicitUserActionUid.isNotBlank())}
}

data class PlayerCharacterBootstrapReceipt(
    val creationUid:String,val campaignUid:String,val playerUid:String,val draftFingerprint:String,val idempotentReplay:Boolean
)

enum class CharacterCreationConversationRole { PLAYER, GAME_MASTER }
data class CharacterCreationConversationEntry(val role:CharacterCreationConversationRole,val text:String){init{require(text.isNotBlank()&&text.length<=8_000)}}
data class AiCharacterCreationRequest(
    val requestUid:String,val campaignUid:String,val catalog:CharacterCreationCatalog,
    val conversation:List<CharacterCreationConversationEntry>,val localeUid:String="pl-PL"
){init{
    require(requestUid.isNotBlank()&&campaignUid==catalog.campaignUid&&conversation.isNotEmpty()&&localeUid.isNotBlank())
    require(conversation.size<=128)
}}

sealed interface CharacterCreationGmCandidate{
    data class NeedsPlayerChoice(val question:String,val missingCategoryUids:List<String> = emptyList()):CharacterCreationGmCandidate{
        init{require(question.isNotBlank()&&missingCategoryUids.none{it.isBlank()})}
    }
    data class ReadyForConfirmation(val draft:PlayerCharacterCreationDraft,val playerFacingSummary:String):CharacterCreationGmCandidate{
        init{require(playerFacingSummary.isNotBlank())}
    }
}

sealed interface CharacterCreationApplicationOutcome{
    data class Question(val text:String):CharacterCreationApplicationOutcome
    data class AwaitingExplicitConfirmation(val creationUid:String,val summary:String,val draftFingerprint:String):CharacterCreationApplicationOutcome
    data class Created(val receipt:PlayerCharacterBootstrapReceipt):CharacterCreationApplicationOutcome
    data class Failed(val reasonUid:String):CharacterCreationApplicationOutcome
    data class Cancelled(val reasonUid:String="CHARACTER_CREATION_CANCELLED"):CharacterCreationApplicationOutcome
}

/** Provider-independent pre-game GM loop. AI drafts; a separate explicit UI action commits. */
class AiCharacterCreationApplication(
    private val route:AiModelRoutePort,
    private val repository:UnifiedGameRepository
){
    private val conversation=mutableListOf<CharacterCreationConversationEntry>()
    private var pending:CharacterCreationGmCandidate.ReadyForConfirmation?=null

    fun play(input:String,cancellation:AiCancellationSignal=AiCancellationSignal.NONE):CharacterCreationApplicationOutcome{
        if(repository.activePlayerRef()!=null)return CharacterCreationApplicationOutcome.Failed("CHARACTER_ALREADY_ACTIVE")
        if(cancellation.isCancelled())return CharacterCreationApplicationOutcome.Cancelled()
        conversation+=CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,input)
        val catalog=repository.characterCreationCatalog()
        // A World Pack can expose hundreds of skills and techniques. Sending the whole catalog to
        // a 2k mobile model made an otherwise ready Bielik fail routing with NO_ELIGIBLE_MODEL.
        // Complete character-profile families stay present; choice families are deterministically
        // projected toward the player's words with a bounded representative fallback.
        val projectedConversation=conversation.projectForAi()
        val projectedCatalog=catalog.projectForAi(projectedConversation)
        val requiredUnits=projectedCatalog.estimatedInputUnits(projectedConversation)
        val selected=when(val result=route.route(AiRole.GAME_MASTER,AiWorkload.CHARACTER_CREATION,requiredUnits)){
            is AiRouteResult.Unavailable->return CharacterCreationApplicationOutcome.Failed(result.reasonUids.joinToString("|"))
            is AiRouteResult.Selected->result.provider
        }
        val request=AiCharacterCreationRequest("CHARACTER-CREATION:${UUID.randomUUID()}",catalog.campaignUid,projectedCatalog,projectedConversation)
        val candidate=when(val result=selected.guideCharacterCreation(request,cancellation)){
            is AiProviderResult.Failure->return if(result.kind==AiProviderFailureKind.CANCELLED)CharacterCreationApplicationOutcome.Cancelled(result.reasonUid) else CharacterCreationApplicationOutcome.Failed(result.reasonUid)
            is AiProviderResult.Success->result.value
        }
        if(cancellation.isCancelled())return CharacterCreationApplicationOutcome.Cancelled()
        return when(candidate){
            is CharacterCreationGmCandidate.NeedsPlayerChoice->{
                conversation+=CharacterCreationConversationEntry(CharacterCreationConversationRole.GAME_MASTER,candidate.question)
                CharacterCreationApplicationOutcome.Question(candidate.question)
            }
            is CharacterCreationGmCandidate.ReadyForConfirmation->{
                if(candidate.draft.campaignUid!=catalog.campaignUid)return CharacterCreationApplicationOutcome.Failed("CHARACTER_CREATION_CROSS_CAMPAIGN_DRAFT")
                val completed=candidate.copy(draft=candidate.draft.completeMandatoryChoices(catalog))
                pending=completed
                conversation+=CharacterCreationConversationEntry(CharacterCreationConversationRole.GAME_MASTER,completed.playerFacingSummary)
                CharacterCreationApplicationOutcome.AwaitingExplicitConfirmation(
                    completed.draft.creationUid,completed.playerFacingSummary,PlayerCharacterBootstrapService.fingerprint(completed.draft)
                )
            }
        }
    }

    fun confirm(creationUid:String,explicitUserActionUid:String):CharacterCreationApplicationOutcome{
        val ready=pending?:return CharacterCreationApplicationOutcome.Failed("NO_CHARACTER_DRAFT_AWAITING_CONFIRMATION")
        if(ready.draft.creationUid!=creationUid)return CharacterCreationApplicationOutcome.Failed("CHARACTER_CREATION_PENDING_UID_MISMATCH")
        val fingerprint=PlayerCharacterBootstrapService.fingerprint(ready.draft)
        val receipt=try{repository.createPlayerCharacter(ready.draft,PlayerCharacterCreationConfirmation(fingerprint,explicitUserActionUid))}
        catch(failure:IllegalArgumentException){return CharacterCreationApplicationOutcome.Failed(failure.message?:"CHARACTER_CREATION_REJECTED")}
        pending=null
        return CharacterCreationApplicationOutcome.Created(receipt)
    }
}

private fun PlayerCharacterCreationDraft.completeMandatoryChoices(catalog:CharacterCreationCatalog):PlayerCharacterCreationDraft{
    fun defaultValue(option:CharacterCreationDefinitionOption)=when(option.kind){
        CharacterCreationDefinitionKind.RESOURCE->option.maximumValue?:option.minimumValue?:1.0
        CharacterCreationDefinitionKind.POTENTIAL->option.maximumValue?:50.0
        else->option.minimumValue?:0.0
    }
    fun complete(kind:CharacterCreationDefinitionKind,current:List<CharacterCreationValueChoice>):List<CharacterCreationValueChoice>{
        val present=current.map{it.definitionUid to it.dimensionUid}.toSet()
        return current+catalog.options.filter{it.kind==kind&&(it.definitionUid to it.dimensionUid) !in present}.map{option->
            CharacterCreationValueChoice(option.definitionUid,defaultValue(option),option.dimensionUid)
        }
    }
    val potentialDomains=potentials.map{it.definitionUid}.toSet()
    val missingPotentials=catalog.options.filter{it.kind==CharacterCreationDefinitionKind.POTENTIAL&&it.definitionUid !in potentialDomains}
        .groupBy{it.definitionUid}.toSortedMap().values.map{options->
            val option=options.firstOrNull{it.dimensionUid=="MAXIMUM"}?:options.sortedBy{it.dimensionUid}.first()
            CharacterCreationValueChoice(option.definitionUid,defaultValue(option),option.dimensionUid)
        }
    return copy(
        stats=complete(CharacterCreationDefinitionKind.STAT,stats),
        resources=complete(CharacterCreationDefinitionKind.RESOURCE,resources),
        talents=complete(CharacterCreationDefinitionKind.TALENT,talents),
        potentials=potentials+missingPotentials
    )
}

internal fun CharacterCreationCatalog.projectForAi(
    conversation:List<CharacterCreationConversationEntry>,
    maximumEstimatedInputUnits:Int=1_250
):CharacterCreationCatalog{
    require(maximumEstimatedInputUnits>0)
    val completeKinds=setOf(CharacterCreationDefinitionKind.STAT,CharacterCreationDefinitionKind.RESOURCE,CharacterCreationDefinitionKind.TALENT)
    val words=conversation.asSequence().flatMap{it.text.lowercase().split(Regex("[^\\p{L}\\p{N}_-]+")).asSequence()}
        .filter{it.length>=3}.toSet()
    fun relevance(option:CharacterCreationDefinitionOption):Int{
        val searchable="${option.definitionUid} ${option.displayName}".lowercase()
        return words.sumOf{word->when{searchable==word->8;searchable.contains(word)->3;else->0}}
    }
    val selected=options.filter{it.kind in completeKinds}.sortedWith(compareBy({it.kind.name},{it.definitionUid})).toMutableList()
    // Potential is represented once per progression domain. The canonical validator requires
    // domain coverage, not all five dimension variants, so repeating every dimension consumed
    // scarce mobile context without adding a legal choice the draft had to contain.
    options.filter{it.kind==CharacterCreationDefinitionKind.POTENTIAL}.groupBy{it.definitionUid}.toSortedMap().values.forEach{variants->
        selected+=variants.firstOrNull{it.dimensionUid=="MAXIMUM"}?:variants.sortedBy{it.dimensionUid}.first()
    }
    val optionalKinds=CharacterCreationDefinitionKind.entries.filterNot{it in completeKinds||it==CharacterCreationDefinitionKind.POTENTIAL}
    val queues=optionalKinds.associateWith{kind->options.filter{it.kind==kind}
        .sortedWith(compareByDescending<CharacterCreationDefinitionOption>(::relevance).thenBy{it.definitionUid}).toMutableList()}
    // Every available choice family gets one representative before additional relevant choices.
    optionalKinds.forEach{kind->queues.getValue(kind).removeFirstOrNull()?.let(selected::add)}
    val remaining=queues.values.flatten().sortedWith(compareByDescending<CharacterCreationDefinitionOption>(::relevance).thenBy{it.kind.name}.thenBy{it.definitionUid})
    for(option in remaining){
        val candidate=CharacterCreationCatalog(campaignUid,selected+option)
        if(candidate.estimatedInputUnits(conversation)>maximumEstimatedInputUnits)break
        selected+=option
    }
    return CharacterCreationCatalog(campaignUid,selected.distinctBy{it.kind to (it.definitionUid to it.dimensionUid)})
}

internal fun List<CharacterCreationConversationEntry>.projectForAi(maximumUnits:Int=300):List<CharacterCreationConversationEntry>{
    require(maximumUnits>0)
    if(isEmpty())return emptyList()
    fun compact(entry:CharacterCreationConversationEntry)=entry.copy(text=entry.text.take(480))
    val first=compact(first())
    val selectedIndices=linkedSetOf(0)
    var used=(first.text.length+3)/4+8
    indices.reversed().filter{it!=0}.forEach{index->
        val entry=compact(this[index])
        val units=(entry.text.length+3)/4+8
        if(used+units<=maximumUnits){selectedIndices+=index;used+=units}
    }
    return selectedIndices.sorted().map{compact(this[it])}
}

internal fun CharacterCreationCatalog.estimatedInputUnits(conversation:List<CharacterCreationConversationEntry>):Int{
    // Bielik's tokenizer splits JSON punctuation and canonical UIDs more aggressively than the
    // usual prose-only chars/4 heuristic. This deliberately conservative estimate mirrors the
    // compact array wire format used by CanonicalAiJsonCodec and keeps room for the draft output.
    val conversationUnits=conversation.sumOf{(it.text.length+1)/2+6}
    val definitionUnits=options.sumOf{option->
        (option.definitionUid.length+option.displayName.length+(option.dimensionUid?.length?:0)+28+1)/2
    }
    return (260+conversationUnits+definitionUnits).coerceAtLeast(1)
}

class CharacterCreationCatalogReader(
    private val db:SQLiteDatabase,
    private val campaignUid:String,
    private val worldPackUid:String?=null,
    private val additionalOptions:List<CharacterCreationDefinitionOption> = emptyList()
){
    private val packWhere=if(worldPackUid==null)"" else " WHERE world_pack_uid=?"
    private val packArgs=worldPackUid?.let{arrayOf(it)}
    fun read():CharacterCreationCatalog=CharacterCreationCatalog(campaignUid,buildList{
        addDefinitions("SELECT stat_uid,stat_key,min_value,max_value FROM stat_definitions$packWhere ORDER BY stat_uid",CharacterCreationDefinitionKind.STAT,packArgs)
        addDefinitions("SELECT resource_uid,resource_key,min_value,max_value FROM resource_definitions$packWhere ORDER BY resource_uid",CharacterCreationDefinitionKind.RESOURCE,packArgs)
        db.rawQuery("SELECT domain_uid,display_name,applies_to_talent,applies_to_potential FROM progression_domain_definitions$packWhere ORDER BY domain_uid",packArgs).use{c->while(c.moveToNext()){
            if(c.getInt(2)!=0)add(CharacterCreationDefinitionOption(CharacterCreationDefinitionKind.TALENT,c.getString(0),c.getString(1),0.0,null))
            if(c.getInt(3)!=0)listOf("GROWTH","MAXIMUM","ADAPTATION","INNOVATION","EVOLUTION").forEach{dimension->
                add(CharacterCreationDefinitionOption(CharacterCreationDefinitionKind.POTENTIAL,c.getString(0),"${c.getString(1)} / $dimension",0.0,null,dimension))
            }
        }}
        val activeWhere=if(worldPackUid==null)" WHERE definition_status='ACTIVE'" else " WHERE world_pack_uid=? AND definition_status='ACTIVE'"
        addDefinitions("SELECT skill_uid,display_name,min_mastery,max_mastery FROM skill_definitions_v2$activeWhere ORDER BY skill_uid",CharacterCreationDefinitionKind.SKILL,packArgs)
        addDefinitions("SELECT technique_uid,display_name,min_mastery,max_mastery FROM technique_definitions_v2$activeWhere ORDER BY technique_uid",CharacterCreationDefinitionKind.TECHNIQUE,packArgs)
        addDefinitions("SELECT origin_uid,display_name,NULL,NULL FROM origin_definitions_v2$activeWhere ORDER BY origin_uid",CharacterCreationDefinitionKind.ORIGIN,packArgs)
        addDefinitions("SELECT feature_uid,display_name,NULL,NULL FROM innate_feature_definitions$activeWhere ORDER BY feature_uid",CharacterCreationDefinitionKind.INNATE_FEATURE,packArgs)
        addAll(additionalOptions)
    })

    private fun MutableList<CharacterCreationDefinitionOption>.addDefinitions(sql:String,kind:CharacterCreationDefinitionKind,args:Array<String>?){
        db.rawQuery(sql,args).use{c->while(c.moveToNext())add(CharacterCreationDefinitionOption(
            kind,c.getString(0),c.getString(1),if(c.isNull(2))null else c.getDouble(2),if(c.isNull(3))null else c.getDouble(3)
        ))}
    }
}

/**
 * One-time new-campaign authority. An AI/GM may prepare a draft, but only a matching explicit user
 * confirmation enters this atomic transaction. Character succession remains owned by Phase71.
 */
class PlayerCharacterBootstrapService(
    private val db:SQLiteDatabase,
    private val campaignUid:String,
    private val worldPackUid:String?=null,
    private val catalogOverride:CharacterCreationCatalog?=null
){
    fun commit(draft:PlayerCharacterCreationDraft,confirmation:PlayerCharacterCreationConfirmation):PlayerCharacterBootstrapReceipt{
        require(draft.campaignUid==campaignUid){"RPGOS-CHARACTER-CREATION:CROSS_CAMPAIGN"}
        val fingerprint=fingerprint(draft)
        require(confirmation.draftFingerprint==fingerprint){"RPGOS-CHARACTER-CREATION:CONFIRMATION_MISMATCH"}
        existingReceipt(draft.creationUid)?.let{existing->
            require(existing.draftFingerprint==fingerprint){"RPGOS-CHARACTER-CREATION:IDEMPOTENCY_COLLISION"}
            return existing.copy(idempotentReplay=true)
        }
        require(ActivePlayerStore(db,campaignUid).active()==null){"RPGOS-CHARACTER-CREATION:ACTIVE_PLAYER_ALREADY_EXISTS"}
        validateAgainstCatalog(draft,catalogOverride?:CharacterCreationCatalogReader(db,campaignUid,worldPackUid).read())
        val provenance="RPGOS-CHARACTER-CREATION:${draft.creationUid}"
        return withAdministrativeMutationAuthority(db,campaignUid){
            require(!identityExists(draft.playerUid)){"RPGOS-CHARACTER-CREATION:PLAYER_UID_EXISTS"}
            persistIdentityFacts(draft,fingerprint,confirmation.explicitUserActionUid,provenance)
            val statStore=StatResourceStore(db,campaignUid)
            draft.stats.forEach{statStore.savePlayerStat(PlayerStat(campaignUid,draft.playerUid,it.definitionUid,it.value))}
            draft.resources.forEach{statStore.savePlayerResource(PlayerResource(campaignUid,draft.playerUid,it.definitionUid,it.value))}
            val profiles=ProgressionProfileStore(db,campaignUid)
            draft.talents.forEach{profiles.saveTalent(TalentEntry(campaignUid,draft.playerUid,it.definitionUid,it.value,provenance=provenance))}
            draft.potentials.forEach{profiles.savePotential(PotentialEntry(campaignUid,draft.playerUid,it.definitionUid,requireNotNull(it.dimensionUid),it.value,provenance=provenance))}
            val skills=SkillStore(db,campaignUid)
            draft.skills.forEach{skills.savePlayerSkill(PlayerSkill(campaignUid,draft.playerUid,it.definitionUid,it.value,provenance=provenance,learnedChapter=0))}
            val techniques=TechniqueStore(db,campaignUid)
            draft.techniques.forEach{techniques.savePlayerTechnique(PlayerTechnique(campaignUid,draft.playerUid,it.definitionUid,it.value,learnedChapter=0,provenance=provenance))}
            val phase9=Phase9Store(db,campaignUid)
            draft.originUids.forEach{phase9.saveOrigin(PlayerOrigin(campaignUid,draft.playerUid,it,"CHARACTER_CREATION",provenance=provenance))}
            draft.innateFeatureUids.forEach{phase9.grantInnateFeature(PlayerInnateFeature(campaignUid,draft.playerUid,it,0,provenance=provenance))}
            db.execSQL("INSERT INTO entity_positions(entity_uid,location_uid,x_coord,y_coord,last_updated_day,updated_chapter) VALUES(?,?,?,?,0,0)",arrayOf<Any?>(draft.playerUid,draft.startingLocationUid,draft.startingXMillimetres/1000.0,draft.startingYMillimetres/1000.0))
            val ownership=OwnershipReferenceRegistry(db,campaignUid)
            ownership.registerOwnerKind("CHARACTER",provenance)
            ownership.registerOwner(OwnershipOwnerRef("CHARACTER",draft.playerUid),provenance)
            ActivePlayerStore(db,campaignUid).set(draft.playerUid)
            PlayerCharacterBootstrapReceipt(draft.creationUid,campaignUid,draft.playerUid,fingerprint,false)
        }
    }

    private fun validateAgainstCatalog(draft:PlayerCharacterCreationDraft,catalog:CharacterCreationCatalog){
        val byKey=catalog.options.associateBy{it.kind to (it.definitionUid to it.dimensionUid)}
        fun validate(kind:CharacterCreationDefinitionKind,choices:List<CharacterCreationValueChoice>){choices.forEach{choice->
            val option=byKey[kind to (choice.definitionUid to choice.dimensionUid)]
                ?:throw IllegalArgumentException("RPGOS-CHARACTER-CREATION:UNKNOWN_${kind.name}_DEFINITION")
            option.minimumValue?.let{require(choice.value>=it){"RPGOS-CHARACTER-CREATION:VALUE_BELOW_MINIMUM"}}
            option.maximumValue?.let{require(choice.value<=it){"RPGOS-CHARACTER-CREATION:VALUE_ABOVE_MAXIMUM"}}
        }}
        validate(CharacterCreationDefinitionKind.STAT,draft.stats);validate(CharacterCreationDefinitionKind.RESOURCE,draft.resources)
        validate(CharacterCreationDefinitionKind.TALENT,draft.talents);validate(CharacterCreationDefinitionKind.POTENTIAL,draft.potentials)
        validate(CharacterCreationDefinitionKind.SKILL,draft.skills);validate(CharacterCreationDefinitionKind.TECHNIQUE,draft.techniques)
        fun requireComplete(kind:CharacterCreationDefinitionKind,choices:List<CharacterCreationValueChoice>){
            val required=catalog.options.filter{it.kind==kind}.map{it.definitionUid}.toSet()
            require(choices.map{it.definitionUid}.toSet()==required){"RPGOS-CHARACTER-CREATION:INCOMPLETE_${kind.name}_PROFILE"}
        }
        requireComplete(CharacterCreationDefinitionKind.STAT,draft.stats)
        requireComplete(CharacterCreationDefinitionKind.RESOURCE,draft.resources)
        requireComplete(CharacterCreationDefinitionKind.TALENT,draft.talents)
        val potentialDomains=catalog.options.filter{it.kind==CharacterCreationDefinitionKind.POTENTIAL}.map{it.definitionUid}.toSet()
        require(draft.potentials.map{it.definitionUid}.toSet().containsAll(potentialDomains)){"RPGOS-CHARACTER-CREATION:INCOMPLETE_POTENTIAL_PROFILE"}
        draft.originUids.forEach{require((CharacterCreationDefinitionKind.ORIGIN to (it to null)) in byKey){"RPGOS-CHARACTER-CREATION:UNKNOWN_ORIGIN"}}
        draft.innateFeatureUids.forEach{require((CharacterCreationDefinitionKind.INNATE_FEATURE to (it to null)) in byKey){"RPGOS-CHARACTER-CREATION:UNKNOWN_INNATE_FEATURE"}}
        catalog.options.filter{it.kind==CharacterCreationDefinitionKind.STARTING_LOCATION}.takeIf{it.isNotEmpty()}?.let{locations->
            require(locations.any{it.definitionUid==draft.startingLocationUid}){"RPGOS-CHARACTER-CREATION:UNKNOWN_STARTING_LOCATION"}
        }
        validateTechniqueRequirements(draft)
    }

    private fun validateTechniqueRequirements(draft:PlayerCharacterCreationDraft){
        val mastery=draft.skills.associate{it.definitionUid to it.value}
        val definitions=TechniqueStore(db,campaignUid).definitions().associateBy{it.techniqueUid}
        draft.techniques.forEach{choice->definitions[choice.definitionUid]?.skillRequirements.orEmpty()
            .filter{it.requirementPhase!=TechniqueRequirementPhase.EXECUTION}.forEach{require((mastery[it.skillUid]?:-1.0)>=it.minimumMastery){
                "RPGOS-CHARACTER-CREATION:TECHNIQUE_SKILL_REQUIREMENT_UNMET"
            }}}
    }

    private fun persistIdentityFacts(draft:PlayerCharacterCreationDraft,fingerprint:String,userActionUid:String,provenance:String){
        val values=linkedMapOf("NAME" to draft.displayName,"GENDER" to draft.genderUid).apply{putAll(draft.identityChoices.toSortedMap())}
        values.forEach{(key,value)->insertFact("$provenance:IDENTITY:$key",draft.playerUid,"RPGOS:PLAYER_IDENTITY:${key.uppercase()}",value,draft.creationUid,userActionUid)}
        insertFact("$provenance:RECEIPT",draft.playerUid,"RPGOS:CHARACTER_CREATION:FINGERPRINT",fingerprint,draft.creationUid,userActionUid)
    }

    private fun insertFact(uid:String,subjectUid:String,predicate:String,value:String,sourceId:String,userActionUid:String){
        db.execSQL("""INSERT INTO campaign_truth_records(truth_uid,campaign_id,truth_kind,subject_uid,predicate,object_value,source_type,source_id,created_turn,confidence,verified,method,engine_version,created_at,active)
            VALUES(?,?,'FACT',?,?,?,'CHARACTER_CREATION',?,0,1.0,1,?,'RPGOS-CHARACTER-CREATION-V1',strftime('%s','now'),1)""".trimIndent(),
            arrayOf<Any?>(stableUid(uid),campaignUid,subjectUid,predicate,value,sourceId,"EXPLICIT_USER_CONFIRMATION:$userActionUid"))
    }

    private fun existingReceipt(creationUid:String):PlayerCharacterBootstrapReceipt?=db.rawQuery(
        "SELECT subject_uid,object_value FROM campaign_truth_records WHERE campaign_id=? AND source_type='CHARACTER_CREATION' AND source_id=? AND predicate='RPGOS:CHARACTER_CREATION:FINGERPRINT' AND active=1 LIMIT 1",
        arrayOf(campaignUid,creationUid)
    ).use{c->if(!c.moveToFirst())null else PlayerCharacterBootstrapReceipt(creationUid,campaignUid,c.getString(0),c.getString(1),true)}

    private fun identityExists(playerUid:String)=listOf(
        "player_stats" to "character_uid","player_resources" to "character_uid","entity_positions" to "entity_uid",
        "active_player_ref" to "player_uid"
    ).any{(table,column)->db.rawQuery("SELECT 1 FROM $table WHERE $column=? LIMIT 1",arrayOf(playerUid)).use{it.moveToFirst()}}

    companion object{
        fun fingerprint(draft:PlayerCharacterCreationDraft)=stableUid(draft.toString())
        private fun stableUid(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    }
}
