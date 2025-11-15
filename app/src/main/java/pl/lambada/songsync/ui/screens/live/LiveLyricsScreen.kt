package pl.lambada.songsync.ui.screens.live

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import pl.lambada.songsync.R
import androidx.compose.foundation.layout.height // <--- THIS WAS THE MISSING IMPORT

// This is the "Live Lyrics" screen (The Face)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLyricsScreen(
    navController: NavController,
    viewModel: LiveLyricsViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // This part handles the auto-scrolling
    LaunchedEffect(uiState.currentLyricIndex) {
        if (uiState.currentLyricIndex > -1) {
            listState.animateScrollToItem(
                index = uiState.currentLyricIndex,
                // This places the highlighted item roughly in the center
                scrollOffset = -listState.layoutInfo.viewportSize.height / 3
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.songTitle,
                            maxLines = 1
                        )
                        Text(
                            text = uiState.songArtist,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.parsedLyrics.isEmpty()) {
                Text(
                    text = uiState.currentLyricLine.ifEmpty { "No lyrics available." },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                )
            } else {
                // This is the scrolling list of lyrics
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Add some empty space at the top and bottom
                    // so the first and last lines can be centered
                    item { Box(modifier = Modifier.height(300.dp)) }

                    itemsIndexed(uiState.parsedLyrics) { index, (time, line) ->
                        val isCurrentLine = (index == uiState.currentLyricIndex)

                        // This is the component for a single line of text
                        Text(
                            text = line,
                            fontSize = if (isCurrentLine) 28.sp else 24.sp,
                            fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrentLine) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .animateContentSize(animationSpec = tween(300))
                        )
                    }

                    item { Box(modifier = Modifier.height(300.dp)) }
                }
            }
        }
    }
}