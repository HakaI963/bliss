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

    /** The whole device is browsable, not only the download folder. */
    @Test
    fun theBrowserReachesTheWholeDevice() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/files/FileBrowser.kt")
        val places = source.substringAfter("fun places(context: Context)")
            .substringBefore("private fun addIfReadable(")
        for (root in listOf("/storage", "/data", "/system", "/vendor")) {
            assertTrue("must offer $root", places.contains("\"$root\""))
        }
        assertTrue(
            "must offer the filesystem root when root is available",
            places.contains("Place(\"root\", \"Root /\"") ||
                places.contains("\"Root /\"") ||
                places.contains("\"root\""),
        )
    }

    /**
     * It must open on internal storage, not Downloads.
     *
     * Opening in Downloads made the browser look like it only handled
     * downloads, which is exactly the report.
     */
    @Test
    fun theBrowserOpensOnInternalStorage() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/files/FileBrowser.kt")
        val fn = source.substringAfter("fun defaultPath(context: Context)")
            .substringBefore("enum class Sort")
        val sdcard = fn.indexOf("\"sdcard\"")
        val download = fn.indexOf("\"download\"")
        assertTrue("must consider internal storage", sdcard >= 0)
        assertTrue(
            "internal storage must win over Downloads",
            download < 0 || sdcard < download,
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
