package com.nova.integrations

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import com.nova.core.BatteryInfo
import com.nova.core.BluetoothInfo
import com.nova.core.DataHub
import com.nova.core.WifiInfo

/**
 * Battery, Wi-Fi, Bluetooth and audio-route status via broadcasts — only while the app is
 * in the foreground (registered in onResume, unregistered in onPause).
 */
class SystemStatus(private val context: Context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent) else { readWifi(); readBluetooth(); readAudioRoute() }
            DataHub.changed()
        }
    }
    private var registered = false

    fun start() {
        if (registered) return
        registered = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, f)?.let { readBattery(it) }
        readWifi(); readBluetooth(); readAudioRoute()
        DataHub.changed()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun refresh() { readWifi(); readBluetooth(); readAudioRoute(); DataHub.changed() }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        DataHub.battery = BatteryInfo(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            plugged = plugged,
            tempC = if (temp == Int.MIN_VALUE) Float.NaN else temp / 10f,
        )
    }

    @SuppressLint("MissingPermission")
    private fun readWifi() {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        @Suppress("DEPRECATION")
        val info = runCatching { wm?.connectionInfo }.getOrNull()
        @Suppress("DEPRECATION")
        val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
        @Suppress("DEPRECATION")
        val level = info?.let { WifiManager.calculateSignalLevel(it.rssi, 3) } ?: 0
        DataHub.wifi = WifiInfo(enabled = wm?.isWifiEnabled == true, connected = onWifi, name = if (onWifi) ssid else "", signal = level, online = online)
    }

    private fun btPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun readBluetooth() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) { DataHub.bluetooth = BluetoothInfo(available = false); return }
        if (!btPermission()) { DataHub.bluetooth = BluetoothInfo(available = true, permission = false); return }
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        val paired = runCatching { adapter.bondedDevices.mapNotNull { it.name } }.getOrDefault(emptyList())
        val audioConnected = runCatching {
            adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED ||
                adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
        val name = if (audioConnected) audioDeviceName() ?: "Audio device" else ""
        DataHub.bluetooth = BluetoothInfo(available = true, enabled = enabled, connectedName = name, paired = paired, permission = true)
    }

    private fun audioDeviceName(): String? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }?.productName?.toString()
    }

    /** Where phone audio is going right now (Bluetooth routing ≠ Spotify Connect). */
    private fun readAudioRoute() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        DataHub.audioRoute = when {
            outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> "Bluetooth · ${audioDeviceName() ?: "speaker"}"
            outs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } -> "Wired headphones"
            outs.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET } -> "USB audio"
            else -> "Phone speaker"
        }
    }
}
