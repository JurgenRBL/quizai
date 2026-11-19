package com.quizai.mobile

import org.junit.Assert.*
import org.junit.Test

class PreviewGeometryTest {
    @Test fun portraitPreviewDoesNotRotateSensorTwiceOrStretchImage() {
        for (sensor in listOf(90, 270)) {
            val fit = PreviewGeometry.fit(420, 660, 1280, 960, sensor, 0)
            assertEquals(0f, fit.rotation, 0.001f)
            assertEquals(960f / 1280f, (420 * fit.scaleX) / (660 * fit.scaleY), 0.001f)
            assertTrue(fit.scaleX <= 1f && fit.scaleY <= 1f)
        }
    }

    @Test fun landscapeCompensatesDisplayRotationAndKeepsAspect() {
        val fit = PreviewGeometry.fit(660, 420, 1280, 960, 90, 90)
        assertEquals(-90f, fit.rotation, 0.001f)
        assertEquals(1280f / 960f, (420 * fit.scaleY) / (660 * fit.scaleX), 0.001f)
    }

    @Test fun jpegUsesSensorAndDisplayRotationIndependentlyOfPreview() {
        assertEquals(90, PreviewGeometry.jpegRotation(90, 0))
        assertEquals(0, PreviewGeometry.jpegRotation(90, 90))
        assertEquals(180, PreviewGeometry.jpegRotation(90, 270))
        assertEquals(270, PreviewGeometry.jpegRotation(270, 0))
    }
}
