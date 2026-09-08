package com.nogirelay.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.nogirelay.app.R
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.ui.NogiRelayTheme
import com.nogirelay.app.ui.RemoteImage
import java.util.Locale
import kotlin.math.min

class IncomingCallActivity : ComponentActivity() {
    private var ringtonePlayer: MediaPlayer? = null
    private var ringtoneFocusRequest: AudioFocusRequest? = null
    private lateinit var message: RelayMessage
    private lateinit var audioManager: AudioManager
    private lateinit var vibrationControl: IncomingCallVibrationControl
    private lateinit var proximityControl: ProximityScreenControl
    private var callState by mutableStateOf(CallState.RINGING)
    private var speakerOn by mutableStateOf(false)
    private var finishingCall = false

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                VoicePlaybackService.ACTION_PLAYBACK_FINISHED -> finishCall()
                ACTION_FINISH_CALL -> {
                    val targetId = intent.getStringExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID)
                    if (targetId == null || targetId == message.id) finishCall(stopPlayback = true)
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any(::isOfficialHeadsetDevice)) {
                if (callState == CallState.PLAYING) {
                    // A newly connected headset takes priority over speaker mode.
                    setSpeakerEnabled(false)
                } else if (callState == CallState.RINGING && ringtonePlayer == null) {
                    // In silent/vibrate mode, a headset is allowed to receive the ringtone privately.
                    startRingtone()
                }
            }
            updateProximityLock()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any(::isOfficialHeadsetDevice)) {
                if (callState == CallState.PLAYING) {
                    // Re-resolve the fallback route after unplugging the selected headset.
                    setSpeakerEnabled(false)
                } else if (callState == CallState.RINGING &&
                    audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL &&
                    !isExternalAudioConnected()
                ) {
                    // Never leak a silent-mode private ringtone to the phone speaker after unplugging.
                    stopRingtone()
                }
            }
            updateProximityLock()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.initialize(this)
        audioManager = getSystemService(AudioManager::class.java)
        vibrationControl = OfficialIncomingCallVibrationControl(this)
        proximityControl = OfficialProximityScreenControl(this)
        configureWindow()

        message = resolveMessage(intent) ?: run {
            finish()
            return
        }
        registerCallReceiver()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        val autoAnswer = intent.getBooleanExtra(IncomingCallNotifier.EXTRA_AUTO_ANSWER, false)
        callState = if (autoAnswer) CallState.PLAYING else CallState.RINGING
        if (autoAnswer) startVoicePlayback() else {
            startRingtone()
            vibrationControl.start()
        }

        setContent {
            NogiRelayTheme(darkTheme = false) {
                val playbackState by VoicePlaybackService.playbackState.collectAsState()
                LaunchedEffect(playbackState.isPlaying, speakerOn) {
                    updateProximityLock(playbackState)
                }
                BackHandler { decline() }
                IncomingCallScreen(
                    message = message,
                    state = callState,
                    speakerOn = speakerOn,
                    playbackState = playbackState,
                    onAnswer = ::answer,
                    onDecline = ::decline,
                    onToggleSpeaker = ::toggleSpeaker,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(IncomingCallNotifier.EXTRA_AUTO_ANSWER, false)) answer()
    }

    override fun onDestroy() {
        stopRingtone()
        vibrationControl.stop()
        proximityControl.close()
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn = false }
        runCatching { unregisterReceiver(finishReceiver) }
        super.onDestroy()
    }

    private fun resolveMessage(intent: Intent): RelayMessage? {
        val messageId = intent.getStringExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID) ?: return null
        return AppGraph.database.find(messageId)
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun registerCallReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_FINISH_CALL)
            addAction(VoicePlaybackService.ACTION_PLAYBACK_FINISHED)
        }
        ContextCompat.registerReceiver(this, finishReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun answer() {
        if (callState != CallState.RINGING) return
        callState = CallState.PLAYING
        stopRingtone()
        vibrationControl.stop()
        IncomingCallNotifier.cancel(this, message.id)
        startVoicePlayback()
    }

    private fun startVoicePlayback() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        speakerOn = false
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn = false }
        AppGraph.database.markPlayed(message.id)
        startService(
            Intent(this, VoicePlaybackService::class.java).apply {
                action = VoicePlaybackService.ACTION_PLAY
                putExtra(VoicePlaybackService.EXTRA_MESSAGE_ID, message.id)
            },
        )
    }

    private fun toggleSpeaker() {
        if (callState != CallState.PLAYING) return
        setSpeakerEnabled(!speakerOn)
    }

    private fun setSpeakerEnabled(enabled: Boolean) {
        speakerOn = enabled
        startService(
            Intent(this, VoicePlaybackService::class.java).apply {
                action = VoicePlaybackService.ACTION_SET_SPEAKER
                putExtra(VoicePlaybackService.EXTRA_SPEAKER_ON, enabled)
            },
        )
        updateProximityLock()
    }

    private fun decline() {
        IncomingCallNotifier.cancel(this, message.id)
        finishCall(stopPlayback = true)
    }

    private fun finishCall(stopPlayback: Boolean = false) {
        if (finishingCall) return
        finishingCall = true
        if (stopPlayback) {
            startService(Intent(this, VoicePlaybackService::class.java).setAction(VoicePlaybackService.ACTION_STOP))
        }
        stopRingtone()
        vibrationControl.stop()
        proximityControl.close()
        if (::message.isInitialized && message.isTestMessage) AppGraph.database.deleteTestMessages()
        finishAndRemoveTask()
    }

    private fun startRingtone() {
        if (ringtonePlayer != null) return
        
        val headset = preferredExternalAudioDevice()
        // Preserve system silence on the phone itself, while still alerting through a connected headset.
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL && headset == null) return
        
        val silentModeWithHeadset = audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL && headset != null
        
        val ringtoneAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ringtoneAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    ringtonePlayer?.runCatching { pause() }
                }
            }
            .build()
        ringtoneFocusRequest = focusRequest
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            ringtoneFocusRequest = null
            return
        }
        ringtonePlayer = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(ringtoneAttributes)
                resources.openRawResourceFd(R.raw.ringtone).use { descriptor ->
                    setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                }
                // In silent mode with headset, let system route automatically; otherwise use setPreferredDevice
                if (headset != null && !silentModeWithHeadset) {
                    check(setPreferredDevice(headset)) { "Unable to route ringtone to connected headset" }
                }
                setVolume(1f, 1f)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
    }

    private fun stopRingtone() {
        ringtonePlayer?.runCatching { stop() }
        ringtonePlayer?.release()
        ringtonePlayer = null
        ringtoneFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        ringtoneFocusRequest = null
    }

    private fun updateProximityLock(playback: VoicePlaybackState = VoicePlaybackService.playbackState.value) {
        if (!::message.isInitialized) return
        val shouldEnable = callState == CallState.PLAYING &&
            playback.messageId == message.id && playback.isPlaying &&
            !speakerOn && !isExternalAudioConnected()
        proximityControl.setEnabled(shouldEnable)
    }

    private fun isExternalAudioConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any(::isOfficialHeadsetDevice)

    private fun preferredExternalAudioDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull(::isOfficialHeadsetDevice)

    companion object {
        const val ACTION_FINISH_CALL = "com.nogirelay.app.FINISH_CALL"
    }
}

private fun isOfficialHeadsetDevice(device: AudioDeviceInfo): Boolean = when (device.type) {
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    -> true
    else -> false
}

private enum class CallState { RINGING, PLAYING }

private val notoSansJpFamily = FontFamily(
    Font(R.font.noto_sans_jp_regular, FontWeight.Normal),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IncomingCallScreen(
    message: RelayMessage,
    state: CallState,
    speakerOn: Boolean,
    playbackState: VoicePlaybackState,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.nogi_call_background)),
    ) {
        val scale = min(maxWidth.value / 375f, maxHeight.value / 667f)
        val bottomHeight = dimensionResource(R.dimen.nogi_call_bottom_bar_height) * scale
        val availableHeight = maxHeight

        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((availableHeight - bottomHeight).coerceAtLeast(0.dp))
                    .background(colorResource(R.color.nogi_call_speaker_inactive)),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(
                    url = message.phoneImageUrl,
                    contentDescription = message.incomingCallFrom ?: message.memberName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    loadCachedImmediately = true,
                    placeholderResId = R.drawable.ic_person_official,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bottomHeight)
                    .background(colorResource(R.color.nogi_call_background))
                    .padding(horizontal = dimensionResource(R.dimen.nogi_call_horizontal_padding) * scale),
            ) {
                CallCircleButton(
                    background = colorResource(R.color.nogi_call_end_background),
                    size = dimensionResource(R.dimen.nogi_call_button_size) * scale,
                    margin = dimensionResource(R.dimen.nogi_call_button_margin) * scale,
                    onClick = onDecline,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_phone_down_official),
                        contentDescription = "通話を終了",
                        tint = colorResource(R.color.nogi_call_control_text),
                        modifier = Modifier.size(dimensionResource(R.dimen.nogi_call_end_icon_size) * scale),
                    )
                }

                CallIdentity(
                    name = message.incomingCallFrom ?: message.memberName,
                    state = state,
                    elapsedMs = playbackState.positionMs,
                    scale = scale,
                    modifier = Modifier.weight(1f),
                )

                if (state == CallState.RINGING) {
                    CallCircleButton(
                        background = colorResource(R.color.nogi_call_start_background),
                        size = dimensionResource(R.dimen.nogi_call_button_size) * scale,
                        margin = dimensionResource(R.dimen.nogi_call_button_margin) * scale,
                        onClick = onAnswer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_phone_up_official),
                            contentDescription = "応答",
                            tint = colorResource(R.color.nogi_call_control_text),
                            modifier = Modifier.size(dimensionResource(R.dimen.nogi_call_answer_icon_size) * scale),
                        )
                    }
                } else {
                    CallCircleButton(
                        background = colorResource(R.color.nogi_call_background),
                        size = dimensionResource(R.dimen.nogi_call_button_size) * scale,
                        margin = dimensionResource(R.dimen.nogi_call_button_margin) * scale,
                        onClick = onToggleSpeaker,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_audio_speaker_official),
                            contentDescription = "スピーカー",
                            tint = colorResource(
                                if (speakerOn) R.color.nogi_call_speaker_active
                                else R.color.nogi_call_speaker_inactive,
                            ),
                            modifier = Modifier.size(dimensionResource(R.dimen.nogi_call_speaker_icon_size) * scale),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallIdentity(
    name: String,
    state: CallState,
    elapsedMs: Int,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = name,
            color = colorResource(R.color.nogi_call_text),
            fontSize = (dimensionResource(R.dimen.nogi_call_name_text_size).value * scale).sp,
            fontFamily = notoSansJpFamily,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(iterations = Int.MAX_VALUE),
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.nogi_call_name_status_spacing) * scale))
        Text(
            text = if (state == CallState.RINGING) "着信中…" else formatElapsed(elapsedMs),
            color = colorResource(R.color.nogi_call_text),
            fontSize = (dimensionResource(
                if (state == CallState.RINGING) R.dimen.nogi_call_status_text_size
                else R.dimen.nogi_call_duration_text_size,
            ).value * scale).sp,
            fontFamily = notoSansJpFamily,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun CallCircleButton(
    background: Color,
    size: Dp,
    margin: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(margin)
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        content()
    }
}

private fun formatElapsed(positionMs: Int): String {
    val seconds = positionMs.coerceAtLeast(0) / 1_000
    return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}
