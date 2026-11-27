pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Lunaria"

include(":app")

include(":core:designsystem")
include(":core:model")
include(":core:audio-engine")
include(":core:media-scanner")
include(":core:database")

include(":feature:home")
include(":feature:library")
include(":feature:player")
include(":feature:search")
include(":feature:equalizer")
include(":feature:settings")
