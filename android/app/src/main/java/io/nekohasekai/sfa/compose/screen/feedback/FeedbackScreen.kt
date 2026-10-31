package io.nekohasekai.sfa.compose.screen.feedback

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.DefaultNetworkMonitor
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.tools.TestGutter
import io.nekohasekai.sfa.compose.screen.tools.TestLink
import io.nekohasekai.sfa.compose.screen.tools.TestRow
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.compose.screen.dashboard.knineTone
import io.nekohasekai.sfa.compose.theme.KnineMotion
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.feedback.*
import io.nekohasekai.sfa.managed.ManagedState
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/** What went wrong in a couple of words, and why, one tap away. */
internal data class FeedbackProblem(val title: String, val reason: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot = ManagedState.snapshot
    var draft by remember { mutableStateOf<FeedbackDraft?>(null) }
    var api by remember { mutableStateOf<FeedbackApi?>(null) }
    var description by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var bindingKey by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<FeedbackProblem?>(null) }
    var receipt by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var pendingImages by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // The picker offers only as many pictures as there is room for, so nothing chosen is dropped.
    // A multiple picker needs room for two or more; the last free tile takes a single pick.
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pendingImages = listOf(uri.toString())
    }
    val pickTwo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(2)) { uris ->
        pendingImages = uris.map { it.toString() }
    }
    val pickThree = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        pendingImages = uris.map { it.toString() }
    }
    fun pickImages() {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        runCatching {
            when (3 - images.size) {
                1 -> pickOne.launch(request)
                2 -> pickTwo.launch(request)
                else -> pickThree.launch(request)
            }
        }.onFailure { problem = FeedbackProblem(context.getString(R.string.kone_feedback_image_not_added), context.getString(R.string.kone_feedback_no_picker)) }
    }
    LaunchedEffect(draft, pendingImages) {
        val saved = draft
        if (saved != null && pendingImages.isNotEmpty()) {
            busy = true
            try {
                val read = withContext(Dispatchers.IO) {
                    pendingImages.take(3 - images.size).map {
                        // One picture that cannot be read must not cost the others.
                        try { FeedbackImage.read(context, android.net.Uri.parse(it)) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                        catch (_: OutOfMemoryError) { null }
                    }
                }
                val added = read.filterNotNull()
                val failed = read.size - added.size
                if (draft === saved) {
                    if (added.isNotEmpty()) {
                        val next = images + added
                        withContext(Dispatchers.IO) { saved.saveImages(JSONArray(next)) }
                        images = next
                    }
                    problem = when (failed) {
                        0 -> null
                        1 -> FeedbackProblem(context.getString(R.string.kone_feedback_image_not_added), context.getString(R.string.kone_feedback_image_unreadable))
                        else -> FeedbackProblem(context.getString(R.string.kone_feedback_images_not_added, failed), context.getString(R.string.kone_feedback_images_unreadable))
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (draft === saved) problem = FeedbackProblem(context.getString(R.string.kone_feedback_image_not_added), context.getString(R.string.kone_feedback_image_failed)) }
            finally { if (draft === saved) { pendingImages = emptyList(); busy = false } }
        }
    }
    LaunchedEffect(snapshot) {
        val binding = withContext(Dispatchers.IO) {
            val endpoint = ManagedState.endpoint
            val credential = ManagedState.credential()
            if (!ManagedState.bound || endpoint == null || credential == null) null
            else Triple(FeedbackDraft.bindingKey(endpoint.base, credential),
                FeedbackDraft(context.applicationContext, endpoint.base, credential), FeedbackApi(endpoint, credential))
        }
        // A credential can change while the endpoint and `bound` flag stay the same. Ordinary
        // sync status updates, on the other hand, must not reset this form or an active upload.
        if (initialized && bindingKey == binding?.first) return@LaunchedEffect
        if (initialized) pendingImages = emptyList()
        bindingKey = binding?.first
        draft = null; api = null; busy = false; initialized = false; problem = null; receipt = null; images = emptyList(); description = ""
        binding?.let { (_, saved, client) ->
            draft = saved; description = saved.description; api = client
            val pictures = saved.images
            images = (0 until pictures.length()).map { pictures.getJSONObject(it) }
            receipt = saved.receipt
        }
        initialized = true
    }
    fun submit() {
            if (busy) return
            val client = api ?: return
            val saved = draft ?: return
            busy = true; problem = null
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val manager = context.getSystemService(ConnectivityManager::class.java)
                        val caps = manager.getNetworkCapabilities(DefaultNetworkMonitor.defaultNetwork ?: manager.activeNetwork)
                        val network = when {
                            caps == null -> "offline"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                            else -> "unknown"
                        }
                        val payload = saved.payload(JSONObject().put("appVersion", BuildConfig.VERSION_NAME.take(32)).put("versionCode", BuildConfig.VERSION_CODE)
                            .put("androidVersion", Build.VERSION.RELEASE.take(32)).put("manufacturer", Build.MANUFACTURER.take(64)).put("model", Build.MODEL.take(80)).put("network", network))
                        val number = client.submit(payload)
                        // The server receipt is authoritative. A local storage failure after it
                        // arrives must not tell the user that the server rejected the feedback.
                        try { saved.complete(number, payload) } catch (_: Exception) { }
                        number to payload
                    }
                    if (api === client) { receipt = result; description = ""; images = emptyList() }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (api === client) problem = FeedbackProblem(context.getString(R.string.kone_feedback_not_sent),
                        (e as? FeedbackFailure)?.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.kone_feedback_not_sent_reason))
                }
                finally { if (api === client) busy = false }
            }
    }
    OverrideTopBar {
        TopAppBar(title = { Text(stringResource(R.string.kone_feedback)) }, navigationIcon = {
            IconButton(onClick = { navController.navigateUp() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back)) }
        })
    }
    // The network tests' face: rows on the 24 dp gutter, a weather where something is wrong,
    // and the reason one tap away. Nothing explains itself in paragraphs.
    BackdropContrastScope(Modifier.fillMaxSize().padding(LocalScaffoldPadding.current)) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            // Reading the binding takes a moment; an empty page reads better than a flash of "Loading".
            if (!initialized) return@Column
            if (draft == null) {
                // Settings offers Feedback only to a linked copy; an older saved page can still land here.
                var open by remember { mutableStateOf(false) }
                TestRow(stringResource(R.string.kone_feedback_unlinked), NodeWeather.Rest,
                    detail = stringResource(R.string.kone_feedback_unlinked_reason), open = open, onToggle = { open = !open })
                return@Column
            }
            val submitted = receipt
            if (submitted != null) {
                FeedbackReceipt(submitted.first, submitted.second, onMore = { draft?.clearReceipt(); receipt = null })
                return@Column
            }
            FeedbackForm(description, images, busy, problem,
                onDescription = { description = it; draft?.save("other", it); problem = null },
                onAddImage = ::pickImages,
                onRemoveImage = { index -> images = images.filterIndexed { i, _ -> i != index }; draft?.saveImages(JSONArray(images)) },
                onSubmit = ::submit,
                // Pictures are still being read while the picker's choice waits; a send never has one.
                adding = pendingImages.isNotEmpty(),
            )
        }
    }
}

private val TileSize = 72.dp
/** The longest description the console takes. */
private const val FeedbackLimit = 4000
private val TileShape = RoundedCornerShape(12.dp)

/**
 * The description, its pictures in a row of small tiles (the last tile adds one), Send on the rows'
 * line, and, when something failed, its weather with the reason underneath on a tap.
 */
@Composable
internal fun FeedbackForm(
    description: String, images: List<JSONObject>, busy: Boolean, problem: FeedbackProblem?,
    onDescription: (String) -> Unit, onAddImage: () -> Unit, onRemoveImage: (Int) -> Unit, onSubmit: () -> Unit,
    adding: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(value = description, onValueChange = { if (!busy && it.length <= FeedbackLimit) onDescription(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = TestGutter), enabled = true, readOnly = busy, minLines = 5,
            label = { androidx.compose.material3.Text(stringResource(R.string.kone_feedback_description), color = LocalContentColor.current) })
        // The limit stays out of sight until it is near, then says why typing stops.
        if (description.length >= FeedbackLimit * 9 / 10) {
            Text(stringResource(R.string.kone_feedback_length, description.length, FeedbackLimit), style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(horizontal = TestGutter + 4.dp, vertical = 4.dp).graphicsLayer { alpha = 0.7f })
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.padding(horizontal = TestGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            images.forEachIndexed { index, picture -> FeedbackTile(picture, index, enabled = !busy, onRemove = { onRemoveImage(index) }) }
            if (images.size < 3) AddImageTile(enabled = !busy, adding = adding, onClick = onAddImage)
        }
        Spacer(Modifier.height(12.dp))
        SendRow(enabled = !busy && (description.isNotBlank() || images.isNotEmpty()), sending = busy && !adding, onClick = onSubmit)
        // The failure unfolds under Send, and folds away with its own words once it is cleared.
        var lastProblem by remember { mutableStateOf(problem) }
        SideEffect { if (problem != null) lastProblem = problem }
        AnimatedVisibility(visible = problem != null, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
            val shown = problem ?: lastProblem
            if (shown != null) {
                var open by remember(shown) { mutableStateOf(false) }
                TestRow(shown.title, NodeWeather.Cut, detail = shown.reason, open = open, onToggle = { open = !open })
            }
        }
    }
}

/** A sent report: its number in the sun, and what was sent with it a tap below. */
@Composable
internal fun FeedbackReceipt(number: String, metadata: JSONObject, onMore: () -> Unit) {
    var open by remember(number) { mutableStateOf(false) }
    val sentWith = "KOne ${metadata.optString("appVersion")} · ${metadata.optInt("versionCode")}\n" +
        "${metadata.optString("manufacturer")} ${metadata.optString("model")} · Android ${metadata.optString("androidVersion")}"
    Column(Modifier.fillMaxWidth()) {
        TestRow(stringResource(R.string.kone_feedback_sent), NodeWeather.Clear, value = number, detail = sentWith, open = open, onToggle = { open = !open })
        TestLink(stringResource(R.string.kone_feedback_new), onMore)
    }
}

@Composable
private fun FeedbackTile(picture: JSONObject, index: Int, enabled: Boolean, onRemove: () -> Unit) {
    // Decoded off the main thread; the tile shows its faint wash until the picture is ready.
    val bitmap by produceState<android.graphics.Bitmap?>(null, picture) {
        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                val data = Base64.decode(picture.getString("data"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(data, 0, data.size)
            }.getOrNull()
        }
        value = decoded
        awaitDispose { decoded?.recycle() }
    }
    val ink = LocalContentColor.current
    Box(Modifier.size(TileSize)) {
        Box(Modifier.matchParentSize().clip(TileShape).background(ink.copy(alpha = 0.07f))) {
            val shown = bitmap
            if (shown != null) Image(shown.asImageBitmap(), stringResource(R.string.kone_feedback_image, index + 1), Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
        // The cross sits on a disc of the page's paper over the tile's corner, so it reads on any picture.
        IconButton(enabled = enabled, onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(32.dp)) {
            Box(Modifier.size(22.dp).background(knineTone().copy(alpha = 0.92f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, stringResource(R.string.kone_feedback_remove_image, index + 1), Modifier.size(14.dp))
            }
        }
    }
}

/** The empty tile where the next picture goes. */
@Composable
private fun AddImageTile(enabled: Boolean, adding: Boolean, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    val label = stringResource(R.string.kone_feedback_add_image)
    Box(
        Modifier
            .size(TileSize)
            .clip(TileShape)
            .border(1.dp, ink.copy(alpha = 0.28f), TileShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .graphicsLayer { alpha = if (enabled || adding) 1f else 0.45f },
        contentAlignment = Alignment.Center,
    ) {
        if (adding) CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
        else Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(24.dp).graphicsLayer { alpha = 0.75f })
    }
}

/** Send as a row: the word on the gutter, its arrow (or the send under way) where rows keep their weather. */
@Composable
private fun SendRow(enabled: Boolean, sending: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = TestGutter, vertical = 13.dp)
            .graphicsLayer { alpha = if (enabled || sending) 1f else 0.45f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(if (sending) R.string.kone_feedback_sending else R.string.kone_feedback_send), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (sending) CircularProgressIndicator(Modifier.size(16.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}
