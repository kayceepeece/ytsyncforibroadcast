package ibytsync.core.metadata

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Spotify public-page metadata without an API key.
 *
 * Scrape contract:
 * - `GET https://open.spotify.com/{track|album}/{id}` with a crawler UA, 10s timeout.
 *   Spotify serves server-rendered OG/`music:` meta tags to crawlers but a JS
 *   shell with no metadata to full-browser UAs (verified 2026-09-08).
 * - Track page: `og:title` -> track title, artist = `music:musician_description`
 *   else `og:description` split on `·` (`[0]`), `og:image` -> cover,
 *   album = `ld+json MusicRecording.inAlbum.name` else the 2nd `·` token of
 *   `og:description`, duration = `music:duration` seconds else first `>M:SS</span>`.
 * - Album page: artist + album only, no duration/cover; `og:title` suffixes
 *   (` - Album by X | Spotify`) are stripped.
 * - Any scrape failure yields null so the caller marks the row
 *   `Failed — metadata` (retry/remove). Never throws, never guesses
 *   metadata from other sources.
 *
 * MockWebServer-friendly seams:
 * - Inject any [SpotifyPageFetcher] (fake lambda in unit tests), and/or
 * - override [baseUrl] to point at a local MockWebServer while keeping the
 *   real OkHttp fetcher from [okHttpFetcher].
 *
 * Pure JVM otherwise (OkHttp + org.json, both already on the compile classpath).
 */
data class SpotifyTrackMetadata(
    val artist: String,
    val trackTitle: String,
    val album: String,
    val durationMs: Long?,
    val coverUrl: String?,
    val releaseType: String = "single"
)

data class SpotifyAlbumMetadata(
    val artist: String,
    val album: String
)

fun interface SpotifyPageFetcher {
    /** Returns the page HTML, or null on network failure. Must not throw. */
    fun fetch(url: String): String?
}

class SpotifyScraper(
    private val fetcher: SpotifyPageFetcher,
    private val baseUrl: String = "https://open.spotify.com"
) {

    /** Resolve track metadata for a classified URL kind. Null = Failed — metadata. Never throws. */
    fun getTrackMetadata(id: String): SpotifyTrackMetadata? {
        if (id.isBlank()) return null
        return try {
            val html = fetcher.fetch(canonicalTrackUrl(id)) ?: return null
            parseTrackHtml(html)
        } catch (_: Exception) {
            null
        }
    }

    /** Resolve album metadata. Never carries duration/cover per spec. Never throws. */
    fun getAlbumMetadata(id: String): SpotifyAlbumMetadata? {
        if (id.isBlank()) return null
        return try {
            val html = fetcher.fetch(canonicalAlbumUrl(id)) ?: return null
            parseAlbumHtml(html)
        } catch (_: Exception) {
            null
        }
    }

    fun canonicalTrackUrl(id: String): String = "${baseUrl.trimEnd('/')}/track/$id"

    fun canonicalAlbumUrl(id: String): String = "${baseUrl.trimEnd('/')}/album/$id"

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        /**
         * UA for Spotify page fetches. Spotify serves server-rendered metadata
         * only to crawlers; full-browser UAs get a JS shell with no meta
         * tags (verified live 2026-09-08: browser 162kB shell vs bot 184kB
         * with OG/music tags). Kept separate from [USER_AGENT], which the
         * iTunes/Deezer correction clients reuse.
         */
        const val SPOTIFY_USER_AGENT =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        const val TIMEOUT_SEC = 10L

        /** Default network fetcher: crawler UA, 10s connect/read/call timeout, null on any failure. */
        fun okHttpFetcher(client: OkHttpClient? = null): SpotifyPageFetcher {
            val http = client ?: ibytsync.core.network.SharedHttpClient.instance.newBuilder()
                .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()
            return SpotifyPageFetcher { url ->
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", SPOTIFY_USER_AGENT)
                        .get()
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@SpotifyPageFetcher null
                        resp.body?.string()?.takeIf { it.isNotBlank() }
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

        /** Parse a track page. Null when title or artist is missing (Failed — metadata). */
        fun parseTrackHtml(html: String): SpotifyTrackMetadata? {
            val title = extractOgProperty(html, "og:title")?.trim().orEmpty()
            val description = extractOgProperty(html, "og:description")?.trim().orEmpty()
            // Structured artist first (exact); legacy description split as fallback.
            val artist = extractOgProperty(html, "music:musician_description")
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?: description.split("·")[0].trim()
            if (title.isEmpty() || artist.isEmpty()) return null
            val cover = extractOgProperty(html, "og:image")?.trim()?.takeIf { it.isNotEmpty() }
            // Album: strict JSON-LD first; og:description 2nd token as fallback.
            // Tagging layer falls back to track title when still absent. Not a failure.
            val album = extractJsonLdAlbum(html)?.trim()?.takeIf { it.isNotEmpty() }
                ?: descriptionAlbumFallback(description).orEmpty()
            val durationMs = parseMusicDurationMs(html) ?: parseDurationMs(html)
            return SpotifyTrackMetadata(
                artist = artist,
                trackTitle = title,
                album = album,
                durationMs = durationMs,
                coverUrl = cover
            )
        }

        /** Parse an album page. No duration/cover by design. Null when artist/album missing. */
        fun parseAlbumHtml(html: String): SpotifyAlbumMetadata? {
            val rawAlbum = extractOgProperty(html, "og:title")?.trim().orEmpty()
            val album = stripAlbumTitleSuffix(rawAlbum)
            val description = extractOgProperty(html, "og:description")?.trim().orEmpty()
            val artist = description.split("·")[0].trim()
            if (album.isEmpty() || artist.isEmpty()) return null
            return SpotifyAlbumMetadata(artist = artist, album = album)
        }

        /**
         * Strip Spotify's album-title decorations: `After Hours - Album by
         * The Weeknd | Spotify` -> `After Hours`. Plain titles pass through.
         */
        fun stripAlbumTitleSuffix(raw: String): String {
            var s = raw.trim()
            s = s.replace(Regex("\\s*\\|\\s*Spotify\\s*$", RegexOption.IGNORE_CASE), "").trim()
            s = s.replace(
                Regex(
                    "\\s+-\\s*(Album|Single|EP|Playlist|Podcast|Artist|Compilation) by .*$",
                    RegexOption.IGNORE_CASE
                ),
                ""
            ).trim()
            return s
        }

        /**
         * Album fallback from `og:description` (`Artist · Album · Song · Year`):
         * the 2nd `·` token. Type words (`Song`) and years are not albums.
         */
        fun descriptionAlbumFallback(description: String): String? {
            val parts = description.split("·").map { it.trim() }
            if (parts.size < 2) return null
            val cand = parts[1]
            if (cand.isEmpty()) return null
            if (cand.lowercase() in ALBUM_FALLBACK_BLOCKLIST) return null
            if (cand.matches(Regex("\\d{4}"))) return null
            return cand
        }

        private val ALBUM_FALLBACK_BLOCKLIST = setOf(
            "song", "single", "album", "ep", "playlist", "podcast", "episode", "track"
        )

        /**
         * Extract `<meta property="..." content="...">` tolerating either
         * attribute order and both `property=` and `name=` attributes
         * (Spotify serves OG tags via `property=` but `music:`/`twitter:`
         * tags via `name=`). Matching is confined to a single tag so values
         * can never bleed across adjacent tags.
         */
        fun extractOgProperty(html: String, property: String): String? {
            val tagPattern = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
            val attrPattern = Regex("(?:property|name)=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            val contentPattern = Regex(
                "content=[\"'](.*?)[\"']",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            for (tag in tagPattern.findAll(html)) {
                val tagText = tag.value
                val prop = attrPattern.find(tagText)?.groupValues?.get(1) ?: continue
                if (!prop.equals(property, ignoreCase = true)) continue
                val raw = contentPattern.find(tagText)?.groupValues?.get(1) ?: continue
                return unescapeHtml(raw).takeIf { it.isNotEmpty() }
            }
            return null
        }

        /**
         * Find the album name in `<script type="application/ld+json">` blocks via
         * `MusicRecording.inAlbum.name`. Handles single objects, arrays, and
         * `@graph` wrappers. Falls back to a tolerant regex when the block is
         * not strict JSON. Null when absent (not a failure).
         */
        fun extractJsonLdAlbum(html: String): String? {
            val blocks = Regex(
                "<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).findAll(html).map { it.groupValues[1] }.toList()
            for (block in blocks) {
                walkJsonLdForAlbum(block.trim())?.let { return it }
            }
            return null
        }

        /**
         * `music:duration` (seconds, may be fractional) -> milliseconds.
         * Null when absent or non-positive (duration stays unknown; matching
         * just widens search).
         */
        fun parseMusicDurationMs(html: String): Long? {
            val raw = extractOgProperty(html, "music:duration")?.trim() ?: return null
            val seconds = raw.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            return (seconds * 1000).toLong()
        }

        /**
         * First `>M:SS</span>` occurrence -> milliseconds. Legacy fallback for
         * [parseMusicDurationMs]. Null when absent (track without duration
         * still resolves; matching just widens search).
         */
        fun parseDurationMs(html: String): Long? {
            val m = Regex(">(\\d+):(\\d{2})</span>").find(html) ?: return null
            val minutes = m.groupValues[1].toLongOrNull() ?: return null
            val seconds = m.groupValues[2].toLongOrNull()?.takeIf { it < 60 } ?: return null
            return (minutes * 60 + seconds) * 1000L
        }

        /** Single-pass HTML entity decoder (named + decimal + hex). Unknown entities kept. */
        fun unescapeHtml(s: String): String {
            if (!s.contains('&')) return s
            return Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z][a-zA-Z0-9]+);").replace(s) { m ->
                decodeEntity(m.groupValues[1]) ?: m.value
            }
        }

        private fun decodeEntity(entity: String): String? {
            if (entity.startsWith("#x", ignoreCase = true)) {
                val code = entity.drop(2).toIntOrNull(16) ?: return null
                return codePointToString(code)
            }
            if (entity.startsWith("#")) {
                val code = entity.drop(1).toIntOrNull() ?: return null
                return codePointToString(code)
            }
            return when (entity) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos", "#39", "#x27" -> "'"
                "middot", "middot-" -> "·"
                "nbsp" -> " "
                "ndash" -> "–"
                "mdash" -> "—"
                "hellip" -> "…"
                "rsquo" -> "’"
                "lsquo" -> "‘"
                "rdquo" -> "”"
                "ldquo" -> "“"
                else -> null
            }
        }

        private fun codePointToString(code: Int): String? {
            if (code <= 0 || code > 0x10FFFF) return null
            return String(Character.toChars(code))
        }

        private fun walkJsonLdForAlbum(block: String): String? {
            // Strict-JSON attempt first.
            try {
                val trimmed = block.trim()
                val roots: List<Any?> = when {
                    trimmed.startsWith("[") -> {
                        val arr = JSONArray(trimmed)
                        (0 until arr.length()).map { arr.opt(it) }
                    }
                    trimmed.startsWith("{") -> listOf(JSONObject(trimmed))
                    else -> emptyList()
                }
                for (root in roots) {
                    scanJsonNode(root)?.let { return it }
                }
            } catch (_: Exception) {
                // Fall through to the tolerant regex.
            }
            val m = Regex(
                "\"inAlbum\"\\s*:\\s*\\{[^}]*?\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                RegexOption.DOT_MATCHES_ALL
            ).find(block) ?: return null
            return unescapeJsonString(m.groupValues[1]).trim().takeIf { it.isNotEmpty() }
        }

        private fun scanJsonNode(node: Any?): String? {
            when (node) {
                is JSONObject -> {
                    val type = node.optString("@type", "")
                    if (type.contains("MusicRecording")) {
                        val inAlbum = node.optJSONObject("inAlbum")
                        val name = inAlbum?.optString("name", "")?.trim().orEmpty()
                        if (name.isNotEmpty()) return name
                    }
                    val graph = node.optJSONArray("@graph")
                    if (graph != null) {
                        for (i in 0 until graph.length()) {
                            scanJsonNode(graph.opt(i))?.let { return it }
                        }
                    }
                    // Recurse into nested objects (e.g. {"@type":"MusicGroup",...} wrappers).
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val v = node.opt(keys.next())
                        if (v is JSONObject || v is JSONArray) {
                            scanJsonNode(v)?.let { return it }
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        scanJsonNode(node.opt(i))?.let { return it }
                    }
                }
            }
            return null
        }

        private fun unescapeJsonString(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) {
                    out.append(c)
                    i++
                    continue
                }
                when (val e = s[i + 1]) {
                    '"', '\\', '/' -> {
                        out.append(e)
                        i += 2
                    }
                    'b' -> {
                        out.append('\b')
                        i += 2
                    }
                    'f' -> {
                        out.append('\u000C')
                        i += 2
                    }
                    'n' -> {
                        out.append('\n')
                        i += 2
                    }
                    'r' -> {
                        out.append('\r')
                        i += 2
                    }
                    't' -> {
                        out.append('\t')
                        i += 2
                    }
                    'u' -> {
                        val hex = if (i + 5 < s.length) s.substring(i + 2, i + 6) else ""
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i += 6
                        } else {
                            out.append(c)
                            i++
                        }
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }
    }
}
