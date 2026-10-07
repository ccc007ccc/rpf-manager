package com.gtav.rpfmanager

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.gtav.rpfmanager.astc.AstcDocument
import com.gtav.rpfmanager.astc.AstcStorage
import com.gtav.rpfmanager.data.model.*
import com.gtav.rpfmanager.data.repository.RpfRepository
import com.gtav.rpfmanager.data.repository.WorkspaceFiles
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real SAF providers and JNI, including recursive transfers and source protection. */
class WorkspaceFilesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var storage: AstcStorage
    private lateinit var fixture: AstcDocument
    private lateinit var repository: RpfRepository
    private lateinit var files: WorkspaceFiles

    @Before fun setUp() {
        val testPackage = instrumentation.context.packageName
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $testPackage/com.gtav.rpfmanager.GrantTestDocumentsActivity").use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        storage = AstcStorage(context)
        val root = storage.root(DocumentsContract.buildTreeDocumentUri("$testPackage.documents", "root").toString())
        fixture = storage.create(root.id, "workspace-${UUID.randomUUID()}", true)
        repository = RpfRepository(context)
        files = WorkspaceFiles(context, repository)
    }

    @After fun tearDown() {
        if (::repository.isInitialized) repository.close()
        if (::fixture.isInitialized) storage.delete(fixture)
    }

    private fun text(parent: AstcDocument, name: String, text: String): AstcDocument =
        storage.create(parent.id, name).also { storage.writeText(it, text) }
    private fun read(document: AstcDocument): String = context.contentResolver.openInputStream(Uri.parse(document.id))!!.use { it.readBytes().toString(Charsets.UTF_8) }
    private suspend fun location(document: AstcDocument) = files.root(Uri.parse(document.id))

    @Test fun recursiveImportExportConflictModesAndSingleUndo() = runBlocking {
        val source = storage.create(fixture.id, "incoming", true)
        val folder = storage.create(source.id, "Folder", true)
        text(folder, "note.txt", "first")
        storage.create(folder.id, "empty", true)
        val sourceLocation = location(source)
        val session = repository.demo()
        val original = session.file!!.readBytes()
        val plan = files.planImport(session, "", sourceLocation.entries)
        assertEquals(3, plan.total)
        assertEquals(0, plan.conflicts)
        val imported = files.executeImport(plan, ConflictPolicy.Skip)
        assertEquals(3, imported.completed)
        assertEquals(0, imported.failed)
        val entry = repository.list(session, "Folder").entries.first { !it.isDirectory }
        assertEquals("first", repository.readText(session, entry))
        repository.edit(session, "undo")
        assertTrue(repository.list(session).entries.none { it.name == "Folder" })
        repository.edit(session, "redo")
        assertEquals("first", repository.readText(session, entry))

        val output = storage.create(fixture.id, "out", true)
        val selected = repository.list(session).entries.filter { it.name == "Folder" }
        val export = files.executeExport(files.planExport(session, location(output), selected), ConflictPolicy.Skip)
        assertEquals(3, export.completed)
        assertEquals(0, export.failed)
        val copiedFolder = storage.find(output.id, "Folder")!!
        val copiedNote = storage.find(copiedFolder.id, "note.txt")!!
        assertEquals("first", read(copiedNote))
        assertNotNull(storage.find(copiedFolder.id, "empty"))
        storage.writeText(copiedNote, "keep existing")
        val conflict = files.planExport(session, location(output), selected)
        assertEquals(1, conflict.conflicts)
        val skipped = files.executeExport(conflict, ConflictPolicy.Skip)
        assertEquals(3, skipped.skipped)
        assertEquals("keep existing", read(copiedNote))
        val replaced = files.executeExport(files.planExport(session, location(output), selected), ConflictPolicy.Replace)
        assertEquals(replaced.failures.toString(), 0, replaced.failed)
        assertEquals(1, replaced.completed)
        assertEquals("first", read(storage.find(copiedFolder.id, "note.txt")!!))
        assertTrue(storage.list(copiedFolder.id).none { it.name.startsWith(".rpf-transfer") || it.name.contains("astc-backup") })
        assertArrayEquals(original, session.file.readBytes())
    }

    @Test fun replacementOnlyCaseConflictsPartialFailureAndCancellation() = runBlocking {
        val source = storage.create(fixture.id, "incoming", true)
        text(source, "alpha.txt", "old")
        text(source, "beta.txt", "second")
        val session = repository.demo()
        val sourceLocation = location(source)
        val cancelled = files.executeImport(files.planImport(session, "", sourceLocation.entries), ConflictPolicy.Skip) {
            if (it.completed == 1) {
                files.cancel()
                // Independent pane searches must not clear a pending transfer cancellation.
                runBlocking {
                    assertTrue(files.searchStorage(sourceLocation, "alpha").isNotEmpty())
                    assertTrue(files.searchArchive(session, "", "alpha").isNotEmpty())
                }
            }
        }
        assertTrue(cancelled.cancelled)
        assertEquals(1, cancelled.completed)
        assertTrue(repository.list(session).entries.any { it.name == "alpha.txt" })
        assertTrue(repository.list(session).entries.none { it.name == "beta.txt" })
        repository.edit(session, "undo")
        assertTrue(repository.list(session).entries.none { it.name == "alpha.txt" })

        val partialPlan = files.planImport(session, "", sourceLocation.entries)
        storage.delete(storage.find(source.id, "beta.txt")!!)
        val partial = files.executeImport(partialPlan, ConflictPolicy.Skip)
        assertEquals(1, partial.completed)
        assertEquals(1, partial.failed)
        assertTrue(partial.failures.single().path.endsWith("beta.txt"))
        val replacements = storage.create(fixture.id, "replacements", true)
        text(replacements, "ALPHA.TXT", "replacement")
        text(replacements, "not-present.txt", "do not add")
        val replacementPlan = files.planImport(session, "", location(replacements).entries, replacementOnly = true)
        assertEquals(1, replacementPlan.conflicts)
        assertEquals(1, replacementPlan.skipped)
        assertEquals(2, files.executeImport(replacementPlan, ConflictPolicy.Skip).skipped)
        val replacement = files.executeImport(replacementPlan, ConflictPolicy.Replace)
        assertEquals(1, replacement.completed)
        assertEquals(1, replacement.skipped)
        val alpha = repository.list(session).entries.single { it.name == "alpha.txt" }
        assertEquals("replacement", repository.readText(session, alpha))
        assertTrue(repository.list(session).entries.none { it.name == "not-present.txt" })
        repository.edit(session, "undo")
        assertEquals("old", repository.readText(session, alpha))

        val duplicates = storage.create(fixture.id, "duplicates", true)
        text(duplicates, "same.txt", "one")
        text(duplicates, "SAME.TXT", "two")
        val duplicateResult = files.executeImport(files.planImport(session, "", location(duplicates).entries), ConflictPolicy.Replace)
        assertEquals(2, duplicateResult.failed)
        assertTrue(repository.list(session).entries.none { it.name.equals("same.txt", true) })
    }

    @Test fun navigationMutationAndOpenedSourceProtection() = runBlocking {
        var root = location(fixture)
        root = files.mkdir(root, "documents")
        val documents = root.entries.single { it.name == "documents" }
        var child = files.enter(root, documents)
        assertEquals(2, child.breadcrumbs.size)
        assertEquals(root.directory.uri, files.up(child).directory.uri)
        files.cancel()
        root = files.rename(root, documents, "renamed")
        child = files.enter(root, root.entries.single { it.name == "renamed" })
        assertTrue(child.entries.isEmpty())

        val demo = repository.demo()
        val document = storage.create(fixture.id, "source.rpf")
        repository.save(demo, Uri.parse(document.id))
        val originalHash = storage.hash(storage.info(document.id, fixture.id))
        val opened = repository.openUri(Uri.parse(document.id)).first
        repository.importFile(demo, "", Uri.parse(document.id))
        root = location(fixture)
        val source = root.entries.single { it.name == "source.rpf" }
        val deletion = files.deleteStorage(listOf(source))
        assertEquals(1, deletion.failed)
        assertEquals(originalHash, storage.hash(storage.info(document.id, fixture.id)))
        val replacement = files.executeExport(files.planExport(demo, root,
            repository.list(demo).entries.filter { it.name == "source.rpf" }), ConflictPolicy.Replace)
        assertEquals(1, replacement.failed)
        assertEquals(originalHash, storage.hash(storage.info(document.id, fixture.id)))
        assertTrue(repository.list(opened).entries.isNotEmpty())
        // Renaming/deleting a directory containing an open archive is protected too.
        val parentDeletion = files.deleteStorage(listOf(root.directory.copy(canDelete = true)))
        assertEquals(1, parentDeletion.failed)
        repository.release(opened)
        assertEquals(1, files.deleteStorage(listOf(source)).completed)
        assertNull(storage.find(fixture.id, "source.rpf"))
    }

    @Test fun explicitReplacementKeepsTargetNameAndDoesNotTouchSameNamedSibling() = runBlocking {
        val originals = storage.create(fixture.id, "originals", true)
        text(originals, "target.txt", "original target")
        text(originals, "patch.txt", "unrelated sibling")
        val patches = storage.create(fixture.id, "patches", true)
        val patch = text(patches, "patch.txt", "replacement payload")
        val session = repository.demo()
        repository.edit(session, "mkdir", "nested")
        files.executeImport(files.planImport(session, "nested", location(originals).entries), ConflictPolicy.Skip)
        val before = repository.list(session, "nested").entries
        val target = before.single { it.name == "target.txt" }
        val sibling = before.single { it.name == "patch.txt" }
        val plan = files.planReplace(session, target, files.document(Uri.parse(patch.id)))
        assertEquals(1, plan.total)
        assertEquals(1, plan.conflicts)
        assertEquals("nested/target.txt", plan.items.single().path)
        assertEquals("patch.txt", plan.items.single().source.name)
        assertEquals(1, files.executeImport(plan, ConflictPolicy.Skip).skipped)
        assertEquals("original target", repository.readText(session, target))
        val replaced = files.executeImport(plan, ConflictPolicy.Replace)
        assertEquals(replaced.failures.toString(), 0, replaced.failed)
        assertEquals(1, replaced.completed)
        assertEquals("replacement payload", repository.readText(session, target))
        assertEquals("unrelated sibling", repository.readText(session, sibling))
        assertEquals(before.map { it.name }, repository.list(session, "nested").entries.map { it.name })
        repository.edit(session, "undo")
        assertEquals("original target", repository.readText(session, target))
        assertEquals("replacement payload", read(patch))
    }

    @Test fun safRecursiveSearchLocatesDeepResultsWithoutChangingTheGrantedRoot() = runBlocking {
        val source = storage.create(fixture.id, "search-root", true)
        val group = storage.create(source.id, "group", true)
        val deep = storage.create(group.id, "matching-folder", true)
        text(deep, "matching.txt", "deep result")
        val root = location(source)
        var skipped = emptyList<BatchFailure>()
        val results = files.searchStorage(root, "matching") { skipped = it }
        assertEquals(2, results.size)
        assertTrue(skipped.toString(), skipped.isEmpty())
        val entry = results.single { !it.isDirectory }
        assertEquals("group/matching-folder/matching.txt", entry.relativePath)
        assertEquals(deep.id, entry.parentUri)
        val located = files.locateStorage(root, entry)
        assertEquals(root.treeUri, located.treeUri)
        assertEquals(3, located.breadcrumbs.size)
        assertEquals(deep.id, files.enter(root, results.single { it.isDirectory }).directory.uri)
        val session = repository.demo()
        files.executeImport(files.planImport(session, "", root.entries), ConflictPolicy.Skip)
        assertEquals("group/matching-folder/matching.txt", files.searchArchive(session, "", "matching.txt").single().path)
    }

    @Test fun deletingManySelectionsHasOneUndoAndParentSelectionCoversChildren() = runBlocking {
        val source = storage.create(fixture.id, "many", true)
        repeat(45) { text(source, "file-$it.txt", "$it") }
        val session = repository.demo()
        val imported = files.executeImport(files.planImport(session, "", location(source).entries), ConflictPolicy.Skip)
        assertEquals(45, imported.completed)
        val selected = repository.list(session).entries.filter { it.name.startsWith("file-") }
        assertEquals(45, files.deleteArchive(session, selected).completed)
        assertTrue(repository.list(session).entries.none { it.name.startsWith("file-") })
        repository.edit(session, "undo")
        assertEquals(45, repository.list(session).entries.count { it.name.startsWith("file-") })
        repository.edit(session, "undo")
        assertTrue(repository.list(session).entries.none { it.name.startsWith("file-") })
        val notes = repository.list(session).entries.first { it.isDirectory }
        val nested = repository.list(session, notes.path).entries
        assertEquals(1, files.deleteArchive(session, listOf(notes) + nested).completed)
        repository.edit(session, "undo")
        assertEquals(nested, repository.list(session, notes.path).entries)
    }
}
