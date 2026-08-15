# RPG OS — CHAT-7 TEMP GM Integration Contract

Status: **FINAL BACKEND CONTRACT / TEMP / NON-PRODUCTION**
Work item: `WORK-20260815-001`
Final backend handoff target: CHAT-6 Android integration

## 1. Scope and authority

This contract exists only for the TEMP local AI-GM harness. It does not define or implement canonical `AiProvider`, canonical Structured GM Output, Mechanics Resolution, Progression, Time Skip, World Simulation, or any Phase-20+ production AI surface.

RPG OS remains authoritative. The TEMP provider, Context Builder, bridge, bug harness and llama.cpp runtime are non-authoritative. A TEMP failure must not mutate Save, DB, PlayerState, authoritative events, PlayerChangeSet, StatePatch or COMMIT state.

Every TEMP GM or bug-harness response exposed to the client must preserve:

`canonicalMutation = false`

## 2. Final model/provider baseline

Logical provider ID:

`BIELIK_4_5B_V3`

Provider profile:

- Bielik 4.5B v3 Instruct
- GGUF / Q4_K_M
- llama.cpp / Vulkan
- CTX 8192
- KV K/V f16/f16
- batch 64
- ubatch 64
- parallel 1
- GPU layers 99
- tested runtime flag: `GGML_VK_DISABLE_OCP_FP4=1`

The bridge exposes only the logical provider ID and safe metadata. Android/UI code must not know the GGUF path, llama.cpp executable path, CLI arguments or model filesystem layout.

## 3. Provider status contract

Stable provider/UI status enum:

- `OFFLINE` — localhost runtime cannot currently be reached.
- `STARTING` — transient launcher/UI state while the runtime is being started and health has not reached READY yet.
- `READY` — provider health succeeds and GM turns may be attempted.
- `ERROR` — provider/runtime answered but reported an invalid/error state.

`STARTING` is a presentation/integration state used before bridge/provider health resolves. Once the bridge is reachable, the current `LocalBielikTempGmProvider.status()` emits `READY`, `OFFLINE` or `ERROR`.

No separate `UNAVAILABLE` provider state is required in the final backend contract. Missing optional diagnostics such as ADB/logcat have their own explicit availability states and must not be conflated with provider status.

## 4. Localhost boundaries

TEMP bridge:

`http://127.0.0.1:8765`

llama.cpp runtime:

`http://127.0.0.1:8768`

Neither service may bind to `0.0.0.0` in this TEMP contract.

The Android frontend communicates with the bridge, not directly with llama.cpp.

## 5. Final bridge endpoint surface

Core TEMP endpoints:

- `GET /health`
- `GET /providers`
- `GET /active-provider`
- `POST /active-provider`
- `POST /gm/turn`
- `POST /bug`

Bug UI support endpoints added only because CHAT-6 needs local pending/preview/confirmation state:

- `GET /bug/pending`
- `POST /bug/control`

No other endpoint is required for this handoff.

The bridge contains **no GitHub write capability**. Explicit user confirmation changes only local authorization state. A separate privileged/user-authorized submission action must perform any actual GitHub write.

## 6. GET /health

Example response:

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

Bridge unreachable is represented by connection failure on the client; it is not converted into campaign-state failure.

## 7. GET /providers

Returns logical provider metadata only.

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

Filesystem/model path and CLI details are intentionally absent.

## 8. GET /active-provider and POST /active-provider

GET response:

```json
{
  "activeProvider": "BIELIK_4_5B_V3",
  "provider": {
    "id": "BIELIK_4_5B_V3",
    "status": "READY"
  }
}
```

POST request:

```json
{
  "providerId": "BIELIK_4_5B_V3"
}
```

Unknown logical provider ID returns `400 unknown_provider`.

The current final device baseline has one selected provider. The selector contract remains logical-ID based so CHAT-6 does not depend on runtime internals.

## 9. TEMP response modes

Allowed modes:

- `NARRATIVE_ONLY`
- `ENGINE_CONFIRMED`
- `TEST_FALLBACK`

`NARRATIVE_ONLY` is normal TEMP narration.

`ENGINE_CONFIRMED` may narrate only engine-confirmed information explicitly provided in the TEMP request envelope. It does not give the model mutation authority.

`TEST_FALLBACK` is degraded/test narration. It is also non-authoritative.

Unknown requested modes normalize to `NARRATIVE_ONLY`.

## 10. POST /gm/turn request

```json
{
  "message": "player declaration",
  "mode": "NARRATIVE_ONLY",
  "maxTokens": 1024,
  "context": {
    "campaignUid": "...",
    "worldPackUid": "...",
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

The frontend does not send unrestricted global World State to the model. The TEMP Context Builder receives a bounded request envelope.

## 11. POST /gm/turn response

Success:

```json
{
  "providerId": "BIELIK_4_5B_V3",
  "mode": "NARRATIVE_ONLY",
  "narrative": "...",
  "canonicalMutation": false,
  "usage": {}
}
```

Provider offline/degraded example:

```json
{
  "error": "provider_offline",
  "providerId": "BIELIK_4_5B_V3",
  "mode": "TEST_FALLBACK",
  "canonicalMutation": false
}
```

The response never contains authoritative `StatePatch`, `PlayerChangeSet`, COMMIT instruction or authoritative event.

## 12. Context Builder / NPC knowledge boundary

The Context Builder targets native CTX=8192 and accepts only bounded TEMP scene information.

Each relevant NPC is shaped as:

```json
{
  "npcUid": "...",
  "sceneFacts": {},
  "knowledge": {
    "observed": [],
    "heard": [],
    "told": [],
    "inferred": []
  }
}
```

Global canonical state may help the RPG OS prepare context, but it must never automatically become an NPC's knowledge.

### CTX planning budget

| Segment | Tokens |
|---|---:|
| TEMP system/authority contract | 900 |
| Scene state | 1100 |
| Player state needed in scene | 700 |
| Relevant NPC scene state | 700 |
| NPC knowledge | 1000 |
| Recent dialogue/actions | 1800 |
| Retrieved Chronicle/Memory | 700 |
| Serialization reserve | 268 |
| Model response reserve | 1024 |
| **Total** | **8192** |

## 13. POST /bug request

Minimal request:

```json
{
  "description": "Po kliknięciu Kontynuuj nic się nie dzieje.",
  "include_logcat": true,
  "include_screenshot": false,
  "screenshotApproved": false
}
```

The real bundle may additionally include safe build identity, campaign/worldpack identity, route, response mode, ADB state, bounded recent actions, bounded recent TEMP responses, reproduction data, exception class/top stack frames and stable environment identifiers.

The original user report is stored as USER-SUPPLIED, except secret-redaction may replace detected credentials/tokens. The bundle records whether this happened.

## 14. POST /bug response

```json
{
  "reportUid": "bug-...",
  "captureStatus": {
    "localBundle": "SAVED",
    "logcat": "CAPTURED",
    "screenshot": "NOT_CAPTURED"
  },
  "duplicateFingerprint": "...",
  "submissionState": "LOCAL_PENDING",
  "githubSubmissionAuthorized": false,
  "canonicalMutation": false,
  "errors": []
}
```

Failure of optional evidence capture does not discard the USER-SUPPLIED report.

## 15. Bug evidence classes

Every bug bundle keeps three explicit classes:

- `USER-SUPPLIED`
- `DEVICE-CAPTURED`
- `AI-SUMMARIZED`

`AI-SUMMARIZED.isEvidence = false`.

AI summary is not fingerprint identity authority and is not technical evidence by itself.

## 16. Submission states

Stable local submission states:

- `LOCAL_PENDING`
- `READY`
- `SUBMITTED`
- `LINKED_DUPLICATE`
- `CANCELLED`

`READY` means the user has locally authorized a specific next submission action. It does **not** mean a GitHub write already occurred.

## 17. GET /bug/pending

Returns only local actionable pending/ready reports, newest first.

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

Queue is bounded and persistent on local storage, so pending reports survive bridge restart.

## 18. POST /bug/control

This endpoint is local control only. It never writes GitHub.

Request envelope:

```json
{
  "reportUid": "bug-...",
  "action": "PREVIEW"
}
```

Allowed actions:

- `PREVIEW` — returns the sanitized bundle and rendered issue preview.
- `SET_DUPLICATES` — records externally searched duplicate candidates; does not authorize submission.
- `CONFIRM_NEW_ISSUE` — explicit user decision; local state becomes `READY` / `submissionKind=NEW_ISSUE`.
- `CONFIRM_LINK_DUPLICATE` — explicit user decision selecting one recorded candidate; local state becomes `READY` / `submissionKind=LINK_DUPLICATE`; it cannot be consumed as new-issue authorization.
- `KEEP_PENDING` — returns the report to `LOCAL_PENDING`.
- `CANCEL` — sets `CANCELLED` and removes local submission authorization.

Example duplicate update:

```json
{
  "reportUid": "bug-...",
  "action": "SET_DUPLICATES",
  "candidates": [
    {
      "issueNumber": 123,
      "title": "similar issue",
      "url": "...",
      "fingerprint": "..."
    }
  ]
}
```

Example explicit confirmation for a new Issue:

```json
{
  "reportUid": "bug-...",
  "action": "CONFIRM_NEW_ISSUE"
}
```

Example duplicate-link authorization:

```json
{
  "reportUid": "bug-...",
  "action": "CONFIRM_LINK_DUPLICATE",
  "targetIssueNumber": 123
}
```

Every response contains:

- current report;
- compact summary;
- sanitized Issue preview;
- `githubWritePerformed: false`;
- `canonicalMutation: false`.

Actual GitHub submission/update is intentionally outside this bridge endpoint and must occur only through a separate privileged action after reading the explicit local authorization state.

## 19. Screenshot consent contract

Screenshot capture is optional.

The UI must distinguish:

- requested/not requested;
- `USER_APPROVED = YES/NO`;
- reference present/absent.

No screenshot may be automatically captured merely because `/bug` was invoked.

If `include_screenshot=true` but `screenshotApproved=false`, no screenshot reference is accepted into the bundle.

## 20. Logcat contract

Stable UI availability states:

- `CAPTURED` / caller-supplied bounded equivalent — bounded excerpt exists.
- `UNAVAILABLE` — optional capture could not be obtained.
- `NOT_REQUESTED` — user/test flow did not request capture.
- `SKIPPED` — package identity was not provided.

The current Galaxy S24 validation produced `LOGCAT_STATUS=UNAVAILABLE` because package PID was unavailable in the controlled test. This is not a blocker.

Capture remains bounded to at most 300 lines / 30,000 characters with a short timeout. There is no continuous background log recording.

## 21. Degraded-state contract

### TEMP GM offline

- provider status `OFFLINE`;
- `/gm/turn` fails/degrades without canonical mutation;
- `/bug` remains usable because bug capture is independent of model generation.

### Bridge offline

- Android UI shows bridge unavailable;
- canonical RPG OS continues independently;
- previously stored LOCAL_PENDING reports remain on disk and recover after bridge restart.

### llama.cpp unavailable

Equivalent to provider `OFFLINE`; no canonical state mutation.

### ADB unavailable

Bug bundle persists; `adbState`/logcat evidence indicates unavailable.

### logcat unavailable

Bug bundle persists with `logcat.status=UNAVAILABLE` and a bounded reason code.

### Internet unavailable

Report remains `LOCAL_PENDING`. No user report is discarded.

### GitHub unavailable

No publication is attempted by the bridge. Local pending/ready state persists; retry requires a later explicit/privileged submission flow.

In all degraded cases canonical RPG OS state is unchanged.

## 22. Duplicate fingerprint contract

Fingerprint is deterministic and excludes timestamp, random report UID and AI summary.

Identity inputs include stable technical symptom data such as app version, route, normalized exception class/top stack frames, normalized user symptom and selected stable environment identifiers.

Duplicate search itself never creates or updates an Issue.

## 23. Authority/security invariants

The TEMP bridge/provider/bug harness must never:

- write Save or canonical DB;
- execute PlayerChangeSet;
- execute StatePatch;
- execute COMMIT;
- create authoritative events;
- bypass PlayerDomainEngine, reference validation, WorldRuleProvider or transaction/validation layers;
- automatically create/update a GitHub Issue;
- treat AI summary as evidence;
- expose secrets, tokens or unrestricted environment dumps;
- expose model filesystem paths to the frontend.

## 24. Device evidence baseline

Validated on Samsung SM-S921B / Android 16 / SDK 36.

Minimal TEMP GM vertical slice: PASS.

TEMP bug harness device validation: PASS.

Latest recorded bug device evidence before final handoff:

`temp-gm/results/device/2026-08-15_18-48-08-bielik45-temp-bug-harness-device-pass/`

Key observed result:

- BUG_01..BUG_20: PASS
- POST /bug: PASS
- LOCAL_PENDING: PASS
- pending recovery after restart: PASS
- screenshot consent: PASS
- autonomous issue creation: NO
- canonicalMutation: false
- logcat: UNAVAILABLE / non-blocking
- TEST_STATUS: PASS

## 25. Handoff rule

CHAT-6 owns Android Developer Settings/presentation/status integration, TEMP provider selector presentation, `/bug` UI, preview/confirmation UX, TEST APK and release/publication.

CHAT-7 backend does not implement Android UI and does not publish APK/release.
