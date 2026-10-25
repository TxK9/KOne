package io.nekohasekai.sfa.feedback

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FeedbackDraftTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("kone_feedback_drafts", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun failedSubmissionKeepsIdenticalPayloadAcrossRestartAndNetworkChanges() {
        val first = FeedbackDraft(context, "https://api.example.invalid", "device-a")
        first.save("connection", "Node switching fails after repeated clicks. Please investigate.")
        val payload = first.payload(JSONObject().put("network", "wifi"))
        val reloaded = FeedbackDraft(context, "https://api.example.invalid", "device-a")
        assertEquals(payload.toString(), reloaded.payload(JSONObject().put("network", "cellular")).toString())
        reloaded.save("speed", reloaded.description)
        assertNotEquals(payload.getString("requestId"), reloaded.payload(JSONObject()).getString("requestId"))
    }
    @Test fun draftsDoNotLeakBetweenBindingsAndClearAfterSuccess() {
        val first = FeedbackDraft(context, "https://one.example.invalid", "device-a")
        first.save("other", "Preserve the draft to reproduce this issue.")
        assertEquals("", FeedbackDraft(context, "https://two.example.invalid", "device-a").description)
        assertEquals("", FeedbackDraft(context, "https://one.example.invalid", "device-b").description)
        first.clear()
        assertEquals("", first.description)
    }
    @Test fun readyIsNotPresentedAsPublished() {
        assertEquals("Fix awaiting release", FeedbackItem("FB-000001", "", "ready", "", "").statusLabel)
        assertEquals("Released", FeedbackItem("FB-000001", "", "resolved", "", "10029").statusLabel)
    }
    @Test fun textAndThreeMaximumSizeImagesStayFrozenAcrossAFailedUpload() {
        val draft = FeedbackDraft(context, "https://api.example.invalid", "device-a")
        draft.save("other", "\u6d4b".repeat(4000))
        val picture = JSONObject().put("mime", "image/jpeg")
            .put("data", android.util.Base64.encodeToString(ByteArray(FeedbackImage.MAX_BYTES), android.util.Base64.NO_WRAP))
        draft.saveImages(JSONArray().put(picture).put(picture).put(picture))
        val metadata = JSONObject().put("appVersion", "1.0.0").put("versionCode", 10029)
            .put("androidVersion", "15").put("manufacturer", "Example").put("model", "Phone").put("network", "wifi")
        val original = draft.payload(metadata)
        assertTrue(original.toString().toByteArray(Charsets.UTF_8).size < 1_000_000)
        val reloaded = FeedbackDraft(context, "https://api.example.invalid", "device-a")
        assertEquals(original.toString(), reloaded.payload(JSONObject().put("network", "cellular")).toString())
        assertEquals(3, reloaded.images.length())
        reloaded.save("other", "Editing the issue must create a new request")
        assertNotEquals(original.getString("requestId"), reloaded.payload(metadata).getString("requestId"))
    }

    @Test fun olderUploadReceiptPreservesANewerDraftAndItsRequestAcrossScreenReentry() {
        val endpoint = "https://api.example.invalid"
        val oldScreen = FeedbackDraft(context, endpoint, "device-a")
        oldScreen.save("other", "Original feedback being uploaded")
        val oldPayload = oldScreen.payload(JSONObject().put("appVersion", "1.0.0").put("versionCode", 10034))

        // The original blocking request is still running when a new screen edits the draft.
        val newScreen = FeedbackDraft(context, endpoint, "device-a")
        newScreen.save("other", "New feedback edited after returning")
        val newImages = JSONArray().put(JSONObject().put("mime", "image/jpeg").put("data", "new-picture"))
        newScreen.saveImages(newImages)
        val newPayload = newScreen.payload(JSONObject().put("appVersion", "1.0.0").put("versionCode", 10034))
        assertNotEquals(oldPayload.getString("requestId"), newPayload.getString("requestId"))

        assertTrue(oldScreen.complete("FB-000001", oldPayload))
        val reopened = FeedbackDraft(context, endpoint, "device-a")
        assertEquals("New feedback edited after returning", reopened.description)
        assertEquals(newImages.toString(), reopened.images.toString())
        assertEquals(newPayload.toString(), reopened.payload(JSONObject()).toString())
        val receipt = reopened.receipt!!
        assertEquals("FB-000001", receipt.first)
        assertEquals(10034, receipt.second.getInt("versionCode"))
        assertFalse(receipt.second.has("description"))
        assertFalse(receipt.second.has("images"))
        assertFalse(receipt.second.has("requestId"))

        reopened.clearReceipt()
        assertNull(FeedbackDraft(context, endpoint, "device-a").receipt)
        assertEquals(newPayload.toString(), reopened.payload(JSONObject()).toString())
        assertNull(FeedbackDraft(context, endpoint, "device-b").receipt)
    }

    @Test fun completedMatchingDraftClearsAndReopeningRestoresOnlyItsSafeReceipt() {
        val draft = FeedbackDraft(context, "https://api.example.invalid", "device-a")
        draft.save("other", "Keep the receipt visible when returning after submission")
        draft.saveImages(JSONArray().put(JSONObject().put("mime", "image/jpeg").put("data", "old-picture")))
        val payload = draft.payload(JSONObject().put("appVersion", "1.0.0").put("versionCode", 10034)
            .put("androidVersion", "15").put("manufacturer", "Example").put("model", "Phone").put("network", "wifi"))
        assertTrue(draft.complete("FB-000002", payload))

        val reopened = FeedbackDraft(context, "https://api.example.invalid", "device-a")
        assertEquals("", reopened.description)
        assertEquals(0, reopened.images.length())
        val receipt = reopened.receipt!!
        assertEquals("FB-000002", receipt.first)
        assertEquals("Phone", receipt.second.getString("model"))
        assertEquals(setOf("appVersion", "versionCode", "androidVersion", "manufacturer", "model"), receipt.second.keys().asSequence().toSet())
        assertNull(FeedbackDraft(context, "https://other.example.invalid", "device-a").receipt)
    }
}
