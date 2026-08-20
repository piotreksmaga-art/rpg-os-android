from pathlib import Path

p = Path("tools/phase37_restore_baseline_minimal.py")
text = p.read_text()
old = '''def replace(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))
    print(f"patched {path}")
'''
new = '''def replace(path: str, old: str, new: str, expected_matches: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected_matches:
        raise SystemExit(f"{path}: expected {expected_matches} match(es), found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))
    print(f"patched {path}")
'''
if text.count(old) != 1:
    raise SystemExit("restore helper function shape drift")
text = text.replace(old, new, 1)
needle = '''        Phase37KnowledgeSchema.canonicalTables.forEach { table ->
            installRuntimeTurnAuthorityTrigger(db, p37GuardName(table), table, null)
        }
    }
'''
# The generic Phase35 guard block occurs twice in baseline by design. Only the first occurrence is
# extended here; the later restore function has its own uniquely anchored replacement below.
call_old = '''        Phase37KnowledgeSchema.canonicalTables.forEach { table ->
            installRuntimeTurnAuthorityTrigger(db, p37GuardName(table), table, null)
        }
    }
'''
# Rather than rewriting production text, annotate the earlier replace call with the expected baseline multiplicity.
marker = '''        Phase37KnowledgeSchema.canonicalTables.forEach { table ->
            installRuntimeTurnAuthorityTrigger(db, p37GuardName(table), table, null)
        }
    }
''',
)
'''
replacement = '''        Phase37KnowledgeSchema.canonicalTables.forEach { table ->
            installRuntimeTurnAuthorityTrigger(db, p37GuardName(table), table, null)
        }
    }
''',
    expected_matches=2,
)
'''
if text.count(marker) != 1:
    raise SystemExit(f"restore helper target call drift: {text.count(marker)}")
text = text.replace(marker, replacement, 1)
p.write_text(text)
print("made Phase37 baseline helper multiplicity explicit")
