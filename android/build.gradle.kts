// Top-level build file. Plugin versions live in gradle/libs.versions.toml;
// `apply false` declares them here so every module resolves the same versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}