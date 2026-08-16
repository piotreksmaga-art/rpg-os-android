#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$HOME/rpgos-temp-gm"
SRC="$ROOT/chat7-harness-src"
HERE="$SRC/temp-gm/bridge"
EXPECTED_HEAD="9d90148ac34db9ac024e2163aaa19ef7934fa082"
RESULT="$ROOT/results/chat7-live-semantic-retest2"
mkdir -p "$RESULT"
rm -f "$RESULT"/* 2>/dev/null || true

HEAD="$(git -C "$SRC" rev-parse HEAD)"
BRANCH="$(git -C "$SRC" branch --show-current 2>/dev/null || true)"

{
  echo "CHAT-7 TEMP GM LIVE SEMANTIC RETEST #2"
  echo "BRANCH=$BRANCH"
  echo "HEAD=$HEAD"
  echo "EXPECTED_HEAD=$EXPECTED_HEAD"
  echo "CURRENT_PROVIDER_TIMEOUT=180"
  echo "CURRENT_ANDROID_TIMEOUT=210"
  echo "CURRENT_MAX_TOKENS=1024"
} > "$RESULT/preflight.txt"

if [ "$HEAD" != "$EXPECTED_HEAD" ]; then
  echo "PREFLIGHT=FAIL_HEAD_MISMATCH" | tee -a "$RESULT/preflight.txt"
  "$HOME/chat7_publish_evidence.sh" "$RESULT" "chat7-live-semantic-retest2-head-mismatch" || true
  exit 20
fi

if ! curl -fsS --max-time 2 http://127.0.0.1:8768/health > "$RESULT/llama-health.json"; then
  echo "LLAMA=FAIL" | tee -a "$RESULT/preflight.txt"
  "$HOME/chat7_publish_evidence.sh" "$RESULT" "chat7-live-semantic-retest2-llama-fail" || true
  exit 21
fi

echo "LLAMA=PASS" >> "$RESULT/preflight.txt"

pkill -f 'temp_gm_bridge.py' 2>/dev/null || true
sleep 2

TGM_BRIDGE_HOST=127.0.0.1 \
TGM_BRIDGE_PORT=8765 \
TGM_BIELIK_URL=http://127.0.0.1:8768 \
TGM_DATA_DIR="$ROOT/bridge-data" \
nohup python "$HERE/temp_gm_bridge.py" > "$RESULT/bridge.log" 2>&1 &
BRIDGE_PID=$!
echo "$BRIDGE_PID" > "$RESULT/bridge.pid"

READY=0
for i in $(seq 1 30); do
  if ! kill -0 "$BRIDGE_PID" 2>/dev/null; then break; fi
  if curl -fsS --max-time 1 http://127.0.0.1:8765/health > "$RESULT/health.json" 2>/dev/null; then
    READY=1
    echo "BRIDGE_READY_SECONDS=$i" >> "$RESULT/preflight.txt"
    break
  fi
  sleep 1
done

if [ "$READY" -ne 1 ]; then
  echo "BRIDGE=FAIL" | tee -a "$RESULT/preflight.txt"
  tail -120 "$RESULT/bridge.log" >> "$RESULT/preflight.txt" 2>/dev/null || true
  "$HOME/chat7_publish_evidence.sh" "$RESULT" "chat7-live-semantic-retest2-bridge-fail" || true
  exit 22
fi

echo "BRIDGE=PASS" >> "$RESULT/preflight.txt"
curl -fsS http://127.0.0.1:8765/providers > "$RESULT/providers.json"
curl -fsS http://127.0.0.1:8765/active-provider > "$RESULT/active-provider.json"

python - "$RESULT" <<'PY'
import json, re, statistics, sys, time, urllib.request, urllib.error, socket
from pathlib import Path

RESULT=Path(sys.argv[1])
CASES=[
("CASE_01_ACTION_DIRECTION","Przede mną stoi wrogi shinobi.\nAtakuję go kataną.\nCeluję w jego prawą dłoń.\nOpisz reakcję wrogiego shinobi."),
("CASE_02_OBSERVATION_ONLY","Stoję przed zamkniętymi drzwiami.\nObserwuję je i czekam.\nOpisz, co widzę i co dzieje się wokół mnie."),
("CASE_03_NPC_QUESTION","Pytam strażnika:\n„Kto znajduje się po drugiej stronie bramy?”\nCzekam na jego odpowiedź."),
("CASE_04_FAILED_ATTEMPT","Próbuję złapać uciekającego shinobi za rękaw.\nOpisz rezultat próby."),
("CASE_05_PLAYER_DOES_NOTHING","Nic nie robię.\nObserwuję przeciwnika."),
("CASE_06_DECLARED_MOVEMENT","Robię jeden krok do tyłu.\nNie atakuję.\nObserwuję przeciwnika."),
("CASE_07_PLAYER_SPEECH","Mówię:\n„Nie chcę walczyć.”"),
("CASE_08_UNKNOWN_ABILITY","Patrzę na dach budynku.\nNie używam żadnej techniki."),
("CASE_09_INTERNAL_CONTEXT","Rozglądam się po dziedzińcu i pytam strażnika, czy zauważył coś niezwykłego. Potem czekam na odpowiedź."),
("CASE_10_STOP_POINT","Atakuję bandytę pojedynczym cięciem miecza w lewe ramię."),
]

CTX={
 "campaignUid":"semantic-retest2",
 "worldPackUid":"semantic-retest2",
 "playerIdentity":{"name":"Smagi"},
 "sceneState":{"location":"test arena"},
 "playerSceneState":{"position":"przed NPC"},
 "relevantNpcs":[{"npcUid":"enemy","sceneFacts":{"hostile":True,"position":"przed graczem"},"knowledge":{"observed":["gracz stoi przede mną"],"heard":[],"told":[],"inferred":[]}}],
 "recentDialogueActions":[],
 "retrievedChronicleMemory":[],
 "availableTestCapabilities":["narrative"],
 "engineConfirmedResults":[]
}

def post(message):
    payload={"message":message,"mode":"NARRATIVE_ONLY","maxTokens":1024,"context":CTX}
    req=urllib.request.Request('http://127.0.0.1:8765/gm/turn',data=json.dumps(payload,ensure_ascii=False).encode('utf-8'),headers={'Content-Type':'application/json'},method='POST')
    start=time.monotonic()
    try:
        with urllib.request.urlopen(req,timeout=190) as r:
            raw=r.read().decode('utf-8'); code=r.status
        elapsed=time.monotonic()-start
        body=json.loads(raw)
        return code,elapsed,body,None
    except Exception as e:
        return None,time.monotonic()-start,None,type(e).__name__+': '+str(e)

forbidden_contract_fields={"statePatch","StatePatch","playerChangeSet","PlayerChangeSet","commit","COMMIT","authoritativeEvent","authoritativeEvents"}
leak_terms=["system_prompt","read-only temp context","read_only_context","internal context","gm_sem","test_failure","test_fallback","hidden instructions","prompt rules","implementation details","canonicalmutation=false"]
ability_terms=["teleport","shunshin","chakra technique","technika chakry","lecę","latam","flight"]

records=[]
for idx,(name,prompt) in enumerate(CASES,1):
    code,elapsed,body,error=post(prompt)
    rec={"case":idx,"name":name,"playerDeclaration":prompt,"http":code,"elapsedSeconds":elapsed,"error":error}
    if body is not None:
        narrative=str(body.get("narrative",''))
        usage=body.get("usage") or {}
        completion=usage.get("completion_tokens")
        prompt_tokens=usage.get("prompt_tokens")
        rec.update({
            "modelOutput":narrative,
            "providerId":body.get("providerId"),
            "canonicalMutation":body.get("canonicalMutation"),
            "promptTokens":prompt_tokens,
            "outputTokens":completion,
            "effectiveOutputTokPerSec": (completion/elapsed if isinstance(completion,(int,float)) and elapsed>0 else None),
            "naturalStop": (isinstance(completion,int) and completion<1024),
            "maxTokenStop": (completion==1024),
            "forbiddenContractFieldsPresent": sorted(k for k in forbidden_contract_fields if k in body),
            "internalLeakMarkers": [x for x in leak_terms if x in narrative.lower()],
        })
        lower=narrative.lower()
        rec["inventedAbilityMarkers"]=[x for x in ability_terms if x in lower]
    records.append(rec)
    (RESULT/f'case-{idx:02d}.json').write_text(json.dumps(rec,ensure_ascii=False,indent=2),encoding='utf-8')

(RESULT/'raw-cases.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')

# Mechanical safety and measurement summary only. Semantic PASS remains for CHAT-7 manual review.
valid=[r for r in records if r.get('http')==200]
canon_ok=sum(1 for r in valid if r.get('canonicalMutation') is False and not r.get('forbiddenContractFieldsPresent'))
timeouts=sum(1 for r in records if r.get('error') and 'timed out' in r['error'].lower())
natural=sum(1 for r in valid if r.get('naturalStop'))
maxstops=sum(1 for r in valid if r.get('maxTokenStop'))
times=[r['elapsedSeconds'] for r in valid]
rates=[r['effectiveOutputTokPerSec'] for r in valid if isinstance(r.get('effectiveOutputTokPerSec'),(int,float))]

with open(RESULT/'summary.txt','w',encoding='utf-8') as f:
    f.write('CHAT-7 TEMP GM LIVE SEMANTIC RETEST #2\n')
    f.write('SEMANTIC_CLASSIFICATION=REQUIRES_CHAT7_MANUAL_REVIEW\n')
    f.write(f'HTTP_200={len(valid)}/10\n')
    f.write(f'CANONICAL_SAFETY={canon_ok}/10\n')
    f.write(f'PROVIDER_TIMEOUTS={timeouts}/10\n')
    f.write(f'NATURAL_STOPS={natural}/10\n')
    f.write(f'MAX_TOKEN_STOPS={maxstops}/10\n')
    if times:
        f.write(f'MIN_GENERATION_TIME={min(times):.3f}\nMEDIAN_GENERATION_TIME={statistics.median(times):.3f}\nMAX_GENERATION_TIME={max(times):.3f}\n')
    if rates:
        f.write(f'MIN_EFFECTIVE_TOK_S={min(rates):.3f}\nMEDIAN_EFFECTIVE_TOK_S={statistics.median(rates):.3f}\nMAX_EFFECTIVE_TOK_S={max(rates):.3f}\n')
    for r in records:
        f.write(f"CASE_{r['case']:02d}: http={r.get('http')} promptTokens={r.get('promptTokens')} outputTokens={r.get('outputTokens')} seconds={r.get('elapsedSeconds'):.3f} tok_s={r.get('effectiveOutputTokPerSec')} natural={r.get('naturalStop')} maxStop={r.get('maxTokenStop')} canonicalMutation={r.get('canonicalMutation')} leakMarkers={r.get('internalLeakMarkers')} abilityMarkers={r.get('inventedAbilityMarkers')} error={r.get('error')}\n")

print((RESULT/'summary.txt').read_text(encoding='utf-8'))
PY

{
  echo "LLAMA_PID=$(pgrep -f 'llama-server.*--port 8768' | head -1 || true)"
  echo "BRIDGE_PID=$BRIDGE_PID"
} > "$RESULT/processes.txt"

cp "$RESULT/health.json" "$RESULT/health-final.json" 2>/dev/null || true
curl -fsS http://127.0.0.1:8765/health > "$RESULT/health-final.json" 2>/dev/null || true

"$HOME/chat7_publish_evidence.sh" "$RESULT" "chat7-live-semantic-retest2"
echo "EVIDENCE_PUBLISHED=YES"
echo "STACK_LEFT_RUNNING=YES"
