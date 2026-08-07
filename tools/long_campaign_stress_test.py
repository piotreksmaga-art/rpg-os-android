#!/usr/bin/env python3
import sqlite3, shutil, tempfile, random, uuid, hashlib, json, os, time
from pathlib import Path

HERE=Path(__file__).resolve().parents[1]
SRC=HERE/"app/src/main/assets/Naruto_Default.campaign.zip"

# This stress test targets a materialized campaign.db if provided, otherwise the RPG OS v0.1 source save.
fallback=Path("/mnt/data/RPG_OS_v0_1/saves/Naruto_Default.campaign/campaign.db")
db_path=Path(os.environ.get("RPGOS_STRESS_DB",fallback))
if not db_path.exists():
    raise SystemExit(f"Missing DB: {db_path}")

tmp=Path(tempfile.mkdtemp())/"campaign.db"
shutil.copy2(db_path,tmp)
con=sqlite3.connect(tmp)
con.row_factory=sqlite3.Row

def exists(t):
    return con.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",(t,)).fetchone() is not None

chapters=int(os.environ.get("RPGOS_STRESS_CHAPTERS","10000"))
issues=[]
start=time.time()

if exists("chapter_manifests_v2"):
    cols={r["name"] for r in con.execute("PRAGMA table_info(chapter_manifests_v2)")}
    required=["chapter","title"]
    if not all(x in cols for x in required):
        issues.append("chapter_manifests_v2 missing required columns")
    else:
        for ch in range(1,chapters+1):
            values={"chapter":ch,"title":f"Stress chapter {ch}"}
            for k in ["opening_state_hash","closing_state_hash","active_threads_json","decisions_json","consequences_json","quests_json","continuity_warnings_json","created_at"]:
                if k in cols:
                    values[k]="[]" if k.endswith("_json") else hashlib.sha256(f"{ch}:{k}".encode()).hexdigest() if "hash" in k else str(time.time())
            names=",".join(values)
            qs=",".join("?" for _ in values)
            con.execute(f"INSERT OR REPLACE INTO chapter_manifests_v2({names}) VALUES({qs})",tuple(values.values()))
            if ch%500==0: con.commit()
else:
    issues.append("chapter_manifests_v2 table missing")

con.commit()
integrity=con.execute("PRAGMA integrity_check").fetchone()[0]
count=con.execute("SELECT COUNT(*) FROM chapter_manifests_v2").fetchone()[0] if exists("chapter_manifests_v2") else 0
size=tmp.stat().st_size
duration=time.time()-start
con.close()

report={
 "chapters_requested":chapters,
 "chapter_rows":count,
 "integrity":integrity,
 "db_size_bytes":size,
 "duration_seconds":round(duration,3),
 "issues":issues,
}
print(json.dumps(report,indent=2))
(Path(os.environ.get("RPGOS_STRESS_REPORT","/mnt/data/RPG_OS_STRESS_REPORT.json"))
 .write_text(json.dumps(report,indent=2),encoding="utf-8"))
