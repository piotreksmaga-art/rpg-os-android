#!/data/data/com.termux/files/usr/bin/bash
set -u

ROOT="$HOME/rpgos-temp-gm"
SRC="${TGM_SRC:-$ROOT/chat7-harness-src}"
BRIDGE_DIR="$SRC/temp-gm/bridge"
SERVER="$ROOT/llama.cpp/build-vulkan-q4safe/bin/llama-server"
MODEL="$ROOT/models/bielik45/Bielik-4.5B-v3.0-Instruct-Q4_K_M.gguf"
RESULT="$ROOT/results/bielik45-temp-bug-harness-device"
DATA="$RESULT/bridge-data"
LOG_LLAMA="$RESULT/llama-server.log"
LOG_BRIDGE="$RESULT/bridge.log"
SUMMARY="$RESULT/summary.txt"
REQ="$RESULT/bug-request.json"
RESP="$RESULT/bug-response.json"
PREVIEW="$RESULT/issue-preview.txt"
RECOVERY="$RESULT/recovery.txt"
UNIT="$RESULT/bug-unit-tests.txt"
PKG="com.rpgos.app"

mkdir -p "$RESULT" "$DATA"
rm -f "$LOG_LLAMA" "$LOG_BRIDGE" "$SUMMARY" "$REQ" "$RESP" "$PREVIEW" "$RECOVERY" "$UNIT"
rm -rf "$DATA/pending-bugs"

LLAMA_PID=""; BRIDGE_PID=""
cleanup(){
  [ -n "${BRIDGE_PID:-}" ] && kill "$BRIDGE_PID" 2>/dev/null || true
  [ -n "${LLAMA_PID:-}" ] && kill "$LLAMA_PID" 2>/dev/null || true
  [ -n "${BRIDGE_PID:-}" ] && wait "$BRIDGE_PID" 2>/dev/null || true
  [ -n "${LLAMA_PID:-}" ] && wait "$LLAMA_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

fail(){ echo "TEST_STATUS=FAIL:$1" | tee -a "$SUMMARY"; exit 10; }

{
 echo "===== CHAT-7 TEMP BUG HARNESS DEVICE TEST ====="
 date
 echo "WORK_ITEM=WORK-20260815-001"
 echo "PACKAGE=$PKG"
 echo "PROVIDER=BIELIK_4_5B_V3"
 echo "BRIDGE=127.0.0.1:8765"
 echo "LLAMA=127.0.0.1:8768"
 echo "ISSUE_PUBLICATION=FORBIDDEN_IN_THIS_TEST"
 echo
 free -h || true
} > "$SUMMARY"

cd "$BRIDGE_DIR" || fail "bridge_dir_missing"
python -m unittest -v test_temp_bug_harness.py >"$UNIT" 2>&1 || { cat "$UNIT" >> "$SUMMARY"; fail "BUG_01_20_unit_tests"; }
echo "BUG_01_20_AUTOMATED=PASS" | tee -a "$SUMMARY"

[ -x "$SERVER" ] || fail "llama_server_missing"
[ -f "$MODEL" ] || fail "bielik_model_missing"

GGML_VK_DISABLE_OCP_FP4=1 "$SERVER" -m "$MODEL" -ngl 99 -c 8192 -np 1 -b 64 -ub 64 -ctk f16 -ctv f16 --cache-prompt --reasoning off --host 127.0.0.1 --port 8768 >"$LOG_LLAMA" 2>&1 &
LLAMA_PID=$!
READY=0
for i in $(seq 1 60); do
  curl -fsS --max-time 1 http://127.0.0.1:8768/health >/dev/null 2>&1 && { READY=1; echo "LLAMA_READY_SECONDS=$i" >> "$SUMMARY"; break; }
  kill -0 "$LLAMA_PID" 2>/dev/null || break
  sleep 1
done
[ "$READY" -eq 1 ] || fail "llama_not_ready"

TGM_DATA_DIR="$DATA" TGM_BIELIK_URL="http://127.0.0.1:8768" python "$BRIDGE_DIR/temp_gm_bridge.py" >"$LOG_BRIDGE" 2>&1 &
BRIDGE_PID=$!
BREADY=0
for i in $(seq 1 20); do
  curl -fsS --max-time 1 http://127.0.0.1:8765/health >/dev/null 2>&1 && { BREADY=1; echo "BRIDGE_READY_SECONDS=$i" >> "$SUMMARY"; break; }
  kill -0 "$BRIDGE_PID" 2>/dev/null || break
  sleep 1
done
[ "$BREADY" -eq 1 ] || fail "bridge_not_ready"

# One harmless TEMP GM interaction before the bug capture.
cat > "$RESULT/gm-request.json" <<'JSON'
{"message":"Test urządzenia: odpowiedz jednym krótkim zdaniem, że TEMP GM działa.","mode":"NARRATIVE_ONLY","maxTokens":48,"context":{"campaignUid":"bug-device-test","worldPackUid":"bug-device-world","playerIdentity":{"name":"Smagi"},"sceneState":{"location":"test"},"playerSceneState":{},"relevantNpcs":[],"recentDialogueActions":[],"retrievedChronicleMemory":[],"availableTestCapabilities":["narrative"],"engineConfirmedResults":[]}}
JSON
curl -fsS --max-time 45 -H 'Content-Type: application/json' --data-binary @"$RESULT/gm-request.json" http://127.0.0.1:8765/gm/turn > "$RESULT/gm-response.json" || fail "gm_preinteraction"
python - "$RESULT/gm-response.json" <<'PY' >> "$SUMMARY" || exit 10
import json,sys
x=json.load(open(sys.argv[1],encoding='utf-8'))
assert x.get('canonicalMutation') is False
print('GM_PREINTERACTION=PASS')
PY

ADB_STATE="UNAVAILABLE"
VERSION_NAME="UNKNOWN"; VERSION_CODE="UNKNOWN"
if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
  ADB_STATE="READY"
  PKG_INFO="$(adb shell dumpsys package "$PKG" 2>/dev/null || true)"
  VERSION_NAME="$(printf '%s\n' "$PKG_INFO" | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')"
  VERSION_CODE="$(printf '%s\n' "$PKG_INFO" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1 | tr -d '\r')"
  [ -n "$VERSION_NAME" ] || VERSION_NAME="UNKNOWN"
  [ -n "$VERSION_CODE" ] || VERSION_CODE="UNKNOWN"
fi

cat > "$REQ" <<JSON
{
  "description": "TEST BUG HARNESS: po naciśnięciu testowego przycisku oczekuję podglądu zgłoszenia; nie publikuj Issue.",
  "include_logcat": true,
  "include_screenshot": false,
  "screenshotApproved": false,
  "packageName": "$PKG",
  "build": {"versionName": "$VERSION_NAME", "versionCode": "$VERSION_CODE", "buildSha": "DEVICE_TEST"},
  "campaignUid": "bug-device-test",
  "worldPackUid": "bug-device-world",
  "route": "TEMP_BUG_TEST",
  "responseMode": "NARRATIVE_ONLY",
  "adbStatus": "$ADB_STATE",
  "recentSafeActions": ["open test screen", "send one TEMP GM turn", "invoke /bug"],
  "recentGmResponses": ["TEMP GM preinteraction completed"],
  "expected": "Powstaje lokalny, nieopublikowany bundle.",
  "actual": "Kontrolowany test capture.",
  "reproductionStatus": "CONTROLLED_TEST",
  "reproducibilityNotes": "Nieszkodliwy test infrastruktury; nie jest production failure.",
  "exceptionClass": "TestOnlySymptom",
  "topStackFrames": ["TempBugHarnessTest.kt:123"],
  "environment": {"deviceModel": "SM-S921B", "androidSdk": 36},
  "aiSummary": "Kontrolowany test harnessu; AI summary nie jest dowodem."
}
JSON

curl -fsS --max-time 20 -H 'Content-Type: application/json' --data-binary @"$REQ" http://127.0.0.1:8765/bug > "$RESP" || fail "post_bug"
REPORT_UID="$(python - "$RESP" <<'PY'
import json,sys
x=json.load(open(sys.argv[1],encoding='utf-8'))
assert x['submissionState']=='LOCAL_PENDING'
assert x['githubSubmissionAuthorized'] is False
assert x['canonicalMutation'] is False
print(x['reportUid'])
PY
)" || fail "bug_response_contract"
[ -n "$REPORT_UID" ] || fail "missing_report_uid"
echo "POST_BUG=PASS" >> "$SUMMARY"
echo "REPORT_UID=$REPORT_UID" >> "$SUMMARY"

# Restart bridge, then load the same report directly from the recovered queue.
kill "$BRIDGE_PID" 2>/dev/null || true; wait "$BRIDGE_PID" 2>/dev/null || true; BRIDGE_PID=""
TGM_DATA_DIR="$DATA" TGM_BIELIK_URL="http://127.0.0.1:8768" python "$BRIDGE_DIR/temp_gm_bridge.py" >>"$LOG_BRIDGE" 2>&1 &
BRIDGE_PID=$!
sleep 2
curl -fsS --max-time 2 http://127.0.0.1:8765/health >/dev/null || fail "bridge_restart"
python - "$DATA" "$REPORT_UID" "$PREVIEW" > "$RECOVERY" <<'PY'
import sys
from pathlib import Path
from temp_bug_harness import BugReportStore,prepare_issue_preview
store=BugReportStore(Path(sys.argv[1])); r=store.load(sys.argv[2])
assert r['submissionState']=='LOCAL_PENDING'
assert r['github']['submissionAuthorized'] is False
assert r['DEVICE-CAPTURED']['screenshot']['userApproved'] is False
assert r['canonicalMutation'] is False
Path(sys.argv[3]).write_text(prepare_issue_preview(r),encoding='utf-8')
print('PENDING_RECOVERY=PASS')
print('SCREENSHOT_CONSENT=PASS')
print('AUTONOMOUS_ISSUE_CREATION=NO')
print('FINGERPRINT='+r['duplicateFingerprint'])
print('LOGCAT_STATUS='+r['DEVICE-CAPTURED']['logcat']['status'])
print('LOGCAT_LINES='+str(len(r['DEVICE-CAPTURED']['logcat']['excerpt'].splitlines())))
PY
cat "$RECOVERY" >> "$SUMMARY"

grep -q '^PENDING_RECOVERY=PASS' "$RECOVERY" || fail "pending_recovery"
grep -q '^AUTONOMOUS_ISSUE_CREATION=NO' "$RECOVERY" || fail "autonomous_issue_gate"

{
 echo "DEVICE_CAPTURE=PASS"
 echo "DUPLICATE_SEARCH=CHAT7_GITHUB_POST_CAPTURE"
 echo "ISSUE_CREATED=NO"
 echo "TEST_STATUS=PASS"
 echo
 echo "===== MEMORY WITH RUNTIME ====="
 free -h || true
} >> "$SUMMARY"

cleanup; LLAMA_PID=""; BRIDGE_PID=""

if [ -x "$HOME/chat7_publish_evidence.sh" ]; then
  "$HOME/chat7_publish_evidence.sh" "$RESULT" "bielik45-temp-bug-harness-device-pass"
else
  echo "PUBLISHER_MISSING=YES" | tee -a "$SUMMARY"
fi

echo
cat "$SUMMARY"
echo
echo "BUG HARNESS DEVICE TEST FINISHED"
