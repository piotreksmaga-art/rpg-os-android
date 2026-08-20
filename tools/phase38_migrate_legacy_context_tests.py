from pathlib import Path

def replace(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"missing legacy test anchor: {path}: {old}")
    p.write_text(s.replace(old, new))

P = "VisibilityPurposeKinds"

# Player-facing canonical status/inventory/technique reads.
replace("app/src/test/java/com/rpgos/app/InventoryContextBuilderTest.kt",
        'ContextBuilder(save,world).build("look",1)',
        'ContextBuilder(save,world).build("look",1,VisibilityAudienceFactory.player("C"),PurposeContext("C",VisibilityPurposeKinds.GAMEPLAY_NARRATION))')
replace("app/src/test/java/com/rpgos/app/TechniqueContextBuilderTest.kt",
        'ContextBuilder(db, db).build("status", 1)',
        'ContextBuilder(db, db).build("status",1,VisibilityAudienceFactory.player(campaignId),PurposeContext(campaignId,VisibilityPurposeKinds.GAMEPLAY_NARRATION))')

# These Phase32 regressions intentionally inspect canonical/internal truth. Preserve that intent with explicit diagnostic authority.
replace("app/src/test/java/com/rpgos/app/Phase32ContextBuilderTruthReadTest.kt",
        'ContextBuilder(save, world).build("look", 1)',
        'ContextBuilder(save,world).build("look",1,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))')
replace("app/src/test/java/com/rpgos/app/Phase32ContextBuilderTruthReadTest.kt",
        'ContextBuilder(save, world).build("look again", 2)',
        'ContextBuilder(save,world).build("look again",2,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))')
replace("app/src/test/java/com/rpgos/app/Phase32ContextCanonicalDomainsTest.kt",
        'ContextBuilder(save, world).build("inspect canonical domains", 1)',
        'ContextBuilder(save,world).build("inspect canonical domains",1,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))')
replace("app/src/test/java/com/rpgos/app/Phase32ContextCanonicalDomainsTest.kt",
        'ContextBuilder(save, world).build("rebuild canonical domains", 2)',
        'ContextBuilder(save,world).build("rebuild canonical domains",2,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))')
replace("app/src/test/java/com/rpgos/app/Phase32LegacyUnknownProjectionTest.kt",
        '.build("inspect legacy history", 1)',
        '.build("inspect legacy history",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))')
replace("app/src/test/java/com/rpgos/app/Phase32OwnershipIsolationTest.kt",
        'ContextBuilder(db, world).build("inspect", 1)',
        'ContextBuilder(db,world).build("inspect",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))')
replace("app/src/test/java/com/rpgos/app/Phase32TruthTypeEndToEndTest.kt",
        'ContextBuilder(db, world).build("inspect truth", 1)',
        'ContextBuilder(db,world).build("inspect truth",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))')

print("Legacy ContextBuilder tests migrated to explicit Phase38 contexts")
