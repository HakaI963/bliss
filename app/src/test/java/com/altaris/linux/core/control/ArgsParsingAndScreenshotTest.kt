package com.altaris.linux.core.control

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two calls that could not work, whatever the agent did.
 *
 * **`altaris_rea_call` saw no arguments.** It read `params.arguments` with
 * `optJSONObject`, which returns null when the member is a *string* or absent.
 * A call that plainly carried `name` then arrived as `{}` and was rejected with
 * "No REA tool name … Received keys: []" — a message that contradicted itself.
 *
 * **The physical screenshot never produced a file.** The Shizuku path asked the
 * shell to `cp` the PNG into the app's private cache, which is a different uid,
 * so the copy always failed while the capture had actually worked.
 */
class ArgsParsingAndScreenshotTest {

    // ------------------------------------------------------- argument shapes

    /**
     * The parser must accept all three shapes a client sends.
     *
     * This mirrors `McpProtocol.argumentsOf`, which is private; the behaviour is
     * what matters, so the shapes are exercised here directly.
     */
    @Test
    fun everyArgumentShapeIsAccepted() {
        // 1. Standard: arguments is an object.
        val standard = JSONObject()
            .put("name", "altaris_rea_call")
            .put("arguments", JSONObject().put("name", "inspect_artifact"))
        assertEquals(
            "inspect_artifact",
            parse(standard).optString("name"),
        )

        // 2. A string-encoded object, which optJSONObject reports as null.
        val encoded = JSONObject()
            .put("name", "altaris_rea_call")
            .put("arguments", "{\"name\":\"inspect_artifact\"}")
        assertEquals(
            "inspect_artifact",
            parse(encoded).optString("name"),
        )

        // 3. Inline: no arguments member, the tool's fields sit beside `name`.
        val inline = JSONObject()
            .put("name", "altaris_altaris_ui_tap_text")
            .put("text", "Team")
        val fromInline = parse(inline)
        assertEquals("Team", fromInline.optString("text"))
        // The envelope's own `name` must not leak into the tool arguments.
        assertFalse(
            "the envelope name must not become a tool argument",
            fromInline.has("name"),
        )
    }

    /** An absent arguments member is an empty object, not a crash. */
    @Test
    fun aMissingArgumentsMemberIsEmpty() {
        assertEquals(0, parse(JSONObject().put("name", "altaris_status")).length())
        assertEquals(0, parse(null).length())
    }

    /** The source must carry the same three paths. */
    @Test
    fun theSourceHandlesTheStringAndInlineForms() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue("must have the parser", mcp.contains("private fun argumentsOf("))
        val body = mcp.substringAfter("private fun argumentsOf(")
            .substringBefore("private fun callTool(")
        assertTrue("must try an object", body.contains("optJSONObject(\"arguments\")"))
        assertTrue("must try a string", body.contains("JSONObject(raw)"))
        assertTrue("must try inline", body.contains("!params.has(\"arguments\")"))
        assertTrue("must drop the envelope name", body.contains("inline.remove(\"name\")"))
        // And the handler must use it rather than reading the object directly.
        val handler = mcp.substringAfter("\"tools/call\" ->")
            .substringBefore("else -> error(id")
        assertTrue("the handler must use the parser", handler.contains("argumentsOf(params)"))
        assertFalse(
            "the handler must not read the object directly",
            handler.contains("optJSONObject(\"arguments\")"),
        )
    }

    /**
     * `altaris_rea_call` must accept arguments beside the tool name.
     *
     * This is the form that failed: `{ "name": "x", "path": "y" }` — reading
     * only a nested `arguments` object turned it into `{}`.
     */
    @Test
    fun theReaCallAcceptsFlattenedArguments() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val call = mcp.substringAfter("\"altaris_rea_call\" ->")
            .substringBefore("\"altaris_rea_agents\" ->")
        assertTrue("must read the nested object", call.contains("val nested = args.optJSONObject(\"arguments\")"))
        assertTrue("must fall back to flattened", call.contains("val arguments = nested ?: JSONObject().apply"))
        assertTrue("must skip the envelope keys", call.contains("key == \"timeoutMs\""))
        assertTrue("must skip name", call.contains("key == \"name\""))
        // The error must show what arrived, not a bare key list.
        assertTrue("must echo the call", call.contains("args.toString().take(300)"))
    }

    // ------------------------------------------------------------ screenshot

    /**
     * The Shizuku capture must copy the file as the app.
     *
     * The shell uid cannot write into the app's private cache, so the old
     * `cp $tmp <cache>` always failed. The app has to read the world-readable
     * temp file itself.
     */
    @Test
    fun theShizukuCaptureCopiesAsTheApp() {
        val shot = readSource("app/src/main/kotlin/com/altaris/linux/core/system/DeviceScreenshot.kt")
        val shizuku = shot.substringAfter("private fun captureWithShizuku(")
            .substringBefore("/** PNG dimensions")
        // The copy must be plain file IO, not a shell command.
        assertTrue(
            "must copy with file IO",
            shizuku.contains("java.io.File(tmp).inputStream()"),
        )
        assertTrue(
            "must write into the app's target",
            shizuku.contains("target.outputStream()"),
        )
        assertFalse(
            "must not ask the shell to write into the app's cache",
            shizuku.contains("\"cp \$tmp \""),
        )
        // A failure must name the real cause and the alternative.
        assertTrue(
            "must name the temp path",
            shizuku.contains("screencap wrote the PNG to"),
        )
        assertTrue(
            "must offer the accessibility path",
            shizuku.contains("enable the Altaris accessibility service"),
        )
    }

    /**
     * The accessibility capture must not compress a hardware bitmap.
     *
     * `Bitmap.wrapHardwareBuffer` returns a hardware bitmap, and `compress` on
     * one returns false on many devices — which surfaced as "PNG encoding
     * failed" on a capture that had worked.
     */
    @Test
    fun theAccessibilityCaptureCopiesToSoftwareFirst() {
        val service = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/a11y/AltarisAccessibilityService.kt"
        )
        val capture = service.substringAfter("fun captureScreenshot(")
            .substringBefore("/** Human-readable reason")
        assertTrue(
            "must copy to a software bitmap",
            capture.contains("hardware.copy("),
        )
        assertTrue(
            "must use ARGB_8888",
            capture.contains("Bitmap.Config.ARGB_8888"),
        )
        assertTrue("must compress the software copy", capture.contains("software.compress("))
        assertTrue("must recycle it", capture.contains("software.recycle()"))
        assertTrue(
            "must still close the buffer",
            capture.contains("screenshot.hardwareBuffer.close()"),
        )
    }

    /**
     * "No display frame" must not be the whole message.
     *
     * It names no target, no cause and no next step, so an agent can only
     * repeat the call. Both display tools now say what to check.
     */
    @Test
    fun aMissingFrameExplainsItself() {
        val mcp = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val shotBlock = mcp.substringAfter("\"altaris_screenshot\" ->")
            .substringBefore("\"altaris_record_start\" ->")
        val ocrBlock = mcp.substringAfter("\"altaris_ocr\" ->")
            .substringBefore("\"altaris_ui_tree\" ->")
        for ((tool, block) in listOf("altaris_screenshot" to shotBlock, "altaris_ocr" to ocrBlock)) {
            assertTrue("$tool must name the cause", block.contains("The clone is not running"))
            assertTrue("$tool must point at altaris_clones", block.contains("altaris_clones"))
        }
        // The clone screenshot must also point at the physical one, since the
        // two are different displays and that confusion was reported.
        val shot = mcp.substringAfter("\"altaris_screenshot\" ->")
            .substringBefore("\"altaris_record_start\" ->")
        assertTrue(
            "must point at the physical capture",
            shot.contains("altaris_device_screenshot"),
        )
    }

    // ------------------------------------------------------------- helpers

    /** The parser, mirrored from McpProtocol.argumentsOf. */
    private fun parse(params: JSONObject?): JSONObject {
        if (params == null) return JSONObject()
        params.optJSONObject("arguments")?.let { return it }
        val raw = params.optString("arguments")
        if (raw.isNotBlank()) {
            runCatching { JSONObject(raw) }.getOrNull()?.let { return it }
        }
        if (!params.has("arguments")) {
            val inline = JSONObject()
            val keys = params.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == "_meta" || key == "arguments") continue
                inline.put(key, params.opt(key))
            }
            inline.remove("name")
            if (inline.length() > 0) return inline
        }
        return JSONObject()
    }

    private fun readSource(path: String): String {
        val relative = path.removePrefix("app/")
        for (candidate in listOf(path, relative, "../$path", "../$relative", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path (cwd=" + File(".").absolutePath + ")")
    }
}
