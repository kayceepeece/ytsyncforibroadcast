package ibytsync.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ibytsync.android.ui.theme.liquidGlassPanel
import ibytsync.core.metadata.ReleaseOption
import ibytsync.core.pipeline.AudioFormatChoice
import ibytsync.core.pipeline.AudioSourcePreference
import ibytsync.core.pipeline.QueueRow
import ibytsync.core.pipeline.RowStatus
import ibytsync.core.pipeline.albumReleaseDurationMs
import ibytsync.core.pipeline.sourceFileDurationMs
import ibytsync.core.upload.Playlist

enum class InspectorTab {
    METADATA,
    AUDIO_SIZE
}

/** How long the APPLIED state is held before the sheet closes, so the change is seen. */
private const val APPLIED_FEEDBACK_MS = 500L

/**
 * Stable identity for a release card.
 *
 * Matches the key the options list uses, and unlike `equals` it survives the synthetic cards
 * being rebuilt, so "is the previewed card the committed one?" does not flicker between states.
 */
internal fun optionKey(option: ReleaseOption): String =
    "${option.sources}_${option.trackTitle}_${option.album}_${option.artist}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleasePickerSheet(
    row: QueueRow,
    batchDestinationName: String = "Library Only",
    batchDestinationIds: List<String> = emptyList(),
    batchDestinationNames: List<String> = emptyList(),
    availablePlaylists: List<Playlist> = emptyList(),
    defaultAudioFormat: AudioFormatChoice = AudioFormatChoice.MP3_192K,
    onReSearch: ((title: String, artist: String) -> Unit)? = null,
    onCreatePlaylist: (suspend (String) -> Playlist?)? = null,
    /** Commits the match. Preferences are already saved by the callbacks below. */
    onSelectOption: (option: ReleaseOption) -> Unit,
    /** Persists a preference the moment it changes, so closing the sheet loses nothing. */
    onStageAudio: (audioPref: AudioSourcePreference, format: AudioFormatChoice, forceUpload: Boolean) -> Unit,
    onStageDestination: (playlistId: String?, playlistName: String?, playlistIds: List<String>, playlistNames: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var previewOpt by remember(row.id) {
        mutableStateOf(
            row.selectedOption
                ?: row.options.firstOrNull()
                ?: ReleaseOption(
                    artist = row.artist,
                    trackTitle = row.title,
                    album = row.album,
                    artworkUrl = row.coverUrl,
                    durationMs = row.durationMs,
                    isrc = null,
                    sources = listOf("manual"),
                    score = 100,
                    year = row.year,
                    genre = row.genre,
                    trackNumber = row.trackNumber,
                    trackCount = row.trackCount
                )
        )
    }
    var userPicked by remember(row.id) { mutableStateOf(false) }
    LaunchedEffect(row.id, row.selectedOption, row.options) {
        if (!userPicked) {
            row.selectedOption?.let { previewOpt = it }
                ?: row.options.firstOrNull()?.let { previewOpt = it }
        }
    }
    var selectedAudioPref by remember(row.id) { mutableStateOf(row.audioPreference) }
    var showEditArtworkDialog by remember(row.id) { mutableStateOf(false) }
    var showEditMetadataDialog by remember(row.id) { mutableStateOf(false) }
    var activeTab by remember(row.id) { mutableStateOf(InspectorTab.METADATA) }
    var selectedPlaylistId by remember(row.id) { mutableStateOf(row.targetPlaylistId) }
    var selectedPlaylistName by remember(row.id) { mutableStateOf(row.targetPlaylistName) }
    var selectedPlaylistIds by remember(row.id) { mutableStateOf(row.targetPlaylistIds) }
    var selectedPlaylistNames by remember(row.id) { mutableStateOf(row.targetPlaylistNames) }
    var forceUpload by remember(row.id) { mutableStateOf(row.forceUpload) }
    var chosenFormat by remember(row.id) { mutableStateOf(row.audioFormat ?: defaultAudioFormat) }
    var showPlaylistDialog by remember(row.id) { mutableStateOf(false) }

    // A row is decided once a match has been committed. Preferences never set this — they only
    // shape how the committed match runs, which is why they can be saved as they change.
    val isDecided = row.status == RowStatus.METADATA_READY && row.selectedOption != null

    // Identity key rather than object equality: effectiveOptions rebuilds synthetic cards
    // (no-metadata, raw-video) on every composition, so a committed card can differ from a
    // freshly built twin by a field such as durationMs and would otherwise read as "changed".
    val appliedKey = row.selectedOption?.let { optionKey(it) }
    val previewKey = optionKey(previewOpt)
    val matchChanged = isDecided && previewKey != appliedKey

    // The button must not close the sheet before the user sees the state change register.
    val scope = rememberCoroutineScope()
    var justApplied by remember(row.id) { mutableStateOf(false) }

    // True once the committed match is the one on screen — either because it already was, or
    // because it was just committed and the sheet is holding for the confirmation beat.
    val showApplied = justApplied || (isDecided && !matchChanged)
    val showUpdate = isDecided && matchChanged

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                previewOpt = previewOpt.copy(artworkUrl = uri.toString())
            }
        }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141414),
        scrimColor = Color.Black.copy(alpha = 0.65f)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "CHOOSE A MATCH",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF29B6F6),
                        letterSpacing = 1.sp
                    )
                    Text(row.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFFAAAAAA))
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val destLabel = when {
                    selectedPlaylistNames.size > 1 -> "${selectedPlaylistNames.size} Playlists (${selectedPlaylistNames.joinToString(", ")})"
                    selectedPlaylistNames.size == 1 -> selectedPlaylistNames.first()
                    selectedPlaylistName != null -> selectedPlaylistName!!
                    else -> "Batch Default ($batchDestinationName)"
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x1AFFFFFF))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                        .clickable { showPlaylistDialog = true }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            tint = Color(0xFF1DB954),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "PLAYLIST: ${destLabel.take(24)} ▾",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                if (row.duplicateMatch?.isDuplicate == true) {
                    val dup = row.duplicateMatch!!
                    val durStr = if (dup.durationSec > 0) " (${dup.durationSec / 60}m ${dup.durationSec % 60}s)" else ""
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (forceUpload) Color(0x2229B6F6) else Color(0x22FFB300))
                            .border(1.dp, if (forceUpload) Color(0x6629B6F6) else Color(0x66FFB300), RoundedCornerShape(6.dp))
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Toggle force upload",
                                onClick = {
                                    forceUpload = !forceUpload
                                    onStageAudio(selectedAudioPref, chosenFormat, forceUpload)
                                }
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (forceUpload) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                contentDescription = null,
                                tint = if (forceUpload) Color(0xFF29B6F6) else Color(0xFFFFB300),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (forceUpload) "UPLOADING ANYWAY" else "IN YOUR LIBRARY$durStr • TAP TO UPLOAD ANYWAY",
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (forceUpload) Color(0xFF29B6F6) else Color(0xFFFFB300)
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val isMetaTab = activeTab == InspectorTab.METADATA
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isMetaTab) Color(0xFF2E2E2E) else Color.Transparent)
                        .clickable { activeTab = InspectorTab.METADATA }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "TAGS & ARTWORK",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isMetaTab) Color(0xFF1DB954) else Color(0xFF888888)
                    )
                }

                val isAudioTab = activeTab == InspectorTab.AUDIO_SIZE
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isAudioTab) Color(0xFF2E2E2E) else Color.Transparent)
                        .clickable { activeTab = InspectorTab.AUDIO_SIZE }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "AUDIO & FORMAT",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isAudioTab) Color(0xFF1DB954) else Color(0xFF888888)
                    )
                }
            }

            HorizontalDivider(color = Color(0x14FFFFFF))

            if (activeTab == InspectorTab.METADATA) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0x331DB954), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .size(92.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF242424))
                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                        ) {
                            if (previewOpt.isNoMetadata) {
                                Column(
                                    Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Filled.Clear, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.height(2.dp))
                                    Text("NO ART", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300))
                                }
                            } else if (!previewOpt.artworkUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                        .data(previewOpt.artworkUrl)
                                        .size(240)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Cover Art Preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Column(
                                    Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = Color(0xFF555555),
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text("DEFAULT ART", fontSize = 7.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF666666))
                                }
                            }

                            if (!previewOpt.isNoMetadata) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.75f))
                                        .border(1.dp, Color(0xFF1DB954), CircleShape)
                                        .clickable(role = Role.Button, onClick = { showEditArtworkDialog = true }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "Change Cover Art",
                                        tint = Color(0xFF1DB954),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                if (previewOpt.isNoMetadata) "No Tags" else previewOpt.trackTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1
                            )
                            Text(
                                if (previewOpt.isNoMetadata) {
                                    "Audio only — no title, artist or cover"
                                } else {
                                    listOf(previewOpt.artist, previewOpt.album)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" • ")
                                },
                                fontSize = 12.sp,
                                color = if (previewOpt.isNoMetadata) Color(0xFFFFB300) else Color(0xFFCCCCCC),
                                maxLines = 1
                            )
                            Spacer(Modifier.height(6.dp))

                            if (previewOpt.isNoMetadata) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MetaTagChip("NO TAGS", Color(0xFFFFB300))
                                    Spacer(Modifier.width(4.dp))
                                    MetaTagChip("NO COVER", Color(0xFFFFB300))
                                }
                            } else {
                                // Duration is deliberately absent: it describes the downloaded audio
                                // rather than something we tag, so it lives in Audio & Format.
                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (previewOpt.year != null) {
                                        MetaTagChip("YEAR ${previewOpt.year}", ibytsync.android.ui.theme.InfoBlue)
                                    }
                                    if (previewOpt.genre != null) {
                                        MetaTagChip(previewOpt.genre!!.uppercase(), Color(0xFFB39DDB))
                                    }
                                    if (previewOpt.trackNumber != null) {
                                        val trk = "TRK ${previewOpt.trackNumber}${if (previewOpt.trackCount != null) "/${previewOpt.trackCount}" else ""}"
                                        MetaTagChip(trk, Color(0xFF81C784))
                                    }
                                }

                                // Shown only once YouTube Music has confirmed a real album
                                // track running a meaningfully different length, so the offer
                                // can be kept. Tapping stages the preference; the match itself
                                // is still committed by the primary button.
                                row.officialAudioId?.let {
                                    Spacer(Modifier.height(8.dp))
                                    val onAlbumAudio = selectedAudioPref == AudioSourcePreference.CLEAN_STUDIO
                                    SwitchToAlbumChip(
                                        durationMs = row.officialAudioDurationMs,
                                        active = onAlbumAudio,
                                        onClick = {
                                            val next = if (onAlbumAudio) {
                                                AudioSourcePreference.ORIGINAL_VIDEO
                                            } else {
                                                AudioSourcePreference.CLEAN_STUDIO
                                            }
                                            selectedAudioPref = next
                                            onStageAudio(next, chosenFormat, forceUpload)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(4.dp))

                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x221DB954))
                                .border(1.dp, Color(0x551DB954), RoundedCornerShape(6.dp))
                                .clickable(role = Role.Button, onClick = { showEditMetadataDialog = true }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Edit tags",
                                tint = Color(0xFF1DB954),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (showApplied) {
                                    onDismiss()
                                } else {
                                    onSelectOption(previewOpt)
                                    justApplied = true
                                    scope.launch {
                                        delay(APPLIED_FEEDBACK_MS)
                                        onDismiss()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showApplied) Color(0xFF2E7D32) else Color(0xFF1DB954)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(
                                if (showApplied) Icons.Filled.Done else Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    showApplied -> "APPLIED"
                                    showUpdate -> "UPDATE MATCH"
                                    previewOpt.isNoMetadata -> "USE AUDIO ONLY"
                                    else -> "USE THIS MATCH"
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }

                        if (onReSearch != null) {
                            OutlinedButton(
                                onClick = {
                                    onReSearch(previewOpt.trackTitle, previewOpt.artist)
                                },
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x6629B6F6)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF29B6F6)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color(0xFF29B6F6), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "SEARCH AGAIN",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF29B6F6)
                                )
                            }
                        }
                    }
                }

                if (row.status == RowStatus.FETCHING_METADATA) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .liquidGlassPanel(cornerRadius = 8.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF29B6F6),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            "Searching Spotify, iTunes and Deezer…",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF29B6F6)
                        )
                    }
                }

                val effectiveOptions = remember(row) {
                    val list = row.options.toMutableList()
                    if (list.none { it.isNoMetadata }) {
                        list.add(ReleaseOption.noMetadata(row.title, row.durationMs))
                    }
                    if (list.none { it.isOriginalSource }) {
                        if (row.sourceType() == SourceType.LOCAL) {
                            list.add(
                                ReleaseOption.originalFile(
                                    title = row.title,
                                    artist = row.artist,
                                    album = row.album,
                                    year = row.year,
                                    durationMs = row.durationMs,
                                    genre = row.genre,
                                    trackNumber = row.trackNumber,
                                    trackCount = row.trackCount,
                                    artworkUrl = row.coverUrl
                                )
                            )
                        } else if (row.sourceType() == SourceType.YOUTUBE) {
                            list.add(ReleaseOption.rawVideo(videoTitle = row.title, channel = row.artist, thumbnailUrl = row.coverUrl, durationMs = row.durationMs))
                        }
                    }
                    list
                }

                Text(
                    if (effectiveOptions.size == 1) "1 MATCH FOUND" else "${effectiveOptions.size} MATCHES FOUND",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFAAAAAA),
                    letterSpacing = 1.sp
                )

                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(effectiveOptions, key = { "${it.sources}_${it.trackTitle}_${it.album}_${it.artist}" }) { opt ->
                        val isSelected = previewOpt == opt
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0x2E1DB954) else Color(0xFF1E1E1E))
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF1DB954) else Color(0x22FFFFFF),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(role = Role.Button, onClick = { previewOpt = opt; userPicked = true })
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF282828))
                            ) {
                                if (opt.isNoMetadata) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Clear, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(20.dp))
                                    }
                                } else if (!opt.artworkUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                            .data(opt.artworkUrl)
                                            .size(128)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "Cover",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF555555), modifier = Modifier.size(22.dp))
                                    }
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            Column(Modifier.weight(1f)) {
                                val mainTitle = when {
                                    opt.isNoMetadata -> "No tags — audio only"
                                    opt.isOriginalSource -> opt.trackTitle
                                    else -> opt.album
                                }
                                val subTitle = when {
                                    opt.isNoMetadata -> "Keeps the audio as-is — no title, artist or cover"
                                    opt.isOriginalSource -> if (row.sourceType() == SourceType.LOCAL) "Original file tags • ${opt.artist.ifBlank { "Unknown" }}" else "Info from the video"
                                    else -> "${opt.trackTitle} • ${opt.artist}"
                                }
                                Text(mainTitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (opt.isNoMetadata) Color(0xFFFFB300) else Color(0xFFEEEEEE), maxLines = 1)
                                Text(subTitle, fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 1)
                                val tagLabel = when {
                                    opt.isNoMetadata -> "NO TAGS"
                                    opt.isOriginalSource -> {
                                        val dur = formatDurationMs(opt.durationMs)
                                        if (row.sourceType() == SourceType.LOCAL) "ORIGINAL TAGS • $dur" else "FROM VIDEO • $dur"
                                    }
                                    else -> listOfNotNull(opt.year, formatDurationMs(opt.durationMs), opt.sources.joinToString(" + ") { it.uppercase() }).joinToString(" • ")
                                }
                                Text(
                                    tagLabel,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isSelected) Color(0xFF1DB954) else if (opt.isNoMetadata) Color(0xFFFFB300) else Color(0xFF29B6F6)
                                )
                            }

                            Spacer(Modifier.width(6.dp))

                            if (isSelected) {
                                Box(
                                    Modifier
                                        .background(Color(0xFF1DB954), CircleShape)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "ACTIVE",
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                            } else {
                                Text(
                                    "PREVIEW",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF666666)
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (row.sourceType() == SourceType.YOUTUBE) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "AUDIO SOURCE",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFAAAAAA),
                                letterSpacing = 0.8.sp
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val isStudio = selectedAudioPref == AudioSourcePreference.CLEAN_STUDIO
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isStudio) Color(0x2E1DB954) else Color(0x1AFFFFFF))
                                        .border(
                                            width = if (isStudio) 1.5.dp else 1.dp,
                                            color = if (isStudio) Color(0xFF1DB954) else Color(0x22FFFFFF),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            selectedAudioPref = AudioSourcePreference.CLEAN_STUDIO
                                            onStageAudio(selectedAudioPref, chosenFormat, forceUpload)
                                        }
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = null,
                                                tint = if (isStudio) Color(0xFF1DB954) else Color(0xFF555555),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "ALBUM AUDIO",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isStudio) Color(0xFF1DB954) else Color(0xFFCCCCCC)
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text("Audio from the released album", fontSize = 10.sp, color = Color(0xFF888888))
                                    }
                                }

                                val isVideo = selectedAudioPref == AudioSourcePreference.ORIGINAL_VIDEO
                                val videoBlue = ibytsync.android.ui.theme.InfoBlue
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isVideo) Color(0x2E29B6F6) else Color(0x1AFFFFFF))
                                        .border(
                                            width = if (isVideo) 1.5.dp else 1.dp,
                                            color = if (isVideo) videoBlue else Color(0x22FFFFFF),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            selectedAudioPref = AudioSourcePreference.ORIGINAL_VIDEO
                                            onStageAudio(selectedAudioPref, chosenFormat, forceUpload)
                                        }
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = null,
                                                tint = if (isVideo) videoBlue else Color(0xFF555555),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "VIDEO AUDIO",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isVideo) videoBlue else Color(0xFFCCCCCC)
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text("Audio exactly as in the video", fontSize = 10.sp, color = Color(0xFF888888))
                                    }
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "FORMAT & QUALITY",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFAAAAAA),
                            letterSpacing = 0.8.sp
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (fmt in AudioFormatChoice.entries) {
                                val isSel = chosenFormat == fmt
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
                                            chosenFormat = fmt
                                            onStageAudio(selectedAudioPref, chosenFormat, forceUpload)
                                        }
                                        .padding(vertical = 10.dp),
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

                    // Quote the file the chosen audio source will actually fetch: Video Audio keeps
                    // the source video, Album Audio swaps in the released master. Falling back to
                    // the previewed card would quote the album's length while Video Audio is on.
                    val estimatedMs = if (selectedAudioPref == AudioSourcePreference.ORIGINAL_VIDEO) {
                        row.sourceFileDurationMs() ?: row.durationMs
                    } else {
                        row.albumReleaseDurationMs() ?: previewOpt.durationMs ?: row.durationMs
                    }
                    val durationSec = ((estimatedMs ?: 210_000L) / 1000L).coerceAtLeast(1L)
                    val estMb = (chosenFormat.approxBitrateKbps.toDouble() * durationSec) / (8.0 * 1024.0)

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "FILE DETAILS",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1DB954),
                            letterSpacing = 1.sp
                        )

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("ESTIMATED SIZE", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF888888))
                                Text(
                                    String.format("~%.1f MB", estMb),
                                    fontSize = 20.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "Actual size may vary by ~15%",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF666666)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("DURATION", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF888888))
                                Text(
                                    formatDurationMs(durationSec * 1000L),
                                    fontSize = 16.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF29B6F6)
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0x14FFFFFF))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("FORMAT: ${chosenFormat.extension.uppercase()}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFAAAAAA))
                            Text("QUALITY: ${chosenFormat.approxBitrateKbps} kbps", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFAAAAAA))
                        }
                    }

                    // Preferences here save the moment they are changed, so there is nothing left
                    // to commit — this only closes the sheet.
                    Text(
                        "Saved as you change them",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF666666)
                    )

                    Button(
                        onClick = { onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Filled.Done, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "DONE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        if (showPlaylistDialog) {
            PlaylistPickerBottomSheet(
                playlists = availablePlaylists,
                selectedId = selectedPlaylistId,
                selectedName = selectedPlaylistName,
                selectedIds = selectedPlaylistIds,
                selectedNames = selectedPlaylistNames,
                batchDefaultName = batchDestinationName,
                batchDefaultIds = batchDestinationIds,
                batchDefaultNames = batchDestinationNames,
                onCreatePlaylist = onCreatePlaylist,
                onSelect = { id, name ->
                    selectedPlaylistId = id
                    selectedPlaylistName = name
                    selectedPlaylistIds = if (id != null && id != "lib_only") listOf(id) else emptyList()
                    selectedPlaylistNames = if (name.isNotBlank() && name != "Library Only" && !name.startsWith("Batch Default")) listOf(name) else emptyList()
                    onStageDestination(id, name, selectedPlaylistIds, selectedPlaylistNames)
                },
                onSelectMultiple = { ids, names ->
                    selectedPlaylistIds = ids
                    selectedPlaylistNames = names
                    selectedPlaylistId = if (ids.contains("lib_only")) "lib_only" else ids.firstOrNull()
                    selectedPlaylistName = when {
                        names.any { it.startsWith("Batch Default") } -> names.first()
                        ids.contains("lib_only") || names.contains("Library Only") -> "Library Only"
                        names.size > 1 -> "${names.size} Playlists (${names.joinToString(", ")})"
                        names.size == 1 -> names.first()
                        else -> "Library Only"
                    }
                    onStageDestination(selectedPlaylistId, selectedPlaylistName, ids, names)
                },
                onDismiss = { showPlaylistDialog = false }
            )
        }

        if (showEditArtworkDialog) {
            EditArtworkDialog(
                initialUrl = previewOpt.artworkUrl ?: "",
                onPickFromGallery = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onApply = { newUrl ->
                    previewOpt = previewOpt.copy(artworkUrl = newUrl)
                    showEditArtworkDialog = false
                },
                onDismiss = { showEditArtworkDialog = false }
            )
        }

        if (showEditMetadataDialog) {
            EditMetadataDialog(
                initialOption = previewOpt,
                onSave = { updated ->
                    previewOpt = updated
                    showEditMetadataDialog = false
                },
                onSaveAndFetch = { updated ->
                    previewOpt = updated
                    showEditMetadataDialog = false
                    onReSearch?.invoke(updated.trackTitle, updated.artist)
                },
                onDismiss = { showEditMetadataDialog = false }
            )
        }
    }
}

@Composable
fun MetaTagChip(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1
        )
    }
}

/**
 * Offers the confirmed album track beside the tags.
 *
 * Deliberately interactive, unlike the descriptive chips next to it: this one does something, so
 * it carries a click role and switches between "switch to…" and a settled state. It stages a
 * preference rather than committing — the primary button still owns that.
 */
@Composable
fun SwitchToAlbumChip(durationMs: Long?, active: Boolean, onClick: () -> Unit) {
    var isPulsing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(3000L)
        isPulsing = false
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val accent = if (active) Color(0xFF1DB954) else Color(0xFF29B6F6)
    val borderAlpha = if (!active && isPulsing) pulseAlpha else if (active) 0.85f else 0.65f
    val bgAlpha = if (!active && isPulsing) (0.15f + (pulseAlpha - 0.45f) * 0.15f) else if (active) 0.22f else 0.14f
    val scale = if (!active && isPulsing) pulseScale else 1.0f

    val formattedDuration = if (durationMs != null && durationMs > 0L) {
        val total = durationMs / 1000
        "${total / 60}:${"%02d".format(total % 60)}"
    } else null

    val label = if (active) {
        if (formattedDuration != null) "USING ALBUM AUDIO · $formattedDuration" else "USING ALBUM AUDIO"
    } else {
        if (formattedDuration != null) "SWITCH TO ALBUM AUDIO · $formattedDuration" else "SWITCH TO ALBUM AUDIO"
    }

    Row(
        Modifier
            .scale(scale)
            .clip(RoundedCornerShape(4.dp))
            .background(accent.copy(alpha = bgAlpha))
            .border(1.dp, accent.copy(alpha = borderAlpha), RoundedCornerShape(4.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = if (active) "Switch to video audio" else "Switch to album audio",
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (active) Icons.Filled.Check else Icons.Filled.Refresh,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(11.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1
        )
        Spacer(Modifier.width(3.dp))
        Text(
            if (active) "✕" else "›",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = accent.copy(alpha = 0.8f),
            maxLines = 1
        )
    }
}

@Composable
fun EditArtworkDialog(
    initialUrl: String,
    onPickFromGallery: () -> Unit,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf(initialUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color(0xFF29B6F6),
        shape = RoundedCornerShape(12.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = Color(0xFF29B6F6), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "CUSTOM COVER ARTWORK",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF29B6F6),
                    letterSpacing = 1.sp
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPickFromGallery,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6)),
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "CHOOSE FROM DEVICE PHOTOS",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(Modifier.weight(1f), color = Color(0x22FFFFFF))
                    Text(
                        "  OR ENTER IMAGE URL  ",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF777777)
                    )
                    HorizontalDivider(Modifier.weight(1f), color = Color(0x22FFFFFF))
                }

                Box(
                    Modifier
                        .size(76.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF121212))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (urlText.isNotBlank()) {
                        coil.compose.SubcomposeAsyncImage(
                            model = urlText,
                            contentDescription = "Artwork Preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            loading = {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        color = Color(0xFF29B6F6),
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            },
                            error = {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Close, contentDescription = "Load failed", tint = Color(0xFFE91429))
                                }
                            }
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF444444))
                    }
                }

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Image link", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF29B6F6),
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedLabelColor = Color(0xFF29B6F6),
                        unfocusedLabelColor = Color(0xFF888888),
                        cursorColor = Color(0xFF29B6F6),
                        focusedTextColor = Color(0xFFEEEEEE),
                        unfocusedTextColor = Color(0xFFEEEEEE)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(urlText.trim()) },
                enabled = urlText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("APPLY URL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF888888))
            }
        }
    )
}

@Composable
fun EditMetadataDialog(
    initialOption: ReleaseOption,
    onSave: (ReleaseOption) -> Unit,
    onSaveAndFetch: ((ReleaseOption) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initialOption.trackTitle) }
    var artist by remember { mutableStateOf(initialOption.artist) }
    var album by remember { mutableStateOf(initialOption.album) }
    var year by remember { mutableStateOf(initialOption.year ?: "") }
    var genre by remember { mutableStateOf(initialOption.genre ?: "") }
    var trackNum by remember { mutableStateOf(initialOption.trackNumber?.toString() ?: "") }
    var trackTotal by remember { mutableStateOf(initialOption.trackCount?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color(0xFF1DB954),
        shape = RoundedCornerShape(12.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Edit, contentDescription = null, tint = Color(0xFF1DB954), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "EDIT TAGS",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1DB954),
                    letterSpacing = 1.sp
                )
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Track Title", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1DB954),
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedLabelColor = Color(0xFF1DB954),
                        unfocusedLabelColor = Color(0xFF888888),
                        cursorColor = Color(0xFF1DB954),
                        focusedTextColor = Color(0xFFEEEEEE),
                        unfocusedTextColor = Color(0xFFEEEEEE)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1DB954),
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedLabelColor = Color(0xFF1DB954),
                        unfocusedLabelColor = Color(0xFF888888),
                        cursorColor = Color(0xFF1DB954),
                        focusedTextColor = Color(0xFFEEEEEE),
                        unfocusedTextColor = Color(0xFFEEEEEE)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1DB954),
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedLabelColor = Color(0xFF1DB954),
                        unfocusedLabelColor = Color(0xFF888888),
                        cursorColor = Color(0xFF1DB954),
                        focusedTextColor = Color(0xFFEEEEEE),
                        unfocusedTextColor = Color(0xFFEEEEEE)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it },
                        label = { Text("Year", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1DB954),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedLabelColor = Color(0xFF1DB954),
                            unfocusedLabelColor = Color(0xFF888888),
                            cursorColor = Color(0xFF1DB954),
                            focusedTextColor = Color(0xFFEEEEEE),
                            unfocusedTextColor = Color(0xFFEEEEEE)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = genre,
                        onValueChange = { genre = it },
                        label = { Text("Genre", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1DB954),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedLabelColor = Color(0xFF1DB954),
                            unfocusedLabelColor = Color(0xFF888888),
                            cursorColor = Color(0xFF1DB954),
                            focusedTextColor = Color(0xFFEEEEEE),
                            unfocusedTextColor = Color(0xFFEEEEEE)
                        ),
                        modifier = Modifier.weight(1.5f)
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = trackNum,
                        onValueChange = { trackNum = it },
                        label = { Text("Track #", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1DB954),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedLabelColor = Color(0xFF1DB954),
                            unfocusedLabelColor = Color(0xFF888888),
                            cursorColor = Color(0xFF1DB954),
                            focusedTextColor = Color(0xFFEEEEEE),
                            unfocusedTextColor = Color(0xFFEEEEEE)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = trackTotal,
                        onValueChange = { trackTotal = it },
                        label = { Text("Total Tracks", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1DB954),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedLabelColor = Color(0xFF1DB954),
                            unfocusedLabelColor = Color(0xFF888888),
                            cursorColor = Color(0xFF1DB954),
                            focusedTextColor = Color(0xFFEEEEEE),
                            unfocusedTextColor = Color(0xFFEEEEEE)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        onSave(
                            initialOption.copy(
                                trackTitle = title.trim(),
                                artist = artist.trim(),
                                album = album.trim(),
                                year = year.trim().ifEmpty { null },
                                genre = genre.trim().ifEmpty { null },
                                trackNumber = trackNum.trim().toIntOrNull(),
                                trackCount = trackTotal.trim().toIntOrNull(),
                                sources = if (initialOption.sources.contains("custom")) initialOption.sources else initialOption.sources + "custom"
                            )
                        )
                    },
                    enabled = title.isNotBlank() && artist.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("SAVE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                }

                if (onSaveAndFetch != null) {
                    Button(
                        onClick = {
                            onSaveAndFetch(
                                initialOption.copy(
                                    trackTitle = title.trim(),
                                    artist = artist.trim(),
                                    album = album.trim(),
                                    year = year.trim().ifEmpty { null },
                                    genre = genre.trim().ifEmpty { null },
                                    trackNumber = trackNum.trim().toIntOrNull(),
                                    trackCount = trackTotal.trim().toIntOrNull(),
                                    sources = if (initialOption.sources.contains("custom")) initialOption.sources else initialOption.sources + "custom"
                                )
                            )
                        },
                        enabled = title.isNotBlank() && artist.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("FETCH ART & TAGS", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF888888))
            }
        }
    )
}
