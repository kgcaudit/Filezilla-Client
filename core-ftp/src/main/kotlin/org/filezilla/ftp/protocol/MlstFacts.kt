package org.filezilla.ftp.protocol

/**
 * Asking an `MLSD` server for the facts the app actually shows.
 *
 * `MLSD` does not send everything it knows. RFC 3659 has the server
 * advertise its facts in `FEAT` with an asterisk on the ones currently
 * switched on, and the client turn the rest on with `OPTS MLST`. Most
 * servers switch on `type`, `size`, `modify` and `perm` and leave the unix
 * facts off.
 *
 * Which is why the properties dialog said "unknown" where permissions and
 * owner should be, on servers that knew both: nothing ever asked. `perm`,
 * the one fact that does arrive, is not the file's permissions at all --
 * it lists what this login may do with it -- so there was nothing to fall
 * back on either.
 *
 *     FEAT ->  MLST type*;size*;modify*;perm*;unix.mode;unix.uid;unix.gid;
 *     OPTS MLST type;size;modify;perm;unix.mode;unix.owner;unix.group;
 *
 * Only ever asks for facts the server listed. A server that is sent a fact
 * it does not have answers 501 and, on some implementations, leaves the
 * whole set at the default -- so asking for more than was offered can cost
 * the facts that were already arriving.
 */
object MlstFacts {

    /**
     * What is worth asking for, in the order FileZilla asks.
     *
     * `unix.mode` is the permissions. The owner and group come under two
     * spellings each -- the name and the numeric id -- and servers differ
     * in which they have, so both are asked for and whichever arrives is
     * shown.
     */
    val WANTED = listOf(
        "type",
        "size",
        "modify",
        "perm",
        "unix.mode",
        "unix.owner",
        "unix.group",
        "unix.uid",
        "unix.gid",
    )

    /**
     * The argument for `OPTS MLST`, or null when there is nothing to gain.
     *
     * Null means the server offered nothing beyond what it already sends,
     * so the command would be a round trip that changes nothing.
     */
    fun optsArgumentFor(featureOption: String?): String? {
        val offered = factsIn(featureOption)
        if (offered.isEmpty()) return null

        val asking = WANTED.filter { wanted -> offered.keys.any { it.equals(wanted, true) } }
        if (asking.isEmpty()) return null

        // Already on, all of it. Some servers reset unlisted facts when
        // OPTS MLST is sent, so a command that can only take things away
        // is not worth sending.
        val alreadyOn = asking.all { wanted ->
            offered.entries.first { it.key.equals(wanted, true) }.value
        }
        if (alreadyOn) return null

        return asking.joinToString(";", postfix = ";")
    }

    /**
     * The facts a `FEAT` line offered, and whether each is already on.
     *
     * The line arrives as `MLST type*;size*;modify*;perm*;unix.mode;` --
     * the word MLST is already stripped by the time this sees it -- and an
     * asterisk marks a fact the server is sending without being asked.
     */
    fun factsIn(featureOption: String?): Map<String, Boolean> {
        val text = featureOption?.trim().orEmpty()
        if (text.isEmpty()) return emptyMap()
        return text.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate { it.removeSuffix("*") to it.endsWith("*") }
    }
}
