package com.gtav.rpfmanager.data.model

import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ArchiveInfo(
    val files: Int,
    val directories: Int,
    val encryption: String,
    val editable: Boolean = false,
    val dirty: Boolean = false,
    val modified: Boolean = false,
    val undo: Boolean = false,
    val redo: Boolean = false,
)

data class RecentFile(val uri: String, val name: String, val size: Long)

data class Session(
    val handle: Long,
    // Only an owned cache file (the bundled sample); never an original document.
    val file: File?,
    val name: String,
    val size: Long,
    val demo: Boolean,
    val info: ArchiveInfo? = null,
    val parent: Session? = null,
    val parentEntry: String? = null,
    val sourceUri: String? = null,
) {
    val isArchive
        get() = handle > 0L
}

data class Location(val session: Session, val path: String, val entries: List<FileEntry>)

data class Settings(
    val language: String = "system",
    val appearance: String = "dark",
    val hasKeys: Boolean = false,
)

data class TextureInfo(
    val index: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val format: String,
    val levels: Int,
    val layers: Int = 1,
    val depth: Int = 1,
    val srgb: Boolean = false,
)

class MeshData(
    val vertices: FloatArray,
    val indices: IntArray,
    val parts: Int,
    val flags: Int,
    val bounds: FloatArray,
) {
    val vertexCount
        get() = vertices.size / 12

    val hasUv
        get() = flags and 4 != 0

    val isEmptyMap
        get() = flags and 1 != 0 && vertices.isEmpty() && indices.isEmpty() && parts == 0

    val triangleCount
        get() = indices.size / 3

    companion object {
        fun decode(bytes: ByteArray): MeshData {
            require(bytes.size >= 48)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(b.int == 0x4D465052 && b.int == 2) { "Invalid mesh header" }
            val vertexCount = b.int
            val indexCount = b.int
            val parts = b.int
            val flags = b.int
            val emptyMap = flags and 1 != 0 && vertexCount == 0 && indexCount == 0 && parts == 0
            require(
                emptyMap ||
                    (vertexCount in 1..1_000_000 &&
                        indexCount in 3..6_000_000 &&
                        indexCount % 3 == 0)
            )
            require(bytes.size.toLong() == 48L + vertexCount * 48L + indexCount * 4L)
            val bounds = FloatArray(6) { b.float }
            val vertices = FloatArray(vertexCount * 12)
            b.asFloatBuffer().get(vertices)
            b.position(48 + vertices.size * 4)
            val indices = IntArray(indexCount)
            b.asIntBuffer().get(indices)
            require(bounds.all { it.isFinite() } && vertices.all { it.isFinite() })
            require(indices.all { it in 0 until vertexCount })
            return MeshData(vertices, indices, parts, flags, bounds)
        }
    }
}

sealed interface ResourcePreview {
    data object Loading : ResourcePreview

    data class Mesh(
        val data: MeshData,
        val sources: List<TextureSource> = emptyList(),
        val source: TextureSource? = null,
        val textures: List<TextureInfo> = emptyList(),
        val active: Int = -1,
        val texture: MeshTexture? = null,
        val textureLoading: Boolean = false,
        val textureError: String? = null,
    ) : ResourcePreview

    data class Textures(
        val textures: List<TextureInfo>,
        val active: Int = 0,
        val bitmap: Bitmap? = null,
        val png: ByteArray? = null,
        val loading: Boolean = true,
        val diagnostic: String? = null,
    ) : ResourcePreview

    data class Picture(val bitmap: Bitmap) : ResourcePreview

    data class Text(val content: String, val truncated: Boolean) : ResourcePreview

    data class Audio(
        val tracks: List<AudioTrack>,
        val active: Int = 0,
        val file: File? = null,
        val loading: Boolean = false,
        val diagnostic: String? = null,
    ) : ResourcePreview

    data object Unsupported : ResourcePreview

    data class Failure(val diagnostic: String) : ResourcePreview
}

data class Busy(
    val label: Int,
    val progress: Float? = null,
    val visited: Int? = null,
    val detail: String = "",
    val cancellable: Boolean = true,
)

data class ExportFile(val file: File, val name: String, val mime: String)

data class DocumentRequest(val name: String, val mime: String)

enum class ToolRequest {
    ExtractInput,
    ExtractOutput,
    PackInput,
    PackOutput,
}

data class EditDialog(val operation: String, val entry: FileEntry? = null, val text: String = "")

enum class PickPurpose {
    Add,
    Replace,
    Texture,
}

data class TextureSource(
    val handle: Long,
    val path: String,
    val name: String,
    val embedded: Boolean = false,
)

class MeshTexture(val width: Int, val height: Int, val pixels: IntArray)

data class AudioTrack(
    val index: Int,
    val id: String,
    val codec: String,
    val rate: Int,
    val durationMs: Long,
    val playable: Boolean,
    val reason: String,
)

data class CoverageCounts(
    val preview: Int,
    val partial: Int,
    val unsupported: Int,
    val failed: Int,
    val container: Int,
) {
    val total
        get() = preview + partial + unsupported + failed + container
}

data class CoverageFormat(val extension: String, val counts: CoverageCounts)

data class CoverageRow(
    val path: String,
    val extension: String,
    val size: Long,
    val status: String,
    val detail: String,
)

data class CoverageReport(
    val name: String,
    val finished: Boolean,
    val complete: Boolean,
    val unopenedArchives: Int,
    val counts: CoverageCounts,
    val formats: List<CoverageFormat>,
    val rows: List<CoverageRow>,
    val json: String,
)

data class BrowserSnapshot(val location: Location, val query: String, val filter: AssetFilter)

data class AppState(
    val location: Location? = null,
    val selected: FileEntry? = null,
    val preview: ResourcePreview = ResourcePreview.Loading,
    val query: String = "",
    val filter: AssetFilter = AssetFilter.All,
    val sort: SortOrder = SortOrder.Name,
    val grid: Boolean = false,
    val settings: Settings = Settings(),
    val recents: List<RecentFile> = emptyList(),
    val showSettings: Boolean = false,
    val busy: Busy? = null,
    val error: String? = null,
    val message: Int? = null,
    val editDialog: EditDialog? = null,
    val confirmDiscard: Boolean = false,
    val workspaceDirty: Boolean = false,
    val coverage: CoverageReport? = null,
    val showCoverage: Boolean = false,
    val activePane: WorkspacePane = WorkspacePane.Archive,
    val dualPane: Boolean = true,
    val archiveSelection: Set<String> = emptySet(),
    val archiveSelectionMode: Boolean = false,
    val storage: StorageLocation? = null,
    val storageQuery: String = "",
    val storageSort: SortOrder = SortOrder.Name,
    val storageSelection: Set<String> = emptySet(),
    val storageSelectionMode: Boolean = false,
    val storageLoading: Boolean = false,
    val storageEdit: StorageEdit? = null,
    val batchConfirmation: BatchConfirmation? = null,
    val batchResult: BatchResult? = null,
    val overwriteTarget: String? = null,
    val overwriteDirectoryTarget: String? = null,
    val fileWorkspaceOpen: Boolean = false,
    val archiveSearchResults: List<FileEntry>? = null,
    val storageSearchResults: List<StorageEntry>? = null,
    val archiveSearching: Boolean = false,
    val storageSearching: Boolean = false,
    val storageSearchSkipped: List<BatchFailure> = emptyList(),
    val searchIssuesVisible: Boolean = false,
) {
    val visibleEntries: List<FileEntry>
        get() =
            filterEntries(archiveSearchResults ?: location?.entries.orEmpty(), query, filter, sort)

    val visibleStorageEntries: List<StorageEntry>
        get() {
            val entries = storageSearchResults ?: storage?.entries.orEmpty()
            val byUri = entries.associateBy { it.uri }
            return filterEntries(
                    entries.map { FileEntry(it.name, it.size, it.isDirectory, it.uri) },
                    storageQuery,
                    AssetFilter.All,
                    storageSort,
                )
                .mapNotNull { byUri[it.path] }
        }
}
