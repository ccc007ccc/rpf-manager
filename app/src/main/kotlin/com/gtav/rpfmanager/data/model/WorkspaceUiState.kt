package com.gtav.rpfmanager.data.model

enum class WorkspacePane {
    Archive,
    Storage,
}

enum class BatchKind {
    Import,
    Replace,
    TargetedReplace,
    Export,
    DeleteArchive,
    DeleteStorage,
}

data class BatchConfirmation(
    val kind: BatchKind,
    val source: String,
    val destination: String,
    val count: Int,
    val conflicts: Int = 0,
    val additions: Int = 0,
    val skipped: Int = 0,
    val paths: List<String> = emptyList(),
    val issues: List<BatchFailure> = emptyList(),
)

data class StorageEdit(
    val operation: String,
    val entry: StorageEntry? = null,
    val text: String = "",
)
