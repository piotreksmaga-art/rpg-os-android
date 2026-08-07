import os, json, uuid, base64, tempfile
from typing import Any, Dict, List, Optional
from fastapi import FastAPI, HTTPException, UploadFile, File, Form
from pydantic import BaseModel, Field
from openai import OpenAI

app = FastAPI(title="RPG OS Backend", version="0.3.0")
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

@app.get("/health")
def health():
    return {"ok": True, "service": "rpg-os-backend", "version": "0.3.0"}

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

