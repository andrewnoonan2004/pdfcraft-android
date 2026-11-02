package com.andrewnoonan.pdfcraft.ui.workbench

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrewnoonan.pdfcraft.input.StickyModifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DesktopControlBarUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun desktopControlOverlay_expandedAndCollapsed_dispatchesModifiersAndCommands() {
        var ctrlClicked = false
        var rightClickToggled = false
        var collapsedClicked = false
        val firedCommands = mutableListOf<String>()

        composeTestRule.setContent {
            DesktopControlOverlay(
                state = WorkbenchUiState(
                    windowTitle = "showcase.pdf — PdfCraft",
                    isDesktopBarExpanded = true,
                    stickyModifiers = StickyModifiers(ctrl = true),
                ),
                onToggleExpanded = { collapsedClicked = true },
                onToggleCtrl = { ctrlClicked = true },
                onToggleShift = {},
                onToggleAlt = {},
                onToggleRightClick = { rightClickToggled = true },
                onCommand = { firedCommands += it },
                onToggleKeyboard = {},
                onShareDocument = {},
                onAdjustScale = {},
            )
        }

        composeTestRule.onNodeWithTag("btn_mod_ctrl").performClick()
        assertTrue(ctrlClicked)

        composeTestRule.onNodeWithTag("btn_mod_right_click").performClick()
        assertTrue(rightClickToggled)

        composeTestRule.onNodeWithTag("btn_cmd_palette").performClick()
        composeTestRule.onNodeWithTag("btn_cmd_open").performClick()
        composeTestRule.onNodeWithTag("btn_cmd_save").performClick()
        assertEquals(listOf("app.command_palette", "file.open", "file.save"), firedCommands)

        composeTestRule.onNodeWithTag("btn_toggle_dock").performClick()
        assertTrue(collapsedClicked)
    }
}
