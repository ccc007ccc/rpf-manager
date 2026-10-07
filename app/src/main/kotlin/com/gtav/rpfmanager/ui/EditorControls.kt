package com.gtav.rpfmanager.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.*
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel

internal fun Session.hasOverwriteDestination(): Boolean {
    val outer = generateSequence(this) { it.parent }.last()
    return !outer.demo && outer.sourceUri != null && outer.info?.editable == true
}

@Composable
fun ArchiveTools(
    state: AppState,
    vm: ArchiveViewModel,
    leadingActions: @Composable RowScope.() -> Unit = {},
) {
    val info = state.location?.session?.info
    val canOverwrite = state.location?.session?.hasOverwriteDestination() == true
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingActions()
        if (info?.editable == true) {
            TextButton(
                onClick = { vm.requestImport() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("add_file"),
            ) {
                RpfIcon(Glyph.Plus, Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.add_file))
            }
            TextButton(
                onClick = { vm.showEdit("mkdir") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("new_folder"),
            ) {
                Text(stringResource(R.string.new_folder))
            }
            IconAction(
                Glyph.Reset,
                stringResource(R.string.undo),
                Modifier.testTag("undo"),
                enabled = info.undo,
                onClick = vm::undo,
            )
            IconAction(
                Glyph.Redo,
                stringResource(R.string.redo),
                Modifier.testTag("redo"),
                enabled = info.redo,
                onClick = vm::redo,
            )
        }
        var expanded by remember { mutableStateOf(false) }
        Box {
            IconAction(
                Glyph.More,
                stringResource(R.string.archive_actions),
                Modifier.testTag("archive_actions"),
                onClick = { expanded = true },
            )
            DropdownMenu(expanded, { expanded = false }) {
                if (info?.editable == true) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ws_overwrite_save)) },
                        leadingIcon = { RpfIcon(Glyph.Save) },
                        modifier = Modifier.testTag("overwrite_save"),
                        enabled = canOverwrite,
                        onClick = {
                            expanded = false
                            vm.requestOverwriteSave()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.save_as)) },
                        leadingIcon = { RpfIcon(Glyph.Save) },
                        modifier = Modifier.testTag("save_as"),
                        onClick = {
                            expanded = false
                            vm.saveWorkspaceAs()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scan_archive)) },
                    leadingIcon = { RpfIcon(Glyph.Search) },
                    modifier = Modifier.testTag("scan_archive"),
                    onClick = {
                        expanded = false
                        vm.showCoverage()
                    },
                )
            }
        }
    }
}

@Composable
fun EntryActions(entry: FileEntry, vm: ArchiveViewModel) {
    var expanded by remember(entry.path) { mutableStateOf(false) }
    Box {
        IconAction(
            Glyph.More,
            stringResource(R.string.entry_actions, entry.name),
            Modifier.testTag("actions_${entry.name}"),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                modifier = Modifier.testTag("rename_entry"),
                onClick = {
                    expanded = false
                    vm.showEdit("rename", entry)
                },
            )
            if (!entry.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.replace_file)) },
                    onClick = {
                        expanded = false
                        vm.requestImport(entry)
                    },
                )
                if (entry.kind == AssetKind.Text)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_text)) },
                        modifier = Modifier.testTag("edit_text"),
                        onClick = {
                            expanded = false
                            vm.showEdit("text", entry)
                        },
                    )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.delete_entry),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                modifier = Modifier.testTag("delete_entry"),
                onClick = {
                    expanded = false
                    vm.showEdit("delete", entry)
                },
            )
        }
    }
}

@Composable
fun EditorDialogs(state: AppState, vm: ArchiveViewModel) {
    state.editDialog?.let { edit ->
        if (edit.operation == "text") {
            Dialog(
                onDismissRequest = vm::dismissEdit,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(Modifier.fillMaxSize().imePadding()) {
                    Column(Modifier.safeDrawingPadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconAction(
                                Glyph.Close,
                                stringResource(R.string.cancel),
                                onClick = vm::dismissEdit,
                            )
                            Text(
                                edit.entry?.name.orEmpty(),
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(
                                onClick = { vm.submitEdit(edit.text) },
                                modifier = Modifier.testTag("apply_edit"),
                            ) {
                                Text(stringResource(R.string.apply_changes))
                            }
                        }
                        OutlinedTextField(
                            edit.text,
                            vm::updateDraft,
                            Modifier.fillMaxWidth()
                                .weight(1f)
                                .padding(12.dp)
                                .testTag("text_editor"),
                            textStyle =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                        )
                    }
                }
            }
        } else {
            val label =
                when (edit.operation) {
                    "mkdir" -> R.string.new_folder
                    "rename" -> R.string.rename
                    else -> R.string.delete_entry
                }
            val nameValid =
                edit.text.isNotBlank() &&
                    edit.text !in listOf(".", "..") &&
                    edit.text.none { it == '/' || it == '\\' || it.isISOControl() }
            AlertDialog(
                onDismissRequest = vm::dismissEdit,
                title = { Text(stringResource(label)) },
                text = {
                    if (edit.operation == "delete")
                        Text(stringResource(R.string.delete_body, edit.entry?.name.orEmpty()))
                    else
                        OutlinedTextField(
                            edit.text,
                            vm::updateDraft,
                            Modifier.fillMaxWidth().testTag("edit_name"),
                            singleLine = true,
                            label = { Text(stringResource(R.string.entry_name)) },
                        )
                },
                confirmButton = {
                    TextButton(
                        onClick = { vm.submitEdit(edit.text) },
                        enabled = nameValid || edit.operation == "delete",
                        modifier = Modifier.testTag("apply_edit"),
                    ) {
                        Text(
                            stringResource(
                                if (edit.operation == "delete") R.string.delete_entry
                                else R.string.apply_changes
                            )
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = vm::dismissEdit) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
    if (state.confirmDiscard)
        AlertDialog(
            onDismissRequest = vm::keepWorkspace,
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_body)) },
            confirmButton = {
                TextButton(onClick = vm::saveBeforeLeaving) {
                    Text(stringResource(R.string.save_as))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = vm::discardWorkspace,
                        modifier = Modifier.testTag("discard_changes"),
                    ) {
                        Text(stringResource(R.string.discard_changes))
                    }
                    TextButton(
                        onClick = vm::keepWorkspace,
                        modifier = Modifier.testTag("keep_editing"),
                    ) {
                        Text(stringResource(R.string.keep_editing))
                    }
                }
            },
        )
}
