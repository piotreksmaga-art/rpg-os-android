from pathlib import Path

def patch(path, old, new):
    p=Path(path); s=p.read_text();
    if old not in s: raise SystemExit(f'missing {old!r} in {path}')
    p.write_text(s.replace(old,new,1))

patch('app/src/main/java/com/rpgos/app/WorldRuleProvider.kt','    data object UnboundGeneric : WorldRuleMode','    internal data object UnboundGeneric : WorldRuleMode')
patch('app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt','        fun createUnboundGeneric(','        internal fun createUnboundGeneric(')
print('authority visibility hardened')
