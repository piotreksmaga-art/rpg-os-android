# RPG OS — TEMP Bug-Reporting Harness

Work item: `WORK-20260815-001`  
Role: `CHAT-7`  
Status: implementation ready for automated + device validation  
Scope: NON-PRODUCTION / TEMP TEST INFRASTRUCTURE

## Authority

The user is reporter authority. The harness captures and prepares evidence only. It has no canonical campaign mutation path and no GitHub write path.

`POST /bug` always creates a local report first. It never creates or updates a GitHub Issue.

GitHub submission requires a separate explicit user decision after preview and duplicate search. The local authorization gate is idempotent: one confirmed report can expose at most one external create action before being marked submitted.

## Bundle schema

Submission states:

- `LOCAL_PENDING`
- `READY`
- `SUBMITTED`
- `LINKED_DUPLICATE`
- `CANCELLED`

Every report separates:

- `USER-SUPPLIED`
- `DEVICE-CAPTURED`
- `AI-SUMMARIZED`

AI summary contains `isEvidence=false` and does not participate in duplicate identity.

Minimum identity/evidence includes reportUid, createdAt, original user report (verbatim for normal text; security redaction overrides verbatim persistence if the text contains credentials), app/build identity, campaign/worldpack IDs, route, TEMP provider/mode, bridge/llama/ADB state, bounded safe actions and GM responses, bounded logcat, optional screenshot reference, reproduction metadata, deterministic duplicate fingerprint, and submission state.

## Privacy / minimization

Never persist API keys, GitHub tokens, bearer tokens, passwords, cookies or similar secret patterns. Logcat, recent actions, recent GM responses, issue-preview fields and user text all pass through bounded secret redaction.

No environment dump, clipboard, cross-app history or unbounded system logcat is captured.

Pending queue is bounded at 100 report files. On queue exhaustion the harness rejects a new capture rather than deleting an older pending report silently.

## Logcat

One-shot only after explicit `/bug` request with `include_logcat=true`.

Preferred capture:

`adb shell pidof com.rpgos.app` -> `adb logcat -d -t 300 --pid <pid> -v threadtime`

Properties:

- package/PID scoped;
- max 300 lines;
- max 30,000 characters after redaction;
- 6 second subprocess timeout;
- no continuous recording;
- if ADB/PID/logcat is unavailable, the report still survives as `LOCAL_PENDING` with logcat status `UNAVAILABLE`.

## Screenshot

The harness never invokes screenshot capture. It may only persist a screenshot reference when both:

- `include_screenshot=true`
- `screenshotApproved=true`

Without approval the stored reference is blank and issue preview displays `USER_APPROVED=NO`.

## Duplicate fingerprint

SHA-256 prefix over canonical JSON containing only stable technical/symptom identity:

- app versionName/versionCode;
- route;
- normalized exception class;
- up to 5 normalized top stack frames (line numbers removed);
- normalized user symptom;
- selected stable environment identifiers: device model + Android SDK.

Timestamp, reportUid, AI summary, field ordering and formatting are excluded.

## GitHub flow

`/bug` -> local bundle -> optional AI summary -> CHAT-7 searches open GitHub Issues -> candidates recorded -> preview -> user decision.

Decisions:

- `CONFIRM_NEW_ISSUE` -> state `READY`, one authorization may be consumed;
- `CANCEL` -> `CANCELLED`, no issue action;
- `KEEP_PENDING` -> stays local;
- linking evidence to a duplicate requires explicit user confirmation too.

Actual GitHub create/comment operations remain outside `POST /bug` and outside the local harness. CHAT-7 uses its GitHub connector only after explicit user authorization.

## Failure safety

Bug capture never calls Save, canonical DB, PlayerDomainEngine, WorldRuleProvider, StatePatch, PlayerChangeSet, transaction or COMMIT code.

TEMP GM offline, ADB unavailable, bridge restart, Internet unavailable or malformed log text must degrade capture, not affect campaign truth.

## Required test suite

`test_temp_bug_harness.py` defines `BUG_01` through `BUG_20` from the coordinator assignment. Device test runner additionally validates the live bridge `/bug`, pending recovery across bridge restart, preview generation and real bounded ADB/logcat behavior where available.

PUBLISHED: NO
