package com.nogirelay.app.media

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import com.nogirelay.app.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class VoicePlaybackState(
    val messageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speakerOn: Boolean = false,
)

class VoicePlaybackService : Service() {
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audioManager: AudioManager
    private var speakerOn = false
    private var outputRoutingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
        audioManager = getSystemService(AudioManager::class.java)
        serviceScope.launch {
            while (isActive) {
                publishPlaybackState()
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SET_SPEAKER) {
            speakerOn = intent.getBooleanExtra(EXTRA_SPEAKER_ON, false)
            outputRoutingJob?.cancel()
            outputRoutingJob = serviceScope.launch { setAudioOutput(speakerOn, fadeOnLegacyAndroid = true) }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SEEK) {
            val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
            val positionMs = intent.getIntExtra(EXTRA_POSITION_MS, -1)
            if (messageId != null && messageId == currentMessageId && positionMs >= 0) {
                player?.runCatching {
                    val duration = duration.takeIf { it > 0 } ?: Int.MAX_VALUE
                    seekTo(positionMs.coerceIn(0, duration))
                }
                publishPlaybackState()
            }
            return START_NOT_STICKY
        }

        val messageId = intent?.getStringExtra(EXTRA_MESSAGE_ID) ?: return START_NOT_STICKY
        val message = AppGraph.database.find(messageId) ?: return START_NOT_STICKY
        if (message.mediaUrl.isNullOrBlank()) return START_NOT_STICKY
        if (currentMessageId == messageId && player != null) {
            val activePlayer = player ?: return START_NOT_STICKY
            val isCurrentlyPlaying = runCatching { activePlayer.isPlaying }.getOrDefault(false)
            if (isCurrentlyPlaying) {
                activePlayer.pause()
                playing = false
            } else {
                runCatching {
                    activePlayer.start()
                    playing = true
                }.onFailure {
                    stopPlayback()
                    return START_NOT_STICKY
                }
            }
            publishPlaybackState()
            return START_NOT_STICKY
        }
        serviceScope.launch {
            val file = runCatching {
                withContext(Dispatchers.IO) {
                    MediaDownloader.enqueueIfNeeded(this@VoicePlaybackService, message)
                }
            }.getOrNull()
            if (file == null) {
                stopPlayback()
                return@launch
            }
            runCatching { play(messageId, file) }
                .onFailure { stopPlayback() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        releasePlayer()
        super.onDestroy()
    }

    private suspend fun play(messageId: String, mediaFile: File) {
        if (currentMessageId != messageId) {
            releasePlayer()
        }
        currentMessageId = messageId
        speakerOn = false
        playing = false
        publishPlaybackState()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    player?.runCatching { pause() }
                    playing = false
                    publishPlaybackState()
                }
            }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)

        val preparedPlayer = MediaPlayer()
        player = preparedPlayer
        preparedPlayer.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setVolume(1f, 1f)
            setDataSource(mediaFile.absolutePath)
            setOnPreparedListener {
                AppGraph.database.markPlayed(messageId)
                it.start()
                playing = true
                publishPlaybackState()
            }
            setOnCompletionListener {
                sendBroadcast(Intent(ACTION_PLAYBACK_FINISHED).setPackage(packageName))
                stopPlayback()
            }
            setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
        }
        setAudioOutput(speakerOn = false, fadeOnLegacyAndroid = false)
        preparedPlayer.prepareAsync()
    }

    private fun stopPlayback() {
        releasePlayer()
        stopSelf()
    }

    private fun publishPlaybackState() {
        val activePlayer = player
        val id = currentMessageId
        if (activePlayer == null || id == null) {
            if (_playbackState.value != VoicePlaybackState()) {
                _playbackState.value = VoicePlaybackState()
            }
            return
        }

        val position = runCatching { activePlayer.currentPosition }.getOrDefault(0).coerceAtLeast(0)
        val duration = runCatching { activePlayer.duration }.getOrDefault(0).coerceAtLeast(0)
        val isPlayingNow = playing && runCatching { activePlayer.isPlaying }.getOrDefault(false)
        val next = VoicePlaybackState(id, isPlayingNow, position, duration, speakerOn)
        if (_playbackState.value != next) _playbackState.value = next
    }

    private fun releasePlayer() {
        outputRoutingJob?.cancel()
        outputRoutingJob = null
        player?.runCatching { stop() }
        player?.release()
        player = null
        currentMessageId = null
        playing = false
        _playbackState.value = VoicePlaybackState()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private suspend fun setAudioOutput(speakerOn: Boolean, fadeOnLegacyAndroid: Boolean) {
        android.util.Log.d("VoicePlayback", "setAudioOutput start: speakerOn=$speakerOn, fade=$fadeOnLegacyAndroid, SDK=${android.os.Build.VERSION.SDK_INT}")
        val preferredPlayerDevice = if (speakerOn) {
            outputDevices().find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            preferredHeadsetDevice()
                ?: outputDevices().find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }

        // Use setPreferredDevice for MediaPlayer with USAGE_MEDIA
        val activePlayer = player
        if (activePlayer != null && preferredPlayerDevice != null) {
            val result = activePlayer.setPreferredDevice(preferredPlayerDevice)
            android.util.Log.d("VoicePlayback", "setPreferredDevice result=$result device=$preferredPlayerDevice")
        } else {
            android.util.Log.d("VoicePlayback", "Cannot set preferred device: player=$activePlayer device=$preferredPlayerDevice")
        }
    }

    private fun outputDevices(): Array<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    private fun preferredHeadsetDevice(): AudioDeviceInfo? {
        val devices = outputDevices()
        val priority = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        priority.forEach { type ->
            devices.find { it.type == type }?.let { return it }
        }
        return null
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "message_id"
        const val ACTION_PLAY = "com.nogirelay.app.PLAY_VOICE"
        const val ACTION_STOP = "com.nogirelay.app.STOP_VOICE"
        const val ACTION_SEEK = "com.nogirelay.app.SEEK_VOICE"
        const val ACTION_SET_SPEAKER = "com.nogirelay.app.SET_SPEAKER"
        const val ACTION_PLAYBACK_FINISHED = "com.nogirelay.app.VOICE_FINISHED"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_SPEAKER_ON = "speaker_on"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 200L
        private const val LEGACY_ROUTE_FADE_STEP_MS = 150L

        private val _playbackState = MutableStateFlow(VoicePlaybackState())
        val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

        @Volatile
        private var currentMessageId: String? = null

        @Volatile
        private var playing: Boolean = false

        fun isPlaying(messageId: String): Boolean =
            _playbackState.value.messageId == messageId && _playbackState.value.isPlaying

        fun seek(context: Context, messageId: String, positionMs: Int) {
            context.startService(
                Intent(context, VoicePlaybackService::class.java).apply {
                    action = ACTION_SEEK
                    putExtra(EXTRA_MESSAGE_ID, messageId)
                    putExtra(EXTRA_POSITION_MS, positionMs)
                },
            )
        }
    }
}
