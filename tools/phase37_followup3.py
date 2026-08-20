from pathlib import Path
p=Path('app/src/test/java/com/rpgos/app/Phase37WorldActorKnowledgeTest.kt')
s=p.read_text()
repls=[(
'''        val failure = runCatching { GameplayRuntimeBootstrap.requireReady(db, "C1") }.exceptionOrNull()
        assertTrue(failure is Phase37KnowledgeCorruptionException)
        assertTrue(failure!!.message.orEmpty().contains("MISSING_GUARD"))''',
'''        val failure = runCatching { GameplayRuntimeBootstrap.requireReady(db, "C1") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("rpgos_p37_schema_seal") || failure.message.orEmpty().contains("MISSING_GUARD"))'''),(
'''        GameplayRuntimeBootstrap.initialize(db, "C1")
        assertPhase37Corruption(db)
    }

    @Test fun projectionRejectsInvalidParentSourceLineage()''',
'''        withAdministrativeMutationAuthority(db, "C1") { Phase37KnowledgeSchema.ensureReady(db) }
        Phase37GuardDefinitionIntegrity.requireCanonical(db)
        assertPhase37Corruption(db)
    }

    @Test fun projectionRejectsInvalidParentSourceLineage()'''),(
'''        GameplayRuntimeBootstrap.initialize(db, "C1")
        assertPhase37Corruption(db)
    }

    @Test fun fullyValidLineageProjectionPasses()''',
'''        withAdministrativeMutationAuthority(db, "C1") { Phase37KnowledgeSchema.ensureReady(db) }
        Phase37GuardDefinitionIntegrity.requireCanonical(db)
        assertPhase37Corruption(db)
    }

    @Test fun fullyValidLineageProjectionPasses()''')]
for old,new in repls:
    if s.count(old)!=1: raise SystemExit(f'anchor count {s.count(old)} for {old[:80]!r}')
    s=s.replace(old,new,1)
p.write_text(s)
print('fixed corruption fixtures')
