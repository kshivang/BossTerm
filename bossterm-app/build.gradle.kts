import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

// Interface for injecting ExecOperations into tasks
// Replaces deprecated project.exec() calls for Gradle 9.0 compatibility
interface InjectedExecOps {
    @get:Inject val execOps: ExecOperations
}

// Load local.properties for signing configuration (gitignored)
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}
val macosSigningIdentity: String = System.getenv("MACOS_DEVELOPER_ID")
    ?: localProperties.getProperty("macos.signing.identity")
    ?: "-"  // Ad-hoc signing fallback

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "ai.rever.bossterm"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvmToolchain(17)

    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":compose-ui"))
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.ui)

                // SLF4J binding so logs from compose-ui (e.g. BossTermMcpManager,
                // Ktor server) actually reach stderr instead of being dropped by
                // the NOP logger.
                runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "ai.rever.bossterm.app.MainKt"

        // JVM args for platform-specific features (access to internal AWT classes)
        jvmArgs += listOf(
            // macOS blur effect
            "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED",
            // Linux X11 WM_CLASS for proper taskbar icon/name
            "--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)

            packageName = "BossTerm"
            packageVersion = project.version.toString().removeSuffix("-SNAPSHOT")
            description = "Modern terminal emulator built with Kotlin/Compose Desktop"
            vendor = "risalabs.ai"
            copyright = "© 2025 risalabs.ai. All rights reserved."

            // Include CLI script + Python MCP helper + man page in app resources.
            // We point at `build/cli-resources/` rather than the raw source dir
            // so the `processCliResources` task can substitute `@@BOSSTERM_VERSION@@`
            // in the canonical script with the real project version before bundling.
            // The source files live under `cli-resources/` in the repo with symlinks
            // (macos/bossterm → ../bossterm) so editors only have to maintain one
            // canonical file; the task expands those symlinks while substituting.
            appResourcesRootDir.set(layout.buildDirectory.dir("cli-resources").get().asFile)

            macOS {
                iconFile.set(rootProject.file("BossTerm.icns"))
                bundleID = "ai.rever.bossterm"
                dockName = "BossTerm"
                // Allow access to all files for terminal operations
                entitlementsFile.set(project.file("../compose-ui/src/desktopMain/resources/entitlements.plist"))
                // JVM runtime also needs entitlements for notarization
                runtimeEntitlementsFile.set(project.file("../compose-ui/src/desktopMain/resources/runtime-entitlements.plist"))

                // Code signing configuration for distribution
                signing {
                    val skipSigning = System.getenv("DISABLE_MACOS_SIGNING") == "true"
                    sign.set(!skipSigning)
                    identity.set(macosSigningIdentity)

                    println("🔐 macOS Code Signing: ${if (skipSigning) "DISABLED" else macosSigningIdentity}")
                }

                infoPlist {
                    extraKeysRawXml = """
                        <key>NSHighResolutionCapable</key>
                        <true/>
                        <key>LSMinimumSystemVersion</key>
                        <string>11.0</string>
                        <key>NSAppleEventsUsageDescription</key>
                        <string>BossTerm needs permission to send notifications when commands complete.</string>
                        <key>NSUserNotificationAlertStyle</key>
                        <string>alert</string>
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>ai.rever.bossterm</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>bossterm</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }

            linux {
                iconFile.set(rootProject.file("BossTerm.png"))
                debMaintainer = "shivang.risa@gmail.com"
                menuGroup = "System;TerminalEmulator"
                appCategory = "Utility"
                shortcut = true
                // RPM-specific options
                rpmLicenseType = "LGPL-3.0"
                // Set app name for desktop integration
                appRelease = "1"
                debPackageVersion = project.version.toString().removeSuffix("-SNAPSHOT")
            }

            windows {
                iconFile.set(rootProject.file("BossTerm.ico"))
                menuGroup = "BossTerm"
                perUserInstall = true
            }

            // Include required JVM modules
            modules("java.sql", "jdk.unsupported", "jdk.management.agent")

            // JVM args for better performance and desktop integration
            val packageVer = project.version.toString().removeSuffix("-SNAPSHOT")
            jvmArgs += listOf(
                "-Xmx2G",
                "-Dapple.awt.application.appearance=system",
                // Version for runtime detection (especially on Linux where there's no Info.plist)
                "-Dbossterm.version=$packageVer",
                // Linux: Set WM_CLASS for proper desktop integration
                "-Dawt.useSystemAAFontSettings=on",
                "-Dsun.java2d.xrender=true"
            )
        }

        // ProGuard configuration for release builds
        buildTypes.release {
            proguard {
                version.set("7.7.0")
                configurationFiles.from(project.file("../compose-ui/proguard-rules.pro"))
            }
        }
    }
}

// Stage the CLI resources under build/cli-resources with @@BOSSTERM_VERSION@@
// substituted to project.version. The canonical script lives flat at
// `cli-resources/bossterm` (the script detects darwin/linux at runtime, so
// there's no per-platform variant). The Python helper and the troff man
// page ride along untouched. Compose Desktop's `appResourcesRootDir` (set
// above) bundles the staged dir into the .app / .deb / .rpm.
val cliVersion = project.version.toString().removeSuffix("-SNAPSHOT")

// cloudflared release pin — the SAME single source of truth the runtime reads
// (compose-ui/.../resources/cloudflared-pin.properties). Used to fetch + bundle the binary into
// the app so session sharing works offline; the runtime auto-downloads it elsewhere as a fallback.
val cloudflaredPin = Properties().apply {
    rootProject.file("compose-ui/src/desktopMain/resources/cloudflared-pin.properties")
        .inputStream().use { load(it) }
}
val cloudflaredVersion: String = cloudflaredPin.getProperty("version")
    ?: error("cloudflared-pin.properties is missing 'version'")
// fetchCloudflaredBinary stages the verified binary here; processCliResources copies it into the
// `common/` bucket alongside the CLI script. A SEPARATE dir (not common/) so the Sync stays the
// single owner of common/ and can't clobber the binary.
val cloudflaredStaging = layout.buildDirectory.dir("cloudflared-staging")

tasks.register<Sync>("processCliResources") {
    description = "Stage cli-resources/ with @@BOSSTERM_VERSION@@ substituted to project.version"
    group = "build"

    // Fetch cloudflared first, then fold its staging dir into common/ as an extra source — so the
    // bundled binary survives this Sync's prune instead of being deleted by it.
    dependsOn("fetchCloudflaredBinary")
    inputs.property("cliVersion", cliVersion)
    from(rootProject.file("cli-resources"))
    from(cloudflaredStaging) // adds `cloudflared` (when bundled for this target) under common/
    // Stage under `common/`: Compose Desktop's appResourcesRootDir only bundles files inside
    // an OS bucket (`common` / `macos` / `linux` / `<os>-<arch>`) — files placed flat at the
    // root are silently dropped, so a packaged .app/.deb/.rpm never got the `bossterm` script
    // (the in-app CLI installer then errored). `common` ships to every OS; at runtime Compose
    // flattens it into `compose.application.resources.dir`, so the script lands at
    // `<resources.dir>/bossterm` where CLIInstaller.findCliResource looks.
    into(layout.buildDirectory.dir("cli-resources/common"))
    // Substitute the version placeholder in the canonical script only.
    // bossterm-mcp.py and bossterm.1 don't carry the token; filtering them
    // would be wasted work (and a line-based filter on the troff source
    // happens to be safe but isn't free).
    filesMatching("bossterm") {
        filter { line: String ->
            line.replace("@@BOSSTERM_VERSION@@", cliVersion)
        }
    }
}

// `appResourcesRootDir` points at the staged dir, so prepareAppResources (the
// gradle.plugin.compose Desktop task that copies into the bundle) depends on
// processCliResources (which itself dependsOn fetchCloudflaredBinary).
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn("processCliResources")
}

// Download + SHA-256-verify the cloudflared binary for THIS build's target and stage it for
// bundling, so a packaged BossTerm can open a Cloudflare quick tunnel offline with zero download.
// Each CI runner builds natively (host arch == target arch). Unsupported OS/arch → bundle nothing
// (the runtime auto-downloads instead); a SHA mismatch is always fatal. Hashes/version come from
// cloudflared-pin.properties (shared with the runtime). Optionally hard-fail on RELEASE_BUILD so a
// release never silently ships without the binary.
tasks.register("fetchCloudflaredBinary") {
    description = "Download + SHA-256-verify cloudflared for the host target and stage it for bundling"
    group = "build"

    val osNameProp = System.getProperty("os.name").lowercase()
    val osArchProp = System.getProperty("os.arch").lowercase()
    inputs.property("cloudflaredVersion", cloudflaredVersion)
    inputs.property("osName", osNameProp)
    inputs.property("osArch", osArchProp)
    val outFile = cloudflaredStaging.map { it.file("cloudflared") }
    outputs.file(outFile)

    val pin = cloudflaredPin
    val version = cloudflaredVersion
    val cacheDirProvider = layout.buildDirectory.dir("cloudflared-cache")
    val releaseBuild = System.getenv("RELEASE_BUILD") != null
    val injected = project.objects.newInstance<InjectedExecOps>()

    doLast {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
        fun download(url: String, dest: File): Boolean {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL) // github.com 302 → CDN
                .connectTimeout(Duration.ofSeconds(20)).build()
            repeat(3) { i ->
                dest.delete()
                try {
                    val req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5)).header("User-Agent", "BossTerm-build").GET().build()
                    val resp = client.send(req, HttpResponse.BodyHandlers.ofFile(dest.toPath()))
                    val size = if (dest.exists()) dest.length() else 0L
                    if (resp.statusCode() == 200 && size > 1_000_000L) return true
                    logger.warn("cloudflared download attempt ${i + 1} failed (HTTP ${resp.statusCode()}, $size bytes)")
                } catch (e: Exception) {
                    logger.warn("cloudflared download attempt ${i + 1} error: ${e.message}")
                }
                if (i < 2) Thread.sleep(2_000)
            }
            return false
        }

        val stagingDir = outFile.get().asFile.parentFile.also { it.mkdirs() }
        // Always "cloudflared" (no extension) — only mac/linux are bundled today. NOTE: if Windows
        // bundling is ever added, stage it as "cloudflared.exe" so it matches CloudflaredExposer's
        // binName; otherwise the runtime's bundledBin() looks for the wrong name and silently misses it.
        val staged = File(stagingDir, "cloudflared")
        staged.delete() // a stale copy from a prior build target must not be bundled

        val isMac = osNameProp.contains("mac")
        val isLinux = osNameProp.contains("linux")
        val arch = when (osArchProp) {            // mirror CloudflaredExposer.linuxArch/macArch
            "amd64", "x86_64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            "arm", "armv7l", "armv7", "armhf" -> "arm"
            "i386", "i486", "i586", "i686", "x86" -> "386"
            else -> null
        }
        if (!isMac && !isLinux) {
            logger.lifecycle("cloudflared: no bundle for OS '$osNameProp' (runtime auto-downloads); skipping"); return@doLast
        }
        if (arch == null) {
            logger.lifecycle("cloudflared: unsupported arch '$osArchProp'; skipping (runtime auto-downloads)"); return@doLast
        }
        val asset = if (isMac) "cloudflared-darwin-$arch.tgz" else "cloudflared-linux-$arch"
        // Surface the resolved target on every run (cache hit or miss) so a mis-arched build — e.g.
        // an x86/Rosetta JVM on Apple Silicon — is obvious in release logs. The app's bundled JRE
        // follows this same build-JVM arch, so the binary always matches the app it ships in.
        logger.lifecycle("cloudflared: bundling for os='$osNameProp' arch='$osArchProp' → $asset (v$version)")
        val expectedSha = pin.getProperty("sha256.$asset")
        if (expectedSha.isNullOrBlank()) {
            if (releaseBuild) error("cloudflared: no pinned SHA for $asset (RELEASE_BUILD)")
            logger.warn("cloudflared: no pinned SHA for $asset; skipping"); return@doLast
        }
        val url = "https://github.com/cloudflare/cloudflared/releases/download/$version/$asset"

        val cacheDir = cacheDirProvider.get().asFile.also { it.mkdirs() }
        val cached = File(cacheDir, "$version-$asset")
        if (!(cached.isFile && sha256(cached).equals(expectedSha, ignoreCase = true))) {
            logger.lifecycle("cloudflared: downloading $asset (v$version)…")
            if (!download(url, cached)) {
                cached.delete()
                if (releaseBuild) error("cloudflared: download failed for $url (RELEASE_BUILD)")
                logger.warn("cloudflared: download failed; skipping bundle (runtime auto-downloads)"); return@doLast
            }
            val actual = sha256(cached)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                cached.delete(); error("cloudflared: SHA-256 mismatch for $asset (expected $expectedSha, got $actual)")
            }
        }

        if (isMac) {
            // The .tgz holds a single `cloudflared` binary at its root → extracts to staging/cloudflared.
            injected.execOps.exec { commandLine("tar", "-xzf", cached.absolutePath, "-C", stagingDir.absolutePath) }
            require(staged.isFile) { "cloudflared: missing after extracting $asset" }
            // The archive is SHA-pinned (binary only), but prune any unexpected sibling entries so
            // the Sync that folds this dir into common/ can only ever bundle the cloudflared binary.
            stagingDir.listFiles()?.forEach { if (it != staged) it.deleteRecursively() }
        } else {
            cached.copyTo(staged, overwrite = true)
        }
        staged.setExecutable(true, false)
        logger.lifecycle("cloudflared: staged ${staged.absolutePath} (v$version)")
    }
}

// Sign PTY4J native binaries with hardened runtime for macOS notarization
tasks.register("signPty4jBinaries") {
    description = "Signs PTY4J native binaries with hardened runtime for Apple notarization"
    group = "build"

    // Only run on macOS and when signing is enabled
    onlyIf {
        val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
        val signingDisabled = System.getenv("DISABLE_MACOS_SIGNING") == "true"
        isMacOS && !signingDisabled
    }

    // Inject ExecOperations for exec calls (replaces deprecated project.exec)
    val injected = project.objects.newInstance<InjectedExecOps>()

    doLast {
        println("🔧 Signing PTY4J native binaries with hardened runtime for notarization...")

        // Get developer identity from environment or use default
        val developerId = System.getenv("MACOS_DEVELOPER_ID")
            ?: localProperties.getProperty("macos.signing.identity")
            ?: "-"

        if (developerId == "-") {
            println("⚠️ No signing identity found, skipping PTY4J signing")
            return@doLast
        }

        // Find the built app in the standard Compose Desktop location
        val appDir = project.layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        val appFile = appDir.listFiles()?.find { it.name.endsWith(".app") }

        if (appFile?.exists() == true) {
            println("Found app: ${appFile.name}")

            // Find PTY4J jar inside the app
            val appContents = File(appFile, "Contents/app")
            val pty4jJar = appContents.listFiles()?.find {
                it.name.startsWith("pty4j-") && it.name.endsWith(".jar")
            }

            if (pty4jJar?.exists() == true) {
                println("Processing PTY4J jar: ${pty4jJar.name}")

                // Create temporary directory for jar manipulation
                val tempDir = File(System.getProperty("java.io.tmpdir"), "pty4j-sign-${System.currentTimeMillis()}")
                tempDir.mkdirs()

                try {
                    // Extract the entire jar
                    injected.execOps.exec {
                        workingDir = tempDir
                        commandLine("jar", "xf", pty4jJar.absolutePath)
                    }

                    // Sign PTY4J native libraries with hardened runtime
                    val nativeFiles = tempDir.walkTopDown().filter {
                        it.isFile && (it.name.endsWith(".dylib") || it.name.contains("spawn-helper"))
                    }.toList()

                    if (nativeFiles.isNotEmpty()) {
                        println("Found ${nativeFiles.size} PTY4J native binary(ies) to sign:")

                        for (nativeFile in nativeFiles) {
                            println("  Signing: ${nativeFile.relativeTo(tempDir)}")

                            // Make executable
                            nativeFile.setExecutable(true)

                            // Sign with hardened runtime
                            try {
                                injected.execOps.exec {
                                    commandLine(
                                        "codesign",
                                        "--force",
                                        "--options", "runtime",
                                        "--sign", developerId,
                                        "--timestamp",
                                        nativeFile.absolutePath
                                    )
                                }

                                // Verify signature
                                injected.execOps.exec {
                                    commandLine("codesign", "-vv", nativeFile.absolutePath)
                                }

                                println("    ✅ Successfully signed ${nativeFile.name}")
                            } catch (e: Exception) {
                                println("    ⚠️ Warning: Failed to sign ${nativeFile.name}: ${e.message}")
                            }
                        }

                        // Recreate the jar with signed native libraries
                        val signedJar = File(pty4jJar.parentFile, "${pty4jJar.nameWithoutExtension}-signed.jar")
                        injected.execOps.exec {
                            workingDir = tempDir
                            commandLine("jar", "cf", signedJar.absolutePath, ".")
                        }

                        // Replace original jar with signed version
                        pty4jJar.delete()
                        signedJar.renameTo(pty4jJar)

                        println("✅ PTY4J jar updated with signed native libraries")

                    } else {
                        println("⚠️ Warning: No PTY4J native binaries found in jar")
                    }

                } finally {
                    // Clean up temp directory
                    tempDir.deleteRecursively()
                }

                // Sign the bundled cloudflared binary (if this target bundled one). It's a Mach-O
                // executable inside a hardened-runtime, notarized bundle, so it MUST be signed with
                // --options runtime or notarization rejects the app. Search the bundle (rather than
                // hardcode a path) so we sign it wherever Compose placed the app resources. The
                // --deep re-sign below then seals it. (pty4j is a hard dependency, so this branch
                // always runs in practice.)
                appFile.walkTopDown().filter { it.isFile && it.name == "cloudflared" }.forEach { cf ->
                    println("  Signing bundled cloudflared: ${cf.relativeTo(appFile)}")
                    cf.setExecutable(true)
                    try {
                        injected.execOps.exec {
                            commandLine("codesign", "--force", "--options", "runtime",
                                "--sign", developerId, "--timestamp", cf.absolutePath)
                        }
                        injected.execOps.exec { commandLine("codesign", "-vv", cf.absolutePath) }
                        println("    ✅ Successfully signed cloudflared")
                    } catch (e: Exception) {
                        println("    ⚠️ Warning: Failed to sign cloudflared: ${e.message}")
                    }
                }

                // CRITICAL: Re-sign the entire app bundle after modifying the JAR
                println("🔒 Re-signing app bundle after PTY4J modifications...")
                try {
                    injected.execOps.exec {
                        commandLine(
                            "codesign",
                            "--force",
                            "--deep",
                            "--options", "runtime",
                            "--sign", developerId,
                            "--timestamp",
                            "--entitlements", project.file("../compose-ui/src/desktopMain/resources/entitlements.plist").absolutePath,
                            appFile.absolutePath
                        )
                    }

                    // Verify the re-signed app
                    injected.execOps.exec {
                        commandLine("codesign", "-vvv", "--deep", "--strict", appFile.absolutePath)
                    }

                    println("✅ App bundle re-signed successfully")
                } catch (e: Exception) {
                    println("❌ Failed to re-sign app bundle: ${e.message}")
                    throw e
                }

            } else {
                println("⚠️ Warning: PTY4J jar not found in app bundle")
            }
        } else {
            println("⚠️ Warning: Built app not found at expected location")
        }
    }
}

// Configure task dependencies for PTY4J signing
afterEvaluate {
    val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
    val isLinux = System.getProperty("os.name").lowercase().contains("linux")
    val signingDisabled = System.getenv("DISABLE_MACOS_SIGNING") == "true"

    // Make signPty4jBinaries run AFTER createDistributable
    tasks.findByName("signPty4jBinaries")?.apply {
        mustRunAfter("createDistributable")
    }

    // CRITICAL: Make createDistributable finalize with signPty4jBinaries
    // This ensures PTY4J natives are signed before Compose Desktop signs the whole app
    tasks.findByName("createDistributable")?.apply {
        if (isMacOS && !signingDisabled) {
            finalizedBy("signPty4jBinaries")
            println("📝 createDistributable will be finalized by signPty4jBinaries")
        }
    }

    // Make sure signing happens before packaging
    tasks.findByName("packageDmg")?.apply {
        if (isMacOS && !signingDisabled) {
            mustRunAfter("signPty4jBinaries")
            println("📝 packageDmg will run after PTY4J signing")
        }
    }

    // Linux: Fix .desktop file after packaging
    if (isLinux) {
        tasks.findByName("fixLinuxDesktopFile")?.apply {
            mustRunAfter("packageDeb", "packageRpm")
        }
        tasks.findByName("packageDeb")?.apply {
            finalizedBy("fixLinuxDesktopFile")
            println("📝 packageDeb will be finalized by fixLinuxDesktopFile")
        }
        tasks.findByName("packageRpm")?.apply {
            finalizedBy("fixLinuxDesktopFile")
            println("📝 packageRpm will be finalized by fixLinuxDesktopFile")
        }
    }
}

// Fix Linux .desktop file to add StartupWMClass for proper desktop integration
// This task post-processes the .deb file after jpackage creates it
tasks.register("fixLinuxDesktopFile") {
    description = "Adds StartupWMClass to Linux .desktop file for proper taskbar integration"
    group = "build"

    val isLinux = System.getProperty("os.name").lowercase().contains("linux")
    onlyIf { isLinux }

    val injected = project.objects.newInstance<InjectedExecOps>()

    doLast {
        // Fix .deb package
        val debDir = project.layout.buildDirectory.dir("compose/binaries/main/deb").get().asFile
        if (debDir.exists()) {
            fixDesktopFileInDebPackage(debDir, injected)
        }

        // Note: RPM repacking requires rpmbuild which is complex; rely on update script for RPM
    }
}

fun fixDesktopFileInDebPackage(packageDir: File, injected: InjectedExecOps) {
    val debFile = packageDir.listFiles()?.find { it.name.endsWith(".deb") }
    if (debFile == null) {
        println("No .deb file found in $packageDir")
        return
    }

    println("Fixing .desktop file in ${debFile.name}...")
    val workDir = File(packageDir, "fix-temp-${System.currentTimeMillis()}")
    workDir.mkdirs()

    try {
        // Extract deb contents
        injected.execOps.exec {
            commandLine("dpkg-deb", "-R", debFile.absolutePath, workDir.absolutePath)
        }

        // Find and modify .desktop file. jpackage's generated entry has neither
        // StartupWMClass (taskbar grouping) nor the bossterm:// scheme handler, so we patch
        // both: add StartupWMClass, register MimeType=x-scheme-handler/bossterm, and ensure the
        // Exec line passes the URL through with %U — without all three, the sign-in magic link
        // (bossterm://auth/verify) has no handler on .deb installs. (RPM is not repacked below —
        // see note; .rpm scheme registration remains unhandled.)
        var modified = false
        workDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".desktop") }
            .forEach { desktopFile ->
                var content = desktopFile.readText()
                var changed = false
                if (!content.contains("StartupWMClass")) {
                    content = content.trimEnd() + "\nStartupWMClass=bossterm\n"
                    changed = true
                }
                if (!content.contains("x-scheme-handler/bossterm")) {
                    content = if (Regex("(?m)^MimeType=").containsMatchIn(content)) {
                        content.replace(Regex("(?m)^MimeType=(.*)$")) { m ->
                            val v = m.groupValues[1].trimEnd(';')
                            "MimeType=$v;x-scheme-handler/bossterm;"
                        }
                    } else {
                        content.trimEnd() + "\nMimeType=x-scheme-handler/bossterm;\n"
                    }
                    changed = true
                }
                // The launcher must receive the URL: ensure the Exec line ends with %U.
                content = content.replace(Regex("(?m)^(Exec=.*?)( %[UufF])?\\s*$")) { m ->
                    if (m.groupValues[2].isBlank()) { changed = true; "${m.groupValues[1]} %U" } else m.value
                }
                if (changed) {
                    desktopFile.writeText(content)
                    println("Patched ${desktopFile.name} (StartupWMClass + bossterm:// scheme handler)")
                    modified = true
                }
            }

        if (modified) {
            // Repack deb using dpkg-deb --build
            injected.execOps.exec {
                commandLine("dpkg-deb", "--build", "--root-owner-group", workDir.absolutePath, debFile.absolutePath)
            }
            println("Repacked ${debFile.name}")
        }
    } finally {
        workDir.deleteRecursively()
    }
}
