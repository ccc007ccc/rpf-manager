package com.gtav.rpfmanager

import android.util.Log

/** Only implemented, exception-reporting JNI entry points are exposed. */
object RpfCore {
    val available = runCatching { System.loadLibrary("rpf_jni") }
        .onFailure { Log.e("RpfCore", "Failed to load rpf_jni", it) }
        .isSuccess

    external fun openArchive(path: String, displayName: String): Long

    external fun openArchiveFd(fd: Int, displayName: String): Long

    external fun openResourceFd(fd: Int): Long

    external fun openNestedArchive(handle: Long, path: String): Long

    external fun closeArchive(handle: Long)

    external fun getArchiveInfo(handle: Long): String

    external fun listFiles(handle: Long, path: String): String

    external fun extractFile(handle: Long, path: String, output: String)

    external fun extractArchiveToDir(handle: Long, output: String): Long

    external fun packDirectory(input: String, outputFd: Int): Long

    external fun extractFileFd(handle: Long, path: String, outputFd: Int, cancellationHandle: Long)

    external fun beginOperation(): Long

    external fun finishOperation(handle: Long)

    external fun packDirectoryCancellable(input: String, outputFd: Int, operation: Long): Long

    external fun getFileData(handle: Long, path: String): ByteArray

    external fun listTextures(handle: Long, path: String): String

    external fun texturePng(handle: Long, path: String, index: Int): ByteArray

    external fun previewMesh(handle: Long, path: String): ByteArray

    external fun loadKeys(path: String)

    external fun clearKeys()

    external fun editArchive(handle: Long, command: String)

    external fun editFileFd(handle: Long, path: String, fd: Int, replace: Boolean, sourceName: String)

    external fun attachNestedArchive(parent: Long, path: String, child: Long)

    external fun saveArchiveFd(handle: Long, fd: Int)

    /** Serialize a publication candidate without marking the current workspace saved. */
    external fun stageArchiveFd(handle: Long, fd: Int)

    external fun cancelOperation(handle: Long)

    external fun resetCancellation(handle: Long)

    external fun copyOutputFd(handle: Long, source: Int, target: Int)

    external fun listAudio(handle: Long, path: String): String

    external fun audioWav(handle: Long, path: String, index: Int, output: String)

    external fun scanArchive(handle: Long): String

    external fun scanProgress(handle: Long): String

    external fun textureSources(handle: Long): String
}
