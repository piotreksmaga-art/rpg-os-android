# Phase 19 — CHAT-2 Fresh Authority Revalidation

ROLE: CHAT-2 — independent adversarial authority/lifecycle auditor

VALIDATED SHA: `8bb463e90142e12a499465b6554d7c8fbf58e355`

VERDICT: **PASS**

## Runtime pin

At audit start, `master` was identical to the target SHA. No later production/test/runtime commit existed.

## Authority source

`CampaignSelectionManager` remains the canonical persisted application authority for active campaign and active World Pack selection. The Phase-19 hotfix exposes an internal read-only `WorldPackAuthorityResolver` that re-reads the current campaign and current validated World Pack binding on every lookup. `activeWorldPackAuthoritySnapshot()` is retained only as a compatibility name and now returns the live resolver, not a captured immutable snapshot.

`WorldPackAuthoritySnapshot` still exists as an internal immutable resolver fixture, but repository inspection found no production wiring that persists or selects a World Pack independently of `CampaignSelectionManager`. It is not a second canonical/persisted selector.

## Mandatory adversarial matrix

1. **Long-lived engine, A -> B, stale Bound(A)** — PASS. Current resolver returns B and `validateWorldRuleAuthority()` rejects stale A with `WORLD_RULE_BINDING_AUTHORITY_MISMATCH` before provider A can run.
2. **Same engine after A -> B, Bound(B)** — PASS. Current B is accepted.
3. **A v1 -> A v2, stale Bound(A v1)** — PASS. UID/version tuple mismatch rejects before provider.
4. **A -> B -> A, same engine** — PASS. Resolver follows current canonical selection on each resolution.
5. **Missing current authority** — PASS. Bound mode with null authoritative result fails `WORLD_RULE_AUTHORITY_MISSING` before provider.
6. **Authority read failure** — PASS. Non-structural resolver exceptions are mapped to stable `WORLD_RULE_AUTHORITY_READ_FAILED`; provider invocation remains zero.
7. **Canonical A + permissive registered B + Bound(B)** — PASS. `WORLD_RULE_BINDING_AUTHORITY_MISMATCH`, B invocation count 0.
8. **Repository-wide alternate authority search** — PASS. No second production persisted World Pack selector was found. `CampaignSelectionManager` owns persisted `active_campaign` / `active_worldpack`; resolver is read-only and world-agnostic.

## Ordering

Verified production ordering:

canonical command validation -> campaign/actor checks -> Phase-18 command reference validation -> current World Pack authority lookup/validation -> COMMAND_PRECHECK provider -> resolution -> Phase-18 draft reference validation -> DRAFT_EFFECT_CHECK -> engine-owned PlayerChangeSet.

Unknown/wrong-campaign Phase-18 references therefore reject before authority/provider execution, and authority mismatch rejects before provider selection/evaluation.

## Previous Phase-19 protections

- nullable/unbound public bypass remains closed: public `PlayerResolutionContext.create()` requires explicit `WorldRuleMode`; generic unbound creation remains internal and fails if canonical bound authority exists;
- Bound substitution remains fail-closed;
- missing provider remains `WORLD_RULE_PROVIDER_MISSING` after authority match;
- provider World Pack version mismatch remains fail-closed;
- constant-specific enum retained mutable state checks remain active;
- structural canonical identity and decision/request/proposal fingerprints remain unchanged by this authority hotfix;
- no authoritative state mutation was observed on resolution; targeted Robolectric test compares selection prefs and World Pack manifest/database bytes before/after.

## Tests / CI

`WorldRuleProviderPhase19AuthorityFreshnessTest` directly exercises the production `PlayerDomainEngine` path with one long-lived engine for A/B switching, version freshness, missing authority, resolver read failure and zero mutation.

Local full JVM execution was not available in the audit environment. Exact GitHub Actions evidence was independently verified:

- run number: **507**
- run ID: **31826220849**
- head SHA: `8bb463e90142e12a499465b6554d7c8fbf58e355`
- status: completed
- conclusion: success
- Validate project: success
- Run JVM unit tests: success
- signed validation APK: success
- immutable Actions artifact preparation/upload: success

## New blockers

NONE.

## Final

CHAT-2 Phase-19 authority revalidation: **PASS**.

This report does not globally accept Phase 19 and does not start Phase 20.
