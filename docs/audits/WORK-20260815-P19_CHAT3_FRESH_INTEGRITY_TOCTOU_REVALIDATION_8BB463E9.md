# WORK-20260815 — PHASE 19 CHAT-3 FRESH INTEGRITY / TOCTOU REVALIDATION

ROLE: CHAT-3 — Independent Integrity / TOCTOU Auditor

VALIDATED RUNTIME SHA: `8bb463e90142e12a499465b6554d7c8fbf58e355`

AUDIT MODE: fresh audit of the exact target runtime. Earlier PASS reports, including any earlier CHAT-3 report for this SHA, were not used as acceptance evidence. No production code or tests were modified. Phase 20 was not implemented. No release/publication action was performed.

## Final verdict

**PHASE 19 INTEGRITY REVALIDATION: PASS**

This is a CHAT-3 integrity verdict only. It does **not** globally accept Phase 19.

## 1. Exact SHA discipline

At the final pre-report gate, `8bb463e90142e12a499465b6554d7c8fbf58e355` remained the merge-base / ancestor of `master`.

The post-target range contained five commits and changed only audit/coordination documentation:

- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/audits/WORK-20260814-P19_CHAT2_AUTHORITY_REVALIDATION_8BB463E9.md`
- `docs/audits/WORK-20260814-P19_CHAT3_INTEGRITY_TOCTOU_REVALIDATION_8BB463E9.md`
- `docs/audits/WORK-20260814-P19_CHAT4_FRESH_ARCHITECTURE_REVALIDATION_8BB463E9.md`

No production source, tests, build workflow, assets, databases, or runtime-bearing files changed after the target.

**RUNTIME CHANGED AFTER TARGET: NO.**

The target commit itself changes only:

- `CampaignSelectionManager.kt`
- `PlayerDomainEngine.kt`
- `WorldRuleProvider.kt`
- adds `WorldRuleProviderPhase19AuthorityFreshnessTest.kt`

Target commit message: `fix: resolve current World Pack authority per resolution`.

## 2. Actual authority contract established from target code

The new contract is **not** “re-read canonical authority before every rule stage”. It is:

1. a fresh read-only authority lookup is made once for each `PlayerDomainEngine.resolve(...)` invocation;
2. that lookup occurs after campaign/actor/reference checks and before `COMMAND_PRECHECK` or component execution;
3. the returned authoritative binding must equal the immutable binding carried by `PlayerResolutionContext.worldRuleMode`;
4. after that equality check, the resolution is pinned to the context binding for the rest of that resolve call;
5. both `COMMAND_PRECHECK` and `DRAFT_EFFECT_CHECK` derive their binding from that same context;
6. a later resolve call performs a new authority lookup.

This contract is supported directly by:

- `WorldPackAuthorityResolver` being a read-only internal function interface with only `bindingForCampaign(campaignUid)`;
- the explicit resolver comment: implementations must not mutate canonical selection;
- `CampaignSelectionManager.activeWorldPackAuthorityResolver()`, which re-reads current campaign and World Pack for every authority lookup;
- the compatibility `activeWorldPackAuthoritySnapshot()` now returning that live resolver, so a long-lived engine does not retain a binding snapshot;
- `PlayerDomainEngine.validateWorldRuleAuthority()` calling the resolver exactly once before rule/provider execution;
- both rule stages subsequently using `context.worldRuleMode`.

The per-resolution pinned evidence is semantic rather than a separate object: after fresh equality validation, the immutable context binding is the resolution-level authority evidence.

## 3. Freshness / stale-cache analysis

### Long-lived engine A -> B

PASS.

A long-lived engine retains the live resolver, not a cached `WorldPackRuleBinding`.

Target production-path tests prove:

- stale context A after canonical A -> B fails `WORLD_RULE_BINDING_AUTHORITY_MISMATCH`;
- provider A invocation count remains zero on that stale attempt;
- current context B after the switch resolves using B;
- A -> B -> A on the same engine follows the current canonical state on each separate resolution.

### Same UID, version change

PASS.

Authority equality includes the full `WorldPackRuleBinding(worldPackUid, worldPackVersion)`. A stale version is rejected before provider execution.

### Campaign switch

PASS.

The production resolver first resolves the current campaign identity. If the requested campaign is no longer the current canonical campaign, it returns no authority for that requested campaign; a bound resolution then fails closed with `WORLD_RULE_AUTHORITY_MISSING` before provider/component execution.

### Missing authority / resolver fault

PASS.

- resolver returns `null` in bound mode -> `WORLD_RULE_AUTHORITY_MISSING`;
- unexpected resolver exception -> stable structural `WORLD_RULE_AUTHORITY_READ_FAILED`;
- provider invocation does not occur on either path.

**STALE CACHE: PASS.**

## 4. Critical TOCTOU question

### Can the schedule occur?

Yes, at the level of wall-clock current selection, this schedule is possible:

```text
T1: resolve(context bound to A)
T1: live authority lookup returns A
T1: validate A == context A
T2: canonical selection switches A -> B
T1: COMMAND_PRECHECK uses context A
T1: component resolves
T1: DRAFT_EFFECT_CHECK uses context A
T1: proposal/evidence is produced under A
```

### Is that a Phase-19 integrity blocker?

**NO — NON-BLOCKING under the actual per-resolution contract.**

The target deliberately establishes one authority read per resolution as the linearization point. Re-reading authority between `COMMAND_PRECHECK` and `DRAFT_EFFECT_CHECK` would create the more dangerous possibility of a single resolution being split across A and B. The target instead pins both stages to the same immutable context binding that was freshly matched against canonical authority before any provider/component execution.

Consistency evidence is retained:

- `PlayerResolutionContext.deterministicFingerprint()` includes world-rule mode plus World Pack UID/version;
- each `WorldRuleDecisionRecord` includes provider UID/version, World Pack UID/version, stage, request fingerprint and decision fingerprint;
- the final proposal UID includes the context fingerprint and all world-rule decision fingerprints;
- proposal provenance records the participating world-rule provider UID;
- both rule stages use the same binding.

Therefore a mid-resolution A -> B switch does not make one proposal internally A/B-inconsistent. It means the current resolution is linearized under A, while the next resolution observes B.

This is also consistent with the canonical architecture: `PlayerChangeSet` remains a proposal until a later transaction/COMMIT boundary. Phase 19 does not commit authority state.

### Near-concurrent acquisition caveat

`CampaignSelectionManager.activeWorldPackAuthorityResolver()` reads current campaign and active World Pack through separate reads; there is no explicit epoch/lock spanning both reads. In an adversarial simultaneous change of both selection dimensions, a torn-pair observation is theoretically possible.

I did not find a supported deterministic path at the target SHA that converts that theoretical pair-read race into cross-stage legality/provenance inconsistency: any accepted resolution still must match the immutable context binding and then uses one binding consistently for both stages. The repository also exposes campaign and World Pack selection as separate operations rather than one atomic paired selection transaction.

Result: **TOCTOU: NON-BLOCKING** for Phase 19 at this SHA. This caveat is worth retaining for future transaction/selection concurrency hardening, but it is not a demonstrated Phase-19 correctness blocker.

## 5. Authority resolver state / mutable aliasing

### Resolver state

PASS within the declared internal Core authority-adapter contract.

The production resolver is intentionally live: its closure reaches `CampaignSelectionManager` so that separate resolutions can observe canonical selection changes. That observable mutability across resolutions is required freshness, not a cache.

There is no generic retained-state sandbox for arbitrary same-module `WorldPackAuthorityResolver` implementations. An internal custom resolver that mutates canonical selection from inside `bindingForCampaign()` would violate the explicit resolver contract (“Implementations must not mutate canonical selection”). It is not a supported World Pack extension point and was not treated as an admissible provider-style plugin attack.

### Mutable aliases

PASS.

- `WorldPackRuleBinding` contains immutable `String` values;
- `WorldRuleMode.Bound` retains the immutable binding value;
- context known-reference/dependency inputs are defensively copied;
- `WorldPackAuthoritySnapshot` defensively copies its input map and exposes no mutable map;
- provider decision evidence lists and resolution rule-decision lists are defensively copied/unmodifiable;
- the live resolver returns binding values, not a mutable selection object.

A caller cannot mutate returned authority data to retroactively change an already validated context/evidence record.

**AUTHORITY RESOLVER STATE: PASS.**

**MUTABLE ALIASING: PASS.**

## 6. Read-only capability boundary

PASS.

`WorldPackAuthorityResolver` exposes only:

```text
bindingForCampaign(campaignUid) -> WorldPackRuleBinding?
```

It exposes no:

- SQLite writer/database handle;
- DAO writer;
- mutable repository;
- StatePatch;
- TurnTransaction;
- COMMIT callback;
- ledger/inventory/project writer;
- generic mutation callback.

`WorldRuleRequest` likewise contains only read-only semantic inputs and immutable effect snapshots. Providers do not receive the authority resolver or `CampaignSelectionManager`.

The production `CampaignSelectionManager` owns selection-write methods, but those methods are not reachable through the resolver interface or provider request. The resolver’s fixed lambda body only performs canonical reads.

**READ-ONLY CAPABILITY: PASS.**

## 7. Provider retained-state security

PASS.

Independent target inspection confirms the provider validator:

- walks concrete provider class and inherited provider subclasses;
- rejects non-final retained fields;
- permits scalar-safe values;
- does not blindly trust enum type alone;
- reads the actual retained enum value and traverses its runtime `javaClass`;
- traverses constant-specific enum subclasses;
- recursively validates nested enum state with identity-cycle protection;
- rejects arbitrary retained object/collection/writer-like state.

Exact target regressions cover:

- base enum mutable field -> rejected;
- constant-specific enum subclass mutable field -> rejected;
- constant-specific nested mutable object -> rejected;
- constant-specific writer-like object -> rejected;
- mutable collection and inherited unsafe provider state -> rejected;
- safe scalar/String/enum configuration -> accepted.

**PROVIDER STATE SECURITY: PASS.**

## 8. Failure atomicity / authoritative mutation

PASS.

Authority validation is before provider/component execution. Missing, mismatched or failed authority therefore cannot produce a proposal and does not invoke a provider.

Target `P19_AUTH_FRESH_08_resolutionDoesNotMutateCanonicalAuthority` snapshots and compares:

- selection SharedPreferences;
- World Pack manifest bytes;
- World Pack database bytes;
- active World Pack selection;
- active rule binding;

across a successful resolution and verifies they are unchanged.

Existing Phase-19 rejection/fault tests remain in the exact full JVM suite and maintain zero-authoritative-mutation checks. No writer capability is supplied through `PlayerDomainEngine`, `PlayerResolutionContext`, provider registry/request, or authority resolver interface.

**FAILURE ATOMICITY: PASS.**

**ZERO AUTHORITATIVE MUTATION: PASS.**

## 9. Determinism / immutability

PASS within a fixed per-resolution authority snapshot.

- context fingerprint uses structural canonical framing and includes world-rule mode/binding;
- reference sets are canonically sorted and dependency versions are stored in deterministic order;
- request/effect/decision/proposal identity uses `WorldRuleCanonicalWriter` framing;
- decision identity contains provider/version + World Pack/version + stage + request + explicit allowed/rejected variant + rule/reason/evidence;
- proposal identity includes the decision fingerprint list;
- same long-lived engine sees changed canonical authority only at the next resolution boundary;
- immutable context/effect/evidence collections are defensively copied.

Changing external canonical authority between resolutions is a semantic input change, not nondeterminism. A concurrent change can choose which authority snapshot a resolution linearizes against, but after the authority check the chosen binding is used consistently within that resolution.

**DETERMINISM: PASS.**

**IMMUTABILITY: PASS.**

## 10. Phase-18 regression lock

PASS.

Representative exact-target inspection confirms:

- `EquipmentChange.slotUid` remains definition/Class-B identity and is not synthesized into generic campaign `EQUIPMENT_SLOT` lookup;
- absent/other-campaign slot definitions do not become Phase-18 reference failures;
- `ownershipRecordUid` remains local/new identity;
- ownership asset, `fromOwner`, and `toOwner` retain full typed identity and campaign-scope validation;
- wrong kind / wrong campaign / ghost draft substitution remains rejected;
- command reference validation still precedes world-rule evaluation.

The full exact JVM gate includes these tests.

**PHASE-18 REGRESSION: PASS.**

## 11. Phase-17 regression lock

PASS.

Representative exact-target inspection confirms continued coverage for:

- `ExactLongDelta` positive/negative/nonzero semantics and zero rejection;
- encode/decode/fingerprint determinism;
- composite conflict identities;
- financial change / financial-ledger exact terms;
- full `OwnedAssetRef` identity;
- ownership exact scale;
- project progress zero semantics in the retained suite;
- zero authoritative mutation during ChangeSet validation/codec operations.

The target authority patch does not modify the Phase-17 production contract.

**PHASE-17 REGRESSION: PASS.**

## 12. Exact development CI

Independently verified GitHub Actions run:

- workflow: `Validate RPG OS ALPHA`
- run number: **507**
- run ID: **31826220849**
- exact head SHA: `8bb463e90142e12a499465b6554d7c8fbf58e355`
- status: `completed`
- conclusion: `success`
- checkout log resolves the exact target SHA;
- release-workflow separation validation: PASS;
- project validation: SUCCESS;
- full JVM command: `gradle --no-daemon :app:testDebugUnitTest --stacktrace`;
- full JVM task: `BUILD SUCCESSFUL` (2m 7s in the job log);
- signed validation APK assembly: SUCCESS;
- immutable validation artifact preparation/upload: SUCCESS;
- artifact ID: `9229030182`;
- artifact name: `RPG-OS-VALIDATION-1.2.0-alpha5-hybrid140-8bb463e90142e12a499465b6554d7c8fbf58e355`;
- artifact digest: `sha256:89af61fbd78ad5f14b12c23c864eceabd60d6531c80338fabac7081605f10d5f`;
- build provenance declares `publication: false`.

No independent local Gradle rerun was performed by CHAT-3. The `FULL JVM: PASS` conclusion below refers to the exact target CI full JVM task, not to a second local run.

**FULL JVM: PASS (exact CI #507).**

**EXACT CI: PASS.**

## 13. Test-disablement / scope sanity

No `@Ignore` was found under the current test source tree, and the exact workflow runs the complete `:app:testDebugUnitTest` task rather than a focused test subset. The target commit adds the authority freshness suite rather than disabling prior coverage.

## 14. New blockers

**NONE.**

The mid-resolution A -> B schedule is explicitly classified **NON-BLOCKING** because the actual target contract is one fresh canonical authority read per resolution followed by a pinned immutable binding used consistently through both rule stages and proposal evidence.

## Final CHAT-3 matrix

```text
PHASE 19 INTEGRITY REVALIDATION
ROLE: CHAT-3
VALIDATED SHA: 8bb463e90142e12a499465b6554d7c8fbf58e355
AUTHORITY RESOLVER STATE: PASS
MUTABLE ALIASING: PASS
STALE CACHE: PASS
TOCTOU: NON-BLOCKING — one fresh authority read linearizes each resolution; both rule stages and evidence remain pinned to the same immutable binding; a later canonical change applies to the next resolution
READ-ONLY CAPABILITY: PASS
FAILURE ATOMICITY: PASS
ZERO AUTHORITATIVE MUTATION: PASS
PROVIDER STATE SECURITY: PASS
DETERMINISM: PASS
IMMUTABILITY: PASS
PHASE-18 REGRESSION: PASS
PHASE-17 REGRESSION: PASS
FULL JVM: PASS (exact CI #507; no separate local rerun)
EXACT CI: PASS
NEW BLOCKERS: NONE
FINAL CHAT-3 VERDICT: PASS
```

This report does **not** globally accept Phase 19.