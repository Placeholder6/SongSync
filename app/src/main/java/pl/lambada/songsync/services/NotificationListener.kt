package pl.lambada.songsync.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationListener : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null
    
    private val mMediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }

        override fun onSessionDestroyed() {
            currentController?.unregisterCallback(this)
            currentController = null
            MusicState.updateSong(null, null, null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private fun startForeground() {
        val channelId = "songsync_live_lyrics"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Live Lyrics Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("SongSync is active")
            .setContentText("Listening for music...")
            .setSmallIcon(pl.lambada.songsync.R.drawable.ic_notification)
            .build()

        startForeground(101, notification)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenToActiveSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        currentController?.unregisterCallback(mMediaControllerCallback)
    }

    private fun listenToActiveSessions() {
        try {
            val componentName = ComponentName(this, NotificationListener::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            val activeController = controllers?.firstOrNull()
            
            if (activeController != null) {
                registerCallback(activeController)
            }
            
            mediaSessionManager?.addOnActiveSessionsChangedListener({ newControllers ->
                val newController = newControllers?.firstOrNull()
                if (newController != null) {
                    registerCallback(newController)
                } else {
                    currentController?.unregisterCallback(mMediaControllerCallback)
                    currentController = null
                    MusicState.updateSong(null, null, null)
                }
            }, componentName)
            
        } catch (e: SecurityException) {
            // Permission hasn't been granted yet
        }
    }

    private fun registerCallback(controller: MediaController) {
        if (currentController?.sessionToken == controller.sessionToken) return
        
        currentController?.unregisterCallback(mMediaControllerCallback)
        currentController = controller
        controller.registerCallback(mMediaControllerCallback)
        
        updateMetadata(controller.metadata)
        updatePlaybackState(controller.playbackState)
    }
    
    private fun updateMetadata(metadata: MediaMetadata?) {
        metadata?.let {
            val title = it.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val artUri = it.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) 
                         ?: it.getString(MediaMetadata.METADATA_KEY_ART_URI)
            
            Log.d("SongSync", "Detected Song: $title by $artist (Art: $artUri)")
            MusicState.updateSong(title, artist, artUri)
        }
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        state?.let {
            val isPlaying = it.state == PlaybackState.STATE_PLAYING
            val position = it.position
            MusicState.updateState(isPlaying, position, System.currentTimeMillis(), it.playbackSpeed)
        }
    }
}

// --- THE BRIDGE ---
// Stores Title, Artist, AND ArtUri now
object MusicState {
    // Triple<Title, Artist, ArtUri?>
    private val _currentSong = MutableStateFlow<Triple<String, String, String?>?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _playbackInfo = MutableStateFlow<PlaybackInfo?>(null)
    val playbackInfo = _playbackInfo.asStateFlow()

    fun updateSong(title: String?, artist: String?, artUri: String?) {
        _currentSong.value = null
        if (title != null && artist != null) {
            _currentSong.value = Triple(title, artist, artUri)
        }
    }

    fun updateState(isPlaying: Boolean, position: Long, updateTime: Long, speed: Float) {
        _playbackInfo.value = PlaybackInfo(isPlaying, position, updateTime, speed)
    }
}

data class PlaybackInfo(
    val isPlaying: Boolean,
    val position: Long,
    val timestamp: Long,
    val speed: Float
)