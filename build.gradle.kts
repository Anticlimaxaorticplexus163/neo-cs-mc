plugins {
    // Declare plugin versions once; subprojects apply them.
    alias(libs.plugins.moddev) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    group = property("mod_group") as String
    version = property("mod_version") as String
}
