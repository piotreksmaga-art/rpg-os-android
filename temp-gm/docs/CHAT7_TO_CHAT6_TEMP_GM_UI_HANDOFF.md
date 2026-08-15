# CHAT-7 -> CHAT-6 — TEMP GM / Bug Harness UI Handoff

Status: **READY FOR CHAT-6**
Work item: `WORK-20260815-001`
Scope: TEMP / NON-PRODUCTION
Backend owner: CHAT-7
Android integration owner: CHAT-6

## 1. Fixed backend baseline

Logical provider ID: `BIELIK_4_5B_V3`.

Validated device profile: Bielik 4.5B v3 Instruct, GGUF Q4_K_M, llama.cpp/Vulkan, CTX=8192, KV=f16/f16, `-b 64 -ub 64 -np 1 -ngl 99`, tested with `GGML_VK_DISABLE_OCP_FP4=1`.

Android must never know or persist a GGUF path, llama.cpp binary path, model CLI flags, or GitHub credentials.

## 2. Localhost/security boundary

- TEMP bridge: `http://127.0.0.1:8765`
- llama.cpp runtime: `http://127.0.0.1:8768`
- CHAT-6 talks only to the bridge.
- Both services are localhost-only; `0.0.0.0` is forbidden.
- The bridge contains no GitHub writer and performs no canonical RPG OS mutation.
- Every lifecycle response is non-authoritative: `canonicalMutation=false`.

## 3. Provider status

Stable UI enum:

- `OFFLINE`
- `STARTING`
- `READY`
- `ERROR`

`STARTING` is a transient launcher/UI state. Bridge/provider health resolves to READY/OFFLINE/ERROR once reachable.

## 4. Core TEMP GM API

Stable endpoints:

- `GET /health`
- `GET /providers`
- `GET /active-provider`
- `POST /active-provider`
- `POST /gm/turn`
- `POST /bug`

Response modes remain `NARRATIVE_ONLY`, `ENGINE_CONFIRMED`, `TEST_FALLBACK`. No mode has authoritative mutation authority.

Example `/gm/turn`:

```json
{
  "message": "Deklaracja gracza",
  "mode": "NARRATIVE_ONLY",
  "maxTokens": 1024,
  "context": {
    "campaignUid": "campaign-uid",
    "worldPackUid": "worldpack-uid",
    "playerIdentity": {},
    "sceneState": {},
    "playerSceneState": {},
    "relevantNpcs": [],
    "recentDialogueActions": [],
    "retrievedChronicleMemory": [],
    "availableTestCapabilities": [],
    "engineConfirmedResults": []
  }
}
```

Response:

```json
{
  "providerId": "BIELIK_4_5B_V3",
  "mode": "NARRATIVE_ONLY",
  "narrative": "...",
  "canonicalMutation": false,
  "usage": {}
}
```

## 5. BugReportStore ownership

There is exactly one local durable bug queue: the existing `BugReportStore` under the bridge data directory. The bridge is only a transport/service adapter over that store. Android must not create a second bug queue or duplicate report lifecycle state.

Submission states:

- `LOCAL_PENDING` — stored locally, no publication authorization.
- `READY` — explicit user authorization exists for exactly one external action kind.
- `SUBMITTED` — an external privileged adapter created a new Issue and recorded the result.
- `LINKED_DUPLICATE` — an external privileged adapter updated/linked the selected existing Issue and recorded the result.
- `CANCELLED` — user cancelled the workflow.

`READY != SUBMITTED`.

## 6. Start `/bug`

`POST /bug`

Minimal request:

```json
{
  "description": "Po kliknięciu Kontynuuj nic się nie dzieje.",
  "include_logcat": true,
  "include_screenshot": false,
  "screenshotApproved": false
}
```

Successful local capture returns HTTP 201:

```json
{
  "reportUid": "bug-...",
  "captureStatus": {
    "localBundle": "SAVED",
    "logcat": "UNAVAILABLE",
    "screenshot": "NOT_CAPTURED"
  },
  "duplicateFingerprint": "...",
  "submissionState": "LOCAL_PENDING",
  "githubSubmissionAuthorized": false,
  "canonicalMutation": false,
  "errors": ["package_pid_unavailable"]
}
```

`POST /bug` never creates or updates a GitHub Issue.

## 7. Final bug lifecycle REST API

CHAT-6 should use the `/bugs` contract below. Legacy aliases `GET /bug/pending` and `POST /bug/control` remain for compatibility only.

### A. Pending count/list

`GET /bugs`

Default scope is pending (`LOCAL_PENDING` + `READY`).

```json
{
  "count": 2,
  "pendingCount": 2,
  "reports": [
    {
      "reportUid": "bug-...",
      "submissionState": "LOCAL_PENDING",
      "duplicateFingerprint": "...",
      "descriptionPreview": "...",
      "route": "SAVES",
      "tempProviderId": "BIELIK_4_5B_V3",
      "bridgeState": "READY",
      "llamaState": "OFFLINE",
      "adbState": "UNAVAILABLE",
      "logcatStatus": "UNAVAILABLE",
      "screenshotRequested": false,
      "screenshotUserApproved": false,
      "screenshotAvailable": false,
      "duplicateCandidateCount": 0,
      "userConfirmationRequired": true,
      "submissionKind": null,
      "targetIssueNumber": null,
      "issueNumber": null,
      "issueUrl": null,
      "canonicalMutation": false
    }
  ],
  "canonicalMutation": false
}
```

`GET /bugs?scope=all` returns all retained lifecycle records, including terminal states.

### B. Report detail

`GET /bugs/{reportUid}`

Returns the sanitized persisted bundle plus compact summary:

```json
{
  "report": { "...": "sanitized BugReportBundle" },
  "summary": { "reportUid": "bug-...", "submissionState": "LOCAL_PENDING" },
  "canonicalMutation": false
}
```

### C. Issue preview

`GET /bugs/{reportUid}/preview`

Returns:

```json
{
  "reportUid": "bug-...",
  "submissionState": "LOCAL_PENDING",
  "duplicateFingerprint": "...",
  "duplicateCandidates": [],
  "issuePreview": "TITLE\n...",
  "userConfirmationRequired": true,
  "githubWritePerformed": false,
  "canonicalMutation": false
}
```

Preview never authorizes or performs a GitHub write.

### D. Duplicate candidates

`POST /bugs/{reportUid}/duplicates`

```json
{
  "candidates": [
    {
      "issueNumber": 123,
      "title": "Similar issue",
      "url": "https://...",
      "fingerprint": "..."
    }
  ]
}
```

This only stores bounded candidate metadata in the same BugReportStore record. It does not authorize or perform any GitHub operation.

The bridge does not need GitHub credentials to search. A privileged coordinator/submission integration may perform the search and feed candidates back to this endpoint.

### E. Explicit user decision

`POST /bugs/{reportUid}/decision`

Keep pending:

```json
{ "decision": "KEEP_PENDING" }
```

Cancel:

```json
{ "decision": "CANCEL" }
```

Authorize a new Issue:

```json
{ "decision": "CONFIRM_NEW_ISSUE" }
```

Authorize linking/updating a selected probable duplicate:

```json
{
  "decision": "CONFIRM_LINK_DUPLICATE",
  "targetIssueNumber": 123
}
```

`CONFIRM_LINK_DUPLICATE` is accepted only for an issue already present in the stored duplicate candidate set.

### F. Retry/reopen local workflow

`POST /bugs/{reportUid}/retry`

Body may be `{}`. It returns a non-terminal report to `LOCAL_PENDING` and clears any previous/consumed authorization. It does not search GitHub and does not submit anything.

Terminal `SUBMITTED` and `LINKED_DUPLICATE` reports cannot be retried implicitly; a new report/user workflow is required.

### G. Cancel shortcut

`POST /bugs/{reportUid}/cancel`

Body `{}`. Equivalent to explicit `CANCEL`; no GitHub write.

### H. One-shot submission authorization consumption

`POST /bugs/{reportUid}/submission-authorization`

For a new issue:

```json
{ "kind": "NEW_ISSUE" }
```

For a selected duplicate:

```json
{ "kind": "LINK_DUPLICATE" }
```

First valid consumption returns HTTP 200 and `allowed=true`. A second attempt without a new explicit user decision returns HTTP 409 and `allowed=false`.

Example:

```json
{
  "allowed": true,
  "kind": "NEW_ISSUE",
  "reportUid": "bug-...",
  "issueDraft": "TITLE\n...",
  "githubWritePerformed": false,
  "canonicalMutation": false
}
```

This endpoint consumes local authorization only. It does not create/update GitHub.

### I. Record successful new-Issue submission

`POST /bugs/{reportUid}/submitted`

Only the privileged external submission adapter calls this after it consumed `NEW_ISSUE` authorization and the external GitHub create action succeeded.

```json
{
  "issueNumber": 321,
  "issueUrl": "https://github.com/.../issues/321"
}
```

The bridge validates lifecycle state and records `SUBMITTED` in the same BugReportStore record. It does not itself perform GitHub I/O.

### J. Record successful linked duplicate

`POST /bugs/{reportUid}/linked-duplicate`

Only after `LINK_DUPLICATE` authorization was consumed and the external GitHub update/link action succeeded:

```json
{
  "issueNumber": 123,
  "issueUrl": "https://github.com/.../issues/123"
}
```

Target issue must match the explicitly selected duplicate. Result becomes `LINKED_DUPLICATE`.

### K. Explicit local delete

`DELETE /bugs/{reportUid}?confirm=true`

Deletion requires explicit user confirmation. Without `confirm=true`, request fails closed. `SUBMITTED`/`LINKED_DUPLICATE` are not silently deleted by this endpoint.

## 8. Formal submission boundary

The responsibility split is fixed:

1. **Android UI**: displays report/preview/candidates, captures explicit user decision. It owns no GitHub token and performs no GitHub Issue API call.
2. **Bridge + BugReportStore**: stores the decision and exposes/consumes a one-shot local authorization. It performs no GitHub write.
3. **External privileged submission adapter**: after explicit approval, consumes the correct one-shot authorization, performs exactly the authorized GitHub action, then records the successful result through `/submitted` or `/linked-duplicate`.

If the external GitHub action fails after authorization was consumed, it must not silently reuse that authorization. The report is not marked submitted. The user can explicitly retry/reopen and approve again.

Internet recovery never auto-publishes pending reports.

## 9. Lifecycle state machine

```text
POST /bug
  -> LOCAL_PENDING

LOCAL_PENDING
  -- duplicates --> LOCAL_PENDING
  -- KEEP_PENDING --> LOCAL_PENDING
  -- CANCEL --> CANCELLED
  -- CONFIRM_NEW_ISSUE --> READY(kind=NEW_ISSUE)
  -- CONFIRM_LINK_DUPLICATE(target) --> READY(kind=LINK_DUPLICATE,target)

READY
  -- submission-authorization --> one-shot CONSUMED
  -- second consume --> FAIL CLOSED / 409
  -- retry --> LOCAL_PENDING + authorization cleared
  -- cancel --> CANCELLED

CONSUMED NEW_ISSUE
  -- external GitHub create succeeds + /submitted --> SUBMITTED

CONSUMED LINK_DUPLICATE
  -- external GitHub update succeeds + /linked-duplicate --> LINKED_DUPLICATE
```

Restart does not create authorization because the decision/consumed markers are persisted in the same report JSON. `KEEP_PENDING` remains non-authorized after restart.

## 10. Duplicate identity semantics

`duplicateFingerprint` remains deterministic and is not based on timestamp, random UUID or AI summary. AI summary may help search but is not report identity authority.

Candidate storage or preview never updates an existing Issue. User selection is mandatory before link authorization.

## 11. Screenshot semantics

Persisted evidence exposes:

```json
{
  "screenshot": {
    "requested": true,
    "userApproved": false,
    "reference": ""
  }
}
```

A reference is retained only when screenshot was requested **and** explicitly approved. Bridge never performs autonomous screenshot capture. CHAT-6 may present `screenshotRequested`, `screenshotUserApproved`, and `screenshotAvailable` from report summary/detail.

## 12. Degraded evidence/statuses

Logcat is bounded and package/PID-scoped. Machine-readable logcat states include:

- `CAPTURED`
- `CALLER_SUPPLIED_BOUNDED`
- `UNAVAILABLE`
- `SKIPPED`
- `NOT_REQUESTED`

`UNAVAILABLE` is valid degraded evidence. Prior S24 validation observed this when package PID was unavailable.

Other degraded states (ADB unavailable, TEMP GM/llama offline, Internet unavailable, GitHub unavailable) must not lose an already captured `LOCAL_PENDING` report. No background continuous logcat or automatic publication is permitted.

## 13. Error contract

Lifecycle errors are fail-closed and return JSON with `canonicalMutation=false`:

- HTTP 400 — malformed body, unsupported decision/kind, missing explicit delete confirmation.
- HTTP 404 — unknown report ID/path.
- HTTP 409 — lifecycle conflict, reused/absent one-shot authorization, terminal-state retry/delete, mismatched duplicate target.
- HTTP 500 — unexpected lifecycle adapter failure.
- HTTP 507 — pending queue unavailable/full during `POST /bug`.

No error path mutates canonical campaign state.

## 14. Evidence classification/privacy

Report detail preserves explicit classes:

- `USER-SUPPLIED`
- `DEVICE-CAPTURED`
- `AI-SUMMARIZED`

AI summary is not evidence. Existing secret redaction remains active. Do not capture API keys, GitHub tokens, Android auth tokens, cookies, clipboard, unrelated app history, unrestricted environment dumps, arbitrary files, or unbounded system logcat.

## 15. Full Android flow for CHAT-6

1. Read `/health`, `/providers`, `/active-provider` and render logical status only.
2. User invokes `/bug <description>`; Android calls `POST /bug`.
3. Save only returned `reportUid` as UI navigation identity; BugReportStore remains lifecycle owner.
4. Open `GET /bugs/{reportUid}` and `GET /bugs/{reportUid}/preview`.
5. If duplicate search is available, privileged integration searches GitHub and stores candidates through `/duplicates`; Android displays them.
6. User explicitly chooses `KEEP_PENDING`, `CANCEL`, `CONFIRM_NEW_ISSUE`, or `CONFIRM_LINK_DUPLICATE`.
7. For a confirmed external action, privileged submission adapter calls `/submission-authorization` once.
8. Only if `allowed=true`, that adapter performs exactly one corresponding GitHub action.
9. On success it calls `/submitted` or `/linked-duplicate`.
10. Android refreshes `GET /bugs/{id}`/`GET /bugs` and displays final state/Issue URL.

Android never has GitHub credentials and never treats Internet recovery as permission to publish.

## 16. Canonical authority invariant

No TEMP endpoint may write Save/canonical DB, execute StatePatch/PlayerChangeSet/COMMIT, create authoritative events, or bypass PlayerDomainEngine, reference validation, WorldRuleProvider, validators or transaction layers.

## 17. Validation/evidence

Bridge lifecycle transport integration is covered by `temp-gm/bridge/test_bug_bridge_lifecycle.py` with `BRIDGE_BUG_01..BRIDGE_BUG_20`.

Prior device evidence remains:

- `temp-gm/evidence/2026-08-15-minimal-vertical-slice-device-pass.md`
- `temp-gm/results/device/2026-08-15_18-48-08-bielik45-temp-bug-harness-device-pass/`

Lifecycle completion evidence:

- `temp-gm/evidence/2026-08-15-chat7-bug-bridge-lifecycle-contract-pass.md`

## 18. CHAT-6 scope after this handoff

CHAT-6 may now implement Android Developer Settings/status, logical provider presentation, `/bug` entry, pending list/detail/preview, screenshot-consent presentation, duplicate selection and explicit submit/cancel UX against this stable localhost contract.

CHAT-7 does not implement Android UI or publication.
