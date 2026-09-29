package ibytsync.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ibytsync.core.pipeline.QueueRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncedTrackInspectorSheet(
    track: QueueRow,
    onDismiss: () -> Unit,
    onEditAndReSync: (QueueRow) -> Unit,
    onDeleteTrack: ((QueueRow) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(12.dp),
            title = {
                Text(
                    "REMOVE 1 TRACK FROM HISTORY?",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE91429)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This removes this track from your history on this device. Music in your iBroadcast library is not affected.",
                        fontSize = 12.sp,
                        color = Color(0xFFCCCCCC)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF262626))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "• ${track.title}${if (track.artist.isNotBlank()) " — ${track.artist}" else ""}",
                            fontSize = 11.sp,
                            color = Color(0xFFCCCCCC),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteTrack?.invoke(track)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91429)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("DELETE TRACK", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("KEEP", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF888888))
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141414),
        scrimColor = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = Color(0xFF1DB954),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "UPLOAD DETAILS",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1DB954),
                        letterSpacing = 1.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onDeleteTrack != null) {
                        IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete from history", tint = Color(0xFFE91429), modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFF888888))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1C1C1C))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!track.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                .data(track.coverUrl)
                                .size(256)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Track Artwork",
                            modifier = Modifier
                                .size(90.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF282828))
                                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("♪", fontSize = 32.sp, color = Color(0xFF666666))
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = track.title.ifBlank { "Untitled Track" },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEEEEEE),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist.ifBlank { "Unknown Artist" },
                            fontSize = 13.sp,
                            color = Color(0xFF1DB954),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.album.ifBlank { "No Album" },
                            fontSize = 12.sp,
                            color = Color(0xFFAAAAAA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val extraInfo = listOfNotNull(
                            track.year?.takeIf { it.isNotBlank() },
                            track.genre?.takeIf { it.isNotBlank() },
                            track.trackNumber?.let { num ->
                                if (track.trackCount != null) "Track $num/${track.trackCount}" else "Track $num"
                            }
                        ).joinToString(" • ")
                        if (extraInfo.isNotBlank()) {
                            Text(
                                text = extraInfo,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF888888),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF222222))
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text(
                            "DESTINATION",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888)
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = null,
                                tint = Color(0xFF29B6F6),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            val plText = when {
                                track.targetPlaylistNames.size > 1 -> "${track.targetPlaylistNames.size} Playlists (${track.targetPlaylistNames.joinToString(", ")})"
                                track.targetPlaylistNames.size == 1 -> track.targetPlaylistNames.first()
                                else -> track.targetPlaylistName ?: "Library Only"
                            }
                            Text(
                                plText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFEEEEEE),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF222222))
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text(
                            "AUDIO FORMAT",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            track.audioFormat?.label ?: "MP3 192k",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1DB954)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF181818))
                    .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (track.sourceInput.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Source: ",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF888888)
                            )
                            Text(
                                track.sourceInput.take(45) + if (track.sourceInput.length > 45) "…" else "",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFAAAAAA),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFAAAAAA)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
                ) {
                    Text("CLOSE", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        onDismiss()
                        onEditAndReSync(track)
                    },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(46.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "EDIT & RE-SYNC",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
