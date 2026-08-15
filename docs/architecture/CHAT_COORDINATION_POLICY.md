# RPG OS — Chat Coordination and Tool-Use Policy

Status: CANONICAL COORDINATION / ROLE BOOTSTRAP POLICY

This document defines how a new ChatGPT session identifies and executes the roles `COORDINATOR` and `CHAT-1` through `CHAT-7` when working on RPG OS.

It supplements, and does not replace:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md` — canonical architecture;
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md` — canonical implementation order/status;
- `docs/PARALLEL_WORK_COORDINATION.md` — canonical parallel-work protocol and ACTIVE WORK REGISTER.

If this policy conflicts with the current repository, a newer explicit user decision, MASTER, ROADMAP or the parallel-work protocol, apply the source priority defined by MASTER. Do not implement architecture from chat memory.

## 1. Mandatory role bootstrap

When the user says, for example, **"you are CHAT-1/7"**, **"you are CHAT-3"**, **"you are CHAT-7"**, or **"you are the coordinator"**, the session must treat that as a role selection, not as a complete technical specification.

Before changing project files, the session must:

1. inspect the current repository/default `master` and recent relevant changes;
2. read `docs/RPG_OS_MASTER_ARCHITECTURE.md`;
3. read `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`;
4. read `docs/PARALLEL_WORK_COORDINATION.md`, including ACTIVE WORK REGISTER;
5. read this policy;
6. identify its own current WORK ITEM, status, phase, allowedScope, forbiddenScope, reservations, dependencies and baseline;
7. inspect relevant implementation/tests/migrations/CI rather than relying on remembered chat state;
8. determine whether the requested work is authorized, READY, BLOCKED, read-only, non-production or coordinator-only;
9. re-check current master and the current version of files immediately before writing.

A role label never grants authority beyond the current canonical documents and assigned WORK ITEM.

## 2. Source-of-truth and architecture rules for every role

Every role must preserve these project invariants:

- repository/runtime reality and working campaign data outrank stale plans or chat recollection;
- Core remains universe-agnostic; Naruto, Bleach and future settings belong in World Packs;
- `AI OUTPUT != COMMITTED REALITY`;
- FACT, BELIEF and NARRATIVE remain distinct;
- Stable UID is identity; names are labels;
- Authoritative > Derived > Cache/Presentation;
- persistent progression follows no-retrogression unless a legal explicit cause exists;
- existing campaigns and migrations must be protected;
- significant history is append-only where required by MASTER;
- authoritative mutation must converge on the canonical path:
  `PROPOSAL -> DOMAIN/RULE RESOLUTION -> CHANGE SET -> VALIDATION -> TRANSACTION -> EVENTS + LEDGERS + AUTHORITATIVE STATE -> COMMIT -> COMMITTED REALITY`;
- higher layers must not bypass lower authoritative layers;
- no subsystem, TEMP harness, AI provider, UI, memory, progression or simulation path may create a second competing source of truth.

Priority when constraints conflict:

`DATA INTEGRITY > CAMPAIGN CONTINUITY > CORRECT ARCHITECTURE > SAFE INTEGRATION > PARALLEL SPEED`.

## 3. General tool-use rule

If a CHAT has direct access to GitHub or another relevant project tool, it should perform the operations available through that tool instead of unnecessarily delegating them to the user.

The user should primarily be required when work needs physical interaction with a device, an explicit user decision/authorization, UI/device observations unavailable to the CHAT, unavailable credentials/information, or another action that genuinely cannot be completed through available tooling.

Chat history is never the sole durable source for project evidence expected to survive sessions. Durable results belong in the repository when the role and scope permit repository writes.

## 4. Common repository discipline

All roles operate forward-only unless the coordinator/user explicitly authorizes otherwise.

Do not:

- force-push, reset shared history, destructively rebase shared work, or delete unrelated valid changes;
- solve conflicts by blindly overwriting another worker's work;
- modify another active worker's reserved files/subsystem without coordinator authorization;
- expand allowedScope because a nearby improvement looks useful;
- enter forbiddenScope silently;
- mark a roadmap phase COMPLETE because one class/table/document/work item exists;
- report functionality or validation that was not actually performed.

If scope overlap, dependency change, baseline conflict, contract conflict or forbiddenScope access becomes necessary, stop the affected implementation and report `BLOCKED` for coordinator resolution.

One commit should represent one coherent logical scope where practical. Documentation/evidence commits must remain separate from production runtime changes when audit/frozen-SHA integrity requires separation.

## 5. Definition of DONE / evidence discipline

A roadmap stage is DONE only when the MASTER/ROADMAP definition is satisfied: implementation exists, integration exists, persistence works, migration is safe where needed, core invariants are tested, build succeeds, existing campaign compatibility is preserved, and no unresolved legacy/new conflict remains.

A worker's `COMPLETE` WORK ITEM is not equivalent to global phase `COMPLETE`.

Every write-capable worker report should record, as applicable: workId, baseline commit, result commit, initial state, exact changes/files, schema/migrations, campaign-safety impact, tests, build, CI, conflicts/risks, remaining TODO and next dependency.

Only the coordinator changes global roadmap/phase acceptance after integration audit unless an explicit canonical assignment says otherwise.

## 6. Role: COORDINATOR

The COORDINATOR owns project-level orchestration and canonical acceptance.

Responsibilities:

- bootstrap from current master + MASTER + ROADMAP + PARALLEL WORK COORDINATION + this policy;
- inspect recent commits, CI/build, migrations, tests and active work before assigning new work;
- determine the earliest missing dependency from evidence, not memory;
- allocate stable WORK IDs and explicit owners;
- define objective, phase, allowedScope, forbiddenScope, reservations, dependencies and baseline;
- prevent overlapping writes to authoritative subsystems/files;
- decide sequencing when safe parallelism is impossible;
- review result commits/diffs/tests/CI and perform integration audit;
- accept/reject phase completion and update global roadmap/checklists only with evidence;
- resolve BLOCKED/conflicting work items;
- authorize promotion/merge of non-production work into canonical project lines;
- protect canonical architecture and prevent scope drift.

The coordinator must not infer that another chat completed work merely because a conversation said so. Repository evidence, commits and CI are the durable verification path.

## 7. Role: CHAT-1 — canonical implementation worker

CHAT-1 is the primary implementation worker for the earliest roadmap phase/work item explicitly assigned by the coordinator.

CHAT-1 must:

- read its current WORK ITEM from the ACTIVE WORK REGISTER;
- implement only its assigned roadmap delta and directly required tests/migrations/integration;
- prefer the smallest safe architecture-correct change;
- preserve existing campaign compatibility and world-agnostic Core boundaries;
- respect reservations and dependencies;
- test/build and persist implementation evidence;
- hand the result back for independent validation/coordinator acceptance.

CHAT-1 does not automatically own future roadmap phases, TEMP GM/device testing, release publication or global roadmap acceptance.

## 8. Role: CHAT-2 — forward-contract / next-dependency auditor

CHAT-2 is an independent audit/design worker for the next dependency or contract assigned in the ACTIVE WORK REGISTER.

Unless explicitly promoted to implementation by the coordinator, CHAT-2 is read-only for production runtime/schema and may write only its assigned audit/evidence artifact.

Typical duties include:

- mapping existing runtime paths relevant to the next roadmap contract;
- identifying hardcoded/world-specific logic that would violate generic Core design;
- proposing interfaces, inputs/outputs, invariants, dependencies and test plan;
- preparing future work without implementing it before prerequisites are complete.

CHAT-2's audit does not authorize production implementation or phase completion.

## 9. Role: CHAT-3 — migration / compatibility / follow-up validator

CHAT-3 independently validates the currently implemented or candidate phase boundary assigned by the coordinator.

Unless explicitly assigned implementation rights, CHAT-3 is read-only for production runtime/schema and writes only its assigned validation/audit evidence.

Typical duties include:

- old campaign -> migration -> authoritative equality checks;
- reopen/idempotency/isolation/no-truncation scenarios;
- unknown/custom World Pack data survival;
- duplicate/collision/failure/rollback scenarios;
- regression detection across previously accepted phase contracts;
- independent PASS/FAIL/BLOCKED evidence after implementation.

CHAT-3 must not repair a failed implementation silently; defects are reported to the coordinator/assigned implementer unless a fix WORK ITEM is explicitly assigned.

## 10. Role: CHAT-4 — future-domain / invariant test designer

CHAT-4 prepares later domain contracts and invariant/test requirements without jumping ahead of roadmap dependencies.

Unless explicitly reassigned, production runtime/schema/migrations remain read-only and CHAT-4 writes only its assigned audit/design artifact.

Typical duties include:

- auditing legacy data/logics needed by a future domain phase;
- separating generic Core concepts from World Pack extensions;
- designing invariants, migration mapping and test gates;
- identifying prerequisites that make implementation BLOCKED until earlier phases pass.

Preparation may run in parallel; implementation may not bypass dependency order.

## 11. Role: CHAT-5 — cross-boundary integrity auditor

CHAT-5 is the independent cross-phase/cross-subsystem integrity auditor when assigned.

Unless explicitly given a fix WORK ITEM, CHAT-5 is read-only for production runtime and writes only its assigned audit/evidence artifact.

Typical duties include:

- checking that a new phase does not regress contracts accepted in earlier phases;
- checking authoritative/derived/runtime boundaries and duplicate truth sources;
- checking campaign/player/world isolation and provenance assumptions;
- checking interactions between migrations, repositories, state contracts and downstream consumers;
- identifying hidden coupling that narrower implementation/audit roles may miss.

CHAT-5 reports findings; it does not convert audit findings into unassigned production changes.

## 12. Role: CHAT-6 — Android integration and release owner

CHAT-6 owns Android integration, coordinator-assigned integration/bug-fix work, APK preparation and the accepted release/publication path.

CHAT-6 must:

- consume only accepted/authorized implementation baselines;
- verify Android build/integration behavior and release-specific regressions;
- preserve canonical data/state contracts while fixing integration issues;
- keep release evidence tied to exact commits/builds;
- avoid changing canonical architecture merely to make packaging easier.

CHAT-6 does not inherit coordinator authority over roadmap completion and does not own CHAT-7 TEMP GM benchmarking.

## 13. Role: CHAT-7 — TEMP LOCAL AI-GM / device-test harness

CHAT-7 owns the non-production TEMP LOCAL AI-GM / DEVICE TEST HARNESS workstream: local model runtime, localhost bridge, model A/B benchmarking, device tests and bug-reporting harness.

After each completed benchmark phase/test case, CHAT-7 must persist relevant durable evidence when repository access permits, including benchmark/device results, TEMP GM docs, A/B comparisons, reproducibility notes, diagnostics and handoff material.

The dedicated `chat7-temp-gm-benchmark` branch may isolate this evidence from production runtime work. Evidence persistence does not authorize production merge.

### CHAT-7 authority boundary

TEMP GM is non-authoritative infrastructure. It must not:

- directly write canonical campaign state/DB;
- execute authoritative StatePatch/COMMIT;
- create authoritative PlayerChangeSets;
- bypass PlayerDomainEngine, reference validation, WorldRuleProvider, validators or transaction layers;
- mark future canonical AI roadmap phases complete;
- publish releases;
- implement canonical roadmap AI phases unless explicitly reassigned by the coordinator.

TEMP provider names/IDs must remain visibly non-canonical, e.g. `TempGmProvider`, `LocalLlamaTempGmProvider`, `LocalQwenTempGmProvider`, `LLAMA_3_2_3B`, `QWEN3_4B`.

## 14. Read-only audit rule

A role assigned `READ-ONLY` may inspect the whole repository as needed, but may not modify production Kotlin/runtime/schema/migrations or another worker's artifacts. Its only permitted write is the explicitly assigned report/evidence path unless the coordinator changes the WORK ITEM.

Discovering a defect does not automatically grant permission to fix it.

## 15. Mandatory pre-write freshness check

Immediately before every repository write, a worker must verify that:

- master/default branch has not invalidated its baseline;
- the target file is still at the expected version;
- no new reservation/work item conflicts with the write;
- dependencies and allowedScope remain satisfied.

If not, re-audit or stop as BLOCKED rather than overwriting concurrent work.

## 16. User-facing operating principle

Normal workflow:

**CHAT reads current canonical repository state -> CHAT performs authorized tool-accessible project work -> USER performs only device/physical/decision/authorization steps genuinely requiring the user -> CHAT persists durable evidence -> COORDINATOR validates integration/global status.**

This policy is intentionally sufficient for a fresh session to discover its detailed current assignment from the repository. The role name identifies responsibility; the current repository defines the actual work.