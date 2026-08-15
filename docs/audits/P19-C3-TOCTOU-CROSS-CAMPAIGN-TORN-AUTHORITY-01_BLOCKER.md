# Phase 19 Blocker Record — P19-C3-TOCTOU-CROSS-CAMPAIGN-TORN-AUTHORITY-01

Status: BLOCKED
Owner: CHAT-6 — Integration / Android / Release Owner (forward-only Phase-19 bug-fix path)
Candidate invalidated for global acceptance: `8bb463e90142e12a499465b6554d7c8fbf58e355`
Detected by: CHAT-3 independent fresh revalidation
Evidence report: `docs/audits/WORK-20260815-P19_CHAT3_FRESH_INTEGRITY_TOCTOU_REVALIDATION_8BB463E9_RERUN.md`
Evidence report commit: `368375fee6f03d1432b23416a3348218b31e9246`

## Finding

The production `WorldPackAuthorityResolver` on the candidate assembles current authority from two separate canonical reads (`activeCampaignId()` followed by `activeWorldRuleMode().binding`). A supported concurrent campaign/world-pack selection change between those reads can produce a torn `(campaignUid, worldPackBinding)` authority result, allowing a binding from the new selection to be returned for the old requested campaign.

CHAT-3 also reports that the production resolver closes over mutable `CampaignSelectionManager`, so the strict read-only capability boundary is not structurally enforced even though ordinary resolution does not itself mutate canonical selection.

## Global effect

- GLOBAL PHASE-19 ACCEPTANCE: BLOCKED
- RELEASE/PUBLICATION: FORBIDDEN for this candidate
- PHASE 20: NOT STARTED
- Existing exact CI run #507 / ID `31826220849` remains valid evidence for the tested candidate but cannot override an independent audit FAIL.
- Existing CHAT-2 / CHAT-4 PASS verdicts do not override CHAT-3 FAIL.
- A fresh CHAT-5 verdict is still required, but cannot make this candidate globally acceptable while this blocker remains open.

## Required remediation contract

1. Forward-only production fix; no history rewrite.
2. Preserve `CampaignSelectionManager` as the sole canonical persisted selection authority; do not create a second persisted source of truth.
3. Resolve campaign + World Pack authority as one coherent current-authority observation, or otherwise make torn cross-campaign authority impossible and fail closed.
4. Preserve all earlier Phase-19 authority freshness protections, Phase-18 ordering, provider invocation-before-rejection guarantees, and zero authoritative mutation.
5. Preserve a structurally read-only authority capability at the `PlayerDomainEngine` boundary.
6. Add focused adversarial coverage for the reported interleaving / equivalent deterministic race harness.
7. Run full `:app:testDebugUnitTest` and exact development CI on the new runtime SHA.
8. After any production change, invalidate all existing candidate-specific Phase-19 verdicts and obtain fresh independent CHAT-2, CHAT-3, CHAT-4 and CHAT-5 revalidation on the new exact runtime SHA.
9. Do not publish and do not start Phase 20 until the new candidate satisfies all formal acceptance gates.
