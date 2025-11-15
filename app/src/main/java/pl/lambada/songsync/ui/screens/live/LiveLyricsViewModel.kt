package pl.lambada.songsync.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.services.MusicState
import pl.lambada.songsync.services.PlaybackInfo
import pl.lambada.songsync.util.LyricsUtils // <--- THIS WAS THE MISSING IMPORT

// This is the state for our new "Live Lyrics" screen
data class LiveLyricsUiState(
    val songTitle: String = "Listening for music...",
    val songArtist: String = "",
    val parsedLyrics: List<Pair<String, String>> = emptyList(),
    val currentLyricLine: String = "",
    val currentLyricIndex: Int = -1,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTimestamp: Long = 0L
)

// This is the "Live Lyrics Manager" (The Brain)
class LiveLyricsViewModel(
    private val lyricsProviderService: LyricsProviderService,
    private val userSettingsController: UserSettingsController
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveLyricsUiState())
    val uiState = _uiState.asStateFlow()

    private var lyricsFetchJob: Job? = null
    private var timestampUpdateJob: Job? = null

    init {
        // --- This is where the magic happens ---
        
        // 1. Listen for song changes from the "Ears" (NotificationListener)
        viewModelScope.launch {
            MusicState.currentSong.collectLatest { songPair ->
                lyricsFetchJob?.cancel() // Cancel any previous lyric search
                timestampUpdateJob?.cancel() // Stop the timer for the old song
                
                if (songPair == null) {
                    _uiState.value = LiveLyricsUiState() // No song, reset everything
                    return@collectLatest
                }

                val (title, artist) = songPair
                _uiState.value = LiveLyricsUiState(
                    songTitle = title,
                    songArtist = artist,
                    isLoading = true,
                    isPlaying = true
                )

                // 2. Tell the "Librarian" to get the lyrics
                lyricsFetchJob = launch {
                    val lyricsString = try {
                        lyricsProviderService.getSyncedLyrics(
                            title,
                            artist,
                            userSettingsController.selectedProvider,
                            userSettingsController.includeTranslation,
                            userSettingsController.includeRomanization,
                            userSettingsController.multiPersonWordByWord,
                            userSettingsController.unsyncedFallbackMusixmatch
                        )
                    } catch (e: Exception) {
                        null // Failed to get lyrics
                    }

                    if (lyricsString == null) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            currentLyricLine = "No lyrics found for this song."
                        )
                        return@launch
                    }

                    // 3. Tell the "Translator" to parse the lyrics
                    val parsedLyrics = LyricsUtils.parseLyrics(lyricsString)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        parsedLyrics = parsedLyrics
                    )

                    // 4. Start the timer!
                    startPlaybackTimer()
                }
            }
        }
    }

    private fun startPlaybackTimer() {
        timestampUpdateJob?.cancel()
        timestampUpdateJob = viewModelScope.launch {
            MusicState.playbackInfo.collect { playbackInfo ->
                if (playbackInfo == null) return@collect
                
                _uiState.value = _uiState.value.copy(isPlaying = playbackInfo.isPlaying)
                
                if (!playbackInfo.isPlaying) {
                    // If paused, just update the timestamp once and stop
                    updateCurrentLyric(playbackInfo.position)
                } else {
                    // If playing, start a loop to update the time
                    while (true) {
                        val (isPlaying, position, timestamp, speed) = MusicState.playbackInfo.value ?: break
                        if (!isPlaying) break // Stop loop if paused
                        
                        // Calculate the "real-time" position
                        val timePassed = (System.currentTimeMillis() - timestamp) * speed
                        val currentPosition = position + timePassed.toLong()
                        
                        updateCurrentLyric(currentPosition)
                        
                        delay(200) // Update 5 times per second
                    }
                }
            }
        }
    }

    private fun updateCurrentLyric(currentPosition: Long) {
        val lyrics = _uiState.value.parsedLyrics
        if (lyrics.isEmpty()) return

        // Find the correct lyric line for the current time
        val currentIndex = lyrics.indexOfLast { (time, _) ->
            // Convert "01:23.456" string to milliseconds
            val parts = time.split(":", ".")
            val minutes = parts[0].toLong()
            val seconds = parts[1].toLong()
            val millis = parts[2].toLong()
            val lyricTime = (minutes * 60 * 1000) + (seconds * 1000) + millis
            
            lyricTime <= currentPosition
        }

        if (currentIndex != -1 && currentIndex != _uiState.value.currentLyricIndex) {
            _uiState.value = _uiState.value.copy(
                currentLyricLine = lyrics[currentIndex].second,
                currentLyricIndex = currentIndex
            )
        }
        
        // Also update the raw timestamp for the UI
        _uiState.value = _uiState.value.copy(currentTimestamp = currentPosition)
    }


    // This part is just to help the app create this new "Manager"
    companion object {
        fun Factory(
            lyricsProviderService: LyricsProviderService,
            userSettingsController: UserSettingsController
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(LiveLyricsViewModel::class.java)) {
                    return LiveLyricsViewModel(lyricsProviderService, userSettingsController) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}