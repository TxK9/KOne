package io.nekohasekai.sfa.bg

import java.io.File
import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CrashReportPersistenceTest {
    private lateinit var directory: File
    private var previous: Thread.UncaughtExceptionHandler? = null
    private var delegated = 0

    @Before fun prepare() {
        // Construct/attach the final application class without calling production onCreate.
        val app = io.nekohasekai.sfa.Application()
        if (app.baseContext == null) {
            io.nekohasekai.sfa.Application::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                isAccessible = true
            }.invoke(app, RuntimeEnvironment.getApplication())
        }
        io.nekohasekai.sfa.Application.application = app
        directory = File(RuntimeEnvironment.getApplication().cacheDir, "synthetic-crash").apply { mkdirs() }
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated++ }
        CrashReportManager.install(directory, File(directory, "base").apply { mkdirs() })
    }

    @After fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
        directory.deleteRecursively()
    }

    private fun capture(failure: Throwable): Pair<String, JSONObject> {
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), failure)
        assertEquals(1, delegated)
        return File(directory, "CrashReport-JVM.log").readText() to
            JSONObject(File(directory, "CrashReport-JVM-metadata.json").readText())
    }

    @Test fun pendingReportsExcludeBearerAndNestedCauseSecrets() {
        val failure = IllegalArgumentException("Header failed: Bearer synthetic-bearer-secret",
            IllegalStateException("credential=synthetic-cause-secret"))
        failure.addSuppressed(RuntimeException("password=synthetic-suppressed-secret"))
        failure.stackTrace = arrayOf(StackTraceElement("example.Loader", "read", "Loader.kt", 42))
        val (log, metadata) = capture(failure)
        for (secret in listOf("synthetic-bearer-secret", "synthetic-cause-secret", "synthetic-suppressed-secret")) {
            assertFalse("Secret excluded from pending stack", log.contains(secret))
            assertFalse("Secret excluded from pending metadata", metadata.toString().contains(secret))
        }
        assertTrue(log.contains("IllegalArgumentException"))
        assertTrue(log.contains("Loader.kt:42"))
        assertTrue(log.contains("Caused by:"))
        assertTrue(log.contains("Suppressed:"))
    }

    @Test fun pendingMetadataStaysValidJsonWithoutJsonOrUrlCredentials() {
        val message = JSONObject().put("password", "synthetic-json-secret").put("stage", "connect").toString() +
            " https://synthetic-user:synthetic-url-secret@example.invalid/connect"
        val (log, metadata) = capture(IllegalStateException(message))
        for (secret in listOf("synthetic-json-secret", "synthetic-user", "synthetic-url-secret")) {
            assertFalse(log.contains(secret))
            assertFalse(metadata.toString().contains(secret))
        }
        assertTrue(log.contains("connect"))
        assertTrue(metadata.getString("exceptionReason").contains("connect"))
        assertEquals("java.lang.IllegalStateException", metadata.getString("exceptionName"))
    }

    @Test fun ordinaryFailureContextAndHandlerDelegationRemainIntact() {
        val message = "Bitmap allocation failed at 320 x 720"
        val (log, metadata) = capture(IllegalStateException(message))
        assertTrue(log.contains(message))
        assertEquals(message, metadata.getString("exceptionReason"))
        assertEquals("Application", metadata.getString("source"))
    }
}
