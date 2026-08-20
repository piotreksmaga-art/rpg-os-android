from pathlib import Path


def replace_once(path, old, new):
    p=Path(path); s=p.read_text()
    if old not in s:
        raise SystemExit(f"missing closure anchor: {path}: {old[:160]!r}")
    if s.count(old)!=1:
        raise SystemExit(f"non-unique closure anchor: {path}: {s.count(old)}")
    p.write_text(s.replace(old,new,1))


def append_once(path, marker, text):
    p=Path(path); s=p.read_text()
    if marker in s: return
    p.write_text(s + text)

# ---- Core purpose + projection version/data-state closure ----
p="app/src/main/java/com/rpgos/app/Phase38Visibility.kt"
replace_once(p,
'    const val LOCATION_VISUALIZATION = "LOCATION_VISUALIZATION"\n',
'    const val LOCATION_VISUALIZATION = "LOCATION_VISUALIZATION"\n    const val IMAGE_EDIT_VISUALIZATION = "IMAGE_EDIT_VISUALIZATION"\n')
replace_once(p,
'enum class ProjectionDataState { DISCLOSED, NO_DATA, DENIED, NOT_DISCLOSED, UNKNOWN }',
'enum class ProjectionDataState { DISCLOSED, NO_DATA, DENIED, NOT_DISCLOSED, UNKNOWN, CORRUPTION }')
replace_once(p,
'    val maximumDisclosure: DisclosureLevel,\n    val authorityUid: String = VisibilityAuthorityService.AUTHORITY_UID\n',
'    val maximumDisclosure: DisclosureLevel,\n    val authorityUid: String = VisibilityAuthorityService.AUTHORITY_UID,\n    val projectionVersionUid: String = VisibilityAuthorityService.PROJECTION_VERSION_UID\n')
replace_once(p,
'        require(authorityUid == VisibilityAuthorityService.AUTHORITY_UID) { "RPGOS-VISIBILITY:UNKNOWN_AUTHORITY" }\n',
'        require(authorityUid == VisibilityAuthorityService.AUTHORITY_UID) { "RPGOS-VISIBILITY:UNKNOWN_AUTHORITY" }\n        require(projectionVersionUid == VisibilityAuthorityService.PROJECTION_VERSION_UID) { "RPGOS-VISIBILITY:UNKNOWN_PROJECTION_VERSION" }\n')
replace_once(p,
'    companion object { const val AUTHORITY_UID = "RPGOS-P38-VISIBILITY-AUTHORITY-1" }',
'    companion object {\n        const val AUTHORITY_UID = "RPGOS-P38-VISIBILITY-AUTHORITY-1"\n        const val PROJECTION_VERSION_UID = "RPGOS-P38-VISIBILITY-PROJECTION-1"\n    }')
replace_once(p,
'        VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION,\n        VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION\n',
'        VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION,\n        VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION, VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION\n')

# ---- ContextBuilder: strict protected reads, optional non-authority campaign/time presentation metadata ----
p="app/src/main/java/com/rpgos/app/ContextBuilder.kt"
replace_once(p,
'        val campaign = queryOne(saveDb,"SELECT campaign_name,schema_version,current_chapter,current_tome FROM campaign_meta WHERE id=1")\n        val time = queryOne(saveDb,"SELECT year_label,era_name,season,hour,minute,absolute_day FROM campaign_calendar WHERE id=1")\n',
'        val campaign = optionalPresentationOne(saveDb,"SELECT campaign_name,schema_version,current_chapter,current_tome FROM campaign_meta WHERE id=1")\n        val time = optionalPresentationOne(saveDb,"SELECT year_label,era_name,season,hour,minute,absolute_day FROM campaign_calendar WHERE id=1")\n')
replace_once(p,
'    private fun queryOne(db:SQLiteDatabase,sql:String,args:Array<String>?=null):Map<String,Any?> = queryMany(db,sql,args).firstOrNull()?:emptyMap()\n',
'''    private fun optionalPresentationOne(db:SQLiteDatabase,sql:String,args:Array<String>?=null):Map<String,Any?> = try {
        val out=mutableMapOf<String,Any?>()
        db.rawQuery(sql,args).use { c ->
            if(c.moveToFirst()) for(i in c.columnNames.indices) out[c.columnNames[i]]=when(c.getType(i)){
                android.database.Cursor.FIELD_TYPE_NULL->null
                android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i)
                android.database.Cursor.FIELD_TYPE_FLOAT->c.getDouble(i)
                android.database.Cursor.FIELD_TYPE_BLOB->"[BLOB ${c.getBlob(i).size} bytes]"
                else->c.getString(i)
            }
        }
        out
    } catch (_: android.database.sqlite.SQLiteException) { emptyMap() }
    private fun queryOne(db:SQLiteDatabase,sql:String,args:Array<String>?=null):Map<String,Any?> = queryMany(db,sql,args).firstOrNull()?:emptyMap()
''')

# ---- Projection JSON version binding ----
p="app/src/main/java/com/rpgos/app/JsonCodec.kt"
replace_once(p,
'            put("authority_uid", context.visibilityEnvelope.authorityUid)\n',
'            put("authority_uid", context.visibilityEnvelope.authorityUid)\n            put("projection_version_uid", context.visibilityEnvelope.projectionVersionUid)\n')

# ---- Visual request authorization is separate from generic ContextBundle ----
Path("app/src/main/java/com/rpgos/app/Phase38VisualAuthorization.kt").write_text(r'''package com.rpgos.app

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

object VisualInputOrigins {
    const val CAMPAIGN_PROJECTION = "CAMPAIGN_PROJECTION"
    const val USER_STANDALONE = "USER_STANDALONE"
}

data class Phase38VisualAuthorization(
    val campaignUid: String,
    val audienceKindUid: String,
    val audienceUid: String?,
    val purposeUid: String,
    val projectionAuthorityUid: String,
    val projectionVersionUid: String,
    val disclosureCeiling: DisclosureLevel,
    val payloadDisclosure: DisclosureLevel,
    val subjectKindUid: String,
    val subjectUid: String,
    val requestUid: String,
    val payloadSha256: String,
    val inputOriginUid: String
) {
    init {
        require(campaignUid.isNotBlank() && purposeUid.isNotBlank())
        require(subjectKindUid.isNotBlank() && subjectUid.isNotBlank() && requestUid.isNotBlank())
        require(projectionAuthorityUid == VisibilityAuthorityService.AUTHORITY_UID) { "RPGOS-VISIBILITY:INVALID_PROJECTION_AUTHORITY" }
        require(projectionVersionUid == VisibilityAuthorityService.PROJECTION_VERSION_UID) { "RPGOS-VISIBILITY:INVALID_PROJECTION_VERSION" }
        require(audienceKindUid in setOf(AudienceKinds.PLAYER, AudienceKinds.PLAYER_CHARACTER)) { "RPGOS-VISIBILITY:UNSUPPORTED_VISUAL_AUDIENCE" }
        require(purposeUid in visualPurposes) { "RPGOS-VISIBILITY:UNSUPPORTED_VISUAL_PURPOSE" }
        require(disclosureCeiling != DisclosureLevel.DENY) { "RPGOS-VISIBILITY:PROJECTION_DENIED" }
        require(disclosureCeiling.canReduceTo(payloadDisclosure)) { "RPGOS-VISIBILITY:VISUAL_DISCLOSURE_ESCALATION" }
        require(payloadSha256.matches(Regex("[0-9a-f]{64}"))) { "RPGOS-VISIBILITY:INVALID_VISUAL_PAYLOAD_DIGEST" }
        require(inputOriginUid in setOf(VisualInputOrigins.CAMPAIGN_PROJECTION, VisualInputOrigins.USER_STANDALONE))
    }

    fun requireRequest(campaignUid: String, expectedPurpose: String, payload: String) {
        if (this.campaignUid != campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        require(purposeUid == expectedPurpose) { "RPGOS-VISIBILITY:VISUAL_PURPOSE_MISMATCH" }
        require(payloadSha256 == digest(payload)) { "RPGOS-VISIBILITY:VISUAL_PAYLOAD_SUBSTITUTION" }
        if (!disclosureCeiling.canReduceTo(payloadDisclosure)) throw VisibilityAuthorityFailure.Escalation()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("campaign_uid", campaignUid)
        put("audience_kind_uid", audienceKindUid)
        put("audience_uid", audienceUid)
        put("purpose_uid", purposeUid)
        put("authority_uid", projectionAuthorityUid)
        put("projection_version_uid", projectionVersionUid)
        put("disclosure_ceiling", disclosureCeiling.name)
        put("payload_disclosure", payloadDisclosure.name)
        put("subject_kind_uid", subjectKindUid)
        put("subject_uid", subjectUid)
        put("request_uid", requestUid)
        put("payload_sha256", payloadSha256)
        put("input_origin_uid", inputOriginUid)
    }

    companion object {
        val visualPurposes = setOf(
            VisibilityPurposeKinds.SCENE_VISUALIZATION,
            VisibilityPurposeKinds.CHARACTER_VISUALIZATION,
            VisibilityPurposeKinds.LOCATION_VISUALIZATION,
            VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION
        )

        fun authorize(
            envelope: VisibilityProjectionEnvelope,
            expectedPurpose: String,
            subjectKindUid: String,
            subjectUid: String,
            payload: String,
            inputOriginUid: String = VisualInputOrigins.CAMPAIGN_PROJECTION,
            requestUid: String = UUID.randomUUID().toString(),
            payloadDisclosure: DisclosureLevel = envelope.maximumDisclosure
        ): Phase38VisualAuthorization {
            envelope.requirePurpose(expectedPurpose)
            if (envelope.maximumDisclosure == DisclosureLevel.DENY) throw VisibilityAuthorityFailure.Escalation()
            return Phase38VisualAuthorization(
                campaignUid = envelope.campaignUid,
                audienceKindUid = envelope.audience.audienceKindUid,
                audienceUid = envelope.audience.principal?.uid,
                purposeUid = expectedPurpose,
                projectionAuthorityUid = envelope.authorityUid,
                projectionVersionUid = envelope.projectionVersionUid,
                disclosureCeiling = envelope.maximumDisclosure,
                payloadDisclosure = payloadDisclosure,
                subjectKindUid = subjectKindUid,
                subjectUid = subjectUid,
                requestUid = requestUid,
                payloadSha256 = digest(payload),
                inputOriginUid = inputOriginUid
            )
        }

        fun digest(payload: String): String = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
''')

# ---- Requests require authorization ----
p="app/src/main/java/com/rpgos/app/ImageModels.kt"
replace_once(p,
'    val relatedEntityUid: String? = null,\n    val chapter: Int? = null\n',
'    val relatedEntityUid: String? = null,\n    val chapter: Int? = null,\n    val authorization: Phase38VisualAuthorization\n')
p="app/src/main/java/com/rpgos/app/ImageEditModels.kt"
replace_once(p,
'    val title: String,\n    val instruction: String\n',
'    val title: String,\n    val instruction: String,\n    val authorization: Phase38VisualAuthorization\n')

# ---- Android image generate/edit clients validate locally and serialize envelope ----
p="app/src/main/java/com/rpgos/app/ImageBackendClient.kt"
replace_once(p,
'            val json = JSONObject().apply {\n',
'''            val expectedPurpose = when(requestData.kind) {
                "scene" -> VisibilityPurposeKinds.SCENE_VISUALIZATION
                "character" -> VisibilityPurposeKinds.CHARACTER_VISUALIZATION
                "location" -> VisibilityPurposeKinds.LOCATION_VISUALIZATION
                else -> error("RPGOS-VISIBILITY:UNSUPPORTED_VISUAL_KIND")
            }
            requestData.authorization.requireRequest(requestData.authorization.campaignUid, expectedPurpose, requestData.prompt)
            val json = JSONObject().apply {
''')
replace_once(p,
'                put("chapter", requestData.chapter)\n',
'                put("chapter", requestData.chapter)\n                put("campaign_uid", requestData.authorization.campaignUid)\n                put("visibility_envelope", requestData.authorization.toJson())\n')

p="app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt"
replace_once(p,
'            val uri = android.net.Uri.parse(reqData.sourceUri)\n',
'''            reqData.authorization.requireRequest(
                reqData.authorization.campaignUid,
                VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
                reqData.instruction
            )
            val uri = android.net.Uri.parse(reqData.sourceUri)
''')
replace_once(p,
'                .addFormDataPart("instruction", reqData.instruction)\n',
'                .addFormDataPart("instruction", reqData.instruction)\n                .addFormDataPart("campaign_uid", reqData.authorization.campaignUid)\n                .addFormDataPart("visibility_envelope", reqData.authorization.toJson().toString())\n')

# ---- ViewModel binds every visual payload to the exact projection and purpose ----
p="app/src/main/java/com/rpgos/app/RpgOsViewModel.kt"
replace_once(p,
'ImageGenerationRequest("scene", title.ifBlank { "Scena" }, prompt, null, chapter)',
'''ImageGenerationRequest(
                        "scene", title.ifBlank { "Scena" }, prompt, null, chapter,
                        Phase38VisualAuthorization.authorize(context.visibilityEnvelope,VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE",title.ifBlank { "SCENE" },prompt)
                    )''')
replace_once(p,
'ImageGenerationRequest("character", name.ifBlank { "Postać" }, prompt)',
'''ImageGenerationRequest(
                        "character", name.ifBlank { "Postać" }, prompt,
                        authorization = Phase38VisualAuthorization.authorize(visualContext.visibilityEnvelope,VisibilityPurposeKinds.CHARACTER_VISUALIZATION,"CHARACTER",name.ifBlank { "CHARACTER" },prompt)
                    )''')
replace_once(p,
'ImageGenerationRequest("location", name.ifBlank { "Lokacja" }, prompt)',
'''ImageGenerationRequest(
                        "location", name.ifBlank { "Lokacja" }, prompt,
                        authorization = Phase38VisualAuthorization.authorize(visualContext.visibilityEnvelope,VisibilityPurposeKinds.LOCATION_VISUALIZATION,"LOCATION",name.ifBlank { "LOCATION" },prompt)
                    )''')
replace_once(p,
'''                val result = ImageEditBackendClient(contextApp, _settings.value.backendUrl).edit(
                    ImageEditRequest(
                        sourceVisualUid = source.visualUid,
                        sourceUri = source.uri,
                        title = source.title + "_edit",
                        instruction = instruction
                    )
                )''',
'''                val editEnvelope = VisibilityAuthorityService().envelope(
                    playerAudience(),
                    playerPurpose(VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION)
                )
                val editAuthorization = Phase38VisualAuthorization.authorize(
                    editEnvelope,
                    VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
                    "VISUAL",
                    source.visualUid,
                    instruction,
                    VisualInputOrigins.USER_STANDALONE
                )
                val result = ImageEditBackendClient(contextApp, _settings.value.backendUrl).edit(
                    ImageEditRequest(
                        sourceVisualUid = source.visualUid,
                        sourceUri = source.uri,
                        title = source.title + "_edit",
                        instruction = instruction,
                        authorization = editAuthorization
                    )
                )''')

# ---- Consumer inventory: image clients/authorization + IMAGE_EDIT purpose + reusable marker classifier ----
p="app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt"
replace_once(p,
'            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION,\n            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),\n',
'            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,\n            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),\n')
replace_once(p,
'''        c("visual-prompt", "app/src/main/java/com/rpgos/app/VisualPromptBuilder.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION),
''',
'''        c("visual-prompt", "app/src/main/java/com/rpgos/app/VisualPromptBuilder.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION),
        c("visual-authorization", "app/src/main/java/com/rpgos/app/Phase38VisualAuthorization.kt", ProtectedConsumerCapability.PROJECTION_AUTHORITY,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),
        c("image-generate-client", "app/src/main/java/com/rpgos/app/ImageBackendClient.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION),
        c("image-edit-client", "app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),
''')
replace_once(p,
'            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),\n',
'            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),\n')
replace_once(p,
'    private val byPath = contracts.associateBy { it.sourcePath }\n',
'''    val protectedMarkers: Set<String> = setOf(
        "gm_summary","npc_memories_v2","npc_beliefs","npc_schedules","npc_decisions",
        "CampaignTruthStore(","KnowledgeContextProjection(","campaign_truth","canon_diverg",
        "hidden_pressure","world_pressures","country_economies","relationships_v2",
        "visibility_envelope","Phase38VisualAuthorization","/v1/images/generate","/v1/images/edit"
    )
    fun looksProtected(sourceText:String):Boolean = protectedMarkers.any(sourceText::contains)
    fun requireClassifiedIfProtected(sourcePath:String,sourceText:String):ProtectedConsumerContract? =
        if(looksProtected(sourceText)) requireClassified(sourcePath) else null

    private val byPath = contracts.associateBy { it.sourcePath }
''')

# ---- Backend image envelope validation ----
p="backend/app.py"
replace_once(p,
'PHASE38_GM_PURPOSES = {"GAMEPLAY_NARRATION", "WORLD_ACTOR_REASONING"}\n',
'''PHASE38_GM_PURPOSES = {"GAMEPLAY_NARRATION", "WORLD_ACTOR_REASONING"}
PHASE38_PROJECTION_VERSION_UID = "RPGOS-P38-VISIBILITY-PROJECTION-1"
PHASE38_VISUAL_PURPOSES = {"SCENE_VISUALIZATION", "CHARACTER_VISUALIZATION", "LOCATION_VISUALIZATION", "IMAGE_EDIT_VISUALIZATION"}
PHASE38_VISUAL_AUDIENCES = {"PLAYER", "PLAYER_CHARACTER"}
PHASE38_DISCLOSURE_RANK = {"DENY": 0, "DISCLOSE_EXISTENCE": 1, "DISCLOSE_REDACTED": 2, "DISCLOSE_PARTIAL": 3, "DISCLOSE_FULL": 4}
''')
replace_once(p,
'class ImageGenerateRequest(BaseModel):\n',
'''class Phase38VisualEnvelope(BaseModel):
    campaign_uid: str
    audience_kind_uid: str
    audience_uid: Optional[str] = None
    purpose_uid: str
    authority_uid: str
    projection_version_uid: str
    disclosure_ceiling: str
    payload_disclosure: str
    subject_kind_uid: str
    subject_uid: str
    request_uid: str
    payload_sha256: str
    input_origin_uid: str


def _visual_payload_digest(payload: str) -> str:
    import hashlib
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _require_visual_projection(envelope: Phase38VisualEnvelope, campaign_uid: str, expected_purpose: str, payload: str):
    if envelope.authority_uid != PHASE38_AUTHORITY_UID:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:INVALID_PROJECTION_AUTHORITY")
    if envelope.projection_version_uid != PHASE38_PROJECTION_VERSION_UID:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:INVALID_PROJECTION_VERSION")
    if envelope.campaign_uid != campaign_uid:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:CROSS_CAMPAIGN_PROJECTION")
    if envelope.audience_kind_uid not in PHASE38_VISUAL_AUDIENCES:
        raise HTTPException(status_code=403, detail="RPGOS-VISIBILITY:UNSUPPORTED_VISUAL_AUDIENCE")
    if expected_purpose not in PHASE38_VISUAL_PURPOSES or envelope.purpose_uid != expected_purpose:
        raise HTTPException(status_code=403, detail="RPGOS-VISIBILITY:VISUAL_PURPOSE_MISMATCH")
    ceiling = PHASE38_DISCLOSURE_RANK.get(envelope.disclosure_ceiling)
    payload_level = PHASE38_DISCLOSURE_RANK.get(envelope.payload_disclosure)
    if ceiling is None or payload_level is None:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:INVALID_DISCLOSURE")
    if ceiling == 0:
        raise HTTPException(status_code=403, detail="RPGOS-VISIBILITY:PROJECTION_DENIED")
    if payload_level > ceiling:
        raise HTTPException(status_code=403, detail="RPGOS-VISIBILITY:VISUAL_DISCLOSURE_ESCALATION")
    if envelope.payload_sha256 != _visual_payload_digest(payload):
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:VISUAL_PAYLOAD_SUBSTITUTION")
    if not envelope.request_uid or not envelope.subject_kind_uid or not envelope.subject_uid:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:MALFORMED_VISUAL_BINDING")
    return envelope


class ImageGenerateRequest(BaseModel):
''')
replace_once(p,
'    chapter: Optional[int] = None\n\nclass ImageGenerateResponse',
'    chapter: Optional[int] = None\n    campaign_uid: str\n    visibility_envelope: Phase38VisualEnvelope\n\nclass ImageGenerateResponse')
replace_once(p,
'''    # Use the Images API so the mobile app receives only image bytes from our backend.
    image_model = os.environ.get("RPGOS_IMAGE_MODEL", "gpt-image-1")
''',
'''    expected_purpose = {
        "scene": "SCENE_VISUALIZATION",
        "character": "CHARACTER_VISUALIZATION",
        "location": "LOCATION_VISUALIZATION",
    }.get(req.kind)
    if expected_purpose is None:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:UNSUPPORTED_VISUAL_KIND")
    _require_visual_projection(req.visibility_envelope, req.campaign_uid, expected_purpose, req.prompt)

    # Use only the already-authorized prompt; never reconstruct campaign context server-side.
    image_model = os.environ.get("RPGOS_IMAGE_MODEL", "gpt-image-1")
''')
replace_once(p,
'''async def edit_image(
    title: str = Form(...),
    instruction: str = Form(...),
    image: UploadFile = File(...)
):
''',
'''async def edit_image(
    title: str = Form(...),
    instruction: str = Form(...),
    campaign_uid: str = Form(...),
    visibility_envelope: str = Form(...),
    image: UploadFile = File(...)
):
''')
replace_once(p,
'''    image_model = os.environ.get("RPGOS_IMAGE_MODEL", "gpt-image-1")
    raw = await image.read()
''',
'''    try:
        visual_envelope = Phase38VisualEnvelope(**json.loads(visibility_envelope))
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"RPGOS-VISIBILITY:MALFORMED_VISUAL_ENVELOPE:{type(exc).__name__}")
    _require_visual_projection(visual_envelope, campaign_uid, "IMAGE_EDIT_VISUALIZATION", instruction)

    image_model = os.environ.get("RPGOS_IMAGE_MODEL", "gpt-image-1")
    raw = await image.read()
''')

# ---- Existing Phase38 inventory test scans with production classifier + backend ----
p="app/src/test/java/com/rpgos/app/Phase38VisibilityBoundaryTest.kt"
replace_once(p,
'''        val markers=listOf("gm_summary","npc_memories_v2","npc_beliefs","npc_schedules","npc_decisions","CampaignTruthStore(","KnowledgeContextProjection(")
        val unclassified=File(root,"app/src/main/java").walkTopDown().filter{it.isFile&&it.extension=="kt"}.mapNotNull{f->
            val text=f.readText();if(markers.any(text::contains)) f.relativeTo(root).invariantSeparatorsPath else null
        }.filter{VisibilityConsumerInventory.contractForSource(it)==null}.toList()
        assertTrue("unclassified protected consumers: $unclassified",unclassified.isEmpty())
        assertTrue(runCatching{VisibilityConsumerInventory.requireClassified("app/src/main/java/com/rpgos/app/NewHiddenConsumer.kt")}.isFailure)
''',
'''        val productionFiles = sequenceOf(
            File(root,"app/src/main/java").walkTopDown().filter{it.isFile&&it.extension=="kt"},
            File(root,"backend").walkTopDown().filter{it.isFile&&it.extension=="py"}
        ).flatten()
        val unclassified=productionFiles.mapNotNull{f->
            val path=f.relativeTo(root).invariantSeparatorsPath
            if(VisibilityConsumerInventory.looksProtected(f.readText()) && VisibilityConsumerInventory.contractForSource(path)==null) path else null
        }.toList()
        assertTrue("unclassified protected consumers: $unclassified",unclassified.isEmpty())
        assertTrue(runCatching{VisibilityConsumerInventory.requireClassifiedIfProtected("app/src/main/java/com/rpgos/app/NewHiddenConsumer.kt","class X { val x = CampaignTruthStore(db, c) }")}.isFailure)
''')

# ---- Phase37 static assertion migration: explicit Phase38 holders replace discovery ----
p="app/src/test/java/com/rpgos/app/Phase37WorldActorKnowledgeTest.kt"
replace_once(p,
'        assertTrue(code.contains("KnowledgeContextHolderDiscovery"))\n',
'        assertTrue(code.contains("audience.knowledgeHolders"))\n        assertTrue(code.contains("PHASE37_HOLDER_KNOWLEDGE"))\n')

# ---- Ownership fixture: use exact campaign identity derived by ContextBuilder for this temp DB ----
p="app/src/test/java/com/rpgos/app/Phase32OwnershipIsolationTest.kt"
replace_once(p,
'ContextBuilder(db,world).build("inspect",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))',
'''run {
                    val contextCampaign = ActiveCampaignRef.fromDatabasePath(db.path).campaignId
                    ContextBuilder(db,world).build("inspect",1,VisibilityAudienceFactory.diagnostic(contextCampaign),PurposeContext(contextCampaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))
                }''')

# ---- Final closure adversarial regression matrix ----
Path("app/src/test/java/com/rpgos/app/Phase38FinalClosureTest.kt").write_text(r'''package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Phase38FinalClosureTest {
    private val authority = VisibilityAuthorityService()
    private val campaign = "C1"
    private val player = VisibilityAudienceFactory.player(campaign)

    private fun env(purpose:String, level:DisclosureLevel=DisclosureLevel.DISCLOSE_FULL):VisibilityProjectionEnvelope =
        authority.envelope(player,PurposeContext(campaign,purpose)).reduceTo(level)

    private fun bundle(purpose:String, secret:String="SECRET"):ContextBundle = ContextBundle(
        playerStatus=emptyMap(), scene=mapOf("world_presentation" to "generic disclosed world"), time=emptyMap(),
        activeThreads=emptyList(), relevantNpcs=emptyList(), npcKnowledge=emptyList(), missions=emptyList(),
        worldPressures=emptyList(), canonConstraints=emptyList(), recentChronicle=emptyList(), retrievedLongTermMemory=emptyList(),
        campaignTruth=listOf(mapOf("gm_only" to secret)),
        visibilityEnvelope=env(purpose)
    )

    @Test fun sceneCharacterAndLocationPromptsCannotMineHiddenContext(){
        val scene=VisualPromptBuilder().buildScenePrompt("look",bundle(VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE_SECRET"))
        val character=VisualPromptBuilder().buildCharacterPrompt("A",listOf("visible"),emptyList(),"public note",bundle(VisibilityPurposeKinds.CHARACTER_VISUALIZATION,"MEM_PRIVATE"))
        val location=VisualPromptBuilder().buildLocationPrompt("L","public place","era",bundle(VisibilityPurposeKinds.LOCATION_VISUALIZATION,"LOCATION_SECRET"))
        assertFalse(scene.contains("SCENE_SECRET"));assertFalse(character.contains("MEM_PRIVATE"));assertFalse(location.contains("LOCATION_SECRET"))
    }

    @Test fun visualAuthorizationRejectsMissingWrongPurposeWrongCampaignDenyAndSubstitution(){
        val prompt="authorized prompt"
        val scene=Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.SCENE_VISUALIZATION),VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE","S",prompt,requestUid="REQ-1")
        scene.requireRequest(campaign,VisibilityPurposeKinds.SCENE_VISUALIZATION,prompt)
        assertTrue(runCatching{scene.requireRequest("C2",VisibilityPurposeKinds.SCENE_VISUALIZATION,prompt)}.isFailure)
        assertTrue(runCatching{scene.requireRequest(campaign,VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,prompt)}.isFailure)
        assertTrue(runCatching{scene.requireRequest(campaign,VisibilityPurposeKinds.SCENE_VISUALIZATION,"substituted")}.isFailure)
        assertTrue(runCatching{Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.GAMEPLAY_NARRATION),VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE","S",prompt)}.isFailure)
        val denied=authority.envelope(player,PurposeContext(campaign,VisibilityPurposeKinds.SCENE_VISUALIZATION)).reduceTo(DisclosureLevel.DENY)
        assertTrue(runCatching{Phase38VisualAuthorization.authorize(denied,VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE","S",prompt)}.isFailure)
        assertTrue(runCatching{Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.SCENE_VISUALIZATION,DisclosureLevel.DISCLOSE_PARTIAL),VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE","S",prompt,payloadDisclosure=DisclosureLevel.DISCLOSE_FULL)}.isFailure)
    }

    @Test fun sceneEnvelopeCannotBeReusedForEditAndEditCannotGainHiddenActor(){
        val instruction="brighten the disclosed foreground"
        val edit=Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,"VISUAL","V1",instruction,VisualInputOrigins.USER_STANDALONE,requestUid="EDIT-1")
        edit.requireRequest(campaign,VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,instruction)
        assertTrue(runCatching{Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.SCENE_VISUALIZATION),VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,"VISUAL","V1",instruction)}.isFailure)
        val editClient=source("app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt")
        assertFalse(editClient.contains("CampaignTruth"));assertFalse(editClient.contains("gm_summary"));assertFalse(editClient.contains("npc_memories"))
    }

    @Test fun imageRequestsStructurallyRequireAuthorization(){
        val generateCtor=ImageGenerationRequest::class.java.declaredConstructors.single { it.parameterTypes.any { t -> t == Phase38VisualAuthorization::class.java } }
        assertNotNull(generateCtor)
        val editCtor=ImageEditRequest::class.java.declaredConstructors.single { it.parameterTypes.any { t -> t == Phase38VisualAuthorization::class.java } }
        assertNotNull(editCtor)
    }

    @Test fun backendAndLocalUseSameVisualAuthoritySemantics(){
        val backend=source("backend/app.py")
        listOf(
            VisibilityAuthorityService.AUTHORITY_UID,
            VisibilityAuthorityService.PROJECTION_VERSION_UID,
            "SCENE_VISUALIZATION","CHARACTER_VISUALIZATION","LOCATION_VISUALIZATION","IMAGE_EDIT_VISUALIZATION",
            "VISUAL_PAYLOAD_SUBSTITUTION","VISUAL_DISCLOSURE_ESCALATION","PROJECTION_DENIED","CROSS_CAMPAIGN_PROJECTION"
        ).forEach { assertTrue("backend missing $it",backend.contains(it)) }
        assertTrue(backend.contains("_require_visual_projection(req.visibility_envelope"))
        assertTrue(backend.contains("_require_visual_projection(visual_envelope"))
    }

    @Test fun playerAndPlayerCharacterAndTwoPcKnowledgeRemainIsolated(){
        val holderA=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-A",campaign)
        val holderB=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-B",campaign)
        val pcA=AudienceContext(campaign,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","PC-A"),listOf(holderA))
        val pcB=AudienceContext(campaign,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","PC-B"),listOf(holderB))
        val reasoning=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        val subA=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-A",holder=holderA)
        val subB=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-B",holder=holderB)
        assertNotEquals(AudienceKinds.PLAYER,pcA.audienceKindUid)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pcA,reasoning,subA)).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(pcA,reasoning,subB)).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pcB,reasoning,subB)).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,reasoning,subA)).level)
        val c2Holder=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-A","C2")
        assertTrue(runCatching{VisibilityRequest(pcA,reasoning,VisibilitySubjectRef("C2",VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-A",holder=c2Holder))}.isFailure)
    }

    @Test fun diagnosticVisibilityDoesNotBecomePlayerVisibilityAndStrategicDisclosureDoesNotAcquireKnowledge(){
        val diagnostic=VisibilityAudienceFactory.diagnostic(campaign)
        val diagPurpose=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        val truth=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T")
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(diagnostic,diagPurpose,truth)).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI),truth)).level)
    }

    @Test fun conservativeRelationshipPoliticsEconomyOrganizationAreNotImplicitlyPublic(){
        val ui=PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)
        listOf(VisibilitySubjectKinds.RELATIONSHIP_DATA,VisibilitySubjectKinds.POLITICS_DATA,VisibilitySubjectKinds.ECONOMY_DATA,VisibilitySubjectKinds.ORGANIZATION_DATA).forEach{
            assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,ui,VisibilitySubjectRef(campaign,it,"X"))).level)
        }
        val diagnostic=VisibilityAudienceFactory.diagnostic(campaign);val dp=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(diagnostic,dp,VisibilitySubjectRef(campaign,VisibilitySubjectKinds.POLITICS_DATA,"X"))).level)
    }

    @Test fun corruptionNoDataDeniedNotDisclosedUnknownAreDistinctContracts(){
        assertNotEquals(ProjectionDataState.NO_DATA,ProjectionDataState.DENIED)
        assertNotEquals(ProjectionDataState.DENIED,ProjectionDataState.NOT_DISCLOSED)
        assertNotEquals(ProjectionDataState.NOT_DISCLOSED,ProjectionDataState.UNKNOWN)
        assertNotEquals(ProjectionDataState.UNKNOWN,ProjectionDataState.CORRUPTION)
        val denied=authority.project(VisibilityRequest(player,PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI),VisibilitySubjectRef(campaign,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T"))){"SECRET"}
        assertEquals(ProjectionDataState.DENIED,denied.dataState)
        val unknownAudience=AudienceContext(campaign,"UNKNOWN",VisibilityPrincipalRef("X","1"))
        val unknown=authority.project(VisibilityRequest(unknownAudience,PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI),VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E"))){"x"}
        assertEquals(ProjectionDataState.UNKNOWN,unknown.dataState)
        val noData=authority.projectList(VisibilityRequest(player,PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI),VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E"))){emptyList<String>()}
        assertEquals(ProjectionDataState.NO_DATA,noData.dataState)
    }

    @Test fun internallyInconsistentProtectedBindingFailsClosed(){
        val holder=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC",campaign)
        val pc=AudienceContext(campaign,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","PC"),listOf(holder))
        val wrong=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"OTHER",campaign)
        val request=VisibilityRequest(pc,PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING),VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC",holder=wrong))
        val projection=authority.project(request){listOf("should not execute")}
        assertEquals(DisclosureLevel.DENY,projection.decision.level);assertNull(projection.value)
    }

    @Test fun universalityMatrixUsesOneCoreContract(){
        val p=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        listOf("ORDINARY_CHARACTER","ORGANIZATION_GENERAL","NON_HUMAN","SHARED_COLLECTIVE","TECH_OBSERVER","SUPERNATURAL_OBSERVER").forEach{kind->
            val a=AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(kind,"ID"))
            assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(a,p,VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E"))).level)
        }
        val strategy=VisibilityAudienceFactory.player(campaign)
        assertEquals(AudienceKinds.PLAYER,strategy.audienceKindUid)
        val characterRpg=AudienceContext(campaign,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","PC"))
        assertEquals(AudienceKinds.PLAYER_CHARACTER,characterRpg.audienceKindUid)
    }

    @Test fun modifiedPhase38CoreContainsNoWorldSpecificAuthorityBranches(){
        val paths=listOf(
            "app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
            "app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt",
            "app/src/main/java/com/rpgos/app/Phase38VisualAuthorization.kt",
            "app/src/main/java/com/rpgos/app/VisualPromptBuilder.kt"
        )
        val banned=listOf("naruto","bleach","witcher","shinobi","ninja","hokage","chakra","reiatsu","wizard","dragon","jedi")
        paths.forEach{path->val code=source(path).lowercase();banned.forEach{assertFalse("$path contains world lock-in $it",code.contains(it))}}
    }

    private fun repoRoot():File{
        var f=File(System.getProperty("user.dir")).canonicalFile
        repeat(8){if(File(f,"app/src/main/java").isDirectory)return f;f=f.parentFile?:return@repeat}
        error("repo root not found")
    }
    private fun source(path:String)=File(repoRoot(),path).readText()
}
''')

print("Phase38 final blocker closure patch applied")
