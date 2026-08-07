#!/usr/bin/env python3
from pathlib import Path
import re,json
root=Path(__file__).resolve().parents[1]
issues=[]
kotlin=list((root/"app/src/main/java").rglob("*.kt"))
for p in kotlin:
    text=p.read_text(encoding="utf-8",errors="ignore")
    if "TODO:" in text:
        issues.append({"file":str(p.relative_to(root)),"type":"TODO"})
    if "YOUR-BACKEND" in text and p.name!="BackendClient.kt":
        pass
backend=(root/"backend/app.py").read_text(encoding="utf-8")
required=["/v1/gm/turn","/v1/images/generate","/v1/images/edit"]
for r in required:
    if r not in backend:issues.append({"backend_missing":r})
report={"kotlin_files":len(kotlin),"issues":issues}
print(json.dumps(report,indent=2))
(root/"STATIC_AUDIT.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
