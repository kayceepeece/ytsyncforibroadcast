package ibytsync.core.upload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.buffer
import java.util.concurrent.TimeUnit

private val ibJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.str(key: String, default: String = ""): String =
    (get(key) as? JsonPrimitive)?.contentOrNull ?: default

private fun JsonObject.int(key: String, default: Int = 0): Int =
    (get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default

private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (get(key) as? JsonPrimitive)?.booleanOrNull ?: default

private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

private fun parseJsonObject(body: String): JsonObject? = try {
    ibJson.parseToJsonElement(body) as? JsonObject
} catch (_: Exception) {
    null
}

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUriComplete: String,
    val intervalSec: Int,
    val expiresInSec: Int
)

/** One poll attempt. Never throws for the four documented device-flow errors. */
sealed interface PollResult {
    data object Pending : PollResult
    data class SlowDown(val nextIntervalSec: Int) : PollResult
    data class Success(val tokens: Tokens) : PollResult
    data class Terminal(val headline: String, val serverDetail: String?) : PollResult
}

data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val scope: String
)

object IBroadcastOAuth {
    private val client = ibytsync.core.network.SharedHttpClient.instance.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    fun deviceCode(clientId: String, scope: String = "user.library:read user.library:write user.upload"): DeviceCode {
        require(clientId.isNotBlank()) { "client_id empty — register app at media.ibroadcast.com > Apps" }
        val url = "https://oauth.ibroadcast.com/device/code?client_id=${clientId}&scope=${scope.replace(" ", "%20")}"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            require(resp.isSuccessful) { "device/code ${resp.code}: ${body.take(300)}" }
            val o = parseJsonObject(body) ?: throw RuntimeException("device/code bad json")
            val uri = o.str("verification_uri_complete").ifEmpty { o.str("verification_uri") }
            return DeviceCode(
                deviceCode = o.str("device_code"),
                userCode = o.str("user_code"),
                // The raw verification_uri has no scheme; without one ACTION_VIEW cannot open it.
                verificationUriComplete = if (uri.contains("://")) uri else "https://$uri",
                intervalSec = o.int("interval", 1),
                expiresInSec = o.int("expires_in", 900)
            )
        }
    }

    fun refresh(clientId: String, refreshToken: String): Tokens {
        val form = okhttp3.FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        val req = Request.Builder().url("https://oauth.ibroadcast.com/token").post(form).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("refresh ${resp.code}: ${body.take(500)}")
            }
            val o = parseJsonObject(body) ?: throw RuntimeException("refresh bad json")
            return Tokens(
                accessToken = o.str("access_token"),
                refreshToken = o.str("refresh_token").ifEmpty { refreshToken },
                expiresIn = o.int("expires_in", 0),
                scope = (o["scope"] as? JsonArray)?.toString() ?: o.str("scope")
            )
        }
    }
    /**
     * Maps a token-endpoint reply to a [PollResult]. Pure, so the device-flow error
     * contract is testable without a network. A 400 carrying `authorization_pending`
     * is the normal "not approved yet" case, not a failure.
     */
    fun mapTokenResponse(code: Int, body: String, currentIntervalSec: Int): PollResult {
        val o = parseJsonObject(body)
        if (code in 200..299) {
            if (o == null) return PollResult.Terminal("Unexpected reply from iBroadcast", null)
            return PollResult.Success(
                Tokens(
                    accessToken = o.str("access_token"),
                    refreshToken = o.str("refresh_token"),
                    expiresIn = o.int("expires_in", 0),
                    scope = (o["scope"] as? JsonArray)?.toString() ?: o.str("scope")
                )
            )
        }
        val error = o?.str("error").orEmpty()
        val detail = o?.str("error_description").takeIf { !it.isNullOrEmpty() }
        return when (error) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown(currentIntervalSec + 5)
            "expired_token" -> PollResult.Terminal("That code expired — sign in again", detail)
            "access_denied" -> PollResult.Terminal("Access was denied in the browser", detail)
            "invalid_client" -> PollResult.Terminal("This app ID was rejected by iBroadcast", detail)
            else -> PollResult.Terminal("Sign-in failed", detail ?: body.take(200).ifEmpty { null })
        }
    }

    fun pollOnce(clientId: String, deviceCode: String, currentIntervalSec: Int): PollResult {
        val form = okhttp3.FormBody.Builder()
            .add("grant_type", "device_code")
            .add("device_code", deviceCode)
            .add("client_id", clientId)
            .build()
        val req = Request.Builder().url("https://oauth.ibroadcast.com/token").post(form).build()
        val (code, body) = try {
            client.newCall(req).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            return PollResult.Terminal("Couldn't reach iBroadcast", e.message)
        }
        return mapTokenResponse(code, body, currentIntervalSec)
    }

    fun fetchLibrary(accessToken: String): LibrarySnapshot? {
        val bodyJson = buildJsonObject {
            put("mode", "library")
            put("client", "ibytsync-android")
            put("version", "0.1.0")
            put("device_name", "android-batch")
            put("user_agent", "ibytsync-android/0.1.0")
        }.toString()
        val req = Request.Builder()
            .url("https://library.ibroadcast.com/")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "ibytsync-android/0.1.0")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                LibraryCache.parseLibrary(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchStatus(accessToken: String): StatusInfo? {
        val req = Request.Builder()
            .url("https://library.ibroadcast.com/")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "ibytsync-android/0.1.0")
            .post(LibraryCache.statusBody().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                LibraryCache.parseStatus(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun createPlaylist(accessToken: String, name: String): Playlist? {
        val bodyJson = PlaylistMutations.createBody(name)
        val req = Request.Builder()
            .url("https://api.ibroadcast.com/")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "ibytsync-android/0.1.0")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val playlistId = PlaylistMutations.parseCreate(body) ?: return null
                Playlist(playlistId, name)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun uploadTimeoutSec(fileLen: Long): Long {
        val floorBps = 50L * 1024L
        return (600L).coerceAtLeast(fileLen / floorBps).coerceAtMost(900L)
    }

    fun uploadTest(
        accessToken: String,
        file: java.io.File,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
        knownMd5: String? = null
    ): String {
        require(accessToken.isNotBlank()) { "no access token — poll first" }
        require(file.exists()) { "missing ${file.absolutePath}" }
        val md5 = knownMd5 ?: try {
            val d = java.security.MessageDigest.getInstance("MD5")
            file.inputStream().use { ins ->
                val buf = ByteArray(8192)
                while (true) { val n = ins.read(buf); if (n < 0) break; d.update(buf, 0, n) }
            }
            d.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { null }
        val fileBody = file.asRequestBody("audio/mpeg".toMediaType())
        fun countingBody(): okhttp3.RequestBody {
            if (onProgress == null) return fileBody
            return object : okhttp3.RequestBody() {
                override fun contentType() = fileBody.contentType()
                override fun contentLength() = fileBody.contentLength()
                override fun writeTo(sink: okio.BufferedSink) {
                    val total = contentLength()
                    var sent = 0L
                    val forwarding = object : okio.ForwardingSink(sink) {
                        override fun write(source: okio.Buffer, byteCount: Long) {
                            super.write(source, byteCount)
                            sent += byteCount
                            onProgress(sent, total)
                        }
                    }
                    val buffered = forwarding.buffer()
                    fileBody.writeTo(buffered)
                    buffered.flush()
                }
            }
        }
        fun buildRequest(): Request {
            val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file_path", file.name)
                .addFormDataPart("method", "manual")
                .addFormDataPart("client", "ibytsync-android")
                .addFormDataPart("file", file.name, countingBody())
                .build()
            return Request.Builder()
                .url("https://upload.ibroadcast.com/")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("User-Agent", "ibytsync-android/0.1.0")
                .post(body)
                .build()
        }
        val t = uploadTimeoutSec(file.length())
        val longClient = client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(t, TimeUnit.SECONDS)
            .readTimeout(t, TimeUnit.SECONDS)
            .callTimeout(t + 60, TimeUnit.SECONDS)
            .build()
        var attempt = 0
        var lastErr = ""
        while (attempt < 2) {
            try {
                longClient.newCall(buildRequest()).execute().use { resp ->
                    val rb = resp.body?.string() ?: ""
                    return "upload http=${resp.code} md5=${md5 ?: "none"} timeout=${t}s body=${rb.take(600)}"
                }
            } catch (e: Exception) {
                lastErr = "${e.javaClass.simpleName}:${e.message?.take(300)}"
                attempt++
            }
        }
        throw RuntimeException("upload timeout=${t}s after 2 tries: $lastErr")
    }

    fun extractTrackId(responseBody: String): String? {
        val m = Regex(".*\\((.*)\\) uploaded successfully.*").find(responseBody)
        return m?.groupValues?.getOrNull(1)
    }

    fun trashTracks(accessToken: String, trackIds: List<String>): Boolean {
        if (trackIds.isEmpty() || accessToken.isBlank()) return true
        val bodyJson = buildJsonObject {
            put("mode", "trash")
            put("client", "ibytsync-android")
            put("version", "0.1.0")
            put("device_name", "android-batch")
            put("user_agent", "ibytsync-android/0.1.0")
            put("tracks", JsonArray(trackIds.map { id ->
                id.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(id)
            }))
        }.toString()
        val req = Request.Builder()
            .url("https://api.ibroadcast.com/")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "ibytsync-android/0.1.0")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.string() ?: return false
                parseJsonObject(body)?.bool("result", false) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun addToPlaylist(accessToken: String, playlistId: String, trackIds: List<String>): Boolean {
        if (trackIds.isEmpty() || playlistId.isBlank() || accessToken.isBlank()) return true
        val bodyJson = PlaylistMutations.appendBody(playlistId, trackIds)
        val req = Request.Builder()
            .url("https://api.ibroadcast.com/")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "ibytsync-android/0.1.0")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val b = resp.body?.string() ?: ""
                PlaylistMutations.parseAppend(b)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun revoke(clientId: String, token: String): Boolean {
        return try {
            val form = okhttp3.FormBody.Builder()
                .add("token", token)
                .add("client_id", clientId)
                .build()
            val req = Request.Builder().url("https://oauth.ibroadcast.com/revoke").post(form).build()
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    fun withRefreshOnce(
        clientId: String,
        refreshToken: String,
        accessToken: String,
        onNewTokens: (Tokens) -> Unit,
        call: (token: String) -> Boolean
    ): Boolean {
        val first = try {
            call(accessToken)
        } catch (_: Exception) {
            false
        }
        if (first) return true
        val renewed = try {
            if (refreshToken.isEmpty()) return false
            refresh(clientId, refreshToken)
        } catch (_: Exception) {
            return false
        }
        try {
            onNewTokens(renewed)
        } catch (_: Exception) {
        }
        return try {
            call(renewed.accessToken)
        } catch (_: Exception) {
            false
        }
    }
}

data class LibrarySnapshot(
    val lastModified: String?,
    val pairs: Map<String, Pair<String, String>>,
    val checksums: Set<String>,
    val playlists: Map<String, ibytsync.core.upload.Playlist>,
    val tracks: Map<String, ibytsync.core.upload.LibraryTrackInfo> = emptyMap()
)

/**
 * Only the account key and the change token are ever used. The raw response also carries
 * the user's email, session device/IP list and subscription — never persist or display it.
 */
data class StatusInfo(val accountId: String, val lastModified: String?)

object LibraryCache {
    fun parseLibrary(body: String): LibrarySnapshot? {
        return try {
            val o = parseJsonObject(body) ?: return null
            val lib = o.obj("library") ?: return null
            val lastModified = o.obj("status")?.str("lastmodified")?.takeIf { it.isNotEmpty() }
            val pairs = mutableMapOf<String, Pair<String, String>>()
            val checksums = mutableSetOf<String>()
            val tracksMap = mutableMapOf<String, ibytsync.core.upload.LibraryTrackInfo>()

            // 1. Parse Artists to resolve artist names for tracks in official format
            val artistNames = mutableMapOf<String, String>()
            val artistsObj = lib.obj("artists")
            if (artistsObj != null) {
                val artistMap = artistsObj.obj("map")
                val nameIdx = artistMap?.int("name", 0) ?: 0
                for ((artistId, el) in artistsObj) {
                    if (artistId.equals("map", ignoreCase = true)) continue
                    when (el) {
                        is JsonArray -> {
                            val aName = (el.getOrNull(nameIdx) as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
                            if (aName.isNotEmpty()) artistNames[artistId] = aName
                        }
                        is JsonObject -> {
                            val aName = el.str("name").trim()
                            if (aName.isNotEmpty()) artistNames[artistId] = aName
                        }
                        else -> {}
                    }
                }
            }

            // 2. Parse Tracks
            val tracks = lib.obj("tracks")
            if (tracks != null) {
                val trackMap = tracks.obj("map")
                val titleIdx = trackMap?.int("title", 2) ?: 2
                val artistIdIdx = trackMap?.int("artist_id", 7) ?: 7
                val lengthIdx = trackMap?.int("length", 4) ?: 4
                val md5Idx = trackMap?.int("md5", -1) ?: -1

                for ((id, el) in tracks) {
                    if (id.equals("map", ignoreCase = true)) continue
                    when (el) {
                        is JsonArray -> {
                            val title = (el.getOrNull(titleIdx) as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
                            val artistVal = (el.getOrNull(artistIdIdx) as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
                            val artist = artistNames[artistVal] ?: artistVal
                            val lenSec = (el.getOrNull(lengthIdx) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
                            val md5 = if (md5Idx >= 0) {
                                (el.getOrNull(md5Idx) as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: ""
                            } else ""
                            if (title.isNotEmpty() && artist.isNotEmpty()) pairs[id] = title to artist
                            if (md5.isNotEmpty()) checksums.add(md5)
                            tracksMap[id] = ibytsync.core.upload.LibraryTrackInfo(title, artist, lenSec, md5)
                        }
                        is JsonObject -> {
                            val title = el.str("title").trim()
                            val artist = el.str("artist").trim()
                            if (title.isNotEmpty() && artist.isNotEmpty()) pairs[id] = title to artist
                            val md5 = el.str("md5").trim().lowercase()
                                .ifEmpty { el.str("checksum").trim().lowercase() }
                            if (md5.isNotEmpty()) checksums.add(md5)
                            val lenSec = el.int("length", 0)
                            tracksMap[id] = ibytsync.core.upload.LibraryTrackInfo(title, artist, lenSec, md5)
                        }
                        else -> {}
                    }
                }
            }

            // 3. Parse Playlists (official array format + dictionary fallback)
            val playlists = mutableMapOf<String, ibytsync.core.upload.Playlist>()
            val pls = lib.obj("playlists")
            if (pls != null) {
                val plMap = pls.obj("map")
                val nameIdx = plMap?.int("name", 0) ?: 0
                for ((id, el) in pls) {
                    if (id.equals("map", ignoreCase = true)) continue
                    when (el) {
                        is JsonArray -> {
                            val name = (el.getOrNull(nameIdx) as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { id } ?: id
                            playlists[id] = ibytsync.core.upload.Playlist(id, name)
                        }
                        is JsonObject -> {
                            val name = el.str("name").trim().ifEmpty { el.str("playlist_name").trim().ifEmpty { id } }
                            playlists[id] = ibytsync.core.upload.Playlist(id, name)
                        }
                        else -> {}
                    }
                }
            }
            LibrarySnapshot(lastModified, pairs, checksums, playlists, tracksMap)
        } catch (_: Exception) {
            null
        }
    }

    fun parseStatus(body: String): StatusInfo? {
        return try {
            val o = parseJsonObject(body) ?: return null
            val user = o.obj("user") ?: return null
            val accountId = user.str("id")
                .ifEmpty { user.str("user_id") }
                .ifEmpty { user.str("userid") }
            if (accountId.isEmpty()) return null
            StatusInfo(accountId, o.obj("status")?.str("lastmodified")?.takeIf { it.isNotEmpty() })
        } catch (_: Exception) {
            null
        }
    }

    fun statusBody(): String = buildJsonObject {
        put("mode", "status")
        put("client", "ibytsync-android")
        put("version", "0.1.0")
        put("device_name", "android-batch")
        put("user_agent", "ibytsync-android/0.1.0")
    }.toString()

    // Documented check: compare the lastmodified from a status call against the value
    // returned by the last library fetch. The server ignores lastmodified as a request
    // parameter, so there is no conditional fetch — only fetch-less-often.
    fun isFresh(statusResponse: String, lastModified: String?): Boolean {
        if (lastModified.isNullOrEmpty()) return false
        return try {
            val o = parseJsonObject(statusResponse) ?: return false
            o.obj("status")?.str("lastmodified") == lastModified
        } catch (_: Exception) {
            false
        }
    }
}

object PlaylistMutations {
    fun createBody(name: String): String = buildJsonObject {
        put("mode", "createplaylist")
        put("client", "ibytsync-android")
        put("version", "0.1.0")
        put("device_name", "android-batch")
        put("user_agent", "ibytsync-android/0.1.0")
        put("name", name)
        put("playlist_name", name)
        put("description", "Created by YT Sync for iBroadcast")
        put("make_public", false)
        put("tracks", JsonArray(emptyList()))
    }.toString()

    fun appendBody(playlistId: String, trackIds: List<String>): String = buildJsonObject {
        put("mode", "appendplaylist")
        put("client", "ibytsync-android")
        put("version", "0.1.0")
        put("device_name", "android-batch")
        put("user_agent", "ibytsync-android/0.1.0")
        val plInt = playlistId.toIntOrNull()
        if (plInt != null) put("playlist", plInt) else put("playlist", playlistId)
        put("playlist_id", playlistId)
        put("tracks", JsonArray(trackIds.map { id ->
            id.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(id)
        }))
    }.toString()

    fun parseCreate(responseBody: String): String? {
        return try {
            val o = parseJsonObject(responseBody) ?: return null
            if (!o.bool("result", false)) return null
            o.str("playlist_id").takeIf { it.isNotEmpty() }
                ?: (o["playlist_id"] as? JsonPrimitive)?.contentOrNull
                ?: (o["playlist"] as? JsonPrimitive)?.contentOrNull
                ?: o.obj("playlist")?.str("id")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun parseAppend(responseBody: String): Boolean {
        return try {
            parseJsonObject(responseBody)?.bool("result", false) == true
        } catch (_: Exception) {
            false
        }
    }

    fun sortedPlaylists(playlists: Collection<ibytsync.core.upload.Playlist>): List<ibytsync.core.upload.Playlist> =
        playlists.sortedBy { it.name.lowercase() }
}
