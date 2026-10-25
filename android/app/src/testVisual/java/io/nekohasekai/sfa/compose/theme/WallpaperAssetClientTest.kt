package io.nekohasekai.sfa.compose.theme

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WallpaperAssetClientTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val client = WallpaperAssetClient(context)
    private fun resource(path: String) = client.resource(Uri.parse("https://appassets.androidplatform.net/wallpaper/$path"))

    @Test fun packagedLibraryContainsEveryApprovedDayAndNightImage() {
        val manifest = JSONObject(resource("images.json").data.bufferedReader().readText())
        val catalog = JSONArray(resource("catalog.json").data.bufferedReader().readText())
        assertEquals(302, catalog.length())
        val countries = mutableSetOf<String>()
        val references = mutableSetOf<String>()
        for (index in 0 until catalog.length()) {
            val scene = catalog.getJSONObject(index)
            countries += scene.getString("country")
            references += scene.getJSONObject("assets").getString("day")
            references += scene.getJSONObject("assets").getString("night")
        }
        assertEquals(31, countries.size)
        assertEquals(604, references.size)
        val files = manifest.getJSONArray("files")
        var bytes = 0L
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index)
            val path = entry.getString("path")
            assertTrue(references.remove(path))
            val response = resource(path)
            assertEquals("image/webp", response.mimeType)
            val content = response.data.use { it.readBytes() }
            assertEquals(entry.getInt("bytes"), content.size)
            val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
            assertEquals(entry.getString("sha256"), digest)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(content, 0, content.size, bounds)
            assertTrue(bounds.outWidth in 1..960)
            assertTrue(bounds.outHeight in 1..1920)
            bytes += content.size
        }
        assertTrue(references.isEmpty())
        assertEquals(manifest.getLong("bytes"), bytes)
        assertTrue(bytes < 80L * 1024 * 1024)
    }

    @Test fun rendererLoadsLocalEntryAndDependenciesWithTheirMimeTypes() {
        val html = resource("index.html")
        assertEquals("text/html", html.mimeType)
        val text = html.data.bufferedReader().readText()
        assertTrue(text.contains("connect-src 'self'"))
        val scripts = Regex("src=\"([^\"]+\\.js)\"").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue(scripts.size >= 10)
        scripts.forEach { assertEquals("application/javascript", resource(it).mimeType) }
        assertEquals("text/css", resource("wallpaper.css").mimeType)
        assertEquals("application/json", resource("motion.json").mimeType)
        assertEquals("application/json", resource("life.json").mimeType)
    }

    @Test fun requestsCannotEscapeTheBundledWallpaperDirectoryOrUseTheNetwork() {
        listOf(
            "https://example.com/wallpaper/catalog.json",
            "http://appassets.androidplatform.net/wallpaper/catalog.json",
            "https://appassets.androidplatform.net:443/wallpaper/catalog.json",
            "https://appassets.androidplatform.net/wallpaper/../catalog.json",
            "https://appassets.androidplatform.net/wallpaper/%2e%2e/catalog.json",
            "https://appassets.androidplatform.net/wallpaper/%252e%252e/catalog.json",
            "https://appassets.androidplatform.net/other/catalog.json",
            "file:///etc/hosts", "content://wallpaper/catalog.json",
        ).forEach { assertEquals(it, 403, client.resource(Uri.parse(it)).statusCode) }
        assertEquals(403, resource("missing.js").statusCode)
        assertTrue(client.shouldOverrideUrlLoading(null, null as android.webkit.WebResourceRequest?))
    }
}
