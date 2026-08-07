package com.rpgos.app

import android.app.Application

class RpgOsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DiagnosticLogger.log(this, "FATAL_UNCAUGHT/${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
