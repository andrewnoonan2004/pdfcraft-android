package com.andrewnoonan.pdfcraft.ui.workbench

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrewnoonan.pdfcraft.ui.theme.PdfCraftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PdfCraftTitleBarUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun titleBar_displaysDocumentTitle_andDispatchesQuickActions() {
        val firedCommands = mutableListOf<String>()
        var shareClicked = false
        var toggleDockClicked = false

        composeTestRule.setContent {
            PdfCraftTheme(dynamicColor = false) {
                PdfCraftTitleBar(
                    state = WorkbenchUiState(
                        windowTitle = "Quarterly_Report.pdf — PdfCraft",
                        activeDocumentPath = "/data/docs/Quarterly_Report.pdf",
                        hasUnsavedChanges = true,
                    ),
                    onCommand = { firedCommands += it },
                    onShareDocument = { shareClicked = true },
                    onToggleDesktopBar = { toggleDockClicked = true },
                    onToggleKeyboard = {},
                )
            }
        }

        // Verify titlebar and document title
        composeTestRule.onNodeWithTag("pdfcraft_titlebar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("titlebar_doc_title")
            .assertTextContains("Quarterly_Report.pdf", substring = true)

        // Verify dirty / unsaved changes badge
        composeTestRule.onNodeWithTag("tag_unsaved_indicator").assertIsDisplayed()

        // Click Command Palette shortcut (Ctrl+K)
        composeTestRule.onNodeWithTag("titlebar_btn_palette").performClick()
        assertEquals(listOf("app.command_palette"), firedCommands)

        // Click Open (Ctrl+O)
        composeTestRule.onNodeWithTag("titlebar_btn_open").performClick()
        assertEquals(listOf("app.command_palette", "file.open"), firedCommands)

        // Click Save (Ctrl+S)
        composeTestRule.onNodeWithTag("titlebar_btn_save").performClick()
        assertEquals(listOf("app.command_palette", "file.open", "file.save"), firedCommands)

        // Click Share
        composeTestRule.onNodeWithTag("titlebar_btn_share").performClick()
        assertTrue(shareClicked)

        // Click Menu & trigger menu item
        composeTestRule.onNodeWithTag("titlebar_btn_menu").performClick()
        composeTestRule.onNodeWithText("Toggle Desktop Dock").performClick()
        assertTrue(toggleDockClicked)
    }

    @Test
    fun titleBar_cleanState_omitsUnsavedBadgeAndShareWhenNoDocument() {
        composeTestRule.setContent {
            PdfCraftTheme(dynamicColor = false) {
                PdfCraftTitleBar(
                    state = WorkbenchUiState(
                        windowTitle = "PdfCraft",
                        activeDocumentPath = null,
                        hasUnsavedChanges = false,
                    ),
                    onCommand = {},
                    onShareDocument = {},
                    onToggleDesktopBar = {},
                    onToggleKeyboard = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("tag_unsaved_indicator").assertDoesNotExist()
        composeTestRule.onNodeWithTag("titlebar_btn_share").assertDoesNotExist()
    }
}
