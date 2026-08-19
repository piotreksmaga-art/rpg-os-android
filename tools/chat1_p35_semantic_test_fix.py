from pathlib import Path
p=Path('app/src/test/java/com/rpgos/app/Phase35CanonDivergenceTest.kt');s=p.read_text()
for old,new in [
('spec("DIV-ROLL", "A", "B")','spec("DIV-ROLL", "CANON", "B")'),
('spec("DIV-IDEM", "A", "B")','spec("DIV-IDEM", "CANON", "B")'),
('spec("DIV-C1", "A", "B")','spec("DIV-C1", "CANON", "B")'),
('spec("DIV-C2", "A", "C")','spec("DIV-C2", "CANON", "C")'),
('spec("DIV-AFTER", "A", "B")','spec("DIV-AFTER", "CANON", "B")')]:
 s=s.replace(old,new)
p.write_text(s)
print('Phase35 legacy divergence expectations aligned')
