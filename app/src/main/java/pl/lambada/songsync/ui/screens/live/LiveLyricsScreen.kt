package pl.lambada.songsync.ui.screens.live

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.components.CommonTextField
import pl.lambada.songsync.ui.components.ProvidersDropdownMenu
import pl.lambada.songsync.util.ext.repeatingClickable
import androidx.compose.foundation.interaction.MutableInteractionSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLyricsScreen(
    navController: NavController,
    viewModel: LiveLyricsViewModel,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    var expandedProviders by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.currentLyricIndex) {
        if (uiState.currentLyricIndex > -1) {
            listState.animateScrollToItem(
                index = uiState.currentLyricIndex,
                scrollOffset = -listState.layoutInfo.viewportSize.height / 3
            )
        }
    }

    if (showEditDialog) {
        EditQueryDialog(
            initialTitle = uiState.songTitle,
            initialArtist = uiState.songArtist,
            onDismiss = { showEditDialog = false },
            onConfirm = { title, artist ->
                viewModel.updateSearchQuery(title, artist)
                showEditDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                title = { },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit)
                        )
                    }
                    IconButton(onClick = { viewModel.forceRefreshLyrics() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.try_again)
                        )
                    }
                    Box {
                        IconButton(onClick = { expandedProviders = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Providers"
                            )
                        }
                        ProvidersDropdownMenu(
                            expanded = expandedProviders,
                            onDismissRequest = { expandedProviders = false },
                            selectedProvider = viewModel.userSettingsController.selectedProvider,
                            onProviderSelectRequest = { newProvider ->
                                viewModel.updateProvider(newProvider)
                            }
                        )
                    }
                }
            )
        },
        // *** MOVED OFFSET BAR FROM HERE TO MAIN CONTENT ***
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Song Info Section
            LiveSongInfo(
                title = uiState.songTitle,
                artist = uiState.songArtist,
                art = uiState.coverArt
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (uiState.parsedLyrics.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = uiState.currentLyricLine.ifEmpty { "No lyrics available." },
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        
                        if (uiState.currentLyricLine.contains("not found") || uiState.currentLyricLine.contains("No lyrics")) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(onClick = { viewModel.forceRefreshLyrics() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.try_again))
                                }
                                Button(onClick = { showEditDialog = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.edit))
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        item { Box(modifier = Modifier.height(150.dp)) }

                        itemsIndexed(uiState.parsedLyrics) { index, (time, line) ->
                            val isCurrentLine = (index == uiState.currentLyricIndex)

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

                        item { Box(modifier = Modifier.height(250.dp)) }
                    }
                }
                
                // *** FLOATING OFFSET BAR ***
                if (uiState.parsedLyrics.isNotEmpty()) {
                    Box(
                         modifier = Modifier
                             .align(Alignment.BottomCenter)
                             .padding(bottom = 24.dp)
                    ) {
                        OffsetControlBar(
                             offset = uiState.lrcOffset,
                             onOffsetChange = viewModel::updateLrcOffset
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveSongInfo(title: String, artist: String, art: Any?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(64.dp)
        ) {
            if (art != null) {
                 Image(
                     painter = rememberAsyncImagePainter(
                         ImageRequest.Builder(LocalContext.current)
                             .data(art)
                             .crossfade(true)
                             .build()
                     ),
                     contentDescription = null,
                     modifier = Modifier.fillMaxSize()
                 )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun EditQueryDialog(
    initialTitle: String,
    initialArtist: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit)) },
        text = {
            Column {
                CommonTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = stringResource(R.string.song_name_no_args),
                    imeAction = ImeAction.Next
                )
                Spacer(modifier = Modifier.height(8.dp))
                CommonTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = stringResource(R.string.artist_name_no_args),
                    imeAction = ImeAction.Done
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(title, artist) }) {
                Text(stringResource(R.string.search))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun OffsetControlBar(
    offset: Int,
    onOffsetChange: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Exposure,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            
            FilledTonalIconButton(
                onClick = { /* handled by repeatingClickable */ },
                modifier = Modifier.size(36.dp).repeatingClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    enabled = true,
                    onClick = { onOffsetChange(offset - 100) }
                )
            ) {
                Icon(Icons.Default.Remove, null, modifier = Modifier.size(18.dp))
            }
            
            Text(
                text = (if (offset >= 0) "+" else "") + "${offset}ms",
                modifier = Modifier.width(70.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            FilledTonalIconButton(
                onClick = { /* handled by repeatingClickable */ },
                modifier = Modifier.size(36.dp).repeatingClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    enabled = true,
                    onClick = { onOffsetChange(offset + 100) }
                )
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            }
        }
    }
}