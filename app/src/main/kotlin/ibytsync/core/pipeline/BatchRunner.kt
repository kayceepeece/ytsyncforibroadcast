package ibytsync.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

const val DOWNLOAD_TIMEOUT_MS = 90_000L
const val SEARCH_TIMEOUT_MS = 30_000L

data class BatchProgress(
    val done: Int,
    val total: Int,
    val currentTitle: String,
    val currentState: String
)

sealed interface BatchEvent {
    data class RowUpdate(val row: QueueRow) : BatchEvent
    data class Progress(val progress: BatchProgress) : BatchEvent
    data class Finished(val summary: ibytsync.core.upload.BatchSummary) : BatchEvent
    data class Cancelled(val completedRows: List<QueueRow>) : BatchEvent
}

class SerialDownloadDispatcher(
    private val downloader: ibytsync.core.download.YtDlpEngine
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun download(
        request: ibytsync.core.download.DownloadRequest,
        timeoutMs: Long = DOWNLOAD_TIMEOUT_MS
    ): ibytsync.core.download.DownloadResult? {
        val deferred = scope.async(Dispatchers.IO) {
            mutex.withLock {
                downloader.download(request)
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            try {
                deferred.cancel()
            } catch (_: Exception) {
            }
            null
        } finally {
            if (deferred.isActive) {
                try {
                    deferred.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun describe(
        url: String,
        timeoutMs: Long = SEARCH_TIMEOUT_MS
    ): ibytsync.core.metadata.YtExtract? = mutex.withLock {
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { downloader.describeVideo(url) }
        }
    }

    suspend fun search(
        query: String,
        count: Int,
        searchFn: suspend (String, Int) -> List<ibytsync.core.matching.YtCandidate>,
        timeoutMs: Long = SEARCH_TIMEOUT_MS
    ): List<ibytsync.core.matching.YtCandidate> = mutex.withLock {
        withTimeoutOrNull(timeoutMs) { searchFn(query, count) } ?: emptyList()
    }
}

class BatchRunner(
    private val orchestratorFactory: () -> BatchOrchestrator,
    private val tmpDir: File
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val currentJob = AtomicReference<Job?>(null)
    private val cancelFlag = AtomicBoolean(false)
    private val runId = AtomicReference<String?>(null)

    fun isRunning(): Boolean = currentJob.get()?.isActive == true

    fun runBatch(
        rows: List<QueueRow>,
        cfg: BatchConfig,
        onEvent: (BatchEvent) -> Unit
    ): String {
        cancelFlag.set(false)
        val id = "run_" + UUID.randomUUID().toString().take(8)
        runId.set(id)
        val orchestrator = orchestratorFactory()
        val summary = ibytsync.core.upload.BatchSummary()
        val finished = mutableListOf<QueueRow>()
        val job = scope.launch {
            val events = Channel<BatchEvent>(Channel.UNLIMITED)
            val drain = launch {
                for (e in events) {
                    try {
                        onEvent(e)
                    } catch (_: Exception) {
                    }
                }
            }
            var terminal: BatchEvent? = null
            try {
                rows.forEachIndexed { i, row ->
                    ensureNotCancelled(events, finished, summary)
                    val cur = row.copy(status = RowStatus.DOWNLOADING, progress = ProgressPhases.DOWNLOAD_START, detail = ProgressPhases.formatDownloadDetail(0f, null, row.durationMs))
                    events.trySend(BatchEvent.RowUpdate(cur))
                    events.trySend(
                        BatchEvent.Progress(
                            BatchProgress(i, rows.size, rowTitle(row), cur.status.label)
                        )
                    )
                    val (next, rowSummary) = runRowCancellable(orchestrator, row, tmpDir, cfg) { rowUpdate ->
                        events.trySend(BatchEvent.RowUpdate(rowUpdate))
                    }
                    if (cancelFlag.get()) throw CancellationException("batch cancelled")
                    summary.uploaded += rowSummary.uploaded
                    summary.alreadyExisted += rowSummary.alreadyExisted
                    summary.failed += rowSummary.failed
                    summary.cancelled += rowSummary.cancelled
                    finished.add(next)
                    events.trySend(BatchEvent.RowUpdate(next))
                    events.trySend(
                        BatchEvent.Progress(
                            BatchProgress(i + 1, rows.size, rowTitle(row), next.status.label)
                        )
                    )
                }
                terminal = BatchEvent.Finished(summary)
            } catch (e: CancellationException) {
                val marked = finished.toList() +
                    rows.drop(finished.size).map { it.copy(status = RowStatus.CANCELLED_UPLOAD, detail = PhaseDetails.CANCELLED) }
                summary.cancelled = rows.size - finished.size
                terminal = BatchEvent.Cancelled(marked)
                throw e
            } finally {
                events.close()
                withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        drain.join()
                    } catch (_: Exception) {
                    }
                    terminal?.let {
                        try {
                            onEvent(it)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        currentJob.set(job)
        return id
    }

    private suspend fun ensureNotCancelled(
        events: Channel<BatchEvent>,
        finished: List<QueueRow>,
        summary: ibytsync.core.upload.BatchSummary
    ) {
        currentCoroutineContext().ensureActive()
        if (cancelFlag.get()) {
            throw CancellationException("batch cancelled")
        }
    }

    private suspend fun runRowCancellable(
        orchestrator: BatchOrchestrator,
        row: QueueRow,
        tmpDir: File,
        cfg: BatchConfig,
        onRowUpdate: ((QueueRow) -> Unit)? = null
    ): Pair<QueueRow, ibytsync.core.upload.BatchSummary> {
        val worker = scope.async(Dispatchers.IO) {
            orchestrator.runRow(row, tmpDir, cfg, onRowUpdate)
        }
        worker.invokeOnCompletion { cause ->
            if (cause is CancellationException && cancelFlag.get()) {
            }
        }
        return try {
            while (worker.isActive) {
                if (cancelFlag.get()) {
                    worker.cancel(CancellationException("batch cancelled"))
                    throw CancellationException("batch cancelled")
                }
                if (!currentCoroutineContext().isActive) {
                    worker.cancel(CancellationException("batch cancelled"))
                    throw CancellationException("batch cancelled")
                }
                delay(50)
            }
            try {
                worker.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                row.copy(status = RowStatus.FAILED_UPLOAD) to
                    ibytsync.core.upload.BatchSummary(failed = 1)
            }
        } finally {
            if (worker.isActive) {
                try {
                    worker.cancel(CancellationException("batch cancelled"))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun rowTitle(row: QueueRow): String {
        val t = "${row.artist} ${row.title}".trim()
        return t.ifEmpty { row.sourceInput.take(60) }
    }

    suspend fun cancelAndJoin() {
        cancelFlag.set(true)
        val job = currentJob.get()
        try {
            job?.cancel(CancellationException("batch cancelled"))
        } catch (_: Exception) {
        }
        try {
            withTimeoutOrNull(15_000L) {
                job?.join()
            }
        } catch (_: Exception) {
        } finally {
            currentJob.set(null)
            runId.set(null)
        }
    }
}
