# CHAT-7 — TEMP bug bridge lifecycle contract PASS

Date: 2026-08-15
Role: CHAT-7
Work item: `WORK-20260815-001`
Scope: TEMP / NON-PRODUCTION
Publication: NO

## Freshness / baselines

- Canonical master checked before implementation and before write: `0a7a06cc66a6328f49bc086a41b36dbfa4528143`.
- CHAT-7 branch parent baseline: `a9ee67239f8bbc4e6f95b76197ec5c06a5776395`.
- Implementation commit: `f9159e2091e527765781b2cd846ce3d0ac210559`.
- No canonical Phase-19, PlayerDomainEngine, WorldRuleProvider, Phase-20+, Android UI or release files were modified.

## Blocker resolved

CHAT-6 required the full lifecycle already owned by the existing `BugReportStore` to be accessible through localhost. The fix does not create a second bug queue. `temp_bug_ui_contract.py` and `temp_gm_bridge.py` are thin adapters over the existing durable store/report JSON.

Stable lifecycle API:

- `GET /bugs`
- `GET /bugs?scope=all`
- `GET /bugs/{reportUid}`
- `GET /bugs/{reportUid}/preview`
- `POST /bugs/{reportUid}/duplicates`
- `POST /bugs/{reportUid}/decision`
- `POST /bugs/{reportUid}/retry`
- `POST /bugs/{reportUid}/cancel`
- `POST /bugs/{reportUid}/submission-authorization`
- `POST /bugs/{reportUid}/submitted`
- `POST /bugs/{reportUid}/linked-duplicate`
- `DELETE /bugs/{reportUid}?confirm=true`

Legacy aliases remain for compatibility: `GET /bug/pending`, `POST /bug/control`.

## Submission boundary

- Android UI: presentation + explicit user decision only; no GitHub credentials/API.
- Bridge/BugReportStore: persists and consumes one-shot authorization only; no GitHub write.
- External privileged submission adapter: after explicit authorization consumption, performs exactly the approved GitHub action and records success through `/submitted` or `/linked-duplicate`.
- `POST /bug`, preview, duplicate storage, retry and Internet recovery never create/update an Issue.
- `canonicalMutation=false` remains invariant.

## One-shot / restart safety

New-Issue and duplicate-link authorizations are persisted in the report lifecycle record and consumed at most once. A second consumption without a new user decision fails closed. `KEEP_PENDING` and retry clear authorization. Restart does not invent publication authority.

## Local validation

Validation performed against the stdlib localhost bridge integration harness:

- Python syntax/bytecode compile: PASS for lifecycle adapter + bridge.
- `temp-gm/bridge/test_bug_bridge_lifecycle.py`: **20/20 PASS**.
- Test implementation uses a real ephemeral `ThreadingHTTPServer` bound to `127.0.0.1` and sends real HTTP requests with `http.client`.

Required matrix:

- BRIDGE_BUG_01 pending list — PASS
- BRIDGE_BUG_02 report detail — PASS
- BRIDGE_BUG_03 preview — PASS
- BRIDGE_BUG_04 preview does not authorize — PASS
- BRIDGE_BUG_05 duplicate candidate round-trip — PASS
- BRIDGE_BUG_06 KEEP_PENDING no authorization — PASS
- BRIDGE_BUG_07 CANCEL no authorization — PASS
- BRIDGE_BUG_08 CONFIRM_NEW_ISSUE explicit authorization — PASS
- BRIDGE_BUG_09 authorization one-shot — PASS
- BRIDGE_BUG_10 restart does not invent authorization — PASS
- BRIDGE_BUG_11 mark SUBMITTED — PASS
- BRIDGE_BUG_12 mark LINKED_DUPLICATE — PASS
- BRIDGE_BUG_13 offline remains LOCAL_PENDING — PASS
- BRIDGE_BUG_14 logcat unavailable remains valid — PASS
- BRIDGE_BUG_15 screenshot without consent unavailable — PASS
- BRIDGE_BUG_16 screenshot with explicit consent represented — PASS
- BRIDGE_BUG_17 cancel/delete explicit action — PASS
- BRIDGE_BUG_18 unknown report ID fail-closed — PASS
- BRIDGE_BUG_19 malformed decision fail-closed — PASS
- BRIDGE_BUG_20 canonicalMutation false throughout lifecycle — PASS

Expected fail-closed transport behavior was also observed: reused authorization returns HTTP 409; unknown report returns 404; delete without explicit confirmation returns 400.

No GitHub Issue was created or updated during these tests.

## Existing device baseline retained

The transport/lifecycle adapter does not alter the validated model/runtime profile. Existing Samsung Galaxy S24 evidence remains valid:

- minimal TEMP GM vertical slice: PASS;
- TEMP Bug Harness: PASS;
- BUG_01..BUG_20: 20/20 PASS;
- provider `BIELIK_4_5B_V3`;
- bridge `127.0.0.1:8765`;
- llama `127.0.0.1:8768`;
- `LOGCAT_STATUS=UNAVAILABLE` in the controlled run was accepted degraded behavior and did not lose the report.

This change is a local transport adapter over the same queue and does not require model retuning.

## Privacy / authority

- bounded logcat remains enforced by existing harness;
- screenshot reference remains gated by explicit user consent;
- secret redaction remains in the existing harness;
- bridge exposes no GitHub credential, GGUF path, arbitrary file access, Save/DB mutation, StatePatch, PlayerChangeSet or COMMIT capability;
- autonomous Issue creation: NO;
- canonical mutation possible: NO;
- Phase-19 semantics changed: NO;
- Phase-20+ implemented: NO;
- PUBLISHED: NO.

## Handoff

Final CHAT-6 contract is documented in:

`temp-gm/docs/CHAT7_TO_CHAT6_TEMP_GM_UI_HANDOFF.md`

Verdict: **READY FOR CHAT-6**.
