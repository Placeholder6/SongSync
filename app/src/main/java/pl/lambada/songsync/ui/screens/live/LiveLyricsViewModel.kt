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
import java.util.regex.Pattern

data class LiveLyricsUiState(
    val songTitle: String = "Listening for music...",
    val songArtist: String = "",
    val coverArt: Any? = null,
    val parsedLyrics: List<Pair<String, String>> = emptyList(),
    val currentLyricLine: String = "", // Used for status messages/lyrics
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
                if (songTriple == null) {
                    _uiState.value = LiveLyricsUiState()
                    queryOffset = 0
                    return@collectLatest
                }

                val (title, artist, art) = songTriple
                
                // Only refresh if the song actually changed
                // We ignore small updates to avoid resetting the "Smart Search" progress
                if (title != _uiState.value.songTitle && _uiState.value.songTitle != "Listening for music...") {
                     // If we are already working on this song (even if we modified the title UI), don't restart
                     // This check is tricky because we modify the UI title during search. 
                     // Ideally, we'd compare against a 'rawTitle' but simple check is okay for now.
                }

                // Force reset on new song detection
                queryOffset = 0
                _uiState.value = _uiState.value.copy(
                    lrcOffset = 0,
                    coverArt = art,
                    // We initially show the raw data, then the search loop will update it
                    songTitle = title,
                    songArtist = artist
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
                    if (timestampUpdateJob == null || timestampUpdateJob?.isActive == false) {
                        timestampUpdateJob = launch {
                            while (true) {
                                val currentInfo = MusicState.playbackInfo.value
                                if (currentInfo == null || !currentInfo.isPlaying) break

                                val (isPlaying, basePosition, baseTime, speed) = currentInfo
                                val timePassed = (System.currentTimeMillis() - baseTime) * speed
                                val currentPosition = basePosition + timePassed.toLong()
                                
                                updateCurrentLyric(currentPosition)
                                delay(200)
                            }
                        }
                    }
                } else {
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
            // Smart Retry: If we failed before, retry from 0. If we succeeded, try next.
            if (currentState.currentLyricLine.contains("not found", ignoreCase = true)) {
                queryOffset = 0
            } else {
                queryOffset++
            }
            // We pass the current displayed title/artist because that might be the "fixed" version 
            // the user wants to keep, OR we can revert to original? 
            // Let's try to fetch based on what is currently on screen to be consistent.
            fetchLyricsFor(currentState.songTitle, currentState.songArtist)
        }
    }

    fun updateSearchQuery(title: String, artist: String) {
        queryOffset = 0
        fetchLyricsFor(title, artist)
    }

    fun updateLrcOffset(offset: Int) {
        _uiState.value = _uiState.value.copy(lrcOffset = offset)
        MusicState.playbackInfo.value?.let {
             val currentPos = if (it.isPlaying) {
                 val timePassed = (System.currentTimeMillis() - it.timestamp) * it.speed
                 it.position + timePassed.toLong()
             } else {
                 it.position
             }
             updateCurrentLyric(currentPos)
        }
    }

    private fun fetchLyricsFor(originalTitle: String, originalArtist: String) {
        lyricsFetchJob?.cancel()

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            parsedLyrics = emptyList(),
            currentLyricLine = "Searching..."
        )

        lyricsFetchJob = viewModelScope.launch {
            val strategies = mutableListOf<SearchStrategy>()
            
            // 1. ORIGINAL
            strategies.add(SearchStrategy("Original", originalTitle, originalArtist))
            
            // 2. ARTIST FIXER (e.g. "Unknown" -> Split Title)
            if (isArtistSuspicious(originalArtist)) {
                val (splitTitle, splitArtist) = trySplitArtistFromTitle(originalTitle)
                if (splitArtist != null) {
                    strategies.add(SearchStrategy("Fixing 'Unknown' Artist", splitTitle, splitArtist))
                }
            }

            // 3. CLEANED (Remove "Official Video", "ft.", etc.)
            val cleanedTitle = cleanText(originalTitle)
            val cleanedArtist = cleanText(originalArtist)
            if (cleanedTitle != originalTitle || cleanedArtist != originalArtist) {
                strategies.add(SearchStrategy("Removing Junk Text", cleanedTitle, cleanedArtist))
            }

            // 4. CLEANED + SPLIT (Clean first, then split if needed)
            if (isArtistSuspicious(cleanedArtist)) {
                 val (splitCleanTitle, splitCleanArtist) = trySplitArtistFromTitle(cleanedTitle)
                 if (splitCleanArtist != null) {
                     strategies.add(SearchStrategy("Cleaning & Fixing Artist", splitCleanTitle, splitCleanArtist))
                 }
            }

            // 5. SUPER CLEAN (Remove all brackets)
            val superCleanTitle = superCleanText(originalTitle)
            if (superCleanTitle != cleanedTitle && superCleanTitle.isNotBlank()) {
                strategies.add(SearchStrategy("Aggressive Filtering", superCleanTitle, cleanedArtist))
            }

            var success = false
            
            for (strategy in strategies) {
                // If Paging (Try Again), stick to the first valid strategy to avoid jumping logic
                if (queryOffset > 0 && strategy.label != strategies.first().label) continue

                // *** UI UPDATE: Show the user what we are searching for ***
                _uiState.value = _uiState.value.copy(
                    songTitle = strategy.title,
                    songArtist = strategy.artist,
                    currentLyricLine = "[${strategy.label}] Searching..."
                )
                // Small delay so the user can actually see the text change (visual feedback)
                delay(300)

                if (tryFetchLyrics(strategy.title, strategy.artist)) {
                    success = true
                    // We found it! The UI already shows the correct "Fixed" Title/Artist.
                    // We just clear the status message.
                    _uiState.value = _uiState.value.copy(
                        currentLyricLine = "" // Clear status
                    )
                    break 
                }
            }

            if (!success) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentLyricLine = "Song not found."
                )
            }
        }
    }

    private suspend fun tryFetchLyrics(title: String, artist: String): Boolean {
        return try {
            val songInfo = lyricsProviderService.getSongInfo(
                query = SongInfo(title, artist),
                offset = queryOffset,
                provider = userSettingsController.selectedProvider
            ) ?: return false

            val lyricsString = lyricsProviderService.getSyncedLyrics(
                songInfo.songName ?: title, 
                songInfo.artistName ?: artist,
                userSettingsController.selectedProvider,
                userSettingsController.includeTranslation,
                userSettingsController.includeRomanization,
                userSettingsController.multiPersonWordByWord,
                userSettingsController.unsyncedFallbackMusixmatch
            ) ?: return false

            val parsedLyrics = parseLyrics(lyricsString)
            if (parsedLyrics.isEmpty()) return false

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                parsedLyrics = parsedLyrics
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    // *** HELPERS ***

    data class SearchStrategy(val label: String, val title: String, val artist: String)

    private fun isArtistSuspicious(artist: String): Boolean {
        val lower = artist.lowercase()
        return lower.contains("unknown") || lower.contains("various") || lower.isBlank() || lower == "<unknown>"
    }

    private fun trySplitArtistFromTitle(title: String): Pair<String, String?> {
        // Looks for "Artist - Title" or "Artist – Title" (en dash)
        val separatorRegex = Pattern.compile("\\s+[-–]\\s+")
        val parts = separatorRegex.split(title)
        return if (parts.size >= 2) {
            // Assume Part 1 is Artist, Part 2 is Title
            // "Glass Animals - Heat Waves" -> Title: Heat Waves, Artist: Glass Animals
            val newArtist = parts[0].trim()
            // Rejoin the rest in case there are multiple dashes
            val newTitle = parts.drop(1).joinToString(" - ").trim()
            newTitle to newArtist
        } else {
            title to null
        }
    }
    
    private fun cleanText(input: String): String {
        return try {
            var text = input
            val keywords = "official|video|lyrics|lyric|visualizer|audio|music video|mv|topic|hd|hq|4k|1080p|remastered|remaster|live|session|performance|concert|cover|remix|mix|edit|extended|radio|instrumental|karaoke|version|clean|explicit"
            val junkRegex = Pattern.compile("(?i)[(\\[](?:$keywords).*?[)\\]]")
            text = junkRegex.matcher(text).replaceAll("").trim()
            
            val featRegex = Pattern.compile("(?i)\\s(feat\\.?|ft\\.?|featuring)\\s.*")
            text = featRegex.matcher(text).replaceAll("").trim()
            
            text
        } catch (e: Exception) {
            input 
        }
    }

    private fun superCleanText(input: String): String {
        return try {
            val bracketRegex = Pattern.compile("[(\\[].*?[)\\]]")
            bracketRegex.matcher(input).replaceAll("").trim()
        } catch (e: Exception) {
            input
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