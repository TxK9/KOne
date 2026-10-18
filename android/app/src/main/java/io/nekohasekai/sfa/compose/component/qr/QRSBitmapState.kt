package io.nekohasekai.sfa.compose.component.qr

import android.graphics.Bitmap
import io.nekohasekai.sfa.compose.util.QRCodeGenerator
import io.nekohasekai.sfa.qrs.QRSEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class QRSGenerationState(
    val currentBitmap: Bitmap? = null,
    val currentFrameIndex: Int = 0,
    val generatedCount: Int = 0,
    val totalFrames: Int = 0,
    val isGenerating: Boolean = true,
)

class QRSBitmapGenerator(
    private val scope: CoroutineScope,
    private val frames: List<QRSEncoder.QRSFrame>,
    private val foregroundColor: Int,
    private val backgroundColor: Int,
    private val bitmapSize: Int = 512,
    bufferSize: Int = 30,
) {
    private val _state = MutableStateFlow(QRSGenerationState(totalFrames = frames.size))
    val state: StateFlow<QRSGenerationState> = _state

    private val actualBufferSize = bufferSize.coerceAtLeast(1).coerceAtMost(frames.size.coerceAtLeast(1))
    private val bitmapBuffer = IndexedFrameBuffer<Bitmap>(actualBufferSize)
    private val generatedFrames = BooleanArray(frames.size)
    private var generatedCount = 0
    private var generationJob: Job? = null
    private var currentFrameIndex = 0
    private var windowStart = -1

    fun start() {
        if (frames.isEmpty()) {
            _state.value = _state.value.copy(isGenerating = false)
            return
        }
        prepareWindow(0)
    }

    private fun prepareWindow(startIndex: Int) {
        if (windowStart == startIndex && generationJob?.isActive == true) return
        generationJob?.cancel()
        windowStart = startIndex
        bitmapBuffer[startIndex]?.let { publish(startIndex, it) }
        _state.value = _state.value.copy(isGenerating = true)
        generationJob = scope.launch {
            // Cache only the current playback window; never label a recycled slot
            // as an earlier frame after the producer has wrapped around.
            for (offset in 0 until actualBufferSize) {
                val index = (startIndex + offset) % frames.size
                var bitmap = bitmapBuffer[index]
                if (bitmap == null) {
                    bitmap = withContext(Dispatchers.Default) {
                        QRCodeGenerator.generate(
                            content = frames[index].content,
                            size = bitmapSize,
                            foregroundColor = foregroundColor,
                            backgroundColor = backgroundColor,
                        )
                    }
                    bitmapBuffer.put(index, bitmap)
                    if (!generatedFrames[index]) {
                        generatedFrames[index] = true
                        generatedCount++
                    }
                }
                if (offset == 0) publish(index, bitmap)
                _state.value = _state.value.copy(generatedCount = generatedCount)
                yield()
            }
            _state.value = _state.value.copy(isGenerating = false)
        }
    }

    private fun publish(index: Int, bitmap: Bitmap) {
        currentFrameIndex = index
        _state.value = _state.value.copy(currentBitmap = bitmap, currentFrameIndex = index)
    }

    fun advanceFrame() {
        if (_state.value.currentBitmap == null || frames.isEmpty()) return
        prepareWindow((currentFrameIndex + 1) % frames.size)
    }

    fun cancel() {
        generationJob?.cancel()
        generationJob = null
        windowStart = -1
        bitmapBuffer.clear()
        // Drop references instead of recycling a bitmap that a retiring Image
        // may still draw during composition disposal.
        _state.value = _state.value.copy(currentBitmap = null, isGenerating = false)
    }
}
