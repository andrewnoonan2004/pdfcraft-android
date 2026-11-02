package com.andrewnoonan.pdfcraft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformEffectsParserTest {

    @Test
    fun parse_validCompleteEffects_deserializesAllFields() {
        val raw = """
            {
              "cursorIcon": "pointing_hand",
              "wantsIme": true,
              "imeRect": {"x": 120.5, "y": 48.0, "width": 200.0, "height": 28.0},
              "repaintAfterMs": 16,
              "copyText": "Invoice #1042",
              "openUrl": "https://getartcraft.com/apps/pdfcraft",
              "windowTitle": "contract.pdf • — PdfCraft",
              "activePath": "/data/user/10/com.andrewnoonan.pdfcraft/files/saf_workspace/abc/contract.pdf",
              "hasUnsavedChanges": true,
              "closeRequested": false,
              "pickerRequest": {
                "id": 42,
                "mode": "save_file",
                "title": "Save As",
                "suggestedName": "contract_signed.pdf",
                "extensions": ["pdf"]
              },
              "savedFile": {
                "path": "/data/user/10/com.andrewnoonan.pdfcraft/files/saf_workspace/abc/contract.pdf"
              },
              "printJob": {
                "jobName": "contract.pdf",
                "pdfPath": "/data/user/10/com.andrewnoonan.pdfcraft/cache/print_job.pdf"
              }
            }
        """.trimIndent()

        val effects = PlatformEffectsParser.parse(raw)
        assertEquals("pointing_hand", effects.cursorIcon)
        assertTrue(effects.wantsIme)
        assertNotNull(effects.imeRect)
        assertEquals(120.5f, effects.imeRect!!.x, 0.01f)
        assertEquals(16L, effects.repaintAfterMs)
        assertEquals("Invoice #1042", effects.copyText)
        assertEquals("https://getartcraft.com/apps/pdfcraft", effects.openUrl)
        assertEquals("contract.pdf • — PdfCraft", effects.windowTitle)
        assertTrue(effects.hasUnsavedChanges)
        assertFalse(effects.closeRequested)
        val picker = effects.pickerRequest
        assertNotNull(picker)
        assertEquals(42L, picker!!.id)
        assertEquals("save_file", picker.mode)
        assertEquals("contract_signed.pdf", picker.suggestedName)
        assertEquals(listOf("pdf"), picker.extensions)
        assertNotNull(effects.savedFile)
        assertNotNull(effects.printJob)
        assertEquals("contract.pdf", effects.printJob!!.jobName)
    }

    @Test
    fun parse_malformedOrEmptyJson_returnsSafeDefaultsWithoutThrowing() {
        val empty = PlatformEffectsParser.parse("")
        assertEquals("default", empty.cursorIcon)
        assertFalse(empty.wantsIme)
        assertNull(empty.pickerRequest)

        val broken = PlatformEffectsParser.parse("{not-valid-json")
        assertEquals("default", broken.cursorIcon)
        assertEquals("PdfCraft", broken.windowTitle)
    }
}
