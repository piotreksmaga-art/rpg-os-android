# CHAT-7 TEMP bug harness — BUG_16 test-fixture correction

Work item: `WORK-20260815-001`
Role: `CHAT-7`
Date: 2026-08-15
Status: **TEST HARNESS FIXED / DEVICE RERUN REQUIRED**

## Observed device-run result

The published device attempt at commit `39e0debf84d46c4597cf3c3a1a8445010dada41f` stopped before the runtime/device `/bug` stage because the automated BUG_01..BUG_20 unit suite had one failure: `test_BUG_16_secrets_redaction`.

Nineteen tests passed. BUG_16 failed because its fixture reused the literal `abc123` both as a legitimate diagnostic `buildSha` and as the synthetic password secret. The assertion searched the entire serialized bundle for `abc123`, so it rejected the intentionally preserved build identity even though that field was not a secret.

This evidence does **not** claim a device bug-harness PASS for that attempt.

## Correction

Only the TEMP test fixture was changed. Production TEMP bug-harness behavior was not relaxed.

BUG_16 now:

- uses a unique password fixture: `pwd-should-never-persist-778899`;
- verifies that the synthetic GitHub token is removed;
- verifies that the unique password fixture is removed;
- verifies that the Bearer value is removed;
- verifies that legitimate `buildSha=abc123` diagnostic metadata remains intact;
- verifies that `originalReportRedactedForSecretSafety=true`.

Fix commit: `fa78a6648f5c8182019f3d81bfc4a20f7e809774`.

## Authority / scope

- Canonical mutation: **NO**.
- Phase-19 semantics changed: **NO**.
- Phase-20+ implemented: **NO**.
- GitHub Issue created: **NO**.
- Production runtime/schema changed: **NO**.

## Required next action

Rerun `temp-gm/bridge/device_test_bug_harness.sh` on the Samsung device from the updated `chat7-temp-gm-benchmark` branch. A full device PASS may be claimed only after BUG_01..BUG_20 pass and the runner completes the real localhost `/bug`, local-pending recovery, screenshot-consent and no-autonomous-submission checks.
