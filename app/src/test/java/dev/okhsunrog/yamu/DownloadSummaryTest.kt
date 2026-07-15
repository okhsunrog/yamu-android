package dev.okhsunrog.yamu

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSummaryTest {
    @Test
    fun reportsSkippedCollectionTracks() {
        val download = PublishedDownload(
            title = "esk8",
            location = "Music/Ya Music/esk8",
            fileCount = 66,
            skippedCount = 1,
            isCollection = true,
            shareTrack = null,
        )

        assertEquals(
            "esk8 · 66 треков · пропущено: 1\nMusic/Ya Music/esk8",
            collectionDownloadDetail(download),
        )
    }
}
