import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * How many commits are behind this build, and which one it is.
 *
 * Every build carried versionCode 1 and versionName "0.1" for eighty-nine
 * commits, so the phone could not tell two of them apart and neither could
 * anybody holding one: "is this the build with the fix in it" had no answer
 * short of finding the bug again. Derived from git rather than typed,
 * because a number somebody has to remember to raise is a number that
 * stops being raised.
 *
 * Falls back when git is not there -- a source archive, a clean CI
 * checkout without history -- rather than failing the build, since a build
 * that cannot say which commit it is is still a build.
 */
fun git(vararg args: String): String? = runCatching {
    val process = ProcessBuilder("git", *args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && text.isNotEmpty()) text else null
}.getOrNull()

val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0
val commitHash = git("rev-parse", "--short", "HEAD") ?: "unknown"
val workingTreeDirty = git("status", "--porcelain")?.isNotEmpty() == true

/**
 * The signing key, when whoever is building has it.
 *
 * Android installs a build over another only when the two signatures
 * match, so the key is what makes an upgrade an upgrade rather than an
 * uninstall. Left to itself the debug build uses a key Android generates
 * per machine and keeps in a home directory: fine for one build, and
 * useless the moment the machine changes, because every phone with the app
 * on it then has to uninstall -- taking its saved servers, their passwords
 * and the transfer journal with it.
 *
 * So the key is named in `keystore.properties`, which is not in the
 * repository and neither is the key: a key in a repository is a key anyone
 * with the repository can sign as this app with. See the .example beside
 * it.
 *
 * Absent, the build carries on unsigned rather than failing. A checkout
 * that only wants to run the tests should not need a key at all.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val keystoreFile = keystoreProperties.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "org.filezilla.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.filezilla.android"
        minSdk = 26
        targetSdk = 35
        // The commit count: it only ever goes up, and it goes up by itself.
        // Android refuses to install a lower one over a higher one, which
        // is the behaviour wanted -- an older build should not quietly
        // replace a newer one.
        versionCode = commitCount
        // What a person needs to answer "which build is this": the commit
        // it was made from. Marked when the tree had uncommitted changes,
        // because such a build exists nowhere but on the machine that made
        // it and saying so saves an afternoon.
        versionName = "0.1.$commitCount ($commitHash${if (workingTreeDirty) "+" else ""})"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("olo") {
                storeFile = keystoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Both build types, deliberately. The phone is handed debug builds
        // while this is being worked on and would be handed release ones
        // later, and if those carry different signatures the switch costs
        // an uninstall -- which is the thing the key exists to avoid.
        val olo = signingConfigs.findByName("olo")
        debug {
            olo?.let { signingConfig = it }
        }
        release {
            olo?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // So the app can say which build it is on its own settings sheet.
        buildConfig = true
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }


    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

composeCompiler {
    // See the file itself: the listing types come from a module the Compose
    // compiler never sees, so it has to be told they hold still.
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose_stability.conf"),
    )
}

dependencies {
    implementation(project(":core-ftp"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.documentfile)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Compose rendered under Robolectric, so a layout can be looked at rather
    // than reasoned about. See LayoutShotTest.
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    // The live FTPS server, shared from :core-ftp. The app's resume path is
    // checked against a real server for the same reason the engine's is.
    testImplementation(testFixtures(project(":core-ftp")))
}

// Room's generated code carries the schema, and exporting it turns a schema
// change into a reviewable file rather than a surprise migration at runtime.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.logging", "stdout")
}

// The app's resume tests drive the same live FTPS server the engine's do.
// The harness is a shared test fixture; its Python environment is a real
// directory on disk, so the path is named here rather than looked up on the
// classpath.
tasks.withType<Test>().configureEach {
    systemProperty(
        "ftps.server.dir",
        rootProject.layout.projectDirectory
            .dir("core-ftp/src/testFixtures/resources/ftps-server")
            .asFile
            .absolutePath,
    )
    // The container's default locale would encode filenames as ASCII, which
    // turns the Korean names these tests use into question marks before they
    // reach the FTP layer.
    environment("LANG", "C.UTF-8")
    environment("LC_ALL", "C.UTF-8")
    systemProperty("file.encoding", "UTF-8")
}
