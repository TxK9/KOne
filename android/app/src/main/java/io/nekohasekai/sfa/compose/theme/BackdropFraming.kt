package io.nekohasekai.sfa.compose.theme

import kotlin.math.max

data class BackdropFocus(val x: Float = 0.5f, val y: Float = 0.5f)

internal data class BackdropTransform(val scale: Float, val x: Float, val y: Float, val width: Float, val height: Float)

internal object BackdropFraming {
    fun transform(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float, focus: BackdropFocus): BackdropTransform {
        require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        // Float scale multiplication can undershoot the viewport by a fraction
        // of a pixel; never pass a positive lower bound to coerceIn(..., 0f).
        val x = (viewWidth / 2f - width * focus.x.coerceIn(0f, 1f)).coerceIn(minOf(viewWidth - width, 0f), 0f)
        val desiredY = viewHeight / 2f - height * focus.y.coerceIn(0f, 1f)
        // The original photograph must cover the entire viewport. A focal
        // point may move the crop, but cannot expose a fabricated extension.
        val y = desiredY.coerceIn(minOf(viewHeight - height, 0f), 0f)
        return BackdropTransform(scale, x, y, width, height)
    }
}
