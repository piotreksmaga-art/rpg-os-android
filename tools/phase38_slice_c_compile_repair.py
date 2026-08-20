from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def rep(rel: str, old: str, new: str) -> None:
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing anchor {rel}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Repair the central Slice C fixup so a future application cannot recreate the duplicate.
rep(
    "tools/phase38_integrated_fixups.py",
    '''        val principal=audience.principal?:return null\n        if(audience.audienceKindUid in setOf(AudienceKinds.GM_RUNTIME,AudienceKinds.INTERNAL_SYSTEM,AudienceKinds.DEVELOPER_DIAGNOSTIC))return null\n        return TrustedPrincipalContext(audience.campaignUid,principal,audience.audienceKindUid,controls,roles,orgs,clearances,cognitionResolver.holdersFor(audience.campaignUid,principal))\n''',
    '''        if(audience.audienceKindUid in setOf(AudienceKinds.GM_RUNTIME,AudienceKinds.INTERNAL_SYSTEM,AudienceKinds.DEVELOPER_DIAGNOSTIC))return null\n        return TrustedPrincipalContext(audience.campaignUid,principal,audience.audienceKindUid,controls,roles,orgs,clearances,cognitionResolver.holdersFor(audience.campaignUid,principal))\n''',
)

# The materialized candidate already contains the duplicate produced by the old fixup.
rep(
    "app/src/main/java/com/rpgos/app/Phase38AccessAuthority.kt",
    '''        val controls=records.filter{it.kindUid==AccessBindingKind.CONTROL.name}.mapNotNull{it.subjectUid}.toSet()\n        val principal=audience.principal?:return null\n        if(audience.audienceKindUid in setOf(AudienceKinds.GM_RUNTIME,AudienceKinds.INTERNAL_SYSTEM,AudienceKinds.DEVELOPER_DIAGNOSTIC))return null\n''',
    '''        val controls=records.filter{it.kindUid==AccessBindingKind.CONTROL.name}.mapNotNull{it.subjectUid}.toSet()\n        if(audience.audienceKindUid in setOf(AudienceKinds.GM_RUNTIME,AudienceKinds.INTERNAL_SYSTEM,AudienceKinds.DEVELOPER_DIAGNOSTIC))return null\n''',
)

# A new canonical payload must participate in the existing reference-validation pipeline.
rep(
    "app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt",
    '''            is KnowledgeAcquisitionChange -> {\n                add(DomainRef(payload.acquisition.holder.holderKindUid, payload.acquisition.holder.holderUid))\n                payload.acquisition.sourceHolder?.let { add(DomainRef(it.holderKindUid, it.holderUid)) }\n                payload.evidence.forEach { e ->\n                    e.sourceRef?.let { source ->\n                        if (source.scope == KnowledgeReferenceScope.CAMPAIGN) {\n                            add(DomainRef(source.kindUid, source.entityUid))\n                        }\n                    }\n                }\n            }\n''',
    '''            is KnowledgeAcquisitionChange -> {\n                add(DomainRef(payload.acquisition.holder.holderKindUid, payload.acquisition.holder.holderUid))\n                payload.acquisition.sourceHolder?.let { add(DomainRef(it.holderKindUid, it.holderUid)) }\n                payload.evidence.forEach { e ->\n                    e.sourceRef?.let { source ->\n                        if (source.scope == KnowledgeReferenceScope.CAMPAIGN) {\n                            add(DomainRef(source.kindUid, source.entityUid))\n                        }\n                    }\n                }\n            }\n            is AccessAuthorityChange -> {\n                add(DomainRef(payload.principalKindUid, payload.principalUid))\n                if (payload.subjectKindUid != null && payload.subjectUid != null) {\n                    add(DomainRef(payload.subjectKindUid, payload.subjectUid))\n                }\n            }\n''',
)

# World-rule fingerprints must remain exhaustive and deterministic for the new canonical payload.
rep(
    "app/src/main/java/com/rpgos/app/WorldRuleProvider.kt",
    '''        is DevelopmentProjectChange -> "DEVELOPMENT_PROJECT_CHANGE"\n        is KnowledgeAcquisitionChange -> "KNOWLEDGE_ACQUISITION_CHANGE"\n''',
    '''        is DevelopmentProjectChange -> "DEVELOPMENT_PROJECT_CHANGE"\n        is KnowledgeAcquisitionChange -> "KNOWLEDGE_ACQUISITION_CHANGE"\n        is AccessAuthorityChange -> "ACCESS_AUTHORITY_CHANGE"\n''',
)

rep(
    "app/src/main/java/com/rpgos/app/WorldRuleProvider.kt",
    '''            is KnowledgeAcquisitionChange -> {\n                section("CLAIM") {\n''',
    '''            is AccessAuthorityChange -> {\n                field("OPERATION", payload.operation.name)\n                field("RECORD_UID", payload.recordUid)\n                field("PRINCIPAL_KIND_UID", payload.principalKindUid)\n                field("PRINCIPAL_UID", payload.principalUid)\n                field("BINDING_OR_GRANT_KIND_UID", payload.bindingOrGrantKindUid)\n                field("VALUE_UID", payload.valueUid)\n                nullableField("SUBJECT_KIND_UID", payload.subjectKindUid)\n                nullableField("SUBJECT_UID", payload.subjectUid)\n                longField("VALID_FROM_ORDER", payload.validFromOrder)\n                nullableLongField("VALID_UNTIL_ORDER", payload.validUntilOrder)\n                nullableField("DELEGATED_BY_PRINCIPAL_UID", payload.delegatedByPrincipalUid)\n            }\n            is KnowledgeAcquisitionChange -> {\n                section("CLAIM") {\n''',
)

print("phase38 slice-c compile repair applied")
