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
import pl.lambada.songsync.util.parseLyrics

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
                // *** THIS IS THE FIX ***
                // We now reset the *entire* state, clearing old lyrics immediately.
                _uiState.value = LiveLyricsUiState(
                    songTitle = title,
                    songArtist = artist,
                    isLoading = true,
                    isPlaying = true, // Assume playing
                    parsedLyrics = emptyList(), // <--- EXPLICITLY CLEAR LYRICS
                    currentLyricLine = "",      // <--- EXPLICITLY CLEAR LINE
                    currentLyricIndex = -1        // <--- EXPLICITLY CLEAR INDEX
                )

                // 2. Tell the "Librarian" to get the lyrics
                lyricsFetchJob = launch {
                    
                    // *** THIS IS THE NEW, CRUCIAL STEP ***
                    // Step A: Go get the "library card" (SongInfo) first!
                    val songInfo = try {
                        lyricsProviderService.getSongInfo(
                            query = SongInfo(title, artist),
                            offset = 0,
                            provider = userSettingsController.selectedProvider
                        )
                    } catch (e: Exception) {
                        null
                    }

                    // If we couldn't even find the song, show an error.
                    if (songInfo == null) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            currentLyricLine = "Song not found by provider."
                        )
                        return@launch
                    }

                    // Step B: NOW get the lyrics using the *correct* info
                    val lyricsString = try {
                        lyricsProviderService.getSyncedLyrics(
                            // Pass the *found* title/artist, not the raw one
                            songInfo.songName ?: title, 
                            songInfo.artistName ?: artist,
                            userSettingsController.selectedProvider,
                            userSettingsController.includeTranslation,
                            userSettingsController.includeRomanization,
                            userSettingsController.multiPersonWordByWord,
                            userSettingsController.unsyncedFallbackMusixmatch
                        )
                    } catch (e: Exception) {
                        null
                    }

                    if (lyricsString == null) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            currentLyricLine = "No lyrics found for this song."
                        )
                        return@launch
                    }

                    // 3. Tell the "Translator" to parse the lyrics
                    val parsedLyrics = parseLyrics(lyricsString)
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
                
                timestampUpdateJob?.cancel() // Stop any previous timer loop
                _uiState.value = _uiState.value.copy(isPlaying = playbackInfo.isPlaying)

                if (playbackInfo.isPlaying) {
                    // If it's playing, start a new timer loop
                    timestampUpdateJob = launch {
                        while (true) {
                            // We must read the latest info on every loop
                            val (isPlaying, basePosition, baseTime, speed) = MusicState.playbackInfo.value ?: break
                            if (!isPlaying) break // Stop if it gets paused

                            val timePassed = (System.currentTimeMillis() - baseTime) * speed
                            val currentPosition = basePosition + timePassed.toLong()
                            updateCurrentLyric(currentPosition)
                            
                            delay(200) // Update 5x/sec
                        }
                    }
                } else {
                    // If it's paused, just update the lyric one last time
                    updateCurrentLyric(playbackInfo.position)
                }
            }
        }
    }

    private fun updateCurrentLyric(currentPosition: Long) {
        val lyrics = _uiState.value.parsedLyrics
        if (lyrics.isEmpty()) return

        // Find the correct lyric line for the current time
        val currentIndex = lyrics.indexOfLast { (time, _) ->
            try {
                // Convert "01:23.456" string to milliseconds
                val parts = time.split(":", ".")
                val minutes = parts[0].toLong()
                val seconds = parts[1].toLong()
                val millis = parts[2].toLong()
                val lyricTime = (minutes * 60 * 1000) + (seconds * 1000) + millis
                
                lyricTime <= currentPosition
            } catch (e: Exception) {
                // Failsafe for a bad lyric line
                false
            }
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