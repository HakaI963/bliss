package com.altaris.linux.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The file browser and the agent identity setting.
 *
 * The browser could only reach internal storage, Downloads and the app's own
 * data — so "full files, not just the download folder" was not true. The
 * sorting and filtering added with it are pure functions and are tested
 * directly.
 */
class FileBrowserAndIdentityTest {

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }

    private fun entry(
        name: String,
        dir: Boolean = false,
        size: Long = 0,
        modified: Long = 0,
    ) = FileBrowser.Entry(
        name = name,
        path = "/x/" + name,
        dir = dir,
        size = size,
        modified = modified,
    )

    /**
     * Two spaces, and only two.
     *
     * The browser used to offer eight places — Internal storage, Downloads,
     * All storage, /data, /system, /vendor, /etc, the filesystem root and one
     * entry per distro — and probed every one of them on open, including an
     * `ls` under root. Internal (the app's own root) and External (the chroot
     * space) are the two a person actually has.
     */
    @Test
    fun theBrowserHasTwoSpaces() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/files/FileBrowser.kt")
        assertTrue("must define Internal", source.contains("INTERNAL(\"Internal\""))
        assertTrue("must define External", source.contains("EXTERNAL(\"External\""))
        assertFalse(
            "the eight-place list must be gone",
            source.contains("fun places(context: Context)"),
        )
        assertTrue(
            "Internal is the app's own root",
            source.contains("filesDir.absolutePath"),
        )
        assertTrue(
            "External is the chroot tree",
            source.contains("ChrootPaths.CHROOTS_DIR"),
        )
    }

    /**
     * Opening the tab must not fork a root shell.
     *
     * The old path called places() on every listing, and places() ran `ls`
     * under root — so the first frame waited on su. A listing must decide its
     * backend from the space it is given, not from the path.
     */
    @Test
    fun listingDecidesTheBackendFromTheSpaceNotThePath() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/files/FileBrowser.kt")
        val fn = source.substringAfter("suspend fun list(")
            .substringBefore("private fun listExternal(")
        assertTrue("must take the space", fn.contains("space: Space"))
        assertTrue(
            "must branch on the space",
            fn.contains("if (space == Space.EXTERNAL)"),
        )
        assertFalse(
            "must not resolve the space by probing the path",
            fn.contains("placeFor("),
        )
    }

    /** Sorting keeps directories first, which is what makes a tree navigable. */
    @Test
    fun sortingKeepsDirectoriesOnTop() {
        val entries = listOf(
            entry("b.txt", size = 10),
            entry("zdir", dir = true),
            entry("a.txt", size = 30),
            entry("adir", dir = true),
        )
        val byName = FileBrowser.sorted(entries, FileBrowser.Sort.NAME)
        assertEquals(listOf("adir", "zdir", "a.txt", "b.txt"), byName.map { it.name })

        val bySize = FileBrowser.sorted(entries, FileBrowser.Sort.SIZE, descending = true)
        // Directories stay on top; within them, and within files, the order is
        // by the chosen key.
        assertEquals(setOf("adir", "zdir"), bySize.take(2).map { it.name }.toSet())
        assertEquals(listOf("a.txt", "b.txt"), bySize.drop(2).map { it.name })
    }

    /** Filtering is a case-insensitive substring match. */
    @Test
    fun filteringMatchesCaseInsensitively() {
        val entries = listOf(
            entry("Game.apk"),
            entry("notes.txt"),
            entry("another.apk"),
        )
        assertEquals(
            listOf("Game.apk", "another.apk"),
            FileBrowser.filtered(entries, "APK").map { it.name },
        )
        assertEquals(
            "an empty query must not filter anything",
            entries,
            FileBrowser.filtered(entries, "   "),
        )
    }

    /** The email is stored locally and validated loosely. */
    @Test
    fun theAgentEmailIsValidatedAndOptional() {
        assertTrue("clearing must always be allowed", com.altaris.linux.core.agent.AgentEmail.looksValid(""))
        assertTrue(com.altaris.linux.core.agent.AgentEmail.looksValid("a@b.co"))
        assertFalse(com.altaris.linux.core.agent.AgentEmail.looksValid("no-at-sign"))
        assertFalse(com.altaris.linux.core.agent.AgentEmail.looksValid("@b.co"))
        assertFalse(com.altaris.linux.core.agent.AgentEmail.looksValid("a@b"))
        assertFalse(com.altaris.linux.core.agent.AgentEmail.looksValid("a@@b.co"))
    }

    /**
     * The agent is told to use the address only for sign-in, and never to
     * invent one.
     */
    @Test
    fun theAgentIsToldHowToUseTheEmail() {
        val mcp = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt"
        )
        assertTrue(
            "must expose the identity tool",
            mcp.contains("\"altaris_identity\""),
        )
        val flat = mcp.replace(Regex("\\s+"), " ")
        assertTrue(
            "must say the browser is Chromium",
            flat.contains("Chromium"),
        )
        assertTrue(
            "must forbid inventing an address",
            flat.contains("Never invent an address"),
        )
        assertTrue(
            "must scope it to sign-in forms",
            flat.contains("sign-in form"),
        )
    }

}
