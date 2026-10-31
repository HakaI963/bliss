package com.altaris.linux.core.quinn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adapter contract.
 *
 * Quinn is agent-agnostic, so the thing that must not rot is the table: every
 * known agent needs a headless command that a real CLI accepts, and an unknown
 * agent must still be drivable through the PTY path.
 */
class AgentCatalogTest {

    @Test
    fun knownAgentsAreHeadlessAndUnknownAgentsFallBackToPty() {
        assertTrue(AgentCatalog.isHeadless("opencode"))
        assertTrue(AgentCatalog.isHeadless("claude"))
        assertTrue(AgentCatalog.isHeadless("codex"))
        assertTrue(AgentCatalog.isHeadless("gemini"))
        assertTrue(AgentCatalog.isHeadless("aider"))

        // An agent nobody has written an adapter for is still usable.
        val unknown = AgentCatalog.forBinary("some-new-agent")
        assertEquals(AgentProfile.Kind.PTY, unknown.kind)
        assertEquals("some-new-agent", unknown.binary)
        assertEquals("some-new-agent", unknown.label)
        assertFalse(AgentCatalog.isHeadless("some-new-agent"))
    }

    @Test
    fun headlessAgentsDeclareATemplate() {
        for (profile in AgentCatalog.PROFILES) {
            if (profile.kind != AgentProfile.Kind.HEADLESS) continue
            assertTrue(
                "${profile.binary} is HEADLESS without a template",
                profile.template.isNotBlank(),
            )
            assertTrue(
                "${profile.binary} template must consume the prompt",
                profile.template.contains("{prompt}"),
            )
        }
    }

    @Test
    fun everyProfileHasADistinctBinary() {
        val binaries = AgentCatalog.PROFILES.map { it.binary }
        assertEquals(binaries.size, binaries.toSet().size)
    }

    @Test
    fun promptIsQuotedSoItCannotBreakOutOfTheCommand() {
        val profile = AgentCatalog.forBinary("opencode")
        val evil = "hello'; rm -rf /; echo '"
        val command = AgentCommand.build(profile, evil)
        // The dangerous characters must sit inside one single-quoted argument.
        assertTrue(command.contains("'hello'\\''; rm -rf /; echo '\\'''"))
        assertFalse(command.contains("; rm -rf /; echo '' --"))
    }

    @Test
    fun sessionAndModelAreQuotedToo() {
        val profile = AgentCatalog.forBinary("opencode")
        val command = AgentCommand.build(
            profile = profile,
            prompt = "hi",
            session = "a'b",
            model = "m'n",
        )
        assertTrue(command.contains("--session 'a'\\''b'"))
        assertTrue(command.contains("--model 'm'\\''n'"))
    }

    @Test
    fun resumableAgentsGetASessionFlagAndOthersDoNot() {
        val opencode = AgentCommand.build(
            AgentCatalog.forBinary("opencode"), "hi", session = "sess-1",
        )
        assertTrue(opencode.contains("--session 'sess-1'"))

        // Codex has no named sessions: the id must not be smuggled in.
        val codex = AgentCommand.build(
            AgentCatalog.forBinary("codex"), "hi", session = "sess-1",
        )
        assertFalse(codex.contains("sess-1"))
    }

    @Test
    fun attachmentsOnlyReachAgentsThatAcceptThem() {
        val files = listOf("/root/a.apk", "/root/b.so")
        val opencode = AgentCommand.build(
            AgentCatalog.forBinary("opencode"), "hi", files = files,
        )
        assertTrue(opencode.contains("--file '/root/a.apk'"))
        assertTrue(opencode.contains("--file '/root/b.so'"))

        // Gemini has no attachment flag; paths must not be appended anyway.
        val gemini = AgentCommand.build(
            AgentCatalog.forBinary("gemini"), "hi", files = files,
        )
        assertFalse(gemini.contains("--file"))
    }

    @Test
    fun agentsWithoutAnAttachmentFlagStillReceiveTheFilePaths() {
        // A PTY/plain agent cannot take --file, but it must still be told which
        // files the user attached instead of silently losing them.
        val command = AgentCommand.build(
            AgentCatalog.forBinary("gemini"), "summarise this", files = listOf("/root/a.apk"),
        )
        assertTrue(command.contains("/root/a.apk"))
        assertFalse(command.contains("--file"))
    }

    @Test
    fun reaIsARecognisedTargetBinary() {
        assertTrue(AgentCatalog.isRea(AgentCatalog.REA_BINARY))
        assertFalse(AgentCatalog.isRea("opencode"))
    }

    @Test
    fun opencodeUsesTheNonInteractiveRunFlags() {
        // A headless turn that prompts for tool approval would hang forever.
        val command = AgentCommand.build(AgentCatalog.forBinary("opencode"), "hi")
        assertTrue(command.startsWith("opencode run"))
        assertTrue(command.contains("--auto"))
        assertTrue(command.contains("--format json"))
    }

    @Test
    fun unknownAgentRunsItsOwnBinary() {
        assertEquals(
            "some-new-agent",
            AgentCommand.build(AgentCatalog.forBinary("some-new-agent"), "hi"),
        )
    }

    @Test
    fun generatedCommandHasNoDoubleSpaces() {
        // Empty {session}/{model}/{file} slots used to leave runs of spaces,
        // which some CLIs treat as an empty argument.
        val command = AgentCommand.build(AgentCatalog.forBinary("opencode"), "hi")
        assertFalse(command.contains("  "))
        assertFalse(command.endsWith(" "))
    }
}
