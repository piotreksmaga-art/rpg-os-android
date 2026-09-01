package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.util.UUID

enum class CharacterCreationDefinitionKind { STAT, RESOURCE, TALENT, POTENTIAL, SKILL, TECHNIQUE, ORIGIN, INNATE_FEATURE, STARTING_LOCATION }
enum class CharacterCreationDraftSection { IDENTITY, ORIGIN, INNATE_FEATURES, PROGRESSION, SKILLS, TECHNIQUES, STARTING_LOCATION }

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
    val conversation:List<CharacterCreationConversationEntry>,
    /** Full Core-owned catalog for post-decode validation/materialization; codecs must encode only [catalog]. */
    val authorityCatalog:CharacterCreationCatalog=catalog,
    val localeUid:String="pl-PL"
){init{
    require(requestUid.isNotBlank()&&campaignUid==catalog.campaignUid&&campaignUid==authorityCatalog.campaignUid&&conversation.isNotEmpty()&&localeUid.isNotBlank())
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

/**
 * Candidate-only projection seam. Implementations may rank the authoritative World Pack catalog,
 * but cannot create definitions or write character state. A failed semantic ranker must return the
 * lexical projection so character creation remains available without Bekko.
 */
fun interface CharacterCreationCatalogProjectionPort{
    fun project(catalog:CharacterCreationCatalog,conversation:List<CharacterCreationConversationEntry>):CharacterCreationCatalog

    companion object{
        val LEXICAL=CharacterCreationCatalogProjectionPort{catalog,conversation->catalog.projectForAi(conversation)}
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
    private val repository:UnifiedGameRepository,
    private val catalogProjection:CharacterCreationCatalogProjectionPort=CharacterCreationCatalogProjectionPort.LEXICAL
){
    private val conversation=mutableListOf<CharacterCreationConversationEntry>()
    private var pending:CharacterCreationGmCandidate.ReadyForConfirmation?=null

    fun play(
        input:String,
        cancellation:AiCancellationSignal=AiCancellationSignal.NONE,
        lockedSections:Set<CharacterCreationDraftSection> = emptySet()
    ):CharacterCreationApplicationOutcome{
        if(repository.activePlayerRef()!=null)return CharacterCreationApplicationOutcome.Failed("CHARACTER_ALREADY_ACTIVE")
        if(cancellation.isCancelled())return CharacterCreationApplicationOutcome.Cancelled()
        conversation+=CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,input)
        val catalog=repository.characterCreationCatalog()
        // A World Pack can expose hundreds of skills and techniques. Sending the whole catalog to
        // a 2k mobile model made an otherwise ready Bielik fail routing with NO_ELIGIBLE_MODEL.
        // Every legal family stays represented, while the full mandatory families are completed
        // below by Core from the authoritative catalog. Re-sending every stat/resource/domain to
        // a 1.5B model only increases prefill and structured-output failures without giving AI
        // additional mutation authority.
        val projectedConversation=conversation.projectForAi()
        val projectedCatalog=runCatching{catalogProjection.project(catalog,projectedConversation)}
            .getOrElse{catalog.projectForAi(projectedConversation)}
        projectedCatalog.answerCatalogQuestion(input)?.let{answer->
            conversation+=CharacterCreationConversationEntry(CharacterCreationConversationRole.GAME_MASTER,answer)
            return CharacterCreationApplicationOutcome.Question(answer)
        }
        // A player editing an already visible draft should not have to reload a 1.5B model merely
        // to select an exact, legal World Pack option. Apart from being wasteful, that path made a
        // simple "change the technique to Academy Clone Technique" vulnerable to an ExecuTorch
        // generation failure. Exact edits are resolved against the full Core-owned catalog, keep
        // locked sections untouched and still remain an uncommitted candidate until confirmation.
        pending?.let{ready->
            ready.draft.applyExplicitLegalEdit(input,catalog,lockedSections)?.let{edited->
                val completed=ready.copy(draft=edited,playerFacingSummary=edited.playerFacingEditSummary(catalog))
                pending=completed
                conversation+=CharacterCreationConversationEntry(CharacterCreationConversationRole.GAME_MASTER,completed.playerFacingSummary)
                return CharacterCreationApplicationOutcome.AwaitingExplicitConfirmation(
                    edited.creationUid,completed.playerFacingSummary,PlayerCharacterBootstrapService.fingerprint(edited)
                )
            }
        }
        val requiredUnits=projectedCatalog.estimatedInputUnits(projectedConversation)
        val selected=when(val result=route.route(AiRole.GAME_MASTER,AiWorkload.CHARACTER_CREATION,requiredUnits)){
            is AiRouteResult.Unavailable->return CharacterCreationApplicationOutcome.Failed(result.reasonUids.joinToString("|"))
            is AiRouteResult.Selected->result.provider
        }
        val request=AiCharacterCreationRequest(
            "CHARACTER-CREATION:${UUID.randomUUID()}",catalog.campaignUid,projectedCatalog,projectedConversation,
            authorityCatalog=catalog
        )
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
                val requestedSections=input.characterCreationRequestedSections()
                val effectiveLocks=if(pending==null||input.isCharacterCreationReroll()||requestedSections.size==CharacterCreationDraftSection.entries.size)
                    lockedSections
                else lockedSections+(CharacterCreationDraftSection.entries.toSet()-requestedSections)
                val completedDraft=candidate.draft.completeMandatoryChoices(catalog)
                    .preserveLockedSections(pending?.draft,effectiveLocks)
                val completed=candidate.copy(draft=completedDraft)
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
        AiProviderExtensionRegistry.onCharacterCreated(ready.draft.campaignUid,receipt.playerUid)
        return CharacterCreationApplicationOutcome.Created(receipt)
    }

    fun pendingDraft():PlayerCharacterCreationDraft?=pending?.draft
}

internal fun String.isCharacterCreationReroll()=
    Regex("(?iu)\\b(losuj|wylosuj|przerzuć|przerzuc|reroll|wygeneruj losow)\\b").containsMatchIn(this)

internal fun String.characterCreationRequestedSections():Set<CharacterCreationDraftSection>{
    val query=lowercase()
    if(Regex("(?iu)\\b(wszystko|cał[ąa] postać|cala postac|od nowa)\\b").containsMatchIn(query))return CharacterCreationDraftSection.entries.toSet()
    return buildSet{
        if(Regex("(?iu)\\b(imi[eę]|nazywam|jestem|płe[ćc]|chłopak|chlopak|chłopiec|chlopiec|dziewczyn|wiek|lat|rola|ucze[ńn]|akadem|era)\\b").containsMatchIn(query))add(CharacterCreationDraftSection.IDENTITY)
        if(Regex("(?iu)\\b(klan\\p{L}*|pochodzen\\p{L}*|ród\\p{L}*|rod\\p{L}*|wiosk\\p{L}*|konoh\\p{L}*|kiri\\p{L}*|suna\\p{L}*|kumo\\p{L}*|iwa\\p{L}*)\\b").containsMatchIn(query))add(CharacterCreationDraftSection.ORIGIN)
        if(Regex("(?iu)\\b(kekkei|genkai|wrodzon\\p{L}*|sharingan|byakugan|rinnegan)\\b").containsMatchIn(query))add(CharacterCreationDraftSection.INNATE_FEATURES)
        if(Regex("(?iu)\\b(statystyk\\p{L}*|atrybut\\p{L}*|zasób\\p{L}*|zasob\\p{L}*|chakra|zdrowi\\p{L}*|talent\\p{L}*|potencjał\\p{L}*|potencjal\\p{L}*)\\b").containsMatchIn(query))add(CharacterCreationDraftSection.PROGRESSION)
        if(Regex("(?iu)\\b(umiejętnoś\\p{L}*|umiejetnos\\p{L}*|skill\\p{L}*|skradani\\p{L}*|walka wr[eę]cz|kontrol\\p{L}* chakry)\\b").containsMatchIn(query))add(CharacterCreationDraftSection.SKILLS)
        if(Regex("(?iu)\\b(technik\\p{L}*|jutsu|klon\\p{L}*|kawarimi|bunshin)\\b").containsMatchIn(query))add(CharacterCreationDraftSection.TECHNIQUES)
        if(Regex("(?iu)\\b(lokacj\\p{L}*|miejsce start\\p{L}*|zaczn\\p{L}*|startuj\\p{L}*|akademi\\p{L}*|konoh\\p{L}*)\\b").containsMatchIn(query))add(CharacterCreationDraftSection.STARTING_LOCATION)
    }
}

/**
 * Factual catalog questions do not need generative inference. The already audience-authorized
 * projection (semantically ranked by Bekko when available) is rendered directly, so the player
 * sees real World Pack names rather than a small model's guess.
 */
internal fun CharacterCreationCatalog.answerCatalogQuestion(input:String):String?{
    val query=input.lowercase()
    val looksLikeQuestion='?' in input||Regex("(?iu)\\b(jakie|jaki|jaka|które|ktore|pokaż|pokaz|wymień|wymien|dostępne|dostepne)\\b").containsMatchIn(query)
    if(!looksLikeQuestion)return null
    val kinds=linkedSetOf<CharacterCreationDefinitionKind>()
    if(Regex("(?iu)\\b(klan|klany|pochodzenie|pochodzenia|ród|rod|wioska|wioski)\\b").containsMatchIn(query))kinds+=CharacterCreationDefinitionKind.ORIGIN
    if(Regex("(?iu)\\b(kekkei|genkai|cech[ay] wrodzon|wrodzone|zdolnoś[ćc] wrodzon)\\b").containsMatchIn(query))kinds+=CharacterCreationDefinitionKind.INNATE_FEATURE
    if(Regex("(?iu)\\b(klasa|klasy|zawód|zawod|zawody|profesj|talent|talenty)\\b").containsMatchIn(query)){
        kinds+=CharacterCreationDefinitionKind.TALENT
        kinds+=CharacterCreationDefinitionKind.ORIGIN
    }
    if(Regex("(?iu)\\b(umiejętnoś|umiejetnos|skill)\\b").containsMatchIn(query))kinds+=CharacterCreationDefinitionKind.SKILL
    if(Regex("(?iu)\\b(technik|jutsu)\\b").containsMatchIn(query))kinds+=CharacterCreationDefinitionKind.TECHNIQUE
    if(Regex("(?iu)\\b(statystyk|cech postaci)\\b").containsMatchIn(query))kinds+=CharacterCreationDefinitionKind.STAT
    if(Regex("(?iu)\\b(potencjał|potencjal)\\b").containsMatchIn(query))kinds+=CharacterCreationDefinitionKind.POTENTIAL
    if(Regex("(?iu)\\b(lokacj|miejsce start|gdzie)\\b").containsMatchIn(query))kinds+=CharacterCreationDefinitionKind.STARTING_LOCATION
    if(kinds.isEmpty())return null
    val labels=mapOf(
        CharacterCreationDefinitionKind.ORIGIN to "Klany i pochodzenia",
        CharacterCreationDefinitionKind.INNATE_FEATURE to "Kekkei Genkai i cechy wrodzone",
        CharacterCreationDefinitionKind.TALENT to "Klasy, zawody i talenty",
        CharacterCreationDefinitionKind.SKILL to "Umiejętności",
        CharacterCreationDefinitionKind.TECHNIQUE to "Techniki",
        CharacterCreationDefinitionKind.STAT to "Statystyki",
        CharacterCreationDefinitionKind.POTENTIAL to "Potencjały",
        CharacterCreationDefinitionKind.STARTING_LOCATION to "Miejsca startowe"
    )
    val lines=kinds.map{kind->
        val names=options.filter{it.kind==kind}.map{it.displayName}.distinct().take(8)
        "${labels.getValue(kind)}: ${names.ifEmpty{listOf("brak pozycji w tym World Packu")}.joinToString(", ")}"
    }
    return lines.joinToString("\n")+"\nMożesz wskazać wybrane elementy albo poprosić o losowy szablon."
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
    maximumEstimatedInputUnits:Int=900,
    semanticOrder:List<String> = emptyList(),
    maximumOptionsPerOptionalKind:Int=Int.MAX_VALUE
):CharacterCreationCatalog{
    require(maximumEstimatedInputUnits>0)
    require(maximumOptionsPerOptionalKind>0)
    val coreCompletedKinds=setOf(CharacterCreationDefinitionKind.STAT,CharacterCreationDefinitionKind.RESOURCE,CharacterCreationDefinitionKind.TALENT)
    val semanticRank=semanticOrder.withIndex().associate{it.value to it.index}
    val queryText=conversation.joinToString("\n"){it.text}.lowercase()
    val words=queryText.split(Regex("[^\\p{L}\\p{N}_-]+")).asSequence()
        .filter{it.length>=3}.toSet()
    val priorityTerms=buildSet{
        if(Regex("(?iu)klon|bunshin").containsMatchIn(queryText)){add("clone");add("bunshin");add("klon")}
        if(Regex("(?iu)akadem|academy").containsMatchIn(queryText)){add("academy");add("akadem")}
        if(Regex("(?iu)konoh").containsMatchIn(queryText)){add("konoha");add("konohagakure")}
        if(Regex("(?iu)skrad|stealth").containsMatchIn(queryText)){add("stealth");add("skrad")}
        if(Regex("(?iu)walka wr[eę]cz|taijutsu|melee|hand.?to.?hand").containsMatchIn(queryText)){
            add("taijutsu");add("melee");add("hand-to-hand");add("walka wręcz");add("walka wrecz")
        }
        if(Regex("(?iu)kontrol\\p{L}* chakry|chakra control").containsMatchIn(queryText)){
            add("chakra control");add("control chakra");add("kontrola chakry")
        }
    }
    fun relevance(option:CharacterCreationDefinitionOption):Int{
        val searchable="${option.definitionUid} ${option.displayName}".lowercase()
        val searchableWords=searchable.split(Regex("[^\\p{L}\\p{N}_-]+")).filter(String::isNotBlank)
        val explicit=priorityTerms.sumOf{term->if(searchable.contains(term))50 else 0}
        val lexical=words.sumOf{word->when{
            searchable==word->8
            searchable.contains(word)->3
            // Generic inflection tolerance keeps Polish forms such as Konoha/Konohy/Konosze
            // ahead of an unrelated exact word elsewhere in a location name. This is ranking
            // only; the final choice still has to be a legal UID from the canonical catalog.
            word.length>=5&&searchableWords.any{candidate->candidate.length>=5&&candidate.commonPrefixWith(word).length>=4}->4
            else->0
        }}
        val ranked=semanticRank[semanticWorldPackRecordUid(option)]?.let{10_000-it}?:0
        // Exact/structured evidence must win before semantic ranking. Bekko orders candidates
        // only inside the same lexical tier, never over an explicit player term.
        return (explicit+lexical)*100_000+ranked
    }

    val selected=mutableListOf<CharacterCreationDefinitionOption>()
    coreCompletedKinds.forEach{kind->options.filter{it.kind==kind}
        .sortedWith(compareByDescending<CharacterCreationDefinitionOption>(::relevance).thenBy{it.definitionUid})
        .firstOrNull()?.let(selected::add)}
    // One legal potential is enough for the compact response. completeMandatoryChoices() fills
    // every missing progression domain from the full canonical catalog before confirmation.
    options.filter{it.kind==CharacterCreationDefinitionKind.POTENTIAL}
        .groupBy{it.definitionUid}.toSortedMap().values
        .map{variants->variants.firstOrNull{it.dimensionUid=="MAXIMUM"}?:variants.sortedBy{it.dimensionUid}.first()}
        .sortedWith(compareByDescending<CharacterCreationDefinitionOption>(::relevance).thenBy{it.definitionUid})
        .firstOrNull()?.let(selected::add)
    val optionalKinds=CharacterCreationDefinitionKind.entries.filterNot{it in coreCompletedKinds||it==CharacterCreationDefinitionKind.POTENTIAL}
    val queues=optionalKinds.associateWith{kind->options.filter{it.kind==kind}
        .sortedWith(compareByDescending<CharacterCreationDefinitionOption>(::relevance).thenBy{it.definitionUid})
        .take(maximumOptionsPerOptionalKind).toMutableList()}
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

internal fun PlayerCharacterCreationDraft.preserveLockedSections(
    previous:PlayerCharacterCreationDraft?,
    locked:Set<CharacterCreationDraftSection>
):PlayerCharacterCreationDraft{
    if(previous==null)return this
    return copy(
        creationUid=previous.creationUid,
        playerUid=previous.playerUid,
        displayName=if(CharacterCreationDraftSection.IDENTITY in locked)previous.displayName else displayName,
        genderUid=if(CharacterCreationDraftSection.IDENTITY in locked)previous.genderUid else genderUid,
        identityChoices=if(CharacterCreationDraftSection.IDENTITY in locked)previous.identityChoices else identityChoices,
        originUids=if(CharacterCreationDraftSection.ORIGIN in locked)previous.originUids else originUids,
        innateFeatureUids=if(CharacterCreationDraftSection.INNATE_FEATURES in locked)previous.innateFeatureUids else innateFeatureUids,
        stats=if(CharacterCreationDraftSection.PROGRESSION in locked)previous.stats else stats,
        resources=if(CharacterCreationDraftSection.PROGRESSION in locked)previous.resources else resources,
        talents=if(CharacterCreationDraftSection.PROGRESSION in locked)previous.talents else talents,
        potentials=if(CharacterCreationDraftSection.PROGRESSION in locked)previous.potentials else potentials,
        skills=if(CharacterCreationDraftSection.SKILLS in locked)previous.skills else skills,
        techniques=if(CharacterCreationDraftSection.TECHNIQUES in locked)previous.techniques else techniques,
        startingLocationUid=if(CharacterCreationDraftSection.STARTING_LOCATION in locked)previous.startingLocationUid else startingLocationUid,
        startingXMillimetres=if(CharacterCreationDraftSection.STARTING_LOCATION in locked)previous.startingXMillimetres else startingXMillimetres,
        startingYMillimetres=if(CharacterCreationDraftSection.STARTING_LOCATION in locked)previous.startingYMillimetres else startingYMillimetres
    )
}

/**
 * Applies only explicit values that already exist in the authoritative character catalog. It is a
 * draft editor, not a generation shortcut: the result has no mutation authority and must still go
 * through the normal fingerprinted confirmation and full Core validation.
 */
internal fun PlayerCharacterCreationDraft.applyExplicitLegalEdit(
    input:String,
    catalog:CharacterCreationCatalog,
    locked:Set<CharacterCreationDraftSection> = emptySet()
):PlayerCharacterCreationDraft?{
    val requested=input.characterCreationRequestedSections()
    if(requested.isEmpty()||input.isCharacterCreationReroll())return null
    fun normalized(value:String)=value.lowercase()
        .replace('ł','l').replace('ó','o').replace('ą','a').replace('ę','e')
        .replace('ś','s').replace('ć','c').replace('ń','n').replace('ż','z').replace('ź','z')
        .replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().replace(Regex("\\s+")," ")
    val query=normalized(input)
    fun matches(kind:CharacterCreationDefinitionKind)=catalog.options.filter{option->
        option.kind==kind&&listOf(option.displayName,option.definitionUid).map(::normalized).any{needle->
            needle.length>=3&&Regex("(^| )${Regex.escape(needle)}( |$)").containsMatchIn(query)
        }
    }.distinctBy{it.definitionUid to it.dimensionUid}
    fun choice(option:CharacterCreationDefinitionOption,existing:List<CharacterCreationValueChoice> = emptyList()):CharacterCreationValueChoice{
        existing.firstOrNull{it.definitionUid==option.definitionUid&&it.dimensionUid==option.dimensionUid}?.let{return it}
        val minimum=option.minimumValue?:0.0
        val maximum=option.maximumValue?:minimum
        val value=when(option.kind){
            CharacterCreationDefinitionKind.RESOURCE->maximum
            CharacterCreationDefinitionKind.POTENTIAL->maximum.takeIf{it>minimum}?:50.0.coerceAtLeast(minimum)
            CharacterCreationDefinitionKind.STAT,CharacterCreationDefinitionKind.TALENT,
            CharacterCreationDefinitionKind.SKILL,CharacterCreationDefinitionKind.TECHNIQUE->
                (minimum+(maximum-minimum)*0.1).coerceIn(minimum,maximum)
            else->minimum
        }
        return CharacterCreationValueChoice(option.definitionUid,value,option.dimensionUid)
    }
    var edited=this
    var recognized=false
    fun editable(section:CharacterCreationDraftSection)=section in requested&&section !in locked

    if(editable(CharacterCreationDraftSection.IDENTITY)){
        var name=edited.displayName
        var gender=edited.genderUid
        val identity=edited.identityChoices.toMutableMap()
        Regex("(?iu)(?:imi[eę]\\s+(?:na|to)|nazywam\\s+si[eę]|jestem)\\s+([\\p{L}][\\p{L}'-]{1,39})")
            .find(input)?.groupValues?.get(1)?.let{candidate->name=candidate.replaceFirstChar{it.uppercase()};recognized=true}
        Regex("(?iu)\\b(\\d{1,3})\\s*(?:lat|lata|years?)\\b").find(input)?.groupValues?.get(1)?.let{identity["AGE"]=it;recognized=true}
        when{
            Regex("(?iu)chłopiec|chlopiec|mężczyzn|mezczyzn|male").containsMatchIn(input)->{gender="MALE";recognized=true}
            Regex("(?iu)dziewczyn|kobiet|female").containsMatchIn(input)->{gender="FEMALE";recognized=true}
            Regex("(?iu)niebinar|non.?binary").containsMatchIn(input)->{gender="NON_BINARY";recognized=true}
        }
        if(Regex("(?iu)akadem|academy").containsMatchIn(input)){identity["ROLE"]="ACADEMY_STUDENT";recognized=true}
        if(Regex("(?iu)naruto").containsMatchIn(input)){identity["ERA"]="NARUTO";recognized=true}
        edited=edited.copy(displayName=name,genderUid=gender,identityChoices=identity)
    }
    if(editable(CharacterCreationDraftSection.ORIGIN))matches(CharacterCreationDefinitionKind.ORIGIN).takeIf{it.isNotEmpty()}?.let{
        edited=edited.copy(originUids=it.map(CharacterCreationDefinitionOption::definitionUid));recognized=true
    }
    if(editable(CharacterCreationDraftSection.INNATE_FEATURES)){
        val rejected=Regex("(?iu)\\b(bez|nie chc[eę]|żadn|zadn)\\b.{0,40}\\b(kekkei|genkai|wrodzon)").containsMatchIn(input)
        val selected=matches(CharacterCreationDefinitionKind.INNATE_FEATURE)
        if(rejected){edited=edited.copy(innateFeatureUids=emptyList());recognized=true}
        else if(selected.isNotEmpty()){edited=edited.copy(innateFeatureUids=selected.map(CharacterCreationDefinitionOption::definitionUid));recognized=true}
    }
    if(editable(CharacterCreationDraftSection.PROGRESSION)){
        fun update(kind:CharacterCreationDefinitionKind,current:List<CharacterCreationValueChoice>):List<CharacterCreationValueChoice>{
            var values=current
            matches(kind).forEach{option->
                val label=Regex.escape(normalized(option.displayName))
                val requestedValue=Regex("(?:^| )$label(?: |\\s*[:=]\\s*)([0-9]+(?:[.,][0-9]+)?)")
                    .find(query)?.groupValues?.get(1)?.replace(',','.')?.toDoubleOrNull()
                val replacement=if(requestedValue!=null)CharacterCreationValueChoice(
                    option.definitionUid,requestedValue.coerceIn(option.minimumValue?:0.0,option.maximumValue?:requestedValue),option.dimensionUid
                ) else choice(option,current)
                values=values.filterNot{it.definitionUid==replacement.definitionUid&&it.dimensionUid==replacement.dimensionUid}+replacement
                recognized=true
            }
            return values
        }
        edited=edited.copy(
            stats=update(CharacterCreationDefinitionKind.STAT,edited.stats),
            resources=update(CharacterCreationDefinitionKind.RESOURCE,edited.resources),
            talents=update(CharacterCreationDefinitionKind.TALENT,edited.talents),
            potentials=update(CharacterCreationDefinitionKind.POTENTIAL,edited.potentials)
        )
    }
    if(editable(CharacterCreationDraftSection.SKILLS))matches(CharacterCreationDefinitionKind.SKILL).takeIf{it.isNotEmpty()}?.let{
        edited=edited.copy(skills=it.map{option->choice(option,skills)});recognized=true
    }
    if(editable(CharacterCreationDraftSection.TECHNIQUES))matches(CharacterCreationDefinitionKind.TECHNIQUE).takeIf{it.isNotEmpty()}?.let{
        edited=edited.copy(techniques=it.map{option->choice(option,techniques)});recognized=true
    }
    if(editable(CharacterCreationDraftSection.STARTING_LOCATION))matches(CharacterCreationDefinitionKind.STARTING_LOCATION).firstOrNull()?.let{
        edited=edited.copy(startingLocationUid=it.definitionUid);recognized=true
    }
    return edited.takeIf{recognized}
}

internal fun PlayerCharacterCreationDraft.playerFacingEditSummary(catalog:CharacterCreationCatalog):String{
    fun label(uid:String)=catalog.options.firstOrNull{it.definitionUid==uid}?.displayName?:uid
    val role=when(identityChoices["ROLE"]){"ACADEMY_STUDENT"->"uczeń Akademii";null->"postać";else->identityChoices.getValue("ROLE")}
    return buildString{
        append("Zaktualizowano projekt: $displayName — $role")
        originUids.firstOrNull()?.let{append(" z ${label(it)}")}
        append(". Umiejętności: ${skills.joinToString{label(it.definitionUid)}}")
        append("; techniki: ${techniques.joinToString{label(it.definitionUid)}}")
        append("; start: ${label(startingLocationUid)}. Zatwierdź dopiero, gdy wszystko Ci odpowiada.")
    }
}

internal fun List<CharacterCreationConversationEntry>.projectForAi(maximumUnits:Int=160):List<CharacterCreationConversationEntry>{
    require(maximumUnits>0)
    if(isEmpty())return emptyList()
    fun compact(entry:CharacterCreationConversationEntry)=entry.copy(text=entry.text.take(240))
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
            alignPristineCampaignEra(draft.identityChoices["ERA"])
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

    /**
     * A freshly cloned campaign template must not keep a contradictory historical era after the
     * player explicitly confirms another era in the character draft. This is intentionally
     * world-agnostic: the confirmed World Pack label is stored without inventing a canon anchor.
     * Once any canonical turn exists, changing the clock here is forbidden.
     */
    private fun alignPristineCampaignEra(explicitEra:String?){
        val raw=explicitEra?.trim()?.takeIf(String::isNotBlank)?:return
        val hasCalendar=tableExists("campaign_calendar")
        val hasWorldClock=tableExists("world_clock")
        if(!hasCalendar&&!hasWorldClock)return
        if(tableExists("turn_transaction_receipts")){
            val committed=db.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts WHERE campaign_uid=?",arrayOf(campaignUid))
                .use{cursor->cursor.moveToFirst();cursor.getLong(0)}
            require(committed==0L){"RPGOS-CHARACTER-CREATION:ERA_CHANGE_AFTER_FIRST_TURN"}
        }
        val normalized=raw.replace(Regex("[^\\p{L}\\p{N}]+"),"_").trim('_').lowercase()
        require(normalized.isNotBlank()){"RPGOS-CHARACTER-CREATION:INVALID_ERA"}
        val title=normalized.split('_').filter(String::isNotBlank).joinToString(" "){word->word.replaceFirstChar{it.titlecase()}}
        val previousEraKey=when{
            hasCalendar->db.rawQuery("SELECT era_key FROM campaign_calendar WHERE id=1",null).use{cursor->
                if(cursor.moveToFirst()&&!cursor.isNull(0))cursor.getString(0) else null
            }
            hasWorldClock->db.rawQuery("SELECT era FROM world_clock WHERE id=1",null).use{cursor->
                if(cursor.moveToFirst()&&!cursor.isNull(0))cursor.getString(0) else null
            }
            else->null
        }?.replace(Regex("[^\\p{L}\\p{N}]+"),"_")?.trim('_')?.lowercase()
        val eraChanged=previousEraKey!=normalized
        // Active instances belong to the template clock that produced them. Once a player
        // explicitly chooses another era for a pristine clone, carrying those instances forward
        // would expose mutually contradictory world state (for example a war from the template's
        // former epoch). Preserve the records for audit, but remove their active authority. This
        // is intentionally genre/world-pack neutral and runs only before the first canonical turn.
        if(eraChanged){
            if(tableExists("active_world_events"))db.execSQL("UPDATE active_world_events SET status='cancelled' WHERE status='active'")
            if(tableExists("timeline_events"))db.execSQL("UPDATE timeline_events SET status='cancelled' WHERE status='active'")
        }
        if(hasCalendar)db.execSQL(
            "UPDATE campaign_calendar SET absolute_day=0,year_number=0,year_label=?,era_key=?,era_name=?,canon_anchor_event_uid=NULL,updated_chapter=0 WHERE id=1",
            arrayOf<Any?>("Początek ery $title",normalized,"Era $title")
        )
        if(hasWorldClock)db.execSQL(
            "UPDATE world_clock SET campaign_day=0,campaign_year=0,era=?,updated_chapter=0 WHERE id=1",
            arrayOf<Any?>("Era $title")
        )
    }

    private fun tableExists(name:String)=db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(name)
    ).use{it.moveToFirst()}

    private fun persistIdentityFacts(draft:PlayerCharacterCreationDraft,fingerprint:String,userActionUid:String,provenance:String){
        val values=linkedMapOf("NAME" to draft.displayName,"GENDER" to draft.genderUid).apply{putAll(draft.identityChoices.toSortedMap())}
        values.forEach{(key,value)->insertFact("$provenance:IDENTITY:$key",draft.playerUid,"RPGOS:PLAYER_IDENTITY:${key.uppercase()}",value,draft.creationUid,userActionUid)}
        insertFact("$provenance:RECEIPT",draft.playerUid,"RPGOS:CHARACTER_CREATION:FINGERPRINT",fingerprint,draft.creationUid,userActionUid)
    }

    private fun insertFact(uid:String,subjectUid:String,predicate:String,value:String,sourceId:String,userActionUid:String){
        db.execSQL("""INSERT INTO campaign_truth_records(truth_uid,campaign_id,truth_kind,subject_uid,predicate,object_value,source_type,source_id,created_turn,confidence,verified,method,engine_version,created_at,active)
            VALUES(?,?,'FACT',?,?,?,'PLAYER_ACTION',?,0,1.0,1,?,'RPGOS-CHARACTER-CREATION-V1',strftime('%s','now'),1)""".trimIndent(),
            arrayOf<Any?>(stableUid(uid),campaignUid,subjectUid,predicate,value,sourceId,"EXPLICIT_USER_CONFIRMATION:$userActionUid"))
    }

    private fun existingReceipt(creationUid:String):PlayerCharacterBootstrapReceipt?=db.rawQuery(
        "SELECT subject_uid,object_value FROM campaign_truth_records WHERE campaign_id=? AND source_type IN ('PLAYER_ACTION','CHARACTER_CREATION') AND source_id=? AND predicate='RPGOS:CHARACTER_CREATION:FINGERPRINT' AND active=1 LIMIT 1",
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
