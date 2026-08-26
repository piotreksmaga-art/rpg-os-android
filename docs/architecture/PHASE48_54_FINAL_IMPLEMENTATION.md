# RPG OS — Phase 48–54 final-plan implementation record

Status: **IMPLEMENTED CANDIDATE / CONTROLLED BACKEND GREEN / LIVE EVIDENCE AND TWO PRODUCTION INTEGRATION GATES PENDING**

Work branch: `codex/phase-48-54-final`

Authoritative input: `CODEX_PLAN_48_54_FINAL.md`. `MGAI.md` and `Analiza_48-54.md` were treated as reference material. This record supersedes the scope description in `PHASE48_54_VERTICAL_SLICE_ACCEPTANCE.md`; the older document remains historical evidence for the previously merged slice.

## Result by status vocabulary

| Area | Status | Evidence / remaining gate |
|---|---|---|
| Provider-independent contracts, role assignments and deterministic Auto routing | `IMPLEMENTATION_COMPLETE` | One AI system; GM and Director can independently use Auto or a pinned local/cloud model. Routing checks workload, context, availability, privacy and local admission. |
| Universal `LocalAiPort`, model/artifact/settings/admission/runtime lifecycle contracts | `IMPLEMENTATION_COMPLETE` | Bielik 4.5B v3 Instruct is the first data profile. CTX, KV, backend, threads, prefill and quantization are capability checked. |
| Android JNI local adapter | `CONCRETE_ADAPTER_GREEN` for controlled driver contract; `ACTUAL_IMPLEMENTATION_BLOCKER` for production inference | The repository does not contain the `rpgos_ai_runtime` native library. Importing weights alone cannot produce real Bielik inference until a compatible packaged implementation is supplied. |
| Real-device Bielik | `LIVE_EVIDENCE_PENDING_EXTERNAL_DEPENDENCY` | Requires compatible weights, packaged native runtime and a physical Android device with sufficient memory. |
| Universal `CloudAiPort` and OpenRouter adapter | `IMPLEMENTATION_COMPLETE`; `CONCRETE_ADAPTER_GREEN` | OAuth PKCE, Android Keystore credential storage, model discovery, structured chat execution, cancellation, usage and typed 429 handling are implemented. Credentials never enter campaign/save state. |
| Live OpenRouter | `LIVE_EVIDENCE_PENDING_EXTERNAL_DEPENDENCY` | Requires user authorization/network. No credential was available in this work block. |
| Phase49 structured GM proposal | `IMPLEMENTATION_COMPLETE` | Strict identity, actor/action/target/modality, dependency, provenance and player-volition validation. |
| Phase50 universal mechanics contracts | `IMPLEMENTATION_COMPLETE` for the new universal mechanics layer; `ACTUAL_IMPLEMENTATION_BLOCKER` for full legacy-domain materialization | Mechanical views cover PC/former PC/NPC/monster/summon/vehicle/unit/group/world actor; generation, perception-gated reactions, typed effects and replay evidence exist. Historical Core has no complete production resolution-component composition for arbitrary chat mechanics (for example movement), so UI must not fabricate a canonical mutation. |
| Phase51 candidate-state consistency | `IMPLEMENTATION_COMPLETE` | Inventory, ownership, finance conservation, progression, location, mutually exclusive effects and temporal/world constraints are validated on a pure candidate projection. |
| Phase52 factual frontier | `IMPLEMENTATION_COMPLETE` | Unsupported facts, belief/narrative promotion, future/counterfactual promotion and out-of-scope subjects/effects fail closed. |
| Phase53 repair | `IMPLEMENTATION_COMPLETE` | Bounded repair preserves verified mechanics, forbids rerolls/entitlement expansion and revalidates the whole candidate. Narration repair is independently bounded. |
| Phase54 committed narration | `IMPLEMENTATION_COMPLETE` at Core/application contracts | Narration receives exact persisted receipt evidence and a Phase38 player-visible post-commit readback only. Semantic validation, bounded repair, natural fallback, delivery idempotency and restart recovery never rerun mechanics or commit. |
| Production Android canonical chat composition | `ACTUAL_IMPLEMENTATION_BLOCKER` | `CanonicalChatApplication` is ready, but wiring arbitrary natural-language actions to the historical `PlayerDomainEngine` still lacks production resolution components for all required domains. The former `ViewModel -> StatePatch` write was removed. The legacy backend is quarantined as narration-only and its patch is discarded. |
| Required Director slice (real owner Phase65) | `IMPLEMENTATION_COMPLETE` for required slice / Phase65 remains `PARTIAL` | Versioned candidate bundles, triggers/cadence, async jobs, stale/cancel/dedup/idempotency/provenance validation; never direct mutation. |
| Controlled test backend | `CONTROLLED_BACKEND_GREEN` | Focused Phase43–54 and final-plan tests pass locally. |

Nothing in the two `ACTUAL_IMPLEMENTATION_BLOCKER` rows may be relabelled as an external credential/model gate. They require repository implementation work. Conversely, absence of weights, a device or an OpenRouter authorization does not downgrade independently completed contracts.

## Canonical runtime

```text
Chat UI
  -> ChatApplicationPort
  -> CanonicalChatApplication
  -> AiChatEngineFacade
  -> role-aware ModelRouter
  -> AiProvider
       -> LocalAiPort -> LocalInferenceRuntime
       -> CloudAiPort -> OpenRouter
  -> IntentDocument validation and trusted reference resolution
  -> GraphTurnPlanner + CapabilityEnvelope
  -> Phase38-projected context integrity/budget/completion
  -> GmProposalCandidate
  -> mechanics + candidate-state consistency + factual frontier
  -> bounded repair and complete revalidation
  -> sealed CanonicalCampaignMutationProposal
  -> existing TurnTransaction
  -> persisted V3 receipt + exact post-commit readback
  -> committed narrative validator/repair/fallback
  -> idempotent delivery
```

Authority invariants:

- AI output is a candidate, never canonical truth or mutation authority.
- local and cloud receive the same semantic entitlement, with cloud-minimised projected payloads;
- cancellation before commit mutates nothing;
- cancellation/failure after commit produces a recoverable committed-without-narration state;
- recovery begins from persisted receipt/readback and cannot rerun planning, mechanics or commit;
- factual outcome is never streamed before commit;
- player volitional action is sourced only from validated player input.

## One AI system and Director ownership

There is no top-level Local/Cloud/Hybrid mode. Settings persist separate GM and Director role assignments:

```text
Game Master: Auto | compatible local model | compatible cloud model
Director:     Auto | compatible local model | compatible cloud model
```

The Director implementation is owned by Phase65. It emits future strategic candidates only; cadence is independent from provider assignment and normal turns never wait for it.

## UI and credential boundary

The Provider Center exposes role assignment, Bielik artifact/settings/admission state, OpenRouter connect/disconnect/model discovery, privacy policy and Director status. Android Keystore stores the OpenRouter credential outside Campaign State. The OAuth callback uses an ephemeral loopback endpoint permitted by OpenRouter's official PKCE flow.

The UI no longer calls `LocalGameStore.applyPatch`. Until the missing canonical production composition is implemented, the pre-Phase48 backend may return prose only. Any legacy `StatePatch` is discarded and the UI explicitly says that no canonical state was changed.

## Verification

- focused final-plan suite: GREEN;
- production Kotlin compilation: GREEN;
- release-workflow separation check: GREEN;
- local full JVM attempt: 1062 tests, 193 historical Windows/Robolectric failures caused by sqlite4java lacking the accepted runtime's SQLite features (`UPSERT`, `VACUUM INTO`, custom authority functions); Linux exact-SHA CI is the authoritative regression gate;
- exact-SHA Linux CI and signed APK: pending push at the time this record was written;
- real-device Bielik/OpenRouter: pending as classified above.

Official OpenRouter references used for the adapter:

- OAuth PKCE: <https://openrouter.ai/docs/guides/overview/auth/oauth>
- authorization-code exchange: <https://openrouter.ai/docs/api/api-reference/o-auth/exchange-auth-code-for-api-key>
- model discovery: <https://openrouter.ai/docs/api/api-reference/models/get-models>
- API quickstart: <https://openrouter.ai/docs/quickstart>

## Merge rule

This branch must not be merged without coordinator authorization. Exact-SHA CI and the signed artifact provide candidate evidence, not global acceptance.
