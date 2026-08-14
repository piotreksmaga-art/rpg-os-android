from pathlib import Path

wp=Path('app/src/main/java/com/rpgos/app/WorldRuleProvider.kt')
s=wp.read_text()
s=s.replace('''sealed interface WorldRuleMode {
    data class Bound(val binding: WorldPackRuleBinding) : WorldRuleMode
    internal data object UnboundGeneric : WorldRuleMode
}
''','''sealed interface WorldRuleMode {
    data class Bound(val binding: WorldPackRuleBinding) : WorldRuleMode
}

internal data object UnboundGenericWorldRuleMode : WorldRuleMode
''')
wp.write_text(s)

pe=Path('app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt')
s=pe.read_text().replace('WorldRuleMode.UnboundGeneric','UnboundGenericWorldRuleMode')
pe.write_text(s)

for path in [
 'app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19Test.kt',
 'app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19BlockerReproductionTest.kt',
 'app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19HardeningTest.kt',
]:
    p=Path(path); t=p.read_text().replace('WorldRuleMode.UnboundGeneric','UnboundGenericWorldRuleMode'); p.write_text(t)
print('unbound mode moved to internal top-level implementation')
