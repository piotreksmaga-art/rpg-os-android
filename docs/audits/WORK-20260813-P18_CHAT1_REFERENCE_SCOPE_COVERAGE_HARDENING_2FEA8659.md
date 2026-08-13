# WORK-20260813-P18 — CHAT-1 REFERENCE/SCOPE COVERAGE HARDENING

**Role:** CHAT-1 Implementer / Recovery Owner  
**Previous Phase-18 runtime:** `f9781df9c3828b06562aad86a91dec9682c02530`  
**Final Phase-18 runtime:** `2fea8659685232ef56947cfbbe87c55df1e44c0f`  
**Fresh master before report write:** `2fea8659685232ef56947cfbbe87c55df1e44c0f`  
**Verdict:** **PASS — implementation complete, awaiting fresh CHAT-2/3/4/5 revalidation**

## 1. Forward-only recovery

Recovery continued from published master without reset, rebase, force push or history rewrite. The desired production/reference commits and all published history were retained. Three temporary implementation artifacts were removed by three new ordinary forward-only commits:

- `.github/workflows/p18-fixture-patch.yml` — removed by `e3bc396960f18e695c427c9f2bd80d8ef6142b7d`
- `docs/audits/.p18-ref-coverage-note` — removed by `6cd89b5e406125f5aa741339c62da22569fc851a`
- `docs/audits/P18_REF_TEMP_CLEANUP_MARKER.md` — removed by final runtime commit `2fea8659685232ef56947cfbbe87c55df1e44c0f`

The final runtime tree contains no Phase-18 temporary workflow, staging note, cleanup marker, patch script or debug bypass. Legitimate historical audit reports remain intact.

## 2. Production reference/scope closure

`PlayerDomainEngine` retains the established resolver-boundary redesign. `PlayerResolutionComponent` remains trusted internal Core code and receives only canonical command plus deterministic read-only `PlayerResolutionContext`. Phase 18 constrains supported injected/retained capability/state; it does not attempt JVM bytecode sandboxing.

The typed reference kinds include `FINANCIAL_ACCOUNT`, `CURRENCY`, `OBLIGATION`, `PROJECT`, `PROJECT_REQUIREMENT`, `PROJECT_MILESTONE`, `SKILL`, and `TECHNIQUE`.

`TRANSFER_FUNDS` command extraction now checks, independently and with typed identity:

- `fromAccountUid` as `FINANCIAL_ACCOUNT`
- `toAccountUid` as `FINANCIAL_ACCOUNT`
- `currencyUid` as `CURRENCY`

Draft extraction checks the same three authoritative identities from `FinancialChange`, and financial ledger intent extraction checks the same account/currency identities. Exact five-field FinancialChange ↔ ledger validation remains Phase-17 structural validation and was not weakened.

Additional confirmed A-class corrections retained in production include existing skill, technique, obligation, project, project requirement, project milestone and successor-project identities, plus the existing typed DomainRef families already covered before this hotfix.

`referenceStatus()` distinguishes exact typed current-campaign references from exact typed references known only in another campaign. Therefore UNKNOWN and WRONG_CAMPAIGN remain distinct. Same textual UID under another reference kind cannot satisfy lookup.

Command references are validated before component execution. Component-produced draft references are independently validated after typed resolution and before engine-owned `PlayerChangeSet` assembly. A successful command-side lookup therefore cannot authorize a substituted or newly introduced unresolved draft reference.

## 3. Final reference coverage matrix

The final static audit enumerated reference/identity-bearing fields across command envelope/preconditions/payloads, resolution drafts, PlayerDomainChange payloads, event and ledger intents, proposal metadata and nested ownership/asset identity structures. Fields were classified semantically, never by a generic `*Uid` naming heuristic.

| Class | Meaning | Count |
|---|---|---:|
| A | PHASE18_EXISTENCE_SCOPE_REFERENCE | 70 |
| B | STRUCTURAL_TYPED_UID_ONLY | 38 |
| C | PHASE19_RULE_REFERENCE | 2 |
| D | LOCAL_IDENTITY | 15 |
| E | OTHER / explicitly owned by another contract | 17 |
| **Total** | **Reference-bearing fields reviewed** | **142** |

**PHASE18_EXISTENCE_SCOPE:** 70  
**COVERED:** 70  
**INTENTIONALLY NOT PHASE18:** 72  
**UNCLASSIFIED:** 0

Representative B-class fields include definition/kind/status/method/type UIDs, equipment slot definition identities and financial transaction type. C-class fields are future rule-provider identities rather than Phase-18 campaign existence legality. D-class fields include proposal-local change/event/ledger/change-set and causal identities validated structurally. E-class fields include campaign/actor identities checked directly by context equality and structured ownership/asset identities governed by existing typed Phase-17 identity contracts rather than indiscriminate campaign lookup.

## 4. Finance and adversarial test matrix

`PlayerDomainEngineReferenceFinanceTest.kt` verifies the intended production path for:

- known source + known destination + known currency → Resolved;
- unknown source/destination/currency → `UNKNOWN_REFERENCE`;
- wrong-campaign source/destination/currency → `WRONG_CAMPAIGN_REFERENCE`;
- command-side refs rejected before component execution;
- component-introduced unknown draft financial identity rejected;
- command-known but draft-substituted unknown account rejected;
- command-known but wrong-campaign draft currency rejected;
- same textual UID under wrong reference kind rejected;
- duplicate reference evaluation deterministic;
- reference failure returns typed rejection rather than structural exception;
- exact finance/ledger terms and financial causal uniqueness remain intact.

`PlayerDomainEngineReferenceRegressionTest.kt`, `PlayerDomainEngineExistingScalarRefTest.kt`, `PlayerDomainEngineProjectWorkRefTest.kt`, and `PlayerDomainEngineReferenceProjectMatrixTest.kt` lock the additional A-class fields and Phase-17 regressions.

The older finance success fixture now explicitly contains `ACCOUNT:A`, `ACCOUNT:B`, and `CUR:PLN` in its valid reference context. Production validation was not weakened to retain obsolete fixture behavior.

The four helper constructors repaired by `a130e788a0b0a22205eb84f1bf4e5e5a6fe75fb6` use named `PlayerCommand` arguments. The old structural tests remain enabled; the large textual diff in `PlayerDomainEngineTest.kt` is compact formatting plus fixture repair, not test removal.

## 5. Architecture/regression preservation

Verified preserved:

- canonical public `PlayerDomainEngine.resolve()` entry;
- internal typed `PlayerResolutionComponent` and typed draft/outcome boundary;
- no public command→PlayerChangeSet resolver bypass;
- read-only immutable `PlayerResolutionContext` capability model;
- typed domain rejection distinct from structural faults;
- hierarchy-aware retained-state validator;
- direct writer, inherited writer and mutable retained-state rejection;
- safe immutable inherited component state;
- explicit deterministic entropy/evidence;
- engine-owned canonical PlayerChangeSet construction;
- proposal/commit separation and zero authoritative mutation on resolution failure.

Phase-17 locks remain green for ProjectProgressDelta zero/negative semantics, ExactLongDelta non-zero invariant including copy, OwnershipShare, composite target identity, legacy/CK1 separation, OwnedAssetRef full identity, financial exact terms and causal uniqueness, standalone ledger semantics, serialization closure, fingerprint determinism and immutability.

## 6. Full JVM

Exact workflow checkout confirmed runtime SHA `2fea8659685232ef56947cfbbe87c55df1e44c0f` and executed:

`gradle --no-daemon :app:testDebugUnitTest --stacktrace`

Result: **BUILD SUCCESSFUL**.

Static test accounting from the known 524-test CI #408 baseline at `cc0b43a...`, plus the subsequently added focused suites, yields:

- total: **553**
- passed: **553**
- failed: **0**
- skipped/ignored: **0** (`@Ignore` absent from the test tree)

## 7. Exact distribution CI

**Workflow:** Build & Release RPG OS ALPHA  
**Run number:** 421  
**Run ID:** `31739185657`  
**Head SHA:** `2fea8659685232ef56947cfbbe87c55df1e44c0f`  
**Status:** completed  
**Conclusion:** success

Required steps:

- Validate project — SUCCESS
- Run JVM unit tests — SUCCESS
- Build signed ALPHA APK — SUCCESS
- Prepare release files — SUCCESS
- Upload Actions artifact — SUCCESS
- Check release — SUCCESS
- Update existing GitHub Release assets — SUCCESS
- Show release information — SUCCESS
- overall build job — SUCCESS

The release-create step was correctly skipped because the release already existed; the existing release assets were replaced with `--clobber` and the update step succeeded.

## 8. Artifact and release evidence

Actions artifact:

- ID: `9196497315`
- name: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140`
- artifact ZIP digest: `sha256:53aa0e3ca4f71e3589e5f6462e2379c748c12e081484f187136e0325ddf68638`
- workflow head SHA: `2fea8659685232ef56947cfbbe87c55df1e44c0f`

Release:

- tag: `v1.2.0-alpha5-hybrid140`
- APK asset: `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140.apk`
- APK asset ID: `513460608`
- APK digest: `sha256:e78ba0eb7c4bfe5aaabc0733743fd467f319cf35072be8d04c96132af21fe8e1`
- release asset update: SUCCESS

## 9. Final verdict

**P18-SEM-REFERENCE-COVERAGE-01: FIXED**  
**P18-REF-01: FIXED**  
**REFERENCE COVERAGE MATRIX: COMPLETE**  
**UNCLASSIFIED: 0**  
**FULL JVM: PASS**  
**EXACT CI: PASS**  
**FINAL CHAT-1 VERDICT: PASS**

Phase 18 is **IMPLEMENTED — AWAITING FRESH 4× INDEPENDENT REVALIDATION**. It is not globally accepted by this report. Phase 19 remains blocked until CHAT-2 Semantic Revalidation, CHAT-3 Integrity Revalidation, CHAT-4 Architecture Revalidation, and CHAT-5 Complete Correctness Review all independently PASS exactly runtime `2fea8659685232ef56947cfbbe87c55df1e44c0f`.
