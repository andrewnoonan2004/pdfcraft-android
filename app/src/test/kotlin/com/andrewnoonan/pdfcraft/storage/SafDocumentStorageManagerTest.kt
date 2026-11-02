package com.andrewnoonan.pdfcraft.storage

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SafDocumentStorageManagerTest {

    private lateinit var context: Context
    private lateinit var workspaceDir: File
    private lateinit var manager: SafDocumentStorageManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workspaceDir = File(context.cacheDir, "test_saf_${System.nanoTime()}").apply { mkdirs() }
        manager = SafDocumentStorageManager(context, workspaceDir)
    }

    @Test
    fun sanitizeFileName_stripsPathTraversalAndControlCharacters() {
        assertEquals("report.pdf", manager.sanitizeFileName("../../etc/report.pdf"))
        assertEquals("document.pdf", manager.sanitizeFileName("   ", "document.pdf"))
        assertEquals("quarterly_2026.pdf", manager.sanitizeFileName("quarterly:2026?.pdf"))
    }

    @Test
    fun importAndSyncSavedPath_roundTripsRealPdfBytesBackToOriginFileUri() {
        val originDir = File(context.cacheDir, "origin_${System.nanoTime()}").apply { mkdirs() }
        val originPdf = File(originDir, "specimen.pdf")
        val initialBytes = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n".encodeToByteArray()
        originPdf.writeBytes(initialBytes)

        val uri = Uri.fromFile(originPdf)
        val imported = manager.importReadableUri(uri, takePersistablePermission = false)
        assertNotNull(imported)
        assertEquals("specimen.pdf", imported!!.displayName)
        val stagedFile = File(imported.localPath)
        assertTrue(stagedFile.exists())
        assertArrayEquals(initialBytes, stagedFile.readBytes())

        // Simulate PdfCraft incremental atomic save to stagedFile, then sync back to origin Uri
        val updatedBytes = initialBytes + "% Incremental revision\n%%EOF\n".encodeToByteArray()
        stagedFile.writeBytes(updatedBytes)

        assertTrue(manager.syncSavedPathToOrigin(imported.localPath))
        assertArrayEquals(updatedBytes, originPdf.readBytes())
    }
}
