package com.gtav.rpfmanager.data.repository

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.gtav.rpfmanager.RpfCore
import com.gtav.rpfmanager.astc.AstcDocument
import com.gtav.rpfmanager.astc.AstcStorage
import com.gtav.rpfmanager.data.model.*
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class RpfRepository(private val context: Context) : Closeable {
    private val prefs = context.getSharedPreferences("rpf_studio", Context.MODE_PRIVATE)
    private val cacheRoot = File(context.cacheDir, "workspaces").apply { mkdirs() }
    private val keysDir = File(context.noBackupFilesDir, "keys")
    private val handles = mutableSetOf<Long>()
    private val protectedSources = mutableMapOf<Long, Pair<Long, Long>>()
    private val protectedUris = mutableMapOf<Long, String>()
    private val files = mutableSetOf<File>()
    private var closed = false
    @Volatile private var toolHandle = 0L

    fun cancelTools() {
        toolHandle.takeIf { it != 0L }?.let(RpfCore::cancelOperation)
    }

    private data class SafEntry(val documentId: String, val name: String, val directory: Boolean)

    private fun queryChildren(parent: Uri): List<SafEntry> {
        val document = if (DocumentsContract.isDocumentUri(context, parent)) parent else
            DocumentsContract.buildDocumentUriUsingTree(parent, DocumentsContract.getTreeDocumentId(parent))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            document,
            DocumentsContract.getDocumentId(document),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val names = mutableSetOf<String>()
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: throw IOException("Missing document name")
                    require(name.isNotBlank() && name !in listOf(".", "..") && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid document name" }
                    require(names.add(name.lowercase(java.util.Locale.ROOT))) { "Duplicate RPF name: $name" }
                    add(
                        SafEntry(
                            cursor.getString(idColumn),
                            name,
                            cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR,
                        )
                    )
                }
            }
        } ?: throw IOException("Cannot read directory")
    }

    private fun childUri(parent: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(parent, documentId)

    init {
        // One-time removal of copies produced by the previous opening workflow.
        if (!prefs.getBoolean("direct_read_v1", false)) {
            cacheRoot.listFiles()?.forEach { it.deleteRecursively() }
            context.cacheDir
                .listFiles()
                ?.filter { it.name.startsWith("temp_") && it.extension == "rpf" }
                ?.forEach { it.delete() }
            prefs.edit().putBoolean("direct_read_v1", true).apply()
        }
    }

    fun settings() =
        Settings(
            prefs.getString("language", "system") ?: "system",
            prefs.getString("appearance", "dark") ?: "dark",
            File(keysDir, "gtav_aes_key.dat").exists(),
        )

    fun saveSettings(s: Settings) {
        prefs.edit().putString("language", s.language).putString("appearance", s.appearance).apply()
    }

    fun recents(): List<RecentFile> =
        runCatching {
                val array = JSONArray(prefs.getString("recents", "[]"))
                List(array.length()) { i ->
                    array.getJSONObject(i).let {
                        RecentFile(it.getString("uri"), it.getString("name"), it.getLong("size"))
                    }
                }
            }
            .getOrDefault(emptyList())

    fun saveRecents(items: List<RecentFile>) {
        prefs
            .edit()
            .putString(
                "recents",
                JSONArray()
                    .apply {
                        items.take(12).forEach {
                            put(
                                JSONObject()
                                    .put("uri", it.uri)
                                    .put("name", it.name)
                                    .put("size", it.size)
                            )
                        }
                    }
                    .toString(),
            )
            .apply()
    }

    suspend fun restoreKeys() =
        withContext(Dispatchers.IO) {
            if (settings().hasKeys && RpfCore.available) RpfCore.loadKeys(keysDir.path)
        }

    private fun newFile(suffix: String = "asset"): File =
        synchronized(this) {
            check(!closed)
            File(cacheRoot, "${UUID.randomUUID()}.$suffix").also { files += it }
        }

    // withContext can discard a successful result when its caller is cancelled.
    // Keep ownership until delivery so a native handle is released in that case.
    private suspend fun <T> sessionOperation(block: suspend (own: (Session) -> Unit) -> T): T {
        var owned: Session? = null
        try {
            return withContext(Dispatchers.IO) { block { owned = it } }
        } catch (error: Throwable) {
            owned?.let(::release)
            throw error
        }
    }

    /** Keep the SAF descriptor in Rust; opening never copies the source file. */
    suspend fun openUri(uri: Uri): Pair<Session, RecentFile> = sessionOperation { own ->
        check(RpfCore.available) { "Native library is unavailable" }
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "resource.rpf"
        var length = -1L
        if (uri.scheme == "content") {
            context.contentResolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                        if (s >= 0 && !cursor.isNull(s)) length = cursor.getLong(s)
                    }
                }
        }
        name = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "resource.rpf" }
        val archive = name.endsWith(".rpf", true)
        var handle = 0L
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize >= 0) length = descriptor.statSize
                currentCoroutineContext().ensureActive()
                handle =
                    if (archive) RpfCore.openArchiveFd(descriptor.fd, name)
                    else RpfCore.openResourceFd(descriptor.fd)
                val stat = android.system.Os.fstat(descriptor.fileDescriptor)
                synchronized(this) {
                    protectedSources[handle] = stat.st_dev to stat.st_ino
                    protectedUris[handle] = uri.toString()
                }
            } ?: throw IOException("Document is no longer accessible")
            synchronized(this) {
                check(!closed)
                handles += handle
            }
            val info = if (archive) archiveInfo(handle) else null
            val session =
                Session(handle, null, name, length.coerceAtLeast(0), false, info, sourceUri = uri.toString()).also(own)
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {}
            session to RecentFile(uri.toString(), name, session.size)
        } catch (error: Throwable) {
            if (handle != 0L) {
                RpfCore.closeArchive(handle)
                synchronized(this) { handles -= handle; protectedSources -= handle; protectedUris -= handle }
            }
            throw error
        }
    }

    private fun archiveInfo(handle: Long): ArchiveInfo =
        JSONObject(RpfCore.getArchiveInfo(handle)).let {
            ArchiveInfo(
                it.getInt("files"),
                it.getInt("directories"),
                it.getString("encryption"),
                it.optBoolean("editable"),
                it.optBoolean("dirty"),
                it.optBoolean("modified"),
                it.optBoolean("undo"),
                it.optBoolean("redo"),
            )
        }

    suspend fun demo(): Session = sessionOperation { own ->
        val file = newFile("rpf")
        context.assets.open("studio.rpf").use { input -> file.outputStream().use(input::copyTo) }
        openFile(file, "studio.rpf", true).also(own)
    }

    private fun openFile(file: File, name: String, demo: Boolean): Session {
        var handle = 0L
        try {
            val isArchive = name.endsWith(".rpf", true)
            if (isArchive) {
                handle = RpfCore.openArchive(file.path, name)
                synchronized(this) {
                    check(!closed)
                    handles += handle
                    val stat = android.system.Os.stat(file.path)
                    protectedSources[handle] = stat.st_dev to stat.st_ino
                    protectedUris[handle] = Uri.fromFile(file).toString()
                }
            }
            val info = if (isArchive) archiveInfo(handle) else null
            return Session(handle, file, name, file.length(), demo, info)
        } catch (e: Throwable) {
            if (handle != 0L) {
                RpfCore.closeArchive(handle)
                synchronized(this) { handles -= handle; protectedSources -= handle; protectedUris -= handle }
            }
            throw e
        }
    }

    suspend fun list(session: Session, path: String = ""): Location =
        withContext(Dispatchers.IO) {
            val entries =
                if (!session.isArchive)
                    listOf(FileEntry(session.name, session.size, path = session.name))
                else {
                    val array = JSONArray(RpfCore.listFiles(session.handle, path))
                    List(array.length()) {
                        array.getJSONObject(it).let { j ->
                            FileEntry(
                                j.getString("name"),
                                j.getLong("size"),
                                j.getBoolean("is_dir"),
                                j.getString("path"),
                            )
                        }
                    }
                }
            Location(
                if (session.isArchive) session.copy(info = archiveInfo(session.handle))
                else session,
                path,
                entries,
            )
        }

    suspend fun nested(session: Session, entry: FileEntry): Session = sessionOperation { own ->
        var handle = 0L
        try {
            handle = RpfCore.openNestedArchive(session.handle, entry.path)
            synchronized(this) {
                check(!closed)
                handles += handle
            }
            Session(
                    handle,
                    null,
                    entry.name,
                    entry.size,
                    session.demo,
                    archiveInfo(handle),
                    session,
                    entry.path,
                )
                .also(own)
        } catch (e: Throwable) {
            if (handle != 0L) {
                RpfCore.closeArchive(handle)
                synchronized(this) { handles -= handle }
            }
            throw e
        }
    }

    suspend fun preview(session: Session, entry: FileEntry): ResourcePreview =
        withContext(Dispatchers.IO) {
            when (entry.kind) {
                AssetKind.Model -> {
                    val data = MeshData.decode(RpfCore.previewMesh(session.handle, entry.path))
                    val embedded = TextureSource(session.handle, entry.path, entry.name, true)
                    val dictionaries = JSONArray(RpfCore.textureSources(session.handle))
                    val sources =
                        listOf(embedded) +
                            List(dictionaries.length()) { i ->
                                    val d = dictionaries.getJSONObject(i)
                                    TextureSource(
                                        session.handle,
                                        d.getString("path"),
                                        d.getString("name"),
                                    )
                                }
                                .sortedBy { it.path }
                    var diagnostic: String? = null
                    val textures =
                        try {
                            textureList(embedded)
                        } catch (e: Exception) {
                            diagnostic = e.message
                            emptyList()
                        }
                    ResourcePreview.Mesh(
                        data,
                        sources,
                        embedded,
                        textures,
                        textureError = diagnostic,
                    )
                }
                AssetKind.Map,
                AssetKind.Collision ->
                    ResourcePreview.Mesh(
                        MeshData.decode(RpfCore.previewMesh(session.handle, entry.path))
                    )
                AssetKind.Texture -> {
                    ResourcePreview.Textures(
                        textureList(TextureSource(session.handle, entry.path, entry.name))
                    )
                }
                AssetKind.Audio -> {
                    val tracks = JSONArray(RpfCore.listAudio(session.handle, entry.path))
                    ResourcePreview.Audio(
                        List(tracks.length()) { i ->
                            val t = tracks.getJSONObject(i)
                            AudioTrack(
                                t.getInt("index"),
                                t.getString("id"),
                                t.getString("codec"),
                                t.getInt("rate"),
                                t.getLong("duration_ms"),
                                t.getBoolean("playable"),
                                t.optString("reason"),
                            )
                        }
                    )
                }
                AssetKind.Text -> {
                    if (entry.size > 2 * 1024 * 1024) ResourcePreview.Unsupported
                    else {
                        val data = RpfCore.getFileData(session.handle, entry.path)
                        val text = runCatching { decodeText(data) }.getOrNull()
                        if (text == null) ResourcePreview.Unsupported
                        else ResourcePreview.Text(text.take(64 * 1024), text.length > 64 * 1024)
                    }
                }
                AssetKind.Image -> {
                    val data = RpfCore.getFileData(session.handle, entry.path)
                    val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, info)
                    require(
                        info.outWidth > 0 &&
                            info.outHeight > 0 &&
                            info.outWidth.toLong() * info.outHeight <= 16_777_216
                    ) {
                        "Image exceeds preview limit"
                    }
                    ResourcePreview.Picture(
                        BitmapFactory.decodeByteArray(data, 0, data.size)
                            ?: throw IOException("Invalid image")
                    )
                }
                else -> {
                    if (entry.size > 2 * 1024 * 1024) ResourcePreview.Unsupported
                    else {
                        val data = RpfCore.getFileData(session.handle,entry.path)
                        val text = runCatching { decodeText(data) }.getOrNull()
                        if (text == null) ResourcePreview.Unsupported
                        else ResourcePreview.Text(text.take(64 * 1024),text.length > 64 * 1024)
                    }
                }
            }
        }

    suspend fun texture(
        session: Session,
        entry: FileEntry,
        index: Int,
    ): Pair<android.graphics.Bitmap, ByteArray> =
        withContext(Dispatchers.IO) {
            val png = RpfCore.texturePng(session.handle, entry.path, index)
            (BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: throw IOException("Invalid texture")) to png
        }

    suspend fun prepareExport(
        session: Session,
        entry: FileEntry,
        png: ByteArray?,
        textureName: String?,
    ): ExportFile =
        withContext(Dispatchers.IO) {
            val output = newFile("export")
            try {
                if (png != null) output.writeBytes(png)
                else RpfCore.extractFile(session.handle, entry.path, output.path)
                ExportFile(
                    output,
                    if (png != null)
                        "${textureName.orEmpty().replace('/', '_').replace('\\', '_').ifBlank { "texture" }}.png"
                    else entry.name,
                    if (png != null) "image/png" else "application/octet-stream",
                )
            } catch (error: Throwable) {
                forgetFile(output)
                throw error
            }
        }

    suspend fun export(source: ExportFile, uri: Uri, handle: Long = 0L) =
        withContext(Dispatchers.IO) {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { output ->
                ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    input ->
                    RpfCore.copyOutputFd(handle, input.fd, output.fd)
                }
            } ?: throw IOException("Cannot write to document")
            source.file.delete()
        }

    suspend fun info(session: Session): ArchiveInfo? =
        withContext(Dispatchers.IO) { if (session.isArchive) archiveInfo(session.handle) else null }

    suspend fun edit(
        session: Session,
        operation: String,
        path: String? = null,
        name: String? = null,
    ) =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            RpfCore.editArchive(
                session.handle,
                JSONObject()
                    .put("op", operation)
                    .apply {
                        path?.let { put("path", it) }
                        name?.let { put("name", it) }
                    }
                    .toString(),
            )
        }

    suspend fun attach(child: Session) =
        withContext(Dispatchers.IO) {
            val parent = child.parent ?: return@withContext
            if (archiveInfo(child.handle).modified)
                RpfCore.attachNestedArchive(
                    parent.handle,
                    requireNotNull(child.parentEntry),
                    child.handle,
                )
        }

    suspend fun save(session: Session, uri: Uri) = run {
        RpfCore.resetCancellation(session.handle)
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            context.contentResolver.openFileDescriptor(uri, "rw")?.use {
                RpfCore.saveArchiveFd(session.handle, it.fd)
            } ?: throw IOException("Cannot write archive destination")
        }
    }

    /** A candidate is not a completed save: keep dirty/undo state until publication succeeds. */
    suspend fun stageArchive(session: Session): ExportFile {
        var owned: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val file = newFile("rpf").also { owned = it }
                currentCoroutineContext().ensureActive()
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                    RpfCore.stageArchiveFd(session.handle, it.fd)
                }
                ExportFile(file, session.name, "application/octet-stream")
            }
        } catch (error: Throwable) {
            owned?.let(::forgetFile)
            throw error
        }
    }

    /** A directory grant must still refer to the file mapped by this workspace. */
    internal suspend fun requireCurrentSource(session: Session, uri: Uri) = withContext(Dispatchers.IO) {
        val expected = synchronized(this@RpfRepository) { protectedSources[session.handle] }
            ?: throw IOException("The original workspace is no longer open")
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val stat = android.system.Os.fstat(descriptor.fileDescriptor)
            require(expected == (stat.st_dev to stat.st_ino)) { "The original file changed; reopen it before overwriting" }
        } ?: throw IOException("The original file is no longer available")
    }

    suspend fun importFile(
        session: Session,
        directory: String,
        uri: Uri,
        replace: FileEntry? = null,
        sourceName: String? = null,
        checkCancelled: () -> Unit = {},
    ) =
        withContext(Dispatchers.IO) {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.bin"
            if (uri.scheme == "content")
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) name = it.getString(0) }
            name = name.substringAfterLast('/').substringAfterLast('\\')
            sourceName?.let { name = it }
            requireWorkspaceName(name)
            val target =
                replace?.path
                    ?: listOf(directory, name).filter { it.isNotEmpty() }.joinToString("/")
            val stage = newFile("staged")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    stage.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var size = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            checkCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n
                            require(size <= 0xffffffffL) { "File exceeds RPF7 entry limit" }
                            output.write(buffer, 0, n)
                        }
                    }
                } ?: throw IOException("Cannot read imported file")
                currentCoroutineContext().ensureActive()
                checkCancelled()
                stageReplacement(session, target, stage, replace != null, name)
            } finally {
                forgetFile(stage)
            }
        }

    /** Check before SAF rename/delete: native output guards cannot protect a renamed source. */
    suspend fun requireStorageMutationAllowed(uri: Uri) = withContext(Dispatchers.IO) {
        fun identity(value: Uri): String = if (DocumentsContract.isDocumentUri(context, value))
            "${value.authority}:${DocumentsContract.getDocumentId(value)}" else value.toString()
        synchronized(this@RpfRepository) {
            check(protectedUris.values.none { identity(Uri.parse(it)) == identity(uri) }) {
                "Close the source archive before changing its storage file"
            }
        }
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val stat = android.system.Os.fstat(descriptor.fileDescriptor)
            synchronized(this@RpfRepository) {
                check((stat.st_dev to stat.st_ino) !in protectedSources.values) {
                    "Close the source archive before changing its storage file"
                }
            }
        } ?: throw IOException("Cannot verify the storage file")
    }

    private fun stageReplacement(session: Session, path: String, file: File, replace: Boolean, sourceName: String = path.substringAfterLast('/')) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            RpfCore.editFileFd(session.handle, path, it.fd, replace, sourceName)
        }
    }

    suspend fun readText(session: Session, entry: FileEntry): String =
        withContext(Dispatchers.IO) {
            require(entry.size <= 2 * 1024 * 1024) { "Text exceeds 2 MiB edit limit" }
            decodeText(RpfCore.getFileData(session.handle, entry.path))
        }

    suspend fun editText(session: Session, entry: FileEntry, text: String) =
        withContext(Dispatchers.IO) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 2 * 1024 * 1024) { "Text exceeds 2 MiB edit limit" }
            val stage = newFile("text")
            try {
                stage.writeBytes(bytes)
                stageReplacement(session, entry.path, stage, true)
            } finally {
                forgetFile(stage)
            }
        }

    private fun decodeText(data: ByteArray): String {
        require(data.size <= 2 * 1024 * 1024) { "Text exceeds 2 MiB limit" }
        val text =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        require(text.none { it.isISOControl() && it !in "\n\r\t" }) { "File contains binary data" }
        return text
    }

    suspend fun textureList(source: TextureSource): List<TextureInfo> =
        withContext(Dispatchers.IO) {
            val array = JSONArray(RpfCore.listTextures(source.handle, source.path))
            List(array.length()) { i ->
                array.getJSONObject(i).let { j ->
                    TextureInfo(
                        j.getInt("index"),
                        j.getString("name"),
                        j.getInt("width"),
                        j.getInt("height"),
                        j.getString("format"),
                        j.getInt("levels"),
                        j.optInt("layers", 1),
                        j.optInt("depth", 1),
                        j.optBoolean("srgb"),
                    )
                }
            }
        }

    suspend fun modelTexture(source: TextureSource, index: Int): MeshTexture =
        withContext(Dispatchers.IO) {
            val png = RpfCore.texturePng(source.handle, source.path, index)
            val bitmap =
                BitmapFactory.decodeByteArray(png, 0, png.size)
                    ?: throw IOException("Invalid texture")
            val ratio = minOf(1f, 2048f / maxOf(bitmap.width, bitmap.height))
            val scaled =
                if (ratio < 1f)
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * ratio).toInt().coerceAtLeast(1),
                        (bitmap.height * ratio).toInt().coerceAtLeast(1),
                        true,
                    )
                else bitmap
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            val result = MeshTexture(scaled.width, scaled.height, pixels)
            if (bitmap !== scaled) bitmap.recycle()
            scaled.recycle()
            result
        }

    suspend fun audio(session: Session, entry: FileEntry, index: Int): File {
        var output: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val file = newFile("wav").also { output = it }
                RpfCore.audioWav(session.handle, entry.path, index, file.path)
                file
            }
        } catch (e: Throwable) {
            output?.let(::forgetFile)
            throw e
        }
    }

    suspend fun exportBytes(bytes: ByteArray, name: String, mime: String): ExportFile =
        withContext(Dispatchers.IO) {
            val file = newFile("export")
            file.writeBytes(bytes)
            ExportFile(file, name, mime)
        }

    suspend fun extractArchive(session: Session, treeUri: Uri): Int = withContext(Dispatchers.IO) {
        require(session.isArchive) { "Select an RPF archive" }
        currentCoroutineContext().ensureActive()
        val storage = AstcStorage(context)
        val parent = storage.root(treeUri.toString())
        val folderName = session.name.substringBeforeLast('.', session.name)
        // Only a newly created directory belongs to this job. Existing user data
        // is never removed to make room for an extraction.
        val root = storage.create(parent.id, folderName, true)
        toolHandle = session.handle
        try {
            var count = 0
            var visited = 0
            suspend fun extract(handle: Long, path: String, destination: AstcDocument, depth: Int, nested: Int) {
                require(depth <= 64 && nested <= 24) { "Archive nesting limit reached" }
                currentCoroutineContext().ensureActive()
                val entries = JSONArray(RpfCore.listFiles(handle, path))
                for (index in 0 until entries.length()) {
                    currentCoroutineContext().ensureActive()
                    require(++visited <= 200_000) { "Archive entry limit reached" }
                    val entry = entries.getJSONObject(index)
                    val name = entry.getString("name")
                    val entryPath = entry.getString("path")
                    if (entry.getBoolean("is_dir")) {
                        extract(handle, entryPath, storage.create(destination.id, name, true), depth + 1, nested)
                    } else if (name.endsWith(".rpf", true)) {
                        val child = RpfCore.openNestedArchive(handle, entryPath)
                        try {
                            extract(child, "", storage.create(destination.id, name, true), depth + 1, nested + 1)
                        } finally { RpfCore.closeArchive(child) }
                    } else {
                        val child = storage.create(destination.id, name)
                        storage.open(child, true).use { output ->
                            RpfCore.extractFileFd(handle, entryPath, output.fd, session.handle)
                        }
                        count++
                    }
                }
            }
            extract(session.handle, "", root, 0, 0)
            currentCoroutineContext().ensureActive()
            count
        } catch (error: Throwable) {
            runCatching { storage.delete(root) }
            throw error
        } finally {
            toolHandle = 0L
        }
    }

    suspend fun stageDirectory(treeUri: Uri): File {
        val root = File(cacheRoot, "pack-${UUID.randomUUID()}").apply { mkdirs() }
        synchronized(this@RpfRepository) { files += root }
        val seen = mutableSetOf<String>()
        var visited = 0
        suspend fun copyDirectory(parent: Uri, target: File, depth: Int) {
            require(depth <= 64) { "Directory nesting limit reached" }
            for (child in queryChildren(parent)) {
                currentCoroutineContext().ensureActive()
                require(seen.add(child.documentId) && ++visited <= 200_000) { "Repeated document or directory entry limit reached" }
                if (child.directory) {
                    val directory = File(target, child.name).apply { mkdirs() }
                    copyDirectory(childUri(parent, child.documentId), directory, depth + 1)
                } else {
                    val output = File(target, child.name)
                    context.contentResolver.openInputStream(childUri(parent, child.documentId))?.use { input ->
                        output.outputStream().use { out ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                        }
                    } ?: throw IOException("Cannot read source file: ${child.name}")
                }
            }
        }
        return try {
            withContext(Dispatchers.IO) { copyDirectory(treeUri, root, 0); root }
        } catch (error: Throwable) {
            forgetFile(root)
            throw error
        }
    }

    suspend fun packDirectory(source: File, outputUri: Uri): Long = withContext(Dispatchers.IO) {
        val operation = RpfCore.beginOperation()
        toolHandle = operation
        try {
            currentCoroutineContext().ensureActive()
            context.contentResolver.openFileDescriptor(outputUri, "rw")?.use { descriptor ->
                RpfCore.packDirectoryCancellable(source.path, descriptor.fd, operation)
            } ?: throw IOException("Cannot write RPF destination")
        } finally { toolHandle = 0L; RpfCore.finishOperation(operation) }
    }

    suspend fun copyExport(source: File, name: String, mime: String): ExportFile =
        withContext(Dispatchers.IO) {
            val file = newFile("export")
            source.inputStream().use { input -> file.outputStream().use(input::copyTo) }
            ExportFile(file, name, mime)
        }

    suspend fun scan(session: Session): CoverageReport = run {
        RpfCore.resetCancellation(session.handle)
        withContext(Dispatchers.IO) {
            val raw = RpfCore.scanArchive(session.handle)
            val j = JSONObject(raw)
            fun counts(o: JSONObject) =
                CoverageCounts(
                    o.optInt("preview"),
                    o.optInt("partial"),
                    o.optInt("unsupported"),
                    o.optInt("failed"),
                    o.optInt("container"),
                )
            val fs = j.getJSONArray("formats")
            val rows = j.getJSONArray("rows")
            CoverageReport(
                j.getString("name"),
                j.getBoolean("finished"),
                j.getBoolean("complete"),
                j.getInt("unopenedArchives"),
                counts(j.getJSONObject("counts")),
                List(fs.length()) { i ->
                    fs.getJSONObject(i).let {
                        CoverageFormat(
                            it.getString("extension"),
                            counts(it.getJSONObject("counts")),
                        )
                    }
                },
                List(rows.length()) { i ->
                    rows.getJSONObject(i).let {
                        CoverageRow(
                            it.getString("path"),
                            it.getString("extension"),
                            it.getLong("size"),
                            it.getString("status"),
                            it.getString("detail"),
                        )
                    }
                },
                raw,
            )
        }
    }

    fun forgetFile(file: File) {
        synchronized(this) { files -= file }
        file.deleteRecursively()
    }

    suspend fun importKeys(uri: Uri) =
        withContext(Dispatchers.IO) {
            val sourceChildren = queryChildren(uri)
            val staging = File(cacheRoot, "keys-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                for ((name, size) in
                    listOf(
                        "gtav_aes_key.dat" to 32L,
                        "gtav_ng_key.dat" to 27472L,
                        "gtav_ng_decrypt_tables.dat" to 278528L,
                    )) {
                    val document =
                        sourceChildren.firstOrNull { it.name == name }
                            ?: throw IOException("Missing key file: $name")
                    context.contentResolver.openInputStream(childUri(uri, document.documentId))?.use { input ->
                        File(staging, name).outputStream().use { output ->
                            val data = input.readBytesBounded(size.toInt() + 1)
                            require(data.size.toLong() == size) { "Invalid key file size: $name" }
                            output.write(data)
                        }
                    } ?: throw IOException("Cannot read key file")
                }
                RpfCore.loadKeys(staging.path)
                keysDir.mkdirs()
                staging.listFiles()?.forEach { it.copyTo(File(keysDir, it.name), overwrite = true) }
            } finally {
                staging.deleteRecursively()
            }
        }

    suspend fun clearKeys() =
        withContext(Dispatchers.IO) {
            RpfCore.clearKeys()
            keysDir.deleteRecursively()
            Unit
        }

    fun release(session: Session) {
        synchronized(this) {
            if (handles.remove(session.handle)) RpfCore.closeArchive(session.handle)
            protectedSources -= session.handle
            protectedUris -= session.handle
            session.file?.let { files -= it }
        }
        session.file?.delete()
    }

    suspend fun clearCache(active: Set<File>) =
        withContext(Dispatchers.IO) {
            cacheRoot.listFiles()?.filter { it !in active }?.forEach { it.deleteRecursively() }
            Unit
        }

    override fun close() =
        synchronized(this) {
            closed = true
            cancelTools()
            handles.forEach { RpfCore.closeArchive(it) }
            handles.clear()
            protectedSources.clear()
            protectedUris.clear()
            files.forEach { it.deleteRecursively() }
            files.clear()
        }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (n < 0) break
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
