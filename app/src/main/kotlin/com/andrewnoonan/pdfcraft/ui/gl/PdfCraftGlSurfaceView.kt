package com.andrewnoonan.pdfcraft.ui.gl

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.text.InputType
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.andrewnoonan.pdfcraft.bridge.PdfCraftNativeBridge
import com.andrewnoonan.pdfcraft.bridge.PlatformEffects
import com.andrewnoonan.pdfcraft.bridge.PlatformEffectsParser
import com.andrewnoonan.pdfcraft.input.DesktopInputTranslator
import com.andrewnoonan.pdfcraft.platform.CrashReporter
import com.andrewnoonan.pdfcraft.input.EguiKey
import com.andrewnoonan.pdfcraft.input.EngineInputAction
import com.andrewnoonan.pdfcraft.input.PointerButtons
import com.andrewnoonan.pdfcraft.input.StickyModifiers
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class PdfCraftGlSurfaceView(
    context: Context,
    private val nativeBridge: PdfCraftNativeBridge,
    private val engineHandleProvider: () -> Long,
    private val stickyModifiersProvider: () -> StickyModifiers,
    private val onConsumeRightClickMode: () -> Unit,
    private val onEffects: (PlatformEffects) -> Unit,
    private val enableGlThread: Boolean = true,
) : GLSurfaceView(context), Choreographer.FrameCallback {

    private val translator = DesktopInputTranslator()
    private val startTimeNanos = SystemClock.elapsedRealtimeNanos()
    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val lastRepaintAfterMs = AtomicLong(0L)
    private val nextDelayedRepaintAtUptimeMs = AtomicLong(0L)

    @Volatile
    private var surfaceWidthPx: Int = 1

    @Volatile
    private var surfaceHeightPx: Int = 1

    private var frameLoopActive = false
    private var lastCursorIcon: String = "default"
    private var activePressedButton: Int? = null

    // Multi-touch pinch-to-zoom & two-finger tap right-click state
    private var suppressSingleTapForMultitouch = false
    private var twoFingerDownTimeMs = 0L
    private var twoFingerStartMidX = 0f
    private var twoFingerStartMidY = 0f
    private var twoFingerMovedTooFar = false

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor.isFinite() && factor > 0.01f && factor != 1.0f) {
                    dispatchActions(listOf(EngineInputAction.Zoom(factor)))
                }
                return true
            }
        },
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false

        if (enableGlThread) {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(WorkbenchRenderer())
            renderMode = RENDERMODE_WHEN_DIRTY
        }
    }

    override fun requestRender() {
        if (!enableGlThread) return
        try {
            super.requestRender()
        } catch (_: Throwable) {
            // Ignored if GL thread has stopped or running without GL thread
        }
    }

    override fun queueEvent(r: Runnable?) {
        if (!enableGlThread || r == null) return
        try {
            super.queueEvent(r)
        } catch (_: Throwable) {
            // Ignored if GL thread has stopped
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startChoreographerLoop()
    }

    override fun onDetachedFromWindow() {
        stopChoreographerLoop()
        val h = engineHandleProvider()
        if (enableGlThread && h != 0L) {
            queueEvent {
                nativeBridge.onSurfaceDestroyed(h)
            }
        }
        super.onDetachedFromWindow()
    }

    override fun onResume() {
        if (enableGlThread) {
            super.onResume()
            startChoreographerLoop()
            requestRender()
        }
    }

    override fun onPause() {
        stopChoreographerLoop()
        if (enableGlThread) {
            super.onPause()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!frameLoopActive) return
        val handle = engineHandleProvider()
        if (handle != 0L && enableGlThread) {
            val repaintMs = lastRepaintAfterMs.get()
            val nowUptime = SystemClock.uptimeMillis()
            val dueDelayed = nowUptime >= nextDelayedRepaintAtUptimeMs.get()
            val woken = nativeBridge.consumeWakeRequested(handle)
            if (repaintMs == 0L || dueDelayed || woken) {
                if (dueDelayed && repaintMs > 0L) {
                    nextDelayedRepaintAtUptimeMs.set(Long.MAX_VALUE)
                }
                requestRender()
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startChoreographerLoop() {
        if (enableGlThread && !frameLoopActive) {
            frameLoopActive = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopChoreographerLoop() {
        if (frameLoopActive) {
            frameLoopActive = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val ppp = currentPixelsPerPoint()
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
            -> {
                val actions = translator.translateHover(event.actionMasked, event.x, event.y, ppp)
                if (actions.isNotEmpty()) {
                    dispatchActions(actions)
                    return true
                }
            }

            MotionEvent.ACTION_SCROLL -> {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val hScroll = -event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val actions = translator.translateScroll(
                    xPx = event.x,
                    yPx = event.y,
                    vScroll = vScroll,
                    hScroll = hScroll,
                    pixelsPerPoint = ppp,
                    androidMetaState = event.metaState,
                    sticky = stickyModifiersProvider(),
                )
                if (actions.isNotEmpty()) {
                    dispatchActions(actions)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasFocus()) {
            requestFocus()
        }

        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        val isMouse = toolType == MotionEvent.TOOL_TYPE_MOUSE ||
            (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS
        val ppp = currentPixelsPerPoint()

        if (!isMouse && !isStylus) {
            scaleGestureDetector.onTouchEvent(event)
            if (handleMultitouchGestures(event, ppp)) {
                return true
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_BUTTON_PRESS,
            -> {
                val stylusBarrel = isStylus &&
                    (event.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0
                val (actions, consumedRightClick) = translator.translateMouseOrTouchPress(
                    xPx = event.x,
                    yPx = event.y,
                    pixelsPerPoint = ppp,
                    pressed = true,
                    actionButton = event.actionButton,
                    buttonState = event.buttonState,
                    isStylusSecondaryPressed = stylusBarrel,
                    androidMetaState = event.metaState,
                    sticky = stickyModifiersProvider(),
                )
                activePressedButton = actions
                    .filterIsInstance<EngineInputAction.PointerButton>()
                    .lastOrNull()
                    ?.button
                    ?: PointerButtons.PRIMARY
                if (consumedRightClick) {
                    onConsumeRightClickMode()
                }
                dispatchActions(actions)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                dispatchActions(
                    listOf(EngineInputAction.PointerMoved(event.x / ppp, event.y / ppp)),
                )
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_BUTTON_RELEASE,
            MotionEvent.ACTION_CANCEL,
            -> {
                val releasedButton = activePressedButton ?: when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> PointerButtons.SECONDARY
                    MotionEvent.BUTTON_TERTIARY -> PointerButtons.MIDDLE
                    else -> PointerButtons.PRIMARY
                }
                if (event.actionMasked != MotionEvent.ACTION_BUTTON_RELEASE || event.buttonState == 0) {
                    activePressedButton = null
                }
                val mods = translator.combineModifiers(event.metaState, stickyModifiersProvider())
                val releaseActions = buildList {
                    add(
                        EngineInputAction.PointerButton(
                            xPt = event.x / ppp,
                            yPt = event.y / ppp,
                            button = releasedButton,
                            pressed = false,
                            modifiers = mods,
                        ),
                    )
                    if (!isMouse && !isStylus && event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        add(EngineInputAction.PointerGone)
                    }
                }
                dispatchActions(releaseActions)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleMultitouchGestures(event: MotionEvent, ppp: Float): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    // Cancel any in-flight primary finger press before entering two-finger gesture
                    activePressedButton?.let { btn ->
                        activePressedButton = null
                        val mods = translator.combineModifiers(event.metaState, stickyModifiersProvider())
                        dispatchActions(
                            listOf(
                                EngineInputAction.PointerButton(
                                    xPt = event.getX(0) / ppp,
                                    yPt = event.getY(0) / ppp,
                                    button = btn,
                                    pressed = false,
                                    modifiers = mods,
                                ),
                            ),
                        )
                    }
                    suppressSingleTapForMultitouch = true
                    twoFingerDownTimeMs = event.eventTime
                    twoFingerStartMidX = (event.getX(0) + event.getX(1)) * 0.5f
                    twoFingerStartMidY = (event.getY(0) + event.getY(1)) * 0.5f
                    twoFingerMovedTooFar = false
                } else if (event.pointerCount > 2) {
                    twoFingerMovedTooFar = true
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (suppressSingleTapForMultitouch) {
                    if (event.pointerCount >= 2) {
                        val midX = (event.getX(0) + event.getX(1)) * 0.5f
                        val midY = (event.getY(0) + event.getY(1)) * 0.5f
                        if (hypot(midX - twoFingerStartMidX, midY - twoFingerStartMidY) > touchSlopPx) {
                            twoFingerMovedTooFar = true
                        }
                    }
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (suppressSingleTapForMultitouch) {
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (suppressSingleTapForMultitouch) {
                    val isQuickTwoFingerTap =
                        event.actionMasked == MotionEvent.ACTION_UP &&
                            !twoFingerMovedTooFar &&
                            (event.eventTime - twoFingerDownTimeMs) <= 280L
                    suppressSingleTapForMultitouch = false
                    if (isQuickTwoFingerTap) {
                        val xPt = twoFingerStartMidX / ppp
                        val yPt = twoFingerStartMidY / ppp
                        val mods = translator.combineModifiers(event.metaState, stickyModifiersProvider())
                        dispatchActions(
                            listOf(
                                EngineInputAction.PointerMoved(xPt, yPt),
                                EngineInputAction.PointerButton(
                                    xPt = xPt,
                                    yPt = yPt,
                                    button = PointerButtons.SECONDARY,
                                    pressed = true,
                                    modifiers = mods,
                                ),
                                EngineInputAction.PointerButton(
                                    xPt = xPt,
                                    yPt = yPt,
                                    button = PointerButtons.SECONDARY,
                                    pressed = false,
                                    modifiers = mods,
                                ),
                            ),
                        )
                    }
                    return true
                }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (shouldDelegateKeyToSystem(keyCode)) {
            return super.onKeyDown(keyCode, event)
        }
        val actions = translator.translateKeyEvent(
            androidKeyCode = keyCode,
            unicodeChar = event.unicodeChar,
            pressed = true,
            repeatCount = event.repeatCount,
            androidMetaState = event.metaState,
            sticky = stickyModifiersProvider(),
        )
        if (actions.isNotEmpty()) {
            dispatchActions(actions)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (shouldDelegateKeyToSystem(keyCode)) {
            return super.onKeyUp(keyCode, event)
        }
        val actions = translator.translateKeyEvent(
            androidKeyCode = keyCode,
            unicodeChar = event.unicodeChar,
            pressed = false,
            repeatCount = 0,
            androidMetaState = event.metaState,
            sticky = stickyModifiersProvider(),
        )
        if (actions.isNotEmpty()) {
            dispatchActions(actions)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString().orEmpty()
                if (str.isNotEmpty()) {
                    dispatchActions(listOf(EngineInputAction.Text(str)))
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val mods = translator.combineModifiers(0, stickyModifiersProvider())
                val events = buildList {
                    repeat(beforeLength.coerceAtLeast(0)) {
                        add(EngineInputAction.Key(EguiKey.Backspace, pressed = true, repeat = false, modifiers = mods))
                        add(EngineInputAction.Key(EguiKey.Backspace, pressed = false, repeat = false, modifiers = mods))
                    }
                    repeat(afterLength.coerceAtLeast(0)) {
                        add(EngineInputAction.Key(EguiKey.Delete, pressed = true, repeat = false, modifiers = mods))
                        add(EngineInputAction.Key(EguiKey.Delete, pressed = false, repeat = false, modifiers = mods))
                    }
                }
                if (events.isNotEmpty()) {
                    dispatchActions(events)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return when (event.action) {
                    KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                    KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
                    else -> super.sendKeyEvent(event)
                }
            }
        }
    }

    private fun shouldDelegateKeyToSystem(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            -> true
            else -> false
        }
    }

    private fun dispatchActions(actions: List<EngineInputAction>) {
        val handle = engineHandleProvider()
        if (handle == 0L || actions.isEmpty()) return
        for (action in actions) {
            when (action) {
                is EngineInputAction.PointerMoved -> {
                    nativeBridge.pushPointerMoved(handle, action.xPt, action.yPt)
                }
                EngineInputAction.PointerGone -> {
                    nativeBridge.pushPointerGone(handle)
                }
                is EngineInputAction.PointerButton -> {
                    nativeBridge.pushPointerButton(
                        handle,
                        action.xPt,
                        action.yPt,
                        action.button,
                        action.pressed,
                        action.modifiers,
                    )
                }
                is EngineInputAction.MouseWheel -> {
                    nativeBridge.pushMouseWheel(
                        handle,
                        action.deltaX,
                        action.deltaY,
                        action.isLineUnit,
                        action.modifiers,
                    )
                }
                is EngineInputAction.Zoom -> {
                    nativeBridge.pushZoom(handle, action.factorDelta)
                }
                is EngineInputAction.Key -> {
                    nativeBridge.pushKey(
                        handle,
                        action.key.code,
                        action.pressed,
                        action.repeat,
                        action.modifiers,
                    )
                }
                is EngineInputAction.Text -> {
                    nativeBridge.pushText(handle, action.text)
                }
            }
        }
        if (enableGlThread) {
            requestRender()
        }
    }

    private fun currentPixelsPerPoint(): Float {
        val d = resources.displayMetrics.density
        return if (d.isFinite() && d > 0.05f) d else 1.0f
    }

    private fun applyEffectsOnMainThread(effects: PlatformEffects) {
        if (effects.cursorIcon != lastCursorIcon) {
            lastCursorIcon = effects.cursorIcon
            pointerIcon = PointerIcon.getSystemIcon(
                context,
                translator.mapCursorIconToSystemType(effects.cursorIcon),
            )
        }
        onEffects(effects)
    }

    private inner class WorkbenchRenderer : Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val handle = engineHandleProvider()
            if (handle != 0L) {
                try {
                    nativeBridge.onSurfaceCreated(handle)
                } catch (t: Throwable) {
                    CrashReporter(context.filesDir).recordJavaCrash(Thread.currentThread(), t)
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidthPx = width.coerceAtLeast(1)
            surfaceHeightPx = height.coerceAtLeast(1)
            val handle = engineHandleProvider()
            if (handle != 0L) {
                try {
                    nativeBridge.onSurfaceChanged(
                        handle,
                        surfaceWidthPx,
                        surfaceHeightPx,
                        currentPixelsPerPoint(),
                    )
                } catch (t: Throwable) {
                    CrashReporter(context.filesDir).recordJavaCrash(Thread.currentThread(), t)
                }
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            val handle = engineHandleProvider()
            if (handle == 0L) return
            try {
                val elapsedSecs = (SystemClock.elapsedRealtimeNanos() - startTimeNanos) / 1_000_000_000.0
                val darkMode =
                    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
                val rawJson = nativeBridge.stepFrame(
                    handle = handle,
                    timeSecs = elapsedSecs,
                    widthPx = surfaceWidthPx,
                    heightPx = surfaceHeightPx,
                    pixelsPerPoint = currentPixelsPerPoint(),
                    darkMode = darkMode,
                    focused = hasWindowFocus(),
                )
                val effects = PlatformEffectsParser.parse(rawJson)
                lastRepaintAfterMs.set(effects.repaintAfterMs)
                if (effects.repaintAfterMs > 0L) {
                    nextDelayedRepaintAtUptimeMs.set(SystemClock.uptimeMillis() + effects.repaintAfterMs)
                } else if (effects.repaintAfterMs < 0L) {
                    nextDelayedRepaintAtUptimeMs.set(Long.MAX_VALUE)
                } else {
                    nextDelayedRepaintAtUptimeMs.set(0L)
                }
                post { applyEffectsOnMainThread(effects) }
            } catch (t: Throwable) {
                CrashReporter(context.filesDir).recordJavaCrash(Thread.currentThread(), t)
            }
        }
    }
}
