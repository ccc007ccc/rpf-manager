package com.gtav.rpfmanager

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.gtav.rpfmanager.astc.*
import com.gtav.rpfmanager.data.model.Session
import com.gtav.rpfmanager.data.repository.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ArchiveOverwriteTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var storage: AstcStorage
    private lateinit var fixture: AstcDocument
    private lateinit var repository: RpfRepository
    private lateinit var files: WorkspaceFiles
    private lateinit var overwrite: ArchiveOverwrite

    @Before fun setUp() {
        val testPackage = instrumentation.context.packageName
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $testPackage/com.gtav.rpfmanager.GrantTestDocumentsActivity").use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        storage = AstcStorage(context)
        val root = storage.root(DocumentsContract.buildTreeDocumentUri("$testPackage.documents", "root").toString())
        fixture = storage.create(root.id, "overwrite-${UUID.randomUUID()}", true)
        repository = RpfRepository(context)
        files = WorkspaceFiles(context, repository)
        overwrite = ArchiveOverwrite(context, repository)
    }

    @After fun tearDown() {
        if (::repository.isInitialized) repository.close()
        if (::fixture.isInitialized) storage.delete(fixture)
        AstcFileJournal(File(context.noBackupFilesDir, "archive-overwrite")).clear()
    }

    private suspend fun source(name: String = "source.rpf"): Pair<Session, AstcDocument> {
        val demo = repository.demo()
        val original = storage.create(fixture.id, name)
        repository.save(demo, Uri.parse(original.id))
        repository.release(demo)
        return repository.openUri(Uri.parse(original.id)).first to storage.info(original.id, fixture.id)
    }

    @Test fun safeOverwriteReopensPublishedFileWhileOldMappingRemainsReadable() = runBlocking {
        val (session, original) = source()
        val entry = repository.list(session).entries.first { !it.isDirectory }
        val originalEntry = RpfCore.getFileData(session.handle, entry.path)
        val originalHash = storage.hash(original)
        repository.edit(session, "mkdir", "saved-change")
        val staged = repository.stageArchive(session)
        assertTrue(repository.info(session)!!.dirty)
        repository.forgetFile(staged.file)
        var publishing = false
        val (saved, recent) = overwrite.overwrite(session, files.root(Uri.parse(fixture.id))) { publishing = true }
        assertTrue(publishing)
        assertEquals(saved.sourceUri, recent.uri)
        assertFalse(repository.info(saved)!!.dirty)
        assertTrue(repository.list(saved).entries.any { it.name == "saved-change" })
        assertTrue(repository.info(session)!!.dirty)
        assertArrayEquals(originalEntry, RpfCore.getFileData(session.handle, entry.path))
        val published = storage.find(fixture.id, "source.rpf")!!
        assertNotEquals(originalHash, storage.hash(published))
        assertTrue(storage.list(fixture.id).none { it.name.startsWith(".rpf-save") || it.name.contains("astc-backup") })
        assertFalse(overwrite.recover())
    }

    @Test fun missingWrongAndReadOnlyDirectoryDoNotStageOrModifyTheSource() = runBlocking {
        val (session, original) = source()
        val hash = storage.hash(original)
        repository.edit(session, "mkdir", "pending")
        try { overwrite.requireDestination(session, null); fail("A directory grant is required") }
        catch (_: OverwriteNeedsDirectory) {}
        val wrong = storage.create(fixture.id, "wrong", true)
        try { overwrite.overwrite(session, files.root(Uri.parse(wrong.id))); fail("An unrelated folder must not be accepted") }
        catch (_: OverwriteNeedsDirectory) {}
        val parent = files.root(Uri.parse(fixture.id))
        try { overwrite.overwrite(session, parent.copy(directory = parent.directory.copy(canWrite = false))); fail("Read-only access cannot publish") }
        catch (error: IOException) { assertFalse(error is OverwriteNeedsDirectory) }
        assertTrue(repository.info(session)!!.dirty)
        assertEquals(hash, storage.hash(original))
        assertTrue(storage.list(fixture.id).none { it.name.startsWith(".rpf-save") })
    }

    @Test fun failureAfterStagingPreservesDirtyWorkspaceAndAllowsRetry() = runBlocking {
        val (session, original) = source()
        val hash = storage.hash(original)
        repository.edit(session, "mkdir", "pending")
        val parent = files.root(Uri.parse(fixture.id))
        try {
            overwrite.overwrite(session, parent) { throw IOException("Simulated publication interruption") }
            fail("The simulated failure must be reported")
        } catch (error: IOException) { assertTrue(error.message!!.contains("Simulated")) }
        assertEquals(hash, storage.hash(original))
        assertTrue(repository.info(session)!!.dirty)
        assertTrue(repository.list(session).entries.any { it.name == "pending" })
        assertTrue(storage.list(fixture.id).none { it.name.startsWith(".rpf-save") || it.name.contains("astc-backup") })
        val result = overwrite.overwrite(session, parent)
        assertTrue(repository.list(result.first).entries.any { it.name == "pending" })
    }

    @Test fun restartRecoveryRestoresBackupOrFinishesVerifiedPublication() = runBlocking {
        val journal = AstcFileJournal(File(context.noBackupFilesDir, "archive-overwrite"))
        for (published in listOf(false, true)) {
            val (session, original) = source("source-$published.rpf")
            val originalHash = storage.hash(original)
            repository.edit(session, "mkdir", "recovered-change")
            val candidate = storage.create(fixture.id, ".candidate-$published.rpf")
            val staged = repository.stageArchive(session)
            try { repository.export(staged, Uri.parse(candidate.id), session.handle) }
            finally { repository.forgetFile(staged.file) }
            val ready = storage.info(candidate.id, fixture.id)
            val expectedHash = storage.hash(ready)
            val backupName = ".backup-$published.rpf"
            journal.save(AstcReplacement(fixture.id, original.name, backupName, ready.name, expectedHash, ready.size))
            storage.rename(original, backupName)
            if (published) storage.rename(ready, original.name)
            // A fresh publisher sees only the journal and current provider state.
            assertTrue(ArchiveOverwrite(context, repository).recover())
            assertFalse(ArchiveOverwrite(context, repository).recover())
            val restored = storage.find(fixture.id, original.name)!!
            assertEquals(if (published) expectedHash else originalHash, storage.hash(restored))
            assertNull(storage.find(fixture.id, backupName))
            assertNull(storage.find(fixture.id, ready.name))
            assertTrue(repository.info(session)!!.dirty)
            assertTrue(repository.list(session).entries.any { it.name == "recovered-change" })
        }
    }
}
