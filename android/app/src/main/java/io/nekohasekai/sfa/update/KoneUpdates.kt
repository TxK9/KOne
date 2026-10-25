package io.nekohasekai.sfa.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.nekohasekai.sfa.bg.DefaultNetworkMonitor
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class KoneUpdatePhase { Idle, WaitingForWifi, Downloading, Ready, Installing, Confirmation, Failed }

data class KoneUpdateStatus(
    val release: KoneRelease? = null,
    val phase: KoneUpdatePhase = KoneUpdatePhase.Idle,
    val progress: Float = 0f,
    val verified: Boolean = false,
    val permissionGranted: Boolean = false,
    val offerPermissionSetup: Boolean = false,
    val message: String? = null,
    val showNonce: Int = 0,
) {
    val important: Boolean get() = release?.recommendedFor(BuildConfig.VERSION_CODE, verified) == true
}

/** One lock owns download and install requests from the UI and WorkManager. */
object KoneUpdates {
    const val SHOW_UPDATE = "kone_show_update"
    private const val CHANNEL = "kone_updates"
    private const val NOTIFICATION = 10016
    private const val RETRY_INSTALL_MS = 6 * 60 * 60_000L
    val enabled: Boolean get() = BuildConfig.KONE_UPDATE_FEED.isNotBlank() && BuildConfig.FLAVOR != "play"
    private val lock = Mutex()
    private val sessionState = Any()
    private val noticeState = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(KoneUpdateStatus())
    val state = mutableState.asStateFlow()
    private var pendingConfirmation: Intent? = null
    @Volatile private var applicationContext: Context? = null
    private var nextShowNonce = 0

    private fun prefs(context: Context) = context.getSharedPreferences("kone_app_updates", Context.MODE_PRIVATE)
    private fun directory(context: Context) = File(context.cacheDir, "kone-updates").apply { mkdirs() }
    private fun apk(context: Context, release: KoneRelease) = File(directory(context), "${release.versionCode}-${release.sha256.take(16)}.apk")
    private fun foreground(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun permissionGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun start(context: Context) {
        if (!enabled) return
        val app = rememberContext(context)
        KoneUpdateWork.schedule(app)
        scope.launch {
            lock.withLock { restore(app); consumePendingOpen(app); notifyReady(app) }
            check(app)
        }
    }

    private fun rememberContext(context: Context): Context = context.applicationContext.also { applicationContext = it }

    private fun releaseKey(release: KoneRelease) = KoneUpdateNoticePolicy.releaseKey(release.versionCode, release.sha256)

    private fun remembered(context: Context, name: String): Set<String> =
        prefs(context).getStringSet(name, emptySet()).orEmpty().toSet()

    private fun remember(context: Context, name: String, key: String): Boolean =
        prefs(context).edit().putStringSet(name, remembered(context, name) + key).commit()

    private fun emitShow() {
        nextShowNonce = if (nextShowNonce == Int.MAX_VALUE) 1 else nextShowNonce + 1
        mutableState.update { if (it.release != null) it.copy(showNonce = nextShowNonce) else it }
    }

    /** Compatibility for the app-version button; the retained context is application-only. */
    fun show() {
        applicationContext?.let { requestShow(it) }
            ?: synchronized(noticeState) { emitShow() }
    }

    /** An activity/notification request only opens our dialog. It never starts an installer. */
    fun requestShow(context: Context) {
        if (!enabled) return
        val app = rememberContext(context)
        synchronized(noticeState) { prefs(app).edit().putBoolean("pending_open", true).commit() }
        scope.launch {
            val needsCheck = lock.withLock {
                restore(app)
                consumePendingOpen(app)
                state.value.release == null
            }
            if (needsCheck) check(app)
        }
    }

    private fun consumePendingOpen(context: Context) = synchronized(noticeState) {
        val release = state.value.release ?: return@synchronized
        if (!prefs(context).getBoolean("pending_open", false)) return@synchronized
        val key = releaseKey(release)
        if (!prefs(context).edit().putBoolean("pending_open", false)
                .putStringSet("prompted_releases", remembered(context, "prompted_releases") + key).commit()) return@synchronized
        emitShow()
        notificationManager(context).cancel(NOTIFICATION)
    }

    /** Call only from the resumed UI that actually contains KoneUpdatePrompt. */
    fun offerForeground(context: Context) {
        if (!enabled) return
        val app = rememberContext(context)
        synchronized(noticeState) {
            val status = state.value
            val release = status.release ?: return@synchronized
            val eligible = status.phase == KoneUpdatePhase.Ready || status.phase == KoneUpdatePhase.WaitingForWifi ||
                status.phase == KoneUpdatePhase.Confirmation
            val key = releaseKey(release)
            if (!KoneUpdateNoticePolicy.shouldPrompt(key, eligible, remembered(app, "prompted_releases"),
                    remembered(app, "dismissed_releases"))) return@synchronized
            if (remember(app, "prompted_releases", key)) {
                emitShow()
                notificationManager(app).cancel(NOTIFICATION)
            }
        }
    }

    fun dismissPrompt(context: Context) {
        val app = rememberContext(context)
        synchronized(noticeState) {
            val editor = prefs(app).edit().putBoolean("pending_open", false)
            state.value.release?.let { editor.putStringSet("dismissed_releases",
                remembered(app, "dismissed_releases") + releaseKey(it)) }
            editor.commit()
            mutableState.update { it.copy(showNonce = 0) }
            notificationManager(app).cancel(NOTIFICATION)
        }
    }

    fun needsNotificationPermission(context: Context): Boolean = enabled && Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !prefs(context).getBoolean("notification_permission_requested", false)

    fun markNotificationPermissionRequested(context: Context) {
        prefs(rememberContext(context)).edit().putBoolean("notification_permission_requested", true).commit()
    }

    fun onNotificationPermissionResult(context: Context) {
        // Only refresh update notices. This permission callback never starts a VPN or installation.
        onResume(context)
    }

    fun offerPermissionSetup(context: Context) {
        rememberContext(context)
        synchronized(noticeState) {
            mutableState.update { it.copy(offerPermissionSetup = !permissionGranted(context)) }
            emitShow()
        }
    }

    fun dismissPermissionSetup(context: Context) {
        prefs(context).edit().putBoolean("setup_seen", true).apply()
        mutableState.update { it.copy(offerPermissionSetup = false) }
    }

    fun onResume(context: Context) {
        if (!enabled) return
        val app = rememberContext(context)
        scope.launch {
            lock.withLock {
                restore(app)
                consumePendingOpen(app)
                notifyReady(app)
            }
        }
    }

    /** Only a locally verified, available package can activate a recommended-version notice. */
    fun hasImportantUpdate(context: Context): Boolean {
        if (!enabled) return false
        val release = savedRelease(context) ?: return false
        return release.recommendedFor(BuildConfig.VERSION_CODE, ready(context, release))
    }

    private fun savedRelease(context: Context): KoneRelease? = runCatching {
        prefs(context).getString("release", null)?.let { KoneRelease.parse(it, BuildConfig.KONE_UPDATE_FEED, context.packageName) }
            ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }.getOrNull()

    private fun ready(context: Context, release: KoneRelease): Boolean =
        prefs(context).getString("verified_sha", null) == release.sha256 && apk(context, release).let { it.isFile && it.length() == release.bytes }

    private fun restore(context: Context) = synchronized(noticeState) {
        rememberContext(context)
        val release = savedRelease(context)
        val verified = release != null && ready(context, release)
        val granted = permissionGranted(context)
        val session = prefs(context).getInt("session", -1)
        val sessionExists = session >= 0 && context.packageManager.packageInstaller.getSessionInfo(session) != null
        val previous = state.value
        val sameRelease = release != null && previous.release?.let { releaseKey(it) == releaseKey(release) } == true
        mutableState.value = KoneUpdateStatus(
            release = release, verified = verified, permissionGranted = granted,
            offerPermissionSetup = false,
            showNonce = if (sameRelease) previous.showNonce else 0,
            message = if (sameRelease) previous.message else null,
            phase = when {
                sessionExists && prefs(context).getBoolean("confirmation", false) -> KoneUpdatePhase.Confirmation
                sessionExists -> KoneUpdatePhase.Installing
                verified -> KoneUpdatePhase.Ready
                sameRelease && previous.phase == KoneUpdatePhase.WaitingForWifi -> KoneUpdatePhase.WaitingForWifi
                sameRelease && previous.phase == KoneUpdatePhase.Failed -> KoneUpdatePhase.Failed
                else -> KoneUpdatePhase.Idle
            },
        )
    }

    suspend fun check(context: Context, manual: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext true
        rememberContext(context)
        lock.withLock {
            try {
                val release = fetchRelease(context)
                if (release == null || release.versionCode <= BuildConfig.VERSION_CODE) {
                    clear(context)
                    prefs(context).edit().putBoolean("pending_open", false).commit()
                    return@withLock true
                }
                require(Build.VERSION.SDK_INT >= release.minSdk) { "This update does not support this Android version." }
                val sessionId = prefs(context).getInt("session", -1)
                if (sessionId >= 0 && context.packageManager.packageInstaller.getSessionInfo(sessionId) != null) {
                    val pending = savedRelease(context)
                    val waiting = prefs(context).getBoolean("confirmation", false)
                    if (!waiting || pending == null || pending.versionCode >= release.versionCode) {
                        restore(context)
                        consumePendingOpen(context)
                        notifyReady(context)
                        return@withLock true
                    }
                    // Replace an older, unconfirmed offer with the newest release.
                    synchronized(sessionState) {
                        prefs(context).edit().remove("session").putBoolean("confirmation", false).commit()
                        pendingConfirmation = null
                    }
                    runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                }
                synchronized(sessionState) {
                    prefs(context).edit().putString("release", release.toJson()).remove("session").commit()
                }
                val verified = ready(context, release)
                synchronized(noticeState) {
                    val sameRelease = state.value.release?.let { releaseKey(it) == releaseKey(release) } == true
                    if (!sameRelease) notificationManager(context).cancel(NOTIFICATION)
                    mutableState.update { it.copy(release = release, verified = verified, permissionGranted = permissionGranted(context),
                        message = null, showNonce = if (sameRelease) it.showNonce else 0,
                        phase = if (verified) KoneUpdatePhase.Ready else KoneUpdatePhase.Idle) }
                }
                if (!verified) {
                    val metered = metered(context)
                    if (metered && !release.allowMetered && !manual) {
                        mutableState.update { it.copy(phase = KoneUpdatePhase.WaitingForWifi) }
                        consumePendingOpen(context)
                        return@withLock true
                    }
                    download(context, release)
                }
                if (manual) installLocked(context, manual)
                consumePendingOpen(context)
                notifyReady(context)
                true
            } catch (e: TimeoutCancellationException) {
                mutableState.update { it.copy(phase = KoneUpdatePhase.Failed, message = "Update download timed out. Try again.") }
                false
            } catch (e: CancellationException) {
                mutableState.update { it.copy(phase = if (it.verified) KoneUpdatePhase.Ready else KoneUpdatePhase.Idle) }
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(phase = KoneUpdatePhase.Failed, message = controlledError(e)) }
                false
            }
        }
    }

    fun updateNow(context: Context) {
        val app = rememberContext(context)
        synchronized(noticeState) {
            // A later explicit install choice supersedes "Later" for its confirmation notice.
            state.value.release?.let { release ->
                prefs(app).edit().putStringSet("dismissed_releases",
                    remembered(app, "dismissed_releases") - releaseKey(release)).commit()
            }
        }
        scope.launch {
            if (state.value.verified) {
                lock.withLock {
                    try { installLocked(app, manual = true) }
                    catch (e: Exception) { mutableState.update { it.copy(phase = KoneUpdatePhase.Failed, message = controlledError(e)) } }
                }
            } else check(app, manual = true)
        }
    }

    fun fetchRelease(context: Context): KoneRelease? {
        val connection = open(context, BuildConfig.KONE_UPDATE_FEED)
        try {
            val text = connection.inputStream.use { input ->
                val bytes = input.readBytesLimited(65_536)
                String(bytes, Charsets.UTF_8)
            }
            return KoneRelease.parse(text, BuildConfig.KONE_UPDATE_FEED, context.packageName)
        } finally { connection.disconnect() }
    }

    private fun open(context: Context, url: String): HttpURLConnection {
        require(URL(url).protocol == "https") { "Update URL is not trusted" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache")
        if (BuildConfig.KNINE_OWNER) {
            val feed = java.net.URI(BuildConfig.KONE_UPDATE_FEED)
            val target = java.net.URI(url)
            require(target.host == feed.host && target.port == feed.port && target.userInfo == null) { "Update URL is not trusted" }
            val token = OwnerUpdateAccess.credential(context) ?: throw IllegalStateException("Sign in to the management console to enable private edition updates.")
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (BuildConfig.KNINE_OWNER && connection.responseCode == 401) {
                OwnerUpdateAccess.forget(context)
                throw IllegalStateException("Update authorization expired. Sign in to the management console again.")
            }
            require(connection.responseCode == 200) { "Unable to retrieve updates" }
            return connection
        } catch (e: Exception) { connection.disconnect(); throw e }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            require(out.size() + n <= limit) { "Update metadata is too large" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private suspend fun download(context: Context, release: KoneRelease) = withTimeout(180_000) {
        mutableState.update { it.copy(phase = KoneUpdatePhase.Downloading, progress = 0f, verified = false) }
        val destination = apk(context, release)
        val partial = File(destination.path + ".part")
        partial.delete()
        val connection = open(context, release.downloadUrl)
        val coroutine = currentCoroutineContext()
        try {
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    KonePackageChecks.copyAndVerify(input, output, release) { bytes ->
                        coroutine.ensureActive()
                        mutableState.update { it.copy(progress = bytes.toFloat() / release.bytes) }
                    }
                }
            }
            verifyIdentity(context, partial, release)
            require(partial.renameTo(destination)) { "Cannot save the update package" }
            prefs(context).edit().putString("verified_sha", release.sha256).commit()
            directory(context).listFiles()?.forEach { if (it.isFile && it != destination) it.delete() }
            mutableState.update { it.copy(phase = KoneUpdatePhase.Ready, progress = 1f, verified = true) }
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): KonePackageChecks.Identity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signers = signatures.orEmpty().map { KonePackageChecks.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return KonePackageChecks.Identity(info.packageName, version, signers)
    }

    @Suppress("DEPRECATION")
    private fun verifyIdentity(context: Context, file: File, release: KoneRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags)) { "Update package cannot be read" }
        KonePackageChecks.requireIdentity(identity(installed), identity(candidate), release)
        if (Build.VERSION.SDK_INT >= 24) require((candidate.applicationInfo?.minSdkVersion ?: 1) <= Build.VERSION.SDK_INT) { "Update package does not support this Android version." }
        java.util.zip.ZipFile(file).use { zip ->
            val abis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                .mapNotNull { it.split('/').getOrNull(1) }.toSet()
            require(abis.isEmpty() || Build.SUPPORTED_ABIS.any { it in abis }) { "Update package does not support this device." }
        }
    }

    private fun installLocked(context: Context, manual: Boolean) {
        val release = state.value.release ?: return
        if (!ready(context, release)) return
        if (!permissionGranted(context)) {
            mutableState.update { it.copy(permissionGranted = false, phase = KoneUpdatePhase.Ready) }
            notifyReady(context)
            return
        }
        if (manual && pendingConfirmation != null) {
            context.startActivity(Intent(pendingConfirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val preferences = prefs(context)
        val existingSession = preferences.getInt("session", -1)
        if (!manual && existingSession >= 0 && context.packageManager.packageInstaller.getSessionInfo(existingSession) != null) return
        if (!manual && preferences.getInt("attempt_build", 0) == release.versionCode &&
            System.currentTimeMillis() - preferences.getLong("attempt_at", 0) < RETRY_INSTALL_MS) return
        val file = apk(context, release)
        try {
            file.inputStream().use { input -> KonePackageChecks.copyAndVerify(input, object : java.io.OutputStream() {
                override fun write(b: Int) = Unit
                override fun write(b: ByteArray, off: Int, len: Int) = Unit
            }, release) }
            verifyIdentity(context, file, release)
        } catch (e: Exception) {
            prefs(context).edit().remove("verified_sha").commit()
            file.delete()
            mutableState.update { it.copy(verified = false) }
            throw e
        }
        synchronized(sessionState) {
            prefs(context).edit().remove("session").putBoolean("confirmation", false).commit()
            pendingConfirmation = null
        }
        if (existingSession >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(existingSession) }
        preferences.edit().putInt("attempt_build", release.versionCode).putLong("attempt_at", System.currentTimeMillis()).commit()
        // PackageManager replaces this app only after it is authorized. Do not stop VPN first.
        KonePackageInstaller.install(context, file, release.versionCode) { sessionId ->
            synchronized(sessionState) {
                preferences.edit().putInt("session", sessionId).putBoolean("confirmation", false).commit()
                mutableState.update { it.copy(phase = KoneUpdatePhase.Installing, message = null) }
            }
        }
    }

    suspend fun installResult(context: Context, intent: Intent) {
        // A BroadcastReceiver has a short lifetime. Never queue this behind a network download.
        val sessionId = intent.getIntExtra(KonePackageInstaller.SESSION, -1)
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
        var confirmation: Intent? = null
        synchronized(sessionState) {
            if (sessionId < 0 || sessionId != prefs(context).getInt("session", -2)) return
            when (status) {
                android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent)
                    pendingConfirmation = confirmation
                    prefs(context).edit().putBoolean("confirmation", true).commit()
                    restore(context)
                    mutableState.update { it.copy(phase = KoneUpdatePhase.Confirmation) }
                }
                android.content.pm.PackageInstaller.STATUS_SUCCESS -> { clear(context); return }
                else -> {
                    prefs(context).edit().remove("session").putBoolean("confirmation", false).commit()
                    pendingConfirmation = null
                    restore(context)
                    mutableState.update { it.copy(phase = KoneUpdatePhase.Failed, message = "Installation incomplete. Try again.") }
                }
            }
        }
        val action = confirmation
        if (action != null && foreground()) withContext(Dispatchers.Main) {
            if (prefs(context).getInt("session", -1) == sessionId) {
                runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        notifyReady(context, action)
    }

    suspend fun replaced(context: Context) {
        lock.withLock { clear(context) }
        KoneUpdateWork.schedule(context)
    }

    private fun clear(context: Context) = synchronized(sessionState) {
        val session = prefs(context).getInt("session", -1)
        if (session >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(session) }
        prefs(context).edit().remove("release").remove("verified_sha").remove("session").remove("confirmation").commit()
        pendingConfirmation = null
        synchronized(noticeState) {
            mutableState.update { it.copy(release = null, phase = KoneUpdatePhase.Idle, verified = false,
                progress = 0f, message = null, showNonce = 0) }
        }
        directory(context).listFiles()?.forEach { if (it.isFile) it.delete() }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION)
    }

    private fun metered(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // The VPN itself may be labelled metered; use its actual underlying network when known.
        val network = DefaultNetworkMonitor.defaultNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        return manager.isActiveNetworkMetered
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Suppress("UNUSED_PARAMETER")
    private fun notifyReady(context: Context, confirmation: Intent? = null) = synchronized(noticeState) {
        val status = state.value
        val release = status.release ?: return@synchronized
        val key = releaseKey(release)
        val confirmationSession = if (status.phase == KoneUpdatePhase.Confirmation) prefs(context).getInt("session", -1) else null
        val notice = KoneUpdateNoticePolicy.notificationKey(key, confirmationSession)
        val eligible = status.verified && (status.phase == KoneUpdatePhase.Ready || status.phase == KoneUpdatePhase.Confirmation)
        val allowed = (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!KoneUpdateNoticePolicy.shouldNotify(key, notice, eligible, foreground(), allowed,
                remembered(context, "notified_releases"), remembered(context, "dismissed_releases"))) return@synchronized
        val manager = notificationManager(context)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return@synchronized
        }
        // A notification never carries the system's install-confirmation Intent.
        // Even a forged SHOW_UPDATE intent can only open our in-app review dialog.
        val intent = Intent(context, MainActivity::class.java).setAction("io.nekohasekai.sfa.SHOW_KONE_UPDATE")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(SHOW_UPDATE, true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pending = PendingIntent.getActivity(context, NOTIFICATION, intent, flags)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_menu)
            .setContentTitle(if (status.phase == KoneUpdatePhase.Confirmation) "KOne update awaiting confirmation" else "New KOne version available")
            .setContentText("${release.versionName} · Tap to view update")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        if (runCatching { manager.notify(NOTIFICATION, notification) }.isSuccess) {
            remember(context, "notified_releases", notice)
        }
    }

    private fun controlledError(error: Exception): String = when (error) {
        is IllegalArgumentException -> error.message?.takeIf { it.startsWith("Update") || it.startsWith("This update") || it.startsWith("Cannot save") || it == "Unable to retrieve updates" } ?: "Update metadata is invalid"
        else -> "Update incomplete. Try again later."
    }
}
