package com.gtav.rpfmanager.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.AppState
import com.gtav.rpfmanager.data.model.FileEntry
import com.gtav.rpfmanager.data.model.RecentFile
import com.gtav.rpfmanager.data.model.formatSize

@Composable
fun LibraryScreen(
    state: AppState,
    open: () -> Unit,
    browseStorage: () -> Unit,
    demo: () -> Unit,
    settings: () -> Unit,
    recent: (RecentFile) -> Unit,
    remove: (String) -> Unit,
    extract: () -> Unit,
    pack: () -> Unit,
    convert: () -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("library_screen")) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RpfIcon(Glyph.Archive, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            IconAction(
                Glyph.Settings,
                stringResource(R.string.settings),
                Modifier.testTag("settings"),
                onClick = settings,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = open,
                        modifier = Modifier.fillMaxWidth().testTag("open_resource"),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RpfIcon(Glyph.Archive)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(stringResource(R.string.library_open), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.library_open_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            RpfIcon(Glyph.Right, Modifier.size(18.dp))
                        }
                    }
                    OutlinedButton(
                        onClick = browseStorage,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("browse_internal_storage"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        RpfIcon(Glyph.Folder, Modifier.size(22.dp))
                        Text(
                            stringResource(R.string.library_internal_storage),
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            textAlign = TextAlign.Start,
                        )
                        RpfIcon(Glyph.Right, Modifier.size(18.dp))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.recent_files)) {
                        if (state.recents.isNotEmpty()) {
                            Text(
                                state.recents.size.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            if (state.recents.isEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RpfIcon(Glyph.File, tint = MaterialTheme.colorScheme.outline)
                                    Column(Modifier.padding(start = 12.dp)) {
                                        Text(stringResource(R.string.library_no_recent), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            stringResource(R.string.empty_recent_body),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else state.recents.forEachIndexed { index, item ->
                                RecentFileRow(item, open = { recent(item) }, remove = { remove(item.uri) })
                                if (index < state.recents.lastIndex) HorizontalDivider(
                                    Modifier.padding(start = 52.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f),
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.library_tools))
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibraryTool(
                            Glyph.Image, stringResource(R.string.library_astc),
                            Modifier.weight(1f).testTag("convert_astc"), convert,
                        )
                        LibraryTool(
                            Glyph.Download, stringResource(R.string.library_extract),
                            Modifier.weight(1f).testTag("extract_rpf"), extract,
                        )
                        LibraryTool(
                            Glyph.Archive, stringResource(R.string.library_pack),
                            Modifier.weight(1f).testTag("pack_directory"), pack,
                        )
                    }
                }

                Surface(
                    onClick = demo,
                    modifier = Modifier.fillMaxWidth().testTag("open_demo"),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RpfIcon(Glyph.Cube, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(stringResource(R.string.library_sample), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.library_sample_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RpfIcon(Glyph.Right, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    stringResource(R.string.supported_formats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RecentFileRow(item: RecentFile, open: () -> Unit, remove: () -> Unit) {
    val kind = FileEntry(item.name, item.size).kind
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = open).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RpfIcon(kind.glyph(), Modifier.size(24.dp), tint = kind.tint())
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${stringResource(kind.label())} · ${formatSize(item.size, LocalConfiguration.current.locales[0])}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconAction(
            Glyph.Close,
            stringResource(R.string.library_remove_recent, item.name),
            onClick = remove,
        )
    }
}

@Composable
private fun LibraryTool(glyph: Glyph, title: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.heightIn(min = 88.dp).padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            RpfIcon(glyph, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}
