package ibytsync.core.pipeline

import ibytsync.core.metadata.ReleaseOption
import ibytsync.core.metadata.SpotifyAlbumMetadata
import ibytsync.core.metadata.SpotifyTrackMetadata
import ibytsync.core.metadata.LocalMetadata
import ibytsync.core.metadata.UrlKind
import ibytsync.core.metadata.YtSuggestion
import java.util.Locale

/**
 * Queue row-state machine for the metadata phase.
 *
 * Download/save/upload reuse [RowStatus] and add
 * their own transitions; the gate rule below already anticipates them.
 *
 * Invariants:
 * - Only metadata failures invalidate a row: [FAILURE_STATUSES] contains
 *   solely [RowStatus.FAILED_METADATA].
 * - Start is enabled iff the batch is non-empty and every row is
 *   [RowStatus.METADATA_READY].
 * - Scrape failures never guess: null metadata -> [RowStatus.FAILED_METADATA]
 *   with retry/remove; invalid URLs skip immediately with no fetch.
 * - Per-row failures never touch sibling rows (all transitions here are
 *   single-row pure functions).
 *
 * Pure Kotlin, no Android dependencies.
 */
enum class RowStatus(val label: String) {
    QUEUED("Queued"),
    FETCHING_METADATA("Matching"),
    METADATA_READY("Ready"),
    FAILED_METADATA("Failed — matching"),
    AWAITING_ACCEPT("Needs review"),
    SKIPPED_BAD_URL("Bad link"),
    DOWNLOADING("Downloading"),
    FAILED_DOWNLOAD("Failed — download"),
    RETUNING("Processing"),
    AWAITING_FOLDER("Choose a folder"),
    SAVING("Saving"),
    UPLOADING("Uploading"),
    DONE("Synced"),
    FAILED("Failed"),
    SKIPPED_FOLDER("Skipped"),
    FAILED_SAVE("Failed — save"),
    ALREADY_UPLOADED("Duplicate"),
    FAILED_UPLOAD("Failed — upload"),
    CANCELLED_UPLOAD("Cancelled");

    companion object {
        fun fromLabel(label: String): RowStatus? = values().firstOrNull { it.label == label }
    }
}

enum class AudioSourcePreference(val label: String) {
    CLEAN_STUDIO("Album Audio"),
    ORIGINAL_VIDEO("Video Audio")
}

enum class AudioFormatChoice(val label: String, val extension: String, val approxBitrateKbps: Int) {
    MP3_192K("MP3 192k", "mp3", 192),
    MP3_320K("MP3 320k", "mp3", 320),
    M4A_AAC("M4A / AAC", "m4a", 160),
    OPUS_NATIVE("OPUS", "opus", 160);

    val ytAudioQuality: String
        get() = when (this) {
            MP3_192K -> "192K"
            MP3_320K -> "320K"
            M4A_AAC -> "256K"
            OPUS_NATIVE -> "0"
        }

    val ytAudioFormat: String
        get() = extension

    companion object {
        fun fromLabel(label: String): AudioFormatChoice =
            values().firstOrNull { it.name.equals(label, true) || it.label.equals(label, true) } ?: MP3_192K
    }
}

data class DuplicateMatch(
    val isDuplicate: Boolean,
    val matchedTitle: String,
    val matchedArtist: String,
    val durationSec: Int,
    val isExactDuration: Boolean = true
)

data class QueueRow(
    val id: String,
    val sourceInput: String,
    val status: RowStatus = RowStatus.QUEUED,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val audioPreference: AudioSourcePreference = AudioSourcePreference.ORIGINAL_VIDEO,
    val options: List<ReleaseOption> = emptyList(),
    val selectedOption: ReleaseOption? = null,
    val detail: String = "",
    val progress: Float? = null,
    val year: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val duplicateMatch: DuplicateMatch? = null,
    val forceUpload: Boolean = false,
    val targetPlaylistId: String? = null,
    val targetPlaylistName: String? = null,
    val targetPlaylistIds: List<String> = emptyList(),
    val targetPlaylistNames: List<String> = emptyList(),
    val audioFormat: AudioFormatChoice? = null,
    val batchId: String? = null,
    val ibroadcastTrackId: String? = null,
    val replacesUploadedTrackId: String? = null,
    val isEditingInStudio: Boolean = false,
    val isUpdated: Boolean = false,
    val savedLocalUri: String? = null,
    /**
     * A confirmed YouTube Music album track for this row's song, when one exists and runs a
     * meaningfully different length to the source video.
     *
     * Only populated after the lookup actually confirms an official-audio entry, so offering to
     * switch is a promise we can keep. Null means "nothing confirmed", never "not yet checked".
     */
    val officialAudioId: String? = null,
    val officialAudioDurationMs: Long? = null
)

fun QueueRow.effectivePlaylistIds(): List<String> {
    if (targetPlaylistIds.isNotEmpty()) return targetPlaylistIds.filter { it.isNotBlank() && it != "lib_only" }
    return listOfNotNull(targetPlaylistId?.takeIf { it.isNotBlank() && it != "lib_only" })
}

fun QueueRow.effectivePlaylistNames(): List<String> {
    if (targetPlaylistNames.isNotEmpty()) return targetPlaylistNames.filter { it.isNotBlank() && it != "Library Only" }
    return listOfNotNull(targetPlaylistName?.takeIf { it.isNotBlank() && it != "Library Only" })
}

/**
 * Length of the released master, never the source video.
 *
 * A music video carries footage the album track does not — end credits run it a few seconds
 * long — so anything that aims at the video's own length treats that padded video as the
 * better match. Used to aim an Album Audio search and to quote its file estimate.
 *
 * Null when no store release with a known length is on the row, so callers fall back.
 */
fun QueueRow.albumReleaseDurationMs(): Long? {
    val picked = selectedOption
    if (picked != null && !picked.isNoMetadata && !picked.isOriginalSource) {
        picked.durationMs?.takeIf { it > 0L }?.let { return it }
    }
    return options
        .firstOrNull { !it.isNoMetadata && !it.isOriginalSource && (it.durationMs ?: 0L) > 0L }
        ?.durationMs
}

/**
 * Length of the file the row's own source supplies: the YouTube video it points at, or the
 * local file that was added. This is what Video Audio actually downloads.
 *
 * Reachable even after an album release has been picked and the row's own [QueueRow.durationMs]
 * has been rewritten to the album's length. Null when unknown — a Spotify link has no video
 * until a search picks one at run time.
 */
fun QueueRow.sourceFileDurationMs(): Long? {
    options.firstOrNull { it.isOriginalSource && (it.durationMs ?: 0L) > 0L }?.durationMs?.let { return it }
    val picked = selectedOption
    if (picked != null && picked.isOriginalSource) {
        picked.durationMs?.takeIf { it > 0L }?.let { return it }
    }
    return null
}

object RowStates {

    /**
     * The only statuses that flip a row valid -> invalid.
     * Download/save failures mark their own failed states but never
     * invalidate the row's metadata.
     */
    val FAILURE_STATUSES: Set<RowStatus> = setOf(RowStatus.FAILED_METADATA)

    /** Start gate: non-empty batch where every row is metadata-ready. */
    fun canStart(statuses: List<RowStatus>): Boolean =
        statuses.isNotEmpty() && statuses.all { it == RowStatus.METADATA_READY }

    fun canStartRows(rows: List<QueueRow>): Boolean = canStart(rows.map { it.status })

    /** Retry is offered for metadata + download/save/upload failures; remove is always offered. */
    fun canRetry(status: RowStatus): Boolean = status == RowStatus.FAILED_METADATA ||
        status == RowStatus.FAILED_DOWNLOAD ||
        status == RowStatus.FAILED_SAVE ||
        status == RowStatus.FAILED_UPLOAD ||
        status == RowStatus.FAILED

    fun statusAfterRetry(status: RowStatus): RowStatus =
        if (status == RowStatus.FAILED_METADATA) RowStatus.FETCHING_METADATA
        else if (canRetry(status)) RowStatus.METADATA_READY
        else status

    /**
     * Classify a pasted line with no network: invalid URLs skip immediately,
     * everything else moves to fetching. Never throws.
     */
    fun statusForClassifiedInput(kind: UrlKind): RowStatus =
        if (kind is UrlKind.Invalid) RowStatus.SKIPPED_BAD_URL else RowStatus.FETCHING_METADATA

    /**
     * Single-row transition after a Spotify track fetch.
     * Null [metadata] -> Failed — metadata (never guessed). Never throws.
     */
    fun QueueRow.afterSpotifyTrackFetch(metadata: SpotifyTrackMetadata?): QueueRow {
        return try {
            if (metadata == null) {
                copy(status = RowStatus.FAILED_METADATA, detail = "Scrape failed")
            } else {
                copy(
                    status = RowStatus.METADATA_READY,
                    title = metadata.trackTitle,
                    artist = metadata.artist,
                    // Tag-write time falls back blank albums to the track title;
                    // keep the scraped value here so the fallback stays visible.
                    album = metadata.album.ifBlank { metadata.trackTitle },
                    durationMs = metadata.durationMs,
                    coverUrl = metadata.coverUrl,
                    detail = "Spotify tags & cover ready"
                )
            }
        } catch (_: Exception) {
            copy(status = RowStatus.FAILED_METADATA, detail = "Scrape error")
        }
    }

    /** Single-row transition after a Spotify album fetch. Null -> Failed — metadata. */
    fun QueueRow.afterSpotifyAlbumFetch(metadata: SpotifyAlbumMetadata?): QueueRow {
        return try {
            if (metadata == null) {
                copy(status = RowStatus.FAILED_METADATA, detail = "Scrape failed")
            } else {
                copy(
                    status = RowStatus.METADATA_READY,
                    title = metadata.album,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationMs = null,
                    coverUrl = null,
                    detail = "Spotify album ready"
                )
            }
        } catch (_: Exception) {
            copy(status = RowStatus.FAILED_METADATA, detail = "Scrape error")
        }
    }

    /** Single-row transition after a local-file tag read (always succeeds via fallback). */
    fun QueueRow.afterLocalTags(metadata: LocalMetadata): QueueRow {
        return try {
            copy(
                status = RowStatus.METADATA_READY,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album.ifBlank { metadata.title },
                durationMs = metadata.durationMs,
                detail = "Local file tags ready"
            )
        } catch (_: Exception) {
            copy(status = RowStatus.FAILED_METADATA, detail = "Tag read error")
        }
    }

    /** YouTube suggestion lands AWAITING_ACCEPT: nothing is trusted yet. Null extract fails. */
    fun QueueRow.afterYouTubeSuggest(suggestion: YtSuggestion?): QueueRow {
        return try {
            if (suggestion == null) {
                copy(status = RowStatus.FAILED_METADATA, detail = "Scrape failed")
            } else {
                val topOpt = suggestion.options.firstOrNull()
                copy(
                    status = RowStatus.AWAITING_ACCEPT,
                    title = suggestion.titleGuess,
                    artist = suggestion.artistGuess,
                    album = topOpt?.album ?: "",
                    durationMs = suggestion.durationMs,
                    coverUrl = topOpt?.artworkUrl ?: suggestion.acceptedCover(),
                    options = suggestion.options,
                    selectedOption = topOpt,
                    year = topOpt?.year,
                    genre = topOpt?.genre,
                    trackNumber = topOpt?.trackNumber,
                    trackCount = topOpt?.trackCount,
                    detail = if (suggestion.options.isNotEmpty()) "Select official release" else "Direct audio ready"
                )
            }
        } catch (_: Exception) {
            copy(status = RowStatus.FAILED_METADATA, detail = "Scrape error")
        }
    }

    /**
     * Explicit user decision on a YouTube suggestion. Accepting stamps the
     * accepted (corrected-or-guess) values and unlocks the Start gate;
     * rejecting marks failed-metadata with retry/remove. Only valid from
     * AWAITING_ACCEPT; any other status is returned untouched.
     */
    fun QueueRow.afterYouTubeDecision(suggestion: YtSuggestion, accept: Boolean): QueueRow {
        return try {
            if (status != RowStatus.AWAITING_ACCEPT) return this
            if (!accept) return copy(status = RowStatus.FAILED_METADATA, detail = "Rejected")
            val top = suggestion.options.firstOrNull()
            copy(
                status = RowStatus.METADATA_READY,
                title = suggestion.acceptedTitle(),
                artist = suggestion.acceptedArtist(),
                album = suggestion.acceptedAlbum().ifBlank { suggestion.acceptedTitle() },
                durationMs = suggestion.durationMs,
                coverUrl = suggestion.acceptedCover(),
                options = suggestion.options,
                selectedOption = top,
                year = top?.year,
                genre = top?.genre,
                trackNumber = top?.trackNumber,
                trackCount = top?.trackCount,
                detail = "${suggestion.acceptedAlbum().ifBlank { suggestion.acceptedTitle() }} • ${audioPreference.label}"
            )
        } catch (_: Exception) {
            copy(status = RowStatus.FAILED_METADATA)
        }
    }

    /** Accept/reject is offered only while a suggestion awaits the user's call. */
    fun canDecide(status: RowStatus): Boolean = status == RowStatus.AWAITING_ACCEPT

    /**
     * Pick one release option by index. Out-of-range counts as reject
     * (failed-metadata with retry/remove); off-path statuses untouched.
     */
    fun QueueRow.afterYouTubePick(
        suggestion: YtSuggestion,
        index: Int,
        audioPreference: AudioSourcePreference = AudioSourcePreference.ORIGINAL_VIDEO
    ): QueueRow {
        return try {
            if (status != RowStatus.AWAITING_ACCEPT) return this
            val opt = suggestion.pick(index) ?: return copy(status = RowStatus.FAILED_METADATA)
            copy(
                status = RowStatus.METADATA_READY,
                title = opt.trackTitle,
                artist = opt.artist,
                album = opt.album.ifBlank { opt.trackTitle },
                durationMs = opt.durationMs ?: suggestion.durationMs,
                coverUrl = opt.artworkUrl,
                audioPreference = audioPreference,
                selectedOption = opt,
                year = opt.year,
                genre = opt.genre,
                trackNumber = opt.trackNumber,
                trackCount = opt.trackCount,
                detail = "${opt.album} • ${audioPreference.label}"
            )
        } catch (_: Exception) {
            copy(status = RowStatus.FAILED_METADATA)
        }
    }

    fun QueueRow.withManualArtwork(newCoverUri: String): QueueRow {
        val updatedOpt = (selectedOption ?: ReleaseOption(
            artist = artist,
            trackTitle = title,
            album = album,
            artworkUrl = newCoverUri,
            durationMs = durationMs,
            isrc = null,
            sources = listOf("custom"),
            score = 100,
            year = year,
            genre = genre,
            trackNumber = trackNumber,
            trackCount = trackCount
        )).copy(artworkUrl = newCoverUri)
        return copy(
            coverUrl = newCoverUri,
            selectedOption = updatedOpt,
            detail = if (detail.contains("Custom Edited")) detail else "Custom Artwork • $detail"
        )
    }

    fun QueueRow.withManualMetadata(opt: ReleaseOption): QueueRow {
        return copy(
            title = opt.trackTitle,
            artist = opt.artist,
            album = opt.album.ifBlank { opt.trackTitle },
            coverUrl = opt.artworkUrl ?: coverUrl,
            selectedOption = opt,
            year = opt.year,
            genre = opt.genre,
            trackNumber = opt.trackNumber,
            trackCount = opt.trackCount,
            detail = "Custom Edited • ${opt.album}"
        )
    }

    /** Terminal states end the row's journey through the batch. */
    fun isTerminal(status: RowStatus): Boolean = when (status) {
        RowStatus.DONE,
        RowStatus.FAILED,
        RowStatus.FAILED_METADATA,
        RowStatus.FAILED_DOWNLOAD,
        RowStatus.FAILED_SAVE,
        RowStatus.FAILED_UPLOAD,
        RowStatus.SKIPPED_BAD_URL,
        RowStatus.SKIPPED_FOLDER,
        RowStatus.ALREADY_UPLOADED,
        RowStatus.CANCELLED_UPLOAD -> true
        else -> false
    }
}

object ProgressPhases {
    const val DOWNLOAD_START = 0.05f
    const val DOWNLOAD_END = 0.65f
    const val PROCESSING_HOLD = 0.65f
    const val PROCESSING_END = 0.70f
    const val SAVE_START = 0.70f
    const val SAVE_END = 0.75f
    const val UPLOAD_START = 0.75f
    const val UPLOAD_START_NO_SAVE = 0.70f
    const val UPLOAD_END = 0.95f
    const val FINALIZE_HOLD = 0.95f
    const val DONE = 1.0f

    private val PAT_TOTAL = Regex("""of\s+(~)?\s*([\d.]+)\s*([kKmMgGtT]i?B)""")
    private val PAT_DOWNLOADED = Regex("""\[download\]\s+([\d.]+)\s*([kKmMgGtT]i?B)\s+at""")

    private fun parseUnitToMb(value: Double, unit: String): Double {
        val u = unit.uppercase(Locale.US)
        return when {
            u.contains("G") -> value * 1024.0
            u.contains("M") -> value
            u.contains("K") -> value / 1024.0
            else -> value / (1024.0 * 1024.0)
        }
    }

    fun formatDownloadDetail(progressPercent: Float, line: String? = null, durationMs: Long? = null): String {
        val pctInt = Math.round(progressPercent).toInt().coerceIn(0, 100)
        if (!line.isNullOrBlank()) {
            val totalMatch = PAT_TOTAL.find(line)
            if (totalMatch != null) {
                val isApprox = totalMatch.groupValues[1] == "~"
                val valNum = totalMatch.groupValues[2].toDoubleOrNull()
                val unit = totalMatch.groupValues[3]
                if (valNum != null) {
                    val totalMb = parseUnitToMb(valNum, unit)
                    val dlMb = totalMb * (progressPercent.toDouble().coerceIn(0.0, 100.0) / 100.0)
                    val approxSym = if (isApprox) "~" else ""
                    return String.format(Locale.US, "%.1f / %s%.1f MB (%d%%)", dlMb, approxSym, totalMb, pctInt)
                }
            }

            val dlMatch = PAT_DOWNLOADED.find(line)
            if (dlMatch != null) {
                val valNum = dlMatch.groupValues[1].toDoubleOrNull()
                val unit = dlMatch.groupValues[2]
                if (valNum != null) {
                    val dlMb = parseUnitToMb(valNum, unit)
                    if (progressPercent > 0f) {
                        val totalMb = dlMb / (progressPercent.toDouble() / 100.0)
                        return String.format(Locale.US, "%.1f / %.1f MB (%d%%)", dlMb, totalMb, pctInt)
                    } else if (durationMs != null && durationMs > 0L) {
                        val totalMb = (durationMs / 1000.0) * (160_000.0 / 8.0) / (1024.0 * 1024.0)
                        return String.format(Locale.US, "%.1f / ~%.1f MB (%d%%)", dlMb, totalMb, pctInt)
                    } else {
                        return String.format(Locale.US, "%.1f MB (%d%%)", dlMb, pctInt)
                    }
                }
            }
        }

        if (durationMs != null && durationMs > 0L) {
            val totalMb = (durationMs / 1000.0) * (160_000.0 / 8.0) / (1024.0 * 1024.0)
            val dlMb = totalMb * (progressPercent.toDouble().coerceIn(0.0, 100.0) / 100.0)
            return String.format(Locale.US, "%.1f / ~%.1f MB (%d%%)", dlMb, totalMb, pctInt)
        }

        return "$pctInt%"
    }

    fun formatUploadDetail(sent: Long, total: Long): String {
        if (total <= 0L) return "0%"
        val totalMb = total.toDouble() / (1024.0 * 1024.0)
        val sentMb = (sent.toDouble() / (1024.0 * 1024.0)).coerceAtMost(totalMb)
        val pct = Math.round((sent.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        return String.format(Locale.US, "%.1f / %.1f MB (%d%%)", sentMb, totalMb, pct)
    }

    fun downloadMapped(ytPct01: Float): Float =
        (DOWNLOAD_START + ytPct01.coerceIn(0f, 1f) * (DOWNLOAD_END - DOWNLOAD_START))
            .coerceIn(DOWNLOAD_START, DOWNLOAD_END)

    fun uploadMapped(sent: Long, total: Long, floor: Float = UPLOAD_START): Float {
        if (total <= 0L) return floor
        val frac = (sent.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        return (floor + frac * (UPLOAD_END - floor)).coerceIn(floor, UPLOAD_END)
    }
}

object PhaseDetails {
    const val DOWNLOADING = "Downloading…"
    const val PROCESSING = "Processing audio…"
    const val SAVING = "Saving…"
    const val UPLOADING = "Uploading…"
    const val FINALIZING = "Finalizing…"
    const val SYNCED = "Synced"
    const val SKIPPED_LIBRARY = "Skipped (Already in Library)"
    const val SKIPPED_IDENTICAL = "Skipped (Identical File in Library)"
    const val DOWNLOAD_FAILED = "Download failed"
    const val UPLOAD_FAILED = "Upload failed"
    const val SAVE_FAILED = "Save failed"
    const val CANCELLED = "Cancelled"
    const val INTERRUPTED = "Interrupted — ready to retry"
    const val READY_RETRY = "Ready to retry"
    const val FOLDER_EXPIRED = "Folder access expired — reselect"
    const val RESYNC_TAGS_CHANGED = "Tags/art changed — re-upload needed"

    val TRANSIENT_STATUSES: Set<RowStatus> = setOf(
        RowStatus.DOWNLOADING,
        RowStatus.RETUNING,
        RowStatus.SAVING,
        RowStatus.UPLOADING,
        RowStatus.AWAITING_FOLDER
    )
}

enum class ResyncChange {
    IDENTICAL,
    TAGS_CHANGED,
    SOURCE_CHANGED
}

object ResyncDiff {
    fun changeKind(staged: QueueRow, synced: QueueRow): ResyncChange {
        val sourceChanged = staged.sourceInput != synced.sourceInput ||
            staged.durationMs != synced.durationMs ||
            staged.audioFormat != synced.audioFormat
        if (sourceChanged) return ResyncChange.SOURCE_CHANGED
        val tagsChanged = staged.title != synced.title ||
            staged.artist != synced.artist ||
            staged.album != synced.album ||
            staged.coverUrl != synced.coverUrl ||
            staged.year != synced.year ||
            staged.genre != synced.genre ||
            staged.trackNumber != synced.trackNumber ||
            staged.trackCount != synced.trackCount
        return if (tagsChanged) ResyncChange.TAGS_CHANGED else ResyncChange.IDENTICAL
    }
}
