# CHAT-6 TEMP GM Android UI integration evidence

Date: 2026-08-15
Role: CHAT-6
Work item: `WORK-20260815-001`
Master baseline: `0a7a06cc66a6328f49bc086a41b36dbfa4528143`
CHAT-7 handoff: `305e5b71ea1bb9a7d32f30ff965fd23905821734`
Implementation branch: `chat6-temp-gm-android-ui`
Validated implementation SHA: `ba66fac544e2e3d4893a5ba7c416d49dbad70de1`
Publication: **NO**

## Contract consumed

Authority: `temp-gm/docs/CHAT7_TO_CHAT6_TEMP_GM_UI_HANDOFF.md`, `temp_gm_bridge.py`, `temp_bug_ui_contract.py` and `test_bug_bridge_lifecycle.py` at exact CHAT-7 handoff SHA.

Android talks only to the TEMP bridge at `http://127.0.0.1:8765`. The presentation exposes logical provider `BIELIK_4_5B_V3` / Bielik 4.5B v3 and live `OFFLINE | STARTING | READY | ERROR` state. The Android client contains no GGUF path, llama.cpp CLI/path, GitHub credential, direct GitHub Issue writer, arbitrary filesystem access or canonical mutation path.

## Implemented Android surfaces

- Settings -> developer TEMP LOCAL GM entry, collapsed by default.
- Live bridge/provider health and degraded OFFLINE presentation.
- TEMP / NON-AUTHORITATIVE `/gm/turn` test interaction; narration only.
- Fail-closed rejection when a TEMP response violates `canonicalMutation=false`.
- `/bug` local report capture with LOCAL_PENDING presentation.
- Per-report logcat opt-in and explicit screenshot request/consent UI; no hidden screenshot capture.
- Bridge-owned pending list/count, detail and issue preview.
- Duplicate candidate presentation and explicit `CONFIRM_LINK_DUPLICATE` selection.
- Exact user decisions: `KEEP_PENDING`, `CANCEL`, `CONFIRM_NEW_ISSUE`, `CONFIRM_LINK_DUPLICATE`.
- Retry/reopen and explicit confirmed local delete.
- Safe user-facing handling for HTTP 400, 404, 409 and 507.
- One-shot authorization transport exists for the privileged submission boundary; Android itself does not consume authorization to publish and does not call GitHub.

## Canonical isolation

No `PlayerDomainEngine`, `WorldRuleProvider`, `CanonicalPackageAuthorityGate`, canonical DB, Save, `StatePatch`, `PlayerChangeSet`, COMMIT, authoritative event or Phase-19 runtime file was modified. No Phase-20+ runtime was implemented. TEMP UI/client has no canonical write path.

Network cleartext remains disabled globally; the Android network-security exception is limited to loopback `127.0.0.1` / `localhost` for the TEMP bridge.

## Automated validation

Workflow: `.github/workflows/chat6-temp-gm-validation.yml`
Run: `#8`, ID `31899247182`
Validated head: `ba66fac544e2e3d4893a5ba7c416d49dbad70de1`
Conclusion: **SUCCESS**

Gates:
- full `:app:testDebugUnitTest`: PASS
- TEMP contract tests: `UI_GM_01..05` + `UI_BUG_01..16` (21 named scenarios included in the full JVM suite)
- `:app:assembleDebug`: PASS
- evidence preparation: PASS
- artifact upload: PASS

TEST APK artifact:
- artifact ID: `9250669001`
- artifact name: `CHAT6-TEMP-GM-TEST-ba66fac544e2e3d4893a5ba7c416d49dbad70de1`
- artifact archive digest: `sha256:a020c06cf8298f37b05973bae14a860a2a6dcc243d1bbb474706c87d28872b62`
- archive includes `RPG-OS-TEMP-GM-TEST.apk`, its SHA-256 file and `build-provenance.json` with `publication:false`.

## Device validation

Not run by CHAT-6 in the current execution environment because no physical/ADB device connection is exposed to this session. The APK is prepared for device validation. Existing CHAT-7 device evidence validates the backend/bridge harness, not this new Android presentation layer.

## Verdict

Android TEMP GM / Bug Harness integration is **READY FOR DEVICE/INTEGRATION TEST**. No release was published. `PUBLISHED = NO`.
