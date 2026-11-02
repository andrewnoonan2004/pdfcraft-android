package com.andrewnoonan.pdfcraft.bridge

class JniPdfCraftNativeBridge : PdfCraftNativeBridge {

    init {
        ensureLoaded()
    }

    external override fun createEngine(filesDir: String, cacheDir: String, initialPrefsJson: String?): Long
    external override fun destroyEngine(handle: Long)
    external override fun onSurfaceCreated(handle: Long)
    external override fun onSurfaceChanged(handle: Long, widthPx: Int, heightPx: Int, pixelsPerPoint: Float)
    external override fun onSurfaceDestroyed(handle: Long)
    external override fun stepFrame(
        handle: Long,
        timeSecs: Double,
        widthPx: Int,
        heightPx: Int,
        pixelsPerPoint: Float,
        darkMode: Boolean,
        focused: Boolean,
    ): String
    external override fun consumeWakeRequested(handle: Long): Boolean
    external override fun pushPointerMoved(handle: Long, xPt: Float, yPt: Float)
    external override fun pushPointerGone(handle: Long)
    external override fun pushPointerButton(
        handle: Long,
        xPt: Float,
        yPt: Float,
        button: Int,
        pressed: Boolean,
        modifiers: Int,
    )
    external override fun pushMouseWheel(
        handle: Long,
        deltaX: Float,
        deltaY: Float,
        isLineUnit: Boolean,
        modifiers: Int,
    )
    external override fun pushTouch(
        handle: Long,
        deviceId: Int,
        pointerId: Int,
        phase: Int,
        xPt: Float,
        yPt: Float,
        force: Float,
    )
    external override fun pushZoom(handle: Long, zoomDelta: Float)
    external override fun pushKey(
        handle: Long,
        eguiKeyCode: Int,
        pressed: Boolean,
        repeat: Boolean,
        modifiers: Int,
    )
    external override fun pushText(handle: Long, text: String)
    external override fun syncSystemClipboardText(handle: Long, text: String)
    external override fun deliverPickedFiles(handle: Long, requestId: Long, paths: Array<String>)
    external override fun openExternalFile(handle: Long, displayName: String, localPath: String)
    external override fun executeCommand(handle: Long, commandId: String)
    external override fun setUiScaleOverride(handle: Long, scale: Float)
    external override fun persistState(handle: Long): String

    companion object {
        @Volatile
        private var loaded = false

        @Synchronized
        fun ensureLoaded() {
            if (!loaded) {
                System.loadLibrary("pdfcraft_android")
                loaded = true
            }
        }
    }
}
