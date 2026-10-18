package cc.txwd.kone

import cc.txwd.kone.app.Manifest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.util.Base64
import kotlin.system.exitProcess

/**
 * What a release needs beside the MSI, run by the workflow (./gradlew updateManifest) or, once,
 * by an authorized release operator (./gradlew updateKeys):
 *
 *   manifest <msi> <url> <version> <folder>  latest.json and latest.json.sig for the MSI that will
 *                                            be served at <url>, signed with the private key in
 *                                            the KONE_UPDATE_SIGNING_KEY environment variable
 *   keys <folder>                            a new update key: update-key.private (a secret, for
 *                                            GitHub's secrets) and update-key.public (for the build)
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "manifest" -> {
            val (msi, url, version, folder) = args.drop(1).takeIf { it.size == 4 } ?: usage()
            val key = System.getenv("KONE_UPDATE_SIGNING_KEY")?.takeIf { it.isNotBlank() } ?: usage()
            release(File(msi), url, version, File(folder), Manifest.privateKey(key))
        }
        "keys" -> keys(File(args.getOrNull(1) ?: usage()))
        else -> usage()
    }
}

/** Writes latest.json and its signature into [folder]; the MSI must already be the one that will be served. */
fun release(msi: File, url: String, version: String, folder: File, key: PrivateKey) {
    require(url.startsWith("https://")) { "the MSI must be served over HTTPS" }
    folder.mkdirs()
    val body = Manifest.write(version, url, msi)
    File(folder, "latest.json").writeBytes(body)
    File(folder, "latest.json.sig").writeText(Manifest.sign(body, key))
}

private fun keys(folder: File) {
    folder.mkdirs()
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val private = File(folder, "update-key.private")
    check(!private.exists()) { "$private is already there; an update key is made once" }
    private.writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
    runCatching { Files.setPosixFilePermissions(private.toPath(), PosixFilePermissions.fromString("rw-------")) }
    File(folder, "update-key.public").writeText(Base64.getEncoder().encodeToString(pair.public.encoded))
    println("Wrote ${private.path} (secret) and update-key.public")
}

private fun usage(): Nothing {
    System.err.println("usage: manifest <msi> <url> <version> <folder> | keys <folder>")
    exitProcess(2)
}
