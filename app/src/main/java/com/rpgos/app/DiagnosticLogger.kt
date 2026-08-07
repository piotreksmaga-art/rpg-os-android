package com.rpgos.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogger {
    private const val FILE_NAME = "rpgos_diagnostics.log"

    fun log(context: Context, stage: String, throwable: Throwable? = null, message: String? = null) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText(buildString {
                append("[").append(stamp).append("] ").append(stage)
                if (!message.isNullOrBlank()) append(" | ").append(message)
                if (throwable != null) {
                    append("\n")
                    append(throwable.stackTraceToString())
                }
                append("\n\n")
            })
        }
    }

    fun read(context: Context): String =
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText().takeLast(16000) else "Brak zapisanych błędów."
        }.getOrElse { "Błąd odczytu diagnostyki: ${it.message}" }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
