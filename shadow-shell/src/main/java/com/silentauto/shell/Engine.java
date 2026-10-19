package com.silentauto.shell;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Minimal stand-in for ShadowAuto's AutomationEngine.
 *
 * <p>The upstream engine drives an OpenAI-driven automation loop (AiClient,
 * okhttp, Paddle OCR). Bliss does not use that loop: it drives the virtual
 * display, launch, capture and input directly through the MCP session API.
 * This shim keeps the RPC surface identical (status / dumpUi / mcp / task
 * stubs) without pulling in the AI stack, the OCR runtime or okhttp.
 *
 * <p>Vendored from ShadowAuto (Apache-2.0) — see shadow-shell/LICENSE.
 */
final class Engine {
    private final LogHub logs;
    private final McpSessions mcp;

    Engine(LogHub logs) {
        this.logs = logs;
        this.mcp = new McpSessions(logs);
    }

    McpSessions mcp() {
        return mcp;
    }

    synchronized JsonObject statusJson() {
        JsonObject object = new JsonObject();
        object.addProperty("state", "idle");
        object.addProperty("displayId", -1);
        object.add("tasks", new JsonArray());
        try {
            object.add("mcpSessions", mcp.statusArray());
        } catch (Throwable ignored) {
            object.add("mcpSessions", new JsonArray());
        }
        return object;
    }

    JsonObject dumpUiJson(int displayId, int width, int height, String mode) {
        return mcp.dumpUi("", displayId, width, height, mode);
    }

    void stopTask() {
        logs.info("stopTask ignored: Bliss drives sessions directly");
    }

    void stopTask(String taskId) {
        logs.info("stopTask ignored: Bliss drives sessions directly");
    }

    String startTask(String taskId, String goal, String apiKey, String apiBase, String model) {
        throw new IllegalStateException("AI tasks are not available in the Bliss build");
    }
}
