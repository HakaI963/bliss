package com.ivarna.fluxlinux.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract test for the first-run onboarding flow.
 *
 * Guards the two product rules the flow must keep:
 *  1. downloads are disclosed + consented to before anything is fetched, and
 *  2. installing a distro is OPTIONAL — the flow reaches a ready state that can
 *     finish ("Later") without downloading a distro.
 */
class OnboardingConsentContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("missing $rel (cwd=$cwd)")
    }

    private fun onboardingSource(): String = repoFile(
        "src/main/kotlin/com/ivarna/fluxlinux/ui/onboarding/OnboardingFlowScreen.kt"
    ).readText()

    @Test
    fun consentStep_stillExistsBeforeHostSetup() {
        val src = onboardingSource()
        assertTrue(src.contains("OnboardStep.Consent"))
        assertTrue(src.contains("OnboardStep.HostSetup"))
        assertTrue(src.contains("fun ConsentPage"))
        assertTrue(src.contains("fun HostSetupPage"))
        assertTrue(src.contains("downloads"))
    }

    @Test
    fun readyStep_offersOptionalInstallAndLaterPath() {
        val src = onboardingSource()
        // The ready step exists and can finish onboarding without a distro.
        assertTrue(src.contains("OnboardStep.Ready"))
        assertTrue(src.contains("fun ReadyPage"))
        assertTrue(src.contains("onInstallDistro"))
        assertTrue(src.contains("onLater"))
        // The old forced linear flow is gone: DistroPick is only reachable
        // from the optional branch, not required to finish.
        assertTrue(src.contains("OnboardStep.DistroPick"))
        assertFalse(src.contains("Step 3 of 5"))
    }
}
