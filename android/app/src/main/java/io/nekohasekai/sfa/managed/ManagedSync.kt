package io.nekohasekai.sfa.managed

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bg.BoxService
import cc.txwd.kone.shared.Countries
import io.nekohasekai.sfa.compose.screen.dashboard.NodeCategoryStore
import io.nekohasekai.sfa.compose.util.FlagIcons
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.selfuse.HomeSettings
import io.nekohasekai.sfa.selfuse.InitialRuleSets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the managed node in step with the console: fetch, acknowledge, build the KOne
 * configuration, check it with the core, swap it in whole, reload if it is the node in use, and
 * report how that went. A configuration that fails anywhere leaves the last good one in place.
 * When the console takes the service back, the managed node goes and nothing else is touched.
 */
object ManagedSync {
    private val lock = Mutex()

    /** Binds this phone with an invite code, then fetches its configuration. */
    suspend fun claim(context: Context, endpoint: ManagedEndpoint, code: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val name = "KOne · " + Build.MODEL
            ManagedState.rememberEndpoint(endpoint)
            val claim = ManagedApi(endpoint).claim(code.trim(), name)
            removeManagedProfile()
            ManagedState.bind(endpoint, name, claim)
            ManagedWork.schedule(context)
        }
        sync(context)
    }

    /** Fetches and applies whatever changed; failures end up in [ManagedState], not thrown. */
    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock { syncLocked(context) }
    }

    /** At start, only when the last sync is older than [maxAgeMillis]. */
    suspend fun syncIfStale(context: Context, maxAgeMillis: Long = 30 * 60_000L) {
        if (!ManagedState.bound) return
        if (System.currentTimeMillis() - ManagedState.snapshot.syncedAt < maxAgeMillis) return
        sync(context)
    }

    /** The user ends the binding here; the console's record of the device stays until revoked there. */
    suspend fun unbind(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            removeManagedProfile()
            ManagedState.unbind()
            ManagedWork.cancel(context)
        }
    }

    private suspend fun syncLocked(context: Context) {
        val endpoint = ManagedState.endpoint ?: return
        if (!ManagedState.bound) return
        val credential = ManagedState.credential() ?: run {
            // The keystore key is gone (a restored copy of the App's data): this phone must claim again.
            removeManagedProfile()
            ManagedState.unbind(ManagedStatus.Revoked, "Claim access again")
            ManagedWork.cancel(context)
            return
        }
        val api = ManagedApi(endpoint)
        val delivery = try {
            api.config(credential)
        } catch (failure: ManagedException) {
            when (failure.failure) {
                ManagedFailure.Revoked -> {
                    removeManagedProfile()
                    // The console's own wording ("Device authentication failed") is technical; the person only needs to know.
                    ManagedState.unbind(ManagedStatus.Revoked, "Access revoked")
                    ManagedWork.cancel(context)
                }
                ManagedFailure.Refused -> ManagedState.status(ManagedStatus.Refused, failure.detail)
                ManagedFailure.Waiting -> ManagedState.status(ManagedStatus.Waiting, failure.detail)
                ManagedFailure.Offline -> ManagedState.status(ManagedStatus.Offline)
                else -> ManagedState.status(ManagedStatus.Failed, failure.shown)
            }
            return
        }

        val profile = ManagedState.profileId.takeIf { it != -1L }?.let { ProfileManager.get(it) }
        val haveFile = profile != null && File(profile.typed.path).isFile
        // What an older App built from the same delivery is built again once (ManagedConfig.TEMPLATE).
        val built = haveFile && ManagedState.template == ManagedConfig.TEMPLATE
        if (delivery.revision == ManagedState.revision && built) {
            ManagedState.synced()
            flushDiagnostics(api, credential, context)
            return
        }
        runCatching { api.ack(credential, delivery.revision, ManagedReport.RECEIVED) }

        if (delivery.configRevision != ManagedState.configRevision || !built) {
            val content = try {
                ManagedConfig.build(delivery.config, HomeSettings.current(), InitialRuleSets.ensure(context)).also { Libbox.checkConfig(it) }
            } catch (_: Exception) {
                ManagedState.record("config_invalid")
                runCatching { api.ack(credential, delivery.revision, ManagedReport.FAILED, "config_invalid") }
                ManagedState.status(ManagedStatus.Failed, ManagedFailure.ConfigInvalid.message)
                return
            }
            val applied = apply(context, profile, content, delivery)
            if (applied == null) {
                runCatching { api.ack(credential, delivery.revision, ManagedReport.FAILED, "rollback") }
                ManagedState.status(ManagedStatus.Failed, ManagedFailure.ApplyFailed.message)
                return
            }
            ManagedState.applied(delivery, applied)
        } else {
            // Only the terms changed (quota, devices, period): nothing to restart.
            ManagedState.applied(delivery, profile!!.id)
        }
        runCatching { api.ack(credential, delivery.revision, ManagedReport.APPLIED) }
        flushDiagnostics(api, credential, context)
    }

    /** Writes the configuration into the managed node, creating it the first time; null when it had to roll back. */
    private suspend fun apply(context: Context, existing: Profile?, content: String, delivery: ManagedDelivery): Long? {
        if (existing == null) {
            val directory = File(context.filesDir, "configs").also { it.mkdirs() }
            val file = File(directory, "${ProfileManager.nextFileID()}.json")
            writeWhole(file, content)
            val name = delivery.configName.ifBlank { "Managed route" }
            val country = Countries.all.filter { it.matches(name) }.singleOrNull()
            val profile = Profile(name = name, typed = TypedProfile().apply { type = TypedProfile.Type.Local; path = file.absolutePath }).apply {
                userOrder = ProfileManager.nextOrder()
                country?.let { icon = FlagIcons.idFor(it.code) }
            }
            return ProfileManager.create(profile, andSelect = Settings.selectedProfile == -1L).id
        }
        val file = File(existing.typed.path)
        val previous = runCatching { file.readText() }.getOrNull()
        val running = existing.id == Settings.selectedProfile && ourVpnIsUp(context)
        writeWhole(file, content)
        // A name the user left as delivered follows the console; one they changed stays theirs.
        if (existing.name == ManagedState.configName && delivery.configName.isNotBlank() && delivery.configName != existing.name) {
            existing.name = delivery.configName
        }
        ProfileManager.update(existing)
        if (running) {
            runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
            delay(2500)
            if (!ourVpnIsUp(context)) {
                // The new configuration stopped the connection: put the old one back for the next start.
                if (previous != null) writeWhole(file, previous)
                ManagedState.record("connection_failed")
                return null
            }
        }
        return existing.id
    }

    private suspend fun removeManagedProfile() {
        val id = ManagedState.profileId
        if (id == -1L) return
        val profile = ProfileManager.get(id)
        if (profile != null) {
            if (Settings.selectedProfile == id) {
                runCatching { BoxService.stop() }
                Settings.selectedProfile = ProfileManager.list().firstOrNull { it.id != id }?.id ?: -1L
            }
            ProfileManager.delete(profile)
            runCatching { File(profile.typed.path).delete() }
        }
        withContext(Dispatchers.Main) { NodeCategoryStore.forget(id) }
        ManagedState.forgetProfile()
    }

    private suspend fun flushDiagnostics(api: ManagedApi, credential: String, context: Context) {
        if (!ManagedState.diagnosticsEnabled) return
        val events = ManagedState.pendingEvents()
        runCatching { api.diagnostics(credential, BuildConfig.VERSION_NAME, networkType(context), events) }
            .onSuccess { ManagedState.clearEvents() }
    }

    private fun writeWhole(file: File, content: String) {
        val next = File(file.parentFile, file.name + ".next")
        next.writeText(content)
        next.setReadable(false, false)
        next.setReadable(true, true)
        if (!next.renameTo(file)) {
            next.delete()
            throw IllegalStateException("write failed")
        }
    }

    private fun ourVpnIsUp(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.allNetworks.any { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || capabilities.ownerUid == Process.myUid())
        }
    }

    private fun networkType(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return "offline"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }
}
