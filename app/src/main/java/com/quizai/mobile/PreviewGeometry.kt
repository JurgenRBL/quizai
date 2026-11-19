package com.quizai.mobile

internal data class PreviewTransform(val scaleX: Float, val scaleY: Float, val rotation: Float)

internal object PreviewGeometry {
    fun jpegRotation(sensorDegrees: Int, displayDegrees: Int) = (sensorDegrees - displayDegrees + 360) % 360

    /** TextureView already applies sensor rotation. Only undo display rotation and fit aspect. */
    fun fit(width: Int, height: Int, bufferWidth: Int, bufferHeight: Int, sensorDegrees: Int, displayDegrees: Int): PreviewTransform {
        val naturalWidth = if (sensorDegrees % 180 == 0) bufferWidth else bufferHeight
        val naturalHeight = if (sensorDegrees % 180 == 0) bufferHeight else bufferWidth
        val rotated = displayDegrees % 180 != 0
        val targetWidth = if (rotated) naturalHeight else naturalWidth
        val targetHeight = if (rotated) naturalWidth else naturalHeight
        val fit = minOf(width.toFloat() / targetWidth, height.toFloat() / targetHeight)
        return PreviewTransform(naturalWidth * fit / width, naturalHeight * fit / height, -displayDegrees.toFloat())
    }
}
