package com.altaris.linux.clone.engine;

import org.json.JSONObject;

/** Tiny JSON reply helpers shared by the two processes. */
final class Jsons {
    private Jsons() {
    }

    static String ok(String detail) {
        try {
            JSONObject object = new JSONObject();
            object.put("ok", true);
            object.put("detail", detail == null ? "" : detail);
            return object.toString();
        } catch (Throwable e) {
            return "{\"ok\":true}";
        }
    }

    /**
     * Success reply for a display command: callers need the display id and the
     * session key back so they can address this clone later without guessing.
     */
    static String display(String detail, int displayId, String key) {
        try {
            JSONObject object = new JSONObject();
            object.put("ok", true);
            object.put("detail", detail == null ? "" : detail);
            object.put("display_id", displayId);
            object.put("key", key == null ? "" : key);
            return object.toString();
        } catch (Throwable e) {
            return ok(detail);
        }
    }

    static String error(String message) {
        try {
            JSONObject object = new JSONObject();
            object.put("ok", false);
            object.put("error", message == null ? "unknown error" : message);
            return object.toString();
        } catch (Throwable e) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }

    static String error(Throwable error) {
        String message = error == null ? "unknown error" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        return error(message);
    }
}
