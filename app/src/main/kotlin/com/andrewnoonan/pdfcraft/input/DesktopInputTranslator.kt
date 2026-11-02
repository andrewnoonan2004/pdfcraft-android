package com.andrewnoonan.pdfcraft.input

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon

object ModifierBits {
    const val ALT = 1
    const val CTRL = 2
    const val SHIFT = 4
    const val MAC_CMD = 8
    const val COMMAND = 16
}

object PointerButtons {
    const val PRIMARY = 0
    const val SECONDARY = 1
    const val MIDDLE = 2
}

enum class EguiKey(val code: Int) {
    ArrowDown(1),
    ArrowLeft(2),
    ArrowRight(3),
    ArrowUp(4),
    Escape(5),
    Tab(6),
    Backspace(7),
    Enter(8),
    Space(9),
    Insert(10),
    Delete(11),
    Home(12),
    End(13),
    PageUp(14),
    PageDown(15),
    Copy(16),
    Cut(17),
    Paste(18),
    Minus(19),
    Plus(20),
    Equals(21),
    OpenBracket(22),
    CloseBracket(23),
    Backslash(24),
    Semicolon(25),
    Quote(26),
    Comma(27),
    Period(28),
    Slash(29),
    Backtick(30),
    Num0(31),
    Num1(32),
    Num2(33),
    Num3(34),
    Num4(35),
    Num5(36),
    Num6(37),
    Num7(38),
    Num8(39),
    Num9(40),
    A(41),
    B(42),
    C(43),
    D(44),
    E(45),
    F(46),
    G(47),
    H(48),
    I(49),
    J(50),
    K(51),
    L(52),
    M(53),
    N(54),
    O(55),
    P(56),
    Q(57),
    R(58),
    S(59),
    T(60),
    U(61),
    V(62),
    W(63),
    X(64),
    Y(65),
    Z(66),
    F1(67),
    F2(68),
    F3(69),
    F4(70),
    F5(71),
    F6(72),
    F7(73),
    F8(74),
    F9(75),
    F10(76),
    F11(77),
    F12(78),
}

data class StickyModifiers(
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val rightClickNextTap: Boolean = false,
) {
    val anyActive: Boolean
        get() = ctrl || shift || alt || rightClickNextTap
}

sealed interface EngineInputAction {
    data class PointerMoved(val xPt: Float, val yPt: Float) : EngineInputAction
    data object PointerGone : EngineInputAction
    data class PointerButton(
        val xPt: Float,
        val yPt: Float,
        val button: Int,
        val pressed: Boolean,
        val modifiers: Int,
    ) : EngineInputAction
    data class MouseWheel(
        val deltaX: Float,
        val deltaY: Float,
        val isLineUnit: Boolean,
        val modifiers: Int,
    ) : EngineInputAction
    data class Zoom(val factorDelta: Float) : EngineInputAction
    data class Key(
        val key: EguiKey,
        val pressed: Boolean,
        val repeat: Boolean,
        val modifiers: Int,
    ) : EngineInputAction
    data class Text(val text: String) : EngineInputAction
}

class DesktopInputTranslator {

    fun combineModifiers(androidMetaState: Int, sticky: StickyModifiers): Int {
        var bits = 0
        if ((androidMetaState and KeyEvent.META_ALT_ON) != 0 || sticky.alt) {
            bits = bits or ModifierBits.ALT
        }
        if ((androidMetaState and KeyEvent.META_SHIFT_ON) != 0 || sticky.shift) {
            bits = bits or ModifierBits.SHIFT
        }
        if ((androidMetaState and KeyEvent.META_CTRL_ON) != 0 || sticky.ctrl) {
            bits = bits or ModifierBits.CTRL or ModifierBits.COMMAND
        }
        if ((androidMetaState and KeyEvent.META_META_ON) != 0) {
            bits = bits or ModifierBits.MAC_CMD or ModifierBits.COMMAND
        }
        return bits
    }

    fun translateHover(actionMasked: Int, xPx: Float, yPx: Float, pixelsPerPoint: Float): List<EngineInputAction> {
        val ppp = safePpp(pixelsPerPoint)
        return when (actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            -> listOf(EngineInputAction.PointerMoved(xPx / ppp, yPx / ppp))
            MotionEvent.ACTION_HOVER_EXIT -> listOf(EngineInputAction.PointerGone)
            else -> emptyList()
        }
    }

    fun translateMouseOrTouchPress(
        xPx: Float,
        yPx: Float,
        pixelsPerPoint: Float,
        pressed: Boolean,
        actionButton: Int,
        buttonState: Int,
        isStylusSecondaryPressed: Boolean,
        androidMetaState: Int,
        sticky: StickyModifiers,
    ): Pair<List<EngineInputAction>, Boolean> {
        val ppp = safePpp(pixelsPerPoint)
        val xPt = xPx / ppp
        val yPt = yPx / ppp
        val modifiers = combineModifiers(androidMetaState, sticky)

        val hardwareSecondary =
            actionButton == MotionEvent.BUTTON_SECONDARY ||
                (buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
                actionButton == MotionEvent.BUTTON_STYLUS_PRIMARY ||
                (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                isStylusSecondaryPressed
        val hardwareMiddle =
            actionButton == MotionEvent.BUTTON_TERTIARY ||
                (buttonState and MotionEvent.BUTTON_TERTIARY) != 0

        val (button, consumedRightClick) = when {
            hardwareSecondary -> PointerButtons.SECONDARY to false
            hardwareMiddle -> PointerButtons.MIDDLE to false
            sticky.rightClickNextTap -> PointerButtons.SECONDARY to pressed
            else -> PointerButtons.PRIMARY to false
        }

        val actions = if (pressed) {
            listOf(
                EngineInputAction.PointerMoved(xPt, yPt),
                EngineInputAction.PointerButton(
                    xPt = xPt,
                    yPt = yPt,
                    button = button,
                    pressed = true,
                    modifiers = modifiers,
                ),
            )
        } else {
            listOf(
                EngineInputAction.PointerButton(
                    xPt = xPt,
                    yPt = yPt,
                    button = button,
                    pressed = false,
                    modifiers = modifiers,
                ),
            )
        }
        return actions to consumedRightClick
    }

    fun translateScroll(
        xPx: Float,
        yPx: Float,
        vScroll: Float,
        hScroll: Float,
        pixelsPerPoint: Float,
        androidMetaState: Int,
        sticky: StickyModifiers,
    ): List<EngineInputAction> {
        if (vScroll == 0f && hScroll == 0f) {
            return emptyList()
        }
        val ppp = safePpp(pixelsPerPoint)
        val modifiers = combineModifiers(androidMetaState, sticky)
        return listOf(
            EngineInputAction.PointerMoved(xPx / ppp, yPx / ppp),
            EngineInputAction.MouseWheel(
                deltaX = hScroll,
                deltaY = vScroll,
                isLineUnit = true,
                modifiers = modifiers,
            ),
        )
    }

    fun translateKeyEvent(
        androidKeyCode: Int,
        unicodeChar: Int,
        pressed: Boolean,
        repeatCount: Int,
        androidMetaState: Int,
        sticky: StickyModifiers,
    ): List<EngineInputAction> {
        val modifiers = combineModifiers(androidMetaState, sticky)
        val actions = mutableListOf<EngineInputAction>()
        val mappedKey = mapAndroidKeyCode(androidKeyCode, unicodeChar)
        if (mappedKey != null) {
            actions += EngineInputAction.Key(
                key = mappedKey,
                pressed = pressed,
                repeat = repeatCount > 0,
                modifiers = modifiers,
            )
        }

        val hasShortcutModifier =
            (modifiers and (ModifierBits.CTRL or ModifierBits.ALT or ModifierBits.MAC_CMD or ModifierBits.COMMAND)) != 0
        if (pressed && !hasShortcutModifier && isPrintableUnicode(unicodeChar) && !isDedicatedControlKey(androidKeyCode)) {
            val text = String(Character.toChars(unicodeChar))
            actions += EngineInputAction.Text(text)
        }
        return actions
    }

    fun mapCursorIconToSystemType(eguiCursor: String): Int {
        return when (eguiCursor.lowercase()) {
            "pointing_hand" -> PointerIcon.TYPE_HAND
            "text" -> PointerIcon.TYPE_TEXT
            "vertical_text" -> PointerIcon.TYPE_VERTICAL_TEXT
            "grab" -> PointerIcon.TYPE_GRAB
            "grabbing" -> PointerIcon.TYPE_GRABBING
            "crosshair" -> PointerIcon.TYPE_CROSSHAIR
            "move", "all_scroll" -> PointerIcon.TYPE_ALL_SCROLL
            "resize_horizontal", "resize_east", "resize_west", "resize_column" ->
                PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
            "resize_vertical", "resize_north", "resize_south", "resize_row" ->
                PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
            "resize_nw_se", "resize_north_west", "resize_south_east" ->
                PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
            "resize_ne_sw", "resize_north_east", "resize_south_west" ->
                PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
            "not_allowed", "no_drop" -> PointerIcon.TYPE_NO_DROP
            "wait", "progress" -> PointerIcon.TYPE_WAIT
            "help" -> PointerIcon.TYPE_HELP
            "cell" -> PointerIcon.TYPE_CELL
            "copy" -> PointerIcon.TYPE_COPY
            "alias" -> PointerIcon.TYPE_ALIAS
            "zoom_in" -> PointerIcon.TYPE_ZOOM_IN
            "zoom_out" -> PointerIcon.TYPE_ZOOM_OUT
            "none" -> PointerIcon.TYPE_NULL
            else -> PointerIcon.TYPE_ARROW
        }
    }

    private fun safePpp(pixelsPerPoint: Float): Float =
        if (pixelsPerPoint.isFinite() && pixelsPerPoint > 0.05f) pixelsPerPoint else 1.0f

    private fun isPrintableUnicode(codePoint: Int): Boolean =
        codePoint >= 0x20 && codePoint != 0x7F && Character.isValidCodePoint(codePoint)

    private fun isDedicatedControlKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            -> true
            else -> false
        }
    }

    private fun mapAndroidKeyCode(keyCode: Int, unicodeChar: Int): EguiKey? {
        if (unicodeChar == '+'.code && (keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_PLUS)) {
            return EguiKey.Plus
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> EguiKey.ArrowDown
            KeyEvent.KEYCODE_DPAD_LEFT -> EguiKey.ArrowLeft
            KeyEvent.KEYCODE_DPAD_RIGHT -> EguiKey.ArrowRight
            KeyEvent.KEYCODE_DPAD_UP -> EguiKey.ArrowUp
            KeyEvent.KEYCODE_ESCAPE -> EguiKey.Escape
            KeyEvent.KEYCODE_TAB -> EguiKey.Tab
            KeyEvent.KEYCODE_DEL -> EguiKey.Backspace
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> EguiKey.Enter
            KeyEvent.KEYCODE_SPACE -> EguiKey.Space
            KeyEvent.KEYCODE_INSERT -> EguiKey.Insert
            KeyEvent.KEYCODE_FORWARD_DEL -> EguiKey.Delete
            KeyEvent.KEYCODE_MOVE_HOME -> EguiKey.Home
            KeyEvent.KEYCODE_MOVE_END -> EguiKey.End
            KeyEvent.KEYCODE_PAGE_UP -> EguiKey.PageUp
            KeyEvent.KEYCODE_PAGE_DOWN -> EguiKey.PageDown
            KeyEvent.KEYCODE_COPY -> EguiKey.Copy
            KeyEvent.KEYCODE_CUT -> EguiKey.Cut
            KeyEvent.KEYCODE_PASTE -> EguiKey.Paste
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> EguiKey.Minus
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> EguiKey.Plus
            KeyEvent.KEYCODE_EQUALS -> EguiKey.Equals
            KeyEvent.KEYCODE_LEFT_BRACKET -> EguiKey.OpenBracket
            KeyEvent.KEYCODE_RIGHT_BRACKET -> EguiKey.CloseBracket
            KeyEvent.KEYCODE_BACKSLASH -> EguiKey.Backslash
            KeyEvent.KEYCODE_SEMICOLON -> EguiKey.Semicolon
            KeyEvent.KEYCODE_APOSTROPHE -> EguiKey.Quote
            KeyEvent.KEYCODE_COMMA -> EguiKey.Comma
            KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT -> EguiKey.Period
            KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE -> EguiKey.Slash
            KeyEvent.KEYCODE_GRAVE -> EguiKey.Backtick
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> EguiKey.Num0
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> EguiKey.Num1
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> EguiKey.Num2
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> EguiKey.Num3
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> EguiKey.Num4
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> EguiKey.Num5
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> EguiKey.Num6
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> EguiKey.Num7
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> EguiKey.Num8
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> EguiKey.Num9
            KeyEvent.KEYCODE_A -> EguiKey.A
            KeyEvent.KEYCODE_B -> EguiKey.B
            KeyEvent.KEYCODE_C -> EguiKey.C
            KeyEvent.KEYCODE_D -> EguiKey.D
            KeyEvent.KEYCODE_E -> EguiKey.E
            KeyEvent.KEYCODE_F -> EguiKey.F
            KeyEvent.KEYCODE_G -> EguiKey.G
            KeyEvent.KEYCODE_H -> EguiKey.H
            KeyEvent.KEYCODE_I -> EguiKey.I
            KeyEvent.KEYCODE_J -> EguiKey.J
            KeyEvent.KEYCODE_K -> EguiKey.K
            KeyEvent.KEYCODE_L -> EguiKey.L
            KeyEvent.KEYCODE_M -> EguiKey.M
            KeyEvent.KEYCODE_N -> EguiKey.N
            KeyEvent.KEYCODE_O -> EguiKey.O
            KeyEvent.KEYCODE_P -> EguiKey.P
            KeyEvent.KEYCODE_Q -> EguiKey.Q
            KeyEvent.KEYCODE_R -> EguiKey.R
            KeyEvent.KEYCODE_S -> EguiKey.S
            KeyEvent.KEYCODE_T -> EguiKey.T
            KeyEvent.KEYCODE_U -> EguiKey.U
            KeyEvent.KEYCODE_V -> EguiKey.V
            KeyEvent.KEYCODE_W -> EguiKey.W
            KeyEvent.KEYCODE_X -> EguiKey.X
            KeyEvent.KEYCODE_Y -> EguiKey.Y
            KeyEvent.KEYCODE_Z -> EguiKey.Z
            KeyEvent.KEYCODE_F1 -> EguiKey.F1
            KeyEvent.KEYCODE_F2 -> EguiKey.F2
            KeyEvent.KEYCODE_F3 -> EguiKey.F3
            KeyEvent.KEYCODE_F4 -> EguiKey.F4
            KeyEvent.KEYCODE_F5 -> EguiKey.F5
            KeyEvent.KEYCODE_F6 -> EguiKey.F6
            KeyEvent.KEYCODE_F7 -> EguiKey.F7
            KeyEvent.KEYCODE_F8 -> EguiKey.F8
            KeyEvent.KEYCODE_F9 -> EguiKey.F9
            KeyEvent.KEYCODE_F10 -> EguiKey.F10
            KeyEvent.KEYCODE_F11 -> EguiKey.F11
            KeyEvent.KEYCODE_F12 -> EguiKey.F12
            else -> null
        }
    }
}
