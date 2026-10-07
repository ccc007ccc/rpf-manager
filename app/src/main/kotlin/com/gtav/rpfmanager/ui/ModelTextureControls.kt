package com.gtav.rpfmanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.ResourcePreview
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel

@Composable
fun ModelTextureControls(preview: ResourcePreview.Mesh, vm: ArchiveViewModel) {
    if (!preview.data.hasUv) {
        Text(
            stringResource(R.string.no_model_uv),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var choosing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { choosing = true },
            modifier = Modifier.weight(1f).testTag("texture_source"),
        ) {
            RpfIcon(Glyph.Layers, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (preview.source?.embedded == true) stringResource(R.string.embedded_textures)
                else preview.source?.name ?: stringResource(R.string.texture_dictionary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            RpfIcon(Glyph.Down, Modifier.size(12.dp))
        }
        TextButton(
            onClick = vm::requestTextureDictionary,
            modifier = Modifier.testTag("open_ytd"),
        ) {
            Text(stringResource(R.string.open_ytd))
        }
    }
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("model_textures"),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ChoiceChip(
                stringResource(R.string.geometry_color),
                preview.active < 0,
                Modifier.testTag("geometry_color"),
            ) {
                vm.chooseModelTexture(-1)
            }
        }
        itemsIndexed(preview.textures) { i, t ->
            ChoiceChip(t.name, preview.active == i, Modifier.testTag("model_texture_$i")) {
                vm.chooseModelTexture(i)
            }
        }
    }
    if (preview.textureLoading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
    preview.textureError?.let { ErrorDetails(it) }
    if (preview.textures.isEmpty() && !preview.textureLoading && preview.textureError == null)
        Text(
            stringResource(R.string.no_embedded_textures),
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    if (preview.texture != null && preview.data.flags and 8 != 0)
        Text(
            stringResource(R.string.model_partial_uv),
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    if (choosing) {
        var search by remember { mutableStateOf("") }
        val sources =
            remember(preview.sources, search) {
                preview.sources.filter { it.path.contains(search, true) }
            }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.texture_dictionary)) },
            text = {
                Column {
                    OutlinedTextField(
                        search,
                        { search = it },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search_files)) },
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(sources) { source ->
                            TextButton(
                                onClick = {
                                    choosing = false
                                    vm.chooseModelSource(source)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (source.embedded) stringResource(R.string.embedded_textures)
                                    else source.path,
                                    Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (source == preview.source) RpfIcon(Glyph.Check)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}
