from pathlib import Path

p = Path("app/src/test/java/com/rpgos/app/Phase37WorldActorKnowledgeTest.kt")
text = p.read_text()
old = '''    private fun init(db: SQLiteDatabase, vararg campaigns: String = arrayOf("C1")) {
        campaigns.forEach { GameplayRuntimeBootstrap.initialize(db, it) }
    }
'''
new = '''    private fun init(db: SQLiteDatabase, vararg campaigns: String) {
        val targets = if (campaigns.isEmpty()) listOf("C1") else campaigns.toList()
        targets.forEach { GameplayRuntimeBootstrap.initialize(db, it) }
    }
'''
if text.count(old) != 1:
    raise SystemExit(f"expected one init helper match, found {text.count(old)}")
p.write_text(text.replace(old, new, 1))
print("patched Phase37 test helper")
