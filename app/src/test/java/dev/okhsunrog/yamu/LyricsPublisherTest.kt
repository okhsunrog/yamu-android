package dev.okhsunrog.yamu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LyricsPublisherTest {
    @Test
    fun preservesNestedCollectionDirectories() {
        assertEquals(
            listOf("Artist - Album", "CD1"),
            LyricsPublisher.directorySegments("Artist - Album/CD1"),
        )
    }

    @Test
    fun rejectsParentDirectoryTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            LyricsPublisher.directorySegments("Album/../Outside")
        }
    }
}
