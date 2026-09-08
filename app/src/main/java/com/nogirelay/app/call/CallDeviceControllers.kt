package com.nogirelay.app.call

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

internal interface IncomingCallVibrationControl {
    fun start()
    fun stop()
}

internal class OfficialIncomingCallVibrationControl(context: Context) : IncomingCallVibrationControl {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    override fun start() {
        if (audioManager.ringerMode !in setOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE)) return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(1_000L, 1_000L), 0))
    }

    override fun stop() {
        vibrator.cancel()
    }
}

internal interface ProximityScreenControl {
    fun setEnabled(enabled: Boolean)
    fun close()
}

internal class OfficialProximityScreenControl(context: Context) : ProximityScreenControl {
    private val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
        WAKE_LOCK_TAG,
    )

    override fun setEnabled(enabled: Boolean) {
        if (enabled && !wakeLock.isHeld) {
            wakeLock.acquire()
        } else if (!enabled && wakeLock.isHeld) {
            releaseWaitingForFarState()
        }
    }

    override fun close() {
        if (wakeLock.isHeld) releaseWaitingForFarState()
    }

    private fun releaseWaitingForFarState() {
        wakeLock.release(RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    private companion object {
        const val WAKE_LOCK_TAG = "com.sonydna.messages.app:VoicePlayerWakeLock"
        const val RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY = 1
    }
}
