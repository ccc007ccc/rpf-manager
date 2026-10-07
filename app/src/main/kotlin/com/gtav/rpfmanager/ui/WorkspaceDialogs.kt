package com.gtav.rpfmanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.*
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel

@Composable
fun WorkspaceDialogs(state: AppState, vm: ArchiveViewModel) {
    if (state.searchIssuesVisible)
        AlertDialog(
            onDismissRequest = vm::dismissSearchSkipped,
            title = { Text(stringResource(R.string.search_skipped_title)) },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Text(stringResource(R.string.search_skipped_body)) }
                    items(state.storageSearchSkipped) {
                        Text(
                            it.path + "\n" + it.message,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::dismissSearchSkipped) {
                    Text(stringResource(R.string.batch_done))
                }
            },
        )
    state.overwriteTarget?.let { name ->
        AlertDialog(
            modifier = Modifier.testTag("overwrite_confirmation"),
            onDismissRequest = vm::dismissOverwriteSave,
            title = { Text(stringResource(R.string.overwrite_title)) },
            text = { Text(stringResource(R.string.overwrite_body, name)) },
            confirmButton = {
                TextButton(
                    onClick = vm::confirmOverwriteSave,
                    modifier = Modifier.testTag("overwrite_execute"),
                ) {
                    Text(stringResource(R.string.overwrite_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissOverwriteSave) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    state.overwriteDirectoryTarget?.let { name ->
        AlertDialog(
            onDismissRequest = vm::dismissOverwriteDirectory,
            title = { Text(stringResource(R.string.overwrite_directory_title)) },
            text = { Text(stringResource(R.string.overwrite_directory_body, name)) },
            confirmButton = {
                TextButton(onClick = vm::chooseOverwriteDirectory) {
                    Text(stringResource(R.string.overwrite_choose_directory))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissOverwriteDirectory) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    state.storageEdit?.let { edit ->
        val valid = runCatching { requireWorkspaceName(edit.text) }.isSuccess
        AlertDialog(
            onDismissRequest = vm::dismissStorageEdit,
            title = {
                Text(
                    stringResource(
                        if (edit.operation == "mkdir") R.string.new_folder else R.string.rename
                    )
                )
            },
            text = {
                OutlinedTextField(
                    edit.text,
                    vm::updateStorageDraft,
                    Modifier.fillMaxWidth().testTag("storage_edit_name"),
                    singleLine = true,
                    label = { Text(stringResource(R.string.entry_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = vm::submitStorageEdit,
                    enabled = valid,
                    modifier = Modifier.testTag("storage_apply_edit"),
                ) {
                    Text(stringResource(R.string.apply_changes))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissStorageEdit) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    state.batchConfirmation?.let { batch ->
        val deleting = batch.kind in listOf(BatchKind.DeleteArchive, BatchKind.DeleteStorage)
        val replacing = batch.kind in listOf(BatchKind.Replace, BatchKind.TargetedReplace)
        val inArchive =
            batch.kind in
                listOf(
                    BatchKind.Import,
                    BatchKind.Replace,
                    BatchKind.TargetedReplace,
                    BatchKind.DeleteArchive,
                )
        var policy by
            remember(batch) {
                mutableStateOf(if (replacing) ConflictPolicy.Replace else ConflictPolicy.Skip)
            }
        val title =
            when (batch.kind) {
                BatchKind.Import -> R.string.batch_import_title
                BatchKind.Replace -> R.string.batch_replace_title
                BatchKind.TargetedReplace -> R.string.batch_targeted_title
                BatchKind.Export -> R.string.batch_export_title
                else -> R.string.batch_delete_title
            }
        AlertDialog(
            modifier = Modifier.testTag("batch_confirmation"),
            onDismissRequest = vm::dismissBatch,
            title = { Text(stringResource(title)) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.batch_source, batch.source))
                    if (!deleting)
                        Text(
                            stringResource(R.string.batch_destination, batch.destination),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    Text(
                        if (deleting) stringResource(R.string.batch_delete_count, batch.count)
                        else
                            stringResource(
                                R.string.batch_counts,
                                batch.count,
                                batch.additions,
                                batch.conflicts,
                            )
                    )
                    if (!deleting)
                        Text(
                            stringResource(R.string.batch_recursive),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    if (batch.kind == BatchKind.Replace)
                        Text(stringResource(R.string.batch_replace_note))
                    if (batch.kind == BatchKind.TargetedReplace)
                        Text(stringResource(R.string.batch_targeted_note))
                    if (batch.skipped > 0)
                        Text(stringResource(R.string.batch_unmatched, batch.skipped))
                    if (batch.conflicts > 0 && !replacing) {
                        Text(
                            stringResource(R.string.batch_conflict_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        ConflictPolicy.entries.forEach { choice ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(policy == choice, role = Role.RadioButton) {
                                        policy = choice
                                    }
                                    .testTag("conflict_${choice.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(policy == choice, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(
                                        if (choice == ConflictPolicy.Skip) R.string.batch_skip
                                        else R.string.batch_overwrite
                                    )
                                )
                            }
                        }
                    }
                    if (
                        batch.kind == BatchKind.Export &&
                            batch.conflicts > 0 &&
                            policy == ConflictPolicy.Replace
                    )
                        Text(
                            stringResource(R.string.batch_export_replace_note),
                            color = MaterialTheme.colorScheme.error,
                        )
                    if (inArchive)
                        Text(
                            stringResource(R.string.batch_workspace_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    if (batch.kind == BatchKind.DeleteStorage)
                        Text(
                            stringResource(R.string.batch_storage_delete_note),
                            color = MaterialTheme.colorScheme.error,
                        )
                    if (batch.issues.isNotEmpty()) {
                        Text(
                            stringResource(R.string.batch_issues, batch.issues.size),
                            color = MaterialTheme.colorScheme.error,
                        )
                        batch.issues.take(4).forEach {
                            Text(
                                it.path + "\n" + it.message,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    HorizontalDivider()
                    batch.paths.take(12).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (batch.paths.size > 12)
                        Text(stringResource(R.string.batch_more_paths, batch.paths.size - 12))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.executeBatch(policy) },
                    modifier = Modifier.testTag("batch_execute"),
                ) {
                    Text(
                        stringResource(
                            if (deleting) R.string.delete_entry else R.string.batch_start
                        ),
                        color =
                            if (deleting) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissBatch) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    state.batchResult?.let { result ->
        AlertDialog(
            modifier = Modifier.testTag("batch_result"),
            onDismissRequest = vm::dismissBatchResult,
            title = {
                Text(
                    stringResource(
                        if (result.cancelled) R.string.batch_result_cancelled
                        else R.string.batch_result_title
                    )
                )
            },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            stringResource(
                                R.string.batch_result_counts,
                                result.completed,
                                result.skipped,
                                result.failed,
                            )
                        )
                    }
                    if (result.cancelled)
                        item { Text(stringResource(R.string.batch_cancelled_note)) }
                    items(result.failures) { failure ->
                        Text(
                            failure.path + "\n" + failure.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = vm::dismissBatchResult,
                    modifier = Modifier.testTag("batch_done"),
                ) {
                    Text(stringResource(R.string.batch_done))
                }
            },
        )
    }
}
