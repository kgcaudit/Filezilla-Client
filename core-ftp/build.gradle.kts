import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Targets JVM 17 bytecode -- what the Android app module consumes -- while
// building with whatever JDK 17+ is on the machine, so no toolchain download
// is needed.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()

    // The container's default locale is POSIX, which makes the JVM encode
    // filenames as ASCII and turns non-Latin names into question marks before
    // they ever reach the FTP layer. The integration tests create files with
    // Korean names on purpose, so the forked test JVM gets a UTF-8 locale.
    environment("LANG", "C.UTF-8")
    environment("LC_ALL", "C.UTF-8")
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
