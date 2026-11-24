package io.github.jackwener.dsh.feature.voice

import io.github.jackwener.dsh.feature.voice.Endpointer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointerTest {
    private fun Endpointer.feedAll(vararg runs: Pair<Float, Int>): Decision {
        var d = Decision.Continue
        for ((db, n) in runs) repeat(n) { if (d == Decision.Continue) d = feed(db) }
        return d
    }

    @Test fun speechThenSilenceEnds() {
        val e = Endpointer()
        assertEquals(Decision.Continue, e.feedAll(-70f to 5, -30f to 10))
        assertTrue(e.heard)
        assertTrue(e.level > 5f)
        assertEquals(Decision.Continue, e.feedAll(-70f to 11))
        assertEquals(Decision.End, e.feed(-70f))
    }

    @Test fun pausesShorterThanTheSilenceKeepListening() {
        val e = Endpointer()
        assertEquals(Decision.Continue, e.feedAll(-70f to 3, -30f to 5, -70f to 8, -30f to 5, -70f to 8))
    }

    @Test fun nothingSaidGivesUp() {
        val e = Endpointer(noSpeechMs = 3000)
        assertEquals(Decision.NoSpeech, e.feedAll(-70f to 40))
        assertFalse(e.heard)
    }

    @Test fun steadyNoiseIsNotSpeech() {
        // A loud but constant background (fan, car) becomes the floor instead of counting as speech.
        val e = Endpointer(noSpeechMs = 5000)
        assertEquals(Decision.NoSpeech, e.feedAll(-40f to 60))
    }

    @Test fun capsOneUtterance() {
        val e = Endpointer(maxMs = 2000)
        assertEquals(Decision.End, e.feedAll(-70f to 2, -30f to 30))
    }
}
