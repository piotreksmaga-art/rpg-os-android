import os, json, uuid, base64, tempfile
from typing import Any, Dict, List, Optional
from fastapi import FastAPI, HTTPException, UploadFile, File, Form
from pydantic import BaseModel, Field
from openai import OpenAI

app = FastAPI(title="RPG OS Backend", version="0.4.0")
client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))

class TurnRequest(BaseModel):
    campaign_id: str
    chapter: int
    player_input: str
    context_bundle: Dict[str, Any]

class PatchOperation(BaseModel):
    op: str
    table: str
    key: Dict[str, Any] = Field(default_factory=dict)
    values: Dict[str, Any] = Field(default_factory=dict)

class StatePatch(BaseModel):
    transaction_id: str
    operations: List[PatchOperation] = Field(default_factory=list)
    chapter_manifest: Dict[str, Any] = Field(default_factory=dict)
    requires_validation: bool = True

class TurnResponse(BaseModel):
    narration: str
    choices: List[str] = Field(default_factory=list)
    state_patch: Optional[StatePatch] = None
    chapter_events: List[Dict[str, Any]] = Field(default_factory=list)

class ProposalRequest(BaseModel):
    protocol: str
    campaign_id: str
    worldpack_id: str
    chapter: int
    locale: str = "pl-PL"
    player_action: str
    context: Dict[str, Any]
    response_contract: Dict[str, Any] = Field(default_factory=dict)

TURN_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "narration": {"type": "string"},
        "choices": {"type": "array", "items": {"type": "string"}, "maxItems": 3},
        "state_patch": {
            "anyOf": [
                {"type": "null"},
                {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "transaction_id": {"type": "string"},
                        "operations": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "additionalProperties": False,
                                "properties": {
                                    "op": {"type": "string", "enum": ["insert","update","delete"]},
                                    "table": {"type": "string"},
                                    "key": {"type": "object"},
                                    "values": {"type": "object"}
                                },
                                "required": ["op","table","key","values"]
                            }
                        },
                        "chapter_manifest": {"type": "object"},
                        "requires_validation": {"type": "boolean"}
                    },
                    "required": ["transaction_id","operations","chapter_manifest","requires_validation"]
                }
            ]
        },
        "chapter_events": {"type": "array", "items": {"type": "object"}}
    },
    "required": ["narration","choices","state_patch","chapter_events"]
}

PROPOSAL_ACTION_TYPES = [
    "EMIT_EVENT",
    "WORLD_EVENT",
    "STATE_SET",
    "STATE_INCREMENT",
    "STATE_DECREMENT",
    "STATE_REMOVE",
    "ASSERT_FACT",
    "ASSERT_BELIEF",
    "ASSERT_NARRATIVE",
    "CANON_DIVERGENCE",
]

MEMORY_TYPES = [
    "FACT",
    "RELATIONSHIP",
    "PROMISE",
    "SECRET",
    "DISCOVERY",
    "CHARACTER_DEVELOPMENT",
    "WORLD_CHANGE",
    "PLAYER_PREFERENCE",
    "LONG_TERM_THREAD",
]

PROPOSAL_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "narrative_draft": {"type": "string"},
        "proposed_actions": {
            "type": "array",
            "maxItems": 32,
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "action_type": {"type": "string", "enum": PROPOSAL_ACTION_TYPES},
                    "actor_id": {"type": ["string", "null"]},
                    "target_id": {"type": ["string", "null"]},
                    "parameters": {
                        "type": "string",
                        "description": "A JSON object serialized as a string. Use only semantic resolver parameters, never SQL or table names."
                    },
                    "reason": {"type": "string"}
                },
                "required": ["action_type", "actor_id", "target_id", "parameters", "reason"]
            }
        },
        "proposed_memories": {
            "type": "array",
            "maxItems": 16,
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "memory_type": {"type": "string", "enum": MEMORY_TYPES},
                    "subject_id": {"type": ["string", "null"]},
                    "text": {"type": "string"},
                    "importance": {"type": "number", "minimum": 0.0, "maximum": 1.0},
                    "chapter": {"type": "integer", "minimum": 0},
                    "tags": {"type": "array", "items": {"type": "string"}, "maxItems": 16}
                },
                "required": ["memory_type", "subject_id", "text", "importance", "chapter", "tags"]
            }
        },
        "proposed_chronicle_entries": {
            "type": "array",
            "maxItems": 4,
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "chapter": {"type": "integer", "minimum": 0},
                    "title": {"type": "string"},
                    "summary": {"type": "string"},
                    "participants": {"type": "array", "items": {"type": "string"}, "maxItems": 32},
                    "location_ids": {"type": "array", "items": {"type": "string"}, "maxItems": 16}
                },
                "required": ["chapter", "title", "summary", "participants", "location_ids"]
            }
        },
        "diagnostics": {
            "type": "object",
            "additionalProperties": False,
            "properties": {
                "context_characters": {"type": "integer", "minimum": 0},
                "retrieved_memory_count": {"type": "integer", "minimum": 0},
                "retrieved_canon_count": {"type": "integer", "minimum": 0},
                "retrieved_npc_count": {"type": "integer", "minimum": 0},
                "retrieved_thread_count": {"type": "integer", "minimum": 0},
                "warnings": {"type": "array", "items": {"type": "string"}, "maxItems": 32}
            },
            "required": [
                "context_characters",
                "retrieved_memory_count",
                "retrieved_canon_count",
                "retrieved_npc_count",
                "retrieved_thread_count",
                "warnings"
            ]
        }
    },
    "required": [
        "narrative_draft",
        "proposed_actions",
        "proposed_memories",
        "proposed_chronicle_entries",
        "diagnostics"
    ]
}

SYSTEM_PROMPT = """You are the Game Master for RPG OS.
Return only data matching the requested JSON schema.
Never invent NPC knowledge that is absent from npc_knowledge or npc_memories.
Use player_skills and player_techniques as authoritative learned abilities.
Use active_world_events, world_pressures and recent_chronicle to preserve causality.
Use player_organizations and scene/location state when deciding who can plausibly appear.
Respect canon_constraints unless campaign state has already diverged.
Narration must be in Polish.
The state_patch must contain only concrete state changes caused by this turn.
Do not write to reference/canon/legacy tables; Android validates all patches anyway.
Do not include hidden GM-only information in narration unless the player has discovered it.
"""

GM141_SYSTEM_PROMPT = """You are the narrative planning layer of RPG OS GM Engine 141.
You are NOT the rules engine and you are NOT allowed to write canonical state directly.
Return only a semantic proposal matching the supplied strict JSON schema.

Hard rules:
- Narration must be in the requested locale (normally Polish).
- Treat CURRENT_SCENE, PLAYER_STATE, ACTIVE_WORLD_STATE, ACTIVE_THREADS, RELEVANT_MEMORIES, RELEVANT_CANON, GM_INVARIANTS and RECENT_HISTORY as bounded evidence, not invitations to invent missing history.
- FACT, BELIEF and NARRATIVE are different. A belief belongs only to its holder. Never leak one NPC's private knowledge to another without an information path present in context.
- Campaign Source of Truth and accepted divergences override untouched canon.
- Never remove an established skill, achievement, relationship or fact merely because it is absent from a short context excerpt.
- Do not output SQL, table names, StatePatch, database operations, trusted old_value/new_value pairs, UUIDs for durable records, or final mechanical calculations.
- proposed_actions are semantic requests only. Android's deterministic resolver decides whether they are legal and calculates durable consequences.
- Use identifiers exactly as present in context. Do not fabricate actor_id/target_id when no reliable identifier is available; use null instead.
- parameters must be a JSON object serialized into a string. Keep it minimal and use only keys expected by the semantic action.
- If no durable consequence follows from the player's action, proposed_actions may be empty.
- proposed_memories should contain only information worth retaining beyond the immediate scene.
- proposed_chronicle_entries should summarize significant campaign progress, not every sentence of narration.
"""

@app.get("/health")
def health():
    return {"ok": True, "service": "rpg-os-backend", "version": "0.4.0"}

@app.post("/v1/gm/turn", response_model=TurnResponse)
def gm_turn(req: TurnRequest):
    if not os.environ.get("OPENAI_API_KEY"):
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")

    model = os.environ.get("RPGOS_MODEL", "gpt-5.6")
    payload = {
        "chapter": req.chapter,
        "player_input": req.player_input,
        "context_bundle": req.context_bundle
    }

    response = client.responses.create(
        model=model,
        instructions=SYSTEM_PROMPT,
        input=[
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_text",
                        "text": "Generate the next RPG turn as JSON. Context:\n" + json.dumps(payload, ensure_ascii=False)
                    }
                ]
            }
        ],
        text={
            "format": {
                "type": "json_schema",
                "name": "rpg_os_turn",
                "strict": True,
                "schema": TURN_SCHEMA
            }
        }
    )

    raw = response.output_text
    data = json.loads(raw)
    if data.get("state_patch") and not data["state_patch"].get("transaction_id"):
        data["state_patch"]["transaction_id"] = str(uuid.uuid4())
    return data


@app.post("/v1/gm/proposal")
def gm_proposal(req: ProposalRequest):
    if req.protocol != "rpg-os-gm141-proposal-v1":
        raise HTTPException(status_code=400, detail="Unsupported GM141 proposal protocol")
    if not req.player_action.strip():
        raise HTTPException(status_code=400, detail="player_action must not be blank")
    if req.chapter < 0:
        raise HTTPException(status_code=400, detail="chapter must be non-negative")
    if not os.environ.get("OPENAI_API_KEY"):
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")

    model = os.environ.get("RPGOS_MODEL", "gpt-5.6")
    payload = {
        "protocol": req.protocol,
        "campaign_id": req.campaign_id,
        "worldpack_id": req.worldpack_id,
        "chapter": req.chapter,
        "locale": req.locale,
        "player_action": req.player_action,
        "context": req.context,
        "client_contract": req.response_contract,
    }

    response = client.responses.create(
        model=model,
        instructions=GM141_SYSTEM_PROMPT,
        input=[
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_text",
                        "text": "Prepare the next GM141 semantic proposal from this bounded context:\n"
                                + json.dumps(payload, ensure_ascii=False)
                    }
                ]
            }
        ],
        text={
            "format": {
                "type": "json_schema",
                "name": "rpg_os_gm141_proposal",
                "strict": True,
                "schema": PROPOSAL_SCHEMA
            }
        }
    )

    try:
        data = json.loads(response.output_text)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"GM141 model returned invalid JSON: {exc}")

    # The server still treats model output as untrusted. Reject obvious attempts
    # to smuggle the old patch/SQL protocol through semantic parameter strings.
    forbidden = ("state_patch", "insert into", "update ", "delete from", "drop table", "alter table")
    for index, action in enumerate(data.get("proposed_actions", [])):
        params = action.get("parameters", "")
        lowered = params.lower()
        if any(token in lowered for token in forbidden):
            raise HTTPException(
                status_code=502,
                detail=f"GM141 proposal action {index} contains forbidden database instructions"
            )
        try:
            decoded = json.loads(params)
        except Exception as exc:
            raise HTTPException(
                status_code=502,
                detail=f"GM141 proposal action {index} parameters are not valid JSON: {exc}"
            )
        if not isinstance(decoded, dict):
            raise HTTPException(
                status_code=502,
                detail=f"GM141 proposal action {index} parameters must encode a JSON object"
            )

    return data


class ImageGenerateRequest(BaseModel):
    kind: str
    title: str
    prompt: str
    related_entity_uid: Optional[str] = None
    chapter: Optional[int] = None

class ImageGenerateResponse(BaseModel):
    title: str
    mime_type: str = "image/png"
    base64_data: str
    revised_prompt: Optional[str] = None

@app.post("/v1/images/generate", response_model=ImageGenerateResponse)
def generate_image(req: ImageGenerateRequest):
    if not os.environ.get("OPENAI_API_KEY"):
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")

    # Use the Images API so the mobile app receives only image bytes from our backend.
    image_model = os.environ.get("RPGOS_IMAGE_MODEL", "gpt-image-1")
    result = client.images.generate(
        model=image_model,
        prompt=req.prompt,
        size="1024x1024",
        quality="medium"
    )

    item = result.data[0]
    b64 = getattr(item, "b64_json", None)
    if not b64:
        raise HTTPException(status_code=502, detail="Image API returned no base64 image data")

    return ImageGenerateResponse(
        title=req.title,
        mime_type="image/png",
        base64_data=b64,
        revised_prompt=getattr(item, "revised_prompt", None)
    )


@app.post("/v1/images/edit", response_model=ImageGenerateResponse)
async def edit_image(
    title: str = Form(...),
    instruction: str = Form(...),
    image: UploadFile = File(...)
):
    if not os.environ.get("OPENAI_API_KEY"):
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")

    image_model = os.environ.get("RPGOS_IMAGE_MODEL", "gpt-image-1")
    raw = await image.read()

    with tempfile.NamedTemporaryFile(suffix=".png", delete=True) as temp:
        temp.write(raw)
        temp.flush()
        with open(temp.name, "rb") as image_file:
            result = client.images.edit(
                model=image_model,
                image=image_file,
                prompt=instruction,
                size="1024x1024"
            )

    item = result.data[0]
    b64 = getattr(item, "b64_json", None)
    if not b64:
        raise HTTPException(status_code=502, detail="Image edit returned no base64 data")

    return ImageGenerateResponse(
        title=title,
        mime_type="image/png",
        base64_data=b64,
        revised_prompt=getattr(item, "revised_prompt", None)
    )



# ---- RPG OS Update System v1 ----
import urllib.request
from fastapi.responses import StreamingResponse

def _update_github_headers(accept="application/vnd.github+json"):
    token = os.environ.get("RPGOS_GITHUB_TOKEN", "").strip()
    headers = {"Accept": accept, "User-Agent": "RPG-OS-Updater"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers

def _latest_release():
    repo = os.environ.get("RPGOS_GITHUB_REPO", "").strip()
    if not repo:
        raise HTTPException(status_code=503, detail="RPGOS_GITHUB_REPO is not configured")
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases/latest",
        headers=_update_github_headers()
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"GitHub release lookup failed: {e}")

def _asset(release, name):
    for asset in release.get("assets", []):
        if asset.get("name") == name:
            return asset
    raise HTTPException(status_code=404, detail=f"Release asset not found: {name}")

def _asset_bytes(asset):
    req = urllib.request.Request(
        asset["url"],
        headers=_update_github_headers("application/octet-stream")
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()

@app.get("/v1/updates/latest")
def latest_update():
    release = _latest_release()
    raw = _asset_bytes(_asset(release, "update_manifest.json"))
    return json.loads(raw.decode("utf-8"))

@app.get("/v1/updates/apk")
def latest_update_apk():
    release = _latest_release()
    apk = _asset(release, "RPG-OS.apk")

    def stream():
        req = urllib.request.Request(
            apk["url"],
            headers=_update_github_headers("application/octet-stream")
        )
        with urllib.request.urlopen(req, timeout=180) as r:
            while True:
                chunk = r.read(262144)
                if not chunk:
                    break
                yield chunk

    return StreamingResponse(
        stream(),
        media_type="application/vnd.android.package-archive",
        headers={"Content-Disposition": 'attachment; filename="RPG-OS.apk"'}
    )
