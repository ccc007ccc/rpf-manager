package com.gtav.rpfmanager.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.gtav.rpfmanager.RpfCore
import com.gtav.rpfmanager.astc.AstcDocument
import com.gtav.rpfmanager.astc.AstcFileJournal
import com.gtav.rpfmanager.astc.AstcPublisher
import com.gtav.rpfmanager.astc.AstcStorage
import com.gtav.rpfmanager.astc.checkedLocalFile
import com.gtav.rpfmanager.data.model.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Two-pane transfers keep local files inside their browsing root and respect SAF grants. */
class WorkspaceFiles(private val context: Context, private val repository: RpfRepository) {
    private val resolver = context.contentResolver
    private val stopped = AtomicBoolean(false)
    @Volatile private var activeArchiveHandle = 0L
    private val storage = AstcStorage(context)
    private val journal = AstcFileJournal(File(context.noBackupFilesDir, "workspace-publish"))
    private val publisher = AstcPublisher(storage, journal)
    private val directoryPaths = java.util.concurrent.ConcurrentHashMap<String, List<StorageEntry>>()
    private class BatchStopped : Exception()

    /** Finish the current SAF publication before stopping, so no original is lost. */
    fun cancel() {
        stopped.set(true)
        activeArchiveHandle.takeIf { it > 0 }?.let(RpfCore::cancelOperation)
    }
    private fun checkStopped() { if (stopped.get()) throw BatchStopped() }

    suspend fun root(uri: Uri): StorageLocation = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val folder = checkedLocalFile(uri)
            require(folder.isDirectory) { "Choose a folder" }
            val directory = localEntry(uri, "", uri.toString())
            return@withContext list(StorageLocation(uri.toString(), directory, listOf(directory)))
        }
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Choose a folder with the system picker" }
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Some providers grant access only for this session. */ }
        }
        val documentUri = if (DocumentsContract.isDocumentUri(context, uri)) uri else
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val directory = document(documentUri)
        require(directory.isDirectory) { "Choose a folder" }
        list(StorageLocation(uri.toString(), directory, listOf(directory)))
    }

    suspend fun document(uri: Uri): StorageEntry = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val file = checkedLocalFile(uri)
            return@withContext localEntry(uri, file.parentFile?.let { Uri.fromFile(it).toString() }.orEmpty(), "")
        }
        val isDocument = DocumentsContract.isDocumentUri(context, uri)
        if (isDocument) query(uri, "", children = false).singleOrNull()
            ?: throw IOException("Document is no longer available")
        else resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "File is no longer available" }
            StorageEntry(uri.toString(), cursor.getString(0) ?: "imported.bin", if (cursor.isNull(1)) 0 else cursor.getLong(1))
        } ?: throw IOException("Cannot read the document")
    }

    suspend fun list(location: StorageLocation): StorageLocation = withContext(Dispatchers.IO) {
        if (Uri.parse(location.treeUri).scheme == "file") checkedLocalFile(Uri.parse(location.directory.uri), location.treeUri)
        val directory = document(Uri.parse(location.directory.uri)).copy(parentUri = location.directory.parentUri,
            rootUri = if (Uri.parse(location.treeUri).scheme == "file") location.treeUri else "")
        val breadcrumbs = location.breadcrumbs.dropLast(1) + directory
        directoryPaths[directory.uri] = breadcrumbs
        location.copy(directory = directory, entries = children(directory), breadcrumbs = breadcrumbs)
    }

    suspend fun enter(location: StorageLocation, entry: StorageEntry): StorageLocation {
        require(entry.isDirectory) { "Choose a folder" }
        val parent = if (entry.parentUri == location.directory.uri) location else locateStorage(location, entry)
        require(parent.entries.any { it.uri == entry.uri }) { "Folder is not in the current location" }
        return list(parent.copy(directory = entry, breadcrumbs = parent.breadcrumbs + entry))
    }

    /** Locate a recursive result without turning its parent into a new browsing root. */
    suspend fun locateStorage(location: StorageLocation, entry: StorageEntry): StorageLocation {
        if (entry.parentUri == location.directory.uri) return list(location)
        val path = directoryPaths[entry.parentUri] ?: throw IOException("Search again to locate this file")
        require(path.firstOrNull()?.uri == location.breadcrumbs.firstOrNull()?.uri) { "File is outside the current storage root" }
        return list(location.copy(directory = path.last(), breadcrumbs = path))
    }

    suspend fun up(location: StorageLocation): StorageLocation = if (location.breadcrumbs.size <= 1) list(location) else
        list(location.copy(directory = location.breadcrumbs[location.breadcrumbs.lastIndex - 1], breadcrumbs = location.breadcrumbs.dropLast(1)))

    suspend fun mkdir(location: StorageLocation, name: String): StorageLocation = withContext(Dispatchers.IO) {
        requireWorkspaceName(name)
        require(location.directory.canWrite) { "This folder is read-only" }
        if (Uri.parse(location.treeUri).scheme == "file") checkedLocalFile(Uri.parse(location.directory.uri), location.treeUri)
        create(location.directory, name, true)
        list(location)
    }

    suspend fun rename(location: StorageLocation, entry: StorageEntry, name: String): StorageLocation = withContext(Dispatchers.IO) {
        stopped.set(false)
        requireWorkspaceName(name)
        val containing = if (entry.parentUri == location.directory.uri) location else locateStorage(location, entry)
        require(entry.canRename && containing.entries.any { it.uri == entry.uri }) { "This document cannot be renamed" }
        if (entry.name == name) return@withContext list(location)
        require(children(containing.directory).none { it.uri != entry.uri && rpfNameKey(it.name) == rpfNameKey(name) }) { "A file with that name already exists" }
        protectTree(entry)
        val renamed = storage.rename(entry.astc(), name)
        require(renamed.name == name) { "Storage changed the requested name" }
        list(location)
    }

    suspend fun deleteStorage(entries: List<StorageEntry>, onProgress: (BatchProgress) -> Unit = {}): BatchResult = withContext(Dispatchers.IO) {
        stopped.set(false)
        runBatch(selectedStorageRoots(entries), { it.name }, onProgress) { entry ->
            require(entry.canDelete) { "This document cannot be deleted" }
            protectTree(entry)
            currentCoroutineContext().ensureActive()
            checkStopped()
            storage.delete(entry.astc())
            true
        }
    }

    /** Search directory descendants; nested RPF files remain ordinary file results. */
    suspend fun searchArchive(session: Session, path: String, query: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext emptyList()
        val result = mutableListOf<FileEntry>()
        var visited = 0
        suspend fun walk(directory: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            require(depth <= 128) { "Directory depth limit reached while searching" }
            for (entry in repository.list(session, directory).entries) {
                currentCoroutineContext().ensureActive()
                require(++visited <= 100_000) { "Search reached the 100,000-entry limit; search a narrower folder" }
                if (entry.name.contains(term, ignoreCase = true)) result += entry
                if (entry.isDirectory) walk(entry.path, depth + 1)
            }
        }
        walk(path, 1)
        result.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { rpfNameKey(it.path) })
    }

    /** Unreadable directories are reported while the rest of the tree is searched. */
    suspend fun searchStorage(
        location: StorageLocation,
        query: String,
        onSkipped: (List<BatchFailure>) -> Unit = {},
    ): List<StorageEntry> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext emptyList()
        if (Uri.parse(location.treeUri).scheme == "file") checkedLocalFile(Uri.parse(location.directory.uri), location.treeUri)
        val result = mutableListOf<StorageEntry>()
        val skipped = mutableListOf<BatchFailure>()
        val seen = mutableSetOf<String>()
        var visited = 0
        var limited = false
        suspend fun walk(directory: StorageEntry, breadcrumbs: List<StorageEntry>, relative: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (limited) return
            if (depth > 128 || !seen.add(directory.uri)) {
                skipped += BatchFailure(relative.ifBlank { directory.name }, "Directory depth limit or repeated directory")
                return
            }
            directoryPaths[directory.uri] = breadcrumbs
            val entries = try { children(directory) } catch (error: Exception) {
                if (error is CancellationException) throw error
                skipped += BatchFailure(relative.ifBlank { directory.name }, error.message ?: "Cannot read folder")
                return
            }
            for (child in entries) {
                val entry = child.copy(relativePath = join(relative, child.name))
                currentCoroutineContext().ensureActive()
                if (++visited > 100_000) {
                    limited = true
                    skipped += BatchFailure(relative.ifBlank { directory.name }, "Search reached the 100,000-entry limit; search a narrower folder")
                    return
                }
                if (entry.name.contains(term, ignoreCase = true)) result += entry
                if (entry.isDirectory) walk(entry, breadcrumbs + entry, entry.relativePath, depth + 1)
                if (limited) return
            }
        }
        val root = location.directory.copy(rootUri = if (Uri.parse(location.treeUri).scheme == "file") location.treeUri else "")
        walk(root, location.breadcrumbs.dropLast(1) + root, "", 1)
        onSkipped(skipped)
        result.sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { rpfNameKey(it.name) }.thenBy { it.parentUri })
    }

    suspend fun planImport(session: Session, directory: String, entries: List<StorageEntry>, replacementOnly: Boolean = false): ImportPlan = withContext(Dispatchers.IO) {
        require(session.info?.editable == true) { "This archive cannot be edited" }
        stopped.set(false)
        val items = mutableListOf<ImportItem>()
        val seen = mutableSetOf<String>()
        suspend fun walk(sources: List<StorageEntry>, parent: String, parentExists: Boolean, inheritedSkip: Boolean, depth: Int) {
            val destinations = if (parentExists) repository.list(session, parent).entries.associateBy { rpfNameKey(it.name) } else emptyMap()
            val duplicateNames = sources.groupingBy { rpfNameKey(it.name) }.eachCount().filterValues { it > 1 }.keys
            for (source in sources.distinctBy { it.uri }) {
                currentCoroutineContext().ensureActive()
                checkStopped()
                require(items.size < 100_000) { "Select fewer files: transfer limit is 100,000 entries" }
                val existing = destinations[rpfNameKey(source.name)]
                val path = existing?.path ?: join(parent, source.name)
                val skip = inheritedSkip || (replacementOnly && existing == null)
                val issue = runCatching {
                    requireWorkspaceName(source.name)
                    require(depth <= 128) { "Directory depth limit reached" }
                    require(rpfNameKey(source.name) !in duplicateNames) { "Multiple source names differ only in letter case" }
                    require(existing == null || existing.isDirectory == source.isDirectory) { "A file and a folder have the same name" }
                    require(seen.add(source.uri)) { "The source directory contains a cycle or duplicate document" }
                }.exceptionOrNull()?.message
                items += ImportItem(source, path, existing, issue, skip)
                if (source.isDirectory && issue == null) {
                    val nested = try { children(source) } catch (error: Exception) {
                        if (error is CancellationException || error is BatchStopped) throw error
                        items[items.lastIndex] = items.last().copy(issue = error.message ?: "Cannot read folder")
                        continue
                    }
                    walk(nested, path, existing?.isDirectory == true, skip, depth + 1)
                }
            }
        }
        walk(selectedStorageRoots(entries), directory, true, false, directory.count { it == '/' } + if (directory.isEmpty()) 1 else 2)
        ImportPlan(session, directory, items, replacementOnly)
    }

    /** Replace one explicit archive file while preserving the imported basename for NG decoding. */
    suspend fun planReplace(session: Session, target: FileEntry, source: StorageEntry): ImportPlan = withContext(Dispatchers.IO) {
        require(session.info?.editable == true) { "This archive cannot be edited" }
        require(!target.isDirectory && !source.isDirectory) { "Select one file on each side" }
        requireWorkspaceName(source.name)
        val parent = target.path.substringBeforeLast('/', "")
        val existing = repository.list(session, parent).entries.firstOrNull { rpfNameKey(it.path) == rpfNameKey(target.path) }
            ?: throw IOException("The selected archive file is no longer available")
        require(!existing.isDirectory) { "The selected destination is a folder" }
        ImportPlan(session, parent, listOf(ImportItem(source, existing.path, existing)), replacementOnly = true)
    }

    suspend fun executeImport(plan: ImportPlan, policy: ConflictPolicy, onProgress: (BatchProgress) -> Unit = {}): BatchResult = withContext(Dispatchers.IO) {
        stopped.set(false)
        inArchiveBatch(plan.session) {
            runBatch(plan.items, { it.path }, onProgress) { item ->
                item.issue?.let { throw IOException(it) }
                if (item.skip) return@runBatch false
                val parent = item.path.substringBeforeLast('/', "")
                val now = repository.list(plan.session, parent).entries.firstOrNull { rpfNameKey(it.name) == rpfNameKey(item.path.substringAfterLast('/')) }
                require(now == null || now.isDirectory == item.source.isDirectory) { "Destination changed: file/folder conflict" }
                if (item.source.isDirectory) {
                    if (now == null) repository.edit(plan.session, "mkdir", item.path)
                    else return@runBatch false
                } else {
                    if (now != null && policy == ConflictPolicy.Skip) return@runBatch false
                    if (now == null && plan.replacementOnly) return@runBatch false
                    require(now == null || item.existing != null) { "A destination file appeared after the preview; review conflicts again" }
                    if (Uri.parse(item.source.uri).scheme == "file") checkedLocalFile(Uri.parse(item.source.uri), item.source.rootUri)
                    repository.importFile(plan.session, parent, Uri.parse(item.source.uri), now, item.source.name, ::checkStopped)
                }
                true
            }
        }
    }

    suspend fun planExport(session: Session, destination: StorageLocation, entries: List<FileEntry>): ExportPlan = withContext(Dispatchers.IO) {
        require(destination.directory.canWrite) { "The destination folder is read-only" }
        stopped.set(false)
        val items = mutableListOf<ExportItem>()
        suspend fun walk(sources: List<FileEntry>, parentPath: String, target: StorageEntry?, depth: Int) {
            val destinations = target?.let { children(it).groupBy { child -> rpfNameKey(child.name) } }.orEmpty()
            val duplicateNames = sources.groupingBy { rpfNameKey(it.name) }.eachCount().filterValues { it > 1 }.keys
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                checkStopped()
                require(items.size < 100_000) { "Select fewer files: transfer limit is 100,000 entries" }
                val matches = destinations[rpfNameKey(source.name)].orEmpty()
                val existing = matches.singleOrNull()
                val path = join(parentPath, source.name)
                val issue = runCatching {
                    requireWorkspaceName(source.name)
                    require(rpfNameKey(source.name) !in duplicateNames) { "Selected files have the same destination name; transfer them separately or select their parent folders" }
                    require(depth <= 128) { "Directory depth limit reached" }
                    require(matches.size <= 1) { "Destination contains ambiguous names differing only in letter case" }
                    require(existing == null || existing.isDirectory == source.isDirectory) { "A file and a folder have the same name" }
                }.exceptionOrNull()?.message
                items += ExportItem(source, path, existing, issue)
                if (source.isDirectory && issue == null) {
                    val nested = try { repository.list(session, source.path).entries } catch (error: Exception) {
                        if (error is CancellationException || error is BatchStopped) throw error
                        items[items.lastIndex] = items.last().copy(issue = error.message ?: "Cannot read folder")
                        continue
                    }
                    walk(nested, path, existing, depth + 1)
                }
            }
        }
        walk(selectedArchiveRoots(entries), "", destination.directory, 1)
        ExportPlan(session, destination, items)
    }

    suspend fun executeExport(plan: ExportPlan, policy: ConflictPolicy, onProgress: (BatchProgress) -> Unit = {}): BatchResult = withContext(Dispatchers.IO) {
        stopped.set(false)
        // Recover a previous interrupted two-rename publication before changing more files.
        publisher.recover()
        val directories = mutableMapOf("" to plan.destination.directory)
        activeArchiveHandle = plan.session.handle
        if (plan.session.isArchive) RpfCore.resetCancellation(plan.session.handle)
        try { runBatch(plan.items, { it.path }, onProgress) { item ->
            item.issue?.let { throw IOException(it) }
            val parent = directories[item.path.substringBeforeLast('/', "")] ?: throw IOException("Parent folder was not created")
            val matches = children(parent).filter { rpfNameKey(it.name) == rpfNameKey(item.source.name) }
            require(matches.size <= 1) { "Destination has ambiguous names" }
            val now = matches.singleOrNull()
            require(now == null || now.isDirectory == item.source.isDirectory) { "Destination changed: file/folder conflict" }
            if (item.source.isDirectory) {
                directories[item.path] = now ?: create(parent, item.source.name, true)
                if (now != null) return@runBatch false
            } else {
                if (now != null && policy == ConflictPolicy.Skip) return@runBatch false
                require(now == null || (item.existing?.uri == now.uri && item.existing.size == now.size && item.existing.modified == now.modified)) {
                    "Destination changed after the preview; review conflicts again"
                }
                now?.let { repository.requireStorageMutationAllowed(Uri.parse(it.uri)) }
                exportFile(plan.session, item.source, parent, now)
            }
            true
        } } finally {
            activeArchiveHandle = 0
            if (plan.session.isArchive) RpfCore.resetCancellation(plan.session.handle)
        }
    }

    suspend fun deleteArchive(session: Session, entries: List<FileEntry>, onProgress: (BatchProgress) -> Unit = {}): BatchResult = withContext(Dispatchers.IO) {
        stopped.set(false)
        inArchiveBatch(session) {
            runBatch(selectedArchiveRoots(entries), { it.path }, onProgress) {
                repository.edit(session, "delete", it.path)
                true
            }
        }
    }

    private suspend fun <T> inArchiveBatch(session: Session, block: suspend () -> T): T {
        var begun = false
        try {
            // Cancellation must not discard the acknowledgement after native begin succeeds.
            withContext(NonCancellable) { repository.edit(session, "begin_batch"); begun = true }
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            if (begun) withContext(NonCancellable) { repository.edit(session, "end_batch") }
        }
    }

    private suspend fun <T> runBatch(items: List<T>, path: (T) -> String, onProgress: (BatchProgress) -> Unit, action: suspend (T) -> Boolean): BatchResult {
        var completed = 0
        var skipped = 0
        val failures = mutableListOf<BatchFailure>()
        for ((index, item) in items.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (stopped.get()) return BatchResult(completed, skipped, failures, true)
            onProgress(BatchProgress(index, items.size, path(item)))
            try {
                if (action(item)) completed++ else skipped++
            } catch (_: BatchStopped) {
                return BatchResult(completed, skipped, failures, true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (stopped.get() && error.message?.contains("Operation cancelled", ignoreCase = true) == true)
                    return BatchResult(completed, skipped, failures, true)
                failures += BatchFailure(path(item), error.message ?: error.javaClass.simpleName)
            }
            onProgress(BatchProgress(index + 1, items.size, path(item)))
        }
        return BatchResult(completed, skipped, failures)
    }

    private suspend fun exportFile(session: Session, source: FileEntry, parent: StorageEntry, existing: StorageEntry?) {
        if (existing != null) require(existing.canRename && existing.canDelete) { "This provider cannot safely replace existing files; choose another folder" }
        val prepared = repository.prepareExport(session, source, null, null)
        var temporary: StorageEntry? = null
        try {
            currentCoroutineContext().ensureActive()
            checkStopped()
            val name = if (existing == null) source.name else ".rpf-transfer-${UUID.randomUUID()}.tmp"
            temporary = create(parent, name, false)
            repository.export(prepared, Uri.parse(temporary.uri), session.handle)
            if (existing != null) {
                val staged = storage.info(temporary.uri, parent.uri)
                val expectedHash = storage.hash(staged) { checkStopped() }
                checkStopped()
                repository.requireStorageMutationAllowed(Uri.parse(existing.uri))
                // Publication includes recovery and must finish even if the ViewModel is destroyed.
                withContext(NonCancellable) {
                    publisher.replace(existing.astc(), staged, expectedHash, UUID.randomUUID().toString())
                }
            }
            temporary = null
        } catch (error: Exception) {
            val recovery = journal.load()
            if (recovery != null) throw IOException("${error.message}; recovery backup: ${recovery.backupName}. Reopen the destination and retry export to recover.", error)
            temporary?.let {
                try {
                    // A completed recovery may already have removed the temporary document.
                    val leftover = children(parent).firstOrNull { child -> child.name == it.name }
                    if (leftover != null) storage.delete(leftover.astc())
                } catch (cleanup: Exception) { throw IOException("${error.message}; incomplete output retained: ${it.name} (${cleanup.message})", error) }
            }
            throw error
        } finally { repository.forgetFile(prepared.file) }
    }

    private suspend fun protectTree(entry: StorageEntry, visited: MutableSet<String> = mutableSetOf()) {
        currentCoroutineContext().ensureActive()
        checkStopped()
        require(visited.add(entry.uri) && visited.size <= 100_000) { "Cannot verify this directory tree" }
        if (Uri.parse(entry.uri).scheme == "file") checkedLocalFile(Uri.parse(entry.uri), entry.rootUri)
        if (entry.isDirectory) children(entry).forEach { protectTree(it, visited) }
        else repository.requireStorageMutationAllowed(Uri.parse(entry.uri))
    }

    private fun create(parent: StorageEntry, name: String, directory: Boolean): StorageEntry {
        requireWorkspaceName(name)
        require(children(parent).none { rpfNameKey(it.name) == rpfNameKey(name) }) { "A file with that name already exists: $name" }
        if (Uri.parse(parent.uri).scheme == "file") {
            checkedLocalFile(Uri.parse(parent.uri), parent.rootUri)
            return localEntry(Uri.parse(storage.create(parent.uri, name, directory).id), parent.uri, parent.rootUri)
        }
        val created = DocumentsContract.createDocument(resolver, Uri.parse(parent.uri),
            if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", name)
            ?: throw IOException("Cannot create $name")
        val result = query(created, parent.uri, children = false).singleOrNull()
            ?: throw IOException("Cannot read newly created document $name")
        if (result.name != name) {
            if (!DocumentsContract.deleteDocument(resolver, created)) throw IOException("Provider renamed the output to ${result.name}; remove it manually")
            throw IOException("Provider changed the output name: $name")
        }
        return result
    }

    private fun children(parent: StorageEntry): List<StorageEntry> {
        require(parent.isDirectory) { "Choose a folder" }
        val uri = Uri.parse(parent.uri)
        if (uri.scheme == "file") {
            checkedLocalFile(uri, parent.rootUri)
            return storage.list(parent.uri).map { localEntry(Uri.parse(it.id), parent.uri, parent.rootUri) }
                .sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { rpfNameKey(it.name) })
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
        return query(children, parent.uri, children = true).sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { rpfNameKey(it.name) })
    }

    private fun query(uri: Uri, parent: String, children: Boolean): List<StorageEntry> {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            val index = columns.map(cursor::getColumnIndexOrThrow)
            buildList {
                while (cursor.moveToNext()) {
                    val flags = cursor.getInt(index[4])
                    val directory = cursor.getString(index[2]) == DocumentsContract.Document.MIME_TYPE_DIR
                    val document = if (children || DocumentsContract.isTreeUri(uri))
                        DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(index[0])) else uri
                    val writableGrant = context.checkUriPermission(document, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
                    add(StorageEntry(document.toString(), cursor.getString(index[1]) ?: throw IOException("Missing file name"),
                        if (cursor.isNull(index[3])) 0 else cursor.getLong(index[3]).coerceAtLeast(0), directory,
                        writableGrant && flags and (if (directory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0,
                        writableGrant && flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0,
                        writableGrant && flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0, parent,
                        if (cursor.isNull(index[5])) 0 else cursor.getLong(index[5])))
                }
            }
        } ?: throw IOException("Cannot read this folder; grant access again")
    }

    private fun StorageEntry.astc() = AstcDocument(uri, name, parentUri, isDirectory, size, modified, canRename, canDelete, canWrite)
    private fun localEntry(uri: Uri, parent: String, root: String): StorageEntry {
        val file = checkedLocalFile(uri, root)
        val parentWritable = file.parentFile?.canWrite() == true
        return StorageEntry(uri.toString(), file.name, if (file.isDirectory) 0 else file.length(), file.isDirectory,
            file.canWrite(), parentWritable, parentWritable, parent, file.lastModified(), root)
    }

    private fun selectedStorageRoots(entries: List<StorageEntry>): List<StorageEntry> {
        val unique = entries.distinctBy { it.uri }
        val directories = unique.filter { it.isDirectory }.map { it.uri }.toSet()
        val localDirectories = directories.map(Uri::parse).filter { it.scheme == "file" }.mapNotNull { it.path }.toSet()
        return unique.filter { entry ->
            val ancestors = directoryPaths[entry.parentUri].orEmpty().map { it.uri } + entry.parentUri
            var localAncestorSelected = false
            val uri = Uri.parse(entry.uri)
            if (uri.scheme == "file") {
                localAncestorSelected = "/" in localDirectories && uri.path != "/"
                var parent = uri.path.orEmpty().substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    if (parent in localDirectories) { localAncestorSelected = true; break }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            ancestors.none { it in directories } && !localAncestorSelected
        }
    }
    private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
}
