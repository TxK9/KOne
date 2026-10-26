package cc.txwd.kone.app

import cc.txwd.kone.console.ConsoleEndpoint
import java.io.File

/** Where things are: the App's own folder, and the core and its service inside the installed App. */
object Places {
    val windows: Boolean = System.getProperty("os.name").startsWith("Windows")

    /**
     * %LOCALAPPDATA%\KOne on Windows: the binding, the rule sets. Not roaming: DPAPI seals it to this
     * computer. The tests set kone.data to a folder of their own, away from any real KOne's.
     */
    val data: File = (System.getProperty("kone.data")?.let(::File) ?: System.getenv("LOCALAPPDATA")?.takeIf { windows }?.let { File(it, "KOne") } ?: File(System.getProperty("user.home"), ".kone"))
        .apply { mkdirs() }

    /** The launcher, KOne.exe, as jpackage starts it; its folder is where the installer put KOne. */
    val launcher: File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    /** The installer's core folder beside KOne.exe: sing-box.exe, kone-service.exe and the rule sets. */
    private val coreFolder: File? = launcher?.parentFile?.let { File(it, "core") }?.takeIf { it.isDirectory }

    /** sing-box.exe, run as the person themselves only for the delay probe. */
    val core: File? = coreFolder?.let { File(it, "sing-box.exe") }?.takeIf { it.isFile }

    /** The service the App connects through; the pipe's server must be this very file. */
    val service: File? = coreFolder?.let { File(it, "kone-service.exe") }?.takeIf { it.isFile }

    /** The rule sets the installer put beside the core, when installed. */
    val rules: File? = coreFolder?.let { File(it, "rules") }?.takeIf { File(it, "geosite-cn.srs").isFile }

    /** What the build wrote about itself (desktop/build.gradle.kts, buildInfo). */
    private val build: java.util.Properties = java.util.Properties().apply {
        runCatching { Places::class.java.getResourceAsStream("/kone-build.properties")?.use { load(it) } }
    }

    /** 1.0.<build>, as the installer and Apps & features show it. */
    val version: String = build.getProperty("version")?.takeIf { it.isNotBlank() } ?: "1.0.0"
    val defaultLanguage: String = build.getProperty("language") ?: "system"

    /**
     * The console a bare key goes to, when this build was made for one (-Pkone.console=https://…);
     * without it only kone://claim links carry their console.
     */
    val console: ConsoleEndpoint? = build.getProperty("console")?.let(ConsoleEndpoint::parse)

    /** Where the release website says which KOne is newest (-Pkone.updates=https://…/latest.json), if anywhere. */
    val updates: String? = build.getProperty("updates")?.takeIf { it.isNotBlank() }

    /** The public half of the release-signing key (-Pkone.updateKey=…), which latest.json must be signed with. */
    val updateKey: String? = build.getProperty("updateKey")?.takeIf { it.isNotBlank() }

    /** "KOne · " and this computer's name, as the console lists the device (60 characters at most). */
    val deviceName: String = ("KOne · " + (System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "Windows")).take(60)
}
