package com.gtav.rpfmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.*
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel

@Composable
fun CoverageScreen(report: CoverageReport, vm: ArchiveViewModel) {
    var files by remember { mutableStateOf(false) }
    var issues by remember { mutableStateOf(false) }
    val rows =
        remember(report, issues) {
            report.rows.filter { !issues || it.status in listOf("failed", "unsupported") }
        }
    Column(Modifier.fillMaxSize().testTag("coverage_report")) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                Glyph.Back,
                stringResource(R.string.back),
                Modifier.testTag("coverage_back"),
                onClick = vm::back,
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    stringResource(R.string.coverage_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    report.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconAction(
                Glyph.Download,
                stringResource(R.string.export_report),
                Modifier.testTag("export_report"),
                onClick = vm::exportCoverage,
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                if (!report.finished)
                    Text(
                        stringResource(R.string.coverage_incomplete),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                if (report.unopenedArchives > 0)
                    Text(
                        pluralStringResource(
                            R.plurals.coverage_unopened_archives,
                            report.unopenedArchives,
                            report.unopenedArchives,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp).testTag("coverage_unopened"),
                    )
                Text(
                    stringResource(R.string.coverage_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Count(report.counts.preview, R.string.coverage_preview, Modifier.weight(1f))
                    Count(report.counts.partial, R.string.coverage_partial, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Count(
                        report.counts.unsupported,
                        R.string.coverage_unsupported,
                        Modifier.weight(1f),
                    )
                    Count(report.counts.failed, R.string.coverage_failed, Modifier.weight(1f))
                }
                Text(
                    stringResource(R.string.coverage_containers, report.counts.container),
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(stringResource(R.string.coverage_formats), !files) { files = false }
                    ChoiceChip(stringResource(R.string.coverage_files), files) { files = true }
                    Spacer(Modifier.weight(1f))
                    IconAction(
                        Glyph.Reset,
                        stringResource(R.string.scan_again),
                        onClick = vm::scanArchive,
                    )
                }
                if (files)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(issues, { issues = it })
                        Text(
                            stringResource(R.string.coverage_issues),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
            }
            if (!files)
                items(report.formats, key = { it.extension }) { format ->
                    Column {
                        Text(
                            format.extension.uppercase().ifEmpty {
                                stringResource(R.string.no_extension)
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val c = format.counts
                        if (c.container > 0)
                            Text(
                                stringResource(R.string.coverage_containers, c.container),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        if (c.container < c.total)
                            Text(
                                stringResource(
                                    R.string.coverage_format_summary,
                                    c.preview,
                                    c.partial,
                                    c.unsupported,
                                    c.failed,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        HorizontalDivider(
                            Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            else {
                if (rows.isEmpty()) item { Text(stringResource(R.string.report_empty)) }
                items(rows, key = { it.path }) { row ->
                    var expanded by remember(row.path) { mutableStateOf(false) }
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            row.path,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(statusLabel(row.status)),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (row.status == "failed") MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        )
                        if (expanded)
                            SelectionContainer {
                                Text(
                                    row.detail,
                                    Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        HorizontalDivider(
                            Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Count(count: Int, label: Int, modifier: Modifier) {
    Column(modifier) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusLabel(status: String): Int =
    when (status) {
        "preview" -> R.string.coverage_preview
        "partial" -> R.string.coverage_partial
        "unsupported" -> R.string.coverage_unsupported
        "container" -> R.string.coverage_container
        else -> R.string.coverage_failed
    }
