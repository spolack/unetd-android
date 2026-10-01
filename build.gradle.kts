// AGP 9 compiles Kotlin itself and depends on a minimum Kotlin Gradle plugin. Putting
// the plugin on the classpath at our version upgrades that, keeping the Kotlin
// compiler in step with the Compose compiler plugin, which must match it exactly.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
