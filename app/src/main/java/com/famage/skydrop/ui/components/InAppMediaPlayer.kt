package com.famage.skydrop.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.famage.skydrop.history.TransferHistoryItem
import kotlinx.coroutines.delay
import java.io.File

private val SUPPORTED_VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "ts", "m4v", "3gp", "flv")
private val SUPPORTED_AUDIO_EXTENSIONS = setOf("mp3", "aac", "flac", "ogg", "wav", "m4a", "opus", "wma")

/**
 * Returns true if this item is a video or audio file that the in-app player should handle.
 */
fun isPlayableMedia(item: TransferHistoryItem): Boolean {
    val mime = item.mimeType?.lowercase() ?: ""
    if (mime.startsWith("video/") || mime.startsWith("audio/")) return true
    val ext = File(item.name).extension.lowercase()
    return ext in SUPPORTED_VIDEO_EXTENSIONS || ext in SUPPORTED_AUDIO_EXTENSIONS
}

fun isAudioOnlyMedia(item: TransferHistoryItem): Boolean {
    val mime = item.mimeType?.lowercase() ?: ""
    if (mime.startsWith("audio/")) return true
    val ext = File(item.name).extension.lowercase()
    return ext in SUPPORTED_AUDIO_EXTENSIONS
}

@OptIn(UnstableApi::class)
@Composable
fun InAppMediaPlayer(
    item: TransferHistoryItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uri = remember(item.uri) { item.uri?.let { Uri.parse(it) } }

    var playerError by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3500L)
            showControls = false
        }
    }

    val exoPlayer = remember(uri) {
        if (uri == null) return@remember null
        ExoPlayer.Builder(context).build().also { player ->
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = "Cannot play this file — format or codec not supported (${error.errorCodeName.replace('_', ' ').lowercase()})."
                isPlaying = false
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer?.addListener(listener)
        onDispose {
            exoPlayer?.removeListener(listener)
            exoPlayer?.release()
        }
    }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                            showControls = true
                            if (exoPlayer?.isPlaying == true) exoPlayer.pause() else exoPlayer?.play()
                            true
                        }
                        Key.DirectionRight -> {
                            showControls = true
                            exoPlayer?.let {
                                val newPos = (it.currentPosition + 10_000L).coerceAtMost(it.duration.coerceAtLeast(0L))
                                it.seekTo(newPos)
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            showControls = true
                            exoPlayer?.let {
                                val newPos = (it.currentPosition - 10_000L).coerceAtLeast(0L)
                                it.seekTo(newPos)
                            }
                            true
                        }
                        Key.Back, Key.Escape -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                }
        ) {
            when {
                uri == null -> {
                    UnsupportedFormatFallback(
                        message = "File path is unavailable.",
                        item = item,
                        context = context,
                        onDismiss = onDismiss
                    )
                }
                playerError != null -> {
                    UnsupportedFormatFallback(
                        message = playerError ?: "Playback error",
                        item = item,
                        context = context,
                        onDismiss = onDismiss
                    )
                }
                else -> {
                    if (isAudioOnlyMedia(item)) {
                        AudioOnlyBackground(item = item)
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    keepScreenOn = true
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                }
                            },
                            update = { view -> view.player = exoPlayer },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Click layer to toggle controls
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showControls = !showControls }
                    )

                    AnimatedVisibility(
                        visible = showControls,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        PlayerControlsOverlay(
                            item = item,
                            exoPlayer = exoPlayer,
                            isPlaying = isPlaying,
                            context = context,
                            onDismiss = onDismiss,
                            onShowControls = { showControls = true }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerControlsOverlay(
    item: TransferHistoryItem,
    exoPlayer: ExoPlayer?,
    isPlaying: Boolean,
    context: Context,
    onDismiss: () -> Unit,
    onShowControls: () -> Unit
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(exoPlayer, isPlaying) {
        while (true) {
            if (!dragging && exoPlayer != null && exoPlayer.duration > 0) {
                sliderPosition = (exoPlayer.currentPosition.toFloat() / exoPlayer.duration.toFloat()).coerceIn(0f, 1f)
            }
            delay(500L)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(48.dp)
                    .remoteFocusable(shape = CircleShape, focusedScale = 1.1f)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { openInExternalPlayer(context, item) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 40.dp)
                    .remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.05f)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open in External Player", fontSize = 12.sp)
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .align(Alignment.BottomCenter)
        ) {
            if (exoPlayer != null && exoPlayer.duration > 0) {
                Slider(
                    value = sliderPosition,
                    onValueChange = { v ->
                        dragging = true
                        sliderPosition = v
                        onShowControls()
                    },
                    onValueChangeFinished = {
                        exoPlayer.seekTo((sliderPosition * exoPlayer.duration).toLong())
                        dragging = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(formatDuration(exoPlayer.currentPosition), color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(formatDuration(exoPlayer.duration), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        exoPlayer?.let { it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L)) }
                        onShowControls()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .remoteFocusable(shape = CircleShape, focusedScale = 1.1f)
                ) {
                    Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                }

                Spacer(modifier = Modifier.width(20.dp))

                IconButton(
                    onClick = {
                        if (exoPlayer?.isPlaying == true) exoPlayer.pause() else exoPlayer?.play()
                        onShowControls()
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                        .remoteFocusable(shape = CircleShape, focusedScale = 1.1f)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                IconButton(
                    onClick = {
                        exoPlayer?.let { it.seekTo((it.currentPosition + 10_000L).coerceAtMost(it.duration.coerceAtLeast(0L))) }
                        onShowControls()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .remoteFocusable(shape = CircleShape, focusedScale = 1.1f)
                ) {
                    Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
private fun UnsupportedFormatFallback(
    message: String,
    item: TransferHistoryItem,
    context: Context,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(48.dp)
                .remoteFocusable(shape = CircleShape, focusedScale = 1.1f)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VideocamOff,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Cannot Play This File",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = { openInExternalPlayer(context, item) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .defaultMinSize(minHeight = 48.dp)
                    .remoteFocusable(shape = RoundedCornerShape(12.dp), focusedScale = 1.05f)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open in Another Player")
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.remoteFocusable(shape = RoundedCornerShape(10.dp), focusedScale = 1.05f)
            ) {
                Text("Go Back", color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun AudioOnlyBackground(item: TransferHistoryItem) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(96.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = item.name,
                color = Color.White.copy(alpha = 0.8f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

private fun openInExternalPlayer(context: Context, item: TransferHistoryItem) {
    if (item.uri.isNullOrBlank()) return
    try {
        val uri = Uri.parse(item.uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (_: Exception) {}
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
