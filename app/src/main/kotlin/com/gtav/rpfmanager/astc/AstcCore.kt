package com.gtav.rpfmanager.astc

import com.gtav.rpfmanager.RpfCore

/** Embedded native codec; descriptors remain owned by the caller. */
object AstcCore {
    fun requireAvailable() = check(RpfCore.available) { "Native resource library is unavailable" }
    external fun beginBatch()
    external fun endBatch()
    external fun convertArchive(
        handle: Long, output: Int, scratch: String, options: String,
    ): String
    external fun progress(handle: Long): String
}
