package ibytsync.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import ibytsync.core.download.YtDlpAndroidImpl
import ibytsync.core.metadata.DeezerCorrection
import ibytsync.core.metadata.ITunesCorrection
import ibytsync.core.metadata.ReleaseOption
import ibytsync.core.metadata.SpotifyCorrection
import ibytsync.core.metadata.SpotifyScraper
import ibytsync.core.metadata.YouTubeHttpScraper
import ibytsync.core.metadata.YouTubeMusicSearch
import ibytsync.core.pipeline.BatchConfig
import ibytsync.core.pipeline.BatchEvent
import ibytsync.core.pipeline.BatchOrchestrator
import ibytsync.core.pipeline.BatchRunner
import ibytsync.core.pipeline.MetadataResolver
import ibytsync.core.pipeline.PipelineHooks
import ibytsync.core.pipeline.QueueRow
import ibytsync.core.pipeline.RowOutcome
import ibytsync.core.pipeline.RowStatus
import ibytsync.core.pipeline.SaveHooks
import ibytsync.core.settings.SettingsStore
import ibytsync.core.storage.DiskStore
import ibytsync.core.storage.FolderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class BatchService : Service() {

    companion object {
        const val CHANNEL_ID = "ibytsync_batch"
        const val NOTIF_ID = 41
        const val ACTION_CANCEL = "ibytsync.android.CANCEL_BATCH"
        const val ACTION_START_BATCH = "ibytsync.android.START_BATCH"
        const val ACTION_FETCH_TAGS = "ibytsync.android.FETCH_TAGS"
        const val ACTION_FINISH_FETCH_TAGS = "ibytsync.android.FINISH_FETCH_TAGS"
        private const val TAG = "BatchService"
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runner: BatchRunner? = null
    private var rows: List<QueueRow> = emptyList()
    private var latest: List<QueueRow> = emptyList()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var metadataJob: Job? = null
    private val isMetadataResolving = AtomicBoolean(false)
    private var metadataResolver: MetadataResolver? = null

    inner class LocalBinder : Binder() {
        fun service(): BatchService = this@BatchService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Batch uploads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelAllWork()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_FINISH_FETCH_TAGS) {
            if (!isRunning() && !isMetadataResolving.get()) {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val notifText = if (intent?.action == ACTION_FETCH_TAGS) "Matching tags in background…" else "Service active"
        startForegroundCompat(buildNotification(0, 0, notifText))
        acquireLocks()
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ibytsync:batch_service_wakelock")?.apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L) // 4 hours maximum timeout
                }
            }
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isWifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            if (isWifi && wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm?.createWifiLock(mode, "ibytsync:batch_wifi_lock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake/wifi locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        if (isRunning() || isMetadataResolving.get()) return
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun forceReleaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun getOrCreateResolver(): MetadataResolver {
        metadataResolver?.let { return it }
        val engine = YtDlpAndroidImpl(this)
        engine.init()
        val scraper = SpotifyScraper(SpotifyScraper.okHttpFetcher())
        val spotify = SpotifyCorrection()
        val itunes = ITunesCorrection(ITunesCorrection.okHttpFetcher())
        val deezer = DeezerCorrection(DeezerCorrection.okHttpFetcher())
        val httpScraper = YouTubeHttpScraper()
        val resolver = MetadataResolver(
            scraper,
            engine,
            listOf(spotify, itunes, deezer),
            httpScraper,
            YouTubeMusicSearch()
        )
        metadataResolver = resolver
        return resolver
    }

    fun startMetadataResolution(
        rowsToResolve: List<QueueRow>,
        onRowResolved: ((QueueRow) -> Unit)? = null,
        onAllResolved: (() -> Unit)? = null
    ) {
        if (rowsToResolve.isEmpty()) {
            onAllResolved?.invoke()
            return
        }
        acquireLocks()
        isMetadataResolving.set(true)
        val resolver = getOrCreateResolver()
        val total = rowsToResolve.size
        var done = 0

        updateNotification(0, total, "Matching tags (0/$total)…")

        metadataJob?.cancel()
        metadataJob = scope.launch(Dispatchers.IO) {
            val semaphore = Semaphore(3)
            val jobs = rowsToResolve.map { row ->
                launch {
                    semaphore.withPermit {
                        val outcome = try {
                            withTimeoutOrNull(15_000L) {
                                resolver.resolve(row)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Resolution failed for ${row.id}: ${e.message}")
                            null
                        }

                        val resolvedRow = when (outcome) {
                            is RowOutcome.Ready -> outcome.row
                            is RowOutcome.AwaitingPick -> outcome.row.copy(
                                options = if (outcome.row.options.isNotEmpty()) outcome.row.options else outcome.suggestion.options,
                                selectedOption = outcome.row.selectedOption ?: outcome.suggestion.options.firstOrNull()
                            )
                            null -> row.copy(
                                status = RowStatus.FAILED_METADATA,
                                detail = "Matching timed out",
                                selectedOption = ReleaseOption.noMetadata(row.title, row.durationMs)
                            )
                        }

                        synchronized(this@BatchService) {
                            done++
                            latest = if (latest.any { it.id == resolvedRow.id }) {
                                latest.map { if (it.id == resolvedRow.id) resolvedRow else it }
                            } else {
                                latest + resolvedRow
                            }
                            DiskStore.saveQueue(this@BatchService, latest)
                        }

                        withContext(Dispatchers.Main) {
                            updateNotification(done, total, "Matching tags ($done/$total): ${resolvedRow.title.take(40)}")
                            onRowResolved?.invoke(resolvedRow)
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
            isMetadataResolving.set(false)

            withContext(Dispatchers.Main) {
                if (!isRunning()) {
                    updateNotification(done, total, "Done — Tags ready for $total tracks")
                    releaseLocks()
                }
                onAllResolved?.invoke()
            }
        }
    }

    fun cancelMetadata() {
        metadataJob?.cancel()
        metadataJob = null
        isMetadataResolving.set(false)
        releaseLocks()
    }

    fun startBatch(
        initial: List<QueueRow>,
        cfg: BatchConfig,
        hooks: PipelineHooks? = null,
        onEvent: (BatchEvent) -> Unit
    ) {
        rows = initial
        latest = initial
        acquireLocks()

        val engine = YtDlpAndroidImpl(this)
        engine.init()
        val pipelineHooks = hooks ?: AndroidPipelineHooks(this, engine, SettingsStore(this))
        val savedTree = FolderStore.savedTree(this)
        val saveHooks = if (savedTree != null) {
            object : SaveHooks {
                override fun saveFile(tmp: File): FolderStore.SaveOutcome =
                    FolderStore.save(this@BatchService, savedTree, tmp, true)
            }
        } else null
        val tmpDir = getExternalFilesDir("youtubedl-android") ?: File(filesDir, "youtubedl-android")
        tmpDir.mkdirs()
        val runner = BatchRunner(
            {
                BatchOrchestrator(
                    downloader = engine,
                    hooks = pipelineHooks,
                    saveHooks = saveHooks,
                    streamOpener = { uriStr ->
                        try {
                            contentResolver.openInputStream(android.net.Uri.parse(uriStr))
                        } catch (_: Exception) {
                            null
                        }
                    }
                )
            },
            tmpDir
        )
        this.runner = runner
        sweepStalePartials()
        startForegroundCompat(buildNotification(0, initial.size, "Starting…"))

        runner.runBatch(initial, cfg) { event ->
            when (event) {
                is BatchEvent.RowUpdate -> {
                    latest = latest.map { if (it.id == event.row.id) event.row else it }
                    DiskStore.saveQueue(this@BatchService, latest)
                }
                is BatchEvent.Progress -> {
                    val pct = if (event.progress.total > 0) {
                        (event.progress.done * 100 / event.progress.total).coerceIn(0, 100)
                    } else 0
                    updateNotification(
                        event.progress.done, event.progress.total,
                        "${event.progress.currentTitle} — ${event.progress.currentState} ($pct%)",
                        event.progress.done, event.progress.total
                    )
                }
                is BatchEvent.Finished -> {
                    persistFinishedBatch(event.completedRows)
                    updateNotification(1, 1, event.summary.message())
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                is BatchEvent.Cancelled -> {
                    latest = event.completedRows
                    DiskStore.saveQueue(this@BatchService, latest)
                    updateNotification(0, 0, "Cancelled")
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            try {
                onEvent(event)
            } catch (_: Exception) {
            }
        }
    }

    private fun persistFinishedBatch(completedRows: List<QueueRow> = emptyList()) {
        try {
            val finished = completedRows.ifEmpty {
                latest.filter {
                    it.status == RowStatus.DONE || it.status == RowStatus.ALREADY_UPLOADED
                }
            }
            if (finished.isNotEmpty()) {
                val existing = DiskStore.loadSynced(this)
                val replacedIds = finished.mapNotNull { it.replacesUploadedTrackId }.toSet()
                val filtered = if (replacedIds.isNotEmpty()) {
                    existing.filterNot { it.ibroadcastTrackId in replacedIds || it.id in replacedIds }
                } else existing

                val ordered = finished.reversed().map { row ->
                    if (row.status == RowStatus.DONE && !row.replacesUploadedTrackId.isNullOrEmpty()) {
                        row.copy(replacesUploadedTrackId = null)
                    } else row
                }
                DiskStore.saveSynced(this, filtered + ordered)

                // Remove finished items from queue
                val finishedIds = finished.map { it.id }.toSet()
                val remaining = latest.filterNot { it.id in finishedIds }
                DiskStore.saveQueue(this, remaining)
                latest = remaining
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed persisting finished batch: ${e.message}", e)
        }
    }

    fun currentRows(): List<QueueRow> = latest

    fun isRunning(): Boolean = runner?.isRunning() == true

    fun sweepStalePartials() {
        try {
            val dir = getExternalFilesDir("youtubedl-android") ?: return
            dir.listFiles { f -> f.name.startsWith("tb_") }?.forEach { f ->
                try {
                    if (System.currentTimeMillis() - f.lastModified() > 24L * 60L * 60L * 1000L) f.delete()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun cancelBatch() {
        scope.launch {
            runner?.cancelAndJoin()
            persistFinishedBatch()
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cancelAllWork() {
        scope.launch {
            runner?.cancelAndJoin()
            metadataJob?.cancel()
            forceReleaseLocks()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(TAG, "Foreground service timeout reached (fgsType=$fgsType). Stopping cleanly.")
        cancelAllWork()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(done: Int, total: Int, text: String, progressDone: Int? = null, progressTotal: Int? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, BatchService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (total > 0) "YT Sync for iBroadcast ($done/$total)" else "YT Sync for iBroadcast"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progressDone != null && progressTotal != null && progressTotal > 0) {
            builder.setProgress(progressTotal, progressDone, false)
        }
        return builder.build()
    }

    private var lastNotifMs: Long = 0L

    private fun updateNotification(done: Int, total: Int, text: String, progressDone: Int? = null, progressTotal: Int? = null) {
        val now = System.currentTimeMillis()
        val terminal = text.startsWith("Done —") || text == "Cancelled"
        if (!terminal && now - lastNotifMs < 1000L) return
        lastNotifMs = now
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(done, total, text, progressDone, progressTotal))
    }

    override fun onDestroy() {
        forceReleaseLocks()
        scope.cancel()
        super.onDestroy()
    }
}
