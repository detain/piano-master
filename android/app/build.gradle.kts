plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.keyquest.app"
    compileSdk = 36

    // NDK toolchain pin (matches toolchain.md). Required once Oboe's prefab
    // CMake packages are resolved against a concrete NDK.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.keyquest.app"
        minSdk = 26 // Android 8.0 -- AAudio baseline (plan §3.2)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native audio engine (android/app/src/main/cpp). -Werror is
        // mandatory: warnings in the engine land are release blockers.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20 -Wall -Wextra -Wpedantic -Werror"
                // Oboe's prefab binaries link the shared libc++; matching the
                // STL avoids symbol duplication when both sides use it.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            // No minification yet: v1 ships debug-grade APKs only. ProGuard rules
            // arrive with the first release pass (P3 monetization/hardening).
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        prefab = true // Oboe ships prefab CMake packages (find_package(oboe))
    }
}

dependencies {
    // Compose (versions resolved by the BOM -- never pin individual compose libs).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Oboe input stream for the native audio engine (prefab CMake package).
    implementation(libs.oboe)

    // JankStats (plan §20 P0.5.3): frame-over-24ms + dropped-frame instrumentation
    // for the scrolling-notation prototype.
    implementation(libs.androidx.metrics.performance)

    // Unit tests (JVM).
    testImplementation(libs.junit)

    // Instrumented tests (device/emulator).
    androidTestImplementation(libs.androidx.junit.ext)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Compose tooling: live previews + layout inspector in debug builds.
    debugImplementation(libs.androidx.ui.tooling)
}