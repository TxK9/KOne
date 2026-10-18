package cc.txwd.kone.ink

import cc.txwd.kone.ui.Fonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Image

/**
 * The four paintings of the handscroll, painted once per theme off the UI thread and kept: the
 * painting takes a moment, showing it costs nothing.
 */
object InkGallery {
    /** How much of a painting's width it shares with its neighbour in the handscroll (150 of 764 in the preview's height). */
    const val SHARED = 150f / 573f

    /** Each painting as the phone shows it, alone: for the tray panel and the installer. */
    private val painted = HashMap<Boolean, List<Image>>()

    /** The same, laid side by side in the handscroll, where neighbours share a stretch of paper. */
    private val scrolled = HashMap<Boolean, List<Image>>()

    /** The Golden Gate for the tray panel, kept even while the rest is let go. */
    private val bridges = HashMap<Boolean, Image>()
    private val lock = Mutex()

    private val brush: Image? by lazy {
        InkGallery::class.java.getResourceAsStream("/ink-brush.png")?.use { Image.makeFromEncoded(it.readBytes()) }
    }

    /** The paintings for [dark] if they are ready, without waiting. */
    fun ready(dark: Boolean): List<Image>? = synchronized(painted) { painted[dark] }

    /** The tray panel's bridge for [dark] if it is ready, without waiting. */
    fun readyBridge(dark: Boolean): Image? = synchronized(painted) { bridges[dark] }

    suspend fun bridge(dark: Boolean): Image = readyBridge(dark) ?: paint(dark)[1]

    /** Lets the paintings go while no one looks at them, all but the tray panel's bridge; they are painted again when wanted. */
    fun rest() = synchronized(painted) {
        painted.clear()
        scrolled.clear()
    }

    /** The handscroll's paintings for [dark] if they are ready, without waiting. */
    fun readyScroll(dark: Boolean): List<Image>? = synchronized(painted) { scrolled[dark] }

    suspend fun paint(dark: Boolean): List<Image> {
        ensure(dark)
        return ready(dark)!!
    }

    suspend fun scroll(dark: Boolean): List<Image> {
        ensure(dark)
        return readyScroll(dark)!!
    }

    private suspend fun ensure(dark: Boolean) = lock.withLock {
        if (ready(dark) != null) return@withLock
        val faces = InkPainter.Faces(Fonts.sealTypeface, Fonts.mottoTypeface)
        val scenes = coroutineScope {
            InkMountains.all.map { mountain -> async(Dispatchers.Default) { InkPainter.render(mountain, dark, brush, faces) } }.awaitAll()
        }
        val alone = scenes.map { InkPainter.compose(it, dark) }
        val insets = scrollInsets(scenes.map { it.sealOnLeft })
        val inScroll = scenes.mapIndexed { index, scene ->
            if (insets[index] == InkPainter.SEAL_INSET) alone[index] else InkPainter.compose(scene, dark, sealInset = insets[index])
        }
        synchronized(painted) {
            painted[dark] = alone
            scrolled[dark] = inScroll
            bridges[dark] = alone[1]
        }
    }

    /**
     * How far in from its painting's edge each seal sits in the handscroll: just clear of the
     * stretch it shares with its neighbour. There a seal would fade with its painting, and a seal
     * on a painting's right would land on its right neighbour's on the left, the two showing
     * through each other.
     */
    fun scrollInsets(onLeft: List<Boolean>): List<Float> = onLeft.map { SHARED + 0.03f }
}
