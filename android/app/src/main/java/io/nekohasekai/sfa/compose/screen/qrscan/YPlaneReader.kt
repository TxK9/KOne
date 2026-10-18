package io.nekohasekai.sfa.compose.screen.qrscan

import java.nio.ByteBuffer

/** Pack visible Y samples without reading row padding or consuming the camera buffer. */
internal object YPlaneReader {
    fun read(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        reusable: ByteArray? = null,
    ): ByteArray {
        require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0) { "Invalid camera plane dimensions" }
        val size = width.toLong() * height
        val visibleRowBytes = (width - 1L) * pixelStride + 1L
        require(size <= Int.MAX_VALUE && visibleRowBytes <= rowStride.toLong()) { "Invalid camera plane stride" }
        val start = buffer.position().toLong()
        // Camera planes may omit the final row's trailing padding. Only the
        // final visible pixel must be present, not height * rowStride bytes.
        val end = start + (height - 1L) * rowStride + visibleRowBytes
        require(end <= buffer.limit().toLong()) { "Incomplete camera luminance plane" }
        val data = reusable?.takeIf { it.size >= size.toInt() } ?: ByteArray(size.toInt())
        val plane = buffer.duplicate()
        if (pixelStride == 1 && rowStride == width) {
            plane.get(data, 0, size.toInt())
        } else {
            for (row in 0 until height) {
                val sourceRow = (start + row.toLong() * rowStride).toInt()
                val destinationRow = row * width
                if (pixelStride == 1) {
                    plane.position(sourceRow)
                    plane.get(data, destinationRow, width)
                } else {
                    for (column in 0 until width) {
                        data[destinationRow + column] = plane.get(sourceRow + column * pixelStride)
                    }
                }
            }
        }
        return data
    }
}
