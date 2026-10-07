package com.gtav.rpfmanager

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.gtav.rpfmanager.astc.*
import com.gtav.rpfmanager.data.repository.RpfRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@android.annotation.TargetApi(29)
@SdkSuppress(minSdkVersion = 29)
class ConversionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var storage: AstcStorage
    private lateinit var root: AstcDocument
    private val reports = mutableListOf<String>()

    private fun tree(document: AstcDocument): String = document.id
    @Before fun setUp() {
        assertTrue("Shared native library must load on ARM64", RpfCore.available)
        storage = AstcStorage(context)
        val authority = instrumentation.context.packageName + ".documents"
        val command = "am start -W -n ${instrumentation.context.packageName}/com.gtav.rpfmanager.GrantTestDocumentsActivity"
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        val parent = storage.root(DocumentsContract.buildTreeDocumentUri(authority, "root").toString())
        root = storage.create(parent.id, "test-${UUID.randomUUID()}", true)
    }
    @After fun tearDown() {
        if (::root.isInitialized) storage.delete(root)
        reports.forEach { File(context.filesDir, "astc-reports/$it.json").delete() }
    }
    private fun read(document: AstcDocument) = requireNotNull(context.contentResolver.openInputStream(Uri.parse(document.id))).use { it.readBytes() }
    private fun archive(parent: AstcDocument, name: String, files: Map<String, ByteArray>): AstcDocument {
        val source = File(context.cacheDir, "conversion-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            files.forEach { (path, bytes) -> File(source, path).also { it.parentFile?.mkdirs(); it.writeBytes(bytes) } }
            return storage.create(parent.id, name).also { output ->
                storage.open(output, true).use { RpfCore.packDirectory(source.path, it.fd) }
            }
        } finally { source.deleteRecursively() }
    }
    private fun request(source: AstcDocument, outputName: String) = AstcRequest(
        source = tree(source), outputParent = tree(root), outputName = outputName,
        block = "8x8", quality = "fast", workers = 1,
    )
    private fun run(request: AstcRequest): AstcState = runBlocking {
        reports += request.id
        AstcRunner(context, request, AtomicBoolean(false), {}, {}).run()
    }
    private fun <T> withArchive(document: AstcDocument, action: (Long) -> T): T = storage.open(document).use {
        val handle = RpfCore.openArchiveFd(it.fd, document.name)
        try { action(handle) } finally { RpfCore.closeArchive(handle) }
    }

    @Test fun directPreviewUsesBcAlphaAndDeepDictionaryRules() {
        val local = File(context.cacheDir, "bc-preview-${UUID.randomUUID()}.ytd")
        try {
            for ((format, expected) in listOf(0x31545844 to 0x00000000, 0x33545844 to 0x88555555.toInt())) {
                local.writeBytes(texture(format = format, nullSlot = true))
                ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    val handle = RpfCore.openResourceFd(fd.fd)
                    try {
                        val list = JSONArray(RpfCore.listTextures(handle, "preview.ytd"))
                        assertEquals(1, list.length())
                        assertTrue(list.getJSONObject(0).getBoolean("srgb"))
                        assertEquals(1, list.getJSONObject(0).getInt("layers"))
                        val png = RpfCore.texturePng(handle, "preview.ytd", 0)
                        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inPremultiplied = false })
                        assertEquals(expected, bitmap.getPixel(0, 0))
                        bitmap.recycle()
                    } finally { RpfCore.closeArchive(handle) }
                }
            }
            local.writeBytes(texture(nullSlot = true, badSlot = true))
            ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val handle = RpfCore.openResourceFd(fd.fd)
                try {
                    try { RpfCore.listTextures(handle, "broken.ytd"); fail("A broken member must not disappear from the dictionary") }
                    catch (_: java.io.IOException) { }
                } finally { RpfCore.closeArchive(handle) }
            }
        } finally { local.delete() }
    }

    @Test fun scanConvertAndReopenNestedTexturesUseTheSameCore() {
        val source = storage.create(root.id, "source", true)
        val original = archive(source, "assets.rpf", mapOf("nested.rpf/color.ytd" to texture(format = 0x33545844), "keep.txt" to "unchanged".toByteArray()))
        val originalBytes = read(original)
        val scan = run(request(source, "scanned").copy(scanOnly = true))
        assertEquals(scan.detail, AstcPhase.Complete, scan.phase)
        assertEquals(1L, scan.textures)
        assertEquals(14, scan.estimatedByBlock.size)
        assertEquals(64L, scan.estimateSourceBytes)
        assertEquals(16L, scan.estimatedByBlock["8x8"])
        assertNull(storage.find(root.id, "scanned"))
        val converted = run(request(source, "converted").copy(effort = 25.0, channelWeights = listOf(1.0, 2.0, 1.0, 1.0)))
        assertEquals(converted.detail, AstcPhase.Complete, converted.phase)
        assertEquals(1L, converted.converted)
        assertEquals(0L, converted.remainingBc)
        assertEquals(16L, converted.astcBytes)
        assertArrayEquals(originalBytes, read(original))
        val target = storage.find(converted.output, "assets.rpf")!!
        withArchive(target) { handle ->
            assertEquals("unchanged", String(RpfCore.getFileData(handle, "keep.txt")))
            val nested = RpfCore.openNestedArchive(handle, "nested.rpf")
            try {
                val list = JSONArray(RpfCore.listTextures(nested, "color.ytd"))
                assertEquals("ASTC 8x8", list.getJSONObject(0).getString("format"))
                val png = RpfCore.texturePng(nested, "color.ytd", 0)
                assertNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
            } finally { RpfCore.closeArchive(nested) }
            val report = JSONObject(RpfCore.scanArchive(handle))
            assertEquals(0, report.getJSONObject("counts").optInt("failed"))
        }
    }

    @Test fun retainedBcIsIncompleteAndStrictReplacementProtectsTheOriginal() {
        val source = storage.create(root.id, "source", true)
        val original = archive(source, "dynamic.rpf", mapOf("dynamic.ytd" to texture(name = "script_rt_fixture")))
        val bytes = read(original)
        val partial = run(request(source, "partial").copy(requireNoBc = false, keepGoing = true))
        assertEquals(partial.detail, AstcPhase.Incomplete, partial.phase)
        assertEquals(1L, partial.remainingBc)
        assertEquals(0, partial.errors)
        val strict = run(request(source, "unused").copy(inPlace = true, requireNoBc = true))
        assertEquals(AstcPhase.Failed, strict.phase)
        assertArrayEquals(bytes, read(original))
        assertTrue(storage.list(source.id).none { it.name.contains("astc-tmp") || it.name.contains("astc-backup") })
    }

    @Test fun pageRepairAndSafExtractionPreserveResourcesAndExistingDirectories() = runBlocking {
        val source = storage.create(root.id, "source", true)
        val resource = texture(badPages = true)
        val original = archive(source, "broken.rpf", mapOf("color.ytd" to resource))
        val repaired = run(request(source, "fixed").copy(repairPages = true))
        assertEquals(repaired.detail, AstcPhase.Complete, repaired.phase)
        assertEquals(1L, repaired.repairedResources)
        assertEquals(0L, repaired.converted)
        assertEquals(1L, repaired.remainingBc)
        val output = storage.find(repaired.output, "broken.rpf")!!
        withArchive(original) { before -> withArchive(output) { after ->
            assertArrayEquals(RpfCore.texturePng(before, "color.ytd", 0), RpfCore.texturePng(after, "color.ytd", 0))
        } }

        val repository = RpfRepository(context)
        var staged: File? = null
        val packed = File(context.cacheDir, "saf-packed-${UUID.randomUUID()}.rpf")
        try {
            val session = repository.openUri(Uri.parse(output.id)).first
            val collision = storage.create(root.id, "broken", true)
            val sentinel = storage.create(collision.id, "keep.txt")
            storage.writeText(sentinel, "must survive")
            try { repository.extractArchive(session, Uri.parse(tree(root))); fail("Existing directory must be preserved") }
            catch (_: IllegalStateException) { }
            assertEquals("must survive", String(read(sentinel)))
            val parent = storage.create(root.id, "extract-here", true)
            assertEquals(1, repository.extractArchive(session, Uri.parse(tree(parent))))
            val extracted = storage.find(parent.id, "broken")!!
            staged = repository.stageDirectory(Uri.parse(tree(extracted)))
            repository.packDirectory(staged!!, Uri.fromFile(packed))
            val reopened = repository.openUri(Uri.fromFile(packed)).first
            val entries = repository.list(reopened).entries
            assertEquals("color.ytd", entries.single().name)
            assertNotNull(repository.preview(reopened, entries.single()))
            repository.forgetFile(staged!!)
            assertFalse(staged!!.exists())
        } finally { staged?.let(repository::forgetFile); repository.close(); packed.delete() }
    }

    @Test fun foregroundConversionSurvivesActivityRecreation() {
        val source = storage.create(root.id, "source", true)
        archive(source, "assets.rpf", mapOf("color.ytd" to texture(format = 0x33545844)))
        val request = request(source, "foreground")
        reports += request.id
        val intent = Intent(context, MainActivity::class.java).putExtra(AstcService.OPEN_TOOLS, true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity {
                ContextCompat.startForegroundService(it, Intent(it, AstcService::class.java).putExtra(AstcService.REQUEST, request.json()))
            }
            scenario.recreate()
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val current = AstcJobs.state.value
                if (current.request?.id == request.id && !current.running) break
                SystemClock.sleep(50)
            }
            val state = AstcJobs.state.value
            assertEquals(request.id, state.request?.id)
            assertEquals(state.detail, AstcPhase.Complete, state.phase)
            instrumentation.waitForIdleSync()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val output = File(context.getExternalFilesDir(null), "ui-review/astc-conversion.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
    }

    private fun texture(format: Int = 0x31545844, name: String = "fixture", nullSlot: Boolean = false,
                        badSlot: Boolean = false, badPages: Boolean = false): ByteArray {
        val size = if (badPages) 8704 else 8192
        val system = ByteArray(size); val graphics = ByteArray(size)
        val sys = ByteBuffer.wrap(system).order(ByteOrder.LITTLE_ENDIAN)
        sys.putLong(8, 0x50000400); sys.putLong(0x20, 0x50000100); sys.putLong(0x30, 0x50000180)
        val count = if (nullSlot) 2 else 1
        for (offset in listOf(0x28, 0x2a, 0x38, 0x3a)) sys.putShort(offset, count.toShort())
        sys.putLong(0x180, if (nullSlot) (if (badSlot) 0x50001ff8 else 0) else 0x50000200)
        if (nullSlot) sys.putLong(0x188, 0x50000200)
        sys.putLong(0x228, 0x50000300)
        name.toByteArray().copyInto(system, 0x300)
        sys.putShort(0x250, 8); sys.putShort(0x252, 8); sys.putShort(0x254, 1)
        sys.putShort(0x256, 4); sys.putInt(0x258, format)
        system[0x25d] = 1; system[0x25f] = 1
        sys.putLong(0x270, 0x60000000)
        system[0x408] = 1; system[0x409] = 1
        val gpu = ByteBuffer.wrap(graphics).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0..3) {
            val base = index * if (format == 0x33545844) 16 else 8
            val colors = if (format == 0x33545844) base + 8 else base
            if (format == 0x33545844) for (i in base until base + 8) graphics[i] = 0x88.toByte()
            gpu.putShort(colors, 0); gpu.putShort(colors + 2, 0xffff.toShort())
            gpu.putInt(colors + 4, if (format == 0x33545844) 0xaaaaaaaa.toInt() else -1)
        }
        val stream = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x37435352).putInt(13).putInt(if (badPages) 0x08020000 else 0x20000)
            .putInt(if (badPages) 0xd8020000.toInt() else 0xd0020000.toInt())
        stream.write(header.array())
        DeflaterOutputStream(stream, Deflater(1, true)).use { it.write(system); it.write(graphics) }
        return stream.toByteArray()
    }
}
