package org.filezilla.ftp.webdav

import org.filezilla.ftp.journal.RemoteFingerprint
import org.filezilla.ftp.listing.DirectoryEntry
import org.filezilla.ftp.listing.EntryTime
import org.filezilla.ftp.listing.TimeAccuracy
import org.filezilla.ftp.net.CertificateNotTrusted
import org.filezilla.ftp.net.TlsFactory
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A WebDAV client: the HTTP verbs the app's browsing and transfers are built on.
 *
 * Deliberately in `core-ftp`, plain Kotlin, beside the FTP and SFTP engines, and
 * proven the same way they are -- against a real server (Apache `mod_dav`),
 * not a stub. Everything the app does to a server is here as one HTTP request
 * each: PROPFIND to list, GET (with a byte range, so a download resumes) to
 * fetch, PUT to send, MKCOL/DELETE/MOVE to make, remove and rename, HEAD for a
 * file's size and time. Paths are relative to [WebDavSettings.basePath]; this
 * splices them onto the origin and encodes them.
 *
 * The transport is a plain [Socket], upgraded to TLS by the same [TlsFactory]
 * the FTP control connection uses, rather than the JDK's `java.net.http` client
 * -- which Android does not ship. HTTP/1.1 is spoken by hand: one request per
 * connection (`Connection: close`), headers written as text and the reply's
 * status, headers and body read back. Keeping to the socket layer is also what
 * lets WebDAV trust a certificate exactly as FTPS does -- by [TlsFactory]'s
 * [org.filezilla.ftp.net.PinningTrustManager], recognising one certificate
 * rather than waving every certificate through.
 *
 * Blocking, like the FTP session: a caller runs one request and waits.
 */
class WebDavClient(private val settings: WebDavSettings) {

    private val auth = "Basic " + Base64.getEncoder()
        .encodeToString("${settings.user}:${settings.password}".toByteArray())

    /**
     * The TLS for an `https://` server, pinned exactly as FTPS is. Kept so a
     * failed handshake can say which certificate was refused -- the
     * `CertificateException` a trust manager throws does not always survive the
     * trip out through the socket, so [TlsFactory] records it and it is read
     * back here. Unused for a plain `http://` server.
     */
    private val tls = TlsFactory(pinnedCertificate = settings.pinnedFingerprint)

    // ------------------------------------------------------------- browse

    /** Whether the server answers as a WebDAV server at the base path. */
    fun connect() {
        execute("OPTIONS", "").use { response ->
            response.drain()
            if (response.status == 401) throw WebDavException(401, "login refused")
            if (response.status !in 200..399) {
                throw WebDavException(response.status, "server did not answer as WebDAV")
            }
        }
    }

    /** The immediate children of [dirPath] (relative to the base). */
    fun list(dirPath: String): List<DirectoryEntry> {
        val body = execute(
            method = "PROPFIND",
            relativePath = ensureTrailingSlash(dirPath),
            headers = mapOf("Depth" to "1", "Content-Type" to "application/xml"),
            requestBody = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
        ).use { response ->
            if (response.status == 404) throw WebDavException(404, "no such folder")
            if (response.status != 207) {
                response.drain()
                throw WebDavException(response.status, "could not list the folder")
            }
            response.bodyBytes()
        }
        // The folder itself comes back in the listing as the first response;
        // drop it, keep its children. Matched by comparing paths, not by order.
        val here = normalizePath(joinPaths(settings.basePath, dirPath))
        return parseListing(body).filterNot { (path, _) ->
            normalizePath(path).trimEnd('/') == here.trimEnd('/')
        }.map { it.second }
    }

    /** A file's size and time, or null when it is not there. */
    fun info(path: String): DirectoryEntry? {
        val body = execute(
            method = "PROPFIND",
            relativePath = path,
            headers = mapOf("Depth" to "0", "Content-Type" to "application/xml"),
            requestBody = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
        ).use { response ->
            if (response.status == 404) return null
            if (response.status != 207) {
                response.drain()
                throw WebDavException(response.status, "could not read the entry")
            }
            response.bodyBytes()
        }
        return parseListing(body).firstOrNull()?.second
    }

    fun makeCollection(path: String) {
        execute("MKCOL", path).use { response ->
            response.drain()
            if (response.status !in 200..201 && response.status != 405) {
                throw WebDavException(response.status, "could not make the folder")
            }
        }
    }

    fun delete(path: String) {
        val code = sendDelete(path)
        if (code in 200..204 || code == 404) return
        // As with MOVE, a collection's slash-less URL answers DELETE with a 301
        // to the trailing-slash form; retry naming it as a collection.
        if (code == 301) {
            val retry = sendDelete(ensureTrailingSlash(path))
            if (retry in 200..204 || retry == 404) return
            throw WebDavException(retry, "could not delete")
        }
        throw WebDavException(code, "could not delete")
    }

    private fun sendDelete(path: String): Int =
        execute("DELETE", path).use { it.drain(); it.status }

    fun move(fromPath: String, toPath: String) {
        val code = sendMove(fromPath, toPath)
        if (code in 200..204) return
        // A collection answers MOVE of its slash-less URL with a 301 to the
        // trailing-slash form, just as PROPFIND does; retry naming both ends as
        // collections so a folder renames as readily as a file.
        if (code == 301) {
            val retry = sendMove(ensureTrailingSlash(fromPath), ensureTrailingSlash(toPath))
            if (retry in 200..204) return
            throw WebDavException(retry, "could not rename")
        }
        throw WebDavException(code, "could not rename")
    }

    private fun sendMove(fromPath: String, toPath: String): Int =
        execute(
            method = "MOVE",
            relativePath = fromPath,
            headers = mapOf("Destination" to absoluteUrl(toPath), "Overwrite" to "T"),
        ).use { it.drain(); it.status }

    fun fingerprint(path: String): RemoteFingerprint = execute("HEAD", path).use { response ->
        response.drain()
        val size = response.header("Content-Length")?.toLongOrNull()
        val modified = response.header("Last-Modified")?.let(::parseHttpDate)
        RemoteFingerprint(size = size, modifiedMillis = modified)
    }

    // ---------------------------------------------------------- transfer

    /**
     * Streams [path] to [out], starting at [offset] so a half-finished download
     * resumes. Returns the total size when the server gave one. A server that
     * ignores the range (answers 200 to a ranged request) is reported, so the
     * caller can start the file over rather than append to its own middle.
     */
    fun download(path: String, offset: Long, out: OutputStream, onProgress: (Long) -> Unit): Long? {
        val headers = if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap()
        return execute("GET", path, headers = headers).use { response ->
            val code = response.status
            if (code == 404) throw WebDavException(404, "no such file")
            if (offset > 0 && code == 200) throw WebDavResumeUnsupported()
            if (code != 200 && code != 206) {
                response.drain()
                throw WebDavException(code, "could not fetch the file")
            }

            var done = offset
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = response.body.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                done += read
                onProgress(done)
            }
            // 206 carries the whole size as the end of Content-Range; 200 as length.
            totalFrom(response, offset)
        }
    }

    /** Sends [length] bytes from [input] to [path], replacing what is there. */
    fun upload(path: String, input: InputStream, length: Long, onProgress: (Long) -> Unit) {
        execute(
            method = "PUT",
            relativePath = path,
            headers = mapOf("Content-Type" to "application/octet-stream"),
            contentLength = length,
            writeBody = { out ->
                var sent = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    sent += read
                    onProgress(sent)
                }
            },
        ).use { response ->
            response.drain()
            if (response.status !in 200..204) throw WebDavException(response.status, "could not upload")
        }
    }

    // ------------------------------------------------------------ transport

    /**
     * Runs one request to its reply. Opens a fresh connection, writes the
     * request line, headers and body, then reads the status and headers back
     * and hands the caller the open [HttpReply] -- whose [HttpReply.body] is the
     * decoded response body and whose [HttpReply.close] shuts the socket. The
     * body is either sent as [requestBody] bytes or written by [writeBody] (for
     * a stream whose length is [contentLength]); not both.
     */
    private fun execute(
        method: String,
        relativePath: String,
        headers: Map<String, String> = emptyMap(),
        requestBody: ByteArray? = null,
        contentLength: Long? = null,
        writeBody: ((OutputStream) -> Unit)? = null,
    ): HttpReply {
        val socket = openSocket()
        try {
            val out = socket.getOutputStream()
            val head = StringBuilder()
            head.append(method).append(' ').append(target(relativePath)).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(hostHeader()).append("\r\n")
            head.append("Authorization: ").append(auth).append("\r\n")
            head.append("Connection: close\r\n")
            val length = contentLength ?: requestBody?.size?.toLong()
            if (length != null) head.append("Content-Length: ").append(length).append("\r\n")
            headers.forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
            head.append("\r\n")
            // The request line and header values are the encoded ASCII path and
            // ASCII header text, so a byte-per-character encoding is exact.
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (requestBody != null) out.write(requestBody)
            writeBody?.invoke(out)
            out.flush()
            return readReply(socket, method)
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }
    }

    /**
     * A connected transport socket, TLS for `https` and plain otherwise. A
     * certificate the pinning trust manager refuses surfaces here as a
     * [CertificateNotTrusted], the WebDAV parallel of
     * [org.filezilla.ftp.protocol.FtpControlConnection]'s handshake handling.
     */
    private fun openSocket(): Socket {
        val plain = Socket()
        plain.connect(InetSocketAddress(settings.host, settings.port), CONNECT_TIMEOUT_MS)
        plain.soTimeout = READ_TIMEOUT_MS
        plain.tcpNoDelay = true
        if (!settings.https) return plain
        return try {
            tls.upgradeControl(plain, settings.host, settings.port)
        } catch (failed: IOException) {
            runCatching { plain.close() }
            val refused = tls.trust.refusalFor(failed) ?: throw failed
            throw CertificateNotTrusted(refused.certificate, refused.previouslyTrusted, failed)
        }
    }

    /** The `Host` header: the host, and the port when it is not the default. */
    private fun hostHeader(): String {
        val default = if (settings.https) 443 else 80
        return if (settings.port == default) settings.host else "${settings.host}:${settings.port}"
    }

    private fun readReply(socket: Socket, method: String): HttpReply {
        val input = BufferedInputStream(socket.getInputStream())
        val statusLine = readHeaderLine(input) ?: throw IOException("the server sent no reply")
        // "HTTP/1.1 207 Multi-Status" -- the code is the second word.
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("the server's reply did not start with a status: $statusLine")

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readHeaderLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                    line.substring(colon + 1).trim()
            }
        }

        // HEAD and the empty statuses carry no body however the headers read.
        val bodyless = method == "HEAD" || status == 204 || status == 304 || status in 100..199
        val body: InputStream = when {
            bodyless -> EmptyInputStream
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                ChunkedInputStream(input)
            headers["content-length"] != null ->
                LengthLimitedInputStream(input, headers.getValue("content-length").toLong())
            // No length and not chunked: the body runs to the close we asked for.
            else -> input
        }
        return HttpReply(status, headers, body, socket)
    }

    /** One header line, read to its CRLF and returned without it; null at EOF. */
    private fun readHeaderLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var any = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (any) buffer.toString("ISO-8859-1") else null
            any = true
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.ISO_8859_1)
            }
            buffer.write(b)
        }
    }

    // ---------------------------------------------------- paths & encoding

    /** The request target: the path on the server, each segment encoded. */
    private fun target(relativePath: String): String {
        // A collection must keep its trailing slash: Apache mod_dav answers a
        // PROPFIND of "/work" with a 301 to "/work/", so the slash is carried
        // through rather than trimmed away with the rest.
        val trailing = relativePath.endsWith("/")
        val joined = joinPaths(settings.basePath, relativePath)
        val full = if (trailing && joined != "/") "$joined/" else joined
        val encoded = full.split('/').joinToString("/") { encodeSegment(it) }
        return ensureLeadingSlash(encoded)
    }

    /** The absolute URL for a path, as MOVE's `Destination` header needs it. */
    private fun absoluteUrl(relativePath: String): String = settings.origin + target(relativePath)

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
            val path = percentDecode(URI.create(href).rawPath)
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

    private fun totalFrom(response: HttpReply, offset: Long): Long? {
        response.header("Content-Range")?.let { range ->
            // bytes start-end/total
            range.substringAfterLast('/').toLongOrNull()?.let { return it }
        }
        return response.header("Content-Length")?.toLongOrNull()?.let { it + offset }
    }

    private companion object {
        const val DAV_NS = "DAV:"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
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
}

/** An HTTP status the WebDAV client could not make sense of. */
class WebDavException(val status: Int, message: String) : java.io.IOException("$message (HTTP $status)")

/** The server ignored a byte-range request, so a download cannot be resumed. */
class WebDavResumeUnsupported : java.io.IOException("the server does not support resuming")

/**
 * One reply, and the socket it is being read from.
 *
 * [body] is the decoded response body -- chunk markers and the connection's own
 * framing taken off -- and [close] shuts the socket, so a caller wraps the whole
 * thing in `use`. [drain] is for the replies whose body is not wanted but has to
 * be read off before the socket is let go.
 */
private class HttpReply(
    val status: Int,
    private val headers: Map<String, String>,
    val body: InputStream,
    private val socket: Socket,
) : Closeable {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    fun bodyBytes(): ByteArray = body.readBytes()

    fun drain() {
        runCatching { body.readBytes() }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** A body of no bytes, for HEAD and the statuses that carry none. */
private object EmptyInputStream : InputStream() {
    override fun read(): Int = -1
}

/** The next [remaining] bytes of [inner], and no more. */
private class LengthLimitedInputStream(
    private val inner: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = inner.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val read = inner.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }
}

/**
 * A `Transfer-Encoding: chunked` body, unwrapped to its bytes.
 *
 * Each chunk is a hex length on its own line, that many bytes, then a CRLF; a
 * zero length ends the body. The trailer after it is read off and dropped.
 */
private class ChunkedInputStream(private val inner: InputStream) : InputStream() {
    private var remaining = 0L
    private var ended = false

    override fun read(): Int {
        if (!advance()) return -1
        val b = inner.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!advance()) return -1
        val read = inner.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }

    /** Ensures a chunk is open with bytes left; false once the body is done. */
    private fun advance(): Boolean {
        if (ended) return false
        if (remaining > 0) return true
        // Between chunks: a size line, or the CRLF that closed the last one
        // then the size line.
        var line = readLine()
        if (line.isEmpty()) line = readLine()
        val size = line.substringBefore(';').trim().toLongOrNull(16)
            ?: throw IOException("the server sent a bad chunk size: '$line'")
        if (size == 0L) {
            ended = true
            // Read the trailer off, up to and including its blank line.
            while (readLine().isNotEmpty()) { /* drop trailer headers */ }
            return false
        }
        remaining = size
        return true
    }

    private fun readLine(): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = inner.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
        }
        return buffer.toString("ISO-8859-1")
    }
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

/**
 * Decodes a URL path's percent-escapes, and only those.
 *
 * A path is not a query string: `+` is a literal plus (a legal path character),
 * not an encoded space. `java.net.URLDecoder` applies form rules and turns `+`
 * into a space, which renamed any listed file whose name contained `+` and then
 * 404'd when it was fetched back. This decodes `%XX` and leaves everything else,
 * `+` included, exactly as the server sent it.
 */
private fun percentDecode(path: String): String {
    if ('%' !in path) return path
    val bytes = java.io.ByteArrayOutputStream(path.length)
    var i = 0
    while (i < path.length) {
        val c = path[i]
        if (c == '%' && i + 2 < path.length) {
            val hi = Character.digit(path[i + 1], 16)
            val lo = Character.digit(path[i + 2], 16)
            if (hi >= 0 && lo >= 0) {
                bytes.write((hi shl 4) or lo)
                i += 3
                continue
            }
        }
        // A non-escape character: its UTF-8 bytes pass through unchanged.
        bytes.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return bytes.toString("UTF-8")
}
