package io.nekohasekai.sfa.feedback
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application=Application::class,sdk=[35])
class FeedbackImageTest {
 @Test fun selectedImageIsResizedReencodedAndDropsLocationMetadata() {
  val app:Application=RuntimeEnvironment.getApplication()
  val file=File(app.cacheDir,"synthetic-feedback.jpg")
  val bitmap=Bitmap.createBitmap(2400,1200,Bitmap.Config.ARGB_8888)
  bitmap.eraseColor(android.graphics.Color.BLUE)
  file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
  ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION,"6");setAttribute(ExifInterface.TAG_GPS_LATITUDE,"1/1,0/1,0/1");saveAttributes() }
  val result=FeedbackImage.read(app,Uri.fromFile(file))
  val bytes=Base64.decode(result.getString("data"),Base64.DEFAULT)
  assertTrue(bytes.size <= FeedbackImage.MAX_BYTES)
  assertEquals("image/jpeg",result.getString("mime"))
  val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
  assertTrue(decoded.height>decoded.width)
  assertTrue(maxOf(decoded.width,decoded.height)<=1600)
  bytes.inputStream().use { assertNull(ExifInterface(it).getAttribute(ExifInterface.TAG_GPS_LATITUDE)) }
  decoded.recycle()
 }
}
