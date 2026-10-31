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
     * The detector must accept unknown names: a new agent has to appear without
     * a code change.
     */
    @Test
    fun unknownAgentNamesAreAccepted() {
        assertTrue(AgentDetector.isAgentNameForTest("opencode"))
        assertTrue(AgentDetector.isAgentNameForTest("claude"))
        assertTrue(AgentDetector.isAgentNameForTest("kimi"))
        assertTrue(AgentDetector.isAgentNameForTest("crush"))
        assertTrue(AgentDetector.isAgentNameForTest("goose"))
        assertTrue(AgentDetector.isAgentNameForTest("some-brand-new-agent"))
        assertTrue(AgentDetector.isAgentNameForTest("qwen-code"))
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
