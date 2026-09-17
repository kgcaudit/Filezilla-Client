pluginManagement {
    repositories {
        gradlePluginPortal()
        // repo1 directly: the repo.maven.apache.org alias rate-limits harder.
        maven { url = uri("https://repo1.maven.org/maven2") }
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://repo1.maven.org/maven2") }
        mavenCentral()
        google()
    }
}

rootProject.name = "filezilla-android"

// The protocol engine is plain Kotlin/JVM: it builds and its integration
// tests run against a live FTPS server without the Android SDK present.
include(":core-ftp")

// The Android app is only included when an SDK is actually available, so
// `./gradlew :core-ftp:test` works on a machine (or CI box) without one.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").takeIf { it.exists() }
        ?.readText()?.contains("sdk.dir") == true
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found; skipping :app. Only :core-ftp is configured.")
}
