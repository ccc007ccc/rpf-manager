package com.gtav.rpfmanager

import android.media.MediaPlayer
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.gtav.rpfmanager.data.model.ResourcePreview
import com.gtav.rpfmanager.data.repository.RpfRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RepositoryTest {
    @Test
    fun editSaveNestedAndOriginalProtection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = RpfRepository(context)
        val imported = File(context.cacheDir, "edit-import.txt")
        val output = File(context.cacheDir, "edit-saved.rpf")
        try {
            val demo = repository.demo()
            val original = requireNotNull(demo.file).readBytes()
            repository.edit(demo, "mkdir", "新建")
            imported.writeText("\uFEFForiginal\r\n世界\r\n")
            repository.importFile(demo, "新建", Uri.fromFile(imported))
            imported.writeText("External changes must not alter staged content")
            val entry = repository.list(demo, "新建").entries.single()
            assertEquals("\uFEFForiginal\r\n世界\r\n", repository.readText(demo, entry))
            repository.editText(demo, entry, "\uFEFFedited\r\n中文\r\n")
            repository.edit(demo, "rename", "新建", "资源")
            repository.edit(demo, "delete", "rover.ydr")
            repository.edit(demo, "undo")
            repository.edit(demo, "redo")
            repository.edit(demo, "undo")
            val nestedEntry = repository.list(demo).entries.first { it.name == "nested.rpf" }
            val child = repository.nested(demo, nestedEntry)
            repository.edit(child, "rename", "readme.txt", "已修改.txt")
            repository.attach(child)
            repository.release(child)
            var rejected = false
            try {
                repository.save(demo, Uri.fromFile(demo.file))
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue("Saving over the mapped source must fail before truncation", rejected)
            assertArrayEquals(original, demo.file.readBytes())
            repository.save(demo, Uri.fromFile(output))
            assertFalse(repository.info(demo)!!.dirty)
            val saved = repository.openUri(Uri.fromFile(output)).first
            val text = repository.list(saved, "资源").entries.single()
            assertEquals("\uFEFFedited\r\n中文\r\n", repository.readText(saved, text))
            val childSaved =
                repository.nested(
                    saved,
                    repository.list(saved).entries.first { it.name == "nested.rpf" },
                )
            assertEquals("已修改.txt", repository.list(childSaved).entries.single().name)
            val model = repository.list(saved).entries.first { it.name == "rover.ydr" }
            assertTrue(repository.preview(saved, model) is ResourcePreview.Mesh)
            val export = repository.prepareExport(saved, model, null, null)
            rejected = false
            try {
                repository.export(export, Uri.fromFile(demo.file))
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue("Export must also protect original descriptors", rejected)
            assertArrayEquals(original, demo.file.readBytes())
        } finally {
            repository.close()
            imported.delete()
            output.delete()
        }
    }

    @Test
    fun pcmAndAdpcmPlayAndSeek() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = RpfRepository(instrumentation.targetContext)
        try {
            val demo = repository.demo()
            val entry = repository.list(demo).entries.first { it.name == "tones.awc" }
            val preview = repository.preview(demo, entry) as ResourcePreview.Audio
            assertEquals(2, preview.tracks.size)
            for (track in preview.tracks) {
                val file = repository.audio(demo, entry, track.index)
                val ready = CountDownLatch(1)
                val seekDone = CountDownLatch(1)
                var player: MediaPlayer? = null
                var failure: String? = null
                try {
                    instrumentation.runOnMainSync {
                        player =
                            MediaPlayer().apply {
                                setVolume(0f, 0f)
                                setOnPreparedListener { ready.countDown() }
                                setOnSeekCompleteListener { seekDone.countDown() }
                                setOnErrorListener { _, a, b ->
                                    failure = "$a/$b"
                                    ready.countDown()
                                    true
                                }
                                setDataSource(file.path)
                                prepareAsync()
                            }
                    }
                    assertTrue("Audio must prepare", ready.await(10, TimeUnit.SECONDS))
                    assertNull(failure)
                    instrumentation.runOnMainSync {
                        assertTrue(player!!.duration in 1950..2050)
                        player!!.start()
                    }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                    var progressed = false
                    while (!progressed && android.os.SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(50)
                        instrumentation.runOnMainSync { progressed = player!!.currentPosition > 0 }
                    }
                    assertTrue("PCM playback must advance after the output route starts",progressed)
                    instrumentation.runOnMainSync {
                        player!!.pause()
                        player!!.seekTo(1000L, MediaPlayer.SEEK_CLOSEST)
                    }
                    assertTrue("Seek must complete",seekDone.await(5,TimeUnit.SECONDS))
                    instrumentation.runOnMainSync {
                        assertTrue(player!!.currentPosition in 900..1100)
                    }
                } finally {
                    instrumentation.runOnMainSync { player?.release() }
                    repository.forgetFile(file)
                }
            }
        } finally {
            repository.close()
        }
    }

    @Test
    fun standaloneResourceAndExportRoundTrip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "standalone-test.YDR")
        val destination = File(context.cacheDir, "export-roundtrip.ydr")
        val invalid = File(context.cacheDir, "damaged-test.rpf")
        val repository = RpfRepository(context)
        try {
            val demo = repository.demo()
            val location = repository.list(demo)
            val model = location.entries.first { it.name == "rover.ydr" }
            RpfCore.extractFile(demo.handle, model.path, source.path)
            val cachedBefore =
                File(context.cacheDir, "workspaces").listFiles()!!.sumOf { it.length() }
            val (standalone, recent) = repository.openUri(Uri.fromFile(source))
            assertEquals("standalone-test.YDR", recent.name)
            assertFalse(standalone.isArchive)
            assertNull(standalone.file)
            assertEquals(
                cachedBefore,
                File(context.cacheDir, "workspaces").listFiles()!!.sumOf { it.length() },
            )
            val entry = repository.list(standalone).entries.single()
            val preview = repository.preview(standalone, entry)
            assertTrue(preview is ResourcePreview.Mesh)
            assertEquals(11, (preview as ResourcePreview.Mesh).data.parts)
            val export = repository.prepareExport(standalone, entry, null, null)
            repository.export(export, Uri.fromFile(destination))
            assertArrayEquals(source.readBytes(), destination.readBytes())
            invalid.writeBytes(ByteArray(64))
            var rejected = false
            try {
                repository.openUri(Uri.fromFile(invalid))
            } catch (_: IOException) {
                rejected = true
            }
            assertTrue("Damaged archive must report a failure", rejected)
            assertTrue(repository.list(demo).entries.any { it.name == "materials.ytd" })
        } finally {
            repository.close()
            source.delete()
            destination.delete()
            invalid.delete()
        }
    }
}
