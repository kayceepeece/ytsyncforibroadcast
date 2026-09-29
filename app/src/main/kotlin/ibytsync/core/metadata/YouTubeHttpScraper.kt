package ibytsync.core.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object YouTubeUrlHelper {
    private val YT_REGEX = Regex(
        "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|v/|embed/|shorts/))([a-zA-Z0-9_-]{11})",
        RegexOption.IGNORE_CASE
    )

    fun extractVideoId(url: String): String? = YT_REGEX.find(url)?.groupValues?.get(1)

    fun canonicalWatchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}

open class YouTubeHttpScraper(
    client: OkHttpClient? = null
) {
    private val http: OkHttpClient = client ?: ibytsync.core.network.SharedHttpClient.instance.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    open fun describe(url: String): YtExtract? {
        val videoId = YouTubeUrlHelper.extractVideoId(url)
        val targetUrl = if (videoId != null) YouTubeUrlHelper.canonicalWatchUrl(videoId) else url

        // 0. Fast-path: YouTube player API (WEB_REMIX) for direct, reliable duration & title without heavy HTML parsing
        if (videoId != null) {
            try {
                val payload = """
                    {
                        "context": {
                            "client": {
                                "clientName": "WEB_REMIX",
                                "clientVersion": "1.20240715.01.00",
                                "hl": "en",
                                "gl": "US"
                            }
                        },
                        "videoId": "$videoId"
                    }
                """.trimIndent()
                val req = Request.Builder()
                    .url("https://music.youtube.com/youtubei/v1/player")
                    .header("Content-Type", "application/json")
                    .header("X-YouTube-Client-Name", "67")
                    .header("X-YouTube-Client-Version", "1.20240715.01.00")
                    .header("Origin", "https://music.youtube.com")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            val root = json.parseToJsonElement(body).jsonObject
                            val details = root["videoDetails"]?.jsonObject
                            if (details != null) {
                                val t = details["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                                val a = details["author"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                                val sec = details["lengthSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
                                if (!t.isNullOrBlank()) {
                                    return YtExtract(
                                        videoTitle = t,
                                        channel = a.orEmpty(),
                                        durationMs = sec?.takeIf { it > 0 }?.let { it * 1000L }
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall through to oEmbed / HTML scraper
            }
        }

        var title: String? = null
        var author: String? = null

        // 1. Try official oEmbed API (Fastest & immune to bot detection)
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=${URLEncoder.encode(targetUrl, "UTF-8")}&format=json"
            val req = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val obj = json.parseToJsonElement(body).jsonObject
                        title = obj["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                        author = obj["author_name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to watch page scraping
        }

        // 2. Fetch watch page HTML for duration (and fallback title/author if oEmbed failed)
        var durationMs: Long? = null
        try {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string().orEmpty()
                    if (html.isNotBlank()) {
                        durationMs = parseDurationMs(html)

                        if (title.isNullOrBlank()) {
                            title = parseTitleFromHtml(html)
                        }
                        if (author.isNullOrBlank()) {
                            author = parseAuthorFromHtml(html)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Duration parsing failed, will use null
        }

        if (title.isNullOrBlank()) return null

        return YtExtract(
            videoTitle = title!!,
            channel = author.orEmpty(),
            durationMs = durationMs
        )
    }

    internal fun parseDurationMs(html: String): Long? {
        // Option A: "lengthSeconds":"250"
        val lengthSecMatch = Regex("\"lengthSeconds\"\\s*:\\s*\"(\\d+)\"").find(html)
        if (lengthSecMatch != null) {
            val sec = lengthSecMatch.groupValues[1].toLongOrNull()
            if (sec != null && sec > 0) return sec * 1000L
        }

        // Option B: <meta itemprop="duration" content="PT4M10S">
        val metaMatch = Regex("<meta\\s+itemprop=[\"']duration[\"']\\s+content=[\"']PT([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
        if (metaMatch != null) {
            val iso = metaMatch.groupValues[1]
            val parsedSec = parseIsoDurationSeconds(iso)
            if (parsedSec != null && parsedSec > 0) return parsedSec * 1000L
        }

        // Option C: approxDurationMs:"250000"
        val approxMatch = Regex("\"approxDurationMs\"\\s*:\\s*\"(\\d+)\"").find(html)
        if (approxMatch != null) {
            val ms = approxMatch.groupValues[1].toLongOrNull()
            if (ms != null && ms > 0) return ms
        }

        return null
    }

    internal fun parseIsoDurationSeconds(iso: String): Long? {
        // e.g. "4M10S", "1H2M30S", "45S"
        return try {
            var total = 0L
            var current = 0L
            for (c in iso.uppercase()) {
                when (c) {
                    in '0'..'9' -> current = current * 10 + (c - '0')
                    'H' -> { total += current * 3600; current = 0 }
                    'M' -> { total += current * 60; current = 0 }
                    'S' -> { total += current; current = 0 }
                }
            }
            total.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseTitleFromHtml(html: String): String? {
        val ogTitle = Regex("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
        if (ogTitle != null) {
            return decodeHtmlEntities(ogTitle.groupValues[1].trim())
        }
        val tagTitle = Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)
        if (tagTitle != null) {
            return decodeHtmlEntities(tagTitle.groupValues[1].replace(Regex("\\s*-\\s*YouTube$"), "").trim())
        }
        return null
    }

    internal fun parseAuthorFromHtml(html: String): String? {
        val author = Regex("<link\\s+itemprop=[\"']name[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("<meta\\s+name=[\"']author[\"']\\s+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
        return author?.let { decodeHtmlEntities(it.groupValues[1].trim()) }
    }

    private fun decodeHtmlEntities(text: String): String {
        return text.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
