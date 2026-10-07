package com.gtav.rpfmanager

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.gtav.rpfmanager.data.model.*
import com.gtav.rpfmanager.data.repository.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Local mode uses only this test application's external-files directory. */
class LocalWorkspaceFilesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var fixture: File
    private lateinit var repository: RpfRepository
    private lateinit var files: WorkspaceFiles

    @Before fun setUp() {
        fixture = File(context.getExternalFilesDir(null), "local-workspace-${UUID.randomUUID()}").apply { mkdirs() }
        repository = RpfRepository(context)
        files = WorkspaceFiles(context, repository)
    }

    @After fun tearDown() {
        repository.close()
        fixture.deleteRecursively()
    }

    @Test fun localNavigationRecursiveSearchAndBoundaries() = runBlocking {
        val incoming = File(fixture, "incoming").apply { mkdir() }
        val deep = File(incoming, "group/matching-folder").apply { mkdirs() }
        File(deep, "matching.txt").writeText("payload")
        val root = files.root(Uri.fromFile(incoming))
        var errors = emptyList<BatchFailure>()
        val matches = files.searchStorage(root, "matching") { errors = it }
        assertEquals(2, matches.size)
        assertTrue(errors.toString(), errors.isEmpty())
        val directory = matches.single { it.isDirectory }
        val text = matches.single { !it.isDirectory }
        assertEquals(root.treeUri, text.rootUri)
        val located = files.locateStorage(root, text)
        assertEquals(Uri.fromFile(deep).toString(), located.directory.uri)
        assertEquals(3, located.breadcrumbs.size)
        assertEquals(located.directory.uri, files.enter(root, directory).directory.uri)
        files.rename(root, text, "renamed.txt")
        assertEquals("payload", File(deep, "renamed.txt").readText())
        assertFalse(File(deep, "matching.txt").exists())
        val outside = File(fixture, "outside").apply { mkdir() }
        val forged = root.copy(directory = files.document(Uri.fromFile(outside)))
        assertTrue(runCatching { files.list(forged) }.isFailure)
        val parentAndChild = files.searchStorage(root, "renamed") + directory
        assertEquals(1, files.deleteStorage(parentAndChild).completed)
        assertFalse(deep.exists())
        assertTrue(outside.exists())
    }

    @Test fun localTransfersAndSafeOverwriteRetainSourceProtection() = runBlocking {
        val incoming = File(fixture, "incoming").apply { mkdir() }
        val payload = File(incoming, "nested/asset.txt").apply { parentFile!!.mkdirs(); writeText("original payload") }
        File(incoming, "nested/empty").mkdir()
        val session = repository.demo()
        val imported = files.executeImport(files.planImport(session, "", files.root(Uri.fromFile(incoming)).entries), ConflictPolicy.Skip)
        assertEquals(imported.failures.toString(), 0, imported.failed)
        assertEquals(3, imported.completed)
        val output = File(fixture, "output").apply { mkdir() }
        val destination = files.root(Uri.fromFile(output))
        val selected = repository.list(session).entries.filter { it.name == "nested" }
        val exported = files.executeExport(files.planExport(session, destination, selected), ConflictPolicy.Skip)
        assertEquals(exported.failures.toString(), 0, exported.failed)
        val exportedFile = File(output, "nested/asset.txt")
        assertEquals(payload.readText(), exportedFile.readText())
        assertTrue(File(output, "nested/empty").isDirectory)
        exportedFile.writeText("existing output")
        val replaced = files.executeExport(files.planExport(session, destination, selected), ConflictPolicy.Replace)
        assertEquals(replaced.failures.toString(), 0, replaced.failed)
        assertEquals("original payload", exportedFile.readText())

        val source = File(fixture, "source.rpf")
        repository.save(session, Uri.fromFile(source))
        val opened = repository.openUri(Uri.fromFile(source)).first
        repository.edit(opened, "mkdir", "saved-locally")
        val saved = ArchiveOverwrite(context, repository).overwrite(opened, null).first
        assertTrue(repository.list(saved).entries.any { it.name == "saved-locally" })
        assertFalse(repository.info(saved)!!.dirty)
        assertTrue(repository.list(opened).entries.any { it.name == "saved-locally" })
        assertEquals(1, files.deleteStorage(listOf(files.document(Uri.fromFile(source)))).failed)
        assertTrue(source.exists())
    }

    @Test fun recursiveResultsKeepPathsAndRejectFlattenedNameCollisions() = runBlocking {
        val incoming = File(fixture, "incoming").apply { mkdir() }
        File(incoming, "one/match.txt").apply { parentFile!!.mkdir(); writeText("one") }
        File(incoming, "two/match.txt").apply { parentFile!!.mkdir(); writeText("two") }
        val root = files.root(Uri.fromFile(incoming))
        val matches = files.searchStorage(root, "match")
        assertEquals(2, matches.size)
        assertNotEquals(matches[0].parentUri, matches[1].parentUri)
        val session = repository.demo()
        val conflictPlan = files.planImport(session, "", matches)
        assertEquals(2, conflictPlan.items.count { it.issue != null })
        assertEquals(2, files.executeImport(conflictPlan, ConflictPolicy.Replace).failed)
        files.executeImport(files.planImport(session, "", root.entries), ConflictPolicy.Skip)
        val archiveMatches = files.searchArchive(session, "", "match")
        assertEquals(setOf("one/match.txt", "two/match.txt"), archiveMatches.map { it.path }.toSet())
        val destination = files.root(Uri.fromFile(File(fixture, "out").apply { mkdir() }))
        assertEquals(2, files.planExport(session, destination, archiveMatches).items.count { it.issue != null })
        assertTrue(files.searchArchive(session, "", "readme").none { it.path.startsWith("nested.rpf/") })
    }
}
