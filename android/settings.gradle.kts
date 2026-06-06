pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kolai"

include(":app")
include(":core")
include(":acquire")
include(":analyze")
include(":mix")
include(":voice")
include(":station")
include(":dsp")
