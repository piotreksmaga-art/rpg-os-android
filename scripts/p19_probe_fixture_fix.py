from pathlib import Path
p = Path('app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19FinalHotfixTest.kt')
s = p.read_text(encoding='utf-8')
old = '''    private class ProbeProvider(
        private val binding: WorldPackRuleBinding,
        private val allow: Boolean
    ) : WorldRuleProvider("PROBE-${binding.worldPackUid}", "1", binding.worldPackUid, binding.worldPackVersion) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            if (binding.worldPackUid == "WORLD-A") InvocationProbe.worldACalls++ else InvocationProbe.worldBCalls++
            return if (allow) WorldRuleDecision.Allowed.create("RULE")
            else WorldRuleDecision.Rejected.create("RULE", "DENY")
        }
    }
'''
new = '''    private class ProbeProvider(
        binding: WorldPackRuleBinding,
        private val allow: Boolean
    ) : WorldRuleProvider("PROBE-${binding.worldPackUid}", "1", binding.worldPackUid, binding.worldPackVersion) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            if (worldPackUid == "WORLD-A") InvocationProbe.worldACalls++ else InvocationProbe.worldBCalls++
            return if (allow) WorldRuleDecision.Allowed.create("RULE")
            else WorldRuleDecision.Rejected.create("RULE", "DENY")
        }
    }
'''
if s.count(old) != 1:
    raise SystemExit('ProbeProvider fixture marker missing or non-unique')
p.write_text(s.replace(old,new,1), encoding='utf-8')
