from pathlib import Path

p = Path("tools/phase37_restore_baseline_minimal.py")
text = p.read_text()
old_fn = "def replace(path: str, old: str, new: str) -> None:\n    p = Path(path)\n    text = p.read_text()\n    count = text.count(old)\n    if count != 1:\n        raise SystemExit(f\"{path}: expected one match, found {count}: {old[:120]!r}\")\n    p.write_text(text.replace(old, new, 1))\n    print(f\"patched {path}\")\n"
new_fn = "def replace(path: str, old: str, new: str, expected_matches: int = 1) -> None:\n    p = Path(path)\n    text = p.read_text()\n    count = text.count(old)\n    if count != expected_matches:\n        raise SystemExit(f\"{path}: expected {expected_matches} match(es), found {count}: {old[:120]!r}\")\n    p.write_text(text.replace(old, new, 1))\n    print(f\"patched {path}\")\n"
if text.count(old_fn) != 1:
    raise SystemExit("restore helper replace() shape drift")
text = text.replace(old_fn, new_fn, 1)

# This exact generic Phase35 install block appears twice in the accepted baseline: once during
# initial guard installation and once during legacy guard restoration. The first CHAT-1 patch is
# intentionally for the first occurrence; a later uniquely-anchored patch handles the restoration.
call_suffix = "        Phase37KnowledgeSchema.canonicalTables.forEach { table ->\n            installRuntimeTurnAuthorityTrigger(db, p37GuardName(table), table, null)\n        }\n    }\n''',\n)\n"
patched_suffix = "        Phase37KnowledgeSchema.canonicalTables.forEach { table ->\n            installRuntimeTurnAuthorityTrigger(db, p37GuardName(table), table, null)\n        }\n    }\n''',\n    expected_matches=2,\n)\n"
if text.count(call_suffix) != 1:
    raise SystemExit(f"restore helper target-call shape drift: {text.count(call_suffix)}")
text = text.replace(call_suffix, patched_suffix, 1)
p.write_text(text)
print("made Phase37 baseline helper multiplicity explicit")
