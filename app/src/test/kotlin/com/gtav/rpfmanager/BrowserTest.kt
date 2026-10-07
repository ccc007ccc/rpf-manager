package com.gtav.rpfmanager

import com.gtav.rpfmanager.data.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BrowserTest {
    private val files =
        listOf(
            FileEntry("z.ydr", 200),
            FileEntry("Materials.YTD", 30),
            FileEntry("notes", 0, true),
            FileEntry("map.ymap", 100),
        )

    @Test
    fun searchCanChangeAndClearWithoutLosingEntries() {
        assertEquals(
            listOf("z.ydr"),
            filterEntries(files, "ydr", AssetFilter.All, SortOrder.Name).map { it.name },
        )
        assertEquals(
            listOf("map.ymap", "Materials.YTD"),
            filterEntries(files, "ma", AssetFilter.All, SortOrder.Name).map { it.name },
        )
        assertEquals(4, filterEntries(files, "", AssetFilter.All, SortOrder.Name).size)
        assertEquals(
            listOf("Materials.YTD"),
            filterEntries(files, "", AssetFilter.Textures, SortOrder.Name).map { it.name },
        )
        assertEquals(4, files.size)
    }

    @Test
    fun directoriesStayFirstWhenSortingBySize() {
        assertEquals(
            listOf("notes", "z.ydr", "map.ymap", "Materials.YTD"),
            filterEntries(files, "", AssetFilter.All, SortOrder.Size).map { it.name },
        )
    }

    @Test
    fun extensionParsingDoesNotDependOnDisplayLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(AssetKind.Texture, FileEntry("TIRE.YTD", 1).kind)
            assertEquals("1.5 KB", formatSize(1536, Locale.ENGLISH))
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun triangle(): ByteArray =
        ByteBuffer.allocate(48 + 3 * 48 + 12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(0x4D465052)
                putInt(2)
                putInt(3)
                putInt(3)
                putInt(1)
                putInt(0)
                repeat(6) { putFloat(0f) }
                repeat(36) { putFloat(0f) }
                putInt(0)
                putInt(1)
                putInt(2)
            }
            .array()

    @Test
    fun emptyMapsCrossTheBridgeWithoutAllowingEmptyModelMeshes() {
        val bytes = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x4D465052)
            putInt(2)
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(1)
            repeat(6) { putFloat(0f) }
        }.array()
        val map = MeshData.decode(bytes)
        assertTrue(map.isEmptyMap)
        assertEquals(0, map.parts)
        assertTrue(map.bounds.all { it == 0f })
        val emptyModel = bytes.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 0)
        }
        assertThrows(IllegalArgumentException::class.java) { MeshData.decode(emptyModel) }
        val missingEntityMesh = bytes.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1)
        }
        assertThrows(IllegalArgumentException::class.java) { MeshData.decode(missingEntityMesh) }
    }

    @Test
    fun meshBridgeRejectsCorruptionBeforeGpuUpload() {
        assertEquals(1, MeshData.decode(triangle()).triangleCount)
        val invalidIndex =
            triangle().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(it.size - 4, 3)
            }
        assertThrows(IllegalArgumentException::class.java) { MeshData.decode(invalidIndex) }
        assertThrows(IllegalArgumentException::class.java) {
            MeshData.decode(triangle().copyOf(40))
        }
        val invalidFloat =
            triangle().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(48, Float.NaN)
            }
        assertThrows(IllegalArgumentException::class.java) { MeshData.decode(invalidFloat) }
    }
}
