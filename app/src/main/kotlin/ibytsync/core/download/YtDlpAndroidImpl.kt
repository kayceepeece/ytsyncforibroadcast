package ibytsync.core.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID

class YtDlpAndroidImpl(private val context: Context) : YtDlpEngine {
    private val variantLog = mutableListOf<String>()

    private var isInitialized = false

    fun pruneOrphanedTempFiles(maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        try {
            val outDir = context.getExternalFilesDir("youtubedl-android") ?: File(context.filesDir, "youtubedl-android")
            if (!outDir.exists() || !outDir.isDirectory) return
            val cutoff = System.currentTimeMillis() - maxAgeMs
            outDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    val name = file.name
                    if (name.endsWith(".part") || name.endsWith(".ytdl") || name.startsWith("tb_")) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun init(): Boolean {
        pruneOrphanedTempFiles()
        if (isInitialized) return true
        return try {
            YoutubeDL.getInstance().init(context)
            com.yausername.ffmpeg.FFmpeg.getInstance().init(context)
            isInitialized = true
            true
        } catch (e: Exception) {
            variantLog.add("init-failed:${e.javaClass.simpleName}:${e.message?.take(500)}")
            isInitialized = false
            false
        }
    }

    override fun isReady(): Boolean = isInitialized

    override fun cancelProcess(tokenPrefix: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(tokenPrefix)
        } catch (_: Exception) {
        }
    }

    fun version(): String {
        return try { YoutubeDL.getInstance().version(context)?.toString() ?: "unknown" }
        catch (e: Exception) { "ver-err:${e.message?.take(200)}" }
    }

    fun updateStable(): String {
        return try {
            val res = YoutubeDL.getInstance().updateYoutubeDL(
                context,
                YoutubeDL.UpdateChannel.STABLE
            )
            res.toString()
        } catch (e: Exception) {
            "update-ex:${e.javaClass.simpleName}:${e.message?.take(500)}"
        }
    }

    override fun activeVariantLog(): List<String> = variantLog.toList()

    override fun searchCandidates(
        query: String,
        count: Int
    ): List<ibytsync.core.matching.YtCandidate> {
        return try {
            val req = YoutubeDLRequest("ytsearch$count:$query")
            req.addOption("--no-playlist")
            req.addOption("--no-check-certificate")
            req.addOption("--no-warnings")
            req.addOption("--socket-timeout", "10")
            req.addOption("--extractor-retries", "1")
            req.addOption("--retries", "1")
            req.addOption("--print", "%(id)s|||%(title)s|||%(duration)s|||%(channel)s|||%(view_count)s")
            val processId = newToken()
            val resp = YoutubeDL.getInstance().execute(req, processId, null)
            parsePrintOutput(resp.out)
        } catch (e: Exception) {
            variantLog.add("search-failed:${e.javaClass.simpleName}")
            emptyList()
        }
    }

    override fun download(request: DownloadRequest): DownloadResult? = download(request, null)

    override fun download(
        request: DownloadRequest,
        onProgress: ((progress: Float, eta: Long, line: String) -> Unit)?
    ): DownloadResult? {
        val outDir = context.getExternalFilesDir("youtubedl-android") ?: File(context.filesDir, "youtubedl-android")
        outDir.mkdirs()
        val playerClients = listOf("ios", "android", "web")
        try {
            for (client in playerClients) {
                try {
                    val req = YoutubeDLRequest(request.queryOrUrl)
                    req.addOption("--no-playlist")
                    req.addOption("--no-check-certificate")
                    req.addOption("--socket-timeout", "20")
                    req.addOption("--retries", "2")
                    req.addOption("--extractor-args", "youtube:player_client=$client")
                    req.addOption("-x")
                    req.addOption("--audio-format", request.audioFormat)
                    req.addOption("--audio-quality", request.audioQuality)
                    req.addOption("-o", File(outDir, "${request.tokenPrefix}_%(title)s.%(ext)s").absolutePath)
                    val resp = if (onProgress != null) {
                        YoutubeDL.getInstance().execute(req, request.tokenPrefix) { progress, eta, line ->
                            onProgress(progress, eta, line)
                        }
                    } else {
                        YoutubeDL.getInstance().execute(req, request.tokenPrefix, null)
                    }
                    variantLog.add("client=$client rc=${resp.exitCode}")
                    val produced = outDir.listFiles { f ->
                        f.name.startsWith(request.tokenPrefix) && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl")
                    }?.sortedBy { it.name }?.firstOrNull()
                    if (resp.exitCode == 0 && produced != null) {
                        return DownloadResult(produced.absolutePath, "player_client=$client", resp.exitCode)
                    }
                } catch (e: Exception) {
                    variantLog.add("client=$client ex=${e.javaClass.simpleName}:${e.message?.take(800)}")
                }
            }
            return null
        } finally {
            try {
                outDir.listFiles { f ->
                    f.name.startsWith(request.tokenPrefix) && (f.name.endsWith(".part") || f.name.endsWith(".ytdl"))
                }?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
    }

    override fun describeVideo(url: String): ibytsync.core.metadata.YtExtract? {
        return try {
            val req = YoutubeDLRequest(url)
            req.addOption("--no-playlist")
            req.addOption("--no-check-certificate")
            req.addOption("--no-warnings")
            req.addOption("--skip-download")
            req.addOption("--socket-timeout", "10")
            req.addOption("--extractor-retries", "1")
            req.addOption("--retries", "1")
            req.addOption("--print", "%(.{title,channel,uploader,duration})j")
            val processId = newToken()
            val resp = YoutubeDL.getInstance().execute(req, processId, null)
            if (resp.exitCode != 0) {
                variantLog.add("describe rc=${resp.exitCode}")
                return null
            }
            val line = resp.out.lines().firstOrNull { it.trim().startsWith("{") } ?: return null
            ibytsync.core.metadata.YtExtractParser.parseDumpJson(line)
        } catch (e: Exception) {
            variantLog.add("describe-failed:${e.javaClass.simpleName}")
            null
        }
    }

    override fun extractPlaylist(playlistUrl: String): List<ibytsync.core.matching.YtCandidate> {
        return try {
            val req = YoutubeDLRequest(playlistUrl)
            req.addOption("--flat-playlist")
            req.addOption("--no-check-certificate")
            req.addOption("--no-warnings")
            req.addOption("--skip-download")
            req.addOption("--socket-timeout", "15")
            req.addOption("--extractor-retries", "2")
            req.addOption("--retries", "2")
            req.addOption("--print", "%(id)s|||%(title)s|||%(duration)s|||%(channel,uploader)s|||%(view_count)s")
            val processId = newToken()
            val resp = YoutubeDL.getInstance().execute(req, processId, null)
            parsePrintOutput(resp.out)
        } catch (e: Exception) {
            variantLog.add("playlist-extract-failed:${e.javaClass.simpleName}:${e.message?.take(500)}")
            emptyList()
        }
    }

    private fun parsePrintOutput(out: String): List<ibytsync.core.matching.YtCandidate> {
        val list = mutableListOf<ibytsync.core.matching.YtCandidate>()
        for (line in out.lines()) {
            val p = line.split("|||")
            if (p.size < 3) continue
            val id = p[0].trim()
            if (id.isEmpty()) continue
            val dur = p[2].trim().toDoubleOrNull() ?: 0.0
            val views = if (p.size > 4) p[4].trim().toLongOrNull() ?: 0L else 0L
            list.add(
                ibytsync.core.matching.YtCandidate(
                    id = id,
                    title = p[1].trim(),
                    channel = if (p.size > 3) p[3].trim() else "",
                    duration = dur,
                    viewCount = views
                )
            )
        }
        return list
    }

    companion object {
        fun newToken(): String = "tb_" + UUID.randomUUID().toString().take(8)
    }
}
