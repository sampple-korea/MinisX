package com.minisx.app.live

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Call-scoped audio routing for a Live conversation: communication mode, and a
 * Bluetooth / wired headset ahead of the loudspeaker whenever one is connected,
 * switching live as devices come and go. Everything it changes is put back on
 * [close], except that it never overwrites a mode someone else has set since.
 */
internal class LiveAudioRoute(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var started = false
    private var sco = false

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = select()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = select()
    }

    fun begin() {
        previousMode = manager.mode
        @Suppress("DEPRECATION")
        previousSpeaker = manager.isSpeakerphoneOn
        started = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.registerAudioDeviceCallback(devices, handler)
        select()
    }

    @Suppress("DEPRECATION")
    private fun select() {
        if (!started) return
        try {
            val available: List<AudioDeviceInfo> =
                if (Build.VERSION.SDK_INT >= 31) manager.availableCommunicationDevices
                else manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            val preferred = available.filter { priority(it.type) < 99 }.minByOrNull { priority(it.type) }
            if (Build.VERSION.SDK_INT >= 31) {
                if (preferred != null && !manager.setCommunicationDevice(preferred)) manager.clearCommunicationDevice()
            } else {
                val bluetooth = preferred?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                if (bluetooth && !sco) { manager.startBluetoothSco(); manager.isBluetoothScoOn = true; sco = true }
                else if (!bluetooth && sco) { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false; sco = false }
                manager.isSpeakerphoneOn = preferred == null || preferred.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        } catch (_: SecurityException) {
            // Bluetooth access is optional: the wired / built-in route still works.
        }
    }

    @Suppress("DEPRECATION")
    override fun close() {
        if (!started) return
        started = false
        manager.unregisterAudioDeviceCallback(devices)
        if (Build.VERSION.SDK_INT >= 31) {
            manager.clearCommunicationDevice()
        } else {
            if (sco) { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false; sco = false }
            manager.isSpeakerphoneOn = previousSpeaker
        }
        // Do not clobber a phone call or another app that has changed the mode since.
        if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) manager.mode = previousMode
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> 1
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2
        else -> 99
    }
}
