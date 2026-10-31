package com.altaris.linux.core.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent discovery rules.
 *
 * The regression these lock down: a fixed allowlist of eleven names plus a
 * direct `File.list()` made every agent in a chroot and every agent with a
 * name the list did not know invisible. "No agents detected at all" is the
 * worst failure mode for a master-of-agents screen, so the *rules* are tested
 * even though the filesystem walk needs a device.
 */
class AgentDetectorTest {

    /**
     * The real agents the user named must be found.
     */
    @Test
    fun knownAgentsAreAccepted() {
        for (name in listOf(
            "opencode", "claude", "codex", "gemini", "aider", "crush", "goose",
            "kimi", "qwen", "droid", "amp", "auggie", "cursor-agent", "copilot",
        )) {
            assertTrue("$name must be detected", AgentDetector.isAgentNameForTest(name))
        }
    }

    /**
     * Agent-shaped names are still accepted, so a new release does not need an
     * app update.
     */
    @Test
    fun agentShapedNamesAreAccepted() {
        assertTrue(AgentDetector.isAgentNameForTest("some-brand-new-agent"))
        assertTrue(AgentDetector.isAgentNameForTest("qwen-code"))
        assertTrue(AgentDetector.isAgentNameForTest("gemini-cli"))
    }

    /**
     * The regression the user reported: distribution plumbing was listed as an
     * agent because "not a known system binary" was the only filter.
     */
    @Test
    fun distributionPlumbingIsRejected() {
        for (name in listOf(
            "addpart", "archive_colour", "zdump", "resizepart", "delpart",
            "partx", "lsblk", "swaplabel", "wipefs", "cal", "col", "colcrt",
            "colrm", "column", "rev", "look", "sponge", "shred", "expand",
            "unexpand", "fmt", "fold", "nl", "pr", "ptx", "tsort",
        )) {
            assertFalse("$name must not be listed as an agent", AgentDetector.isAgentNameForTest(name))
        }
    }

    /** System binaries must not fill the picker. */
    @Test
    fun systemBinariesAreRejected() {
        for (name in listOf(
            "ls", "cat", "grep", "sed", "awk", "sh", "bash", "python3", "node",
            "npm", "git", "curl", "wget", "tar", "gzip", "file", "jq", "java",
            "javac", "apktool", "jadx", "adb", "proot", "busybox", "gdb",
        )) {
            assertFalse("$name must not be listed as an agent", AgentDetector.isAgentNameForTest(name))
        }
    }

    /** Version-suffixed runtime binaries are not agents either. */
    @Test
    fun versionSuffixedRuntimesAreRejected() {
        assertFalse(AgentDetector.isAgentNameForTest("python3.12"))
        assertFalse(AgentDetector.isAgentNameForTest("node18"))
        assertFalse(AgentDetector.isAgentNameForTest("pip3.11"))
    }

    /** Shared objects and packaging noise are not executables a user chats with. */
    @Test
    fun librariesAndMetadataAreRejected() {
        assertFalse(AgentDetector.isAgentNameForTest("libfoo.so.1"))
        assertFalse(AgentDetector.isAgentNameForTest("module.pyc"))
        assertFalse(AgentDetector.isAgentNameForTest("pkg-config"))
        assertFalse(AgentDetector.isAgentNameForTest(""))
        assertFalse(AgentDetector.isAgentNameForTest(".hidden"))
    }
}
