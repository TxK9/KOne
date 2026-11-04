package io.nekohasekai.sfa.compose.component.qr

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import io.nekohasekai.sfa.compose.theme.KnineDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import io.nekohasekai.sfa.compose.theme.KnineGlassBottomSheet as ModalBottomSheet
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.qrscan.QRCodeCropArea
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanViewModel
import io.nekohasekai.sfa.compose.screen.qrscan.ScanMessage
import kotlin.math.max
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScanSheet(onDismiss: () -> Unit, onScanResult: (QRScanResult) -> Unit, viewModel: QRScanViewModel = viewModel()) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        hasPermission = isGranted
        permissionDenied = !isGranted
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.scanImage(uri) else viewModel.cancelImagePicker()
    }

    val chooseImage = {
        viewModel.pauseForImagePicker()
        try {
            imageLauncher.launch("image/*")
        } catch (_: ActivityNotFoundException) {
            viewModel.imagePickerUnavailable()
        }
    }

    DisposableEffect(viewModel) {
        viewModel.beginScanSession()
        onDispose { viewModel.endScanSession() }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(uiState.result) {
        uiState.result?.let { result ->
            viewModel.endScanSession()
            viewModel.clearResult()
            onScanResult(result)
        }
    }

    var showMenu by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.profile_add_scan_qr_code),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = chooseImage, enabled = !uiState.isDecodingImage) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.knine_qr_image), modifier = Modifier.padding(start = 6.dp))
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.knine_qr_scan_from_image)) },
                                enabled = !uiState.isDecodingImage,
                                onClick = {
                                    showMenu = false
                                    chooseImage()
                                },
                            )
                            DropdownMenuItem(
                                enabled = uiState.cameraReady && !uiState.isDecodingImage,
                                text = {
                                    Text(
                                        (if (uiState.useFrontCamera) "✓ " else "   ") +
                                            stringResource(R.string.profile_add_scan_use_front_camera),
                                    )
                                },
                                onClick = {
                                    viewModel.toggleFrontCamera(lifecycleOwner)
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                enabled = uiState.cameraReady && uiState.hasFlashUnit && !uiState.isDecodingImage,
                                text = {
                                    Text(
                                        (if (uiState.torchEnabled) "✓ " else "   ") +
                                            stringResource(R.string.profile_add_scan_enable_torch),
                                    )
                                },
                                onClick = {
                                    viewModel.toggleTorch()
                                    showMenu = false
                                },
                            )
                            if (uiState.vendorAnalyzerAvailable) {
                                DropdownMenuItem(
                                    enabled = uiState.cameraReady && !uiState.isDecodingImage,
                                    text = {
                                        Text(
                                            (if (uiState.useVendorAnalyzer) "✓ " else "   ") +
                                                stringResource(R.string.profile_add_scan_use_vendor_analyzer),
                                        )
                                    },
                                    onClick = {
                                        viewModel.toggleVendorAnalyzer()
                                        showMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.knine_qr_aim_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (uiState.cameraReady && uiState.maxZoomRatio > 1f) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { viewModel.setZoomRatio(uiState.zoomRatio - 0.5f) }) { Text("−") }
                    Slider(
                        value = uiState.zoomRatio.coerceAtMost(uiState.maxZoomRatio.coerceAtMost(6f)),
                        onValueChange = viewModel::setZoomRatio,
                        enabled = !uiState.isDecodingImage,
                        valueRange = 1f..uiState.maxZoomRatio.coerceAtMost(6f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(String.format(Locale.ROOT, "%.1f×", uiState.zoomRatio), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { viewModel.setZoomRatio((uiState.zoomRatio + 0.5f).coerceAtMost(6f)) }) { Text("＋") }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f), RoundedCornerShape(24.dp)),
            ) {
                if (hasPermission) {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = viewModel,
                        lifecycleOwner = lifecycleOwner,
                        cropArea = uiState.cropArea,
                    )
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.knine_qr_camera_off), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.knine_qr_camera_off_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        TextButton(onClick = {
                            if (permissionDenied) {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            } else {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }) {
                            Text(stringResource(if (permissionDenied) R.string.knine_qr_open_permission_settings else R.string.knine_qr_allow_camera))
                        }
                        TextButton(onClick = chooseImage, enabled = !uiState.isDecodingImage) { Text(stringResource(R.string.knine_qr_scan_image)) }
                    }
                }

                if ((hasPermission && uiState.isLoading) || uiState.isDecodingImage) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(io.nekohasekai.sfa.compose.theme.wallpaperPanelColor(Color.Black.copy(alpha = 0.64f))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                            Text(stringResource(if (uiState.isDecodingImage) R.string.knine_qr_scanning_image else R.string.knine_qr_opening_camera), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (uiState.qrsMode && uiState.qrsProgress != null) {
                    val (decoded, total) = uiState.qrsProgress!!
                    val progress = if (total > 0) decoded.toFloat() / total.toFloat() else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(io.nekohasekai.sfa.compose.theme.wallpaperPanelColor(Color.Black.copy(alpha = 0.5f))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.size(96.dp),
                                color = Color.White,
                                strokeWidth = 8.dp,
                                trackColor = Color.White.copy(alpha = 0.3f),
                            )
                            if (total > 0) {
                                Text(
                                    text = "${minOf(99, (progress * 100).toInt())}%",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = Color.White,
                                )
                            }
                            Text(
                                text = stringResource(R.string.knine_qr_animated),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier.offset(y = (-88).dp),
                            )
                            Text(
                                text = stringResource(R.string.knine_qr_animated_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                modifier = Modifier.offset(y = 84.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    uiState.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text(stringResource(R.string.knine_qr_scan_incomplete)) },
            text = {
                Text(
                    when (message) {
                        is ScanMessage.Resource -> stringResource(message.id)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text(stringResource(if (hasPermission) R.string.knine_qr_continue_scanning else R.string.knine_qr_got_it))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissError()
                    chooseImage()
                }) { Text(stringResource(R.string.knine_qr_choose_image)) }
            },
        )
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    viewModel: QRScanViewModel,
    lifecycleOwner: LifecycleOwner,
    cropArea: QRCodeCropArea?,
) {
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(previewView) {
        val attachedView = previewView
        val observer = Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING) viewModel.onPreviewStreamStateChanged(true)
        }
        attachedView?.let { view ->
            view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            view.previewStreamState.observe(lifecycleOwner, observer)
            viewModel.startCamera(lifecycleOwner, view)
        }
        onDispose {
            if (attachedView != null) {
                attachedView.previewStreamState.removeObserver(observer)
                viewModel.stopCamera()
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = this
                }
            },
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val rect = cropArea?.let { mapCropAreaToPreview(it, size.width, size.height) } ?: return@Canvas
            drawRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

private fun mapCropAreaToPreview(area: QRCodeCropArea, viewWidth: Float, viewHeight: Float): Rect? {
    if (viewWidth <= 0f || viewHeight <= 0f) return null

    val rotation = ((area.rotationDegrees % 360) + 360) % 360
    var rotLeft = area.left.toFloat()
    var rotTop = area.top.toFloat()
    var rotRight = area.right.toFloat()
    var rotBottom = area.bottom.toFloat()
    var imageWidth = area.imageWidth.toFloat()
    var imageHeight = area.imageHeight.toFloat()
    when (rotation) {
        90 -> {
            rotLeft = (area.imageHeight - area.bottom).toFloat()
            rotTop = area.left.toFloat()
            rotRight = (area.imageHeight - area.top).toFloat()
            rotBottom = area.right.toFloat()
            imageWidth = area.imageHeight.toFloat()
            imageHeight = area.imageWidth.toFloat()
        }
        180 -> {
            rotLeft = (area.imageWidth - area.right).toFloat()
            rotTop = (area.imageHeight - area.bottom).toFloat()
            rotRight = (area.imageWidth - area.left).toFloat()
            rotBottom = (area.imageHeight - area.top).toFloat()
        }
        270 -> {
            rotLeft = area.top.toFloat()
            rotTop = (area.imageWidth - area.right).toFloat()
            rotRight = area.bottom.toFloat()
            rotBottom = (area.imageWidth - area.left).toFloat()
            imageWidth = area.imageHeight.toFloat()
            imageHeight = area.imageWidth.toFloat()
        }
    }

    if (imageWidth <= 0f || imageHeight <= 0f) return null

    val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
    val dx = (viewWidth - imageWidth * scale) / 2f
    val dy = (viewHeight - imageHeight * scale) / 2f

    val left = rotLeft * scale + dx
    val top = rotTop * scale + dy
    val right = rotRight * scale + dx
    val bottom = rotBottom * scale + dy

    val clampedLeft = left.coerceIn(0f, viewWidth)
    val clampedTop = top.coerceIn(0f, viewHeight)
    val clampedRight = right.coerceIn(0f, viewWidth)
    val clampedBottom = bottom.coerceIn(0f, viewHeight)

    if (clampedRight - clampedLeft < 4f || clampedBottom - clampedTop < 4f) return null

    return Rect(clampedLeft, clampedTop, clampedRight, clampedBottom)
}
