from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "app/src/main/java/com/rpgos/app/WorldRuleProvider.kt"
text = p.read_text()
old = '        is CampaignTruthChange -> "CAMPAIGN_TRUTH_CHANGE"\n        is ConditionChange -> "CONDITION_CHANGE"'
new = '        is CampaignTruthChange -> "CAMPAIGN_TRUTH_CHANGE"\n        is NpcKnowledgeChange -> "NPC_KNOWLEDGE_CHANGE"\n        is ConditionChange -> "CONDITION_CHANGE"'
count = text.count(old)
if count != 1:
    raise SystemExit(f"WorldRuleProvider payloadType anchor mismatch: {count}")
p.write_text(text.replace(old, new, 1))
print("Phase37 payloadType exhaustiveness patch applied")
