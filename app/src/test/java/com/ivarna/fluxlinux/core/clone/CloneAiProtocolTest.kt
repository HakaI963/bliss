package com.ivarna.fluxlinux.core.clone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneAiProtocolTest {

    private fun line(text: String, x: Int, y: Int, w: Int = 100, h: Int = 40) =
        CloneAi.ScreenLine(text, x, y, w, h)

    @Test
    fun `parses a plain action`() {
        val action = AiProtocol.parseAction("""{"action":"tap_text","text":"Search","reason":"open search"}""")
        assertNotNull(action)
        assertEquals("tap_text", action!!.type)
        assertEquals("Search", action.text)
        assertEquals("open search", action.reason)
    }

    @Test
    fun `parses fenced json and coordinates`() {
        val action = AiProtocol.parseAction(
            """
            Sure.
            ```json
            {"action":"tap","x":120,"y":640}
            ```
            """.trimIndent(),
        )
        assertEquals("tap", action!!.type)
        assertEquals(120, action.x)
        assertEquals(640, action.y)
    }

    @Test
    fun `parses function-style wrappers`() {
        val action = AiProtocol.parseAction(
            """{"name":"tap","arguments":"{\"x\":10,\"y\":20}"}""",
        )
        assertEquals("tap", action!!.type)
        assertEquals(10, action.x)
        assertEquals(20, action.y)

        val nested = AiProtocol.parseAction(
            """{"function":{"name":"input_text","arguments":{"text":"hello"}}}""",
        )
        assertEquals("input_text", nested!!.type)
        assertEquals("hello", nested.text)
    }

    @Test
    fun `rejects text without a json object`() {
        assertNull(AiProtocol.parseAction("I will tap the search button."))
    }

    @Test
    fun `finds the best visible text`() {
        val lines = listOf(
            line("Search", 10, 10),
            line("Search settings", 10, 80),
            line("Cancel", 10, 150),
        )
        assertEquals("Search", AiProtocol.bestTextMatch(lines, "search")!!.text)
        assertEquals("Search settings", AiProtocol.bestTextMatch(lines, "Settings")!!.text)
        assertNull(AiProtocol.bestTextMatch(lines, "teleport"))
    }

    @Test
    fun `swipe direction maps to finger movement`() {
        val up = AiProtocol.swipePath("up", 720, 1560, 0)
        assertTrue(up[1] > up[3]) // finger moves from bottom to top
        val down = AiProtocol.swipePath("down", 720, 1560, 0)
        assertTrue(down[1] < down[3])
        val left = AiProtocol.swipePath("left", 720, 1560, 0)
        assertTrue(left[0] > left[2])
        val right = AiProtocol.swipePath("right", 720, 1560, 0)
        assertTrue(right[0] < right[2])
    }

    @Test
    fun `prompt carries goal app screen and ocr`() {
        val prompt = AiProtocol.buildPrompt(
            goal = "turn wifi on",
            appLabel = "Settings",
            packageName = "com.android.settings",
            width = 720,
            height = 1560,
            lines = listOf(line("Wi-Fi", 40, 300)),
            history = listOf("tap text=\"Wi-Fi\" -> tapped"),
            step = 2,
            maxSteps = 30,
        )
        assertTrue(prompt.contains("turn wifi on"))
        assertTrue(prompt.contains("com.android.settings"))
        assertTrue(prompt.contains("720x1560"))
        assertTrue(prompt.contains("0 | Wi-Fi"))
        assertTrue(prompt.contains("tap text=\"Wi-Fi\""))
    }

    @Test
    fun `endpoint normalisation`() {
        assertEquals("https://api.openai.com/v1/chat/completions", AiProtocol.endpoint("https://api.openai.com/v1/"))
        assertEquals("https://api.openai.com/v1/chat/completions", AiProtocol.endpoint("https://api.openai.com/v1"))
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            AiProtocol.endpoint("http://127.0.0.1:8080/v1/chat/completions"),
        )
    }
}
