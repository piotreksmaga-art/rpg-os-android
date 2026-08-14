from pathlib import Path

# These are Phase-18/generic test fixtures: make the no-rule mode explicit.
for path in [
    'app/src/test/java/com/rpgos/app/PlayerDomainEngineReferenceClassificationTest.kt',
    'app/src/test/java/com/rpgos/app/PlayerDomainEngineReferenceFinanceTest.kt',
    'app/src/test/java/com/rpgos/app/PlayerDomainEngineReferenceRegressionTest.kt',
]:
    p=Path(path); s=p.read_text(); s=s.replace('PlayerResolutionContext.create(', 'PlayerResolutionContext.createUnboundGeneric('); p.write_text(s)

p=Path('app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19HardeningTest.kt')
s=p.read_text()
s=s.replace('r.rejection.worldRuleDecision!!.stage', 'r.evidence.worldRuleDecisions.single().stage', 1)
s=s.replace('r.rejection.worldRuleDecision!!.stage', 'r.evidence.worldRuleDecisions.last().stage', 1)
s=s.replace('''private fun train()=PlayerCommand("CMD-HARDEN","C1",actor,PlayerCommandKinds.TRAIN,TrainCommandPayload(DomainRef("STAT","STR"),10L,"METHOD"),CommandProvenance("TEST"))''', '''private fun train()=PlayerCommand(
        commandUid="CMD-HARDEN", campaignUid="C1", actor=actor,
        commandKindUid=PlayerCommandKinds.TRAIN,
        payload=TrainCommandPayload(DomainRef("STAT","STR"),10L,"METHOD"),
        provenance=CommandProvenance("TEST")
    )''')
s=s.replace('private open class BaseProvider(', 'private abstract class BaseProvider(')
p.write_text(s)
print('compile call sites fixed')
