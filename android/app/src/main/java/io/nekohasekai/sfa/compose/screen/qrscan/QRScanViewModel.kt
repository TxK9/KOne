package io.nekohasekai.sfa.compose.screen.qrscan

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import android.util.Base64
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import cc.txwd.kone.shared.ShareLinks
import cc.txwd.kone.shared.SubscriptionContent
import io.nekohasekai.sfa.qrs.QRSDecoder
import io.nekohasekai.sfa.qrs.readIntLE
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed class QRScanResult {
    data class RemoteProfile(val uri: Uri) : QRScanResult()
    /** A KOne claim link (see ManagedClaimLink); never printed, it carries an invite code. */
    data class ClaimLink(val link: String) : QRScanResult() {
        override fun toString(): String = "ClaimLink(redacted)"
    }
    /** A node share link (see [ShareLinks]); never printed, it carries credentials. */
    data class NodeLink(val link: String) : QRScanResult() {
        override fun toString(): String = "NodeLink(redacted)"
    }
    /** A subscription's https link, from itself or another client's import link (see SubscriptionContent.linkOf); never printed, it carries a token. */
    data class SubscriptionLink(val link: String) : QRScanResult() {
        override fun toString(): String = "SubscriptionLink(redacted)"
    }
    data class QRSData(val data: ByteArray) : QRScanResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as QRSData
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }
}

data class QRScanUiState(
    val isLoading: Boolean = true,
    val isDecodingImage: Boolean = false,
    val cameraReady: Boolean = false,
    val hasFlashUnit: Boolean = false,
    val useFrontCamera: Boolean = false,
    val torchEnabled: Boolean = false,
    val useVendorAnalyzer: Boolean = true,
    val vendorAnalyzerAvailable: Boolean = false,
    val qrsMode: Boolean = false,
    val qrsProgress: Pair<Int, Int>? = null,
    val cropArea: QRCodeCropArea? = null,
    val errorMessage: String? = null,
    val result: QRScanResult? = null,
    val zoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
)

class QRScanViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "QRScanViewModel"
    }

    private val _uiState = MutableStateFlow(QRScanUiState())
    val uiState: StateFlow<QRScanUiState> = _uiState.asStateFlow()

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageAnalyzer: ImageAnalysis.Analyzer? = null
    private var cameraPreview: Preview? = null
    private var cameraGeneration = 0
    private var sessionGeneration = 0
    @Volatile private var sessionOpen = false
    @Volatile private var pickingImage = false

    private var qrsDecoder: QRSDecoder? = null
    private val showingError = AtomicBoolean(false)
    private val qrsLock = Any()

    private val vendorAnalyzer: ImageAnalysis.Analyzer? = Vendor.createQRCodeAnalyzer(
        onSuccess = { rawValue -> handleScanSuccess(rawValue) },
        onFailure = { exception -> handleScanFailure(exception) },
        onCropArea = ::updateCropArea,
    )

    init {
        _uiState.update {
            it.copy(
                vendorAnalyzerAvailable = vendorAnalyzer != null,
                useVendorAnalyzer = vendorAnalyzer != null,
            )
        }
    }

    private val onSuccess: (String) -> Unit = { rawValue: String ->
        handleScanSuccess(rawValue)
    }

    private val onFailure: (Exception) -> Unit = { exception ->
        handleScanFailure(exception)
    }

    private fun updateCropArea(area: QRCodeCropArea?) {
        _uiState.update { state ->
            if (state.cropArea == area) {
                state
            } else {
                state.copy(cropArea = area)
            }
        }
    }

    private fun handleScanSuccess(rawValue: String) {
        if (!sessionOpen || pickingImage || showingError.get() || _uiState.value.result != null) return
        Log.d(TAG, "QR payload recognized")
        val qrsPayload = extractQRSPayload(rawValue)
        Log.d(TAG, "extractQRSPayload result: ${qrsPayload?.size ?: "null"}")
        if (qrsPayload != null) {
            handleQRSFrame(qrsPayload)
            if (_uiState.value.result == null && !showingError.get()) {
                imageAnalyzer?.let { imageAnalysis?.setAnalyzer(analysisExecutor, it) }
            }
        } else {
            updateCropArea(null)
            if (_uiState.value.qrsMode) {
                resetQRSState()
            }
            imageAnalysis?.clearAnalyzer()
            processQRCode(rawValue)
        }
    }

    private fun handleScanFailure(exception: Exception) {
        if (!sessionOpen || pickingImage) return
        updateCropArea(null)
        imageAnalysis?.clearAnalyzer()
        if (showingError.compareAndSet(false, true)) {
            resetAnalyzer()
            // Reader / buffer exceptions often have no message. A null value
            // would stop analysis while leaving the error dialog invisible.
            Log.w(TAG, "QR analysis paused: ${exception.javaClass.simpleName}")
            _uiState.update {
                it.copy(errorMessage = "QR scanning was interrupted. Try again or scan an image.", isLoading = false)
            }
        }
    }

    private fun resetAnalyzer() {
        if (_uiState.value.useVendorAnalyzer && vendorAnalyzer != null) {
            _uiState.update { it.copy(useVendorAnalyzer = false) }
            imageAnalysis?.clearAnalyzer()
            imageAnalyzer = ZxingQRCodeAnalyzer(onSuccess, onFailure, onCropArea = ::updateCropArea)
            imageAnalysis?.setAnalyzer(analysisExecutor, imageAnalyzer!!)
        }
    }

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (!sessionOpen) return
        val generation = ++cameraGeneration
        Log.d(TAG, "QR camera request started")
        val context = getApplication<Application>()
        val cameraProviderFuture = try {
            ProcessCameraProvider.getInstance(context)
        } catch (e: Exception) {
            showScanError("The camera could not start. Reopen the scanner or scan an image.")
            return
        }

        cameraProviderFuture.addListener({
            if (!sessionOpen || generation != cameraGeneration) {
                Log.d(TAG, "QR stale camera request ignored")
                return@addListener
            }
            try {
                cameraProvider = cameraProviderFuture.get()

                cameraPreview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .build()

                imageAnalyzer = if (_uiState.value.useVendorAnalyzer && vendorAnalyzer != null) {
                    vendorAnalyzer
                } else {
                    ZxingQRCodeAnalyzer(onSuccess, onFailure, onCropArea = ::updateCropArea)
                }
                imageAnalysis?.setAnalyzer(analysisExecutor, imageAnalyzer!!)

                bindCamera(lifecycleOwner)
            } catch (e: Exception) {
                showScanError("The camera could not start. Reopen the scanner or scan an image.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return
        val preview = cameraPreview ?: return
        val analysis = imageAnalysis ?: return

        provider.unbindAll()

        val cameraSelector = if (_uiState.value.useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                analysis,
            )
            val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            _uiState.update {
                it.copy(maxZoomRatio = maxZoom, zoomRatio = 1f, cameraReady = true,
                    hasFlashUnit = camera?.cameraInfo?.hasFlashUnit() == true, torchEnabled = false)
            }
            Log.d(TAG, "QR camera bound")
        } catch (e: Exception) {
            showScanError("Cannot connect to the camera. Check camera permissions or scan an image.")
        }
    }

    fun beginScanSession() {
        stopCamera()
        sessionGeneration++
        sessionOpen = true
        pickingImage = false
        showingError.set(false)
        resetQRSState()
        Log.d(TAG, "QR scan session opened")
        _uiState.update {
            it.copy(errorMessage = null, result = null, cropArea = null, isLoading = true, isDecodingImage = false,
                cameraReady = false, hasFlashUnit = false, torchEnabled = false, zoomRatio = 1f, maxZoomRatio = 1f)
        }
    }

    fun endScanSession() {
        sessionOpen = false
        sessionGeneration++
        pickingImage = false
        stopCamera()
    }

    fun stopCamera() {
        cameraGeneration++
        if (cameraProvider != null || camera != null) Log.d(TAG, "QR camera bindings cleared")
        imageAnalysis?.clearAnalyzer()
        camera?.cameraControl?.enableTorch(false)
        cameraPreview?.setSurfaceProvider(null)
        cameraProvider?.unbindAll()
        camera = null
        cameraPreview = null
        imageAnalysis = null
        imageAnalyzer = null
        cameraProvider = null
        _uiState.update { it.copy(cameraReady = false, hasFlashUnit = false, torchEnabled = false) }
    }

    fun onPreviewStreamStateChanged(isStreaming: Boolean) {
        if (isStreaming) {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun toggleFrontCamera(lifecycleOwner: LifecycleOwner) {
        _uiState.update { it.copy(useFrontCamera = !it.useFrontCamera) }
        bindCamera(lifecycleOwner)
    }

    fun toggleTorch() {
        if (!_uiState.value.cameraReady || !_uiState.value.hasFlashUnit) return
        val newTorchState = !_uiState.value.torchEnabled
        camera?.cameraControl?.enableTorch(newTorchState)
        _uiState.update { it.copy(torchEnabled = newTorchState) }
    }

    fun setZoomRatio(ratio: Float) {
        val clampedRatio = ratio.coerceIn(1f, _uiState.value.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(clampedRatio)
        _uiState.update { it.copy(zoomRatio = clampedRatio) }
    }

    fun toggleVendorAnalyzer() {
        if (vendorAnalyzer == null) return

        val newState = !_uiState.value.useVendorAnalyzer
        _uiState.update { it.copy(useVendorAnalyzer = newState) }
        updateCropArea(null)

        imageAnalysis?.clearAnalyzer()
        imageAnalyzer = if (newState) {
            vendorAnalyzer
        } else {
            ZxingQRCodeAnalyzer(onSuccess, onFailure, onCropArea = ::updateCropArea)
        }
        imageAnalysis?.setAnalyzer(analysisExecutor, imageAnalyzer!!)
    }

    fun dismissError() {
        showingError.set(false)
        _uiState.update { it.copy(errorMessage = null) }
        resumeAnalyzer()
    }

    fun pauseForImagePicker() {
        pickingImage = true
        imageAnalysis?.clearAnalyzer()
    }

    fun cancelImagePicker() {
        pickingImage = false
        resumeAnalyzer()
    }

    fun imagePickerUnavailable() {
        pickingImage = false
        showScanError("Cannot open the image picker. Try again later.")
    }

    private fun resumeAnalyzer() {
        if (sessionOpen && !pickingImage && !showingError.get() && _uiState.value.result == null) {
            imageAnalyzer?.let { imageAnalysis?.setAnalyzer(analysisExecutor, it) }
        }
    }

    fun scanImage(uri: Uri) {
        if (!sessionOpen) return
        val generation = sessionGeneration
        imageAnalysis?.clearAnalyzer()
        pickingImage = true
        resetQRSState()
        showingError.set(false)
        _uiState.update { it.copy(errorMessage = null, isDecodingImage = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    var sample = 1
                    while (maxOf(options.outWidth, options.outHeight) / sample > 3072) sample *= 2
                    options.inJustDecodeBounds = false
                    options.inSampleSize = sample
                    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        ?: throw IllegalArgumentException("image unavailable")
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        Result.success(QRImageDecoder.decode(pixels, bitmap.width, bitmap.height))
                    } finally {
                        bitmap.recycle()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (!sessionOpen || generation != sessionGeneration) return@launch
            pickingImage = false
            _uiState.update { it.copy(isDecodingImage = false) }
            val content = result.getOrNull()
            when {
                result.isFailure -> showScanError("Cannot read this image. Choose another image or save it to the device first.")
                content == null -> showScanError("No QR code found. Choose a clear image of the complete node QR code.")
                else -> {
                    handleScanSuccess(content)
                    if (_uiState.value.qrsMode && _uiState.value.result == null && !showingError.get()) {
                        resetQRSState()
                        showScanError("This image contains one frame of an animated QR code. Scan the full animation with the camera.")
                    }
                }
            }
        }
    }

    private fun showScanError(message: String) {
        if (!sessionOpen) return
        imageAnalysis?.clearAnalyzer()
        if (showingError.compareAndSet(false, true)) {
            _uiState.update { it.copy(errorMessage = message, isLoading = false) }
        }
    }

    fun clearResult() {
        resetQRSState()
        _uiState.update { it.copy(result = null, cropArea = null) }
    }

    private fun extractQRSPayload(content: String): ByteArray? {
        val base64Data = when {
            content.startsWith("http") && content.contains("#") -> {
                content.substring(content.indexOf('#') + 1)
            }
            else -> content
        }

        val decoded = try {
            Base64.decode(base64Data, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.d(TAG, "Payload is not QRS base64")
            return null
        }

        Log.d(TAG, "Decoded size: ${decoded.size}")
        if (decoded.size < 20) {
            Log.d(TAG, "Too small: ${decoded.size} < 20")
            return null
        }

        val degree = decoded.readIntLE(0)
        Log.d(TAG, "degree: $degree")
        if (degree <= 0 || degree > 1000) {
            Log.d(TAG, "Invalid degree: $degree")
            return null
        }

        val headerSize = 4 + 4 * degree + 12
        if (decoded.size < headerSize) {
            Log.d(TAG, "Too small for header: ${decoded.size} < $headerSize")
            return null
        }

        val k = decoded.readIntLE(4 + 4 * degree)
        Log.d(TAG, "k: $k")
        if (k <= 0 || k > 100000) {
            Log.d(TAG, "Invalid k: $k")
            return null
        }

        Log.d(TAG, "Valid QRS block detected!")
        return decoded
    }

    private fun handleQRSFrame(payload: ByteArray) = synchronized(qrsLock) {
        Log.d(TAG, "Processing QRS frame")
        if (qrsDecoder == null) {
            qrsDecoder = QRSDecoder()
            _uiState.update { it.copy(qrsMode = true) }
            (imageAnalyzer as? ZxingQRCodeAnalyzer)?.qrsMode = true
            Log.d(TAG, "Created new QRSDecoder, entered QRS mode")
        }

        val progress = qrsDecoder!!.processFrame(payload)
        Log.d(TAG, "processFrame result: $progress")
        if (progress == null) {
            Log.d(TAG, "processFrame returned null!")
            return@synchronized
        }

        _uiState.update {
            it.copy(qrsProgress = Pair(progress.decodedBlocks, progress.totalBlocks))
        }

        if (progress.isComplete) {
            if (progress.error != null) {
                Log.e(TAG, "QRS validation failed")
                resetQRSState()
                showScanError("Animated QR verification failed. Scan the full animation again or export a new configuration QR code.")
            } else if (progress.data != null) {
                imageAnalysis?.clearAnalyzer()
                Log.d(TAG, "QRS complete! Data size: ${progress.data.size}")
                importQRSProfile(progress.data)
            }
        }
    }

    fun resetQRSState() = synchronized(qrsLock) {
        qrsDecoder?.reset()
        qrsDecoder = null
        (imageAnalyzer as? ZxingQRCodeAnalyzer)?.qrsMode = false
        _uiState.update { it.copy(qrsMode = false, qrsProgress = null) }
    }

    private fun parseQRSFileFormat(data: ByteArray): ByteArray {
        var offset = 0

        val metaLength = ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        offset += 4

        offset += metaLength

        val dataLength = ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        offset += 4

        return data.copyOfRange(offset, offset + dataLength)
    }

    private fun importQRSProfile(data: ByteArray) {
        try {
            val actualData = try {
                parseQRSFileFormat(data)
            } catch (e: Exception) {
                Log.d(TAG, "Not official QRS format, using raw data")
                data
            }
            Log.d(TAG, "Decoding profile content, size: ${actualData.size}")
            Libbox.decodeProfileContent(actualData)
            _uiState.update { it.copy(result = QRScanResult.QRSData(actualData)) }
        } catch (e: Exception) {
            resetQRSState()
            showScanError("Cannot read the QR configuration. Export a complete sing-box configuration QR code again.")
        }
    }

    private fun processQRCode(value: String): Boolean {
        try {
            val normalized = value.trim()
            if (io.nekohasekai.sfa.managed.ManagedClaimLink.parse(normalized) != null) {
                _uiState.update { it.copy(result = QRScanResult.ClaimLink(normalized)) }
                return true
            }
            if (ShareLinks.isShareLink(normalized)) {
                ShareLinks.parse(normalized)
                _uiState.update { it.copy(result = QRScanResult.NodeLink(normalized)) }
                return true
            }
            val uri = Uri.parse(normalized)
            if (uri.scheme == "sing-box" && uri.host == "import-remote-profile") {
                Libbox.parseRemoteProfileImportLink(uri.toString())
                _uiState.update { it.copy(result = QRScanResult.RemoteProfile(uri)) }
                return true
            }
            // A subscription's link, or another client's import link holding one (clash://install-config?url=…).
            SubscriptionContent.linkOf(normalized)?.let { link ->
                _uiState.update { it.copy(result = QRScanResult.SubscriptionLink(link)) }
                return true
            }
            showScanError("This QR code contains no recognized node link, subscription or sing-box remote configuration.")
            return false
        } catch (e: Exception) {
            showScanError(QRImportErrors.controlledMessage(e))
        }
        return false
    }

    override fun onCleared() {
        super.onCleared()
        endScanSession()
        analysisExecutor.shutdown()
    }
}
