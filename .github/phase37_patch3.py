from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "app/src/test/java/com/rpgos/app/Phase32RepositoryWideWriterSourceInventoryTest.kt"
text = p.read_text()
old = '            "Phase35CanonDivergence.kt",\n            "Phase9Store.kt",'
new = '            "Phase35CanonDivergence.kt",\n            "Phase37NpcKnowledge.kt",\n            "Phase9Store.kt",'
count = text.count(old)
if count != 1:
    raise SystemExit(f"Phase32 writer inventory anchor mismatch: {count}")
p.write_text(text.replace(old, new, 1))
print("Phase37 durable writer explicitly classified as CANONICAL_DOMAIN")
