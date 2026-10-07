package com.gtav.rpfmanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.AppState
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel

@Composable
fun SettingsScreen(state: AppState, vm: ArchiveViewModel, importKeys: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("settings_screen")) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                Glyph.Back,
                stringResource(R.string.back),
                Modifier.testTag("settings_back"),
                onClick = { vm.showSettings(false) },
            )
            Text(
                stringResource(R.string.settings),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsGroup(Glyph.Globe, stringResource(R.string.language)) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((value, label) in
                            listOf(
                                "system" to R.string.follow_system,
                                "zh" to R.string.language_zh,
                                "en" to R.string.language_en,
                            )) {
                            SettingOption(
                                stringResource(label),
                                state.settings.language == value,
                                Modifier.weight(1f).testTag("language_$value"),
                            ) { vm.language(value) }
                        }
                    }
                }
                SettingsGroup(Glyph.Sun, stringResource(R.string.appearance)) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((value, label) in
                            listOf(
                                "system" to R.string.follow_system,
                                "dark" to R.string.theme_dark,
                                "light" to R.string.theme_light,
                            )) {
                            SettingOption(
                                stringResource(label),
                                state.settings.appearance == value,
                                Modifier.weight(1f).testTag("theme_$value"),
                            ) { vm.appearance(value) }
                        }
                    }
                }
                SettingsGroup(Glyph.Key, stringResource(R.string.keys)) {
                    Text(
                        stringResource(
                            if (state.settings.hasKeys) R.string.keys_loaded
                            else R.string.keys_missing
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Text(
                        stringResource(R.string.keys_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "gtav_aes_key.dat\ngtav_ng_key.dat\ngtav_ng_decrypt_tables.dat",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = importKeys, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.import_keys))
                    }
                    if (state.settings.hasKeys)
                        TextButton(onClick = vm::clearKeys, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.clear_keys))
                        }
                }
                SettingsGroup(Glyph.Archive, stringResource(R.string.storage)) {
                    Text(
                        stringResource(R.string.cache_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    OutlinedButton(onClick = vm::clearCache, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.clear_cache))
                    }
                    TextButton(
                        onClick = vm::clearRecents,
                        enabled = state.recents.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.clear_history))
                    }
                }
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text(
                        stringResource(R.string.preview_support),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.preview_support_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    glyph: Glyph,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RpfIcon(glyph, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SettingOption(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxHeight().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick),
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(
            Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}
