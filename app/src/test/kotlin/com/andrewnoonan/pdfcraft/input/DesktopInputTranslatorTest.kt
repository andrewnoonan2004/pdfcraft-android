package com.andrewnoonan.pdfcraft.input

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DesktopInputTranslatorTest {

    private lateinit var translator: DesktopInputTranslator

    @Before
    fun setUp() {
        translator = DesktopInputTranslator()
    }

    @Test
    fun translateHover_hoverMoveAndHoverExit_scalesCoordinatesToLogicalPoints() {
        val moved = translator.translateHover(
            actionMasked = MotionEvent.ACTION_HOVER_MOVE,
            xPx = 400f,
            yPx = 300f,
            pixelsPerPoint = 2.0f,
        )
        assertEquals(listOf(EngineInputAction.PointerMoved(200f, 150f)), moved)

        val exited = translator.translateHover(
            actionMasked = MotionEvent.ACTION_HOVER_EXIT,
            xPx = 400f,
            yPx = 300f,
            pixelsPerPoint = 2.0f,
        )
        assertEquals(listOf(EngineInputAction.PointerGone), exited)
    }

    @Test
    fun translateMouseOrTouchPress_hardwareRightClickAndStylusButton_emitsSecondaryPointerButton() {
        val (rightClickActions, consumedSticky) = translator.translateMouseOrTouchPress(
            xPx = 240f,
            yPx = 180f,
            pixelsPerPoint = 2.0f,
            pressed = true,
            actionButton = MotionEvent.BUTTON_SECONDARY,
            buttonState = MotionEvent.BUTTON_SECONDARY,
            isStylusSecondaryPressed = false,
            androidMetaState = 0,
            sticky = StickyModifiers(),
        )
        assertFalse(consumedSticky)
        assertEquals(
            listOf(
                EngineInputAction.PointerMoved(120f, 90f),
                EngineInputAction.PointerButton(
                    xPt = 120f,
                    yPt = 90f,
                    button = PointerButtons.SECONDARY,
                    pressed = true,
                    modifiers = 0,
                ),
            ),
            rightClickActions,
        )

        val (stylusActions, _) = translator.translateMouseOrTouchPress(
            xPx = 100f,
            yPx = 50f,
            pixelsPerPoint = 1.0f,
            pressed = true,
            actionButton = 0,
            buttonState = 0,
            isStylusSecondaryPressed = true,
            androidMetaState = 0,
            sticky = StickyModifiers(),
        )
        val buttonEvent = stylusActions.filterIsInstance<EngineInputAction.PointerButton>().single()
        assertEquals(PointerButtons.SECONDARY, buttonEvent.button)
    }

    @Test
    fun translateMouseOrTouchPress_stickyRightClickAndStickyCtrl_combinesModifiersAndConsumesRightClick() {
        val sticky = StickyModifiers(ctrl = true, rightClickNextTap = true)
        val (actions, consumedRightClick) = translator.translateMouseOrTouchPress(
            xPx = 300f,
            yPx = 200f,
            pixelsPerPoint = 1.5f,
            pressed = true,
            actionButton = 0,
            buttonState = 0,
            isStylusSecondaryPressed = false,
            androidMetaState = 0,
            sticky = sticky,
        )
        assertTrue(consumedRightClick)
        val press = actions.filterIsInstance<EngineInputAction.PointerButton>().single()
        assertEquals(PointerButtons.SECONDARY, press.button)
        assertEquals(ModifierBits.CTRL or ModifierBits.COMMAND, press.modifiers)
    }

    @Test
    fun translateScroll_mouseWheelAndCtrlWheel_emitsPointerPositionAndWheelWithModifiers() {
        val actions = translator.translateScroll(
            xPx = 500f,
            yPx = 400f,
            vScroll = -2.0f,
            hScroll = 1.0f,
            pixelsPerPoint = 2.0f,
            androidMetaState = KeyEvent.META_CTRL_ON,
            sticky = StickyModifiers(),
        )
        assertEquals(
            listOf(
                EngineInputAction.PointerMoved(250f, 200f),
                EngineInputAction.MouseWheel(
                    deltaX = 1.0f,
                    deltaY = -2.0f,
                    isLineUnit = true,
                    modifiers = ModifierBits.CTRL or ModifierBits.COMMAND,
                ),
            ),
            actions,
        )
    }

    @Test
    fun translateKeyEvent_ctrlKShortcut_emitsCommandKWithoutTextEvent() {
        val actions = translator.translateKeyEvent(
            androidKeyCode = KeyEvent.KEYCODE_K,
            unicodeChar = 'k'.code,
            pressed = true,
            repeatCount = 0,
            androidMetaState = KeyEvent.META_CTRL_ON,
            sticky = StickyModifiers(),
        )
        assertEquals(
            listOf(
                EngineInputAction.Key(
                    key = EguiKey.K,
                    pressed = true,
                    repeat = false,
                    modifiers = ModifierBits.CTRL or ModifierBits.COMMAND,
                )
            ),
            actions,
        )
    }

    @Test
    fun translateKeyEvent_printableCharacterWithoutCommand_emitsKeyAndText() {
        val actions = translator.translateKeyEvent(
            androidKeyCode = KeyEvent.KEYCODE_A,
            unicodeChar = 'A'.code,
            pressed = true,
            repeatCount = 0,
            androidMetaState = KeyEvent.META_SHIFT_ON,
            sticky = StickyModifiers(),
        )
        assertEquals(
            listOf(
                EngineInputAction.Key(
                    key = EguiKey.A,
                    pressed = true,
                    repeat = false,
                    modifiers = ModifierBits.SHIFT,
                ),
                EngineInputAction.Text("A"),
            ),
            actions,
        )
    }

    @Test
    fun mapCursorIconToSystemType_mapsDesktopEguiCursorsToAndroidPointerIcons() {
        assertEquals(PointerIcon.TYPE_HAND, translator.mapCursorIconToSystemType("pointing_hand"))
        assertEquals(PointerIcon.TYPE_TEXT, translator.mapCursorIconToSystemType("text"))
        assertEquals(PointerIcon.TYPE_GRAB, translator.mapCursorIconToSystemType("grab"))
        assertEquals(PointerIcon.TYPE_GRABBING, translator.mapCursorIconToSystemType("grabbing"))
        assertEquals(PointerIcon.TYPE_CROSSHAIR, translator.mapCursorIconToSystemType("crosshair"))
        assertEquals(PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW, translator.mapCursorIconToSystemType("resize_horizontal"))
        assertEquals(PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW, translator.mapCursorIconToSystemType("resize_vertical"))
        assertEquals(PointerIcon.TYPE_ARROW, translator.mapCursorIconToSystemType("default"))
    }
}
