package org.thisisthepy.compose.window.macos

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The caption is measured again a bounded number of times, and only while it reads zero. */
class CaptionRetryTest {
    @Test
    fun retriesAtMostThreeTimesWhileTheHeightIsZero() {
        assertTrue(shouldRetryCaptionMeasure(0, 0f, true))
        assertTrue(shouldRetryCaptionMeasure(1, 0f, true))
        assertTrue(shouldRetryCaptionMeasure(2, 0f, true))
        assertFalse(shouldRetryCaptionMeasure(3, 0f, true))
    }

    @Test
    fun stopsOnceAHeightWasMeasured() {
        assertFalse(shouldRetryCaptionMeasure(0, 28f, true))
    }

    @Test
    fun neverRetriesForAChromeThatIsNotFullSize() {
        assertFalse(shouldRetryCaptionMeasure(0, 0f, false))
    }
}
