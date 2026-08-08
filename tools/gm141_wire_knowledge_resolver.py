from pathlib import Path

p = Path('app/src/main/java/com/rpgos/app/GameMasterRuleResolver141.kt')
s = p.read_text()
old = '''                "ASSERT_BELIEF" -> {
                    truths += truthFrom(action, params, TruthKind.BELIEF)
                }
'''
new = '''                "ASSERT_BELIEF", "KNOWLEDGE_PROPAGATE" -> {
                    truths += GameMasterKnowledgeResolver141(repository, campaignUid).resolve(action, params)
                }
'''
if s.count(old) != 1:
    raise SystemExit(f'ASSERT_BELIEF block mismatch: {s.count(old)}')
s = s.replace(old, new, 1)
p.write_text(s)
