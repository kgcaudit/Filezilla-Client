package org.filezilla.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the release build stays shrunk, and that the rules it needs stay
 * written down.
 *
 * Shrinking took the app from 17.4 MB to 1.7 MB -- almost all of it code
 * that nothing calls, the icon set most of all. It also takes away the
 * debuggable flag, which on a sideloaded build is not a detail: a
 * debuggable app hands its private files, the saved servers and their
 * passwords among them, to anything that can reach it over ADB.
 *
 * Neither is visible from any screen. A build that quietly stopped
 * shrinking would look identical, weigh ten times as much, and give its
 * data away -- so what it looks like from the build file is checked here
 * instead.
 *
 * What this cannot check is that the shrunk app still runs. Nothing on a
 * JVM can: the tests run against classes R8 never saw. That part is
 * verified by running the release build on a phone, and the reason the
 * rules file is short and every rule in it carries its reason is that
 * this is the half nobody can automate.
 */
class ShrinkingTest {

    private val buildFile: String get() = File("build.gradle.kts").readText()

    private val rulesFile = File("proguard-rules.pro")

    private val release: String
        get() = buildFile.substringAfter("release {").substringBefore("\n        }")

    @Test
    fun `the release build is shrunk`() {
        assertTrue(
            "release stopped shrinking; the app is ten times its size again " +
                "and R8 is no longer removing anything: $release",
            Regex("""isMinifyEnabled\s*=\s*true""").containsMatchIn(release),
        )
        assertTrue(
            "unused resources are being carried again",
            Regex("""isShrinkResources\s*=\s*true""").containsMatchIn(release),
        )
    }

    @Test
    fun `the rules the app needs are still handed to R8`() {
        assertTrue("the rules file is gone but the build still names it", rulesFile.isFile)
        assertTrue(
            "shrinking without these rules removes what nothing appears to " +
                "call but Room and the enums reach anyway",
            "proguard-rules.pro" in release,
        )
    }

    /**
     * Every rule is a promise that something cannot be removed, which is
     * also a promise that it will never be shrunk. A file of rules nobody
     * can account for is how a shrink configuration stops shrinking a year
     * later, one cautious keep at a time.
     */
    @Test
    fun `every keep rule says why it is there`() {
        val unexplained = mutableListOf<String>()
        var explained = false
        for (line in rulesFile.readLines()) {
            val text = line.trim()
            when {
                text.startsWith("#") -> explained = true
                text.isEmpty() -> explained = false
                text.startsWith("-keep") -> {
                    if (!explained) unexplained += text
                    explained = true
                }
            }
        }

        assertEquals(
            "a keep rule with no comment above it: nobody after you can tell " +
                "whether it is still needed, so it will never be removed",
            emptyList<String>(),
            unexplained,
        )
    }

    @Test
    fun `the release build is not debuggable`() {
        // Not something to set: release is not debuggable unless somebody
        // says otherwise, and the only way this goes wrong is somebody
        // saying otherwise to chase a bug and leaving it.
        assertFalse(
            "the release build was made debuggable, which hands its saved " +
                "passwords to anything that can reach it over ADB",
            Regex("""isDebuggable\s*=\s*true""").containsMatchIn(release),
        )
    }
}
