package com.gtav.rpfmanager.data.model

import java.text.NumberFormat
import java.util.Locale

enum class AssetKind {
    Folder,
    Archive,
    Texture,
    Model,
    Map,
    Collision,
    Text,
    Image,
    Audio,
    File,
}

enum class AssetFilter {
    All,
    Models,
    Textures,
    Maps,
    Collisions,
    Audio,
}

enum class SortOrder {
    Name,
    Size,
    Type,
}

data class FileEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean = false,
    val path: String = name,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val kind: AssetKind
        get() =
            when {
                isDirectory -> AssetKind.Folder
                else ->
                    when (extension) {
                        "rpf", "rpf7" -> AssetKind.Archive
                        "ytd", "dds" -> AssetKind.Texture
                        "ydr",
                        "ydd",
                        "yft" -> AssetKind.Model
                        "ymap" -> AssetKind.Map
                        "ybn" -> AssetKind.Collision
                        "awc" -> AssetKind.Audio
                        "txt",
                        "xml",
                        "meta",
                        "json",
                        "csv",
                        "ini",
                        "dat",
                        "ide",
                        "ipl",
                        "cfg", "sps", "list", "ugc" -> AssetKind.Text
                        "png",
                        "jpg",
                        "jpeg",
                        "webp", "bmp" -> AssetKind.Image
                        else -> AssetKind.File
                    }
            }
}

fun filterEntries(
    entries: List<FileEntry>,
    query: String,
    filter: AssetFilter,
    sort: SortOrder,
): List<FileEntry> {
    val result =
        entries.filter { entry ->
            entry.name.contains(query.trim(), ignoreCase = true) &&
                when (filter) {
                    AssetFilter.All -> true
                    AssetFilter.Models -> entry.kind == AssetKind.Model
                    AssetFilter.Textures -> entry.kind in listOf(AssetKind.Texture, AssetKind.Image)
                    AssetFilter.Maps -> entry.kind == AssetKind.Map
                    AssetFilter.Collisions -> entry.kind == AssetKind.Collision
                    AssetFilter.Audio -> entry.kind == AssetKind.Audio
                }
        }
    val base = compareByDescending<FileEntry> { it.isDirectory }
    return result.sortedWith(
        when (sort) {
            SortOrder.Name -> base.thenBy { it.name.lowercase(Locale.ROOT) }
            SortOrder.Size -> base.thenByDescending { it.size }.thenBy { it.name }
            SortOrder.Type -> base.thenBy { it.extension }.thenBy { it.name }
        }
    )
}

fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble().coerceAtLeast(0.0)
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val number =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = if (unit == 0) 0 else 1
        }
    return "${number.format(value)} ${units[unit]}"
}
