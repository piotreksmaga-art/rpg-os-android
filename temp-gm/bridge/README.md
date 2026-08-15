# TEMP GM Bridge

Non-production CHAT-7 bridge for WORK-20260815-001.

Purpose: expose a localhost-only abstraction between RPG OS Android and a local llama.cpp runtime.

Authority boundary:
- RPG OS remains authoritative.
- TEMP GM cannot write canonical DB/Save/state.
- No StatePatch/COMMIT/authoritative PlayerChangeSet/event creation.
- No bypass of PlayerDomainEngine, WorldRuleProvider, reference validation, validators, or transaction layers.

Default endpoints:
- GET /health
- GET /providers
- GET /active-provider
- POST /active-provider
- POST /gm/turn
- POST /bug

Bridge bind: `127.0.0.1:8765` only.

Current provider candidate:
- logical id: `QWEN3_5_4B`
- runtime: llama.cpp
- runtime endpoint: `http://127.0.0.1:8768`
- model file stays outside the Git repository.

`POST /bug` only captures a local pending report. It never creates a GitHub Issue by itself.

Run in Termux:

```sh
python temp-gm/bridge/temp_gm_bridge.py
```

Environment overrides:
- `TGM_BRIDGE_HOST` must remain `127.0.0.1`
- `TGM_BRIDGE_PORT` default `8765`
- `TGM_QWEN35_URL` default `http://127.0.0.1:8768`
- `TGM_DATA_DIR` default `~/rpgos-temp-gm/bridge-data`

PUBLISHED: NO
