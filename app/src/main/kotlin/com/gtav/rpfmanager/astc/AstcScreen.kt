package com.gtav.rpfmanager.astc

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.formatSize
import com.gtav.rpfmanager.ui.Glyph
import com.gtav.rpfmanager.ui.IconAction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AstcScreen(back: () -> Unit) {
    val context = LocalContext.current
    val initial = remember {
        AstcStateStore.restore(context)
        AstcJobs.state.value.request?.copy(scanOnly = false) ?: AstcRequest(source = "")
    }
    val state by AstcJobs.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable(stateSaver = Saver<AstcRequest, String>(
        save = { it.json() }, restore = { AstcRequest.parse(it, validate = false) },
    )) { mutableStateOf(initial) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var effort by rememberSaveable { mutableStateOf(initial.effort?.toString().orEmpty()) }
    var weights by rememberSaveable { mutableStateOf(initial.channelWeights.joinToString(",")) }
    var alphaRadius by rememberSaveable { mutableStateOf(initial.alphaWeightRadius?.toString().orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<AstcRequest?>(null) }
    var exportSource by rememberSaveable { mutableStateOf("") }
    var pendingNotificationRequest by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val enabled = !state.running && pendingNotificationRequest == null

    fun retain(uri: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .getOrElse { context.contentResolver.takePersistableUriPermission(uri, read) }
    }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { retain(uri); draft = draft.copy(source = uri.toString()) }
            .onFailure { error = it.message }
    }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { retain(uri); draft = draft.copy(outputParent = uri.toString()) }
            .onFailure { error = it.message }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = if (exportSource.startsWith("content:")) context.contentResolver.openInputStream(Uri.parse(exportSource))
                        else File(exportSource).inputStream()
                    requireNotNull(input).use { source ->
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { source.copyTo(it) }
                    }
                }
            }.onFailure { error = it.message }
        }
    }
    fun launchTask(request: AstcRequest) {
        if (AstcJobs.state.value.running) return
        error = null
        val preparing = AstcState(AstcPhase.Preparing, request)
        AstcJobs.mutable.value = preparing
        try {
            ContextCompat.startForegroundService(context, Intent(context, AstcService::class.java)
                .putExtra(AstcService.REQUEST, request.json()))
        } catch (failure: Exception) {
            AstcJobs.mutable.value = preparing.copy(phase = AstcPhase.Failed, detail = failure.message.orEmpty())
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingNotificationRequest?.let { request -> pendingNotificationRequest = null; launchTask(AstcRequest.parse(request)) }
    }
    fun start(request: AstcRequest) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotificationRequest = request.json()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launchTask(request)
    }
    fun submit(scan: Boolean) {
        runCatching {
            val request = draft.copy(id = UUID.randomUUID().toString(), scanOnly = scan,
                effort = effort.trim().takeIf { it.isNotEmpty() }?.toDouble(),
                channelWeights = weights.split(',').map { it.trim().toDouble() },
                alphaWeightRadius = alphaRadius.trim().takeIf { it.isNotEmpty() }?.toInt())
            request.validate()
            if (!scan && request.inPlace) confirmation = request else start(request)
        }.onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize().testTag("astc_screen")) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Glyph.Back, stringResource(R.string.back), onClick = back)
            Text(stringResource(R.string.astc_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.astc_description), style = MaterialTheme.typography.bodyMedium)
                if (state.phase != AstcPhase.Idle) AstcProgress(state) {
                    context.startService(Intent(context, AstcService::class.java).setAction(AstcService.CANCEL))
                }
                if (!state.running && state.report.isNotBlank()) {
                    OutlinedButton(onClick = { exportSource = state.report; export.launch("rpf-astc-report.json") }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.export_report))
                    }
                }
                DirectoryPicker(R.string.astc_source, draft.source, enabled, "astc_source") { sourcePicker.launch(null) }
                Toggle(R.string.astc_repair_pages, draft.repairPages, enabled) { draft = draft.copy(repairPages = it) }
                if (draft.repairPages) Text(stringResource(R.string.astc_repair_hint), style = MaterialTheme.typography.bodySmall)
                Toggle(R.string.astc_in_place, draft.inPlace, enabled) { draft = draft.copy(inPlace = it) }
                if (!draft.inPlace) {
                    DirectoryPicker(R.string.astc_destination, draft.outputParent, enabled, "astc_destination") { outputPicker.launch(null) }
                    OutlinedTextField(draft.outputName, { draft = draft.copy(outputName = it) }, enabled = enabled,
                        label = { Text(stringResource(R.string.astc_output_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (!draft.repairPages) {
                    Choice(stringResource(R.string.astc_block), draft.block, AstcRequest.blocks, enabled) { draft = draft.copy(block = it) }
                    val qualityLabels = listOf(R.string.astc_quality_fastest, R.string.astc_quality_fast, R.string.astc_quality_medium,
                        R.string.astc_quality_thorough, R.string.astc_quality_very_thorough, R.string.astc_quality_exhaustive).map { stringResource(it) }
                    Choice(stringResource(R.string.astc_quality), qualityLabels[AstcRequest.qualities.indexOf(draft.quality)], qualityLabels, enabled) {
                        draft = draft.copy(quality = AstcRequest.qualities[qualityLabels.indexOf(it)])
                    }
                    val matchingEstimate = state.request?.source == draft.source && state.estimatedByBlock.isNotEmpty()
                    if (matchingEstimate) {
                        val locale = LocalConfiguration.current.locales[0]
                        Text(stringResource(R.string.astc_estimate, formatSize(state.estimateSourceBytes, locale),
                            draft.block, formatSize(state.estimatedByBlock[draft.block] ?: 0, locale)),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("astc_estimate"))
                        if (!state.estimateComplete) Text(stringResource(R.string.astc_estimate_partial), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.astc_estimate_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Toggle(R.string.astc_require_no_bc, draft.requireNoBc, enabled) { draft = draft.copy(requireNoBc = it) }
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("astc_advanced")) { Text(stringResource(R.string.astc_advanced)) }
                    if (advanced) {
                        OutlinedTextField(effort, { effort = it }, enabled = enabled, label = { Text(stringResource(R.string.astc_effort)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(weights, { weights = it }, enabled = enabled, label = { Text(stringResource(R.string.astc_weights)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(alphaRadius, { alphaRadius = it }, enabled = enabled, label = { Text(stringResource(R.string.astc_alpha_radius)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Toggle(R.string.astc_perceptual, draft.perceptual, enabled) { draft = draft.copy(perceptual = it) }
                        Text(stringResource(R.string.astc_advanced_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Choice(stringResource(R.string.astc_workers), draft.workers.toString(), listOf("1", "2", "3", "4"), enabled) { draft = draft.copy(workers = it.toInt()) }
                Toggle(R.string.astc_keep_going, draft.keepGoing, enabled) { draft = draft.copy(keepGoing = it) }
                error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { submit(true) }, enabled = enabled, modifier = Modifier.weight(1f).testTag("astc_scan")) {
                        Text(stringResource(R.string.astc_scan))
                    }
                    Button(onClick = { submit(false) }, enabled = enabled, modifier = Modifier.weight(1f).testTag("astc_start")) {
                        Text(stringResource(if (draft.repairPages) R.string.astc_repair_start else R.string.astc_start))
                    }
                }
            }
        }
    }
    confirmation?.let { request ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(R.string.astc_replace_title)) },
            text = { Text(stringResource(R.string.astc_replace_body)) },
            confirmButton = { TextButton(onClick = { confirmation = null; start(request) }) { Text(stringResource(R.string.astc_start)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun DirectoryPicker(label: Int, value: String, enabled: Boolean, tag: String, choose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = choose, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(stringResource(label)) }
        if (value.isNotBlank()) SelectionContainer {
            Text(Uri.decode(Uri.parse(value).lastPathSegment ?: value), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Choice(label: String, value: String, choices: List<String>, enabled: Boolean, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f)); Text(value)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { open = false; select(choice) }) }
        }
    }
}

@Composable
private fun Toggle(label: Int, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun AstcProgress(state: AstcState, stop: () -> Unit) {
    val label = when (state.phase) {
        AstcPhase.Idle -> R.string.astc_status_idle
        AstcPhase.Preparing -> R.string.astc_status_preparing
        AstcPhase.Scanning -> R.string.astc_status_scanning
        AstcPhase.Converting -> if (state.request?.repairPages == true) R.string.astc_status_repairing else R.string.astc_status_converting
        AstcPhase.Writing -> R.string.astc_status_writing
        AstcPhase.Verifying -> R.string.astc_status_verifying
        AstcPhase.Publishing -> R.string.astc_status_publishing
        AstcPhase.Stopping -> R.string.astc_status_stopping
        AstcPhase.Complete -> R.string.astc_status_complete
        AstcPhase.Incomplete -> R.string.astc_status_incomplete
        AstcPhase.Cancelled -> R.string.astc_status_cancelled
        AstcPhase.Failed -> R.string.astc_status_failed
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("astc_status"))
            if (state.running) {
                if (state.total > 0) LinearProgressIndicator(progress = { state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.astc_notification_progress, state.completed, state.total, state.converted))
            Text(stringResource(R.string.astc_counts, state.textures, state.remainingBc, state.blockedBc, state.repairedResources, state.errors),
                style = MaterialTheme.typography.bodySmall)
            if (state.archive.isNotBlank()) Text(state.archive, style = MaterialTheme.typography.bodySmall)
            if (state.resource.isNotBlank()) Text(state.resource, style = MaterialTheme.typography.bodySmall)
            if (state.detail.isNotBlank()) SelectionContainer { Text(state.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (state.running) {
                Text(stringResource(R.string.astc_stop_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = stop, enabled = state.phase != AstcPhase.Stopping) { Text(stringResource(R.string.astc_stop)) }
            }
        }
    }
}
