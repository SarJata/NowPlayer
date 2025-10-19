@file:OptIn(
    kotlinx.serialization.InternalSerializationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.example.nowplayer

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// --- CRITICAL VECTOR GRAPHICS IMPORTS ---
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
// --- END CRITICAL IMPORTS ---

// Ensure all necessary opt-in annotations are at the file level

// Custom dark colors for a rich media experience
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1D1D1D)
val DarkPrimary = Color(0xFF1DB954) // Spotify green equivalent
val DarkOnSurfaceVariant = Color(0xFFAFAFAF)
val VinylColor = Color(0xFF444444)
val VinylLabelColor = Color(0xFF1DB954) // Contrast color for the label

/**
 * Utility function to convert milliseconds to mm:ss format.
 */
fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%02d:%02d", minutes, remainingSeconds)
}

/**
 * Data class for music information received over WebSocket.
 */
@Serializable
data class NowPlayingData(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUrl: String? = null,
    val albumArtBase64: String,
    val isPlaying: Boolean,
    val position: Long,
    val duration: Long,
    val timestamp: Long? = null
)

// --- START: ROTATING VINYL COMPONENT ---

private val VinylRecordVector: ImageVector
    get() = ImageVector.Builder(
        name = "VinylRecord",
        defaultWidth = 100.dp,
        defaultHeight = 100.dp,
        viewportWidth = 100f,
        viewportHeight = 100f
    ).apply {
        // Main Black Vinyl Disc (Radius 50)
        path(fill = SolidColor(VinylColor)) {
            moveTo(50f, 0f)
            arcToRelative(50f, 50f, 0f, true, true, 0f, 100f)
            arcToRelative(50f, 50f, 0f, true, true, 0f, -100f)
            close()
        }
        // Inner Red Label (Radius 25)
        path(fill = SolidColor(VinylLabelColor)) {
            moveTo(50f, 25f)
            arcToRelative(25f, 25f, 0f, true, true, 0f, 50f)
            arcToRelative(25f, 25f, 0f, true, true, 0f, -50f)
            close()
        }
        // Center Hole (Radius 5)
        path(fill = SolidColor(VinylColor)) {
            moveTo(50f, 45f)
            arcToRelative(5f, 5f, 0f, true, true, 0f, 10f)
            arcToRelative(5f, 5f, 0f, true, true, 0f, -10f)
            close()
        }
        // Groove lines (Radius 45, Stroke)
        path(
            fill = null,
            stroke = SolidColor(Color.Black.copy(alpha = 0.5f)),
            strokeLineWidth = 1f
        ) {
            moveTo(50f, 5f)
            arcToRelative(45f, 45f, 0f, true, true, 0f, 90f)
            arcToRelative(45f, 45f, 0f, true, true, 0f, -90f)
            close()
        }
    }.build()


@Composable
fun RotatingVinyl(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "VinylRotationTransition")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isPlaying) 4000 else 1000000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "VinylRotation"
    )

    Image(
        painter = rememberVectorPainter(VinylRecordVector),
        contentDescription = "Rotating Vinyl Record",
        modifier = modifier
            .rotate(rotation)
            .background(Color.Transparent)
    )
}

// --- END: ROTATING VINYL COMPONENT ---


@Composable
fun DashboardScreen(
    isConnected: Boolean,
    serverStatus: String,
    nowPlayingData: NowPlayingData?,
    localIpAddress: String,
    onStopServer: () -> Unit,
    onSendCommand: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // --- State for client-side smooth position tracking ---
    val currentPositionMs = remember { mutableLongStateOf(0L) }
    val lastKnownPositionMs = remember { mutableLongStateOf(0L) }
    val lastUpdateTimeMs = remember { mutableLongStateOf(0L) }

    // CRITICAL FIX: The logic here ensures the time updates smoothly
    LaunchedEffect(nowPlayingData) {
        // Only run if we have data to base the position on
        if (nowPlayingData != null) {
            val data = nowPlayingData // Cache the data to avoid frequent dereferencing

            // 1. Reset base position data immediately upon receiving a new update
            lastKnownPositionMs.longValue = data.position
            lastUpdateTimeMs.longValue = data.timestamp ?: System.currentTimeMillis()
            currentPositionMs.longValue = data.position

            // 2. Start the high-frequency ticker only if music is playing
            if (data.isPlaying) {
                // This loop runs until the LaunchedEffect is restarted (by new data) or cancelled
                while (true) {
                    delay(50) // Update every 50 milliseconds for visual smoothness

                    // Calculate elapsed time since the last update time (timestamp from PC)
                    val timeElapsed = System.currentTimeMillis() - lastUpdateTimeMs.longValue

                    // Calculate new position (base position + elapsed time)
                    val newPosition = lastKnownPositionMs.longValue + timeElapsed

                    // Update the state, clamping it at the duration
                    currentPositionMs.longValue = newPosition.coerceAtMost(data.duration)

                    // Stop the loop if we hit the end of the track
                    if (currentPositionMs.longValue >= data.duration) break
                }
            }
        }
    }
    // -----------------------------------------------------------------

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            val scrollState = rememberScrollState()

            ModalDrawerSheet(
                drawerContainerColor = DarkSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .padding(24.dp)
                        .verticalScroll(scrollState)
                ) {
                    // Drawer Header
                    Text(
                        "Server Controls",
                        style = MaterialTheme.typography.headlineSmall,
                        color = DarkPrimary
                    )

                    Divider(Modifier.padding(vertical = 16.dp), color = DarkOnSurfaceVariant.copy(alpha = 0.3f))

                    // Status Text
                    Text(
                        text = if (isConnected) "Status: Connected" else if (serverStatus.startsWith("Listening")) "Status: Listening..." else "Status: Stopped",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            isConnected -> DarkPrimary
                            serverStatus.startsWith("Listening") -> DarkOnSurfaceVariant
                            else -> Color.Red
                        }
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    // Stop Button
                    OutlinedButton(
                        onClick = {
                            onStopServer()
                            scope.launch { drawerState.close() }
                        },
                        border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop Server", modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("STOP SERVER")
                    }
                }
            }
        }
    ) {
        // Main Screen Content
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DarkBackground
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {

                // Hamburger Menu Button (Top Left)
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = "Open Menu",
                        tint = DarkPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Main Content (Centered)
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (nowPlayingData != null) {
                        NowPlayingCard(
                            data = nowPlayingData,
                            currentPositionMs = currentPositionMs.longValue,
                            onSendCommand = onSendCommand
                        )
                    } else {
                        // Placeholder for when no music is playing (Now shows IP address)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(300.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(DarkSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (serverStatus.startsWith("Stopped")) {
                                        "Server is stopped."
                                    } else {
                                        "Awaiting Connection..."
                                    },
                                    style = MaterialTheme.typography.headlineSmall,
                                    textAlign = TextAlign.Center,
                                    color = DarkOnSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // --- IP Address Front and Center ---
                                Text(
                                    text = "Connect PC Client to:",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = DarkOnSurfaceVariant
                                )
                                Text(
                                    text = "$localIpAddress:8080/ws",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = DarkPrimary
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Check the menu for server controls.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DarkOnSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card component to display the current track details, optimized for landscape row layout.
 */
@Composable
fun NowPlayingCard(
    data: NowPlayingData,
    currentPositionMs: Long,
    onSendCommand: (String) -> Unit // <-- Command sender
) {
    // Decode the Base64 image string into a Bitmap for Compose
    val base64Bitmap = remember(data.albumArtBase64) {
        try {
            if (data.albumArtBase64.isNotEmpty()) {
                val bytes = Base64.decode(data.albumArtBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp), // Constrain height for landscape
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- Left Side: Album Art (Focal Point) ---
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                if (base64Bitmap != null) {
                    Image(
                        bitmap = base64Bitmap,
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // --- REPLACED DEFAULT PLACEHOLDER WITH ROTATING VINYL ---
                    RotatingVinyl(
                        isPlaying = data.isPlaying,
                        modifier = Modifier.fillMaxSize()
                    )
                    // --- END ROTATING VINYL ---
                }
            }

            Spacer(modifier = Modifier.width(32.dp))

            // --- Right Side: Details and Progress ---
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
            ) {
                // 1. Playback Status Icon (Visual only)
                Icon(
                    imageVector = if (data.isPlaying) Icons.Filled.PlayCircleOutline else Icons.Filled.PauseCircleOutline,
                    contentDescription = if (data.isPlaying) "Playing" else "Paused",
                    tint = DarkPrimary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 2. Title
                Text(
                    text = data.title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 3. Artist
                Text(
                    text = data.artist,
                    style = MaterialTheme.typography.titleLarge,
                    color = DarkOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // 4. Album
                Text(
                    text = data.album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DarkOnSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.weight(1f)) // Push controls and progress to bottom

                // 5. Playback Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous Track Button
                    IconButton(
                        onClick = { onSendCommand("PREVIOUS_TRACK") },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous Track",
                            tint = DarkOnSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Play/Pause Button
                    IconButton(
                        onClick = { onSendCommand("PLAY_PAUSE") },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = if (data.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause Track",
                            tint = DarkPrimary,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Next Track Button
                    IconButton(
                        onClick = { onSendCommand("NEXT_TRACK") },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next Track",
                            tint = DarkOnSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // 6. Track Progress Bar
                TrackProgressBar(
                    currentPosition = currentPositionMs, // Pass smooth position
                    duration = data.duration
                )
            }
        }
    }
}

/**
 * Custom progress bar showing current track position.
 */
@Composable
fun TrackProgressBar(currentPosition: Long, duration: Long) {

    // Calculate progress directly from the smooth position state
    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            DarkPrimary,
            trackColor = Color.Gray.copy(alpha = 0.3f)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Use smooth position for display
            Text(
                text = formatTime(currentPosition),
                style = MaterialTheme.typography.labelMedium,
                color = DarkOnSurfaceVariant
            )
            Text(
                text = formatTime(duration),
                style = MaterialTheme.typography.labelMedium,
                color = DarkOnSurfaceVariant
            )
        }
    }
}