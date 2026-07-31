plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

neoForge {
    // Vanilla-only mode: Mojang-mapped Minecraft on the compile classpath, no loader.
    neoFormVersion = libs.versions.neoform.get()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Provided at runtime by KFF on NeoForge (and fabric-language-kotlin on Fabric) — never shaded.
    compileOnly(libs.kotlinx.coroutines)
    compileOnly(libs.kotlinx.serialization.json)

    compileOnly(libs.mixin)
    compileOnly(libs.mixinextras.common)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
