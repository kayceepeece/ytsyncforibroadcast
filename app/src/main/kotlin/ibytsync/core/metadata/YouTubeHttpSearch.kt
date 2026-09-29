package ibytsync.core.metadata

import ibytsync.core.matching.YtCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

open class YouTubeHttpSearch(
    client: OkHttpClient? = null
) {
    private val http: OkHttpClient = client ?: ibytsync.core.network.SharedHttpClient.instance.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    open fun search(query: String, count: Int = 10): List<YtCandidate> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        return try {
            val payload = """
                {
                    "context": {
                        "client": {
                            "clientName": "WEB",
                            "clientVersion": "2.20240101.00.00"
                        }
                    },
                    "query": ${json.encodeToString(kotlinx.serialization.serializer(), q)}
                }
            """.trimIndent()

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()
                parseSearchResponse(body, count)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseSearchResponse(body: String, maxCount: Int): List<YtCandidate> {
        val list = mutableListOf<YtCandidate>()
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val contents = root["contents"]?.jsonObject
                ?.get("twoColumnSearchResultsRenderer")?.jsonObject
                ?.get("primaryContents")?.jsonObject
                ?.get("sectionListRenderer")?.jsonObject
                ?.get("contents")?.jsonArray ?: return emptyList()

            for (section in contents) {
                val itemSection = section.jsonObject["itemSectionRenderer"]?.jsonObject
                val sectionContents = itemSection?.get("contents")?.jsonArray ?: continue
                for (item in sectionContents) {
                    val vr = item.jsonObject["videoRenderer"]?.jsonObject ?: continue
                    val videoId = vr["videoId"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (videoId.isEmpty()) continue

                    val title = vr["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("text")?.jsonPrimitive?.content?.trim().orEmpty()
                        .ifEmpty {
                            vr["title"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.content?.trim().orEmpty()
                        }
                    if (title.isEmpty()) continue

                    val channel = vr["ownerText"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("text")?.jsonPrimitive?.content?.trim().orEmpty()
                        .ifEmpty {
                            vr["longBylineText"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
                                ?.jsonObject?.get("text")?.jsonPrimitive?.content?.trim().orEmpty()
                        }

                    val lengthText = vr["lengthText"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.content?.trim().orEmpty()
                    val durationSec = parseDurationSeconds(lengthText)

                    val viewText = vr["viewCountText"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.content?.trim().orEmpty()
                    val views = parseViewCount(viewText)

                    list.add(
                        YtCandidate(
                            id = videoId,
                            title = title,
                            channel = channel,
                            duration = durationSec,
                            viewCount = views
                        )
                    )
                    if (list.size >= maxCount) return list
                }
            }
        } catch (_: Exception) {
            // parsing error fallback
        }
        return list
    }

    internal fun parseDurationSeconds(text: String): Double {
        if (text.isEmpty()) return 0.0
        val parts = text.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            1 -> parts[0].toDouble()
            2 -> (parts[0] * 60 + parts[1]).toDouble()
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toDouble()
            else -> 0.0
        }
    }

    internal fun parseViewCount(text: String): Long {
        if (text.isEmpty()) return 0L
        val clean = text.replace(",", "").replace(" views", "").trim()
        val numMatch = Regex("^([0-9]+(\\.[0-9]+)?)\\s*([KMBkmb])?").find(clean) ?: return 0L
        val num = numMatch.groupValues[1].toDoubleOrNull() ?: return 0L
        val multiplier = when (numMatch.groupValues[3].uppercase()) {
            "K" -> 1_000.0
            "M" -> 1_000_000.0
            "B" -> 1_000_000_000.0
            else -> 1.0
        }
        return (num * multiplier).toLong()
    }
}

open class YouTubeSuggestService(
    client: OkHttpClient? = null
) {
    private val http: OkHttpClient = client ?: ibytsync.core.network.SharedHttpClient.instance.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    open fun querySuggestions(query: String, maxCount: Int = 4): List<String> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        return try {
            val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${URLEncoder.encode(q, "UTF-8")}"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()
                val root = json.parseToJsonElement(body).jsonArray
                if (root.size > 1) {
                    val suggestions = root[1].jsonArray
                    suggestions.mapNotNull { it.jsonPrimitive.content.trim().takeIf { s -> s.isNotEmpty() } }
                        .filterNot { it.equals(q, ignoreCase = true) }
                        .take(maxCount)
                } else {
                    emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
