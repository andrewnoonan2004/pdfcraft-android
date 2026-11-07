package com.andrewnoonan.pdfcraft

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.andrewnoonan.pdfcraft.bridge.JniPdfCraftNativeBridge
import com.andrewnoonan.pdfcraft.bridge.PdfCraftNativeBridge
import com.andrewnoonan.pdfcraft.bridge.PickerRequest
import com.andrewnoonan.pdfcraft.platform.AndroidClipboardBridge
import com.andrewnoonan.pdfcraft.platform.AndroidPrintBridge
import com.andrewnoonan.pdfcraft.platform.AndroidShareBridge
import com.andrewnoonan.pdfcraft.platform.AndroidUrlLauncherBridge
import com.andrewnoonan.pdfcraft.platform.CrashReporter
import com.andrewnoonan.pdfcraft.platform.FilePreferencesStore
import com.andrewnoonan.pdfcraft.storage.SafDocumentStorageManager
import com.andrewnoonan.pdfcraft.storage.SafMimeTypeMapper
import com.andrewnoonan.pdfcraft.ui.CrashReportScreen
import com.andrewnoonan.pdfcraft.ui.gl.PdfCraftGlSurfaceView
import com.andrewnoonan.pdfcraft.ui.theme.PdfCraftTheme
import com.andrewnoonan.pdfcraft.ui.workbench.DesktopControlOverlay
import com.andrewnoonan.pdfcraft.ui.workbench.WorkbenchViewModel
import com.andrewnoonan.pdfcraft.ui.workbench.WorkbenchViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WorkbenchDependencies {
    @Volatile
    var overrideBridgeForTesting: PdfCraftNativeBridge? = null

    @Volatile
    var disableGlThreadForTesting: Boolean = false

    fun obtainNativeBridge(): PdfCraftNativeBridge {
        return overrideBridgeForTesting ?: JniPdfCraftNativeBridge()
    }
}

private data class CreateDocInput(
    val mimeType: String,
    val suggestedName: String,
)

private class DynamicCreateDocumentContract : ActivityResultContract<CreateDocInput, Uri?>() {
    override fun createIntent(context: Context, input: CreateDocInput): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.suggestedName)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var nativeBridge: PdfCraftNativeBridge
    private lateinit var storageManager: SafDocumentStorageManager
    private lateinit var clipboardBridge: AndroidClipboardBridge
    private lateinit var viewModel: WorkbenchViewModel

    private var activeSafRequest: PickerRequest? = null
    private var glSurfaceViewRef by mutableStateOf<PdfCraftGlSurfaceView?>(null)

    private val openSingleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val req = activeSafRequest
        activeSafRequest = null
        if (req == null) return@registerForActivityResult
        if (uri == null) {
            viewModel.onFilesPicked(req.id, emptyList())
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val imported = storageManager.importReadableUri(uri)
            withContext(Dispatchers.Main) {
                val paths = imported?.let { listOf(it.localPath) } ?: emptyList()
                viewModel.onFilesPicked(req.id, paths)
            }
        }
    }

    private val openMultipleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val req = activeSafRequest
        activeSafRequest = null
        if (req == null) return@registerForActivityResult
        if (uris.isEmpty()) {
            viewModel.onFilesPicked(req.id, emptyList())
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val paths = uris.mapNotNull { uri -> storageManager.importReadableUri(uri)?.localPath }
            withContext(Dispatchers.Main) {
                viewModel.onFilesPicked(req.id, paths)
            }
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        DynamicCreateDocumentContract(),
    ) { uri ->
        val req = activeSafRequest
        activeSafRequest = null
        if (req == null) return@registerForActivityResult
        if (uri == null) {
            viewModel.onFilesPicked(req.id, emptyList())
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val fallbackName = req.suggestedName?.ifBlank { null } ?: "document.pdf"
            val destPath = storageManager.prepareSaveDestinationUri(uri, fallbackName)
            withContext(Dispatchers.Main) {
                val paths = destPath?.let { listOf(it) } ?: emptyList()
                viewModel.onFilesPicked(req.id, paths)
            }
        }
    }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val req = activeSafRequest
        activeSafRequest = null
        if (req == null) return@registerForActivityResult
        if (treeUri == null) {
            viewModel.onFilesPicked(req.id, emptyList())
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val folderPath = storageManager.prepareFolderDestinationUri(treeUri)
            withContext(Dispatchers.Main) {
                val paths = folderPath?.let { listOf(it) } ?: emptyList()
                viewModel.onFilesPicked(req.id, paths)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val appCtx = applicationContext
        val crashReporter = (application as? PdfCraftApplication)?.crashReporter
            ?: CrashReporter(appCtx.filesDir)
        val previousCrashReport = crashReporter.consumePendingReport()
        var startupError: String? = null

        try {
            nativeBridge = WorkbenchDependencies.obtainNativeBridge()
            storageManager = SafDocumentStorageManager(appCtx)
            clipboardBridge = AndroidClipboardBridge(appCtx)

            val factory = WorkbenchViewModelFactory(
                nativeBridge = nativeBridge,
                storageManager = storageManager,
                clipboardBridge = clipboardBridge,
                printBridge = AndroidPrintBridge { this },
                preferencesStore = FilePreferencesStore(appCtx.filesDir),
                urlLauncherBridge = AndroidUrlLauncherBridge(appCtx),
                shareBridge = AndroidShareBridge(appCtx),
            )
            viewModel = ViewModelProvider(this, factory)[WorkbenchViewModel::class.java]
            viewModel.initializeEngine(
                filesDir = appCtx.filesDir.absolutePath,
                cacheDir = appCtx.cacheDir.absolutePath,
            )
        } catch (t: Throwable) {
            val described = CrashReporter.describe(t)
            crashReporter.recordJavaCrash(Thread.currentThread(), t)
            startupError = described
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::viewModel.isInitialized && viewModel.uiState.value.hasUnsavedChanges) {
                        viewModel.dispatchCommand("file.quit")
                        glSurfaceViewRef?.requestRender()
                    } else {
                        if (::viewModel.isInitialized) {
                            viewModel.persistAndSavePreferences()
                        }
                        finish()
                    }
                }
            },
        )

        if (savedInstanceState == null && ::viewModel.isInitialized) {
            handleIncomingDocumentIntent(intent)
        }

        setContent {
            PdfCraftTheme {
                var pendingReportToShow by remember {
                    mutableStateOf(startupError ?: previousCrashReport)
                }

                if (pendingReportToShow != null) {
                    val isFatal = startupError != null
                    CrashReportScreen(
                        title = if (isFatal) "PdfCraft Startup Error" else "Previous Crash Report",
                        report = pendingReportToShow!!,
                        onContinue = if (isFatal) null else { { pendingReportToShow = null } },
                    )
                } else if (::viewModel.isInitialized) {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    LaunchedEffect(uiState.pendingPickerRequest?.id) {
                        val req = uiState.pendingPickerRequest ?: return@LaunchedEffect
                        viewModel.clearPendingPickerRequest()
                        launchSafPicker(req)
                    }

                    LaunchedEffect(uiState.wantsSoftKeyboard) {
                        val view = glSurfaceViewRef ?: return@LaunchedEffect
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            ?: return@LaunchedEffect
                        if (uiState.wantsSoftKeyboard) {
                            view.requestFocus()
                            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                        } else {
                            imm.hideSoftInputFromWindow(view.windowToken, 0)
                        }
                    }

                    LaunchedEffect(uiState.closeRequested) {
                        if (uiState.closeRequested) {
                            viewModel.persistAndSavePreferences()
                            finish()
                        }
                    }

                    LaunchedEffect(uiState.windowTitle, uiState.hasUnsavedChanges) {
                        val base = uiState.windowTitle.ifBlank { getString(R.string.app_name) }
                        title = if (uiState.hasUnsavedChanges) "• $base" else base
                    }

                    val latestSticky = remember(uiState.stickyModifiers) { uiState.stickyModifiers }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .imePadding(),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                PdfCraftGlSurfaceView(
                                    context = ctx,
                                    nativeBridge = nativeBridge,
                                    engineHandleProvider = { viewModel.engineHandle },
                                    stickyModifiersProvider = { viewModel.uiState.value.stickyModifiers },
                                    onConsumeRightClickMode = { viewModel.consumeRightClickMode() },
                                    onEffects = { effects -> viewModel.onFrameEffects(effects) },
                                    enableGlThread = !WorkbenchDependencies.disableGlThreadForTesting,
                                ).also { glView ->
                                    glSurfaceViewRef = glView
                                }
                            },
                            update = { _ ->
                                val _unused = latestSticky
                            },
                        )

                        DesktopControlOverlay(
                            state = uiState,
                            onToggleExpanded = viewModel::toggleDesktopBarExpanded,
                            onToggleCtrl = viewModel::toggleCtrl,
                            onToggleShift = viewModel::toggleShift,
                            onToggleAlt = viewModel::toggleAlt,
                            onToggleRightClick = viewModel::toggleRightClickNextTap,
                            onCommand = { cmd ->
                                viewModel.dispatchCommand(cmd)
                                glSurfaceViewRef?.requestRender()
                            },
                            onToggleKeyboard = viewModel::requestSoftKeyboardToggle,
                            onShareDocument = { viewModel.shareActiveDocument() },
                            onAdjustScale = { delta ->
                                viewModel.adjustUiScale(delta)
                                glSurfaceViewRef?.requestRender()
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp, start = 12.dp, end = 12.dp),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingDocumentIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        glSurfaceViewRef?.onResume()
        syncClipboardIntoEngine()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            syncClipboardIntoEngine()
            glSurfaceViewRef?.requestRender()
        }
    }

    override fun onPause() {
        glSurfaceViewRef?.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::viewModel.isInitialized) {
            viewModel.persistAndSavePreferences()
        }
        super.onStop()
    }

    private fun syncClipboardIntoEngine() {
        if (!::viewModel.isInitialized || !::clipboardBridge.isInitialized) return
        val handle = viewModel.engineHandle
        if (handle == 0L) return
        val text = clipboardBridge.getText() ?: return
        nativeBridge.syncSystemClipboardText(handle, text)
    }

    private fun launchSafPicker(req: PickerRequest) {
        activeSafRequest = req
        runCatching {
            when (req.mode) {
                "open_multiple" -> {
                    openMultipleLauncher.launch(SafMimeTypeMapper.openMimeTypes(req.extensions))
                }
                "save_file" -> {
                    val suggested = storageManager.sanitizeFileName(
                        req.suggestedName ?: "document.pdf",
                        fallback = "document.pdf",
                    )
                    val mime = SafMimeTypeMapper.createMimeType(suggested, req.extensions)
                    createDocumentLauncher.launch(CreateDocInput(mimeType = mime, suggestedName = suggested))
                }
                "pick_folder" -> {
                    openFolderLauncher.launch(null)
                }
                else -> {
                    openSingleLauncher.launch(SafMimeTypeMapper.openMimeTypes(req.extensions))
                }
            }
        }.onFailure {
            activeSafRequest = null
            viewModel.onFilesPicked(req.id, emptyList())
        }
    }

    private fun handleIncomingDocumentIntent(incoming: Intent?) {
        if (incoming == null) return
        if (!::viewModel.isInitialized || !::storageManager.isInitialized) return
        val uris = extractDocumentUris(incoming)
        if (uris.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val importedList = uris.mapNotNull { uri ->
                storageManager.importReadableUri(uri, takePersistablePermission = true)
            }
            withContext(Dispatchers.Main) {
                for (doc in importedList) {
                    viewModel.openExternalDocument(doc.displayName, doc.localPath)
                }
                try {
                    glSurfaceViewRef?.requestRender()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun extractDocumentUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW,
            Intent.ACTION_EDIT,
            -> {
                intent.data?.let { out += it }
            }

            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { out += it }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { out.addAll(it) }
            }
        }
        val clip = intent.clipData
        if (clip != null && (
                intent.action == Intent.ACTION_VIEW ||
                    intent.action == Intent.ACTION_EDIT ||
                    intent.action == Intent.ACTION_SEND ||
                    intent.action == Intent.ACTION_SEND_MULTIPLE
                )
        ) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { out += it }
            }
        }
        return out.toList()
    }
}
