package com.altaris.linux.core.clone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing rules for concurrent clones.
 *
 * The regression these tests lock down: every screen/input request used to fall
 * back to "the one display", so a second agent could steal or destroy the first
 * agent's clone. Now an explicit package wins, and only a request without one
 * falls back to the viewer.
 */
class SessionKeysTest {

    private val cloneA = "com.altaris.apps.aaaa1111"
    private val cloneB = "com.altaris.apps.bbbb2222"

    @Test
    fun explicitPackageAlwaysWins() {
        assertEquals(cloneA, SessionKeys.resolve(cloneA, cloneB))
        assertEquals(cloneB, SessionKeys.resolve(cloneB, cloneA))
        // Even with no viewer at all.
        assertEquals(cloneA, SessionKeys.resolve(cloneA, null))
    }

    @Test
    fun viewerIsOnlyTheFallback() {
        assertEquals(cloneB, SessionKeys.resolve(null, cloneB))
        assertEquals(cloneB, SessionKeys.resolve("", cloneB))
        assertEquals(cloneB, SessionKeys.resolve("   ", cloneB))
    }

    @Test
    fun noCloneMeansPrimary() {
        assertEquals(SessionKeys.PRIMARY, SessionKeys.resolve(null, null))
        assertNull(SessionKeys.normalize(null))
        assertNull(SessionKeys.normalize("  "))
    }

    @Test
    fun packageNamesAreTrimmed() {
        assertEquals(cloneA, SessionKeys.resolve("  $cloneA  ", null))
        assertEquals("primary-installed-app", SessionKeys.resolve(" primary-installed-app ", null))
    }

    @Test
    fun cloneKeysAreRecognised() {
        assertTrue(SessionKeys.isCloneKey(cloneA))
        assertTrue(SessionKeys.isCloneKey("com.nopiece.hclone.legacy"))
        assertFalse(SessionKeys.isCloneKey(SessionKeys.PRIMARY))
        assertFalse(SessionKeys.isCloneKey("org.example.app"))
        assertFalse(SessionKeys.isCloneKey(null))
    }

    @Test
    fun twoAgentsKeepSeparateKeys() {
        // Agent A drives clone A, agent B drives clone B, neither has a viewer.
        val agentA = SessionKeys.resolve(cloneA, null)
        val agentB = SessionKeys.resolve(cloneB, null)
        assertTrue(agentA != agentB)
        // And a viewer switch by the user does not move either agent's target.
        val viewer = cloneA
        assertEquals(cloneA, SessionKeys.resolve(cloneA, viewer))
        assertEquals(cloneB, SessionKeys.resolve(cloneB, viewer))
    }
}
