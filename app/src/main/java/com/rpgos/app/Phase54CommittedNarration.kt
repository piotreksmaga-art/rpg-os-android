package com.rpgos.app

import java.io.File
import java.security.MessageDigest

enum class CommittedNarrativeFactKind { FACT, HOLDER_BELIEF, PLAYER_ASSERTION, MECHANICAL_RESULT, NARRATIVE_COLOR, PRESENTATION_CONSEQUENCE }
data class CommittedNarrativeFact(
    val factUid:String,
    val kind:CommittedNarrativeFactKind,
    val subjectProjectedUid:String?,
    val predicateUid:String,
    val valueCanonical:String,
    val committedOrder:Long
){init{
    require(factUid.isNotBlank()&&predicateUid.isNotBlank()&&valueCanonical.isNotBlank()&&committedOrder>0)
    require(subjectProjectedUid?.isBlank()!=true)
}}

data class PostCommitPlayerVisibleReadback(
    val campaignUid:String,
    val turnUid:String,
    val commandUid:String,
    val transactionUid:String,
    val committedOrder:Long,
    val phase38ProjectionUid:String,
    val playerSnapshot:Map<String,String>,
    val legalFacts:List<CommittedNarrativeFact>,
    val presentationConsequences:List<String>,
    val forbiddenDisclosureTokens:Set<String>,
    val campaignDivergenceUids:Set<String>,
    val stopPointUid:String
){init{
    require(listOf(campaignUid,turnUid,commandUid,transactionUid,phase38ProjectionUid,stopPointUid).none{it.isBlank()}&&committedOrder>0)
    require(legalFacts.all{it.committedOrder==committedOrder}&&legalFacts.map{it.factUid}.distinct().size==legalFacts.size)
    require(playerSnapshot.keys.none{it.isBlank()}&&presentationConsequences.none{it.isBlank()}&&forbiddenDisclosureTokens.none{it.isBlank()})
}}

class CommittedNarrationContext internal constructor(
    val campaignUid:String,
    val turnUid:String,
    val commandUid:String,
    val transactionUid:String,
    val committedOrder:Long,
    val playerVisibleProjectionUid:String,
    val playerSnapshot:Map<String,String>,
    val legalFacts:List<CommittedNarrativeFact>,
    val presentationConsequences:List<String>,
    val forbiddenDisclosureTokens:Set<String>,
    val campaignDivergenceUids:Set<String>,
    val stopPointUid:String,
    val contextFingerprint:String
)

fun interface CommittedNarrationReadPort{
    /** Must perform an as-of read at exactly receipt.commitOrder and apply Phase38 before returning. */
    fun read(identity:TurnTransactionIdentity,receipt:TurnCommitReceipt,audience:AudienceContext,purpose:PurposeContext):PostCommitPlayerVisibleReadback
}

class CommittedNarrationContextBuilder(private val readPort:CommittedNarrationReadPort){
    fun build(
        evidence:AuthoritativeCommitEvidence,
        audience:AudienceContext,
        purpose:PurposeContext
    ):CommittedNarrationContext{
        val receipt=evidence.receipt;val identity=evidence.requestedIdentity
        require(audience.campaignUid==identity.campaignUid&&purpose.campaignUid==identity.campaignUid){"RPGOS-P54:CROSS_CAMPAIGN_NARRATION_READ"}
        val read=readPort.read(identity,receipt,audience,purpose)
        require(read.campaignUid==identity.campaignUid&&read.turnUid==identity.turnUid&&read.commandUid==identity.commandUid&&read.transactionUid==identity.transactionUid){"RPGOS-P54:POST_COMMIT_READBACK_IDENTITY_MISMATCH"}
        require(read.committedOrder==receipt.commitOrder){"RPGOS-P54:POST_COMMIT_READBACK_ORDER_MISMATCH"}
        require(read.phase38ProjectionUid.isNotBlank()){"RPGOS-P54:PHASE38_PROJECTION_REQUIRED"}
        val fingerprint=sha256(listOf(
            read.campaignUid,read.turnUid,read.commandUid,read.transactionUid,read.committedOrder,read.phase38ProjectionUid,
            read.playerSnapshot.toSortedMap(),read.legalFacts.sortedBy{it.factUid},read.presentationConsequences,
            read.forbiddenDisclosureTokens.sorted(),read.campaignDivergenceUids.sorted(),read.stopPointUid
        ).joinToString("|"))
        return CommittedNarrationContext(
            read.campaignUid,read.turnUid,read.commandUid,read.transactionUid,read.committedOrder,read.phase38ProjectionUid,
            read.playerSnapshot.toSortedMap(),read.legalFacts.sortedBy{it.factUid},read.presentationConsequences.toList(),
            read.forbiddenDisclosureTokens.toSet(),read.campaignDivergenceUids.toSet(),read.stopPointUid,fingerprint
        )
    }
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

enum class NarrativeClaimKind { FACT, BELIEF, PLAYER_ASSERTION, MECHANICAL_RESULT, NARRATIVE_COLOR }
data class NarrativeSemanticClaim(
    val claimUid:String,val kind:NarrativeClaimKind,val supportFactUid:String?,val predicateUid:String?,val valueCanonical:String?
){init{
    require(claimUid.isNotBlank()&&!supportFactUid.isNullOrBlank()&&!predicateUid.isNullOrBlank()&&!valueCanonical.isNullOrBlank())
}}

data class NarrativeValidationResult(val accepted:Boolean,val reasonUids:List<String>){init{require(accepted==reasonUids.isEmpty())}}

class NarrativeValidator{
    fun validate(narrative:RenderedNarrative,context:CommittedNarrationContext,playerInput:String?=null):NarrativeValidationResult{
        val reasons=linkedSetOf<String>()
        if(narrative.committedOrder!=context.committedOrder)reasons+="NARRATIVE_COMMIT_ORDER_MISMATCH"
        if(narrative.stopReasonUid!=context.stopPointUid)reasons+="NARRATIVE_STOP_POINT_MISMATCH"
        val authorizedNonPlayerUtterances=context.legalFacts.filter{
            it.kind==CommittedNarrativeFactKind.NARRATIVE_COLOR&&it.predicateUid==GmNarrativePredicates.NPC_UTTERANCE
        }.map{it.valueCanonical}
        if(narrative.assertsPlayerVolition||NarrativePlayerAgencySurfaceGuard.violates(narrative.text,playerInput,authorizedNonPlayerUtterances))
            reasons+="NARRATIVE_INVENTED_PLAYER_VOLITION"
        val facts=context.legalFacts.associateBy{it.factUid}
        narrative.claims.forEach{claim->
            val support=facts[claim.supportFactUid]
            if(support==null)reasons+="NARRATIVE_UNSUPPORTED_FACT:${claim.claimUid}"
            else if(support.predicateUid!=claim.predicateUid||support.valueCanonical!=claim.valueCanonical)reasons+="NARRATIVE_FACT_DRIFT:${claim.claimUid}"
            when(claim.kind){
                NarrativeClaimKind.FACT->if(support?.kind!=CommittedNarrativeFactKind.FACT)reasons+="NARRATIVE_FACT_WITHOUT_FACT_SUPPORT:${claim.claimUid}"
                NarrativeClaimKind.MECHANICAL_RESULT->if(support?.kind!=CommittedNarrativeFactKind.MECHANICAL_RESULT)reasons+="NARRATIVE_MECHANICS_DRIFT:${claim.claimUid}"
                NarrativeClaimKind.NARRATIVE_COLOR->if(support?.kind!=CommittedNarrativeFactKind.NARRATIVE_COLOR)reasons+="NARRATIVE_COLOR_WITHOUT_SUPPORT:${claim.claimUid}"
                NarrativeClaimKind.BELIEF->if(support?.kind!=CommittedNarrativeFactKind.HOLDER_BELIEF)reasons+="NARRATIVE_BELIEF_WITHOUT_SUPPORT:${claim.claimUid}"
                NarrativeClaimKind.PLAYER_ASSERTION->if(support?.kind!=CommittedNarrativeFactKind.PLAYER_ASSERTION)reasons+="NARRATIVE_PLAYER_ASSERTION_WITHOUT_SUPPORT:${claim.claimUid}"
            }
        }
        val lowercase=narrative.text.lowercase()
        context.forbiddenDisclosureTokens.filter{it.lowercase() in lowercase}.forEach{reasons+="HIDDEN_DISCLOSURE_TOKEN"}
        val internalPatterns=listOf(Regex("RPGOS-[A-Z0-9:_-]+"),Regex("(?:EVENT|PROOF|TX|RECEIPT):[A-Za-z0-9:_-]+"))
        if(internalPatterns.any{it.containsMatchIn(narrative.text)})reasons+="INTERNAL_PROVENANCE_DISCLOSURE"
        val technicalSurface=listOf(
            Regex("(?iu)\\btor(?:ze|u|em)?\\s+mechanicz\\p{L}*"),
            Regex("(?iu)\\bmechanicz\\p{L}*\\s+(?:tor|licznik|wynik)\\p{L}*"),
            Regex("(?iu)\\b(?:odnotowano|zarejestrowano)\\s+[+-]?\\d+\\b"),
            Regex("\\b[A-Z]{2,}(?:_[A-Z0-9]+)+\\b")
        )
        if(technicalSurface.any{it.containsMatchIn(narrative.text)})reasons+="INTERNAL_MECHANICS_SURFACE_DISCLOSURE"
        return NarrativeValidationResult(reasons.isEmpty(),reasons.sorted())
    }
}

/**
 * A provider-controlled `assertsPlayerVolition=false` is not evidence that the prose preserves
 * player agency.  This deliberately conservative surface guard catches the high-confidence
 * failures which can be recognized without treating the language model as an authority:
 * first-person narration, explicit first-person future decisions and a new Polish adverbial
 * action (for example `szukając`) which has no lexical root in the submitted player action.
 */
private object NarrativePlayerAgencySurfaceGuard{
    private val firstPersonPronouns=setOf("ja","mnie","mi","mna","moj","moja","moje","moim","moich","moja")
    private val explicitFirstPersonFuture=setOf(
        "zacznę","zaczne","będę","bede","pójdę","pojde","zrobię","zrobie","poszukam","spróbuję","sprobuje",
        "zaatakuję","zaatakuje","wybiorę","wybiore","zdecyduję","zdecyduje","postanowię","postanowie",
        "zamierzam","planuję","planuje"
    )
    private val words=Regex("(?iu)\\p{L}+")
    private val sentenceStart=Regex("(?iu)(?:^|[.!?]\\s+)(\\p{L}+)")
    private val polishGerund=Regex("(?iu)\\b(\\p{L}{4,})ąc\\b")
    private val explicitFirstPersonDesire=Regex("(?iu)\\bchcę\\b|\\bja\\s+chce\\b")

    fun violates(text:String,playerInput:String?,authorizedNonPlayerUtterances:List<String> = emptyList()):Boolean{
        var playerAgencySurface=text
        authorizedNonPlayerUtterances.sortedByDescending{it.length}.forEach{utterance->
            val plain=utterance.trim().trim('„','”','“','"')
            listOf(utterance,plain).filter{it.isNotBlank()}.distinct().forEach{authorized->
                playerAgencySurface=playerAgencySurface.replace(authorized,"",ignoreCase=false)
            }
        }
        if(explicitFirstPersonDesire.containsMatchIn(playerAgencySurface))return true
        val outputWords=words.findAll(playerAgencySurface).map{fold(it.value)}.toList()
        if(outputWords.any{it in firstPersonPronouns||it in explicitFirstPersonFuture})return true
        val inputWords=playerInput?.let{value->words.findAll(value).map{fold(it.value)}.toList()}?:emptyList()
        if(inputWords.isNotEmpty()){
            val mirroredFirstPerson=sentenceStart.findAll(playerAgencySurface).map{fold(it.groupValues[1])}.any{token->
                token in inputWords&&looksLikeFirstPersonVerb(token)
            }
            if(mirroredFirstPerson)return true
            val unauthorizedGerund=polishGerund.findAll(playerAgencySurface).any{match->
                val stem=fold(match.groupValues[1]).removeSuffix("uj")
                inputWords.none{candidate->commonPrefixLength(stem,candidate)>=4}
            }
            if(unauthorizedGerund)return true
        }
        return false
    }

    private fun looksLikeFirstPersonVerb(token:String)=token.length>=4&&(
        token.endsWith("am")||token.endsWith("em")||token.endsWith("ę")||token.endsWith("e")&&token in explicitFirstPersonFuture
    )

    private fun commonPrefixLength(left:String,right:String):Int{
        var index=0
        while(index<left.length&&index<right.length&&left[index]==right[index])index++
        return index
    }

    private fun fold(value:String)=value.lowercase()
        .replace('ą','a').replace('ć','c').replace('ę','e').replace('ł','l')
        .replace('ń','n').replace('ó','o').replace('ś','s').replace('ź','z').replace('ż','z')
}

data class AiNarrativeRepairRequest(
    val requestUid:String,val original:AiNarrativeRequest,val rejected:RenderedNarrative,val rejectionReasonUids:List<String>,val attempt:Int
){init{require(requestUid.isNotBlank()&&rejectionReasonUids.isNotEmpty()&&attempt in 1..3)}}

data class NarrativeRepairPolicy(val maximumAttempts:Int=2){init{require(maximumAttempts in 0..3)}}
data class NarrativeRenderOutcome(val narrative:RenderedNarrative,val usedFallback:Boolean,val repairAttempts:Int,val terminalReasonUid:String)

class CommittedNarrativeRenderer(
    private val validator:NarrativeValidator=NarrativeValidator(),
    private val policy:NarrativeRepairPolicy=NarrativeRepairPolicy()
){
    fun fallbackOnly(context:CommittedNarrationContext,reasonUid:String)=fallback(context,reasonUid,0)
    fun render(provider:AiProvider,request:AiNarrativeRequest,cancellation:AiCancellationSignal):NarrativeRenderOutcome{
        val first=provider.renderNarrative(request,cancellation)
        if(first is AiProviderResult.Failure)return fallback(request.context,"NARRATOR_FAILURE:${first.reasonUid}",0)
        var current=(first as AiProviderResult.Success).value
        var validation=validator.validate(current,request.context,request.playerInput);var attempts=0
        while(!validation.accepted&&attempts<policy.maximumAttempts&&!cancellation.isCancelled()){
            val repaired=provider.repairNarrative(AiNarrativeRepairRequest("${request.requestUid}:REPAIR:${attempts+1}",request,current,validation.reasonUids,attempts+1),cancellation)
            if(repaired is AiProviderResult.Failure)return fallback(request.context,"NARRATIVE_REPAIR_FAILURE:${repaired.reasonUid}",attempts)
            current=(repaired as AiProviderResult.Success).value;attempts++;validation=validator.validate(current,request.context,request.playerInput)
        }
        return if(validation.accepted)NarrativeRenderOutcome(current,false,attempts,"NARRATIVE_ACCEPTED")
        else fallback(request.context,"NARRATIVE_VALIDATION_REJECTED",attempts)
    }

    private fun fallback(context:CommittedNarrationContext,reason:String,attempts:Int):NarrativeRenderOutcome{
        val sentences=buildList{
            addAll(context.presentationConsequences.take(4))
            if(isEmpty())context.legalFacts.take(4).forEach{fact->add("${humanize(fact.predicateUid)}: ${fact.valueCanonical}.")}
            if(isEmpty())add("Świat przyjął rezultat tej tury. Możesz zdecydować, co robisz dalej.")
        }
        val text=sentences.joinToString(" ").replace(Regex("\\s+")," ").trim()
        val claims=context.legalFacts.take(4).map{fact->NarrativeSemanticClaim(
            "FALLBACK:${fact.factUid}",if(fact.kind==CommittedNarrativeFactKind.MECHANICAL_RESULT)NarrativeClaimKind.MECHANICAL_RESULT else NarrativeClaimKind.FACT,
            fact.factUid,fact.predicateUid,fact.valueCanonical
        )}
        return NarrativeRenderOutcome(RenderedNarrative(text,context.stopPointUid,context.committedOrder,claims,false),true,attempts,reason)
    }

    private fun humanize(value:String)=value.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()}
}

data class NarrativeDeliveryIdentity(val transactionUid:String,val committedOrder:Long,val localeUid:String){init{require(transactionUid.isNotBlank()&&committedOrder>0&&localeUid.isNotBlank())}}
data class NarrativeDeliveryReceipt(
    val deliveryUid:String,val identity:NarrativeDeliveryIdentity,val contextFingerprint:String,val narrative:RenderedNarrative,
    val providerUid:String,val modelUid:String,val narrativeFingerprint:String
){init{require(listOf(deliveryUid,contextFingerprint,providerUid,modelUid,narrativeFingerprint).none{it.isBlank()})}}

interface NarrativeDeliveryStore{
    fun find(identity:NarrativeDeliveryIdentity):NarrativeDeliveryReceipt?
    fun record(receipt:NarrativeDeliveryReceipt):NarrativeDeliveryReceipt
}

class InMemoryNarrativeDeliveryStore:NarrativeDeliveryStore{
    private val values=linkedMapOf<NarrativeDeliveryIdentity,NarrativeDeliveryReceipt>()
    @Synchronized override fun find(identity:NarrativeDeliveryIdentity)=values[identity]
    @Synchronized override fun record(receipt:NarrativeDeliveryReceipt):NarrativeDeliveryReceipt{
        val existing=values[receipt.identity]
        require(existing==null||existing==receipt){"RPGOS-P54:DELIVERY_IDEMPOTENCY_CONFLICT"}
        return existing?:receipt.also{values[it.identity]=it}
    }
}

/** Presentation/recovery cache; it has no mutation authority and is rebuildable from committed state. */
class FileNarrativeDeliveryStore(private val directory:File):NarrativeDeliveryStore{
    init{directory.mkdirs()}
    override fun find(identity:NarrativeDeliveryIdentity):NarrativeDeliveryReceipt?{
        val file=file(identity);if(!file.isFile)return null
        return runCatching{
            val parts=file.readLines(Charsets.UTF_8)
            if(parts.firstOrNull()!="RPGOS-NARRATIVE-DELIVERY-V2"||parts.size<12)return null
            val claimCount=unescape(parts[11]).toIntOrNull()?:return null
            if(claimCount<0||parts.size!=12+claimCount*5)return null
            val claims=(0 until claimCount).map{index->
                val at=12+index*5
                NarrativeSemanticClaim(unescape(parts[at]),enumValueOf(unescape(parts[at+1])),unescapeNullable(parts[at+2]),unescapeNullable(parts[at+3]),unescapeNullable(parts[at+4]))
            }
            val narrative=RenderedNarrative(unescape(parts[9]),unescape(parts[8]),identity.committedOrder,claims,unescape(parts[10]).toBooleanStrict())
            NarrativeDeliveryReceipt(unescape(parts[1]),identity,unescape(parts[2]),narrative,unescape(parts[3]),unescape(parts[4]),unescape(parts[5]))
                .takeIf{narrativeFingerprint(it.narrative)==it.narrativeFingerprint}
        }.getOrNull()
    }
    @Synchronized override fun record(receipt:NarrativeDeliveryReceipt):NarrativeDeliveryReceipt{
        find(receipt.identity)?.let{existing->require(existing==receipt){"RPGOS-P54:DELIVERY_IDEMPOTENCY_CONFLICT"};return existing}
        val target=file(receipt.identity);val staging=File(directory,".${target.name}.${System.nanoTime()}.partial")
        val values=buildList{
            add("RPGOS-NARRATIVE-DELIVERY-V2")
            listOf(receipt.deliveryUid,receipt.contextFingerprint,receipt.providerUid,receipt.modelUid,receipt.narrativeFingerprint,
                receipt.identity.localeUid,receipt.identity.committedOrder.toString(),receipt.narrative.stopReasonUid,receipt.narrative.text,
                receipt.narrative.assertsPlayerVolition.toString(),receipt.narrative.claims.size.toString()).forEach{add(escape(it))}
            receipt.narrative.claims.forEach{claim->
                add(escape(claim.claimUid));add(escape(claim.kind.name));add(escapeNullable(claim.supportFactUid));add(escapeNullable(claim.predicateUid));add(escapeNullable(claim.valueCanonical))
            }
        }
        staging.writeText(values.joinToString("\n"),Charsets.UTF_8)
        if(!staging.renameTo(target)){staging.copyTo(target,true);staging.delete()}
        return receipt
    }
    private fun file(identity:NarrativeDeliveryIdentity)=File(directory,sha256("${identity.transactionUid}|${identity.committedOrder}|${identity.localeUid}")+".delivery")
    private fun escape(value:String)=java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun unescape(value:String)=String(java.util.Base64.getDecoder().decode(value),Charsets.UTF_8)
    private fun escapeNullable(value:String?)=value?.let(::escape)?:"-"
    private fun unescapeNullable(value:String)=if(value=="-")null else unescape(value)
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

data class PendingNarrationRecovery(val request:ChatTurnRequest,val committedOrder:Long){init{require(committedOrder>0)}}
interface NarrationRecoveryStore{
    fun record(request:ChatTurnRequest,receipt:TurnCommitReceipt)
    fun pending(campaignUid:String):PendingNarrationRecovery?
    fun clear(transactionUid:String)
}
class InMemoryNarrationRecoveryStore:NarrationRecoveryStore{
    private val values=linkedMapOf<String,PendingNarrationRecovery>()
    @Synchronized override fun record(request:ChatTurnRequest,receipt:TurnCommitReceipt){
        require(receipt.transactionUid==request.transactionUid);val value=PendingNarrationRecovery(request,requireNotNull(receipt.commitOrder))
        val old=values[request.transactionUid];require(old==null||old==value){"RPGOS-P54:RECOVERY_IDEMPOTENCY_CONFLICT"};values[request.transactionUid]=value
    }
    @Synchronized override fun pending(campaignUid:String)=values.values.filter{it.request.campaignUid==campaignUid}.maxByOrNull{it.committedOrder}
    @Synchronized override fun clear(transactionUid:String){values.remove(transactionUid)}
}

/** Durable post-commit recovery marker. It contains presentation identity only and no mutation material. */
class FileNarrationRecoveryStore(private val directory:File):NarrationRecoveryStore{
    init{directory.mkdirs()}
    @Synchronized override fun record(request:ChatTurnRequest,receipt:TurnCommitReceipt){
        require(receipt.campaignUid==request.campaignUid&&receipt.turnUid==request.turnUid&&receipt.commandUid==request.commandUid&&receipt.transactionUid==request.transactionUid)
        val order=requireNotNull(receipt.commitOrder);val target=file(request.transactionUid)
        read(target)?.let{existing->require(existing==PendingNarrationRecovery(request,order)){"RPGOS-P54:RECOVERY_IDEMPOTENCY_CONFLICT"};return}
        val principal=request.audience.principal
        val raw=listOf("RPGOS-NARRATIVE-RECOVERY-V1",request.requestUid,request.campaignUid,request.turnUid,request.commandUid,request.transactionUid,
            request.actor.actorKindUid,request.actor.actorUid,request.input,request.localeUid,request.audience.audienceKindUid,principal?.kindUid.orEmpty(),principal?.uid.orEmpty(),
            request.purpose.purposeUid,request.atOrder?.toString().orEmpty(),order.toString())
        val staging=File(directory,".${target.name}.${System.nanoTime()}.partial")
        staging.writeText(raw.mapIndexed{index,value->if(index==0)value else escape(value)}.joinToString("\n"),Charsets.UTF_8)
        if(!staging.renameTo(target)){staging.copyTo(target,true);staging.delete()}
    }
    @Synchronized override fun pending(campaignUid:String)=directory.listFiles{file->file.name.endsWith(".recovery")}.orEmpty().mapNotNull(::read)
        .filter{it.request.campaignUid==campaignUid}.maxByOrNull{it.committedOrder}
    @Synchronized override fun clear(transactionUid:String){val target=file(transactionUid);if(target.isFile&&!target.delete())target.writeText("",Charsets.UTF_8)}
    private fun read(file:File):PendingNarrationRecovery?=runCatching{
        val p=file.readLines(Charsets.UTF_8);if(p.size!=16||p[0]!="RPGOS-NARRATIVE-RECOVERY-V1")return null
        val v=p.drop(1).map(::unescape);val campaign=v[1]
        val principal=if(v[10].isBlank()||v[11].isBlank())null else VisibilityPrincipalRef(v[10],v[11])
        PendingNarrationRecovery(ChatTurnRequest(v[0],campaign,v[2],v[3],v[4],CommandActorRef(v[5],v[6]),v[7],v[8],
            AudienceContext(campaign,v[9],principal),PurposeContext(campaign,v[12]),v[13].toLongOrNull()),v[14].toLong())
    }.getOrNull()
    private fun file(transactionUid:String)=File(directory,sha256(transactionUid)+".recovery")
    private fun escape(value:String)=java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun unescape(value:String)=String(java.util.Base64.getDecoder().decode(value),Charsets.UTF_8)
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

internal fun narrativeFingerprint(narrative:RenderedNarrative)=MessageDigest.getInstance("SHA-256")
    .digest("${narrative.committedOrder}|${narrative.stopReasonUid}|${narrative.text}|${narrative.claims}|${narrative.assertsPlayerVolition}".toByteArray()).joinToString(""){"%02x".format(it)}
