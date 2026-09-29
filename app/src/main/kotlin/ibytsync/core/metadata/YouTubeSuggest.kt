package ibytsync.core.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class YtExtract(
    val videoTitle: String,
    val channel: String,
    val durationMs: Long?
)

object YtTitleSplitter {
    private val FEAT = Regex("\\s*[\\(\\[]?\\s*(ft\\.?|feat\\.?|featuring)\\s+[^\\]\\)]+[\\]\\)]?", RegexOption.IGNORE_CASE)
    private val JUNK = Regex("official\\s+(music\\s+)?video|lyric(s)?|karaoke|live|remix|sped\\s*up|nightcore|slowed|reverb|8d|audio|visualizer", RegexOption.IGNORE_CASE)
    private val BOILERPLATE = Regex(
        "(?i)\\s*[\\[\\(]?(?:HD|4K|HQ|Official Video|Official Audio|Official Music Video|Title Sequence|Theme Song|Opening Theme|Opening Credits|Full OST|Soundtrack)[\\]\\)]?\\s*"
    )
    private val NOISE_SEGMENT = Regex(
        "(?i)^(?:Netflix|HBO|Disney\\+|VEVO|Title Sequence|Theme Song|Opening Theme|Opening Credits|Official Video|HD|4K|HQ)$"
    )

    fun split(rawTitle: String, channel: String): Pair<String, String> {
        val withoutFeat = FEAT.replace(rawTitle, "").trim().trim('-', '–', '—', ':', '|').trim()
        val dash = Regex("\\s+[–—-]\\s+").find(withoutFeat)
        if (dash != null) {
            val left = withoutFeat.substring(0, dash.range.first).trim()
            val right = withoutFeat.substring(dash.range.last + 1).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) return left to right
        }

        // Delimiter split on | or • or /: e.g. "Stranger Things | Title Sequence [HD] | Netflix"
        val pipeParts = withoutFeat.split(Regex("\\s*[|•/]\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (pipeParts.size > 1) {
            val meaningful = pipeParts.filter { !NOISE_SEGMENT.matches(it) && !looksPureBoilerplate(it) }
            if (meaningful.isNotEmpty()) {
                val cand = meaningful.first()
                val byChannel = channel.trim().removeSuffix(" - Topic").trim()
                if (byChannel.isNotEmpty() && !NOISE_SEGMENT.matches(byChannel) && !byChannel.equals(cand, ignoreCase = true)) {
                    return byChannel to cand
                }
                return "" to cand
            }
        }

        val byChannel = channel.trim().removeSuffix(" - Topic").trim()
        if (byChannel.isNotEmpty() && !NOISE_SEGMENT.matches(byChannel) && !byChannel.equals(withoutFeat, ignoreCase = true)) {
            return byChannel to withoutFeat
        }
        return "" to withoutFeat
    }

    private fun looksPureBoilerplate(s: String): Boolean {
        val stripped = BOILERPLATE.replace(s, "").trim()
        return stripped.isEmpty() || NOISE_SEGMENT.matches(stripped)
    }

    fun looksJunk(rawTitle: String): Boolean = JUNK.containsMatchIn(rawTitle)
}

data class CorrectionCandidate(
    val artist: String,
    val trackTitle: String,
    val album: String,
    val artworkUrl: String?,
    val source: String,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val discNumber: Int? = null,
    val discCount: Int? = null
)

fun interface CorrectionFetcher {
    fun fetch(url: String): String?
}

interface CorrectionClient {
    fun candidates(artistGuess: String, titleGuess: String): List<CorrectionCandidate>
}

fun CorrectionClient.suggest(
    artistGuess: String,
    titleGuess: String,
    videoDurationMs: Long? = null
): CorrectionCandidate? {
    val query = guessQuery(artistGuess, titleGuess)
    if (query.isBlank()) return null
    return try {
        candidates(artistGuess, titleGuess)
            .map {
                it to ITunesCorrection.comatchScore(
                    query, it.artist, it.trackTitle, it.durationMs, videoDurationMs, it.album
                )
            }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }?.first
    } catch (_: Exception) {
        null
    }
}

private const val MIN_SCORE = 60
private const val MAX_OPTIONS = 5
private const val CLOSE_MS = 5_000L
private const val NEAR_MS = 18_000L
private const val FAR_MS = 30_000L

private val correctionJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun encode(q: String): String = URLEncoder.encode(q, "UTF-8")

private fun guessQuery(artistGuess: String, titleGuess: String): String =
    listOf(artistGuess, titleGuess).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

private fun pickArtwork(raw: String?): String? {
    val u = raw?.trim().orEmpty()
    if (u.isEmpty()) return null
    return if ("100x100" in u) u.replace("100x100", "600x600") else u
}

class ITunesCorrection(
    private val fetcher: CorrectionFetcher,
    private val baseUrl: String = "https://itunes.apple.com"
) : CorrectionClient {
    override fun candidates(artistGuess: String, titleGuess: String): List<CorrectionCandidate> {
        val query = guessQuery(artistGuess, titleGuess)
        if (query.isBlank()) return emptyList()
        return try {
            val url = "${baseUrl.trimEnd('/')}/search?term=${encode(query)}&media=music&entity=song&limit=5"
            val body = fetcher.fetch(url) ?: return emptyList()
            parseCandidates(body).map { it.copy(source = "itunes") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        fun parseCandidates(body: String): List<CorrectionCandidate> {
            return try {
                val root = correctionJson.parseToJsonElement(body).jsonObject
                val results = root["results"]?.jsonArray ?: return emptyList()
                results.mapNotNull { el ->
                    try {
                        val obj = el.jsonObject
                        val artist = obj["artistName"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val track = obj["trackName"]?.jsonPrimitive?.content?.trim().orEmpty()
                        if (artist.isEmpty() || track.isEmpty()) return@mapNotNull null
                        val album = obj["collectionName"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val art = pickArtwork(obj["artworkUrl100"]?.jsonPrimitive?.content)
                        val dur = obj["trackTimeMillis"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
                        val year = obj["releaseDate"]?.jsonPrimitive?.content?.trim()?.take(4)
                            ?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } }
                        val genre = obj["primaryGenreName"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                        val trackNo = obj["trackNumber"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                        val trackCt = obj["trackCount"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                        val discNo = obj["discNumber"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                        val discCt = obj["discCount"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                        CorrectionCandidate(artist, track, album.ifBlank { track }, art, "itunes", dur, null,
                            year, genre, trackNo, trackCt, discNo, discCt)
                    } catch (_: Exception) {
                        null
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun comatchScore(
            query: String,
            artist: String,
            track: String,
            candidateDurationMs: Long? = null,
            videoDurationMs: Long? = null,
            album: String? = null
        ): Int {
            val q = query.lowercase().trim()
            var score = 0
            val artistL = artist.lowercase().trim()
            val trackL = track.lowercase().trim()
            val albumL = album?.lowercase()?.trim().orEmpty()

            val artistMatches = artistL.isNotEmpty() && (
                q.contains(artistL) ||
                artistL.split(Regex("[,&]|\\bfeat\\.?\\b", RegexOption.IGNORE_CASE))
                    .any { it.trim().length > 2 && q.contains(it.trim()) }
            )
            if (artistMatches) score += 50

            val trackMatches = trackL.isNotEmpty() && (
                q.contains(trackL) ||
                trackL.split(Regex("[-–—:]")).any { it.trim().length > 2 && q.contains(it.trim()) }
            )
            if (trackMatches) score += 50

            // Exact track title match bonus: exact title query match outranks loose substring / split matches
            val exactTrackMatch = trackL.isNotEmpty() && (trackL == q || q == "$artistL $trackL" || q == "$trackL $artistL")
            if (exactTrackMatch) {
                score += 15
            }

            if (albumL.isNotEmpty()) {
                val isSoundtrack = albumL.contains("soundtrack") || albumL.contains("ost") ||
                    albumL.contains("original score") || albumL.contains("theme") || albumL.contains("music from") ||
                    albumL.contains("original series")
                val albumMatchesQuery = (q.length > 3 && albumL.contains(q)) || (albumL.length > 3 && q.contains(albumL))
                val soundtrackTrackMatch = isSoundtrack && trackL.isNotEmpty() && albumL.contains(trackL) &&
                    (q.contains("theme") || q.contains("soundtrack") || q.contains("ost") || albumMatchesQuery)

                if (albumMatchesQuery || soundtrackTrackMatch) {
                    score += 30
                }
            }

            // Cover/Tribute/Collective penalty: original artist releases must outrank cover bands
            val isCoverOrTribute = artistL.contains("tribute") || artistL.contains("collective") ||
                artistL.contains("orchestra") || artistL.contains("karaoke") ||
                trackL.contains("cover") || trackL.contains("tribute") ||
                albumL.contains("tribute")
            if (isCoverOrTribute) {
                score -= 25
            }

            if (score == 0) {
                val qTokens = q.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
                val cTokens = "$artist $track $albumL".lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
                if (qTokens.isEmpty() || cTokens.isEmpty()) return 0
                val overlap = qTokens.intersect(cTokens).size
                score = (overlap * 100) / maxOf(qTokens.size, cTokens.size)
            }
            return score + durationPenalty(candidateDurationMs, videoDurationMs)
        }

        fun durationPenalty(candidateMs: Long?, videoMs: Long?): Int {
            if (candidateMs == null || candidateMs <= 0 || videoMs == null || videoMs <= 0) return 0
            val diff = kotlin.math.abs(candidateMs - videoMs)
            return when {
                diff <= CLOSE_MS -> 0
                diff <= NEAR_MS -> -20
                diff <= FAR_MS -> -40
                else -> -1000
            }
        }

        fun okHttpFetcher(client: OkHttpClient? = null): CorrectionFetcher {
            val http = client ?: ibytsync.core.network.SharedHttpClient.instance.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
            return CorrectionFetcher { url ->
                try {
                    val req = Request.Builder().url(url).header("User-Agent", SpotifyScraper.USER_AGENT).get().build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@CorrectionFetcher null
                        resp.body?.string()?.takeIf { it.isNotBlank() }
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}

class DeezerCorrection(
    private val fetcher: CorrectionFetcher,
    private val baseUrl: String = "https://api.deezer.com"
) : CorrectionClient {
    override fun candidates(artistGuess: String, titleGuess: String): List<CorrectionCandidate> {
        val query = guessQuery(artistGuess, titleGuess)
        if (query.isBlank()) return emptyList()
        return try {
            val url = "${baseUrl.trimEnd('/')}/search?q=${encode(query)}&limit=5"
            val body = fetcher.fetch(url) ?: return emptyList()
            parseCandidates(body).map { it.copy(source = "deezer") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        fun parseCandidates(body: String): List<CorrectionCandidate> {
            return try {
                val root = correctionJson.parseToJsonElement(body).jsonObject
                if (root.containsKey("error")) return emptyList()
                val results = root["data"]?.jsonArray ?: return emptyList()
                results.mapNotNull { el ->
                    try {
                        val obj = el.jsonObject
                        val track = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val artist = obj["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content?.trim().orEmpty()
                        if (artist.isEmpty() || track.isEmpty()) return@mapNotNull null
                        val album = obj["album"]?.jsonObject?.get("title")?.jsonPrimitive?.content?.trim().orEmpty()
                        val art = obj["album"]?.jsonObject?.get("cover_xl")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                            ?: obj["album"]?.jsonObject?.get("cover_big")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                            ?: obj["album"]?.jsonObject?.get("cover")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                        val durSec = obj["duration"]?.jsonPrimitive?.doubleOrNull
                            ?: obj["duration"]?.jsonPrimitive?.longOrNull?.toDouble()
                            ?: obj["duration"]?.jsonPrimitive?.intOrNull?.toDouble()
                        val durMs = durSec?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
                        val isrc = obj["isrc"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                        val year = obj["release_date"]?.jsonPrimitive?.content?.trim()?.take(4)
                            ?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } }
                        CorrectionCandidate(artist, track, album.ifBlank { track }, art, "deezer", durMs, isrc, year)
                    } catch (_: Exception) {
                        null
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun okHttpFetcher(client: OkHttpClient? = null): CorrectionFetcher =
            CorrectionFetcher { url -> ITunesCorrection.okHttpFetcher(client).fetch(url) }
    }
}

object YtExtractParser {
    fun parseDumpJson(dumpJson: String): YtExtract? {
        return try {
            val obj = correctionJson.parseToJsonElement(dumpJson).jsonObject
            val title = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (title.isEmpty()) return null
            val channel = obj["channel"]?.jsonPrimitive?.content?.trim().orEmpty()
                .ifEmpty { obj["uploader"]?.jsonPrimitive?.content?.trim().orEmpty() }
            val durSec = obj["duration"]?.jsonPrimitive?.doubleOrNull
                ?: obj["duration"]?.jsonPrimitive?.longOrNull?.toDouble()
                ?: obj["duration"]?.jsonPrimitive?.intOrNull?.toDouble()
            YtExtract(title, channel, durSec?.takeIf { it > 0 }?.let { (it * 1000).toLong() })
        } catch (_: Exception) {
            null
        }
    }
}

data class ReleaseOption(
    val artist: String,
    val trackTitle: String,
    val album: String,
    val artworkUrl: String?,
    val durationMs: Long?,
    val isrc: String?,
    val sources: List<String>,
    val score: Int,
    val year: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val discNumber: Int? = null,
    val discCount: Int? = null
) {
    val isNoMetadata: Boolean
        get() = sources.contains("no_metadata") || sources.contains("none")

    val isOriginalSource: Boolean
        get() = sources.contains("original_file") || sources.contains("raw_video") ||
            sources.contains("original") || sources.contains("local_original")

    companion object {
        fun noMetadata(trackTitle: String, durationMs: Long? = null): ReleaseOption = ReleaseOption(
            artist = "",
            trackTitle = trackTitle,
            album = "",
            artworkUrl = null,
            durationMs = durationMs,
            isrc = null,
            sources = listOf("no_metadata"),
            score = 0
        )

        fun originalFile(
            title: String,
            artist: String,
            album: String,
            artworkUrl: String? = null,
            durationMs: Long? = null,
            year: String? = null,
            genre: String? = null,
            trackNumber: Int? = null,
            trackCount: Int? = null
        ): ReleaseOption = ReleaseOption(
            artist = artist,
            trackTitle = title,
            album = album.ifBlank { title },
            artworkUrl = artworkUrl,
            durationMs = durationMs,
            isrc = null,
            sources = listOf("original_file"),
            score = 99,
            year = year,
            genre = genre,
            trackNumber = trackNumber,
            trackCount = trackCount
        )

        fun rawVideo(
            videoTitle: String,
            channel: String,
            thumbnailUrl: String? = null,
            durationMs: Long? = null
        ): ReleaseOption = ReleaseOption(
            artist = channel.removeSuffix(" - Topic").trim(),
            trackTitle = videoTitle,
            album = "",
            artworkUrl = thumbnailUrl,
            durationMs = durationMs,
            isrc = null,
            sources = listOf("raw_video"),
            score = 80
        )
    }
}

fun groupReleases(scored: List<Pair<CorrectionCandidate, Int>>): List<ReleaseOption> {
    if (scored.isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<Pair<CorrectionCandidate, Int>>>()
    for (item in scored) {
        val cand = item.first
        val isrc = cand.isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val matchingGroup = groups.firstOrNull { group ->
            group.any { other ->
                val otherCand = other.first
                val otherIsrc = otherCand.isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                val isrcMatches = isrc != null && otherIsrc != null && isrc == otherIsrc

                val artistMatches = normArtist(cand.artist) == normArtist(otherCand.artist) ||
                    (cand.artist.isNotBlank() && otherCand.artist.isNotBlank() &&
                        (normArtist(cand.artist).contains(normArtist(otherCand.artist)) || normArtist(otherCand.artist).contains(normArtist(cand.artist))))
                val trackMatches = normTrack(cand.trackTitle) == normTrack(otherCand.trackTitle)
                val albumNorm1 = normAlbum(cand.album)
                val albumNorm2 = normAlbum(otherCand.album)
                val albumMatches = albumNorm1 == albumNorm2 ||
                    (albumNorm1.length > 5 && albumNorm2.length > 5 && (albumNorm1.contains(albumNorm2) || albumNorm2.contains(albumNorm1)))
                val durationMatches = cand.durationMs == null || otherCand.durationMs == null || kotlin.math.abs(cand.durationMs - otherCand.durationMs) <= 3000L

                isrcMatches || (artistMatches && trackMatches && albumMatches && durationMatches)
            }
        }

        if (matchingGroup != null) {
            matchingGroup.add(item)
        } else {
            groups.add(mutableListOf(item))
        }
    }

    return groups.map { members ->
        val (best, bestScore) = members.maxByOrNull { it.second }!!
        val distinctSources = members.map { it.first.source }.distinct()
        // Strong bonus for multi-provider consensus: +20 points for each additional concurring music service
        val consensusBonus = (distinctSources.size - 1) * 20
        val totalScore = bestScore + consensusBonus
        val xlArt = members.mapNotNull { it.first.artworkUrl }
            .maxByOrNull { artRank(it) }
        ReleaseOption(
            artist = best.artist,
            trackTitle = best.trackTitle,
            album = best.album,
            artworkUrl = xlArt ?: best.artworkUrl,
            durationMs = best.durationMs ?: members.mapNotNull { it.first.durationMs }.firstOrNull(),
            isrc = (best.isrc ?: members.mapNotNull { it.first.isrc }.firstOrNull())?.trim()?.takeIf { it.isNotEmpty() },
            sources = distinctSources,
            score = totalScore,
            year = best.year ?: members.mapNotNull { it.first.year }.firstOrNull(),
            genre = best.genre ?: members.mapNotNull { it.first.genre }.firstOrNull(),
            trackNumber = best.trackNumber ?: members.mapNotNull { it.first.trackNumber }.firstOrNull(),
            trackCount = best.trackCount ?: members.mapNotNull { it.first.trackCount }.firstOrNull(),
            discNumber = best.discNumber ?: members.mapNotNull { it.first.discNumber }.firstOrNull(),
            discCount = best.discCount ?: members.mapNotNull { it.first.discCount }.firstOrNull()
        )
    }.sortedByDescending { it.score }.take(MAX_OPTIONS)
}

private fun normTrack(t: String): String =
    t.trim().lowercase().replace(Regex("\\s*[\\(\\[][^\\]\\)]*[\\)\\]]"), "").replace(Regex("\\s+"), " ").trim()

private fun normArtist(a: String): String =
    a.trim().lowercase().replace(Regex("[,&]|\\bfeat\\.?\\b", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()

private fun normAlbum(a: String): String =
    a.trim().lowercase()
        .replace(Regex("\\s*\\([^)]*\\)"), "")
        .replace(Regex("\\s*\\[[^\\]]*\\]"), "")
        .replace(Regex("[,.'\"\\-–—]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun artRank(url: String): Int = when {
    "1000x1000" in url || "cover_xl" in url || "/xl." in url || "/xl-" in url ||
        url.lowercase().endsWith("/xl.jpg") || url.lowercase().endsWith("/xl.png") -> 4
    "0000b273" in url || "640x640" in url -> 3
    "600x600" in url -> 2
    "500x500" in url || "cover_big" in url -> 1
    else -> 0
}

data class YtSuggestion(
    val rawTitle: String,
    val rawChannel: String,
    val artistGuess: String,
    val titleGuess: String,
    val looksJunk: Boolean,
    val correction: CorrectionCandidate?,
    val options: List<ReleaseOption> = emptyList(),
    val durationMs: Long?
) {
    fun acceptedTitle(): String = correction?.trackTitle ?: titleGuess
    fun acceptedArtist(): String = correction?.artist ?: artistGuess
    fun acceptedAlbum(): String = correction?.album ?: titleGuess
    fun acceptedCover(): String? = correction?.artworkUrl
    fun correctedBy(): String? = correction?.source
    fun pick(index: Int): ReleaseOption? = options.getOrNull(index)
}

object YtSuggestionBuilder {
    fun build(
        extract: YtExtract,
        vararg clients: CorrectionClient
    ): YtSuggestion? {
        if (extract.videoTitle.isBlank()) return null
        return try {
            val (artistGuess, titleGuess) = YtTitleSplitter.split(extract.videoTitle, extract.channel)
            if (titleGuess.isBlank()) return null
            val query = guessQuery(artistGuess, titleGuess)
            val scored = mutableListOf<Pair<CorrectionCandidate, Int>>()
            for (client in clients) {
                val cands = try {
                    client.candidates(artistGuess, titleGuess)
                } catch (_: Exception) {
                    emptyList()
                }
                for (cand in cands) {
                    val score = try {
                        ITunesCorrection.comatchScore(
                            query, cand.artist, cand.trackTitle, cand.durationMs, extract.durationMs, cand.album
                        )
                    } catch (_: Exception) {
                        0
                    }
                    if (score >= MIN_SCORE) scored.add(cand to score)
                }
            }
            val options = groupReleases(scored)
            val top = options.firstOrNull()
            val correction = top?.let {
                CorrectionCandidate(
                    it.artist, it.trackTitle, it.album, it.artworkUrl,
                    it.sources.joinToString("+"), it.durationMs, it.isrc,
                    it.year, it.genre, it.trackNumber, it.trackCount, it.discNumber, it.discCount
                )
            }
            YtSuggestion(
                rawTitle = extract.videoTitle,
                rawChannel = extract.channel,
                artistGuess = artistGuess,
                titleGuess = titleGuess,
                looksJunk = YtTitleSplitter.looksJunk(extract.videoTitle),
                correction = correction,
                options = options,
                durationMs = extract.durationMs
            )
        } catch (_: Exception) {
            null
        }
    }
}
