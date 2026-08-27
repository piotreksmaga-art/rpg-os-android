# RPG OS — Phase 48–54 integrated repair candidate

Status: **TARGETED ACCEPTANCE REPAIR IMPLEMENTED / LOCAL ACCEPTANCE GREEN / EXACT-SHA CI PENDING**

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
| Phase50 Combat | incomplete integration | `GREEN` local acceptance — one universal engine, canonical persistent PC/NPC/world-actor/group/unit state, spatial/timing/detection/reaction/clash/contest/objectives/evidence/replay and typed owner materialization |
| Arbitrary/multi-action mechanics | production blocker | `GREEN` — earlier verified effects are projected into later snapshots; additive deltas commit in plan order through one TurnTransaction and failure rolls back the whole turn |
| Phase51–53 | implemented contracts | `GREEN` — pure candidate consistency, factual frontier, bounded no-reroll repair and complete revalidation |
| Phase54 / Android canonical chat | production blocker | `GREEN` local acceptance — Android uses one production composition root; exact four-field receipt identity and replay-bound readback precede narration; durable restart recovery never reruns planner/mechanics/assembler/commit |
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

AI remains a candidate generator. It has no repository, raw database, mechanics outcome or commit authority. Cancellation/failure before commit changes nothing. Immediately after authoritative commit the application persists a recovery marker. Failure after commit returns recoverable committed-without-narration state; restart discovery can rebuild the marker from the latest authoritative receipt, and recovery never repeats planner, mechanics, assembler or commit. Delivery persistence retains semantic claims and player-volition metadata and verifies its fingerprint before reuse.

## Phase50 and scale

The engine is World-Pack-agnostic. `CombatAbilityContractPort` supplies ability shape/range/cost/status bindings. AoE includes blast/cone/line/zone/sweep-style semantic families and has no `FIREBALL` branch in Core.

`UniversalStatusEffectRegistry` owns status identity and stacking policy. A World Pack ability supplies `AbilityStatusApplication`, for example a 20% binding to Core `BURNING`. Unknown private status identities fail closed.

Large battles use bounded aggregate resolution backed by canonical `aggregate_combat_populations` and `aggregate_combat_conditions` state:

- area attack against an aggregate population;
- extreme-power individual/direct attack against a group;
- group-vs-group and unit-vs-unit engagement;
- O(1) distributions for eliminated, wounded, status-affected and unaffected counts;
- deterministic evidence/replay without member expansion.

World actors are materialized once during administrative bootstrap into the `MECHANICAL_ACTOR_AND_AGGREGATE_STATE` truth family. Combat snapshots read that state; they do not regenerate the NPC from a generic template on every turn. Wounds, movement, equipment/structure integrity, morale/cohesion/formation and aggregate casualties use typed canonical changes. The old runtime-counter adapter remains only for replay compatibility and rejects new material effects.

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

- focused repair/provider/schema/production E2E: `GREEN`, including individual combat, two-step staged movement, production AOE against a persisted aggregate group and restart readback;
- Phase54 process-restart recovery: `GREEN`; persisted delivery preserves claims and recovery proves zero repeat assembler/commit calls;
- targeted snapshot retention, campaign isolation and production restart paths: `GREEN` locally;
- unchanged legacy SQLite authority/concurrency suites: post-repair exact revalidation remains assigned to Linux CI; the Windows legacy Robolectric backend cannot register the required connection-local scalar guards, while Windows Defender blocks Robolectric's native test DLL;
- local Android debug compilation: `GREEN` as part of focused Gradle runs;
- Windows snapshot staging paths and campaign-isolated payload names were shortened, closing the SQLite long-path failure in Robolectric without weakening snapshot identity, digest or catalog authority;
- Linux exact-SHA full JVM, signed release APK, signature/digest and immutable provenance: pending final push/CI;
- live Bielik weights/device and live OpenRouter authorization: `PENDING_EXTERNAL_DEPENDENCY`.

## Merge rule

The user/coordinator authorized merge in this run only if the independent audit and exact-SHA CI are green. Any failing test, build, signing, provenance or audit gate restores `STOP / DO NOT MERGE`.
