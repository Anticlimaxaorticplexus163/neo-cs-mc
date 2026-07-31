plugins {
    alias(libs.plugins.moddev)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        name = "KotlinForForge"
        content { includeGroup("thedarkcolour") }
    }
}

val commonProject = project(":common")

neoForge {
    version = libs.versions.neoforge.get()

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }

    mods {
        create("chatsounds") {
            sourceSet(sourceSets.main.get())
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// MultiLoader pattern: common's sources compile directly into this module's jar,
// so there is no separate common artifact to bundle or relocate.
sourceSets.main {
    kotlin.srcDir(commonProject.file("src/main/kotlin"))
    resources.srcDir(commonProject.file("src/main/resources"))
}

dependencies {
    implementation(libs.kff.neoforge)
}
