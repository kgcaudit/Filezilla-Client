package org.filezilla.ftp.protocol

/**
 * A server reply as defined by RFC 959 section 4.2.
 *
 * [code] is the three digit reply code and [text] is the first line's text.
 * [lines] holds every line of the reply, including the first, so callers that
 * need the body of a multi-line reply (`FEAT`, `SYST`) can read it.
 */
data class FtpReply(
    val code: Int,
    val text: String,
    val lines: List<String>,
) {
    /** The leading digit, which is what most of the engine branches on. */
    val category: Int get() = code / 100

    val isPositivePreliminary: Boolean get() = category == 1
    val isPositiveCompletion: Boolean get() = category == 2
    val isPositiveIntermediate: Boolean get() = category == 3

    /**
     * FileZilla accepts both 2yz and 3yz wherever it checks for success, so
     * this mirrors `code != 2 && code != 3` in the C++ source.
     */
    val isSuccess: Boolean get() = category == 2 || category == 3

    /** The whole reply joined back together, for logging. */
    val raw: String get() = lines.joinToString("\n")

    companion object {
        /**
         * True when [line] opens a multi-line reply, i.e. `250-` rather than
         * `250 `. A line shorter than four characters cannot be one.
         */
        fun isMultilineStart(line: String): Boolean =
            line.length >= 4 && line[3] == '-' && line.take(3).all { it.isDigit() }

        /**
         * True when [line] terminates a multi-line reply opened with [tag],
         * i.e. it starts with the same code followed by a space.
         */
        fun isMultilineEnd(line: String, tag: String): Boolean =
            line.length >= 4 && line.startsWith(tag) && line[3] == ' '

        /** Parses an already-collected reply. [lines] must not be empty. */
        fun of(lines: List<String>): FtpReply {
            require(lines.isNotEmpty()) { "a reply needs at least one line" }
            val first = lines.first()
            val code = first.take(3).toIntOrNull()
                ?: throw FtpProtocolException("malformed reply, no status code: $first")
            return FtpReply(code, first.drop(4).trim(), lines)
        }
    }
}

/** Raised when the server says something that cannot be understood at all. */
class FtpProtocolException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
