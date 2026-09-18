import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    // The live FTPS server harness is shared with the app module's tests, so
    // that the app's resume behaviour can be checked against a real server
    // rather than a mock. A mock would agree with whatever the app does, and
    // agreeing is exactly the failure mode worth catching here.
    `java-test-fixtures`
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

    // The harness is plain JDK; it needs nothing from the engine but its
    // package, so the fixtures carry no extra dependencies.

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val ftpsServerDir: String =
    layout.projectDirectory.dir("src/testFixtures/resources/ftps-server").asFile.absolutePath

tasks.test {
    useJUnitPlatform()

    // Where the harness finds the Python server and its virtual environment.
    systemProperty("ftps.server.dir", ftpsServerDir)

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
