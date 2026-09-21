package org.filezilla.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the signing key stays the app's own, and stays out of the repository.
 *
 * Android installs a build over another only when the two signatures match,
 * so the key is what makes an upgrade an upgrade. Left to itself the debug
 * build uses a key Android generates per machine and keeps in a home
 * directory: fine for one build, and useless the moment the machine
 * changes -- every phone with the app on it then has to uninstall, taking
 * its saved servers, their passwords and the transfer journal with it.
 *
 * Two things can undo that, and neither is visible from any screen: the
 * config quietly ceasing to apply to a build type, or the key being
 * committed. The second is worse than the first.
 */
class SigningKeyTest {

    private val buildFile: String
        get() = File("build.gradle.kts").readText()

    private val ignoreFile: String
        get() = File("../.gitignore").readText()

    @Test
    fun `both build types are signed with the same key`() {
        val types = buildFile.substringAfter("buildTypes {").substringBefore("\n    compileOptions")

        assertTrue("the debug build no longer uses the app's own key", "debug {" in types)
        assertTrue(
            "one of the build types stopped taking the signing config, which " +
                "makes switching between them cost an uninstall: $types",
            Regex("""signingConfig\s*=\s*it""").findAll(types).count() == 2,
        )
    }

    /** Absent a key, a checkout that only wants to run the tests still builds. */
    @Test
    fun `a checkout without the key still builds`() {
        assertTrue(
            "the build now requires a key; a fresh clone cannot run its tests",
            "takeIf { it.isFile }" in buildFile,
        )
        assertTrue(
            "the signing config is applied unconditionally",
            "olo?.let" in buildFile,
        )
    }

    /**
     * A key in a repository is a key anyone with the repository can sign as
     * this app with.
     */
    @Test
    fun `the key and its password are not in the repository`() {
        for (pattern in listOf("*.jks", "*.keystore", "keystore.properties")) {
            assertTrue("$pattern is no longer ignored", pattern in ignoreFile)
        }
    }

    @Test
    fun `no key is actually committed`() {
        val tracked = File("..").walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile }
            .filter { it.extension == "jks" || it.extension == "keystore" }
            .map { it.path }
            .toList()

        // The working copy may hold one; git is what must not.
        val committed = tracked.filterNot { File("../.gitignore").isFile }
        assertFalse("a keystore is tracked: $committed", committed.isNotEmpty())
    }

    /** And the shape of the file is written down, since the file itself is not. */
    @Test
    fun `the key file's format is documented`() {
        val example = File("../keystore.properties.example")

        assertTrue("keystore.properties.example is missing", example.isFile)
        for (key in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) {
            assertTrue("$key is not documented", key in example.readText())
        }
    }
}
