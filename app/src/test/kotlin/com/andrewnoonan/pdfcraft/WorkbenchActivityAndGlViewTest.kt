package com.andrewnoonan.pdfcraft

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import com.andrewnoonan.pdfcraft.bridge.PdfCraftNativeBridge
import com.andrewnoonan.pdfcraft.input.EguiKey
import com.andrewnoonan.pdfcraft.input.ModifierBits
import com.andrewnoonan.pdfcraft.input.PointerButtons
import com.andrewnoonan.pdfcraft.input.StickyModifiers
import com.andrewnoonan.pdfcraft.storage.SafMimeTypeMapper
import com.andrewnoonan.pdfcraft.ui.gl.PdfCraftGlSurfaceView
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkbenchActivityAndGlViewTest {

    @After
    fun tearDown() {
        WorkbenchDependencies.overrideBridgeForTesting = null
    }

    @Test
    fun `SafMimeTypeMapper maps extensions for open and save SAF contracts`() {
        assertArrayEquals(
            arrayOf("application/pdf"),
            SafMimeTypeMapper.openMimeTypes(listOf("pdf")),
        )
        assertArrayEquals(
            arrayOf("application/pdf", "image/png", "image/jpeg"),
            SafMimeTypeMapper.openMimeTypes(listOf("PDF", ".png", "jpg", "jpeg")),
        )
        assertArrayEquals(
            arrayOf("*/*"),
            SafMimeTypeMapper.openMimeTypes(emptyList()),
        )
        assertEquals(
            "application/pdf",
            SafMimeTypeMapper.createMimeType("report.pdf", listOf("pdf")),
        )
        assertEquals(
            "text/csv",
            SafMimeTypeMapper.createMimeType("fields.csv", listOf("csv")),
        )
        assertEquals(
            "image/png",
            SafMimeTypeMapper.createMimeType("page_1.png", emptyList()),
        )
    }

    @Test
    fun `PdfCraftGlSurfaceView forwards hardware keyboard shortcuts IME text and mouse right-click release accurately`() {
        val recorder = RecordingNativeBridge()
        val context = RuntimeEnvironment.getApplication()
        val glView = PdfCraftGlSurfaceView(
            context = context,
            nativeBridge = recorder,
            engineHandleProvider = { 77L },
            stickyModifiersProvider = { StickyModifiers() },
            onConsumeRightClickMode = {},
            onEffects = {},
            enableGlThread = false,
        )

        // 1. Hardware Ctrl+K key down
        val ctrlK = KeyEvent(
            0L,
            0L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_K,
            0,
            KeyEvent.META_CTRL_ON,
        )
        assertTrue(glView.onKeyDown(KeyEvent.KEYCODE_K, ctrlK))
        assertEquals(1, recorder.keys.size)
        assertEquals(EguiKey.K.code, recorder.keys.single().first)
        assertTrue((recorder.keys.single().fourth and ModifierBits.COMMAND) != 0)

        // 2. Soft keyboard InputConnection commitText + Backspace
        val editorInfo = EditorInfo()
        val ic = glView.onCreateInputConnection(editorInfo)
        assertNotNull(ic)
        ic.commitText("PdfCraft Desktop", 1)
        assertEquals(listOf("PdfCraft Desktop"), recorder.texts)
        ic.deleteSurroundingText(1, 0)
        assertTrue(recorder.keys.any { it.first == EguiKey.Backspace.code && it.second })

        // 3. Mouse secondary (right) click press + release when ACTION_UP has buttonState == 0
        val downProps = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = 200f
            y = 100f
        })
        val rightDown = MotionEvent.obtain(
            10L,
            10L,
            MotionEvent.ACTION_DOWN,
            1,
            downProps,
            coords,
            0,
            MotionEvent.BUTTON_SECONDARY,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_MOUSE,
            0,
        )
        val rightUp = MotionEvent.obtain(
            10L,
            30L,
            MotionEvent.ACTION_UP,
            1,
            downProps,
            coords,
            0,
            0, // Android clears buttonState on ACTION_UP!
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_MOUSE,
            0,
        )
        assertTrue(glView.onTouchEvent(rightDown))
        assertTrue(glView.onTouchEvent(rightUp))
        rightDown.recycle()
        rightUp.recycle()

        assertEquals(2, recorder.pointerButtons.size)
        assertEquals(PointerButtons.SECONDARY, recorder.pointerButtons[0].first)
        assertEquals(true, recorder.pointerButtons[0].second)
        assertEquals(PointerButtons.SECONDARY, recorder.pointerButtons[1].first)
        assertEquals(false, recorder.pointerButtons[1].second)
    }

    @Test
    fun `MainActivity boots initializes engine and imports incoming ACTION_VIEW PDF intent`() {
        val recorder = RecordingNativeBridge()
        WorkbenchDependencies.overrideBridgeForTesting = recorder
        WorkbenchDependencies.disableGlThreadForTesting = true

        val context = RuntimeEnvironment.getApplication()
        val samplePdf = File(context.cacheDir, "incoming_contract.pdf").apply {
            writeBytes("%PDF-1.4\n%%EOF\n".encodeToByteArray())
        }
        val viewIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.fromFile(samplePdf)
        }

        Robolectric.buildActivity(MainActivity::class.java, viewIntent).use { controller ->
            controller.setup()
            // Drain main looper / IO coroutines
            Thread.sleep(80)
            shadowOf(android.os.Looper.getMainLooper()).idle()

            assertTrue("Engine should be created on launch", recorder.createCount >= 1)
            assertTrue(
                "Incoming ACTION_VIEW PDF should be imported and opened in engine",
                recorder.openedExternalFiles.any { it.first.contains("incoming_contract.pdf") },
            )
        }
        WorkbenchDependencies.disableGlThreadForTesting = false
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private class RecordingNativeBridge : PdfCraftNativeBridge {
        var createCount = 0
        val keys = mutableListOf<Quad<Int, Boolean, Boolean, Int>>()
        val texts = mutableListOf<String>()
        val pointerButtons = mutableListOf<Pair<Int, Boolean>>()
        val openedExternalFiles = mutableListOf<Pair<String, String>>()

        override fun createEngine(filesDir: String, cacheDir: String, initialPrefsJson: String?): Long {
            createCount++
            return 99L
        }

        override fun destroyEngine(handle: Long) = Unit
        override fun onSurfaceCreated(handle: Long) = Unit
        override fun onSurfaceChanged(handle: Long, widthPx: Int, heightPx: Int, pixelsPerPoint: Float) = Unit
        override fun onSurfaceDestroyed(handle: Long) = Unit
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
        override fun pushPointerMoved(handle: Long, xPt: Float, yPt: Float) = Unit
        override fun pushPointerGone(handle: Long) = Unit
        override fun pushPointerButton(handle: Long, xPt: Float, yPt: Float, button: Int, pressed: Boolean, modifiers: Int) {
            pointerButtons += button to pressed
        }
        override fun pushMouseWheel(handle: Long, deltaX: Float, deltaY: Float, isLineUnit: Boolean, modifiers: Int) = Unit
        override fun pushTouch(handle: Long, deviceId: Int, pointerId: Int, phase: Int, xPt: Float, yPt: Float, force: Float) = Unit
        override fun pushZoom(handle: Long, zoomDelta: Float) = Unit
        override fun pushKey(handle: Long, eguiKeyCode: Int, pressed: Boolean, repeat: Boolean, modifiers: Int) {
            keys += Quad(eguiKeyCode, pressed, repeat, modifiers)
        }
        override fun pushText(handle: Long, text: String) {
            texts += text
        }
        override fun syncSystemClipboardText(handle: Long, text: String) = Unit
        override fun deliverPickedFiles(handle: Long, requestId: Long, paths: Array<String>) = Unit
        override fun openExternalFile(handle: Long, displayName: String, localPath: String) {
            openedExternalFiles += displayName to localPath
        }
        override fun executeCommand(handle: Long, commandId: String) = Unit
        override fun setUiScaleOverride(handle: Long, scale: Float) = Unit
        override fun persistState(handle: Long): String = """{"theme":"System"}"""
    }
}
