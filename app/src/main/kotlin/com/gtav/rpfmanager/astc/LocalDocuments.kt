package com.gtav.rpfmanager.astc

import android.net.Uri
import java.io.File
import java.nio.file.Files

/** Reject traversal and symbolic links before local-document operations. */
internal fun checkedLocalFile(uri: Uri, rootUri: String? = null, mustExist: Boolean = true): File {
    require(uri.scheme == "file" && uri.authority.isNullOrEmpty()) { "Invalid local file identity" }
    val path = requireNotNull(uri.path) { "Missing file path" }
    val rootPath = rootUri?.takeIf { it.isNotBlank() }?.let { value ->
        val root = Uri.parse(value)
        require(root.scheme == "file" && root.authority.isNullOrEmpty()) { "Invalid local storage root" }
        requireNotNull(root.path)
    }
    return checkedLocalPath(path, rootPath, mustExist)
}

internal fun checkedLocalPath(path: String, rootPath: String? = null, mustExist: Boolean = true): File {
    require(path.startsWith('/') && path.split('/').none { it == "." || it == ".." }) { "File path is outside the storage root" }
    val file = File(path).absoluteFile
    require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file) { "Symbolic links are not followed" }
    if (!rootPath.isNullOrBlank()) {
        val root = checkedLocalPath(rootPath)
        require(file == root || file.path.startsWith(root.path.trimEnd('/') + "/")) { "File path is outside the storage root" }
    }
    if (mustExist) require(file.exists()) { "File is no longer available" }
    return file
}
