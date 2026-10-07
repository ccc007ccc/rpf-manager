package com.gtav.rpfmanager.data.model

/** File or SAF identities, with an explicit browsing boundary for local storage. */
data class StorageEntry(
    val uri: String,
    val name: String,
    val size: Long = 0,
    val isDirectory: Boolean = false,
    val canWrite: Boolean = false,
    val canDelete: Boolean = false,
    val canRename: Boolean = false,
    val parentUri: String = "",
    val modified: Long = 0,
    val rootUri: String = "",
    val relativePath: String = "",
)

data class StorageLocation(
    val treeUri: String,
    val directory: StorageEntry,
    val breadcrumbs: List<StorageEntry>,
    val entries: List<StorageEntry> = emptyList(),
)

enum class ConflictPolicy { Skip, Replace }

data class BatchProgress(val completed: Int, val total: Int, val path: String)
data class BatchFailure(val path: String, val message: String)
data class BatchResult(
    val completed: Int = 0,
    val skipped: Int = 0,
    val failures: List<BatchFailure> = emptyList(),
    val cancelled: Boolean = false,
) {
    val failed: Int get() = failures.size
}

data class ImportItem(
    val source: StorageEntry,
    val path: String,
    val existing: FileEntry? = null,
    val issue: String? = null,
    val skip: Boolean = false,
) {
    val conflict: Boolean get() = existing != null && !source.isDirectory && issue == null
}

data class ImportPlan(
    val session: Session,
    val directory: String,
    val items: List<ImportItem>,
    val replacementOnly: Boolean = false,
) {
    val total: Int get() = items.size
    val conflicts: Int get() = items.count { it.conflict }
    val newItems: Int get() = items.count { it.existing == null && !it.skip && it.issue == null }
    val skipped: Int get() = items.count { it.skip }
}

data class ExportItem(
    val source: FileEntry,
    /** Path relative to the chosen storage directory. */
    val path: String,
    val existing: StorageEntry? = null,
    val issue: String? = null,
) {
    val conflict: Boolean get() = existing != null && !source.isDirectory && issue == null
}

data class ExportPlan(
    val session: Session,
    val destination: StorageLocation,
    val items: List<ExportItem>,
) {
    val total: Int get() = items.size
    val conflicts: Int get() = items.count { it.conflict }
    val newItems: Int get() = items.count { it.existing == null && it.issue == null }
    val skipped: Int get() = 0
}

/** RPF's case-insensitive namespace uses Unicode lowercase, not a device locale. */
internal fun rpfNameKey(name: String): String = buildString {
    // Lowercase one code point at a time, matching Rust str::to_lowercase; whole-string
    // Java lowercase applies a contextual final-sigma rule that Rust does not use.
    name.codePoints().forEach { append(String(Character.toChars(it)).lowercase(java.util.Locale.ROOT)) }
}

internal fun requireWorkspaceName(name: String) {
    require(name.isNotBlank() && name !in listOf(".", "..") &&
        name.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } &&
        name.toByteArray(Charsets.UTF_8).size <= 255) { "Invalid file name: $name" }
}

internal fun selectedArchiveRoots(entries: List<FileEntry>): List<FileEntry> {
    val unique = entries.distinctBy { rpfNameKey(it.path) }
    val directories = unique.filter { it.isDirectory }.map { rpfNameKey(it.path) }.toSet()
    return unique.filter { entry ->
        var parent = rpfNameKey(entry.path).substringBeforeLast('/', "")
        var selectedAncestor = false
        while (parent.isNotEmpty()) {
            if (parent in directories) { selectedAncestor = true; break }
            parent = parent.substringBeforeLast('/', "")
        }
        !selectedAncestor
    }
}
