#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/rpgos-temp-gm"
SRC="$ROOT/chat7-harness-src"
HERE="$SRC/temp-gm/bridge"
EXPECTED_HEAD="ae29edd8c1158b08e18003748732b831bf86d5f0"
RESULT="$ROOT/results/chat7-live-semantic-retest3"
SERVER="$ROOT/llama.cpp/build-vulkan-q4safe/bin/llama-server"
MODEL="$ROOT/models/bielik45/Bielik-4.5B-v3.0-Instruct-Q4_K_M.gguf"
mkdir -p "$RESULT"
rm -f "$RESULT"/* 2>/dev/null || true

HEAD="$(git -C "$SRC" rev-parse HEAD)"
BRANCH="$(git -C "$SRC" branch --show-current 2>/dev/null || true)"

{
  echo "CHAT-7 TEMP GM LIVE SEMANTIC RETEST #3"
  echo "BRANCH=$BRANCH"
  echo "HEAD=$HEAD"
  echo "EXPECTED_HEAD=$EXPECTED_HEAD"
  echo "CURRENT_PROVIDER_TIMEOUT=180"
  echo "CURRENT_ANDROID_TIMEOUT=210"
  echo "CURRENT_MAX_TOKENS=1024"
  echo "MODEL=Bielik 4.5B v3 Q4_K_M"
  echo "CTX=8192"
  echo "KV=f16/f16"
  echo "BATCH=64"
  echo "UBATCH=64"
  echo "NP=1"
  echo "NGL=99"
} > "$RESULT/preflight.txt"

publish_fail() {
  local label="$1"
  "$HOME/chat7_publish_evidence.sh" "$RESULT" "$label" || true
}

if [ "$HEAD" != "$EXPECTED_HEAD" ]; then
  echo "PREFLIGHT=FAIL_HEAD_MISMATCH" | tee -a "$RESULT/preflight.txt"
  publish_fail "chat7-live-semantic-retest3-head-mismatch"
  exit 20
fi

test -x "$SERVER" || { echo "LLAMA_SERVER=NOT_FOUND" | tee -a "$RESULT/preflight.txt"; publish_fail "chat7-live-semantic-retest3-server-missing"; exit 21; }
test -f "$MODEL" || { echo "MODEL=NOT_FOUND" | tee -a "$RESULT/preflight.txt"; publish_fail "chat7-live-semantic-retest3-model-missing"; exit 22; }
test -x "$HOME/chat7_publish_evidence.sh" || { echo "PUBLISH_HELPER=NOT_FOUND" | tee -a "$RESULT/preflight.txt"; exit 23; }

termux-wake-lock 2>/dev/null || true

# Start the approved Bielik runtime because this runner must also work from a clean Termux session.
pkill -f 'llama-server.*--port 8768' 2>/dev/null || true
pkill -f 'temp_gm_bridge.py' 2>/dev/null || true
sleep 2

GGML_VK_DISABLE_OCP_FP4=1 \
nohup "$SERVER" \
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
  > "$RESULT/llama.log" 2>&1 &
LLAMA_PID=$!
echo "$LLAMA_PID" > "$RESULT/llama.pid"

LLAMA_READY=0
for i in $(seq 1 90); do
  if ! kill -0 "$LLAMA_PID" 2>/dev/null; then break; fi
  if curl -fsS --max-time 2 http://127.0.0.1:8768/health > "$RESULT/llama-health.json" 2>/dev/null; then
    LLAMA_READY=1
    echo "LLAMA_READY_SECONDS=$i" >> "$RESULT/preflight.txt"
    break
  fi
  sleep 1
done

if [ "$LLAMA_READY" -ne 1 ]; then
  echo "LLAMA=FAIL" | tee -a "$RESULT/preflight.txt"
  tail -160 "$RESULT/llama.log" >> "$RESULT/preflight.txt" 2>/dev/null || true
  publish_fail "chat7-live-semantic-retest3-llama-fail"
  exit 24
fi
echo "LLAMA=PASS" >> "$RESULT/preflight.txt"

TGM_BRIDGE_HOST=127.0.0.1 \
TGM_BRIDGE_PORT=8765 \
TGM_BIELIK_URL=http://127.0.0.1:8768 \
TGM_DATA_DIR="$ROOT/bridge-data" \
nohup python "$HERE/temp_gm_bridge.py" > "$RESULT/bridge.log" 2>&1 &
BRIDGE_PID=$!
echo "$BRIDGE_PID" > "$RESULT/bridge.pid"

BRIDGE_READY=0
for i in $(seq 1 30); do
  if ! kill -0 "$BRIDGE_PID" 2>/dev/null; then break; fi
  if curl -fsS --max-time 1 http://127.0.0.1:8765/health > "$RESULT/health.json" 2>/dev/null; then
    BRIDGE_READY=1
    echo "BRIDGE_READY_SECONDS=$i" >> "$RESULT/preflight.txt"
    break
  fi
  sleep 1
done

if [ "$BRIDGE_READY" -ne 1 ]; then
  echo "BRIDGE=FAIL" | tee -a "$RESULT/preflight.txt"
  tail -160 "$RESULT/bridge.log" >> "$RESULT/preflight.txt" 2>/dev/null || true
  publish_fail "chat7-live-semantic-retest3-bridge-fail"
  exit 25
fi
echo "BRIDGE=PASS" >> "$RESULT/preflight.txt"
curl -fsS http://127.0.0.1:8765/providers > "$RESULT/providers.json"
curl -fsS http://127.0.0.1:8765/active-provider > "$RESULT/active-provider.json"

python "$HERE/test_temp_gm_semantics.py" >/dev/null 2>&1 || {
  echo "STATIC_SEMANTIC_CONTRACT=FAIL" | tee -a "$RESULT/preflight.txt"
  publish_fail "chat7-live-semantic-retest3-static-contract-fail"
  exit 26
}
echo "STATIC_SEMANTIC_CONTRACT=10/10_PASS" >> "$RESULT/preflight.txt"

python - "$RESULT" <<'PY'
import json, socket, statistics, sys, time, urllib.error, urllib.request
from pathlib import Path

RESULT = Path(sys.argv[1])
CASES = [
    ("CASE_01_ACTION_DIRECTION", "Przede mną stoi wrogi shinobi.\nAtakuję go kataną.\nCeluję w jego prawą dłoń.\nOpisz reakcję wrogiego shinobi."),
    ("CASE_02_OBSERVATION_ONLY", "Stoję przed zamkniętymi drzwiami.\nObserwuję je i czekam.\nOpisz, co widzę i co dzieje się wokół mnie."),
    ("CASE_03_NPC_QUESTION", "Pytam strażnika:\n„Kto znajduje się po drugiej stronie bramy?”\nCzekam na jego odpowiedź."),
    ("CASE_04_FAILED_ATTEMPT", "Próbuję złapać uciekającego shinobi za rękaw.\nOpisz rezultat próby."),
    ("CASE_05_PLAYER_DOES_NOTHING", "Nic nie robię.\nObserwuję przeciwnika."),
    ("CASE_06_DECLARED_MOVEMENT", "Robię jeden krok do tyłu.\nNie atakuję.\nObserwuję przeciwnika."),
    ("CASE_07_PLAYER_SPEECH", "Mówię:\n„Nie chcę walczyć.”"),
    ("CASE_08_UNKNOWN_ABILITY", "Patrzę na dach budynku.\nNie używam żadnej techniki."),
    ("CASE_09_INTERNAL_CONTEXT", "Rozglądam się po dziedzińcu i pytam strażnika, czy zauważył coś niezwykłego. Potem czekam na odpowiedź."),
    ("CASE_10_STOP_POINT", "Atakuję bandytę pojedynczym cięciem miecza w lewe ramię."),
]

CTX = {
    "campaignUid": "semantic-retest3",
    "worldPackUid": "semantic-retest3",
    "playerIdentity": {"name": "Smagi"},
    "sceneState": {"location": "dziedziniec przy bramie"},
    "playerSceneState": {"position": "przed NPC"},
    "relevantNpcs": [{
        "npcUid": "enemy",
        "sceneFacts": {"hostile": True, "position": "przed graczem"},
        "knowledge": {"observed": ["gracz stoi przede mną"], "heard": [], "told": [], "inferred": []},
    }],
    "recentDialogueActions": [],
    "retrievedChronicleMemory": [],
    "availableTestCapabilities": ["narrative"],
    "engineConfirmedResults": [],
}

FORBIDDEN_FIELDS = {"statePatch", "StatePatch", "playerChangeSet", "PlayerChangeSet", "commit", "COMMIT", "authoritativeEvent", "authoritativeEvents"}
LEAK_TERMS = ["system_prompt", "system prompt", "read-only", "read_only", "internal context", "gm_sem", "test_failure", "test_fallback", "hidden instructions", "prompt rules", "implementation details", "canonicalmutation=false", "contextwindow", "engineconfirmedresults"]
ABILITY_TERMS = ["teleport", "shunshin", "technika chakry", "chakra technique", "lecę", "latam", "flight"]


def post(message):
    payload = {"message": message, "mode": "NARRATIVE_ONLY", "maxTokens": 1024, "context": CTX}
    req = urllib.request.Request(
        "http://127.0.0.1:8765/gm/turn",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=190) as response:
            raw = response.read().decode("utf-8")
            code = response.status
        return code, time.monotonic() - start, json.loads(raw), None
    except urllib.error.HTTPError as exc:
        try:
            detail = exc.read().decode("utf-8", errors="replace")
        except Exception:
            detail = ""
        return exc.code, time.monotonic() - start, None, f"HTTPError: {exc}; body={detail[:500]}"
    except Exception as exc:
        return None, time.monotonic() - start, None, type(exc).__name__ + ": " + str(exc)


records = []
for idx, (name, declaration) in enumerate(CASES, 1):
    code, elapsed, body, error = post(declaration)
    rec = {
        "case": idx,
        "name": name,
        "playerDeclaration": declaration,
        "http": code,
        "elapsedSeconds": elapsed,
        "error": error,
    }
    if body is not None:
        narrative = str(body.get("narrative", ""))
        usage = body.get("usage") or {}
        completion = usage.get("completion_tokens")
        rec.update({
            "modelOutput": narrative,
            "providerId": body.get("providerId"),
            "canonicalMutation": body.get("canonicalMutation"),
            "promptTokens": usage.get("prompt_tokens"),
            "outputTokens": completion,
            "effectiveOutputTokPerSec": (completion / elapsed if isinstance(completion, (int, float)) and elapsed > 0 else None),
            "naturalStop": isinstance(completion, int) and completion < 1024,
            "maxTokenStop": completion == 1024,
            "forbiddenContractFieldsPresent": sorted(k for k in FORBIDDEN_FIELDS if k in body),
            "internalLeakMarkers": [term for term in LEAK_TERMS if term in narrative.lower()],
            "inventedAbilityMarkers": [term for term in ABILITY_TERMS if term in narrative.lower()],
            "characterCount": len(narrative),
        })
    records.append(rec)
    (RESULT / f"case-{idx:02d}.json").write_text(json.dumps(rec, ensure_ascii=False, indent=2), encoding="utf-8")

(RESULT / "raw-cases.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")

valid = [r for r in records if r.get("http") == 200]
canon_ok = sum(1 for r in valid if r.get("canonicalMutation") is False and not r.get("forbiddenContractFieldsPresent"))
provider_boundary_failures = sum(1 for r in records if r.get("http") == 502 and r.get("elapsedSeconds", 0) >= 175)
natural = sum(1 for r in valid if r.get("naturalStop"))
max_stops = sum(1 for r in valid if r.get("maxTokenStop"))
leak_marker_cases = sum(1 for r in valid if r.get("internalLeakMarkers"))
ability_marker_cases = sum(1 for r in valid if r.get("inventedAbilityMarkers"))
times = [r["elapsedSeconds"] for r in valid]
rates = [r["effectiveOutputTokPerSec"] for r in valid if isinstance(r.get("effectiveOutputTokPerSec"), (int, float))]
outputs = [r["outputTokens"] for r in valid if isinstance(r.get("outputTokens"), int)]

with open(RESULT / "summary.txt", "w", encoding="utf-8") as f:
    f.write("CHAT-7 TEMP GM LIVE SEMANTIC RETEST #3\n")
    f.write("SEMANTIC_CLASSIFICATION=REQUIRES_CHAT7_MANUAL_REVIEW\n")
    f.write(f"HTTP_200={len(valid)}/10\n")
    f.write(f"CANONICAL_SAFETY={canon_ok}/10\n")
    f.write(f"PROVIDER_BOUNDARY_FAILURES={provider_boundary_failures}/10\n")
    f.write(f"NATURAL_STOPS={natural}/10\n")
    f.write(f"MAX_TOKEN_STOPS={max_stops}/10\n")
    f.write(f"LEAK_MARKER_CASES={leak_marker_cases}/10\n")
    f.write(f"ABILITY_MARKER_CASES={ability_marker_cases}/10\n")
    if times:
        f.write(f"MIN_GENERATION_TIME={min(times):.3f}\n")
        f.write(f"MEDIAN_GENERATION_TIME={statistics.median(times):.3f}\n")
        f.write(f"MAX_GENERATION_TIME={max(times):.3f}\n")
    if rates:
        f.write(f"MIN_EFFECTIVE_TOK_S={min(rates):.3f}\n")
        f.write(f"MEDIAN_EFFECTIVE_TOK_S={statistics.median(rates):.3f}\n")
        f.write(f"MAX_EFFECTIVE_TOK_S={max(rates):.3f}\n")
    if outputs:
        f.write(f"MIN_OUTPUT_TOKENS={min(outputs)}\n")
        f.write(f"MEDIAN_OUTPUT_TOKENS={statistics.median(outputs)}\n")
        f.write(f"MAX_OUTPUT_TOKENS={max(outputs)}\n")
    for r in records:
        f.write(
            f"CASE_{r['case']:02d}: http={r.get('http')} promptTokens={r.get('promptTokens')} "
            f"outputTokens={r.get('outputTokens')} seconds={r.get('elapsedSeconds'):.3f} "
            f"tok_s={r.get('effectiveOutputTokPerSec')} natural={r.get('naturalStop')} "
            f"maxStop={r.get('maxTokenStop')} canonicalMutation={r.get('canonicalMutation')} "
            f"leakMarkers={r.get('internalLeakMarkers')} abilityMarkers={r.get('inventedAbilityMarkers')} "
            f"error={r.get('error')}\n"
        )

print((RESULT / "summary.txt").read_text(encoding="utf-8"))
PY

{
  echo "LLAMA_PID=$LLAMA_PID"
  echo "BRIDGE_PID=$BRIDGE_PID"
} > "$RESULT/processes.txt"

curl -fsS http://127.0.0.1:8765/health > "$RESULT/health-final.json" 2>/dev/null || true

"$HOME/chat7_publish_evidence.sh" "$RESULT" "chat7-live-semantic-retest3"
echo "EVIDENCE_PUBLISHED=YES"
echo "STACK_LEFT_RUNNING=YES"
