package ibytsync.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import ibytsync.core.download.DownloadRequest
import ibytsync.core.download.YtDlpEngine
import ibytsync.core.matching.YtCandidate
import ibytsync.core.matching.findBestYtMatch
import ibytsync.core.metadata.CorrectionClient
import ibytsync.core.metadata.YtExtract
import ibytsync.core.metadata.YtSuggestion
import ibytsync.core.metadata.YtSuggestionBuilder
import ibytsync.core.pipeline.RowStates.afterYouTubePick
import ibytsync.core.pipeline.RowStates.afterYouTubeSuggest
import ibytsync.core.storage.FolderStore
import ibytsync.core.tagging.CoverFetcher
import ibytsync.core.tagging.Id3Writer
import ibytsync.core.upload.BatchSummary
import ibytsync.core.upload.Checksummer
import ibytsync.core.upload.DuplicateDetector
import ibytsync.core.upload.RoutePreference
import ibytsync.core.upload.UploadRouter
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class BatchConfig(
    val saveToDisk: Boolean = false,
    val routePreference: RoutePreference = RoutePreference.LibraryOnly,
    val askedPlaylistId: String? = null,
    val batchId: String? = null,
    val skipDuplicates: Boolean = true
)

data class UploadOutcome(val success: Boolean, val trackId: String? = null)

interface PipelineHooks {
    fun search(query: String, count: Int): List<YtCandidate>
    fun uploadFile(tmp: File, playlistId: String?): Boolean
    fun uploadFileWithResult(
        tmp: File,
        playlistId: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): UploadOutcome =
        UploadOutcome(uploadFile(tmp, playlistId))
    fun uploadFileWithResult(
        tmp: File,
        playlistId: String?,
        md5: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): UploadOutcome =
        uploadFileWithResult(tmp, playlistId, onProgress)
    fun uploadFileWithResult(
        tmp: File,
        playlistIds: List<String>,
        md5: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): UploadOutcome =
        uploadFileWithResult(tmp, playlistIds.firstOrNull(), md5, onProgress)
    fun libraryPairs(): Map<String, Pair<String, String>>
    fun libraryChecksums(): Set<String>
    fun playlists(): Map<String, ibytsync.core.upload.Playlist>
    fun libraryTracks(): Collection<ibytsync.core.upload.LibraryTrackInfo> = emptyList()
    fun createPlaylist(name: String): ibytsync.core.upload.Playlist? = null
    fun trashTracks(trackIds: List<String>): Boolean = true
}

interface SaveHooks {
    fun saveFile(tmp: File): FolderStore.SaveOutcome
}

sealed interface RowOutcome {
    data class Ready(val row: QueueRow) : RowOutcome
    data class AwaitingPick(val row: QueueRow, val suggestion: YtSuggestion) : RowOutcome
}

class BatchOrchestrator(
    private val downloader: YtDlpEngine,
    private val hooks: PipelineHooks,
    private val checksummer: Checksummer = Checksummer,
    private val saveHooks: SaveHooks? = null,
    private val streamOpener: ((String) -> java.io.InputStream?)? = null
) {
    /**
     * Resolve a YouTube URL row to suggestion state. Pure except for the
     * injected describe + correction clients. AWAITING_ACCEPT parks the row
     * until the UI calls [decideYouTube]; FAILED_METADATA on extract failure.
     */
    fun prepareYouTubeRow(
        row: QueueRow,
        youtubeUrl: String,
        vararg clients: CorrectionClient
    ): RowOutcome {
        val extract: YtExtract? = try {
            downloader.describeVideo(youtubeUrl)
        } catch (_: Exception) {
            null
        }
        val suggestion = if (extract == null) {
            null
        } else {
            try {
                YtSuggestionBuilder.build(extract, *clients)
            } catch (_: Exception) {
                null
            }
        }
        val next = row.afterYouTubeSuggest(suggestion)
        return if (next.status == RowStatus.AWAITING_ACCEPT && suggestion != null) {
            RowOutcome.AwaitingPick(next, suggestion)
        } else {
            RowOutcome.Ready(next)
        }
    }

    /** Explicit user pick on an awaiting row. Out-of-range counts as reject. */
    fun decideYouTube(
        row: QueueRow,
        suggestion: YtSuggestion,
        index: Int,
        audioPreference: AudioSourcePreference = AudioSourcePreference.ORIGINAL_VIDEO
    ): QueueRow {
        val decided = row.afterYouTubePick(suggestion, index, audioPreference)
        if (decided.status == RowStatus.METADATA_READY) {
            suggestion.pick(index)?.let { stashRich(row.id, it) }
        }
        return decided
    }

    suspend fun runRow(
        row: QueueRow,
        tmpDir: File,
        cfg: BatchConfig,
        onRowUpdate: ((QueueRow) -> Unit)? = null
    ): Pair<QueueRow, BatchSummary> {
        val summary = BatchSummary()
        if (row.status == RowStatus.AWAITING_ACCEPT) {
            return row.copy(status = RowStatus.FAILED_METADATA) to summary
        }
        var cur = row.copy(status = RowStatus.DOWNLOADING, progress = ProgressPhases.DOWNLOAD_START, detail = ProgressPhases.formatDownloadDetail(0f, null, row.durationMs))
        onRowUpdate?.invoke(cur)
        val progressThrottle = ProgressThrottle { updated -> cur = updated; onRowUpdate?.invoke(cur) }
        fun emitProgress(status: RowStatus, progress: Float, detail: String, force: Boolean = false) {
            val floor = cur.progress ?: 0f
            val next = if (force) progress else maxOf(progress, floor)
            progressThrottle.emit(cur, status, next, detail, force)
        }
        fun downloadTick(progressPercent: Float, line: String? = null) {
            val detail = ProgressPhases.formatDownloadDetail(progressPercent, line, row.durationMs)
            emitProgress(RowStatus.DOWNLOADING, ProgressPhases.downloadMapped(progressPercent / 100f), detail)
        }
        var tmp: File? = null
        var activeDownloadToken: String? = null
        try {
            coroutineContext.ensureActive()
            val formatChoice = row.audioFormat ?: AudioFormatChoice.MP3_192K
            val audioQuality = formatChoice.ytAudioQuality
            val audioFormat = formatChoice.ytAudioFormat

            if (cfg.skipDuplicates && !row.forceUpload) {
                val preDup = if (hooks.libraryTracks().isNotEmpty()) {
                    DuplicateDetector.findDuplicate(row.title, row.artist, row.durationMs, hooks.libraryTracks()) != null
                } else {
                    DuplicateDetector.isDuplicate(row.title, row.artist, hooks.libraryPairs())
                }
                if (preDup) {
                    summary.alreadyExisted++
                    return cur.copy(status = RowStatus.ALREADY_UPLOADED, progress = ProgressPhases.DONE, detail = PhaseDetails.SKIPPED_LIBRARY) to summary
                }
            }

            val directUrl = directDownloadUrl(row)
            val localCandidate = if (!row.savedLocalUri.isNullOrBlank() && isLocalAudioSource(row.savedLocalUri)) {
                row.savedLocalUri
            } else if (isLocalAudioSource(row.sourceInput)) {
                row.sourceInput
            } else null

            val isResyncLocalReuse = !row.replacesUploadedTrackId.isNullOrEmpty() && localCandidate != null
            if (localCandidate != null) {
                val ext = localCandidate.substringAfterLast('.', "mp3").take(4).lowercase()
                val token = "tb_local_" + UUID.randomUUID().toString().take(8)
                val testFile = File(tmpDir, "$token.$ext")
                if (copyLocalAudio(localCandidate, testFile) && testFile.exists() && testFile.length() > 0L) {
                    tmp = testFile
                }
                if (tmp != null && isResyncLocalReuse) {
                    emitProgress(RowStatus.RETUNING, ProgressPhases.PROCESSING_HOLD, PhaseDetails.RESYNC_TAGS_CHANGED, force = true)
                }
            }

            if (tmp == null) {
                if (directUrl != null && row.audioPreference == AudioSourcePreference.ORIGINAL_VIDEO) {
                    val token = "tb_" + UUID.randomUUID().toString().take(8)
                    activeDownloadToken = token
                    val req = DownloadRequest(directUrl, token, audioQuality = audioQuality, audioFormat = audioFormat)
                    val dl = downloader.download(req) { progressPercent, _, line ->
                        downloadTick(progressPercent, line)
                    } ?: return cur.copy(status = RowStatus.FAILED_DOWNLOAD, detail = PhaseDetails.DOWNLOAD_FAILED) to summary
                    val f = File(dl.filePath)
                    if (!f.exists()) return cur.copy(status = RowStatus.FAILED_DOWNLOAD, detail = PhaseDetails.DOWNLOAD_FAILED) to summary
                    tmp = f
                } else {
                    val query = "${row.artist} ${row.title}".trim()
                    val wantsAlbumAudio = row.audioPreference == AudioSourcePreference.CLEAN_STUDIO

                    // A confirmed album track was offered in the sheet, so honour that exact
                    // recording instead of re-deciding and possibly landing on a different version.
                    val pinned = row.officialAudioId?.takeIf { wantsAlbumAudio && it.isNotBlank() }
                    val targetUrl = if (pinned != null) {
                        "https://music.youtube.com/watch?v=$pinned"
                    } else {
                        // Album Audio hunts the released master, so aim at the album's length
                        // rather than the source video's (see QueueRow.albumReleaseDurationMs).
                        val targetMs = (if (wantsAlbumAudio) row.albumReleaseDurationMs() else null)
                            ?: row.durationMs ?: 0L
                        // No keyword hint is added any more: the search itself now returns labelled
                        // album tracks, and appending "official audio" only re-ranked real results
                        // below user re-uploads of the same song.
                        val count = if (wantsAlbumAudio) 10 else if (targetMs > 0L) 5 else 8
                        val cands = hooks.search(query, count)
                        findBestYtMatch(row.title, row.artist, targetMs, cands)
                            ?: return cur.copy(status = RowStatus.FAILED_DOWNLOAD, detail = PhaseDetails.DOWNLOAD_FAILED) to summary
                    }

                    val token = "tb_" + UUID.randomUUID().toString().take(8)
                    activeDownloadToken = token
                    val req = DownloadRequest(targetUrl, token, audioQuality = audioQuality, audioFormat = audioFormat)
                    val dl = downloader.download(req) { progressPercent, _, line ->
                        downloadTick(progressPercent, line)
                    } ?: return cur.copy(status = RowStatus.FAILED_DOWNLOAD, detail = PhaseDetails.DOWNLOAD_FAILED) to summary
                    val f = File(dl.filePath)
                    if (!f.exists()) return cur.copy(status = RowStatus.FAILED_DOWNLOAD, detail = PhaseDetails.DOWNLOAD_FAILED) to summary
                    tmp = f
                }
            }

            // Re-sync of an existing track reuses the saved audio instead of re-downloading
            // from YouTube; only tags/art changed, so say so rather than showing a download.
            val processingDetail = if (isResyncLocalReuse) PhaseDetails.RESYNC_TAGS_CHANGED else PhaseDetails.PROCESSING
            emitProgress(RowStatus.RETUNING, ProgressPhases.PROCESSING_HOLD, processingDetail, force = true)
            tagFile(tmp, cur)
            emitProgress(RowStatus.RETUNING, ProgressPhases.PROCESSING_END, processingDetail, force = true)

            var savedUri: String? = null
            if (cfg.saveToDisk) {
                emitProgress(RowStatus.SAVING, ProgressPhases.SAVE_START, PhaseDetails.SAVING, force = true)
                when (val outcome = saveToDisk(tmp)) {
                    is FolderStore.SaveOutcome.Skipped -> {
                        tmp.delete()
                        return cur.copy(status = RowStatus.SKIPPED_FOLDER) to summary
                    }
                    is FolderStore.SaveOutcome.Failed -> {
                        tmp.delete()
                        summary.failed++
                        val saveDetail = if (outcome.reason.contains("permission", ignoreCase = true)) {
                            PhaseDetails.FOLDER_EXPIRED
                        } else PhaseDetails.SAVE_FAILED
                        return cur.copy(status = RowStatus.FAILED_SAVE, detail = saveDetail) to summary
                    }
                    is FolderStore.SaveOutcome.Saved -> {
                        savedUri = outcome.displayName
                        cur = cur.copy(status = RowStatus.SAVING, progress = ProgressPhases.SAVE_END, detail = PhaseDetails.SAVING, savedLocalUri = savedUri)
                        onRowUpdate?.invoke(cur)
                        tagFile(tmp, cur)
                    }
                }
            }

            val uploadFloor = if (cfg.saveToDisk) ProgressPhases.UPLOAD_START else ProgressPhases.UPLOAD_START_NO_SAVE
            emitProgress(RowStatus.UPLOADING, uploadFloor, PhaseDetails.UPLOADING, force = true)
            emitProgress(RowStatus.UPLOADING, ProgressPhases.FINALIZE_HOLD, PhaseDetails.FINALIZING)
            val isDup = if (cfg.skipDuplicates && !row.forceUpload) {
                if (hooks.libraryTracks().isNotEmpty()) {
                    DuplicateDetector.findDuplicate(row.title, row.artist, row.durationMs, hooks.libraryTracks()) != null
                } else {
                    DuplicateDetector.isDuplicate(row.title, row.artist, hooks.libraryPairs())
                }
            } else false
            if (isDup) {
                summary.alreadyExisted++
                tmp.delete()
                return cur.copy(status = RowStatus.ALREADY_UPLOADED, progress = ProgressPhases.DONE, detail = PhaseDetails.SKIPPED_LIBRARY) to summary
            }
            val md5 = try {
                checksummer.md5(tmp)
            } catch (_: Exception) {
                null
            }
            if (cfg.skipDuplicates && !row.forceUpload && md5 != null && hooks.libraryChecksums().contains(md5)) {
                summary.alreadyExisted++
                tmp.delete()
                return cur.copy(status = RowStatus.ALREADY_UPLOADED, progress = ProgressPhases.DONE, detail = PhaseDetails.SKIPPED_IDENTICAL) to summary
            }
            val playlistIds = when {
                row.effectivePlaylistIds().isNotEmpty() -> row.effectivePlaylistIds()
                row.targetPlaylistId == "lib_only" || row.targetPlaylistName == "Library Only" || row.targetPlaylistIds.contains("lib_only") -> emptyList()
                row.effectivePlaylistNames().isNotEmpty() -> {
                    row.effectivePlaylistNames().mapNotNull { targetName ->
                        val existing = hooks.playlists().values.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                        existing?.id ?: hooks.createPlaylist(targetName)?.id
                    }
                }
                row.targetPlaylistName?.takeIf { it.isNotBlank() && it != "Library Only" } != null -> {
                    val targetName = row.targetPlaylistName!!
                    val existing = hooks.playlists().values.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                    listOfNotNull(existing?.id ?: hooks.createPlaylist(targetName)?.id)
                }
                else -> listOfNotNull(UploadRouter.resolve(cfg.routePreference, hooks.playlists(), cfg.askedPlaylistId))
            }
            emitProgress(RowStatus.UPLOADING, uploadFloor, ProgressPhases.formatUploadDetail(0L, tmp.length()), force = true)
            val uploadOutcome = try {
                hooks.uploadFileWithResult(tmp, playlistIds, md5) { sent, total ->
                    emitProgress(RowStatus.UPLOADING, ProgressPhases.uploadMapped(sent, total, uploadFloor), ProgressPhases.formatUploadDetail(sent, total))
                }
            } catch (_: Exception) { UploadOutcome(false) }
            tmp.delete()
            if (!uploadOutcome.success) {
                summary.failed++
                return cur.copy(status = RowStatus.FAILED_UPLOAD, detail = PhaseDetails.UPLOAD_FAILED) to summary
            }
            if (!row.replacesUploadedTrackId.isNullOrEmpty()) {
                try {
                    hooks.trashTracks(listOf(row.replacesUploadedTrackId))
                } catch (_: Exception) {}
            }
            progressThrottle.flush(cur, RowStatus.DONE, ProgressPhases.DONE, PhaseDetails.SYNCED)
            summary.uploaded++
            return cur.copy(
                status = RowStatus.DONE,
                progress = ProgressPhases.DONE,
                detail = PhaseDetails.SYNCED,
                batchId = cfg.batchId ?: cur.batchId,
                ibroadcastTrackId = uploadOutcome.trackId ?: cur.ibroadcastTrackId,
                isUpdated = !row.replacesUploadedTrackId.isNullOrEmpty(),
                savedLocalUri = savedUri ?: cur.savedLocalUri
            ) to summary
        } catch (e: CancellationException) {
            activeDownloadToken?.let { token ->
                downloader.cancelProcess(token)
                try {
                    tmpDir.listFiles { f -> f.name.startsWith(token) }?.forEach { it.delete() }
                } catch (_: Exception) {}
            }
            tmp?.delete()
            try { progressThrottle.flush(cur, cur.status, cur.progress ?: 0f, PhaseDetails.CANCELLED) } catch (_: Exception) {}
            throw e
        } catch (_: Exception) {
            summary.failed++
            val failedStatus = if (cur.status == RowStatus.DOWNLOADING) RowStatus.FAILED_DOWNLOAD else RowStatus.FAILED_UPLOAD
            val failedDetail = if (failedStatus == RowStatus.FAILED_DOWNLOAD) PhaseDetails.DOWNLOAD_FAILED else PhaseDetails.UPLOAD_FAILED
            val failed = cur.copy(status = failedStatus, detail = failedDetail)
            try { onRowUpdate?.invoke(failed) } catch (_: Exception) {}
            cur = failed
            return failed to summary
        } finally {
            progressThrottle.flushQuietly()
        }
    }

    private fun directDownloadUrl(row: QueueRow): String? {
        val input = row.sourceInput.trim()
        if (input.isEmpty()) return null
        return try {
            val uri = java.net.URI(input)
            val scheme = (uri.scheme ?: "").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = (uri.host ?: "").lowercase()
            val yt = host == "youtube.com" || host == "www.youtube.com" ||
                host == "m.youtube.com" || host == "music.youtube.com" ||
                host == "youtu.be" || host == "www.youtu.be"
            if (yt) input else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isLocalAudioSource(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.startsWith("content://", ignoreCase = true)) return true
        if (trimmed.startsWith("file://", ignoreCase = true)) return true
        if (trimmed.startsWith("/") && (File(trimmed).exists() || trimmed.contains("."))) return true
        return false
    }

    private fun copyLocalAudio(source: String, dest: File): Boolean {
        return try {
            val stream = streamOpener?.invoke(source)
                ?: if (File(source).exists()) File(source).inputStream() else null
                ?: if (source.startsWith("file://")) {
                    val path = try { java.net.URI(source).path } catch (_: Exception) { source.removePrefix("file://") }
                    File(path).inputStream()
                } else null
            if (stream == null) return false
            stream.use { ins ->
                dest.outputStream().use { outs ->
                    ins.copyTo(outs)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tagFile(tmp: File, row: QueueRow) {
        if (row.selectedOption?.isNoMetadata == true) {
            return
        }
        if (row.selectedOption?.isOriginalSource == true && isLocalAudioSource(row.sourceInput)) {
            return
        }
        val coverBytes = row.coverUrl?.let { CoverFetcher.fetch(it, streamOpener) }
        val coverMime = coverBytes?.let { CoverFetcher.sniffMime(it) }
        val extra = pendingRich(row)
        val opt = row.selectedOption
        val tags = Id3Writer.resolveRich(
            title = row.title,
            artist = row.artist,
            album = row.album,
            year = row.year?.ifBlank { null } ?: opt?.year?.ifBlank { null } ?: extra?.year,
            genre = row.genre?.ifBlank { null } ?: opt?.genre?.ifBlank { null } ?: extra?.genre,
            trackNumber = row.trackNumber?.takeIf { it > 0 } ?: opt?.trackNumber?.takeIf { it > 0 } ?: extra?.trackNumber,
            trackCount = row.trackCount?.takeIf { it > 0 } ?: opt?.trackCount?.takeIf { it > 0 } ?: extra?.trackCount,
            discNumber = opt?.discNumber?.takeIf { it > 0 } ?: extra?.discNumber,
            discCount = opt?.discCount?.takeIf { it > 0 } ?: extra?.discCount
        )
        try {
            Id3Writer.write(
                tmp,
                tags.copy(coverBytes = coverBytes, coverMime = coverMime),
                embedCoverOnlyIfAbsent = true
            )
        } catch (_: Exception) { /* tag failure never fails the row */ }
    }

    private data class RichExtra(
        val year: String?, val genre: String?,
        val trackNumber: Int?, val trackCount: Int?,
        val discNumber: Int?, val discCount: Int?
    )

    private val richExtras = mutableMapOf<String, RichExtra>()

    fun stashRich(rowId: String, option: ibytsync.core.metadata.ReleaseOption) {
        richExtras[rowId] = RichExtra(option.year, option.genre,
            option.trackNumber, option.trackCount, option.discNumber, option.discCount)
    }

    private fun pendingRich(row: QueueRow): RichExtra? = richExtras[row.id]

    private fun saveToDisk(tmp: File): FolderStore.SaveOutcome {
        val saver = saveHooks ?: return FolderStore.SaveOutcome.Failed("no save handler")
        return try {
            saver.saveFile(tmp)
        } catch (e: Exception) {
            FolderStore.SaveOutcome.Failed(e.javaClass.simpleName)
        }
    }
}

class ProgressThrottle(
    private val minIntervalMs: Long = 160L,
    private val minDelta: Float = 0.01f,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sink: (QueueRow) -> Unit
) {
    private var lastEmitMs: Long = 0L
    private var lastProgress: Float = -1f
    private var pending: QueueRow? = null

    fun emit(row: QueueRow, status: RowStatus, progress: Float, detail: String, force: Boolean = false): QueueRow {
        val next = row.copy(status = status, progress = progress, detail = detail)
        if (force) {
            lastEmitMs = clock()
            lastProgress = progress
            pending = null
            sink(next)
            return next
        }
        val now = clock()
        val delta = if (lastProgress < 0f) Float.MAX_VALUE else progress - lastProgress
        if (now - lastEmitMs >= minIntervalMs && delta >= minDelta) {
            lastEmitMs = now
            lastProgress = progress
            pending = null
            sink(next)
        } else {
            pending = next
        }
        return next
    }

    fun flush(row: QueueRow, status: RowStatus, progress: Float, detail: String) {
        val next = row.copy(status = status, progress = progress, detail = detail)
        lastEmitMs = clock()
        lastProgress = progress
        pending = null
        sink(next)
    }

    fun flushQuietly() {
        val p = pending ?: return
        pending = null
        lastEmitMs = clock()
        lastProgress = p.progress ?: lastProgress
        try { sink(p) } catch (_: Exception) {}
    }
}
