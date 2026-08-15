# CHAT-7 -> CHAT-6 — TEMP GM / Bug Harness UI Handoff

Status: **READY FOR CHAT-6 HANDOFF**
Work item: `WORK-20260815-001`
Scope: TEMP / NON-PRODUCTION
Backend owner: CHAT-7
Android integration owner: CHAT-6

## 1. Backend baseline

Logical provider:

`BIELIK_4_5B_V3`

Validated model profile:

```text
Bielik 4.5B v3 Instruct
GGUF Q4_K_M
llama.cpp / Vulkan
CTX=8192
KV=f16/f16
-b 64
-ub 64
-np 1
-ngl 99
GGML_VK_DISABLE_OCP_FP4=1 (tested Vulkan runtime flag)
```

Frontend must not know or persist the GGUF filesystem path, llama.cpp executable path or CLI arguments.

## 2. Network contract

TEMP bridge:

`http://127.0.0.1:8765`

llama.cpp runtime:

`http://127.0.0.1:8768`

CHAT-6 Android code talks to the bridge only. It does not talk directly to llama.cpp.

Neither TEMP service is allowed to bind to `0.0.0.0` under this contract.

## 3. Provider status enum for UI

Use exactly:

- `OFFLINE`
- `STARTING`
- `READY`
- `ERROR`

`STARTING` is a transient UI/launcher state before bridge/provider health resolves. Once the bridge is reachable, provider health currently resolves to `READY`, `OFFLINE` or `ERROR`.

Do not invent model-path-derived statuses.

## 4. Stable bridge endpoints

Core:

- `GET /health`
- `GET /providers`
- `GET /active-provider`
- `POST /active-provider`
- `POST /gm/turn`
- `POST /bug`

Bug UI support:

- `GET /bug/pending`
- `POST /bug/control`

No Android-side endpoint expansion is needed for the initial CHAT-6 integration.

## 5. GET /health

Expected shape:

```json
{
  "status": "ok",
  "bridge": "READY",
  "activeProvider": "BIELIK_4_5B_V3",
  "provider": {
    "id": "BIELIK_4_5B_V3",
    "status": "READY"
  },
  "canonicalMutation": false
}
```

Recommended UI behavior:

- connection failure -> Bridge Offline;
- bridge reachable + provider OFFLINE -> bridge available, model unavailable;
- provider STARTING -> show starting/progress state only;
- provider READY -> enable TEMP GM turn submission;
- provider ERROR -> show diagnostic error state, do not change campaign state.

## 6. GET /providers

The current final baseline exposes one logical provider.

```json
{
  "providers": [
    {
      "id": "BIELIK_4_5B_V3",
      "name": "Bielik 4.5B v3",
      "status": "READY",
      "runtime": "llama.cpp",
      "backend": "Vulkan",
      "format": "GGUF",
      "quantization": "Q4_K_M",
      "contextWindow": 8192
    }
  ]
}
```

UI identity key is `id`, not display name.

## 7. Active provider

GET:

`GET /active-provider`

POST:

```json
{
  "providerId": "BIELIK_4_5B_V3"
}
```

Unknown ID returns `400 unknown_provider`.

The selector is intentionally logical-ID based even though the current final device profile has only one provider.

## 8. GM turn request

`POST /gm/turn`

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

Do not send arbitrary full canonical World State as model context.

## 9. GM turn response

```json
{
  "providerId": "BIELIK_4_5B_V3",
  "mode": "NARRATIVE_ONLY",
  "narrative": "...",
  "canonicalMutation": false,
  "usage": {}
}
```

Allowed modes:

- `NARRATIVE_ONLY`
- `ENGINE_CONFIRMED`
- `TEST_FALLBACK`

None grants authoritative mutation authority.

The Android client must not interpret narrative text as committed player/campaign state.

## 10. NPC knowledge boundary

`relevantNpcs[]` must preserve separate scene and knowledge views:

```json
{
  "npcUid": "npc-1",
  "sceneFacts": {
    "position": "gate"
  },
  "knowledge": {
    "observed": [],
    "heard": [],
    "told": [],
    "inferred": []
  }
}
```

Do not copy hidden/global state into `knowledge` merely because RPG OS knows it.

The device E2E already validated a hidden-secret non-leak scenario.

## 11. CTX budget

Native context target is fixed at 8192.

Planning budget:

| Segment | Tokens |
|---|---:|
| system/authority contract | 900 |
| scene | 1100 |
| player scene state | 700 |
| relevant NPC scene state | 700 |
| NPC knowledge | 1000 |
| recent dialogue/actions | 1800 |
| Chronicle/Memory retrieval | 700 |
| serialization reserve | 268 |
| response reserve | 1024 |
| Total | 8192 |

CHAT-6 should not attempt to increase model CTX.

## 12. `/bug` start flow

The UI command `/bug <opis>` or equivalent UI action should call:

`POST /bug`

Minimal body:

```json
{
  "description": "Po kliknięciu Kontynuuj nic się nie dzieje.",
  "include_logcat": true,
  "include_screenshot": false,
  "screenshotApproved": false
}
```

Recommended additional safe fields when known:

- build versionName/versionCode/build SHA;
- campaignUid;
- worldPackUid;
- current route/screen;
- TEMP response mode;
- ADB status;
- bounded recent safe actions;
- bounded recent TEMP GM responses;
- reproduction status/notes;
- exception class and bounded top stack frames;
- stable device model / Android SDK.

Never send tokens, cookies, clipboard, unrestricted environment dumps or unrelated app history.

## 13. `/bug` capture response

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

`LOCAL_PENDING` is a successful local capture state, not an error.

## 14. Evidence classification UI

When previewing a report, keep the labels visible or otherwise unambiguous:

- `USER-SUPPLIED`
- `DEVICE-CAPTURED`
- `AI-SUMMARIZED`

AI summary must not be presented as captured evidence.

## 15. Pending queue

`GET /bug/pending`

Example:

```json
{
  "count": 1,
  "reports": [
    {
      "reportUid": "bug-...",
      "submissionState": "LOCAL_PENDING",
      "duplicateFingerprint": "...",
      "descriptionPreview": "...",
      "route": "SAVES",
      "logcatStatus": "UNAVAILABLE",
      "screenshotRequested": false,
      "screenshotUserApproved": false,
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

The queue persists across bridge restart and is bounded by the TEMP harness.

## 16. Report preview

Call:

```json
POST /bug/control
{
  "reportUid": "bug-...",
  "action": "PREVIEW"
}
```

Response contains:

- full sanitized local report;
- compact summary;
- rendered Issue preview;
- `githubWritePerformed=false`;
- `canonicalMutation=false`.

The preview is the mandatory user decision surface before submission authorization.

## 17. Duplicate detection flow

Duplicate search is not performed by the local bridge itself. A privileged integration layer/CHAT tool searches GitHub and returns candidate metadata to the bridge.

Record candidates with:

```json
POST /bug/control
{
  "reportUid": "bug-...",
  "action": "SET_DUPLICATES",
  "candidates": [
    {
      "issueNumber": 123,
      "title": "Similar issue",
      "url": "...",
      "fingerprint": "..."
    }
  ]
}
```

This does not authorize or perform any GitHub write.

UI then presents three explicit user choices where applicable:

A. Create a new Issue.

B. Link/add evidence to a selected existing duplicate.

C. Cancel/keep local only.

## 18. Explicit confirmation — new Issue

Only after the user explicitly confirms:

```json
POST /bug/control
{
  "reportUid": "bug-...",
  "action": "CONFIRM_NEW_ISSUE"
}
```

Result becomes local `READY`, `submissionKind=NEW_ISSUE`.

This still performs **zero GitHub writes**. A separate privileged submission step must inspect this authorization and create exactly one Issue.

## 19. Explicit confirmation — existing duplicate

Only after user selects a recorded candidate and confirms:

```json
POST /bug/control
{
  "reportUid": "bug-...",
  "action": "CONFIRM_LINK_DUPLICATE",
  "targetIssueNumber": 123
}
```

The local report becomes `READY`, `submissionKind=LINK_DUPLICATE`, with the selected issue number.

Safety property: this authorization is deliberately not consumable as a new-Issue authorization.

A separate privileged submission/update action is required to actually add evidence to the existing Issue.

## 20. Cancel / keep pending

Keep local:

```json
{
  "reportUid": "bug-...",
  "action": "KEEP_PENDING"
}
```

Cancel:

```json
{
  "reportUid": "bug-...",
  "action": "CANCEL"
}
```

`CANCEL` must never create/update GitHub.

## 21. Submitted state display

When a privileged user-authorized submission succeeds and the backend report is later marked `SUBMITTED` or `LINKED_DUPLICATE`, the UI may display:

- `issueNumber`;
- `issueUrl`.

Do not display an Issue as submitted merely because the local report is `READY`.

## 22. Screenshot consent

Screenshot is opt-in only.

UI must track:

- screenshot requested;
- user approved YES/NO;
- capture/reference available or unavailable.

Do not silently flip `screenshotApproved=true`.

If the user did not approve, screenshot reference remains empty.

## 23. Logcat presentation

Possible states:

- `CAPTURED`
- `CALLER_SUPPLIED_BOUNDED`
- `UNAVAILABLE`
- `NOT_REQUESTED`
- `SKIPPED`

The device validation produced `UNAVAILABLE` because package PID was unavailable. This is expected degraded behavior and not a blocker.

Suggested UI copy is neutral: “Logcat unavailable — report saved without logcat.”

Do not retry continuously in background.

## 24. Degraded behavior matrix

| Condition | UI/backend behavior | Canonical state |
|---|---|---|
| TEMP GM offline | disable/degrade TEMP GM turn; `/bug` still usable | unchanged |
| Bridge offline | show bridge unavailable; canonical app continues | unchanged |
| llama unavailable | provider OFFLINE; no model turn | unchanged |
| ADB unavailable | report saved with missing optional evidence | unchanged |
| logcat unavailable | report saved; show unavailable | unchanged |
| Internet unavailable | remain LOCAL_PENDING | unchanged |
| GitHub unavailable | remain local; retry only after later user action | unchanged |

## 25. Submission states for UI

- `LOCAL_PENDING` — captured locally, no external write authorized.
- `READY` — explicit local user authorization exists for a specific next external action.
- `SUBMITTED` — external new Issue action succeeded and result metadata was recorded.
- `LINKED_DUPLICATE` — external duplicate-link/update action succeeded and result metadata was recorded.
- `CANCELLED` — user cancelled submission.

`READY != SUBMITTED`.

## 26. Security/privacy requirements

CHAT-6 must preserve these boundaries:

- no API keys/GitHub tokens in bug bundle;
- no Android auth tokens/cookies;
- no full environment dump;
- no full unbounded logcat;
- no history from other apps;
- no clipboard capture;
- no automatic screenshot;
- no model GGUF path in frontend;
- no autonomous Issue creation/update.

## 27. Canonical authority boundary

The TEMP UI must never convert model output or bug flow into:

- Save write;
- canonical DB write;
- StatePatch;
- PlayerChangeSet;
- authoritative event;
- COMMIT;
- bypass of PlayerDomainEngine / WorldRuleProvider / validation / transaction layers.

Any future requirement to do so is a STOP/BLOCKED condition requiring coordinator decision.

## 28. Evidence references

Final model profile:

`temp-gm/docs/BIELIK_4.5B_V3_FINAL_TEMP_GM_PROFILE.md`

Integration contract:

`temp-gm/docs/TEMP_GM_INTEGRATION_CONTRACT.md`

Minimal vertical slice device PASS:

`temp-gm/evidence/2026-08-15-minimal-vertical-slice-device-pass.md`

Bug harness device PASS raw evidence:

`temp-gm/results/device/2026-08-15_18-48-08-bielik45-temp-bug-harness-device-pass/`

Bug harness implementation evidence:

`temp-gm/evidence/2026-08-15-bug-harness-implementation-ready.md`

## 29. CHAT-6 owned remaining work

CHAT-6 owns:

- Android Developer Settings/status UI;
- logical TEMP provider selector presentation;
- bridge health/provider status presentation;
- `/bug` entry UX;
- pending report list/preview UX;
- screenshot consent UX;
- duplicate candidate presentation;
- explicit confirmation/cancel UX;
- TEST APK integration/testing;
- release/publication only when separately authorized.

CHAT-7 does not implement these Android surfaces.

## 30. Handoff acceptance rule

Backend handoff is accepted when CHAT-6 consumes this contract without needing canonical architecture changes.

If Android integration requires changing Phase-19 semantics, canonical AI contracts, Phase-20+ runtime or authoritative mutation pathways:

`STOP -> BLOCKED -> coordinator decision`.
