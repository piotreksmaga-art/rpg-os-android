# CHAT-7 — TEMP GM Integration Bootstrap / Contract Evidence

Work item: `WORK-20260815-001`
Date: 2026-08-15
Status: **IMPLEMENTATION READY FOR DEVICE E2E; DEVICE PASS NOT YET CLAIMED**

## Role bootstrap / freshness

Mandatory source-of-truth review was performed against current master documents:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/PARALLEL_WORK_COORDINATION.md`
- `WORK-20260815-001`

Master at initial implementation bootstrap: `c86a61f019d8579b970b0c07c8a9df41b922ff83`.

Pre-evidence freshness recheck: master advanced to `adb6ba27ca0a06cded24930715235a852e7975ab`. The new head is an audit-document commit (`docs: add CHAT-2 Phase 19 atomic authority revalidation`) validating runtime SHA c86a61f; it does not modify CHAT-7 TEMP harness files or authorize a canonical production contract change.

`WORK-20260815-001` remains the active reservation for the TEMP bridge/provider/context/bug harness. No canonical PlayerDomainEngine, WorldRuleProvider, transaction/invariant or production AI surface is reserved by CHAT-7.

## Existing TEMP audit

The pre-existing bridge was localhost-only and non-authoritative, but still registered an obsolete Qwen provider (`QWEN3_5_4B`) and directly assembled a loose context object. It therefore did not reflect the finalized Bielik baseline or the newly required explicit NPC knowledge boundary/CTX budget.

## Contract decision

Final logical provider ID:

`BIELIK_4_5B_V3`

Model baseline remains:

- Bielik 4.5B v3 Instruct Q4_K_M
- llama.cpp / Vulkan
- CTX 8192
- KV f16/f16
- batch 64
- ubatch 64
- np 1
- ngl 99
- device test flag `GGML_VK_DISABLE_OCP_FP4=1`

Contract document:

`temp-gm/docs/TEMP_GM_INTEGRATION_CONTRACT.md`

## Implemented TEMP-only vertical-slice components

- `temp-gm/bridge/temp_gm_provider.py`
  - `TempGmProvider`
  - `LocalBielikTempGmProvider`
  - non-authoritative `TempGmResponse`
- `temp-gm/bridge/temp_context_builder.py`
  - native CTX=8192 planning budget
  - 1024-token output reserve
  - deterministic optional-context trimming
  - explicit `relevantNpcs[].sceneFacts` vs `relevantNpcs[].knowledge` separation
  - accepted NPC knowledge categories only: observed/heard/told/inferred
- `temp-gm/bridge/temp_gm_bridge.py`
  - final Bielik provider wiring
  - localhost-only bridge and runtime validation
  - required existing endpoints retained
  - required TEMP response modes retained
  - `canonicalMutation=false`
- `temp-gm/bridge/selftest_temp_gm.py`
  - stdlib-only fake-runtime provider/context/authority self-test
- `temp-gm/bridge/test_bridge_unit.py`
  - contract and knowledge-isolation regression assertions
- `temp-gm/bridge/device_e2e_bielik.sh`
  - real-device minimal vertical slice using approved Bielik baseline
  - automatic durable evidence publication

## CTX planning budget

- system/authority contract: 900
- scene state: 1100
- player state needed in scene: 700
- relevant NPC scene state: 700
- NPC knowledge: 1000
- recent dialogue/actions: 1800
- retrieved Chronicle/Memory: 700
- serialization reserve: 268
- response reserve: 1024
- total: 8192

The TEMP builder does not implement a future canonical Memory Engine.

## Authority result

Canonical mutation possible through the implemented TEMP response contract: **NO**.

The bridge/provider/context modules do not import, call or bypass canonical PlayerDomainEngine/Save/DB/StatePatch/COMMIT surfaces.

Phase-19 semantics changed: **NO**.

Phase-20+ implemented: **NO**.

## Current test status

Repository-side contract/static audit: complete.

Device E2E: **PENDING USER DEVICE EXECUTION** because real llama.cpp/Vulkan/Bielik inference exists only on the Samsung/Termux device.

No E2E PASS is claimed until the device script completes and its evidence is published.

## Commits in this integration step

- `6b5275efbe73b922ee4e7402121150d201aceaa6` — integration contract
- `34b0a1c67ab6e4ec7d5b0e4a92c5ab8379b9ccc7` — TempGmProvider / LocalBielik provider
- `eaa91a13f5052339660b3afda396e83305fbc404` — initial bounded context builder
- `4970dfe83544b4717b04af1278edd7199febb613` — bridge wired to Bielik/provider/context
- `b82af4900d35a58d87bc9bf9a6f194e948cce646` — import-safe unit tests
- `82dcbe24076efb6ff2650c0b95c9b97c9b4f7a03` — bridge README baseline
- `0d34cf8011403becc30e8f8924c2d2e40272df10` — tightened CTX accounting
- `59fe7d489a033ba5620473d7e7846c96c4da774a` — stdlib self-test
- `2a9425a71df88d0f442f3f399723a9700560f30c` — real-device E2E runner

Next action: execute `device_e2e_bielik.sh` on the target device, verify self-test + bridge + provider + GM turn + NPC secret non-leak + zero canonical mutation, and publish the generated evidence bundle.
