package com.gtav.rpfmanager

import com.gtav.rpfmanager.astc.checkedLocalPath
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalDocumentPathsTest {
    @Test fun localPathsRejectTraversalSiblingPrefixesAndSymbolicLinks() {
        val fixture = Files.createTempDirectory("rpf-local-boundary").toFile()
        try {
            val root = fixture.resolve("root").apply { mkdir() }
            val outside = fixture.resolve("root-other").apply { mkdir() }
            val file = root.resolve("file.txt").apply { writeText("safe") }
            val external = outside.resolve("outside.txt").apply { writeText("outside") }
            assertEquals(file, checkedLocalPath(file.path, root.path))
            assertTrue(runCatching { checkedLocalPath(external.path, root.path) }.isFailure)
            assertTrue(runCatching { checkedLocalPath(root.path + "/../root-other/outside.txt", root.path) }.isFailure)
            val link = root.resolve("link.txt")
            Files.createSymbolicLink(link.toPath(), external.toPath())
            assertTrue(runCatching { checkedLocalPath(link.path, root.path) }.isFailure)
            val directoryLink = root.resolve("linked-directory")
            Files.createSymbolicLink(directoryLink.toPath(), outside.toPath())
            assertTrue(runCatching { checkedLocalPath(directoryLink.resolve("outside.txt").path, root.path) }.isFailure)
            assertEquals("outside", external.readText())
            Files.delete(link.toPath())
            Files.delete(directoryLink.toPath())
        } finally { fixture.deleteRecursively() }
    }
}
