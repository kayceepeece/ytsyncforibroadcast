package ibytsync.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressDetailTest {

    @Test
    fun testStandardDownloadLine() {
        val line = "[download]  45.2% of   12.85MiB at  2.40MiB/s ETA 00:05"
        val detail = ProgressPhases.formatDownloadDetail(45.2f, line)
        assertEquals("5.8 / 12.9 MB (45%)", detail)
    }

    @Test
    fun testApproximateDownloadLine() {
        val line = "[download]  85.0% of ~  3.50MiB at  1.10MiB/s ETA 00:01"
        val detail = ProgressPhases.formatDownloadDetail(85.0f, line)
        assertEquals("3.0 / ~3.5 MB (85%)", detail)
    }

    @Test
    fun testFinishedDownloadLine() {
        val line = "[download] 100.0% of    4.12MiB in 00:02"
        val detail = ProgressPhases.formatDownloadDetail(100.0f, line)
        assertEquals("4.1 / 4.1 MB (100%)", detail)
    }

    @Test
    fun testUnknownTotalSizeLine() {
        val line = "[download]   2.40MiB at  1.20MiB/s (unknown total size)"
        val detail = ProgressPhases.formatDownloadDetail(50.0f, line)
        assertEquals("2.4 / 4.8 MB (50%)", detail)
    }

    @Test
    fun testNullLineWithDuration() {
        val detail = ProgressPhases.formatDownloadDetail(50.0f, null, durationMs = 210_000L)
        assertEquals("2.0 / ~4.0 MB (50%)", detail)
    }

    @Test
    fun testNullLineWithZeroPercent() {
        val detail = ProgressPhases.formatDownloadDetail(0.0f, null, durationMs = 210_000L)
        assertEquals("0.0 / ~4.0 MB (0%)", detail)
    }

    @Test
    fun testNullLineNoDuration() {
        val detail0 = ProgressPhases.formatDownloadDetail(0.0f, null, null)
        assertEquals("0%", detail0)

        val detail60 = ProgressPhases.formatDownloadDetail(60.0f, null, null)
        assertEquals("60%", detail60)
    }

    @Test
    fun testUploadDetail() {
        val totalBytes = (4.12 * 1024 * 1024).toLong()
        val halfBytes = totalBytes / 2

        assertEquals("0.0 / 4.1 MB (0%)", ProgressPhases.formatUploadDetail(0L, totalBytes))
        assertEquals("2.1 / 4.1 MB (50%)", ProgressPhases.formatUploadDetail(halfBytes, totalBytes))
        assertEquals("4.1 / 4.1 MB (100%)", ProgressPhases.formatUploadDetail(totalBytes, totalBytes))
        assertEquals("0%", ProgressPhases.formatUploadDetail(0L, 0L))
    }
}
