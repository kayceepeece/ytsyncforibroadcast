package ibytsync.android

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ibytsync.android.ui.*
import ibytsync.android.ui.theme.IbytsyncColorScheme
import ibytsync.core.download.YtDlpAndroidImpl
import ibytsync.core.metadata.DeezerCorrection
import ibytsync.core.metadata.ITunesCorrection
import ibytsync.core.metadata.LocalTagsReader
import ibytsync.core.metadata.SpotifyCorrection
import ibytsync.core.metadata.SpotifyPageFetcher
import ibytsync.core.metadata.SpotifyScraper
import ibytsync.core.pipeline.*
import ibytsync.core.settings.SettingsStore
import ibytsync.core.storage.DiskStore
import ibytsync.core.storage.FolderStore
import ibytsync.core.upload.IBroadcastOAuth
import ibytsync.core.upload.Playlist
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private lateinit var settingsStore: SettingsStore
    private lateinit var ytEngine: YtDlpAndroidImpl
    private lateinit var pipelineHooks: AndroidPipelineHooks
    private lateinit var metadataResolver: MetadataResolver
    private val viewModel: BatchViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BatchViewModel(
                    metadataResolver = metadataResolver,
                    initialRows = DiskStore.loadQueue(this@MainActivity),
                    libraryTracksProvider = { pipelineHooks.libraryTracks() },
                    downloaderProvider = { ytEngine }
                ) as T
            }
        }
    }
    private var activeBatchService: BatchService? = null
    private var batchServiceConnection: ServiceConnection? = null

    private var triggerBatteryPrompt by mutableStateOf(false)

    private fun checkBatteryPrompt() {
        if (::settingsStore.isInitialized && !settingsStore.hasSeenBatteryPrompt() && !settingsStore.isIgnoringBatteryOptimizations(this)) {
            triggerBatteryPrompt = true
        }
    }

    private val loginViewModel: LoginViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LoginViewModel(
                    context = applicationContext,
                    settings = settingsStore,
                    hooks = pipelineHooks,
                    onAccountSwitch = {
                        viewModel.clearAll()
                        DiskStore.clearQueue(applicationContext)
                    },
                    onLibraryUpdated = { viewModel.refreshDuplicateMatches() }
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageLoader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.10)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(25L * 1024 * 1024)
                    .build()
            }
            .okHttpClient { ibytsync.core.network.SharedHttpClient.instance }
            .crossfade(true)
            .build()
        Coil.setImageLoader(imageLoader)

        settingsStore = SettingsStore(this)
        ytEngine = YtDlpAndroidImpl(this)
        pipelineHooks = AndroidPipelineHooks(this, ytEngine, settingsStore)
        val scraper = SpotifyScraper(SpotifyScraper.okHttpFetcher())
        val spotify = SpotifyCorrection()
        val itunes = ITunesCorrection(ITunesCorrection.okHttpFetcher())
        val deezer = DeezerCorrection(DeezerCorrection.okHttpFetcher())
        val httpScraper = ibytsync.core.metadata.YouTubeHttpScraper()
        metadataResolver = MetadataResolver(
            scraper,
            ytEngine,
            listOf(spotify, itunes, deezer),
            httpScraper,
            ibytsync.core.metadata.YouTubeMusicSearch()
        )

        // Initialize yt-dlp, refresh token silently, and fetch library cache in background
        lifecycleScope.launch(Dispatchers.IO) {
            val downloaderOk = try {
                ytEngine.init()
            } catch (_: Exception) {
                false
            }
            viewModel.setDownloaderReady(downloaderOk)
            if (!downloaderOk) {
                messages.tryEmit("Couldn't start downloader — tap to retry")
            }
            try {
                settingsStore.ensureFreshToken()
                if (settingsStore.isLoggedIn()) {
                    Log.i("MainActivity", "User is logged in. Loading cached library snapshot...")
                    val cached = DiskStore.loadLibrary(this@MainActivity)
                    if (cached != null) {
                        pipelineHooks.updateLibraryCache(cached)
                        viewModel.refreshDuplicateMatches()
                    }
                    val token = settingsStore.getAccessToken()
                    val status = IBroadcastOAuth.fetchStatus(token)
                    if (status != null) settingsStore.setAccountId(status.accountId)
                    val unchanged = status?.lastModified != null &&
                        status.lastModified == cached?.lastModified
                    if (unchanged) {
                        Log.i("MainActivity", "Library unchanged since last fetch; skipping.")
                    } else {
                        Log.i("MainActivity", "Fetching iBroadcast library snapshot...")
                        val snapshot = IBroadcastOAuth.fetchLibrary(token)
                        if (snapshot != null) {
                            Log.i("MainActivity", "Fetched library snapshot: ${snapshot.playlists.size} playlists, ${snapshot.tracks.size} tracks")
                            pipelineHooks.updateLibraryCache(snapshot)
                            DiskStore.saveLibrary(this@MainActivity, snapshot)
                            viewModel.refreshDuplicateMatches()
                        } else {
                            Log.w("MainActivity", "Library snapshot returned null")
                            messages.tryEmit("Couldn't refresh your library")
                        }
                    }
                } else {
                    Log.i("MainActivity", "User is not logged in to iBroadcast.")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed fetching library cache: ${e.message}", e)
                messages.tryEmit("Couldn't refresh your library")
            }
        }

        handleIntent(intent)
        registerTestReceiver()

        setContent {
            MaterialTheme(colorScheme = IbytsyncColorScheme) {
                MainAppScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainAppScreen() {
        val context = LocalContext.current
        val haptics = LocalHapticFeedback.current
        val coroutineScope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            messages.collect { snackbarHostState.showSnackbar(it) }
        }

        var activeTab by remember { mutableStateOf(NavTab.QUEUE) }
        val queueRows by viewModel.rows.collectAsState()
        val syncedState by viewModel.syncedRows.collectAsState()
        val syncedRows = remember { mutableStateListOf<QueueRow>() }
        LaunchedEffect(syncedState) {
            syncedRows.clear()
            syncedRows.addAll(syncedState)
        }
        var selectedInspectorRow by remember { mutableStateOf<QueueRow?>(null) }
        val isBatchRunning by viewModel.isBatchRunning.collectAsState()
        val isDownloaderReady by viewModel.isDownloaderReady.collectAsState()
        val retryDownloader: () -> Unit = {
            coroutineScope.launch(Dispatchers.IO) {
                val ok = try {
                    ytEngine.init()
                } catch (_: Exception) {
                    false
                }
                viewModel.setDownloaderReady(ok)
                if (!ok) {
                    messages.tryEmit("Couldn't start downloader — tap to retry")
                }
            }
        }

        val availablePlaylists by pipelineHooks.playlistsFlow.collectAsState()

        val handleCreatePlaylist: suspend (String) -> Playlist? = { newName ->
            val pl = withContext(Dispatchers.IO) {
                try {
                    IBroadcastOAuth.createPlaylist(
                        accessToken = settingsStore.getAccessToken(),
                        name = newName
                    )
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed creating playlist '$newName': ${e.message}", e)
                    null
                }
            }
            if (pl != null) {
                pipelineHooks.addPlaylistToCache(pl)
            } else {
                messages.tryEmit("Couldn't create that playlist — check your connection")
            }
            pl
        }

        // Batch Destination modal state (picks survive rotation via ViewModel)
        var showBatchDestinationSheet by remember { mutableStateOf(false) }
        val batchSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val batchDestination by viewModel.batchDestination.collectAsState()
        val currentBatchPlaylistId = batchDestination.playlistId ?: settingsStore.getDefaultPlaylistId()
        val currentBatchPlaylistName = batchDestination.playlistName ?: settingsStore.getDefaultPlaylistName()
        val currentBatchFormat = batchDestination.format ?: settingsStore.getDefaultAudioFormat()
        val currentBatchSkipDups = batchDestination.skipDuplicates ?: settingsStore.isSkipDuplicates()
        var inspectedSyncedTrack by remember { mutableStateOf<QueueRow?>(null) }
        var syncedSortOrder by remember {
            mutableStateOf(SyncedSortOrder.fromCode(settingsStore.getSyncedSortOrder()))
        }

        LaunchedEffect(Unit) {
            val loadedSynced = withContext(Dispatchers.IO) { DiskStore.loadSynced(context) }
            viewModel.setSyncedRows(loadedSynced)
        }

        // Persist queue rows whenever they change (debounced, progress-only writes skipped)
        var lastQueueSaveMs by remember { mutableStateOf(0L) }
        var lastSavedQueue by remember { mutableStateOf<List<QueueRow>>(emptyList()) }
        LaunchedEffect(queueRows) {
            val now = System.currentTimeMillis()
            val stripProgress: (QueueRow) -> QueueRow = { r ->
                if (r.status == RowStatus.DOWNLOADING || r.status == RowStatus.UPLOADING ||
                    r.status == RowStatus.RETUNING || r.status == RowStatus.SAVING
                ) r.copy(progress = null) else r
            }
            val stripped = queueRows.map(stripProgress)
            val lastStripped = lastSavedQueue.map(stripProgress)
            val structuralChange = stripped.map { it.copy(progress = null) } !=
                lastStripped.map { it.copy(progress = null) }
            if (structuralChange || now - lastQueueSaveMs >= 750L) {
                lastQueueSaveMs = now
                lastSavedQueue = queueRows
                withContext(Dispatchers.IO) {
                    DiskStore.saveQueue(context, queueRows)
                }
            }
        }

        // Keep BatchService running in foreground during metadata resolution to prevent CPU sleep & App Freezer
        LaunchedEffect(queueRows) {
            val hasFetching = queueRows.any { it.status == RowStatus.FETCHING_METADATA }
            if (hasFetching && !viewModel.isBatchRunning.value) {
                try {
                    val serviceIntent = Intent(this@MainActivity, BatchService::class.java).apply {
                        action = BatchService.ACTION_FETCH_TAGS
                    }
                    startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed starting BatchService for tags: ${e.message}")
                }
            } else if (!hasFetching && !viewModel.isBatchRunning.value && activeBatchService?.isRunning() != true) {
                try {
                    val stopIntent = Intent(this@MainActivity, BatchService::class.java).apply {
                        action = BatchService.ACTION_FINISH_FETCH_TAGS
                    }
                    startService(stopIntent)
                } catch (_: Exception) {}
            }
        }

        // Re-bind to a running BatchService after rotation; adopt live rows + running state
        DisposableEffect(Unit) {
            val probe = Intent(this@MainActivity, BatchService::class.java)
            lateinit var probeConn: ServiceConnection
            probeConn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val batchService = (service as? BatchService.LocalBinder)?.service() ?: return
                    if (batchService.isRunning()) {
                        activeBatchService = batchService
                        if (batchServiceConnection == null) batchServiceConnection = probeConn
                        viewModel.setBatchRunning(true)
                        viewModel.upsertRows(batchService.currentRows())
                    } else {
                        try { unbindService(this) } catch (_: Exception) {}
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (activeBatchService != null && batchServiceConnection == probeConn) {
                        activeBatchService = null
                        batchServiceConnection = null
                    }
                }
            }
            var bound = false
            try {
                bound = bindService(probe, probeConn, Context.BIND_AUTO_CREATE)
            } catch (_: Exception) {}
            onDispose {
                if (bound && batchServiceConnection == probeConn && !viewModel.isBatchRunning.value) {
                    try { unbindService(probeConn) } catch (_: Exception) {}
                    batchServiceConnection = null
                }
            }
        }

        // OAuth Login — state lives in a ViewModel so rotation can't kill the poll
        val login = loginViewModel
        val showLoginDialog by login.isDialogOpen.collectAsState()
        val deviceCodeInfo by login.deviceCode.collectAsState()
        val loginStatusMsg by login.statusMessage.collectAsState()
        val loginSecondsLeft by login.secondsLeft.collectAsState()
        val loginWarning by login.warning.collectAsState()
        LaunchedEffect(loginWarning) {
            loginWarning?.let {
                messages.tryEmit(it)
                login.consumeWarning()
            }
        }
        val pendingPlaylistPrompt by viewModel.pendingPlaylistPrompt.collectAsState()

        val searchResults by viewModel.searchResults.collectAsState()
        val isSearching by viewModel.isSearching.collectAsState()
        val suggestions by viewModel.suggestions.collectAsState()
        var showSearchSheet by remember { mutableStateOf(false) }
        var activeSearchQuery by remember { mutableStateOf("") }

        val audioPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                checkBatteryPrompt()
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val tempFile = File.createTempFile("picked_audio_", ".mp3", cacheDir)
                        contentResolver.openInputStream(uri)?.use { ins ->
                            tempFile.outputStream().use { out -> ins.copyTo(out) }
                        }
                        val localMeta = LocalTagsReader.read(tempFile)
                        viewModel.addLocalFile(
                            sourcePathOrUri = uri.toString(),
                            title = localMeta.title,
                            artist = localMeta.artist,
                            album = localMeta.album,
                            durationMs = localMeta.durationMs
                        )
                        tempFile.delete()
                    } catch (e: Exception) {
                        viewModel.addInput(uri.toString())
                    }
                }
            }
        }

        val folderPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            if (uri != null) {
                FolderStore.saveTree(this@MainActivity, uri)
            }
        }

        // Gate: All rows must be METADATA_READY and queue must be non-empty.
        // Also block when a local copy is requested but no folder was ever chosen —
        // otherwise every row fails with a bare FAILED and no explanation.
        val noSaveFolder = settingsStore.isLocalSave() &&
            FolderStore.savedTree(this@MainActivity) == null
        val canStartBatch = queueRows.isNotEmpty() &&
            queueRows.all { it.status == RowStatus.METADATA_READY } &&
            !noSaveFolder &&
            !isBatchRunning &&
            isDownloaderReady
        val pendingIssuesCount = queueRows.count { it.status != RowStatus.METADATA_READY }

        fun startBatchExecution() {
            if (viewModel.isBatchRunning.value || activeBatchService?.isRunning() == true || !isDownloaderReady) return
            checkBatteryPrompt()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    if (settingsStore.isLoggedIn()) {
                        val snapshot = IBroadcastOAuth.fetchLibrary(settingsStore.getAccessToken())
                        if (snapshot != null) {
                            pipelineHooks.updateLibraryCache(snapshot)
                            DiskStore.saveLibrary(this@MainActivity, snapshot)
                        }
                    }
                } catch (_: Exception) {}
                viewModel.refreshDuplicateMatches()
            }
            viewModel.setBatchRunning(true)
            val intent = Intent(this@MainActivity, BatchService::class.java)
            startForegroundService(intent)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as? BatchService.LocalBinder ?: return
                    val batchService = binder.service()
                    activeBatchService = batchService
                    val currentBatchId = "batch_" + System.currentTimeMillis()
                    batchService.startBatch(
                        initial = viewModel.rows.value,
                        cfg = BatchConfig(
                            saveToDisk = settingsStore.isLocalSave(),
                            routePreference = if (currentBatchPlaylistId != null) ibytsync.core.upload.RoutePreference.Favorite(currentBatchPlaylistId!!, currentBatchPlaylistName ?: "") else ibytsync.core.upload.RoutePreference.LibraryOnly,
                            batchId = currentBatchId,
                            skipDuplicates = currentBatchSkipDups
                        ),
                        hooks = pipelineHooks
                    ) { event ->
                        when (event) {
                            is BatchEvent.RowUpdate -> {
                                viewModel.updateRow(event.row)
                            }
                            is BatchEvent.Progress -> {
                                // Handled via BatchEvent.RowUpdate for individual cards
                            }
                            is BatchEvent.Finished -> {
                                viewModel.setBatchRunning(false)
                                val finished = batchService.currentRows().filter {
                                    it.status == RowStatus.DONE || it.status == RowStatus.ALREADY_UPLOADED
                                }
                                val replacedIds = finished.mapNotNull { it.replacesUploadedTrackId }.toSet()
                                if (replacedIds.isNotEmpty()) {
                                    viewModel.replaceSyncedRows { current ->
                                        current.filterNot { it.ibroadcastTrackId in replacedIds || it.id in replacedIds }
                                    }
                                }
                                val ordered = finished.reversed().map { row ->
                                    if (row.status == RowStatus.DONE && !row.replacesUploadedTrackId.isNullOrEmpty()) {
                                        row.copy(replacesUploadedTrackId = null)
                                    } else row
                                }
                                viewModel.addSyncedRows(ordered)
                                DiskStore.saveSynced(this@MainActivity, viewModel.syncedRows.value)
                                // One summary line per batch — never one per failed row.
                                val failedCount = batchService.currentRows().count {
                                    it.status == RowStatus.FAILED_UPLOAD || it.status == RowStatus.FAILED_DOWNLOAD ||
                                        it.status == RowStatus.FAILED_SAVE || it.status == RowStatus.FAILED_METADATA
                                }
                                if (failedCount > 0) {
                                    messages.tryEmit(
                                        if (failedCount == 1) "1 track failed — open it to see why"
                                        else "$failedCount tracks failed — open one to see why"
                                    )
                                }
                                viewModel.clearFinished()
                                try { unbindService(this) } catch (_: Exception) {}
                            }
                            is BatchEvent.Cancelled -> {
                                viewModel.setBatchRunning(false)
                                viewModel.upsertRows(event.completedRows)
                                viewModel.clearFinished()
                                try { unbindService(this) } catch (_: Exception) {}
                            }
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    activeBatchService = null
                    viewModel.setBatchRunning(false)
                }
            }
            batchServiceConnection = conn
            bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }

        fun cancelBatchExecution() {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            activeBatchService?.cancelBatch()
            viewModel.setBatchRunning(false)
            batchServiceConnection?.let {
                try { unbindService(it) } catch (_: Exception) {}
                batchServiceConnection = null
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Column(Modifier.fillMaxSize()) {
                TopConsoleBar(
                    activeTab = activeTab,
                    queueCount = queueRows.size,
                    syncedCount = syncedRows.size,
                    onClearSynced = {
                        viewModel.setSyncedRows(emptyList())
                        DiskStore.clearSynced(this@MainActivity)
                    }
                )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (activeTab) {
                        NavTab.QUEUE -> QueueTabContent(
                            rows = queueRows,
                            isBatchRunning = isBatchRunning,
                            onAddInput = { input ->
                                checkBatteryPrompt()
                                viewModel.addInput(input)
                            },
                            onPickLocalFile = {
                                checkBatteryPrompt()
                                audioPickerLauncher.launch("audio/*")
                            },
                            onOpenInspector = { row -> selectedInspectorRow = row },
                            onRetry = { row -> viewModel.retryRow(row.id) },
                            onRemove = { row ->
                                val replacedId = viewModel.removeRow(row.id)
                                if (replacedId != null) {
                                    viewModel.replaceSyncedRows { current ->
                                        current.map {
                                            if (it.ibroadcastTrackId == replacedId || it.id == replacedId) it.copy(isEditingInStudio = false) else it
                                        }
                                    }
                                    DiskStore.saveSynced(this@MainActivity, viewModel.syncedRows.value)
                                }
                            },
                            onAcceptOption = { row -> viewModel.acceptCurrentOption(row.id) },
                            onTriggerSearch = { query ->
                                activeSearchQuery = query.trim()
                                if (activeSearchQuery.isNotEmpty()) {
                                    viewModel.searchYouTube(activeSearchQuery)
                                }
                                showSearchSheet = true
                            },
                            suggestions = suggestions,
                            onQueryChanged = { viewModel.updateSearchQuery(it) }
                        )
                        NavTab.SYNCED -> SyncedTabContent(
                            syncedList = syncedRows,
                            sortOrder = syncedSortOrder,
                            onSortOrderChange = { order ->
                                syncedSortOrder = order
                                settingsStore.setSyncedSortOrder(if (order == SyncedSortOrder.OLDEST_FIRST) "oldest" else "newest")
                            },
                            onTrackClick = { inspectedSyncedTrack = it }
                        )
                        NavTab.SETTINGS -> SettingsTabContent(
                            settings = settingsStore,
                            queuedCount = queueRows.size,
                            onPickFolderSaf = { folderPickerLauncher.launch(null) },
                            onDisconnect = {
                                viewModel.clearAll()
                                DiskStore.clearQueue(this@MainActivity)
                                DiskStore.clearLibrary(this@MainActivity)
                                settingsStore.clearAccountId()
                                pipelineHooks.updateLibraryCache(null)
                            },
                            onStartLogin = {
                                login.start()
                            },
                            onDefaultFormatChange = { fmt ->
                                if (batchDestination.format != null) {
                                    viewModel.setBatchDestination(
                                        batchDestination.playlistId,
                                        batchDestination.playlistName,
                                        fmt,
                                        batchDestination.skipDuplicates ?: settingsStore.isSkipDuplicates(),
                                        batchDestination.playlistIds,
                                        batchDestination.playlistNames
                                    )
                                }
                            }
                        )
                    }
                }

                if (activeTab == NavTab.QUEUE) {
                    MasterActionConsole(
                        canStart = canStartBatch,
                        isRunning = isBatchRunning,
                        isDownloaderReady = isDownloaderReady,
                        onRetryDownloader = retryDownloader,
                        issuesCount = pendingIssuesCount,
                        queueCount = queueRows.size,
                        needsSaveFolder = noSaveFolder,
                        destinationName = currentBatchPlaylistName ?: "Library Only",
                        onOpenBatchSettings = { showBatchDestinationSheet = true },
                        onStartBatch = { startBatchExecution() },
                        onCancelBatch = { cancelBatchExecution() }
                    )
                }

                BottomNavigationBar(
                    currentTab = activeTab,
                    queueBadge = queueRows.size,
                    onSelectTab = { activeTab = it }
                )
            }

            selectedInspectorRow?.let { row ->
                val currentRow = queueRows.firstOrNull { it.id == row.id } ?: row
                ReleasePickerSheet(
                    row = currentRow,
                    batchDestinationName = currentBatchPlaylistName ?: "Library Only",
                    batchDestinationIds = batchDestination.playlistIds.ifEmpty { listOfNotNull(currentBatchPlaylistId) },
                    batchDestinationNames = batchDestination.playlistNames.ifEmpty { listOfNotNull(currentBatchPlaylistName) },
                    availablePlaylists = availablePlaylists,
                    defaultAudioFormat = currentBatchFormat,
                    onReSearch = { title, artist ->
                        viewModel.reSearchMetadata(currentRow.id, title, artist)
                    },
                    onCreatePlaylist = handleCreatePlaylist,
                    onSelectOption = { selectedOption ->
                        // Preferences are already persisted as they change, so only the match is
                        // committed here.
                        viewModel.commitMatch(rowId = currentRow.id, option = selectedOption)
                        selectedInspectorRow = null
                    },
                    onStageAudio = { audioPref, format, forceUp ->
                        viewModel.stageAudioChoice(
                            rowId = currentRow.id,
                            audioPref = audioPref,
                            audioFormat = format,
                            forceUpload = forceUp
                        )
                    },
                    onStageDestination = { plId, plName, plIds, plNames ->
                        viewModel.stageDestination(currentRow.id, plId, plName, plIds, plNames)
                    },
                    onDismiss = { selectedInspectorRow = null }
                )
            }

            inspectedSyncedTrack?.let { track ->
                SyncedTrackInspectorSheet(
                    track = track,
                    onDismiss = { inspectedSyncedTrack = null },
                    onEditAndReSync = { reSyncTrack ->
                        val stagedRow = viewModel.startReSync(reSyncTrack)
                        viewModel.replaceSyncedRows { current ->
                            current.map {
                                if (it.id == reSyncTrack.id) it.copy(isEditingInStudio = true) else it
                            }
                        }
                        DiskStore.saveSynced(this@MainActivity, viewModel.syncedRows.value)
                        activeTab = NavTab.QUEUE
                        selectedInspectorRow = stagedRow
                    }
                )
            }

            if (showSearchSheet) {
                YouTubeSearchSheet(
                    initialQuery = activeSearchQuery,
                    candidates = searchResults,
                    isLoading = isSearching,
                    onSearch = { newQuery ->
                        activeSearchQuery = newQuery
                        viewModel.searchYouTube(newQuery)
                    },
                    onAddCandidate = { candidate ->
                        checkBatteryPrompt()
                        viewModel.addSearchCandidate(candidate)
                    },
                    onDismiss = {
                        showSearchSheet = false
                        viewModel.clearSearchResults()
                    }
                )
            }

            if (showBatchDestinationSheet) {
                BatchDestinationSheet(
                    sheetState = batchSheetState,
                    initialPlaylistId = currentBatchPlaylistId,
                    initialPlaylistName = currentBatchPlaylistName,
                    initialPlaylistIds = batchDestination.playlistIds,
                    initialPlaylistNames = batchDestination.playlistNames,
                    playlists = availablePlaylists,
                    defaultFormat = currentBatchFormat,
                    skipDuplicates = currentBatchSkipDups,
                    trackCount = queueRows.size,
                    onDismiss = { showBatchDestinationSheet = false },
                    onCreatePlaylist = handleCreatePlaylist,
                    onConfirm = { selectedId, selectedName, format, skipDups, setAsDefault ->
                        viewModel.setBatchDestination(selectedId, selectedName, format, skipDups)
                        if (setAsDefault) {
                            settingsStore.setDefaultPlaylist(selectedId, selectedName)
                            settingsStore.setDefaultAudioFormat(format)
                            settingsStore.setSkipDuplicates(skipDups)
                        }
                        viewModel.applyBatchSettings(selectedId, selectedName, format)
                        showBatchDestinationSheet = false
                    },
                    onConfirmMultiple = { selectedId, selectedName, selectedIds, selectedNames, format, skipDups, setAsDefault ->
                        viewModel.setBatchDestination(selectedId, selectedName, format, skipDups, selectedIds, selectedNames)
                        if (setAsDefault) {
                            settingsStore.setDefaultPlaylist(selectedId, selectedName)
                            settingsStore.setDefaultAudioFormat(format)
                            settingsStore.setSkipDuplicates(skipDups)
                        }
                        viewModel.applyBatchSettings(selectedId, selectedName, format, selectedIds, selectedNames)
                        showBatchDestinationSheet = false
                    }
                )
            }

            if (showLoginDialog) {
                AlertDialog(
                    onDismissRequest = { login.dismiss() },
                    containerColor = Color(0xFF1E1E1E),
                    title = {
                        Text(
                            "IBROADCAST LOGIN",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1DB954)
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                loginStatusMsg,
                                fontSize = 12.sp,
                                color = Color(0xFFEEEEEE)
                            )
                            deviceCodeInfo?.let { dc ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SelectionContainer(Modifier.weight(1f)) {
                                        Text(
                                            dc.userCode,
                                            fontSize = 20.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF29B6F6)
                                        )
                                    }
                                    val clipboard = LocalClipboardManager.current
                                    OutlinedButton(
                                        onClick = { clipboard.setText(AnnotatedString(dc.userCode)) },
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x6629B6F6)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("COPY", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF29B6F6))
                                    }
                                }
                                Button(
                                    onClick = {
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUriComplete))
                                        startActivity(browserIntent)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6)),
                                    modifier = Modifier.fillMaxWidth().height(38.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("OPEN BROWSER TO APPROVE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                                loginSecondsLeft?.let { left ->
                                    Text(
                                        "Waiting for you to approve in the browser… ${left / 60}:${(left % 60).toString().padStart(2, '0')} left",
                                        fontSize = 11.sp,
                                        color = Color(0xFFAAAAAA)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { login.dismiss() }) {
                            Text("CLOSE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF888888))
                        }
                    }
                )
            }

            if (pendingPlaylistPrompt != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissPlaylistPrompt() },
                    containerColor = Color(0xFF1E1E1E),
                    title = {
                        Text(
                            "YOUTUBE PLAYLIST DETECTED",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1DB954)
                        )
                    },
                    text = {
                        Text(
                            "This link contains both a single video and a playlist. Would you like to import only this single video or the entire playlist?",
                            fontSize = 12.sp,
                            color = Color(0xFFEEEEEE)
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                checkBatteryPrompt()
                                viewModel.acceptPlaylistPrompt(importEntirePlaylist = true)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("ENTIRE PLAYLIST", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = {
                                checkBatteryPrompt()
                                viewModel.acceptPlaylistPrompt(importEntirePlaylist = false)
                            },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("SINGLE VIDEO", fontSize = 11.sp, color = Color(0xFFCCCCCC))
                        }
                    }
                )
            }

            if (triggerBatteryPrompt) {
                AlertDialog(
                    onDismissRequest = {
                        settingsStore.setHasSeenBatteryPrompt(true)
                        triggerBatteryPrompt = false
                    },
                    containerColor = Color(0xFF1E1E1E),
                    title = {
                        Text(
                            "ENABLE BACKGROUND WORK",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1DB954)
                        )
                    },
                    text = {
                        Text(
                            "YT Sync downloads music and fetches tags in the background. To prevent Android from pausing downloads or tag matching when you switch apps or turn off your screen, allow unrestricted background execution.",
                            fontSize = 12.sp,
                            color = Color(0xFFEEEEEE)
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                settingsStore.setHasSeenBatteryPrompt(true)
                                triggerBatteryPrompt = false
                                settingsStore.requestIgnoreBatteryOptimizations(this@MainActivity)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("ALLOW UNRESTRICTED", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                settingsStore.setHasSeenBatteryPrompt(true)
                                triggerBatteryPrompt = false
                            }
                        ) {
                            Text("NOT NOW", fontSize = 11.sp, color = Color(0xFF888888))
                        }
                    }
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                checkBatteryPrompt()
                viewModel.addInput(text)
            }
        }
    }

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val url = intent?.getStringExtra("url")
            if (!url.isNullOrBlank()) {
                checkBatteryPrompt()
                viewModel.addInput(url)
            }
        }
    }

    private fun registerTestReceiver() {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val filter = IntentFilter("ibytsync.android.ADD_URL")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(testReceiver, filter)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(testReceiver) } catch (_: Exception) {}
        batchServiceConnection?.let {
            try { unbindService(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
