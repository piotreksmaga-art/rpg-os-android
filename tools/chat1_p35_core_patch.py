from pathlib import Path

def rep(path, old, new):
    p=Path(path); s=p.read_text(); n=s.count(old)
    if n!=1: raise SystemExit(f'{path}: expected 1 match, got {n}')
    p.write_text(s.replace(old,new))

p='app/src/main/java/com/rpgos/app/Phase35CanonDivergence.kt'
rep(p, '''data class CanonDivergenceRecord(
    val campaignUid: String,
    val spec: CanonDivergenceSpec,
    val createdTransactionUid: String?,
    val createdTurnUid: String?,
    val createdEventUid: String?,
    val createdAtEpochMs: Long
)
''', '''data class CanonDivergenceRecord(
    val campaignUid: String,
    val spec: CanonDivergenceSpec,
    val createdTransactionUid: String?,
    val createdTurnUid: String?,
    val createdEventUid: String?,
    val createdAtEpochMs: Long
)

data class CanonicalWorldExpectation(
    val canonicalReference: CanonReference,
    val kind: CanonDivergenceKind,
    val expectedCanonicalValue: String
) { init { require(expectedCanonicalValue.isNotBlank()) } }

internal data class CanonicalDivergenceCommitAuthorization(
    val spec: CanonDivergenceSpec,
    val eventUid: String
)

private data class ActiveCanonDivergenceAuthority(
    val db: SQLiteDatabase,
    val identity: TurnTransactionIdentity,
    val authorizations: List<CanonicalDivergenceCommitAuthorization>
)
private val activeCanonDivergenceAuthority=ThreadLocal<ActiveCanonDivergenceAuthority?>()

internal fun <T> withCanonicalDivergenceCommitAuthorityForTurn(
    db:SQLiteDatabase, identity:TurnTransactionIdentity, seal:Any,
    authorizations:List<CanonicalDivergenceCommitAuthorization>, block:()->T
):T {
    require(TurnTransactionBoundary.acceptsCanonicalSeal(seal)){"RPGOS-CANON:FORGED_TURN_AUTHORITY"}
    check(db.inTransaction()){"RPGOS-CANON:AUTHORITY_OUTSIDE_TRANSACTION"}
    check(isCanonicalGameplayMutationActive(db,identity.campaignUid)){"RPGOS-CANON:CANONICAL_GAMEPLAY_AUTHORITY_REQUIRED"}
    check(activeCanonDivergenceAuthority.get()==null){"RPGOS-CANON:NESTED_AUTHORITY"}
    val frozen=authorizations.toList()
    require(frozen.all{it.spec.provenanceStatus==HistoricalProvenanceStatus.RECORDED&&it.eventUid.isNotBlank()})
    activeCanonDivergenceAuthority.set(ActiveCanonDivergenceAuthority(db,identity,frozen))
    return try{block()}finally{activeCanonDivergenceAuthority.remove()}
}
''')
rep(p, '''        db.execSQL("CREATE INDEX IF NOT EXISTS idx_canon_divergence_campaign ON $TABLE(campaign_uid,lifecycle_status,created_at_epoch_ms,divergence_uid)")
''', '''        db.execSQL("CREATE INDEX IF NOT EXISTS idx_canon_divergence_campaign ON $TABLE(campaign_uid,lifecycle_status,created_at_epoch_ms,divergence_uid)")
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_canon_divergence_recorded_provenance_insert")
        db.execSQL("""CREATE TRIGGER rpgos_canon_divergence_recorded_provenance_insert BEFORE INSERT ON $TABLE
            WHEN NEW.provenance_status='RECORDED' AND (
              NEW.created_transaction_uid IS NULL OR NEW.created_turn_uid IS NULL OR NEW.created_event_uid IS NULL OR
              NOT EXISTS(SELECT 1 FROM turn_transaction_receipts r WHERE r.transaction_uid=NEW.created_transaction_uid AND r.campaign_uid=NEW.campaign_uid AND r.turn_uid=NEW.created_turn_uid AND r.commit_state='COMMITTED') OR
              NOT EXISTS(SELECT 1 FROM canonical_gameplay_events e WHERE e.event_uid=NEW.created_event_uid AND e.campaign_uid=NEW.campaign_uid AND e.transaction_uid=NEW.created_transaction_uid AND e.turn_uid=NEW.created_turn_uid) OR
              NOT EXISTS(SELECT 1 FROM canonical_turn_replay_payloads p WHERE p.transaction_uid=NEW.created_transaction_uid AND p.campaign_uid=NEW.campaign_uid AND p.turn_uid=NEW.created_turn_uid))
            BEGIN SELECT RAISE(ABORT,'RPGOS-CANON:CANONICAL_COMMIT_EVIDENCE_REQUIRED'); END""")
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_canon_divergence_lifecycle_insert")
        db.execSQL("""CREATE TRIGGER rpgos_canon_divergence_lifecycle_insert BEFORE INSERT ON $TABLE
            WHEN NEW.divergence_uid=NEW.supersedes_divergence_uid OR NEW.divergence_uid=NEW.resolves_divergence_uid OR
              (NEW.supersedes_divergence_uid IS NOT NULL AND NEW.resolves_divergence_uid IS NOT NULL) OR
              (NEW.lifecycle_status='ACTIVE' AND NEW.resolves_divergence_uid IS NOT NULL) OR
              (NEW.lifecycle_status='SUPERSEDED' AND (NEW.supersedes_divergence_uid IS NULL OR NEW.resolves_divergence_uid IS NOT NULL)) OR
              (NEW.lifecycle_status='RESOLVED' AND (NEW.resolves_divergence_uid IS NULL OR NEW.supersedes_divergence_uid IS NOT NULL)) OR
              (NEW.supersedes_divergence_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM $TABLE d WHERE d.divergence_uid=NEW.supersedes_divergence_uid AND d.campaign_uid=NEW.campaign_uid)) OR
              (NEW.resolves_divergence_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM $TABLE d WHERE d.divergence_uid=NEW.resolves_divergence_uid AND d.campaign_uid=NEW.campaign_uid))
            BEGIN SELECT RAISE(ABORT,'RPGOS-CANON:INVALID_DIVERGENCE_LIFECYCLE'); END""")
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_canon_divergence_no_update")
        db.execSQL("CREATE TRIGGER rpgos_canon_divergence_no_update BEFORE UPDATE ON $TABLE BEGIN SELECT RAISE(ABORT,'RPGOS-CANON:DIVERGENCE_APPEND_ONLY'); END")
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_canon_divergence_no_delete")
        db.execSQL("CREATE TRIGGER rpgos_canon_divergence_no_delete BEFORE DELETE ON $TABLE BEGIN SELECT RAISE(ABORT,'RPGOS-CANON:DIVERGENCE_APPEND_ONLY'); END")
''')
rep(p, '''        require(spec.provenanceStatus != HistoricalProvenanceStatus.RECORDED)
        return insert(spec, null, null, null, System.currentTimeMillis())
''', '''        require(spec.provenanceStatus != HistoricalProvenanceStatus.RECORDED){"RPGOS-CANON:RECORDED_REQUIRES_CANONICAL_TURN"}
        validateLifecycle(spec)
        return insert(spec, null, null, null, System.currentTimeMillis())
''')
rep(p, '''        check(db.inTransaction()) { "RPGOS-CANON:OUTSIDE_TURN" }
        require(spec.provenanceStatus == HistoricalProvenanceStatus.RECORDED)
        return insert(spec, identity.transactionUid, identity.turnUid, eventUid, spec.effectiveFrom ?: 0L)
    }

    private fun insert''', '''        check(db.inTransaction()) { "RPGOS-CANON:OUTSIDE_TURN" }
        require(identity.campaignUid==campaignUid){"RPGOS-CANON:CAMPAIGN_IDENTITY_MISMATCH"}
        require(spec.provenanceStatus == HistoricalProvenanceStatus.RECORDED)
        val a=activeCanonDivergenceAuthority.get()?:error("RPGOS-CANON:CANONICAL_TURN_AUTHORITY_REQUIRED")
        require(a.db===db&&a.identity==identity){"RPGOS-CANON:CANONICAL_TURN_AUTHORITY_MISMATCH"}
        require(a.authorizations.any{it.spec==spec&&it.eventUid==eventUid}){"RPGOS-CANON:DIVERGENCE_NOT_AUTHORIZED_BY_CANONICAL_PROPOSAL"}
        requireCanonicalCommitEvidence(identity,eventUid)
        validateLifecycle(spec)
        return insert(spec, identity.transactionUid, identity.turnUid, eventUid, spec.effectiveFrom ?: 0L)
    }

    private fun requireCanonicalCommitEvidence(identity:TurnTransactionIdentity,eventUid:String){
        val receipt=TurnTransactionReceiptStore(db).committedTransaction(identity.transactionUid)?:error("RPGOS-CANON:NONEXISTENT_CANONICAL_TRANSACTION")
        require(receipt.campaignUid==campaignUid&&receipt.turnUid==identity.turnUid&&receipt.commandUid==identity.commandUid){"RPGOS-CANON:CANONICAL_TRANSACTION_IDENTITY_MISMATCH"}
        require(db.rawQuery("SELECT 1 FROM canonical_gameplay_events WHERE campaign_uid=? AND event_uid=? AND transaction_uid=? AND turn_uid=? AND command_uid=? LIMIT 1",arrayOf(campaignUid,eventUid,identity.transactionUid,identity.turnUid,identity.commandUid)).use{it.moveToFirst()}){"RPGOS-CANON:NONEXISTENT_CANONICAL_EVENT"}
        require(db.rawQuery("SELECT 1 FROM canonical_turn_replay_payloads WHERE campaign_uid=? AND transaction_uid=? AND turn_uid=? AND command_uid=? LIMIT 1",arrayOf(campaignUid,identity.transactionUid,identity.turnUid,identity.commandUid)).use{it.moveToFirst()}){"RPGOS-CANON:NONEXISTENT_CANONICAL_REPLAY_EVIDENCE"}
    }

    private fun validateLifecycle(spec:CanonDivergenceSpec){
        require(spec.supersedesDivergenceUid!=spec.divergenceUid&&spec.resolvesDivergenceUid!=spec.divergenceUid){"RPGOS-CANON:DIVERGENCE_SELF_REFERENCE"}
        require(spec.supersedesDivergenceUid==null||spec.resolvesDivergenceUid==null){"RPGOS-CANON:MULTIPLE_LIFECYCLE_LINKS"}
        when(spec.status){
            CanonDivergenceStatus.ACTIVE->require(spec.resolvesDivergenceUid==null){"RPGOS-CANON:ACTIVE_CANNOT_RESOLVE"}
            CanonDivergenceStatus.SUPERSEDED->require(spec.supersedesDivergenceUid!=null&&spec.resolvesDivergenceUid==null){"RPGOS-CANON:SUPERSEDED_REQUIRES_SUPERSEDES_LINK"}
            CanonDivergenceStatus.RESOLVED->require(spec.resolvesDivergenceUid!=null&&spec.supersedesDivergenceUid==null){"RPGOS-CANON:RESOLVED_REQUIRES_RESOLVES_LINK"}
        }
        listOfNotNull(spec.supersedesDivergenceUid,spec.resolvesDivergenceUid).forEach{uid->
            val target=db.rawQuery("SELECT campaign_uid FROM ${Phase35CanonDivergenceSchema.TABLE} WHERE divergence_uid=? LIMIT 1",arrayOf(uid)).use{c->if(c.moveToFirst())c.getString(0)else null}?:error("RPGOS-CANON:LIFECYCLE_TARGET_NOT_FOUND")
            require(target==campaignUid){"RPGOS-CANON:LIFECYCLE_TARGET_WRONG_CAMPAIGN"}
        }
    }

    private fun insert''')
print('Phase35 core patched')
