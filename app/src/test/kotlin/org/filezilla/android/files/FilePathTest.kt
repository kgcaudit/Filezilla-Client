package org.filezilla.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The string handling both panes walk folders with.
 *
 * Worth its own tests because every case that breaks it is an edge: the root,
 * a trailing slash, a doubled separator, a `..` from a server that should not
 * be obeyed. None of them come up while writing the happy path, and each of
 * them is a pane that walks somewhere the user did not ask for.
 */
class FilePathTest {

    @Test
    fun `a child of the root has one separator, not two`() {
        assertEquals("/Download", FilePath.child("/", "Download"))
    }

    @Test
    fun `a child of a folder is appended`() {
        assertEquals("/storage/Download", FilePath.child("/storage", "Download"))
    }

    /** A path that already ends in a slash must not produce a doubled one. */
    @Test
    fun `a trailing slash does not double up`() {
        assertEquals("/storage/Download", FilePath.child("/storage/", "Download"))
    }

    @Test
    fun `an empty directory is treated as the root`() {
        assertEquals("/a", FilePath.child("", "a"))
    }

    // ------------------------------------------------------------- parent

    @Test
    fun `the parent of a nested folder is the one above it`() {
        assertEquals("/storage/emulated", FilePath.parent("/storage/emulated/0"))
    }

    @Test
    fun `the parent of a top-level folder is the root`() {
        assertEquals("/", FilePath.parent("/storage"))
    }

    /**
     * Null, not the root again. Returning the root would make "up" a button
     * that never stops working, and a loop that walks up forever.
     */
    @Test
    fun `the root has no parent`() {
        assertNull(FilePath.parent("/"))
        assertNull(FilePath.parent(""))
    }

    @Test
    fun `a trailing slash does not hide the parent`() {
        assertEquals("/storage", FilePath.parent("/storage/emulated/"))
    }

    // --------------------------------------------------------------- name

    @Test
    fun `the name is the last segment`() {
        assertEquals("Download", FilePath.name("/storage/emulated/0/Download"))
        assertEquals("Download", FilePath.name("/storage/emulated/0/Download/"))
        assertEquals("/", FilePath.name("/"))
    }

    // ---------------------------------------------------------- normalize

    @Test
    fun `doubled and trailing separators collapse`() {
        assertEquals("/a/b", FilePath.normalize("//a//b/"))
    }

    @Test
    fun `a relative path becomes absolute`() {
        assertEquals("/a/b", FilePath.normalize("a/b"))
    }

    @Test
    fun `a dot segment goes away`() {
        assertEquals("/a/b", FilePath.normalize("/a/./b"))
    }

    /**
     * A remote listing is not ours to trust. A server that answers with an
     * entry named ".." must not be able to walk a transfer out of the folder
     * the user picked, so this is resolved here rather than left to whatever
     * opens the file later.
     */
    @Test
    fun `a dot-dot segment removes the one before it`() {
        assertEquals("/a", FilePath.normalize("/a/b/.."))
        assertEquals("/c", FilePath.normalize("/a/b/../../c"))
    }

    @Test
    fun `dot-dot cannot climb above the root`() {
        assertEquals("/", FilePath.normalize("/../.."))
        assertEquals("/a", FilePath.normalize("/../a"))
    }

    // ----------------------------------------------------------- segments

    @Test
    fun `segments are the parts, top first`() {
        assertEquals(listOf("storage", "emulated", "0"), FilePath.segments("/storage/emulated/0"))
    }

    @Test
    fun `the root has no segments`() {
        assertEquals(emptyList<String>(), FilePath.segments("/"))
    }

    // ----------------------------------------------------------- isWithin

    @Test
    fun `a folder is within itself and within its parents`() {
        assertTrue(FilePath.isWithin("/a/b", "/a/b"))
        assertTrue(FilePath.isWithin("/a/b", "/a"))
        assertTrue(FilePath.isWithin("/a/b", "/"))
    }

    @Test
    fun `a parent is not within its child`() {
        assertFalse(FilePath.isWithin("/a", "/a/b"))
    }

    /**
     * The reason this compares segments rather than prefixes: "Downloads"
     * starts with "Down", and a prefix test would call one a child of the
     * other -- which, once this guards a move, silently allows moving a
     * folder into itself.
     */
    @Test
    fun `a name that merely starts the same is not within`() {
        assertFalse(FilePath.isWithin("/storage/Downloads", "/storage/Down"))
    }
}
