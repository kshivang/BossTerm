import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

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

            // Include CLI script in app resources
            appResourcesRootDir.set(rootProject.file("cli-resources"))

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
