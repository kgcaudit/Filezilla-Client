package org.filezilla.ftp.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Asking for the facts the app shows, and not asking for anything else.
 *
 * The rule that matters is the negative one. Some servers reset every
 * fact not named in `OPTS MLST`, so a command asking for a fact the server
 * does not have can cost the ones that were already arriving -- which
 * would turn "permissions are missing" into "the date is missing too".
 */
class MlstFactsTest {

    @Test
    fun `asks for the unix facts a server offers`() {
        val asking = MlstFacts.optsArgumentFor("type*;size*;modify*;perm*;unix.mode;unix.uid;unix.gid;")

        assertEquals("type;size;modify;perm;unix.mode;unix.uid;unix.gid;", asking)
    }

    @Test
    fun `never asks for a fact that was not offered`() {
        // size is off, so there is something to ask for -- and what is
        // asked for must still contain nothing the server did not list. A
        // 501 here can take away the facts that were already arriving.
        val asking = MlstFacts.optsArgumentFor("type*;size;modify*;")

        assertEquals("type;size;modify;", asking)
        assertTrue(
            asking!!.split(";").none { it.startsWith("unix.") },
            "asked for a unix fact the server never offered",
        )
    }

    @Test
    fun `says nothing when everything wanted is already on`() {
        // A round trip that can only change things for the worse.
        assertNull(MlstFacts.optsArgumentFor("type*;size*;modify*;perm*;"))
        assertNull(MlstFacts.optsArgumentFor("type*;size*;modify*;perm*;unix.mode*;unix.owner*;unix.group*;unix.uid*;unix.gid*;"))
    }

    @Test
    fun `asks when one wanted fact is off`() {
        assertEquals(
            "type;size;modify;perm;unix.mode;",
            MlstFacts.optsArgumentFor("type*;size*;modify*;perm*;unix.mode;"),
        )
    }

    @Test
    fun `a server that offers no facts is not sent anything`() {
        assertNull(MlstFacts.optsArgumentFor(null))
        assertNull(MlstFacts.optsArgumentFor(""))
        assertNull(MlstFacts.optsArgumentFor("   "))
    }

    @Test
    fun `an offer of facts we do not want is not worth a command`() {
        assertNull(MlstFacts.optsArgumentFor("media-type;charset;"))
    }

    @Test
    fun `the asterisk says which are already on`() {
        val facts = MlstFacts.factsIn("type*;size*;modify*;perm*;unix.mode;unix.uid;")

        assertEquals(true, facts["type"])
        assertEquals(false, facts["unix.mode"])
        assertEquals(6, facts.size)
    }

    @Test
    fun `case and spacing in a FEAT line do not change the answer`() {
        // Servers write this line by hand and they do not all agree.
        assertEquals(
            "type;size;modify;perm;unix.mode;",
            MlstFacts.optsArgumentFor(" Type*; Size*; Modify*; Perm*; UNIX.mode; "),
        )
    }
}
