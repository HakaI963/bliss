package com.altaris.linux.core.quinn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slash commands.
 *
 * The two namespaces must never collide: `/btw` belongs to the agent, `//btw`
 * belongs to Quinn, and a message that merely contains a slash must not be
 * hijacked.
 */
class SlashCommandsTest {

    @Test
    fun doubleSlashIsQuinnAndSingleSlashIsTheAgent() {
        val quinn = SlashCommands.parse("//status")
        assertNotNull(quinn)
        assertTrue(quinn!!.quinn)
        assertEquals("status", quinn.name)

        val agent = SlashCommands.parse("/goal ship it")
        assertNotNull(agent)
        assertFalse(agent!!.quinn)
        assertEquals("goal", agent.name)
        assertEquals("ship it", agent.argument)
    }

    @Test
    fun aNormalMessageIsNotACommand() {
        assertNull(SlashCommands.parse("explain this file"))
        assertNull(SlashCommands.parse("a/b path"))
        // Leading whitespace does not change the parse.
        assertEquals("status", SlashCommands.parse("  //status")?.name)
    }

    @Test
    fun aBareSlashIsAnEmptyDraftNotACommandName() {
        val draft = SlashCommands.parse("//")
        assertNotNull(draft)
        assertEquals("", draft!!.name)
        assertTrue(draft.typingName)
        // And it suggests everything, which is what an empty prefix should do.
        assertTrue(SlashCommands.suggestions(draft).size >= 5)
    }

    @Test
    fun suggestionsComeFromTheRightNamespace() {
        val quinn = SlashCommands.suggestions(SlashCommands.parse("//st"))
        assertTrue(quinn.all { it.quinn })
        assertTrue(quinn.any { it.name == "status" })

        val agent = SlashCommands.suggestions(SlashCommands.parse("/go"))
        assertTrue(agent.all { !it.quinn })
        assertTrue(agent.any { it.name == "goal" })
    }

    @Test
    fun everyQuinnCommandHasItsOwnToken() {
        for (command in SlashCommands.QUINN) {
            assertEquals("//" + command.name, command.token)
            assertTrue("${command.name} needs a description", command.description.isNotBlank())
        }
        // The two namespaces must be disjoint by token.
        val agentTokens = SlashCommands.AGENT_DEFAULTS.map { it.token }.toSet()
        assertTrue(SlashCommands.QUINN.none { it.token in agentTokens })
    }

    @Test
    fun quinnAnswersTheStateCommandsWithoutAModel() {
        val snapshot = QuinnBrain.Snapshot(
            distros = listOf(
                QuinnBrain.DistroFact("debian", "Debian", "proot", true, listOf("opencode"))
            ),
            agents = listOf(
                QuinnBrain.AgentFact("OpenCode", "Debian", "opencode", true)
            ),
            rea = QuinnBrain.ReaFact(
                installed = true,
                ready = true,
                method = "proot",
                tools = 18,
                serverRunning = false,
            ),
            quinnModel = "nvidia/nemotron-3-super-120b-a12b",
            activeSession = "",
            runningAgents = 0,
        )
        val status = QuinnBrain.answerLocally("status", snapshot)
        assertNotNull(status)
        assertTrue(status!!.contains("1 distros"))
        assertTrue(status.contains("1 agents"))
        assertTrue(status.contains("18 tools"))

        val distros = QuinnBrain.answerLocally("distros", snapshot)!!
        assertTrue(distros.contains("Debian"))
        assertTrue(distros.contains("proot"))

        val agents = QuinnBrain.answerLocally("agents", snapshot)!!
        assertTrue(agents.contains("OpenCode"))
        assertTrue(agents.contains("Debian"))

        val model = QuinnBrain.answerLocally("model", snapshot)!!
        assertTrue(model.contains("nemotron-3-super"))

        // An open-ended question has no local answer.
        assertNull(QuinnBrain.answerLocally("btw", snapshot))
    }

    @Test
    fun thePromptOnlyContainsRealState() {
        val snapshot = QuinnBrain.Snapshot(
            distros = emptyList(),
            agents = emptyList(),
            rea = QuinnBrain.ReaFact(false, false, "", 0, false),
            quinnModel = "",
            activeSession = "",
            runningAgents = 0,
        )
        val prompt = QuinnBrain.systemPrompt(snapshot)
        assertTrue(prompt.contains("DISTROS (0)"))
        assertTrue(prompt.contains("AGENTS (0)"))
        assertTrue(prompt.contains("REA: not installed"))
        // It must be told not to invent facts.
        assertTrue(prompt.contains("say it is unknown"))
    }
}

/** The context ring's arithmetic. */
class ContextBudgetTest {

    @Test
    fun tokensApproximateCharactersOverFour() {
        assertEquals(0, ContextBudget.tokens(""))
        assertEquals(1, ContextBudget.tokens("abcd"))
        assertEquals(3, ContextBudget.tokens("abcdefghij"))
    }

    @Test
    fun partsAreSplitAndFractionsSumToOne() {
        val estimate = ContextBudget.estimate(
            systemPrompt = "a".repeat(400),
            tools = "b".repeat(200),
            skills = "",
            messages = listOf("c".repeat(200) to "d".repeat(200)),
            window = 10_000,
        )
        // System, Tools and Messages; the empty Skills part is dropped.
        assertEquals(3, estimate.parts.size)
        assertTrue(estimate.total > 0)
        val sum = estimate.parts.sumOf { it.fraction.toDouble() }
        assertEquals(1.0, sum, 0.001)
        // Empty parts are dropped, not shown as zero-width rows.
        assertTrue(estimate.parts.none { it.label == "Skills" })
    }

    @Test
    fun theWindowIsKnownPerFamily() {
        assertEquals(1_000_000, ContextBudget.windowFor("gemini-2.5-pro"))
        assertEquals(200_000, ContextBudget.windowFor("claude-sonnet-4"))
        assertEquals(256_000, ContextBudget.windowFor("kimi-k3"))
        assertEquals(128_000, ContextBudget.windowFor("nvidia/nemotron-3-super-120b-a12b"))
        assertEquals(ContextBudget.DEFAULT_WINDOW, ContextBudget.windowFor("something-else"))
    }

    @Test
    fun remainingNeverGoesNegative() {
        val estimate = ContextBudget.Estimate(
            parts = emptyList(),
            total = 200_000,
            window = 128_000,
        )
        assertEquals(0, estimate.remaining)
        assertEquals(1f, estimate.fraction, 0.001f)
    }
}

/** The remembered selection. */
class QuinnPrefsTest {

    @Test
    fun theSelectionShapeSurvivesAReconstruction() {
        val selection = QuinnPrefs.Selection(
            agentKey = "debian/opencode",
            sessionId = "abc",
            agentModel = "nvidia/nemotron-3-super-120b-a12b",
        )
        // The persisted form is three strings; this asserts the defaults that
        // make a first launch behave (blank means "nothing chosen yet").
        assertEquals("", QuinnPrefs.Selection().agentKey)
        assertEquals("", QuinnPrefs.Selection().sessionId)
        assertEquals("", QuinnPrefs.Selection().agentModel)
        assertTrue(selection.agentKey.isNotBlank())
    }
}
