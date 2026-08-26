// Top-level build file. Plugin versions live in gradle/libs.versions.toml;
// `apply false` declares them here so every module resolves the same versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.paparazzi) apply false
}
// Paparazzi 2.0.0-alpha05 transitively pulls com.android.tools:sdk-common 31.13.2,
// which REMOVED GradleVersion.parse(String) — that breaks AGP 8.9.1's
// VersionCheckPlugin on the shared buildscript classpath. Pin sdk-common to the
// version AGP 8.9.1 itself uses (31.9.1); the paparazzi plugin does not
// reference the removed API (verified: no GradleVersion refs in its classes).
buildscript {
    configurations.classpath {
        resolutionStrategy.force("com.android.tools:sdk-common:31.9.1")
    }
}
