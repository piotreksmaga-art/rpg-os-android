#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$HOME/rpgos-temp-gm"
SRC="$ROOT/chat7-harness-src"
HERE="$SRC/temp-gm/bridge"
RESULT="$ROOT/results/chat7-semantic-timeout-validation"
mkdir -p "$RESULT"
rm -f "$RESULT"/* 2>/dev/null || true

python "$HERE/test_temp_gm_semantics.py" >/dev/null 2>&1 || true

python - "$HERE" "$RESULT" <<'PY'
import http.client, json, os, socket, sys, time, urllib.request
from pathlib import Path
HERE=Path(sys.argv[1]); RESULT=Path(sys.argv[2]); sys.path.insert(0,str(HERE))
import test_temp_gm_semantics as sem
import temp_context_builder as cb

# Executed contract suite, not static inspection.
results=[]
for name in sorted(n for n in dir(sem) if n.startswith('test_GM_SEM_')):
    fn=getattr(sem,name)
    try:
        fn(); results.append((name,'PASS',''))
    except Exception as e:
        results.append((name,'FAIL',repr(e)))
with open(RESULT/'semantic-contract-tests.txt','w',encoding='utf-8') as f:
    for r in results: f.write(' | '.join(r)+'\n')
    f.write(f"TOTAL_PASS={sum(r[1]=='PASS' for r in results)}/10\n")
if any(r[1]!='PASS' for r in results):
    raise SystemExit(41)

fixture="Przede mną stoi wrogi shinobi.\nAtakuję go kataną.\nCeluję w jego prawą dłoń.\nOpisz reakcję wrogiego shinobi."
semantic_prompts=[
 fixture,
 "Atakuję wrogiego shinobi mieczem. Opisz tylko jego reakcję. Nie deklaruję uniku ani drugiego ataku.",
 "Mówię do strażnika: 'Odsuń się'. Opisz jego odpowiedź. Nie dopisuj żadnej kolejnej wypowiedzi ani ruchu mojej postaci.",
 "Popycham przeciwnika lewą dłonią. On może zareagować dowolnie jako NPC. Zatrzymaj narrację przed moją następną decyzją.",
 "Strzelam z łuku do prawego barku bandyty. Opisz reakcję bandyty; nie zmieniaj aktora ani celu.",
 "Podchodzę jeden krok do wrogiego shinobi i zatrzymuję się. NPC może się bronić, wycofać lub mówić, ale nie steruj mną.",
 "Próbuję przeciąć linę nad głową bandyty. Opisz jego natychmiastową reakcję i zakończ.",
 "Obserwuję zamknięte drzwi. Nie wykonuję żadnej innej czynności. Opisz tylko reakcję otoczenia/NPC.",
 "Pytam Rena: 'Kto tu był?'. Ren zna tylko to, co widział i słyszał. Nie dopisuj mi dalszej wypowiedzi.",
 "Atakuję wrogiego ninja kataną w jego prawą dłoń. NPC może zablokować, uniknąć lub kontratakować jako NPC. Nie wykonuj za mnie żadnej dalszej czynności."
]

def post_bridge(message,max_tokens,timeout=190):
    payload={"message":message,"mode":"NARRATIVE_ONLY","maxTokens":max_tokens,"context":{"campaignUid":"sem-timeout","worldPackUid":"sem-timeout","playerIdentity":{"name":"Smagi"},"sceneState":{"location":"test arena"},"playerSceneState":{"position":"naprzeciw wrogiego shinobi"},"relevantNpcs":[{"npcUid":"enemy","sceneFacts":{"hostile":True,"position":"przede mną"},"knowledge":{"observed":["gracz stoi przede mną"],"heard":[],"told":[],"inferred":[]}}],"recentDialogueActions":[],"retrievedChronicleMemory":[],"availableTestCapabilities":["narrative"],"engineConfirmedResults":[]}}
    data=json.dumps(payload,ensure_ascii=False).encode('utf-8')
    req=urllib.request.Request('http://127.0.0.1:8765/gm/turn',data=data,headers={'Content-Type':'application/json'},method='POST')
    start=time.monotonic()
    try:
        with urllib.request.urlopen(req,timeout=timeout) as r: body=json.loads(r.read().decode('utf-8')); code=r.status
        return {'http':code,'elapsed':time.monotonic()-start,'body':body,'timeout':False}
    except Exception as e:
        return {'http':None,'elapsed':time.monotonic()-start,'error':type(e).__name__+': '+str(e),'timeout':isinstance(e,(TimeoutError,socket.timeout)) or 'timed out' in str(e).lower()}

# Live semantic samples (short output). They are evidence for CHAT-7 review; not auto-judged as semantic PASS.
live=[]
for i,p in enumerate(semantic_prompts,1):
    r=post_bridge(p,192,timeout=90); live.append({'test':f'GM_SEM_{i:02d}','prompt':p,**r})
    (RESULT/f'gm-sem-{i:02d}.json').write_text(json.dumps(live[-1],ensure_ascii=False,indent=2),encoding='utf-8')
(RESULT/'semantic-live-all.json').write_text(json.dumps(live,ensure_ascii=False,indent=2),encoding='utf-8')

# Three representative 1024-token bridge runs under CURRENT provider timeout=180s.
long_prompts=[
 fixture,
 "Prowadzę ostrożną rozmowę z podejrzliwym najemnikiem w gospodzie. Mówię tylko: 'Szukam informacji o karawanie'. Opisz szczegółowo jego reakcję, zachowanie otoczenia i bezpośrednią odpowiedź NPC, ale nie wybieraj za mnie kolejnej wypowiedzi ani działania.",
 "Wchodzę do opuszczonego magazynu i zatrzymuję się przy wejściu. Rozglądam się. Opisz szczegółowo to, co mogę dostrzec, oraz natychmiastowe reakcje obecnych NPC lub otoczenia. Nie wykonuj za mnie dalszego ruchu ani decyzji."
]
measures=[]
for i,p in enumerate(long_prompts,1):
    r=post_bridge(p,1024,timeout=195)
    body=r.get('body') or {}; usage=body.get('usage') or {}
    tokens=usage.get('completion_tokens')
    r['completionTokens']=tokens; r['promptTokens']=usage.get('prompt_tokens')
    r['effectiveTokPerSec']=(tokens/r['elapsed']) if isinstance(tokens,(int,float)) and r['elapsed']>0 else None
    r['naturallyEndedBeforeMax']=isinstance(tokens,int) and tokens<1024
    measures.append({'run':i,'prompt':p,**r})
    (RESULT/f'long-run-{i}.json').write_text(json.dumps(measures[-1],ensure_ascii=False,indent=2),encoding='utf-8')
(RESULT/'long-runs.json').write_text(json.dumps(measures,ensure_ascii=False,indent=2),encoding='utf-8')

health={}
for path in ('health','providers','active-provider'):
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:8765/{path}',timeout=3) as r: health[path]=json.loads(r.read().decode('utf-8'))
    except Exception as e: health[path]={'error':type(e).__name__+': '+str(e)}
(RESULT/'health-final.json').write_text(json.dumps(health,ensure_ascii=False,indent=2),encoding='utf-8')

with open(RESULT/'summary.txt','w',encoding='utf-8') as f:
    f.write('CHAT-7 TEMP GM SEMANTIC + TIMEOUT MEASUREMENT\n')
    f.write('CURRENT_PROVIDER_TIMEOUT=180\nCURRENT_ANDROID_TIMEOUT=210\nCURRENT_MAX_TOKENS=1024\n')
    f.write(f"GM_SEM_CONTRACT_TESTS={sum(r[1]=='PASS' for r in results)}/10 PASS\n")
    for m in measures:
        f.write(f"RUN_{m['run']}: promptTokens={m.get('promptTokens')} completionTokens={m.get('completionTokens')} elapsed={m.get('elapsed'):.3f}s effectiveTokPerSec={m.get('effectiveTokPerSec')} naturalEnd={m.get('naturallyEndedBeforeMax')} timeout={m.get('timeout')} http={m.get('http')} error={m.get('error')}\n")
    f.write('LIVE_SEMANTIC_OUTPUTS_REQUIRE_CHAT7_REVIEW=YES\n')
print((RESULT/'summary.txt').read_text(encoding='utf-8'))
PY

"$HOME/chat7_publish_evidence.sh" "$RESULT" "chat7-semantic-timeout-validation-current-contract"
echo "EVIDENCE_PUBLISHED=YES"
