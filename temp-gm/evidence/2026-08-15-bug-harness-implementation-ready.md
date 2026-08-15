# CHAT-7 TEMP Bug Harness — implementation ready

Work item: `WORK-20260815-001`  
Role: `CHAT-7`  
Date: 2026-08-15  
Status: **IMPLEMENTED / DEVICE VALIDATION PENDING**

## Freshness

- master before implementation: `9a50d00eb12aff05ceb2ee21f5869d97a5122084`
- master pre-evidence recheck: `9a50d00eb12aff05ceb2ee21f5869d97a5122084`
- no reservation collision found with CHAT-7 TEMP harness scope.

## Implemented

- `temp-gm/bridge/temp_bug_harness.py`
- `temp-gm/bridge/test_temp_bug_harness.py`
- bridge `POST /bug` routed to local durable harness
- `temp-gm/docs/TEMP_BUG_REPORTING_HARNESS.md`
- `temp-gm/bridge/device_test_bug_harness.sh`

## Safety contract

- user remains reporter authority;
- POST `/bug` has no GitHub write capability;
- initial state is `LOCAL_PENDING`;
- evidence classes are structurally separated as USER-SUPPLIED / DEVICE-CAPTURED / AI-SUMMARIZED;
- AI summary is `isEvidence=false` and excluded from duplicate fingerprint;
- queue is persistent and bounded to 100 reports;
- bounded package/PID logcat capture is one-shot, max 300 lines / 30k chars / 6s timeout;
- screenshot reference is retained only when `include_screenshot=true` AND `screenshotApproved=true`;
- secret redaction covers user text, logcat, recent actions, recent GM responses and preview fields;
- malformed Unicode/binary-like text is converted safely before persistence;
- deterministic fingerprint excludes timestamp, report UID and AI summary;
- explicit confirmation gate is idempotent and exposes at most one external issue-create authorization;
- canonicalMutation remains false; harness contains no Save/DB/StatePatch/PlayerChangeSet/COMMIT path.

## Tests authored

`test_temp_bug_harness.py` contains mandatory cases BUG_01 through BUG_20.

They cover verbatim normal user report preservation, evidence classification, bounded logcat, ADB/Internet degradation, persistent pending recovery, deterministic fingerprint, timestamp/AI-summary independence, duplicate candidate handling, explicit confirmation/cancellation/idempotency, screenshot consent, secret redaction, canonical-state non-mutation sentinel, TEMP-GM/bridge offline capture, and malformed logcat handling.

Tests are authored but not claimed PASS until executed in Termux/device flow.

## Device validation runner

`device_test_bug_harness.sh` will:

1. run BUG_01..BUG_20 unittest suite;
2. start final Bielik runtime on localhost using the accepted CTX8192/f16 profile;
3. start TEMP bridge;
4. perform one harmless TEMP GM interaction;
5. call POST `/bug` with a controlled non-production test symptom;
6. request bounded package-scoped logcat for `com.rpgos.app` when ADB is available;
7. keep screenshot unapproved/unavailable;
8. assert `LOCAL_PENDING`, `githubSubmissionAuthorized=false`, `canonicalMutation=false`;
9. restart the bridge;
10. recover the same report from disk;
11. produce an issue preview without publishing;
12. publish device evidence to the CHAT-7 evidence branch if the existing publisher is available.

## Publication

PUBLISHED: NO. No GitHub Issue, PR, canonical merge or release is created by this stage.
