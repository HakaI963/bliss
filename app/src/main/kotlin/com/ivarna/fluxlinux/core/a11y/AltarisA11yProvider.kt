package com.ivarna.fluxlinux.core.a11y

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

/**
 * Synchronous bridge between the Altaris app process and
 * [AltarisAccessibilityService].
 *
 * The provider and the service share the `:a11y` process, so the running
 * service instance is reachable directly and the app can call it through a
 * binder without restarting anything.
 *
 * Every reply is a [Bundle] with a `json` string; the app side parses it with
 * [A11yBridge].
 */
class AltarisA11yProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val bundle = Bundle()
        bundle.putString("json", dispatch(method, arg, extras))
        return bundle
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String = "application/json"

    private fun dispatch(method: String, arg: String?, extras: Bundle?): String {
        val service = AltarisAccessibilityService.instance
            ?: return json(JSONObject().put("ok", false).put("error", "accessibility service not running"))
        return runCatching {
            when (method) {
                "status" -> service.status()
                "tree" -> service.snapshot(
                    arg?.toIntOrNull() ?: -1,
                    extras?.getInt("max", 600) ?: 600,
                )
                "click" -> service.clickDetailed(arg?.toLongOrNull() ?: -1L)
                "text" -> service.setTextDetailed(
                    arg?.toLongOrNull() ?: -1L,
                    extras?.getString("text").orEmpty(),
                )
                "scroll" -> service.scrollDetailed(
                    arg?.toLongOrNull() ?: -1L,
                    extras?.getBoolean("forward", true) ?: true,
                )
                "gesture" -> service.gestureDetailed(
                    extras?.getInt("display", -1) ?: -1,
                    extras?.getFloat("x1", 0f) ?: 0f,
                    extras?.getFloat("y1", 0f) ?: 0f,
                    extras?.getFloat("x2", 0f) ?: 0f,
                    extras?.getFloat("y2", 0f) ?: 0f,
                    extras?.getLong("ms", 300L) ?: 300L,
                )
                else -> json(JSONObject().put("ok", false).put("error", "unknown method: $method"))
            }
        }.getOrElse {
            json(JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName))
        }
    }

    private fun json(obj: JSONObject): String = obj.toString()
}
