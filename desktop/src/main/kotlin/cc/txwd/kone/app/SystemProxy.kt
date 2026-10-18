package cc.txwd.kone.app

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.util.Properties

/**
 * Windows' own proxy setting, the one browsers and most programs follow: pointed at KOne's port
 * while KOne connects without TUN, and put back exactly as it was afterwards. The setting before
 * is kept in a file until it is put back, so a KOne that crashed puts it back when it next starts.
 */
object SystemProxy {
    /** The proxy as Windows has it: how it connects (direct, a proxy, a PAC address, finding one itself), and each part. */
    data class Setting(val flags: Int, val server: String?, val bypass: String?, val pac: String?)

    /** Never through the proxy: this computer and the home network. */
    private const val LOCAL = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;" +
        "172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"

    private const val PROXY_TYPE_DIRECT = 1
    private const val PROXY_TYPE_PROXY = 2

    private val saved get() = File(Places.data, "proxy-before.properties")

    /** Points Windows at 127.0.0.1:[port], having kept what was there; false when Windows would not. */
    @Synchronized
    fun point(port: Int): Boolean {
        val before = read() ?: return false
        // Kept once: when KOne points it again, what it points away from is still the person's own.
        if (!saved.isFile) keep(before)
        return write(Setting(PROXY_TYPE_DIRECT or PROXY_TYPE_PROXY, "127.0.0.1:$port", LOCAL, null))
    }

    /** Puts back what was there before KOne pointed it, if KOne did. */
    @Synchronized
    fun restore() {
        val file = saved
        if (!file.isFile) return
        val values = Properties().apply { runCatching { file.inputStream().use(::load) } }
        val before = Setting(
            flags = values.getProperty("flags")?.toIntOrNull() ?: PROXY_TYPE_DIRECT,
            server = values.getProperty("server"), bypass = values.getProperty("bypass"), pac = values.getProperty("pac"),
        )
        if (write(before)) file.delete()
    }

    /** Whether Windows' proxy is KOne's on [port] now. */
    fun pointsAt(port: Int): Boolean = read()?.let { it.server == "127.0.0.1:$port" && it.flags and PROXY_TYPE_PROXY != 0 } == true

    private fun keep(setting: Setting) {
        val values = Properties()
        values.setProperty("flags", setting.flags.toString())
        setting.server?.let { values.setProperty("server", it) }
        setting.bypass?.let { values.setProperty("bypass", it) }
        setting.pac?.let { values.setProperty("pac", it) }
        saved.parentFile.mkdirs()
        saved.outputStream().use { values.store(it, null) }
    }

    // WinINet's per-connection options, laid out as on 64-bit Windows: an option is 16 bytes
    // (its number, then a DWORD or a string pointer at 8), the list 32.
    private const val INTERNET_OPTION_PER_CONNECTION_OPTION = 75
    private const val INTERNET_OPTION_SETTINGS_CHANGED = 39
    private const val INTERNET_OPTION_REFRESH = 37
    private const val FLAGS = 1
    private const val SERVER = 2
    private const val BYPASS = 3
    private const val PAC = 4

    private interface WinInet : StdCallLibrary {
        fun InternetSetOptionW(handle: Pointer?, option: Int, buffer: Pointer?, length: Int): Boolean
        fun InternetQueryOptionW(handle: Pointer?, option: Int, buffer: Pointer?, length: IntByReference): Boolean
    }

    private val wininet: WinInet? by lazy { runCatching { Native.load("wininet", WinInet::class.java) }.getOrNull() }

    private fun list(options: Memory, count: Int) = Memory(32).apply {
        clear()
        setInt(0, 32)
        setPointer(8, null)
        setInt(16, count)
        setInt(20, 0)
        setPointer(24, options)
    }

    fun read(): Setting? {
        val api = wininet ?: return null
        val asked = intArrayOf(FLAGS, SERVER, BYPASS, PAC)
        val options = Memory(16L * asked.size).apply { clear() }
        asked.forEachIndexed { i, option -> options.setInt(16L * i, option) }
        val list = list(options, asked.size)
        if (!api.InternetQueryOptionW(null, INTERNET_OPTION_PER_CONNECTION_OPTION, list, IntByReference(32))) return null
        fun text(i: Int): String? {
            val pointer = options.getPointer(16L * i + 8) ?: return null
            return pointer.getWideString(0).also { Kernel32.INSTANCE.GlobalFree(pointer) }.ifEmpty { null }
        }
        return Setting(options.getInt(8), text(1), text(2), text(3))
    }

    private fun write(setting: Setting): Boolean {
        val api = wininet ?: return false
        val texts = mutableListOf<Memory>()
        fun wide(text: String?): Pointer? = text?.let { Memory((it.length + 1) * 2L).apply { setWideString(0, it) }.also(texts::add) }
        val options = Memory(16L * 4).apply { clear() }
        options.setInt(0, FLAGS)
        options.setInt(8, setting.flags)
        options.setInt(16, SERVER)
        options.setPointer(24, wide(setting.server))
        options.setInt(32, BYPASS)
        options.setPointer(40, wide(setting.bypass))
        options.setInt(48, PAC)
        options.setPointer(56, wide(setting.pac))
        val done = api.InternetSetOptionW(null, INTERNET_OPTION_PER_CONNECTION_OPTION, list(options, 4), 32)
        // Programs that follow the setting hear it changed.
        api.InternetSetOptionW(null, INTERNET_OPTION_SETTINGS_CHANGED, null, 0)
        api.InternetSetOptionW(null, INTERNET_OPTION_REFRESH, null, 0)
        texts.clear()
        return done
    }
}
