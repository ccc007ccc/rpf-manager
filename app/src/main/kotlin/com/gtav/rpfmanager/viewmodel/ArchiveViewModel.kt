package com.gtav.rpfmanager.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.RpfCore
import com.gtav.rpfmanager.data.model.*
import com.gtav.rpfmanager.data.repository.ArchiveOverwrite
import com.gtav.rpfmanager.data.repository.OverwriteNeedsDirectory
import com.gtav.rpfmanager.data.repository.RpfRepository
import com.gtav.rpfmanager.data.repository.WorkspaceFiles
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject

class ArchiveViewModel(app: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(app) {
    private val repository = RpfRepository(app)
    private val workspaceFiles = WorkspaceFiles(app, repository)
    private val archiveOverwrite = ArchiveOverwrite(app, repository)
    private val workspacePrefs =
        app.getSharedPreferences("file_workspace", Application.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(
            AppState(
                settings = repository.settings(),
                recents = repository.recents(),
                fileWorkspaceOpen = hasInternalStorageAccess(),
                activePane =
                    if (hasInternalStorageAccess()) WorkspacePane.Storage else WorkspacePane.Archive,
            )
        )
    val state = mutable.asStateFlow()
    private val history = mutableListOf<BrowserSnapshot>()
    private var operation: Job? = null
    private var operationId = 0
    private var previewJob: Job? = null
    private var pendingExport: ExportFile? = null
    private val exports = Channel<DocumentRequest>(Channel.BUFFERED)
    val exportRequests = exports.receiveAsFlow()
    private val pickers = Channel<PickPurpose>(Channel.BUFFERED)
    val pickerRequests = pickers.receiveAsFlow()
    private val tools = Channel<ToolRequest>(Channel.BUFFERED)
    val toolRequests = tools.receiveAsFlow()
    private var pendingPicker: Triple<PickPurpose, Location, FileEntry?>? = null
    private var pendingSave: Session? = null
    private var afterDiscard: (() -> Unit)? = null
    private var afterSave: (() -> Unit)? = null
    private var audioFile: File? = null
    private var textureSession: Session? = null
    private var pendingToolAction: ToolRequest? = null
    private var pendingToolArchive: Session? = null
    private var pendingPackSource: File? = null
    private val storagePickers = Channel<Unit>(Channel.BUFFERED)
    val storagePickerRequests = storagePickers.receiveAsFlow()
    private var importPlan: ImportPlan? = null
    private var exportPlan: ExportPlan? = null
    private var deleteArchiveEntries: List<FileEntry> = emptyList()
    private var deleteStorageEntries: List<StorageEntry> = emptyList()
    private var batchRunning = false
    private var exportAfterDirectory = false
    private var overwriteAfterDirectory = false
    private var overwriteRunning = false
    private var storageGeneration = 0
    private val internalStoragePickers = Channel<Unit>(Channel.BUFFERED)
    val internalStorageRequests = internalStoragePickers.receiveAsFlow()
    private val archiveOpeners = Channel<Unit>(Channel.BUFFERED)
    val archiveOpenRequests = archiveOpeners.receiveAsFlow()
    private var archiveSearchJob: Job? = null
    private var storageSearchJob: Job? = null
    private var archiveSearchId = 0
    private var storageSearchId = 0

    init {
        viewModelScope.launch {
            val initialStorage =
                if (hasInternalStorageAccess())
                    Uri.fromFile(Environment.getExternalStorageDirectory()).toString()
                else workspacePrefs.getString("tree", null)
            initialStorage?.let { tree ->
                val generation = storageGeneration
                mutable.update { it.copy(storageLoading = true) }
                try {
                    val location = workspaceFiles.root(Uri.parse(tree))
                    if (generation == storageGeneration)
                        mutable.update { it.copy(storage = location) }
                } catch (_: Exception) {
                    // A removed provider or revoked grant returns the pane to its directory picker.
                } finally {
                    mutable.update { it.copy(storageLoading = false) }
                }
            }
        }
        viewModelScope.launch {
            try {
                archiveOverwrite.recover()
                repository.restoreKeys()
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                return@launch
            }
            when (val last = saved.get<String>("source")) {
                "demo" -> openDemo()
                null -> Unit
                else -> openUri(Uri.parse(last))
            }
        }
    }

    private fun work(label: Int, after: (() -> Unit)? = null, block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        val id = ++operationId
        operation =
            viewModelScope.launch {
                var success = false
                mutable.update { it.copy(busy = Busy(label), error = null) }
                state.value.location?.session?.let { RpfCore.resetCancellation(it.handle) }
                try {
                    block()
                    success = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("RpfManager", "Resource operation failed", e)
                    if (id == operationId)
                        mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                } finally {
                    if (
                        label in
                            listOf(
                                R.string.editing_archive,
                                R.string.saving_archive,
                                R.string.importing_file,
                            )
                    ) {
                        withContext(NonCancellable) { runCatching { refreshLocation() } }
                    }
                    if (id == operationId) mutable.update { it.copy(busy = null) }
                }
                if (success && id == operationId && after != null) {
                    operation = null
                    after()
                }
            }
    }

    fun cancelOperation() {
        if (state.value.busy?.cancellable == false) return
        if (overwriteRunning) {
            archiveOverwrite.cancel()
            message(R.string.save_cancelled)
            return
        }
        if (batchRunning) {
            workspaceFiles.cancel()
            return
        }
        repository.cancelTools()
        state.value.location?.session?.let { RpfCore.cancelOperation(it.handle) }
        if (state.value.busy?.label == R.string.scan_running) return
        if (state.value.busy?.label == R.string.saving_archive) message(R.string.save_cancelled)
        operation?.cancel()
    }

    fun openUri(uri: Uri) {
        guardUnsaved { openUriNow(uri) }
    }

    private fun openUriNow(uri: Uri) =
        work(R.string.importing) {
            var session: Session? = null
            var committed = false
            try {
                val (opened, recent) = repository.openUri(uri)
                session = opened
                val location = repository.list(opened)
                currentCoroutineContext().ensureActive()
                replaceWorkspace(location)
                committed = true
                val recents =
                    (listOf(recent) + state.value.recents.filter { it.uri != recent.uri }).take(12)
                repository.saveRecents(recents)
                mutable.update { it.copy(recents = recents) }
                saved["source"] = uri.toString()
                if (!opened.isArchive) select(location.entries.first())
            } finally {
                if (!committed) session?.let(repository::release)
            }
        }

    fun openDemo() {
        guardUnsaved(::openDemoNow)
    }

    private fun openDemoNow() =
        work(R.string.opening_demo) {
            var session: Session? = null
            var committed = false
            try {
                val opened = repository.demo()
                session = opened
                replaceWorkspace(repository.list(opened))
                committed = true
                saved["source"] = "demo"
            } finally {
                if (!committed) session?.let(repository::release)
            }
        }

    private fun replaceWorkspace(location: Location) {
        releaseWorkspace()
        mutable.update {
            it.copy(
                location = location,
                selected = null,
                query = "",
                filter = AssetFilter.All,
                preview = ResourcePreview.Loading,
                showSettings = false,
                workspaceDirty = false,
                coverage = null,
                showCoverage = false,
                archiveSelection = emptySet(),
                archiveSelectionMode = false,
                activePane = WorkspacePane.Archive,
                fileWorkspaceOpen = true,
            )
        }
    }

    private fun releaseWorkspace() {
        resetArchiveSearch()
        previewJob?.cancel()
        releasePreviewResources()
        (history.map { it.location.session } + listOfNotNull(state.value.location?.session))
            .distinctBy { it.handle }
            .forEach(repository::release)
        history.clear()
    }

    fun closeWorkspace() {
        guardUnsaved(::closeNow)
    }

    private fun closeNow() {
        releaseWorkspace()
        saved["source"] = null
        mutable.update {
            it.copy(
                location = null,
                selected = null,
                preview = ResourcePreview.Loading,
                query = "",
                filter = AssetFilter.All,
                workspaceDirty = false,
                coverage = null,
                showCoverage = false,
                editDialog = null,
                fileWorkspaceOpen = false,
                archiveSelection = emptySet(),
                archiveSelectionMode = false,
            )
        }
    }

    fun select(entry: FileEntry) {
        if (state.value.busy != null) return
        if (state.value.archiveSelectionMode) {
            toggleArchiveSelection(entry)
            return
        }
        if (entry.isDirectory || entry.kind == AssetKind.Archive) {
            enter(entry)
            return
        }
        val location = state.value.location ?: return
        previewJob?.cancel()
        releasePreviewResources()
        mutable.update { it.copy(selected = entry, preview = ResourcePreview.Loading) }
        previewJob =
            viewModelScope.launch {
                try {
                    val preview = repository.preview(location.session, entry)
                    mutable.update { it.copy(preview = preview) }
                    if (preview is ResourcePreview.Textures && preview.textures.isNotEmpty())
                        loadTexture(location.session, entry, preview, 0)
                    if (preview is ResourcePreview.Audio && preview.tracks.isNotEmpty()) {
                        val active = preview.tracks.indexOfFirst { it.playable }.coerceAtLeast(0)
                        loadAudio(location.session, entry, preview, active)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(preview = ResourcePreview.Failure(e.message.orEmpty()))
                    }
                }
            }
    }

    private suspend fun loadTexture(
        session: Session,
        entry: FileEntry,
        previous: ResourcePreview.Textures,
        index: Int,
    ) {
        mutable.update {
            it.copy(
                preview =
                    previous.copy(
                        active = index,
                        bitmap = null,
                        png = null,
                        loading = true,
                        diagnostic = null,
                    )
            )
        }
        try {
            val (bitmap, png) = repository.texture(session, entry, previous.textures[index].index)
            mutable.update {
                it.copy(
                    preview =
                        previous.copy(
                            active = index,
                            bitmap = bitmap,
                            png = png,
                            loading = false,
                            diagnostic = null,
                        )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update {
                it.copy(
                    preview =
                        previous.copy(
                            active = index,
                            bitmap = null,
                            png = null,
                            loading = false,
                            diagnostic = e.message,
                        )
                )
            }
        }
    }

    fun chooseTexture(index: Int) {
        val s = state.value
        val texture = s.preview as? ResourcePreview.Textures ?: return
        if (index !in texture.textures.indices) return
        previewJob?.cancel()
        previewJob =
            viewModelScope.launch {
                try {
                    loadTexture(s.location!!.session, s.selected!!, texture, index)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(preview = ResourcePreview.Failure(e.message.orEmpty()))
                    }
                }
            }
    }

    fun retryPreview() {
        state.value.selected?.let(::select)
    }

    private fun enter(entry: FileEntry) =
        work(R.string.loading) {
            val old = state.value
            val location = old.location ?: return@work
            var child: Session? = null
            try {
                val newLocation =
                    if (entry.isDirectory) repository.list(location.session, entry.path)
                    else {
                        check(history.map { it.location.session.handle }.distinct().size < 24) {
                            "Nested archive depth limit reached"
                        }
                        val session = repository.nested(location.session, entry)
                        child = session
                        repository.list(session)
                    }
                previewJob?.cancel()
                releasePreviewResources()
                if (newLocation.session.handle != location.session.handle)
                    history += BrowserSnapshot(location, old.query, old.filter)
                resetArchiveSearch()
                mutable.update {
                    it.copy(
                        location = newLocation,
                        selected = null,
                        query = "",
                        filter = AssetFilter.All,
                        preview = ResourcePreview.Loading,
                        archiveSelection = emptySet(),
                        archiveSelectionMode = false,
                    )
                }
                child = null
            } finally {
                child?.let(repository::release)
            }
        }

    fun navigateTo(path: String) =
        work(R.string.loading) {
            val old = state.value
            val location = old.location ?: return@work
            if (path == location.path) return@work
            val next = repository.list(location.session, path)
            resetArchiveSearch()
            previewJob?.cancel()
            mutable.update {
                it.copy(
                    location = next,
                    query = "",
                    filter = AssetFilter.All,
                    selected = null,
                    preview = ResourcePreview.Loading,
                    archiveSelection = emptySet(),
                    archiveSelectionMode = false,
                )
            }
        }

    fun back() {
        val s = state.value
        when {
            s.busy != null -> cancelOperation()
            s.batchConfirmation != null -> dismissBatch()
            s.batchResult != null -> dismissBatchResult()
            s.overwriteTarget != null -> dismissOverwriteSave()
            s.overwriteDirectoryTarget != null -> dismissOverwriteDirectory()
            s.searchIssuesVisible -> dismissSearchSkipped()
            s.storageEdit != null -> dismissStorageEdit()
            s.editDialog != null -> dismissEdit()
            s.showCoverage -> mutable.update { it.copy(showCoverage = false) }
            s.showSettings -> showSettings(false)
            s.selected != null -> {
                previewJob?.cancel()
                releasePreviewResources()
                if (s.location?.session?.isArchive == false) {
                    closeWorkspace()
                    if (s.storage != null)
                        mutable.update {
                            it.copy(fileWorkspaceOpen = true, activePane = WorkspacePane.Storage)
                        }
                } else
                    mutable.update { it.copy(selected = null, preview = ResourcePreview.Loading) }
            }
            !s.fileWorkspaceOpen && s.location != null -> showWorkspace()
            s.activePane == WorkspacePane.Storage && s.storageSelectionMode ->
                clearStorageSelection()
            s.activePane == WorkspacePane.Archive && s.archiveSelectionMode ->
                clearArchiveSelection()
            s.activePane == WorkspacePane.Storage && s.storageQuery.isNotEmpty() -> storageQuery("")
            s.activePane == WorkspacePane.Archive && s.query.isNotEmpty() -> query("")
            s.activePane == WorkspacePane.Storage && (s.storage?.breadcrumbs?.size ?: 0) > 1 ->
                storageUp()
            s.activePane == WorkspacePane.Storage ->
                if (s.location == null) showLibrary() else activatePane(WorkspacePane.Archive)
            s.location != null &&
                (s.location.path.isNotEmpty() || s.location.session.parent != null) -> archiveUp()
            s.location != null -> closeWorkspace()
            s.fileWorkspaceOpen -> showLibrary()
        }
    }

    fun query(value: String) {
        archiveSearchJob?.cancel()
        val id = ++archiveSearchId
        val location = state.value.location
        val searching = value.isNotBlank() && location?.session?.isArchive == true
        mutable.update {
            it.copy(
                query = value,
                archiveSelection = emptySet(),
                archiveSearchResults = if (searching) emptyList() else null,
                archiveSearching = searching,
            )
        }
        if (!searching) return
        archiveSearchJob =
            viewModelScope.launch {
                try {
                    delay(220)
                    val results =
                        workspaceFiles.searchArchive(location.session, location.path, value.trim())
                    if (id == archiveSearchId)
                        mutable.update { it.copy(archiveSearchResults = results) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (id == archiveSearchId) mutable.update { it.copy(error = e.message) }
                } finally {
                    if (id == archiveSearchId) mutable.update { it.copy(archiveSearching = false) }
                }
            }
    }

    private fun resetArchiveSearch() {
        archiveSearchJob?.cancel()
        archiveSearchId++
        mutable.update { it.copy(archiveSearchResults = null, archiveSearching = false) }
    }

    private fun resetStorageSearch() {
        storageSearchJob?.cancel()
        storageSearchId++
        mutable.update {
            it.copy(
                storageSearchResults = null,
                storageSearching = false,
                storageSearchSkipped = emptyList(),
            )
        }
    }

    fun filter(value: AssetFilter) {
        mutable.update { it.copy(filter = value, archiveSelection = emptySet()) }
    }

    fun sort(value: SortOrder) {
        mutable.update { it.copy(sort = value) }
    }

    fun toggleGrid() {
        mutable.update { it.copy(grid = !it.grid) }
    }

    fun showSettings(show: Boolean) {
        mutable.update { it.copy(showSettings = show) }
    }

    fun language(value: String) {
        updateSettings(state.value.settings.copy(language = value))
    }

    fun appearance(value: String) {
        updateSettings(state.value.settings.copy(appearance = value))
    }

    private fun updateSettings(settings: Settings) {
        repository.saveSettings(settings)
        mutable.update { it.copy(settings = settings) }
    }

    fun removeRecent(uri: String) {
        val recents = state.value.recents.filter { it.uri != uri }
        repository.saveRecents(recents)
        mutable.update { it.copy(recents = recents) }
    }

    fun clearRecents() {
        repository.saveRecents(emptyList())
        mutable.update { it.copy(recents = emptyList()) }
    }

    fun importKeys(uri: Uri) =
        work(R.string.importing_keys) {
            repository.importKeys(uri)
            updateSettings(state.value.settings.copy(hasKeys = true))
            message(R.string.keys_imported)
        }

    fun clearKeys() =
        work(R.string.loading) {
            repository.clearKeys()
            updateSettings(state.value.settings.copy(hasKeys = false))
        }

    fun clearCache() =
        work(R.string.loading) {
            val active =
                (history.mapNotNull { it.location.session.file } +
                        listOfNotNull(state.value.location?.session?.file, pendingExport?.file))
                    .plus(listOfNotNull(audioFile))
                    .toSet()
            repository.clearCache(active)
            message(R.string.cache_cleared)
        }

    fun startExtractArchive() {
        if (state.value.busy != null) return
        pendingToolAction = ToolRequest.ExtractInput
        viewModelScope.launch { tools.send(ToolRequest.ExtractInput) }
    }

    fun startPackDirectory() {
        if (state.value.busy != null) return
        pendingToolAction = ToolRequest.PackInput
        viewModelScope.launch { tools.send(ToolRequest.PackInput) }
    }

    fun toolArchiveSelected(uri: Uri?) {
        if (pendingToolAction != ToolRequest.ExtractInput) return
        if (uri == null) {
            pendingToolAction = null
            return
        }
        pendingToolAction = ToolRequest.ExtractOutput
        work(R.string.preparing_extract) {
            val (session, _) = repository.openUri(uri)
            if (!session.isArchive) {
                repository.release(session)
                error("Select an RPF archive")
            }
            pendingToolArchive?.let(repository::release)
            pendingToolArchive = session
            tools.send(ToolRequest.ExtractOutput)
        }
    }

    fun toolDirectorySelected(uri: Uri?) {
        val action = pendingToolAction ?: return
        pendingToolAction = null
        if (uri == null) {
            pendingToolArchive?.let(repository::release)
            pendingToolArchive = null
            return
        }
        when (action) {
            ToolRequest.ExtractOutput -> {
                val session = pendingToolArchive
                pendingToolArchive = null
                if (session == null) return
                work(R.string.extracting_archive) {
                    try {
                        val count = repository.extractArchive(session, uri)
                        message(R.string.extract_complete)
                        if (count >= 0) Unit
                    } finally {
                        repository.release(session)
                    }
                }
            }
            ToolRequest.PackInput -> {
                work(R.string.preparing_pack) {
                    pendingPackSource?.let(repository::forgetFile)
                    pendingPackSource = repository.stageDirectory(uri)
                    tools.send(ToolRequest.PackOutput)
                }
            }
            else -> Unit
        }
    }

    fun completeToolOutput(uri: Uri?) {
        val source = pendingPackSource
        pendingPackSource = null
        if (source == null) return
        if (uri == null) {
            repository.forgetFile(source)
            return
        }
        work(R.string.packing_archive) {
            try {
                repository.packDirectory(source, uri)
                message(R.string.pack_complete)
            } finally {
                repository.forgetFile(source)
            }
        }
    }

    fun prepareExport(asPng: Boolean = false) =
        work(R.string.preparing_export) {
            val s = state.value
            val session = s.location?.session ?: return@work
            val entry = s.selected ?: return@work
            val texture = s.preview as? ResourcePreview.Textures
            val png = if (asPng) requireNotNull(texture?.png) else null
            pendingExport?.file?.delete()
            val export =
                repository.prepareExport(
                    session,
                    entry,
                    png,
                    texture?.textures?.get(texture.active)?.name,
                )
            pendingExport = export
            exports.send(DocumentRequest(export.name, export.mime))
        }

    fun completeExport(uri: Uri?) {
        pendingSave?.let { session ->
            pendingSave = null
            val next = afterSave
            afterSave = null
            if (uri != null)
                work(R.string.saving_archive, next) {
                    var current = state.value.location?.session ?: return@work
                    while (current.parent != null) current = requireNotNull(current.parent)
                    check(current.handle == session.handle) {
                        "The workspace changed while choosing the save destination"
                    }
                    val prepared = prepareSave()
                    repository.save(prepared, uri)
                    refreshLocation()
                    message(R.string.archive_saved_as)
                }
            return
        }
        val source = pendingExport ?: return
        pendingExport = null
        if (uri == null) {
            source.file.delete()
            return
        }
        work(R.string.exporting) {
            try {
                repository.export(source, uri, state.value.location?.session?.handle ?: 0L)
                message(R.string.export_complete)
            } finally {
                source.file.delete()
            }
        }
    }

    private fun guardUnsaved(action: () -> Unit) {
        if (state.value.busy != null) return
        if (state.value.workspaceDirty) {
            afterDiscard = action
            mutable.update { it.copy(confirmDiscard = true) }
        } else action()
    }

    fun keepWorkspace() {
        afterDiscard = null
        mutable.update {
            it.copy(
                confirmDiscard = false,
                fileWorkspaceOpen = it.location != null || it.fileWorkspaceOpen,
            )
        }
    }

    fun discardWorkspace() {
        val action = afterDiscard
        afterDiscard = null
        mutable.update { it.copy(confirmDiscard = false) }
        action?.invoke()
    }

    fun saveBeforeLeaving() {
        afterSave = afterDiscard
        afterDiscard = null
        mutable.update { it.copy(confirmDiscard = false) }
        saveWorkspaceAs()
    }

    private suspend fun listExisting(session: Session, wanted: String): Location {
        var path = wanted
        while (true) {
            try {
                return repository.list(session, path)
            } catch (e: Exception) {
                if (
                    e is CancellationException ||
                        path.isEmpty() ||
                        e.message?.contains("Directory not found") != true
                )
                    throw e
                path = path.substringBeforeLast('/', "")
            }
        }
    }

    private suspend fun updateDirty() {
        val sessions =
            (history.map { it.location.session } + listOfNotNull(state.value.location?.session))
                .distinctBy { it.handle }
        val dirty = sessions.any { repository.info(it)?.dirty == true }
        mutable.update { it.copy(workspaceDirty = dirty) }
    }

    private suspend fun refreshLocation() {
        val location = state.value.location ?: return
        val fresh = listExisting(location.session, location.path)
        val previousSelection = state.value.selected
        val selected =
            previousSelection?.let {
                val parent = it.path.substringBeforeLast('/', "")
                val entries =
                    if (parent == fresh.path) fresh.entries
                    else listExisting(location.session, parent).entries
                entries.find { entry -> entry.path == it.path }
            }
        mutable.update { old ->
            old.copy(
                location = fresh,
                selected = selected,
                archiveSelection =
                    old.archiveSelection.intersect(fresh.entries.map { it.path }.toSet()),
            )
        }
        updateDirty()
        if (state.value.query.isNotBlank()) query(state.value.query)
    }

    private fun releasePreviewResources() {
        audioFile?.let(repository::forgetFile)
        audioFile = null
        textureSession?.let(repository::release)
        textureSession = null
    }

    fun showEdit(operation: String, entry: FileEntry? = null) {
        val location = state.value.location ?: return
        if (location.session.info?.editable != true || state.value.busy != null) return
        if (operation == "text" && entry != null)
            work(R.string.loading) {
                val content = repository.readText(location.session, entry)
                mutable.update { it.copy(editDialog = EditDialog(operation, entry, content)) }
            }
        else
            mutable.update {
                it.copy(
                    editDialog =
                        EditDialog(
                            operation,
                            entry,
                            if (operation == "rename") entry?.name.orEmpty() else "",
                        )
                )
            }
    }

    fun dismissEdit() {
        mutable.update { it.copy(editDialog = null) }
    }

    fun updateDraft(text: String) {
        mutable.update { it.copy(editDialog = it.editDialog?.copy(text = text)) }
    }

    fun submitEdit(value: String = "") =
        work(R.string.editing_archive) {
            val s = state.value
            val dialog = s.editDialog ?: return@work
            val location = s.location ?: return@work
            when (dialog.operation) {
                "mkdir" ->
                    repository.edit(
                        location.session,
                        "mkdir",
                        listOf(location.path, value).filter { it.isNotEmpty() }.joinToString("/"),
                    )
                "rename" ->
                    repository.edit(
                        location.session,
                        "rename",
                        requireNotNull(dialog.entry).path,
                        value,
                    )
                "delete" ->
                    repository.edit(location.session, "delete", requireNotNull(dialog.entry).path)
                "text" -> repository.editText(location.session, requireNotNull(dialog.entry), value)
            }
            mutable.update { it.copy(editDialog = null, coverage = null) }
            refreshLocation()
            if (dialog.operation == "text") reloadAfterEdit()
        }

    private fun reloadAfterEdit() {
        // work() still owns the modal operation; reload once it finishes.
        val current = operation ?: return
        viewModelScope.launch {
            current.join()
            state.value.selected?.let(::select)
        }
    }

    fun undo() = editHistory("undo")

    fun redo() = editHistory("redo")

    private fun editHistory(operation: String) =
        work(R.string.editing_archive) {
            val session = state.value.location?.session ?: return@work
            repository.edit(session, operation)
            mutable.update { it.copy(coverage = null) }
            refreshLocation()
            reloadAfterEdit()
        }

    fun requestImport(replace: FileEntry? = null) {
        val location = state.value.location ?: return
        if (location.session.info?.editable != true) return
        val purpose = if (replace == null) PickPurpose.Add else PickPurpose.Replace
        pendingPicker = Triple(purpose, location, replace)
        viewModelScope.launch { pickers.send(purpose) }
    }

    fun requestTextureDictionary() {
        val s = state.value
        val location = s.location ?: return
        pendingPicker = Triple(PickPurpose.Texture, location, s.selected)
        viewModelScope.launch { pickers.send(PickPurpose.Texture) }
    }

    fun pickedDocument(uri: Uri?) {
        val (purpose, location, entry) = pendingPicker ?: return
        pendingPicker = null
        if (uri == null || state.value.location?.session?.handle != location.session.handle) return
        if (purpose == PickPurpose.Texture) {
            if (state.value.selected?.path != entry?.path) return
            work(R.string.loading) {
                var owned: Session? = null
                try {
                    val (session, _) = repository.openUri(uri)
                    owned = session
                    require(session.name.endsWith(".ytd", true)) {
                        "Select a YTD texture dictionary"
                    }
                    val source = TextureSource(session.handle, session.name, session.name)
                    val textures = repository.textureList(source)
                    val mesh = state.value.preview as? ResourcePreview.Mesh ?: return@work
                    val oldHandle = textureSession?.handle
                    textureSession?.let(repository::release)
                    textureSession = session
                    owned = null
                    mutable.update {
                        it.copy(
                            preview =
                                mesh.copy(
                                    sources =
                                        mesh.sources.filter { source ->
                                            source.handle != oldHandle
                                        } + source,
                                    source = source,
                                    textures = textures,
                                    active = -1,
                                    texture = null,
                                    textureError = null,
                                )
                        )
                    }
                } finally {
                    owned?.let(repository::release)
                }
            }
        } else
            work(R.string.importing_file) {
                repository.importFile(location.session, location.path, uri, entry)
                mutable.update { it.copy(coverage = null) }
                refreshLocation()
                if (entry != null) reloadAfterEdit()
            }
    }

    private suspend fun prepareSave(): Session {
        val original = state.value
        resetArchiveSearch()
        var session = state.value.location?.session ?: throw IllegalStateException("No session")
        previewJob?.cancel()
        releasePreviewResources()
        // Fold child snapshots into their parents before writing the outer RPF.
        while (session.parent != null) {
            repository.attach(session)
            val parent = requireNotNull(session.parent)
            val location =
                listExisting(parent, session.parentEntry.orEmpty().substringBeforeLast('/', ""))
            val childHandle = session.handle
            history.removeAll { it.location.session.handle == childHandle }
            mutable.update {
                it.copy(location = location, selected = null, preview = ResourcePreview.Loading)
            }
            repository.release(session)
            session = parent
        }
        history.clear()
        val location = listExisting(session, state.value.location?.path.orEmpty())
        mutable.update {
            it.copy(
                location = location,
                selected = null,
                query =
                    if (original.location?.session?.handle == session.handle) original.query
                    else "",
                filter =
                    if (original.location?.session?.handle == session.handle) original.filter
                    else AssetFilter.All,
                preview = ResourcePreview.Loading,
                showCoverage = false,
                archiveSelection = emptySet(),
                archiveSelectionMode = false,
            )
        }
        return session
    }

    fun saveWorkspace() {
        var root = state.value.location?.session ?: return
        while (root.parent != null) root = requireNotNull(root.parent)
        if (root.sourceUri == null || root.demo) {
            saveWorkspaceAs()
            return
        }
        val original = root
        work(R.string.saving_archive) {
            var destination = state.value.storage
            try {
                if (Uri.parse(original.sourceUri).scheme == "file") {
                    archiveOverwrite.requireDestination(original, null)
                    destination = null
                } else {
                    val current = destination
                    if (current == null) throw OverwriteNeedsDirectory(original.name)
                    var found = false
                    for (index in current.breadcrumbs.indices.reversed()) {
                        val candidate =
                            current.copy(
                                directory = current.breadcrumbs[index],
                                breadcrumbs = current.breadcrumbs.take(index + 1),
                            )
                        try {
                            archiveOverwrite.requireDestination(original, candidate)
                            destination = candidate
                            found = true
                            break
                        } catch (_: OverwriteNeedsDirectory) {
                            /* Try an already granted ancestor. */
                        }
                    }
                    if (!found) throw OverwriteNeedsDirectory(original.name)
                }
            } catch (need: OverwriteNeedsDirectory) {
                mutable.update { it.copy(overwriteDirectoryTarget = need.archiveName) }
                return@work
            }
            val prepared = prepareSave()
            overwriteRunning = true
            var owned: Session? = null
            try {
                val (reopened, recent) =
                    archiveOverwrite.overwrite(prepared, destination) {
                        mutable.update {
                            it.copy(busy = Busy(R.string.workspace_publishing, cancellable = false))
                        }
                    }
                owned = reopened
                val previous = state.value
                val location = listExisting(reopened, previous.location?.path.orEmpty())
                replaceWorkspace(location)
                mutable.update { it.copy(query = previous.query, filter = previous.filter) }
                owned = null
                saved["source"] = recent.uri
                val recents =
                    (listOf(recent) +
                            state.value.recents.filter {
                                it.uri != original.sourceUri && it.uri != recent.uri
                            })
                        .take(12)
                repository.saveRecents(recents)
                mutable.update { it.copy(recents = recents) }
                runCatching { refreshStorageLocation() }
                message(R.string.archive_saved)
            } finally {
                owned?.let(repository::release)
                overwriteRunning = false
            }
        }
    }

    fun saveWorkspaceAs() =
        work(R.string.preparing_save) {
            var session = state.value.location?.session ?: return@work
            while (session.parent != null) session = requireNotNull(session.parent)
            pendingSave = session
            val name = session.name.substringBeforeLast('.', session.name) + ".edited.rpf"
            exports.send(DocumentRequest(name, "application/x-rpf"))
        }

    fun chooseModelSource(source: TextureSource) {
        val mesh = state.value.preview as? ResourcePreview.Mesh ?: return
        previewJob?.cancel()
        mutable.update {
            it.copy(
                preview =
                    mesh.copy(
                        source = source,
                        textureLoading = true,
                        texture = null,
                        active = -1,
                        textureError = null,
                    )
            )
        }
        previewJob =
            viewModelScope.launch {
                try {
                    val textures = repository.textureList(source)
                    mutable.update {
                        it.copy(
                            preview =
                                mesh.copy(
                                    source = source,
                                    textures = textures,
                                    active = -1,
                                    texture = null,
                                    textureError = null,
                                )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it.copy(preview = mesh.copy(textureError = e.message)) }
                }
            }
    }

    fun chooseModelTexture(index: Int) {
        val mesh = state.value.preview as? ResourcePreview.Mesh ?: return
        previewJob?.cancel()
        if (index < 0) {
            mutable.update {
                it.copy(
                    preview =
                        mesh.copy(
                            active = -1,
                            texture = null,
                            textureLoading = false,
                            textureError = null,
                        )
                )
            }
            return
        }
        if (index !in mesh.textures.indices || !mesh.data.hasUv) return
        val source = mesh.source ?: return
        mutable.update {
            it.copy(preview = mesh.copy(active = index, textureLoading = true, textureError = null))
        }
        previewJob =
            viewModelScope.launch {
                try {
                    val texture = repository.modelTexture(source, mesh.textures[index].index)
                    mutable.update {
                        it.copy(
                            preview =
                                mesh.copy(active = index, texture = texture, textureError = null)
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it.copy(preview = mesh.copy(textureError = e.message)) }
                }
            }
    }

    private suspend fun loadAudio(
        session: Session,
        entry: FileEntry,
        audio: ResourcePreview.Audio,
        index: Int,
    ) {
        audioFile?.let(repository::forgetFile)
        audioFile = null
        val track = audio.tracks[index]
        mutable.update {
            it.copy(
                preview =
                    audio.copy(
                        active = index,
                        file = null,
                        loading = track.playable,
                        diagnostic = null,
                    )
            )
        }
        if (!track.playable) return
        try {
            val file = repository.audio(session, entry, track.index)
            audioFile = file
            mutable.update {
                it.copy(
                    preview =
                        audio.copy(active = index, file = file, loading = false, diagnostic = null)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update {
                it.copy(
                    preview =
                        audio.copy(
                            active = index,
                            file = null,
                            loading = false,
                            diagnostic = e.message,
                        )
                )
            }
        }
    }

    fun chooseAudio(index: Int) {
        val s = state.value
        val audio = s.preview as? ResourcePreview.Audio ?: return
        if (index !in audio.tracks.indices) return
        previewJob?.cancel()
        previewJob =
            viewModelScope.launch { loadAudio(s.location!!.session, s.selected!!, audio, index) }
    }

    fun exportWav() =
        work(R.string.preparing_export) {
            val audio = state.value.preview as? ResourcePreview.Audio ?: return@work
            val file = audio.file ?: return@work
            val track = audio.tracks[audio.active]
            val name = state.value.selected!!.name.substringBeforeLast('.') + "_${track.id}.wav"
            queueExport(repository.copyExport(file, name, "audio/wav"))
        }

    private suspend fun queueExport(export: ExportFile) {
        pendingExport?.file?.let(repository::forgetFile)
        pendingExport = export
        exports.send(DocumentRequest(export.name, export.mime))
    }

    fun scanArchive() =
        work(R.string.scan_running) {
            val session = state.value.location?.session ?: return@work
            if (!session.isArchive) return@work
            coroutineScope {
                val progress = launch {
                    while (isActive) {
                        delay(250)
                        val p = JSONObject(RpfCore.scanProgress(session.handle))
                        mutable.update {
                            it.copy(
                                busy =
                                    Busy(
                                        R.string.scan_running,
                                        visited = p.optInt("visited"),
                                        detail = p.optString("path"),
                                    )
                            )
                        }
                    }
                }
                try {
                    val report = repository.scan(session)
                    mutable.update { it.copy(coverage = report, showCoverage = true) }
                } finally {
                    progress.cancelAndJoin()
                }
            }
        }

    fun showCoverage() {
        if (state.value.coverage != null) mutable.update { it.copy(showCoverage = true) }
        else scanArchive()
    }

    fun exportCoverage() =
        work(R.string.preparing_export) {
            val report = state.value.coverage ?: return@work
            queueExport(
                repository.exportBytes(
                    report.json.toByteArray(Charsets.UTF_8),
                    report.name + ".coverage.json",
                    "application/json",
                )
            )
        }

    fun activatePane(pane: WorkspacePane) {
        mutable.update { it.copy(activePane = pane) }
    }

    fun toggleDualPane() {
        mutable.update { it.copy(dualPane = !it.dualPane) }
    }

    fun startArchiveSelection() {
        mutable.update { it.copy(activePane = WorkspacePane.Archive, archiveSelectionMode = true) }
    }

    fun toggleArchiveSelection(entry: FileEntry) {
        mutable.update {
            it.copy(
                activePane = WorkspacePane.Archive,
                archiveSelectionMode = true,
                archiveSelection =
                    if (entry.path in it.archiveSelection) it.archiveSelection - entry.path
                    else it.archiveSelection + entry.path,
            )
        }
    }

    fun selectAllArchive() {
        mutable.update {
            it.copy(
                archiveSelectionMode = true,
                archiveSelection = it.visibleEntries.map { entry -> entry.path }.toSet(),
            )
        }
    }

    fun invertArchiveSelection() {
        mutable.update {
            it.copy(
                archiveSelectionMode = true,
                archiveSelection =
                    it.visibleEntries.map { entry -> entry.path }.toSet() - it.archiveSelection,
            )
        }
    }

    fun clearArchiveSelection() {
        mutable.update { it.copy(archiveSelection = emptySet(), archiveSelectionMode = false) }
    }

    fun startStorageSelection() {
        mutable.update { it.copy(activePane = WorkspacePane.Storage, storageSelectionMode = true) }
    }

    fun toggleStorageSelection(entry: StorageEntry) {
        mutable.update {
            it.copy(
                activePane = WorkspacePane.Storage,
                storageSelectionMode = true,
                storageSelection =
                    if (entry.uri in it.storageSelection) it.storageSelection - entry.uri
                    else it.storageSelection + entry.uri,
            )
        }
    }

    fun selectAllStorage() {
        mutable.update {
            it.copy(
                storageSelectionMode = true,
                storageSelection = it.visibleStorageEntries.map { entry -> entry.uri }.toSet(),
            )
        }
    }

    fun invertStorageSelection() {
        mutable.update {
            it.copy(
                storageSelectionMode = true,
                storageSelection =
                    it.visibleStorageEntries.map { entry -> entry.uri }.toSet() -
                        it.storageSelection,
            )
        }
    }

    fun clearStorageSelection() {
        mutable.update { it.copy(storageSelection = emptySet(), storageSelectionMode = false) }
    }

    fun storageQuery(value: String) {
        storageSearchJob?.cancel()
        val id = ++storageSearchId
        val location = state.value.storage
        val searching = value.isNotBlank() && location != null
        mutable.update {
            it.copy(
                storageQuery = value,
                storageSelection = emptySet(),
                storageSearchResults = if (searching) emptyList() else null,
                storageSearching = searching,
                storageSearchSkipped = emptyList(),
            )
        }
        if (!searching) return
        storageSearchJob =
            viewModelScope.launch {
                try {
                    delay(220)
                    val results =
                        workspaceFiles.searchStorage(location, value.trim()) { skipped ->
                            if (id == storageSearchId)
                                mutable.update { it.copy(storageSearchSkipped = skipped) }
                        }
                    if (id == storageSearchId)
                        mutable.update { it.copy(storageSearchResults = results) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (id == storageSearchId) mutable.update { it.copy(error = e.message) }
                } finally {
                    if (id == storageSearchId) mutable.update { it.copy(storageSearching = false) }
                }
            }
    }

    fun showSearchSkipped() {
        mutable.update { it.copy(searchIssuesVisible = true) }
    }

    fun dismissSearchSkipped() {
        mutable.update { it.copy(searchIssuesVisible = false) }
    }

    private fun hasInternalStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

    fun requestInternalStorage() {
        if (hasInternalStorageAccess()) openInternalStorage()
        else viewModelScope.launch { internalStoragePickers.send(Unit) }
    }

    fun internalStorageAccessResult() {
        if (hasInternalStorageAccess()) openInternalStorage()
        else message(R.string.internal_storage_not_granted)
    }

    fun openInternalStorage() {
        if (!hasInternalStorageAccess()) {
            requestInternalStorage()
            return
        }
        storageDirectorySelected(Uri.fromFile(Environment.getExternalStorageDirectory()))
    }

    fun browseInternalStorage() {
        mutable.update { it.copy(fileWorkspaceOpen = true, activePane = WorkspacePane.Storage) }
        requestInternalStorage()
    }

    fun requestOpenArchive() {
        viewModelScope.launch { archiveOpeners.send(Unit) }
    }

    fun showWorkspace() {
        mutable.update { it.copy(fileWorkspaceOpen = true) }
    }

    fun showLibrary() {
        previewJob?.cancel()
        releasePreviewResources()
        mutable.update { it.copy(fileWorkspaceOpen = false, selected = null) }
    }

    fun storageSort(value: SortOrder) {
        mutable.update { it.copy(storageSort = value) }
    }

    fun requestStorageDirectory() {
        if (state.value.busy == null) viewModelScope.launch { storagePickers.send(Unit) }
    }

    fun storageDirectorySelected(uri: Uri?) {
        val export = exportAfterDirectory
        val overwrite = overwriteAfterDirectory
        exportAfterDirectory = false
        overwriteAfterDirectory = false
        if (uri == null) return
        storageGeneration++
        val next: (() -> Unit)? =
            when {
                overwrite -> ({ saveWorkspace() })
                export -> ({ requestBatchExport() })
                else -> null
            }
        work(R.string.loading, after = next) {
            val location = workspaceFiles.root(uri)
            workspacePrefs.edit().putString("tree", uri.toString()).apply()
            setStorageLocation(location)
            if (!export && !overwrite) activatePane(WorkspacePane.Storage)
        }
    }

    private fun setStorageLocation(location: StorageLocation) {
        resetStorageSearch()
        mutable.update {
            it.copy(
                storage = location,
                storageQuery = "",
                storageSelection = emptySet(),
                storageSelectionMode = false,
            )
        }
    }

    fun refreshStorage() = work(R.string.loading) { refreshStorageLocation() }

    private suspend fun refreshStorageLocation() {
        val previous = state.value.storage ?: return
        val location = workspaceFiles.list(previous)
        mutable.update {
            it.copy(
                storage = location,
                storageSelection =
                    it.storageSelection.intersect(
                        location.entries.map { entry -> entry.uri }.toSet()
                    ),
            )
        }
        if (state.value.storageQuery.isNotBlank()) storageQuery(state.value.storageQuery)
    }

    fun openStorageEntry(entry: StorageEntry) {
        if (entry.isDirectory)
            work(R.string.loading) {
                val location = state.value.storage ?: return@work
                setStorageLocation(workspaceFiles.enter(location, entry))
            }
        else openUri(Uri.parse(entry.uri))
    }

    fun storageUp() =
        work(R.string.loading) {
            state.value.storage?.let { setStorageLocation(workspaceFiles.up(it)) }
        }

    fun storageNavigateTo(index: Int) =
        work(R.string.loading) {
            val location = state.value.storage ?: return@work
            val directory = location.breadcrumbs.getOrNull(index) ?: return@work
            setStorageLocation(
                workspaceFiles.list(
                    location.copy(
                        directory = directory,
                        breadcrumbs = location.breadcrumbs.take(index + 1),
                    )
                )
            )
        }

    fun archiveUp() {
        val location = state.value.location ?: return
        if (location.path.isNotEmpty()) navigateTo(location.path.substringBeforeLast('/', ""))
        else if (location.session.parent != null)
            work(R.string.loading) {
                val child = location.session
                repository.attach(child)
                val parent = requireNotNull(child.parent)
                resetArchiveSearch()
                val snapshot = history.lastOrNull { it.location.session.handle == parent.handle }
                val previous =
                    listExisting(
                        parent,
                        snapshot?.location?.path
                            ?: child.parentEntry.orEmpty().substringBeforeLast('/', ""),
                    )
                history.removeAll {
                    it.location.session.handle == child.handle ||
                        it.location.session.handle == parent.handle
                }
                repository.release(child)
                mutable.update {
                    it.copy(
                        location = previous,
                        selected = null,
                        query = snapshot?.query.orEmpty(),
                        filter = snapshot?.filter ?: AssetFilter.All,
                        archiveSelection = emptySet(),
                        archiveSelectionMode = false,
                    )
                }
                updateDirty()
                if (state.value.query.isNotBlank()) query(state.value.query)
            }
    }

    fun showStorageEdit(operation: String, entry: StorageEntry? = null) {
        if (state.value.busy != null) return
        mutable.update {
            it.copy(
                storageEdit =
                    StorageEdit(
                        operation,
                        entry,
                        if (operation == "rename") entry?.name.orEmpty() else "",
                    )
            )
        }
    }

    fun updateStorageDraft(value: String) {
        mutable.update { it.copy(storageEdit = it.storageEdit?.copy(text = value)) }
    }

    fun dismissStorageEdit() {
        mutable.update { it.copy(storageEdit = null) }
    }

    fun submitStorageEdit() =
        work(R.string.workspace_working) {
            val s = state.value
            val edit = s.storageEdit ?: return@work
            val location = s.storage ?: return@work
            when (edit.operation) {
                "mkdir" -> workspaceFiles.mkdir(location, edit.text)
                "rename" -> {
                    val entry = requireNotNull(edit.entry)
                    workspaceFiles.rename(
                        workspaceFiles.locateStorage(location, entry),
                        entry,
                        edit.text,
                    )
                }
            }
            mutable.update {
                it.copy(
                    storageEdit = null,
                    storageSelection = emptySet(),
                    storageSelectionMode = false,
                )
            }
            refreshStorageLocation()
        }

    private fun archivePath(): String =
        state.value.location?.let { it.session.name + "/" + it.path }.orEmpty()

    private fun storagePath(): String =
        state.value.storage
            ?.let {
                if (Uri.parse(it.directory.uri).scheme == "file")
                    Uri.parse(it.directory.uri).path.orEmpty()
                else it.breadcrumbs.joinToString("/") { entry -> entry.name }
            }
            .orEmpty()

    fun requestBatchImport(replaceOnly: Boolean = false) =
        work(R.string.workspace_preparing) {
            val s = state.value
            val location = s.location ?: return@work
            if (location.session.info?.editable != true) return@work
            val entries =
                (s.storageSearchResults ?: s.storage?.entries.orEmpty()).filter {
                    it.uri in s.storageSelection
                }
            if (entries.isEmpty()) return@work
            prepareImport(location, entries, replaceOnly, storagePath())
        }

    private suspend fun prepareImport(
        location: Location,
        entries: List<StorageEntry>,
        replaceOnly: Boolean,
        source: String,
    ) {
        val plan = workspaceFiles.planImport(location.session, location.path, entries, replaceOnly)
        importPlan = plan
        mutable.update {
            it.copy(
                batchConfirmation =
                    BatchConfirmation(
                        if (replaceOnly) BatchKind.Replace else BatchKind.Import,
                        source,
                        archivePath(),
                        plan.total,
                        plan.conflicts,
                        plan.newItems,
                        plan.skipped,
                        plan.items.map { item -> item.path },
                        plan.items.mapNotNull { item ->
                            item.issue?.let { BatchFailure(item.path, it) }
                        },
                    )
            )
        }
    }

    fun requestTargetedReplace() =
        work(R.string.workspace_preparing) {
            val s = state.value
            val location = s.location ?: return@work
            val target =
                (s.archiveSearchResults ?: location.entries)
                    .filter { it.path in s.archiveSelection }
                    .singleOrNull() ?: return@work
            val source =
                (s.storageSearchResults ?: s.storage?.entries.orEmpty())
                    .filter { it.uri in s.storageSelection }
                    .singleOrNull() ?: return@work
            if (target.isDirectory || source.isDirectory) return@work
            importPlan = workspaceFiles.planReplace(location.session, target, source)
            mutable.update {
                it.copy(
                    batchConfirmation =
                        BatchConfirmation(
                            BatchKind.TargetedReplace,
                            storagePath() + "/" + source.name,
                            location.session.name + "/" + target.path,
                            1,
                            conflicts = 1,
                            paths = listOf(source.name + " → " + target.name),
                        )
                )
            }
        }

    fun requestOverwriteSave() {
        var session = state.value.location?.session ?: return
        while (session.parent != null) session = requireNotNull(session.parent)
        if (session.sourceUri == null || session.demo) {
            saveWorkspaceAs()
            return
        }
        mutable.update { it.copy(overwriteTarget = session.name) }
    }

    fun dismissOverwriteSave() {
        mutable.update { it.copy(overwriteTarget = null) }
    }

    fun confirmOverwriteSave() {
        dismissOverwriteSave()
        saveWorkspace()
    }

    fun dismissOverwriteDirectory() {
        mutable.update { it.copy(overwriteDirectoryTarget = null) }
    }

    fun chooseOverwriteDirectory() {
        dismissOverwriteDirectory()
        overwriteAfterDirectory = true
        requestStorageDirectory()
    }

    fun pickedDocuments(uris: List<Uri>) {
        val pending = pendingPicker ?: return
        if (pending.first != PickPurpose.Add) {
            pickedDocument(uris.firstOrNull())
            return
        }
        pendingPicker = null
        if (
            uris.isEmpty() || state.value.location?.session?.handle != pending.second.session.handle
        )
            return
        work(R.string.workspace_preparing) {
            val entries = uris.distinct().map { workspaceFiles.document(it) }
            prepareImport(
                pending.second,
                entries,
                false,
                getApplication<Application>().getString(R.string.workspace_picked_files),
            )
        }
    }

    fun requestBatchExport() {
        if (state.value.busy != null) return
        if (state.value.storage == null) {
            exportAfterDirectory = true
            requestStorageDirectory()
            return
        }
        work(R.string.workspace_preparing) {
            val s = state.value
            val location = s.location ?: return@work
            val destination = s.storage ?: return@work
            val entries =
                (s.archiveSearchResults ?: location.entries).filter {
                    it.path in s.archiveSelection
                }
            if (entries.isEmpty()) return@work
            val plan = workspaceFiles.planExport(location.session, destination, entries)
            exportPlan = plan
            mutable.update {
                it.copy(
                    batchConfirmation =
                        BatchConfirmation(
                            BatchKind.Export,
                            archivePath(),
                            storagePath(),
                            plan.total,
                            plan.conflicts,
                            plan.newItems,
                            plan.skipped,
                            plan.items.map { item -> item.path },
                            plan.items.mapNotNull { item ->
                                item.issue?.let { BatchFailure(item.path, it) }
                            },
                        )
                )
            }
        }
    }

    fun requestBatchDelete() {
        val s = state.value
        if (s.busy != null) return
        if (s.activePane == WorkspacePane.Archive) {
            if (s.location?.session?.info?.editable != true) return
            deleteArchiveEntries =
                (s.archiveSearchResults ?: s.location.entries).filter {
                    it.path in s.archiveSelection
                }
            if (deleteArchiveEntries.isEmpty()) return
            mutable.update {
                it.copy(
                    batchConfirmation =
                        BatchConfirmation(
                            BatchKind.DeleteArchive,
                            archivePath(),
                            "",
                            deleteArchiveEntries.size,
                            paths = deleteArchiveEntries.map { entry -> entry.name },
                        )
                )
            }
        } else {
            deleteStorageEntries =
                (s.storageSearchResults ?: s.storage?.entries.orEmpty()).filter {
                    it.uri in s.storageSelection
                }
            if (deleteStorageEntries.isEmpty()) return
            mutable.update {
                it.copy(
                    batchConfirmation =
                        BatchConfirmation(
                            BatchKind.DeleteStorage,
                            storagePath(),
                            "",
                            deleteStorageEntries.size,
                            paths = deleteStorageEntries.map { entry -> entry.name },
                        )
                )
            }
        }
    }

    fun dismissBatch() {
        importPlan = null
        exportPlan = null
        deleteArchiveEntries = emptyList()
        deleteStorageEntries = emptyList()
        mutable.update { it.copy(batchConfirmation = null) }
    }

    fun dismissBatchResult() {
        mutable.update { it.copy(batchResult = null) }
    }

    fun executeBatch(policy: ConflictPolicy) {
        val confirmation = state.value.batchConfirmation ?: return
        work(R.string.workspace_working) {
            batchRunning = true
            mutable.update { it.copy(batchConfirmation = null) }
            val progress: (BatchProgress) -> Unit = { p ->
                mutable.update {
                    it.copy(
                        busy =
                            Busy(
                                R.string.workspace_working,
                                progress =
                                    if (p.total > 0) p.completed.toFloat() / p.total else null,
                                visited = p.completed,
                                detail = "${p.completed} / ${p.total} · ${p.path}",
                            )
                    )
                }
            }
            try {
                val result =
                    when (confirmation.kind) {
                        BatchKind.Import,
                        BatchKind.Replace,
                        BatchKind.TargetedReplace ->
                            workspaceFiles.executeImport(
                                requireNotNull(importPlan),
                                policy,
                                progress,
                            )
                        BatchKind.Export ->
                            workspaceFiles.executeExport(
                                requireNotNull(exportPlan),
                                policy,
                                progress,
                            )
                        BatchKind.DeleteArchive ->
                            workspaceFiles.deleteArchive(
                                requireNotNull(state.value.location).session,
                                deleteArchiveEntries,
                                progress,
                            )
                        BatchKind.DeleteStorage ->
                            workspaceFiles.deleteStorage(deleteStorageEntries, progress)
                    }
                mutable.update { it.copy(batchResult = result, coverage = null) }
            } finally {
                batchRunning = false
                dismissBatch()
                withContext(NonCancellable) {
                    runCatching { refreshLocation() }
                    runCatching { refreshStorageLocation() }
                }
                clearArchiveSelection()
                clearStorageSelection()
            }
        }
    }

    private fun message(id: Int) {
        mutable.update { it.copy(message = id) }
    }

    fun consumeMessage() {
        mutable.update { it.copy(message = null) }
    }

    fun dismissError() {
        mutable.update { it.copy(error = null) }
    }

    override fun onCleared() {
        archiveSearchJob?.cancel()
        storageSearchJob?.cancel()
        internalStoragePickers.close()
        archiveOpeners.close()
        archiveOverwrite.cancel()
        workspaceFiles.cancel()
        storagePickers.close()
        pendingToolArchive?.let(repository::release)
        pendingPackSource?.let(repository::forgetFile)
        repository.close()
        exports.close()
        pickers.close()
        tools.close()
        super.onCleared()
    }
}
