plugins {
    alias(libs.plugins.loom)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
}

val commonProject = project(":common")

dependencies {
    minecraft("com.mojang:minecraft:${libs.versions.minecraft.get()}")
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)
    modImplementation(libs.flk)

    // Dev-only: Dynamic Surroundings (+ its Architectury/cloth-config dependencies) in
    // runClient to exercise the reverb bridge.
    modLocalRuntime("maven.modrinth:dynamicsurroundingsfabric:0.4.2")
    modLocalRuntime("maven.modrinth:architectury-api:13.0.11+fabric")
    modLocalRuntime("maven.modrinth:cloth-config:15.0.140+fabric")
}

loom {
    mixin {
        // Refmap-free mixin remapping: tiny-remapper rewrites targets at remapJar time.
        useLegacyMixinAp = false
    }
}

kotlin {
    jvmToolchain(21)
}

// MultiLoader pattern: common's sources compile directly into this module's jar.
sourceSets.main {
    kotlin.srcDir(commonProject.file("src/main/kotlin"))
    java.srcDir(commonProject.file("src/main/java"))
    resources.srcDir(commonProject.file("src/main/resources"))
}
