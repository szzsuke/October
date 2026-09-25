package com.metrolist.music.converter

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistConverterTest {

    @Test
    fun `parseCsvRow handles standard and quoted comma separated values`() {
        val row1 = "Track Name,Artist Name,Album Name,180000,spotify:track:12345"
        val parsed1 = PlaylistConverter.parseCsvRow(row1)
        assertEquals(listOf("Track Name", "Artist Name", "Album Name", "180000", "spotify:track:12345"), parsed1)

        val row2 = """"Song, With Comma","Artist, The Duo","Album, Deluxe",210000,spotify:track:abc"""
        val parsed2 = PlaylistConverter.parseCsvRow(row2)
        assertEquals(listOf("Song, With Comma", "Artist, The Duo", "Album, Deluxe", "210000", "spotify:track:abc"), parsed2)

        val row3 = """"Song with ""Escaped"" Quotes",Artist Name,Album Name"""
        val parsed3 = PlaylistConverter.parseCsvRow(row3)
        assertEquals(listOf("""Song with "Escaped" Quotes""", "Artist Name", "Album Name"), parsed3)
    }

    @Test
    fun `scoreCandidate rewards artist and title match and ATV release`() {
        val source = PlaylistConverter.ConvertedTrack(
            title = "Starboy",
            artist = "The Weeknd",
            durationMs = 230000,
            spotifyId = "xyz"
        )

        val perfectCandidate = SongItem(
            id = "yt_starboy",
            title = "Starboy",
            artists = listOf(Artist("The Weeknd - Topic", "a1")),
            thumbnail = "thumb.jpg",
            duration = 230,
            musicVideoType = "MUSIC_VIDEO_TYPE_ATV"
        )

        val scorePerfect = PlaylistConverter.scoreCandidate(source, perfectCandidate)
        assertTrue("Expected high score for perfect ATV candidate, got $scorePerfect", scorePerfect >= 100)
    }

    @Test
    fun `scoreCandidate penalizes unsolicited remix or cover versions`() {
        val source = PlaylistConverter.ConvertedTrack(
            title = "Blinding Lights",
            artist = "The Weeknd",
            durationMs = 200000,
            spotifyId = "bl123"
        )

        val remixCandidate = SongItem(
            id = "yt_remix",
            title = "Blinding Lights (Remix)",
            artists = listOf(Artist("The Weeknd", "a1")),
            thumbnail = "thumb.jpg",
            duration = 200
        )

        val originalCandidate = SongItem(
            id = "yt_orig",
            title = "Blinding Lights",
            artists = listOf(Artist("The Weeknd", "a1")),
            thumbnail = "thumb.jpg",
            duration = 200
        )

        val scoreRemix = PlaylistConverter.scoreCandidate(source, remixCandidate)
        val scoreOrig = PlaylistConverter.scoreCandidate(source, originalCandidate)

        assertTrue("Original candidate should score significantly higher than remix", scoreOrig > scoreRemix)
    }
}
