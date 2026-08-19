# LocalGMAi.md — System RPG OS Local AI-GM Research Report

Status: **RESEARCH / ARCHITECTURE INPUT / NON-CANONICAL UNTIL COORDINATOR ACCEPTANCE**  
Author role: **CHAT-7**  
Created: **2026-08-19**  
Repository: `piotreksmaga-art/rpg-os-android`  
Report branch: `chat7-local-gm-ai-report`  
Baseline master at report creation: `7cb61d3cdc42e0c20f2688181d054e55eacbfd8f`

> This file is a durable research report. It records device evidence, TEMP-GM discoveries, architecture conclusions, external runtime/model research, open questions and recommendations for the future native Local AI-GM subsystem. It does **not** change canonical phase status, does **not** authorize Phase 48 implementation and does **not** replace `docs/RPG_OS_MASTER_ARCHITECTURE.md` or `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`.

---

# 1. Executive summary

System RPG OS is intended to support very long text-RPG campaigns: hundreds of thousands of turns, millions of events/words and years of play while preserving state, causality, progression, ownership, chronology, NPC knowledge and campaign divergence.

The Local AI-GM must ultimately run **from inside the Android RPG OS application**. The player must not need Termux, shell scripts, a manually started `llama-server`, manual ports or developer tooling during normal use.

The current TEMP-GM work proved that on-device inference is realistic on the target phone class. Bielik 4.5B v3 was successfully run on a real Samsung Galaxy S24 SM-S921B / Android 16 with llama.cpp + Vulkan and a native 8192-token context. The TEMP bridge, provider contract, Context Builder, NPC knowledge isolation and non-authoritative mutation boundary all passed real-device tests.

At the same time, live gameplay-oriented tests discovered important semantic and runtime limitations:

- `ACTION_DIRECTION_REVERSAL` occurred in an early combat narration;
- `PLAYER_AGENCY_VIOLATION` occurred when the model invented player actions that the user never declared;
- an observation-only prompt caused an invented player teleport action in a later semantic run;
- internal TEMP/test labels such as `TEST_FAILURE` and `TEST_FALLBACK` leaked into generated output;
- some responses ignored the intended stop point and continued until the 1024-token cap;
- a live generation failed at approximately the provider's 180 s boundary, showing that output budget and timeout must become model/runtime/device-aware rather than arbitrary constants.

These discoveries strongly support the canonical design principle already present in RPG OS:

```text
RPG OS owns reality.
RPG OS owns durable memory.
RPG OS owns rules.
RPG OS owns transactions.

AI interprets.
AI proposes.
AI narrates.

Model is replaceable.
Runtime is replaceable.
Hardware backend is replaceable.
```

The most important architecture recommendation is to keep `AiProvider` independent from the specific local inference engine and introduce explicit future concepts below/around it:

```text
AiProvider
  -> AiCapabilityContract
  -> GmToolGateway
  -> LocalInferenceRuntime
  -> RuntimeBackendSelector
  -> ModelLifecycleController
  -> ModelProfile
  -> CPU / GPU / future supported NPU
```

Termux and localhost remain valuable **TEMP test infrastructure**, not production architecture.

---

# 2. Evidence classes used in this report

This report intentionally distinguishes four evidence classes.

## 2.1 REPO / DEVICE EVIDENCE

Facts captured in durable RPG OS repository evidence from real device runs or committed code/docs.

Examples:

- Bielik profile and runtime parameters;
- real Galaxy S24 device pass;
- prompt/completion timing;
- RAM observations;
- semantic runtime failures;
- provider timeout behavior;
- canonicalMutation safety.

## 2.2 USER-OBSERVED EMPIRICAL FINDINGS

Direct observations made during project testing but not necessarily represented by a formal external benchmark.

Important example:

- Qwen was removed from the main GM shortlist after project testing because of very poor Polish and loss/forgetting of supplied facts.

This observation is important for product/model selection, even though it is not a universal statement about the whole Qwen family.

## 2.3 EXTERNAL RESEARCH SNAPSHOT

Information gathered from official or primary technical sources about LiteRT-LM, ExecuTorch, Samsung Exynos AI LiteCore, model architectures and supported backends.

External ecosystem facts are time-sensitive and must be revalidated before implementation.

## 2.4 ARCHITECTURE INFERENCE / RECOMMENDATION

Conclusions drawn from canonical architecture + device evidence + external technical research.

These are proposals until accepted by the coordinator.

---

# 3. Canonical project architecture — what is already correct

The existing MASTER architecture is fundamentally well aligned with the target Local AI-GM and should not be replaced.

## 3.1 AI is not the campaign database

The canonical design already states that AI is not:

- the database;
- durable campaign memory;
- the mechanics calculator;
- the source of truth.

That is exactly the right design for an on-device model that may be unloaded, restarted, replaced, upgraded or changed between runtimes.

The campaign must survive even if the model disappears completely from RAM.

## 3.2 FACT / BELIEF / NARRATIVE separation is essential

The distinction between:

- `FACT` — objective committed campaign truth;
- `BELIEF` — what a specific actor believes;
- `NARRATIVE` — what is presented to the player;

is one of the strongest safeguards against LLM hallucinations becoming campaign truth.

The Local AI-GM may generate NARRATIVE and proposals. It must never promote its own text directly into FACT.

## 3.3 Single Truth Mutation Path is essential

The canonical path:

```text
PROPOSAL
  -> DOMAIN/RULE RESOLUTION
  -> CHANGE SET
  -> VALIDATION
  -> TRANSACTION
  -> EVENTS + LEDGERS + AUTHORITATIVE STATE
  -> COMMIT
```

must remain the only legal way to create committed reality.

Forbidden future shortcuts include:

```text
AI output -> direct DB mutation
AI tool call -> set HP
AI tool call -> grant skill
AI tool call -> add money
Android UI -> model mutation -> Save
```

## 3.4 Six-layer dependency direction is correct

The canonical direction remains:

```text
SOURCE OF TRUTH
  -> CAMPAIGN STATE
  -> CAMPAIGN INTELLIGENCE
  -> SIMULATION / RULE ENGINE
  -> CONTEXT & DIRECTOR
  -> AI GAME MASTER
```

The Local AI-GM belongs at the final interpretation/narration layer.

## 3.5 Roadmap order is correct

The current roadmap correctly places knowledge, retrieval and context before canonical AI:

```text
41 Structured SQL Retriever
42 Knowledge Graph / causal retrieval
43 Intent Parser
44 Turn Planner
45 Context Builder
46 Context Budget Manager
47 Iterative Retrieval + missing-context loop
48 AiProvider abstraction
49 Structured GM Output
50 Mechanics Resolution integration
51 Consistency Validator
52 Counterfactual Guard
53 Repair Pass
54 committed narrative delivery after valid transaction
```

This order should not be reversed merely because TEMP-GM already runs on a phone.

A good model without Phases 37–47 would still behave largely like a chatbot with a big prompt.

---

# 4. Target production experience

The final user experience should be:

```text
Install RPG OS
  -> install/download selected Local GM model
  -> open campaign
  -> RPG OS loads model automatically
  -> user plays
  -> RPG OS unloads/releases model when appropriate
```

The normal player must not need to:

- open Termux;
- run shell scripts;
- start a local server manually;
- configure ports;
- know a GGUF path;
- know llama.cpp parameters;
- choose Vulkan CLI switches.

A future production path should look like:

```text
RPG OS Android application
  -> GM orchestration
  -> AiProvider
  -> LocalInferenceRuntime
  -> native Android inference engine
  -> model
  -> CPU / GPU / supported NPU
```

---

# 5. TEMP-GM — validated baseline

## 5.1 Final test model profile

Durable repository profile:

`temp-gm/docs/BIELIK_4.5B_V3_FINAL_TEMP_GM_PROFILE.md`

Final TEMP baseline:

```text
Model: Bielik 4.5B v3 Instruct
Format: GGUF
Quantization: Q4_K_M
Runtime: llama.cpp
Backend: Vulkan
Context: 8192
KV K: f16
KV V: f16
Batch: 64
Ubatch: 64
Parallel slots: 1
GPU layers: 99
GGML_VK_DISABLE_OCP_FP4=1
```

Equivalent important llama.cpp parameters:

```text
-c 8192
-ctk f16
-ctv f16
-b 64
-ub 64
-np 1
-ngl 99
```

Logical TEMP provider ID:

```text
BIELIK_4_5B_V3
```

TEMP bridge:

```text
127.0.0.1:8765
```

llama.cpp endpoint:

```text
127.0.0.1:8768
```

The Q4_K_M + Vulkan + CTX8192 + KV f16 profile was selected after device benchmarking. KV f32 worked but increased RAM pressure and was slower enough that f16 was retained as the preferred TEMP baseline.

## 5.2 Real device vertical slice PASS

Durable evidence:

`temp-gm/evidence/2026-08-15-minimal-vertical-slice-device-pass.md`

Device:

```text
Samsung SM-S921B
Android 16
```

Observed PASS:

```text
TEMP_GM_SELFTEST = PASS
BRIDGE_HEALTH = PASS
PROVIDER_STATUS = PASS
GM_TURN = PASS
canonicalMutation = false
NPC_SECRET_LEAK = NO
NPC_KNOWLEDGE_ISOLATION = PASS
```

The actual llama.cpp runtime became ready in about 10 s and the bridge in about 2 s in that run.

A real `/gm/turn` completed using logical provider `BIELIK_4_5B_V3` and returned non-authoritative `NARRATIVE_ONLY` output with `canonicalMutation=false`.

## 5.3 Real measured timing from the vertical slice

Measured turn:

```text
prompt tokens: 614
completion tokens: 65
prompt evaluation: 14.03 s / 43.75 tok/s
completion evaluation: 9.17 s / 6.98 tok/s
total generation request: 23.20 s
```

This established that a ~4.5B local model is practical on the target phone class, but also showed that decode latency is a material UX concern.

## 5.4 Real memory observation

The same evidence recorded approximately:

```text
Before runtime load:
available RAM ~3.0 GiB
swap used ~2.1 GiB

With runtime loaded:
available RAM ~872 MiB
swap used ~2.3 GiB

After stop:
available RAM ~4.1 GiB
swap used ~2.3 GiB
```

This reinforces the need for an explicit production `ModelLifecycleController` with load/unload, memory-pressure and OOM handling.

## 5.5 Network hardening lesson

llama.cpp emitted a CORS/API-key warning in the TEMP run. The test remained bound to `127.0.0.1` only, so exposure remained localhost-only.

Production native in-process inference should ideally remove the need for localhost HTTP entirely.

---

# 6. TEMP Context Builder and authority findings

The TEMP Context Builder validated several architectural principles that should survive into the canonical system.

## 6.1 Model context is bounded working context

CTX8192 was treated as a working context budget, not campaign memory.

## 6.2 NPC knowledge must be filtered before the model sees it

The device test used a hidden scene secret and confirmed that an NPC did not reveal information absent from its allowed knowledge section.

This supports the future canonical rule:

```text
Global state may help RPG OS build context.
Global state must not automatically become NPC knowledge.
```

## 6.3 The model never becomes authoritative

TEMP responses kept:

```text
canonicalMutation = false
```

and did not execute:

- StatePatch;
- COMMIT;
- authoritative PlayerChangeSet;
- Save write;
- canonical DB write;
- authoritative event creation.

That boundary should become provider-independent in Phase 48+.

---

# 7. Semantic defects discovered in real Bielik runtime

Public benchmarks do not test the failure modes most important to a role-playing GM. Real device tests exposed several.

## 7.1 ACTION_DIRECTION_REVERSAL

Player declaration:

```text
Przede mną stoi wrogi shinobi.
Atakuję go kataną.
Celuję w jego prawą dłoń.
Opisz reakcję wrogiego shinobi.
```

Observed early defect:

- PLAYER was the declared attacker;
- NPC right hand was the declared target;
- model output reversed roles and narrated the NPC attacking the PLAYER / targeting the PLAYER's right hand.

Architectural lesson:

> Important action semantics should not rely only on free-text interpretation by the LLM.

Future Intent Parser / Turn Planner should be able to provide stable structured semantics such as:

```text
actorUid = PLAYER
action = ATTACK
targetUid = NPC_123
targetPart = RIGHT_HAND
weaponUid = KATANA_001
```

The model may narrate those semantics but should not redefine them.

## 7.2 PLAYER_AGENCY_VIOLATION

Observed undeclared player actions included examples such as:

- player dodge;
- player counterattack;
- player attack/hit;
- continued future player behavior.

A later semantic runtime run contained an even clearer case: while the player only observed a closed door, the model invented a teleport action for the PLAYER.

Architectural lesson:

```text
PLAYER ACTION SOURCE = USER ONLY
```

should become a **provider-independent GM invariant**, not merely a Bielik prompt trick.

Allowed model behavior:

- narrate consequences of declared player actions;
- narrate NPC perception;
- choose NPC reaction;
- choose NPC movement/defense/counterattack/dialogue;
- describe environment.

Forbidden model behavior:

- invent a new player action;
- invent player movement;
- invent player speech;
- invent player technique/ability use;
- continue the next player turn.

NPC autonomy remains allowed.

## 7.3 INTERNAL CONTEXT / TEST LABEL LEAK

Durable evidence:

`temp-gm/evidence/2026-08-16-semantic-runtime-failure-timeout-measurement.md`

and:

`temp-gm/evidence/2026-08-16-live-semantic-retest2-review.md`

Observed output contained internal/test-style strings including:

```text
TEST_FAILURE
TEST_FALLBACK
```

Some outputs echoed internal read-only context instead of producing clean player-facing narration.

Architectural lesson:

- model-facing prompt should avoid unnecessary internal implementation labels;
- provider contract and output validator should detect internal instruction leakage;
- internal engine labels must not be treated as a normal part of narrative output;
- production tool/result schemas should be minimal and typed rather than exposed as verbose debugging prose.

## 7.4 STOP POINT FAILURE

Some generations failed to stop after the immediate declared action/NPC reaction and instead continued until the 1024-token output cap.

Architectural lesson:

- output budget must be explicit;
- stop semantics must be provider-independent;
- `TurnPlan.requiredOutputs` should request only the minimal content required;
- constrained decoding / structured output should be used where helpful;
- streaming should support cancellation.

---

# 8. Provider timeout discovery

## 8.1 Controlled measurements

Durable evidence recorded:

```text
provider timeout = 180 s
Android timeout = 210 s
maxTokens = 1024
```

Controlled runs:

```text
Run 1
prompt=993
completion=67
elapsed=12.174 s
~5.50 completion tok/s
natural end

Run 2
prompt=1010
completion=1024
elapsed=127.846 s
~8.01 completion tok/s
max-token stop

Run 3
prompt=1009
completion=177
elapsed=25.124 s
~7.05 completion tok/s
natural end
```

## 8.2 Live timeout reproduction

A later 10-case real runtime retest produced:

```text
HTTP 200: 9/10
natural stops: 7/10
max-token stops: 2/10
one request failed at ~180.176 s with HTTP 502
```

Successful request generation times:

```text
min: 27.167 s
median: 72.839 s
max: 142.106 s
```

Effective output rate among successful responses:

```text
min: 5.146 tok/s
median: 6.786 tok/s
max: 7.331 tok/s
```

The failure occurred essentially at the 180 s provider boundary.

## 8.3 Architecture lesson

The production system should not hardcode:

```text
providerTimeout = 180
clientTimeout = 210
maxTokens = 1024
```

Instead, timeout/output policy should be derived from:

```text
ModelProfile
Runtime capabilities
measured device class
recommendedOutputBudget
streaming/cancellation support
thermal/performance state
```

The normal relationship should remain coherent:

```text
client deadline > provider generation deadline + transport/UI margin
```

but the values should belong to the runtime/provider contract rather than arbitrary frontend constants.

---

# 9. Bug harness discoveries relevant to future AI architecture

The TEMP bug-reporting harness is not the production AI architecture, but it validated several general principles.

## 9.1 User remains authority for external actions

The harness enforced:

```text
POST /bug != GitHub issue creation
preview != authorization
search duplicate != authorization
```

External submission requires explicit user approval and one-shot authorization.

This is a useful precedent for future AI tools:

> AI may propose/request; privileged external or canonical action must require the correct authority gate.

## 9.2 Offline/degraded operation should preserve local evidence

Missing ADB/logcat/network/GitHub did not invalidate the user-supplied bug report.

Analogous production principle:

> Local model/runtime failure must degrade narration/service without corrupting the campaign.

## 9.3 One-shot authorization matters

Authorization consumption was made one-shot to prevent repeated external actions.

This is conceptually similar to future idempotent/transactional mechanics tools.

---

# 10. Qwen project result — current status

**Evidence class: USER-OBSERVED EMPIRICAL FINDING**

Qwen was tested earlier during local model evaluation and removed from the current main GM shortlist because of:

- very poor Polish in the tested configuration;
- poor retention/use of supplied facts during the tested gameplay-style interactions.

This report does not claim that every future Qwen model will have the same result.

Current project decision:

```text
Qwen = not a primary Local GM candidate now
```

A future Qwen generation could be retested only if there is a strong reason and a new controlled benchmark.

---

# 11. Bielik model architecture and portability research

**Evidence class: EXTERNAL RESEARCH SNAPSHOT**

Current Bielik 4.5B v3 Instruct family metadata identifies the model as:

```text
architecture: LlamaForCausalLM
model_type: llama
hidden_size: 2048
num_hidden_layers: 60
num_attention_heads: 16
num_key_value_heads: 2
max_position_embeddings: 32768
attention_bias: true
mlp_bias: true
```

The model is Polish-focused and distributed under Apache 2.0 in the referenced Hugging Face model family.

Research implication:

- Bielik is not intrinsically tied to GGUF/llama.cpp;
- its `LlamaForCausalLM` family increases the chance that newer export/runtime stacks can support it;
- however Bielik-specific details such as attention/MLP bias still require a real export test;
- successful architectural-family matching is **not** equivalent to verified runtime compatibility.

Important LiteRT research finding:

Google's current `litert-torch export_hf` verified architecture list includes `LlamaForCausalLM`, and exported models become `.litertlm` artifacts intended for CPU/GPU on-device deployment.

Therefore a future feasibility experiment:

```text
Bielik HF/safetensors
  -> litert-torch export_hf
  -> .litertlm
  -> LiteRT-LM Android
```

is technically justified and should be tested before assuming Bielik must remain a GGUF/llama.cpp model.

Primary research sources:

- https://huggingface.co/speakleash/Bielik-4.5B-v3.0-Instruct
- https://huggingface.co/speakleash/Bielik-4.5B-v3.0-Instruct-MLX-8bit/blob/main/config.json
- https://developers.google.com/edge/litert/conversion/pytorch/genai

---

# 12. LiteRT-LM — strongest current production-native Android candidate

**Evidence class: EXTERNAL RESEARCH SNAPSHOT**

Google currently describes LiteRT-LM as a production-ready orchestration layer for on-device LLMs.

Important properties for RPG OS:

- Android support;
- CPU backend;
- GPU backend;
- NPU support on supported hardware;
- native in-app integration rather than mandatory external server;
- model management around `.litertlm` artifacts;
- streaming;
- tool/function-calling support for supported models;
- constrained decoding capabilities;
- multimodality support where model/runtime support it.

This means the target production architecture can plausibly be:

```text
RPG OS APK
  -> LiteRtLmRuntime
  -> .litertlm model
  -> GPU/CPU
```

without Termux.

## 12.1 Why LiteRT-LM is strategically important

It solves several problems that are currently manual in TEMP-GM:

- no need to launch `llama-server` manually;
- no need for localhost bridge as the model transport;
- explicit engine lifecycle;
- direct Android embedding;
- GPU/NPU evolution path;
- model artifact abstraction.

## 12.2 Current public performance reference points

Google currently publishes a Gemma-4-E2B LiteRT-LM model of about 2.58 GB and reports, on Samsung S26 Ultra:

```text
CPU decode ~47 tok/s
GPU decode ~52 tok/s
GPU TTFT ~0.3 s
```

Gemma-4-E4B is listed around 3.65 GB and, on the same device:

```text
CPU decode ~18 tok/s
GPU decode ~22 tok/s
```

These values **must not be projected directly onto SM-S921B / Exynos 2400**. They are evidence that mobile-first LLM runtimes/models can operate at a much higher performance class on newer mobile hardware, not a prediction for our phone.

LiteRT-LM also lists reference results on Samsung S24 Ultra for other models, for example:

```text
Gemma-3n-E2B: ~16 tok/s decode CPU/GPU
Gemma-3n-E4B: ~9 tok/s decode CPU/GPU
Phi-4-mini: ~7 tok/s CPU / ~10 tok/s GPU
```

Again, S24 Ultra hardware differs from our European SM-S921B Exynos device.

Primary sources:

- https://developers.google.com/edge/litert-lm/overview
- https://developers.google.com/edge/litert-lm/android
- https://developers.google.com/edge/litert-lm/models/gemma-4
- https://developers.google.com/edge/litert/conversion/pytorch/genai

---

# 13. ExecuTorch — strong second runtime candidate

**Evidence class: EXTERNAL RESEARCH SNAPSHOT**

ExecuTorch currently offers native Android AAR integration with Java/Kotlin-facing APIs and JNI/native runtime components.

Available Android acceleration paths include:

```text
XNNPACK -> CPU
Vulkan -> GPU
Qualcomm AI Engine -> NPU
MediaTek -> NPU
Samsung Exynos backend exists in the wider backend ecosystem
```

The Vulkan backend is specifically developed with Android GPU workloads in mind and supports FP32/FP16 plus quantized linear paths including 4-bit and 8-bit weight modes.

The XNNPACK backend provides optimized ARM/x86 CPU kernels and is recommended as a broad compatibility baseline.

ExecuTorch exports models to `.pte`. Backend lowering may make a `.pte` artifact backend-specific, which means model packaging must keep runtime/backend metadata separate from model identity.

Architecture implication:

```text
ModelProfile != Runtime != Backend
```

Example:

```text
PLLuM profile
  -> ExecuTorchRuntime
  -> Vulkan backend
```

or:

```text
same logical model family
  -> ExecuTorchRuntime
  -> XNNPACK backend
```

Primary sources:

- https://docs.pytorch.org/executorch/stable/using-executorch-android.html
- https://docs.pytorch.org/executorch/stable/android-backends.html
- https://docs.pytorch.org/executorch/stable/android-vulkan.html
- https://docs.pytorch.org/executorch/stable/llm/export-custom-llm.html

---

# 14. Exynos 2400 NPU research

**Evidence class: EXTERNAL RESEARCH SNAPSHOT**

The target SM-S921B uses the Exynos 2400 family. Samsung's official material describes strong on-device AI capability and reports substantial AI performance improvement versus Exynos 2200.

The key discovery is not that the phone lacks an NPU. It clearly has significant AI acceleration hardware.

The practical issue is **public developer runtime support for our exact SoC/model workload**.

## 14.1 Samsung Exynos AI LiteCore exists

Samsung publicly provides Exynos AI LiteCore, a lightweight AI SDK that can compile graphs/models toward Exynos NPU/DSP execution and create Samsung NN Container artifacts.

Samsung explicitly describes LiteRT and ExecuTorch integration paths.

## 14.2 Current supported Samsung LiteRT NPU SoCs do not include Exynos 2400

Current Google Samsung Exynos AI LiteCore documentation lists supported SoCs:

```text
Exynos 2500 (E9955)
Exynos 2600 (E9965)
```

Exynos 2400 is not in that published supported list.

Therefore:

```text
Exynos 2400 NPU = future/experimental opportunity
not current production dependency
```

## 14.3 Architecture conclusion

We should preserve:

```text
RuntimeBackend.NPU
```

as a future capability, but not design Phase 48 around the assumption that the current Galaxy S24 can run our selected 4B–5B GM through a supported Samsung NPU path today.

Primary sources:

- https://semiconductor.samsung.com/processor/mobile-processor/exynos-2400/
- https://soc-developer.semiconductor.samsung.com/global/development/ai-litecore/
- https://developers.google.com/edge/litert/next/samsung
- https://developers.google.com/edge/litert/next/npu

---

# 15. PLLuM — high-priority Polish candidate

**Evidence class: EXTERNAL RESEARCH SNAPSHOT / CANDIDATE, NOT YET DEVICE-VALIDATED**

PLLuM-4B-instruct-2512 is a particularly interesting candidate because it combines:

- Polish specialization;
- ~4B-class model size;
- Gemma 3 family architecture;
- Apache-2.0 license in the referenced model repository;
- potential compatibility with Android-edge model families.

Current configuration identifies:

```text
architectures = Gemma3ForConditionalGeneration
model_type = gemma3
text hidden size = 2560
text layers = 34
attention heads = 8
KV heads = 4
max_position_embeddings = 131072
```

The repository also contains a vision configuration because the architecture is `Gemma3ForConditionalGeneration` rather than a pure `Gemma3ForCausalLM` text-only class.

This creates both opportunity and an open question.

Opportunity:

- Gemma 3 is strongly supported by modern edge runtimes.

Open question:

- can the PLLuM checkpoint be exported cleanly as the text path needed for RPG OS without carrying unnecessary vision components or requiring custom conversion?

Google's `litert-torch export_hf` explicitly verifies:

- `Gemma3ForCausalLM` for text;
- `Gemma3ForConditionalGeneration` for supported image-text export workflows.

Therefore PLLuM is a justified feasibility target, but **not yet a proven LiteRT-LM model**.

Proposed test:

```text
PLLuM-4B-instruct-2512
  -> export feasibility
  -> text-only or appropriate Gemma3 export path
  -> .litertlm
  -> Android CPU/GPU smoke
  -> RPG OS Local GM benchmark
```

Primary sources:

- https://huggingface.co/CYFRAGOVPL/PLLuM-4B-instruct-2512
- https://huggingface.co/CYFRAGOVPL/PLLuM-4B-instruct-2512/blob/main/config.json
- https://developers.google.com/edge/litert/conversion/pytorch/genai

---

# 16. Gemma family — important mobile-first challengers

## 16.1 Gemma 4 E2B

Research interest:

- small mobile-oriented artifact size;
- very strong published mobile runtime performance on newer hardware;
- LiteRT-LM is a first-class deployment path;
- may provide significantly better latency/energy behavior than our current llama.cpp baseline.

Main question for RPG OS:

> Is the Polish narrative quality and strict semantic obedience good enough to compensate for moving away from a Polish-specialized model?

## 16.2 Gemma 4 E4B

Research interest:

- larger effective model class than E2B;
- still mobile-first;
- likely stronger narrative/reasoning capacity;
- published LiteRT-LM GPU performance remains substantially above our current Bielik decode rate on the newer S26 Ultra reference platform.

Main risk:

- RAM/thermal behavior on our Exynos 2400 device must be measured directly.

## 16.3 Gemma 3n E2B / E4B

These provide useful mobile reference points because public LiteRT-LM measurements exist for Samsung S24 Ultra generation hardware.

They are valuable as runtime/mobile baselines even if they do not become the primary Polish GM.

---

# 17. Phi-4-mini — useful instruction-following/control candidate

Phi-4-mini is not currently the main expected winner for our Polish narrative workload, but it remains useful as a control model because:

- it is in the ~4B class;
- mobile LiteRT-LM reference measurements exist;
- it may provide different instruction-following behavior from Bielik/PLLuM/Gemma.

It can help distinguish:

```text
model semantic discipline
vs
runtime performance
```

---

# 18. Current candidate shortlist

This is a **research shortlist**, not canonical provider selection.

## Primary candidates

```text
1. Bielik 4.5B v3 Instruct
2. PLLuM-4B-instruct-2512
3. PLLuM-4B-chat-2512
4. Gemma 4 E2B
5. Gemma 4 E4B
```

## Secondary/control candidates

```text
Phi-4-mini
Gemma 3 / Gemma 3n
Llama-class reference model
future Polish 3B–7B models
```

## Currently removed from main shortlist

```text
Qwen — removed based on our empirical Polish/fact-retention testing
```

---

# 19. Why model selection must be separate from runtime selection

We must avoid classes such as:

```text
BielikEngine
PLLuMEngine
GemmaEngine
```

Instead:

```text
ModelProfile
  + Runtime implementation
  + RuntimeBackend
```

Examples:

```text
ModelProfile: Bielik 4.5B
Runtime: LiteRT-LM
Backend: GPU
```

```text
ModelProfile: PLLuM 4B
Runtime: ExecuTorch
Backend: XNNPACK
```

```text
ModelProfile: future Polish model
Runtime: future NPU runtime
Backend: NPU
```

This keeps campaign and GM architecture stable while technology evolves.

---

# 20. Proposed `AiProvider`

`AiProvider` should remain a logical GM inference abstraction.

Conceptually:

```text
AiProvider.generate(GmRequest) -> GmModelResult
AiProvider.generateStream(GmRequest) -> stream<GmChunk>
AiProvider.cancel(requestId)
AiProvider.getCapabilities()
AiProvider.getStatus()
```

Responsibilities:

- accept a bounded RPG OS request/context;
- invoke selected runtime;
- return model output/proposal;
- surface provider status/errors;
- remain independent of Android UI and campaign persistence.

Non-responsibilities:

- direct DB access;
- direct authoritative mutation;
- model file download/storage implementation;
- GPU/NPU driver policy;
- Android Activity lifecycle;
- direct SQL;
- durable campaign memory.

---

# 21. Proposed `AiCapabilityContract`

Provider/model/runtime capabilities should be explicit.

Suggested fields/capabilities:

```text
TEXT_GENERATION
STREAMING
STRUCTURED_OUTPUT
CONSTRAINED_DECODING
TOOL_CALL_REQUESTS
VISION
AUDIO
MAX_CONTEXT
MAX_OUTPUT
SUPPORTED_LANGUAGES
```

This allows Turn Planner to request only what the chosen provider can reliably produce.

---

# 22. Proposed `ModelProfile`

Suggested data-driven profile:

```text
modelUid
logicalModelId
displayName
family
version
artifactFormat
artifactVersion
quantization
checksum/signature
licenseMetadata
chatTemplateId
promptContractVersion
semanticProfileVersion

contextLimit
recommendedInputBudget
recommendedOutputBudget

supportedLanguages
capabilities
supportedRuntimeIds
supportedBackends

storageBytes
estimatedPeakRamClass
recommendedDeviceClass
```

The profile contains **no campaign state**.

---

# 23. Proposed `LocalInferenceRuntime`

This is the main missing future concept below `AiProvider`.

Suggested interface responsibilities:

```text
initialize()
loadModel(ModelProfile)
unloadModel()

generate(request)
generateStream(request)
cancel(requestId)

getStatus()
getCapabilities()
getMetrics()
getMemoryUsage()
```

Possible implementations:

```text
LiteRtLmRuntime
ExecuTorchRuntime
LlamaCppNativeRuntime (optional research/fallback)
future vendor runtime
```

Termux is not an implementation of the production interface.

---

# 24. Proposed `RuntimeBackend` and `RuntimeBackendSelector`

Canonical code should reason in terms of capabilities:

```text
CPU
GPU
NPU
AUTO
```

Concrete mappings belong to runtimes.

Examples:

```text
ExecuTorch XNNPACK -> CPU
ExecuTorch Vulkan -> GPU
LiteRT-LM GPU -> GPU
future Samsung LiteCore target -> NPU
```

Selection may depend on:

- model compatibility;
- runtime compatibility;
- available memory;
- device SoC;
- thermal state;
- battery;
- user settings;
- measured performance.

`ModelRouter` and `RuntimeBackendSelector` are different responsibilities.

---

# 25. Proposed `ModelLifecycleController`

A production Android local model needs explicit lifecycle management.

Suggested states:

```text
NOT_INSTALLED
DOWNLOADING
VERIFYING
AVAILABLE
LOADING
READY
GENERATING
UNLOADING
ERROR
```

Responsibilities:

- artifact download/install;
- checksum/signature verification;
- storage accounting;
- model load/unload;
- cancellation;
- OOM handling;
- process death recovery;
- app foreground/background transitions where relevant;
- provider switching;
- safe model update.

Invariant:

> Runtime/model lifecycle failure must never partially mutate the campaign.

---

# 26. Proposed `GmToolGateway`

The model should use RPG OS capabilities through a controlled broker rather than direct database access.

Examples of future safe operations:

```text
queryPlayerSnapshot(profile)
queryCurrentScene()
queryNpcKnowledge(npcUid)
queryRelationships(...)
retrieveRelevantEvents(...)
retrieveRelevantMemory(...)
retrieveCanon(...)
queryLocationState(...)
requestMechanicsResolution(...)
```

Forbidden direct tools:

```text
setHp(...)
setStat(...)
addMoney(...)
grantSkill(...)
writeSave(...)
updateDatabase(...)
commit(...)
```

The model may:

```text
QUERY
REQUEST
PROPOSE
```

RPG OS remains the authority that validates and commits.

Modern edge runtimes increasingly support tool/function-calling mechanisms, but the presence of runtime tool calling must **not** be interpreted as permission to bypass canonical domain/transaction layers.

---

# 27. Long campaign memory contract

This is one of the most important conclusions.

## 27.1 KV cache is not campaign memory

Model KV/cache exists for the current inference/session only.

It may be discarded after:

- model unload;
- app restart;
- device reboot;
- model upgrade;
- provider switch.

The campaign remains intact because durable knowledge lives in RPG OS.

## 27.2 Durable campaign memory belongs to RPG OS

Long-term information should come from:

```text
CampaignRepository
Event Store
Working Memory
Episodic Memory
Semantic Campaign Memory
NPC Knowledge
Temporal Engine
Causal Graph
Chronicle
```

## 27.3 Context is reconstructed per turn

Future flow:

```text
PLAYER INPUT
  -> Intent Parser
  -> Turn Planner
  -> Retrieval
  -> Temporal filter
  -> NPC knowledge filter
  -> Context Budget Manager
  -> Context Builder
  -> AI GM
```

The model should receive only what matters now.

## 27.4 A campaign may outlive many models

Example:

```text
2026 -> Bielik
2027 -> PLLuM-X
2028 -> Gemma-X
2029 -> new Polish NPU-native model
```

No campaign migration should be required merely because the inference model changes.

---

# 28. Context Budget Manager recommendations

Phase 46 should become explicitly model-capability-aware.

It must not assume:

```text
CTX = 8192 forever
```

Inputs should include at least:

```text
ModelProfile.contextLimit
ModelProfile.recommendedOutputBudget
runtime overhead
TurnPlan required outputs
```

Illustrative 8K-class budget only:

```text
system/GM contract            500–900
scene                          400–800
player GM context              300–600
relevant NPC state             500–900
NPC knowledge                  600–1000
recent dialogue/actions        800–1400
retrieved events/memory        900–1600
mechanics result               200–500
output reserve                 700–1200
```

The actual budget must be benchmarked.

---

# 29. Provider-independent semantic invariants

TEMP-GM testing showed that important semantics cannot remain model-specific prompt folklore.

Recommended future invariants:

## 29.1 Player Agency

```text
PLAYER ACTION SOURCE = USER ONLY
```

## 29.2 Action role preservation

```text
ACTOR / ACTION / TARGET must not be reversed by AI narration
```

## 29.3 NPC autonomy

NPC may:

- move;
- speak;
- defend;
- dodge;
- flee;
- counterattack;
- make decisions within its knowledge/rules.

## 29.4 Stop point

Response stops before inventing the next player decision.

## 29.5 Internal context isolation

No leaking:

- system prompt;
- hidden rules;
- internal retrieval/debug labels;
- test labels;
- implementation details.

## 29.6 Non-authority

Model output remains proposal/narrative until validated/committed.

These invariants should be tested by every provider/model candidate.

---

# 30. Structured GM Output should be turn-dependent

Small local models should not be forced to generate a maximal schema on every turn.

Instead of always requesting all possible sections:

```text
narrative
events
state changes
knowledge changes
relationships
memory
chronicle
threads
time advance
NPC intentions
warnings
...
```

Turn Planner may define:

```text
requiredOutputs = {
  NARRATIVE,
  NPC_INTENT,
  MECHANICS_REQUEST
}
```

Benefits:

- fewer output tokens;
- lower latency;
- lower battery cost;
- fewer malformed structures;
- easier validation;
- better fit for 2B–5B mobile models.

---

# 31. One-pass vs two-pass turn design

Not every turn should require two model inferences.

## 31.1 Simple dialogue/narration

```text
PLAYER INPUT
  -> retrieval/context
  -> AI narration/NPC response
  -> validation/presentation
```

## 31.2 Mechanics-relevant turn

Possible future pipeline:

```text
PLAYER INPUT
  -> Intent Parser
  -> Turn Planner
  -> context
  -> AI PASS 1: interpretation/NPC intent/proposal
  -> Mechanics/Domain resolution
  -> validation
  -> transaction/COMMIT
  -> AI PASS 2: narrate confirmed outcome
```

The second pass should be used only when it materially improves consistency with confirmed mechanics.

---

# 32. Native Android model packaging

Models in the 3B–5B class can occupy multiple gigabytes.

Production should not assume every model is bundled inside the APK.

Recommended flow:

```text
install RPG OS
  -> AI Models screen/settings
  -> select model
  -> download artifact
  -> verify checksum/signature
  -> register ModelProfile
  -> load model when needed
```

Model can be removed or replaced without removing the campaign.

---

# 33. Streaming and cancellation

Streaming should be part of the future capability contract even if a provider does not initially implement it.

Suggested API support:

```text
generate()
generateStream()
cancel()
```

Why it matters:

- better perceived latency;
- user can interrupt overly long output;
- thermal/battery protection;
- stop-point enforcement;
- graceful provider switching.

---

# 34. Android performance acceptance criteria

Phase 78 should explicitly include local inference measurements.

Required future metrics:

```text
artifact size
load time
TTFT
prompt/prefill tok/s
decode tok/s
peak RSS
steady RSS
KV/cache footprint
battery drain
surface/device temperature
thermal throttling
OOM behavior
cancel latency
unload/reload behavior
process death recovery
10–30+ consecutive turn stability
context-size scaling
```

A model that is fast for one 20-second benchmark but throttles badly during a 60-minute campaign session is not a production winner.

---

# 35. RPG OS Local GM Benchmark proposal

Public MMLU-style benchmarks are insufficient.

RPG OS should own a benchmark specifically for GM behavior.

## 35.1 Polish quality

- grammar;
- vocabulary;
- naturalness;
- style;
- declension of Polish names;
- dialogue quality.

## 35.2 Player agency

- no invented player movement;
- no invented player attack;
- no invented dialogue;
- no invented ability use;
- no continuation of next player turn.

## 35.3 Action semantics

- actor preservation;
- target preservation;
- weapon/action preservation;
- directionality.

## 35.4 NPC autonomy and knowledge

- intelligent NPC reactions;
- no omniscience;
- correct belief vs fact handling;
- rumor handling;
- secret isolation.

## 35.5 Fact/context use

- retain facts still present in context;
- use retrieved facts correctly;
- resist contradictions;
- distinguish historical from current truth;
- use multiple retrieved events consistently.

## 35.6 Long context

Evaluate at multiple usable context sizes, e.g.:

```text
2K
4K
8K
larger where supported
```

## 35.7 Internal isolation

Zero output of:

- system prompt;
- internal test labels;
- hidden instructions;
- raw implementation/debug context.

## 35.8 Structured output/tool behavior

- schema validity;
- correct tool selection;
- bounded tool arguments;
- no unauthorized write intent;
- correct use of mechanics result.

## 35.9 Runtime performance

- TTFT;
- tok/s;
- RAM;
- thermal;
- battery;
- sustained turns.

Final provider acceptance should combine semantic score + device score rather than maximizing one metric.

---

# 36. Suggested shortlist evaluation strategy

For fair model comparison, prefer the same native runtime where possible.

Example first tournament:

```text
Bielik 4.5B
PLLuM 4B instruct
PLLuM 4B chat
Gemma 4 E2B
Gemma 4 E4B
```

Run each through the same RPG OS Local GM benchmark where technical compatibility allows.

If one model requires a different runtime, record that as a separate variable rather than pretending it is a pure model comparison.

Second-stage runtime comparison can then test the winning model/family across:

```text
LiteRT-LM GPU
ExecuTorch XNNPACK
ExecuTorch Vulkan
future NPU backend
```

---

# 37. Roadmap recommendations

Do **not** change current roadmap order.

Do **not** implement canonical Phase 48 early.

Instead, clarify future scope.

## 37.1 Proposed Phase 48 expansion

Current:

```text
48. AiProvider abstraction
```

Recommended future specification:

```text
48. AI Provider & Local Inference Architecture

48.1 AiProvider canonical contract
48.2 AiCapabilityContract
48.3 ModelProfile
48.4 LocalInferenceRuntime
48.5 RuntimeBackend abstraction
48.6 RuntimeBackendSelector
48.7 ModelLifecycleController
48.8 GmToolGateway boundary
48.9 Model artifact lifecycle
48.10 Streaming/cancellation contract
48.11 Provider-independent semantic contract
48.12 Player Agency invariant
48.13 Actor/Action/Target preservation
48.14 provider failure/degraded behavior
48.15 Android process/OOM lifecycle
48.16 Provider Conformance Suite
```

## 37.2 Phase 46 clarification

`Context Budget Manager` should be model-capability-aware.

## 37.3 Phase 49 clarification

Structured GM Output should remain provider-independent and may be turn/capability-dependent.

## 37.4 Phase 55–59 clarification

Add explicit invariant:

```text
NO AI PROVIDER OWNS DURABLE CAMPAIGN MEMORY
```

## 37.5 Phase 78 clarification

Include local inference Android profiling and thermal/battery acceptance.

## 37.6 Phase 79 clarification

Distinguish:

```text
ModelRouter
RuntimeBackendSelector
```

Potential Phase 79 scope:

- task/model routing;
- local/remote provider routing;
- memory-aware routing;
- thermal-aware routing;
- runtime/backend routing;
- fallback policy.

---

# 38. Phase 48 acceptance gate recommendation

Phase 48 should **not** be COMPLETE because one model can answer one prompt.

Suggested acceptance criteria:

```text
[ ] AiProvider independent from concrete model
[ ] AiProvider independent from concrete runtime
[ ] ModelProfile exists
[ ] AiCapabilityContract exists
[ ] LocalInferenceRuntime exists
[ ] hardware backend does not leak into GM core
[ ] model can change without campaign migration
[ ] provider does not own campaign memory
[ ] provider has no canonical mutation authority
[ ] PLAYER agency invariant tested
[ ] Actor/Action/Target invariant tested
[ ] NPC knowledge isolation tested
[ ] internal-context isolation tested
[ ] ContextBudget is model-aware
[ ] streaming/cancel contract defined
[ ] provider crash cannot damage campaign
[ ] process death cannot partially commit a turn
[ ] Android lifecycle/OOM behavior tested
[ ] provider conformance suite passes
[ ] real Android integration PASS
[ ] CI PASS
[ ] independent audit PASS
```

---

# 39. What TEMP-GM should remain

TEMP-GM should not be deleted merely because production architecture will differ.

It remains useful as:

```text
semantic laboratory
provider testbed
model benchmark
runtime benchmark
device benchmark
bug harness
reference evidence
```

Formal classification:

```text
Termux = TEST INFRASTRUCTURE
localhost bridge = TEMP TRANSPORT
llama.cpp = TEMP/REFERENCE RUNTIME
Bielik = REFERENCE MODEL
GGUF = CURRENT TEST ARTIFACT FORMAT
Vulkan = CURRENT TEST BACKEND
CTX8192 = CURRENT TEST PROFILE
```

None of the above should become a canonical production requirement.

---

# 40. Independent R&D allowed before Phase 48

Coordinator may choose to allow noncanonical feasibility work that does not implement production Phase 48.

Useful R&D tracks:

```text
Bielik -> LiteRT export feasibility
PLLuM -> LiteRT export feasibility
Gemma -> direct device benchmark
ExecuTorch XNNPACK benchmark
ExecuTorch Vulkan benchmark
Android-native minimal inference PoC
```

Rules:

- no canonical architecture mutation;
- no roadmap COMPLETE status;
- no AI-owned memory;
- no direct canonical DB writes;
- evidence committed separately.

---

# 41. Recommended immediate next research sequence

## Stage A — no phone required

1. Bielik HF -> `litert-torch export_hf` feasibility.
2. PLLuM 4B instruct -> determine text-only/export path.
3. PLLuM 4B chat -> same.
4. Record artifact size and conversion constraints.
5. Verify tokenizer/chat-template behavior.
6. Define common Local GM benchmark fixtures.

## Stage B — minimal native Android PoC

1. Tiny standalone Android test module/app.
2. Load `.litertlm` without Termux.
3. One fixed Polish prompt.
4. Confirm unload/close.
5. Record RAM/TTFT/tok/s.

## Stage C — model tournament

Run the Local GM benchmark on viable candidates.

## Stage D — runtime tournament

For top candidate(s), compare LiteRT-LM vs ExecuTorch CPU/GPU where feasible.

## Stage E — architecture decision before canonical Phase 48

Select initial production runtime adapter(s) based on evidence, while keeping the interface runtime-independent.

---

# 42. Critical open questions

## Model/export

- Does Bielik export cleanly through current `LlamaForCausalLM` LiteRT path despite its bias configuration?
- Can PLLuM's Gemma3 conditional architecture be exported efficiently for text-only RPG use?
- What quantization gives the best Polish/semantic quality per GB and tok/s?
- Does runtime/quantization change Bielik's agency/context-leak behavior materially?

## Device/runtime

- What LiteRT-LM GPU performance does SM-S921B / Exynos 2400 actually achieve?
- How does sustained GPU inference behave thermally over 30–60 minutes?
- Is ExecuTorch XNNPACK more energy-efficient or stable than GPU for long sessions?
- Can any supported public NPU path become available for Exynos 2400 later?

## RPG semantics

- Which model best preserves player agency?
- Which model uses supplied facts most reliably?
- Which model handles Polish NPC dialogue best?
- Which model leaks system/internal context least?
- Which model follows structured output/tool contracts best?

## Architecture

- Where exactly should model installation metadata live?
- Should model artifacts be globally shared across campaigns?
- What provider fallback UX is acceptable during OOM/runtime failure?
- When should the app unload the model to reclaim RAM?
- What background/foreground behavior is appropriate on Android?

---

# 43. Durable repository evidence referenced

Primary internal evidence:

```text
temp-gm/docs/BIELIK_4.5B_V3_FINAL_TEMP_GM_PROFILE.md

temp-gm/evidence/2026-08-15-minimal-vertical-slice-device-pass.md

temp-gm/evidence/2026-08-16-semantic-runtime-failure-timeout-measurement.md

temp-gm/evidence/2026-08-16-live-semantic-retest2-review.md
```

Important raw device evidence folders referenced by those reports include:

```text
temp-gm/results/device/2026-08-15_18-01-47-bielik45-tempgm-integration-e2e-ctx8192-f16/

temp-gm/results/device/2026-08-16_09-08-50-chat7-live-semantic-retest2/
```

Earlier architecture proposal:

```text
branch:
chat7-local-ai-native-architecture-proposal

commit:
e8fe884c1cc3259f17f763b11ad1be83ae474921

file:
docs/architecture/LOCAL_AI_GM_NATIVE_ANDROID_ARCHITECTURE_PROPOSAL.md
```

---

# 44. External primary/official research references

Research snapshot used for this report:

## Google LiteRT / LiteRT-LM

- https://developers.google.com/edge/litert-lm/overview
- https://developers.google.com/edge/litert-lm/android
- https://developers.google.com/edge/litert-lm/models/gemma-4
- https://developers.google.com/edge/litert/conversion/pytorch/genai
- https://developers.google.com/edge/litert/next/npu
- https://developers.google.com/edge/litert/next/samsung

## ExecuTorch

- https://docs.pytorch.org/executorch/stable/using-executorch-android.html
- https://docs.pytorch.org/executorch/stable/android-backends.html
- https://docs.pytorch.org/executorch/stable/android-vulkan.html
- https://docs.pytorch.org/executorch/stable/llm/export-custom-llm.html

## Samsung Exynos

- https://semiconductor.samsung.com/processor/mobile-processor/exynos-2400/
- https://soc-developer.semiconductor.samsung.com/global/development/ai-litecore/

## Bielik

- https://huggingface.co/speakleash/Bielik-4.5B-v3.0-Instruct
- https://huggingface.co/speakleash/Bielik-4.5B-v3.0-Instruct-MLX-8bit/blob/main/config.json

## PLLuM

- https://huggingface.co/CYFRAGOVPL/PLLuM-4B-instruct-2512
- https://huggingface.co/CYFRAGOVPL/PLLuM-4B-instruct-2512/blob/main/config.json

> External runtime/model documentation evolves quickly. Revalidate support matrices, API stability and device support before implementation or roadmap acceptance.

---

# 45. Final architecture recommendation

Do not optimize RPG OS around today's exact stack:

```text
Bielik + GGUF + llama.cpp + Vulkan + CTX8192
```

That stack is valuable evidence, not a permanent architectural contract.

The durable architecture should instead preserve:

```text
Campaign Reality
Event History
Player State
NPC State
NPC Knowledge
Temporal Truth
Causal Graph
World Rules
Mechanics
Memory
Retrieval
Context Builder
Tool Contracts
Validators
Transactions
```

The AI layer should remain replaceable.

Recommended invariant:

```text
RPG OS OWNS REALITY.
RPG OS OWNS DURABLE MEMORY.
RPG OS OWNS RULES.
RPG OS OWNS TRANSACTIONS.

AI GM INTERPRETS.
AI GM PROPOSES.
AI GM NARRATES.

MODEL IS REPLACEABLE.
RUNTIME IS REPLACEABLE.
HARDWARE BACKEND IS REPLACEABLE.
```

This design gives RPG OS the best chance of supporting years-long campaigns while allowing the local AI technology to improve over time without forcing campaign migrations or architectural rewrites.

---

# 46. Coordinator decision topics

Before canonical Phase 48 implementation, coordinator should eventually resolve:

```text
1. Expand Phase 48 to include LocalInferenceRuntime?
2. Add ModelProfile?
3. Add AiCapabilityContract?
4. Add RuntimeBackend / RuntimeBackendSelector?
5. Add ModelLifecycleController?
6. Add GmToolGateway?
7. Make PLAYER ACTION SOURCE = USER ONLY a canonical GM invariant?
8. Make Actor/Action/Target preservation a canonical GM invariant?
9. Explicitly state that no AI provider owns durable campaign memory?
10. Make Phase 46 model-capability-aware?
11. Extend Phase 78 with local inference thermal/RAM/battery acceptance?
12. Extend Phase 79 with separate model/runtime/backend routing?
13. Keep TEMP-GM as noncanonical benchmark infrastructure?
14. Allow independent Bielik/PLLuM/Gemma/LiteRT/ExecuTorch R&D before Phase 48?
```

Until such decisions are accepted, this report remains architecture input rather than canonical specification.
