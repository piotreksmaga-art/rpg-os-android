from pathlib import Path

def rep(path,old,new):
 p=Path(path);s=p.read_text();n=s.count(old)
 if n!=1: raise SystemExit(f'{path}: expected 1 match got {n}')
 p.write_text(s.replace(old,new))

p='app/src/main/java/com/rpgos/app/CampaignEventStore.kt'
rep(p,'        migrateEventTableIfNeeded(db)\n        installEventTriggers(db)','''        when(physicalEventSchemaVersion(db)) {
            null -> { createCurrentEventTable(db,EVENT_TABLE); createEventIndexes(db) }
            PHASE30_EVENT_SCHEMA_VERSION -> createEventIndexes(db)
            else -> error("RPGOS-PHASE30:LEGACY_EVENT_MIGRATION_REQUIRES_PHASE36")
        }
        installEventTriggers(db)''')
rep(p,'    private fun migrateEventTableIfNeeded(db: SQLiteDatabase) {','''    internal fun physicalEventSchemaVersion(db:SQLiteDatabase):Int? {
        if(!tableExists(db,EVENT_TABLE)) {
            if(tableExists(db,"canonical_gameplay_events_v2_new")) error("RPGOS-SCHEMA:EVENT_STAGING_WITHOUT_CANONICAL_TABLE")
            return null
        }
        return if(hasColumn(db,EVENT_TABLE,"event_ordinal")&&!eventTableHasLegacyUniqueCommittedOrder(db)) PHASE30_EVENT_SCHEMA_VERSION else 1
    }

    internal fun migrateEventTableIfNeeded(db: SQLiteDatabase, injector:Phase36MigrationFailureInjector=Phase36MigrationFailureInjector.NONE) {''')
rep(p,'''        listOf("rpgos_event_store_no_update", "rpgos_event_store_no_delete", "rpgos_event_store_turn_insert").forEach {
            db.execSQL("DROP TRIGGER IF EXISTS $it")
        }
        db.execSQL("DROP TABLE IF EXISTS canonical_gameplay_events_v2_new")
        createCurrentEventTable(db, "canonical_gameplay_events_v2_new")
''','''        listOf("rpgos_event_store_no_update", "rpgos_event_store_no_delete", "rpgos_event_store_turn_insert").forEach {
            db.execSQL("DROP TRIGGER IF EXISTS $it")
        }
        injector.failIfRequested(Phase36MigrationFailurePoint.BEFORE_STAGING_CREATE)
        db.execSQL("DROP TABLE IF EXISTS canonical_gameplay_events_v2_new")
        createCurrentEventTable(db, "canonical_gameplay_events_v2_new")
        injector.failIfRequested(Phase36MigrationFailurePoint.AFTER_STAGING_CREATE)
''')
rep(p,'''        // legacyOrderExpr is deliberately not used as a fallback: an Event-local sequence is not proof of Phase29 order.
        @Suppress("UNUSED_VARIABLE") val ignoredLegacyOrder = legacyOrderExpr
        db.execSQL("DROP TABLE $EVENT_TABLE")
        db.execSQL("ALTER TABLE canonical_gameplay_events_v2_new RENAME TO $EVENT_TABLE")
        createEventIndexes(db)
''','''        injector.failIfRequested(Phase36MigrationFailurePoint.AFTER_COPY)
        // legacyOrderExpr is deliberately not used as a fallback: an Event-local sequence is not proof of Phase29 order.
        @Suppress("UNUSED_VARIABLE") val ignoredLegacyOrder = legacyOrderExpr
        injector.failIfRequested(Phase36MigrationFailurePoint.BEFORE_DROP)
        db.execSQL("DROP TABLE $EVENT_TABLE")
        injector.failIfRequested(Phase36MigrationFailurePoint.AFTER_DROP)
        db.execSQL("ALTER TABLE canonical_gameplay_events_v2_new RENAME TO $EVENT_TABLE")
        injector.failIfRequested(Phase36MigrationFailurePoint.AFTER_RENAME)
        createEventIndexes(db)
''')
print('Phase36 Event Store patched')
