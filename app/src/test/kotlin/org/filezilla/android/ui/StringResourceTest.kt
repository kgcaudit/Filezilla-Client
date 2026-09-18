package org.filezilla.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The string resources, checked against each other and against how they are
 * called.
 *
 * These are the mistakes that do not show up in a compile and often not in a
 * screenshot either: a format specifier that differs between languages crashes
 * only for users in the other language, and a placeholder that reuses another
 * field's label simply reads as the wrong question -- which is how the name
 * box came to say "호스트".
 */
class StringResourceTest {

    private val resDir = File("src/main/res")
    private val kotlinDir = File("src/main/kotlin")

    private fun strings(values: String): Map<String, String> {
        val file = File(resDir, "$values/strings.xml")
        assertTrue("missing $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableMapOf<String, String>()
        val root = doc.documentElement
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i) as? Element ?: continue
            when (node.tagName) {
                "string" -> out[node.getAttribute("name")] = node.textContent
                "plurals" -> out[node.getAttribute("name")] = node.textContent
            }
        }
        return out
    }

    private fun sources(): List<Pair<String, String>> =
        kotlinDir.walkTopDown().filter { it.extension == "kt" }.map { it.name to it.readText() }.toList()

    private val specifier = Regex("""%(\d+\$)?[sdf]""")

    @Test
    fun `every English string has a Korean translation`() {
        val en = strings("values").keys
        val ko = strings("values-ko").keys
        // The app name is a name, not a phrase, and is deliberately not
        // translated.
        assertEquals(emptySet<String>(), en - ko - setOf("app_name"))
    }

    @Test
    fun `a string and its translation take the same arguments`() {
        // A "%1$s" in one language and "%1$d" in the other is an exception
        // thrown only for users of the second one -- exactly the sort of
        // failure that never appears in testing done in the first.
        val en = strings("values")
        val ko = strings("values-ko")
        val mismatched = en.keys.filter { key ->
            val other = ko[key] ?: return@filter false
            // Plurals legitimately differ in item count -- Korean has one
            // form where English has two -- so the comparison is on the set
            // of specifiers used, not on how many times they appear.
            specifier.findAll(en.getValue(key)).map { it.value }.toSet() !=
                specifier.findAll(other).map { it.value }.toSet()
        }
        assertEquals(emptyList<String>(), mismatched)
    }

    @Test
    fun `no call site passes the wrong number of arguments`() {
        val en = strings("values")
        val call = Regex("""stringResource\(\s*R\.string\.(\w+)((?:\s*,[^)]*)?)\)""")
        val wrong = mutableListOf<String>()
        for ((name, src) in sources()) {
            for (match in call.findAll(src)) {
                val key = match.groupValues[1]
                val text = en[key]
                if (text == null) {
                    wrong += "$name: unknown R.string.$key"
                } else {
                    val expected = specifier.findAll(text).map { it.value }.toSet().size
                    val actual = match.groupValues[2].count { it == ',' }
                    if (expected != actual) wrong += "$name: $key wants $expected, got $actual"
                }
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `a placeholder never borrows another field's label`() {
        // The bug that put "호스트" in the name box: the hint fell back to the
        // host field's label, so the form appeared to ask the wrong question.
        val placeholder = Regex("""placeholder\s*=\s*\{[^}]*R\.string\.(\w+)""")
        val borrowed = sources().flatMap { (name, src) ->
            placeholder.findAll(src)
                .map { it.groupValues[1] }
                .filter { it.startsWith("field_") }
                .map { "$name: $it" }
        }
        assertEquals(emptyList<String>(), borrowed)
    }

    @Test
    fun `no rendered string is patched back into a template`() {
        // stringResource(...).replace(...) renders the sentence and then edits
        // it, which holds only until a translation contains the same
        // characters somewhere else. Formatting through the context is the
        // way to do this.
        val patched = sources().flatMap { (name, src) ->
            src.lines().withIndex()
                .filter { (_, line) -> "stringResource(" in line && ".replace(" in line }
                .map { (i, _) -> "$name:${i + 1}" }
        }
        assertEquals(emptyList<String>(), patched)
    }

    @Test
    fun `every string is used somewhere`() {
        val all = sources().joinToString("\n") { it.second }
        val referenced = Regex("""R\.(?:string|plurals)\.(\w+)""")
            .findAll(all).map { it.groupValues[1] }.toSet()
        val strays = strings("values").keys - referenced - setOf("app_name")
        assertEquals(emptySet<String>(), strays)
    }
}
