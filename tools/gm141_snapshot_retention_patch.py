from pathlib import Path

p = Path("app/src/main/java/com/rpgos/app/SQLiteUnifiedCampaignRepository.kt")
s = p.read_text()

old = ") : UnifiedCampaignRepository, Closeable {"
new = ") : UnifiedCampaignRepository, SnapshotRetention141, Closeable {"
if s.count(old) != 1:
    raise SystemExit(f"class declaration mismatch: {s.count(old)}")
s = s.replace(old, new, 1)

old = '''        require(db.insertWithOnConflict("gm_turns", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "Nie można zapisać tury ${turn.turnId}."
        }
'''
new = '''        require(db.insertOrThrow("gm_turns", null, values) != -1L) {
            "Nie można zapisać tury ${turn.turnId}."
        }
'''
if s.count(old) != 1:
    raise SystemExit(f"writeTurn insert mismatch: {s.count(old)}")
s = s.replace(old, new, 1)

marker = "    override suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T {\n"
method = '''    override suspend fun pruneSnapshots(campaignUid: EntityUid, keepNewest: Int) {
        requireCampaign(campaignUid)
        require(keepNewest >= 1) { "keepNewest musi być >= 1." }
        require(transactionDepth == 0 && !db.inTransaction()) {
            "Retencji snapshotów nie wolno wykonywać wewnątrz aktywnej transakcji."
        }

        val currentSnapshotId = db.rawQuery(
            "SELECT current_snapshot_id FROM gm_campaign_meta WHERE campaign_id=? LIMIT 1",
            arrayOf(campaignUid.value)
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

        data class SnapshotFile(val id: String, val path: String?)
        val stale = mutableListOf<SnapshotFile>()
        db.rawQuery(
            """
            SELECT snapshot_id,storage_path FROM gm_snapshots
            WHERE campaign_id=?
            ORDER BY turn_number DESC, created_at DESC
            """.trimIndent(),
            arrayOf(campaignUid.value)
        ).use { c ->
            var index = 0
            while (c.moveToNext()) {
                val id = c.getString(0)
                val path = if (c.isNull(1)) null else c.getString(1)
                if (index >= keepNewest && id != currentSnapshotId) stale += SnapshotFile(id, path)
                index++
            }
        }

        stale.forEach { snapshot ->
            snapshot.path?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching { File(path).takeIf(File::exists)?.delete() }
            }
            db.delete(
                "gm_snapshots",
                "campaign_id=? AND snapshot_id=?",
                arrayOf(campaignUid.value, snapshot.id)
            )
        }
    }

'''
if s.count(marker) != 1:
    raise SystemExit(f"transaction marker mismatch: {s.count(marker)}")
s = s.replace(marker, method + marker, 1)
p.write_text(s)
