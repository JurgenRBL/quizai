package com.quizai.mobile

import org.junit.Assert.*
import org.junit.Test

class HoldCaptureTest {
    @Test fun releasingBeforeTheFirstPreviewFrameCancelsWithoutSubmitting() {
        val hold = HoldCapture()
        assertFalse(hold.release())
        assertFalse(hold.previewReady())
        assertFalse(hold.acceptImage())
        assertEquals(HoldCapture.State.CANCELLED, hold.state)
    }

    @Test fun readyThenReleaseAcceptsExactlyOnePhoto() {
        val hold = HoldCapture()
        assertTrue(hold.previewReady())
        assertFalse(hold.acceptImage())
        assertTrue(hold.release())
        assertFalse(hold.release())
        assertTrue(hold.acceptImage())
        assertFalse(hold.acceptImage())
    }

    @Test fun draggingOrClosingCancelsEvenIfCameraCallbackArrivesLater() {
        for (ready in listOf(false, true)) {
            val hold = HoldCapture()
            if (ready) hold.previewReady()
            hold.cancel()
            assertFalse(hold.previewReady())
            assertFalse(hold.release())
            assertFalse(hold.acceptImage())
        }
    }

    @Test fun cancellationDuringCaptureRejectsLateImage() {
        val hold = HoldCapture()
        hold.previewReady(); hold.release(); hold.cancel()
        assertFalse(hold.acceptImage())
        val next = HoldCapture()
        assertTrue(next.previewReady())
        assertTrue(next.release())
        assertTrue(next.acceptImage())
    }
}
