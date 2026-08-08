from pathlib import Path

p = Path('backend/app.py')
s = p.read_text()

old = '''    "ASSERT_FACT",\n    "ASSERT_BELIEF",\n    "ASSERT_NARRATIVE",\n'''
new = '''    "ASSERT_FACT",\n    "ASSERT_BELIEF",\n    "KNOWLEDGE_PROPAGATE",\n    "ASSERT_NARRATIVE",\n'''
if s.count(old) != 1:
    raise SystemExit(f'action list anchor mismatch: {s.count(old)}')
s = s.replace(old, new, 1)

old = '''- proposed_actions are semantic requests only. Android's deterministic resolver decides whether they are legal and calculates durable consequences.\n- Use identifiers exactly as present in context. Do not fabricate actor_id/target_id when no reliable identifier is available; use null instead.\n'''
new = '''- proposed_actions are semantic requests only. Android's deterministic resolver decides whether they are legal and calculates durable consequences.\n- For new NPC knowledge use KNOWLEDGE_PROPAGATE, not a free-form ASSERT_BELIEF. It must point to durable source knowledge already present in context.\n- KNOWLEDGE_PROPAGATE parameters must include channel=OBSERVATION|REPORT|INFERENCE, source_subject_id, source_predicate and enough of source_truth_id/source_holder_id/source_value to identify exactly one durable source. REPORT must include source_npc_id. actor_id is the receiving NPC.\n- Never invent a knowledge transmission merely to make narration convenient. If no valid source path is present, the NPC does not gain the knowledge.\n- Use identifiers exactly as present in context. Do not fabricate actor_id/target_id when no reliable identifier is available; use null instead.\n'''
if s.count(old) != 1:
    raise SystemExit(f'prompt anchor mismatch: {s.count(old)}')
s = s.replace(old, new, 1)

p.write_text(s)
