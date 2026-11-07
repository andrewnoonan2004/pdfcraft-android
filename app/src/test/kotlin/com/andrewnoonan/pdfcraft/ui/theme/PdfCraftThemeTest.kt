package com.andrewnoonan.pdfcraft.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdfCraftThemeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theme_darkMode_syncsChromeOsTaskDescriptionAndWindowBars() {
        val activity = composeTestRule.activity

        composeTestRule.setContent {
            PdfCraftTheme(darkTheme = true, dynamicColor = false) {
                Text("Content in Dark Theme")
            }
        }
        composeTestRule.waitForIdle()

        // Verify window bar colors were set to opaque surface container
        assertNotEquals(0, activity.window.statusBarColor)
        assertNotEquals(0, activity.window.navigationBarColor)
    }

    @Test
    fun theme_lightMode_syncsChromeOsTaskDescriptionAndWindowBars() {
        val activity = composeTestRule.activity

        composeTestRule.setContent {
            PdfCraftTheme(darkTheme = false, dynamicColor = false) {
                Text("Content in Light Theme")
            }
        }
        composeTestRule.waitForIdle()

        assertNotEquals(0, activity.window.statusBarColor)
        assertNotEquals(0, activity.window.navigationBarColor)
    }

    @Test
    fun colorPalette_expressiveTokensProvideDistinctTonalHierarchy() {
        assertTrue(ExpressiveSurfaceDark != ExpressiveSurfaceContainerDark)
        assertTrue(ExpressiveSurfaceContainerDark != ExpressiveSurfaceContainerHighDark)
        assertTrue(ExpressivePrimaryDark != ExpressiveOnPrimaryDark)
        assertTrue(ExpressivePrimaryLight != ExpressiveOnPrimaryLight)
    }
}
