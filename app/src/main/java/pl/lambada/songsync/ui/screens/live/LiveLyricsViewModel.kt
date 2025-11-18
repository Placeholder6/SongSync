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
    val parsedLyrics: List<Pair<String, String>> = emptyList(),
    val currentLyricLine: String = "",
    val currentLyricIndex: Int = -1,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTimestamp: Long = 0L,
    val lrcOffset: Int = 0 // Timing offset in milliseconds
)

class LiveLyricsViewModel(
    private val lyricsProviderService: LyricsProviderService,
    val userSettingsController: UserSettingsController
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveLyricsUiState())
    val uiState = _uiState.asStateFlow()

    private var lyricsFetchJob: Job? = null
    private var timestampUpdateJob: Job? = null
    
    // Pagination offset for search results (e.g. finding the next match)
    private var queryOffset = 0

    init {
        // COLLECTOR 1: Handles Song Changes
        viewModelScope.launch {
            MusicState.currentSong.collectLatest { songPair ->
                timestampUpdateJob?.cancel()

                if (songPair == null) {
                    _uiState.value = LiveLyricsUiState()
                    queryOffset = 0
                    return@collectLatest
                }

                // Reset query offset for a new song
                queryOffset = 0
                // Reset LRC offset for a new song
                _uiState.value = _uiState.value.copy(lrcOffset = 0)
                
                fetchLyricsFor(songPair.first, songPair.second)
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
                timestampUpdateJob?.cancel()

                if (playbackInfo.isPlaying) {
                    timestampUpdateJob = launch {
                        while (true) {
                            val (isPlaying, basePosition, baseTime, speed) = MusicState.playbackInfo.value ?: break
                            if (!isPlaying) break

                            val timePassed = (System.currentTimeMillis() - baseTime) * speed
                            val currentPosition = basePosition + timePassed.toLong()
                            updateCurrentLyric(currentPosition)
                            
                            delay(200)
                        }
                    }
                } else {
                    updateCurrentLyric(playbackInfo.position)
                }
            }
        }
    }

    fun updateProvider(provider: Providers) {
        userSettingsController.updateSelectedProviders(provider)
        // Reset query offset when changing provider to start from top result
        queryOffset = 0
        forceRefreshLyrics()
    }

    fun forceRefreshLyrics() {
        val currentState = _uiState.value
        if (currentState.songTitle != "Listening for music...") {
            // Increment query offset to try the "next" result (like "Try Again" button)
            queryOffset++
            fetchLyricsFor(currentState.songTitle, currentState.songArtist)
        }
    }

    fun updateSearchQuery(title: String, artist: String) {
        queryOffset = 0 // Reset pagination for a new manual query
        fetchLyricsFor(title, artist)
    }

    fun updateLrcOffset(offset: Int) {
        _uiState.value = _uiState.value.copy(lrcOffset = offset)
        // Immediately re-calculate current lyric with new offset
        MusicState.playbackInfo.value?.let {
            if (!it.isPlaying) updateCurrentLyric(it.position)
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
                    offset = queryOffset, // Use the pagination offset
                    provider = userSettingsController.selectedProvider
                )
            } catch (e: Exception) {
                null
            }

            if (songInfo == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentLyricLine = "Song not found (Offset: $queryOffset)."
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

        // Apply the user-defined offset to the current player position logic
        // OR conceptually: LyricTimestamp + Offset <= CurrentPosition
        // If offset is +500ms, it means lyrics should appear 500ms LATER. 
        // So we look for: LyricTimestamp <= CurrentPosition - Offset
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