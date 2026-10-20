// Top-level build file. Plugin versions live in gradle/libs.versions.toml (version catalog).
//
// AGP 9 provides built-in Kotlin: the `org.jetbrains.kotlin.android` plugin MUST NOT be
// applied (see https://developer.android.com/build/migrate-to-built-in-kotlin). The KGP
// version AGP runs on is raised via the buildscript classpath below.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}
