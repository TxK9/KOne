import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.1"
}

group = "cc.txwd.kone"
// 1.0.<build>: the workflow gives its run number, so every MSI is newer than the last and
// Windows replaces the old one when it is installed.
version = "1.0." + providers.gradleProperty("kone.build").getOrElse("0")

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// What a build knows about itself: its version; the console a bare key goes to when it was made
// for one (-Pkone.console=https://…; without it only kone://claim links, which carry their
// console, are accepted); and where it looks for updates with the public key they must be signed
// with (-Pkone.updates=https://…/latest.json -Pkone.updateKey=…; without both it never looks).
val buildInfo = tasks.register("buildInfo") {
    val release = version.toString()
    val fields = mapOf(
        "version" to providers.provider { release },
        "console" to providers.gradleProperty("kone.console").orElse(""),
        "updates" to providers.gradleProperty("kone.updates").orElse(""),
        "updateKey" to providers.gradleProperty("kone.updateKey").orElse(""),
    )
    val out = layout.buildDirectory.dir("generated/kone")
    fields.forEach { (name, value) -> inputs.property(name, value) }
    outputs.dir(out)
    doLast {
        out.get().file("kone-build.properties").asFile.apply { parentFile.mkdirs() }
            .writeText(fields.entries.joinToString("") { (name, value) -> "$name=${value.get()}\n" })
    }
}
sourceSets.main {
    resources.srcDir(buildInfo)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // DPAPI for the device credential, the registry for dark mode, the pipe to the KOne service.
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    // Share links read as the phone reads them (node/ShareLinks.kt, ported as it is).
    implementation("org.json:json:20250517")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Each test by name in the log, so a run shows which ran on the real core and which were skipped.
    testLogging {
        events("passed", "skipped", "failed")
        // The whole message of a failure, so a Windows run's log says what went wrong.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The screen tests leave their PNGs here, for comparing with docs/windows/preview.html.
    systemProperty("kone.screens", layout.buildDirectory.dir("screens").get().asFile.path)
    // The core and the service, where the workflow puts them (build/core), or -Pkone.singbox=…
    // and -Pkone.service=…: the tests then have sing-box check what the App builds and the
    // service build the same configuration.
    for ((property, file) in listOf("kone.singbox" to "sing-box.exe", "kone.service" to "kone-service.exe")) {
        val path = providers.gradleProperty(property).orNull
            ?: layout.buildDirectory.file("core/$file").get().asFile.takeIf { it.isFile }?.path
        if (path != null) systemProperty(property, path)
    }
    // The end-to-end tests drive the installed service; only the workflow's Windows runner sets this.
    providers.gradleProperty("kone.e2e").orNull?.let { systemProperty("kone.e2e", it) }
    jvmArgs("-Dfile.encoding=UTF-8")
}

// The rule sets beside the core in the installer (build/core/rules), for the service.
tasks.register<Copy>("coreRules") {
    from("src/main/resources/rules")
    into(layout.buildDirectory.dir("core/rules"))
}

// The installer's pictures, painted by the App's own ink painter (InstallerArt.kt).
tasks.register<JavaExec>("installerArt") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("cc.txwd.kone.InstallerArtKt")
    args(layout.buildDirectory.dir("wix").get().asFile.path)
}

// The download page's paintings (SiteArt.kt), for desktop/packaging/site.
tasks.register<JavaExec>("siteArt") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("cc.txwd.kone.SiteArtKt")
    args(layout.buildDirectory.dir("site/art").get().asFile.path)
}

// latest.json and its signature for the MSI a release puts on the website (ReleaseTool.kt), with
// the private update key in KONE_UPDATE_SIGNING_KEY: -Pkone.msi=… -Pkone.msiUrl=https://…
tasks.register<JavaExec>("updateManifest") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("cc.txwd.kone.ReleaseToolKt")
    val msi = providers.gradleProperty("kone.msi")
    val url = providers.gradleProperty("kone.msiUrl")
    val release = version.toString()
    val out = layout.buildDirectory.dir("update").get().asFile.path
    argumentProviders.add(CommandLineArgumentProvider { listOf("manifest", msi.get(), url.get(), release, out) })
}

// A new update key, created once by an authorized release operator, into build/update-key (ReleaseTool.kt).
tasks.register<JavaExec>("updateKeys") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("cc.txwd.kone.ReleaseToolKt")
    args("keys", layout.buildDirectory.dir("update-key").get().asFile.path)
}

compose.desktop {
    application {
        mainClass = "cc.txwd.kone.MainKt"
        // KOne waits by the clock most of the day: a small heap that gives memory back after use.
        // The paintings live outside the heap, in Skia.
        jvmArgs += listOf("-XX:+UseSerialGC", "-Xms16m", "-Xmx256m", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25", "-XX:ReservedCodeCacheSize=96m")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "KOne"
            packageVersion = version.toString()
            vendor = "KOne"
            description = "KOne"
            // suggestRuntimeModules, plus EC for the TLS the console speaks.
            modules("java.instrument", "java.net.http", "jdk.unsupported", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("packaging/kone.ico"))
                // Fixed for good: Windows recognises a newer MSI of the same app by this.
                upgradeUuid = "38e977e1-5745-41c2-97b8-6aeef34d6e22"
                menu = true
                menuGroup = "KOne"
                shortcut = true
                dirChooser = false
                perUserInstall = false
            }
        }
    }
}
