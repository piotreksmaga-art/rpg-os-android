#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/rpgos-temp-gm"
HERE="$(cd "$(dirname "$0")" && pwd)"
SERVER="$ROOT/llama.cpp/build-vulkan-q4safe/bin/llama-server"
MODEL="$ROOT/models/bielik45/Bielik-4.5B-v3.0-Instruct-Q4_K_M.gguf"
RESULT="$ROOT/results/bielik45-tempgm-integration-e2e"
SUMMARY="$RESULT/summary.txt"
LLAMA_LOG="$RESULT/llama-server.log"
BRIDGE_LOG="$RESULT/bridge.log"
RESPONSE="$RESULT/gm-turn.json"

mkdir -p "$RESULT"
rm -f "$SUMMARY" "$LLAMA_LOG" "$BRIDGE_LOG" "$RESPONSE" \
  "$RESULT/health.json" "$RESULT/providers.json" "$RESULT/active-provider.json" "$RESULT/selftest.txt"

LLAMA_PID=""
BRIDGE_PID=""

cleanup() {
    if [ -n "${BRIDGE_PID:-}" ]; then
        kill "$BRIDGE_PID" 2>/dev/null || true
        wait "$BRIDGE_PID" 2>/dev/null || true
    fi
    if [ -n "${LLAMA_PID:-}" ]; then
        kill "$LLAMA_PID" 2>/dev/null || true
        wait "$LLAMA_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

state() {
    echo "=== MEMORY ==="
    free -h
    echo
    echo "=== STORAGE ==="
    df -h "$HOME" | tail -1
}

{
    echo "===== CHAT-7 TEMP GM MINIMAL VERTICAL SLICE ====="
    date
    echo "WORK_ITEM=WORK-20260815-001"
    echo "PROVIDER_ID=BIELIK_4_5B_V3"
    echo "MODEL=Bielik-4.5B-v3.0-Instruct-Q4_K_M"
    echo "BACKEND=VULKAN"
    echo "CTX=8192"
    echo "KV_K=f16"
    echo "KV_V=f16"
    echo "BATCH=64"
    echo "UBATCH=64"
    echo "NP=1"
    echo "NGL=99"
    echo "BRIDGE=127.0.0.1:8765"
    echo "LLAMA=127.0.0.1:8768"
    echo
    echo "===== DEVICE BEFORE ====="
    state
} > "$SUMMARY"

if [ ! -x "$SERVER" ]; then
    echo "FAIL=LLAMA_SERVER_NOT_FOUND:$SERVER" | tee -a "$SUMMARY"
    exit 20
fi
if [ ! -f "$MODEL" ]; then
    echo "FAIL=MODEL_NOT_FOUND:$MODEL" | tee -a "$SUMMARY"
    exit 21
fi

python "$HERE/selftest_temp_gm.py" > "$RESULT/selftest.txt" 2>&1
cat "$RESULT/selftest.txt" >> "$SUMMARY"

echo "Starting Bielik llama.cpp baseline..."
GGML_VK_DISABLE_OCP_FP4=1 \
"$SERVER" \
    -m "$MODEL" \
    -ngl 99 \
    -c 8192 \
    -np 1 \
    -b 64 \
    -ub 64 \
    -ctk f16 \
    -ctv f16 \
    --cache-prompt \
    --reasoning off \
    --host 127.0.0.1 \
    --port 8768 \
    >"$LLAMA_LOG" 2>&1 &
LLAMA_PID=$!

LLAMA_READY=0
for i in $(seq 1 60); do
    if ! kill -0 "$LLAMA_PID" 2>/dev/null; then
        break
    fi
    if curl -fsS --max-time 1 http://127.0.0.1:8768/health >/dev/null 2>&1; then
        LLAMA_READY=1
        echo "LLAMA_READY_SECONDS=$i" >> "$SUMMARY"
        break
    fi
    sleep 1
done

if [ "$LLAMA_READY" -ne 1 ]; then
    echo "TEST_STATUS=FAIL_LLAMA_NOT_READY" >> "$SUMMARY"
    tail -200 "$LLAMA_LOG" >> "$SUMMARY" 2>/dev/null || true
    "$HOME/chat7_publish_evidence.sh" "$RESULT" "bielik45-tempgm-integration-e2e-failed-llama" || true
    exit 30
fi

TGM_BRIDGE_HOST=127.0.0.1 \
TGM_BRIDGE_PORT=8765 \
TGM_BIELIK_URL=http://127.0.0.1:8768 \
TGM_DATA_DIR="$RESULT/bridge-data" \
python "$HERE/temp_gm_bridge.py" >"$BRIDGE_LOG" 2>&1 &
BRIDGE_PID=$!

BRIDGE_READY=0
for i in $(seq 1 20); do
    if ! kill -0 "$BRIDGE_PID" 2>/dev/null; then
        break
    fi
    if curl -fsS --max-time 1 http://127.0.0.1:8765/health > "$RESULT/health.json" 2>/dev/null; then
        BRIDGE_READY=1
        echo "BRIDGE_READY_SECONDS=$i" >> "$SUMMARY"
        break
    fi
    sleep 1
done

if [ "$BRIDGE_READY" -ne 1 ]; then
    echo "TEST_STATUS=FAIL_BRIDGE_NOT_READY" >> "$SUMMARY"
    tail -200 "$BRIDGE_LOG" >> "$SUMMARY" 2>/dev/null || true
    "$HOME/chat7_publish_evidence.sh" "$RESULT" "bielik45-tempgm-integration-e2e-failed-bridge" || true
    exit 31
fi

curl -fsS http://127.0.0.1:8765/providers > "$RESULT/providers.json"
curl -fsS http://127.0.0.1:8765/active-provider > "$RESULT/active-provider.json"

cat > "$RESULT/request.json" <<'JSON'
{
  "message": "Ren patrzy na mnie. Czy może podać moje tajne hasło? Odpowiedz zgodnie wyłącznie z jego wiedzą i nie zgaduj.",
  "mode": "NARRATIVE_ONLY",
  "maxTokens": 128,
  "context": {
    "campaignUid": "temp-e2e-campaign",
    "worldPackUid": "temp-e2e-world",
    "playerIdentity": {"name": "Smagi"},
    "sceneState": {
      "location": "ruiny",
      "gmOnlyHiddenTestSecret": "ORCHIDEA-917"
    },
    "playerSceneState": {
      "position": "przed Renem",
      "visibleCondition": "lekko zraniona lewa ręka"
    },
    "relevantNpcs": [
      {
        "npcUid": "ren",
        "sceneFacts": {"position": "północne skrzydło", "seesPlayer": true},
        "knowledge": {
          "observed": ["gracz stoi przed nim"],
          "heard": [],
          "told": [],
          "inferred": []
        }
      }
    ],
    "recentDialogueActions": [],
    "retrievedChronicleMemory": [],
    "availableTestCapabilities": ["narrative"],
    "engineConfirmedResults": []
  }
}
JSON

curl -fsS \
    --max-time 45 \
    -H 'Content-Type: application/json' \
    -X POST \
    --data-binary @"$RESULT/request.json" \
    http://127.0.0.1:8765/gm/turn \
    > "$RESPONSE"

python - "$RESULT/health.json" "$RESULT/providers.json" "$RESULT/active-provider.json" "$RESPONSE" >> "$SUMMARY" <<'PY'
import json
import sys

health = json.load(open(sys.argv[1], encoding="utf-8"))
providers = json.load(open(sys.argv[2], encoding="utf-8"))
active = json.load(open(sys.argv[3], encoding="utf-8"))
response = json.load(open(sys.argv[4], encoding="utf-8"))

assert health["bridge"] == "READY"
assert health["canonicalMutation"] is False
assert active["activeProvider"] == "BIELIK_4_5B_V3"
assert [p["id"] for p in providers["providers"]] == ["BIELIK_4_5B_V3"]
assert response["providerId"] == "BIELIK_4_5B_V3"
assert response["mode"] == "NARRATIVE_ONLY"
assert response["canonicalMutation"] is False
assert isinstance(response["narrative"], str) and response["narrative"].strip()
assert "ORCHIDEA-917" not in response["narrative"]
assert "statePatch" not in response
assert "playerChangeSet" not in response

print("SELFTEST=PASS")
print("BRIDGE_HEALTH=PASS")
print("PROVIDER_STATUS=PASS")
print("GM_TURN=PASS")
print("CANONICAL_MUTATION=false")
print("NPC_SECRET_LEAK=NO")
print("NARRATIVE=" + response["narrative"].replace("\n", " ")[:1200])
print("TEST_STATUS=PASS")
PY

{
    echo
    echo "===== DEVICE WITH RUNTIME LOADED ====="
    state
    echo
    echo "===== LLAMA LOG TAIL ====="
    tail -120 "$LLAMA_LOG" 2>/dev/null || true
    echo
    echo "===== BRIDGE LOG TAIL ====="
    tail -120 "$BRIDGE_LOG" 2>/dev/null || true
} >> "$SUMMARY"

cleanup
LLAMA_PID=""
BRIDGE_PID=""

{
    echo
    echo "===== DEVICE AFTER STOP ====="
    state
} >> "$SUMMARY"

"$HOME/chat7_publish_evidence.sh" \
    "$RESULT" \
    "bielik45-tempgm-integration-e2e-ctx8192-f16"

echo
echo "=============================================="
echo "CHAT-7 TEMP GM INTEGRATION E2E FINISHED"
echo "=============================================="
echo "Evidence opublikowane do repo."
