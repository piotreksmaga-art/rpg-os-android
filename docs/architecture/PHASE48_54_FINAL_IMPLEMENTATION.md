# RPG OS — Phase 48–54 integrated repair candidate

Status: **IMPLEMENTATION + PRODUCTION INTEGRATION COMPLETE / FOCUSED GREEN / EXACT-SHA CI AND COORDINATOR ACCEPTANCE PENDING**

Branch: `codex/phase-48-54-repair`

Base master SHA: `0ea25f1abb4b9e7639058df5c48466e4f5f3d70e`

Authoritative input: `CODEX_PLAN_POPRAWKI_48-54.md`. `poprawki_48-54.md` was reference material. The older `PHASE48_54_VERTICAL_SLICE_ACCEPTANCE.md` remains historical evidence only.

## Status matrix

| Area | Status before repair | Repair-candidate status |
|---|---|---|
| Phase48 provider contracts/routing | implemented | `GREEN` — one semantic port and common conformance suite |
| Local Android AI | JNI boundary without packaged runtime | `GREEN` at implementation/package gate — official ExecuTorch Android AAR, package import, tokenizer/model validation, admission and lifecycle are wired; compatible weights and physical-device performance remain external evidence |
| OpenRouter | concrete adapter | `GREEN` controlled — official PKCE/loopback flow, Android Keystore, discovery, typed 429/cancel, workload-specific strict JSON Schema plus Core revalidation; live authorization/network remain external evidence |
| Phase49 | implemented contract | `GREEN` — proposal identity, actor/action/target/modality/dependencies/agency and malformed-output rejection |
| Phase50 Combat | incomplete integration | `GREEN` focused — one universal engine, persistent non-player state, spatial/timing/detection/reaction/clash/contest/objectives/evidence/replay and owner-routed materialization |
| Arbitrary/multi-action mechanics | production blocker | `GREEN` — staged execution through one canonical assembler and existing TurnTransaction; failure rolls back the turn |
| Phase51–53 | implemented contracts | `GREEN` — pure candidate consistency, factual frontier, bounded no-reroll repair and complete revalidation |
| Phase54 / Android canonical chat | production blocker | `GREEN` focused — Android uses one production composition root; commit precedes exact readback/narration; restart recovery does not rerun mechanics |
| Universal character creation | missing | `GREEN` focused — provider-independent draft, active World Pack definitions, separate fingerprinted confirmation and atomic bootstrap |
| Phase63 required seam | missing | `GREEN` pulled-forward slice only — aggregate population input for Phase50; no World Simulation lifecycle |
| Phase65 Director slice | already implemented | preserved; no new authority and no expansion of Phase65 scope |

Global roadmap status remains `[-]` until exact-SHA CI and coordinator acceptance. This record does not self-promote Phase48–54 to global `[x]`.

## Canonical runtime

```text
Android Chat UI
  -> CanonicalChatApplication
  -> ProductionGameEngineCompositionRoot
  -> role-aware AiProvider (controlled | local ExecuTorch | OpenRouter)
  -> IntentDocument + Phase43 validation/resolution
  -> GraphTurnPlanner + CapabilityEnvelope
  -> Phase38-safe projected context + semantic budget/completion
  -> Structured GM Proposal
  -> Phase50 mechanics / existing domain owners
  -> Phase51 consistency + Phase52 factual frontier
  -> bounded Phase53 repair + full revalidation
  -> staged CanonicalCampaignMutationProposal
  -> existing TurnTransaction (exactly once)
  -> persisted receipt + exact Phase38 post-commit readback
  -> Phase54 narrative validation/repair/fallback
  -> idempotent player-visible delivery
```

AI remains a candidate generator. It has no repository, raw database, mechanics outcome or commit authority. Cancellation/failure before commit changes nothing. Failure after commit returns recoverable committed-without-narration state; recovery starts from the receipt and never repeats mechanics or commit.

## Phase50 and scale

The engine is World-Pack-agnostic. `CombatAbilityContractPort` supplies ability shape/range/cost/status bindings. AoE includes blast/cone/line/zone/sweep-style semantic families and has no `FIREBALL` branch in Core.

`UniversalStatusEffectRegistry` owns status identity and stacking policy. A World Pack ability supplies `AbilityStatusApplication`, for example a 20% binding to Core `BURNING`. Unknown private status identities fail closed.

Large battles use bounded aggregate resolution:

- area attack against an aggregate population;
- extreme-power individual/direct attack against a group;
- group-vs-group and unit-vs-unit engagement;
- O(1) distributions for eliminated, wounded, status-affected and unaffected counts;
- deterministic evidence/replay without member expansion.

The Phase63 pulled-forward seam supplies only aggregate population state. Phase63 remains owner of LOD promotion/coarsening, conservation across LOD, background simulation and World Actor lifecycle.

## Universal new-campaign character creation

The GM can gather user choices and produce a complete `PlayerCharacterCreationDraft`: identity, gender, stats, resources, talent, potential, skills, techniques, origins, innate features and starting position. The draft cannot mutate state. A separate confirmation must match the draft fingerprint, after which `PlayerCharacterBootstrapService` commits the whole character atomically and makes it active.

Definitions come from the active World Pack typed schema. A narrow compatibility bridge reads supported legacy tables. A valid pack with no character schema receives a namespaced, genre-neutral fallback; missing World Pack authority never causes fabricated definitions. Naruto-specific content is not part of the Core creator.

## Provider conformance and OpenRouter

The same semantic probe runs against controlled, real LocalAiPort and real CloudAiPort adapter paths. It checks cancellation, identity, actor/action/target, Phase43 validation, structured proposal, agency, invented ability, bounded repair, hidden-marker leakage and absence of provider mutation methods.

OpenRouter uses OAuth PKCE with an ephemeral localhost callback, `/api/v1/auth/keys`, model discovery and `/api/v1/chat/completions`. When a model advertises structured output, requests use a named strict `json_schema` for the exact workload and require compatible provider parameters. Core decoding/validation remains authoritative.

Official references:

- <https://openrouter.ai/docs/guides/overview/auth/oauth>
- <https://openrouter.ai/docs/guides/features/structured-outputs>
- <https://openrouter.ai/docs/guides/overview/models>
- <https://openrouter.ai/docs/quickstart>

## Verification state before final push

- focused repair/provider/schema/production E2E: `GREEN`;
- legacy migration/backup routing regressions: `GREEN` after missing-World-Pack fail-closed repair;
- financial concurrency/idempotency and closed-account invariants: `GREEN`;
- local Android debug compilation: `GREEN` as part of focused Gradle runs;
- local full JVM attempt: environment-invalid after Windows Defender blocked Robolectric's extracted native runtime; this caused cascading missing SQLite native functions and is not recorded as green;
- Linux exact-SHA full JVM, signed release APK, signature/digest and immutable provenance: pending final push/CI;
- live Bielik weights/device and live OpenRouter authorization: `PENDING_EXTERNAL_DEPENDENCY`.

## Merge rule

`STOP / DO NOT MERGE / WAIT FOR COORDINATOR AUDIT`.

Exact-SHA CI and a signed validation artifact are candidate evidence, not permission to merge or global acceptance.
