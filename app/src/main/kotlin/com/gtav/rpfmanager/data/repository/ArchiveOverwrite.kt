package com.gtav.rpfmanager.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.gtav.rpfmanager.RpfCore
import com.gtav.rpfmanager.astc.*
import com.gtav.rpfmanager.data.model.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OverwriteNeedsDirectory(val archiveName: String) : IOException("Choose the folder containing $archiveName to allow safe replacement")

/** Publish a saved RPF through a recoverable pair of SAF renames, keeping the old
 * mapped session readable until the caller accepts and installs the new session. */
class ArchiveOverwrite(private val context: Context, private val repository: RpfRepository) {
    private val storage = AstcStorage(context)
    private val journal = AstcFileJournal(File(context.noBackupFilesDir, "archive-overwrite"))
    private val publisher = AstcPublisher(storage, journal)
    private val mutex = Mutex()
    private val stopped = AtomicBoolean(false)
    @Volatile private var activeHandle = 0L
    @Volatile private var publishing = false

    /** Cancellation is accepted while preparing; publication and reopening finish together. */
    fun cancel() {
        if (publishing) return
        stopped.set(true)
        activeHandle.takeIf { it > 0L }?.let(RpfCore::cancelOperation)
    }

    /** Run before opening any source documents after process restart. */
    suspend fun recover(): Boolean = withContext(Dispatchers.IO) { mutex.withLock { publisher.recover() } }

    /** Read-only preflight; no staging or filesystem mutation. */
    suspend fun requireDestination(session: Session, parent: StorageLocation?): StorageEntry = withContext(Dispatchers.IO) {
        require(session.isArchive && session.parent == null) { "Save the outer archive" }
        val source = session.sourceUri?.let(Uri::parse) ?: throw OverwriteNeedsDirectory(session.name)
        fun identity(uri: Uri): String = if (DocumentsContract.isDocumentUri(context, uri))
            "${uri.authority}:${DocumentsContract.getDocumentId(uri)}" else uri.toString()
        val original: AstcDocument
        val writableParent: Boolean
        if (source.scheme == "file") {
            val file = checkedLocalFile(source)
            val folder = requireNotNull(file.parentFile)
            original = storage.info(source.toString(), Uri.fromFile(folder).toString())
            writableParent = folder.canWrite()
        } else {
            if (parent == null || source.scheme != "content") throw OverwriteNeedsDirectory(session.name)
            original = storage.list(parent.directory.uri).singleOrNull { identity(Uri.parse(it.id)) == identity(source) }
                ?: throw OverwriteNeedsDirectory(session.name)
            writableParent = parent.directory.canWrite
        }
        if (!writableParent || !original.rename || !original.delete)
            throw IOException("This folder cannot safely replace files. Grant writable access to a provider that supports rename and delete.")
        require(!original.directory) { "The original archive is no longer a file" }
        repository.requireCurrentSource(session, Uri.parse(original.id))
        StorageEntry(original.id, original.name, original.size, false, true, original.delete,
            original.rename, original.parent, original.modified)
    }

    suspend fun overwrite(
        session: Session,
        parent: StorageLocation?,
        onPublishing: () -> Unit = {},
    ): Pair<Session, RecentFile> {
        stopped.set(false)
        var delivered: Session? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    checkPreparing()
                    publisher.recover()
                    val original = requireDestination(session, parent)
                    activeHandle = session.handle
                    RpfCore.resetCancellation(session.handle)
                    var staged: ExportFile? = null
                    try {
                        checkPreparing()
                        staged = repository.stageArchive(session)
                        val expectedSize = staged.file.length()
                        val expectedHash = hash(staged.file)
                        checkPreparing()
                        val job = UUID.randomUUID().toString()
                        val temporaryName = ".rpf-save-$job.rpf"
                        // Register the temporary name before create/write so a killed process
                        // leaves enough information to remove only its own incomplete output.
                        journal.save(AstcReplacement(original.parentUri, original.name, ".rpf-backup-$job",
                            temporaryName, expectedHash, expectedSize, phase = "staging"))
                        val temporary = storage.create(original.parentUri, temporaryName)
                        repository.export(staged, Uri.parse(temporary.id), session.handle)
                        val candidate = storage.info(temporary.id, original.parentUri)
                        require(candidate.size == expectedSize && storage.hash(candidate) { checkStopped() } == expectedHash) {
                            "The saved file failed verification; the original has been preserved"
                        }
                        val verification = repository.openUri(Uri.parse(candidate.id)).first
                        try { require(verification.isArchive) { "The saved file is not a valid RPF archive" } }
                        finally { repository.release(verification) }
                        checkPreparing()
                        repository.requireCurrentSource(session, Uri.parse(original.uri))
                        publishing = true
                        onPublishing()
                        withContext(NonCancellable) {
                            publisher.replace(original.astc(), candidate, expectedHash, job)
                            val published = storage.find(original.parentUri, original.name)
                                ?: throw IOException("Saved archive is missing after publication")
                            repository.openUri(Uri.parse(published.id)).also { delivered = it.first }
                        }
                    } catch (error: Exception) {
                        val recoveryError = withContext(NonCancellable) {
                            try { publisher.recover(); null } catch (failure: Exception) { failure }
                        }
                        if (recoveryError != null) {
                            val record = journal.load()
                            throw IOException("${error.message}; recovery backup retained: ${record?.backupName ?: "see the source folder"}. ${recoveryError.message}", error)
                        }
                        if (!publishing && stopped.get()) throw CancellationException("Save cancelled").apply { initCause(error) }
                        throw error
                    } finally {
                        staged?.file?.let(repository::forgetFile)
                        activeHandle = 0L
                        publishing = false
                        RpfCore.resetCancellation(session.handle)
                    }
                }
            }
        } catch (error: Throwable) {
            // withContext may discard an acquired session if the owner was destroyed.
            delivered?.let(repository::release)
            throw error
        }
    }

    private fun checkStopped() { if (stopped.get()) throw CancellationException("Save cancelled") }
    private suspend fun checkPreparing() { currentCoroutineContext().ensureActive(); checkStopped() }

    private suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                checkPreparing()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun StorageEntry.astc() = AstcDocument(uri, name, parentUri, isDirectory, size, modified, canRename, canDelete, canWrite)
}
