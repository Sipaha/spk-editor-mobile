// Top-level build file. Plugins are configured per-module; declared here with
// `apply false` so versions stay aligned across `:core` and `:app`.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    // Note: `kotlin("android")` is intentionally NOT declared. As of AGP 9.0+
    // Kotlin support is built into the Android Gradle Plugin and applying the
    // legacy `org.jetbrains.kotlin.android` plugin causes configuration to fail.
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
}
