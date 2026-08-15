# RPG OS — CHAT-7 TEMP GM Integration Contract

Status: TEMP / NON-PRODUCTION
Work item: `WORK-20260815-001`
Master freshness baseline reviewed: `c86a61f019d8579b970b0c07c8a9df41b922ff83`

## 1. Scope and authority

This contract exists only for the TEMP local AI-GM harness. It does not define or implement canonical AiProvider, Structured GM Output, Mechanics Resolution, Progression, Time Skip, World Simulation, or any Phase-20+ production AI surface.

RPG OS remains authoritative. The TEMP provider, Context Builder, bridge and llama.cpp runtime are read-only with respect to canonical campaign state. A TEMP failure must not mutate Save, DB, PlayerState or authoritative events.

`canonicalMutation` is always `false` for TEMP responses.

## 2. Final logical provider

Logical provider ID exposed by the TEMP harness:

`BIELIK_4_5B_V3`

Provider metadata:

- model family: Bielik 4.5B v3 Instruct
- artifact format: GGUF
- quantization: Q4_K_M
- runtime: llama.cpp
- backend: Vulkan
- native CTX: 8192
- KV: f16/f16
- batch: 64
- ubatch: 64
- parallel slots: 1
- GPU layers: 99
- test runtime flag: `GGML_VK_DISABLE_OCP_FP4=1`

The provider ID intentionally does not expose a GGUF path. Clients must not know the model path or llama.cpp launch details.

## 3. TempGmProvider contract

Minimal TEMP contract:

- `provider_id: str`
- `metadata() -> object`
- `status() -> READY | OFFLINE | ERROR`
- `generate(request) -> TempGmResponse`

`TempGmResponse` contains only:

- `providerId`
- `mode`
- `narrative`
- `canonicalMutation: false`
- optional runtime `usage`
- optional TEMP diagnostics

It contains no StatePatch, PlayerChangeSet, COMMIT instruction or authoritative event.

`LocalBielikTempGmProvider` is the single selected local provider for the current device baseline.

## 4. Localhost bridge contract

Bind: `127.0.0.1:8765` only.

Required endpoints remain:

- `GET /health`
- `GET /providers`
- `GET /active-provider`
- `POST /active-provider`
- `POST /gm/turn`
- `POST /bug`

No new endpoint is required for the minimal vertical slice.

The bridge may talk to a llama.cpp HTTP runtime on another localhost-only port. Neither bridge nor llama.cpp is exposed on `0.0.0.0`.

## 5. TEMP response modes

Allowed modes:

- `NARRATIVE_ONLY` — narrative continuation only; no engine result is asserted.
- `ENGINE_CONFIRMED` — narrative may describe only engine results explicitly supplied in `engineConfirmedResults`.
- `TEST_FALLBACK` — degraded test narration when a requested mechanic/provider capability is unavailable; still non-authoritative.

Unknown requested modes are normalized to `NARRATIVE_ONLY`.

## 6. TEMP Context Builder input

The builder accepts a TEMP request envelope rather than raw canonical World State. Minimal fields:

- `campaignUid`
- `worldPackUid`
- `playerIdentity`
- `sceneState`
- `playerSceneState`
- `relevantNpcs[]`
- `recentDialogueActions[]`
- `retrievedChronicleMemory[]`
- `availableTestCapabilities`
- `engineConfirmedResults`

Each entry in `relevantNpcs[]` has two explicitly separated views:

- `sceneFacts` — facts the system needs to describe the NPC in the current scene.
- `knowledge` — only facts the NPC is permitted to know.

The builder must never convert global/canonical World State into NPC knowledge implicitly.

## 7. NPC knowledge isolation

Every relevant NPC context entry is shaped as:

```json
{
  "npcUid": "...",
  "sceneFacts": {},
  "knowledge": {
    "observed": [],
    "heard": [],
    "told": [],
    "inferred": []
  }
}
```

No `globalWorldState`, unrestricted campaign snapshot, hidden player data, hidden NPC data, GM-only secrets or unrelated Chronicle entries are accepted as NPC knowledge by default.

The boundary is testable: a fact present only in system/global scene data must not appear inside that NPC's `knowledge` section unless explicitly supplied there by the caller.

## 8. CTX=8192 budget

The TEMP Context Builder targets the model's native 8192-token window. Initial hard planning budget:

| Segment | Budget tokens |
|---|---:|
| TEMP system/authority contract | 900 |
| Scene state | 1100 |
| Player state needed in scene | 700 |
| Relevant NPC scene state | 700 |
| NPC knowledge | 1000 |
| Recent dialogue/actions | 1800 |
| Retrieved Chronicle/Memory | 700 |
| Serialization/structural reserve | 268 |
| Model response reserve | 1024 |
| **Total** | **8192** |

The first TEMP implementation may use conservative token estimation because no canonical tokenizer service is introduced by this work item. Trimming order for over-budget context is oldest dialogue/actions first, then lowest-priority retrieved Chronicle/Memory, then lowest-priority nonessential scene description. Authority rules, current scene essentials, current player scene state and NPC knowledge boundaries are never removed to make room.

## 9. Minimal `/gm/turn` request

```json
{
  "message": "player declaration",
  "mode": "NARRATIVE_ONLY",
  "context": {
    "campaignUid": "...",
    "worldPackUid": "...",
    "playerIdentity": {},
    "sceneState": {},
    "playerSceneState": {},
    "relevantNpcs": [],
    "recentDialogueActions": [],
    "retrievedChronicleMemory": [],
    "availableTestCapabilities": [],
    "engineConfirmedResults": []
  }
}
```

## 10. Minimal `/gm/turn` response

```json
{
  "providerId": "BIELIK_4_5B_V3",
  "mode": "NARRATIVE_ONLY",
  "narrative": "...",
  "canonicalMutation": false,
  "usage": {}
}
```

## 11. Forbidden TEMP behavior

The integration must reject or ignore any request attempting to grant the model authority to write canonical state. The TEMP layer does not execute StatePatch, COMMIT, PlayerChangeSet, authoritative events, Save writes, DB writes, or rule bypasses.

## 12. Current collision/freshness audit

At master `c86a61f019d8579b970b0c07c8a9df41b922ff83`, `WORK-20260815-001` remains ACTIVE and reserves the TEMP branch, TEMP bridge/provider abstractions, TEMP benchmark/evidence and TEMP bug harness. Canonical PlayerDomainEngine, WorldRuleProvider, transaction/invariant layers and production AI surfaces remain explicitly outside reservation.

The current TEMP branch is diverged from master and is intentionally isolated; the post-baseline master changes concern canonical Phase-19 authority hardening/CI. This contract does not modify those canonical paths or semantics.

No collision requiring canonical contract modification was identified for the minimal TEMP-only vertical slice.
