package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The REA file manager's path and performance rules.
 *
 * The listing runs in the guest, but the two things that decide whether a
 * delete is safe — path normalisation and the root guard — are pure.
 */
class ReaFilesTest {

    @Test
    fun pathsAreAbsoluteAndCollapseDots() {
        assertEquals("/root/a", ReaFiles.normalize("root/a"))
        assertEquals("/root/a", ReaFiles.normalize("/root//a"))
        assertEquals("/root/a", ReaFiles.normalize("/root/./a"))
        assertEquals("/", ReaFiles.normalize(""))
        assertEquals("/", ReaFiles.normalize("/"))
    }

    /** `..` resolves but can never escape the root. */
    @Test
    fun dotDotCannotEscapeTheRoot() {
        assertEquals("/root", ReaFiles.normalize("/root/a/.."))
        assertEquals("/root/b", ReaFiles.normalize("/root/a/../b"))
        assertEquals("/", ReaFiles.normalize("/../../.."))
        assertEquals("/", ReaFiles.normalize("../../../etc/passwd/../../.."))
        assertTrue(ReaFiles.normalize("../../etc/shadow").startsWith("/"))
    }

    @Test
    fun parentOfWalksUpToTheRootAndStops() {
        assertEquals("/root", ReaFiles.parentOf("/root/sub"))
        assertEquals("/", ReaFiles.parentOf("/root"))
        assertEquals("/", ReaFiles.parentOf("/"))
    }

    @Test
    fun textAndBinaryFilesAreClassified() {
        assertTrue(ReaFiles.isTextPath("/root/notes.md"))
        assertTrue(ReaFiles.isTextPath("/root/a.smali"))
        assertTrue(ReaFiles.isTextPath("/root/.env"))
        assertTrue(ReaFiles.isBinaryPath("/root/app.apk"))
        assertTrue(ReaFiles.isBinaryPath("/root/lib.so"))
        assertTrue(ReaFiles.isBinaryPath("/root/classes.dex"))
    }

    /**
     * The performance rule: a listing is one `find`, not one process per entry.
     * The first implementation spawned `wc` and `date` for every file.
     */
    @Test
    fun theListingUsesASinglePrintfPass() {
        val script = ReaFiles.listingScriptForTest("/root")
        assertEquals("exactly one find invocation", 1, Regex("find ").findAll(script).count())
        assertTrue("uses -printf", script.contains("-printf"))
        assertTrue("bounded depth", script.contains("-maxdepth 1"))
        assertTrue("no wc per entry", !script.contains("wc -c"))
        assertTrue("no date per entry", !script.contains("date -r"))
    }
}
