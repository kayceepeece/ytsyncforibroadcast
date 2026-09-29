package ibytsync.core.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SpotifyCollectionTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val coverUrl: String?,
    val isPlayable: Boolean = true
)

data class SpotifyCollectionResult(
    val title: String,
    val type: String, // "playlist" or "album"
    val coverUrl: String?,
    val tracks: List<SpotifyCollectionTrack>
)

fun interface SpotifyEmbedFetcher {
    fun fetch(url: String): String?
}

class SpotifyEmbedScraper(
    private val fetcher: SpotifyEmbedFetcher = defaultFetcher()
) {
    fun fetchCollection(kind: UrlKind): SpotifyCollectionResult? {
        val (type, id) = when (kind) {
            is UrlKind.SpotifyPlaylist -> "playlist" to kind.id
            is UrlKind.SpotifyAlbum -> "album" to kind.id
            else -> return null
        }
        val url = "https://open.spotify.com/embed/$type/$id"
        val html = fetcher.fetch(url) ?: return null
        return parseEmbedHtml(html)
    }

    companion object {
        private const val TIMEOUT_SEC = 10L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun defaultFetcher(client: OkHttpClient? = null): SpotifyEmbedFetcher {
            val http = client ?: ibytsync.core.network.SharedHttpClient.instance.newBuilder()
                .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()
            return SpotifyEmbedFetcher { url ->
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@SpotifyEmbedFetcher null
                        resp.body?.string()?.takeIf { it.isNotBlank() }
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

        fun parseEmbedHtml(html: String): SpotifyCollectionResult? {
            return try {
                val scriptStart = html.indexOf("<script id=\"__NEXT_DATA__\"")
                if (scriptStart == -1) return null
                val jsonStart = html.indexOf('>', scriptStart) + 1
                val jsonEnd = html.indexOf("</script>", jsonStart)
                if (jsonStart <= 0 || jsonEnd <= jsonStart) return null
                val jsonText = html.substring(jsonStart, jsonEnd).trim()

                val root = json.parseToJsonElement(jsonText).jsonObject
                val entity = root["props"]?.jsonObject
                    ?.get("pageProps")?.jsonObject
                    ?.get("state")?.jsonObject
                    ?.get("data")?.jsonObject
                    ?.get("entity")?.jsonObject ?: return null

                val title = entity["name"]?.jsonPrimitive?.content
                    ?.ifBlank { entity["title"]?.jsonPrimitive?.content }
                    ?.trim()
                    .orEmpty()
                val type = entity["type"]?.jsonPrimitive?.content?.ifBlank { "collection" } ?: "collection"

                val coverSources = entity["coverArt"]?.jsonObject?.get("sources")?.jsonArray
                val coverUrl = coverSources?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

                val trackArray = entity["trackList"]?.jsonArray ?: return null
                val tracks = mutableListOf<SpotifyCollectionTrack>()

                for (item in trackArray) {
                    val t = item.jsonObject
                    val rawTitle = t["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (rawTitle.isBlank()) continue

                    val rawArtist = (t["subtitle"]?.jsonPrimitive?.content ?: "")
                        .replace('\u00A0', ' ')
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                    val uri = t["uri"]?.jsonPrimitive?.content ?: ""
                    val trackId = if (uri.contains(':')) uri.substringAfterLast(':') else uri
                    val duration = t["duration"]?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it > 0L }
                    val playable = t["isPlayable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

                    tracks.add(
                        SpotifyCollectionTrack(
                            trackId = trackId,
                            title = rawTitle,
                            artist = rawArtist.ifBlank { "Unknown Artist" },
                            album = if (type.equals("album", ignoreCase = true)) title else null,
                            durationMs = duration,
                            coverUrl = coverUrl,
                            isPlayable = playable
                        )
                    )
                }

                if (tracks.isEmpty()) return null
                SpotifyCollectionResult(
                    title = title.ifBlank { if (type == "album") "Spotify Album" else "Spotify Playlist" },
                    type = type,
                    coverUrl = coverUrl,
                    tracks = tracks
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
