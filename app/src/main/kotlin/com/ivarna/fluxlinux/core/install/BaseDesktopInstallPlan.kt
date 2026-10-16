package com.ivarna.fluxlinux.core.install

import android.content.Context
import android.util.Base64
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.data.ScriptManager

/**
 * Minimal base install: rootfs + family/base setup ([DesktopChoice] set gates
 * the desktop packages via `FLUX_DESKTOPS` / `FLUX_DESKTOP`) + customization.
 * Distro-specific paths come from [DistroInstallProfile]; KDE components are
 * resolved from [DistroRepository] data by [DesktopInstallSelection].
 */
object BaseDesktopInstallPlan {

    /** @deprecated Prefer [DistroInstallProfile.familyScript] for the active distro. */
    const val FAMILY_SCRIPT = "debian/common/setup/setup_debian_family.sh"
    /** @deprecated Prefer [DistroInstallProfile.customizationScript]. */
    const val CUSTOMIZATION_SCRIPT = "debian/common/setup/setup_customization_debian.sh"

    data class Phase(val id: String, val label: String, val weight: Int)

    fun distroById(id: String): Distro? =
        DistroRepository.supportedDistros.find { it.id == id }

    fun methodFor(distroId: String): String = DistroInstallProfile.methodFor(distroId)

    fun profileFor(distroId: String): DistroInstallProfile? =
        DistroInstallProfile.forId(distroId)

    /**
     * Phase list with stable ids (HOST/DL/ROOTFS/XFCE/CUSTOM). Labels track the
     * selected [desktops] (`FLUX_DESKTOPS` contract) so progress stays honest:
     * both selected → labels mention both, empty → terminal base.
     */
    fun phasesFor(
        method: String,
        displayName: String = "Debian",
        desktops: Set<DesktopChoice> = setOf(DesktopChoice.XFCE)
    ): List<Phase> {
        val selected = DesktopInstallSelection.canonical(desktops)
        val xfce = DesktopChoice.XFCE in selected
        val kde = DesktopChoice.KDE in selected
        val terminalOnly = selected.isEmpty()
        return if (method == "chroot") {
            listOf(
                Phase("R0", "Checking root access…", 5),
                Phase("HOST", "Preparing host (may download bootstrap from GitHub)…", 15),
                Phase("DL", "Downloading $displayName rootfs…", 10),
                Phase("ROOTFS", "Installing $displayName chroot rootfs…", 30),
                Phase(
                    "XFCE",
                    when {
                        xfce && kde -> "Installing XFCE + KDE Plasma desktops…"
                        kde -> "Installing KDE Plasma desktop…"
                        terminalOnly -> "Configuring terminal base…"
                        else -> "Installing XFCE desktop…"
                    },
                    25
                ),
                Phase("CUSTOM", customizationPhaseLabel(terminalOnly), 15),
            )
        } else {
            listOf(
                Phase("HOST", "Preparing host (may download bootstrap from GitHub)…", 20),
                Phase("DL", "Downloading $displayName rootfs…", 15),
                Phase(
                    "ROOTFS",
                    when {
                        xfce && kde -> "Installing $displayName rootfs + XFCE + KDE Plasma…"
                        kde -> "Installing $displayName rootfs + KDE Plasma…"
                        terminalOnly -> "Installing $displayName rootfs (terminal base)…"
                        else -> "Installing $displayName rootfs + XFCE…"
                    },
                    35
                ),
                Phase("CUSTOM", customizationPhaseLabel(terminalOnly), 30),
            )
        }
    }

    private fun customizationPhaseLabel(terminalOnly: Boolean): String =
        if (terminalOnly) "Finalizing install…" else "Applying Bliss customization…"

    /**
     * Family/base setup payload. [desktops] are the `FLUX_DESKTOPS` contract
     * values (space-separated, canonical) exported for guest family scripts,
     * which gate desktop packages on them; `FLUX_DESKTOP`/`FLUX_DESKTOP_ENV`
     * carry the first selection (or `none`) for legacy single-desktop gates.
     * The body stays the distro family script (base system, user, pulse — the
     * desktops themselves are separate components).
     */
    fun familySetupPayload(
        ctx: Context,
        theme: String,
        distroId: String = "debian",
        desktops: Set<DesktopChoice> = setOf(DesktopChoice.XFCE)
    ): String {
        val profile = DistroInstallProfile.forId(distroId)
            ?: DistroInstallProfile.require("debian")
        val sm = ScriptManager(ctx)
        val family = sm.getScriptContent(profile.familyScript)
        val common = runCatching {
            sm.getScriptContent("common/setup/flux_guest_common.sh")
        }.getOrDefault("")
        val desktopsEnv = DesktopInstallSelection.envValue(desktops)
        val firstEnv = DesktopInstallSelection.firstEnvValue(desktops)
        return buildString {
            append("export FLUX_THEME='").append(theme).append("'\n")
            append("export FLUX_DESKTOPS='").append(desktopsEnv).append("'\n")
            append("export FLUX_DESKTOP='").append(firstEnv).append("'\n")
            append("export FLUX_DESKTOP_ENV='").append(firstEnv).append("'\n")
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n\n")
            if (common.isNotBlank()) {
                append(common).append("\n\n")
            }
            append(family)
        }
    }

    fun familySetupB64(
        ctx: Context,
        theme: String,
        distroId: String = "debian",
        desktops: Set<DesktopChoice> = setOf(DesktopChoice.XFCE)
    ): String =
        Base64.encodeToString(
            familySetupPayload(ctx, theme, distroId, desktops).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

    /**
     * Customization payload for one desktop [choice]. KDE resolves the
     * data-driven `kde_customization` component script
     * ([DesktopInstallSelection]); XFCE keeps the profile customization script.
     * Multi-desktop installs run this once per selected desktop.
     */
    fun customizationPayload(
        ctx: Context,
        theme: String,
        distroId: String = "debian",
        choice: DesktopChoice = DesktopChoice.XFCE
    ): String {
        val profile = DistroInstallProfile.forId(distroId)
            ?: DistroInstallProfile.require("debian")
        val scriptPath = if (choice == DesktopChoice.KDE) {
            DesktopInstallSelection.componentScriptName(
                distroId, DesktopInstallSelection.COMPONENT_KDE_CUSTOMIZATION
            ) ?: profile.customizationScript
        } else {
            profile.customizationScript
        }
        val body = ScriptManager(ctx).getScriptContent(scriptPath)
        return buildString {
            append("export FLUX_THEME='").append(theme).append("'\n")
            append("export FLUX_DESKTOPS='").append(choice.envValue).append("'\n")
            append("export FLUX_DESKTOP='").append(choice.envValue).append("'\n")
            // Guest installs pokemon (60s budget). Host never ships it.
            append("export FLUX_SKIP_POKEMON='0'\n")
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n\n")
            append(body)
        }
    }

    /**
     * KDE Plasma base payload (chroot path; proot stages the component script
     * directly). Null when the distro has no `kde_plasma` component.
     */
    fun kdePlasmaPayload(ctx: Context, theme: String, distroId: String): String? {
        val scriptPath = DesktopInstallSelection.componentScriptName(
            distroId, DesktopInstallSelection.COMPONENT_KDE_PLASMA
        ) ?: return null
        val body = ScriptManager(ctx).getScriptContent(scriptPath)
        return buildString {
            append("export FLUX_THEME='").append(theme).append("'\n")
            append("export FLUX_DESKTOPS='").append(DesktopChoice.KDE.envValue).append("'\n")
            append("export FLUX_DESKTOP='").append(DesktopChoice.KDE.envValue).append("'\n")
            append("export FLUX_DESKTOP_ENV='").append(DesktopChoice.KDE.envValue).append("'\n")
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n\n")
            append(body)
        }
    }

    /**
     * Guest hw-accel payload: [flux_gpu_common.sh] + installer.
     * [fluxGpu] must already be `turnip`, `virgl`, or `ask` — never `auto`.
     */
    fun hwAccelPayload(
        ctx: Context,
        distroId: String,
        fluxGpu: String,
        vendor: String,
        extraEnv: Map<String, String> = emptyMap()
    ): String {
        val profile = DistroInstallProfile.forId(distroId)
            ?: DistroInstallProfile.require("debian")
        val sm = ScriptManager(ctx)
        val installerPath = profile.hwAccelScript ?: "common/setup/setup_hw_accel_guest.sh"
        val installer = sm.getScriptContent(installerPath)
        val common = runCatching {
            sm.getScriptContent("common/setup/flux_gpu_common.sh")
        }.getOrDefault("")
        return buildString {
            append("export FLUX_GPU='").append(fluxGpu).append("'\n")
            append("export FLUX_GPU_VENDOR='").append(vendor).append("'\n")
            extraEnv.forEach { (k, v) ->
                append("export ").append(k).append("='").append(v.replace("'", "")).append("'\n")
            }
            append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n\n")
            if (common.isNotBlank()) {
                append(common).append("\n\n")
            }
            append(installer)
        }
    }
}
