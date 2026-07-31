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

    // Dynamic Surroundings has no 1.21.11 build yet; re-add the dev-runtime deps here
    // (dynamicsurroundingsfabric + architectury-api + cloth-config + nashorn-core) to
    // exercise the reverb bridge once it ships.
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

base {
    archivesName = "chatsounds-fabric"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

// MultiLoader pattern: common's sources compile directly into this module's jar.
sourceSets.main {
    kotlin.srcDir(commonProject.file("src/main/kotlin"))
    java.srcDir(commonProject.file("src/main/java"))
    resources.srcDir(commonProject.file("src/main/resources"))
}
