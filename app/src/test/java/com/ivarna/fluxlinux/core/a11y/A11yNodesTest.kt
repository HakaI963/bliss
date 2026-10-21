package com.ivarna.fluxlinux.core.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the accessibility tree payload parser. */
class A11yNodesTest {

    @Test
    fun parsesTreePayload() {
        val nodes = A11yNodes.parse(TREE)
        assertEquals(2, nodes.size)
        assertEquals(11L, nodes[0].id)
        assertEquals("Sign in", nodes[0].label)
        assertEquals("Button", nodes[0].type)
        assertEquals("10,20 → 110,60", nodes[0].boundsLabel)
        assertTrue(nodes[0].clickable)
        assertEquals("Email", nodes[1].label)
        assertTrue(nodes[1].editable)
    }

    @Test
    fun brokenPayloadYieldsNoNodes() {
        assertEquals(0, A11yNodes.parse("not json").size)
        assertEquals(0, A11yNodes.parse("{}").size)
        assertEquals(0, A11yNodes.parse("{\"ok\":true}").size)
    }

    private companion object {
        const val TREE = """{"ok":true,"display":75,"source":"accessibility","nodes":[
            {"id":11,"class":"android.widget.Button","text":"Sign in","desc":"",
             "package":"com.example.app","bounds":[10,20,110,60],"winBounds":[10,20,110,60],
             "clickable":true,"editable":false,"scrollable":false,"focusable":true,
             "enabled":true,"visible":true,"checked":false,"actions":[16],"childCount":0,"depth":3},
            {"id":12,"class":"android.widget.EditText","text":"","desc":"Email",
             "package":"com.example.app","bounds":[0,0,10,10],"winBounds":[0,0,10,10],
             "clickable":false,"editable":true,"scrollable":false,"focusable":true,
             "enabled":true,"visible":true,"checked":false,"actions":[32],"childCount":0,"depth":4}]}"""
    }
}
