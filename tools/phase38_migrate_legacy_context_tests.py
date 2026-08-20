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

# Phase38 strict canonical reads correctly treat missing required schema as corruption.
# Older focused tests intentionally build only the tables needed by their original assertion,
# so complete only the unrelated ContextBuilder schema in those fixtures rather than weakening production fail-closed behavior.
fixture = Path("app/src/test/java/com/rpgos/app/Phase38LegacyContextFixtureSchema.kt")
fixture.write_text('''package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

internal object Phase38LegacyContextFixtureSchema {
    fun ensure(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS entity_positions(entity_uid TEXT,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS story_threads(thread_uid TEXT,title TEXT,thread_type TEXT,status TEXT,priority INTEGER,last_advanced_chapter INTEGER,description TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS missions_v3(mission_uid TEXT,title TEXT,mission_rank TEXT,status TEXT,objective_summary TEXT,reward_ryo INTEGER,deadline_day INTEGER,location_uid TEXT,consequence_on_failure TEXT)")
    }
}
''')

# Wrap migrated ContextBuilder calls so fixture completion happens immediately before the protected read.
replace("app/src/test/java/com/rpgos/app/InventoryContextBuilderTest.kt",
        'ContextBuilder(save,world).build("look",1,VisibilityAudienceFactory.player("C"),PurposeContext("C",VisibilityPurposeKinds.GAMEPLAY_NARRATION))',
        'run { Phase38LegacyContextFixtureSchema.ensure(save); ContextBuilder(save,world).build("look",1,VisibilityAudienceFactory.player("C"),PurposeContext("C",VisibilityPurposeKinds.GAMEPLAY_NARRATION)) }')
replace("app/src/test/java/com/rpgos/app/TechniqueContextBuilderTest.kt",
        'ContextBuilder(db, db).build("status",1,VisibilityAudienceFactory.player(campaignId),PurposeContext(campaignId,VisibilityPurposeKinds.GAMEPLAY_NARRATION))',
        'run { Phase38LegacyContextFixtureSchema.ensure(db); ContextBuilder(db, db).build("status",1,VisibilityAudienceFactory.player(campaignId),PurposeContext(campaignId,VisibilityPurposeKinds.GAMEPLAY_NARRATION)) }')
replace("app/src/test/java/com/rpgos/app/Phase32ContextBuilderTruthReadTest.kt",
        'ContextBuilder(save,world).build("look",1,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))',
        'run { Phase38LegacyContextFixtureSchema.ensure(save); ContextBuilder(save,world).build("look",1,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }')
replace("app/src/test/java/com/rpgos/app/Phase32ContextBuilderTruthReadTest.kt",
        'ContextBuilder(save,world).build("look again",2,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))',
        'run { Phase38LegacyContextFixtureSchema.ensure(save); ContextBuilder(save,world).build("look again",2,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }')
replace("app/src/test/java/com/rpgos/app/Phase32ContextCanonicalDomainsTest.kt",
        'ContextBuilder(save,world).build("inspect canonical domains",1,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))',
        'run { Phase38LegacyContextFixtureSchema.ensure(save); ContextBuilder(save,world).build("inspect canonical domains",1,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }')
replace("app/src/test/java/com/rpgos/app/Phase32ContextCanonicalDomainsTest.kt",
        'ContextBuilder(save,world).build("rebuild canonical domains",2,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))',
        'run { Phase38LegacyContextFixtureSchema.ensure(save); ContextBuilder(save,world).build("rebuild canonical domains",2,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }')
replace("app/src/test/java/com/rpgos/app/Phase32LegacyUnknownProjectionTest.kt",
        '.build("inspect legacy history",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))',
        '.let { builder -> Phase38LegacyContextFixtureSchema.ensure(db); builder.build("inspect legacy history",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }')
replace("app/src/test/java/com/rpgos/app/Phase32OwnershipIsolationTest.kt",
        'ContextBuilder(db,world).build("inspect",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))',
        'run { Phase38LegacyContextFixtureSchema.ensure(db); ContextBuilder(db,world).build("inspect",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }')
replace("app/src/test/java/com/rpgos/app/Phase32TruthTypeEndToEndTest.kt",
        'ContextBuilder(db,world).build("inspect truth",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))',
        'run { Phase38LegacyContextFixtureSchema.ensure(db); ContextBuilder(db,world).build("inspect truth",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }')

# This regression reaches ContextBuilder through LocalGameStore; complete its setup DB fixture before the read.
replace("app/src/test/java/com/rpgos/app/Phase32BuildContextNoRepairRegressionTest.kt",
        'Phase32ProductionReadyTestFixture.setup(db, campaignUid)\n            assertFalse(tableExists(db, "rpgos_repair_log"))',
        'Phase32ProductionReadyTestFixture.setup(db, campaignUid)\n            Phase38LegacyContextFixtureSchema.ensure(db)\n            assertFalse(tableExists(db, "rpgos_repair_log"))')

print("Legacy ContextBuilder tests migrated to explicit Phase38 contexts and complete strict-read fixtures")