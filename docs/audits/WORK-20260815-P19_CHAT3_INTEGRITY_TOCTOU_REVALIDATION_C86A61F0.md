# PHASE 19 — CHAT-3 Integrity / TOCTOU Revalidation

Role: **CHAT-3**  
Mode: **READ-ONLY production/runtime audit; report-only write**  
Validated SHA: `c86a61f019d8579b970b0c07c8a9df41b922ff83`

## Verdict

**FAIL — new blocker found.**

The previous prefs-key torn-read defect was partially fixed, but the declared authority snapshot is still not atomic across all three required values: `campaignUid + worldPackUid + worldPackVersion`.

This report does not modify production/runtime/schema, does not implement Phase 20, does not publish a release, and does not mark Phase 19 globally accepted.

## Bootstrap / freshness

Mandatory role bootstrap was performed from current `master` and canonical documents:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/PARALLEL_WORK_COORDINATION.md` including ACTIVE WORK REGISTER
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- recent commits
- exact target implementation/tests
- exact CI run

Immediately before report creation, `master` was `90e5a5e3af7e8800e97924e691d6dc1b36e71e79`, one documentation-only commit ahead of the validated runtime SHA. No runtime source changed after the target.

## What the hotfix fixes

`CanonicalSelectionWorldPackAuthoritySource.currentAuthority()` starts with:

```kotlin
val selectionSnapshot = LinkedHashMap(prefs.all)
```

and extracts both selected directory names from that one map. Therefore the old race:

`read active_campaign -> switch -> read active_worldpack`

is closed for the two preference keys themselves.

`P19_AUTH_ATOMIC_02_controlledInterleavingCannotProduceC1PlusBHybrid` correctly verifies this narrower property by returning a captured C1/A preference map and switching the live preferences to C2/B before the source continues. The source still returns C1/A for that fixture.

## Primary blocker — the snapshot does not contain campaignUid or World Pack uid/version

After `prefs.all` is captured, production code still performs later filesystem reads:

```kotlin
val campaignUid = ActiveCampaignRef.resolve(saves, campaignDirName).campaignId
...
val validation = PackageValidator().validateWorldPack(worldPackDir)
val uid = validation.packageId ...
val version = validation.version ...
```

`ActiveCampaignRef.resolve()` reads `campaign.json` after the preferences snapshot.

`PackageValidator.validateWorldPack()` reads `worldpack.json` and opens `world.db` after the preferences snapshot.

Both backing package directories are mutable through supported production paths.

Therefore `CurrentWorldPackAuthority(campaignUid, binding)` is not one immutable canonical observation. It is assembled from:

1. selected directory names at time T1;
2. campaign package contents at a later time T2;
3. World Pack package contents at a later time T3.

## Supported controlled interleaving — version/cross-campaign hybrid

Initial canonical state:

`C1 / WORLD-A v1`

with preferences selecting `C1.campaign` and `WORLD-A.worldpack`.

Interleaving:

1. T1 enters `currentAuthority()` and captures `prefs.all` containing C1 + WORLD-A directory names.
2. T1 pauses before resolving the captured directories.
3. T2 performs supported `setActiveCampaign(C2.campaign)`. Canonical state is now `C2 / WORLD-A v1`.
4. T3 performs a supported content update through `ContentUpdateManager.install()` for the same World Pack id `WORLD-A`, replacing `worldpacks/WORLD-A.worldpack` from v1 to v2. Canonical state is now `C2 / WORLD-A v2`.
5. T1 resumes.
6. It resolves campaign UID from the stale captured `C1.campaign` name -> `C1`.
7. It validates the stale captured `WORLD-A.worldpack` path after the update -> `WORLD-A v2`.
8. It returns `CurrentWorldPackAuthority(C1, WORLD-A v2)`.

Observed canonical sequence was:

`C1/A1 -> C2/A1 -> C2/A2`

`C1/A2` never existed as one canonical state.

This violates atomic authority snapshot, cross-campaign isolation and version atomicity.

## Exact C1+B reproducer remains possible

A stronger supported production API exists in `RpgPackageManager`:

```kotlin
fun validatedImportWorldPack(zipFile: File, targetDirName: String): ValidationResult
```

`importWorldPack()` deletes and replaces the caller-selected target directory. `PackageValidator` validates the manifest/database but does not require the manifest `id` to match `targetDirName`.

Controlled schedule:

1. canonical state C1/A; T1 captures preference names `C1.campaign` + `A.worldpack`;
2. T1 pauses;
3. supported canonical selection switches to C2/B (`C2.campaign`, `B.worldpack`);
4. after that switch, caller imports a valid B package into the now-inactive stale path `A.worldpack` using `validatedImportWorldPack(..., "A.worldpack")`;
5. T1 resumes and resolves old captured `C1.campaign` -> C1;
6. T1 validates old captured path `A.worldpack`, whose manifest now says B -> binding B;
7. source returns C1+B.

C1+B was not a canonical selected state in this schedule. This is a direct continuation of the original blocker class, now through mutable package contents rather than split preference-key reads.

No production test was added because CHAT-3 is read-only.

## Capability boundary

The hotfix materially improves the PlayerDomainEngine boundary:

- `PlayerDomainEngine` receives only `WorldPackAuthorityResolver`.
- `CurrentSelectionWorldPackAuthorityResolver` retains `WorldPackAuthoritySource`, not `CampaignSelectionManager`.
- `WorldPackAuthoritySource` exposes only `currentAuthority()`.
- resolver/source API does not expose `setActiveCampaign`, `setActiveWorldPack`, DB/DAO writer, `StatePatch`, `TurnTransaction`, or COMMIT callback.

Therefore the engine-facing read-only capability check passes.

The source necessarily observes mutable backing storage (`SharedPreferences` and package filesystem), but that mutable backing is not exposed to PlayerDomainEngine as a mutation API. The blocker is snapshot atomicity, not a direct engine writer capability.

## Freshness / single-resolution consistency

`PlayerDomainEngine.validateWorldRuleAuthority()` invokes `bindingForCampaign(context.campaignUid)` once per resolve.

After authority validation, both `COMMAND_PRECHECK` and `DRAFT_EFFECT_CHECK` use the same frozen `context.worldRuleMode.binding`.

Target test `P19_AUTH_ATOMIC_09` confirms one resolver read and identical World Pack uid/version in both decision records.

Sequential long-lived-engine behavior remains correct:

- C1/A -> C2/B on the same engine sees B on the next resolve;
- A -> B -> A remains fresh;
- no binding cache was introduced.

The FAIL is specifically for an authority read overlapping mutable package replacement.

## Failure behavior / provider invocation

Existing target tests still verify:

- missing authority -> `WORLD_RULE_AUTHORITY_MISSING` before provider execution;
- stale authority/version -> mismatch before provider execution;
- resolver failure -> `WORLD_RULE_AUTHORITY_READ_FAILED` before provider execution;
- invalid cross-campaign authority -> provider invocation counters remain zero.

`PlayerDomainEngine.resolve()` itself has no canonical DB/Save/preferences writer path. The successful-resolution freshness test also checks unchanged selection prefs, manifest bytes and World Pack DB bytes.

Results:

- failure atomicity: PASS
- zero authoritative mutation: PASS

## Provider state security

Target provider-state validation remains unchanged and rejects unsafe retained mutable state.

Representative adversarial coverage/source rules include:

- ordinary mutable field -> rejected;
- inherited unsafe field -> rejected;
- mutable collection -> rejected;
- base enum mutable field -> rejected;
- constant-specific / nested unsafe enum state -> rejected through recursive enum field inspection;
- writer-like / non-scalar retained object -> rejected;
- safe stateless enum -> accepted;
- safe String/scalar configuration -> accepted.

Result: PASS.

## Determinism / immutability

For equivalent complete authority evidence, request/decision/proposal identity remains canonical and deterministic. No clock/random/UUID/object identity is used by the audited identity path. Collections used in resolution evidence are copied/frozen and ordered as required.

The discovered defect is TOCTOU across mutable canonical package backing, not algorithmic fingerprint nondeterminism.

Results:

- determinism: PASS
- immutability of resolution evidence: PASS

## Phase 17 / 18 representative regression

The exact target full JVM suite includes the existing Phase-17/18 regression locks, including zero-delta semantics, project progress, ownership/assets, finance/ledger, equipment/reference ordering and serialization/fingerprint invariants. No production code outside the authority hotfix/testability/test/workflow path changed in the audited delta.

Results:

- Phase-18 regression: PASS
- Phase-17 regression: PASS

## Exact CI

Verified GitHub Actions:

- run number: `518`
- run ID: `31868961756`
- head SHA: `c86a61f019d8579b970b0c07c8a9df41b922ff83`
- status/conclusion: `completed / success`
- job ID: `94974449319`

The job reports success for `Run JVM unit tests`, signed validation APK build and artifact preparation/upload.

Therefore:

- FULL JVM: PASS (exact target CI; no independent local checkout run in this connector environment)
- EXACT CI: PASS

## Final matrix

| Check | Result |
|---|---|
| Atomic authority snapshot | FAIL |
| Torn read C1+B | POSSIBLE |
| Controlled interleaving | FAIL |
| Mutable aliasing | PASS at engine capability boundary; mutable canonical backing remains intentionally live |
| Read-only capability | PASS |
| Stale cache | PASS |
| Single-resolution consistency | PASS |
| Long-lived engine | PASS |
| Cross-campaign | FAIL |
| Version atomicity | FAIL |
| Failure atomicity | PASS |
| Zero authoritative mutation | PASS |
| Provider state security | PASS |
| Determinism | PASS |
| Immutability | PASS |
| Phase-18 regression | PASS |
| Phase-17 regression | PASS |
| Full JVM | PASS |
| Exact CI #518 / 31868961756 | PASS |

## New blocker

`P19-C3-ATOMIC-AUTHORITY-PACKAGE-CONTENT-TOCTOU-02`

`CanonicalSelectionWorldPackAuthoritySource` atomically captures only the selected directory-name keys. It subsequently derives `campaignUid`, `worldPackUid` and `worldPackVersion` from mutable filesystem packages. Supported campaign/package update or package import interleavings can therefore return a campaign/binding combination that never existed as one canonical state, including a controlled C1+B construction.

**FINAL CHAT-3 VERDICT: FAIL.**