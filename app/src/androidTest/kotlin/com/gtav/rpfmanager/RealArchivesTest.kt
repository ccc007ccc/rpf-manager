package com.gtav.rpfmanager

import android.net.Uri
import android.util.Log
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gtav.rpfmanager.data.model.MeshData
import com.gtav.rpfmanager.data.repository.RpfRepository
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in acceptance against the two user-selected documents; originals stay read-only. */
class RealArchivesTest {
    @Test
    fun scanUserArchives() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realArchives") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = RpfRepository(context)
        val foreground = ActivityScenario.launch(MainActivity::class.java)
        foreground.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val reports = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
        val candidates = repository.recents().associate { it.name.lowercase() to Uri.parse(it.uri) }
        try {
            for (name in listOf("common.rpf", "x64c.rpf")) {
                val uri =
                    candidates[name]
                        ?: Uri.parse(
                            "content://com.android.externalstorage.documents/document/primary%3AGTAV%2F$name"
                        )
                val cache = File(context.cacheDir, "workspaces")
                val before = cache.listFiles().orEmpty().sumOf { it.length() }
                val session = repository.openUri(uri).first
                assertNull(session.file)
                assertEquals(before, cache.listFiles().orEmpty().sumOf { it.length() })
                val report = coroutineScope {
                    val heartbeat = launch(Dispatchers.IO) {
                        val progress = android.util.AtomicFile(File(reports,"$name.progress.json"))
                        while (isActive) {
                            delay(500)
                            val p = JSONObject(RpfCore.scanProgress(session.handle))
                                .put("native_heap",android.os.Debug.getNativeHeapAllocatedSize())
                            val stream = progress.startWrite()
                            try { stream.write(p.toString().toByteArray()); progress.finishWrite(stream) }
                            catch(e: Exception) { progress.failWrite(stream); throw e }
                        }
                    }
                    try { repository.scan(session) } finally { heartbeat.cancelAndJoin() }
                }
                File(reports, "$name.coverage.json").writeText(report.json)
                Log.i(
                    "RpfValidation",
                    JSONObject()
                        .put("name", name)
                        .put("counts", JSONObject(report.json).getJSONObject("counts"))
                        .toString(),
                )
                assertTrue("Scan must reach the end without cancellation or limits", report.finished)
                assertEquals(report.unopenedArchives == 0, report.complete)
                assertEquals(
                    report.rows.count { it.extension == "rpf" && it.status != "container" },
                    report.unopenedArchives,
                )
                assertTrue(report.counts.total > 0)
                assertEquals(report.rows.size, report.counts.total)
                for (row in report.rows.filter {
                    it.extension == "ymap" && it.detail.startsWith("Map metadata parsed; no entities")
                }) {
                    val segments = row.path.split("!/")
                    var current = session
                    val children = mutableListOf<com.gtav.rpfmanager.data.model.Session>()
                    try {
                        for (path in segments.dropLast(1)) {
                            val directory = path.substringBeforeLast('/', "")
                            val entry = repository.list(current, directory).entries.single { it.path == path }
                            current = repository.nested(current, entry)
                            children += current
                        }
                        val mesh = MeshData.decode(RpfCore.previewMesh(current.handle, segments.last()))
                        assertTrue("Empty map must preview without a parser or GPU error: ${row.path}", mesh.isEmptyMap)
                    } finally {
                        children.asReversed().forEach(repository::release)
                    }
                }
                if (name == "common.rpf") {
                    val root = repository.list(session)
                    val text = root.entries.firstOrNull { it.name.endsWith(".xml", true) }
                    repository.edit(session, "mkdir", "rpf_manager_validation")
                    val output = File(context.cacheDir, "common-validation.rpf")
                    try {
                        repository.save(session, Uri.fromFile(output))
                        val reopened = repository.openUri(Uri.fromFile(output)).first
                        assertTrue(
                            repository.list(reopened).entries.any {
                                it.name == "rpf_manager_validation"
                            }
                        )
                        if (text != null)
                            assertArrayEquals(
                                RpfCore.getFileData(session.handle, text.path),
                                RpfCore.getFileData(reopened.handle, text.path),
                            )
                        val savedReport = repository.scan(reopened)
                        File(reports, "common.saved.coverage.json").writeText(savedReport.json)
                        assertEquals(report.counts, savedReport.counts)
                        repository.release(reopened)
                    } finally {
                        output.delete()
                    }
                    val outer = repository.demo()
                    val renamedOutput = File(context.cacheDir,"nested-ng-rename.rpf")
                    try {
                        repository.importFile(outer,"",uri)
                        repository.edit(outer,"rename","common.rpf","renamed.rpf")
                        repository.edit(outer,"undo")
                        assertTrue(repository.list(outer).entries.any { it.name == "common.rpf" })
                        repository.edit(outer,"redo")
                        repository.save(outer,Uri.fromFile(renamedOutput))
                        val reopened = repository.openUri(Uri.fromFile(renamedOutput)).first
                        val renamed = repository.nested(reopened,repository.list(reopened).entries.first { it.name == "renamed.rpf" })
                        assertEquals(report.counts,repository.scan(renamed).counts)
                        repository.release(renamed); repository.release(reopened)
                        val target = repository.list(outer).entries.first { it.name == "nested.rpf" }
                        repository.importFile(outer,"",uri,target)
                        val replaced = repository.nested(outer,repository.list(outer).entries.first { it.name == "nested.rpf" })
                        assertEquals(report.counts,repository.scan(replaced).counts)
                        repository.release(replaced)
                    } finally { repository.release(outer); renamedOutput.delete() }
                }
                repository.release(session)
            }
        } finally {
            repository.close()
            foreground.close()
        }
    }
}
