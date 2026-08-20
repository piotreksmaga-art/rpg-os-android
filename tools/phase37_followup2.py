from pathlib import Path
p=Path('app/src/main/java/com/rpgos/app/Phase37WorldActorKnowledge.kt')
s=p.read_text()
old='''        arrayOf(campaignUid,uid)).use { c -> if(c.moveToFirst())AcquisitionScope(c.getString(0),KnowledgeHolderRef(c.getString(1),c.getString(2))) else null }'''
new='''        arrayOf(campaignUid,uid)).use { c -> if(c.moveToFirst())AcquisitionScope(c.getString(0),KnowledgeHolderRef(c.getString(1),c.getString(2),campaignUid)) else null }'''
assert s.count(old)==1, s.count(old)
p.write_text(s.replace(old,new,1))
print('qualified acquisitionScope holder')
