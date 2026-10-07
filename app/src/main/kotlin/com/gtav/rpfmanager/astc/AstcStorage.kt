package com.gtav.rpfmanager.astc

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.provider.DocumentsContract
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject

class AstcStorage(private val context: Context) : AstcPublishStore {
    private val resolver = context.contentResolver
    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
    )
    fun root(tree: String): AstcDocument {
        val uri = Uri.parse(tree)
        if (uri.scheme == "file") return info(tree, "").also { require(it.directory) { "Choose a directory" } }
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Choose a directory with the system picker" }
        val document = if (DocumentsContract.isDocumentUri(context, uri)) uri else
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        return info(document.toString(), "").also { require(it.directory) { "Choose a directory" } }
    }
    fun info(id: String, parent: String): AstcDocument {
        val uri = Uri.parse(id)
        if (uri.scheme == "file") {
            val file = checkedLocalFile(uri)
            val parentWritable = file.parentFile?.canWrite() == true
            return AstcDocument(Uri.fromFile(file).toString(), file.name, parent,
                directory = file.isDirectory, size = if (file.isDirectory) 0 else file.length(), modified = file.lastModified(),
                rename = parentWritable, delete = parentWritable, create = file.isDirectory && file.canWrite())
        }
        return query(uri, parent).singleOrNull() ?: throw IOException("Document is missing")
    }
    fun list(parent: String): List<AstcDocument> {
        val uri = Uri.parse(parent)
        if (uri.scheme == "file") {
            val folder = checkedLocalFile(uri)
            require(folder.isDirectory) { "Choose a directory" }
            return (folder.listFiles() ?: throw IOException("Cannot read directory: ${folder.path}"))
                .filterNot { java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .map { info(Uri.fromFile(it).toString(), parent) }.sortedBy { it.name }
        }
        return query(DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri)), parent)
            .sortedBy { it.name }
    }
    private fun query(uri: Uri, parent: String): List<AstcDocument> =
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            val indices = columns.map(cursor::getColumnIndexOrThrow)
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(indices[1]) ?: throw IOException("Missing document name")
                    require(name.isNotEmpty() && name !in listOf(".", "..") && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid document name" }
                    val flags = cursor.getInt(indices[5])
                    val id = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(indices[0])).toString()
                    add(AstcDocument(id, name, parent,
                        directory = cursor.getString(indices[2]) == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = cursor.getLong(indices[3]).coerceAtLeast(0), modified = cursor.getLong(indices[4]),
                        rename = flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0,
                        delete = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0,
                        create = flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0))
                }
            }
        } ?: throw IOException("Cannot read directory")

    override fun find(parent: String, name: String): AstcDocument? = list(parent).filter { it.name == name }.let {
        check(it.size <= 1) { "Storage has duplicate document names" }
        it.singleOrNull()
    }
    fun create(parent: String, name: String, directory: Boolean = false): AstcDocument {
        require(name.isNotBlank() && name !in listOf(".", "..") && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid output name" }
        check(find(parent, name) == null) { "Output already exists: $name" }
        if (Uri.parse(parent).scheme == "file") {
            val folder = checkedLocalFile(Uri.parse(parent))
            val output = File(folder, name)
            checkedLocalFile(Uri.fromFile(output), parent, mustExist = false)
            if (!(if (directory) output.mkdir() else output.createNewFile())) throw IOException("Cannot create $name")
            return info(Uri.fromFile(output).toString(), parent)
        }
        val uri = DocumentsContract.createDocument(resolver, Uri.parse(parent),
            if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", name)
            ?: throw IOException("Cannot create $name")
        return info(uri.toString(), parent).also {
            if (it.name != name) {
                runCatching { delete(it) }
                throw IOException("Storage changed the output name: $name")
            }
        }
    }
    override fun rename(document: AstcDocument, name: String): AstcDocument {
        if (Uri.parse(document.id).scheme == "file") {
            require(name.isNotBlank() && name !in listOf(".", "..") && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid output name" }
            val original = checkedLocalFile(Uri.parse(document.id))
            val parent = requireNotNull(original.parentFile)
            val target = checkedLocalFile(Uri.fromFile(File(parent, name)), Uri.fromFile(parent).toString(), mustExist = false)
            check(!target.exists()) { "Output already exists: $name" }
            if (!original.renameTo(target)) throw IOException("Cannot rename ${document.name}")
            return info(Uri.fromFile(target).toString(), document.parent)
        }
        val uri = DocumentsContract.renameDocument(resolver, Uri.parse(document.id), name)
            ?: throw IOException("Cannot rename ${document.name}")
        val granted = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(document.id), DocumentsContract.getDocumentId(uri))
        return info(granted.toString(), document.parent)
    }
    override fun delete(document: AstcDocument) {
        if (Uri.parse(document.id).scheme == "file") {
            val root = checkedLocalFile(Uri.parse(document.id))
            fun remove(file: File) {
                // Unlink links inside a removed directory; never visit their targets.
                if (!java.nio.file.Files.isSymbolicLink(file.toPath())) {
                    checkedLocalFile(Uri.fromFile(file), Uri.fromFile(root).toString())
                    if (file.isDirectory) (file.listFiles() ?: throw IOException("Cannot read ${file.name}")).forEach(::remove)
                }
                if (!file.delete()) throw IOException("Cannot remove ${file.name}")
            }
            remove(root)
            return
        }
        if (!DocumentsContract.deleteDocument(resolver, Uri.parse(document.id))) throw IOException("Cannot remove ${document.name}")
    }
    fun open(document: AstcDocument, write: Boolean = false): ParcelFileDescriptor {
        val uri = Uri.parse(document.id)
        if (uri.scheme == "file") return ParcelFileDescriptor.open(checkedLocalFile(uri),
            if (write) ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY)
        return resolver.openFileDescriptor(uri, if (write) "rw" else "r") ?: throw IOException("Cannot open ${document.name}")
    }

    override fun hash(document: AstcDocument): String = hash(document) {}
    fun hash(document: AstcDocument, check: () -> Unit): String {
        if (Uri.parse(document.id).scheme == "file") checkedLocalFile(Uri.parse(document.id))
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(Uri.parse(document.id))?.use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: throw IOException("Cannot verify ${document.name}")
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun copy(source: AstcDocument, target: AstcDocument, check: () -> Unit) {
        require(source.id != target.id)
        if (Uri.parse(source.id).scheme == "file") checkedLocalFile(Uri.parse(source.id))
        if (Uri.parse(target.id).scheme == "file") checkedLocalFile(Uri.parse(target.id))
        resolver.openInputStream(Uri.parse(source.id))?.use { input ->
            resolver.openOutputStream(Uri.parse(target.id), "wt")?.use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.flush()
            } ?: throw IOException("Cannot write ${target.name}")
        } ?: throw IOException("Cannot read ${source.name}")
    }
    fun writeText(target: AstcDocument, text: String) {
        if (Uri.parse(target.id).scheme == "file") checkedLocalFile(Uri.parse(target.id))
        resolver.openOutputStream(Uri.parse(target.id), "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Cannot write report")
    }
    fun requireSeparate(source: AstcDocument, parent: AstcDocument, descendants: Collection<AstcDocument>) {
        val a = Uri.parse(source.id); val b = Uri.parse(parent.id)
        if (a.scheme == "file" && b.scheme == "file") {
            val input = checkedLocalFile(a)
            val output = checkedLocalFile(b)
            check(output != input && !output.path.startsWith(input.path.trimEnd('/') + "/")) { "Choose an output location outside the source directory" }
            return
        }
        if (a.authority != b.authority) return
        check(DocumentsContract.getDocumentId(a) != DocumentsContract.getDocumentId(b)) { "Choose an output location outside the source directory" }
        // The complete source inventory also works on API 27–28, where the
        // public isChildDocument API is unavailable. Document IDs are provider identities.
        val targetId = DocumentsContract.getDocumentId(b)
        check(descendants.none { DocumentsContract.getDocumentId(Uri.parse(it.id)) == targetId }) { "Output location must be outside the source directory" }
        if (Build.VERSION.SDK_INT < 29) return
        val child = try { DocumentsContract.isChildDocument(resolver, a, b) }
            catch (e: Exception) { throw IOException("Choose local storage that can verify directory boundaries", e) }
        check(!child) { "Output location must be outside the source directory" }
    }
}

class AstcFileJournal(directory: File) : AstcJournal {
    private val file = AtomicFile(File(directory.apply { mkdirs() }, "replacement.json"))
    override fun load(): AstcReplacement? {
        if (!file.baseFile.exists()) return null
        return JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).let {
            AstcReplacement(it.getString("parent"), it.getString("originalName"), it.getString("backupName"),
                it.getString("temporaryName"), it.getString("expectedHash"), it.getLong("expectedSize"),
                it.getString("phase"), it.optString("publishedId"))
        }
    }
    override fun save(record: AstcReplacement) {
        val bytes = JSONObject().put("parent", record.parent).put("originalName", record.originalName)
            .put("backupName", record.backupName).put("temporaryName", record.temporaryName)
            .put("expectedHash", record.expectedHash).put("expectedSize", record.expectedSize)
            .put("phase", record.phase).put("publishedId", record.publishedId).toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    override fun clear() { file.delete() }
}
