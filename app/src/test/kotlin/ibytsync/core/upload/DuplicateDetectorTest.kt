package ibytsync.core.upload

import org.junit.Assert.*
import org.junit.Test

class DuplicateDetectorTest {

    @Test
    fun exactTitleAndArtistMatches() {
        val tracks = listOf(
            LibraryTrackInfo("Sere", "SPINALL", lengthSec = 186)
        )
        val match = DuplicateDetector.findDuplicate("Sere", "SPINALL", durationMs = 186000L, tracks)
        assertNotNull(match)
        assertTrue(match!!.isDuplicate)
        assertEquals("Sere", match.matchedTitle)
        assertEquals(186, match.durationSec)
    }

    @Test
    fun remixDoesNotMatchOriginal() {
        val tracks = listOf(
            LibraryTrackInfo("Sere", "SPINALL", lengthSec = 186)
        )
        // Remix has different title and different duration (195s vs 186s)
        val match = DuplicateDetector.findDuplicate("Sere - Remix", "SPINALL, Fireboy DML, 6LACK", durationMs = 195000L, tracks)
        assertNull(match)
    }

    @Test
    fun titleSubstringDoesNotMatch() {
        val tracks = listOf(
            LibraryTrackInfo("Bad", "Michael Jackson", lengthSec = 247)
        )
        // "Bad Blood" should not match "Bad"
        val match = DuplicateDetector.findDuplicate("Bad Blood", "Taylor Swift", durationMs = 211000L, tracks)
        assertNull(match)

        // Even with same artist name substring
        val match2 = DuplicateDetector.findDuplicate("Bad Guy", "Michael Jackson", durationMs = 247000L, tracks)
        assertNull(match2)
    }

    @Test
    fun durationMismatchPreventsDuplicateMatch() {
        val tracks = listOf(
            LibraryTrackInfo("Song Title", "Artist", lengthSec = 180)
        )
        // 9 seconds difference (> 5s) -> not a duplicate
        val match = DuplicateDetector.findDuplicate("Song Title", "Artist", durationMs = 189000L, tracks)
        assertNull(match)

        // 4 seconds difference (<= 5s) -> duplicate
        val matchClose = DuplicateDetector.findDuplicate("Song Title", "Artist", durationMs = 184000L, tracks)
        assertNotNull(matchClose)
        assertTrue(matchClose!!.isDuplicate)
    }

    @Test
    fun officialAudioAndVideoTagsNormalized() {
        val tracks = listOf(
            LibraryTrackInfo("Feels Like Summer", "Childish Gambino", lengthSec = 297)
        )
        val match = DuplicateDetector.findDuplicate(
            "Feels Like Summer (Official Video)",
            "Childish Gambino",
            durationMs = 298000L,
            tracks
        )
        assertNotNull(match)
        assertTrue(match!!.isDuplicate)
    }

    @Test
    fun quotesAndPunctuationNormalized() {
        val tracks = listOf(
            LibraryTrackInfo("Can't Feel My Face", "The Weeknd", lengthSec = 213)
        )
        val match = DuplicateDetector.findDuplicate(
            "Cant Feel My Face",
            "The Weeknd",
            durationMs = 214000L,
            tracks
        )
        assertNotNull(match)
        assertTrue(match!!.isDuplicate)
    }

    @Test
    fun collaboratorArtistMatches() {
        val tracks = listOf(
            LibraryTrackInfo("Collabo", "P-Square", lengthSec = 224)
        )
        val match = DuplicateDetector.findDuplicate(
            "Collabo",
            "P-Square, Don Jazzy",
            durationMs = 224000L,
            tracks
        )
        assertNotNull(match)
        assertTrue(match!!.isDuplicate)
    }
}
