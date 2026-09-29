package ibytsync.core.metadata

import ibytsync.core.matching.YtCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * YouTube Music search — the audio-matching source of truth.
 *
 * Plain YouTube search cannot express "this is the album master": an upload is just a video,
 * so a music video that runs a few seconds long on end credits is indistinguishable from the
 * released track. Keyword heuristics ("official audio") guess at that distinction and lose
 * whenever the wording and the duration disagree.
 *
 * YouTube Music states it outright. Every result carries a `musicVideoType`:
 *  - `MUSIC_VIDEO_TYPE_ATV` — the official album track (what Album Audio should pick)
 *  - `MUSIC_VIDEO_TYPE_OMV` — an official music video
 *  - `MUSIC_VIDEO_TYPE_UGC` — a user upload
 *
 * Asking with the "songs" shelf filter returns album tracks only, each with its real duration
 * in the same payload — verified against "Ayra Starr Hot Body", where the master (2:40) sits
 * at rank 1 and the music video (2:44) is not a song result at all.
 *
 * The shelf filter is an undocumented protobuf constant. If YouTube changes it the shelf
 * stops matching and [search] returns an empty list, which callers treat as "fall back to
 * video search" — the failure mode is degradation, never a wrong pick.
 */
open class YouTubeMusicSearch(
    client: OkHttpClient? = null
) {
    private val http: OkHttpClient = client ?: ibytsync.core.network.SharedHttpClient.instance.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    open fun search(query: String, count: Int = 20): List<YtCandidate> {
        val q = query.trim()
        if (q.isEmpty() || count <= 0) return emptyList()

        return try {
            val payload = """
                {
                    "context": {
                        "client": {
                            "clientName": "WEB_REMIX",
                            "clientVersion": "$CLIENT_VERSION",
                            "hl": "en",
                            "gl": "US"
                        }
                    },
                    "query": ${json.encodeToString(kotlinx.serialization.serializer(), q)},
                    "params": "$SONGS_SHELF_PARAMS"
                }
            """.trimIndent()

            val req = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("X-YouTube-Client-Name", CLIENT_ID)
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()
                parseSongsSearch(body, count)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseSongsSearch(body: String, maxCount: Int): List<YtCandidate> {
        val list = mutableListOf<YtCandidate>()
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val rows = mutableListOf<JsonObject>()
            collectByKey(root, "musicResponsiveListItemRenderer", rows)

            for (row in rows) {
                val id = nestedString(row, "watchEndpoint", "videoId") ?: continue
                if (id.isEmpty()) continue

                val columns = row["flexColumns"]?.jsonArray ?: continue
                val title = columnText(columns, 0).trim()
                if (title.isEmpty()) continue

                val subtitle = columnText(columns, 1)
                val artist = subtitleArtist(columns, 1)
                    ?: subtitle.split(" • ").firstOrNull()?.trim().orEmpty()
                val duration = parseTrailingDuration(subtitle)
                val views = parsePlayCount(columnText(columns, 2))

                list.add(
                    YtCandidate(
                        id = id,
                        title = title,
                        channel = artist,
                        duration = duration,
                        viewCount = views,
                        isOfficialAudio = nestedString(row, "watchEndpointMusicConfig", "musicVideoType")
                            == MUSIC_VIDEO_TYPE_ATV
                    )
                )
                if (list.size >= maxCount) return list
            }
        } catch (_: Exception) {
            // Parsing failure means "no song results" — the caller falls back to video search.
        }
        return list
    }

    /** Depth-first search for every object stored under [key]; the shelf can nest arbitrarily. */
    private fun collectByKey(element: JsonElement, key: String, out: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> {
                element[key]?.jsonObject?.let { out.add(it) }
                element.values.forEach { collectByKey(it, key, out) }
            }
            is JsonArray -> element.forEach { collectByKey(it, key, out) }
            else -> Unit
        }
    }

    /** Reads `outer.inner` as a string from anywhere inside [element]. */
    private fun nestedString(element: JsonElement, outer: String, inner: String): String? {
        val holders = mutableListOf<JsonObject>()
        collectByKey(element, outer, holders)
        for (holder in holders) {
            val value = holder[inner]?.jsonPrimitive?.content?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun columnText(columns: JsonArray, index: Int): String {
        val column = columns.getOrNull(index)?.jsonObject ?: return ""
        val text = column["musicResponsiveListItemFlexColumnRenderer"]?.jsonObject
            ?.get("text")?.jsonObject ?: return ""
        text["runs"]?.jsonArray?.let { runs ->
            return runs.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        }
        return text["simpleText"]?.jsonPrimitive?.content.orEmpty()
    }

    /** The artist is the run tagged as an artist page, which avoids splitting on " • ". */
    private fun subtitleArtist(columns: JsonArray, index: Int): String? {
        val column = columns.getOrNull(index)?.jsonObject ?: return null
        val runs = column["musicResponsiveListItemFlexColumnRenderer"]?.jsonObject
            ?.get("text")?.jsonObject?.get("runs")?.jsonArray ?: return null
        for (run in runs) {
            val pageType = run.jsonObject["navigationEndpoint"]?.jsonObject
                ?.get("browseEndpoint")?.jsonObject
                ?.get("browseEndpointContextSupportedConfigs")?.jsonObject
                ?.get("browseEndpointContextMusicConfig")?.jsonObject
                ?.get("pageType")?.jsonPrimitive?.content
            if (pageType == "MUSIC_PAGE_TYPE_ARTIST") {
                val name = run.jsonObject["text"]?.jsonPrimitive?.content?.trim()
                if (!name.isNullOrEmpty()) return name
            }
        }
        return null
    }

    /**
     * Subtitle looks like "Ayra Starr • Hot Body • 2:40"; the length is the last time-shaped
     * token so an album or artist that happens to contain digits cannot be mistaken for it.
     */
    internal fun parseTrailingDuration(subtitle: String): Double {
        val matches = DURATION_PATTERN.findAll(subtitle).toList()
        val last = matches.lastOrNull()?.value ?: return 0.0
        val parts = last.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            1 -> parts[0].toDouble()
            2 -> (parts[0] * 60 + parts[1]).toDouble()
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toDouble()
            else -> 0.0
        }
    }

    internal fun parsePlayCount(text: String): Long {
        if (text.isEmpty()) return 0L
        val clean = text.replace(",", "").trim()
        val match = Regex("([0-9]+(\\.[0-9]+)?)\\s*([KMBkmb])?").find(clean) ?: return 0L
        val num = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val multiplier = when (match.groupValues[3].uppercase()) {
            "K" -> 1_000.0
            "M" -> 1_000_000.0
            "B" -> 1_000_000_000.0
            else -> 1.0
        }
        return (num * multiplier).toLong()
    }

    companion object {
        const val MUSIC_VIDEO_TYPE_ATV = "MUSIC_VIDEO_TYPE_ATV"
        private const val CLIENT_VERSION = "1.20240101.01.00"
        private const val CLIENT_ID = "67"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        /** Shelf filter selecting YouTube Music's "songs" results. */
        private const val SONGS_SHELF_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="

        private val DURATION_PATTERN = Regex("\\b\\d{1,3}:\\d{2}(?::\\d{2})?\\b")
    }
}
