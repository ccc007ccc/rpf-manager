package com.gtav.rpfmanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.data.model.ResourcePreview
import com.gtav.rpfmanager.preview.AudioPlayback
import com.gtav.rpfmanager.viewmodel.ArchiveViewModel
import java.util.Locale
import kotlinx.coroutines.delay

private fun timestamp(ms: Long): String =
    String.format(Locale.ROOT, "%d:%02d", ms / 60000, (ms / 1000) % 60)

@Composable
fun AudioViewer(audio: ResourcePreview.Audio, vm: ArchiveViewModel, modifier: Modifier) {
    val track = audio.tracks.getOrNull(audio.active) ?: return
    Column(modifier.fillMaxWidth()) {
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(audio.tracks) { i, t ->
                ChoiceChip(
                    stringResource(R.string.track_number, i + 1),
                    audio.active == i,
                    Modifier.testTag("audio_track_$i"),
                ) {
                    vm.chooseAudio(i)
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RpfIcon(Glyph.Audio, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text(
                track.id,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.audio_track_info, track.codec, track.rate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            when {
                audio.loading -> {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.audio_loading), Modifier.padding(top = 12.dp))
                }
                audio.file != null -> AudioTransport(audio, vm)
                audio.diagnostic != null -> {
                    Text(stringResource(R.string.audio_error))
                    ErrorDetails(audio.diagnostic)
                }
                else ->
                    Text(
                        stringResource(
                            when (track.reason) {
                                "encrypted" -> R.string.audio_encrypted
                                "codec" -> R.string.audio_codec
                                "limit" -> R.string.audio_limit
                                else -> R.string.audio_unsupported
                            }
                        )
                    )
            }
        }
    }
}

@Composable
private fun AudioTransport(audio: ResourcePreview.Audio, vm: ArchiveViewModel) {
    val context = LocalContext.current
    val player = remember(audio.file) { AudioPlayback(context, requireNotNull(audio.file)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)
                player.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.close()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(200)
            player.tick()
        }
    }
    var seek by remember(player) { mutableStateOf<Float?>(null) }
    val position = seek ?: player.position.toFloat()
    val duration = player.duration.coerceAtLeast(1)
    val label = stringResource(R.string.seek_audio)
    Slider(
        value = position.coerceIn(0f, duration.toFloat()),
        onValueChange = { seek = it },
        valueRange = 0f..duration.toFloat(),
        enabled = player.ready,
        modifier =
            Modifier.fillMaxWidth().testTag("audio_seek").semantics { contentDescription = label },
        onValueChangeFinished = {
            seek?.let { player.seek(it.toInt()) }
            seek = null
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            timestamp(position.toLong()),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            timestamp(
                if (player.ready) duration.toLong() else audio.tracks[audio.active].durationMs
            ),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(20.dp))
    FilledTonalButton(
        onClick = player::toggle,
        enabled = player.ready,
        modifier = Modifier.height(56.dp).testTag("audio_play"),
    ) {
        RpfIcon(if (player.playing) Glyph.Pause else Glyph.Play)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(if (player.playing) R.string.pause_audio else R.string.play_audio))
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = vm::exportWav, modifier = Modifier.testTag("export_wav")) {
        RpfIcon(Glyph.Download, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.export_wav))
    }
    player.error?.let {
        Text(stringResource(R.string.audio_error))
        ErrorDetails(it)
    }
}
