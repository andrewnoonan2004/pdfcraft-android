package com.andrewnoonan.pdfcraft.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun noPendingReportWhenNothingCrashed() {
        val reporter = CrashReporter(tmp.root)
        assertNull(reporter.consumePendingReport())
    }

    @Test
    fun javaCrashIsRecordedThenConsumedOnce() {
        val reporter = CrashReporter(tmp.root)
        val thread = Thread(null, {}, "GLThread 42")

        reporter.recordJavaCrash(thread, IllegalStateException("surface lost"))

        val report = reporter.consumePendingReport()
        assertNotNull(report)
        assertTrue(report!!.contains("GLThread 42"))
        assertTrue(report.contains("java.lang.IllegalStateException: surface lost"))
        assertTrue(report.contains("CrashReporterTest"))
        assertNull("report must be deleted after it is shown", reporter.consumePendingReport())
    }

    @Test
    fun nativePanicFileWrittenByRustIsIncluded() {
        File(tmp.root, CrashReporter.NATIVE_CRASH_FILE).writeText(
            "Native panic on thread 'GLThread 7':\npanicked at src/lib.rs:1:1: boom\n",
        )
        val reporter = CrashReporter(tmp.root)

        val report = reporter.consumePendingReport()

        assertNotNull(report)
        assertTrue(report!!.contains("panicked at src/lib.rs:1:1: boom"))
        assertFalse(File(tmp.root, CrashReporter.NATIVE_CRASH_FILE).exists())
    }

    @Test
    fun startupFailureTextIncludesCauseChain() {
        val error = UnsatisfiedLinkError("dlopen failed: cannot locate symbol \"android_main\"")
        val text = CrashReporter.describe(error)
        assertTrue(text.contains("UnsatisfiedLinkError"))
        assertTrue(text.contains("android_main"))
    }

    @Test
    fun installedHandlerRecordsAndDelegatesToPrevious() {
        val reporter = CrashReporter(tmp.root)
        var delegated: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, e -> delegated = e }
        val handler = reporter.handlerChainingTo(previous)
        val boom = RuntimeException("boom")

        handler.uncaughtException(Thread.currentThread(), boom)

        assertSame(boom, delegated)
        val report = reporter.consumePendingReport()
        assertNotNull(report)
        assertTrue(report!!.contains("RuntimeException: boom"))
    }

    @Test
    fun recordingNeverThrowsWhenDirectoryIsUnwritable() {
        val notADir = tmp.newFile("plain-file")
        val reporter = CrashReporter(notADir)
        reporter.recordJavaCrash(Thread.currentThread(), RuntimeException("x"))
        assertEquals(null, reporter.consumePendingReport())
    }
}
