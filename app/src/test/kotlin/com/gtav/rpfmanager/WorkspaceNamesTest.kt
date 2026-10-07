package com.gtav.rpfmanager

import com.gtav.rpfmanager.data.model.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceNamesTest {
    @Test fun unicodeCaseKeysMatchRustIncludingNonContextualSigma() {
        assertEquals(rpfNameKey("NOTES/ALPHA.TXT"), rpfNameKey("notes/alpha.txt"))
        assertEquals("οσ", rpfNameKey("ΟΣ"))
        assertEquals("i\u0307", rpfNameKey("İ"))
    }

    @Test fun selectingParentsDoesNotDeleteOrExportChildrenTwice() {
        val parent = FileEntry("Folder", 0, true, "Folder")
        val nested = FileEntry("child.txt", 12, false, "folder/child.txt")
        val unrelated = FileEntry("other.txt", 3)
        assertEquals(listOf(parent, unrelated), selectedArchiveRoots(listOf(parent, nested, unrelated, unrelated)))
    }

    @Test fun namesEnforceRpfByteLimitAndCannotEscapeDirectories() {
        for (name in listOf("..", "a/b", "a\\b", "a\u0000b", "a\nb", "界".repeat(86))) {
            assertTrue(name, runCatching { requireWorkspaceName(name) }.isFailure)
        }
        requireWorkspaceName("界".repeat(85))
        requireWorkspaceName("notes v2.txt")
    }
}
