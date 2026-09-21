package org.filezilla.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That a build can say which build it is.
 *
 * Eighty-nine commits shipped as versionCode 1, versionName "0.1". The
 * phone could not tell two of them apart -- Android compares versionCode
 * to decide whether an install is an upgrade -- and neither could anybody
 * holding one: "is this the build with the fix in it" had no answer short
 * of finding the bug again.
 *
 * Read from the build file, because the failure is a literal creeping back
 * in, and a literal compiles perfectly.
 */
class BuildIdentityTest {

    private val buildFile: String
        get() = File("build.gradle.kts").readText()

    @Test
    fun `the version is derived rather than typed`() {
        val block = buildFile.substringAfter("defaultConfig {").substringBefore("\n    }")

        assertTrue(
            "versionCode is a literal again; it will stop being raised: $block",
            Regex("""versionCode\s*=\s*\d""").containsMatchIn(block).not(),
        )
        assertTrue(
            "versionName is a literal again, so no build can be told from another",
            Regex(""""versionName"?\s*=\s*"[\d.]+"\s*$""", RegexOption.MULTILINE)
                .containsMatchIn(block).not(),
        )
        assertTrue("versionCode is not the commit count", "commitCount" in block)
        assertTrue("versionName does not name the commit", "commitHash" in block)
    }

    /**
     * And that the number only ever goes up.
     *
     * The commit count does; a hash does not, and a date only does if the
     * clock is right. Android refuses a lower versionCode over a higher
     * one, which is the behaviour wanted -- an older build should not
     * quietly replace a newer one -- but only if the number is ordered.
     */
    @Test
    fun `the version code counts commits`() {
        assertTrue(
            "versionCode comes from something other than the commit count",
            Regex("""versionCode\s*=\s*commitCount""").containsMatchIn(buildFile),
        )
        assertTrue(
            "the commit count is not read from git",
            Regex("""commitCount\s*=\s*git\("rev-list", "--count", "HEAD"\)""")
                .containsMatchIn(buildFile),
        )
    }

    /** The app has to show it, or reading it means holding a computer. */
    @Test
    fun `the app shows its build somewhere a person can read it`() {
        val sheet = File("src/main/kotlin/org/filezilla/android/ui/QueueSettingsSheet.kt").readText()

        assertTrue(
            "the settings sheet no longer says which build this is",
            "BuildConfig.VERSION_NAME" in sheet,
        )
    }

    /** Which needs the generated class to exist at all. */
    @Test
    fun `the build config is generated`() {
        assertTrue("buildConfig is off; VERSION_NAME would not exist", "buildConfig = true" in buildFile)
    }
}
