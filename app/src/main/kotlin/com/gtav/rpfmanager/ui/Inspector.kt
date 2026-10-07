package com.gtav.rpfmanager.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.*
import com.gtav.rpfmanager.preview.VulkanView
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Inspector(
    state: AppState,
    vm: ArchiveViewModel,
    modifier: Modifier,
    wide: Boolean,
    compact: Boolean = false,
) {
    val entry = state.selected ?: return
    key(entry.path) {
        var details by rememberSaveable { mutableStateOf(false) }
        Column(modifier) {
            Row(
                Modifier.fillMaxWidth()
                    .height(if (compact) 52.dp else 64.dp)
                    .padding(horizontal = if (wide) 16.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (wide) {
                    RpfIcon(entry.kind.glyph(), tint = entry.kind.tint())
                    Spacer(Modifier.width(12.dp))
                } else
                    IconAction(
                        Glyph.Back,
                        stringResource(R.string.back),
                        Modifier.testTag("preview_back"),
                        onClick = vm::back,
                    )
                Column(Modifier.weight(1f).padding(start = if (wide) 0.dp else 8.dp)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(entry.kind.label()),
                        style = MaterialTheme.typography.bodySmall,
                        color = entry.kind.tint(),
                    )
                }
                if (compact)
                    IconAction(
                        Glyph.Info,
                        stringResource(R.string.details),
                        active = details,
                        onClick = { details = !details },
                    )
                IconAction(
                    Glyph.Download,
                    stringResource(R.string.export_original),
                    Modifier.testTag("export_original"),
                    onClick = { vm.prepareExport() },
                )
                if (state.location?.session?.info?.editable == true) EntryActions(entry, vm)
                if (wide)
                    IconAction(Glyph.Close, stringResource(R.string.close), onClick = vm::back)
            }
            if (!compact)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChoiceChip(
                        stringResource(R.string.preview),
                        !details,
                        Modifier.testTag("preview_tab"),
                    ) {
                        details = false
                    }
                    ChoiceChip(
                        stringResource(R.string.details),
                        details,
                        Modifier.testTag("details_tab"),
                    ) {
                        details = true
                    }
                }
            if (!compact) Spacer(Modifier.height(8.dp))
            if (details) FileDetails(state, Modifier.weight(1f))
            else
                when (val preview = state.preview) {
                    ResourcePreview.Loading ->
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                    is ResourcePreview.Mesh ->
                        MeshViewer(preview, vm, entry.kind, Modifier.weight(1f), compact)
                    is ResourcePreview.Audio -> AudioViewer(preview, vm, Modifier.weight(1f))
                    is ResourcePreview.Textures ->
                        TextureViewer(preview, vm, Modifier.weight(1f), compact)
                    is ResourcePreview.Picture ->
                        Column(Modifier.weight(1f)) {
                            PictureViewport(preview.bitmap, entry.name, Modifier.weight(1f))
                            Text(
                                stringResource(R.string.texture_gesture_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    is ResourcePreview.Text ->
                        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                            if (preview.truncated)
                                Text(
                                    stringResource(R.string.text_truncated),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            SelectionContainer(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    preview.content.ifEmpty { stringResource(R.string.empty_text) },
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 24.dp),
                                )
                            }
                        }
                    is ResourcePreview.Failure ->
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            EmptyState(
                                Glyph.Info,
                                stringResource(R.string.preview_error),
                                stringResource(R.string.preview_error_body),
                                action = {
                                    Button(onClick = vm::retryPreview) {
                                        Text(stringResource(R.string.retry))
                                    }
                                },
                            )
                            ErrorDetails(preview.diagnostic)
                        }
                    ResourcePreview.Unsupported ->
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            EmptyState(
                                entry.kind.glyph(),
                                stringResource(R.string.no_preview_title),
                                stringResource(R.string.no_preview_body),
                            )
                            FileDetailsContent(state)
                        }
                }
        }
    }
}

@Composable
private fun FileDetails(state: AppState, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        FileDetailsContent(state)
    }
}

@Composable
private fun FileDetailsContent(state: AppState) {
    val file = state.selected ?: return
    val session = state.location?.session ?: return
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        MetadataRow(stringResource(R.string.file_format), file.extension.uppercase(locale))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MetadataRow(stringResource(R.string.file_size), formatSize(file.size, locale))
        MetadataRow(
            stringResource(R.string.file_path),
            if (session.isArchive) file.path else file.name,
        )
        if (session.isArchive) {
            MetadataRow(stringResource(R.string.archive_name), session.name)
            MetadataRow(
                stringResource(R.string.encryption),
                if (session.info?.encryption in listOf("None", "Open"))
                    stringResource(R.string.unencrypted)
                else session.info?.encryption.orEmpty(),
            )
        }
        (state.preview as? ResourcePreview.Mesh)?.data?.let { mesh ->
            val b = mesh.bounds
            MetadataRow(
                stringResource(R.string.dimensions),
                String.format(locale, "%.2f × %.2f × %.2f", b[3] - b[0], b[4] - b[1], b[5] - b[2]),
            )
        }
    }
}

@Composable
private fun MeshViewer(
    preview: ResourcePreview.Mesh,
    vm: ArchiveViewModel,
    kind: AssetKind,
    modifier: Modifier,
    compact: Boolean,
) {
    val mesh = preview.data
    if (mesh.isEmptyMap) {
        Box(modifier.fillMaxWidth().testTag("empty_map"), contentAlignment = Alignment.Center) {
            EmptyState(
                Glyph.Map,
                stringResource(R.string.empty_map_title),
                stringResource(R.string.empty_map_body),
            )
        }
        return
    }
    var wire by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var camera by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    // Own a stable View, not a state-backed reference assigned by AndroidView's
    // factory. Such assignment can dispose an effect after the new View exists,
    // shutting its renderer down immediately after the first frame.
    val view =
        remember(context, mesh, generation) {
            VulkanView(context).apply {
                onFailure = { error = it }
                onOrbit = { camera = 0 }
                setScene(mesh)
            }
        }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(view, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.resumeRendering()
                Lifecycle.Event.ON_PAUSE -> view.pauseRendering()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.pauseRendering()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.release()
        }
    }
    Column(modifier.fillMaxWidth()) {
        if (kind == AssetKind.Model && !compact) ModelTextureControls(preview, vm)
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(Color(0xFF121518), RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
        ) {
            if (error == null) {
                key(view) {
                    AndroidView(
                        factory = { view },
                        modifier = Modifier.fillMaxSize().testTag("mesh_canvas"),
                        update = {
                            it.setScene(mesh)
                            it.setTexture(preview.texture)
                            it.wire = wire
                            it.grid = grid
                        },
                    )
                }
                Row(
                    Modifier.align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ChoiceChip(stringResource(R.string.shaded), !wire, Modifier.testTag("shaded")) {
                        wire = false
                    }
                    ChoiceChip(
                        stringResource(R.string.wireframe),
                        wire,
                        Modifier.testTag("wireframe"),
                    ) {
                        wire = true
                    }
                }
                Column(
                    Modifier.align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                            RoundedCornerShape(12.dp),
                        )
                ) {
                    IconAction(
                        Glyph.Grid,
                        stringResource(R.string.toggle_grid),
                        active = grid,
                        onClick = { grid = !grid },
                    )
                    IconAction(
                        Glyph.Fit,
                        stringResource(R.string.fit_view),
                        Modifier.testTag("fit_view"),
                        onClick = {
                            camera = 0
                            view.fit()
                        },
                    )
                }
                var viewsMenu by remember { mutableStateOf(false) }
                Box(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                    TextButton(
                        onClick = { viewsMenu = true },
                        modifier =
                            Modifier.background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                                    RoundedCornerShape(10.dp),
                                )
                                .testTag("camera_view"),
                    ) {
                        RpfIcon(Glyph.Orbit, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                listOf(
                                    R.string.view_perspective,
                                    R.string.view_front,
                                    R.string.view_top,
                                )[camera]
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        RpfIcon(Glyph.Down, Modifier.size(12.dp))
                    }
                    DropdownMenu(expanded = viewsMenu, onDismissRequest = { viewsMenu = false }) {
                        listOf(R.string.view_perspective, R.string.view_front, R.string.view_top)
                            .forEachIndexed { i, id ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(id)) },
                                    onClick = {
                                        camera = i
                                        view.view(i)
                                        viewsMenu = false
                                    },
                                )
                            }
                    }
                }
            } else
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EmptyState(
                        Glyph.Info,
                        stringResource(R.string.renderer_error),
                        stringResource(R.string.preview_error_body),
                        action = {
                            TextButton(
                                onClick = {
                                    generation++
                                    camera = 0
                                    error = null
                                }
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        },
                    )
                    ErrorDetails(error.orEmpty())
                }
        }
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = if (compact) 8.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Metric(
                stringResource(if (kind == AssetKind.Map) R.string.entities else R.string.vertices),
                if (kind == AssetKind.Map) mesh.parts else mesh.vertexCount,
                Modifier.weight(1f),
                compact,
            )
            Metric(
                stringResource(R.string.triangles),
                mesh.triangleCount,
                Modifier.weight(1f),
                compact,
            )
            if (kind != AssetKind.Map)
                Metric(stringResource(R.string.parts), mesh.parts, Modifier.weight(1f), compact)
        }
        val notice =
            when {
                mesh.flags and 1 != 0 -> R.string.map_marker_notice
                mesh.flags and 2 != 0 -> R.string.collision_bounds_notice
                kind == AssetKind.Model && preview.texture != null -> R.string.model_texture_hint
                kind == AssetKind.Model -> R.string.static_model_notice
                else -> null
            }
        if (notice != null && (!compact || kind != AssetKind.Model))
            Text(
                stringResource(notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
            )
        if (!compact)
            Text(
                stringResource(R.string.gesture_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 14.dp),
            )
    }
}

@Composable
private fun Metric(label: String, value: Int, modifier: Modifier, compact: Boolean = false) {
    val number =
        NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(value)
    if (compact)
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                number,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    else
        Column(modifier) {
            Text(
                number,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
}

@Composable
private fun TextureViewer(
    preview: ResourcePreview.Textures,
    vm: ArchiveViewModel,
    modifier: Modifier,
    compact: Boolean,
) {
    val selected = preview.textures.getOrNull(preview.active)
    Column(modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(preview.textures) { i, t ->
                ChoiceChip(
                    t.name.ifBlank { "#${t.index+1}" },
                    i == preview.active,
                    Modifier.testTag("texture_$i"),
                ) {
                    vm.chooseTexture(i)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (preview.bitmap != null)
            PictureViewport(preview.bitmap, selected?.name.orEmpty(), Modifier.weight(1f))
        else if (preview.diagnostic != null) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(Glyph.Info,stringResource(R.string.preview_error),stringResource(R.string.preview_error_body))
            ErrorDetails(preview.diagnostic)
        }
        else
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${selected?.width ?: 0} × ${selected?.height ?: 0}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "${selected?.format.orEmpty()} · ${pluralStringResource(R.plurals.mip_levels, selected?.levels ?: 0, selected?.levels ?: 0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.texture_layout, selected?.layers ?: 1,
                        if (selected?.srgb == true) "sRGB" else stringResource(R.string.texture_linear)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if ((selected?.layers ?: 1) > 1) Text(stringResource(R.string.texture_first_layer), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = { vm.prepareExport(true) },
                enabled = preview.png != null,
                modifier = Modifier.testTag("export_png"),
            ) {
                RpfIcon(Glyph.Download, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_png))
            }
        }
        if (!compact)
            Text(
                stringResource(R.string.texture_gesture_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp),
            )
    }
}

@Composable
private fun PictureViewport(bitmap: Bitmap, name: String, modifier: Modifier) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var alpha by rememberSaveable { mutableStateOf(false) }
    var display by remember(bitmap) { mutableStateOf(bitmap) }
    LaunchedEffect(bitmap, alpha) {
        display =
            if (!alpha) bitmap
            else
                withContext(Dispatchers.Default) {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    for (i in pixels.indices) {
                        val a = pixels[i] ushr 24
                        pixels[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
                    }
                    Bitmap.createBitmap(
                        pixels,
                        bitmap.width,
                        bitmap.height,
                        Bitmap.Config.ARGB_8888,
                    )
                }
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF171B20))
            .testTag("texture_canvas")
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = 16.dp.toPx()
            val columns = (size.width / cell).toInt() + 1
            val rows = (size.height / cell).toInt() + 1
            for (y in 0..rows) for (x in 0..columns) if ((x + y) % 2 == 0)
                drawRect(Color(0xFF232930), Offset(x * cell, y * cell), Size(cell, cell))
        }
        Box(
            Modifier.fillMaxSize().pointerInput(bitmap) {
                detectTransformGestures { _, delta, zoom, _ ->
                    scale = (scale * zoom).coerceIn(.25f, 16f)
                    pan += delta
                }
            }
        ) {
            Image(
                display.asImageBitmap(),
                contentDescription = name,
                modifier =
                    Modifier.fillMaxSize()
                        .padding(36.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = pan.x,
                            translationY = pan.y,
                        ),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        }
        Row(
            Modifier.align(Alignment.TopStart)
                .padding(10.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = .95f),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChoiceChip(stringResource(R.string.rgba), !alpha, Modifier.testTag("rgba")) {
                alpha = false
            }
            ChoiceChip(stringResource(R.string.alpha), alpha, Modifier.testTag("alpha")) {
                alpha = true
            }
        }
        IconAction(
            Glyph.Fit,
            stringResource(R.string.fit_view),
            Modifier.align(Alignment.TopEnd)
                .padding(10.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = .95f),
                    RoundedCornerShape(12.dp),
                ),
            onClick = {
                scale = 1f
                pan = Offset.Zero
            },
        )
    }
}
