package com.gtav.rpfmanager.astc

import android.content.Context
import android.os.ParcelFileDescriptor
import com.gtav.rpfmanager.RpfCore
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class AstcStopped : IOException("Conversion stopped")

/** Traverses SAF once, then streams one top-level RPF at a time through the
 * native converter. No game file is opened with a truncating write mode. */
class AstcRunner(
    private val context: Context,
    private val request: AstcRequest,
    private val cancelled: AtomicBoolean,
    private val handleChanged: (Long) -> Unit,
    private val onState: (AstcState) -> Unit,
) {
    private val storage = AstcStorage(context)
    private val journal = AstcFileJournal(File(context.noBackupFilesDir, "astc"))
    private val publisher = AstcPublisher(storage, journal)
    private val archives = JSONArray()
    private val failures = JSONArray()
    private val retained = JSONObject()
    private var current = AstcState(AstcPhase.Preparing, request)
    private var textures = 0L
    private var converted = 0L
    private var sourceBytes = 0L
    private var astcBytes = 0L
    private var errors = 0
    private var complete = true
    private var remainingBc = 0L
    private var blockedBc = 0L
    private var repairedResources = 0L
    private var estimateSourceBytes = 0L
    private val estimatedByBlock = mutableMapOf<String, Long>()
    private var estimateComplete = true
    private var outputRoot: AstcDocument? = null
    private val scratch = File(context.cacheDir, "astc/${request.id}")
    private data class Entry(val node: AstcDocument, val path: String, val depth: Int)

    @Synchronized private fun update(transform: (AstcState) -> AstcState) {
        current = transform(current)
        if (cancelled.get() && current.running) current = current.copy(phase = AstcPhase.Stopping)
        onState(current)
    }
    private fun check() { if (cancelled.get()) throw AstcStopped() }

    suspend fun run(): AstcState = withContext(Dispatchers.IO) {
        var batch = false
        var terminal = AstcPhase.Complete
        var detail = ""
        try {
            request.validate()
            AstcCore.requireAvailable()
            val keys = File(context.noBackupFilesDir, "keys")
            if (File(keys, "gtav_aes_key.dat").exists()) RpfCore.loadKeys(keys.path)
            AstcCore.beginBatch(); batch = true
            publisher.recover()
            val source = storage.root(request.source)
            val entries = scan(source)
            val rpfs = entries.filter { !it.node.directory && it.node.name.endsWith(".rpf", true) }
            require(rpfs.isNotEmpty()) { "No RPF files found in the selected directory" }
            update { it.copy(total = rpfs.size) }
            val directories = mutableMapOf<String, AstcDocument>()
            if (!request.scanOnly && !request.inPlace) {
                val parent = storage.root(request.outputParent)
                storage.requireSeparate(source, parent, entries.map { it.node })
                outputRoot = storage.create(parent.id, request.outputName, true)
                directories[source.id] = outputRoot!!
                update { it.copy(output = outputRoot!!.id) }
                for (entry in entries.filter { it.node.directory }.sortedBy { it.depth }) {
                    check()
                    directories[entry.node.id] = storage.create(directories.getValue(entry.node.parent).id, entry.node.name, true)
                }
            } else if (request.inPlace && !request.scanOnly) {
                outputRoot = source
                update { it.copy(output = source.id) }
            }
            scratch.mkdirs()
            for ((index, entry) in rpfs.withIndex()) {
                check()
                update { it.copy(phase = if (request.scanOnly) AstcPhase.Scanning else AstcPhase.Converting,
                    archive = entry.path, resource = "", completed = index) }
                val targetParent = if (request.inPlace) entry.node.parent else directories[entry.node.parent]?.id
                process(entry, targetParent)
                update { it.copy(completed = index + 1, textures = textures, converted = converted,
                    sourceBytes = sourceBytes, astcBytes = astcBytes, errors = errors) }
            }
            if (!request.scanOnly && !request.inPlace) {
                for (entry in entries.filter { !it.node.directory && !it.node.name.endsWith(".rpf", true) }) {
                    check()
                    update { it.copy(phase = AstcPhase.Writing, archive = entry.path, resource = "") }
                    val target = storage.create(directories.getValue(entry.node.parent).id, entry.node.name)
                    try { storage.copy(entry.node, target, ::check) }
                    catch (error: Exception) { runCatching { storage.delete(target) }; throw error }
                }
            }
            check()
            if (errors > 0 || !complete) terminal = AstcPhase.Incomplete
        } catch (error: Exception) {
            terminal = if (cancelled.get() || error is AstcStopped) AstcPhase.Cancelled else AstcPhase.Failed
            detail = error.message ?: error.javaClass.simpleName
            failures.put(JSONObject().put("path", current.archive).put("message", detail))
            if (terminal == AstcPhase.Failed) errors++
        } finally {
            handleChanged(0)
            if (batch) AstcCore.endBatch()
            // Only this job's private scratch directory is removed.
            scratch.deleteRecursively()
        }
        val report = JSONObject().put("schema", 1).put("request", JSONObject(request.json()))
            .put("finished", terminal in listOf(AstcPhase.Complete, AstcPhase.Incomplete))
            .put("complete", terminal == AstcPhase.Complete).put("status", terminal.name)
            .put("textures", textures).put("converted", converted).put("errors", errors)
            .put("sourceTextureBytes", sourceBytes).put("astcTextureBytes", astcBytes)
            .put("retained", retained).put("archives", archives).put("failures", failures)
            .put("remainingBc", remainingBc).put("blockedBc", blockedBc).put("repairedResources", repairedResources)
            .put("sizeEstimate", JSONObject().put("sourceBytes", estimateSourceBytes)
                .put("byBlock", JSONObject(estimatedByBlock)).put("complete", estimateComplete && errors == 0 && terminal != AstcPhase.Failed && terminal != AstcPhase.Cancelled))
            .put("output", outputRoot?.id ?: "").toString(2)
        val reportFile = File(context.filesDir, "astc-reports/${request.id}.json")
        reportFile.parentFile?.mkdirs()
        try { reportFile.writeText(report) }
        catch (error: Exception) { detail = listOf(detail, error.message).filterNotNull().filter { it.isNotEmpty() }.joinToString("\n") }
        var reportUri = ""
        if (!request.scanOnly && outputRoot != null) {
            try {
                val document = storage.create(outputRoot!!.id, "rpf-astc-report-${request.id}.json")
                storage.writeText(document, report)
                reportUri = document.id
            } catch (error: Exception) {
                detail = listOf(detail, "Report is stored in the app: ${error.message}").filter { it.isNotEmpty() }.joinToString("\n")
            }
        }
        update { it.copy(phase = terminal, textures = textures, converted = converted, errors = errors,
            retained = retained.keys().asSequence().sumOf { key -> retained.optLong(key) },
            sourceBytes = sourceBytes, astcBytes = astcBytes, report = reportUri.ifBlank { reportFile.path }, detail = detail,
            remainingBc = remainingBc, blockedBc = blockedBc, repairedResources = repairedResources,
            estimateSourceBytes = estimateSourceBytes, estimatedByBlock = estimatedByBlock.toMap(),
            estimateComplete = estimateComplete && errors == 0 && terminal in listOf(AstcPhase.Complete, AstcPhase.Incomplete),
            resource = "") }
        current
    }

    private fun scan(root: AstcDocument): List<Entry> {
        val result = mutableListOf<Entry>()
        val seen = mutableSetOf(root.id)
        val pending = ArrayDeque<Entry>()
        pending.add(Entry(root, "", 0))
        while (pending.isNotEmpty()) {
            check()
            val parent = pending.removeFirst()
            require(parent.depth < 64) { "Directory nesting limit reached" }
            update { it.copy(archive = parent.path) }
            for (node in storage.list(parent.node.id)) {
                check()
                require(seen.add(node.id)) { "Repeated document identity in source tree" }
                require(result.size < 200_000) { "Directory entry limit reached" }
                val entry = Entry(node, if (parent.path.isBlank()) node.name else "${parent.path}/${node.name}", parent.depth + 1)
                result += entry
                if (node.directory) pending.add(entry)
            }
        }
        return result
    }

    private suspend fun process(entry: Entry, parent: String?) {
        val source = storage.info(entry.node.id, entry.node.parent)
        if (request.inPlace && !request.scanOnly) require(source.rename && source.delete) { "Storage cannot safely replace ${source.name}; choose a new copy" }
        var temporary: AstcDocument? = null
        var nativeResult: JSONObject? = null
        var publishing = false
        try {
            if (!request.scanOnly) {
                temporary = storage.create(requireNotNull(parent), ".${source.name}.astc-tmp-${request.id}")
                journal.save(AstcReplacement(parent, source.name, ".${source.name}.astc-backup-${request.id}",
                    temporary!!.name, "", 0, "staging"))
            }
            storage.open(source).use { input ->
                val handle = RpfCore.openArchiveFd(input.fd, source.name)
                handleChanged(handle)
                try {
                    val output = temporary?.let { storage.open(it, true) }
                    try {
                        nativeResult = convert(handle, output)
                    } finally { output?.close() }
                } finally {
                    handleChanged(0)
                    RpfCore.closeArchive(handle)
                }
            }
            check()
            val result = requireNotNull(nativeResult)
            if (temporary != null && (result.optBoolean("changed") || !request.inPlace)) {
                val ready = storage.info(temporary!!.id, requireNotNull(parent))
                update { it.copy(phase = AstcPhase.Verifying) }
                val hash = storage.hash(ready, ::check)
                check()
                update { it.copy(phase = AstcPhase.Publishing) }
                publishing = true
                if (request.inPlace) publisher.replace(source, ready, hash, request.id)
                else {
                    val published = storage.rename(ready, source.name)
                    check(published.name == source.name && storage.hash(published) == hash) { "Published copy failed verification" }
                    journal.clear()
                }
                temporary = null
            }
            merge(entry.path, result)
        } catch (error: Exception) {
            if (cancelled.get() || !request.keepGoing || publishing || journal.load()?.phase?.let { it != "staging" } == true) throw error
            // Unreadable top-level archives can be retained too; a new copy is
            // verified byte-for-byte, while in-place mode leaves it untouched.
            if (!request.scanOnly && !request.inPlace) {
                val target = temporary ?: storage.create(requireNotNull(parent), ".${source.name}.astc-tmp-${request.id}")
                storage.copy(source, target, ::check)
                val ready = storage.info(target.id, target.parent)
                check(storage.hash(source, ::check) == storage.hash(ready, ::check)) { "Retained archive copy differs from source" }
                val published = storage.rename(ready, source.name)
                check(published.name == source.name) { "Provider changed the retained archive name" }
                journal.clear()
                temporary = null
            }
            errors++
            complete = false
            estimateComplete = false
            failures.put(JSONObject().put("path", entry.path).put("message", error.message ?: "Cannot convert archive"))
        } finally {
            val pending = journal.load()
            if (pending?.phase == "staging") {
                storage.find(pending.parent, pending.temporaryName)?.let(storage::delete)
                journal.clear()
            } else if (pending == null) temporary?.let { runCatching { storage.delete(it) } }
        }
    }

    private suspend fun convert(handle: Long, output: ParcelFileDescriptor?): JSONObject = coroutineScope {
        val baseTextures = textures; val baseConverted = converted
        val poll = launch(Dispatchers.IO) {
            while (isActive) {
                delay(250)
                runCatching { JSONObject(AstcCore.progress(handle)) }.getOrNull()?.let { value ->
                    val phase = when (value.optString("stage")) {
                        "writing" -> AstcPhase.Writing; "verifying" -> AstcPhase.Verifying
                        else -> if (request.scanOnly) AstcPhase.Scanning else AstcPhase.Converting
                    }
                    update { it.copy(phase = phase, resource = value.optString("path"),
                        textures = baseTextures + value.optLong("textures"), converted = baseConverted + value.optLong("converted")) }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { JSONObject(AstcCore.convertArchive(handle, output?.fd ?: -1, scratch.path,
                request.json())) }
        } finally { poll.cancelAndJoin() }
    }

    private fun merge(path: String, result: JSONObject) {
        archives.put(JSONObject().put("path", path).put("result", result))
        val summary = result.getJSONObject("summary")
        complete = complete && result.getBoolean("complete")
        textures += summary.optLong("textures"); converted += summary.optLong("converted")
        remainingBc += summary.optLong("remainingBc"); blockedBc += summary.optLong("blockedBc")
        repairedResources += summary.optLong("repairedResources")
        val estimate = summary.getJSONObject("sizeEstimate")
        estimateComplete = estimateComplete && estimate.getBoolean("complete")
        estimateSourceBytes += estimate.getLong("sourceBytes")
        val byBlock = estimate.getJSONObject("byBlock")
        for (key in byBlock.keys()) estimatedByBlock[key] = (estimatedByBlock[key] ?: 0) + byBlock.getLong(key)
        sourceBytes += summary.optLong("sourceTextureBytes"); astcBytes += summary.optLong("astcTextureBytes")
        val kept = summary.optJSONObject("retained") ?: JSONObject()
        for (key in kept.keys()) retained.put(key, retained.optLong(key) + kept.optLong(key))
        errors += summary.optJSONArray("errors")?.length() ?: 0
    }
}
