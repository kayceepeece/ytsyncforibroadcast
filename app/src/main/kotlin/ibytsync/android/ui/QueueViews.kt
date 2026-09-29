package ibytsync.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ibytsync.android.ui.theme.liquidGlassPanel
import ibytsync.core.pipeline.AudioSourcePreference
import ibytsync.core.pipeline.QueueRow
import ibytsync.core.pipeline.RowStates
import ibytsync.core.pipeline.RowStatus

@OptIn(FlowPreview::class)
@Composable
fun QueueTabContent(
    rows: List<QueueRow>,
    isBatchRunning: Boolean,
    onAddInput: (String) -> Unit,
    onPickLocalFile: () -> Unit,
    onOpenInspector: (QueueRow) -> Unit,
    onRetry: (QueueRow) -> Unit,
    onRemove: (QueueRow) -> Unit,
    onAcceptOption: (QueueRow) -> Unit = {},
    onTriggerSearch: (String) -> Unit = {},
    suggestions: List<String> = emptyList(),
    onQueryChanged: (String) -> Unit = {}
) {
    var textInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val intent = remember(textInput) { ibytsync.core.input.OmnibarClassifier.classify(textInput) }

    LaunchedEffect(Unit) {
        snapshotFlow { textInput }
            .debounce(350L)
            .distinctUntilChanged()
            .collectLatest { onQueryChanged(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlassPanel(cornerRadius = 10.dp)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SEARCH",
                    fontSize = 10.sp,
                    color = Color(0xFF888888),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                when (intent) {
                    is ibytsync.core.input.OmnibarIntent.BatchUrls -> Text(
                        "BATCH (${intent.urls.size} LINKS)",
                        fontSize = 10.sp,
                        color = Color(0xFF1DB954),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    is ibytsync.core.input.OmnibarIntent.SingleUrl -> Text(
                        "LINK FOUND",
                        fontSize = 10.sp,
                        color = Color(0xFF1DB954),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    is ibytsync.core.input.OmnibarIntent.SearchQuery -> Text(
                        "YOUTUBE SEARCH",
                        fontSize = 10.sp,
                        color = Color(0xFF29B6F6),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    ibytsync.core.input.OmnibarIntent.Empty -> {}
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val leadingTint = when (intent) {
                    is ibytsync.core.input.OmnibarIntent.SearchQuery -> Color(0xFF29B6F6)
                    is ibytsync.core.input.OmnibarIntent.SingleUrl, is ibytsync.core.input.OmnibarIntent.BatchUrls -> Color(0xFF1DB954)
                    else -> Color(0xFF666666)
                }

                val actionColor by androidx.compose.animation.animateColorAsState(
                    targetValue = when (intent) {
                        is ibytsync.core.input.OmnibarIntent.SearchQuery -> Color(0xFF0288D1)
                        is ibytsync.core.input.OmnibarIntent.SingleUrl, is ibytsync.core.input.OmnibarIntent.BatchUrls -> Color(0xFF1DB954)
                        ibytsync.core.input.OmnibarIntent.Empty -> Color(0xFF2A2A2A)
                    },
                    label = "btnColor"
                )

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Search YouTube or paste links…", fontSize = 11.sp, color = Color(0xFF666666)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = when (intent) {
                                is ibytsync.core.input.OmnibarIntent.BatchUrls -> Icons.AutoMirrored.Filled.List
                                is ibytsync.core.input.OmnibarIntent.SingleUrl -> Icons.Filled.PlayArrow
                                else -> Icons.Filled.Search
                            },
                            contentDescription = null,
                            tint = leadingTint,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (textInput.isNotEmpty()) {
                            IconButton(onClick = { textInput = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Color(0xFF888888), modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = when (intent) {
                            is ibytsync.core.input.OmnibarIntent.SearchQuery -> Color(0xFF29B6F6)
                            else -> Color(0xFF1DB954)
                        },
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedTextColor = Color(0xFFEEEEEE),
                        unfocusedTextColor = Color(0xFFEEEEEE),
                        cursorColor = Color(0xFF1DB954)
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = if (intent is ibytsync.core.input.OmnibarIntent.SearchQuery) androidx.compose.ui.text.input.ImeAction.Search else androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = {
                            if (intent is ibytsync.core.input.OmnibarIntent.SearchQuery && textInput.isNotBlank()) {
                                onTriggerSearch(textInput.trim())
                            }
                        },
                        onDone = {
                            if (textInput.isNotBlank()) {
                                onAddInput(textInput.trim())
                                textInput = ""
                            }
                        }
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                )

                Button(
                    onClick = {
                        when (intent) {
                            is ibytsync.core.input.OmnibarIntent.SearchQuery -> onTriggerSearch(textInput.trim())
                            is ibytsync.core.input.OmnibarIntent.SingleUrl, is ibytsync.core.input.OmnibarIntent.BatchUrls -> {
                                onAddInput(textInput.trim())
                                textInput = ""
                            }
                            ibytsync.core.input.OmnibarIntent.Empty -> onPickLocalFile()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    val label = when (intent) {
                        is ibytsync.core.input.OmnibarIntent.SearchQuery -> "SEARCH"
                        is ibytsync.core.input.OmnibarIntent.BatchUrls -> "+ ALL (${intent.urls.size})"
                        is ibytsync.core.input.OmnibarIntent.SingleUrl -> "+ QUEUE"
                        ibytsync.core.input.OmnibarIntent.Empty -> "FILE"
                    }
                    val textColor = when (intent) {
                        is ibytsync.core.input.OmnibarIntent.SearchQuery -> Color.White
                        is ibytsync.core.input.OmnibarIntent.BatchUrls, is ibytsync.core.input.OmnibarIntent.SingleUrl -> Color.Black
                        ibytsync.core.input.OmnibarIntent.Empty -> Color(0xFFDDDDDD)
                    }
                    Text(label, fontSize = 11.sp, color = textColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }

            AnimatedVisibility(visible = intent is ibytsync.core.input.OmnibarIntent.SearchQuery && suggestions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (suggestion in suggestions) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF222628))
                                .border(1.dp, Color(0xFF0288D1).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable {
                                    textInput = suggestion
                                    onTriggerSearch(suggestion)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF29B6F6), modifier = Modifier.size(11.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(suggestion, fontSize = 10.sp, color = Color(0xFFDDDDDD), fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color(0xFF333333), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Batch Queue Empty", fontSize = 14.sp, color = Color(0xFF666666), fontFamily = FontFamily.Monospace)
                    Text("Paste YouTube or Spotify links to start a batch", fontSize = 12.sp, color = Color(0xFF444444))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val clip = clipboardManager.getText()?.text?.trim()
                                if (clip.isNullOrBlank()) {
                                    android.widget.Toast.makeText(context, "Clipboard is empty", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    when (val parsed = ibytsync.core.input.OmnibarClassifier.classify(clip)) {
                                        is ibytsync.core.input.OmnibarIntent.SearchQuery -> {
                                            textInput = clip
                                            onTriggerSearch(clip)
                                        }
                                        else -> onAddInput(clip)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1DB954),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("PASTE", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                        OutlinedButton(
                            onClick = onPickLocalFile,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x66FFFFFF)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE0E0E0)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("+ LOCAL FILE", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFFE0E0E0))
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rows, key = { it.id }) { row ->
                    QueueTrackCard(
                        row = row,
                        onOpenInspector = { onOpenInspector(row) },
                        onRetry = { onRetry(row) },
                        onRemove = { onRemove(row) },
                        onAcceptOption = { onAcceptOption(row) }
                    )
                }
            }
        }
    }
}

@Composable
fun QueueTrackCard(
    row: QueueRow,
    onOpenInspector: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onAcceptOption: () -> Unit = {}
) {
    val canInspect = RowStates.canDecide(row.status) ||
        row.status == RowStatus.METADATA_READY ||
        row.options.isNotEmpty()

    Box(
        Modifier
            .fillMaxWidth()
            .liquidGlassPanel(cornerRadius = 10.dp, lightweight = true)
            .then(
                if (canInspect) {
                    Modifier.clickable(role = Role.Button, onClick = onOpenInspector)
                } else Modifier
            )
            .padding(10.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkThumbnail(
                    url = row.coverUrl,
                    source = row.sourceType(),
                    modifier = Modifier.size(48.dp)
                )

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SourceBadge(row.sourceType())
                        if (row.selectedOption?.isNoMetadata == true) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFFFFB300).copy(alpha = 0.12f))
                                    .border(1.dp, Color(0xFFFFB300).copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text("NO TAGS", fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300))
                            }
                        } else if (row.sourceType() == SourceType.YOUTUBE) {
                            AudioBadge(row.audioPreference)
                        }

                        if (row.duplicateMatch?.isDuplicate == true) {
                            val dup = row.duplicateMatch!!
                            val durStr = if (dup.durationSec > 0) " (${dup.durationSec / 60}m ${dup.durationSec % 60}s)" else ""
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (row.forceUpload) Color(0x2229B6F6) else Color(0x22FFB300))
                                    .border(1.dp, if (row.forceUpload) Color(0x6629B6F6) else Color(0x66FFB300), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    if (row.forceUpload) "UPLOAD ANYWAY" else "IN LIBRARY$durStr",
                                    fontSize = 7.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (row.forceUpload) Color(0xFF29B6F6) else Color(0xFFFFB300)
                                )
                            }
                        }

                        row.audioFormat?.let { fmt ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0x1AFFFFFF))
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(fmt.label, fontSize = 7.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFCCCCCC))
                            }
                        }

                        val plDisplay = when {
                            row.targetPlaylistNames.size > 1 -> "${row.targetPlaylistNames.size} PLAYLISTS"
                            row.targetPlaylistNames.size == 1 -> row.targetPlaylistNames.first()
                            row.targetPlaylistName != null -> row.targetPlaylistName
                            else -> null
                        }
                        plDisplay?.let { plName ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0x1E1DB954))
                                    .border(1.dp, Color(0x441DB954), RoundedCornerShape(3.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text("PLAYLIST: $plName", fontSize = 7.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF1DB954))
                            }
                        }
                    }

                    Spacer(Modifier.height(3.dp))

                    Text(
                        row.title.ifBlank { row.sourceInput.take(40) },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEEEEEE),
                        maxLines = 1
                    )
                    Text(
                        when {
                            row.artist.isNotBlank() && row.detail.isNotBlank() -> "${row.artist} • ${row.detail}"
                            row.artist.isNotBlank() -> row.artist
                            else -> row.detail
                        },
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                        maxLines = 1
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (row.status) {
                        RowStatus.QUEUED -> StatusPill("QUEUED", Color(0xFF888888), active = false)
                        RowStatus.FETCHING_METADATA -> StatusPill("MATCHING…", Color(0xFFFFB300), active = true)
                        RowStatus.AWAITING_ACCEPT -> StatusPill("NEEDS REVIEW", Color(0xFF29B6F6), active = true)
                        RowStatus.METADATA_READY -> StatusPill("READY", Color(0xFF1DB954), active = true)
                        RowStatus.SKIPPED_BAD_URL -> StatusPill("BAD URL", Color(0xFFE91429), active = false)
                        RowStatus.FAILED_METADATA -> StatusPill("FAILED", Color(0xFFE91429), active = false)
                        RowStatus.DOWNLOADING -> StatusPill("DOWNLOADING", Color(0xFF29B6F6), active = true)
                        RowStatus.RETUNING -> StatusPill("PROCESSING", Color(0xFFFFB300), active = true)
                        RowStatus.SAVING -> StatusPill("SAVING", Color(0xFF29B6F6), active = true)
                        RowStatus.UPLOADING -> StatusPill("UPLOADING", Color(0xFF1DB954), active = true)
                        RowStatus.DONE -> StatusPill("SYNCED", Color(0xFF1DB954), active = false)
                        RowStatus.ALREADY_UPLOADED -> StatusPill("DUPLICATE", Color(0xFFFFB300), active = false)
                        RowStatus.FAILED, RowStatus.FAILED_DOWNLOAD, RowStatus.FAILED_SAVE, RowStatus.FAILED_UPLOAD ->
                            StatusPill("FAILED", Color(0xFFE91429), active = false)
                        RowStatus.CANCELLED_UPLOAD -> StatusPill("CANCELLED", Color(0xFF888888), active = false)
                        // AWAITING_FOLDER and SKIPPED_FOLDER are unreachable in production:
                        // FolderStore.save is only called with onConfirm = true. Render them
                        // as the nearest reachable state rather than dead pills.
                        RowStatus.AWAITING_FOLDER, RowStatus.SKIPPED_FOLDER ->
                            StatusPill("READY", Color(0xFF1DB954), active = true)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        if (RowStates.canDecide(row.status)) {
                            IconButton(onClick = onAcceptOption, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.Check, contentDescription = "Accept Suggested Option", tint = Color(0xFF1DB954), modifier = Modifier.size(18.dp))
                            }
                        }
                        if (canInspect) {
                            IconButton(onClick = onOpenInspector, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.Edit, contentDescription = "Inspect Release", tint = Color(0xFFAAAAAA), modifier = Modifier.size(16.dp))
                            }
                        }
                        if (row.status == RowStatus.FAILED_METADATA || row.status == RowStatus.FAILED_DOWNLOAD ||
                            row.status == RowStatus.FAILED_SAVE || row.status == RowStatus.FAILED_UPLOAD ||
                            row.status == RowStatus.FAILED
                        ) {
                            IconButton(onClick = onRetry, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Retry", tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color(0xFF666666), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (row.progress != null || row.status == RowStatus.DOWNLOADING || row.status == RowStatus.UPLOADING) {
                Spacer(Modifier.height(8.dp))
                val barColor = if (row.status == RowStatus.UPLOADING) Color(0xFF1DB954) else Color(0xFF29B6F6)
                val pct = (row.progress ?: 0f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp)),
                    color = barColor,
                    trackColor = Color(0xFF1E1E1E)
                )
            }
        }
    }
}

@Composable
fun ArtworkThumbnail(
    url: String?,
    source: SourceType,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF202020))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(url)
                    .size(128)
                    .crossfade(true)
                    .build(),
                contentDescription = "Cover Art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                when (source) {
                    SourceType.SPOTIFY -> Icons.Filled.PlayArrow
                    SourceType.YOUTUBE -> Icons.Filled.PlayArrow
                    SourceType.LOCAL -> Icons.AutoMirrored.Filled.List
                },
                contentDescription = null,
                tint = source.color.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SourceBadge(source: SourceType) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(source.color.copy(alpha = 0.12f))
            .border(1.dp, source.color.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            source.label,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = source.color
        )
    }
}

@Composable
fun AudioBadge(pref: AudioSourcePreference) {
    val isStudio = pref == AudioSourcePreference.CLEAN_STUDIO
    val badge = if (isStudio) "ALBUM VER." else "VIDEO VER."
    val color = if (isStudio) Color(0xFF1DB954) else ibytsync.android.ui.theme.InfoBlue

    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            badge,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun StatusPill(label: String, color: Color, active: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (active) 0.15f else 0.08f))
            .border(1.dp, color.copy(alpha = if (active) 0.35f else 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
