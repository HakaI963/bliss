package com.altaris.linux.core.rea

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCP bridge must be a proxy, not a stub.
 *
 * A real client rejected the old endpoint:
 *
 *   ✗ rea failed: Invalid result for initialize:
 *     protocolVersion: expected string, received undefined
 *     capabilities:    expected object, received undefined
 *     serverInfo:      expected object, received undefined
 *
 * because the bridge answered `initialize` with `{}` and `tools/list` with an
 * empty array instead of forwarding to REA's stdio process. These tests pin the
 * three properties a real client checks: a real handshake, real tools, and the
 * session header.
 */
class ReaMcpProtocolTest {

    /** A minimal, valid initialize result — the shape REA actually returns. */
    private fun handshake(): JSONObject = JSONObject(
        """
        {"jsonrpc":"2.0","id":1,"result":{
          "protocolVersion":"2025-06-18",
          "capabilities":{"tools":{"listChanged":true}},
          "serverInfo":{"name":"rea","version":"6.7.0"},
          "instructions":"REA provides reverse engineering tools."
        }}
        """.trimIndent()
    )

    @Test
    fun aRealHandshakeHasEverythingAClientValidates() {
        val result = handshake().getJSONObject("result")
        assertTrue(
            "protocolVersion must be a non-empty string",
            result.getString("protocolVersion").isNotBlank(),
        )
        assertNotNull("capabilities must be an object", result.optJSONObject("capabilities"))
        val serverInfo = result.optJSONObject("serverInfo")
        assertNotNull("serverInfo must be an object", serverInfo)
        assertEquals("rea", serverInfo!!.getString("name"))
        assertTrue("version must be present", serverInfo.getString("version").isNotBlank())
        assertTrue("instructions should be passed through", result.has("instructions"))
    }

    /**
     * The bridge must replay REA's handshake rather than synthesising one.
     * Replaying is required because REA is already initialized by the bridge and
     * the spec forbids initializing it twice.
     */
    @Test
    fun theBridgeReplaysReaHandshakeWithTheClientId() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt"
        )
        assertTrue(
            "must replay the cached handshake",
            source.contains("session.handshake()"),
        )
        assertTrue(
            "must answer under the client's id",
            source.contains("JSONObject(real.toString()).put(\"id\", id ?: 0)"),
        )
        // It must not fabricate an empty result for initialize.
        assertFalse(
            "initialize must not return an empty result",
            source.contains("\"initialize\" -> JSONObject()"),
        )
    }

    /** The session must capture the real handshake at startup. */
    @Test
    fun theSessionCapturesTheHandshake() {
        val bridge = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaBridge.kt"
        )
        assertTrue("must store the handshake", bridge.contains("handshake = forward(\"initialize\""))
        assertTrue("must expose it", bridge.contains("fun handshake(): JSONObject?"))
        // A current protocol version must be offered.
        assertTrue(
            "must offer a current protocol version",
            bridge.contains("\"2025-06-18\""),
        )
    }

    /** tools/list must be forwarded, never replaced with an empty array. */
    @Test
    fun toolsAreForwardedNotInvented() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt"
        )
        assertFalse(
            "must not build an empty tool list",
            source.contains("JSONObject().put(\"tools\", JSONArray(toolList()))"),
        )
        assertTrue(
            "tools/list must be forwarded",
            source.contains("session.forward(method, params, REQUEST_TIMEOUT_MS)"),
        )
    }

    /** tools/call must return REA's reply verbatim, including errors. */
    @Test
    fun toolCallsReturnReasReply() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt"
        )
        assertTrue(
            "must forward the call",
            source.contains("session.forward(method, params, TOOL_TIMEOUT_MS)"),
        )
        assertTrue(
            "must return REA's reply body",
            source.contains("withClientId(reply, id)"),
        )
    }

    /**
     * The reply must carry the *client's* id.
     *
     * The session correlates REA's replies with its own counter, because
     * several bridge requests can be in flight. Returning that counter to the
     * client is fatal: JSON-RPC matches a response to a request by id, so a
     * client that sent `id: 999` and received `id: 11` discards the response
     * and blocks until its own timeout. That is exactly the reported
     * "Received a response for an unknown message ID" followed by
     * "Request timed out", while the endpoint itself answered in milliseconds.
     */
    @Test
    fun repliesCarryTheClientsRequestId() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt"
        )
        assertTrue(
            "must rewrite the id on the reply",
            source.contains("private fun withClientId("),
        )
        assertTrue(
            "must set the caller's id",
            source.contains("reply.put(\"id\", id)"),
        )
        // Both forwarded paths must go through it: the generic pass-through
        // and tools/call.
        assertTrue(
            "the generic pass-through must echo the client id",
            source.contains("withClientId(decorate(method, reply), id)"),
        )
        assertTrue(
            "tools/call must echo the client id",
            source.contains("withClientId(reply, id)"),
        )
        assertFalse(
            "must not hand back the session's own id",
            source.contains("// Return REA's reply as-is"),
        )
    }

    /** Streamable HTTP requires the session header. */
    @Test
    fun theSessionHeaderIsSent() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt"
        )
        assertTrue("must send Mcp-Session-Id", source.contains("Mcp-Session-Id: "))
        assertTrue("must expose it to browsers", source.contains("Access-Control-Expose-Headers"))
        assertTrue("the id must be stable", source.contains("private val sessionId"))
    }

    /** A notification gets no body, per JSON-RPC. */
    @Test
    fun notificationsGetNoBody() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt"
        )
        assertTrue(
            "must detect a notification",
            source.contains("val isNotification = id == null || id == JSONObject.NULL"),
        )
        assertTrue(
            "must forward it without waiting",
            source.contains("session.notifyForward(method, params)"),
        )
        assertTrue(
            "must answer 202 with no body",
            source.contains("respond(socket.getOutputStream(), 202, \"\")"),
        )
    }

    /**
     * The bridge must resolve the session per request. Holding a direct
     * reference meant a restart left it writing to a dead process, which is
     * where "Stream closed" came from.
     */
    @Test
    fun theSessionIsResolvedPerRequest() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt"
        )
        assertTrue(
            "must take a session resolver",
            source.contains("private val sessionRef: () -> ReaBridge.Session?"),
        )
        assertTrue(
            "must resolve it on each access",
            source.contains("get() = sessionRef() ?: throw IllegalStateException("),
        )
        assertTrue(
            "a dead process must be reported plainly",
            source.contains("REA process is not running"),
        )
        // And the bridge must be constructed with the resolver.
        val bridge = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaBridge.kt"
        )
        assertTrue(
            "must pass the live session",
            bridge.contains("BridgeServer(app) { session }"),
        )
    }

    /** The guide is appended to the lists without disturbing REA's entries. */
    @Test
    fun theGuideIsAppendedNotSubstituted() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt"
        )
        assertTrue("must decorate list replies", source.contains("private fun decorate("))
        // It must only add, never replace, the arrays REA returned.
        assertTrue(
            "must keep REA's resources",
            source.contains("result.optJSONArray(\"resources\") ?: JSONArray()"),
        )
        assertTrue(
            "must keep REA's prompts",
            source.contains("result.optJSONArray(\"prompts\") ?: JSONArray()"),
        )
    }

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }
}
