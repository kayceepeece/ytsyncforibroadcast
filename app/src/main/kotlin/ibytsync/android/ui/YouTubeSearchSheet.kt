package ibytsync.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ibytsync.core.matching.YtCandidate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeSearchSheet(
    initialQuery: String,
    candidates: List<YtCandidate>,
    isLoading: Boolean,
    onSearch: (String) -> Unit,
    onAddCandidate: (YtCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current
    var queryText by remember(initialQuery) { mutableStateOf(initialQuery) }
    var stagedIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(candidates) {
        val ids = candidates.map { it.id }.toSet()
        stagedIds = stagedIds intersect ids
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141414),
        scrimColor = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "YOUTUBE SEARCH",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF29B6F6)
                    )
                    Text(
                        if (isLoading) "Searching YouTube…" else "${candidates.size} results found",
                        fontSize = 11.sp,
                        color = Color(0xFF888888)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    placeholder = { Text("Search songs, artists, videos…", fontSize = 12.sp, color = Color(0xFF666666)) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF29B6F6),
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedTextColor = Color(0xFFEEEEEE),
                        unfocusedTextColor = Color(0xFFEEEEEE),
                        cursorColor = Color(0xFF29B6F6)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (queryText.isNotBlank()) {
                            onSearch(queryText.trim())
                        }
                    }),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                )

                Button(
                    onClick = {
                        if (queryText.isNotBlank()) {
                            onSearch(queryText.trim())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF29B6F6), modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Searching YouTube…", fontSize = 11.sp, color = Color(0xFF888888))
                    }
                }
            } else if (candidates.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (queryText.isBlank()) "Type a query to search YouTube" else "No matches for \"$queryText\"",
                        fontSize = 12.sp,
                        color = Color(0xFF777777),
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(candidates, key = { it.id }) { candidate ->
                        val isStaged = candidate.id in stagedIds
                        CandidateItemRow(
                            candidate = candidate,
                            isStaged = isStaged,
                            onToggleStage = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                stagedIds = if (isStaged) {
                                    stagedIds - candidate.id
                                } else {
                                    stagedIds = stagedIds + candidate.id
                                    onAddCandidate(candidate)
                                    stagedIds
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFAAAAAA)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Text("CLOSE", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = onDismiss,
                    enabled = stagedIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1DB954),
                        disabledContainerColor = Color(0xFF242424),
                        disabledContentColor = Color(0xFF666666)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1.5f)
                        .height(46.dp)
                ) {
                    Text(
                        if (stagedIds.isNotEmpty()) "ADD ${stagedIds.size} TO QUEUE" else "ADD TO QUEUE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (stagedIds.isNotEmpty()) Color.Black else Color(0xFF666666),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CandidateItemRow(
    candidate: YtCandidate,
    isStaged: Boolean,
    onToggleStage: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1C1C1C))
            .border(1.dp, if (isStaged) Color(0xFF1DB954).copy(alpha = 0.5f) else Color(0x22FFFFFF), RoundedCornerShape(8.dp))
            .clickable(onClick = onToggleStage)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(width = 80.dp, height = 45.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF2A2A2A))
        ) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data("https://i.ytimg.com/vi/${candidate.id}/hqdefault.jpg")
                    .size(160, 90)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )

            if (candidate.duration > 0) {
                val durSec = candidate.duration.toLong()
                val min = durSec / 60
                val sec = durSec % 60
                val durText = "%d:%02d".format(min, sec)

                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                ) {
                    Text(durText, fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                candidate.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEEEEEE),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    candidate.channel,
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (candidate.viewCount > 0) {
                    Text(
                        " • ${formatViews(candidate.viewCount)}",
                        fontSize = 10.sp,
                        color = Color(0xFF777777)
                    )
                }
            }
        }

        if (isStaged) {
            OutlinedButton(
                onClick = onToggleStage,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1DB954)),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1DB954))),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("UNDO", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        } else {
            OutlinedButton(
                onClick = onToggleStage,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF29B6F6)),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF0288D1))),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("+ ADD", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun formatViews(views: Long): String = when {
    views >= 1_000_000_000 -> "%.1fB views".format(views / 1_000_000_000.0)
    views >= 1_000_000 -> "%.1fM views".format(views / 1_000_000.0)
    views >= 1_000 -> "%.1fK views".format(views / 1_000.0)
    else -> "$views views"
}
