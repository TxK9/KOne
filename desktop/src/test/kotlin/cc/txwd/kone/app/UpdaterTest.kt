package cc.txwd.kone.app

import cc.txwd.kone.console.FakeConsole
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.security.KeyPairGenerator
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Updates as KOne takes them: only a latest.json signed by the release-signing key, only a newer
 * version, only over HTTPS, and only an MSI that matches it to the byte. A website made for
 * the test plays the release publisher's; the keys are made here and thrown away.
 */
class UpdaterTest {
    private val owner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val folder = Files.createTempDirectory("kone-updates").toFile()
    private val msi = File(folder, "built.msi").apply { writeBytes(ByteArray(300_000) { (it * 31).toByte() }) }

    /** The owner's website: whatever files the test puts in [files], over HTTPS on 127.0.0.1. */
    private class Site : AutoCloseable {
        val files = mutableMapOf<String, ByteArray>()
        private val keystore = FakeConsole.makeKeystore()
        private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply {
                init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keystore, FakeConsole.PASSWORD) }.keyManagers, null, null)
            })
            createContext("/") { exchange ->
                exchange.use {
                    val body = files[it.requestURI.path]
                    if (body == null) it.sendResponseHeaders(404, -1) else {
                        it.sendResponseHeaders(200, body.size.toLong())
                        it.responseBody.use { out -> out.write(body) }
                    }
                }
            }
            start()
        }
        val base = "https://127.0.0.1:${server.address.port}"
        val tls: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keystore) }.trustManagers, null)
        }

        override fun close() = server.stop(0)
    }

    private fun publish(site: Site, version: String, signer: java.security.PrivateKey = owner.private, body: ByteArray? = null) {
        site.files["/kone/KOne-$version.msi"] = msi.readBytes()
        val manifest = body ?: Manifest.write(version, "${site.base}/kone/KOne-$version.msi", msi)
        site.files["/kone/latest.json"] = manifest
        site.files["/kone/latest.json.sig"] = Manifest.sign(manifest, signer).toByteArray()
    }

    private fun updater(site: Site, current: String = "1.0.5") =
        Updater(URI("${site.base}/kone/latest.json"), owner.public, current, File(folder, "updates"), site.tls)

    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(Updater.newer("1.0.10", "1.0.9"))
        assertTrue(Updater.newer("1.1.0", "1.0.99"))
        assertFalse(Updater.newer("1.0.5", "1.0.5"))
        assertFalse(Updater.newer("1.0.4", "1.0.5"))
        assertFalse(Updater.newer("1.0.x", "1.0.5"))
    }

    @Test
    fun aSignedNewerReleaseIsFoundAndDownloadedIntact(): Unit = Site().use { site ->
        publish(site, "1.0.6")
        val release = assertNotNull(updater(site).check())
        assertEquals("1.0.6", release.version)
        var last = 0f
        val file = updater(site).download(release) { last = it }
        assertEquals(1f, last)
        assertTrue(file.readBytes().contentEquals(msi.readBytes()))
        assertEquals("KOne-1.0.6.msi", file.name)
        assertFalse(File(file.parentFile, "KOne-1.0.6.msi.part").exists())
    }

    @Test
    fun theSameOrAnOlderVersionIsNotAnUpdate(): Unit = Site().use { site ->
        publish(site, "1.0.5")
        assertNull(updater(site).check())
        publish(site, "1.0.4")
        assertNull(updater(site).check(), "an older KOne, even signed, is never offered")
    }

    @Test
    fun onlyTheOwnersSignatureCounts(): Unit = Site().use { site ->
        publish(site, "1.0.6", signer = stranger.private)
        assertFailsWith<UpdateException> { updater(site).check() }

        // A signed manifest changed afterwards (here: another MSI's hash) no longer verifies.
        publish(site, "1.0.6")
        val tampered = site.files["/kone/latest.json"]!!.decodeToString().replace(Regex("\"sha256\":\"[0-9a-f]+\""), "\"sha256\":\"${"0".repeat(64)}\"")
        site.files["/kone/latest.json"] = tampered.toByteArray()
        assertFailsWith<UpdateException> { updater(site).check() }

        site.files.remove("/kone/latest.json.sig")
        assertFailsWith<UpdateException> { updater(site).check() }
    }

    @Test
    fun aManifestMustBeForKOneAndPointToHttps() {
        val plain = Manifest.write("1.0.6", "http://203.0.113.5/KOne-1.0.6.msi", msi)
        assertNull(Manifest.read(plain, Manifest.sign(plain, owner.private), owner.public), "an MSI over plain HTTP is refused")
        val other = """{"app":"Other","version":"1.0.6","msi":"https://203.0.113.5/a.msi","size":1,"sha256":"${"a".repeat(64)}"}""".toByteArray()
        assertNull(Manifest.read(other, Manifest.sign(other, owner.private), owner.public))
        val good = Manifest.write("1.0.6", "https://203.0.113.5/KOne-1.0.6.msi", msi)
        assertNotNull(Manifest.read(good, Manifest.sign(good, owner.private), owner.public))
        assertFailsWith<IllegalArgumentException> { Updater(URI("http://203.0.113.5/latest.json"), owner.public, "1.0.5", folder) }
    }

    @Test
    fun anMsiThatDoesNotMatchIsThrownAway(): Unit = Site().use { site ->
        publish(site, "1.0.6")
        val release = assertNotNull(updater(site).check())
        site.files["/kone/KOne-1.0.6.msi"] = msi.readBytes().also { it[1000] = (it[1000] + 1).toByte() }
        assertFailsWith<UpdateException> { updater(site).download(release) }
        site.files["/kone/KOne-1.0.6.msi"] = msi.readBytes() + ByteArray(10)
        assertFailsWith<UpdateException> { updater(site).download(release) }
        assertTrue(File(folder, "updates").listFiles().orEmpty().none { it.name.startsWith("KOne-1.0.6.msi") }, "nothing unverified is left to install")
    }

    @Test
    fun keysTravelAsBase64() {
        val public = java.util.Base64.getEncoder().encodeToString(owner.public.encoded)
        val private = java.util.Base64.getEncoder().encodeToString(owner.private.encoded)
        val body = "{}".toByteArray()
        val signature = Manifest.sign(body, Manifest.privateKey(private))
        assertTrue(java.security.Signature.getInstance("Ed25519").apply { initVerify(Manifest.publicKey(public)); update(body) }.verify(java.util.Base64.getDecoder().decode(signature)))
    }

    /** What the release step writes is what KOne takes. */
    @Test
    fun theReleaseToolWritesWhatKOneTakes(): Unit = Site().use { site ->
        val out = File(folder, "release")
        cc.txwd.kone.release(msi, "${site.base}/kone/KOne-1.0.9.msi", "1.0.9", out, owner.private)
        site.files["/kone/latest.json"] = File(out, "latest.json").readBytes()
        site.files["/kone/latest.json.sig"] = File(out, "latest.json.sig").readBytes()
        site.files["/kone/KOne-1.0.9.msi"] = msi.readBytes()
        val release = assertNotNull(updater(site).check())
        assertEquals("1.0.9", release.version)
        assertTrue(updater(site).download(release).readBytes().contentEquals(msi.readBytes()))
        assertFailsWith<IllegalArgumentException> { cc.txwd.kone.release(msi, "http://203.0.113.5/KOne.msi", "1.0.9", out, owner.private) }
    }

    /** Start with Windows writes this person's Run entry only when turned on, and takes it away when turned off. */
    @Test
    fun startingWithWindowsIsOffUntilTurnedOn() {
        assumeTrue(Places.windows, "the Run entry is Windows' own")
        val launcher = File(folder, "KOne.exe").apply { writeText("") }
        assertFalse(WindowsShell.startsWithWindows(launcher))
        try {
            WindowsShell.startWithWindows(launcher, true)
            assertTrue(WindowsShell.startsWithWindows(launcher))
        } finally {
            WindowsShell.startWithWindows(launcher, false)
        }
        assertFalse(WindowsShell.startsWithWindows(launcher))
    }
}
