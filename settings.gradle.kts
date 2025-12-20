pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "bossterm"
include(
    ":bossterm-core-mpp",
    ":compose-ui",
    ":bossterm-app",
    ":embedded-example",
    ":tabbed-example"
)

