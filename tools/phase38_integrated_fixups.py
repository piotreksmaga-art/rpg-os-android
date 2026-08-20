from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
def p(x): return ROOT/x
def rep(rel,old,new):
    f=p(rel); s=f.read_text(encoding='utf-8')
    if old not in s: raise SystemExit(f'missing anchor {rel}: {old[:100]}')
    f.write_text(s.replace(old,new),encoding='utf-8')
def hardening():
    # The old diagnostic test intentionally exercised caller-forgeable privilege; after hardening it must fail closed.
    rep('app/src/test/java/com/rpgos/app/Phase38VisibilityBoundaryTest.kt',
'''    @Test fun explicitDiagnosticAudienceCanReceiveAuthorizedPrivateProjection(){
        world.execSQL("INSERT INTO canon_characters_v2(character_uid,name,sex,status) VALUES('B','Beta','x','active')")
        save.execSQL("INSERT INTO npc_memories_v2(entity_uid,summary,importance,chapter) VALUES('B','MEM_SECRET',1,1)")
        val a=VisibilityAudienceFactory.diagnostic(campaign);val p=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        assertEquals(listOf("MEM_SECRET"),NpcWorldDashboardReader(world,save).npcDetail("B",a,p).memories)
    }
''',
'''    @Test fun callerConstructedDiagnosticAudienceCannotReceivePrivateProjection(){
        world.execSQL("INSERT INTO canon_characters_v2(character_uid,name,sex,status) VALUES('B','Beta','x','active')")
        save.execSQL("INSERT INTO npc_memories_v2(entity_uid,summary,importance,chapter) VALUES('B','MEM_SECRET',1,1)")
        val a=VisibilityAudienceFactory.diagnostic(campaign);val p=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        assertTrue(NpcWorldDashboardReader(world,save).npcDetail("B",a,p).memories.isEmpty())
    }
''')
    rep('app/src/test/java/com/rpgos/app/Phase38VisibilityBoundaryTest.kt',
'''        val gm=AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef("GM","RUNTIME"))
        val gmPurpose=PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(req(gm,gmPurpose,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T")).level)
''',
'''        val gm=AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef("GM","RUNTIME"))
        val gmPurpose=PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
        assertEquals(DisclosureLevel.DENY,authority.decide(req(gm,gmPurpose,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T")).level)
        val trusted=Phase38RuntimeAuthority.privileged(gm,Phase38RuntimeAuthority.PRIV_GM)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(req(gm,gmPurpose,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T"),trusted).level)
''')
    # Image clients construct full semantic request locally and edit sends source UID after hashing source bytes.
    rep('app/src/main/java/com/rpgos/app/ImageBackendClient.kt',
'''            requestData.authorization.requireRequest(requestData.authorization.campaignUid, expectedPurpose, requestData.prompt)
''',
'''            requestData.authorization.requireRequest(VisualSemanticRequest(
                requestData.authorization.campaignUid, requestData.authorization.audienceKindUid, requestData.authorization.audienceUid,
                expectedPurpose, requestData.authorization.subjectKindUid, requestData.authorization.subjectUid,
                requestData.authorization.requestUid, VisualRequestKinds.GENERATE, requestData.prompt,
                relatedEntityUid = requestData.relatedEntityUid
            ))
''')
    rep('app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt',
'''            reqData.authorization.requireRequest(
                reqData.authorization.campaignUid,
                VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
                reqData.instruction
            )
            val uri = android.net.Uri.parse(reqData.sourceUri)
            val bytes = context.contentResolver.openInputStream(uri).use { input ->
''',
'''            val uri = android.net.Uri.parse(reqData.sourceUri)
            val bytes = context.contentResolver.openInputStream(uri).use { input ->
''')
    rep('app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt',
'''                input.readBytes()
            }

            val multipart = MultipartBody.Builder()
''',
'''                input.readBytes()
            }
            val sourceDigest = Phase38VisualAuthorization.digestBytes(bytes)
            reqData.authorization.requireRequest(VisualSemanticRequest(
                reqData.authorization.campaignUid, reqData.authorization.audienceKindUid, reqData.authorization.audienceUid,
                VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION, reqData.authorization.subjectKindUid, reqData.authorization.subjectUid,
                reqData.authorization.requestUid, VisualRequestKinds.EDIT, reqData.instruction,
                relatedEntityUid = reqData.authorization.relatedEntityUid, sourceVisualUid = reqData.sourceVisualUid, sourceImageSha256 = sourceDigest
            ))

            val multipart = MultipartBody.Builder()
''')
    rep('app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt',
'''                .addFormDataPart("campaign_uid", reqData.authorization.campaignUid)
''',
'''                .addFormDataPart("campaign_uid", reqData.authorization.campaignUid)
                .addFormDataPart("source_visual_uid", reqData.sourceVisualUid)
''')
    rep('app/src/main/java/com/rpgos/app/Phase38VisualAuthorization.kt',
'''        fun digest(payload:String)=MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
''',
'''        fun digest(payload:String)=digestBytes(payload.toByteArray(Charsets.UTF_8))
        fun digestBytes(payload:ByteArray)=MessageDigest.getInstance("SHA-256").digest(payload).joinToString(""){"%02x".format(it)}
''')
    # Backend independently recomputes both payload and full semantic request digests, including edit source bytes.
    rel='backend/app.py'; f=p(rel); s=f.read_text(encoding='utf-8')
    s=s.replace('import os, json, uuid, base64, tempfile','import os, json, uuid, base64, tempfile, hashlib')
    s=s.replace('''    payload_sha256: str
    input_origin_uid: str
''','''    request_kind_uid: str
    payload_sha256: str
    semantic_request_sha256: str
    input_origin_uid: str
    related_entity_uid: Optional[str] = None
    source_visual_uid: Optional[str] = None
    source_image_sha256: Optional[str] = None
''')
    s=s.replace('''def _visual_payload_digest(payload: str) -> str:
    import hashlib
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _require_visual_projection(envelope: Phase38VisualEnvelope, campaign_uid: str, expected_purpose: str, payload: str):
''','''def _visual_payload_digest(payload: str) -> str:
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _visual_semantic_digest(envelope: Phase38VisualEnvelope, payload: str, request_kind: str, related_entity_uid=None, source_visual_uid=None, source_image_sha256=None) -> str:
    fields = [envelope.campaign_uid,envelope.audience_kind_uid,envelope.audience_uid or "",envelope.purpose_uid,
              envelope.subject_kind_uid,envelope.subject_uid,envelope.request_uid,request_kind,payload,related_entity_uid or "",
              source_visual_uid or "",source_image_sha256 or ""]
    return hashlib.sha256("\\x1f".join(fields).encode("utf-8")).hexdigest()


def _require_visual_projection(envelope: Phase38VisualEnvelope, campaign_uid: str, expected_purpose: str, payload: str,
                               request_kind: str, related_entity_uid=None, source_visual_uid=None, source_image_sha256=None):
''')
    s=s.replace('''    if envelope.payload_sha256 != _visual_payload_digest(payload):
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:VISUAL_PAYLOAD_SUBSTITUTION")
    if not envelope.request_uid or not envelope.subject_kind_uid or not envelope.subject_uid:
''','''    if envelope.payload_sha256 != _visual_payload_digest(payload):
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:VISUAL_PAYLOAD_SUBSTITUTION")
    if envelope.request_kind_uid != request_kind:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:VISUAL_REQUEST_KIND_MISMATCH")
    if envelope.related_entity_uid != related_entity_uid or envelope.source_visual_uid != source_visual_uid or envelope.source_image_sha256 != source_image_sha256:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:VISUAL_SOURCE_SUBSTITUTION")
    expected_semantic = _visual_semantic_digest(envelope,payload,request_kind,related_entity_uid,source_visual_uid,source_image_sha256)
    if envelope.semantic_request_sha256 != expected_semantic:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:VISUAL_SEMANTIC_SUBSTITUTION")
    if not envelope.request_uid or not envelope.subject_kind_uid or not envelope.subject_uid:
''')
    s=s.replace('''    _require_visual_projection(req.visibility_envelope, req.campaign_uid, expected_purpose, req.prompt)
''','''    _require_visual_projection(req.visibility_envelope, req.campaign_uid, expected_purpose, req.prompt, "GENERATE", req.related_entity_uid)
''')
    s=s.replace('''    _require_visual_projection(visual_envelope, campaign_uid, "IMAGE_EDIT_VISUALIZATION", instruction)

    image_model = os.environ.get("RPGOS_IMAGE_MODEL", "gpt-image-1")
    raw = await image.read()
''','''    image_model = os.environ.get("RPGOS_IMAGE_MODEL", "gpt-image-1")
    raw = await image.read()
    source_digest = hashlib.sha256(raw).hexdigest()
    _require_visual_projection(visual_envelope, campaign_uid, "IMAGE_EDIT_VISUALIZATION", instruction, "EDIT", None, source_visual_uid, source_digest)
''')
    s=s.replace('''    visibility_envelope: str = Form(...),
    image: UploadFile = File(...)
''','''    visibility_envelope: str = Form(...),
    source_visual_uid: str = Form(...),
    image: UploadFile = File(...)
''')
    f.write_text(s,encoding='utf-8')
def c():
    # Keep the access conflict key explicit and stable regardless helper arity.
    rep('app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt',
'''conflicts = { setOf(compositeConflictKey("ACCESS",it.principalKindUid,it.principalUid,it.recordUid,it.validFromOrder.toString())) }''',
'''conflicts = { setOf("ACCESS:${it.principalKindUid}:${it.principalUid}:${it.recordUid}:${it.validFromOrder}") }''')
    # Role/org/clearance may only enter normal trusted context through canonical access resolver, not generic runtime helper.
    rel='app/src/main/java/com/rpgos/app/Phase38TrustedAuthority.kt'; f=p(rel); s=f.read_text(encoding='utf-8')
    s=s.replace(''',
        roleUids: Set<String> = emptySet(),
        organizationUids: Set<String> = emptySet(),
        clearanceUids: Set<String> = emptySet()
''','''
''')
    s=s.replace('''            controlledSubjectUids, roleUids, organizationUids, clearanceUids,
            cognitionResolver.holdersFor(audience.campaignUid, principal)
''','''            controlledSubjectUids, emptySet(), emptySet(), emptySet(),
            cognitionResolver.holdersFor(audience.campaignUid, principal)
''')
    f.write_text(s,encoding='utf-8')
    # Access resolver is the sole builder that injects canonical role/org/clearance state.
    rel='app/src/main/java/com/rpgos/app/Phase38AccessAuthority.kt'; f=p(rel); s=f.read_text(encoding='utf-8')
    s=s.replace('''        return Phase38RuntimeAuthority.application(audience,controls,cognitionResolver,roles,orgs,clearances)
''','''        val principal=audience.principal?:return null
        if(audience.audienceKindUid in setOf(AudienceKinds.GM_RUNTIME,AudienceKinds.INTERNAL_SYSTEM,AudienceKinds.DEVELOPER_DIAGNOSTIC))return null
        return TrustedPrincipalContext(audience.campaignUid,principal,audience.audienceKindUid,controls,roles,orgs,clearances,cognitionResolver.holdersFor(audience.campaignUid,principal))
''')
    f.write_text(s,encoding='utf-8')
    # Test no longer demonstrates use of runtime helper to assert a role.
    rel='app/src/test/java/com/rpgos/app/Phase38AccessAuthorityTest.kt'; f=p(rel); s=f.read_text(encoding='utf-8')
    s=s.replace('''        val forged=Phase38RuntimeAuthority.application(a,roleUids=setOf("ROLE"))!!
        val noStore=AccessAuthorityStore(db,"C");val auth=UniversalAccessAuthority(noStore)
        assertTrue(auth.authorize(forged,AccessRequirement("P",requiredRoleUids=setOf("ROLE"))).authorized)
        val trustedFromCanonical=auth.trustedContext(a)!!
''','''        val noStore=AccessAuthorityStore(db,"C");val auth=UniversalAccessAuthority(noStore)
        val trustedFromCanonical=auth.trustedContext(a)!!
''')
    f.write_text(s,encoding='utf-8')
if __name__=='__main__':
    if len(sys.argv)!=2: raise SystemExit('hardening|c')
    {'hardening':hardening,'c':c}[sys.argv[1]](); print('fixups',sys.argv[1])
