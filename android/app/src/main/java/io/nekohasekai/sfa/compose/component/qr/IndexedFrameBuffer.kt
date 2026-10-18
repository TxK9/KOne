package io.nekohasekai.sfa.compose.component.qr

internal class IndexedFrameBuffer<T>(private val capacity: Int) {
    private val frames = LinkedHashMap<Int, T>()

    init { require(capacity > 0) }

    operator fun get(index: Int): T? = frames[index]

    fun put(index: Int, frame: T) {
        frames[index] = frame
        while (frames.size > capacity) frames.remove(frames.keys.first())
    }

    fun clear() = frames.clear()
    val size: Int get() = frames.size
}
