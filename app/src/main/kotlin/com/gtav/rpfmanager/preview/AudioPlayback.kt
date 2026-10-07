package com.gtav.rpfmanager.preview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.File

/** A foreground-only player. It stops on navigation, backgrounding and unplugging. */
class AudioPlayback(context: Context, file: File) : Closeable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val player = MediaPlayer()
    private var closed = false
    var ready by mutableStateOf(false)
        private set

    var playing by mutableStateOf(false)
        private set

    var position by mutableIntStateOf(0)
        private set

    var duration by mutableIntStateOf(0)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) pause() }
            .build()
    private val unplug =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                pause()
            }
        }

    init {
        ContextCompat.registerReceiver(
            this.context,
            unplug,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener {
            if (!closed) {
                duration = it.duration.coerceAtLeast(1)
                ready = true
            }
        }
        player.setOnCompletionListener {
            playing = false
            position = duration
            manager.abandonAudioFocusRequest(focus)
        }
        player.setOnErrorListener { _, what, extra ->
            if (!closed) {
                error = "MediaPlayer: $what / $extra"
                ready = false
                playing = false
                manager.abandonAudioFocusRequest(focus)
            }
            true
        }
        runCatching {
                player.setDataSource(file.path)
                player.prepareAsync()
            }
            .onFailure { error = it.message }
    }

    fun toggle() {
        if (!ready || closed) return
        if (playing) {
            pause()
            return
        }
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error = "Audio focus unavailable"
            return
        }
        runCatching {
                if (position >= duration) {
                    player.seekTo(0)
                    position = 0
                }
                player.start()
                playing = true
                error = null
            }
            .onFailure {
                error = it.message
                manager.abandonAudioFocusRequest(focus)
            }
    }

    fun pause() {
        if (closed) return
        if (ready && playing)
            runCatching {
                player.pause()
                position = player.currentPosition
            }
        playing = false
        manager.abandonAudioFocusRequest(focus)
    }

    fun seek(milliseconds: Int) {
        if (!ready || closed) return
        position = milliseconds.coerceIn(0, duration)
        runCatching { player.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST) }
            .onFailure { error = it.message }
    }

    fun tick() {
        if (ready && playing && !closed)
            runCatching { position = player.currentPosition.coerceIn(0, duration) }
    }

    override fun close() {
        if (closed) return
        pause()
        closed = true
        ready = false
        player.release()
        context.unregisterReceiver(unplug)
    }
}
