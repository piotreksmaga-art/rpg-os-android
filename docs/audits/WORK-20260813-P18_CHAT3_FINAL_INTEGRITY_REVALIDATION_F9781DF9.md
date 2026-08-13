# WORK-20260813-P18 — CHAT-3 Final Integrity Revalidation

ROLE: CHAT-3 — Independent Integrity Auditor

VALIDATED RUNTIME SHA: `f9781df9c3828b06562aad86a91dec9682c02530`

FINAL VERDICT: **PHASE 18 INTEGRITY REVALIDATION: FAIL**

## Repository pin

Fresh master before this report was `956e64671fa3b71493958e91934257efea6c5310`.

`f9781df9c3828b06562aad86a91dec9682c02530..master` contained exactly one later file, `docs/audits/WORK-20260813-P18_CHAT1_STRUCTURAL_BOUNDARY_REDESIGN_F9781DF9.md`. It was report-only. No later Phase-18 production/test runtime existed. Audit remained pinned to the exact target SHA.

## What was independently inspected

Production:
- `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- `app/src/main/java/com/rpgos/app/PlayerResolutionComponentStateValidator.kt`
- accepted Phase-17 `PlayerCommand`, `PlayerChangeSet`, value/reference and codec boundaries as consumed by Phase 18

Tests:
- `app/src/test/java/com/rpgos/app/PlayerDomainEngineTest.kt`
- `app/src/test/java/com/rpgos/app/PlayerDomainEngineInheritedStateTest.kt`
- relevant Phase-17 regression suites through exact CI evidence

CI:
- GitHub Actions run #401
- run ID `31727239097`
- exact head SHA `f9781df9c3828b06562aad86a91dec9682c02530`
- completed / success

## Gate results

| Gate | Result | Notes |
|---|---|---|
| Public construction paths | PASS | Engine is public entry; component/registry/draft internals are internal. Components return typed draft/rejection, not final ChangeSet. |
| Hierarchy-aware state validation | PASS for field-state model | Validator walks runtime subclass hierarchy until `PlayerResolutionComponent`, examines declared/inherited fields and rejects mutable or unsupported field types. |
| Inherited writer attack | PASS | Writer captured in inherited field is rejected at registry construction before component body executes. |
| Direct writer field attack | PASS | Direct SQLite/DAO/store-like object field type is outside safe scalar/enum whitelist and rejected. |
| Safe immutable inherited state | PASS | final scalar/String/enum state remains acceptable. |
| Read-only capability | PASS only for structural context surface | `PlayerResolutionContext` carries immutable copied reference/evidence/version values rather than writer/read-write capability objects. |
| Mutable state leakage | PASS for instance fields | non-final component fields are rejected; registry/context/draft collections are copied and unmodifiable. |
| Failure atomicity | **FAIL** | Stateless component body can perform authoritative write, then return Rejected/throw/produce invalid draft. Engine cannot roll it back. |
| Determinism | PASS for explicit modeled inputs, but not enforceable against arbitrary component body | Entropy is explicit in context/evidence and deterministic fingerprint. A component body can still consult global time/random/static state because method code is unrestricted. |
| Context immutability | PASS | known refs and dependency versions are defensive immutable copies; entropy is immutable value state. |
| Routing integrity | PASS | exact kind lookup, duplicate registration reject, unsupported kind fail closed, payload type mismatch before invocation. |
| Reference integrity | PASS | command refs checked before component; draft refs checked before proposal; wrong campaign/unknown refs reject. |
| PlayerChangeSet construction | PASS | component returns `PlayerResolutionDraft`; engine owns final ChangeSet construction and canonical Phase-17 validation. |
| Numeric integrity | PASS | no new Float/Double/toInt/string numeric coercion path found in orchestration; Phase-17 ExactLongDelta/ProjectProgressDelta/OwnershipShare semantics retained. |
| Serialization | PASS | representative engine proposal closes encode→decode→encode through Phase-17 codec. |
| Fingerprint | PASS for legal proposal path | context/command/proposal fingerprints deterministic for modeled input; component state blocker below is independent of canonical codec. |
| Authority boundary | **FAIL** | Component method body can invoke arbitrary/global/local writer despite having no prohibited field. |
| Zero authoritative mutation | **FAIL as enforceable contract** | Benign tested components are read-only, but API cannot enforce this for supported internal component implementations. |
| Phase-17 bug regression | PASS | ExactLongDelta zero reject, project zero accept/negative reject, composite STAT alias, asset identity and financial causal constraints retained. |
| Test quality | **FAIL** | Tests cover captured/inherited writer fields but do not attack a stateless component that writes via global/static/local authority from its method body. |
| Phase 3–17 regression | PASS in exact-CI-covered scope | Full JVM suite passes on exact SHA. |
| Full JVM | PASS via exact CI; NOT-RUN locally | Audit environment had no local checkout of repository, so no independent local Gradle invocation was claimed. |
| Exact CI | PASS | #401 / 31727239097 / exact head / success. |

## Release blocker P18-INT-AUTH-STATELESS-01

### Invariant

Phase-18 resolution components must be structurally proposal-only. Registration must prevent supported components from executing authoritative writes, including failure paths.

### Production path

`PlayerResolutionComponentRegistry.of(...)`
→ `PlayerResolutionComponentStateValidator.validate(component)`
→ `PlayerDomainEngine.resolve(...)`
→ `resolveTyped(component, canonicalCommand, context)`
→ `PlayerResolutionComponent.resolve(...)`

The validator inspects **object fields**. It cannot inspect or restrict side effects implemented directly in the method body.

### Minimal reproducer

A component can have no instance fields at all, therefore passes hierarchy-aware state validation, but call a global/static writer or create a local writer in `resolve()`:

```kotlin
internal class StatelessWritingComponent : PlayerResolutionComponent<TrainCommandPayload>(
    PlayerCommandKinds.TRAIN,
    TrainCommandPayload::class,
    "RPGOS-COMPONENT:STATELESS-WRITER",
    "1"
) {
    override fun resolve(
        command: PlayerCommand<TrainCommandPayload>,
        context: PlayerResolutionContext
    ): PlayerResolutionComponentOutcome {
        GlobalAuthoritativeStore.updatePlayerStat(...) // or locally obtain/open a writer
        return PlayerResolutionComponentOutcome.Rejected(
            PlayerResolutionRejection.create(PlayerResolutionRejectionReason.DOMAIN_REJECTED)
        )
    }
}
```

No unsafe/mutable component field exists, so registration succeeds. The authoritative write occurs when the engine invokes the component.

### Expected

The Phase-18 supported component contract must make authoritative writes structurally unavailable or otherwise guarantee/verify proposal-only execution before a component is admitted/executed.

Failure must mean:
- no authoritative write,
- no partial success,
- no second route,
- no persistent side effect.

### Actual

Field-state hardening catches captured/inherited writer capability but does not constrain the body of `resolve()`. A registered stateless component can mutate authority and then return rejection, throw, or return an invalid draft. The engine has no transaction/rollback/sandbox capable of undoing such a write.

### Architectural impact

This preserves the same fundamental authority/failure-atomicity class found in the earlier Phase-18 candidate, only with captured writer state removed. The proposal-only boundary is therefore not enforceable by the final component API/state validator.

### Why tests missed it

Existing tests use:
- `DbCapturingTrainComponent` / inherited writer fixture, where writer capability is stored in a field and therefore rejected;
- benign rejection/throw components with no writer;
- reflection assertions over engine/context fields.

They do not instantiate a component with no prohibited state whose method body obtains/calls authoritative capability through global/static/local code.

## Secondary integrity note: deterministic method body is also conventional

The same structural limitation means a stateless component can call `System.currentTimeMillis()`, `UUID.randomUUID()`, `Random`, or mutable global state without exposing such capability as a field. Explicit `ResolutionEntropyEvidence` is correctly designed and tested, but the component API does not enforce exclusive use of it.

This is not listed as a separate blocker because it has the same root cause as P18-INT-AUTH-STATELESS-01: unrestricted component method bodies remain part of the trusted computing base.

## Exact CI evidence

GitHub Actions:
- run number `401`
- run ID `31727239097`
- head SHA `f9781df9c3828b06562aad86a91dec9682c02530`
- status `completed`
- conclusion `success`

Green CI is execution evidence, not proof of the missing stateless-writer case.

## Final CHAT-3 verdict

**PHASE 18 INTEGRITY REVALIDATION: FAIL**

New blocker: `P18-INT-AUTH-STATELESS-01`.

No production/test/schema/workflow file was modified by CHAT-3. This commit is audit-report-only.

Phase 18 is not globally accepted. Phase 19 remains blocked until the coordinated acceptance condition is satisfied.
