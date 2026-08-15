# CHAT-7 TEMP GM minimal vertical slice — DEVICE PASS

Work item: `WORK-20260815-001`
Role: `CHAT-7`
Date: 2026-08-15
Status: **PASS**

## Freshness

- master at post-device verification: `9a50d00eb12aff05ceb2ee21f5869d97a5122084`
- CHAT-7 branch device evidence commit: `d8ad9e33f9d1764620d7c16304d92786a76f9d41`
- master advancement after the implementation-stage baseline is documentation/coordination work outside the TEMP harness reserved files; no TEMP harness reservation collision observed in this verification.

## Exact tested profile

- provider ID: `BIELIK_4_5B_V3`
- model: Bielik 4.5B v3 Instruct Q4_K_M GGUF
- runtime: llama.cpp
- backend: Vulkan
- CTX: 8192
- KV K/V: f16/f16
- batch / ubatch: 64 / 64
- parallel slots: 1
- GPU layers: 99
- bridge: `127.0.0.1:8765`
- llama.cpp: `127.0.0.1:8768`

## Device result

Device: Samsung SM-S921B / Android 16.

Observed:

- TEMP_GM_SELFTEST = PASS
- BRIDGE_HEALTH = PASS
- PROVIDER_STATUS = PASS
- GM_TURN = PASS
- canonicalMutation = false
- NPC_SECRET_LEAK = NO
- NPC_KNOWLEDGE_ISOLATION = PASS
- llama.cpp ready in 10 s
- bridge ready in 2 s
- final TEST_STATUS = PASS

The real `/gm/turn` response used logical provider `BIELIK_4_5B_V3`, returned `NARRATIVE_ONLY`, and explicitly returned `canonicalMutation=false`.

The hidden scene-only test secret `ORCHIDEA-917` was not revealed by the NPC. The NPC response stayed within its supplied observation boundary.

## Runtime timing for the tested turn

- prompt: 614 tokens
- completion: 65 tokens
- total: 679 tokens
- prompt eval: 14.03 s / 43.75 tok/s
- completion eval: 9.17 s / 6.98 tok/s
- total generation request: 23.20 s

## Memory observation

Before runtime load:
- available RAM: ~3.0 GiB
- swap used: ~2.1 GiB

With runtime loaded:
- available RAM: ~872 MiB
- swap used: ~2.3 GiB

After stop:
- available RAM: ~4.1 GiB
- swap used: ~2.3 GiB

No crash was recorded during this minimal vertical slice.

## Authority verdict

PASS: TEMP path remained non-authoritative.

No evidence of:
- StatePatch execution,
- COMMIT,
- authoritative PlayerChangeSet,
- Save/DB write,
- canonical event creation,
- bypass of PlayerDomainEngine / WorldRuleProvider / validation layers.

This PASS does not change Phase-19 semantics and does not implement or complete Phase 20+.

## Durable raw evidence

`temp-gm/results/device/2026-08-15_18-01-47-bielik45-tempgm-integration-e2e-ctx8192-f16/`

Important files:
- `summary.txt`
- `gm-turn.json`
- `request.json`
- `health.json`
- `providers.json`
- `active-provider.json`
- `llama-server.log`
- `selftest.txt`
- `SHA256SUMS`

## Note

llama.cpp emitted a warning that CORS allows `*` and no API key is configured. In this test the runtime was bound only to `127.0.0.1:8768`, so the required network exposure boundary remained localhost-only. This warning is retained as hardening evidence, not treated as a canonical architecture blocker.

## Next action

Proceed within WORK-20260815-001 to the TEMP bug-reporting harness: bounded capture, deterministic duplicate handling, offline pending queue, explicit screenshot approval flag, and user-authorized-only GitHub submission path.
