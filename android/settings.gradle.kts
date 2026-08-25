pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Keep module build scripts free of repository declarations so future
    // modules (:engine-ndk, :core-data, ...) cannot drift onto ad-hoc repos.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "keyquest-android"
include(":app")
include(":scoring")