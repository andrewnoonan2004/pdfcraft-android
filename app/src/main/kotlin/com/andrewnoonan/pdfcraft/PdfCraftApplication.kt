package com.andrewnoonan.pdfcraft

import android.app.Application
import com.andrewnoonan.pdfcraft.platform.CrashReporter

/**
 * Application class for PdfCraft.
 *
 * Installs the uncaught exception handler as early as possible so that any crash
 * (JVM or native panic) is recorded in filesDir and can be displayed to the user
 * on next launch.
 */
class PdfCraftApplication : Application() {

    lateinit var crashReporter: CrashReporter
        private set

    override fun onCreate() {
        super.onCreate()
        crashReporter = CrashReporter(filesDir)
        crashReporter.install()
    }
}
