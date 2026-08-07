import os, json, uuid, base64, tempfile
from typing import Any, Dict, List, Optional
from fastapi import FastAPI, HTTPException, UploadFile, File, Form
from pydantic import BaseModel, Field
from openai import OpenAI

app = FastAPI(title="RPG OS Backend", version="0.3.0")
def get_client():
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        return None
    return OpenAI(api_key=key)

def is_mock():
    return os.environ.get("RPGOS_OFFLINE_MOCK", "0") == "1"

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
Never invent NPC knowledge that is absent from npc_knowledge.
Respect canon_constraints unless campaign state has already diverged.
Narration must be in Polish.
The state_patch must contain only concrete state changes caused by this turn.
Do not write to reference/canon/legacy tables; Android validates all patches anyway.
Do not include hidden GM-only information in narration unless the player has discovered it.
"""

@app.get("/health")
def health():
    client = get_client()
    return {
        "ok": True,
        "service": "rpg-os-backend",
        "version": "1.0.1",
        "openai_key_configured": client is not None,
        "mock_mode": is_mock(),
        "text_model": os.environ.get("RPGOS_MODEL", "gpt-5.2"),
        "image_model": os.environ.get("RPGOS_IMAGE_MODEL", "gpt-image-1")
    }

@app.post("/v1/gm/turn", response_model=TurnResponse)
def gm_turn(req: TurnRequest):
    if is_mock():
        return {
            "narration": "[TRYB TESTOWY BACKENDU] Otrzymałem ruch gracza: " + req.player_input,
            "choices": ["Kontynuuj", "Sprawdź status", "Rozejrzyj się"],
            "state_patch": None,
            "chapter_events": []
        }

    client = get_client()
    if client is None:
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")

    model = os.environ.get("RPGOS_MODEL", "gpt-5.2")
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
    if is_mock():
        raise HTTPException(status_code=503, detail="Image generation disabled in mock mode")

    client = get_client()
    if client is None:
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")

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
    if is_mock():
        raise HTTPException(status_code=503, detail="Image editing disabled in mock mode")

    client = get_client()
    if client is None:
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


@app.get("/v1/openai/check")
def openai_check():
    if is_mock():
        return {"ok": True, "mode": "mock", "message": "Backend działa w trybie testowym."}

    client = get_client()
    if client is None:
        return {"ok": False, "mode": "live", "message": "Brak OPENAI_API_KEY."}

    model = os.environ.get("RPGOS_MODEL", "gpt-5.2")
    try:
        response = client.responses.create(
            model=model,
            input="Reply with exactly: RPG_OS_BACKEND_OK"
        )
        return {
            "ok": "RPG_OS_BACKEND_OK" in response.output_text,
            "mode": "live",
            "model": model,
            "output": response.output_text
        }
    except Exception as e:
        return {"ok": False, "mode": "live", "model": model, "message": str(e)}
