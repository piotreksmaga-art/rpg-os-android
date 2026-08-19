from pathlib import Path
p=Path('app/src/test/java/com/rpgos/app/Phase35CanonDivergenceTest.kt')
s=p.read_text();old='GameplayMutationDatabaseGuards.CONTEXT_TABLE';new='GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME'
assert s.count(old)==3,s.count(old)
p.write_text(s.replace(old,new))
print('Phase35 compile alias fixed')
