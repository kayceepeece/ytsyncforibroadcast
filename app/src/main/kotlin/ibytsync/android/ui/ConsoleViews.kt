package ibytsync.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import ibytsync.android.R
import ibytsync.android.ui.theme.liquidGlassPanel
import ibytsync.core.pipeline.AudioFormatChoice
import ibytsync.core.pipeline.QueueRow
import ibytsync.core.pipeline.RowStatus
import ibytsync.core.settings.SettingsStore

/** Shared confirm dialog. Red is reserved for account-level actions; pass grey otherwise. */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    confirmColor: Color,
    titleColor: Color = Color(0xFFEEEEEE),
    extraContent: @Composable (ColumnScope.() -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                title,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, fontSize = 12.sp, color = Color(0xFFCCCCCC))
                extraContent?.invoke(this)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = confirmColor),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(confirmLabel, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("KEEP", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF888888))
            }
        }
    )
}

@Composable
fun TopConsoleBar(
    activeTab: NavTab,
    queueCount: Int,
    syncedCount: Int,
    onClearSynced: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    if (showClearConfirm) {
        ConfirmDialog(
            title = "CLEAR HISTORY?",
            message = "This removes the list on this screen. Your music in iBroadcast is not affected.",
            confirmLabel = "CLEAR HISTORY",
            confirmColor = Color(0xFF3A3A3A),
            onConfirm = {
                onClearSynced()
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "YT SYNC FOR IBROADCAST",
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = Color(0xFFEEEEEE)
            )
            Text(
                "Download · Tag · Sync",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF1DB954),
                letterSpacing = 0.8.sp
            )
        }

        Spacer(Modifier.weight(1f))

        if (activeTab == NavTab.SYNCED && syncedCount > 0) {
            TextButton(
                onClick = { showClearConfirm = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("CLEAR HISTORY", fontSize = 10.sp, color = Color(0xFF888888), fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun MasterActionConsole(
    canStart: Boolean,
    isRunning: Boolean,
    issuesCount: Int,
    queueCount: Int,
    needsSaveFolder: Boolean = false,
    isDownloaderReady: Boolean = true,
    onRetryDownloader: (() -> Unit)? = null,
    destinationName: String = "Library Only",
    onOpenBatchSettings: () -> Unit = {},
    onStartBatch: () -> Unit,
    onCancelBatch: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = if (isRunning) Color(0xFFE91429) else if (canStart) Color(0xFF1DB954) else Color(0xFF262626),
        animationSpec = tween(300), label = "buttonColor"
    )

    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616))
            .border(1.dp, Color(0x1AFFFFFF))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!isDownloaderReady && !isRunning) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x22FFB300))
                        .border(1.dp, Color(0x44FFB300), RoundedCornerShape(6.dp))
                        .then(if (onRetryDownloader != null) Modifier.clickable(role = Role.Button, onClick = onRetryDownloader) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "DOWNLOADER NOT READY",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300)
                    )
                    if (onRetryDownloader != null) {
                        Text(
                            "RETRY",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            } else if (needsSaveFolder && !isRunning) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x22FFB300))
                        .border(1.dp, Color(0x44FFB300), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        "CHOOSE SAVE FOLDER",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300)
                    )
                }
            } else if (!canStart && !isRunning && issuesCount > 0) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x22FFB300))
                        .border(1.dp, Color(0x44FFB300), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (issuesCount == 1) "1 TRACK NEEDS REVIEW" else "$issuesCount TRACKS NEED REVIEW",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300)
                    )
                }
            } else if (queueCount == 0 && !isRunning) {
                Text(
                    "QUEUE EMPTY",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF666666)
                )
            }

            Spacer(Modifier.weight(1f))

            if (!isRunning) {
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x1AFFFFFF))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onOpenBatchSettings)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = "Batch Destination",
                            tint = Color(0xFF1DB954),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "TO: ${destinationName.take(18).uppercase()}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Button(
                onClick = { if (isRunning) onCancelBatch() else onStartBatch() },
                enabled = canStart || isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    disabledContainerColor = Color(0xFF262626)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(44.dp),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                if (isRunning) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "CANCEL BATCH",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = if (canStart) Color.Black else Color(0xFF555555), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (canStart) {
                            if (queueCount == 1) "START BATCH (1 TRACK)" else "START BATCH ($queueCount TRACKS)"
                        } else "START BATCH",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (canStart) Color.Black else Color(0xFF666666)
                    )
                }
            }
        }
    }
}

@Composable
fun BottomNavigationBar(
    currentTab: NavTab,
    queueBadge: Int,
    onSelectTab: (NavTab) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616))
            .border(1.dp, Color(0x1AFFFFFF))
            .navigationBarsPadding()
            .height(58.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavTabItem(
            tab = NavTab.QUEUE,
            currentTab = currentTab,
            icon = Icons.AutoMirrored.Filled.List,
            badge = if (queueBadge > 0) queueBadge.toString() else null,
            onClick = { onSelectTab(NavTab.QUEUE) }
        )
        NavTabItem(
            tab = NavTab.SYNCED,
            currentTab = currentTab,
            icon = Icons.Filled.Check,
            badge = null,
            onClick = { onSelectTab(NavTab.SYNCED) }
        )
        NavTabItem(
            tab = NavTab.SETTINGS,
            currentTab = currentTab,
            icon = Icons.Filled.Settings,
            badge = null,
            onClick = { onSelectTab(NavTab.SETTINGS) }
        )
    }
}

@Composable
private fun NavTabItem(
    tab: NavTab,
    currentTab: NavTab,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badge: String?,
    onClick: () -> Unit
) {
    val isSelected = tab == currentTab
    val tint = if (isSelected) Color(0xFF1DB954) else Color(0xFF777777)

    Column(
        Modifier
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BadgedBox(
            badge = {
                if (badge != null) {
                    Badge(containerColor = Color(0xFF1DB954), contentColor = Color.Black) {
                        Text(badge, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        ) {
            Icon(icon, contentDescription = tab.title, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(
            tab.title.uppercase(),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = tint,
            letterSpacing = 1.sp
        )
    }
}

enum class SyncedViewMode {
    ALL_TRACKS,
    BY_BATCH
}

private fun formatBatchLabel(batchId: String?): String {
    if (batchId == null) return "Previous Uploads"
    val ts = batchId.removePrefix("batch_").toLongOrNull()
    return if (ts != null) {
        val sdf = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
        "Batch • ${sdf.format(java.util.Date(ts))}"
    } else {
        "Batch • $batchId"
    }
}

@Composable
fun SyncedTabContent(
    syncedList: List<QueueRow>,
    sortOrder: SyncedSortOrder = SyncedSortOrder.NEWEST_FIRST,
    onSortOrderChange: (SyncedSortOrder) -> Unit = {},
    onTrackClick: (QueueRow) -> Unit = {}
) {
    var viewMode by remember { mutableStateOf(SyncedViewMode.ALL_TRACKS) }
    var filterText by remember { mutableStateOf("") }

    val sortedList = remember(syncedList, sortOrder) {
        when (sortOrder) {
            SyncedSortOrder.NEWEST_FIRST -> syncedList.sortedByDescending { row ->
                row.batchId?.removePrefix("batch_")?.toLongOrNull() ?: 0L
            }
            SyncedSortOrder.OLDEST_FIRST -> syncedList.sortedBy { row ->
                row.batchId?.removePrefix("batch_")?.toLongOrNull() ?: 0L
            }
        }
    }

    val filteredList = remember(sortedList, filterText) {
        val q = filterText.trim().lowercase()
        if (q.isEmpty()) sortedList
        else sortedList.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (syncedList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF333333), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No Synced Tracks Yet", fontSize = 14.sp, color = Color(0xFF666666), fontFamily = FontFamily.Monospace)
                    Text("Tracks processed and uploaded will appear here", fontSize = 12.sp, color = Color(0xFF444444))
                }
            }
        } else {
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = {
                    Text(
                        if (filterText.isBlank()) "Filter synced tracks…" else "${filteredList.size}/${syncedList.size}",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF666666), modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    if (filterText.isNotEmpty()) {
                        IconButton(onClick = { filterText = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear filter", tint = Color(0xFF888888), modifier = Modifier.size(14.dp))
                        }
                    }
                },
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1DB954),
                    unfocusedBorderColor = Color(0x33FFFFFF),
                    focusedTextColor = Color(0xFFEEEEEE),
                    unfocusedTextColor = Color(0xFFEEEEEE),
                    cursorColor = Color(0xFF1DB954)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                        .padding(2.dp)
                ) {
                    val allSelected = viewMode == SyncedViewMode.ALL_TRACKS
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (allSelected) Color(0xFF1DB954) else Color.Transparent)
                            .clickable { viewMode = SyncedViewMode.ALL_TRACKS }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "ALL TRACKS (${syncedList.size})",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (allSelected) Color.Black else Color(0xFFAAAAAA)
                        )
                    }

                    val batchesCount = remember(filteredList) {
                        filteredList.groupBy { it.batchId ?: "legacy" }.keys.size
                    }
                    val batchSelected = viewMode == SyncedViewMode.BY_BATCH
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (batchSelected) Color(0xFF1DB954) else Color.Transparent)
                            .clickable { viewMode = SyncedViewMode.BY_BATCH }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "BY BATCH ($batchesCount)",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (batchSelected) Color.Black else Color(0xFFAAAAAA)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E1E1E))
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(6.dp))
                        .clickable {
                            val next = if (sortOrder == SyncedSortOrder.NEWEST_FIRST) {
                                SyncedSortOrder.OLDEST_FIRST
                            } else {
                                SyncedSortOrder.NEWEST_FIRST
                            }
                            onSortOrderChange(next)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (sortOrder == SyncedSortOrder.NEWEST_FIRST) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Change sort order",
                        tint = Color(0xFFAAAAAA),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (sortOrder == SyncedSortOrder.NEWEST_FIRST) "NEWEST" else "OLDEST",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFCCCCCC)
                    )
                }
            }

            if (viewMode == SyncedViewMode.ALL_TRACKS) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredList, key = { it.id }) { row ->
                        SyncedTrackCard(row = row, onClick = { onTrackClick(row) })
                    }
                }
            } else {
                val grouped = remember(filteredList, sortOrder) {
                    val rawGroups = filteredList.groupBy { it.batchId ?: "legacy" }
                    val sortedEntries = when (sortOrder) {
                        SyncedSortOrder.NEWEST_FIRST -> rawGroups.entries.sortedByDescending { (key, _) ->
                            key.removePrefix("batch_").toLongOrNull() ?: 0L
                        }
                        SyncedSortOrder.OLDEST_FIRST -> rawGroups.entries.sortedBy { (key, _) ->
                            key.removePrefix("batch_").toLongOrNull() ?: 0L
                        }
                    }
                    sortedEntries.associate { (key, tracks) ->
                        val sortedTracks = when (sortOrder) {
                            SyncedSortOrder.NEWEST_FIRST -> tracks.sortedByDescending { it.batchId?.removePrefix("batch_")?.toLongOrNull() ?: 0L }
                            SyncedSortOrder.OLDEST_FIRST -> tracks.sortedBy { it.batchId?.removePrefix("batch_")?.toLongOrNull() ?: 0L }
                        }
                        key to sortedTracks
                    }
                }
                val expandedMap = remember { mutableStateMapOf<String, Boolean>() }
                LaunchedEffect(grouped.keys) {
                    for (k in grouped.keys) {
                        if (!expandedMap.containsKey(k)) expandedMap[k] = true
                    }
                }
                val allExpanded = grouped.keys.all { expandedMap[it] == true }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${grouped.size} BATCHES",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF888888),
                        letterSpacing = 1.sp
                    )
                    TextButton(
                        onClick = {
                            val nextState = !allExpanded
                            for (k in grouped.keys) expandedMap[k] = nextState
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            if (allExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color(0xFF1DB954),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (allExpanded) "COLLAPSE ALL" else "EXPAND ALL",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1DB954)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    grouped.forEach { (batchKey, tracks) ->
                        item(key = batchKey) {
                            val isExpanded = expandedMap[batchKey] ?: true
                            val destinationName = tracks.firstNotNullOfOrNull { track ->
                                when {
                                    track.targetPlaylistNames.size > 1 -> "${track.targetPlaylistNames.size} Playlists"
                                    track.targetPlaylistNames.size == 1 -> track.targetPlaylistNames.first()
                                    else -> track.targetPlaylistName
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF191919))
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { expandedMap[batchKey] = !isExpanded }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        contentDescription = null,
                                        tint = Color(0xFF1DB954),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            formatBatchLabel(if (batchKey == "legacy") null else batchKey),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFFEEEEEE)
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                if (tracks.size == 1) "1 track" else "${tracks.size} tracks",
                                                fontSize = 10.sp,
                                                color = Color(0xFF888888)
                                            )
                                            if (!destinationName.isNullOrBlank()) {
                                                Text("•", fontSize = 10.sp, color = Color(0xFF666666))
                                                Text(
                                                    "Playlist: $destinationName",
                                                    fontSize = 10.sp,
                                                    color = Color(0xFF29B6F6)
                                                )
                                            }
                                        }
                                    }
                                    Icon(
                                        if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color(0xFF888888),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                if (isExpanded) {
                                    HorizontalDivider(color = Color(0x1FFFFFFF))
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        tracks.forEach { row ->
                                            SyncedTrackCard(row = row, onClick = { onTrackClick(row) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedTrackCard(row: QueueRow, onClick: () -> Unit) {
    val opacity = if (row.isEditingInStudio) 0.55f else 1.0f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF181818).copy(alpha = opacity))
            .border(1.dp, Color(0x22FFFFFF).copy(alpha = opacity), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkThumbnail(
            url = row.coverUrl,
            source = row.sourceType(),
            modifier = Modifier.size(42.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE0E0E0).copy(alpha = opacity),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                if (row.artist.isNotBlank()) "${row.artist} • ${row.album}" else row.detail,
                fontSize = 11.sp,
                color = Color(0xFF777777).copy(alpha = opacity),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(6.dp))
        when {
            row.isEditingInStudio -> {
                StatusPill(
                    label = "EDITING",
                    color = Color(0xFFFFB300),
                    active = false
                )
            }
            row.isUpdated -> {
                StatusPill(
                    label = "UPDATED",
                    color = Color(0xFF1DB954),
                    active = false
                )
            }
            row.status == RowStatus.ALREADY_UPLOADED -> {
                StatusPill(
                    label = "DUPLICATE",
                    color = Color(0xFFFFB300),
                    active = false
                )
            }
            else -> {
                StatusPill(
                    label = "SYNCED",
                    color = Color(0xFF1DB954).copy(alpha = 0.8f),
                    active = false
                )
            }
        }
    }
}

@Composable
fun SettingsTabContent(
    settings: SettingsStore,
    queuedCount: Int,
    onPickFolderSaf: () -> Unit,
    onStartLogin: () -> Unit,
    onDisconnect: () -> Unit,
    onDefaultFormatChange: (AudioFormatChoice) -> Unit = {}
) {
    var saveToDisk by remember { mutableStateOf(settings.isLocalSave()) }
    var defaultAudioFormat by remember { mutableStateOf(settings.getDefaultAudioFormat()) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var clientIdInput by remember { mutableStateOf(settings.getClientId().takeIf { it != SettingsStore.DEFAULT_CLIENT_ID } ?: "") }
    LaunchedEffect(settings) {
        saveToDisk = settings.isLocalSave()
        defaultAudioFormat = settings.getDefaultAudioFormat()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlassPanel(cornerRadius = 12.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isLoggedIn = settings.isLoggedIn()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "IBROADCAST ACCOUNT",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFAAAAAA),
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                StatusPill(
                    label = if (isLoggedIn) "CONNECTED" else "NOT CONNECTED",
                    color = if (isLoggedIn) Color(0xFF1DB954) else Color(0xFFFFB300),
                    active = isLoggedIn
                )
            }
            if (isLoggedIn) {
                Text("Connected · Library and upload access", fontSize = 12.sp, color = Color(0xFFEEEEEE))
            } else {
                Text("Connect your iBroadcast account to upload music directly to the cloud.", fontSize = 12.sp, color = Color(0xFF888888))
            }

            // Client ID — deliberately above the sign-in button: sign-in, uploads and silent
            // token refresh all read it, so it must be settable before the first sign-in.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "CLIENT ID",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFAAAAAA),
                    letterSpacing = 1.sp
                )
                OutlinedTextField(
                    value = clientIdInput,
                    onValueChange = { clientIdInput = it.trim() },
                    placeholder = {
                        Text(
                            SettingsStore.DEFAULT_CLIENT_ID,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF666666)
                        )
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1DB954),
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedTextColor = Color(0xFFEEEEEE),
                        unfocusedTextColor = Color(0xFFEEEEEE),
                        cursorColor = Color(0xFF1DB954)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (clientIdInput.isBlank()) "Using the built-in app ID"
                        else if (isLoggedIn) "Saved — sign in again to use it"
                        else "Saved for sign-in",
                        fontSize = 10.sp,
                        color = Color(0xFF888888),
                        modifier = Modifier.weight(1f)
                    )
                    if (clientIdInput.isNotBlank()) {
                        TextButton(onClick = {
                            clientIdInput = ""
                            settings.setClientId("")
                        }) {
                            Text("RESET", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF29B6F6))
                        }
                    }
                }
                LaunchedEffect(clientIdInput) {
                    settings.setClientId(clientIdInput)
                }
            }

            if (isLoggedIn) {
                Button(
                    onClick = { showDisconnectConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262626)),
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Disconnect Account", fontSize = 11.sp, color = Color(0xFFE91429))
                }
            } else {
                Button(
                    onClick = onStartLogin,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Sign in to iBroadcast", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            // Official "Powered by iBroadcast" logo. The branding guide forbids modifying it,
            // so this is the published asset verbatim — aspect ratio locked, no recolouring.
            Image(
                painter = painterResource(R.drawable.powered_by_ibroadcast),
                contentDescription = "Powered by iBroadcast",
                modifier = Modifier
                    .width(128.dp)
                    .aspectRatio(338.7f / 79.5f)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlassPanel(cornerRadius = 12.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AUDIO FORMAT",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFAAAAAA),
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                StatusPill(
                    label = defaultAudioFormat.label,
                    color = Color(0xFF1DB954),
                    active = true
                )
            }
            Text(
                "Format used for downloads. OPUS and M4A download fastest and use less battery. MP3 works on almost any player.",
                fontSize = 12.sp,
                color = Color(0xFF888888)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (fmt in AudioFormatChoice.values()) {
                    val isSel = defaultAudioFormat == fmt
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSel) Color(0x2E1DB954) else Color(0x1AFFFFFF))
                            .border(
                                width = if (isSel) 1.5.dp else 1.dp,
                                color = if (isSel) Color(0xFF1DB954) else Color(0x22FFFFFF),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                defaultAudioFormat = fmt
                                settings.setDefaultAudioFormat(fmt)
                                onDefaultFormatChange(fmt)
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            fmt.label,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isSel) Color(0xFF1DB954) else Color(0xFFCCCCCC)
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlassPanel(cornerRadius = 12.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "OFFLINE SONGS",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFAAAAAA),
                letterSpacing = 1.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Save a copy on this device", fontSize = 13.sp, color = Color(0xFFEEEEEE))
                    Text("Keeps a copy you can play anywhere", fontSize = 11.sp, color = Color(0xFF888888))
                }
                Switch(
                    checked = saveToDisk,
                    onCheckedChange = {
                        saveToDisk = it
                        settings.setLocalSave(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF1DB954),
                        checkedTrackColor = Color(0xFF0F5A28)
                    )
                )
            }
            if (saveToDisk) {
                Button(
                    onClick = onPickFolderSaf,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text("CHOOSE FOLDER", fontSize = 12.sp, color = Color(0xFF1DB954))
                }
            }
        }

        val context = LocalContext.current
        var isIgnoringBattery by remember { mutableStateOf(settings.isIgnoringBatteryOptimizations(context)) }

        LaunchedEffect(Unit) {
            isIgnoringBattery = settings.isIgnoringBatteryOptimizations(context)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlassPanel(cornerRadius = 12.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "BACKGROUND RELIABILITY",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFAAAAAA),
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                StatusPill(
                    label = if (isIgnoringBattery) "UNRESTRICTED" else "OPTIMIZED",
                    color = if (isIgnoringBattery) Color(0xFF1DB954) else Color(0xFFFFB300),
                    active = isIgnoringBattery
                )
            }
            if (isIgnoringBattery) {
                Text(
                    "Battery optimization is disabled. Downloads and tag fetching will run reliably in the background and when the screen is off.",
                    fontSize = 12.sp,
                    color = Color(0xFFEEEEEE)
                )
            } else {
                Text(
                    "Battery saver or system optimization may pause downloads and tag matching when you switch apps or turn off your screen.",
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
                Button(
                    onClick = {
                        settings.requestIgnoreBatteryOptimizations(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text(
                        "ALLOW UNRESTRICTED BACKGROUND",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF1DB954),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showDisconnectConfirm) {
        ConfirmDialog(
            title = "DISCONNECT ACCOUNT?",
            titleColor = Color(0xFFE91429),
            message = "Your uploaded music stays in iBroadcast. You just can't upload anything new until you sign in again.",
            confirmLabel = "DISCONNECT",
            confirmColor = Color(0xFFE91429),
            extraContent = if (queuedCount > 0) {
                {
                    Text(
                        if (queuedCount == 1) "1 track waiting in the queue will be removed."
                        else "$queuedCount tracks waiting in the queue will be removed.",
                        fontSize = 12.sp,
                        color = Color(0xFFFFB300)
                    )
                }
            } else null,
            onConfirm = {
                settings.clearTokens()
                onDisconnect()
                showDisconnectConfirm = false
            },
            onDismiss = { showDisconnectConfirm = false }
        )
    }
}
