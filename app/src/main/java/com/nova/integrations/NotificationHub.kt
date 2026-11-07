package com.nova.integrations

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nova.core.DataHub
import com.nova.core.MediaInfo
import com.nova.core.NotificationItem
import com.nova.storage.Prefs

/**
 * Notification access (user-enabled in Android settings) gives NØVA two things:
 *  - selected notifications for the Notifications widget (filtered by an app allow-list),
 *  - the active media sessions, so Spotify (or any player) can be shown and controlled
 *    without a Spotify developer account: play/pause, next, previous, seek, metadata, artwork.
 */
class NovaNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        Prefs.init(this)
        instance = this
        refreshNotifications()
        MediaHub.attach(this)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        MediaHub.detach()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refreshNotifications()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refreshNotifications()

    private fun refreshNotifications() {
        val allowed = Prefs.raw.getString("notifApps", "")!!.split(',').filter { it.isNotBlank() }.toSet()
        val pm = packageManager
        val list = runCatching { activeNotifications?.toList() ?: emptyList() }.getOrDefault(emptyList())
            .filter { it.packageName != packageName && it.isClearable && !it.isOngoing }
            .filter { allowed.isEmpty() || it.packageName in allowed }
            .sortedByDescending { it.postTime }
            .take(8)
            .map { sbn ->
                val ex = sbn.notification.extras
                NotificationItem(
                    app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName),
                    title = ex.getCharSequence("android.title")?.toString() ?: "",
                    text = ex.getCharSequence("android.text")?.toString() ?: "",
                    time = sbn.postTime, key = sbn.key,
                )
            }
        DataHub.notifications = list
        DataHub.changed()
    }

    companion object {
        @Volatile var instance: NovaNotificationService? = null

        fun enabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, NovaNotificationService::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}

/** Active media session tracking and transport controls. */
object MediaHub {
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var component: ComponentName? = null
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
    }

    fun attach(context: Context) {
        val m = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val c = ComponentName(context, NovaNotificationService::class.java)
        manager = m; component = c
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsListener, c)
            pick(m.getActiveSessions(c))
        }.onFailure { DataHub.mediaStatus = "Notification access needed for music"; DataHub.changed() }
    }

    fun detach() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
        DataHub.media = null; DataHub.mediaStatus = "Enable notification access to see music"; DataHub.changed()
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull { it.packageName == "com.spotify.music" } ?: list?.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback)
        }
        publish()
    }

    private var wasPlaying = false

    private fun publish() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) {
            DataHub.media = null
            DataHub.mediaStatus = "Nothing playing — open Spotify"
        } else {
            val st = c.playbackState
            val art: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            DataHub.media = MediaInfo(
                app = appName(c.packageName), packageName = c.packageName,
                title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
                album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                playing = st?.state == PlaybackState.STATE_PLAYING,
                positionMs = st?.position ?: 0, durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                positionAt = System.currentTimeMillis(), art = art?.let { scaled(it) },
            )
            DataHub.mediaStatus = ""
        }
        val playing = DataHub.media?.playing == true
        if (playing != wasPlaying) { wasPlaying = playing; onPlayingChanged?.invoke(playing) }
        DataHub.changed()
    }

    private fun scaled(b: Bitmap): Bitmap = if (b.width > 400) Bitmap.createScaledBitmap(b, 400, 400 * b.height / b.width, true) else b

    private fun appName(pkg: String) = when (pkg) {
        "com.spotify.music" -> "Spotify"; "com.google.android.apps.youtube.music" -> "YouTube Music"
        "com.jio.media.jiobeats" -> "JioSaavn"; "com.amazon.mp3" -> "Amazon Music"; else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    val available: Boolean get() = controller != null
    fun playPause() { val c = controller ?: return; if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play() }
    fun play() = controller?.transportControls?.play()
    fun pause() = controller?.transportControls?.pause()
    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = controller?.transportControls?.seekTo(ms)
}
