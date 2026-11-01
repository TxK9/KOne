package io.nekohasekai.sfa.compose.component

import io.nekohasekai.sfa.R
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.selfuse.NodeShareLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/** Who may share a node: the owner's own copy, and only nodes the person added, never the console's. */
internal fun canShareNode(profile: Profile): Boolean =
    io.nekohasekai.sfa.BuildConfig.KNINE_OWNER && !io.nekohasekai.sfa.managed.ManagedState.isManaged(profile.id)

/** The node as a portable link, or null when its settings have no link form. Read off the main thread. */
internal suspend fun nodeShareLink(profile: Profile): String? = withContext(Dispatchers.IO) {
    runCatching { NodeShareLink.forConfig(File(profile.typed.path).readText(), profile.name) }.getOrNull()
}

/** Hands the node's link to another app, through Android's own share sheet. */
internal suspend fun shareNodeAsText(context: Context, profile: Profile) {
    val link = nodeShareLink(profile)
    if (link == null) {
        Toast.makeText(context, context.getString(R.string.kone_node_share_unusable), Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    context.startActivity(Intent.createChooser(send, profile.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun qr(text: String, size: Int): ImageBitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) INK else PAPER }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private const val INK = 0xFF15201C.toInt()
private const val PAPER = 0xFFF6F5EE.toInt()

/**
 * The node's QR code on a square of paper, unfolding in place under its row, with the link to copy
 * or hand on. The link itself is not printed: it carries the node's key, and the code says it all.
 */
@Composable
internal fun NodeSharePanel(profile: Profile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val link by produceState<String?>(null, profile.id, profile.name) { value = nodeShareLink(profile) ?: "" }
    val code by produceState<ImageBitmap?>(null, link) {
        value = link?.takeIf { it.isNotEmpty() }?.let { withContext(Dispatchers.Default) { runCatching { qr(it, 720) }.getOrNull() } }
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(code) { if (code != null) appear.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            link == null -> Box(Modifier.size(196.dp))
            link == "" -> Text(
                stringResource(R.string.kone_node_share_unusable),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp).graphicsLayer { alpha = 0.75f },
            )
            else -> {
                Box(
                    Modifier
                        .size(196.dp)
                        .graphicsLayer {
                            alpha = appear.value
                            val s = 0.92f + 0.08f * appear.value
                            scaleX = s; scaleY = s
                        }
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(PAPER))
                        .padding(12.dp),
                ) {
                    code?.let { Image(it, contentDescription = stringResource(R.string.kone_node_qr, profile.name), filterQuality = FilterQuality.None, modifier = Modifier.fillMaxWidth()) }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    NodeActionBubble(stringResource(R.string.kone_action_copy), Icons.Outlined.ContentCopy) {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText(profile.name, link))
                        Toast.makeText(context, context.getString(R.string.kone_node_link_copied), Toast.LENGTH_SHORT).show()
                    }
                    NodeActionBubble(stringResource(R.string.kone_action_share), Icons.Outlined.IosShare) {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                        context.startActivity(Intent.createChooser(send, profile.name))
                    }
                }
            }
        }
    }
}
