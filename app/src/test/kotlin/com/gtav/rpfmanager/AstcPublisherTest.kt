package com.gtav.rpfmanager

import com.gtav.rpfmanager.astc.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AstcPublisherTest {
    private class ProcessDeath : Error()
    private class Journal : AstcJournal {
        var record: AstcReplacement? = null
        override fun load() = record
        override fun save(record: AstcReplacement) { this.record = record }
        override fun clear() { record = null }
    }
    private class Documents : AstcPublishStore {
        val files = linkedMapOf<String, Pair<AstcDocument, String>>()
        var crashAfterRename = 0
        var renames = 0
        fun add(name: String, content: String): AstcDocument = AstcDocument(
            id = "id-${files.size}-$name", name = name, parent = "parent", size = content.length.toLong(),
            modified = 1, rename = true, delete = true,
        ).also { files[name] = it to content }
        override fun find(parent: String, name: String) = files[name]?.first
        override fun rename(document: AstcDocument, name: String): AstcDocument {
            check(name !in files)
            val (current, content) = files.remove(document.name) ?: error("Missing source")
            check(current.id == document.id)
            val renamed = current.copy(id = current.id + "-renamed", name = name)
            files[name] = renamed to content
            if (++renames == crashAfterRename) throw ProcessDeath()
            return renamed
        }
        override fun delete(document: AstcDocument) { check(files.remove(document.name)?.first?.id == document.id) }
        override fun hash(document: AstcDocument) = files.getValue(document.name).second
    }

    @Test fun replacementKeepsOriginalUntilOutputHasBeenVerified() {
        val files = Documents(); val journal = Journal()
        val original = files.add("archive.rpf", "original")
        val staged = files.add("temporary", "converted")
        AstcPublisher(files, journal).replace(original, staged, "converted", "test")
        assertEquals(setOf("archive.rpf"), files.files.keys)
        assertEquals("converted", files.hash(files.find("parent", "archive.rpf")!!))
        assertNull(journal.load())
    }

    @Test fun recoveryHandlesProcessDeathAfterEitherRename() {
        for (crashAt in 1..2) {
            val files = Documents(); val journal = Journal()
            val original = files.add("archive.rpf", "original")
            val staged = files.add("temporary", "converted")
            files.crashAfterRename = crashAt
            try { AstcPublisher(files, journal).replace(original, staged, "converted", "test"); fail("Expected process death") }
            catch (_: ProcessDeath) { }
            files.crashAfterRename = 0
            assertTrue(AstcPublisher(files, journal).recover())
            assertEquals(if (crashAt == 1) "original" else "converted", files.hash(files.find("parent", "archive.rpf")!!))
            assertEquals(setOf("archive.rpf"), files.files.keys)
            assertNull(journal.load())
        }
    }

    @Test fun anUnrelatedFileNeverDisplacesTheRecoveryBackup() {
        val files = Documents(); val journal = Journal()
        files.add("archive.rpf", "unrelated")
        files.add("backup", "original")
        files.add("temporary", "converted")
        journal.save(AstcReplacement("parent", "archive.rpf", "backup", "temporary", "converted", 9, "backed-up"))
        try { AstcPublisher(files, journal).recover(); fail("Expected recovery conflict") } catch (_: IOException) { }
        assertEquals("original", files.hash(files.find("parent", "backup")!!))
        assertEquals("unrelated", files.hash(files.find("parent", "archive.rpf")!!))
        assertNotNull(journal.load())
    }

    @Test fun badPublishedDataRestoresTheOriginalAndPreservesFailedOutput() {
        val files = Documents(); val journal = Journal()
        val original = files.add("archive.rpf", "original")
        val staged = files.add("temporary", "bad")
        try { AstcPublisher(files, journal).replace(original, staged, "expected", "test"); fail("Expected verification failure") }
        catch (_: IllegalStateException) { }
        assertEquals("original", files.hash(files.find("parent", "archive.rpf")!!))
        assertEquals("bad", files.hash(files.find("parent", "temporary.failed")!!))
        assertNull(journal.load())
    }
}
