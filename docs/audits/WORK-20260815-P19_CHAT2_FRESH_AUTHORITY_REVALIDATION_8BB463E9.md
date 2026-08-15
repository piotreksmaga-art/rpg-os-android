# PHASE 19 — CHAT-2 Fresh Authority / Lifecycle Revalidation

Role: **CHAT-2**  
Audit mode: **READ-ONLY production audit**  
Validated runtime SHA: `8bb463e90142e12a499465b6554d7c8fbf58e355`  
Rejected predecessor considered only as historical context: `6287fb2612afc9b60c7a9d47508cb0fcb79dbb67` / `P19-C2-AUTH-STALE-01` / `STALE_WORLD_PACK_AUTHORITY_SNAPSHOT`.

## Verdict

**PASS.** No new Phase-19 blocker found on the exact target SHA. This report does **not** mark Phase 19 globally accepted and does not authorize Phase 20 or release publication.

## Role bootstrap / scope

Bootstrap was performed from the then-current `master` before auditing the target runtime:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/PARALLEL_WORK_COORDINATION.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- ACTIVE WORK REGISTER
- current `master`, recent commits and CI

Allowed scope for this task: repository-wide reads, exact-SHA source/test/CI inspection, and this audit report under `docs/audits/`.

Forbidden scope: production changes, fixes, Phase-20 implementation, release publication, and global phase acceptance/status mutation.

At bootstrap, `master` was `a55f80076d76aeb57c147e91b14752388e4da03d`. Comparing target `8bb463e...` to that head showed post-target changes confined to documentation/audit coordination. No production/runtime source changed after the target. Therefore `RUNTIME CHANGED AFTER TARGET = NO`.

## Independent authority attack results

### Long-lived engine: A -> B, stale Bound(A)

The exact target contains `WorldRuleProviderPhase19AuthorityFreshnessTest` attacks that construct one long-lived `PlayerDomainEngine` backed by the `CampaignSelectionManager` compatibility entry point. On `8bb463e...`, that compatibility method returns the live read-only resolver, not a frozen map.

Attack sequence:

1. C1 selects World Pack A.
2. One `PlayerDomainEngine` is constructed.
3. Canonical selection changes A -> B through `CampaignSelectionManager.setActiveWorldPack(...)`.
4. The same engine receives `Bound(A)`.
5. Resolution fails with `WORLD_RULE_BINDING_AUTHORITY_MISMATCH` before provider evaluation.

Measured old provider A invocation count: **0**.

The same long-lived engine then receives `Bound(B)` and resolves successfully; provider B executes while provider A remains at zero for the stale-A attack.

Result: **PASS**.

### Version freshness: A v1 -> A v2

With canonical selection updated to A v2, stale `Bound(A v1)` is rejected by authority mismatch before provider A v1 execution. Invocation count remains zero for the stale binding.

Result: **PASS**.

### A -> B -> A on the same engine

The live resolver re-reads canonical campaign and World Pack selection for every authority lookup. The target freshness tests exercise A -> B -> A using the same engine and accept only the authority current at each resolution.

Result: **PASS**.

### Missing current authority

A resolver returning no current binding causes `WORLD_RULE_AUTHORITY_MISSING` before provider execution.

Result: **PASS**.

### Authority read failure

Non-structural resolver failure is converted to `WORLD_RULE_AUTHORITY_READ_FAILED`; provider counters remain zero.

Result: **PASS**.

### Canonical A + valid registered provider B + Bound(B)

The exact target hotfix tests register both providers, retain canonical authority A, then supply `Bound(B)`. `PlayerDomainEngine` rejects with `WORLD_RULE_BINDING_AUTHORITY_MISMATCH` before registry/provider execution. Provider B invocation count is **0**.

Result: **PASS**.

### Campaign C1 -> C2 with the same engine

Production resolver semantics are campaign-current on every lookup:

- `bindingForCampaign(requestedCampaign)` re-reads `activeCampaignId()`.
- if requested campaign differs from the newly current campaign, it returns no authority;
- otherwise it re-reads `activeWorldRuleMode().binding`.

Therefore an engine retaining the resolver object across C1 -> C2 does not retain C1 authority. The target also contains cross-campaign fail-closed coverage (`WORLD_RULE_AUTHORITY_MISSING` when C1 authority is presented as the only authority for C2), and the exact full JVM suite passed.

Result: **PASS**. No cross-campaign authority leakage found.

## Repository-wide authority search

Searches covered all required symbols and construction patterns:

- `WorldPackAuthoritySnapshot`
- `WorldPackAuthorityResolver`
- `activeWorldPackAuthority*`
- `activeWorldRuleMode`
- `setActiveWorldPack`
- `PlayerDomainEngine(` / `PlayerDomainEngine`
- `worldPackAuthority =`

Production findings:

1. `CampaignSelectionManager.activeWorldRuleMode()` is the canonical app-level authority and resolves the selected pack from current persisted app selection.
2. `CampaignSelectionManager.activeWorldPackAuthorityResolver()` is read-only and re-reads both current campaign and current World Pack on every lookup.
3. `activeWorldPackAuthoritySnapshot()` remains only as a compatibility-named entry point and delegates to the live resolver.
4. `WorldPackAuthoritySnapshot` remains as an immutable `WorldPackAuthorityResolver` implementation / fixture; its source explicitly states it is not a persisted source of truth. It is not a second production canonical authority.
5. `PlayerDomainEngine` stores a resolver reference, not a resolved authority binding. `validateWorldRuleAuthority()` invokes `bindingForCampaign(context.campaignUid)` for each `resolve()` call.
6. No hidden persisted authority, second canonical selector, stale cached binding, or resolver permanently bound to an old campaign was found.

`SECOND AUTHORITY FOUND = NO`.

## Production ordering

Exact `PlayerDomainEngine.resolve()` order on `8bb463e...`:

1. canonical command registry validation and canonical command round-trip;
2. Phase-18 command reference validation;
3. **current World Pack authority validation**;
4. Phase-19 `COMMAND_PRECHECK`;
5. domain resolution;
6. Phase-18 draft reference validation;
7. Phase-19 `DRAFT_EFFECT_CHECK`;
8. proposal assembly / `PlayerChangeSet` validation and resolved outcome.

Authority mismatch/read/missing-authority failures therefore occur before provider evaluation. Phase-18 command-reference rejection also remains before provider evaluation.

Result: **ORDERING PASS**.

## Regression recheck

- Null/unbound bypass: **PASS**. Public context creation requires explicit `WorldRuleMode`; internal unbound mode is accepted only when canonical authority is absent, otherwise `WORLD_RULE_GENERIC_MODE_AUTHORITY_MISMATCH`.
- Arbitrary `Bound` substitution: **PASS**. Canonical A cannot be replaced by supplied B.
- Missing provider: **PASS**, fail closed.
- Provider version mismatch: **PASS**, `WORLD_RULE_PROVIDER_VERSION_MISMATCH` fail closed.
- Constant-specific enum mutable state: **PASS**, mutable or unsafe retained enum/provider state rejected.
- Canonical identities/fingerprints: **PASS**, target tests cover canonical request/decision identity stability.
- Zero authoritative mutation: **PASS**. Freshness attack verifies canonical selection prefs plus World Pack manifest/database bytes are unchanged by resolution; rejection/fault hardening covers no authoritative mutation.
- Phase-18 ordering/regression: **PASS**.
- Phase-17 representative regression: **PASS**.

## CI verification — exact run only

Independently inspected GitHub Actions run:

- run number: **507**
- run ID: **31826220849**
- head SHA: **`8bb463e90142e12a499465b6554d7c8fbf58e355`**
- status/conclusion: **completed / success**
- build job ID: `94850887968`

Raw job logs independently confirm checkout of the exact target SHA and:

- `gradle --no-daemon :app:testDebugUnitTest --stacktrace` -> **BUILD SUCCESSFUL**;
- `gradle --no-daemon :app:assembleRelease --stacktrace` -> **BUILD SUCCESSFUL**;
- signed release validation build completed;
- immutable validation artifact was prepared with `build-provenance.json` containing exact head SHA, run ID `31826220849`, version `1.2.0-alpha5-hybrid140`, and `publication: false`;
- artifact upload succeeded.

Artifact:

`RPG-OS-VALIDATION-1.2.0-alpha5-hybrid140-8bb463e90142e12a499465b6554d7c8fbf58e355`

Artifact ID: `9229030182`  
Artifact ZIP SHA-256: `89af61fbd78ad5f14b12c23c864eceabd60d6531c80338fabac7081605f10d5f`

CI result: **PASS**.

## Final matrix

| Check | Result |
|---|---|
| Current authority per resolution | PASS |
| Stale A after A -> B | PASS |
| Old provider A invocations in stale-A attack | 0 |
| Current B after switch | PASS |
| Version freshness | PASS |
| A -> B -> A | PASS |
| Cross-campaign | PASS |
| Missing authority | PASS |
| Authority read failure | PASS |
| Forged Bound | PASS |
| Second authority found | NO |
| Ordering | PASS |
| Zero authoritative mutation | PASS |
| Phase-18 regression | PASS |
| Phase-17 regression | PASS |
| Full JVM on exact CI run | PASS |
| Exact CI | PASS |
| New blockers | NONE |

## CHAT-2 conclusion

The attempted revalidation did not reproduce `P19-C2-AUTH-STALE-01` on `8bb463e...`. The hotfix changes the lifecycle from a captured authority snapshot to a read-only resolver whose production implementation re-reads canonical selection on every resolution. No independent second authority or stale authority cache was found.

**FINAL CHAT-2 VERDICT: PASS**
