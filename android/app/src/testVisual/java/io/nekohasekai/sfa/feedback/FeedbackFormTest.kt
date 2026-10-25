package io.nekohasekai.sfa.feedback

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.screen.feedback.FeedbackForm
import io.nekohasekai.sfa.compose.screen.feedback.FeedbackProblem
import io.nekohasekai.sfa.compose.screen.feedback.FeedbackReceipt
import io.nekohasekai.sfa.compose.screen.dashboard.SwipeNode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w320dp-h720dp-xhdpi")
class FeedbackFormTest {
 @get:Rule val compose=createComposeRule()
 @Test fun onlyDescriptionImagesAndSubmitAreShownBeforeSubmission() {
  var submissions=0
  compose.setContent {
   MaterialTheme {
    var description by remember { mutableStateOf("") }
    Column(Modifier.padding(vertical=12.dp)) {
     FeedbackForm(description, emptyList(), false, null, {description=it}, {}, {}, {submissions++})
    }
   }
  }
  compose.onNodeWithText("Send").assertIsNotEnabled()
  compose.onNodeWithText("Description").performTextInput("No response after switching nodes")
  compose.onNodeWithText("Send").assertIsEnabled().performClick()
  assertEquals(1,submissions)
  for (label in listOf("My feedback","Other","Connection","Devices","KOne")) compose.onNodeWithText(label,substring=true).assertDoesNotExist()
  compose.onNodeWithContentDescription("Add image").assertIsDisplayed().assertHasClickAction()
  val output=File(System.getProperty("knine.previewDir"),"feedback-simple.png")
  output.parentFile!!.mkdirs()
  output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
 }
 @Test fun threePicturesFillTheRowAndEachCanBeRemoved() {
  val removed=mutableListOf<Int>()
  val pictures=(0 until 3).map { i ->
   val bitmap=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(60*i,120,180)) }
   val bytes=java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG,80,it) }.toByteArray()
   org.json.JSONObject().put("mime","image/jpeg").put("data",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
  }
  compose.setContent { MaterialTheme { Column(Modifier.padding(vertical=12.dp)) {
   FeedbackForm("", pictures, false, null, {}, {}, { removed+=it }, {})
  } } }
  compose.onNodeWithContentDescription("Add image").assertDoesNotExist()
  compose.onNodeWithText("Send").assertIsEnabled()
  compose.onNodeWithContentDescription("Remove image 2").performClick()
  assertEquals(listOf(1),removed)
  val output=File(System.getProperty("knine.previewDir"),"feedback-pictures.png")
  output.parentFile!!.mkdirs()
  output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
 }
 @Test fun aFailureIsItsWeatherWithTheReasonOneTapAway() {
  val reason="Too many submissions. Try again later."
  compose.setContent { MaterialTheme { Column(Modifier.padding(vertical=12.dp)) {
   FeedbackForm("No response after switching nodes", emptyList(), false, FeedbackProblem("Not sent",reason), {}, {}, {}, {})
  } } }
  compose.onNodeWithText("Not sent").assertIsDisplayed()
  compose.onNodeWithText(reason).assertDoesNotExist()
  compose.onNodeWithText("Not sent").performClick()
  compose.onNodeWithText(reason).assertIsDisplayed()
  val output=File(System.getProperty("knine.previewDir"),"feedback-failed.png")
  output.parentFile!!.mkdirs()
  output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
 }
 @Test fun aReceiptShowsItsNumberAndWhatWasSentWithItOnATap() {
  var more=0
  val metadata=org.json.JSONObject().put("appVersion","1.0.0").put("versionCode",10038)
   .put("manufacturer","Example").put("model","Phone 8").put("androidVersion","15")
  compose.setContent { MaterialTheme { Column(Modifier.padding(vertical=12.dp)) {
   FeedbackReceipt("FB-000123", metadata) { more++ }
  } } }
  compose.onNodeWithText("Sent").assertIsDisplayed()
  compose.onNodeWithText("FB-000123").assertIsDisplayed()
  compose.onNodeWithText("Example Phone 8 · Android 15",substring=true).assertDoesNotExist()
  compose.onNodeWithText("Sent").performClick()
  compose.onNodeWithText("KOne 1.0.0 · 10038",substring=true).assertIsDisplayed()
  compose.onNodeWithText("Example Phone 8 · Android 15",substring=true).assertIsDisplayed()
  val output=File(System.getProperty("knine.previewDir"),"feedback-receipt.png")
  output.parentFile!!.mkdirs()
  output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
  compose.onNodeWithText("New feedback").performClick()
  assertEquals(1,more)
 }
 @Test fun rightSwipeOnlyRevealsAndRequiresClickInBothLayoutDirections() {
  var deleted=0
  var rtl by mutableStateOf(false)
  compose.setContent { MaterialTheme {
   CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides
    if(rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr) {
    key(rtl) {
     SwipeNode(true,{deleted++}) {
      Box(Modifier.fillMaxWidth().height(60.dp).testTag("node")) { androidx.compose.material3.Text("Test node") }
     }
    }
   }
  } }
  for (direction in listOf(false,true)) {
   compose.runOnIdle { rtl=direction }
   compose.waitForIdle()
   val before=deleted
   compose.onNodeWithTag("node").performTouchInput { swipeLeft() }
   compose.waitForIdle()
   assertEquals(before,deleted)
   compose.onNodeWithText("Delete").assertDoesNotExist()
   compose.onNodeWithText("Share").assertDoesNotExist()
   compose.onNodeWithTag("node").performTouchInput { swipeRight() }
   compose.waitForIdle()
   assertEquals(before,deleted)
   compose.onNodeWithText("Share").assertDoesNotExist()
   val output=File(System.getProperty("knine.previewDir"),"node-bubbles-${if(direction) "rtl" else "ltr"}.png")
   output.parentFile!!.mkdirs()
   output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
   compose.onNodeWithText("Delete").assertIsDisplayed().performClick()
   compose.waitForIdle()
   assertEquals(before+1,deleted)
   compose.onNodeWithText("Delete").assertDoesNotExist()
   compose.onNodeWithText("Share").assertDoesNotExist()
  }
 }
 @Test fun threeDotsEditWithoutSelectingTheNode() {
  var edits=0;var selections=0
  compose.setContent { MaterialTheme {
   io.nekohasekai.sfa.compose.screen.dashboard.NodeRow(
    profile=io.nekohasekai.sfa.database.Profile(name="Test node"), selected=false, clear=true,
    onSelect={selections++},onEdit={edits++},
   )
  } }
  compose.onNodeWithContentDescription("Edit node").performClick()
  assertEquals(1,edits);assertEquals(0,selections)
 }
 @Test fun actualBackdropKeepsInputAndSubmitReadableWhileSubmitting() {
  var dark by mutableStateOf(false)
  var busy by mutableStateOf(false)
  compose.setContent {
   val wallpaper=io.nekohasekai.sfa.compose.theme.WallpaperTheme(dark)
   val paper=androidx.compose.ui.graphics.Color(wallpaper.readingSurface)
   val ink=androidx.compose.ui.graphics.Color(wallpaper.ink)
   val scheme=(if(dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()).copy(
    surface=paper,onSurface=ink,primary=ink,onPrimary=paper,background=paper,onBackground=ink)
   MaterialTheme(colorScheme=scheme) {
    CompositionLocalProvider(
      io.nekohasekai.sfa.compose.theme.LocalBackdrop provides io.nekohasekai.sfa.compose.theme.BackdropContext(null,androidx.compose.ui.geometry.Rect(0f,0f,640f,1440f),theme=wallpaper),
      androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(color=paper),
      androidx.compose.material3.LocalContentColor provides ink,
    ) {
     androidx.compose.material3.Surface(color=paper) {
      Column(Modifier.padding(vertical=12.dp)) { FeedbackForm("No response after switching nodes", emptyList(),busy,null,{},{},{},{}) }
     }
    }
   }
  }
  for(d in listOf(false,true)) for(b in listOf(false,true)) {
   compose.runOnIdle { dark=d;busy=b }
   compose.waitForIdle()
   val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
   compose.onNodeWithText("No response after switching nodes").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
   val wallpaper=io.nekohasekai.sfa.compose.theme.WallpaperTheme(d)
   assertEquals(androidx.compose.ui.graphics.Color(wallpaper.ink),layouts.first().layoutInput.style.color)
   val label=if(b) "Sending" else "Send"
   compose.onNodeWithText(label).assertIsDisplayed()
   val out=File(System.getProperty("knine.previewDir"),"feedback-${if(d) "dark" else "light"}-${if(b) "busy" else "ready"}.png")
   out.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
  }
 }
 @Test fun homeUsesOnlyAnActionableIconAndDisappearsWhenOff() {
  var state by mutableStateOf(io.nekohasekai.sfa.compose.screen.dashboard.HomeState.None)
  var taps=0
  compose.setContent { MaterialTheme {
   io.nekohasekai.sfa.compose.screen.dashboard.HomeMark(io.nekohasekai.sfa.compose.screen.dashboard.KnineHost(
    homeState=state,homeStatus="Home sign-in required",openHome={taps++}))
  } }
  compose.onNodeWithContentDescription("Home sign-in required").assertDoesNotExist()
  for(value in listOf(io.nekohasekai.sfa.compose.screen.dashboard.HomeState.NeedsAction,
   io.nekohasekai.sfa.compose.screen.dashboard.HomeState.Online,io.nekohasekai.sfa.compose.screen.dashboard.HomeState.Settling)) {
   compose.runOnIdle { state=value }
   compose.onNodeWithText("Home sign-in required").assertDoesNotExist()
   compose.onNodeWithContentDescription("Home sign-in required").performClick()
  }
  assertEquals(3,taps)
 }

}
