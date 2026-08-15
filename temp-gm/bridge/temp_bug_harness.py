#!/usr/bin/env python3
"""TEMP bug-reporting harness for WORK-20260815-001.

Non-authoritative, local-first, user-authorized. No GitHub write happens here.
Python stdlib only for Termux compatibility.
"""
from __future__ import annotations
import hashlib, json, re, subprocess, time
from pathlib import Path
from typing import Any

SUBMISSION_STATES={"LOCAL_PENDING","READY","SUBMITTED","LINKED_DUPLICATE","CANCELLED"}
MAX_PENDING_REPORTS=100; MAX_USER_REPORT_CHARS=12000; MAX_ACTIONS=12; MAX_GM_RESPONSES=6
MAX_LOGCAT_LINES=300; MAX_LOGCAT_CHARS=30000; LOGCAT_TIMEOUT_SECONDS=6
SECRET_PATTERNS=[
 re.compile(r"gh[opsu]_[A-Za-z0-9_\-]{20,}"),
 re.compile(r"(?i)(authorization\s*:\s*bearer\s+)[^\s]+"),
 re.compile(r"(?i)((?:api[_-]?key|token|password|secret|cookie)\s*[:=]\s*)[^\s,;]+"),
]

def redact_secrets(value:Any,max_chars:int=MAX_LOGCAT_CHARS)->str:
    text=str(value or "").replace("\x00","�")
    text=text.encode("utf-8",errors="replace").decode("utf-8",errors="replace")[:max_chars]
    for p in SECRET_PATTERNS:
        text=p.sub("[REDACTED_GITHUB_TOKEN]",text) if p.pattern.startswith("gh") else p.sub(lambda m:m.group(1)+"[REDACTED]",text)
    return text

def _safe_list(value:Any,limit:int,item_chars:int=2000)->list[str]:
    if not isinstance(value,list): return []
    return [redact_secrets(x,item_chars) for x in value[-limit:]]

def normalize_symptom(text:str)->str:
    text=redact_secrets(text,1200).lower(); text=re.sub(r"\b\d{2,}\b","#",text)
    text=re.sub(r"[^a-ząćęłńóśźż0-9# _-]+"," ",text)
    return " ".join(text.split())[:400]

def normalized_stack_frames(frames:Any)->list[str]:
    out=[]
    if not isinstance(frames,list): return out
    for frame in frames[:5]: out.append(re.sub(r":\d+",":#",redact_secrets(frame,500)).strip())
    return out

def duplicate_fingerprint(*,version_name:Any,version_code:Any,route:Any,exception_class:Any,top_stack_frames:Any,user_report:Any,stable_environment:Any=None)->str:
    env=stable_environment if isinstance(stable_environment,dict) else {}
    identity={"versionName":str(version_name or ""),"versionCode":str(version_code or ""),"route":str(route or ""),"exceptionClass":str(exception_class or ""),"topStackFrames":normalized_stack_frames(top_stack_frames),"symptom":normalize_symptom(str(user_report or "")),"stableEnvironment":{"androidSdk":env.get("androidSdk"),"deviceModel":env.get("deviceModel")}}
    canonical=json.dumps(identity,ensure_ascii=False,sort_keys=True,separators=(",",":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:24]

def _bounded_text_lines(text:str,max_lines:int=MAX_LOGCAT_LINES,max_chars:int=MAX_LOGCAT_CHARS)->str:
    safe=redact_secrets(text,max_chars*2); return "\n".join(safe.splitlines()[-max_lines:])[-max_chars:]

def capture_package_logcat(package_name:str,adb_command:str="adb")->dict[str,Any]:
    package_name=re.sub(r"[^A-Za-z0-9._]","",str(package_name or ""))
    if not package_name:return {"status":"SKIPPED","reason":"package_missing","excerpt":"","lineLimit":MAX_LOGCAT_LINES}
    try:
        pid=subprocess.run([adb_command,"shell","pidof",package_name],capture_output=True,text=True,timeout=LOGCAT_TIMEOUT_SECONDS,check=False).stdout.strip().split()
        if not pid:return {"status":"UNAVAILABLE","reason":"package_pid_unavailable","excerpt":"","lineLimit":MAX_LOGCAT_LINES}
        proc=subprocess.run([adb_command,"logcat","-d","-v","threadtime","-t",str(MAX_LOGCAT_LINES),"--pid",pid[0]],capture_output=True,timeout=LOGCAT_TIMEOUT_SECONDS,check=False)
        raw=proc.stdout.decode("utf-8",errors="replace") if isinstance(proc.stdout,bytes) else str(proc.stdout or "")
        return {"status":"CAPTURED" if raw else "UNAVAILABLE","reason":None if raw else "empty_logcat","excerpt":_bounded_text_lines(raw),"lineLimit":MAX_LOGCAT_LINES,"timeoutSeconds":LOGCAT_TIMEOUT_SECONDS,"scope":"package_pid"}
    except (FileNotFoundError,subprocess.TimeoutExpired,OSError) as e:
        return {"status":"UNAVAILABLE","reason":type(e).__name__,"excerpt":"","lineLimit":MAX_LOGCAT_LINES}

class BugReportStore:
    def __init__(self,root:Path): self.root=Path(root); self.pending_dir=self.root/"pending-bugs"; self.pending_dir.mkdir(parents=True,exist_ok=True)
    def _paths(self): return sorted(self.pending_dir.glob("*.json"))
    def _new_report_uid(self,fingerprint:str,created_ms:int)->str:
        base=f"bug-{created_ms}-{fingerprint[:8]}"; uid=base; n=1; existing={p.stem for p in self._paths()}
        while uid in existing: n+=1; uid=f"{base}-{n}"
        return uid
    def save_new(self,report):
        if len(self._paths())>=MAX_PENDING_REPORTS: raise RuntimeError("pending_queue_full")
        path=self.pending_dir/f"{report['reportUid']}.json"; tmp=path.with_suffix(".tmp")
        tmp.write_text(json.dumps(report,ensure_ascii=False,indent=2,sort_keys=True),encoding="utf-8"); tmp.replace(path); return path
    def load(self,report_uid):
        safe=re.sub(r"[^A-Za-z0-9._-]","",report_uid); return json.loads((self.pending_dir/f"{safe}.json").read_text(encoding="utf-8"))
    def update(self,report):
        path=self.pending_dir/f"{report['reportUid']}.json"; tmp=path.with_suffix(".tmp"); tmp.write_text(json.dumps(report,ensure_ascii=False,indent=2,sort_keys=True),encoding="utf-8"); tmp.replace(path)
    def list_reports(self):
        out=[]
        for p in self._paths():
            try: out.append(json.loads(p.read_text(encoding="utf-8")))
            except Exception: pass
        return out

def build_bug_bundle(body:dict[str,Any],*,provider_id:str,provider_status:str,bridge_status:str,store:BugReportStore,capture_logcat:bool=False)->dict[str,Any]:
    raw=body.get("description") if body.get("description") is not None else body.get("userReport")
    if not isinstance(raw,str) or not raw: raise ValueError("description_required")
    if len(raw)>MAX_USER_REPORT_CHARS: raise ValueError("description_too_long")
    description=redact_secrets(raw,MAX_USER_REPORT_CHARS); was_redacted=description!=raw
    build=body.get("build") if isinstance(body.get("build"),dict) else {}; runtime=body.get("runtime") if isinstance(body.get("runtime"),dict) else {}; env=body.get("environment") if isinstance(body.get("environment"),dict) else {}
    route=redact_secrets(body.get("route",""),500); exc=redact_secrets(body.get("exceptionClass",""),500); stack=normalized_stack_frames(body.get("topStackFrames",[]))
    fp=duplicate_fingerprint(version_name=build.get("versionName"),version_code=build.get("versionCode"),route=route,exception_class=exc,top_stack_frames=stack,user_report=description,stable_environment=env)
    created=int(time.time()*1000); uid=store._new_report_uid(fp,created)
    include_logcat=bool(body.get("include_logcat",False) or capture_logcat)
    if include_logcat:
        if body.get("logcatExcerpt") is not None: logcat={"status":"CALLER_SUPPLIED_BOUNDED","reason":None,"excerpt":_bounded_text_lines(body.get("logcatExcerpt","")),"lineLimit":MAX_LOGCAT_LINES,"scope":"package_pid_expected"}
        else: logcat=capture_package_logcat(str(body.get("packageName","")))
    else: logcat={"status":"NOT_REQUESTED","reason":None,"excerpt":"","lineLimit":MAX_LOGCAT_LINES}
    shot_req=bool(body.get("include_screenshot",False)); shot_ok=bool(body.get("screenshotApproved",False)); shot_ref=redact_secrets(body.get("screenshotReference",""),1000) if shot_req and shot_ok else ""
    report={
      "schemaVersion":2,"reportUid":uid,"createdAtUnixMs":created,"submissionState":"LOCAL_PENDING","evidenceClassification":["USER-SUPPLIED","DEVICE-CAPTURED","AI-SUMMARIZED"],
      "USER-SUPPLIED":{"originalReport":description,"originalReportRedactedForSecretSafety":was_redacted,"expected":redact_secrets(body.get("expected",""),6000),"actual":redact_secrets(body.get("actual",""),6000),"reproducibilityNotes":redact_secrets(body.get("reproducibilityNotes",""),8000)},
      "DEVICE-CAPTURED":{"app":{"versionName":redact_secrets(build.get("versionName",""),200),"versionCode":build.get("versionCode"),"buildSha":redact_secrets(build.get("buildSha") or build.get("runtimeSha") or "",200)},"campaignUid":redact_secrets(body.get("campaignUid",""),300),"worldPackUid":redact_secrets(body.get("worldPackUid",""),300),"route":route,"tempProviderId":provider_id,"tempResponseMode":redact_secrets(body.get("responseMode",""),100),"bridgeState":bridge_status,"llamaState":provider_status,"adbState":redact_secrets(body.get("adbStatus","UNKNOWN"),100),"runtime":{"llamaSha":redact_secrets(runtime.get("llamaSha",""),200),"backend":redact_secrets(runtime.get("backend",""),100)},"recentSafeActions":_safe_list(body.get("recentSafeActions",[]),MAX_ACTIONS),"recentTempGmResponses":_safe_list(body.get("recentGmResponses",[]),MAX_GM_RESPONSES),"logcat":logcat,"screenshot":{"requested":shot_req,"userApproved":shot_ok,"reference":shot_ref},"exceptionClass":exc,"topStackFrames":stack,"environment":{"deviceModel":redact_secrets(env.get("deviceModel",""),100),"androidSdk":env.get("androidSdk")}},
      "AI-SUMMARIZED":{"summary":redact_secrets(body.get("aiSummary",""),8000),"isEvidence":False},"reproductionStatus":redact_secrets(body.get("reproductionStatus","UNCONFIRMED"),100),"duplicateFingerprint":fp,"duplicateCandidates":[],"github":{"submissionAuthorized":False,"issueNumber":None,"issueUrl":None,"submissionActionConsumed":False},"canonicalMutation":False}
    store.save_new(report); return report

def prepare_issue_preview(report:dict[str,Any])->str:
    u=report["USER-SUPPLIED"]; d=report["DEVICE-CAPTURED"]; ai=report["AI-SUMMARIZED"]; app=d["app"]; log=d["logcat"]; shot=d["screenshot"]; title=normalize_symptom(u["originalReport"])[:90] or "TEMP bug report"
    return f"""TITLE\n[RPG OS TEMP] {title}\n\nUSER REPORT\n{u['originalReport']}\n\nEXPECTED\n{u['expected'] or 'Not supplied'}\n\nACTUAL\n{u['actual'] or 'Not supplied'}\n\nREPRODUCTION\nStatus: {report['reproductionStatus']}\nNotes: {u['reproducibilityNotes'] or 'Not supplied'}\n\nAPP / BUILD\nversionName={app.get('versionName')}\nversionCode={app.get('versionCode')}\nbuildSha={app.get('buildSha')}\n\nCAMPAIGN / WORLD PACK\ncampaignUid={d.get('campaignUid')}\nworldPackUid={d.get('worldPackUid')}\nroute={d.get('route')}\n\nTEMP GM\nprovider={d.get('tempProviderId')}\nmode={d.get('tempResponseMode')}\nbridge={d.get('bridgeState')}\nllama={d.get('llamaState')}\n\nDEVICE EVIDENCE\nadb={d.get('adbState')}\ndevice={d.get('environment',{}).get('deviceModel')}\nandroidSdk={d.get('environment',{}).get('androidSdk')}\n\nLOGCAT EXCERPT\nstatus={log.get('status')}\n{log.get('excerpt') or '[none]'}\n\nSCREENSHOT\nUSER_APPROVED={'YES' if shot.get('userApproved') else 'NO'}\nreference={shot.get('reference') or '[none]'}\n\nDUPLICATE FINGERPRINT\n{report['duplicateFingerprint']}\n\nAI SUMMARY\n{ai.get('summary') or '[none]'}\n(AI summary is not evidence.)\n\nEVIDENCE CLASSIFICATION\nUSER-SUPPLIED: user report / expected / actual / reproduction notes\nDEVICE-CAPTURED: build, route, provider states, bounded actions/responses/logcat/screenshot metadata\nAI-SUMMARIZED: summary only; not identity authority and not evidence\n"""

def apply_duplicate_candidates(store,report_uid,candidates):
    report=store.load(report_uid); safe=[]
    for c in candidates[:10]:
        if isinstance(c,dict): safe.append({"issueNumber":c.get("issueNumber"),"title":redact_secrets(c.get("title",""),300),"url":redact_secrets(c.get("url",""),1000),"fingerprint":c.get("fingerprint"),"fingerprintMatch":c.get("fingerprint")==report["duplicateFingerprint"]})
    report["duplicateCandidates"]=safe; store.update(report); return report

def set_user_submission_decision(store,report_uid,decision):
    report=store.load(report_uid); decision=str(decision).upper()
    if report["submissionState"] in {"SUBMITTED","LINKED_DUPLICATE","CANCELLED"}: return report
    if decision=="CONFIRM_NEW_ISSUE": report["submissionState"]="READY"; report["github"]["submissionAuthorized"]=True
    elif decision=="CANCEL": report["submissionState"]="CANCELLED"; report["github"]["submissionAuthorized"]=False
    elif decision=="KEEP_PENDING": report["submissionState"]="LOCAL_PENDING"; report["github"]["submissionAuthorized"]=False
    else: raise ValueError("unsupported_submission_decision")
    store.update(report); return report

def consume_issue_creation_authorization(store,report_uid):
    report=store.load(report_uid); allowed=report["submissionState"]=="READY" and report["github"].get("submissionAuthorized") is True and report["github"].get("submissionActionConsumed") is not True
    if allowed: report["github"]["submissionActionConsumed"]=True; store.update(report)
    return {"allowed":allowed,"report":report,"issueDraft":prepare_issue_preview(report)}

def mark_submitted(store,report_uid,issue_number,issue_url):
    report=store.load(report_uid)
    if not report["github"].get("submissionActionConsumed"): raise ValueError("submission_authorization_not_consumed")
    if report["submissionState"]=="SUBMITTED": return report
    report["submissionState"]="SUBMITTED"; report["github"]["issueNumber"]=int(issue_number); report["github"]["issueUrl"]=redact_secrets(issue_url,1000); store.update(report); return report

def mark_linked_duplicate(store,report_uid,issue_number,issue_url,user_confirmed:bool):
    if not user_confirmed: raise ValueError("explicit_user_confirmation_required")
    report=store.load(report_uid); report["submissionState"]="LINKED_DUPLICATE"; report["github"]["submissionAuthorized"]=True; report["github"]["submissionActionConsumed"]=True; report["github"]["issueNumber"]=int(issue_number); report["github"]["issueUrl"]=redact_secrets(issue_url,1000); store.update(report); return report
