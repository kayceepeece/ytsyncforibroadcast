package ibytsync.core.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface SpotifyCorrectionFetcher {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String?
    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String?
}

/**
 * Metadata correction client for Spotify.
 *
 * Implements [CorrectionClient] alongside [ITunesCorrection] and [DeezerCorrection].
 * Searches Spotify's music catalogue without requiring user developer keys by:
 * 1. Using Spotify's Web Player TOTP-authenticated GraphQL query endpoint (`searchDesktop`).
 * 2. Caching the anonymous session token in memory.
 * 3. Falling back gracefully to the resilient wolfXspotify mirror search endpoint if GraphQL is unavailable.
 *
 * Never throws on any network or parsing error.
 */
class SpotifyCorrection(
    private val fetcher: SpotifyCorrectionFetcher = defaultFetcher(),
    private val baseUrl: String = "https://api-partner.spotify.com"
) : CorrectionClient {

    private val tokenLock = Any()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0L
    @Volatile private var lastTokenFailureMs: Long = 0L

    override fun candidates(artistGuess: String, titleGuess: String): List<CorrectionCandidate> {
        val query = listOf(artistGuess, titleGuess)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (query.isBlank()) return emptyList()

        return try {
            // Attempt 1: Direct Spotify Web GraphQL endpoint with anonymous token
            val token = getAccessToken()
            if (token != null) {
                val gqlPayload = buildSearchPayload(query)
                val gqlResp = fetcher.post(
                    url = "${baseUrl.trimEnd('/')}/pathfinder/v2/query",
                    body = gqlPayload,
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Content-Type" to "application/json;charset=UTF-8",
                        "User-Agent" to USER_AGENT,
                        "app-platform" to "WebPlayer",
                        "spotify-app-version" to APP_VERSION,
                        "Referer" to "https://open.spotify.com/",
                        "Origin" to "https://open.spotify.com"
                    )
                )
                if (!gqlResp.isNullOrBlank()) {
                    val results = parseGraphQLTracks(gqlResp)
                    if (results.isNotEmpty()) return results
                }
            }

            // Attempt 2: Fallback to wolfXspotify public search API
            val wolfUrl = "https://spotify.xwolf.space/api/search?q=${encode(query)}&type=track&limit=10"
            val wolfResp = fetcher.get(wolfUrl, mapOf("User-Agent" to USER_AGENT))
            if (!wolfResp.isNullOrBlank()) {
                val results = parseWolfResults(wolfResp)
                if (results.isNotEmpty()) return results
            }

            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getAccessToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < (tokenExpiresAtMs - 60_000L)) {
            return cachedToken
        }
        if (cachedToken == null && now - lastTokenFailureMs < 5 * 60_000L) {
            return null
        }
        synchronized(tokenLock) {
            val currentNow = System.currentTimeMillis()
            if (cachedToken != null && currentNow < (tokenExpiresAtMs - 60_000L)) {
                return cachedToken
            }
            if (cachedToken == null && currentNow - lastTokenFailureMs < 5 * 60_000L) {
                return null
            }
            val token = fetchFreshToken()
            if (token != null) {
                cachedToken = token
                if (tokenExpiresAtMs <= currentNow) {
                    tokenExpiresAtMs = currentNow + 30 * 60 * 1000L
                }
            } else {
                lastTokenFailureMs = currentNow
            }
            return token
        }
    }

    private fun fetchFreshToken(): String? {
        return try {
            val serverTimeResp = fetcher.get(
                "https://open.spotify.com/api/server-time",
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Referer" to "https://open.spotify.com/"
                )
            )
            val serverTimeSec = parseServerTime(serverTimeResp) ?: (System.currentTimeMillis() / 1000L)
            val totp = computeTotp(serverTimeSec, TOTP_VERSION, DEFAULT_SECRET)
            val tokenUrl = "https://open.spotify.com/api/token?reason=init&productType=web-player&totp=$totp&totpVer=$TOTP_VERSION&ts=$serverTimeSec"
            val tokenResp = fetcher.get(
                tokenUrl,
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Referer" to "https://open.spotify.com/"
                )
            ) ?: return null
            val (token, expiresAt) = parseTokenResponse(tokenResp)
            if (expiresAt != null && expiresAt > 0) {
                tokenExpiresAtMs = expiresAt
            }
            token
        } catch (_: Exception) {
            null
        }
    }

    private fun buildSearchPayload(query: String): String {
        val escapedQuery = query.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            {
                "variables": {
                    "searchTerm": "$escapedQuery",
                    "offset": 0,
                    "limit": 5,
                    "numberOfTopResults": 5,
                    "includeAudiobooks": false,
                    "includeArtistHasConcertsField": false,
                    "includePreReleases": true,
                    "includeLocalConcertsField": false
                },
                "operationName": "searchDesktop",
                "extensions": {
                    "persistedQuery": {
                        "version": 1,
                        "sha256Hash": "$SEARCH_HASH"
                    }
                }
            }
        """.trimIndent()
    }

    companion object {
        const val SEARCH_HASH = "3c9d3f60dac5dea3876b6db3f534192b1c1d90032c4233c1bbaba526db41eb31"
        const val APP_VERSION = "1.2.84.359.g17db506e"
        const val TOTP_VERSION = 61
        const val TIMEOUT_SEC = 10L
        const val CALL_TIMEOUT_SEC = 12L

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        val DEFAULT_SECRET = intArrayOf(
            44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78
        )

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun encode(q: String): String = URLEncoder.encode(q, "UTF-8")

        fun computeTotp(
            timestampSeconds: Long,
            version: Int = TOTP_VERSION,
            secret: IntArray = DEFAULT_SECRET
        ): String {
            val sb = StringBuilder()
            for (t in secret.indices) {
                sb.append(secret[t] xor ((t % 33) + 9))
            }
            val keyBytes = sb.toString().toByteArray(Charsets.UTF_8)
            val counter = timestampSeconds / 30L
            val counterBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(counter).array()

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
            val hash = mac.doFinal(counterBytes)

            val offset = hash[hash.size - 1].toInt() and 0x0F
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
            val otp = binary % 1_000_000
            return otp.toString().padStart(6, '0')
        }

        fun parseServerTime(body: String?): Long? {
            if (body.isNullOrBlank()) return null
            return try {
                val root = json.parseToJsonElement(body).jsonObject
                root["serverTime"]?.jsonPrimitive?.longOrNull
            } catch (_: Exception) {
                null
            }
        }

        fun parseTokenResponse(body: String): Pair<String?, Long?> {
            return try {
                val root = json.parseToJsonElement(body).jsonObject
                val token = root["accessToken"]?.jsonPrimitive?.contentOrNull
                val exp = root["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.longOrNull
                token to exp
            } catch (_: Exception) {
                null to null
            }
        }

        fun parseGraphQLTracks(body: String): List<CorrectionCandidate> {
            return try {
                val root = json.parseToJsonElement(body).jsonObject
                val searchV2 = root["data"]?.jsonObject?.get("searchV2")?.jsonObject ?: return emptyList()
                val items = searchV2["tracksV2"]?.jsonObject?.get("items")?.jsonArray ?: return emptyList()

                items.mapNotNull { el ->
                    try {
                        val elObj = el.jsonObject
                        val item = elObj["item"]?.jsonObject?.get("data")?.jsonObject
                            ?: elObj["track"]?.jsonObject
                            ?: elObj

                        val trackTitle = item["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (trackTitle.isEmpty()) return@mapNotNull null

                        val artistItems = item["artists"]?.jsonObject?.get("items")?.jsonArray
                        val artist = artistItems?.mapNotNull {
                            it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                                ?: it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                        }?.joinToString(", ")?.trim().orEmpty()
                        if (artist.isEmpty()) return@mapNotNull null

                        val albumOfTrack = item["albumOfTrack"]?.jsonObject ?: item["album"]?.jsonObject
                        val album = albumOfTrack?.get("name")?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { trackTitle }

                        val coverSources = albumOfTrack?.get("coverArt")?.jsonObject?.get("sources")?.jsonArray
                            ?: albumOfTrack?.get("images")?.jsonArray
                        val artworkUrl = pickBestCover(coverSources)

                        val durationMs = item["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.longOrNull
                            ?: item["duration_ms"]?.jsonPrimitive?.longOrNull

                        val year = albumOfTrack?.get("date")?.jsonObject?.get("year")?.jsonPrimitive?.contentOrNull
                            ?: albumOfTrack?.get("date")?.jsonObject?.get("year")?.jsonPrimitive?.intOrNull?.toString()
                            ?: albumOfTrack?.get("release_date")?.jsonPrimitive?.contentOrNull?.take(4)

                        val trackNumber = item["trackNumber"]?.jsonPrimitive?.intOrNull
                        val isrc = item["isrc"]?.jsonPrimitive?.contentOrNull
                            ?: item["external_ids"]?.jsonObject?.get("isrc")?.jsonPrimitive?.contentOrNull

                        CorrectionCandidate(
                            artist = artist,
                            trackTitle = trackTitle,
                            album = album,
                            artworkUrl = artworkUrl,
                            source = "spotify",
                            durationMs = durationMs,
                            isrc = isrc,
                            year = year,
                            trackNumber = trackNumber
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseWolfResults(body: String): List<CorrectionCandidate> {
            return try {
                val root = json.parseToJsonElement(body).jsonObject
                val results = root["results"]?.jsonArray ?: return emptyList()
                results.mapNotNull { el ->
                    try {
                        val obj = el.jsonObject
                        val trackTitle = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        val artist = obj["artist"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (trackTitle.isEmpty() || artist.isEmpty()) return@mapNotNull null
                        val album = obj["album"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { trackTitle }
                        val artworkUrl = obj["thumbnail"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                        val durationMs = obj["duration_ms"]?.jsonPrimitive?.longOrNull
                        val year = obj["release_date"]?.jsonPrimitive?.contentOrNull?.take(4)
                        val trackNumber = obj["track_number"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
                        val isrc = obj["isrc"]?.jsonPrimitive?.contentOrNull
                            ?: obj["external_ids"]?.jsonObject?.get("isrc")?.jsonPrimitive?.contentOrNull
                        CorrectionCandidate(
                            artist = artist,
                            trackTitle = trackTitle,
                            album = album,
                            artworkUrl = artworkUrl,
                            source = "spotify",
                            durationMs = durationMs,
                            isrc = isrc,
                            year = year,
                            trackNumber = trackNumber
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun pickBestCover(sources: JsonArray?): String? {
            if (sources == null || sources.isEmpty()) return null
            return sources.mapNotNull { it.jsonObject }
                .maxByOrNull {
                    it["width"]?.jsonPrimitive?.intOrNull
                        ?: it["height"]?.jsonPrimitive?.intOrNull
                        ?: 0
                }?.get("url")?.jsonPrimitive?.contentOrNull
        }

        fun okHttpFetcher(client: OkHttpClient? = null): SpotifyCorrectionFetcher = defaultFetcher(client)

        fun defaultFetcher(client: OkHttpClient? = null): SpotifyCorrectionFetcher {
            val http = client ?: ibytsync.core.network.SharedHttpClient.instance.newBuilder()
                .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()

            return object : SpotifyCorrectionFetcher {
                override fun get(url: String, headers: Map<String, String>): String? {
                    return try {
                        val reqBuilder = Request.Builder().url(url).get()
                        for ((k, v) in headers) {
                            reqBuilder.header(k, v)
                        }
                        http.newCall(reqBuilder.build()).execute().use { resp ->
                            if (!resp.isSuccessful) return null
                            resp.body?.string()?.takeIf { it.isNotBlank() }
                        }
                    } catch (_: Exception) {
                        null
                    }
                }

                override fun post(url: String, body: String, headers: Map<String, String>): String? {
                    return try {
                        val mediaType = (headers["Content-Type"] ?: "application/json;charset=UTF-8").toMediaType()
                        val reqBuilder = Request.Builder().url(url).post(body.toRequestBody(mediaType))
                        for ((k, v) in headers) {
                            reqBuilder.header(k, v)
                        }
                        http.newCall(reqBuilder.build()).execute().use { resp ->
                            if (!resp.isSuccessful) return null
                            resp.body?.string()?.takeIf { it.isNotBlank() }
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }
}
