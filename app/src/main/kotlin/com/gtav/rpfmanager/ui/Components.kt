package com.gtav.rpfmanager.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.AssetKind
import com.gtav.rpfmanager.ui.theme.*

enum class Glyph {
    Folder,
    Archive,
    Cube,
    Image,
    Map,
    Collision,
    File,
    Search,
    Grid,
    List,
    Right,
    Back,
    Close,
    Plus,
    Settings,
    Down,
    Check,
    Download,
    Fit,
    Orbit,
    Reset,
    Globe,
    Key,
    Trash,
    Info,
    ArrowUp,
    Layers,
    Sun,
    More,
    Edit,
    Save,
    Redo,
    Audio,
    Play,
    Pause,
}

@Composable
fun RpfIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    description: String? = null,
) {
    val path =
        remember(glyph) {
            Path().apply {
                fun line(vararg p: Float) {
                    moveTo(p[0], p[1])
                    for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
                }
                fun rect(l: Float, t: Float, r: Float, b: Float) {
                    line(l, t, r, t, r, b, l, b, l, t)
                }
                fun circle(x: Float, y: Float, radius: Float) {
                    addOval(Rect(x - radius, y - radius, x + radius, y + radius))
                }
                when (glyph) {
                    Glyph.More -> {
                        for (y in listOf(5f, 12f, 19f)) circle(12f, y, 1f)
                    }
                    Glyph.Edit -> {
                        line(4f, 16f, 16f, 4f, 20f, 8f, 8f, 20f, 4f, 20f, 4f, 16f)
                        line(13f, 7f, 17f, 11f)
                    }
                    Glyph.Save -> {
                        rect(3f, 3f, 21f, 21f)
                        rect(7f, 3f, 17f, 9f)
                        rect(7f, 14f, 17f, 21f)
                    }
                    Glyph.Redo -> {
                        arcTo(Rect(4f, 4f, 20f, 20f), 270f, -300f, false)
                        line(21f, 3f, 21f, 9f, 15f, 9f)
                    }
                    Glyph.Audio -> {
                        line(9f, 17f, 9f, 4f, 20f, 2f, 20f, 15f)
                        addOval(Rect(3f, 15f, 9f, 21f))
                        addOval(Rect(14f, 13f, 20f, 19f))
                    }
                    Glyph.Play -> line(7f, 3f, 21f, 12f, 7f, 21f, 7f, 3f)
                    Glyph.Pause -> {
                        rect(5f, 3f, 9f, 21f)
                        rect(15f, 3f, 19f, 21f)
                    }
                    Glyph.Folder -> {
                        line(3f, 7f, 3f, 19f, 21f, 19f, 21f, 7f, 12f, 7f, 9f, 4f, 3f, 4f, 3f, 7f)
                    }
                    Glyph.Archive -> {
                        rect(4f, 5f, 20f, 21f)
                        rect(3f, 2f, 21f, 6f)
                        line(9f, 10f, 15f, 10f)
                    }
                    Glyph.Cube -> {
                        line(12f, 2f, 21f, 7f, 21f, 17f, 12f, 22f, 3f, 17f, 3f, 7f, 12f, 2f)
                        line(3f, 7f, 12f, 12f, 21f, 7f)
                        line(12f, 12f, 12f, 22f)
                    }
                    Glyph.Image -> {
                        rect(3f, 3f, 21f, 21f)
                        circle(8f, 8f, 1.5f)
                        line(3f, 18f, 9f, 12f, 13f, 16f, 17f, 11f, 21f, 16f)
                    }
                    Glyph.Map -> {
                        line(
                            3f,
                            5f,
                            9f,
                            3f,
                            15f,
                            5f,
                            21f,
                            3f,
                            21f,
                            19f,
                            15f,
                            21f,
                            9f,
                            19f,
                            3f,
                            21f,
                            3f,
                            5f,
                        )
                        line(9f, 3f, 9f, 19f)
                        line(15f, 5f, 15f, 21f)
                    }
                    Glyph.Collision -> {
                        line(12f, 2f, 21f, 19f, 3f, 19f, 12f, 2f)
                        line(12f, 2f, 12f, 14f, 3f, 19f)
                        line(12f, 14f, 21f, 19f)
                    }
                    Glyph.File -> {
                        line(5f, 2f, 14f, 2f, 19f, 7f, 19f, 22f, 5f, 22f, 5f, 2f)
                        line(14f, 2f, 14f, 7f, 19f, 7f)
                        line(9f, 12f, 15f, 12f)
                        line(9f, 16f, 15f, 16f)
                    }
                    Glyph.Search -> {
                        circle(10.5f, 10.5f, 6.5f)
                        line(16f, 16f, 21f, 21f)
                    }
                    Glyph.Grid -> {
                        rect(3f, 3f, 9f, 9f)
                        rect(15f, 3f, 21f, 9f)
                        rect(3f, 15f, 9f, 21f)
                        rect(15f, 15f, 21f, 21f)
                    }
                    Glyph.List -> {
                        for (y in listOf(5f, 12f, 19f)) {
                            line(8f, y, 21f, y)
                            line(3f, y, 4f, y)
                        }
                    }
                    Glyph.Right -> line(9f, 5f, 16f, 12f, 9f, 19f)
                    Glyph.Back -> {
                        line(12f, 5f, 5f, 12f, 12f, 19f)
                        line(5f, 12f, 21f, 12f)
                    }
                    Glyph.Close -> {
                        line(6f, 6f, 18f, 18f)
                        line(18f, 6f, 6f, 18f)
                    }
                    Glyph.Plus -> {
                        line(12f, 4f, 12f, 20f)
                        line(4f, 12f, 20f, 12f)
                    }
                    Glyph.Settings -> {
                        circle(12f, 12f, 3f)
                        line(
                            9f,
                            3f,
                            15f,
                            3f,
                            16f,
                            6f,
                            20f,
                            7f,
                            22f,
                            12f,
                            20f,
                            17f,
                            16f,
                            18f,
                            15f,
                            21f,
                            9f,
                            21f,
                            8f,
                            18f,
                            4f,
                            17f,
                            2f,
                            12f,
                            4f,
                            7f,
                            8f,
                            6f,
                            9f,
                            3f,
                        )
                    }
                    Glyph.Down -> line(5f, 9f, 12f, 16f, 19f, 9f)
                    Glyph.Check -> line(4f, 12f, 9f, 17f, 20f, 6f)
                    Glyph.Download -> {
                        line(12f, 3f, 12f, 15f)
                        line(7f, 10f, 12f, 15f, 17f, 10f)
                        line(4f, 15f, 4f, 21f, 20f, 21f, 20f, 15f)
                    }
                    Glyph.Fit -> {
                        line(8f, 3f, 3f, 3f, 3f, 8f)
                        line(16f, 3f, 21f, 3f, 21f, 8f)
                        line(21f, 16f, 21f, 21f, 16f, 21f)
                        line(8f, 21f, 3f, 21f, 3f, 16f)
                    }
                    Glyph.Orbit -> {
                        addOval(Rect(2f, 7f, 22f, 17f))
                        circle(12f, 12f, 8f)
                    }
                    Glyph.Reset -> {
                        arcTo(Rect(4f, 4f, 20f, 20f), -90f, 300f, false)
                        line(3f, 3f, 3f, 9f, 9f, 9f)
                    }
                    Glyph.Globe -> {
                        circle(12f, 12f, 9f)
                        addOval(Rect(8f, 3f, 16f, 21f))
                        line(3f, 12f, 21f, 12f)
                    }
                    Glyph.Key -> {
                        circle(8f, 8f, 5f)
                        line(12f, 12f, 21f, 21f, 21f, 17f)
                        line(17f, 17f, 20f, 14f)
                    }
                    Glyph.Trash -> {
                        line(3f, 6f, 21f, 6f)
                        line(8f, 6f, 8f, 3f, 16f, 3f, 16f, 6f)
                        line(5f, 6f, 6f, 21f, 18f, 21f, 19f, 6f)
                        line(10f, 10f, 10f, 17f)
                        line(14f, 10f, 14f, 17f)
                    }
                    Glyph.Info -> {
                        circle(12f, 12f, 9f)
                        line(12f, 11f, 12f, 17f)
                        circle(12f, 7f, 0.25f)
                    }
                    Glyph.ArrowUp -> {
                        line(6f, 8f, 12f, 2f, 18f, 8f)
                        line(12f, 2f, 12f, 22f)
                    }
                    Glyph.Layers -> {
                        line(3f, 8f, 12f, 3f, 21f, 8f, 12f, 13f, 3f, 8f)
                        line(3f, 13f, 12f, 18f, 21f, 13f)
                        line(3f, 18f, 12f, 23f, 21f, 18f)
                    }
                    Glyph.Sun -> {
                        circle(12f, 12f, 4f)
                        for (i in 0..7) {
                            val a = i * Math.PI / 4
                            line(
                                (12 + 7 * kotlin.math.cos(a)).toFloat(),
                                (12 + 7 * kotlin.math.sin(a)).toFloat(),
                                (12 + 10 * kotlin.math.cos(a)).toFloat(),
                                (12 + 10 * kotlin.math.sin(a)).toFloat(),
                            )
                        }
                    }
                }
            }
        }
    Canvas(
        modifier
            .size(22.dp)
            .then(
                if (description != null) Modifier.semantics { contentDescription = description }
                else Modifier
            )
    ) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            drawPath(
                path,
                tint,
                style = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

fun AssetKind.glyph(): Glyph =
    when (this) {
        AssetKind.Folder -> Glyph.Folder
        AssetKind.Archive -> Glyph.Archive
        AssetKind.Texture,
        AssetKind.Image -> Glyph.Image
        AssetKind.Model -> Glyph.Cube
        AssetKind.Map -> Glyph.Map
        AssetKind.Collision -> Glyph.Collision
        AssetKind.Audio -> Glyph.Audio
        else -> Glyph.File
    }

fun AssetKind.label(): Int =
    when (this) {
        AssetKind.Folder -> R.string.kind_folder
        AssetKind.Archive -> R.string.kind_archive
        AssetKind.Texture -> R.string.kind_texture
        AssetKind.Model -> R.string.kind_model
        AssetKind.Map -> R.string.kind_map
        AssetKind.Collision -> R.string.kind_collision
        AssetKind.Text -> R.string.kind_text
        AssetKind.Image -> R.string.kind_image
        AssetKind.File -> R.string.kind_file
        AssetKind.Audio -> R.string.kind_audio
    }

@Composable
fun AssetKind.tint(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    return when (this) {
        AssetKind.Folder,
        AssetKind.Archive -> MaterialTheme.colorScheme.primary
        AssetKind.Model -> if (dark) ModelBlue else Color(0xFF386A8C)
        AssetKind.Texture,
        AssetKind.Image -> if (dark) TextureLilac else Color(0xFF75559D)
        AssetKind.Map -> if (dark) MapMint else Color(0xFF33765D)
        AssetKind.Collision -> if (dark) CollisionOrange else Color(0xFF9C622A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
fun TypeBadge(kind: AssetKind, modifier: Modifier = Modifier) {
    val color = kind.tint()
    Box(
        modifier.size(44.dp).background(color.copy(alpha = .10f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        RpfIcon(kind.glyph(), tint = color)
    }
}

@Composable
fun IconAction(
    glyph: Glyph,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .size(48.dp)
                .then(
                    if (active)
                        Modifier.background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(12.dp),
                        )
                    else Modifier
                ),
    ) {
        RpfIcon(
            glyph,
            tint =
                if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
                else if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            description = label,
        )
    }
}

@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 5.dp)
            .background(
                if (selected) colors.primaryContainer else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (selected) colors.primary.copy(alpha = .45f) else colors.outlineVariant,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

@Composable
fun SectionLabel(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        trailing()
    }
}

@Composable
fun EmptyState(
    glyph: Glyph,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            RpfIcon(glyph, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        action?.let {
            Spacer(Modifier.height(14.dp))
            it()
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun ErrorDetails(diagnostic: String) {
    var expanded by remember(diagnostic) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.hide_details else R.string.diagnostics))
    }
    if (expanded)
        Text(
            diagnostic,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
        )
}
