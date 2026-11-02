package io.nekohasekai.sfa.feedback

import io.nekohasekai.sfa.managed.ManagedEndpoint
import io.nekohasekai.sfa.managed.ManagedCredential
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

data class FeedbackItem(val number: String, val description: String, val status: String, val reply: String, val fixedVersion: String) {
    val statusLabel: String get() = when (status) {
        "new" -> "Awaiting investigation"
        "investigating" -> "Investigating"
        "needs_info" -> "More information needed"
        "ready" -> "Fix awaiting release"
        "resolved" -> "Released"
        else -> "Processing"
    }
}

class FeedbackApi(
    private val endpoint: ManagedEndpoint,
    private val credential: String,
    private val requestTimeoutMillis: Long = 90_000L,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun submit(payload: JSONObject): String = call(payload).optString("number").also {
        if (!it.matches(Regex("FB-[0-9]{6,20}"))) throw FeedbackFailure("No valid receipt was received. Your draft is saved. Try again.")
    }

    fun history(): List<FeedbackItem> {
        val items = call(null).getJSONArray("items")
        require(items.length() <= 50) { "Feedback list is too large" }
        return (0 until items.length()).map {
            val item = items.getJSONObject(it)
            FeedbackItem(item.getString("number"), item.getString("description"), item.getString("status"), item.optString("reply"), item.optString("fixedVersion"))
        }
    }

    private fun call(payload: JSONObject?): JSONObject {
        if (!ManagedCredential.valid(credential)) throw FeedbackFailure("This device is not authorized to submit feedback. Contact the administrator.")
        val connection = connectionFactory(URL(endpoint.url("/api/client/feedback")))
        val timedOut = AtomicBoolean(false)
        // HttpURLConnection has no write timeout. Closing the connection also releases a
        // stalled image upload, so a bad connection cannot leave the form busy indefinitely.
        val deadline = deadlines.schedule({
            timedOut.set(true)
            connection.disconnect()
        }, requestTimeoutMillis, TimeUnit.MILLISECONDS)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = if (payload == null) "GET" else "POST"
            connection.setRequestProperty("Authorization", "Bearer $credential")
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > 1_000_000) throw FeedbackFailure("Feedback is too large. Remove some images and try again.")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            if (code !in 200..201) {
                // Only translate known messages. A proxy or server error must never reveal an
                // address, credential, traceback or node configuration in the App.
                val error = runCatching {
                    connection.errorStream?.use { JSONObject(readLimited(it, 16_384)).optString("error") }
                }.getOrNull()
                throw FeedbackFailure(when (code) {
                    400 -> when (error) {
                        "\u53cd\u9988\u6587\u672c\u542b\u65e0\u6548\u5b57\u7b26" -> "The description contains unsupported characters. Edit it and try again."
                        "\u53cd\u9988\u6587\u672c\u957f\u5ea6\u4e0d\u7b26\u5408\u8981\u6c42" -> "Check the description length and try again."
                        "\u56fe\u7247\u683c\u5f0f\u65e0\u6548", "\u56fe\u7247\u7f16\u7801\u65e0\u6548", "\u8bf7\u4f7f\u7528\u6709\u6548\u56fe\u7247", "\u56fe\u7247\u5c3a\u5bf8\u65e0\u6548" -> "Invalid image. Add it again."
                        "\u6700\u591a\u6dfb\u52a0\u4e09\u5f20\u56fe\u7247" -> "Add up to three images."
                        "\u53cd\u9988\u5b57\u6bb5\u4e0d\u5b8c\u6574\u6216\u542b\u4e0d\u652f\u6301\u7684\u5b57\u6bb5", "\u53cd\u9988\u5b57\u6bb5\u65e0\u6548" -> "The feedback service does not support this content. Contact the administrator."
                        else -> "Invalid feedback. Edit it and try again."
                    }
                    401, 403 -> "This device is not authorized to submit feedback. Contact the administrator."
                    404, 405 -> "Feedback is not enabled on the server. Try again later."
                    409 -> "Feedback content changed. Edit and submit it again."
                    413 -> "Feedback is too large. Remove some images and try again."
                    415 -> "Feedback service format mismatch. Contact the administrator."
                    429 -> "Too many submissions. Try again later."
                    500, 502, 503, 504 -> "The feedback service is unavailable. Try again later."
                    in 300..399 -> "The feedback service URL changed. Contact the administrator."
                    else -> "Unexpected feedback service response. Try again later."
                }, code)
            }
            return connection.inputStream.use { JSONObject(readLimited(it, 2_000_000)) }
        } catch (_: SocketTimeoutException) {
            throw FeedbackFailure("Submission timed out. Your draft is saved. Try again.")
        } catch (_: UnknownHostException) {
            throw FeedbackFailure("Cannot reach the feedback service. Check the network and try again.")
        } catch (_: SSLException) {
            throw FeedbackFailure("Cannot securely connect to the feedback service. Try again later.")
        } catch (_: IOException) {
            throw FeedbackFailure(if (timedOut.get()) "Submission timed out. Your draft is saved. Try again." else "Connection interrupted. Your draft is saved. Try again.")
        } catch (_: JSONException) {
            throw FeedbackFailure("No valid receipt was received. Your draft is saved. Try again.")
        } finally {
            deadline.cancel(false)
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream, limit: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (output.size() + n > limit) throw FeedbackFailure("Invalid feedback service response. Try again later.")
            output.write(buffer, 0, n)
        }
        return output.toString("UTF-8")
    }

    private companion object {
        val deadlines = Executors.newScheduledThreadPool(1) { task ->
            Thread(task, "kone-feedback-deadline").apply { isDaemon = true }
        }
    }
}

class FeedbackFailure(message: String, val httpStatus: Int? = null) : Exception(message)
