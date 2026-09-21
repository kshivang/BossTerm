plugins {
    // Keep Kotlin and its compiler plugins on the same release.
    id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("com.android.kotlin.multiplatform.library") version "9.4.1" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

// Version format: MAJOR.MINOR.PATCH
// - If APP_VERSION env var is set (from CI), use it directly
// - Otherwise: MAJOR.MINOR from VERSION file + PATCH from BUILD_NUMBER
// - Local builds get "-SNAPSHOT" suffix
val isReleaseBuild = System.getenv("RELEASE_BUILD") != null || System.getenv("INTELLIJ_DEPENDENCIES_BOT") != null
val projectVersion = System.getenv("APP_VERSION")?.let { appVersion ->
    // Use pre-computed version from CI (ensures consistency with artifact naming)
    appVersion + if (!isReleaseBuild) "-SNAPSHOT" else ""
} ?: run {
    // Fallback: compute from VERSION file + BUILD_NUMBER (local builds)
    val baseVersion = rootProject.projectDir.resolve("VERSION").readText().trim()
    val buildNumber = System.getenv("BUILD_NUMBER") ?: "0"
    "$baseVersion.$buildNumber" + if (!isReleaseBuild) "-SNAPSHOT" else ""
}

allprojects {
  version = projectVersion
  group = "ai.rever.bossterm"
  layout.buildDirectory = rootProject.projectDir.resolve(".gradleBuild/" + project.name)
}

subprojects {
  repositories {
    mavenCentral()
    google()
  }
}
