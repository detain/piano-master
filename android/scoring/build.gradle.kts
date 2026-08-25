// :scoring — the pure-Kotlin scoring engine (plan §20 P1.5, spec docs/specs/scoring-v1.md).
//
// Purity bar: ZERO Android dependencies, ZERO I/O in main code, deterministic.
// The only dependency is JUnit 4 for tests; the CI lint-all purity grep
// forbids `import android.` / `import androidx.` anywhere under src/.

plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

java {
    // CI runs JDK 21; target 17 bytecode (no jvmToolchain — no toolchain
    // provisioning on CI runners).
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // KGP 2.2 removed the kotlinOptions DSL; compilerOptions is the modern
    // equivalent with the same effect (jvmTarget 17).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The ONLY dependency: JUnit 4 for tests. Everything else is stdlib.
    testImplementation(libs.junit)
}

jacoco {
    // Pin explicitly (Gradle 8.11.1 default; the Kotlin-generated-method
    // filters we rely on for fair line counts exist in 0.8.12).
    toolVersion = "0.8.12"
}

tasks.test {
    useJUnit()
}

// Coverage gate (plan §20 P1.5 expectation: >= 95% line coverage).
// Exclude nothing: the tests must cover every line of main code.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true) // CI reads the XML for the gate numbers
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

/**
 * Replay a recorded scoring session against the scorer (plan §20 P1.5.6).
 *
 * Usage:
 *   ./gradlew :scoring:replay --args="--events <file> --expected <file> \
 *     [--tempo-map <file> | --bpm <n>] [--beginner] [--stars one,two,three]"
 *
 * Session files are the TSV format documented in docs/specs/scoring-v1.md
 * (see com.keyquest.scoring.replay.SessionFormat). This task is a developer
 * tool — deliberately NOT part of `check`.
 */
tasks.register<JavaExec>("replay") {
    group = "application"
    description = "Replay a recorded session TSV against the scorer (plan P1.5.6)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.keyquest.scoring.replay.ReplayMain")
}