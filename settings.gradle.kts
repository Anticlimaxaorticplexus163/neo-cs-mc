pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.neoforged.net/releases")
    }
}

rootProject.name = "neo-cs-mc"

include("common", "neoforge")
