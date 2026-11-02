package com.andrewnoonan.pdfcraft.ui.workbench

import com.andrewnoonan.pdfcraft.bridge.PdfCraftNativeBridge
import com.andrewnoonan.pdfcraft.bridge.PickerRequest
import com.andrewnoonan.pdfcraft.bridge.PlatformEffects
import com.andrewnoonan.pdfcraft.bridge.PrintJobRequest
import com.andrewnoonan.pdfcraft.bridge.SavedFileEvent
import com.andrewnoonan.pdfcraft.platform.ClipboardBridge
import com.andrewnoonan.pdfcraft.platform.PreferencesStore
import com.andrewnoonan.pdfcraft.platform.PrintBridge
import com.andrewnoonan.pdfcraft.platform.ShareBridge
import com.andrewnoonan.pdfcraft.platform.UrlLauncherBridge
import com.andrewnoonan.pdfcraft.storage.DocumentStorageManager
import com.andrewnoonan.pdfcraft.storage.ImportedDocument
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeNativeBridge : PdfCraftNativeBridge {
    var createdHandle = 77L
    val executedCommands = mutableListOf<String>()
    val deliveredPicks = mutableListOf<Pair<Long, List<String>>>()
    val openedExternal = mutableListOf<Pair<String, String>>()
    var lastUiScale = 1.0f
    var persistedJson = """{"theme":"Dark"}"""
    var destroyed = false

    override fun createEngine(filesDir: String, cacheDir: String, initialPrefsJson: String?): Long = createdHandle
    override fun destroyEngine(handle: Long) { destroyed = true }
    override fun onSurfaceCreated(handle: Long) {}
    override fun onSurfaceChanged(handle: Long, widthPx: Int, heightPx: Int, pixelsPerPoint: Float) {}
    override fun onSurfaceDestroyed(handle: Long) {}
    override fun stepFrame(
        handle: Long,
        timeSecs: Double,
        widthPx: Int,
        heightPx: Int,
        pixelsPerPoint: Float,
        darkMode: Boolean,
        focused: Boolean,
    ): String = "{}"
    override fun consumeWakeRequested(handle: Long): Boolean = false
    override fun pushPointerMoved(handle: Long, xPt: Float, yPt: Float) {}
    override fun pushPointerGone(handle: Long) {}
    override fun pushPointerButton(handle: Long, xPt: Float, yPt: Float, button: Int, pressed: Boolean, modifiers: Int) {}
    override fun pushMouseWheel(handle: Long, deltaX: Float, deltaY: Float, isLineUnit: Boolean, modifiers: Int) {}
    override fun pushTouch(handle: Long, deviceId: Int, pointerId: Int, phase: Int, xPt: Float, yPt: Float, force: Float) {}
    override fun pushZoom(handle: Long, zoomDelta: Float) {}
    override fun pushKey(handle: Long, eguiKeyCode: Int, pressed: Boolean, repeat: Boolean, modifiers: Int) {}
    override fun pushText(handle: Long, text: String) {}
    override fun syncSystemClipboardText(handle: Long, text: String) {}
    override fun deliverPickedFiles(handle: Long, requestId: Long, paths: Array<String>) {
        deliveredPicks += requestId to paths.toList()
    }
    override fun openExternalFile(handle: Long, displayName: String, localPath: String) {
        openedExternal += displayName to localPath
    }
    override fun executeCommand(handle: Long, commandId: String) {
        executedCommands += commandId
    }
    override fun setUiScaleOverride(handle: Long, scale: Float) {
        lastUiScale = scale
    }
    override fun persistState(handle: Long): String = persistedJson
}

class WorkbenchViewModelTest {

    private lateinit var fakeBridge: FakeNativeBridge
    private val syncedSavedPaths = mutableListOf<String>()
    private val copiedTexts = mutableListOf<String>()
    private val printedJobs = mutableListOf<Pair<String, String>>()
    private val launchedUrls = mutableListOf<String>()
    private val sharedFiles = mutableListOf<Pair<String, String>>()
    private var savedPrefsJson: String? = """{"theme":"System"}"""
    private var savedBarExpanded = true

    private lateinit var viewModel: WorkbenchViewModel

    @Before
    fun setUp() {
        fakeBridge = FakeNativeBridge()
        val storage = object : DocumentStorageManager {
            override fun importReadableUri(uri: Uri, takePersistablePermission: Boolean): ImportedDocument? = null
            override fun prepareSaveDestinationUri(uri: Uri, suggestedName: String): String? = null
            override fun prepareFolderDestinationUri(treeUri: Uri): String? = null
            override fun syncSavedPathToOrigin(savedLocalPath: String): Boolean {
                syncedSavedPaths += savedLocalPath
                return true
            }
            override fun sanitizeFileName(rawName: String, fallback: String): String = rawName
        }
        val clipboard = object : ClipboardBridge {
            override fun setText(text: String) { copiedTexts += text }
            override fun getText(): String? = copiedTexts.lastOrNull()
        }
        val printer = object : PrintBridge {
            override fun printPdfFile(jobName: String, pdfPath: String): Boolean {
                printedJobs += jobName to pdfPath
                return true
            }
        }
        val prefs = object : PreferencesStore {
            override fun loadEnginePrefs(): String? = savedPrefsJson
            override fun saveEnginePrefs(json: String) { savedPrefsJson = json }
            override fun isDesktopBarExpanded(): Boolean = savedBarExpanded
            override fun setDesktopBarExpanded(expanded: Boolean) { savedBarExpanded = expanded }
        }
        val urlLauncher = object : UrlLauncherBridge {
            override fun openHttpsUrl(url: String): Boolean {
                launchedUrls += url
                return true
            }
        }
        val share = object : ShareBridge {
            override fun sharePdfFile(localPath: String, title: String): Boolean {
                sharedFiles += localPath to title
                return true
            }
        }

        viewModel = WorkbenchViewModel(
            nativeBridge = fakeBridge,
            storageManager = storage,
            clipboardBridge = clipboard,
            printBridge = printer,
            preferencesStore = prefs,
            urlLauncherBridge = urlLauncher,
            shareBridge = share,
        )
    }

    @Test
    fun initializeAndModifiers_manageStickyStateAndCommands() {
        val handle = viewModel.initializeEngine("/files", "/cache")
        assertEquals(77L, handle)

        viewModel.toggleCtrl()
        viewModel.toggleShift()
        viewModel.toggleRightClickNextTap()
        assertTrue(viewModel.uiState.value.stickyModifiers.ctrl)
        assertTrue(viewModel.uiState.value.stickyModifiers.shift)
        assertTrue(viewModel.uiState.value.stickyModifiers.rightClickNextTap)

        viewModel.consumeRightClickMode()
        assertFalse(viewModel.uiState.value.stickyModifiers.rightClickNextTap)
        assertTrue(viewModel.uiState.value.stickyModifiers.ctrl)

        viewModel.dispatchCommand("app.command_palette")
        assertEquals(listOf("app.command_palette"), fakeBridge.executedCommands)

        viewModel.adjustUiScale(25)
        assertEquals(125, viewModel.uiState.value.uiScalePercent)
        assertEquals(1.25f, fakeBridge.lastUiScale, 0.001f)
    }

    @Test
    fun onFrameEffects_routesClipboardSafSyncPrintPickerAndHttpsOnlyUrls() {
        viewModel.initializeEngine("/files", "/cache")

        viewModel.onFrameEffects(
            PlatformEffects(
                windowTitle = "spec.pdf • — PdfCraft",
                activePath = "/files/saf_workspace/1/spec.pdf",
                hasUnsavedChanges = true,
                wantsIme = true,
                copyText = "Copied paragraph",
                openUrl = "https://github.com/storytold/pdfcraft",
                pickerRequest = PickerRequest(id = 9L, mode = "open_multiple", extensions = listOf("pdf")),
                savedFile = SavedFileEvent("/files/saf_workspace/1/spec.pdf"),
                printJob = PrintJobRequest("spec.pdf", "/cache/print_1.pdf"),
            )
        )

        assertEquals("spec.pdf • — PdfCraft", viewModel.uiState.value.windowTitle)
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)
        assertTrue(viewModel.uiState.value.wantsSoftKeyboard)
        assertEquals(listOf("Copied paragraph"), copiedTexts)
        assertEquals(listOf("https://github.com/storytold/pdfcraft"), launchedUrls)
        assertEquals(listOf("/files/saf_workspace/1/spec.pdf"), syncedSavedPaths)
        assertEquals(listOf("spec.pdf" to "/cache/print_1.pdf"), printedJobs)
        assertEquals(9L, viewModel.uiState.value.pendingPickerRequest?.id)

        // Reject cleartext http:// URLs per Play Protect / security guardrails
        viewModel.onFrameEffects(PlatformEffects(openUrl = "http://insecure.example.com"))
        assertEquals(1, launchedUrls.size)

        viewModel.onFilesPicked(9L, listOf("/files/a.pdf"))
        assertNull(viewModel.uiState.value.pendingPickerRequest)
        assertEquals(listOf(9L to listOf("/files/a.pdf")), fakeBridge.deliveredPicks)

        assertTrue(viewModel.shareActiveDocument())
        assertEquals(listOf("/files/saf_workspace/1/spec.pdf" to "spec.pdf • — PdfCraft"), sharedFiles)

        viewModel.persistAndSavePreferences()
        assertEquals("""{"theme":"Dark"}""", savedPrefsJson)
    }
}
