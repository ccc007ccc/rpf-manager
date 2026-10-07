package com.gtav.rpfmanager

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtav.rpfmanager.astc.AstcScreen
import com.gtav.rpfmanager.astc.AstcService
import com.gtav.rpfmanager.data.model.DocumentRequest
import com.gtav.rpfmanager.ui.*
import com.gtav.rpfmanager.ui.theme.RpfManagerTheme
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: ArchiveViewModel by viewModels()
    private var showConversion by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showConversion =
            savedInstanceState?.getBoolean(AstcService.OPEN_TOOLS)
                ?: intent.getBooleanExtra(AstcService.OPEN_TOOLS, false)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent.action == Intent.ACTION_VIEW)
            intent.data?.let(model::openUri)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val system = LocalConfiguration.current
            val config =
                remember(system, state.settings.language) {
                    Configuration(system).apply {
                        if (state.settings.language != "system")
                            setLocales(LocaleList(Locale.forLanguageTag(state.settings.language)))
                    }
                }
            val base = LocalContext.current
            val context =
                remember(base, config) {
                    ContextThemeWrapper(base, R.style.Theme_RpfManager).apply {
                        applyOverrideConfiguration(config)
                    }
                }
            val dark =
                when (state.settings.appearance) {
                    "light" -> false
                    "system" -> isSystemInDarkTheme()
                    else -> true
                }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides config,
            ) {
                RpfManagerTheme(dark) {
                    val opener =
                        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                            it?.let(model::openUri)
                        }
                    val toolArchivePicker =
                        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                            model.toolArchiveSelected(it)
                        }
                    val toolDirectoryPicker =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocumentTree()
                        ) {
                            model.toolDirectorySelected(it)
                        }
                    val keys =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocumentTree()
                        ) {
                            it?.let(model::importKeys)
                        }
                    val exporter =
                        rememberLauncherForActivityResult(CreateResourceDocument()) {
                            model.completeExport(it)
                        }
                    val toolOutput =
                        rememberLauncherForActivityResult(CreateResourceDocument()) {
                            model.completeToolOutput(it)
                        }
                    val importer =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument(),
                            model::pickedDocument,
                        )
                    val multiImporter =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenMultipleDocuments(),
                            model::pickedDocuments,
                        )
                    val storageDirectory =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocumentTree(),
                            model::storageDirectorySelected,
                        )
                    val storageAccess =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.StartActivityForResult()
                        ) {
                            model.internalStorageAccessResult()
                        }
                    val legacyStorageAccess =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions()
                        ) {
                            model.internalStorageAccessResult()
                        }
                    LaunchedEffect(model) {
                        model.internalStorageRequests.collect {
                            if (Build.VERSION.SDK_INT >= 30) {
                                val intent =
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                try {
                                    storageAccess.launch(intent)
                                } catch (_: ActivityNotFoundException) {
                                    storageAccess.launch(
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    )
                                }
                            } else
                                legacyStorageAccess.launch(
                                    arrayOf(
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    )
                                )
                        }
                    }
                    LaunchedEffect(model) {
                        model.archiveOpenRequests.collect { opener.launch(arrayOf("*/*")) }
                    }
                    LaunchedEffect(model) {
                        model.pickerRequests.collect {
                            if (it == com.gtav.rpfmanager.data.model.PickPurpose.Add)
                                multiImporter.launch(arrayOf("*/*"))
                            else importer.launch(arrayOf("*/*"))
                        }
                    }
                    LaunchedEffect(model) {
                        model.storagePickerRequests.collect { storageDirectory.launch(null) }
                    }
                    LaunchedEffect(model) { model.exportRequests.collect { exporter.launch(it) } }
                    LaunchedEffect(model) {
                        model.toolRequests.collect { request ->
                            when (request) {
                                com.gtav.rpfmanager.data.model.ToolRequest.ExtractInput ->
                                    toolArchivePicker.launch(arrayOf("*/*"))
                                com.gtav.rpfmanager.data.model.ToolRequest.ExtractOutput,
                                com.gtav.rpfmanager.data.model.ToolRequest.PackInput ->
                                    toolDirectoryPicker.launch(null)
                                com.gtav.rpfmanager.data.model.ToolRequest.PackOutput ->
                                    toolOutput.launch(
                                        com.gtav.rpfmanager.data.model.DocumentRequest(
                                            "packed.rpf",
                                            "application/x-rpf",
                                        )
                                    )
                            }
                        }
                    }
                    val snack = remember { SnackbarHostState() }
                    LaunchedEffect(state.message) {
                        state.message?.let {
                            snack.showSnackbar(context.getString(it))
                            model.consumeMessage()
                        }
                    }
                    BackHandler(
                        showConversion ||
                            state.showSettings ||
                            state.fileWorkspaceOpen ||
                            state.location != null ||
                            state.busy != null
                    ) {
                        if (showConversion) showConversion = false else model.back()
                    }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Scaffold(
                            modifier =
                                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
                            containerColor = MaterialTheme.colorScheme.background,
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            snackbarHost = {
                                SnackbarHost(
                                    snack,
                                    Modifier.padding(
                                        bottom =
                                            if (
                                                state.fileWorkspaceOpen &&
                                                    state.selected == null &&
                                                    !state.showSettings &&
                                                    !showConversion
                                            )
                                                80.dp
                                            else 0.dp
                                    ),
                                )
                            },
                        ) { padding ->
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                when {
                                    showConversion -> AstcScreen { showConversion = false }
                                    state.showSettings ->
                                        SettingsScreen(state, model) { keys.launch(null) }
                                    state.showCoverage && state.coverage != null ->
                                        CoverageScreen(state.coverage!!, model)
                                    state.fileWorkspaceOpen -> WorkspaceScreen(state, model)
                                    else ->
                                        LibraryScreen(
                                            state,
                                            open = { opener.launch(arrayOf("*/*")) },
                                            demo = model::openDemo,
                                            settings = { model.showSettings(true) },
                                            recent = { model.openUri(Uri.parse(it.uri)) },
                                            remove = model::removeRecent,
                                            extract = model::startExtractArchive,
                                            pack = model::startPackDirectory,
                                            convert = { showConversion = true },
                                            browseStorage = model::browseInternalStorage,
                                        )
                                }
                            }
                        }
                    }
                    EditorDialogs(state, model)
                    WorkspaceDialogs(state, model)
                    state.busy?.let { busy ->
                        AlertDialog(
                            onDismissRequest = model::cancelOperation,
                            title = {
                                Text(
                                    stringResource(busy.label),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            },
                            text = {
                                Column {
                                    if (busy.progress != null) {
                                        LinearProgressIndicator(
                                            progress = { busy.progress },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            stringResource(
                                                R.string.percent_complete,
                                                (busy.progress * 100).toInt(),
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    } else
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    busy.visited?.let {
                                        if (busy.label == R.string.scan_running)
                                            Text(
                                                stringResource(R.string.scan_visited, it),
                                                Modifier.padding(top = 12.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        Text(
                                            busy.detail,
                                            maxLines = 2,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                if (busy.cancellable)
                                    TextButton(onClick = model::cancelOperation) {
                                        Text(stringResource(R.string.cancel))
                                    }
                            },
                        )
                    }
                    state.error?.let { diagnostic ->
                        val reason =
                            when {
                                diagnostic.contains("overwriting", true) ||
                                    diagnostic.contains("already open", true) ||
                                    diagnostic.contains("Choose a new file", true) ->
                                    R.string.error_overwrite
                                diagnostic.contains("entry name", true) ||
                                    diagnostic.contains("already exists", true) ||
                                    diagnostic.contains("archive path", true) ->
                                    R.string.error_edit_name
                                diagnostic.contains("key", true) &&
                                    diagnostic.contains("NG", true) -> R.string.error_ng_keys
                                diagnostic.contains("key", true) -> R.string.error_keys
                                diagnostic.contains("seekable", true) ||
                                    diagnostic.contains("direct mapping", true) ->
                                    R.string.error_source
                                diagnostic.contains("space", true) -> R.string.error_storage
                                diagnostic.contains("limit", true) ||
                                    diagnostic.contains("exceeds", true) -> R.string.error_limit
                                else -> R.string.error_generic
                            }
                        AlertDialog(
                            onDismissRequest = model::dismissError,
                            icon = { RpfIcon(Glyph.Info, tint = MaterialTheme.colorScheme.error) },
                            title = { Text(stringResource(R.string.operation_error)) },
                            text = {
                                Column {
                                    Text(stringResource(reason))
                                    ErrorDetails(diagnostic)
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = model::dismissError) {
                                    Text(stringResource(R.string.dismiss))
                                }
                            },
                            dismissButton = {
                                if (
                                    reason == R.string.error_keys ||
                                        reason == R.string.error_ng_keys
                                ) {
                                    TextButton(
                                        onClick = {
                                            model.dismissError()
                                            keys.launch(null)
                                        }
                                    ) {
                                        Text(stringResource(R.string.import_keys))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(AstcService.OPEN_TOOLS, false)) showConversion = true
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let(model::openUri)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(AstcService.OPEN_TOOLS, showConversion)
        super.onSaveInstanceState(outState)
    }
}

private class CreateResourceDocument : ActivityResultContract<DocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: DocumentRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}
