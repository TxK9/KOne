package io.nekohasekai.sfa.locale

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** An AppCompat host without the production activity, services or native initialization. */
class LocaleStorageActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(state)
    }
}

private fun fixture(): File = File(requireNotNull(System.getenv("KONE_LOCALE_FIXTURE")) {
    "Select a disposable cross-process locale fixture directory"
}).apply { mkdirs() }

private fun requireFixture() {
    assumeTrue("Select a disposable fixture directory for the two-process locale check",
        !System.getenv("KONE_LOCALE_FIXTURE").isNullOrBlank())
}

/** Use the host JVM's public process API, which is absent from Android's compile classpath. */
private fun workerProcess(): Long {
    val type = Class.forName("java.lang.ProcessHandle")
    return type.getMethod("pid").invoke(type.getMethod("current").invoke(null)) as Long
}

private fun awaitStorage(predicate: () -> Boolean) {
    val deadline = System.nanoTime() + 2_000_000_000L
    do {
        shadowOf(Looper.getMainLooper()).idle()
        if (predicate()) return
        Thread.sleep(10)
    } while (System.nanoTime() < deadline)
    assertTrue("AppCompat persisted the selected application locale", predicate())
}

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [32])
class LocalePersistenceWriteTest {
    @Before fun selectFixture() = requireFixture()
    @Test fun api32WritesTheSelectionToPrivateStorage() {
        val app = RuntimeEnvironment.getApplication()
        val host = Robolectric.buildActivity(LocaleStorageActivity::class.java).create().start().resume()
        try {
            assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
            assertEquals("zh-CN", AppCompatDelegate.getApplicationLocales().toLanguageTags())
            fun record() = app.fileList().map(app::getFileStreamPath).firstOrNull {
                it.length() in 1..4096 && it.readText().contains("application_locales=\"zh-CN\"")
            }
            awaitStorage { record() != null }
            val stored = requireNotNull(record())
            stored.copyTo(File(fixture(), "locale-record.xml"), overwrite = true)
            File(fixture(), "receipt.json").writeText(JSONObject().put("name", stored.name).put("locale", "zh-CN")
                .put("writerProcess", workerProcess()).toString())
        } finally { host.pause().stop().destroy() }
    }
}

/** Run in a separate Gradle test-worker JVM after LocalePersistenceWriteTest. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [32])
class LocalePersistenceRestoreTest {
    @Before fun selectFixture() = requireFixture()
    @Test fun freshApi32ProcessRestoresTheSelectionAndCanReturnToSystem() {
        val app = RuntimeEnvironment.getApplication()
        val receipt = JSONObject(File(fixture(), "receipt.json").readText())
        assertTrue("Restore runs in a fresh test-worker process",
            receipt.getLong("writerProcess") != workerProcess())
        val name = receipt.getString("name")
        require(File(name).name == name && '/' !in name && '\\' !in name)
        File(fixture(), "locale-record.xml").copyTo(app.getFileStreamPath(name), overwrite = true)
        assertTrue("Fresh test worker starts without an in-memory selection", AppCompatDelegate.getApplicationLocales().isEmpty)
        val host = Robolectric.buildActivity(LocaleStorageActivity::class.java).create().start().resume()
        try {
            assertEquals("zh-CN", AppCompatDelegate.getApplicationLocales().toLanguageTags())
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
            awaitStorage { !app.getFileStreamPath(name).exists() }
        } finally { host.pause().stop().destroy() }
    }
}
