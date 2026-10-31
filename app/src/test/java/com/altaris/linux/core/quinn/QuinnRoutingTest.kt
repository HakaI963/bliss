package com.altaris.linux.core.quinn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing and model-config rules.
 *
 * The regression these lock down: Quinn is a master of many agents, so a
 * message must reach exactly the agents that were asked for, and a fan-out must
 * never collapse two agents into one thread.
 */
class QuinnRoutingTest {

    private fun target(distro: String, binary: String) = AgentTarget(
        distroId = distro,
        distroName = distro,
        method = "proot",
        binary = binary,
        label = binary,
        headless = true,
    )

    @Test
    fun fanOutSendsToEverySelectedTarget() {
        val a = target("debian", "opencode")
        val b = target("fedora", "claude")
        val sent = FanOut.targetsFor("do the thing", listOf(a, b), current = a)
        assertEquals(2, sent.size)
        assertTrue(sent.contains(a))
        assertTrue(sent.contains(b))
    }

    @Test
    fun noSelectionSendsOnlyToTheCurrentSession() {
        val a = target("debian", "opencode")
        val b = target("fedora", "claude")
        // Without a selection a normal send must never become a broadcast.
        assertEquals(listOf(a), FanOut.targetsFor("hi", emptyList(), current = a))
        assertFalse(FanOut.targetsFor("hi", emptyList(), current = a).contains(b))
    }

    @Test
    fun blankPromptSendsNothing() {
        val a = target("debian", "opencode")
        assertTrue(FanOut.targetsFor("", listOf(a), a).isEmpty())
        assertTrue(FanOut.targetsFor("   ", listOf(a), a).isEmpty())
    }

    @Test
    fun duplicateSelectionsAreCollapsed() {
        val a = target("debian", "opencode")
        assertEquals(1, FanOut.targetsFor("hi", listOf(a, a), null).size)
    }

    @Test
    fun theSameAgentInTwoDistrosIsTwoTargets() {
        val debian = target("debian", "opencode")
        val fedora = target("fedora", "opencode")
        assertTrue(debian.key != fedora.key)
        assertEquals(2, FanOut.targetsFor("hi", listOf(debian, fedora), null).size)
    }

    @Test
    fun sessionTitleFallsBackToTheTargetThenTheFirstMessage() {
        val a = target("debian", "opencode")
        val empty = QuinnSession(target = a)
        assertEquals("opencode · debian", empty.displayTitle)

        val used = empty.copy(
            turns = listOf(Turn(TurnRole.USER, "Explain the login flow\nplease")),
        )
        assertEquals("Explain the login flow please", used.displayTitle)

        assertEquals("Named", used.copy(title = "Named").displayTitle)
    }

    @Test
    fun eachSessionGetsItsOwnAgentSessionId() {
        val a = target("debian", "opencode")
        val one = QuinnSession(target = a)
        val two = QuinnSession(target = a)
        assertTrue(one.id != two.id)
        // Separate agent session ids are what keep two threads from resuming
        // into each other.
        assertTrue(one.agentSessionId != two.agentSessionId)
    }
}

/** The REA tab's own endpoint configuration. */
class QuinnModelConfigTest {

    @Test
    fun baseUrlAcceptsWhatPeopleType() {
        assertEquals(
            "https://api.openai.com/v1",
            QuinnModelConfig.normalizeBaseUrl("api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1",
            QuinnModelConfig.normalizeBaseUrl("https://api.openai.com/v1/"),
        )
        assertEquals(
            "http://127.0.0.1:8080/v1",
            QuinnModelConfig.normalizeBaseUrl("http://127.0.0.1:8080/v1"),
        )
    }

    @Test
    fun baseUrlRefusesNonHttpSchemes() {
        assertNull(QuinnModelConfig.normalizeBaseUrl(""))
        assertNull(QuinnModelConfig.normalizeBaseUrl("   "))
        assertNull(QuinnModelConfig.normalizeBaseUrl("ftp://example.com"))
        assertNull(QuinnModelConfig.normalizeBaseUrl("file:///etc/passwd"))
        assertNull(QuinnModelConfig.normalizeBaseUrl("javascript:alert(1)"))
    }

    @Test
    fun endpointsTolerateATrailingSlash() {
        val config = QuinnModelConfig.Config(
            baseUrl = "https://api.example.com/v1/",
            apiKey = "k",
            model = "m",
        )
        assertEquals("https://api.example.com/v1/chat/completions", config.chatUrl)
        assertEquals("https://api.example.com/v1/models", config.modelsUrl)
        assertTrue(config.configured)
    }

    @Test
    fun configIsOnlyUsableWithUrlAndKey() {
        assertFalse(QuinnModelConfig.Config().configured)
        assertFalse(QuinnModelConfig.Config(baseUrl = "https://x/v1").configured)
        assertFalse(QuinnModelConfig.Config(apiKey = "k").configured)
        assertTrue(QuinnModelConfig.Config(baseUrl = "https://x/v1", apiKey = "k").configured)
    }

    @Test
    fun modelListParsesTheOpenAiShape() {
        val body = """
            {"object":"list","data":[
              {"id":"gpt-5-6","object":"model"},
              {"id":"gpt-5-3-mini","object":"model"},
              {"id":"auto","object":"model"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("auto", "gpt-5-3-mini", "gpt-5-6"),
            QuinnModelConfig.parseModels(body),
        )
    }

    @Test
    fun malformedModelRepliesYieldNoModels() {
        assertTrue(QuinnModelConfig.parseModels("not json").isEmpty())
        assertTrue(QuinnModelConfig.parseModels("{}").isEmpty())
        assertTrue(QuinnModelConfig.parseModels("""{"data":"nope"}""").isEmpty())
    }
}
