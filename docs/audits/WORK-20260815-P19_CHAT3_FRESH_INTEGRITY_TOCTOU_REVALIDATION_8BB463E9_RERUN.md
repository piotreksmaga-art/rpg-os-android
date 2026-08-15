# PHASE 19 — CHAT-3 Fresh Integrity / TOCTOU / Capability Revalidation (Rerun)

Role: **CHAT-3**  
Mode: **READ-ONLY production/runtime audit; report-only write**  
Validated SHA: `8bb463e90142e12a499465b6554d7c8fbf58e355`

## Verdict

**FAIL — new blocker found.**

This report does **not** mark Phase 19 globally accepted, does not modify production/runtime/schema, does not implement Phase 20, and does not publish a release.

## Bootstrap and freshness

Mandatory bootstrap was performed from current `master`, including:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/PARALLEL_WORK_COORDINATION.md` including ACTIVE WORK REGISTER
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- recent commits
- CI
- CHAT-3 allowed/forbidden scope

Immediately before this report write, `master` was `390e581bdebef020b929f19ea064193e2880b974`.

Comparison from target `8bb463e90142e12a499465b6554d7c8fbf58e355` to that master showed eight commits ahead and changes confined to documentation / coordination / audit reports. No production runtime source changed after the target.

`RUNTIME CHANGED AFTER TARGET = NO`.

## Primary finding — torn campaign / World Pack authority read

Target source: `app/src/main/java/com/rpgos/app/CampaignSelectionManager.kt`.

The production live resolver is:

```kotlin
internal fun activeWorldPackAuthorityResolver(): WorldPackAuthorityResolver =
    WorldPackAuthorityResolver { campaignUid ->
        val currentCampaignUid = activeCampaignId()
        if (campaignUid != currentCampaignUid) null else activeWorldRuleMode().binding
    }
```

This is fresh per lookup, but the canonical authority evidence is assembled from **two separate reads**:

1. `activeCampaignId()`
2. `activeWorldRuleMode().binding`

There is no lock, generation token, immutable `(campaignUid, worldPackBinding)` selection snapshot, or post-read campaign revalidation around those reads.

### Supported-path TOCTOU reproducer schedule

The inconsistency is distinct from the explicitly acceptable contract “read A at resolution start -> freeze A for that one resolution -> next resolution sees B”.

A valid interleaving is:

1. Canonical selection is campaign `C1`, World Pack `A`.
2. T1 begins `bindingForCampaign("C1")`.
3. T1 executes `currentCampaignUid = activeCampaignId()` and obtains `C1`.
4. T2 performs the supported canonical selection change to campaign `C2` and World Pack `B`.
5. T1 evaluates `campaignUid == currentCampaignUid` using its retained local `C1`; it is true.
6. T1 then executes `activeWorldRuleMode().binding` and obtains the newly selected `B`.
7. Resolver returns `B` **as authority for requested campaign `C1`**, despite the canonical selection now being `C2/B` and despite `C1/B` never being the single canonical pair read by the resolver.
8. If the supplied `PlayerResolutionContext` is `campaignUid=C1` with `WorldRuleMode.Bound(B)`, `validateWorldRuleAuthority()` can accept that hybrid value. The rest of this resolution then freezes `context.worldRuleMode.binding == B` and uses it for both world-rule stages.

This is a cross-campaign torn-read window inside the authority lookup itself. It is therefore **not** the benign case where authority A was already fully validated before canonical selection changed.

No production modification or test fixture was added because CHAT-3 is read-only. The schedule above is directly supported by the exact target source and canonical `setActiveCampaign` / `setActiveWorldPack` mutation path.

## Single-resolution consistency after authority validation

Target source: `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`.

`resolve()` calls `validateWorldRuleAuthority(context)` once before `COMMAND_PRECHECK`. `evaluateWorldRules(...)` does not re-read canonical authority; it uses `context.worldRuleMode.binding` for both `COMMAND_PRECHECK` and `DRAFT_EFFECT_CHECK`.

Therefore, once one authority binding has been fully accepted, a later canonical A -> B change cannot produce:

`COMMAND_PRECHECK under A -> DRAFT_EFFECT_CHECK under B`.

Both stages use the same frozen binding from the context. That part of TOCTOU handling is sound.

The blocker is earlier: the live resolver can construct a hybrid campaign/binding result during the authority read.

## Resolver state / mutable alias / capability boundary

`WorldPackAuthorityResolver` exposes only `bindingForCampaign(...)`, but the production lambda is created as an instance method on `CampaignSelectionManager` and necessarily closes over the manager instance in order to call `activeCampaignId()` and `activeWorldRuleMode()`.

That retained object is mutable and also exposes canonical selection mutators including `setActiveCampaign(...)` and `setActiveWorldPack(...)`. Thus the resolver implementation retains an alias to an object that owns campaign/World Pack mutation capability rather than a structurally read-only authority-reader capability.

No current lambda statement calls those mutators, and the engine itself does not invoke them. Therefore this finding does not imply observed mutation during ordinary resolution. It does mean the requested strict capability boundary (“resolver path must not get campaign mutation API”) is not structurally enforced on the production resolver path.

Results:

- `AUTHORITY RESOLVER STATE = FAIL`
- `MUTABLE ALIASING = FAIL`
- `READ-ONLY CAPABILITY = FAIL`

The immutable `WorldPackAuthoritySnapshot` fixture itself copies its input map and is not the problem.

## Freshness / stale cache / long-lived engine

The target change replaced the old retained snapshot in production composition with a live resolver. `PlayerDomainEngine` stores the resolver reference, not a cached binding. `validateWorldRuleAuthority()` calls `bindingForCampaign(context.campaignUid)` on each resolution.

Exact target tests `WorldRuleProviderPhase19AuthorityFreshnessTest` cover:

- stale A rejected after A -> B,
- current B accepted after A -> B,
- stale version rejected,
- missing authority fail-closed,
- A -> B -> A on the same engine,
- authority read failure before provider execution,
- canonical prefs / manifest / DB unchanged by a successful resolution.

Sequential long-lived-engine freshness is therefore preserved.

Results:

- `STALE CACHE = PASS`
- `LONG-LIVED ENGINE = PASS`
- `CROSS-CAMPAIGN = FAIL` specifically for the torn-read interleaving above, not for ordinary sequential reuse.

## Failure atomicity / zero authoritative mutation

`PlayerDomainEngine.resolve()` constructs/validates domain objects and has no DB/Save/preferences writer in its own resolution path. Authority mismatch/missing/read failure occurs before provider execution. The exact freshness test verifies canonical SharedPreferences plus World Pack manifest/database bytes remain unchanged after resolution.

Provider requests/decisions/proposals are in-memory resolution evidence; no proposal persistence is performed by this engine path.

Results:

- `FAILURE ATOMICITY = PASS`
- `ZERO AUTHORITATIVE MUTATION = PASS`

The capability-boundary failure above is structural; it is not an observed mutation by the current resolver body.

## Provider state security

Exact target provider registry validation walks provider class hierarchies, requires retained instance fields to be final, permits only approved immutable scalar/configuration state, recursively validates enums, and rejects unsafe mutable holders.

Exact target adversarial coverage includes:

- ordinary mutable provider field,
- inherited unsafe/mutable field,
- mutable collection,
- mutable base-enum state,
- constant-specific enum subclass mutable state,
- constant-specific nested `StringBuilder`,
- writer-like / unsafe retained objects,
- safe stateless enum,
- safe String/scalar configuration.

`PROVIDER STATE SECURITY = PASS`.

## Determinism / immutability

For equivalent request plus equivalent fully resolved current authority, target construction derives request/decision/proposal identities from canonical values/fingerprints. No clock, random, UUID, object identity or ordinary object `hashCode()` is used as an identity input in the audited path.

Collections/evidence used for fingerprints are copied/frozen and canonically ordered where required; decision evidence is canonicalized before identity derivation.

The newly found race is a freshness/atomic-selection problem, not a clock/random nondeterminism defect.

Results:

- `DETERMINISM = PASS`
- `IMMUTABILITY = PASS`

## Phase 17 / 18 representative regression

Exact target test suite includes representative coverage for:

- `ExactLongDelta(0)` rejection,
- `ProjectProgressDelta(0)` acceptance,
- `OwnershipShare`,
- `OwnedAssetRef`,
- composite identity,
- finance/ledger exact semantics,
- equipment slot identity B,
- ownership D/A/A/A classification,
- reference ordering,
- serialization/fingerprint round-trip.

The exact full JVM suite passed at the target SHA.

Results:

- `PHASE-18 REGRESSION = PASS`
- `PHASE-17 REGRESSION = PASS`

## Exact CI verification

Independently inspected GitHub Actions run:

- run number: `507`
- run ID: `31826220849`
- head SHA: `8bb463e90142e12a499465b6554d7c8fbf58e355`
- status / conclusion: `completed / success`
- build job ID: `94850887968`

Job logs confirm exact checkout of `8bb463e90142e12a499465b6554d7c8fbf58e355` and execution of:

`gradle --no-daemon :app:testDebugUnitTest --stacktrace`

with `BUILD SUCCESSFUL` and `:app:testDebugUnitTest` completed successfully.

Therefore:

- `FULL JVM = PASS` (verified through the exact target CI run; no second local rerun was possible in the current connector environment)
- `EXACT CI = PASS`

## Final matrix

| Check | Result |
|---|---|
| Runtime changed after target | NO |
| Authority resolver state | FAIL |
| Mutable aliasing | FAIL |
| Stale cache | PASS |
| TOCTOU | FAIL |
| Single-resolution command/draft binding consistency after validation | PASS |
| Long-lived engine sequential A -> B -> A | PASS |
| Cross-campaign | FAIL |
| Read-only capability | FAIL |
| Failure atomicity | PASS |
| Zero authoritative mutation | PASS |
| Provider state security | PASS |
| Determinism | PASS |
| Immutability | PASS |
| Phase-18 regression | PASS |
| Phase-17 regression | PASS |
| Full JVM | PASS |
| Exact CI run #507 / 31826220849 | PASS |

## New blocker

`P19-C3-TOCTOU-CROSS-CAMPAIGN-TORN-AUTHORITY-01`

A production `WorldPackAuthorityResolver` lookup is not an atomic read of `(activeCampaign, activeWorldPackBinding)`. A campaign/worldpack switch between its two reads can return a new World Pack binding for the old requested campaign and allow hybrid authority validation. The same implementation also retains the mutable `CampaignSelectionManager` object rather than a structurally read-only authority-reader capability.

**FINAL CHAT-3 VERDICT: FAIL.**
