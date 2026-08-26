package com.rpgos.app

import java.io.File
import java.security.MessageDigest

enum class CommittedNarrativeFactKind { FACT, HOLDER_BELIEF, PLAYER_ASSERTION, MECHANICAL_RESULT, PRESENTATION_CONSEQUENCE }
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
            read.campaignDivergenceUids.sorted(),read.stopPointUid
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
    require(claimUid.isNotBlank()&&supportFactUid?.isBlank()!=true&&predicateUid?.isBlank()!=true&&valueCanonical?.isBlank()!=true)
    if(kind in setOf(NarrativeClaimKind.FACT,NarrativeClaimKind.MECHANICAL_RESULT))require(supportFactUid!=null&&predicateUid!=null&&valueCanonical!=null)
}}

data class NarrativeValidationResult(val accepted:Boolean,val reasonUids:List<String>){init{require(accepted==reasonUids.isEmpty())}}

class NarrativeValidator{
    fun validate(narrative:RenderedNarrative,context:CommittedNarrationContext):NarrativeValidationResult{
        val reasons=linkedSetOf<String>()
        if(narrative.committedOrder!=context.committedOrder)reasons+="NARRATIVE_COMMIT_ORDER_MISMATCH"
        if(narrative.stopReasonUid!=context.stopPointUid)reasons+="NARRATIVE_STOP_POINT_MISMATCH"
        if(narrative.assertsPlayerVolition)reasons+="NARRATIVE_INVENTED_PLAYER_VOLITION"
        val facts=context.legalFacts.associateBy{it.factUid}
        narrative.claims.forEach{claim->
            if(claim.kind in setOf(NarrativeClaimKind.FACT,NarrativeClaimKind.MECHANICAL_RESULT)){
                val support=facts[claim.supportFactUid]
                if(support==null)reasons+="NARRATIVE_UNSUPPORTED_FACT:${claim.claimUid}"
                else if(support.predicateUid!=claim.predicateUid||support.valueCanonical!=claim.valueCanonical)reasons+="NARRATIVE_FACT_DRIFT:${claim.claimUid}"
                if(claim.kind==NarrativeClaimKind.MECHANICAL_RESULT&&support?.kind!=CommittedNarrativeFactKind.MECHANICAL_RESULT)reasons+="NARRATIVE_MECHANICS_DRIFT:${claim.claimUid}"
            }
            if(claim.kind==NarrativeClaimKind.BELIEF&&claim.supportFactUid?.let{facts[it]?.kind!=CommittedNarrativeFactKind.HOLDER_BELIEF}!=false)reasons+="NARRATIVE_BELIEF_WITHOUT_SUPPORT:${claim.claimUid}"
        }
        val lowercase=narrative.text.lowercase()
        context.forbiddenDisclosureTokens.filter{it.lowercase() in lowercase}.forEach{reasons+="HIDDEN_DISCLOSURE_TOKEN"}
        val internalPatterns=listOf(Regex("RPGOS-[A-Z0-9:_-]+"),Regex("(?:EVENT|PROOF|TX|RECEIPT):[A-Za-z0-9:_-]+"))
        if(internalPatterns.any{it.containsMatchIn(narrative.text)})reasons+="INTERNAL_PROVENANCE_DISCLOSURE"
        return NarrativeValidationResult(reasons.isEmpty(),reasons.sorted())
    }
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
        var validation=validator.validate(current,request.context);var attempts=0
        while(!validation.accepted&&attempts<policy.maximumAttempts&&!cancellation.isCancelled()){
            val repaired=provider.repairNarrative(AiNarrativeRepairRequest("${request.requestUid}:REPAIR:${attempts+1}",request,current,validation.reasonUids,attempts+1),cancellation)
            if(repaired is AiProviderResult.Failure)return fallback(request.context,"NARRATIVE_REPAIR_FAILURE:${repaired.reasonUid}",attempts)
            current=(repaired as AiProviderResult.Success).value;attempts++;validation=validator.validate(current,request.context)
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
        val parts=file.readLines(Charsets.UTF_8);if(parts.size<9)return null
        val narrative=RenderedNarrative(unescape(parts[8]),unescape(parts[7]),identity.committedOrder)
        return NarrativeDeliveryReceipt(unescape(parts[0]),identity,unescape(parts[1]),narrative,unescape(parts[2]),unescape(parts[3]),unescape(parts[4]))
    }
    @Synchronized override fun record(receipt:NarrativeDeliveryReceipt):NarrativeDeliveryReceipt{
        find(receipt.identity)?.let{existing->require(existing.narrativeFingerprint==receipt.narrativeFingerprint){"RPGOS-P54:DELIVERY_IDEMPOTENCY_CONFLICT"};return existing}
        val target=file(receipt.identity);val staging=File(directory,".${target.name}.${System.nanoTime()}.partial")
        val values=listOf(receipt.deliveryUid,receipt.contextFingerprint,receipt.providerUid,receipt.modelUid,receipt.narrativeFingerprint,
            receipt.identity.localeUid,receipt.identity.committedOrder.toString(),receipt.narrative.stopReasonUid,receipt.narrative.text)
        staging.writeText(values.joinToString("\n"){escape(it)},Charsets.UTF_8)
        if(!staging.renameTo(target)){staging.copyTo(target,true);staging.delete()}
        return receipt
    }
    private fun file(identity:NarrativeDeliveryIdentity)=File(directory,sha256("${identity.transactionUid}|${identity.committedOrder}|${identity.localeUid}")+".delivery")
    private fun escape(value:String)=java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun unescape(value:String)=String(java.util.Base64.getDecoder().decode(value),Charsets.UTF_8)
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

internal fun narrativeFingerprint(narrative:RenderedNarrative)=MessageDigest.getInstance("SHA-256")
    .digest("${narrative.committedOrder}|${narrative.stopReasonUid}|${narrative.text}|${narrative.claims}".toByteArray()).joinToString(""){"%02x".format(it)}
