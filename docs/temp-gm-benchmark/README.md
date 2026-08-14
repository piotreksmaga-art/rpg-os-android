# CHAT-7 — TEMP LOCAL AI-GM A/B DEVICE BENCHMARK

Status: **IN PROGRESS**  
Published release: **NO**

This directory is a temporary benchmark journal for CHAT-7. It is intentionally isolated from canonical RPG OS authority and from Phase-19/Phase-20+ production contracts.

## Device

- Samsung Galaxy S24 SM-S921B
- Android 16
- 8 GB RAM class (~7.1 GiB visible)
- Termux
- Wireless ADB/self-ADB

## Runtime

- llama.cpp
- revision: `9b05354ec6fb58b4e665e9a39ebc40285c015638`
- backend: CPU first
- context: 4096
- Vulkan: disabled for first round

## Logical TEMP providers

- `LLAMA_3_2_3B`
- `QWEN3_4B`

## Authority boundary

RPG OS remains authoritative. TEMP GM models must not write canonical DB state, execute StatePatch, COMMIT canonical state, create authoritative events, bypass PlayerDomainEngine/reference validation/WorldRuleProvider, auto-create GitHub issues, modify source code, merge, or publish releases.

## Benchmark policy

Both models are tested under as-identical-as-practical prompts, system instructions, context length, CPU backend, thread count, thermal start conditions and RPG OS coexistence conditions. Winner selection must not be based only on tokens/second; stability, RPG quality, context obedience, canonical-state discipline and NPC-knowledge discipline are primary.

## Current checkpoint

Fresh master at branch creation: `7e94dca6f6a94a816a94da253b53fe5955974913`.

Acquisition: PASS for both models.  
Initial load tests: PASS for both models.  
AB-01 Qwen: PASS.  
Remaining AB suite and RPG OS coexistence tests: PENDING.
