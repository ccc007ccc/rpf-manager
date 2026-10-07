package com.gtav.rpfmanager.astc

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

object AstcStateStore {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "astc/state.json").also { it.parentFile?.mkdirs() })
    @Synchronized fun save(context: Context, state: AstcState) {
        val target = file(context)
        val json = JSONObject().put("phase", state.phase.name).put("request", state.request?.json())
            .put("archive", state.archive).put("resource", state.resource)
            .put("completed", state.completed).put("total", state.total).put("textures", state.textures)
            .put("converted", state.converted).put("retained", state.retained).put("errors", state.errors)
            .put("sourceBytes", state.sourceBytes).put("astcBytes", state.astcBytes)
            .put("output", state.output).put("report", state.report).put("detail", state.detail)
            .put("remainingBc", state.remainingBc).put("blockedBc", state.blockedBc).put("repairedResources", state.repairedResources)
            .put("estimateSourceBytes", state.estimateSourceBytes).put("estimatedByBlock", JSONObject(state.estimatedByBlock))
            .put("estimateComplete", state.estimateComplete)
        val stream = target.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (error: Exception) { target.failWrite(stream); throw error }
    }
    @Synchronized fun restore(context: Context) {
        if (AstcJobs.state.value.phase != AstcPhase.Idle) return
        runCatching {
            val value = JSONObject(file(context).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            val phase = AstcPhase.valueOf(value.getString("phase"))
            AstcState(phase = phase, request = value.optString("request").takeIf { it.isNotBlank() }?.let(AstcRequest::parse),
                archive = value.optString("archive"), resource = value.optString("resource"),
                completed = value.optInt("completed"), total = value.optInt("total"), textures = value.optLong("textures"),
                converted = value.optLong("converted"), retained = value.optLong("retained"), errors = value.optInt("errors"),
                sourceBytes = value.optLong("sourceBytes"), astcBytes = value.optLong("astcBytes"),
                output = value.optString("output"), report = value.optString("report"), detail = value.optString("detail"),
                remainingBc = value.optLong("remainingBc"), blockedBc = value.optLong("blockedBc"),
                repairedResources = value.optLong("repairedResources"), estimateSourceBytes = value.optLong("estimateSourceBytes"),
                estimatedByBlock = value.optJSONObject("estimatedByBlock")?.let { blocks -> blocks.keys().asSequence().associateWith { blocks.getLong(it) } } ?: emptyMap(),
                estimateComplete = value.optBoolean("estimateComplete"))
        }.getOrNull()?.let { state ->
            AstcJobs.mutable.value = if (state.running) state.copy(phase = AstcPhase.Failed,
                detail = context.getString(com.gtav.rpfmanager.R.string.astc_interrupted)) else state
        }
    }
}
