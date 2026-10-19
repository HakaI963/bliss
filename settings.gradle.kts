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
// Bcore / black-reflection / compiler: vendored BlackBox sources kept in-tree
// as reference only (the clone space is shell-based since round 13) — not built.
