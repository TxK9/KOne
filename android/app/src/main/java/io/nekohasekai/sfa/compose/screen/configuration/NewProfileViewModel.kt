package io.nekohasekai.sfa.compose.screen.configuration

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import cc.txwd.kone.shared.ShareLinks
import cc.txwd.kone.shared.SubscriptionContent
import io.nekohasekai.sfa.selfuse.SubscriptionException
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

data class NewProfileUiState(
    val name: String = "",
    val profileType: ProfileType = ProfileType.Local,
    val profileSource: ProfileSource = ProfileSource.CreateNew,
    // Remote profile fields
    val remoteUrl: String = "",
    val autoUpdate: Boolean = true,
    // As typed; read with [refreshMinutes] when the profile is created.
    val autoUpdateInterval: String = "60",
    // File import
    val importUri: Uri? = null,
    val importFileName: String? = null,
    // QRS import
    val qrsData: ByteArray? = null,
    // State
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false,
    val createdProfile: Profile? = null,
    // Field errors
    val nameError: String? = null,
    val remoteUrlError: String? = null,
    val importError: String? = null,
)

/** Minutes between refreshes from what was typed: at least 15, and 60 when nothing usable was. */
internal fun refreshMinutes(typed: String): Int = typed.trim().toIntOrNull()?.coerceAtLeast(15) ?: 60

/**
 * The link to fetch from what was pasted: trimmed, and another client's import link
 * (clash://install-config?url=…, sing-box://import-remote-profile?url=…) opened to the
 * subscription inside it.
 */
internal fun subscriptionLink(pasted: String): String = pasted.trim().let { SubscriptionContent.linkOf(it) ?: it }

/** The same subscription added twice, said under its link. */
private class DuplicateSubscription(message: String) : Exception(message)

enum class ProfileType {
    Local,
    Remote,
}

enum class ProfileSource {
    CreateNew,
    Import,
}

class NewProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(NewProfileUiState())
    val uiState: StateFlow<NewProfileUiState> = _uiState.asStateFlow()

    fun initializeFromQRImport(name: String?, url: String?) {
        if (name != null && url != null) {
            _uiState.update {
                it.copy(
                    name = name,
                    profileType = ProfileType.Remote,
                    remoteUrl = url,
                )
            }
        }
    }

    fun initializeFromQRSImport(name: String?, qrsData: ByteArray) {
        _uiState.update {
            it.copy(
                name = name ?: "",
                profileType = ProfileType.Local,
                profileSource = ProfileSource.Import,
                qrsData = qrsData,
            )
        }
    }

    fun updateName(name: String) {
        _uiState.update {
            it.copy(
                name = name,
                nameError = if (name.isNotBlank()) null else it.nameError,
            )
        }
    }

    fun updateProfileType(type: ProfileType) {
        _uiState.update { it.copy(profileType = type) }
    }

    fun updateProfileSource(source: ProfileSource) {
        _uiState.update {
            it.copy(
                profileSource = source,
                importError = null, // Clear import error when changing source
            )
        }
    }

    fun updateRemoteUrl(url: String) {
        _uiState.update {
            it.copy(
                remoteUrl = url,
                remoteUrlError = if (url.isNotBlank()) null else it.remoteUrlError,
            )
        }
    }

    fun updateAutoUpdate(enabled: Boolean) {
        _uiState.update { it.copy(autoUpdate = enabled) }
    }

    fun updateAutoUpdateInterval(interval: String) {
        // Kept as typed, so "30" can be typed through "3"; the minimum applies on create.
        _uiState.update { it.copy(autoUpdateInterval = interval.filter(Char::isDigit).take(5)) }
    }

    fun setImportUri(uri: Uri, fileName: String?) {
        _uiState.update {
            it.copy(
                importUri = uri,
                importFileName = fileName,
                importError = null, // Clear error when file is selected
                name =
                if (it.name.isEmpty()) {
                    fileName?.substringBeforeLast(".") ?: "Imported Profile"
                } else {
                    it.name
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun validateAndCreateProfile(): Boolean {
        val state = _uiState.value
        val context = getApplication<Application>()

        // Clear previous errors
        _uiState.update {
            it.copy(
                nameError = null,
                remoteUrlError = null,
                importError = null,
            )
        }

        var hasError = false

        // A subscription with no name typed takes its #fragment or host, never its path, which may hold its token.
        val pasted = subscriptionLink(state.remoteUrl)
        if (state.name.isBlank() && state.profileType == ProfileType.Remote && pasted.startsWith("http", ignoreCase = true)) {
            _uiState.update { it.copy(name = SubscriptionContent.name(pasted, null)) }
        } else if (state.name.isBlank()) {
            _uiState.update { it.copy(nameError = context.getString(R.string.profile_input_required)) }
            hasError = true
        }

        // Validate based on profile type
        when (state.profileType) {
            ProfileType.Local -> {
                if (state.profileSource == ProfileSource.Import && state.importUri == null && state.qrsData == null) {
                    _uiState.update { it.copy(importError = context.getString(R.string.profile_input_required)) }
                    hasError = true
                }
            }
            ProfileType.Remote -> {
                val url = subscriptionLink(state.remoteUrl)
                // What will be fetched is what the field shows.
                if (url != state.remoteUrl) _uiState.update { it.copy(remoteUrl = url) }
                if (url.isEmpty()) {
                    _uiState.update { it.copy(remoteUrlError = context.getString(R.string.profile_input_required)) }
                    hasError = true
                } else if (ShareLinks.findAll(url).isNotEmpty()) {
                    _uiState.update { it.copy(remoteUrlError = "This is a node link. Copy it, then choose Import → Clipboard on the dashboard.") }
                    hasError = true
                } else if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
                    _uiState.update { it.copy(remoteUrlError = "Remote configuration URLs must start with https:// or http://.") }
                    hasError = true
                }
            }
        }

        if (hasError) {
            return false
        }

        // If validation passes, create the profile
        createProfile()
        return true
    }

    private fun createProfile() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            try {
                val profile =
                    withContext(Dispatchers.IO) {
                        when (state.profileType) {
                            ProfileType.Local -> createLocalProfile(state)
                            ProfileType.Remote -> createRemoteProfile(state)
                        }
                    }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        isSuccess = true,
                        createdProfile = profile,
                    )
                }
            } catch (duplicate: DuplicateSubscription) {
                _uiState.update { it.copy(isSaving = false, remoteUrlError = duplicate.message) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = userMessage(e),
                    )
                }
            }
        }
    }

    /** Refusals are fixed words and shown as they are; anything else may echo a link or a node, so it is not. */
    private fun userMessage(failure: Exception): String = when (failure) {
        is SubscriptionException, is ImportRefusal -> failure.message
        else -> null
    } ?: getApplication<Application>().getString(R.string.kone_profile_create_failed)

    private suspend fun createLocalProfile(state: NewProfileUiState): Profile {
        val context = getApplication<Application>()
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Local
            }

        val profile =
            Profile(name = state.name, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        typedProfile.path = configFile.path

        // Get config content
        val configContent =
            when (state.profileSource) {
                ProfileSource.CreateNew -> "{}"
                ProfileSource.Import -> {
                    if (state.qrsData != null) {
                        val content = Libbox.decodeProfileContent(state.qrsData)
                        content.config
                    } else {
                        val handler = ProfileImportHandler(context)
                        val text = state.importUri?.let { uri ->
                            when (uri.scheme?.lowercase()) {
                                "content", "file" -> String(handler.readFile(uri)).removePrefix("\uFEFF")
                                "http", "https" -> HTTPClient().use { it.getString(uri.toString()) }
                                else -> throw ImportRefusal(context.getString(R.string.kone_import_file_unopened))
                            }
                        } ?: "{}"
                        // A list of nodes belongs on the dashboard, one profile each.
                        handler.refuseNodeList(text)
                        text
                    }
                }
            }

        // Validate config; sing-box's own reason may quote a node's password, so it is not shown.
        try {
            Libbox.checkConfig(configContent)
        } catch (_: Exception) {
            throw ImportRefusal(context.getString(R.string.kone_import_config_invalid))
        }
        configFile.writeText(configContent)

        // Create profile in database and select it
        ProfileManager.create(profile, andSelect = true)

        return profile
    }

    private suspend fun createRemoteProfile(state: NewProfileUiState): Profile {
        val context = getApplication<Application>()
        val url = subscriptionLink(state.remoteUrl)
        ProfileManager.list().firstOrNull { it.typed.type == TypedProfile.Type.Remote && it.typed.remoteURL.trim() == url }?.let { existing ->
            throw DuplicateSubscription(context.getString(R.string.kone_subscription_already_added, existing.name))
        }
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Remote
                remoteURL = url
                autoUpdate = state.autoUpdate
                autoUpdateInterval = refreshMinutes(state.autoUpdateInterval)
                lastUpdated = Date()
            }

        val profile =
            Profile(name = state.name, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        // Fetch initial config - this MUST succeed for remote profiles
        // Links, base64 or a Clash profile become KOne's own template (a whole sing-box configuration
        // is kept); the provider's terms come with it.
        val fetched = io.nekohasekai.sfa.selfuse.SubscriptionProfile.fetch(url)
        val configContent =
            try {
                io.nekohasekai.sfa.selfuse.HomeSettings.saved(fetched.content).also { Libbox.checkConfig(it) }
            } catch (_: Exception) {
                throw ImportRefusal(context.getString(R.string.kone_subscription_unusable))
            }

        // The file is numbered only once the subscription has arrived.
        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        typedProfile.path = configFile.path
        configFile.writeText(configContent)

        // Create profile in database and select it
        ProfileManager.create(profile, andSelect = true)
        io.nekohasekai.sfa.selfuse.SubscriptionProfile.keep(profile.id, fetched.info, profile.name)

        // Reconfigure updater if auto-update is enabled
        if (state.autoUpdate) {
            UpdateProfileWork.reconfigureUpdater()
        }

        return profile
    }
}
