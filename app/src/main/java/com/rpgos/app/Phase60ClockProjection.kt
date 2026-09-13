package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.util.Locale

/** Presentation only: never publishes process state and never mutates either clock. */
internal object Phase60ClockProjection {
    fun project(db: SQLiteDatabase, campaignUid: String, legacy: Map<String, Any?>): Map<String, Any?> {
        if (!Phase60TemporalSchema.isReady(db)) return legacy
        val tick = db.rawQuery("SELECT world_time_ms FROM ${Phase60TemporalSchema.TABLE} WHERE campaign_uid=?", arrayOf(campaignUid)).use {
            if (!it.moveToFirst()) return legacy
            WorldTimeTick(it.getLong(0))
        }
        val reading = WorldCalendarReading.fromTick(tick)
        val anchorDay = (legacy["absolute_day"] as? Number)?.toLong()
            ?: legacy["absolute_day"]?.toString()?.toLongOrNull()
        return legacy.toMutableMap().apply {
            put("absolute_day", reading.absoluteDay)
            put("hour", reading.hour)
            put("minute", reading.minute)
            put("millisecond_of_minute", reading.millisecondOfMinute)
            put("world_time_ms", tick.milliseconds)
            // Calendar-specific year/season rules must not be guessed from a 24-hour axis.
            if (anchorDay != reading.absoluteDay) {
                put("year_label", "Dzień ${reading.absoluteDay}")
                put("season", null)
            }
        }
    }

    fun snapshot(values: Map<String, Any?>): TimeSnapshot = TimeSnapshot(
        label = values["year_label"]?.toString() ?: "—",
        era = values["era_name"]?.toString() ?: "—",
        season = values["season"]?.toString() ?: "—",
        hour = String.format(Locale.ROOT, "%02d:%02d", values["hour"]?.toString()?.toIntOrNull() ?: 0,
            values["minute"]?.toString()?.toIntOrNull() ?: 0)
    )
}
