package ibytsync.core.pipeline

import ibytsync.core.metadata.ReleaseOption
import ibytsync.core.metadata.UrlClassifier
import ibytsync.core.metadata.UrlKind
import ibytsync.core.pipeline.RowStates.withManualArtwork
import ibytsync.core.pipeline.RowStates.withManualMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class BatchViewModel(
    private val metadataResolver: MetadataResolver? = null,
    scope: CoroutineScope? = null,
    initialRows: List<QueueRow> = emptyList(),
    private val libraryTracksProvider: (() -> Collection<ibytsync.core.upload.LibraryTrackInfo>)? = null,
    private val downloaderProvider: (() -> ibytsync.core.download.YtDlpEngine)? = null,
    private val spotifyEmbedScraper: ibytsync.core.metadata.SpotifyEmbedScraper = ibytsync.core.metadata.SpotifyEmbedScraper()
) : androidx.lifecycle.ViewModel() {
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _rows = MutableStateFlow<List<QueueRow>>(initialRows)
    val rows: StateFlow<List<QueueRow>> = _rows.asStateFlow()

    data class PlaylistPrompt(
        val videoUrl: String,
        val playlistUrl: String,
        val playlistId: String
    )

    private val _pendingPlaylistPrompt = MutableStateFlow<PlaylistPrompt?>(null)
    val pendingPlaylistPrompt: StateFlow<PlaylistPrompt?> = _pendingPlaylistPrompt.asStateFlow()

    fun dismissPlaylistPrompt() {
        _pendingPlaylistPrompt.value = null
    }

    fun acceptPlaylistPrompt(importEntirePlaylist: Boolean) {
        val prompt = _pendingPlaylistPrompt.value ?: return
        _pendingPlaylistPrompt.value = null
        if (importEntirePlaylist) {
            expandPlaylistUrl(prompt.playlistUrl)
        } else {
            addInputs(listOf(prompt.videoUrl))
        }
    }

    private val httpSearch = ibytsync.core.metadata.YouTubeHttpSearch()
    private val suggestService = ibytsync.core.metadata.YouTubeSuggestService()

    private val _searchResults = MutableStateFlow<List<ibytsync.core.matching.YtCandidate>>(emptyList())
    val searchResults: StateFlow<List<ibytsync.core.matching.YtCandidate>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _isBatchRunning = MutableStateFlow(false)
    val isBatchRunning: StateFlow<Boolean> = _isBatchRunning.asStateFlow()

    private val _syncedRows = MutableStateFlow<List<QueueRow>>(emptyList())
    val syncedRows: StateFlow<List<QueueRow>> = _syncedRows.asStateFlow()

    private val _isDownloaderReady = MutableStateFlow(true)
    val isDownloaderReady: StateFlow<Boolean> = _isDownloaderReady.asStateFlow()

    fun setDownloaderReady(ready: Boolean) {
        _isDownloaderReady.value = ready
    }

    data class BatchDestination(
        val playlistId: String? = null,
        val playlistName: String? = null,
        val playlistIds: List<String> = emptyList(),
        val playlistNames: List<String> = emptyList(),
        val format: AudioFormatChoice? = null,
        val skipDuplicates: Boolean? = null
    )

    private val _batchDestination = MutableStateFlow(BatchDestination())
    val batchDestination: StateFlow<BatchDestination> = _batchDestination.asStateFlow()

    fun setBatchDestination(
        playlistId: String?,
        playlistName: String?,
        format: AudioFormatChoice,
        skipDuplicates: Boolean,
        playlistIds: List<String> = emptyList(),
        playlistNames: List<String> = emptyList()
    ) {
        val pIds = if (playlistIds.isNotEmpty()) playlistIds else listOfNotNull(playlistId)
        val pNames = if (playlistNames.isNotEmpty()) playlistNames else listOfNotNull(playlistName)
        _batchDestination.value = BatchDestination(playlistId, playlistName, pIds, pNames, format, skipDuplicates)
    }

    fun setBatchRunning(running: Boolean) {
        _isBatchRunning.value = running
    }

    fun setSyncedRows(rows: List<QueueRow>) {
        val activeReplaces = _rows.value.mapNotNull { it.replacesUploadedTrackId }.toSet()
        _syncedRows.value = rows.map {
            if (it.isEditingInStudio && (it.ibroadcastTrackId !in activeReplaces && it.id !in activeReplaces)) {
                it.copy(isEditingInStudio = false)
            } else it
        }
    }

    fun reconcileEditingStatus() {
        val activeReplaces = _rows.value.mapNotNull { it.replacesUploadedTrackId }.toSet()
        _syncedRows.update { list ->
            list.map {
                if (it.isEditingInStudio && (it.ibroadcastTrackId !in activeReplaces && it.id !in activeReplaces)) {
                    it.copy(isEditingInStudio = false)
                } else it
            }
        }
    }

    fun addSyncedRows(rows: List<QueueRow>) {
        if (rows.isEmpty()) return
        _syncedRows.update { it + rows }
    }

    fun replaceSyncedRows(transform: (List<QueueRow>) -> List<QueueRow>) {
        _syncedRows.update(transform)
    }

    fun replaceRows(rows: List<QueueRow>) {
        _rows.value = rows
    }

    fun upsertRows(rows: List<QueueRow>) {
        if (rows.isEmpty()) return
        _rows.update { current ->
            val byId = rows.associateBy { it.id }
            val updated = current.map { byId[it.id] ?: it }
            val known = current.map { it.id }.toSet()
            updated + rows.filterNot { known.contains(it.id) }
        }
    }

    private var suggestJob: kotlinx.coroutines.Job? = null

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        val q = query.trim()
        suggestJob?.cancel()
        if (q.length >= 2) {
            suggestJob = scope.launch(Dispatchers.IO) {
                val list = suggestService.querySuggestions(q)
                _suggestions.value = list
            }
        } else {
            _suggestions.value = emptyList()
        }
    }

    fun clearSuggestions() {
        _suggestions.value = emptyList()
    }

    fun searchYouTube(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        _searchQuery.value = q
        _isSearching.value = true
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Try fast native HTTP Innertube search (<400ms)
                val fastResults = httpSearch.search(q, count = 10)
                if (fastResults.isNotEmpty()) {
                    _searchResults.value = fastResults
                } else {
                    // 2. Safe fallback to downloader engine
                    val dl = downloaderProvider?.invoke()
                    val fallback = dl?.searchCandidates(q, 10) ?: emptyList()
                    _searchResults.value = fallback
                }
            } catch (_: Exception) {
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
        _isSearching.value = false
    }

    fun enrichDuplicateMatch(row: QueueRow): QueueRow {
        val tracks = libraryTracksProvider?.invoke() ?: emptyList()
        if (tracks.isEmpty() || row.title.isBlank()) return row
        val match = ibytsync.core.upload.DuplicateDetector.findDuplicate(row.title, row.artist, row.durationMs, tracks)
        return row.copy(duplicateMatch = match)
    }

    fun addInputs(inputs: List<String>) {
        if (inputs.isEmpty()) return
        val newRows = mutableListOf<QueueRow>()
        for (l in inputs) {
            val trimmed = l.trim()
            if (trimmed.isEmpty()) continue
            val kind = UrlClassifier.classify(trimmed)
            if (kind is UrlKind.YouTubeWatchWithPlaylist) {
                if (inputs.size == 1) {
                    _pendingPlaylistPrompt.value = PlaylistPrompt(
                        videoUrl = "https://www.youtube.com/watch?v=${kind.videoId}",
                        playlistUrl = "https://www.youtube.com/playlist?list=${kind.playlistId}",
                        playlistId = kind.playlistId
                    )
                    return
                } else {
                    val status = RowStatus.FETCHING_METADATA
                    val row = QueueRow(
                        id = UUID.randomUUID().toString().take(8),
                        sourceInput = "https://www.youtube.com/watch?v=${kind.videoId}",
                        status = status,
                        title = trimmed.take(60),
                        detail = "Queued for metadata"
                    )
                    newRows.add(row)
                    continue
                }
            }
            if (UrlClassifier.isCollection(kind)) {
                expandCollectionAsync(trimmed, kind)
                continue
            }
            val status = RowStates.statusForClassifiedInput(kind)
            val row = QueueRow(
                id = UUID.randomUUID().toString().take(8),
                sourceInput = trimmed,
                status = status,
                title = trimmed.take(60),
                detail = if (status == RowStatus.SKIPPED_BAD_URL) "Invalid URL" else "Queued for metadata"
            )
            newRows.add(row)
        }
        if (newRows.isEmpty()) return
        _rows.update { it + newRows }
        for (row in newRows) {
            if (row.status == RowStatus.FETCHING_METADATA) {
                resolveRowAsync(row)
            }
        }
    }

    fun addInput(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        val extracted = ibytsync.core.input.UrlBatchExtractor.extractAudioUrls(trimmed)
        if (extracted.size > 1) {
            addInputs(extracted)
        } else if (extracted.size == 1 && (trimmed == extracted[0] || trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true))) {
            val kind = UrlClassifier.classify(extracted[0])
            if (kind is UrlKind.YouTubeWatchWithPlaylist) {
                _pendingPlaylistPrompt.value = PlaylistPrompt(
                    videoUrl = "https://www.youtube.com/watch?v=${kind.videoId}",
                    playlistUrl = "https://www.youtube.com/playlist?list=${kind.playlistId}",
                    playlistId = kind.playlistId
                )
            } else if (UrlClassifier.isCollection(kind)) {
                expandCollectionAsync(extracted[0], kind)
            } else {
                addInputs(listOf(extracted[0]))
            }
        } else {
            val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
            addInputs(lines)
        }
    }

    fun addSearchCandidate(candidate: ibytsync.core.matching.YtCandidate) {
        val canonicalUrl = "https://www.youtube.com/watch?v=${candidate.id}"
        val durationMs = if (candidate.duration > 0) (candidate.duration * 1000L).toLong() else null
        val (splitArtist, splitTitle) = ibytsync.core.metadata.YtTitleSplitter.split(candidate.title, candidate.channel)
        val row = QueueRow(
            id = UUID.randomUUID().toString().take(8),
            sourceInput = canonicalUrl,
            status = RowStatus.FETCHING_METADATA,
            title = splitTitle.ifBlank { candidate.title },
            artist = splitArtist.ifBlank { candidate.channel },
            durationMs = durationMs,
            detail = "Queued for metadata"
        )
        _rows.update { it + row }
        resolveRowAsync(row)
    }

    fun expandPlaylistUrl(url: String) {
        val kind = UrlClassifier.classify(url)
        expandCollectionAsync(url, kind)
    }

    fun expandCollectionAsync(url: String, kind: UrlKind) {
        val placeholderId = UUID.randomUUID().toString().take(8)
        val sourceLabel = when (kind) {
            is UrlKind.SpotifyPlaylist -> "Spotify Playlist"
            is UrlKind.SpotifyAlbum -> "Spotify Album"
            is UrlKind.YouTubePlaylist -> "YouTube Playlist"
            else -> "Collection"
        }
        val placeholder = QueueRow(
            id = placeholderId,
            sourceInput = url,
            status = RowStatus.FETCHING_METADATA,
            title = "Importing $sourceLabel…",
            detail = "Fetching tracklist…"
        )
        _rows.update { it + placeholder }

        scope.launch(Dispatchers.IO) {
            try {
                when (kind) {
                    is UrlKind.SpotifyPlaylist, is UrlKind.SpotifyAlbum -> {
                        val result = spotifyEmbedScraper.fetchCollection(kind)
                        if (result != null && result.tracks.isNotEmpty()) {
                            val collectionTitle = result.title
                            if (_batchDestination.value.playlistName.isNullOrBlank()) {
                                _batchDestination.update { it.copy(playlistName = collectionTitle) }
                            }
                            val tracksToImport = result.tracks.take(250)
                            val newRows = tracksToImport.mapIndexed { idx, t ->
                                val opt = ReleaseOption(
                                    artist = t.artist,
                                    trackTitle = t.title,
                                    album = t.album ?: collectionTitle,
                                    artworkUrl = t.coverUrl,
                                    durationMs = t.durationMs,
                                    isrc = null,
                                    sources = listOf("spotify"),
                                    score = 100,
                                    trackNumber = idx + 1,
                                    trackCount = tracksToImport.size
                                )
                                val noMeta = ReleaseOption.noMetadata(t.title, t.durationMs)
                                QueueRow(
                                    id = UUID.randomUUID().toString().take(8),
                                    sourceInput = "https://open.spotify.com/track/${t.trackId}",
                                    status = RowStatus.METADATA_READY,
                                    title = t.title,
                                    artist = t.artist,
                                    album = t.album ?: collectionTitle,
                                    durationMs = t.durationMs,
                                    coverUrl = t.coverUrl,
                                    options = listOf(opt, noMeta),
                                    selectedOption = opt,
                                    trackNumber = idx + 1,
                                    trackCount = tracksToImport.size,
                                    targetPlaylistName = collectionTitle,
                                    detail = "From $collectionTitle"
                                )
                            }
                            _rows.update { current ->
                                val idx = current.indexOfFirst { it.id == placeholderId }
                                if (idx >= 0) {
                                    current.toMutableList().apply {
                                        removeAt(idx)
                                        addAll(idx, newRows)
                                    }
                                } else {
                                    current + newRows
                                }
                            }
                        } else {
                            _rows.update { current ->
                                current.map {
                                    if (it.id == placeholderId) it.copy(
                                        status = RowStatus.FAILED_METADATA,
                                        detail = "Failed to load $sourceLabel tracks"
                                    ) else it
                                }
                            }
                        }
                    }
                    is UrlKind.YouTubePlaylist -> {
                        val dl = downloaderProvider?.invoke()
                        val candidates = dl?.extractPlaylist(url) ?: emptyList()
                        if (candidates.isNotEmpty()) {
                            val plTitle = "YouTube Playlist"
                            if (_batchDestination.value.playlistName.isNullOrBlank()) {
                                _batchDestination.update { it.copy(playlistName = plTitle) }
                            }
                            val tracksToImport = candidates.take(250)
                            val newRows = tracksToImport.mapIndexed { idx, c ->
                                QueueRow(
                                    id = UUID.randomUUID().toString().take(8),
                                    sourceInput = "https://www.youtube.com/watch?v=${c.id}",
                                    status = RowStatus.FETCHING_METADATA,
                                    title = c.title,
                                    artist = c.channel,
                                    durationMs = (c.duration * 1000).toLong().takeIf { it > 0L },
                                    trackNumber = idx + 1,
                                    trackCount = tracksToImport.size,
                                    targetPlaylistName = plTitle,
                                    detail = "Queued for metadata"
                                )
                            }
                            _rows.update { current ->
                                val idx = current.indexOfFirst { it.id == placeholderId }
                                if (idx >= 0) {
                                    current.toMutableList().apply {
                                        removeAt(idx)
                                        addAll(idx, newRows)
                                    }
                                } else {
                                    current + newRows
                                }
                            }
                            for (r in newRows) {
                                resolveRowAsync(r)
                            }
                        } else {
                            _rows.update { current ->
                                current.map {
                                    if (it.id == placeholderId) it.copy(
                                        status = RowStatus.FAILED_METADATA,
                                        detail = "Failed to load playlist videos"
                                    ) else it
                                }
                            }
                        }
                    }
                    else -> {}
                }
            } catch (_: Exception) {
                _rows.update { current ->
                    current.map {
                        if (it.id == placeholderId) it.copy(
                            status = RowStatus.FAILED_METADATA,
                            detail = "Error expanding $sourceLabel"
                        ) else it
                    }
                }
            }
        }
    }

    fun addLocalFile(
        sourcePathOrUri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long? = null,
        coverUrl: String? = null,
        year: String? = null,
        genre: String? = null,
        trackNumber: Int? = null,
        trackCount: Int? = null
    ) {
        val originalOpt = ReleaseOption.originalFile(
            title = title.ifBlank { "Local Audio Track" },
            artist = artist,
            album = album.ifBlank { title.ifBlank { "Local File" } },
            artworkUrl = coverUrl,
            durationMs = durationMs,
            year = year,
            genre = genre,
            trackNumber = trackNumber,
            trackCount = trackCount
        )
        val noMetaOpt = ReleaseOption.noMetadata(
            trackTitle = title.ifBlank { "Local Audio Track" },
            durationMs = durationMs
        )
        val row = QueueRow(
            id = UUID.randomUUID().toString().take(8),
            sourceInput = sourcePathOrUri,
            status = if (metadataResolver != null) RowStatus.FETCHING_METADATA else RowStatus.METADATA_READY,
            title = title.ifBlank { "Local Audio Track" },
            artist = artist,
            album = album.ifBlank { title.ifBlank { "Local File" } },
            durationMs = durationMs,
            coverUrl = coverUrl,
            options = listOf(originalOpt, noMetaOpt),
            selectedOption = originalOpt,
            year = year,
            genre = genre,
            trackNumber = trackNumber,
            trackCount = trackCount,
            detail = if (metadataResolver != null) "Searching official releases…" else "Local file tags ready"
        )
        _rows.update { it + row }
        if (metadataResolver != null) {
            resolveRowAsync(row)
        }
    }

    fun resolveRowAsync(row: QueueRow) {
        val resolver = metadataResolver ?: return
        scope.launch {
            _rows.update { list ->
                list.map { if (it.id == row.id) it.copy(status = RowStatus.FETCHING_METADATA, detail = "Fetching tags & releases…") else it }
            }
            val outcome = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                resolver.resolve(row)
            }
            if (outcome != null) {
                when (outcome) {
                    is RowOutcome.Ready -> updateRow(outcome.row)
                    is RowOutcome.AwaitingPick -> updateRow(
                        outcome.row.copy(
                            options = if (outcome.row.options.isNotEmpty()) outcome.row.options else outcome.suggestion.options,
                            selectedOption = outcome.row.selectedOption ?: outcome.suggestion.options.firstOrNull()
                        )
                    )
                }
            } else {
                val timedOutRow = row.copy(
                    status = RowStatus.FAILED_METADATA,
                    detail = "Resolution timed out",
                    selectedOption = ReleaseOption.noMetadata(row.title, row.durationMs)
                )
                updateRow(timedOutRow)
            }
        }
    }

    fun reSearchMetadata(rowId: String, title: String, artist: String) {
        val row = _rows.value.firstOrNull { it.id == rowId } ?: return
        val updated = row.copy(
            title = title.trim(),
            artist = artist.trim(),
            status = RowStatus.FETCHING_METADATA,
            // The confirmed album track belonged to the previous title/artist, so it must not
            // survive a re-search — the resolver will confirm one for the new terms.
            officialAudioId = null,
            officialAudioDurationMs = null,
            detail = "Searching official releases & artwork…"
        )
        updateRow(updated)
        resolveRowAsync(updated)
    }

    fun refreshDuplicateMatches() {
        _rows.update { list ->
            list.map { enrichDuplicateMatch(it) }
        }
    }

    /**
     * Saves the destination without deciding the match.
     *
     * Writes unconditionally, nulls included: "Use batch default" is expressed as a null playlist
     * id, so treating null as "leave alone" would make that choice impossible to record.
     */
    fun stageDestination(
        rowId: String,
        playlistId: String?,
        playlistName: String?,
        playlistIds: List<String> = emptyList(),
        playlistNames: List<String> = emptyList()
    ) {
        _rows.update { list ->
            list.map { row ->
                if (row.id != rowId) row
                else {
                    val pIds = if (playlistIds.isNotEmpty()) playlistIds else listOfNotNull(playlistId)
                    val pNames = if (playlistNames.isNotEmpty()) playlistNames else listOfNotNull(playlistName)
                    row.copy(
                        targetPlaylistId = playlistId,
                        targetPlaylistName = playlistName,
                        targetPlaylistIds = pIds,
                        targetPlaylistNames = pNames
                    )
                }
            }
        }
    }

    /**
     * Saves how the row would run — audio source, format, upload override — without deciding the
     * match.
     *
     * These are preferences, not a decision: they only shape how a chosen release executes, so
     * they can be persisted the moment they change. Committing the match itself stays behind
     * USE THIS MATCH, because that is what flips the row to METADATA_READY and releases it from
     * NEEDS REVIEW and the disabled START BATCH gate.
     *
     * Null arguments leave the existing value alone, so a caller can update one without having
     * to know the others.
     */
    fun stageAudioChoice(
        rowId: String,
        audioPref: AudioSourcePreference? = null,
        audioFormat: AudioFormatChoice? = null,
        forceUpload: Boolean? = null
    ) {
        _rows.update { list ->
            list.map { row ->
                if (row.id != rowId) row
                else row.copy(
                    audioPreference = audioPref ?: row.audioPreference,
                    audioFormat = audioFormat ?: row.audioFormat,
                    forceUpload = forceUpload ?: row.forceUpload
                )
            }
        }
    }

    /**
     * Commits the match only. Preferences were already persisted by [stagePreferences], so
     * rewriting them here would risk clobbering a choice made after the sheet last read them.
     */
    fun commitMatch(rowId: String, option: ReleaseOption) {
        var rowToLookup: QueueRow? = null
        _rows.update { list ->
            list.map { row ->
                if (row.id != rowId) row
                else if (option.isNoMetadata) {
                    enrichDuplicateMatch(
                        row.copy(
                            status = RowStatus.METADATA_READY,
                            title = option.trackTitle.ifBlank { row.title },
                            artist = "",
                            album = "",
                            durationMs = option.durationMs ?: row.durationMs,
                            coverUrl = null,
                            selectedOption = option,
                            year = null,
                            genre = null,
                            trackNumber = null,
                            trackCount = null,
                            detail = "No tags"
                        )
                    )
                } else {
                    val albumDisplay = option.album.ifBlank { option.trackTitle }
                    val detail = if (option.isOriginalSource) {
                        if (!row.sourceInput.startsWith("http")) "Original file tags" else "Info from the video"
                    } else {
                        "$albumDisplay • ${row.audioPreference.label}"
                    }
                    val updated = enrichDuplicateMatch(
                        row.copy(
                            status = RowStatus.METADATA_READY,
                            title = option.trackTitle,
                            artist = option.artist,
                            album = albumDisplay,
                            durationMs = option.durationMs ?: row.durationMs,
                            coverUrl = option.artworkUrl ?: row.coverUrl,
                            selectedOption = option,
                            year = option.year,
                            genre = option.genre,
                            trackNumber = option.trackNumber,
                            trackCount = option.trackCount,
                            detail = detail
                        )
                    )
                    if (!option.isOriginalSource && (updated.officialAudioId == null || option.artist != row.selectedOption?.artist)) {
                        rowToLookup = updated
                    }
                    updated
                }
            }
        }
        rowToLookup?.let { r ->
            scope.launch {
                val resolver = metadataResolver ?: return@launch
                val effectiveDuration = r.sourceFileDurationMs() ?: r.durationMs
                val albumTrack = resolver.findOfficialAudio(option.artist, option.trackTitle, effectiveDuration)
                if (albumTrack != null) {
                    _rows.update { list ->
                        list.map { curr ->
                            if (curr.id == r.id) {
                                curr.copy(
                                    officialAudioId = albumTrack.id,
                                    officialAudioDurationMs = albumTrack.duration.toLong() * 1000L
                                )
                            } else curr
                        }
                    }
                }
            }
        }
    }

    fun applyBatchSettings(
        playlistId: String?,
        playlistName: String?,
        format: AudioFormatChoice,
        playlistIds: List<String> = emptyList(),
        playlistNames: List<String> = emptyList()
    ) {
        val pIds = if (playlistIds.isNotEmpty()) playlistIds else listOfNotNull(playlistId)
        val pNames = if (playlistNames.isNotEmpty()) playlistNames else listOfNotNull(playlistName)
        _rows.update { list ->
            list.map { row ->
                row.copy(
                    targetPlaylistId = playlistId,
                    targetPlaylistName = playlistName,
                    targetPlaylistIds = pIds,
                    targetPlaylistNames = pNames,
                    audioFormat = format
                )
            }
        }
    }

    fun selectRelease(
        rowId: String,
        option: ReleaseOption,
        audioPref: AudioSourcePreference? = null,
        audioFormat: AudioFormatChoice? = null,
        targetPlaylistId: String? = null,
        targetPlaylistName: String? = null,
        forceUpload: Boolean = false
    ) {
        _rows.update { list ->
            list.map { row ->
                if (row.id == rowId) {
                    val pref = audioPref ?: row.audioPreference
                    val fmt = audioFormat ?: row.audioFormat
                    if (option.isNoMetadata) {
                        enrichDuplicateMatch(
                            row.copy(
                                status = RowStatus.METADATA_READY,
                                title = option.trackTitle.ifBlank { row.title },
                                artist = "",
                                album = "",
                                durationMs = option.durationMs ?: row.durationMs,
                                coverUrl = null,
                                audioPreference = pref,
                                audioFormat = fmt,
                                targetPlaylistId = targetPlaylistId,
                                targetPlaylistName = targetPlaylistName,
                                forceUpload = forceUpload,
                                selectedOption = option,
                                year = null,
                                genre = null,
                                trackNumber = null,
                                trackCount = null,
                                detail = "No tags"
                            )
                        )
                    } else {
                        val albumDisplay = option.album.ifBlank { option.trackTitle }
                        val detail = if (option.isOriginalSource) {
                            if (row.sourceInput.startsWith("content://") || row.sourceInput.startsWith("file://") || !row.sourceInput.startsWith("http")) {
                                "Original file tags"
                            } else {
                                "Info from the video"
                            }
                        } else {
                            "$albumDisplay • ${pref.label}"
                        }
                        enrichDuplicateMatch(
                            row.copy(
                                status = RowStatus.METADATA_READY,
                                title = option.trackTitle,
                                artist = option.artist,
                                album = albumDisplay,
                                durationMs = option.durationMs ?: row.durationMs,
                                coverUrl = option.artworkUrl ?: row.coverUrl,
                                audioPreference = pref,
                                audioFormat = fmt,
                                targetPlaylistId = targetPlaylistId,
                                targetPlaylistName = targetPlaylistName,
                                forceUpload = forceUpload,
                                selectedOption = option,
                                year = option.year,
                                genre = option.genre,
                                trackNumber = option.trackNumber,
                                trackCount = option.trackCount,
                                detail = detail
                            )
                        )
                    }
                } else row
            }
        }
    }

    fun selectNoMetadata(rowId: String) {
        _rows.update { list ->
            list.map { row ->
                if (row.id == rowId) {
                    val noMeta = ReleaseOption.noMetadata(row.title, row.durationMs)
                    row.copy(
                        status = RowStatus.METADATA_READY,
                        artist = "",
                        album = "",
                        coverUrl = null,
                        selectedOption = noMeta,
                        year = null,
                        genre = null,
                        trackNumber = null,
                        trackCount = null,
                        detail = "No tags"
                    )
                } else row
            }
        }
    }

    fun acceptCurrentOption(rowId: String) {
        val row = _rows.value.firstOrNull { it.id == rowId } ?: return
        val opt = row.selectedOption ?: row.options.firstOrNull()
        if (opt != null) {
            selectRelease(rowId, opt)
        } else {
            _rows.update { list ->
                list.map { if (it.id == rowId) it.copy(status = RowStatus.METADATA_READY) else it }
            }
        }
    }

    fun updateAudioPreference(rowId: String, audioPref: AudioSourcePreference) {
        _rows.update { list ->
            list.map { row ->
                if (row.id == rowId) {
                    val currentAlbum = row.selectedOption?.album ?: row.album
                    val audioLabel = audioPref.label
                    val detail = if (currentAlbum.isNotBlank()) "$currentAlbum • $audioLabel" else audioLabel
                    row.copy(
                        audioPreference = audioPref,
                        detail = detail
                    )
                } else row
            }
        }
    }

    fun manualEditArtwork(rowId: String, newCoverUri: String) {
        _rows.update { list ->
            list.map { row ->
                if (row.id == rowId) {
                    row.withManualArtwork(newCoverUri)
                } else row
            }
        }
    }

    fun manualEditMetadata(rowId: String, opt: ReleaseOption) {
        _rows.update { list ->
            list.map { row ->
                if (row.id == rowId) {
                    row.withManualMetadata(opt)
                } else row
            }
        }
    }

    fun updateRow(updated: QueueRow) {
        val enriched = enrichDuplicateMatch(updated)
        _rows.update { list -> list.map { if (it.id == enriched.id) enriched else it } }
    }

    fun updateRowProgress(rowId: String, status: RowStatus, progress: Float? = null, detail: String? = null, clearProgress: Boolean = false) {
        _rows.update { list ->
            list.map { row ->
                if (row.id == rowId) {
                    row.copy(
                        status = status,
                        progress = if (clearProgress) null else progress ?: row.progress,
                        detail = detail ?: row.detail
                    )
                } else row
            }
        }
    }

    fun setTargetPlaylist(
        id: String,
        playlistId: String?,
        playlistName: String?,
        playlistIds: List<String> = emptyList(),
        playlistNames: List<String> = emptyList()
    ) {
        val pIds = if (playlistIds.isNotEmpty()) playlistIds else listOfNotNull(playlistId)
        val pNames = if (playlistNames.isNotEmpty()) playlistNames else listOfNotNull(playlistName)
        _rows.update { list ->
            list.map {
                if (it.id == id) it.copy(
                    targetPlaylistId = playlistId,
                    targetPlaylistName = playlistName,
                    targetPlaylistIds = pIds,
                    targetPlaylistNames = pNames
                ) else it
            }
        }
    }

    fun setAudioFormat(id: String, format: AudioFormatChoice?) {
        _rows.update { list ->
            list.map {
                if (it.id == id) it.copy(audioFormat = format) else it
            }
        }
    }

    fun setForceUpload(id: String, force: Boolean) {
        _rows.update { list ->
            list.map {
                if (it.id == id) it.copy(forceUpload = force) else it
            }
        }
    }

    fun removeRow(id: String): String? {
        val row = _rows.value.firstOrNull { it.id == id }
        _rows.update { list -> list.filterNot { it.id == id } }
        return row?.replacesUploadedTrackId
    }

    fun startReSync(syncedRow: QueueRow): QueueRow {
        val newRowId = "resync_" + UUID.randomUUID().toString().take(8)
        val copyToQueue = syncedRow.copy(
            id = newRowId,
            status = RowStatus.METADATA_READY,
            progress = null,
            detail = "Staged for Re-Sync • ${syncedRow.album.ifBlank { syncedRow.title }}",
            replacesUploadedTrackId = syncedRow.ibroadcastTrackId ?: syncedRow.id,
            isEditingInStudio = false,
            isUpdated = false,
            forceUpload = true
        )
        _rows.update { it + copyToQueue }
        return copyToQueue
    }

    fun retryRow(id: String) {
        var toRetry: QueueRow? = null
        _rows.update { list ->
            list.map {
                if (it.id == id) {
                    val nextStatus = RowStates.statusAfterRetry(it.status)
                    if (nextStatus == RowStatus.FETCHING_METADATA) {
                        val next = it.copy(status = nextStatus)
                        toRetry = next
                        next
                    } else if (nextStatus == RowStatus.METADATA_READY) {
                        it.copy(status = nextStatus, progress = null, detail = PhaseDetails.READY_RETRY)
                    } else it
                } else it
            }
        }
        toRetry?.let { resolveRowAsync(it) }
    }

    fun clearFinished() {
        _rows.update { list -> list.filterNot { RowStates.isTerminal(it.status) } }
        reconcileEditingStatus()
    }

    fun clearAll() {
        _rows.value = emptyList()
    }

    fun canStart(): Boolean = _isDownloaderReady.value && RowStates.canStartRows(_rows.value)

    fun summaryLine(): String {
        val done = _rows.value.count { it.status == RowStatus.DONE }
        val exist = _rows.value.count { it.status == RowStatus.ALREADY_UPLOADED }
        val failed = _rows.value.count {
            it.status == RowStatus.FAILED_UPLOAD || it.status == RowStatus.FAILED_DOWNLOAD ||
                it.status == RowStatus.FAILED_SAVE || it.status == RowStatus.FAILED_METADATA
        }
        return "Done — $done uploaded, $exist duplicates, $failed failed"
    }
}
