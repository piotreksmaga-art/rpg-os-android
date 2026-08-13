# WORK-20260813-P18 — CHAT-1 Structural Boundary Redesign Final Recovery

## Status

FINAL PHASE-18 RUNTIME SHA: `f9781df9c3828b06562aad86a91dec9682c02530`

OLD REJECTED PHASE-18 RUNTIME: `3d5c24438d477bb6670efcb31771058332bd451f`

FAILED RECOVERY CANDIDATE: `61ed7ef2ca6e6eb04a4f72048414b775f96691ae`

LAST KNOWN GREEN PREDECESSOR BEFORE RECOVERY: `45a6d30a43968e4dd833d7738e9c94e6ab3d9c04`

Phase 18 is implemented at the final runtime SHA above and awaits fresh independent revalidation by CHAT-2, CHAT-3, CHAT-4 and CHAT-5 against exactly that SHA. Phase 19 remains blocked until 4× PASS.

## CI #397 root cause and classification

Exact failed run #397 / run ID `31725605933` failed in `PlayerDomainEngineInheritedStateTest.inheritedWriterStateMustBeRejectedBeforeResolution` before the Phase-18 registry/validator was reached. The test fixture called `android.database.sqlite.SQLiteDatabase.create(...)` from an ordinary JVM unit-test path; the Android framework stub raised `RuntimeException: Method create in android.database.sqlite.SQLiteDatabase not mocked` at test line 10.

Classification: **E — test fixture accidentally violated the JVM-test environment contract**. The production hierarchy validator was not the source of that failure.

The regression was not weakened. The fixture was replaced with an independent pure-JVM writable authority object whose `write()` operation changes externally observable authority state. The authority is retained on an inherited base component field; registration must reject the component before `resolve()` can execute. The test verifies the authority remains unchanged.

An intermediate superseded recovery run also revealed that a mutable `resolveInvoked` field on the concrete test component was correctly rejected first as mutable component state. That field was removed so the regression isolates the inherited writer path; non-execution is instead proven by the independent authority remaining at its original value.

## Final hierarchy-aware component-state boundary

The supported Phase-18 registry validates component state before resolution. It traverses the concrete component class and relevant superclasses up to the `PlayerResolutionComponent` base boundary. Declared fields are inspected by metadata, including private/protected backing fields and inherited fields. No reflective value access is required.

Non-final semantic state is rejected as `MUTABLE_RESOLUTION_COMPONENT_STATE`. Final object references not belonging to the explicitly safe immutable scalar/configuration set are rejected as `UNSAFE_RESOLUTION_COMPONENT_STATE`. Safe primitive/scalar/string/enum configuration is accepted. Static fields are not blanket-exempted: mutable static state and object authority references do not gain a supported bypass merely by being static. Synthetic/captured object references are likewise not silently trusted.

This is a supported-capability boundary, not a claim that Kotlin/JVM can prevent arbitrary malicious process-global side effects in every possible class. Phase 18 does not grant writable database/store/DAO/transaction/state-patch capabilities through its canonical context or component construction surface.

## Structural redesign invariants preserved

- `PlayerDomainEngine` remains the canonical public command-to-proposal orchestration entry.
- The typed Phase-18 resolution component is internal and does not expose a public `PlayerCommand -> PlayerChangeSet` bypass.
- Components return typed resolution draft/outcome values, not final authoritative `PlayerChangeSet` objects.
- Expected domain rejection remains typed and distinct from structural engine failure.
- `PlayerResolutionContext` remains immutable/snapshotted and does not carry writable DB/DAO/store/transaction capabilities.
- Reference/scope validation distinguishes structurally valid references from references resolved in the explicit campaign context.
- Final `PlayerChangeSet` assembly remains owned by `PlayerDomainEngine` and uses canonical Phase-17 construction/validation.
- Failure atomicity is obtained by withholding authoritative write capability from the supported Phase-18 resolution surface, not by adding rollback or stealing `TurnTransaction` responsibility.
- Hidden semantic wall-clock/randomness remains absent from the Phase-18 path; entropy/evidence is explicit input.
- Mutable retained component state is rejected, preserving deterministic same-command + same-context semantics.

## Recovery regression evidence

Focused coverage includes:

- inherited writer state: rejected before resolution; independent authority remains unchanged;
- directly declared database writer state: existing P18-HOTFIX regression rejects supported registration before component execution;
- safe inherited immutable scalar configuration: accepted;
- read-only Phase-18 context/snapshot behavior: existing P18-HOTFIX context and reference regressions pass;
- mutable semantic component state: existing mutable-counter/alias regression rejects it;
- rejection/non-execution/zero-mutation: inherited authority fixture plus existing real independent authority regression;
- routing, duplicate registration, unsupported-command fail-closed behavior and canonical command/proposal linkage remain covered;
- Phase-17 value, conflict identity, asset identity, finance/ledger, serialization, immutability and fingerprint regressions remain covered.

`P18-HOTFIX-01..30`: PASS in the final full JVM suite.

Phase-17 regression: PASS.

Phase 3–17 regression: PASS.

Full `:app:testDebugUnitTest`: PASS on exact final runtime SHA.

## Static production audit

The Phase-18 production path was checked for hidden semantic entropy and writable-authority regressions. No supported Phase-18 path was found using `UUID.randomUUID`, `Random`, `Instant.now` or `System.currentTimeMillis` as hidden semantic inputs. The explicit resolution context remains data/snapshot based and does not expose authoritative write capabilities.

The temporary staging artifact `docs/audits/.p18-hotfix-tree-note` is not present on final runtime master.

## Forward-only history

Recovery was integrated only by forward commits. Comparison from failed candidate `61ed7ef2ca6e6eb04a4f72048414b775f96691ae` to final runtime `f9781df9c3828b06562aad86a91dec9682c02530` is `ahead_by=4`, `behind_by=0`, with the failed candidate as merge base. No reset, force-push, rebase rewrite, Phase-17 rewrite, or audit deletion was used.

The recovery diff is limited to the hierarchy component-state validator and its inherited-state regression test. Existing rejected-runtime and CHAT-1/2/3/4/5 audit evidence remains in history.

## Exact final CI / release evidence

Workflow: `Build & Release RPG OS ALPHA`

Run number: `401`

Run ID: `31727239097`

Head SHA: `f9781df9c3828b06562aad86a91dec9682c02530`

Status: `completed`

Conclusion: `success`

Required steps:

- Validate project: SUCCESS
- Full JVM unit tests: SUCCESS
- Build signed ALPHA APK: SUCCESS
- Prepare release files: SUCCESS
- Upload Actions artifact: SUCCESS
- Existing release asset update: SUCCESS
- Show release information: SUCCESS
- Overall workflow: SUCCESS

Actions artifact:

- ID: `9191894439`
- name: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140`
- digest: `sha256:47f98fbe8f23a05b72b775ae32a3b99c7c168888c964fb302dc563473924b314`
- workflow head SHA: `f9781df9c3828b06562aad86a91dec9682c02530`

Release:

- status: existing release updated successfully
- tag: `v1.2.0-alpha5-hybrid140`
- release ID: `367217333`
- APK asset: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140.apk`
- APK digest: `sha256:6ffb6549c98ec9e6141328213b4a2f35eee6731ce7e61c7b7263abef9f49a3cb`

## CHAT-1 verdict

PASS.

Phase 18: **IMPLEMENTED — AWAITING FRESH 4× INDEPENDENT REVALIDATION**.

Required next: CHAT-2 Semantic Revalidation, CHAT-3 Integrity Revalidation, CHAT-4 Architecture Revalidation and CHAT-5 Complete Correctness Review must each evaluate exactly `f9781df9c3828b06562aad86a91dec9682c02530`.

Phase 19 remains BLOCKED until all four return PASS.
