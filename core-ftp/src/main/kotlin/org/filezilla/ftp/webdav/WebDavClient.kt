package org.filezilla.ftp.webdav

import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.listing.EntryTime
import org.filezilla.ftp.listing.TimeAccuracy
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A WebDAV client: the HTTP verbs the app's browsing and transfers are built on.
 *
 * Deliberately in `core-ftp`, plain JVM, beside the FTP and SFTP engines, and
 * proven the same way they are -- against a real server (Apache `mod_dav`),
 * not a stub. Everything the app does to a server is here as one HTTP request
 * each: PROPFIND to list, GET (with a byte range, so a download resumes) to
 * fetch, PUT to send, MKCOL/DELETE/MOVE to make, remove and rename, HEAD for a
 * file's size and time. Paths are relative to [WebDavSettings.basePath]; this
 * splices them onto the origin and encodes them.
 *
 * Blocking, like the FTP session: a caller runs one request and waits. The JDK
 * [HttpClient] underneath is given a connection timeout and, for a self-signed
 * server the user has accepted, a trust-everything SSL context.
 */
class WebDavClient(private val settings: WebDavSettings) {

    private val auth = "Basic " + Base64.getEncoder()
        .encodeToString("${settings.user}:${settings.password}".toByteArray())

    private val http: HttpClient = HttpClient.newBuilder().apply {
        connectTimeout(java.time.Duration.ofSeconds(20))
        if (settings.https && settings.allowUntrustedCertificate) {
            sslContext(trustEverything())
        }
    }.build()

    /** The URL for a path relative to the base, each segment percent-encoded. */
    private fun url(relativePath: String): URI {
        // A collection must keep its trailing slash: Apache mod_dav answers a
        // PROPFIND of "/work" with a 301 to "/work/", so the slash is carried
        // through rather than trimmed away with the rest.
        val trailing = relativePath.endsWith("/")
        val joined = joinPaths(settings.basePath, relativePath)
        val full = if (trailing && joined != "/") "$joined/" else joined
        val encoded = full.split('/').joinToString("/") { encodeSegment(it) }
        return URI.create(settings.origin + ensureLeadingSlash(encoded))
    }

    private fun request(method: String, relativePath: String): HttpRequest.Builder =
        HttpRequest.newBuilder(url(relativePath))
            .header("Authorization", auth)
            .method(method, HttpRequest.BodyPublishers.noBody())

    // ------------------------------------------------------------- browse

    /** Whether the server answers as a WebDAV server at the base path. */
    fun connect() {
        val response = http.send(
            request("OPTIONS", "").build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        if (response.statusCode() == 401) throw WebDavException(401, "login refused")
        if (response.statusCode() !in 200..399) {
            throw WebDavException(response.statusCode(), "server did not answer as WebDAV")
        }
    }

    /** The immediate children of [dirPath] (relative to the base). */
    fun list(dirPath: String): List<DirectoryEntry> {
        val response = http.send(
            request("PROPFIND", ensureTrailingSlash(dirPath))
                .header("Depth", "1")
                .header("Content-Type", "application/xml")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() == 404) throw WebDavException(404, "no such folder")
        if (response.statusCode() != 207) {
            throw WebDavException(response.statusCode(), "could not list the folder")
        }
        // The folder itself comes back in the listing as the first response;
        // drop it, keep its children. Matched by comparing paths, not by order.
        val here = normalizePath(joinPaths(settings.basePath, dirPath))
        return parseListing(response.body()).filterNot { (path, _) ->
            normalizePath(path).trimEnd('/') == here.trimEnd('/')
        }.map { it.second }
    }

    /** A file's size and time, or null when it is not there. */
    fun info(path: String): DirectoryEntry? {
        val response = http.send(
            request("PROPFIND", path)
                .header("Depth", "0")
                .header("Content-Type", "application/xml")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() == 404) return null
        if (response.statusCode() != 207) throw WebDavException(response.statusCode(), "could not read the entry")
        return parseListing(response.body()).firstOrNull()?.second
    }

    fun makeCollection(path: String) {
        val code = http.send(request("MKCOL", path).build(), HttpResponse.BodyHandlers.discarding()).statusCode()
        if (code !in 200..201 && code != 405) throw WebDavException(code, "could not make the folder")
    }

    fun delete(path: String) {
        val code = http.send(request("DELETE", path).build(), HttpResponse.BodyHandlers.discarding()).statusCode()
        if (code !in 200..204 && code != 404) throw WebDavException(code, "could not delete")
    }

    fun move(fromPath: String, toPath: String) {
        val destination = url(toPath).toString()
        val code = http.send(
            request("MOVE", fromPath).header("Destination", destination).header("Overwrite", "T").build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()
        if (code !in 200..204) throw WebDavException(code, "could not rename")
    }

    fun fingerprint(path: String): RemoteFingerprint {
        val response = http.send(request("HEAD", path).build(), HttpResponse.BodyHandlers.discarding())
        val size = response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
        val modified = response.headers().firstValue("Last-Modified").orElse(null)?.let(::parseHttpDate)
        return RemoteFingerprint(size = size, modifiedMillis = modified)
    }

    // ---------------------------------------------------------- transfer

    /**
     * Streams [path] to [out], starting at [offset] so a half-finished download
     * resumes. Returns the total size when the server gave one. A server that
     * ignores the range (answers 200 to a ranged request) is reported, so the
     * caller can start the file over rather than append to its own middle.
     */
    fun download(path: String, offset: Long, out: OutputStream, onProgress: (Long) -> Unit): Long? {
        val builder = request("GET", path).method("GET", HttpRequest.BodyPublishers.noBody())
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val code = response.statusCode()
        if (code == 404) throw WebDavException(404, "no such file")
        if (offset > 0 && code == 200) throw WebDavResumeUnsupported()
        if (code != 200 && code != 206) throw WebDavException(code, "could not fetch the file")

        var done = offset
        response.body().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                done += read
                onProgress(done)
            }
        }
        // 206 carries the whole size as the end of Content-Range; 200 as length.
        return totalFrom(response, offset)
    }

    /** Sends [length] bytes from [input] to [path], replacing what is there. */
    fun upload(path: String, input: InputStream, length: Long, onProgress: (Long) -> Unit) {
        val counting = CountingInputStream(input, onProgress)
        val body = HttpRequest.BodyPublishers.fromPublisher(
            HttpRequest.BodyPublishers.ofInputStream { counting },
            length,
        )
        val code = http.send(
            request("PUT", path).header("Content-Type", "application/octet-stream")
                .method("PUT", body).build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()
        if (code !in 200..204) throw WebDavException(code, "could not upload")
    }

    // ------------------------------------------------------------ parse

    private fun parseListing(xml: ByteArray): List<Pair<String, DirectoryEntry>> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // A listing is data, never a document that should fetch anything.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val responses = doc.getElementsByTagNameNS(DAV_NS, "response")
        val out = mutableListOf<Pair<String, DirectoryEntry>>()
        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val href = textOf(response, "href") ?: continue
            val path = URLDecoder.decode(URI.create(href).rawPath, Charsets.UTF_8)
            val isDir = element(response, "resourcetype")
                ?.let { element(it, "collection") != null } ?: false
            val size = textOf(response, "getcontentlength")?.toLongOrNull() ?: -1
            val modified = textOf(response, "getlastmodified")?.let(::parseHttpDate)
            val name = path.trimEnd('/').substringAfterLast('/')
            out += path to DirectoryEntry(
                name = name,
                size = if (isDir) -1 else size,
                isDirectory = isDir,
                time = modified?.let { EntryTime(it, TimeAccuracy.SECONDS) },
            )
        }
        return out
    }

    private fun element(parent: Element, localName: String): Element? {
        val nodes = parent.getElementsByTagNameNS(DAV_NS, localName)
        return if (nodes.length > 0) nodes.item(0) as Element else null
    }

    private fun textOf(parent: Element, localName: String): String? =
        element(parent, localName)?.textContent?.trim()?.ifEmpty { null }

    private fun totalFrom(response: HttpResponse<*>, offset: Long): Long? {
        response.headers().firstValue("Content-Range").orElse(null)?.let { range ->
            // bytes start-end/total
            range.substringAfterLast('/').toLongOrNull()?.let { return it }
        }
        return response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
            ?.let { it + offset }
    }

    private companion object {
        const val DAV_NS = "DAV:"
        val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
            |<D:propfind xmlns:D="DAV:"><D:prop>
            |<D:resourcetype/><D:getcontentlength/><D:getlastmodified/>
            |</D:prop></D:propfind>""".trimMargin()
    }

    private fun parseHttpDate(value: String): Long? = runCatching {
        // RFC 1123, the form WebDAV's getlastmodified and HTTP's Last-Modified use.
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        format.parse(value)?.time
    }.getOrNull()

    private fun trustEverything(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), java.security.SecureRandom())
        }
    }
}

/** An HTTP status the WebDAV client could not make sense of. */
class WebDavException(val status: Int, message: String) : java.io.IOException("$message (HTTP $status)")

/** The server ignored a byte-range request, so a download cannot be resumed. */
class WebDavResumeUnsupported : java.io.IOException("the server does not support resuming")

/** Reports how many bytes have been read, for upload progress. */
private class CountingInputStream(
    private val inner: InputStream,
    private val onProgress: (Long) -> Unit,
) : InputStream() {
    private var count = 0L
    override fun read(): Int = inner.read().also { if (it >= 0) report(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        inner.read(b, off, len).also { if (it > 0) report(it.toLong()) }
    private fun report(n: Long) { count += n; onProgress(count) }
    override fun close() = inner.close()
}

// ---- path helpers, shared by url building and listing comparison ----

private fun joinPaths(base: String, relative: String): String {
    val b = base.trim('/')
    val r = relative.trim('/')
    return when {
        b.isEmpty() && r.isEmpty() -> "/"
        b.isEmpty() -> "/$r"
        r.isEmpty() -> "/$b"
        else -> "/$b/$r"
    }
}

private fun normalizePath(path: String): String = "/" + path.trim('/')
private fun ensureLeadingSlash(path: String): String = if (path.startsWith("/")) path else "/$path"
private fun ensureTrailingSlash(path: String): String = if (path.endsWith("/")) path else "$path/"

/** Percent-encodes one path segment, leaving unreserved characters alone. */
private fun encodeSegment(segment: String): String = buildString {
    for (byte in segment.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        if (c.toChar().isUnreserved()) append(c.toChar()) else append("%%%02X".format(c))
    }
}

private fun Char.isUnreserved(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"
