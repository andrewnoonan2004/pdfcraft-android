package com.andrewnoonan.pdfcraft.bridge

import kotlinx.serialization.Serializable

@Serializable
data class PickerRequest(
    val id: Long,
    val mode: String,
    val title: String? = null,
    val suggestedName: String? = null,
    val extensions: List<String> = emptyList(),
)

@Serializable
data class SavedFileEvent(
    val path: String,
)

@Serializable
data class PrintJobRequest(
    val jobName: String,
    val pdfPath: String,
)

@Serializable
data class ImeRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

@Serializable
data class PlatformEffects(
    val cursorIcon: String = "default",
    val wantsIme: Boolean = false,
    val imeRect: ImeRect? = null,
    val repaintAfterMs: Long = -1L,
    val copyText: String? = null,
    val openUrl: String? = null,
    val windowTitle: String = "PdfCraft",
    val activePath: String? = null,
    val hasUnsavedChanges: Boolean = false,
    val closeRequested: Boolean = false,
    val pickerRequest: PickerRequest? = null,
    val savedFile: SavedFileEvent? = null,
    val printJob: PrintJobRequest? = null,
)

interface PdfCraftNativeBridge {
    fun createEngine(filesDir: String, cacheDir: String, initialPrefsJson: String?): Long
    fun destroyEngine(handle: Long)
    fun onSurfaceCreated(handle: Long)
    fun onSurfaceChanged(handle: Long, widthPx: Int, heightPx: Int, pixelsPerPoint: Float)
    fun onSurfaceDestroyed(handle: Long)
    fun stepFrame(
        handle: Long,
        timeSecs: Double,
        widthPx: Int,
        heightPx: Int,
        pixelsPerPoint: Float,
        darkMode: Boolean,
        focused: Boolean,
    ): String
    fun consumeWakeRequested(handle: Long): Boolean
    fun pushPointerMoved(handle: Long, xPt: Float, yPt: Float)
    fun pushPointerGone(handle: Long)
    fun pushPointerButton(handle: Long, xPt: Float, yPt: Float, button: Int, pressed: Boolean, modifiers: Int)
    fun pushMouseWheel(handle: Long, deltaX: Float, deltaY: Float, isLineUnit: Boolean, modifiers: Int)
    fun pushTouch(handle: Long, deviceId: Int, pointerId: Int, phase: Int, xPt: Float, yPt: Float, force: Float)
    fun pushZoom(handle: Long, zoomDelta: Float)
    fun pushKey(handle: Long, eguiKeyCode: Int, pressed: Boolean, repeat: Boolean, modifiers: Int)
    fun pushText(handle: Long, text: String)
    fun syncSystemClipboardText(handle: Long, text: String)
    fun deliverPickedFiles(handle: Long, requestId: Long, paths: Array<String>)
    fun openExternalFile(handle: Long, displayName: String, localPath: String)
    fun executeCommand(handle: Long, commandId: String)
    fun setUiScaleOverride(handle: Long, scale: Float)
    fun persistState(handle: Long): String
}
