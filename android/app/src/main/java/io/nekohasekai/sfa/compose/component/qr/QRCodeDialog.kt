package io.nekohasekai.sfa.compose.component.qr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import io.nekohasekai.sfa.compose.theme.KnineDialogSurface
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.TextButton
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.R
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.KnineSurface as Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.FilterQuality
import io.nekohasekai.sfa.compose.util.QRCodeGenerator
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun QRCodeDialog(content: String, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    // Keep the whole code visible in short windows; scrolling remains available
    // for unusually large text, while the QR stays square and pixel-aligned.
    val qrSide = kotlin.math.min(
        kotlin.math.min(configuration.screenWidthDp * 0.9f, 420f),
        (configuration.screenHeightDp * 0.85f - 120f - fontScale * 24f).coerceAtLeast(96f),
    ).dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        KnineDialogSurface(
            modifier =
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.9f)
                .verticalScroll(rememberScrollState())
                .wrapContentHeight(),
        ) {
            Text(stringResource(R.string.knine_qr_scan_hint), modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(qrSide),
                shape = RoundedCornerShape(0.dp),
                color = Color.White,
            ) {
                BoxWithConstraints(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val pixelSize = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1)
                    val bitmap = QRCodeGenerator.rememberBitmap(content, pixelSize)
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(io.nekohasekai.sfa.R.string.content_description_qr_code),
                        modifier = Modifier.fillMaxSize(),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.close))
            }
        }
    }
}
