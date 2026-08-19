from pathlib import Path
p=Path('app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt')
s=p.read_text(encoding='utf-8')
old='private data class CapturedSnapshotAnchor('
new='internal data class CapturedSnapshotAnchor('
if new not in s:
    if old not in s: raise SystemExit('CapturedSnapshotAnchor marker missing')
    s=s.replace(old,new,1)
p.write_text(s,encoding='utf-8')
print('snapshot anchor visibility fixed')
