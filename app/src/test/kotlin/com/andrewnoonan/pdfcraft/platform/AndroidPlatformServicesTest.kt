package com.andrewnoonan.pdfcraft.platform

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrewnoonan.pdfcraft.storage.SafDocumentStorageManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AndroidPlatformServicesTest {

    private lateinit var app: Application
    private lateinit var context: Context

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        context = app
    }

    @Test
    fun filePreferencesStore_persistsEngineAndShellPreferencesAcrossInstances() {
        val dir = File(context.cacheDir, "prefs_${System.nanoTime()}").apply { mkdirs() }
        val store1 = FilePreferencesStore(dir)
        assertNull(store1.loadEnginePrefs())
        assertTrue(store1.isDesktopBarExpanded())

        store1.saveEnginePrefs("""{"theme":"Dark","ui_scale":1.25}""")
        store1.setDesktopBarExpanded(false)

        val store2 = FilePreferencesStore(dir)
        assertEquals("""{"theme":"Dark","ui_scale":1.25}""", store2.loadEnginePrefs())
        assertFalse(store2.isDesktopBarExpanded())
    }

    @Test
    fun androidClipboardBridge_roundTripsPlainTextThroughSystemClipboardManager() {
        val clipboard = AndroidClipboardBridge(context)
        clipboard.setText("Section 4.2 — Indemnification")
        assertEquals("Section 4.2 — Indemnification", clipboard.getText())
    }

    @Test
    fun androidUrlLauncherBridge_allowsHttpsAndBlocksCleartextOrCustomSchemes() {
        val launcher = AndroidUrlLauncherBridge(context)
        assertFalse(launcher.openHttpsUrl("http://example.com"))
        assertFalse(launcher.openHttpsUrl("javascript:alert(1)"))
        assertFalse(launcher.openHttpsUrl("file:///etc/passwd"))

        assertTrue(launcher.openHttpsUrl("https://getartcraft.com/apps/pdfcraft"))
        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals("https://getartcraft.com/apps/pdfcraft", started.dataString)
    }

    @Test
    fun safDocumentStorageManager_persistsSaveAsAndFolderBindingsAcrossRestarts() {
        val workspace = File(context.cacheDir, "ws_${System.nanoTime()}").apply { mkdirs() }
        val exportDir = File(context.cacheDir, "exports_${System.nanoTime()}").apply { mkdirs() }
        val saveAsTarget = File(exportDir, "signed_copy.pdf")

        val manager1 = SafDocumentStorageManager(context, workspace)
        val stagedSavePath = manager1.prepareSaveDestinationUri(Uri.fromFile(saveAsTarget), "signed_copy.pdf")
        assertNotNull(stagedSavePath)
        File(stagedSavePath!!).writeBytes("%PDF-1.7\nsigned\n%%EOF\n".encodeToByteArray())

        // Re-instantiate manager to verify persistent uri_bindings.json survives process restart
        val manager2 = SafDocumentStorageManager(context, workspace)
        assertTrue(manager2.syncSavedPathToOrigin(stagedSavePath))
        assertArrayEquals("%PDF-1.7\nsigned\n%%EOF\n".encodeToByteArray(), saveAsTarget.readBytes())

        // Verify folder export staging syncs newly created split PDFs into origin folder
        val treeDir = File(context.cacheDir, "split_out_${System.nanoTime()}").apply { mkdirs() }
        val stagedFolder = manager2.prepareFolderDestinationUri(Uri.fromFile(treeDir))
        assertNotNull(stagedFolder)
        val part1 = File(stagedFolder!!, "part_1.pdf")
        part1.writeBytes("%PDF-1.7\npart1\n%%EOF\n".encodeToByteArray())
        assertTrue(manager2.syncSavedPathToOrigin(part1.absolutePath))
        assertArrayEquals(part1.readBytes(), File(treeDir, "part_1.pdf").readBytes())
    }
}
