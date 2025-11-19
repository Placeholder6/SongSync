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
import pl.lambada.songsync.util.Providers

data class LiveLyricsUiState(
    val songTitle: String = "Listening for music...",
    val songArtist: String = "",
    val coverArt: Any? = null,
    val parsedLyrics: List<Pair<String, String>> = emptyList(),
    val currentLyricLine: String = "",
    val currentLyricIndex: Int = -1,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTimestamp: Long = 0L,
    val lrcOffset: Int = 0
)

class LiveLyricsViewModel(
    private val lyricsProviderService: LyricsProviderService,
    val userSettingsController: UserSettingsController
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveLyricsUiState())
    val uiState = _uiState.asStateFlow()

    private var lyricsFetchJob: Job? = null
    private var timestampUpdateJob: Job? = null
    
    private var queryOffset = 0

    init {
        // COLLECTOR 1: Handles Song Changes
        viewModelScope.launch {
            MusicState.currentSong.collectLatest { songTriple ->
                // When song changes, we DO want to stop the old timer immediately
                timestampUpdateJob?.cancel()

                if (songTriple == null) {
                    _uiState.value = LiveLyricsUiState()
                    queryOffset = 0
                    return@collectLatest
                }

                val (title, artist, art) = songTriple
                
                queryOffset = 0
                _uiState.value = _uiState.value.copy(
                    lrcOffset = 0,
                    coverArt = art 
                )
                
                fetchLyricsFor(title, artist)
            }
        }

        // COLLECTOR 2: Handles Play/Pause/Time
        viewModelScope.launch {
            MusicState.playbackInfo.collect { playbackInfo ->
                if (playbackInfo == null) {
                    timestampUpdateJob?.cancel()
                    return@collect
                }

                _uiState.value = _uiState.value.copy(isPlaying = playbackInfo.isPlaying)

                if (playbackInfo.isPlaying) {
                    // *** FIX FOR BUG #1 ***
                    // Only start the job if it's not already running.
                    // If it IS running, we let it continue. The loop inside reads 
                    // MusicState.playbackInfo.value directly, so it will pick up 
                    // the new timestamp/position automatically in the next iteration.
                    if (timestampUpdateJob == null || timestampUpdateJob?.isActive == false) {
                        timestampUpdateJob = launch {
                            while (true) {
                                // Read the LATEST info directly from the StateFlow
                                val currentInfo = MusicState.playbackInfo.value
                                
                                if (currentInfo == null || !currentInfo.isPlaying) break

                                val (isPlaying, basePosition, baseTime, speed) = currentInfo
                                val timePassed = (System.currentTimeMillis() - baseTime) * speed
                                val currentPosition = basePosition + timePassed.toLong()
                                
                                updateCurrentLyric(currentPosition)
                                delay(200) // Update 5x per second
                            }
                        }
                    }
                } else {
                    // If paused, cancel the loop and update one last time to snap to exact position
                    timestampUpdateJob?.cancel()
                    updateCurrentLyric(playbackInfo.position)
                }
            }
        }
    }

    fun updateProvider(provider: Providers) {
        userSettingsController.updateSelectedProviders(provider)
        queryOffset = 0
        forceRefreshLyrics()
    }

    fun forceRefreshLyrics() {
        val currentState = _uiState.value
        if (currentState.songTitle != "Listening for music...") {
            if (currentState.currentLyricLine.contains("Song not found", ignoreCase = true)) {
                queryOffset = 0
            } else {
                queryOffset++
            }
            fetchLyricsFor(currentState.songTitle, currentState.songArtist)
        }
    }

    fun updateSearchQuery(title: String, artist: String) {
        queryOffset = 0
        fetchLyricsFor(title, artist)
    }

    fun updateLrcOffset(offset: Int) {
        _uiState.value = _uiState.value.copy(lrcOffset = offset)
        // Force an immediate update so the user sees the result instantly
        MusicState.playbackInfo.value?.let {
            // We calculate effective position based on whether it's playing or not
            val currentPos = if (it.isPlaying) {
                 val timePassed = (System.currentTimeMillis() - it.timestamp) * it.speed
                 it.position + timePassed.toLong()
            } else {
                it.position
            }
            updateCurrentLyric(currentPos)
        }
    }

    private fun fetchLyricsFor(title: String, artist: String) {
        lyricsFetchJob?.cancel()

        _uiState.value = _uiState.value.copy(
            songTitle = title,
            songArtist = artist,
            isLoading = true,
            parsedLyrics = emptyList(),
            currentLyricLine = ""
        )

        lyricsFetchJob = viewModelScope.launch {
            val songInfo = try {
                lyricsProviderService.getSongInfo(
                    query = SongInfo(title, artist),
                    offset = queryOffset,
                    provider = userSettingsController.selectedProvider
                )
            } catch (e: Exception) {
                null
            }

            if (songInfo == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentLyricLine = "Song not found."
                )
                return@launch
            }

            val lyricsString = try {
                lyricsProviderService.getSyncedLyrics(
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
                    currentLyricLine = "No lyrics found."
                )
                return@launch
            }

            val parsedLyrics = parseLyrics(lyricsString)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                parsedLyrics = parsedLyrics
            )
        }
    }

    private fun updateCurrentLyric(currentPosition: Long) {
        val state = _uiState.value
        val lyrics = state.parsedLyrics
        if (lyrics.isEmpty()) return

        val effectivePosition = currentPosition - state.lrcOffset

        val currentIndex = lyrics.indexOfLast { (time, _) ->
            try {
                val parts = time.split(":", ".")
                val minutes = parts[0].toLong()
                val seconds = parts[1].toLong()
                val millis = parts[2].toLong()
                val lyricTime = (minutes * 60 * 1000) + (seconds * 1000) + millis
                
                lyricTime <= effectivePosition
            } catch (e: Exception) {
                false
            }
        }

        if (currentIndex != -1 && currentIndex != state.currentLyricIndex) {
            _uiState.value = state.copy(
                currentLyricLine = lyrics[currentIndex].second,
                currentLyricIndex = currentIndex
            )
        }
        
        _uiState.value = _uiState.value.copy(currentTimestamp = currentPosition)
    }

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