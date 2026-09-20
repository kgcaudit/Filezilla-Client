package org.filezilla.ftp.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The arithmetic behind making a folder before writing into it.
 *
 * The bug: uploading a folder spliced the folders a file sat in back into
 * its remote path and never made them, so every file below the top level
 * came back `550 ... No such file or directory`, three attempts each, and
 * the queue filled with failures that all named a folder that was never
 * there.
 */
class RemoteDirectoriesTest {

    @Test
    fun `a file's folders are listed from the top down`() {
        assertEquals(
            listOf("/HDD1", "/HDD1/Vision", "/HDD1/Vision/test"),
            RemoteDirectories.ancestorsOf("/HDD1/Vision/test/film.mkv"),
        )
    }

    @Test
    fun `a file at the root has no folders above it`() {
        assertEquals(emptyList<String>(), RemoteDirectories.ancestorsOf("/film.mkv"))
    }

    @Test
    fun `a bare name has none either`() {
        assertEquals(emptyList<String>(), RemoteDirectories.ancestorsOf("film.mkv"))
    }

    /** A relative path stays relative; prefixing it would move the upload. */
    @Test
    fun `a relative path is not made absolute`() {
        assertEquals(
            listOf("a", "a/b"),
            RemoteDirectories.ancestorsOf("a/b/film.mkv"),
        )
    }

    @Test
    fun `nothing is made when the folder is already there`() {
        val asked = mutableListOf<String>()
        val plan = RemoteDirectories.plan("/HDD1/Vision/test/film.mkv") {
            asked += it
            true
        }
        assertEquals(emptyList<String>(), plan)
        // One question, about the folder the file goes in. Walking the whole
        // path would cost a round trip per level on every single upload.
        assertEquals(listOf("/HDD1/Vision/test"), asked)
    }

    @Test
    fun `only what is missing is made, from the top down`() {
        val there = setOf("/HDD1", "/HDD1/Vision")
        val plan = RemoteDirectories.plan("/HDD1/Vision/a/b/film.mkv") { it in there }

        // Shallowest first: MKD will not make a folder whose parent is
        // missing either, so the order is part of the answer.
        assertEquals(listOf("/HDD1/Vision/a", "/HDD1/Vision/a/b"), plan)
    }

    @Test
    fun `a server that agrees with nothing gets the whole chain`() {
        assertEquals(
            listOf("/a", "/a/b", "/a/b/c"),
            RemoteDirectories.plan("/a/b/c/film.mkv") { false },
        )
    }

    /** The Korean path from the failure the user reported. */
    @Test
    fun `a path with non-ascii folders is split the same way`() {
        val there = setOf("/HDD1", "/HDD1/Vision", "/HDD1/Vision/안녕")
        assertEquals(
            listOf("/HDD1/Vision/안녕/Vision", "/HDD1/Vision/안녕/Vision/test"),
            RemoteDirectories.plan("/HDD1/Vision/안녕/Vision/test/film.mkv") { it in there },
        )
    }
}
