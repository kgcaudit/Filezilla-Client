package org.filezilla.android.viewer

import org.filezilla.android.archive.Archive
import org.filezilla.android.archive.ArchiveEntry
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * An EPUB, read as far as showing it needs.
 *
 * An EPUB is a zip: a `META-INF/container.xml` names the package file (the
 * OPF), whose spine lists the XHTML files in reading order and whose manifest
 * points at a table of contents -- an EPUB 3 navigation document, or the
 * older EPUB 2 NCX. This reads those three things and no more; the chapters
 * themselves are handed to a WebView, which is what renders XHTML and CSS.
 *
 * Everything here works on an [Archive] and plain XML, so none of it is
 * Android and all of it can be tested on its own.
 */
data class EpubBook(
    /** The book's title from the package metadata, or null when it gives none. */
    val title: String?,
    /** The XHTML files, as archive paths, in the order they are read. */
    val spine: List<String>,
    /** The table of contents, or empty when the book carries none we could read. */
    val toc: List<TocEntry>,
) {
    /** One line of the contents: a title, and the chapter it opens. */
    data class TocEntry(val title: String, val path: String)
}

object EpubReader {

    /** Whether [archive] is an EPUB, by the container every EPUB must carry. */
    fun looksEpub(archive: Archive): Boolean =
        archive.entries.any { it.path == CONTAINER }

    /**
     * Reads [archive]'s spine and contents, or null when it is not an EPUB or
     * its package file cannot be found or parsed. A book whose contents fail
     * to parse still returns, with an empty [EpubBook.toc]: reading does not
     * depend on the table.
     */
    fun read(archive: Archive): EpubBook? {
        val byPath = archive.entries.associateBy { it.path }
        val containerXml = text(archive, byPath[CONTAINER] ?: return null) ?: return null
        val opfPath = rootfilePath(containerXml) ?: return null
        val opf = parse(text(archive, byPath[opfPath] ?: return null) ?: return null) ?: return null
        val opfDir = opfPath.substringBeforeLast('/', "")

        // Manifest: id -> the file it names and the properties it carries.
        val manifest = HashMap<String, ManifestItem>()
        for (item in elementsByLocal(opf, "item")) {
            val id = item.getAttribute("id")
            if (id.isEmpty()) continue
            manifest[id] = ManifestItem(
                href = item.getAttribute("href"),
                properties = item.getAttribute("properties"),
                mediaType = item.getAttribute("media-type"),
            )
        }

        // Spine: the reading order, skipping anything marked not linear.
        val spine = ArrayList<String>()
        for (ref in elementsByLocal(opf, "itemref")) {
            if (ref.getAttribute("linear") == "no") continue
            val item = manifest[ref.getAttribute("idref")] ?: continue
            resolve(opfDir, item.href).takeIf { it in byPath }?.let(spine::add)
        }
        if (spine.isEmpty()) return null

        val title = elementsByLocal(opf, "title").firstOrNull()?.textContent?.trim()?.ifEmpty { null }
        val toc = readToc(archive, byPath, opf, manifest, opfDir, spine.toSet())
        return EpubBook(title = title, spine = spine, toc = toc)
    }

    private data class ManifestItem(val href: String, val properties: String, val mediaType: String)

    private fun readToc(
        archive: Archive,
        byPath: Map<String, ArchiveEntry>,
        opf: Document,
        manifest: Map<String, ManifestItem>,
        opfDir: String,
        inSpine: Set<String>,
    ): List<EpubBook.TocEntry> {
        // EPUB 3 first: the manifest item whose properties include "nav".
        val navHref = manifest.values.firstOrNull { "nav" in it.properties }?.href
        if (navHref != null) {
            val navPath = resolve(opfDir, navHref)
            byPath[navPath]?.let { entry ->
                text(archive, entry)?.let { xml ->
                    tocFromNav(xml, navPath.substringBeforeLast('/', ""))
                        .filter { it.path in inSpine }
                        .let { if (it.isNotEmpty()) return it }
                }
            }
        }
        // EPUB 2: the NCX, named by the spine's toc attribute or by media type.
        val spineEl = elementsByLocal(opf, "spine").firstOrNull()
        val ncxId = spineEl?.getAttribute("toc")?.ifEmpty { null }
        val ncxHref = ncxId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.href
        if (ncxHref != null) {
            val ncxPath = resolve(opfDir, ncxHref)
            byPath[ncxPath]?.let { entry ->
                text(archive, entry)?.let { xml ->
                    tocFromNcx(xml, ncxPath.substringBeforeLast('/', ""))
                        .filter { it.path in inSpine }
                        .let { if (it.isNotEmpty()) return it }
                }
            }
        }
        return emptyList()
    }

    /** The package file's path, from `container.xml`'s first rootfile. */
    private fun rootfilePath(containerXml: String): String? {
        val doc = parse(containerXml) ?: return null
        return elementsByLocal(doc, "rootfile")
            .firstOrNull { it.getAttribute("full-path").isNotEmpty() }
            ?.getAttribute("full-path")
    }

    private fun tocFromNav(xml: String, navDir: String): List<EpubBook.TocEntry> {
        val doc = parse(xml) ?: return navAnchorsByRegex(xml, navDir)
        // The nav[epub:type=toc], or the first nav when none says which it is.
        val navs = elementsByLocal(doc, "nav")
        val toc = navs.firstOrNull { it.getAttribute("epub:type").contains("toc") } ?: navs.firstOrNull()
        val out = ArrayList<EpubBook.TocEntry>()
        for (a in elementsByLocal(toc ?: return emptyList(), "a")) {
            val href = a.getAttribute("href")
            val title = a.textContent.trim()
            if (href.isNotEmpty() && title.isNotEmpty()) {
                out += EpubBook.TocEntry(title, resolve(navDir, href))
            }
        }
        return out
    }

    /** A last resort when the nav is XHTML the XML parser will not take. */
    private fun navAnchorsByRegex(xml: String, navDir: String): List<EpubBook.TocEntry> {
        val out = ArrayList<EpubBook.TocEntry>()
        for (m in Regex("""<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val href = m.groupValues[1]
            val title = m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            if (href.isNotEmpty() && title.isNotEmpty()) out += EpubBook.TocEntry(title, resolve(navDir, href))
        }
        return out
    }

    private fun tocFromNcx(xml: String, ncxDir: String): List<EpubBook.TocEntry> {
        val doc = parse(xml) ?: return emptyList()
        val out = ArrayList<EpubBook.TocEntry>()
        for (point in elementsByLocal(doc, "navPoint")) {
            val label = elementsByLocal(point, "text").firstOrNull()?.textContent?.trim().orEmpty()
            val src = elementsByLocal(point, "content").firstOrNull()?.getAttribute("src").orEmpty()
            if (label.isNotEmpty() && src.isNotEmpty()) out += EpubBook.TocEntry(label, resolve(ncxDir, src))
        }
        return out
    }

    // --------------------------------------------------------------- helpers

    private fun text(archive: Archive, entry: ArchiveEntry): String? = runCatching {
        val bytes = archive.open(entry).use { it.readBytes() }
        // A leading byte-order mark would otherwise sit at the front of the
        // first tag and stop the parser matching it.
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0
        String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }.getOrNull()

    private fun parse(xml: String): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // Never reach out for a DTD: an EPUB names external ones it does not
        // carry, and fetching them would be both a hang and a network call.
        for (feature in DTD_OFF) runCatching { factory.setFeature(feature, false) }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    /** Elements whose tag's local part (after any `prefix:`) is [local]. */
    private fun elementsByLocal(node: org.w3c.dom.Node, local: String): List<Element> {
        val out = ArrayList<Element>()
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                if (child.tagName.substringAfterLast(':') == local) out += child
                out += elementsByLocal(child, local)
            }
        }
        return out
    }

    /**
     * An archive path from a directory and an href within the book.
     *
     * The href is URL-decoded (a space arrives as `%20` but the zip entry has
     * the space), its fragment dropped, and any `..` walked, so the result is
     * a plain entry path to match against the archive.
     */
    fun resolve(baseDir: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { java.net.URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val combined = when {
            decoded.startsWith("/") -> decoded.removePrefix("/")
            baseDir.isEmpty() -> decoded
            else -> "$baseDir/$decoded"
        }
        val parts = ArrayList<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    private const val CONTAINER = "META-INF/container.xml"

    private val DTD_OFF = listOf(
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
    )
}
