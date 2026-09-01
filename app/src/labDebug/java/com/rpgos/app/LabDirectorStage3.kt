package com.rpgos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal class LabPersistentDirectorJobStore(private val root:File):DirectorJobStore{
    private val lock=Any()
    init{root.mkdirs()}
    override fun reserve(record:DirectorJobRecord):Boolean=synchronized(lock){
        val target=file(record.campaignUid,record.jobUid,"job")
        if(target.isFile)return@synchronized false
        writeAtomic(target,jobJson(record));true
    }
    override fun transition(record:DirectorJobRecord)=synchronized(lock){
        val existing=find(record.jobUid);require(existing?.campaignUid==record.campaignUid){"RPGOS-P65:DIRECTOR_JOB_CAMPAIGN_MISMATCH"}
        writeAtomic(file(record.campaignUid,record.jobUid,"job"),jobJson(record))
    }
    override fun find(jobUid:String):DirectorJobRecord?=synchronized(lock){
        root.listFiles().orEmpty().asSequence().filter(File::isDirectory).flatMap{it.listFiles().orEmpty().asSequence()}
            .firstOrNull{it.name=="${stage3Sha256(jobUid)}.job.json"}?.let(::readJob)
    }
    override fun lastAcceptedOrder(campaignUid:String):Long?=list(campaignUid)
        .filter{it.state==DirectorJobState.ACCEPTED}.maxOfOrNull{it.atCommittedOrder}
    fun list(campaignUid:String):List<DirectorJobRecord> = synchronized(lock){
        campaignDirectory(campaignUid).listFiles{file->file.name.endsWith(".job.json")}.orEmpty().mapNotNull(::readJob)
            .sortedWith(compareByDescending<DirectorJobRecord>{it.atCommittedOrder}.thenBy{it.jobUid})
    }
    /**
     * RESERVED/RUNNING records cannot have a live worker after a new application process starts.
     * Close them explicitly so a reconnect can schedule fresh work with current provider routing.
     */
    fun recoverAbandoned(campaignUid:String):Int=synchronized(lock){
        val abandoned=list(campaignUid).filter{it.state==DirectorJobState.RESERVED||it.state==DirectorJobState.RUNNING}
        abandoned.forEach{record->
            writeAtomic(file(record.campaignUid,record.jobUid,"job"),jobJson(record.copy(
                state=DirectorJobState.FAILED,
                terminalReasonUid="DIRECTOR_PROCESS_RESTARTED"
            )))
        }
        abandoned.size
    }
    fun clear(campaignUid:String):Boolean=synchronized(lock){
        val dir=campaignDirectory(campaignUid);!dir.exists()||dir.deleteRecursively()
    }
    private fun campaignDirectory(campaignUid:String)=File(root,stage3Sha256(campaignUid)).apply{mkdirs()}
    private fun file(campaignUid:String,uid:String,kind:String)=File(campaignDirectory(campaignUid),"${stage3Sha256(uid)}.$kind.json")
    private fun readJob(file:File)=runCatching{JSONObject(file.readText()).let{value->DirectorJobRecord(
        value.getString("job_uid"),value.getString("campaign_uid"),value.getString("trigger_uid"),value.getString("context_version"),
        value.getLong("at_committed_order"),DirectorJobState.valueOf(value.getString("state")),value.optNullableString("provider_uid"),
        value.optNullableString("model_uid"),value.optNullableString("terminal_reason_uid")
    )}}.getOrNull()
}

internal class LabPersistentDirectorCandidateStore(private val root:File):DirectorCandidateStore{
    private val lock=Any()
    init{root.mkdirs()}
    override fun put(bundle:DirectorBundle)=synchronized(lock){
        val target=file(bundle.campaignUid,bundle.bundleUid)
        if(target.isFile){require(readBundle(target)==bundle){"RPGOS-P65:DIRECTOR_BUNDLE_IDENTITY_CONFLICT"};return@synchronized}
        writeAtomic(target,bundleJson(bundle))
    }
    override fun latest(campaignUid:String):DirectorBundle?=list(campaignUid).maxByOrNull{it.asOfCommittedOrder}
    fun list(campaignUid:String):List<DirectorBundle> = synchronized(lock){
        campaignDirectory(campaignUid).listFiles{file->file.name.endsWith(".bundle.json")}.orEmpty().mapNotNull(::readBundle)
            .sortedWith(compareByDescending<DirectorBundle>{it.asOfCommittedOrder}.thenBy{it.bundleUid})
    }
    fun clear(campaignUid:String):Boolean=synchronized(lock){
        val dir=campaignDirectory(campaignUid);!dir.exists()||dir.deleteRecursively()
    }
    private fun campaignDirectory(campaignUid:String)=File(root,stage3Sha256(campaignUid)).apply{mkdirs()}
    private fun file(campaignUid:String,uid:String)=File(campaignDirectory(campaignUid),"${stage3Sha256(uid)}.bundle.json")
    private fun readBundle(file:File)=runCatching{decodeBundle(JSONObject(file.readText()))}.getOrNull()
}

internal class LabDirectorCoordinator(context:Context):DirectorGuidancePort,DirectorSchedulerPort,DirectorContextProjectionPort{
    private val app=context.applicationContext
    private val executor=Executors.newSingleThreadExecutor{r->Thread(r,"rpgos-lab-director").apply{isDaemon=true}}
    private val repository by lazy{UnifiedGameRepository(app).also{it.bootstrap()}}
    private val semantic by lazy{BekkoSemanticApplication(app,repository)}
    private val jobs=LabPersistentDirectorJobStore(File(app.filesDir,"lab-director/jobs"))
    private val candidates=LabPersistentDirectorCandidateStore(File(app.filesDir,"lab-director/candidates"))
    private val recoveredCampaigns=ConcurrentHashMap.newKeySet<String>()
    private val providerCenter by lazy{AndroidAiProviderCenterApplication(app,LabCodexProviderRuntime.trace)}
    private val settings by lazy{AppSettings(app)}
    private val versionPort=DirectorContextVersionPort{campaignUid->contextVersion(campaignUid)}
    private val engine by lazy{
        ProductionGameEngineCompositionRoot(
            app,repository,providerCenter,{AiProviderExtensionRegistry.configuration(settings.load().ai)},
            AiProviderExtensionRegistry::providers,semanticApplication=semantic
        ).directorEngine(jobs,candidates,DirectorJobDispatcher{_,work->executor.execute(work)},versionPort)
    }
    @Volatile private var lastScheduleReasonUid:String="DIRECTOR_NOT_SCHEDULED"

    override fun onCampaignOpened(campaignUid:String){executor.execute{
        runCatching{
            val recovered=if(recoveredCampaigns.add(campaignUid))jobs.recoverAbandoned(campaignUid) else 0
            if(recovered>0)lastScheduleReasonUid="DIRECTOR_RECOVERED_ABANDONED:$recovered"
            if(repository.activePlayerRef()==null)lastScheduleReasonUid="DIRECTOR_WAITING_FOR_CHARACTER"
            else if(candidates.latest(campaignUid)==null)schedule(campaignUid,DirectorTriggerKind.SEMANTIC_EVENT,"CAMPAIGN_OPENED",stable=true)
        }.onFailure{lastScheduleReasonUid=it.message?:"DIRECTOR_CAMPAIGN_OPEN_FAILED"}
    }}
    fun onHostReady(){executor.execute{
        runCatching{
            repository.activeCampaignRef().campaignId.let{campaign->
                val recovered=if(recoveredCampaigns.add(campaign))jobs.recoverAbandoned(campaign) else 0
                if(recovered>0)lastScheduleReasonUid="DIRECTOR_RECOVERED_ABANDONED:$recovered"
                if(repository.activePlayerRef()==null)lastScheduleReasonUid="DIRECTOR_WAITING_FOR_CHARACTER"
                else if(candidates.latest(campaign)==null)schedule(campaign,DirectorTriggerKind.SEMANTIC_EVENT,"HOST_READY",stable=true)
            }
        }.onFailure{lastScheduleReasonUid=it.message?:"DIRECTOR_HOST_READY_FAILED"}
    }}
    override fun onCanonicalCommit(receipt:TurnCommitReceipt){executor.execute{
        runCatching{schedule(receipt.campaignUid,DirectorTriggerKind.CADENCE,"COMMIT:${receipt.commitOrder?:0L}",stable=true)}
            .onFailure{lastScheduleReasonUid=it.message?:"DIRECTOR_POST_COMMIT_FAILED"}
    }}
    override fun onCharacterCreated(campaignUid:String,playerUid:String){executor.execute{
        runCatching{
            require(repository.activePlayerRef()?.playerUid==playerUid){"DIRECTOR_CHARACTER_NOT_ACTIVE"}
            // The sidecar is rebuildable. A campaign-open bundle may have been produced by an
            // older lab build before character confirmation changed the era, player and starting
            // scene at the same committed order. Never let that obsolete advice win a same-order
            // tie: confirmation is the authoritative boundary for a fresh Director context.
            jobs.clear(campaignUid);candidates.clear(campaignUid)
            schedule(campaignUid,DirectorTriggerKind.SEMANTIC_EVENT,"CHARACTER_CREATED:$playerUid",stable=true)
        }
            .onFailure{lastScheduleReasonUid=it.message?:"DIRECTOR_CHARACTER_TRIGGER_FAILED"}
    }}
    fun runNow(arguments:JSONObject):JSONObject{
        val campaign=arguments.optString("campaign_uid").trim().ifBlank{repository.activeCampaignRef().campaignId}
        val result=runNow(campaign,arguments.optString("reason_uid","MANUAL_LAB_TRIGGER").trim().ifBlank{"MANUAL_LAB_TRIGGER"})
        return dispatchJson(result)
    }
    override fun runNow(campaignUid:String,reasonUid:String):DirectorDispatchResult=
        schedule(campaignUid,DirectorTriggerKind.SEMANTIC_EVENT,reasonUid,stable=false)

    override fun guidance(campaignUid:String,asOfOrder:Long,authorizedRecordUids:Set<String>):DirectorGuidanceEnvelope?{
        val bundle=candidates.latest(campaignUid)?:return null
        if(bundle.asOfCommittedOrder>asOfOrder||asOfOrder-bundle.asOfCommittedOrder>16L)return null
        val eligible=bundle.candidates.asSequence().filter{it.directMutationPayload==null}
            .filter{candidate->candidate.supportingProjectedRecordUids.isEmpty()||candidate.supportingProjectedRecordUids.all{it in authorizedRecordUids}}
            .sortedBy{it.candidateUid}.toList()
        var usedUnits=0
        val admitted=eligible.takeWhile{candidate->
            val units=(candidate.title.length+candidate.summary.length+candidate.supportingProjectedRecordUids.sumOf(String::length)+candidate.pacingTags.sumOf(String::length))/4+8
            (usedUnits+units<=1_024).also{accepted->if(accepted)usedUnits+=units}
        }.take(8)
        if(admitted.isEmpty())return null
        return DirectorGuidanceEnvelope(campaignUid,bundle.bundleUid,bundle.contextVersion,bundle.asOfCommittedOrder,admitted)
    }

    fun state():JSONObject{
        val campaign=runCatching{repository.activeCampaignRef().campaignId}.getOrNull()
        val currentJobs=campaign?.let(jobs::list).orEmpty();val currentBundles=campaign?.let(candidates::list).orEmpty()
        val latestBundle=currentBundles.firstOrNull()
        val displayedJob=latestBundle?.let{bundle->currentJobs.firstOrNull{it.jobUid==bundle.jobUid}}?:currentJobs.firstOrNull()
        return JSONObject().put("phase_uid","PHASE65_DIRECTOR").put("automatic_scheduler_wired",true)
            .put("direct_mutation_allowed",false).put("campaign_uid",campaign?:JSONObject.NULL)
            .put("last_schedule_reason_uid",lastScheduleReasonUid)
            .put("job_count",currentJobs.size).put("candidate_bundle_count",currentBundles.size)
            .put("last_job",displayedJob?.let(::jobJson)?:JSONObject.NULL)
            .put("last_bundle",latestBundle?.let(::bundleJson)?:JSONObject.NULL)
    }
    fun jobsJson():JSONObject{
        val campaign=repository.activeCampaignRef().campaignId
        return JSONObject().put("campaign_uid",campaign).put("jobs",JSONArray(jobs.list(campaign).map(::jobJson)))
    }
    fun candidatesJson():JSONObject{
        val campaign=repository.activeCampaignRef().campaignId
        return JSONObject().put("campaign_uid",campaign).put("bundles",JSONArray(candidates.list(campaign).map(::bundleJson)))
    }
    fun guidanceJson(arguments:JSONObject):JSONObject{
        val campaign=repository.activeCampaignRef().campaignId
        val authorized=arguments.optJSONArray("authorized_record_uids")?.stringsLab()?.toSet().orEmpty()
        val value=guidance(campaign,arguments.optLong("as_of_order",repository.infrastructureLastCommitOrder()),authorized)
        return JSONObject().put("available",value!=null).put("guidance",value?.let(::guidanceJson)?:JSONObject.NULL)
    }
    fun clear():JSONObject{
        val campaign=repository.activeCampaignRef().campaignId
        val jobsCleared=jobs.clear(campaign);val candidatesCleared=candidates.clear(campaign)
        return JSONObject().put("campaign_uid",campaign).put("jobs_cleared",jobsCleared).put("candidates_cleared",candidatesCleared)
    }

    private fun schedule(campaignUid:String,kind:DirectorTriggerKind,reasonUid:String,stable:Boolean):DirectorDispatchResult{
        require(repository.activeCampaignRef().campaignId==campaignUid){"DIRECTOR_CROSS_CAMPAIGN"}
        semantic.catchUp()
        val order=repository.infrastructureLastCommitOrder()
        val suffix=if(stable)stage3Sha256("$campaignUid|$kind|$reasonUid|$order").take(24) else UUID.randomUUID().toString()
        val trigger=DirectorTrigger("DIRECTOR-TRIGGER:$suffix",campaignUid,kind,order,
            semanticEvidenceUids=listOf(reasonUid.take(240)))
        val context=project(trigger)
        val result=engine.schedule(trigger,context)
        lastScheduleReasonUid=when(result){is DirectorDispatchResult.Scheduled->"DIRECTOR_SCHEDULED:${result.jobUid}";is DirectorDispatchResult.Skipped->result.reasonUid}
        return result
    }
    override fun project(trigger:DirectorTrigger):DirectorContextEnvelope{
        val audience=AudienceContext(trigger.campaignUid,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LAB_DIRECTOR"))
        val purpose=PurposeContext(trigger.campaignUid,VisibilityPurposeKinds.INTERNAL_SIMULATION)
        val trusted=Phase38RuntimeAuthority.privileged(audience,Phase38RuntimeAuthority.PRIV_GM)
        val bundle=repository.infrastructureBuildTrustedContext(
            "DIRECTOR_STRATEGY",(trigger.atCommittedOrder+1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),audience,purpose,trusted
        )
        val json=JsonCodec.contextToJson(bundle)
        val projected=linkedSetOf<String>();collectProjectedUids(json,projected)
        val segments=json.keys().asSequence().toList().sorted().mapNotNull{key->
            val value=json.opt(key);if(value==null||value==JSONObject.NULL)return@mapNotNull null
            "$key=${value.toString().take(2_048)}".takeIf{it.substringAfter('=').isNotBlank()}
        }.take(24)
        val projectionUid="${bundle.visibilityEnvelope.authorityUid}:${bundle.visibilityEnvelope.projectionVersionUid}:${stage3Sha256(json.toString()).take(24)}"
        return DirectorContextEnvelope(trigger.campaignUid,contextVersion(trigger.campaignUid),trigger.atCommittedOrder,projected,segments,emptySet(),projectionUid)
    }
    private fun contextVersion(campaignUid:String):String{
        require(repository.activeCampaignRef().campaignId==campaignUid){"DIRECTOR_CROSS_CAMPAIGN"}
        val receipt=repository.infrastructureLastReceipt();val order=repository.infrastructureLastCommitOrder()
        return "DIRECTOR-CONTEXT:$order:${receipt?.resultFingerprint?:receipt?.semanticFingerprint?:"NO-RECEIPT"}"
    }
}

private fun collectProjectedUids(value:Any?,target:MutableSet<String>,key:String?=null){
    when(value){
        is JSONObject->value.keys().forEach{child->collectProjectedUids(value.opt(child),target,child)}
        is JSONArray->for(index in 0 until value.length())collectProjectedUids(value.opt(index),target,key)
        is String->if(key?.endsWith("uid",ignoreCase=true)==true&&value.isNotBlank())target+=value
    }
}

private fun dispatchJson(value:DirectorDispatchResult)=when(value){
    is DirectorDispatchResult.Scheduled->JSONObject().put("state","SCHEDULED").put("job_uid",value.jobUid)
    is DirectorDispatchResult.Skipped->JSONObject().put("state","SKIPPED").put("reason_uid",value.reasonUid)
}
private fun jobJson(value:DirectorJobRecord)=JSONObject()
    .put("job_uid",value.jobUid).put("campaign_uid",value.campaignUid).put("trigger_uid",value.triggerUid)
    .put("context_version",value.contextVersion).put("at_committed_order",value.atCommittedOrder).put("state",value.state.name)
    .put("provider_uid",value.providerUid?:JSONObject.NULL).put("model_uid",value.modelUid?:JSONObject.NULL)
    .put("terminal_reason_uid",value.terminalReasonUid?:JSONObject.NULL)
private fun bundleJson(value:DirectorBundle)=JSONObject()
    .put("schema_version",value.schemaVersion).put("bundle_uid",value.bundleUid).put("job_uid",value.jobUid)
    .put("campaign_uid",value.campaignUid).put("trigger_uid",value.triggerUid).put("context_version",value.contextVersion)
    .put("as_of_committed_order",value.asOfCommittedOrder).put("provider_uid",value.providerUid).put("model_uid",value.modelUid)
    .put("created_against_fingerprint",value.createdAgainstFingerprint)
    .put("candidates",JSONArray(value.candidates.map{candidate->JSONObject()
        .put("candidate_uid",candidate.candidateUid).put("kind",candidate.kind.name).put("title",candidate.title).put("summary",candidate.summary)
        .put("supporting_projected_record_uids",JSONArray(candidate.supportingProjectedRecordUids)).put("horizon_uid",candidate.horizonUid)
        .put("pacing_tags",JSONArray(candidate.pacingTags.sorted())).put("proposed_owner_phase_uid",candidate.proposedOwnerPhaseUid)
        .put("direct_mutation_payload",candidate.directMutationPayload?:JSONObject.NULL)}))
private fun decodeBundle(value:JSONObject)=DirectorBundle(
    value.getInt("schema_version"),value.getString("bundle_uid"),value.getString("job_uid"),value.getString("campaign_uid"),
    value.getString("trigger_uid"),value.getString("context_version"),value.getLong("as_of_committed_order"),
    value.getString("provider_uid"),value.getString("model_uid"),value.getJSONArray("candidates").objectsLab().map{candidate->DirectorCandidate(
        candidate.getString("candidate_uid"),DirectorCandidateKind.valueOf(candidate.getString("kind")),candidate.getString("title"),candidate.getString("summary"),
        candidate.getJSONArray("supporting_projected_record_uids").stringsLab(),candidate.getString("horizon_uid"),
        candidate.getJSONArray("pacing_tags").stringsLab().toSet(),candidate.getString("proposed_owner_phase_uid"),candidate.optNullableString("direct_mutation_payload")
    )},value.getString("created_against_fingerprint")
)
private fun guidanceJson(value:DirectorGuidanceEnvelope)=JSONObject()
    .put("campaign_uid",value.campaignUid).put("bundle_uid",value.bundleUid).put("context_version",value.contextVersion)
    .put("as_of_committed_order",value.asOfCommittedOrder).put("candidates",JSONArray(value.candidates.map{it.candidateUid}))
private fun writeAtomic(target:File,value:JSONObject){
    target.parentFile?.mkdirs();val staging=File(target.parentFile,".${target.name}.${System.nanoTime()}.partial")
    staging.writeText(value.toString());require(staging.renameTo(target)||run{target.delete();staging.renameTo(target)}){"LAB_DIRECTOR_ATOMIC_WRITE_FAILED"}
}
private fun JSONObject.optNullableString(key:String)=if(isNull(key))null else optString(key).takeIf(String::isNotBlank)
private fun JSONArray.stringsLab()=(0 until length()).map{getString(it)}
private fun JSONArray.objectsLab()=(0 until length()).map{getJSONObject(it)}
internal fun stage3Sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
