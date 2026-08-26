plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

// ---------------------------------------------------------------------------
// SongPack v1 canonical-schema test resources (plan §8.1.10, §20 P1.1).
// The Kotlin schema consumer must validate the CANONICAL schema + golden
// fixtures from content/ — COPIED into a GENERATED (gitignored) dir, never a
// committed copy, so the three consumers (Python pipeline, PHP API, Kotlin
// tests) cannot drift by construction. The CI lint-all drift guard fails if
// any other committed songpack-v1.json appears anywhere in the repo.
// Declared before `android {}` because the android block references it.
// ---------------------------------------------------------------------------
val songpackTestResourcesDir = layout.buildDirectory.dir("generated/songpack")

val copySongpackSchema by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("content/schema"))
    include("songpack-v1.json")
    into(songpackTestResourcesDir)
}

val copySongpackFixtures by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("content/fixtures"))
    include("songpack-v1/**")
    into(songpackTestResourcesDir)
}

// Ensure the copies run before the unit-test resources are processed (the
// android unit-test task is not a `Test` subtype and the generated dir is
// consumed by process*UnitTestJavaRes, so wire the dependency there).
tasks.matching { it.name.startsWith("process") && it.name.endsWith("UnitTestJavaRes") }.configureEach {
    dependsOn(copySongpackSchema, copySongpackFixtures)
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

    // Expose the generated SongPack schema/fixture dir as test resources
    // (see the copySongpack* tasks at the top of this file).
    sourceSets["test"].resources.srcDir(songpackTestResourcesDir)
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

    // SongPack v1 parsing (P1.6 lesson player) — org.json, pure JVM. Main code
    // compiles against the Android framework's org.json (present since API 1,
    // minSdk 26); the Maven artifact is only needed on the JVM test classpath
    // (plan §24: "testImplementation org.json:json for JVM").
    testImplementation(libs.org.json)

    // RealtimeScorer + batch scoring engine (P1.5/1.6).
    implementation(project(":scoring"))

    // Unit tests (JVM).
    testImplementation(libs.junit)

    // SongPack v1 canonical-schema validation (plan §8.1.10, §20 P1.1) — JVM
    // only. networknt brings jackson-databind + core + annotations transitively.
    testImplementation(libs.json.schema.validator)

    // Instrumented tests (device/emulator).
    androidTestImplementation(libs.androidx.junit.ext)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Compose tooling: live previews + layout inspector in debug builds.
    debugImplementation(libs.androidx.ui.tooling)
}

// Compiler/stdlib consistency: Paparazzi 2.0.0-alpha05 transitively forces
// kotlin-stdlib 2.3.0 onto the unit-test classpath — metadata newer than the
// Kotlin 2.2.0 compiler supports reliably. Pin stdlib to the project's Kotlin
// version everywhere in this module.
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}