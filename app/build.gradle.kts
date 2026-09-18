import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.filezilla.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.filezilla.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // No shrinking yet: the engine is reached reflection-free, but the
            // release build is not part of any phase that has been verified,
            // and shipping an untested shrink configuration would be worse
            // than shipping none.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
