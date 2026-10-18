package cc.txwd.kone.app

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

/**
 * Seals what must not be read off the disk: the device credential and the nodes the console
 * sent. On Windows that is DPAPI for the current user, so only this user on this computer can
 * open it; a copy of the folder elsewhere is useless.
 */
interface Vault {
    fun seal(plain: ByteArray): ByteArray

    /** The plain bytes, or null when they cannot be opened here (another user, another computer). */
    fun open(sealed: ByteArray): ByteArray?

    companion object {
        fun forThisComputer(): Vault = if (System.getProperty("os.name").startsWith("Windows")) Dpapi else Memory()
    }

    /** DPAPI, with KOne's own entropy so a plain CryptUnprotectData by another program does not open it. */
    object Dpapi : Vault {
        private val entropy = "KOne device credential".toByteArray()

        override fun seal(plain: ByteArray): ByteArray =
            Crypt32Util.cryptProtectData(plain, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "KOne", null)

        override fun open(sealed: ByteArray): ByteArray? =
            runCatching { Crypt32Util.cryptUnprotectData(sealed, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null) }.getOrNull()
    }

    /**
     * Away from Windows (the cloud, tests) nothing is written in the clear: what is sealed opens
     * only in this run, so a binding there does not outlive the process.
     */
    class Memory : Vault {
        private val kept = HashMap<String, ByteArray>()

        override fun seal(plain: ByteArray): ByteArray {
            val token = java.util.UUID.randomUUID().toString()
            synchronized(kept) { kept[token] = plain.copyOf() }
            return token.toByteArray()
        }

        override fun open(sealed: ByteArray): ByteArray? = synchronized(kept) { kept[sealed.decodeToString()]?.copyOf() }
    }
}
