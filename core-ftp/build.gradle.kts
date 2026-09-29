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

    // SFTP: the SSH transport for the SFTP engine. Pure Java, so it builds and
    // tests here in the plain-JVM module just like the rest of the engine does.
    implementation(libs.jsch)

    // The FTPS harness is plain JDK, but the SFTP harness in testFixtures is a
    // real in-process SSH server, so it needs MINA SSHD to compile. Shared with
    // the app module, which browses and transfers against it too.
    testFixturesImplementation(libs.sshd.core)
    testFixturesImplementation(libs.sshd.sftp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val ftpsServerDir: String =
    layout.projectDirectory.dir("src/testFixtures/resources/ftps-server").asFile.absolutePath

tasks.test {
    useJUnitPlatform()

    // A larger thread stack for the test JVM. NoBlanketTrustTest scans every
    // source file with a regex that recurses once per character of each string
    // literal, and one viewer source embeds a multi-kilobyte literal that
    // pushes that recursion past the default stack -- a StackOverflowError in
    // the guard rather than a verdict from it. The extra stack lets the guard
    // finish reading a legitimately large source instead of falling over on it.
    jvmArgs("-Xss4m")

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
