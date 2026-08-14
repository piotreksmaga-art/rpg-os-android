# RPG OS — Chat Coordination and Tool-Use Policy

Status: canonical coordination policy

## 1. General tool-use rule

If a CHAT has direct access to the repository or another relevant project tool, it should perform the project/repository operations that are available to it instead of delegating those operations to the user.

The user should be involved primarily when an action requires one or more of the following:

- physical interaction with a device;
- an explicit user decision or authorization;
- observation of device/UI behavior that the CHAT cannot access directly;
- information or credentials unavailable to the CHAT's tools;
- another action that genuinely cannot be completed through the available project tooling.

Chat history must not be treated as the only durable record for project evidence that is expected to survive across sessions.

## 2. CHAT-7 durable-evidence rule

CHAT-7 owns the TEMP LOCAL AI-GM / DEVICE TEST HARNESS workstream.

After each completed benchmark phase or completed test case, CHAT-7 must persist the relevant result and evidence in the repository when repository access permits it. This includes, as applicable:

- benchmark evidence;
- device-test evidence;
- TEMP GM documentation;
- A/B model comparison results;
- test-case results;
- reproducibility notes;
- diagnostic evidence;
- handoff material for the coordinator and CHAT-6.

Chat history is not the sole durable source for CHAT-7 benchmark/test results.

The dedicated `chat7-temp-gm-benchmark` branch is non-production and may be used to keep benchmark/device-test evidence isolated from production runtime work. Evidence persistence does not by itself authorize a production merge.

## 3. Responsibility boundaries

### CHAT-1

Owns implementation of roadmap phases assigned by the coordinator. CHAT-1 does not inherit TEMP GM/device-test ownership from CHAT-7 and does not inherit release ownership from CHAT-6.

### CHAT-2 through CHAT-5

Own independent revalidation/audit roles when assigned. Their audit work does not itself authorize production fixes, roadmap implementation, or release publication unless separately assigned.

### CHAT-6

Owns Android application integration, bug fixes/integration assigned by the coordinator, APK preparation and the accepted release/publication path. CHAT-6 remains release owner.

### CHAT-7

Owns TEMP GM runtime, localhost bridge, local-model benchmark/device testing and the bug-reporting harness. CHAT-7 does not publish releases and does not implement canonical roadmap AI phases unless explicitly reassigned by the coordinator.

### Coordinator

Owns canonical architecture/master decisions, phase acceptance decisions, responsibility allocation and decisions to promote or merge non-production work into canonical project lines.

## 4. TEMP GM authority boundary

The TEMP LOCAL AI-GM / DEVICE TEST HARNESS is non-authoritative test infrastructure.

It must not be promoted into canonical game-state authority. In particular, TEMP GM work does not authorize direct writes to authoritative campaign state, direct DB mutation, StatePatch execution, COMMIT, authoritative PlayerChangeSet creation, or bypass of PlayerDomainEngine, reference validation, WorldRuleProvider or later validation/transaction layers.

The TEMP provider naming remains explicitly non-canonical, for example:

- `TempGmProvider`
- `LocalLlamaTempGmProvider`
- `LocalQwenTempGmProvider`
- logical IDs such as `LLAMA_3_2_3B` and `QWEN3_4B`

This policy does not mark any future canonical AI roadmap phase as complete.

## 5. Repository and history discipline

Project work should remain forward-only unless the coordinator explicitly authorizes another operation. CHATs must avoid overwriting unrelated concurrent work and should use fresh repository state before writes when possible.

Documentation/evidence commits must be kept separate from production runtime changes when that separation is required for an audit or frozen-SHA revalidation.

No role gains permission to change canonical architecture merely because it has repository write access. Tool access enables execution of already-authorized work; it does not replace coordinator authority.

## 6. User-facing principle

The intended workflow is:

**CHAT performs tool-accessible project operations → USER performs device/physical/decision/authorization steps that genuinely require the user → CHAT persists durable evidence when required.**

This reduces unnecessary manual repository work for the user while preserving explicit user control over physical-device actions, approvals and canonical project decisions.
