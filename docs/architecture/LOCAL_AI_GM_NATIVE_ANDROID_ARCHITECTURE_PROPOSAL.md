# RPG OS — LOCAL AI GM NATIVE ANDROID ARCHITECTURE PROPOSAL

Status: **PROPOSAL / NON-CANONICAL / COORDINATOR REVIEW REQUIRED**  
Author role: CHAT-7  
Date: 2026-08-17  
Baseline master audited: `3a66136a119078358fb89a6f2d1e794c6cda9b2d`

> This document does **not** modify `RPG_OS_MASTER_ARCHITECTURE.md`, does **not** change roadmap phase status, and does **not** authorize implementation of Phase 26+ or canonical AI phases. It is an architecture proposal for coordinator review before Phase 48 / native local-AI implementation.

---

## 1. Purpose

RPG OS is intended to support extremely long text-RPG campaigns: hundreds of thousands of turns, millions of events/words and years of play, while preserving authoritative state, history, progression, ownership, NPC knowledge, chronology and campaign divergence.

The target AI Game Master must ultimately run **from the RPG OS Android application**, not from Termux and not from a manually launched localhost process. Termux/localhost may remain a TEMP development harness only.

The production target is therefore:

```text
RPG OS Android application
  -> controlled GM orchestration
  -> provider abstraction
  -> native local inference runtime
  -> on-device model
  -> CPU / GPU / future supported NPU
```

The model must use RPG OS retrieval, rules, knowledge, memory and mechanics rather than replacing them.

---

## 2. Canonical architecture audit — what is already correct

The current MASTER architecture is fundamentally aligned with a native local AI GM and should **not** be replaced.

### 2.1 AI is not campaign storage or authority

MASTER already establishes that:

- AI is not the database;
- AI is not campaign memory;
- AI is not the mechanics calculator;
- AI is not the source of truth;
- `AI OUTPUT != COMMITTED REALITY`;
- durable truth flows through the canonical mutation path.

This is exactly the correct design for an on-device 2B–5B class model. A small model can be replaced, unloaded, restarted or upgraded without losing the campaign because campaign truth lives outside model weights and KV cache.

### 2.2 Six-layer direction is correct

The canonical dependency direction remains valid:

```text
SOURCE OF TRUTH
  -> CAMPAIGN STATE
  -> CAMPAIGN INTELLIGENCE
  -> SIMULATION / RULE ENGINE
  -> CONTEXT & DIRECTOR
  -> AI GAME MASTER
```

A local model belongs at the final AI-GM layer. It must not invert this dependency by becoming the holder of truth or rules.

### 2.3 Single Truth Mutation Path is essential

The existing invariant:

```text
PROPOSAL
  -> DOMAIN/RULE RESOLUTION
  -> CHANGE SET
  -> VALIDATION
  -> TRANSACTION
  -> EVENTS + LEDGERS + AUTHORITATIVE STATE
  -> COMMIT
```

must remain unchanged.

Native local inference must never create a shortcut such as:

```text
Local model -> direct DB/state write
```

or:

```text
Android UI -> model-generated mutation -> authoritative state
```

### 2.4 Context / retrieval roadmap is correctly placed before canonical AI

Roadmap phases 41–47 are essential prerequisites for a small local GM:

- Structured SQL Retriever;
- Knowledge Graph / causal retrieval;
- Intent Parser;
- Turn Planner;
- Context Builder;
- Context Budget Manager;
- Iterative Retrieval / missing-context loop.

For long campaigns, model context is a bounded working set, not long-term storage.

### 2.5 Memory architecture is suitable for model replacement

The canonical Working / Episodic / Semantic Campaign Memory split is appropriate. The prohibition on recursive summary-of-summary degradation is especially important for campaigns lasting years.

A local inference runtime must be allowed to discard all model runtime memory after unload. Relevant campaign information must be reconstructable by retrieval/context assembly.

---

## 3. Identified architecture gap

The current roadmap contains:

- Phase 48 — `AiProvider abstraction`;
- later Android performance / model-routing work in phases 78–79.

This is not yet explicit enough for a **production native Android local model**.

`AiProvider` should not own every device/runtime concern. A provider abstraction that directly mixes model identity, Android lifecycle, native libraries, model files, hardware backend selection, thermal policy, tokenizer behavior, streaming, memory pressure and GM semantics will become difficult to replace and test.

The missing concept is an explicit **Native Local AI Runtime subsystem** below `AiProvider` and above runtime-specific engines such as LiteRT-LM or ExecuTorch.

---

## 4. Proposed separation of responsibilities

### 4.1 `AiProvider`

Logical GM inference abstraction.

Responsibilities:

- accept an RPG OS GM request / context bundle;
- declare provider capabilities;
- invoke the selected inference runtime;
- return model output/proposal;
- expose provider status and failures;
- remain independent of UI and canonical persistence.

Non-responsibilities:

- loading arbitrary DB state;
- direct authoritative mutation;
- hardware-specific memory allocation policy;
- Android Activity lifecycle;
- raw model-file management;
- direct SQL access.

Conceptually:

```text
AiProvider.generate(GmRequest) -> GmModelResult
```

### 4.2 `LocalInferenceRuntime`

Native Android inference execution abstraction.

Responsibilities:

- initialize native runtime;
- load/unload model;
- tokenize / prepare inference input where runtime requires it;
- prefill / decode;
- stream tokens/chunks;
- cancel generation;
- expose runtime/backend state;
- release RAM/GPU resources;
- handle runtime faults without mutating canonical state;
- expose measured memory/performance information;
- react to memory pressure / lifecycle requests.

Possible implementations:

```text
LiteRtLmRuntime
ExecuTorchRuntime
LlamaCppNativeRuntime   (optional/fallback/research)
```

Termux is **not** a production runtime implementation.

### 4.3 `ModelProfile`

Model identity/configuration must be data-driven rather than encoded in provider classes.

Suggested fields:

```text
modelUid
modelFamily
modelVersion
modelFormat
quantization
modelArtifactVersion
modelChecksum
minimumRuntimeVersion
supportedRuntimeIds
contextLimit
recommendedContextBudget
recommendedOutputBudget
capabilities
storageBytes
expectedPeakRamClass
licenseMetadata
chatTemplateId
```

This allows Bielik, PLLuM, Gemma or a future model to be swapped without changing campaign storage or GM orchestration architecture.

### 4.4 `RuntimeBackend`

Hardware execution is a separate concern from model selection.

Suggested values/capabilities:

```text
CPU
GPU
NPU
AUTO
```

with runtime-specific concrete mappings, e.g.:

```text
LiteRT-LM GPU
ExecuTorch XNNPACK
ExecuTorch Vulkan
future supported NPU backend
```

Do not equate `ModelRouter` with `HardwareBackendSelector`.

### 4.5 `ModelLifecycleController`

Android needs explicit local-model lifecycle management.

Responsibilities should include:

- installed/not-installed state;
- artifact verification/checksum;
- load state (`UNLOADED`, `LOADING`, `READY`, `ERROR`);
- unload/close;
- recovery after process death;
- safe behavior on OOM or memory pressure;
- optional model update/migration metadata;
- cancellation on user exit / provider switch;
- no campaign-state side effects on runtime failure.

### 4.6 `AiCapabilityContract`

Providers/models should declare capabilities rather than forcing all models into the same output shape.

Suggested capabilities:

```text
TEXT_GENERATION
STREAMING
STRUCTURED_OUTPUT
CONSTRAINED_DECODING
TOOL_CALL_REQUESTS
MAX_CONTEXT
MAX_OUTPUT
VISION
AUDIO
```

The Turn Planner can then request only outputs supported and needed for that turn.

### 4.7 `GmToolGateway`

The model must be able to use RPG OS tools **without receiving mutation authority**.

The gateway is an allowlisted broker between AI orchestration and canonical/read-only systems.

Examples of safe classes of operations:

```text
queryPlayerSnapshot(...)
queryNpcKnowledge(...)
retrieveRelevantEvents(...)
retrieveCanon(...)
queryLocationState(...)
queryRelationships(...)
requestMechanicsResolution(...)
```

Forbidden direct tools:

```text
setHp(...)
addMoney(...)
grantSkill(...)
writeSave(...)
updateDatabase(...)
commit(...)
```

Tool requests that imply mechanics must resolve through existing domain/rule/validation/transaction paths.

---

## 5. Proposed production pipeline

### 5.1 Simple narrative/dialogue turn

```text
PLAYER INPUT
  -> Intent Parser
  -> Turn Planner
  -> bounded Retrieval
  -> Context Builder
  -> AiProvider
  -> LocalInferenceRuntime
  -> MODEL
  -> narrative/proposal validation
  -> delivery to user
```

If no authoritative mutation is required, the turn should avoid unnecessary mechanics passes.

### 5.2 Mechanics-relevant turn

Recommended conceptual flow:

```text
PLAYER INPUT
  -> Intent Parser / Turn Planner
  -> retrieval + context
  -> GM proposal / interpretation
  -> mechanics/domain resolution
  -> validation
  -> transaction / COMMIT
  -> final narration based on confirmed result
  -> USER
```

For local models this may require two inference passes only when needed:

```text
PASS 1: interpret / propose
MECHANICS: authoritative resolution
PASS 2: narrate confirmed result
```

Do **not** require two model passes for every dialogue or low-risk narrative turn.

---

## 6. Long-campaign memory contract

### 6.1 KV cache is working inference memory only

Model KV cache must never be treated as campaign memory.

After runtime unload/restart:

```text
CampaignRepository
+ Event/Memory/Knowledge systems
+ Retriever
+ Context Builder
=> reconstruct needed GM context
```

This allows the campaign to survive model replacement, runtime upgrades, process death and device reboot.

### 6.2 Context bundle should be relevance-driven

A future Context Budget Manager should budget by semantic importance rather than dump full world state.

Example 8K-class budget (illustrative only, not canonical):

```text
system / GM contract          500–900
current scene                400–800
player GM-context snapshot   300–600
relevant NPC state           500–900
NPC knowledge                600–1000
recent actions/dialogue      800–1400
retrieved events/memory      900–1600
confirmed mechanics          200–500
output reserve               700–1200
```

The exact budget must be device/model benchmarked and later owned by Phase 46 semantics.

---

## 7. Structured GM Output should be capability- and turn-dependent

MASTER correctly plans Structured GM Output, but small local models should not be forced to emit every possible section on every turn.

Recommended direction:

```text
TurnPlan.requiredOutputs = {
    NARRATIVE,
    NPC_INTENT,
    PROPOSED_EVENT
}
```

instead of always requiring:

```text
narrative + events + state + knowledge + relationships + memory + chronicle + threads + time + warnings + ...
```

Benefits:

- fewer output tokens;
- lower latency and battery cost;
- fewer malformed structures;
- simpler validation;
- easier constrained decoding;
- better fit for 2B–5B mobile models.

This does not weaken canonical validation; it narrows each request to what the Turn Planner actually needs.

---

## 8. Android-native runtime requirements

Before any local model becomes an accepted production GM provider, its runtime path should satisfy at least:

### Lifecycle

- native Android integration (Kotlin/Java/JNI/C++ library as appropriate);
- no Termux dependency;
- no manual server start;
- deterministic load/unload state;
- cancellation;
- clean resource release;
- safe app/process restart behavior.

### Performance

- bounded peak RSS;
- measured model load time;
- measured TTFT;
- measured prefill throughput;
- measured decode throughput;
- sustained multi-turn thermal behavior;
- battery drain benchmark;
- OOM behavior;
- performance under target context sizes.

### Security/privacy

- localhost networking not required for production in-process runtimes;
- model files verified before load;
- no secrets in model prompts/logs;
- no arbitrary filesystem exposure to the model;
- no direct DB credentials/handles;
- tool calls allowlisted through RPG OS.

### Canonical safety

- runtime failure cannot mutate campaign state;
- cancellation cannot partially commit a turn;
- provider switch cannot alter authoritative state;
- model upgrade cannot require rewriting campaign history;
- AI output remains proposal/presentation until validated/committed.

---

## 9. Current runtime technology assessment (2026-08-17)

This section is informative, not canonical.

### 9.1 LiteRT-LM

Current official Google documentation describes LiteRT-LM as an on-device LLM runtime with Android CPU/GPU/NPU support and Kotlin API. The Hugging Face export path can produce `.litertlm` artifacts for supported Transformer architectures and deploy them on-device.

Relevant characteristics for RPG OS:

- native Android API;
- CPU/GPU support;
- NPU path on supported hardware;
- model lifecycle through Engine/Conversation abstractions;
- streaming-capable inference API;
- `.litertlm` deployment artifact;
- `export_hf` path for several common causal-LM architectures.

Primary sources:

- https://developers.google.com/edge/litert-lm/overview
- https://developers.google.com/edge/litert-lm/api_overview
- https://developers.google.com/edge/litert/conversion/pytorch/genai

### 9.2 ExecuTorch

Current official PyTorch documentation provides Android AAR integration and backends including XNNPACK CPU and Vulkan GPU; LLM-specific Java integration exists but is documented as experimental.

Relevant characteristics:

- Android AAR;
- CPU/XNNPACK backend;
- Android-focused Vulkan GPU backend;
- 4-bit/8-bit quantized linear support in Vulkan backend;
- native C++ runtime path;
- hardware backend separation.

Primary sources:

- https://docs.pytorch.org/executorch/stable/using-executorch-android.html
- https://docs.pytorch.org/executorch/stable/android-xnnpack.html
- https://docs.pytorch.org/executorch/stable/android-vulkan.html
- https://docs.pytorch.org/executorch/stable/llm/run-on-android.html

### 9.3 Recommendation at architecture level

Do **not** hardcode RPG OS around LiteRT-LM or ExecuTorch.

Instead:

```text
RPG OS AiProvider
  -> LocalInferenceRuntime
       -> LiteRT-LM implementation
       -> ExecuTorch implementation
       -> future implementation
```

The current technology winner may change during the lifetime of a multi-year RPG OS project.

---

## 10. Model selection must be separate from runtime selection

The architecture should allow a tournament of local models without rewriting the engine.

Candidate model families may include Polish-specialized and mobile-first models such as Bielik, PLLuM, Gemma variants or future models. Candidate inclusion/exclusion is a benchmark/product decision, not a core-architecture decision.

A model should be accepted only after:

1. native-runtime feasibility;
2. Android device stability;
3. quality benchmark for Polish narrative;
4. semantic GM contract benchmark;
5. long-context/retrieval-use benchmark;
6. thermal/RAM/battery benchmark;
7. license/artifact verification.

---

## 11. RPG OS Local GM benchmark proposal

Public general-purpose benchmarks are insufficient for choosing the GM.

RPG OS should eventually own a dedicated benchmark suite covering at least:

### Semantic authority

- PLAYER agency preservation;
- actor/action/target preservation;
- no invented player action/dialogue/ability;
- NPC autonomy preserved;
- stop-before-next-player-turn;
- canonical-vs-narrative distinction.

### Knowledge and memory use

- facts present in context retained;
- NPC knowledge isolation;
- belief vs fact distinction;
- rumor handling;
- temporal truth;
- retrieval result use;
- conflict detection;
- long-context fact retention at multiple context sizes.

### RPG quality

- Polish grammar and naturalness;
- character voice consistency;
- NPC differentiation;
- combat narration;
- social dialogue;
- pacing;
- anti-repetition;
- campaign continuity;
- world-pack rule adherence.

### Runtime/device

- model size;
- peak RSS;
- model load time;
- TTFT;
- prefill tok/s;
- decode tok/s;
- 10+ consecutive generations;
- thermal throttling;
- battery drain;
- crash/OOM recovery;
- context-size scaling.

A model should have separate scores, e.g.:

```text
GM_SEMANTICS
POLISH
CONTEXT_FACT_RETENTION
NPC_KNOWLEDGE
NARRATIVE
RUNTIME_PERFORMANCE
THERMAL_STABILITY
MEMORY_FOOTPRINT
```

No single `tok/s` or public reasoning benchmark should select the production GM.

---

## 12. Roadmap impact recommendation

### 12.1 Do not change current nearest work

Current canonical roadmap says Phase 26 is next and requires AUDIT FIRST. This proposal does not change that.

Recommended sequence remains:

```text
Phase 26+
  -> campaign-integrity foundation
  -> knowledge/retrieval/context phases
  -> canonical GM engine phases
```

### 12.2 Before canonical Phase 48 implementation, coordinator should decide where to formalize native-local runtime work

Two reasonable options:

#### Option A — subphases under Phase 48

```text
48.1 AiProvider contract
48.2 LocalInferenceRuntime
48.3 ModelProfile / artifact contract
48.4 RuntimeBackend abstraction
48.5 ModelLifecycleController
48.6 AiCapabilityContract
48.7 GmToolGateway integration contract
```

#### Option B — explicit pre-48 cross-cutting architecture phase

For example:

```text
47A Native Local AI Runtime Architecture
47B Android Model Lifecycle / Artifact Contract
47C Hardware Backend / Capability Contract
```

then canonical Phase 48 consumes these contracts.

This document does **not** choose between A and B; coordinator should decide after Phase-26/coordination impact review.

### 12.3 Phase 78 / 79 clarification

Phase 78 can remain the deep optimization phase, but device budgets should exist before final provider selection.

Phase 79 should eventually distinguish:

```text
ModelRouter
```

from:

```text
RuntimeBackendSelector
```

because selecting a model and selecting CPU/GPU/NPU are different decisions.

---

## 13. Proposed acceptance gates for native local GM architecture

Before declaring the native local-GM integration production-ready:

### Gate A — canonical isolation

- AI cannot directly mutate authoritative state;
- all mutation follows canonical path;
- provider/runtime crash leaves canonical state unchanged.

### Gate B — runtime abstraction

- at least one Android-native runtime implementation;
- provider code independent of concrete runtime;
- model profile independent of campaign storage.

### Gate C — tool boundary

- model has no raw DB access;
- only allowlisted query/proposal operations;
- mechanics requests resolve through canonical engines.

### Gate D — long-campaign continuity

- model unload/reload does not lose campaign knowledge;
- provider/model replacement does not require campaign migration;
- context reconstructed from canonical memory/retrieval.

### Gate E — device viability

- sustained device benchmark passes target RAM/thermal/battery thresholds;
- clean load/unload;
- safe cancellation;
- safe process death/restart.

### Gate F — GM semantic quality

- project-specific semantic benchmark passes minimum thresholds;
- player agency violations below acceptance threshold (ideally zero for hard invariants);
- NPC knowledge leakage below acceptance threshold (hard invariant: zero for protected facts);
- Polish narrative quality accepted.

---

## 14. Migration from TEMP harness

Current TEMP architecture may continue to be used for testing:

```text
Android UI
  -> localhost bridge
  -> llama.cpp
  -> local model
```

Production migration should preserve the logical provider contract while replacing transport/runtime:

```text
TEMP:
Android -> localhost HTTP -> TempGmProvider -> llama.cpp

PRODUCTION TARGET:
Android -> AiProvider -> LocalInferenceRuntime -> native runtime -> model
```

Reusable TEMP learnings include:

- logical provider IDs;
- provider states;
- context-budget discipline;
- NPC knowledge isolation;
- player-agency semantic tests;
- non-authoritative AI boundary;
- degraded/failure behavior;
- device benchmarking methodology.

TEMP bridge itself should not become mandatory production architecture.

---

## 15. Non-goals

This proposal does not:

- implement Phase 26;
- implement Phase 48;
- implement native LiteRT-LM or ExecuTorch runtime;
- change PlayerDomainEngine;
- change WorldRuleProvider;
- change canonical DB/schema;
- choose final GM model;
- choose final quantization;
- choose final context size;
- authorize NPU-specific work;
- publish an APK/release;
- alter accepted roadmap statuses.

---

## 16. Coordinator decisions requested later

Before canonical GM-engine implementation, coordinator should explicitly decide:

1. Whether `LocalInferenceRuntime` becomes a formal canonical contract.
2. Whether runtime/model lifecycle is represented as Phase-48 subphases or a pre-48 cross-cutting phase.
3. Whether `GmToolGateway` is formalized under Turn Planner / AiProvider integration.
4. Whether `AiCapabilityContract` becomes a required provider contract.
5. Where `ModelProfile` / model-artifact verification is owned.
6. Device acceptance budgets required before a local model can become production provider.
7. How Phase 79 distinguishes model routing from hardware backend routing.

---

## 17. Final recommendation

**Keep the canonical RPG OS architecture. Do not redesign the core around a specific model or runtime.**

Add an explicit native local-inference layer before canonical AI-GM integration so the final architecture becomes:

```text
Campaign truth / state / memory / rules
  -> retrieval / turn planning / context
  -> AiProvider
  -> GmToolGateway (controlled queries / proposals)
  -> LocalInferenceRuntime
  -> runtime implementation (LiteRT-LM / ExecuTorch / future)
  -> ModelProfile (Bielik / PLLuM / Gemma / future)
  -> structured proposal / narration
  -> validation / mechanics / transaction
  -> COMMIT
  -> committed narrative to user
```

The production user should launch **RPG OS only**. The application owns the local AI lifecycle. Termux and manually started localhost services remain test infrastructure only.
