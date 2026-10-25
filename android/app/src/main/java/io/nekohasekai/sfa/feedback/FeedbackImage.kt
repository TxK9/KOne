package io.nekohasekai.sfa.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.media.ExifInterface
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object FeedbackImage {
    const val MAX_BYTES = 192 * 1024
    fun read(context: Context, uri: Uri): JSONObject {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot read the image" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600) inSampleSize *= 2
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Cannot read the image")
        val orientation = resolver.openInputStream(uri)?.use { stream -> runCatching {
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL
        val transform = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
        if (rotated !== decoded) decoded.recycle()
        var current = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        Canvas(current).apply { drawColor(Color.WHITE); drawBitmap(rotated, 0f, 0f, null) }
        rotated.recycle()
        try {
            while (true) {
                for (quality in listOf(88, 72, 56)) {
                    val output = ByteArrayOutputStream()
                    check(current.compress(Bitmap.CompressFormat.JPEG, quality, output))
                    if (output.size() <= MAX_BYTES) return JSONObject().put("mime", "image/jpeg")
                        .put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
                }
                require(minOf(current.width, current.height) > 160) { "Image is too large. Choose another image." }
                val smaller = Bitmap.createScaledBitmap(current, (current.width * .75f).toInt(), (current.height * .75f).toInt(), true)
                current.recycle(); current = smaller
            }
        } finally { current.recycle() }
    }
}
