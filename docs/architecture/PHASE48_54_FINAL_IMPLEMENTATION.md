# RPG OS — Phase 48–54 integrated repair candidate

Status: **TARGETED ACCEPTANCE REPAIR IMPLEMENTED / LOCAL ACCEPTANCE GREEN / EXACT-SHA CI PENDING**

Branch: `codex/bekko-bielik-emulator-fix`

Base master SHA: `8ac8ce50549b96198b85e55f375a32dde886dd79`

Final code-bearing SHA jest rejestrowany po merge przez exact-SHA CI i immutable release provenance; dokument źródłowy nie deklaruje własnego przyszłego SHA.

Authoritative input: `CODEX_PLAN_POPRAWKI_48-54.md`. `poprawki_48-54.md` was reference material. The older `PHASE48_54_VERTICAL_SLICE_ACCEPTANCE.md` remains historical evidence only.

## Status matrix

| Area | Status before repair | Repair-candidate status |
|---|---|---|
| Phase48 provider contracts/routing | implemented | `GREEN` — one semantic port and common conformance suite |
| Local Android AI | concrete ExecuTorch boundary | `GREEN` at implementation/artifact gate — official ExecuTorch Android AAR, package import, tokenizer/model validation, admission and lifecycle are wired; complete Bielik 1.5B v3 XNNPACK package is the mobile default while the 4.5B profile remains compatible/reference; physical-device PTE performance remains external evidence |
| OpenRouter | concrete adapter | `GREEN` controlled — official PKCE/loopback flow completes code exchange before the callback can report success, credentials remain in Android Keystore, typed transport reasons survive to diagnostic UI, model discovery/inference use workload-specific strict JSON Schema plus Core revalidation; live authorization/network remain external evidence |
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

OpenRouter uses OAuth PKCE with an ephemeral localhost callback, `/api/v1/auth/keys`, model discovery and `/api/v1/chat/completions`. The browser callback is not considered successful until the application has exchanged the code, validated the result and stored the credential; a failed exchange renders a failure page and preserves a typed, secret-free reason for the Android UI. The manually entered API-key path is an explicit secondary recovery route and validates the key online before encrypted storage. When a model advertises structured output, requests use a named strict `json_schema` for the exact workload and require compatible provider parameters. Core decoding/validation remains authoritative.

Official references:

- <https://openrouter.ai/docs/guides/overview/auth/oauth>
- <https://openrouter.ai/docs/guides/features/structured-outputs>
- <https://openrouter.ai/docs/guides/overview/models>
- <https://openrouter.ai/docs/quickstart>

## Mobile Bielik artifact

The release artifact `RPG-OS-Bielik-1.5B-v3-ExecuTorch-XNNPACK.zip` is a complete importable package, not a launcher or metadata-only placeholder. It contains the exported `model.pte`, `tokenizer.json`, manifest, notice and Apache-2.0 license.

- source: official `speakleash/Bielik-1.5B-v3.0-Instruct-FP8-Dynamic`, revision `9392d5165c964fa5482abc9e2e60a8bf0d7bc5ce`;
- export: ExecuTorch 1.3.x, XNNPACK, 8da4w linear + 8w embedding, maximum sequence length 2048;
- `model.pte`: 923,083,008 bytes, SHA-256 `4e5a6b8e6684e94d794a609a2f76cfb56f3b3ddef3dfc96904cd10f40244457e`;
- ZIP: 926,801,866 bytes, SHA-256 `d79d42d6a0bea8b21e9bcd4e00424be451c4167d5329d7bd9515987bdbb3181a`;
- host-side ExecuTorch load: `GREEN`, with exported metadata methods including `forward`, tokenizer IDs, vocabulary, layer/head counts and max sequence length;
- physical-device load, inference speed, memory and thermal evidence: still an external acceptance gate.

Historical device evidence remains relevant but separate: Bielik 4.5B v3 GGUF Q4_K_M passed on Samsung SM-S921B with llama.cpp/Vulkan, CTX8192, KV f16, `-ngl 99`, `-b 64`, `-ub 64`. That proves the 4.5B class is viable on the target phone, but it is not evidence that the separate ExecuTorch PTE profile has passed on-device.

## Production llama.cpp / GGUF provider

Android can select an arbitrary local GGUF file and run it through a statically linked llama.cpp provider with CPU or Vulkan. This is not a side chat or narration-only bridge: `LOCAL:LLAMA_CPP_VULKAN` is registered in the same role-aware provider registry and reaches intent interpretation, GM proposal/repair, character creation, narrative render/repair and periodic Director workloads through `LocalAiPort` and the canonical Phase43–54 pipeline.

The UI persists the selected engine and manual context, backend, GPU layers (`-1` means all), CPU threads, batch, ubatch, K/V cache types, temperature, top-k, top-p, repeat penalty, Flash Attention and mmap choices. The recommended Vulkan/Xclipse profile is CTX8192, 99 GPU layers, F16 K/V and batch/ubatch 64. GGUF settings are user-managed: the admission controller does not impose RAM, thermal, context or GPU-layer limits. Basic value validity and backend/format compatibility remain necessary to form a callable runtime request.

llama.cpp runs in the private `:local_ai` process through AIDL. A native abort, driver failure or model OOM can stop that process without terminating the UI process or partially mutating a turn. The process boundary is operational containment, not a restriction on GGUF parameters. AI output still passes the same typed JSON decoder, semantic validators, mechanics resolution and commit authority as every other provider; a model never receives canonical mutation authority.

The APK contains the ARM64 llama.cpp runtime and Vulkan shaders but not user-selected model weights. GGUF remains an imported content artifact, so changing a model does not require rebuilding the APK. Current native dependencies are pinned as Git submodules to exact llama.cpp, Vulkan-Headers and SPIRV-Headers revisions and CI checks them out recursively.

## Verification state before final push

- focused repair/provider/schema/production E2E: `GREEN`, including individual combat, two-step staged movement, production AOE against a persisted aggregate group and restart readback;
- guarded campaign clone/bootstrap regression: `GREEN`; schema/default-definition preparation is ADMIN-authorized and lifecycle-serialized before gameplay readiness;
- same-campaign concurrent identical turn: `GREEN`; exactly one canonical commit and one replay, without SQLite busy leakage;
- Phase54 process-restart recovery: `GREEN`; persisted delivery preserves claims and recovery proves zero repeat assembler/commit calls;
- targeted snapshot retention, campaign isolation and production restart paths: `GREEN` locally;
- pełny finalny zestaw JVM/Robolectric po wszystkich poprawkach: `GREEN` — `1261/0/0`, bez pominiętych testów;
- local Android debug compilation: `GREEN` as part of focused Gradle runs;
- Android API 36 x86_64 emulator with the complete Bielik 1.5B PTE/tokenizer: `GREEN` for install, campaign resume, isolated model load, 1549-token real Naruto character-creation prompt and typed `Q` continuation in the UI; the small-model adapter bounds output inside CTX2048, stops at the first complete JSON and may recover only a malformed non-mutating question while every malformed `R` draft remains fail-closed;
- fizyczna Motorola Edge 30 Neo / Android 14, `labDebug` Bridge Stage 3 + `LAB_CODEX`: `GREEN` dla rzeczywistego utworzenia kampanii i postaci, 10 tur jakościowych, zaakceptowanego Directora i późniejszego użycia guidance, mechaniki bez fałszywego obrażenia, a następnie 100 canonical tur od order 14 do 114 przez prawdziwy `ChatApplicationPort`; harness zatrzymał się na dwóch rzeczywistych findingach, po poprawkach powtórzono dokładne akcje i wznowiono sekwencję do celu. Finalne fingerprinty to semantic `912d9ba9816b98da71716c200d30f6b26eec6b0bb629c22d45a4d7adc343aef8` i result `51edbc5929ad5d3e97760e96459f31acc4ee231d70e5b5c4cb3359cbc974353c`;
- restart po setnej turze: `GREEN`; campaign/player/order oraz oba fingerprinty wróciły bez pending recovery, ponownego planowania, mechaniki lub commitu;
- Director w tym przebiegu: `12/12` zaakceptowanych pakietów, ostatni przy order 110; osobna kolejka Directora nie blokowała MG;
- Bekko na tym samym urządzeniu: audience-scoped search po 100 turach zwrócił trzy rekordy treningowe; szybkie przełączenie `Test_Motorola_2 -> Akceptacja_faz_48_54_v2` podczas pracy indeksu zachowało oba zapisy i proces aplikacji;
- real-device finding zamknięty w kandydacie: asynchroniczny worker Bekko mógł wcześniej wyrzucić nieobsłużony `SQLITE_SCHEMA` podczas zmiany kampanii. Koordynator jest teraz związany z jednym campaign UID, anuluje pracę przy zmianie i zamienia każdy błąd rebuildable cache na typed fallback zamiast kończyć proces;
- Windows snapshot staging paths and campaign-isolated payload names were shortened, closing the SQLite long-path failure in Robolectric without weakening snapshot identity, digest or catalog authority;
- Linux exact-SHA full JVM, signed release APK, signature/digest and immutable provenance: pending final push/CI;
- complete Bielik 1.5B mobile weights/package and host-side ExecuTorch load: `GREEN`; pierwsza próba na fizycznym Samsungu ujawniła native-process crash podczas inferencji. Od alpha14 ExecuTorch działa w prywatnym procesie `:local_ai`, więc awaria runtime nie zamyka UI ani nie dotyka canonical state; zgodność samego PTE/runtime nadal pozostaje `PENDING_EXTERNAL_DEPENDENCY` do ponownego testu na reprezentatywnych klasach urządzeń. Live OpenRouter używa system-first, szyfrowanego DNS fallbacku ograniczonego do `openrouter.ai` po potwierdzonym `UnknownHostException` resolvera Androida.

Motorola jest dowodem zgodności jednego fizycznego urządzenia, nie docelowym ograniczeniem produktu. RPG OS pozostaje uniwersalną aplikacją Android; wydajność, termika, pamięć i backendy lokalnego AI są akceptowane macierzą reprezentatywnych urządzeń. Historyczne pomiary Galaxy S24 są punktem odniesienia, a nie jedyną bramką.

## Merge rule

The user/coordinator authorized merge in this run only if the independent audit and exact-SHA CI are green. Any failing test, build, signing, provenance or audit gate restores `STOP / DO NOT MERGE`.
