from pathlib import Path
p=Path('app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt')
s=p.read_text()
s=s.replace('import java.security.MessageDigest\n','')
old='''        val changeSetUid = "RPGOS-CS18:" + sha256(
            buildString {
                appendToken(commandRegistry.encode(command))
                appendToken(contextFingerprint)
                appendToken(component.componentKindUid)
                appendToken(component.componentVersion)
                ruleDecisions.forEach { appendToken(it.decisionFingerprint) }
            }
        )'''
new='''        val changeSetUid = "RPGOS-CS18:" + WorldRuleCanonicalWriter.fingerprint("PLAYER_DOMAIN_PROPOSAL") {
            field("COMMAND_ENCODING", commandRegistry.encode(command))
            field("CONTEXT_FINGERPRINT", contextFingerprint)
            section("COMPONENT") {
                field("KIND_UID", component.componentKindUid)
                field("VERSION", component.componentVersion)
            }
            list("WORLD_RULE_DECISIONS", ruleDecisions) { decision ->
                record("WORLD_RULE_DECISION_FINGERPRINT") {
                    field("FINGERPRINT", decision.decisionFingerprint)
                }
            }
        }'''
if old not in s: raise SystemExit('proposal block not found')
s=s.replace(old,new,1)
old2='''\nprivate fun StringBuilder.appendToken(value: String) {
    append(value.length).append(':').append(value).append('|')
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
'''
if old2 not in s: raise SystemExit('legacy helpers not found')
s=s.replace(old2,'\n',1)
p.write_text(s)
print('proposal identity migrated to structural canonical writer')
