package com.andrewnoonan.pdfcraft.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrewnoonan.pdfcraft.ui.theme.AccentUnsavedAmber

/**
 * Material 3 Expressive desktop titlebar matching the theme and craftsmanship of PdfCraft.
 *
 * Provides:
 * - Brand badge & document title with real-time dirty/unsaved status indicator
 * - Quick desktop actions: Command Palette (Ctrl+K), Open (Ctrl+O), Save (Ctrl+S), Share
 * - Overflow menu for secondary commands and workbench controls
 */
@Composable
fun PdfCraftTitleBar(
    state: WorkbenchUiState,
    onCommand: (String) -> Unit,
    onShareDocument: () -> Unit,
    onToggleDesktopBar: () -> Unit,
    onToggleKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .testTag("pdfcraft_titlebar"),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 10.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // --- Left Section: App Brand & Document Title ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Expressive App Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "PDF",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                        )
                    }

                    Text(
                        text = "PdfCraft",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    // Subtle separator
                    Spacer(
                        modifier = Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )

                    // Clean Document Display Name
                    val displayTitle = state.windowTitle.ifBlank { "Ready" }
                    Text(
                        text = displayTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("titlebar_doc_title"),
                    )

                    // Expressive Unsaved Changes Badge
                    if (state.hasUnsavedChanges) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .testTag("tag_unsaved_indicator")
                                .clip(RoundedCornerShape(percent = 50))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(AccentUnsavedAmber),
                            )
                            Text(
                                text = "Unsaved",
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // --- Right Section: Quick Expressive Desktop Actions ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Command Palette (Ctrl+K)
                    TitleBarActionButton(
                        label = "⌘K Palette",
                        active = false,
                        tag = "titlebar_btn_palette",
                        onClick = { onCommand("app.command_palette") },
                    )

                    // Open Document (Ctrl+O)
                    TitleBarActionButton(
                        label = "Open",
                        active = false,
                        tag = "titlebar_btn_open",
                        onClick = { onCommand("file.open") },
                    )

                    // Save Document (Ctrl+S) - Expressive accent if dirty
                    TitleBarActionButton(
                        label = if (state.hasUnsavedChanges) "Save •" else "Save",
                        active = state.hasUnsavedChanges,
                        accentColor = if (state.hasUnsavedChanges) MaterialTheme.colorScheme.primaryContainer else null,
                        tag = "titlebar_btn_save",
                        onClick = { onCommand("file.save") },
                    )

                    // Share (when file is open)
                    if (state.activeDocumentPath != null) {
                        TitleBarActionButton(
                            label = "Share",
                            active = false,
                            tag = "titlebar_btn_share",
                            onClick = onShareDocument,
                        )
                    }

                    // Overflow Menu
                    Box {
                        TitleBarActionButton(
                            label = "•••",
                            active = menuExpanded,
                            tag = "titlebar_btn_menu",
                            onClick = { menuExpanded = true },
                        )

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Save As...", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    menuExpanded = false
                                    onCommand("file.save_as")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Undo", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    menuExpanded = false
                                    onCommand("edit.undo")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Redo", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    menuExpanded = false
                                    onCommand("edit.redo")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Find in Document", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    menuExpanded = false
                                    onCommand("edit.find")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Print...", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    menuExpanded = false
                                    onCommand("print.dialog")
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            DropdownMenuItem(
                                text = { Text("Toggle Desktop Dock", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    menuExpanded = false
                                    onToggleDesktopBar()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Toggle Soft Keyboard", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    menuExpanded = false
                                    onToggleKeyboard()
                                },
                            )
                        }
                    }
                }
            }

            // Bottom border separator for clean window chrome
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun TitleBarActionButton(
    label: String,
    active: Boolean,
    tag: String,
    accentColor: Color? = null,
    onClick: () -> Unit,
) {
    val bg = when {
        accentColor != null -> accentColor
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        accentColor != null -> MaterialTheme.colorScheme.onPrimaryContainer
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (active || accentColor != null) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }

    Box(
        modifier = Modifier
            .testTag(tag)
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(percent = 50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (active || accentColor != null) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
