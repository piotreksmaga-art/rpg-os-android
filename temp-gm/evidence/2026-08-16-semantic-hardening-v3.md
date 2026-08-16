# CHAT-7 TEMP GM semantic hardening v3

Date: 2026-08-16
Work item: WORK-20260815-001

## Input evidence
LIVE semantic retest #2 device evidence commit: `260fa86f3f0f9722af625567c7d5a3e13f4fc523`.

Observed failures:
- model emitted diagnostic-looking labels repeatedly in CASE_01..08;
- CASE_01 and CASE_03 reached maxTokens=1024 instead of respecting the requested short stop point;
- CASE_10 returned HTTP 502 at ~180.176 s, matching the current provider timeout boundary;
- canonicalMutation remained false for all 9 successful HTTP responses.

## Root cause addressed in v3
The v2 system prompt itself contained literal diagnostic/test marker tokens and explicit technical labels such as internal/read-only context and canonical mutation metadata. Bielik repeatedly echoed those tokens into player-facing narration. This is prompt-token priming and is distinct from canonical-state mutation.

## v3 change
`temp_context_builder.py` now uses positive-only, player-facing instructions:
- output is ordinary Polish narration, normally 2–5 short sentences;
- player action/dialogue/movement source remains user declaration only;
- actor/action/target direction remains fixed;
- NPC autonomy remains allowed;
- response stops after immediate result/reaction;
- no invented player capabilities or follow-up actions;
- control/input structure must not be exposed;
- NPC knowledge boundary remains explicit;
- persistent/canonical state remains outside the model.

The model-facing prompt no longer contains the literal diagnostic markers that appeared in retest #2, does not expose the TEMP mode name, and does not include canonicalMutation metadata. Transport response still returns `canonicalMutation=false` through `TempGmResponse`.

## Scope intentionally unchanged
No change to:
- Bielik model or Vulkan profile;
- CTX=8192 / KV=f16 / batch parameters;
- provider timeout=180 s;
- Android timeout=210 s;
- maxTokens=1024;
- Phase 19 / Phase 20+ / canonical state contracts.

## Next gate
Execute LIVE semantic retest #3 against the exact v3 semantic HEAD. CHAT-6 remains blocked until 10/10 live cases pass under the strict semantic criteria. Timeout contract must not be tuned until semantic behavior passes.
