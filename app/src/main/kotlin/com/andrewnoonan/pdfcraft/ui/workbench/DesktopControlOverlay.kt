package com.andrewnoonan.pdfcraft.ui.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val DockSurfaceColor = Color(0xEB181A20)
private val DockBorderColor = Color(0xFF343846)
private val ChipIdleColor = Color(0xFF252834)
private val ChipActiveAccent = Color(0xFFB70228)
private val TextPrimary = Color(0xFFF3F4F6)
private val TextMuted = Color(0xFF9CA3AF)

@Composable
fun DesktopControlOverlay(
    state: WorkbenchUiState,
    onToggleExpanded: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleRightClick: () -> Unit,
    onCommand: (String) -> Unit,
    onToggleKeyboard: () -> Unit,
    onShareDocument: () -> Unit,
    onAdjustScale: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isDesktopBarVisible) {
        return
    }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier
            .offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    dragOffsetX += dragAmount.x
                    dragOffsetY += dragAmount.y
                }
            },
        shape = RoundedCornerShape(14.dp),
        color = DockSurfaceColor,
        border = BorderStroke(1.dp, DockBorderColor),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DockPillButton(
                label = if (state.isDesktopBarExpanded) "▾ Dock" else "▴ Desktop",
                active = !state.isDesktopBarExpanded && state.stickyModifiers.anyActive,
                tag = "btn_toggle_dock",
                onClick = onToggleExpanded,
            )

            if (state.isDesktopBarExpanded) {
                DockDivider()

                // Sticky Desktop Modifiers
                DockPillButton(
                    label = "Ctrl",
                    active = state.stickyModifiers.ctrl,
                    tag = "btn_mod_ctrl",
                    onClick = onToggleCtrl,
                )
                DockPillButton(
                    label = "Shift",
                    active = state.stickyModifiers.shift,
                    tag = "btn_mod_shift",
                    onClick = onToggleShift,
                )
                DockPillButton(
                    label = "Alt",
                    active = state.stickyModifiers.alt,
                    tag = "btn_mod_alt",
                    onClick = onToggleAlt,
                )
                DockPillButton(
                    label = "Right-Click",
                    active = state.stickyModifiers.rightClickNextTap,
                    tag = "btn_mod_right_click",
                    onClick = onToggleRightClick,
                )

                DockDivider()

                // Core Desktop Commands
                DockPillButton(
                    label = "Ctrl+K",
                    active = false,
                    tag = "btn_cmd_palette",
                    onClick = { onCommand("app.command_palette") },
                )
                DockPillButton(
                    label = "Open",
                    active = false,
                    tag = "btn_cmd_open",
                    onClick = { onCommand("file.open") },
                )
                DockPillButton(
                    label = if (state.hasUnsavedChanges) "Save •" else "Save",
                    active = state.hasUnsavedChanges,
                    tag = "btn_cmd_save",
                    onClick = { onCommand("file.save") },
                )
                DockPillButton(
                    label = "Save As",
                    active = false,
                    tag = "btn_cmd_save_as",
                    onClick = { onCommand("file.save_as") },
                )
                DockPillButton(
                    label = "Undo",
                    active = false,
                    tag = "btn_cmd_undo",
                    onClick = { onCommand("edit.undo") },
                )
                DockPillButton(
                    label = "Redo",
                    active = false,
                    tag = "btn_cmd_redo",
                    onClick = { onCommand("edit.redo") },
                )
                DockPillButton(
                    label = "Find",
                    active = false,
                    tag = "btn_cmd_find",
                    onClick = { onCommand("edit.find") },
                )
                DockPillButton(
                    label = "Tools",
                    active = false,
                    tag = "btn_cmd_tools",
                    onClick = { onCommand("view.tools_pane") },
                )
                DockPillButton(
                    label = "Panels",
                    active = false,
                    tag = "btn_cmd_panels",
                    onClick = { onCommand("view.side_panel") },
                )
                DockPillButton(
                    label = "Print",
                    active = false,
                    tag = "btn_cmd_print",
                    onClick = { onCommand("print.dialog") },
                )

                DockDivider()

                // Android Host Bridges & UI Scale
                if (state.activeDocumentPath != null) {
                    DockPillButton(
                        label = "Share",
                        active = false,
                        tag = "btn_share",
                        onClick = onShareDocument,
                    )
                }
                DockPillButton(
                    label = "Keyboard",
                    active = state.wantsSoftKeyboard,
                    tag = "btn_keyboard",
                    onClick = onToggleKeyboard,
                )
                DockPillButton(
                    label = "−",
                    active = false,
                    tag = "btn_scale_down",
                    onClick = { onAdjustScale(-10) },
                )
                Text(
                    text = "${state.uiScalePercent}%",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                DockPillButton(
                    label = "+",
                    active = false,
                    tag = "btn_scale_up",
                    onClick = { onAdjustScale(10) },
                )
            }
        }
    }
}

@Composable
private fun DockPillButton(
    label: String,
    active: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val bg = if (active) ChipActiveAccent else ChipIdleColor
    val borderColor = if (active) Color(0xFFFF4D6D) else Color(0xFF3B4052)
    Box(
        modifier = Modifier
            .testTag(tag)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun DockDivider() {
    Spacer(
        modifier = Modifier
            .width(1.dp)
            .height(18.dp)
            .background(DockBorderColor),
    )
}
