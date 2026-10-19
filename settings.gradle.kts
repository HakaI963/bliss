pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Bliss"
include(":app")
include(":termux-x11")
include(":terminal-emulator")
include(":stub")
// ShadowAuto's shell engine (Apache-2.0, vendored): the virtual-display /
// capture / input engine Bliss drives over JSON-RPC. Built as an APK and
// launched through app_process from the shell.
include(":shadow-shell")
include(":shadow-stubs")
// Bcore / black-reflection / compiler: vendored BlackBox sources kept in-tree
// as reference only (the clone space is shell-based since round 13) — not built.
