package io.nekohasekai.sfa.compose.theme

/** Pixel geometry shared by the photo renderer and the measured reading frame. */
internal object BrightSceneGeometry {
    fun photoHeight(viewHeight: Float, readingTop: Float?): Float =
        (readingTop ?: (viewHeight * 0.44f)).coerceIn(1f, viewHeight.coerceAtLeast(1f))

    fun photoTransform(
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
        readingTop: Float?,
        focus: BackdropFocus,
    ): BackdropTransform = BackdropFraming.transform(
        imageWidth, imageHeight, viewWidth, photoHeight(viewHeight, readingTop),
        focus,
    )
}
