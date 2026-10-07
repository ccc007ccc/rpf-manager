package com.gtav.rpfmanager.ui

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.*
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel

/** Two independent file browsers; inspecting a resource never replaces the storage pane. */
@Composable
fun WorkspaceScreen(state: AppState, vm: ArchiveViewModel) {
    val archivePositions =
        rememberSaveable(saver = BrowserPositionsSaver) { mutableMapOf<String, LazyListState>() }
    val storagePositions =
        rememberSaveable(saver = BrowserPositionsSaver) { mutableMapOf<String, LazyListState>() }
    val archiveEntries =
        remember(
            state.location?.entries,
            state.archiveSearchResults,
            state.query,
            state.filter,
            state.sort,
        ) {
            state.visibleEntries
        }
    val storageEntries =
        remember(
            state.storage?.entries,
            state.storageSearchResults,
            state.storageQuery,
            state.storageSort,
        ) {
            state.visibleStorageEntries
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val workspaceWidth = maxWidth
        val compactHeader = maxHeight < 480.dp && maxWidth >= 600.dp
        if (state.selected != null) {
            Inspector(
                state,
                vm,
                Modifier.fillMaxSize().testTag("resource_inspector"),
                wide = false,
                compact = maxHeight < 480.dp,
            )
        } else
            Column(Modifier.fillMaxSize().testTag("file_workspace")) {
                if (compactHeader) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WorkspaceHeader(
                            state,
                            vm,
                            Modifier.width((workspaceWidth * .4f).coerceIn(280.dp, 360.dp)),
                        )
                        VerticalDivider(
                            Modifier.height(40.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        PaneControls(state, vm, Modifier.weight(1f))
                    }
                } else {
                    WorkspaceHeader(state, vm)
                }
                if (!compactHeader) {
                    PaneTabs(state, vm, archiveEntries.size, storageEntries.size)
                    PaneControls(state, vm)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.dualPane || state.activePane == WorkspacePane.Archive) {
                        ArchivePane(
                            state,
                            vm,
                            archiveEntries,
                            archivePositions,
                            Modifier.weight(1f).fillMaxHeight(),
                            compact = compactHeader,
                        )
                    }
                    if (state.dualPane) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (state.dualPane || state.activePane == WorkspacePane.Storage) {
                        StoragePane(
                            state,
                            vm,
                            storageEntries,
                            storagePositions,
                            Modifier.weight(1f).fillMaxHeight(),
                            compact = compactHeader,
                        )
                    }
                }
                WorkspaceActions(state, vm, compact = compactHeader)
            }
    }
}

// Navigation, previewing, pane switching and rotation preserve each directory position.
private val BrowserPositionsSaver =
    listSaver<MutableMap<String, LazyListState>, Any>(
        save = { positions ->
            positions.flatMap { (key, position) ->
                listOf(key, position.firstVisibleItemIndex, position.firstVisibleItemScrollOffset)
            }
        },
        restore = { values ->
            values
                .chunked(3)
                .associate { item ->
                    item[0] as String to LazyListState(item[1] as Int, item[2] as Int)
                }
                .toMutableMap()
        },
    )

@Composable
private fun WorkspaceHeader(state: AppState, vm: ArchiveViewModel, modifier: Modifier = Modifier) {
    val location = state.location
    Row(
        modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Glyph.Back,
            stringResource(R.string.back),
            Modifier.testTag("workspace_back"),
            onClick = vm::back,
        )
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(
                location?.session?.name ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    if (state.workspaceDirty) R.string.unsaved_changes
                    else R.string.ws_file_workspace
                ),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (state.workspaceDirty) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.workspaceDirty && location?.session?.info?.editable == true) {
            val canOverwrite = location.session.hasOverwriteDestination()
            IconAction(
                Glyph.Save,
                stringResource(if (canOverwrite) R.string.ws_overwrite_save else R.string.save_as),
                Modifier.testTag("save_archive"),
                onClick = if (canOverwrite) vm::requestOverwriteSave else vm::saveWorkspaceAs,
            )
        }
        IconAction(
            Glyph.Layers,
            stringResource(if (state.dualPane) R.string.ws_single_pane else R.string.ws_dual_pane),
            Modifier.testTag("toggle_dual_pane"),
            active = state.dualPane,
            onClick = vm::toggleDualPane,
        )
        var expanded by remember { mutableStateOf(false) }
        Box {
            IconAction(
                Glyph.More,
                stringResource(R.string.ws_workspace_menu),
                Modifier.testTag("workspace_menu"),
                onClick = { expanded = true },
            )
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ws_open_archive)) },
                    leadingIcon = { RpfIcon(Glyph.Archive) },
                    modifier = Modifier.testTag("open_workspace_archive"),
                    onClick = {
                        expanded = false
                        vm.requestOpenArchive()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ws_library)) },
                    leadingIcon = { RpfIcon(Glyph.List) },
                    modifier = Modifier.testTag("workspace_library"),
                    onClick = {
                        expanded = false
                        vm.showLibrary()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings)) },
                    leadingIcon = { RpfIcon(Glyph.Settings) },
                    modifier = Modifier.testTag("settings"),
                    onClick = {
                        expanded = false
                        vm.showSettings(true)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.close)) },
                    leadingIcon = { RpfIcon(Glyph.Close) },
                    modifier = Modifier.testTag("close_workspace"),
                    onClick = {
                        expanded = false
                        vm.closeWorkspace()
                    },
                )
            }
        }
    }
}

@Composable
private fun PaneTabs(state: AppState, vm: ArchiveViewModel, archiveCount: Int, storageCount: Int) {
    Row(Modifier.fillMaxWidth()) {
        PaneTab(
            stringResource(R.string.ws_archive_pane),
            Glyph.Archive,
            archiveCount,
            state.archiveSelection.size,
            state.activePane == WorkspacePane.Archive,
            Modifier.weight(1f).testTag("archive_pane_tab"),
        ) {
            vm.activatePane(WorkspacePane.Archive)
        }
        PaneTab(
            stringResource(R.string.ws_storage_pane),
            Glyph.Folder,
            storageCount,
            state.storageSelection.size,
            state.activePane == WorkspacePane.Storage,
            Modifier.weight(1f).testTag("storage_pane_tab"),
        ) {
            vm.activatePane(WorkspacePane.Storage)
        }
    }
}

@Composable
private fun PaneTab(
    label: String,
    glyph: Glyph,
    count: Int,
    selection: Int,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.clickable(role = Role.Tab, onClick = onClick).semantics { selected = active }) {
        Row(
            Modifier.fillMaxWidth()
                .height(45.dp)
                .background(
                    if (active) colors.primaryContainer.copy(alpha = .5f) else colors.surface
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RpfIcon(
                glyph,
                Modifier.size(18.dp),
                tint = if (active) colors.primary else colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) colors.primary else colors.onSurfaceVariant,
            )
            Text(
                if (selection > 0) "$selection ✓" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selection > 0) colors.primary else colors.onSurfaceVariant,
            )
        }
        Box(
            Modifier.fillMaxWidth()
                .height(3.dp)
                .background(if (active) colors.primary else colors.outlineVariant.copy(alpha = .5f))
        )
    }
}

@Composable
private fun PaneControls(state: AppState, vm: ArchiveViewModel, modifier: Modifier = Modifier) {
    val archive = state.activePane == WorkspacePane.Archive
    val selecting = if (archive) state.archiveSelectionMode else state.storageSelectionMode
    val count = if (archive) state.archiveSelection.size else state.storageSelection.size
    val clear = if (archive) vm::clearArchiveSelection else vm::clearStorageSelection
    val selectAll = if (archive) vm::selectAllArchive else vm::selectAllStorage
    val invert = if (archive) vm::invertArchiveSelection else vm::invertStorageSelection
    Row(
        modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            IconAction(
                Glyph.Close,
                stringResource(R.string.ws_clear_selection),
                Modifier.testTag("clear_selection"),
                onClick = clear,
            )
            Text(
                stringResource(R.string.ws_selected_count, count),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = selectAll,
                modifier = Modifier.heightIn(min = 48.dp).testTag("select_all"),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Text(stringResource(R.string.ws_select_all), maxLines = 1)
            }
            TextButton(
                onClick = invert,
                modifier = Modifier.heightIn(min = 48.dp).testTag("invert_selection"),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Text(stringResource(R.string.ws_invert_selection), maxLines = 1)
            }
        } else {
            SearchField(
                if (archive) state.query else state.storageQuery,
                if (archive) vm::query else vm::storageQuery,
                stringResource(
                    if (archive) R.string.ws_search_archive else R.string.ws_search_storage
                ),
                if (archive) "search" else "storage_search",
                Modifier.weight(1f).padding(start = 4.dp),
                enabled = if (archive) state.location != null else state.storage != null,
            )
            SortMenu(state, vm, archive)
            IconAction(
                Glyph.Check,
                stringResource(R.string.ws_select_files),
                Modifier.testTag("begin_selection"),
                enabled = if (archive) state.location != null else state.storage != null,
                onClick = if (archive) vm::startArchiveSelection else vm::startStorageSelection,
            )
        }
    }
}

@Composable
private fun SortMenu(state: AppState, vm: ArchiveViewModel, archive: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconAction(
            Glyph.List,
            stringResource(R.string.ws_sort_filter),
            Modifier.testTag(if (archive) "sort" else "storage_sort"),
            active = archive && state.filter != AssetFilter.All,
            enabled = if (archive) state.location != null else state.storage != null,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded, { expanded = false }) {
            SortOrder.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.label())) },
                    trailingIcon = {
                        if ((if (archive) state.sort else state.storageSort) == sort)
                            RpfIcon(Glyph.Check)
                    },
                    onClick = {
                        if (archive) vm.sort(sort) else vm.storageSort(sort)
                        expanded = false
                    },
                )
            }
            if (archive) {
                HorizontalDivider()
                AssetFilter.entries.forEach { filter ->
                    DropdownMenuItem(
                        text = { Text(stringResource(filter.label())) },
                        modifier = Modifier.testTag("filter_${filter.name}"),
                        trailingIcon = { if (state.filter == filter) RpfIcon(Glyph.Check) },
                        onClick = {
                            vm.filter(filter)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivePane(
    state: AppState,
    vm: ArchiveViewModel,
    entries: List<FileEntry>,
    positions: MutableMap<String, LazyListState>,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val location = state.location
    if (location == null) {
        Column(modifier.testTag("archive_pane")) {
            PanePath(
                stringResource(R.string.ws_no_archive),
                stringResource(R.string.ws_no_archive),
                canUp = false,
                tag = "archive_path",
                enabled = false,
                pane = if (compact) WorkspacePane.Archive else null,
                paneActive = state.activePane == WorkspacePane.Archive,
                singlePane = !state.dualPane,
                onActivatePane = vm::activatePane,
                onUp = {},
            ) {}
            PaneEmpty(
                R.string.ws_open_archive_hint,
                Modifier.weight(1f),
                R.string.ws_open_archive,
            ) {
                vm.requestOpenArchive()
            }
        }
        return
    }
    val parts = location.path.split('/').filter(String::isNotBlank)
    val key = "${location.session.handle}:${location.path}"
    val listState = positions.getOrPut(key) { LazyListState() }
    Column(modifier.testTag("archive_pane")) {
        PanePath(
            label = parts.lastOrNull() ?: "/",
            fullPath = "${location.session.name}/${location.path}",
            canUp = location.path.isNotBlank() || location.session.parent != null,
            tag = "archive_path",
            pane = if (compact) WorkspacePane.Archive else null,
            paneActive = state.activePane == WorkspacePane.Archive,
            paneCount = entries.size,
            selectionCount = state.archiveSelection.size,
            singlePane = !state.dualPane,
            onActivatePane = vm::activatePane,
            onUp = {
                vm.activatePane(WorkspacePane.Archive)
                vm.archiveUp()
            },
        ) { close ->
            DropdownMenuItem(
                text = { Text(location.session.name) },
                leadingIcon = { RpfIcon(Glyph.Archive) },
                onClick = {
                    close()
                    vm.activatePane(WorkspacePane.Archive)
                    vm.navigateTo("")
                },
            )
            parts.forEachIndexed { index, part ->
                DropdownMenuItem(
                    text = { Text(part) },
                    leadingIcon = { RpfIcon(Glyph.Folder) },
                    onClick = {
                        close()
                        vm.activatePane(WorkspacePane.Archive)
                        vm.navigateTo(parts.take(index + 1).joinToString("/"))
                    },
                )
            }
        }
        if (state.archiveSearching) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().height(2.dp).testTag("archive_search_progress")
            )
        }
        if (entries.isEmpty()) {
            PaneEmpty(
                if (state.archiveSearching) R.string.ws_searching
                else if (state.query.isNotBlank() || state.filter != AssetFilter.All)
                    R.string.no_results
                else R.string.empty_folder,
                Modifier.weight(1f),
                if (
                    !state.archiveSearching &&
                        (state.query.isNotBlank() || state.filter != AssetFilter.All)
                )
                    R.string.clear_filters
                else null,
            ) {
                vm.query("")
                vm.filter(AssetFilter.All)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("file_list"),
                state = listState,
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(entries, key = { it.path }) { entry ->
                    CompactFileRow(
                        entry,
                        state.archiveSelection.contains(entry.path),
                        state.archiveSelectionMode,
                        "entry_${entry.name}",
                        compact = compact,
                        parentPath =
                            if (state.query.isNotBlank())
                                entry.path
                                    .substringBeforeLast('/', "")
                                    .removePrefix(location.path)
                                    .trim('/')
                                    .ifBlank { "/" }
                            else null,
                        onClick = {
                            vm.activatePane(WorkspacePane.Archive)
                            vm.select(entry)
                        },
                        onToggle = {
                            vm.activatePane(WorkspacePane.Archive)
                            vm.toggleArchiveSelection(entry)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StoragePane(
    state: AppState,
    vm: ArchiveViewModel,
    entries: List<StorageEntry>,
    positions: MutableMap<String, LazyListState>,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val location = state.storage
    val listState = positions.getOrPut(location?.directory?.uri.orEmpty()) { LazyListState() }
    Column(modifier.testTag("storage_pane")) {
        PanePath(
            label = location?.directory?.displayName() ?: stringResource(R.string.ws_no_storage),
            fullPath =
                location?.let { storageDisplayPath(it) } ?: stringResource(R.string.ws_no_storage),
            canUp = (location?.breadcrumbs?.size ?: 0) > 1,
            tag = "storage_path",
            pane = if (compact) WorkspacePane.Storage else null,
            paneActive = state.activePane == WorkspacePane.Storage,
            paneCount = entries.size,
            selectionCount = state.storageSelection.size,
            singlePane = !state.dualPane,
            onActivatePane = vm::activatePane,
            onUp = {
                vm.activatePane(WorkspacePane.Storage)
                vm.storageUp()
            },
        ) { close ->
            location?.breadcrumbs?.forEachIndexed { index, part ->
                DropdownMenuItem(
                    text = { Text(part.displayName()) },
                    leadingIcon = { RpfIcon(Glyph.Folder) },
                    onClick = {
                        close()
                        vm.activatePane(WorkspacePane.Storage)
                        vm.storageNavigateTo(index)
                    },
                )
            }
            if (location != null) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ws_internal_storage)) },
                leadingIcon = { RpfIcon(Glyph.Folder) },
                modifier = Modifier.testTag("storage_internal_root"),
                onClick = {
                    close()
                    vm.openInternalStorage()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ws_choose_storage)) },
                leadingIcon = { RpfIcon(Glyph.Plus) },
                modifier = Modifier.testTag("choose_storage"),
                onClick = {
                    close()
                    vm.requestStorageDirectory()
                },
            )
            if (location != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ws_refresh)) },
                    leadingIcon = { RpfIcon(Glyph.Reset) },
                    onClick = {
                        close()
                        vm.refreshStorage()
                    },
                )
            }
        }
        if (location == null) {
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RpfIcon(
                    Glyph.Folder,
                    Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.ws_connect_storage),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ws_storage_access_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = vm::requestInternalStorage,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("connect_storage"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(stringResource(R.string.ws_access_internal_storage), maxLines = 2)
                }
                TextButton(
                    onClick = vm::requestStorageDirectory,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("connect_other_storage"),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.ws_other_folder), maxLines = 2)
                }
            }
        } else {
            if (state.storageSearching) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp).testTag("storage_search_progress")
                )
            }
            if (state.storageQuery.isNotBlank() && state.storageSearchSkipped.isNotEmpty()) {
                TextButton(
                    onClick = vm::showSearchSkipped,
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("storage_search_issues"),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.ws_search_skipped, state.storageSearchSkipped.size),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                    )
                }
            }
            if (entries.isEmpty()) {
                PaneEmpty(
                    if (state.storageSearching) R.string.ws_searching
                    else if (state.storageQuery.isNotBlank()) R.string.no_results
                    else R.string.empty_folder,
                    Modifier.weight(1f),
                    if (!state.storageSearching && state.storageQuery.isNotBlank())
                        R.string.clear_search
                    else null,
                ) {
                    vm.storageQuery("")
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("storage_file_list"),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(entries, key = { it.uri }) { entry ->
                        CompactFileRow(
                            FileEntry(entry.name, entry.size, entry.isDirectory, entry.uri),
                            state.storageSelection.contains(entry.uri),
                            state.storageSelectionMode,
                            "storage_entry_${entry.name}",
                            compact = compact,
                            parentPath =
                                if (state.storageQuery.isNotBlank())
                                    storageRelativeParent(entry, location)
                                else null,
                            onClick = {
                                vm.activatePane(WorkspacePane.Storage)
                                if (state.storageSelectionMode || !entry.isDirectory)
                                    vm.toggleStorageSelection(entry)
                                else vm.openStorageEntry(entry)
                            },
                            onToggle = {
                                vm.activatePane(WorkspacePane.Storage)
                                vm.toggleStorageSelection(entry)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun StorageEntry.isInternalRoot(): Boolean =
    Uri.parse(uri).let {
        it.scheme == "file" &&
            it.path?.trimEnd('/') == Environment.getExternalStorageDirectory().path.trimEnd('/')
    }

@Composable
private fun StorageEntry.displayName(): String =
    if (isInternalRoot()) stringResource(R.string.ws_internal_storage) else name

@Composable
private fun storageDisplayPath(location: StorageLocation): String {
    val internal = stringResource(R.string.ws_internal_storage)
    return location.breadcrumbs.joinToString("/") { if (it.isInternalRoot()) internal else it.name }
}

private fun storageRelativeParent(entry: StorageEntry, location: StorageLocation): String {
    fun documentPath(value: String): String {
        val uri = Uri.parse(value)
        return if (uri.scheme == "file") uri.path.orEmpty()
        else
            runCatching { DocumentsContract.getDocumentId(uri) }
                .getOrElse { Uri.decode(uri.lastPathSegment.orEmpty()) }
    }
    val parent = documentPath(entry.parentUri).trimEnd('/')
    val current = documentPath(location.directory.uri).trimEnd('/')
    return when {
        parent == current -> "/"
        parent.startsWith("$current/") -> parent.removePrefix("$current/")
        else -> parent.substringAfter(':').ifBlank { "/" }
    }
}

@Composable
private fun PanePath(
    label: String,
    fullPath: String,
    canUp: Boolean,
    tag: String,
    enabled: Boolean = true,
    pane: WorkspacePane? = null,
    paneActive: Boolean = false,
    paneCount: Int = 0,
    selectionCount: Int = 0,
    singlePane: Boolean = false,
    onActivatePane: (WorkspacePane) -> Unit = {},
    onUp: () -> Unit,
    menu: @Composable ColumnScope.(() -> Unit) -> Unit,
) {
    if (pane != null) {
        CompactPanePath(
            label,
            fullPath,
            canUp,
            tag,
            enabled,
            pane,
            paneActive,
            paneCount,
            selectionCount,
            singlePane,
            onActivatePane,
            onUp,
            menu,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Glyph.ArrowUp,
            stringResource(R.string.ws_parent_folder),
            Modifier.testTag("${tag}_up"),
            enabled = enabled && canUp,
            onClick = onUp,
        )
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled) { expanded = true }
                    .semantics { contentDescription = fullPath }
                    .testTag(tag)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(4.dp))
                RpfIcon(
                    Glyph.Down,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded,
                { expanded = false },
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                menu { expanded = false }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
}

/** A 48dp pane tab and breadcrumb row preserves room for files on short landscapes. */
@Composable
private fun CompactPanePath(
    label: String,
    fullPath: String,
    canUp: Boolean,
    tag: String,
    enabled: Boolean,
    pane: WorkspacePane,
    active: Boolean,
    count: Int,
    selection: Int,
    singlePane: Boolean,
    onActivate: (WorkspacePane) -> Unit,
    onUp: () -> Unit,
    menu: @Composable ColumnScope.(() -> Unit) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val archive = pane == WorkspacePane.Archive
    val paneTitle =
        stringResource(if (archive) R.string.ws_archive_pane else R.string.ws_storage_pane)
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .height(48.dp)
                .background(
                    if (active) colors.primaryContainer.copy(alpha = .4f) else colors.surface
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                Glyph.ArrowUp,
                stringResource(R.string.ws_parent_folder),
                Modifier.testTag("${tag}_up"),
                enabled = enabled && canUp,
                onClick = onUp,
            )
            Column(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .clickable(role = Role.Tab) { onActivate(pane) }
                    .semantics { selected = active }
                    .testTag(if (archive) "archive_pane_tab" else "storage_pane_tab")
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        paneTitle,
                        Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) colors.primary else colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (selection > 0) "$selection ✓" else count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selection > 0) colors.primary else colors.onSurfaceVariant,
                    )
                }
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (singlePane) {
                val other = if (archive) WorkspacePane.Storage else WorkspacePane.Archive
                TextButton(
                    onClick = { onActivate(other) },
                    modifier =
                        Modifier.height(48.dp)
                            .testTag(if (archive) "storage_pane_tab" else "archive_pane_tab"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(
                        stringResource(
                            if (archive) R.string.ws_storage_pane else R.string.ws_archive_pane
                        ),
                        maxLines = 1,
                    )
                }
            }
            Box {
                IconAction(
                    Glyph.Down,
                    fullPath,
                    Modifier.testTag(tag),
                    enabled = enabled,
                    onClick = { expanded = true },
                )
                DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(max = 320.dp)) {
                    menu { expanded = false }
                }
            }
        }
        if (active)
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(colors.primary)
            )
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactFileRow(
    entry: FileEntry,
    checked: Boolean,
    selecting: Boolean,
    tag: String,
    parentPath: String? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = if (compact) 52.dp else 58.dp)
            .background(
                if (checked) colors.primaryContainer.copy(alpha = .55f) else Color.Transparent
            )
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.ws_select_files),
                onLongClick = onToggle,
            )
            .semantics { selected = checked }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked,
                onCheckedChange = { onToggle() },
                modifier =
                    Modifier.size(48.dp)
                        .semantics { contentDescription = entry.name }
                        .testTag("select_$tag"),
            )
        } else {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                RpfIcon(entry.kind.glyph(), Modifier.size(22.dp), tint = entry.kind.tint())
            }
        }
        Column(
            Modifier.weight(1f)
                .padding(
                    top = if (compact) 4.dp else 7.dp,
                    bottom = if (compact) 4.dp else 7.dp,
                    end = 8.dp,
                )
        ) {
            Text(
                entry.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            val metadata =
                if (entry.isDirectory) stringResource(R.string.kind_folder)
                else formatSize(entry.size, locale)
            Text(
                if (parentPath == null) metadata else "$parentPath · $metadata",
                maxLines = if (parentPath == null) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(
        Modifier.padding(start = if (selecting) 48.dp else 40.dp),
        color = colors.outlineVariant.copy(alpha = .3f),
    )
}

@Composable
private fun PaneEmpty(label: Int, modifier: Modifier, action: Int? = null, onAction: () -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RpfIcon(Glyph.Folder, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null)
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(action))
            }
    }
}

@Composable
private fun WorkspaceActions(state: AppState, vm: ArchiveViewModel, compact: Boolean = false) {
    val archive = state.activePane == WorkspacePane.Archive
    val location = state.location
    val editable = location?.session?.info?.editable == true
    val archiveEntries = state.archiveSearchResults ?: location?.entries.orEmpty()
    val storageEntries = state.storageSearchResults ?: state.storage?.entries.orEmpty()
    val selection = if (archive) state.archiveSelection.size else state.storageSelection.size
    val target =
        if (archive) {
            state.storage?.let { storageDisplayPath(it) }
                ?: stringResource(R.string.ws_choose_folder)
        } else location?.let { "${it.session.name}/${it.path}" }.orEmpty()
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (!compact)
                Text(
                    if (selection == 0 && archive && state.query.isNotBlank())
                        stringResource(R.string.ws_archive_search_scope)
                    else if (selection == 0) stringResource(R.string.ws_selection_hint)
                    else if (!archive && location == null)
                        stringResource(R.string.ws_storage_tools_hint)
                    else
                        stringResource(
                            if (archive) R.string.ws_export_target else R.string.ws_import_target,
                            target,
                        ),
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("transfer_target"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (archive && location?.session?.isArchive == true) {
                ArchiveTools(state, vm) {
                    WorkspaceAction(
                        Glyph.Download,
                        if (state.storage == null) R.string.ws_choose_export_folder
                        else R.string.ws_export_to_storage,
                        "batch_export",
                        selection > 0,
                    ) {
                        vm.requestBatchExport()
                    }
                    if (selection > 0 && editable) {
                        WorkspaceAction(
                            Glyph.Trash,
                            R.string.delete_entry,
                            "batch_delete",
                            true,
                            destructive = true,
                        ) {
                            vm.requestBatchDelete()
                        }
                        if (selection == 1) {
                            archiveEntries
                                .find { it.path in state.archiveSelection }
                                ?.let { entry ->
                                    WorkspaceAction(
                                        Glyph.Edit,
                                        R.string.rename,
                                        "rename_selection",
                                        true,
                                    ) {
                                        vm.showEdit("rename", entry)
                                    }
                                    if (!entry.isDirectory) {
                                        WorkspaceAction(
                                            Glyph.File,
                                            R.string.replace_file,
                                            "replace_selection",
                                            true,
                                        ) {
                                            vm.requestImport(entry)
                                        }
                                    }
                                }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (archive) {
                        WorkspaceAction(
                            Glyph.Archive,
                            R.string.ws_open_archive,
                            "open_archive_action",
                            true,
                        ) {
                            vm.requestOpenArchive()
                        }
                    } else {
                        val archiveFile =
                            archiveEntries.singleOrNull { it.path in state.archiveSelection }
                        val storageFile =
                            storageEntries.singleOrNull { it.uri in state.storageSelection }
                        if (
                            state.archiveSelection.size == 1 &&
                                state.storageSelection.size == 1 &&
                                archiveFile?.isDirectory == false &&
                                storageFile?.isDirectory == false
                        ) {
                            WorkspaceAction(
                                Glyph.Back,
                                R.string.ws_replace_selected_file,
                                "targeted_replace",
                                editable,
                            ) {
                                vm.requestTargetedReplace()
                            }
                        }
                        if (location != null) {
                            WorkspaceAction(
                                Glyph.Back,
                                R.string.ws_import_to_archive,
                                "batch_import",
                                editable && selection > 0,
                            ) {
                                vm.requestBatchImport()
                            }
                            WorkspaceAction(
                                Glyph.Reset,
                                R.string.ws_replace_in_archive,
                                "batch_replace",
                                editable && selection > 0,
                            ) {
                                vm.requestBatchImport(replaceOnly = true)
                            }
                        }
                        if (selection > 0) {
                            val selected =
                                storageEntries.filter { it.uri in state.storageSelection }
                            WorkspaceAction(
                                Glyph.Trash,
                                R.string.delete_entry,
                                "batch_delete",
                                selected.any { it.canDelete },
                                destructive = true,
                            ) {
                                vm.requestBatchDelete()
                            }
                            if (selected.size == 1) {
                                val entry = selected.single()
                                WorkspaceAction(
                                    Glyph.Right,
                                    R.string.ws_open_file,
                                    "open_storage_selection",
                                    true,
                                ) {
                                    vm.openStorageEntry(entry)
                                }
                                WorkspaceAction(
                                    Glyph.Edit,
                                    R.string.rename,
                                    "rename_selection",
                                    entry.canRename,
                                ) {
                                    vm.showStorageEdit("rename", entry)
                                }
                            }
                        }
                        WorkspaceAction(
                            Glyph.Plus,
                            R.string.new_folder,
                            "storage_new_folder",
                            state.storage?.directory?.canWrite == true,
                        ) {
                            vm.showStorageEdit("mkdir")
                        }
                        WorkspaceAction(
                            Glyph.Folder,
                            R.string.ws_other_folder,
                            "change_storage",
                            true,
                        ) {
                            vm.requestStorageDirectory()
                        }
                        WorkspaceAction(
                            Glyph.Reset,
                            R.string.ws_refresh,
                            "refresh_storage",
                            state.storage != null,
                        ) {
                            vm.refreshStorage()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceAction(
    glyph: Glyph,
    label: Int,
    tag: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors =
            ButtonDefaults.textButtonColors(
                contentColor =
                    if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
            ),
    ) {
        RpfIcon(glyph, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), maxLines = 1)
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier
            .height(48.dp)
            .background(colors.surface, RoundedCornerShape(8.dp))
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RpfIcon(Glyph.Search, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value,
            onValueChange = onChange,
            enabled = enabled,
            modifier =
                Modifier.weight(1f)
                    .heightIn(min = 48.dp)
                    .padding(vertical = 14.dp)
                    .testTag(tag)
                    .semantics { contentDescription = label },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty())
                        Text(
                            label,
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    inner()
                }
            },
        )
        if (value.isNotEmpty())
            IconAction(
                Glyph.Close,
                stringResource(R.string.clear_search),
                onClick = { onChange("") },
            )
    }
}

private fun AssetFilter.label() =
    when (this) {
        AssetFilter.All -> R.string.filter_all
        AssetFilter.Models -> R.string.models
        AssetFilter.Textures -> R.string.textures
        AssetFilter.Maps -> R.string.maps
        AssetFilter.Collisions -> R.string.collisions
        AssetFilter.Audio -> R.string.audio
    }

private fun SortOrder.label() =
    when (this) {
        SortOrder.Name -> R.string.sort_name
        SortOrder.Size -> R.string.sort_size
        SortOrder.Type -> R.string.sort_type
    }
