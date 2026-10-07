package com.gtav.rpfmanager.astc

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class AstcRequest(
    val source: String,
    val outputParent: String = "",
    val outputName: String = "GTA-ASTC",
    val inPlace: Boolean = false,
    val scanOnly: Boolean = false,
    val block: String = "4x4",
    val quality: String = "thorough",
    val workers: Int = 2,
    val keepGoing: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
    val repairPages: Boolean = false,
    val requireNoBc: Boolean = true,
    val effort: Double? = null,
    val channelWeights: List<Double> = listOf(1.0, 1.0, 1.0, 1.0),
    val alphaWeightRadius: Int? = null,
    val perceptual: Boolean = false,
) {
    fun validate() {
        require(source.isNotBlank()) { "Select a source directory" }
        require(scanOnly || inPlace || outputParent.isNotBlank()) { "Select an output directory" }
        require(scanOnly || inPlace || (outputName.isNotBlank() && outputName.length <= 128 && outputName !in listOf(".", "..") && outputName.none { it == '/' || it == '\\' || it == '\u0000' })) { "Invalid output directory name" }
        require(id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid task identity" }
        require(block in blocks && quality in qualities && workers in 1..4) { "Invalid conversion settings" }
        require(effort == null || (effort.isFinite() && effort in 0.0..100.0)) { "Search effort must be between 0 and 100" }
        require(channelWeights.size == 4 && channelWeights.all { it.isFinite() && it in 0.01..64.0 }) { "RGBA weights must each be between 0.01 and 64" }
        require(alphaWeightRadius == null || alphaWeightRadius in 0..16) { "Alpha weight radius must be between 0 and 16" }
    }
    fun json() = JSONObject().put("source", source).put("outputParent", outputParent)
        .put("outputName", outputName).put("inPlace", inPlace).put("scanOnly", scanOnly)
        .put("block", block).put("quality", quality).put("workers", workers)
        .put("keepGoing", keepGoing).put("id", id)
        .put("repairPages", repairPages).put("requireNoBc", requireNoBc)
        .put("effort", effort ?: JSONObject.NULL).put("channelWeights", org.json.JSONArray(channelWeights))
        .put("alphaWeightRadius", alphaWeightRadius ?: JSONObject.NULL).put("perceptual", perceptual).toString()
    companion object {
        val blocks = listOf("4x4", "5x4", "5x5", "6x5", "6x6", "8x5", "8x6", "8x8", "10x5", "10x6", "10x8", "10x10", "12x10", "12x12")
        val qualities = listOf("fastest", "fast", "medium", "thorough", "very-thorough", "exhaustive")
        fun parse(text: String, validate: Boolean = true): AstcRequest = JSONObject(text).let {
            AstcRequest(source = it.getString("source"), outputParent = it.optString("outputParent"),
                outputName = it.optString("outputName", "GTA-ASTC"), inPlace = it.optBoolean("inPlace"),
                scanOnly = it.optBoolean("scanOnly"), block = it.optString("block", "4x4"),
                quality = it.optString("quality", "thorough"), workers = it.optInt("workers", 2),
                keepGoing = it.optBoolean("keepGoing"), id = it.optString("id", UUID.randomUUID().toString()),
                repairPages = it.optBoolean("repairPages"), requireNoBc = it.optBoolean("requireNoBc", true),
                effort = if (it.isNull("effort")) null else it.getDouble("effort"),
                channelWeights = it.optJSONArray("channelWeights")?.let { weights -> List(weights.length()) { i -> weights.getDouble(i) } }
                    ?: listOf(1.0, 1.0, 1.0, 1.0),
                alphaWeightRadius = if (it.isNull("alphaWeightRadius")) null else it.getInt("alphaWeightRadius"),
                perceptual = it.optBoolean("perceptual"))
        }.also { if (validate) it.validate() }
    }
}

enum class AstcPhase { Idle, Preparing, Scanning, Converting, Writing, Verifying, Publishing, Stopping, Complete, Incomplete, Cancelled, Failed }

data class AstcState(
    val phase: AstcPhase = AstcPhase.Idle,
    val request: AstcRequest? = null,
    val archive: String = "",
    val resource: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val textures: Long = 0,
    val converted: Long = 0,
    val retained: Long = 0,
    val errors: Int = 0,
    val sourceBytes: Long = 0,
    val astcBytes: Long = 0,
    val output: String = "",
    val report: String = "",
    val detail: String = "",
    val remainingBc: Long = 0,
    val blockedBc: Long = 0,
    val repairedResources: Long = 0,
    val estimateSourceBytes: Long = 0,
    val estimatedByBlock: Map<String, Long> = emptyMap(),
    val estimateComplete: Boolean = false,
) {
    val running: Boolean get() = phase in listOf(AstcPhase.Preparing, AstcPhase.Scanning, AstcPhase.Converting,
        AstcPhase.Writing, AstcPhase.Verifying, AstcPhase.Publishing, AstcPhase.Stopping)
}

object AstcJobs {
    internal val mutable = MutableStateFlow(AstcState())
    val state = mutable.asStateFlow()
}

/** Plain identities keep the replacement protocol testable without Android. */
data class AstcDocument(
    val id: String, val name: String, val parent: String,
    val directory: Boolean = false, val size: Long = 0, val modified: Long = 0,
    val rename: Boolean = false, val delete: Boolean = false, val create: Boolean = false,
)

data class AstcReplacement(
    val parent: String, val originalName: String, val backupName: String, val temporaryName: String,
    val expectedHash: String, val expectedSize: Long, val phase: String = "prepared",
    val publishedId: String = "",
)

interface AstcPublishStore {
    fun find(parent: String, name: String): AstcDocument?
    fun rename(document: AstcDocument, name: String): AstcDocument
    fun delete(document: AstcDocument)
    fun hash(document: AstcDocument): String
}

interface AstcJournal {
    fun load(): AstcReplacement?
    fun save(record: AstcReplacement)
    fun clear()
}
