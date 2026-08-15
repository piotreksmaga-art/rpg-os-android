# TEMP GM Bridge

Non-production CHAT-7 bridge for `WORK-20260815-001`.

Purpose: expose a localhost-only abstraction between RPG OS TEMP integration and the selected local Bielik/llama.cpp runtime.

Authority boundary:
- RPG OS remains authoritative.
- TEMP GM cannot write canonical DB/Save/state.
- No StatePatch/COMMIT/authoritative PlayerChangeSet/event creation.
- No bypass of PlayerDomainEngine, WorldRuleProvider, reference validation, validators, or transaction layers.
- Every TEMP GM response carries `canonicalMutation: false`.

Default endpoints:
- `GET /health`
- `GET /providers`
- `GET /active-provider`
- `POST /active-provider`
- `POST /gm/turn`
- `POST /bug`

Bridge bind: `127.0.0.1:8765` only.

Current selected TEMP provider:
- logical id: `BIELIK_4_5B_V3`
- model: Bielik 4.5B v3 Instruct Q4_K_M
- runtime: llama.cpp / Vulkan
- native CTX: 8192
- KV: f16/f16
- batch/ubatch: 64/64
- parallel slots: 1
- GPU layers: 99
- runtime endpoint default: `http://127.0.0.1:8768`
- GGUF path remains outside this API and outside the Git repository.

TEMP modules:
- `temp_gm_provider.py` — `TempGmProvider` and `LocalBielikTempGmProvider`
- `temp_context_builder.py` — bounded CTX=8192 read-only Context Builder with explicit NPC knowledge isolation
- `temp_gm_bridge.py` — localhost bridge and pending bug bundle capture
- `test_bridge_unit.py` — contract/authority/context tests

Response modes:
- `NARRATIVE_ONLY`
- `ENGINE_CONFIRMED`
- `TEST_FALLBACK`

`POST /bug` only captures a local pending report. It never creates a GitHub Issue by itself.

Run in Termux:

```sh
python temp-gm/bridge/temp_gm_bridge.py
```

Environment overrides:
- `TGM_BRIDGE_HOST` must remain `127.0.0.1`
- `TGM_BRIDGE_PORT` default `8765`
- `TGM_BIELIK_URL` default `http://127.0.0.1:8768`
- `TGM_DATA_DIR` default `~/rpgos-temp-gm/bridge-data`

The llama.cpp server is launched separately using the approved device baseline documented in `temp-gm/docs/BIELIK_4.5B_V3_FINAL_TEMP_GM_PROFILE.md`.

PUBLISHED: NO — TEMP harness only.
